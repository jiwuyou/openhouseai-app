package com.termux.app.openhouse;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.termux.R;
import com.termux.app.OpenCodeDownloadSourceSettings;
import com.termux.app.OpenCodeSettings;
import com.termux.shared.logger.Logger;
import com.termux.shared.termux.TermuxConstants;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class OpenHouseInstallController {

    private static final String LOG_TAG = "OpenHouseInstall";
    private static final String MANIFEST_FULL_SLUG = "manifest_full";
    private static final String OFFICIAL_DOCS_ASSET_DIR = "openhouse/docs-public";
    private static final String DEFAULT_BOOTSTRAP_URL = "https://raw.githubusercontent.com/jiwuyou/openhouseai-bootstrap/main/bootstrap.sh";
    private static final String REMOTE_SCHEDULE_HINT = "远程一键初始化会按全程约30分钟模拟进度";
    private static final int DEFAULT_LOCAL_MAINTENANCE_WEB_PORT = 38423;
    private static final long POLL_INTERVAL_MS = 2000L;
    private static final long STALE_RUNNING_MARKER_MS = 6 * 60 * 60 * 1000L;
    private static final int MAX_AUTO_RETRY_ATTEMPTS = 3;
    private static final long GENERAL_STUCK_NO_LOG_MS = 30L * 60L * 1000L;
    private static final long OPENCODE_STUCK_NO_LOG_MS = 45L * 60L * 1000L;
    private static final long TOTAL_INSTALL_DURATION_MS = 30L * 60L * 1000L;
    private static final long OPENCODE_STAGE_DURATION_MS = 12L * 60L * 1000L;
    private static final double OPENCODE_STAGE_WEIGHT_PERCENT = 40.0d;
    private static final double FAST_STAGE_PROGRESS_RATIO = 0.90d;
    private static final double SLOW_STAGE_PROGRESS_RATIO = 1.0d - FAST_STAGE_PROGRESS_RATIO;
    private static final Pattern DONE_PATTERN = Pattern.compile("__TERMUX_MAINT_DONE__:manifest_full:(\\d+)");
    private static final Pattern STAGE_PATTERN = Pattern.compile("__OPENHOUSE_INSTALL_STAGE__:([a-zA-Z0-9_-]+):(.+)");
    private static final Pattern SECRET_PATTERN = Pattern.compile("(?i)\\b(api[_-]?key|authorization|bearer|token|password)([=:\"' ]+)([^\\s\"']{8,})");
    private static final Pattern OPENAI_STYLE_KEY_PATTERN = Pattern.compile("\\bsk-[A-Za-z0-9_-]{12,}\\b");
    private static final Stage[] ONE_CLICK_STAGE_SEQUENCE = new Stage[] {
        Stage.PREPARE,
        Stage.TERMUX_PACKAGES,
        Stage.INSTALL_UBUNTU,
        Stage.SYNC_OFFICIAL_DOCS,
        Stage.UBUNTU_PACKAGES,
        Stage.CONFIGURE_ENTRY_UBUNTU,
        Stage.INSTALL_NODE,
        Stage.INSTALL_OPENCODE,
        Stage.INSTALL_CODEX,
        Stage.INSTALL_CLAUDE_CODE,
        Stage.INSTALL_CLAUDE_CODE_UI,
        Stage.INSTALL_REASONIX,
        Stage.RUNTIME_COMPONENTS,
        Stage.INSTALL_HERMES,
        Stage.SYNC_OPENHOUSE_REGISTRY,
        Stage.START_SMALLPHONE
    };
    private static final long OTHER_STAGE_DURATION_MS =
        (TOTAL_INSTALL_DURATION_MS - OPENCODE_STAGE_DURATION_MS) / (ONE_CLICK_STAGE_SEQUENCE.length - 1);
    private static final double OTHER_STAGE_WEIGHT_PERCENT =
        (100.0d - OPENCODE_STAGE_WEIGHT_PERCENT) / (ONE_CLICK_STAGE_SEQUENCE.length - 1);

    private static volatile OpenHouseInstallController instance;

    private final Context context;
    private final OpenHouseStatusRepository statusRepository;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Set<Listener> listeners = new CopyOnWriteArraySet<>();
    private final Object processLock = new Object();
    private final Runnable pollRunnable = new Runnable() {
        @Override
        public void run() {
            OpenHouseInstallState current = state;
            if (!current.running) {
                return;
            }

            updateState(readRunningStateFromLog());
            if (state.running && shouldAutoRetryStuckRun()) {
                executor.execute(() -> autoRetryOneClickInstall("安装长时间没有新进展，系统正在确认后自动重试。"));
                return;
            }
            if (state.running) {
                mainHandler.postDelayed(this, POLL_INTERVAL_MS);
            }
        }
    };

    private volatile OpenHouseInstallState state;
    private volatile Process currentProcess;
    private volatile Stage observedProgressStage;
    private volatile long observedProgressStageStartedAtMs;
    private volatile int autoRetryAttemptCount;
    private volatile boolean autoRetryInProgress;

    public interface Listener {
        void onInstallStateChanged(OpenHouseInstallState state);
    }

    public static OpenHouseInstallController getInstance(Context context) {
        if (instance == null) {
            synchronized (OpenHouseInstallController.class) {
                if (instance == null) {
                    instance = new OpenHouseInstallController(context);
                }
            }
        }
        return instance;
    }

    private OpenHouseInstallController(Context context) {
        this.context = context.getApplicationContext();
        this.statusRepository = new OpenHouseStatusRepository(this.context);
        this.state = readInitialStateFromLog();
        if (this.state.running) {
            mainHandler.postDelayed(pollRunnable, POLL_INTERVAL_MS);
        }
    }

    public void addListener(Listener listener) {
        if (listener == null) {
            return;
        }

        listeners.add(listener);
        mainHandler.post(() -> listener.onInstallStateChanged(state));
    }

    public void removeListener(Listener listener) {
        if (listener != null) {
            listeners.remove(listener);
        }
    }

    public OpenHouseInstallState getState() {
        if (autoRetryInProgress) {
            return state;
        }
        if (state.running && currentProcess == null) {
            if (hasFreshRunningMarker()) {
                updateState(readRunningStateFromLog());
            } else {
                updateState(new OpenHouseInstallState(
                    false,
                    false,
                    true,
                    state.percent,
                    "初始化状态异常",
                    "安装进程状态已过期，请查看详细进度后重新初始化。",
                    state.currentStageSlug
                ));
            }
            if (state.running) {
                mainHandler.removeCallbacks(pollRunnable);
                mainHandler.postDelayed(pollRunnable, POLL_INTERVAL_MS);
            }
        }
        return state;
    }

    public OpenHouseOnboardingState getOnboardingState() {
        return statusRepository.loadOnboardingState(getState(), null);
    }

    public boolean startOneClickInstall() {
        return startOneClickInstallInternal(true);
    }

    private boolean startOneClickInstallInternal(boolean resetAutoRetryCount) {
        synchronized (processLock) {
            if (resetAutoRetryCount) {
                autoRetryAttemptCount = 0;
                autoRetryInProgress = false;
            }
            if (hasFreshRunningMarker()) {
                OpenHouseInstallState runningState = readRunningStateFromLog();
                updateState(runningState);
                if (runningState.running) {
                    statusRepository.markOneClickInstallStarted();
                    return false;
                }
            }
            if (currentProcess != null || (state.running && !autoRetryInProgress)) {
                return false;
            }

            if (markCompletedIfAlreadyDeployed()) {
                return false;
            }

            updateState(new OpenHouseInstallState(
                true,
                false,
                false,
                1,
                autoRetryInProgress ? "正在自动重试安装" : "准备初始化",
                autoRetryInProgress
                    ? "系统正在第 " + autoRetryAttemptCount + "/" + MAX_AUTO_RETRY_ATTEMPTS + " 次自动重试，会从第一个未完成阶段继续。"
                    : "正在生成一键初始化任务。",
                MANIFEST_FULL_SLUG
            ));

            try {
                File bash = new File(TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH, "bash");
                if (!bash.isFile()) {
                    updateState(new OpenHouseInstallState(
                        false,
                        false,
                        true,
                        0,
                        "初始化失败",
                        "Termux 基础环境尚未安装完成，缺少 bash。",
                        MANIFEST_FULL_SLUG
                    ));
                    return true;
                }

                File logDir = ensureLogDir();
                File tempScript = new File(logDir, "run-" + MANIFEST_FULL_SLUG + ".sh");
                OpenHouseBundledRuntimeSync.Result runtimeSync = prepareBundledRuntimeAssets();
                writeScript(tempScript, buildFullInstallScript());
                resetManifestLogForNewRun();
                long startedAtMs = System.currentTimeMillis();
                writeRunningMarker(startedAtMs);
                resetProgressSimulation(startedAtMs);

                File outputFile = File.createTempFile("openhouse-install-", ".log", context.getCacheDir());
                ProcessBuilder processBuilder = new ProcessBuilder(
                    bash.getAbsolutePath(),
                    tempScript.getAbsolutePath()
                );
                processBuilder.directory(new File(TermuxConstants.TERMUX_HOME_DIR_PATH));
                processBuilder.redirectErrorStream(true);
                processBuilder.redirectOutput(ProcessBuilder.Redirect.to(outputFile));
                configureEnvironment(processBuilder.environment(), startedAtMs, runtimeSync);

                Process process = processBuilder.start();
                currentProcess = process;
                autoRetryInProgress = false;
                statusRepository.markOneClickInstallStarted();
                mainHandler.removeCallbacks(pollRunnable);
                mainHandler.postDelayed(pollRunnable, 500L);
                executor.execute(() -> waitForInstallProcess(process, outputFile));
                return true;
            } catch (Exception e) {
                Logger.logStackTraceWithMessage(LOG_TAG, "Failed to start OpenHouseAI one-click install", e);
                autoRetryInProgress = false;
                appendCompletionMarkerIfMissing(1);
                clearRunningMarker();
                updateState(new OpenHouseInstallState(
                    false,
                    false,
                    true,
                    state.percent,
                    "初始化失败",
                    "无法启动初始化任务：" + safeMessage(e),
                    MANIFEST_FULL_SLUG
                ));
                return true;
            }
        }
    }

    public boolean forceRestartOneClickInstall() {
        synchronized (processLock) {
            stopTrackedInstallProcess();
            terminateExistingInstallProcesses();
            clearRunningMarker();
            updateState(OpenHouseInstallState.idle());
            return startOneClickInstallInternal(true);
        }
    }

    private boolean markCompletedIfAlreadyDeployed() {
        if (state.completed) {
            statusRepository.markOneClickInstallCompleted();
            updateState(state);
            return true;
        }

        if (statusRepository.loadStatus().isDeploymentComplete()) {
            autoRetryAttemptCount = 0;
            autoRetryInProgress = false;
            OpenHouseInstallState completedState = new OpenHouseInstallState(
                false,
                true,
                false,
                100,
                "初始化安装完成",
                "已检测到 Linux 环境和 AI 工具安装完成，无需再次执行初始化。",
                MANIFEST_FULL_SLUG
            );
            clearRunningMarker();
            updateState(completedState);
            statusRepository.markOneClickInstallCompleted();
            return true;
        }

        return false;
    }

    public boolean stopOneClickInstall() {
        synchronized (processLock) {
            if (!state.running || currentProcess == null) {
                return false;
            }

            currentProcess.destroy();
            updateState(new OpenHouseInstallState(
                false,
                false,
                true,
                state.percent,
                "初始化已停止",
                "一键初始化已停止，可稍后重新执行。",
                state.currentStageSlug
            ));
            return true;
        }
    }

    private void stopTrackedInstallProcess() {
        Process process = currentProcess;
        currentProcess = null;
        if (process == null) {
            return;
        }

        try {
            process.destroy();
            if (!process.waitFor(1800L, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                process.waitFor(2200L, TimeUnit.MILLISECONDS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        } catch (Exception e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to stop tracked one-click install process", e);
            process.destroyForcibly();
        }
    }

    private void terminateExistingInstallProcesses() {
        try {
            File bash = new File(TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH, "bash");
            if (!bash.isFile()) {
                return;
            }

            ProcessBuilder processBuilder = new ProcessBuilder(
                bash.getAbsolutePath(),
                "-lc",
                buildTerminateExistingInstallProcessesCommand()
            );
            processBuilder.directory(new File(TermuxConstants.TERMUX_HOME_DIR_PATH));
            processBuilder.redirectErrorStream(true);
            Map<String, String> environment = processBuilder.environment();
            environment.put("HOME", TermuxConstants.TERMUX_HOME_DIR_PATH);
            environment.put("PREFIX", TermuxConstants.TERMUX_PREFIX_DIR_PATH);
            environment.put("PATH", TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + ":/system/bin");
            environment.put("LD_LIBRARY_PATH", TermuxConstants.TERMUX_LIB_PREFIX_DIR_PATH);
            environment.put("TMPDIR", TermuxConstants.TERMUX_TMP_PREFIX_DIR_PATH);
            environment.put("OPENHOUSEAI_NO_AUTO_UBUNTU", "1");
            environment.put("TERMUX_NO_AUTO_UBUNTU", "1");
            removeProxyEnvironment(environment);

            Process process = processBuilder.start();
            if (!process.waitFor(5L, TimeUnit.SECONDS)) {
                process.destroyForcibly();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to terminate existing one-click install processes", e);
        }
    }

    private String buildTerminateExistingInstallProcessesCommand() {
        return ""
            + "set +e\n"
            + "self=\"$$\"\n"
            + "collect_roots(){ ps -ef 2>/dev/null | awk -v self=\"$self\" '\n"
            + "  /run-manifest_full[.]sh|openhouseai-bootstrap[.]sh/ { if ($2 != self) print $2 }\n"
            + "'; }\n"
            + "collect_tree(){ ps -ef 2>/dev/null | awk -v roots=\"$1\" '\n"
            + "  BEGIN { split(roots, r); for (i in r) if (r[i] != \"\") wanted[r[i]]=1; changed=1 }\n"
            + "  { pid[NR]=$2; ppid[NR]=$3 }\n"
            + "  END { while (changed) { changed=0; for (i=1; i<=NR; i++) if (wanted[ppid[i]] && !wanted[pid[i]]) { wanted[pid[i]]=1; changed=1 } } for (p in wanted) print p }\n"
            + "'; }\n"
            + "roots=\"$(collect_roots | sort -u)\"\n"
            + "pids=\"$(collect_tree \"$roots\" | sort -rn)\"\n"
            + "if [ -n \"$pids\" ]; then for pid in $pids; do kill -TERM \"$pid\" 2>/dev/null; done; fi\n"
            + "sleep 1\n"
            + "roots=\"$(collect_roots | sort -u)\"\n"
            + "pids=\"$(collect_tree \"$roots\" | sort -rn)\"\n"
            + "if [ -n \"$pids\" ]; then for pid in $pids; do kill -KILL \"$pid\" 2>/dev/null; done; fi\n"
            + "exit 0\n";
    }

    public String getLogTail(int maxChars) {
        int charLimit = Math.max(0, Math.min(maxChars, 120000));
        if (charLimit == 0) {
            return "";
        }

        return redactSecrets(readLogTail(charLimit));
    }

    private void waitForInstallProcess(Process process, File outputFile) {
        int exitCode = 1;
        try {
            exitCode = process.waitFor();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroy();
            exitCode = 130;
        } finally {
            if (outputFile != null) {
                outputFile.delete();
            }
        }

        synchronized (processLock) {
            if (currentProcess != process) {
                return;
            }
            currentProcess = null;
            clearRunningMarker();
        }

        if (exitCode != 0 && autoRetryOneClickInstall("安装遇到网络或软件包问题，系统正在自动重试。")) {
            return;
        }

        appendCompletionMarkerIfMissing(exitCode);
        OpenHouseInstallState finishedState = readFinishedStateFromLog(exitCode);
        updateState(finishedState);
        if (finishedState.completed) {
            autoRetryAttemptCount = 0;
            autoRetryInProgress = false;
            statusRepository.markOneClickInstallCompleted();
        }
    }

    private boolean autoRetryOneClickInstall(String reason) {
        synchronized (processLock) {
            if (autoRetryInProgress || autoRetryAttemptCount >= MAX_AUTO_RETRY_ATTEMPTS) {
                return false;
            }

            autoRetryAttemptCount++;
            autoRetryInProgress = true;
            updateState(new OpenHouseInstallState(
                true,
                false,
                false,
                Math.max(1, state.percent),
                "系统正在确认安装状态",
                reason + "正在进行第 " + autoRetryAttemptCount + "/" + MAX_AUTO_RETRY_ATTEMPTS + " 次自动重试。",
                state.currentStageSlug
            ));
            stopTrackedInstallProcess();
            terminateExistingInstallProcesses();
            clearRunningMarker();
            boolean started = startOneClickInstallInternal(false);
            if (!started) {
                autoRetryInProgress = false;
                updateState(new OpenHouseInstallState(
                    false,
                    false,
                    true,
                    Math.max(1, state.percent),
                    "自动重试未能启动",
                    "系统已尝试自动恢复安装，但未能重新启动任务。请查看详细进度，或确认后使用“强制重启并继续”。",
                    state.currentStageSlug
                ));
            }
            return started;
        }
    }

    private OpenHouseInstallState readInitialStateFromLog() {
        String logTail = readLogTail(24000);
        if (hasFreshRunningMarker()) {
            return readRunningStateFromLog();
        }
        Integer exitCode = readLastExitCode(logTail);
        if (exitCode == null) {
            return OpenHouseInstallState.idle();
        }
        return buildFinishedState(logTail, exitCode);
    }

    private OpenHouseInstallState readRunningStateFromLog() {
        String logTail = readLogTail(24000);
        Integer exitCode = readLastExitCode(logTail);
        if (exitCode != null) {
            clearRunningMarker();
            return buildFinishedState(logTail, exitCode);
        }

        RunningMarker runningMarker = readRunningMarker();
        StageMarkerInfo stageMarkerInfo = inferCurrentStageMarker(logTail);
        Stage stage = stageMarkerInfo.found
            ? stageMarkerInfo.stage
            : runningMarker.stage == null ? Stage.PREPARE : runningMarker.stage;
        if (shouldUseScheduledRemoteStage(stageMarkerInfo, runningMarker)) {
            stage = inferScheduledStage(runningMarker, System.currentTimeMillis());
        }
        stage = resolveMonotonicStage(stage, stageMarkerInfo, runningMarker);
        String detail = latestReadableLogLine(logTail);
        if (detail.isEmpty()) {
            detail = stage.detail;
        }
        int percent = Math.max(simulateRunningPercent(stage, runningMarker), currentRunningPercentFloor());
        return new OpenHouseInstallState(
            true,
            false,
            false,
            percent,
            stage.phaseLabel,
            detail,
            stage.slug
        );
    }

    private OpenHouseInstallState readFinishedStateFromLog(int processExitCode) {
        String logTail = readLogTail(24000);
        Integer markerExitCode = readLastExitCode(logTail);
        return buildFinishedState(logTail, markerExitCode == null ? processExitCode : markerExitCode);
    }

    private OpenHouseInstallState buildFinishedState(String logTail, int exitCode) {
        if (exitCode == 0) {
            return new OpenHouseInstallState(
                false,
                true,
                false,
                100,
                "初始化安装完成",
                "Linux 环境和 AI 工具已安装完成，OpenCode 可在主页手动启动。",
                MANIFEST_FULL_SLUG
            );
        }

        StageMarkerInfo stageMarkerInfo = inferCurrentStageMarker(logTail);
        Stage stage = resolveMonotonicStage(stageMarkerInfo.stage, stageMarkerInfo, null);
        String detail = latestReadableLogLine(logTail);
        if (detail.isEmpty()) {
            detail = "请点击“查看详细进度”查看完整日志。";
        }
        if (autoRetryAttemptCount >= MAX_AUTO_RETRY_ATTEMPTS) {
            detail = "系统已自动重试 " + MAX_AUTO_RETRY_ATTEMPTS + " 次，仍未完成安装。"
                + detail
                + " 如确认长时间没有变化，可使用“强制重启并继续”。";
        }
        return new OpenHouseInstallState(
            false,
            false,
            true,
            failurePercent(stage),
            "初始化失败",
            detail,
            stage.slug
        );
    }

    private Integer readLastExitCode(String logContent) {
        Matcher matcher = DONE_PATTERN.matcher(logContent == null ? "" : logContent);
        Integer exitCode = null;
        while (matcher.find()) {
            exitCode = Integer.parseInt(matcher.group(1));
        }
        return exitCode;
    }

    private StageMarkerInfo inferCurrentStageMarker(String logContent) {
        Matcher matcher = STAGE_PATTERN.matcher(logContent == null ? "" : logContent);
        Stage stage = Stage.PREPARE;
        boolean found = false;
        int markerCount = 0;
        while (matcher.find()) {
            Stage matched = Stage.fromSlug(matcher.group(1));
            if (matched != null) {
                stage = matched;
                found = true;
                markerCount++;
            }
        }
        return new StageMarkerInfo(stage, found, markerCount);
    }

    private boolean shouldUseScheduledRemoteStage(StageMarkerInfo stageMarkerInfo, RunningMarker runningMarker) {
        if (runningMarker == null || runningMarker.startedAtMs <= 0L) {
            return false;
        }
        return runningMarker.remoteSchedule && (stageMarkerInfo == null || stageMarkerInfo.markerCount <= 1);
    }

    private Stage inferScheduledStage(RunningMarker runningMarker, long nowMs) {
        long elapsedMs = Math.max(0L, nowMs - runningMarker.startedAtMs);
        Stage scheduledStage = Stage.PREPARE;
        long boundaryMs = 0L;
        for (Stage candidate : ONE_CLICK_STAGE_SEQUENCE) {
            scheduledStage = candidate;
            boundaryMs += getStageDurationMs(candidate);
            if (elapsedMs < boundaryMs) {
                break;
            }
        }
        return scheduledStage;
    }

    private int simulateRunningPercent(Stage stage, RunningMarker runningMarker) {
        int stageStartPercent = getStageStartPercent(stage);
        int stageEndPercent = getStageEndPercent(stage);
        if (stageEndPercent <= stageStartPercent) {
            return stageStartPercent;
        }

        long nowMs = System.currentTimeMillis();
        long stageStartedAtMs = resolveStageStartedAtMs(stage, runningMarker, nowMs);
        long elapsedMs = Math.max(0L, nowMs - stageStartedAtMs);
        long stageDurationMs = Math.max(1L, getStageDurationMs(stage));
        double stageProgress = calculateInProgressStageRatio(elapsedMs, stageDurationMs);
        double rawPercent = stageStartPercent + (stageEndPercent - stageStartPercent) * stageProgress;
        int percent = (int) Math.floor(rawPercent);
        int maxInProgressPercent = Math.max(stageStartPercent, stageEndPercent - 1);
        return Math.max(stageStartPercent, Math.min(maxInProgressPercent, percent));
    }

    private double calculateInProgressStageRatio(long elapsedMs, long stageDurationMs) {
        long fastDurationMs = Math.max(1L, Math.round(stageDurationMs * FAST_STAGE_PROGRESS_RATIO));
        if (elapsedMs <= fastDurationMs) {
            return FAST_STAGE_PROGRESS_RATIO * ((double) elapsedMs / fastDurationMs);
        }

        long slowDurationMs = Math.max(1L, stageDurationMs - fastDurationMs);
        double slowElapsedRatio = (double) (elapsedMs - fastDurationMs) / slowDurationMs;
        double easedSlowRatio = 1.0d - Math.exp(-3.0d * Math.max(0.0d, slowElapsedRatio));
        return Math.min(1.0d, FAST_STAGE_PROGRESS_RATIO + SLOW_STAGE_PROGRESS_RATIO * easedSlowRatio);
    }

    private long resolveStageStartedAtMs(Stage stage, RunningMarker runningMarker, long nowMs) {
        if (runningMarker.stage == stage
            && isUsableTimestamp(runningMarker.stageStartedAtMs, runningMarker.startedAtMs, nowMs)) {
            observedProgressStage = stage;
            observedProgressStageStartedAtMs = Math.min(runningMarker.stageStartedAtMs, nowMs);
            return observedProgressStageStartedAtMs;
        }

        if (observedProgressStage == stage
            && isUsableTimestamp(observedProgressStageStartedAtMs, runningMarker.startedAtMs, nowMs)) {
            return observedProgressStageStartedAtMs;
        }

        long estimatedStartedAtMs = estimateStageStartedAtMs(stage, runningMarker, nowMs);
        observedProgressStage = stage;
        observedProgressStageStartedAtMs = estimatedStartedAtMs;
        return estimatedStartedAtMs;
    }

    private boolean isUsableTimestamp(long timestampMs, long runStartedAtMs, long nowMs) {
        if (timestampMs <= 0L) {
            return false;
        }

        long earliestMs = runStartedAtMs > 0L ? runStartedAtMs - POLL_INTERVAL_MS : 0L;
        return timestampMs >= earliestMs && timestampMs <= nowMs + POLL_INTERVAL_MS;
    }

    private long estimateStageStartedAtMs(Stage stage, RunningMarker runningMarker, long nowMs) {
        if (runningMarker.startedAtMs <= 0L) {
            return nowMs;
        }

        long scheduledStartedAtMs = runningMarker.startedAtMs + getCumulativeDurationBeforeStageMs(stage);
        return Math.min(scheduledStartedAtMs, nowMs);
    }

    private long getCumulativeDurationBeforeStageMs(Stage stage) {
        long durationMs = 0L;
        for (Stage candidate : ONE_CLICK_STAGE_SEQUENCE) {
            if (candidate == stage) {
                return durationMs;
            }
            durationMs += getStageDurationMs(candidate);
        }
        return durationMs;
    }

    private long getStageDurationMs(Stage stage) {
        return stage == Stage.INSTALL_OPENCODE ? OPENCODE_STAGE_DURATION_MS : OTHER_STAGE_DURATION_MS;
    }

    private int getStageStartPercent(Stage stage) {
        return clampPercent((int) Math.round(getCumulativeWeightBeforeStage(stage)));
    }

    private int getStageEndPercent(Stage stage) {
        return clampPercent((int) Math.round(getCumulativeWeightBeforeStage(stage) + getStageWeightPercent(stage)));
    }

    private double getCumulativeWeightBeforeStage(Stage stage) {
        double percent = 0.0d;
        for (Stage candidate : ONE_CLICK_STAGE_SEQUENCE) {
            if (candidate == stage) {
                return percent;
            }
            percent += getStageWeightPercent(candidate);
        }
        return percent;
    }

    private double getStageWeightPercent(Stage stage) {
        return stage == Stage.INSTALL_OPENCODE ? OPENCODE_STAGE_WEIGHT_PERCENT : OTHER_STAGE_WEIGHT_PERCENT;
    }

    private Stage resolveMonotonicStage(Stage stage, StageMarkerInfo stageMarkerInfo, RunningMarker runningMarker) {
        if (runningMarker != null && !runningMarker.remoteSchedule && stageMarkerInfo != null && stageMarkerInfo.found) {
            return stage;
        }
        if (stageMarkerInfo != null && stageMarkerInfo.markerCount > 1) {
            return stage;
        }
        OpenHouseInstallState current = state;
        Stage currentStage = current == null || !current.running ? null : Stage.fromSlug(current.currentStageSlug);
        if (currentStage != null && getStageIndex(stage) < getStageIndex(currentStage)) {
            return currentStage;
        }
        return stage;
    }

    private int getStageIndex(Stage stage) {
        for (int i = 0; i < ONE_CLICK_STAGE_SEQUENCE.length; i++) {
            if (ONE_CLICK_STAGE_SEQUENCE[i] == stage) {
                return i;
            }
        }
        return 0;
    }

    private int failurePercent(Stage stage) {
        int percent = getStageStartPercent(stage);
        OpenHouseInstallState current = state;
        if (current != null && current.running) {
            percent = Math.max(percent, current.percent);
        }
        return Math.min(99, clampPercent(percent));
    }

    private int currentRunningPercentFloor() {
        OpenHouseInstallState current = state;
        if (current == null || !current.running) {
            return 0;
        }
        return Math.min(99, clampPercent(current.percent));
    }

    private boolean shouldAutoRetryStuckRun() {
        if (autoRetryInProgress || autoRetryAttemptCount >= MAX_AUTO_RETRY_ATTEMPTS) {
            return false;
        }

        RunningMarker runningMarker = readRunningMarker();
        if (!runningMarker.exists) {
            return false;
        }

        File logFile = getManifestLogFile();
        if (!logFile.isFile()) {
            return false;
        }

        long nowMs = System.currentTimeMillis();
        Stage stage = runningMarker.stage == null ? Stage.PREPARE : runningMarker.stage;
        long thresholdMs = stage == Stage.INSTALL_OPENCODE ? OPENCODE_STUCK_NO_LOG_MS : GENERAL_STUCK_NO_LOG_MS;
        long noLogMs = Math.max(0L, nowMs - logFile.lastModified());
        long stageStartedAtMs = runningMarker.stageStartedAtMs > 0L
            ? runningMarker.stageStartedAtMs
            : runningMarker.startedAtMs;
        long stageElapsedMs = stageStartedAtMs > 0L ? Math.max(0L, nowMs - stageStartedAtMs) : 0L;
        return noLogMs >= thresholdMs && stageElapsedMs >= thresholdMs;
    }

    private int clampPercent(int percent) {
        return Math.max(0, Math.min(100, percent));
    }

    private void resetProgressSimulation(long startedAtMs) {
        observedProgressStage = Stage.PREPARE;
        observedProgressStageStartedAtMs = startedAtMs;
    }

    private String latestReadableLogLine(String logContent) {
        if (logContent == null || logContent.isEmpty()) {
            return "";
        }

        String[] lines = logContent.split("\\r?\\n");
        for (int i = lines.length - 1; i >= 0; i--) {
            String line = lines[i].trim();
            if (line.isEmpty()
                || line.startsWith("__TERMUX_MAINT_DONE__:")
                || line.startsWith("__OPENHOUSE_INSTALL_STAGE__:")) {
                continue;
            }

            if (line.length() > 180) {
                line = line.substring(0, 180) + "...";
            }
            return redactSecrets(line);
        }
        return "";
    }

    private void updateState(OpenHouseInstallState nextState) {
        state = nextState == null ? OpenHouseInstallState.idle() : nextState;
        mainHandler.post(() -> {
            for (Listener listener : listeners) {
                listener.onInstallStateChanged(state);
            }
        });
    }

    private File ensureLogDir() {
        File logDir = new File(TermuxConstants.TERMUX_HOME_DIR_PATH, ".maintainer-logs");
        if (!logDir.exists()) {
            logDir.mkdirs();
        }
        return logDir;
    }

    private File getManifestLogFile() {
        return new File(TermuxConstants.TERMUX_HOME_DIR_PATH, ".maintainer-logs/" + MANIFEST_FULL_SLUG + ".log");
    }

    private File getRunningMarkerFile() {
        return new File(TermuxConstants.TERMUX_HOME_DIR_PATH, ".maintainer-logs/" + MANIFEST_FULL_SLUG + ".running");
    }

    private RunningMarker readRunningMarker() {
        File marker = getRunningMarkerFile();
        if (!marker.isFile()) {
            return RunningMarker.missing();
        }

        long lastModifiedAtMs = marker.lastModified();
        long startedAtMs = 0L;
        long stageStartedAtMs = 0L;
        boolean remoteSchedule = false;
        Stage stage = null;
        String content = readSmallFile(marker, 4096);
        String[] lines = content.split("\\r?\\n");
        for (String rawLine : lines) {
            String line = rawLine.trim();
            if (line.isEmpty()) {
                continue;
            }

            if (line.indexOf('=') < 0) {
                long parsedStartedAtMs = parsePositiveLong(line);
                if (parsedStartedAtMs > 0L) {
                    startedAtMs = parsedStartedAtMs;
                }
                continue;
            }

            int separatorIndex = line.indexOf('=');
            String key = line.substring(0, separatorIndex).trim();
            String value = line.substring(separatorIndex + 1).trim();
            if ("started_at_ms".equals(key)) {
                startedAtMs = parsePositiveLong(value);
            } else if ("stage_started_at_ms".equals(key)) {
                stageStartedAtMs = parsePositiveLong(value);
            } else if ("stage_slug".equals(key)) {
                stage = Stage.fromSlug(value);
            } else if ("remote_schedule".equals(key)) {
                remoteSchedule = "1".equals(value) || "true".equalsIgnoreCase(value);
            }
        }

        if (startedAtMs <= 0L) {
            startedAtMs = lastModifiedAtMs;
        }
        return new RunningMarker(true, startedAtMs, stage, stageStartedAtMs, lastModifiedAtMs, remoteSchedule);
    }

    private String readSmallFile(File file, int byteLimit) {
        int limit = Math.max(0, byteLimit);
        if (limit == 0) {
            return "";
        }

        try (RandomAccessFile randomAccessFile = new RandomAccessFile(file, "r")) {
            long length = Math.min(randomAccessFile.length(), limit);
            byte[] buffer = new byte[(int) length];
            randomAccessFile.readFully(buffer);
            return new String(buffer, StandardCharsets.UTF_8);
        } catch (IOException e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to read one-click install running marker", e);
            return "";
        }
    }

    private long parsePositiveLong(String value) {
        try {
            long parsed = Long.parseLong(value);
            return parsed > 0L ? parsed : 0L;
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    private void writeRunningMarker(long startedAtMs) throws IOException {
        File marker = getRunningMarkerFile();
        try (FileOutputStream outputStream = new FileOutputStream(marker, false)) {
            outputStream.write(("started_at_ms=" + startedAtMs + "\n").getBytes(StandardCharsets.UTF_8));
            outputStream.write("stage_slug=prepare\n".getBytes(StandardCharsets.UTF_8));
            outputStream.write(("stage_started_at_ms=" + startedAtMs + "\n").getBytes(StandardCharsets.UTF_8));
            outputStream.write("remote_schedule=1\n".getBytes(StandardCharsets.UTF_8));
        }
    }

    private void clearRunningMarker() {
        File marker = getRunningMarkerFile();
        if (marker.isFile() && !marker.delete()) {
            Logger.logWarn(LOG_TAG, "Failed to delete one-click install running marker");
        }
    }

    private boolean hasFreshRunningMarker() {
        RunningMarker marker = readRunningMarker();
        if (!marker.exists) {
            return false;
        }

        long ageMs = Math.max(0L, System.currentTimeMillis() - marker.lastModifiedAtMs);
        if (ageMs <= STALE_RUNNING_MARKER_MS) {
            return true;
        }

        clearRunningMarker();
        return false;
    }

    private void writeScript(File file, String content) throws IOException {
        try (FileOutputStream outputStream = new FileOutputStream(file, false)) {
            outputStream.write(content.getBytes(StandardCharsets.UTF_8));
        }
        file.setExecutable(true, true);
    }

    private void resetManifestLogForNewRun() throws IOException {
        File logFile = getManifestLogFile();
        File parent = logFile.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }
        try (FileOutputStream outputStream = new FileOutputStream(logFile, false)) {
            outputStream.write("开始执行 SmallPhoneAI 一键初始化。\n".getBytes(StandardCharsets.UTF_8));
            if (autoRetryAttemptCount > 0) {
                String retryLine = "系统正在第 " + autoRetryAttemptCount + "/" + MAX_AUTO_RETRY_ATTEMPTS
                    + " 次自动重试，会从第一个未完成阶段继续。\n";
                outputStream.write(retryLine.getBytes(StandardCharsets.UTF_8));
            }
        }
    }

    private void configureEnvironment(Map<String, String> environment,
                                      long startedAtMs,
                                      OpenHouseBundledRuntimeSync.Result runtimeSync) {
        environment.put("HOME", TermuxConstants.TERMUX_HOME_DIR_PATH);
        environment.put("PREFIX", TermuxConstants.TERMUX_PREFIX_DIR_PATH);
        environment.put("PATH", TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + ":/system/bin");
        environment.put("LD_LIBRARY_PATH", TermuxConstants.TERMUX_LIB_PREFIX_DIR_PATH);
        environment.put("TMPDIR", TermuxConstants.TERMUX_TMP_PREFIX_DIR_PATH);
        environment.put("LANG", "C.UTF-8");
        environment.put("OPENHOUSEAI_NO_AUTO_UBUNTU", "1");
        environment.put("TERMUX_NO_AUTO_UBUNTU", "1");
        environment.put("OPENHOUSE_RUN_STARTED_AT_MS", Long.toString(startedAtMs));
        if (runtimeSync != null) {
            environment.put("SMALLPHONEAI_BOOTSTRAP", runtimeSync.bootstrapFile.getAbsolutePath());
            environment.put("SMALLPHONEAI_OFFLINE_PAYLOAD_DIR", runtimeSync.payloadDir.getAbsolutePath());
            File manifest = new File(runtimeSync.payloadDir, "manifest.json");
            if (manifest.isFile()) {
                environment.put("SMALLPHONEAI_OFFLINE_PAYLOAD_MANIFEST", manifest.getAbsolutePath());
            }
        }
        removeProxyEnvironment(environment);
    }

    private void removeProxyEnvironment(Map<String, String> environment) {
        environment.remove("http_proxy");
        environment.remove("https_proxy");
        environment.remove("ftp_proxy");
        environment.remove("all_proxy");
        environment.remove("no_proxy");
        environment.remove("HTTP_PROXY");
        environment.remove("HTTPS_PROXY");
        environment.remove("FTP_PROXY");
        environment.remove("ALL_PROXY");
        environment.remove("NO_PROXY");
    }

    private String buildFullInstallScript() throws IOException {
        StringBuilder bundledBody = new StringBuilder();
        bundledBody.append("log '开始执行 APK 内置 SmallPhoneAI 一键安装流程。'\n");
        for (Stage stage : ONE_CLICK_STAGE_SEQUENCE) {
            bundledBody.append("log ").append(shellQuote("__OPENHOUSE_INSTALL_STAGE__:" + stage.slug + ":" + stage.phaseLabel)).append('\n');
            bundledBody.append("log ").append(shellQuote("当前步骤：" + stage.phaseLabel)).append('\n');
            bundledBody.append("run_environment_probe\n");
            bundledBody.append("(\n");
            bundledBody.append(buildAssetScriptBody(stage));
            if (bundledBody.length() > 0 && bundledBody.charAt(bundledBody.length() - 1) != '\n') {
                bundledBody.append('\n');
            }
            bundledBody.append(")\n");
            bundledBody.append("log ").append(shellQuote("步骤完成：" + stage.phaseLabel)).append('\n');
        }
        bundledBody.append("log 'APK 内置 SmallPhoneAI 一键初始化已完成。'\n");

        OpenCodeInstallSpec installSpec = resolveOpenCodeInstallSpec();
        StringBuilder scriptBody = new StringBuilder();
        scriptBody.append("log '开始执行 SmallPhoneAI 一键初始化。'\n");
        scriptBody.append("log '安装过程中会准备 Linux 环境，安装 service-manager、openhouse-connect、SmallPhone 和 AI CLI。'\n");
        scriptBody.append("run_remote_bootstrap(){\n");
        scriptBody.append("  log '正在探测远程一键维护脚本。'\n");
        scriptBody.append("  if ! command -v curl >/dev/null 2>&1; then log '缺少 curl，切换到 APK 内置阶段脚本。'; return 127; fi\n");
        scriptBody.append("  if ! run_logged curl -fL --connect-timeout 10 --max-time 35 --retry 1 --retry-delay 2 ")
            .append(shellQuote(DEFAULT_BOOTSTRAP_URL))
            .append(" -o \"$HOME/openhouseai-bootstrap.sh\"; then return 21; fi\n");
        scriptBody.append("  chmod +x \"$HOME/openhouseai-bootstrap.sh\"\n");
        scriptBody.append("  log '").append(REMOTE_SCHEDULE_HINT).append("；OpenCode 阶段会在预计进度到达后进入 40%-80% 区间。'\n");
        scriptBody.append("  run_logged env OPENHOUSEAI_PORT=")
            .append(shellQuote(Integer.toString(OpenCodeSettings.DEFAULT_OPENCODE_PORT)))
            .append(" OPENHOUSEAI_WEB_PORT=")
            .append(shellQuote(Integer.toString(DEFAULT_LOCAL_MAINTENANCE_WEB_PORT)))
            .append(" OPENCODE_INSTALL_URL=")
            .append(shellQuote(installSpec.primaryUrl))
            .append(" bash \"$HOME/openhouseai-bootstrap.sh\" full\n");
        scriptBody.append("}\n");
        scriptBody.append("use_remote_bootstrap=0\n");
        scriptBody.append("case \"${SMALLPHONEAI_USE_REMOTE_BOOTSTRAP:-${OPENHOUSEAI_USE_REMOTE_BOOTSTRAP:-0}}\" in 1|true|TRUE|True|yes|YES|Yes|on|ON|On) use_remote_bootstrap=1 ;; esac\n");
        scriptBody.append("if [ \"$use_remote_bootstrap\" = \"1\" ] && run_remote_bootstrap; then\n");
        scriptBody.append("  log '远程一键初始化已完成。OpenCode 需要点击启动按钮后再启动。'\n");
        scriptBody.append("else\n");
        scriptBody.append("  remote_status=\"$?\"\n");
        scriptBody.append("  if [ \"$use_remote_bootstrap\" = \"1\" ]; then\n");
        scriptBody.append("    log \"远程一键维护不可用或执行失败（退出码：$remote_status），切换到 APK 内置阶段脚本。\"\n");
        scriptBody.append("  else\n");
        scriptBody.append("    log 'SmallPhoneAI 默认使用 APK 内置阶段脚本；如需旧远程脚本，请显式设置 SMALLPHONEAI_USE_REMOTE_BOOTSTRAP=1。'\n");
        scriptBody.append("  fi\n");
        scriptBody.append("  REMOTE_SCHEDULE_ACTIVE=0\n");
        scriptBody.append(bundledBody);
        if (!bundledBody.toString().endsWith("\n")) {
            scriptBody.append('\n');
        }
        scriptBody.append("fi\n");
        scriptBody.append("log 'SmallPhoneAI 一键初始化已完成。'\n");
        return buildWrapperScript("一键初始化", MANIFEST_FULL_SLUG, scriptBody.toString());
    }

    private String buildAssetScriptBody(Stage stage) throws IOException {
        OpenCodeInstallSpec installSpec = stage == Stage.INSTALL_OPENCODE
            ? resolveOpenCodeInstallSpec()
            : OpenCodeInstallSpec.defaultSpec(context);
        return loadAsset("maintainer/" + stage.assetName)
            .replace("__PORT__", Integer.toString(OpenCodeSettings.DEFAULT_OPENCODE_PORT))
            .replace("__CLAUDE_CODE_UI_PORT__", "23083")
            .replace("__BOOTSTRAP_URL__", DEFAULT_BOOTSTRAP_URL)
            .replace("__REQUIRED_COMPONENT_TARGETS__", "")
            .replace("__LOCAL_MAINTENANCE_WEB_PORT__", Integer.toString(DEFAULT_LOCAL_MAINTENANCE_WEB_PORT))
            .replace("__DEEPSEEK_KEY_FILE__", new File(TermuxConstants.TERMUX_HOME_DIR_PATH, ".maintainer-logs/deepseek-api-key.tmp").getAbsolutePath())
            .replace("__BUNDLED_OFFICIAL_DOCS__", buildBundledAssetWriteSnippet(OFFICIAL_DOCS_ASSET_DIR, "OFFICIAL_DOC_DIR"))
            .replace("__OPENCODE_INSTALL_PRIMARY_URL__", installSpec.primaryUrl)
            .replace("__OPENCODE_INSTALL_PRIMARY_LABEL__", installSpec.primaryLabel)
            .replace("__OPENCODE_INSTALL_SECONDARY_URL__", installSpec.secondaryUrl)
            .replace("__OPENCODE_INSTALL_SECONDARY_LABEL__", installSpec.secondaryLabel)
            .replace("__OPENCODE_INSTALL_ALLOW_FALLBACK__", installSpec.allowFallback ? "1" : "0");
    }

    private OpenHouseBundledRuntimeSync.Result prepareBundledRuntimeAssets() throws IOException {
        try {
            return OpenHouseBundledRuntimeSync.sync(context);
        } catch (IOException e) {
            throw new IOException("APK 内置 bootstrap/scripts/payload 同步失败：" + e.getMessage(), e);
        }
    }

    private String buildWrapperScript(String stageLabel, String stageSlug, String scriptBody) {
        StringBuilder builder = new StringBuilder();
        builder.append("#!/data/data/com.termux/files/usr/bin/bash\n");
        builder.append("set -euo pipefail\n");
        builder.append("export HOME=\"${HOME:-/data/data/com.termux/files/home}\"\n");
        builder.append("export PREFIX=\"${PREFIX:-/data/data/com.termux/files/usr}\"\n");
        builder.append("export PATH=\"$PREFIX/bin:/system/bin:${PATH:-}\"\n");
        builder.append("export LD_LIBRARY_PATH=\"$PREFIX/lib:${LD_LIBRARY_PATH:-}\"\n");
        builder.append("export TMPDIR=\"${TMPDIR:-$PREFIX/tmp}\"\n");
        builder.append("export TERM=\"xterm-256color\"\n");
        builder.append("export OPENHOUSEAI_NO_AUTO_UBUNTU=1\n");
        builder.append("export TERMUX_NO_AUTO_UBUNTU=1\n");
        builder.append("export SMALLPHONEAI_BOOTSTRAP=\"${SMALLPHONEAI_BOOTSTRAP:-$HOME/.smallphoneai-bootstrap/bootstrap.sh}\"\n");
        builder.append("export SMALLPHONEAI_OFFLINE_PAYLOAD_DIR=\"${SMALLPHONEAI_OFFLINE_PAYLOAD_DIR:-$HOME/.smallphoneai-bootstrap/apk-assets/openhouse/product-payloads}\"\n");
        builder.append("if [ -f \"$SMALLPHONEAI_OFFLINE_PAYLOAD_DIR/manifest.json\" ]; then export SMALLPHONEAI_OFFLINE_PAYLOAD_MANIFEST=\"${SMALLPHONEAI_OFFLINE_PAYLOAD_MANIFEST:-$SMALLPHONEAI_OFFLINE_PAYLOAD_DIR/manifest.json}\"; fi\n");
        builder.append("unset http_proxy https_proxy ftp_proxy all_proxy no_proxy HTTP_PROXY HTTPS_PROXY FTP_PROXY ALL_PROXY NO_PROXY\n");
        builder.append("STAGE_NAME=").append(shellQuote(stageLabel)).append('\n');
        builder.append("STAGE_SLUG=").append(shellQuote(stageSlug)).append('\n');
        builder.append("LOG_DIR=\"$HOME/.maintainer-logs\"\n");
        builder.append("LOG_FILE=\"$LOG_DIR/$STAGE_SLUG.log\"\n");
        builder.append("RUNNING_FILE=\"$LOG_DIR/$STAGE_SLUG.running\"\n");
        builder.append("mkdir -p \"$LOG_DIR\"\n");
        builder.append(": > \"$LOG_FILE\"\n");
        builder.append("current_epoch_ms(){ local seconds; seconds=\"$(date +%s 2>/dev/null || true)\"; if [ -n \"$seconds\" ]; then printf '%s000' \"$seconds\"; else printf '0'; fi; }\n");
        builder.append("RUN_STARTED_AT_MS=\"${OPENHOUSE_RUN_STARTED_AT_MS:-$(current_epoch_ms)}\"\n");
        builder.append("REMOTE_SCHEDULE_ACTIVE=0\n");
        builder.append("mark_stage_marker(){ case \"$1\" in __OPENHOUSE_INSTALL_STAGE__:*) local payload=\"${1#__OPENHOUSE_INSTALL_STAGE__:}\"; local stage_slug=\"${payload%%:*}\"; local now_ms=\"$(current_epoch_ms)\"; { printf 'started_at_ms=%s\\n' \"$RUN_STARTED_AT_MS\"; printf 'stage_slug=%s\\n' \"$stage_slug\"; printf 'stage_started_at_ms=%s\\n' \"$now_ms\"; printf 'remote_schedule=%s\\n' \"${REMOTE_SCHEDULE_ACTIVE:-1}\"; } > \"$RUNNING_FILE\" || true;; esac; }\n");
        builder.append("log(){ printf '%s\\n' \"$1\" | tee -a \"$LOG_FILE\"; mark_stage_marker \"$1\"; }\n");
        builder.append("run_logged(){ local status=0; set +e; \"$@\" 2>&1 | tee -a \"$LOG_FILE\"; status=${PIPESTATUS[0]}; set -e; return \"$status\"; }\n");
        builder.append("is_termux(){ [ -n \"${PREFIX:-}\" ] && [ -d \"${PREFIX:-}/bin\" ] && [ -d \"/data/data/com.termux/files\" ]; }\n");
        builder.append("is_current_ubuntu(){ [ -r /etc/os-release ] && grep -qi 'ubuntu' /etc/os-release; }\n");
        builder.append("detect_openhouseai_runtime(){ if is_current_ubuntu; then printf 'ubuntu'; return 0; fi; if [ -x \"${PREFIX:-/data/data/com.termux/files/usr}/bin/openhouseai-env-probe\" ]; then \"${PREFIX:-/data/data/com.termux/files/usr}/bin/openhouseai-env-probe\" 2>/dev/null | awk -F= '$1==\"OPENHOUSEAI_RUNTIME\"{print $2; found=1} END{if(!found) exit 1}' && return 0; fi; if is_termux; then printf 'termux'; return 0; fi; printf 'unknown'; }\n");
        builder.append("run_environment_probe(){ local probe=\"${PREFIX:-/data/data/com.termux/files/usr}/bin/openhouseai-env-probe\"; if [ -x \"$probe\" ]; then log \"正在检查当前运行环境。\"; run_logged \"$probe\" || true; else log \"正在检查当前运行环境。\"; fi; CURRENT_RUNTIME=\"$(detect_openhouseai_runtime)\"; log \"当前运行环境：$CURRENT_RUNTIME\"; }\n");
        builder.append("run_ubuntu_logged(){ if is_current_ubuntu; then run_logged \"$@\"; else run_logged proot-distro login ubuntu -- \"$@\"; fi; }\n");
        builder.append("require_ubuntu(){ if is_current_ubuntu; then return 0; fi; if ! command -v proot-distro >/dev/null 2>&1; then log '缺少 proot-distro，请先完成“准备 Linux 环境”。'; exit 2; fi; if ! proot-distro login ubuntu -- true >/dev/null 2>&1; then log 'Linux 环境尚未安装完成，请先等待“下载 Linux 系统”。'; exit 3; fi; }\n");
        builder.append("__maint_finish(){ local exit_code=$?; printf '__TERMUX_MAINT_DONE__:%s:%s\\n' \"$STAGE_SLUG\" \"$exit_code\" | tee -a \"$LOG_FILE\"; }\n");
        builder.append("trap __maint_finish EXIT\n");
        builder.append("log \"==> $STAGE_NAME\"\n");
        builder.append(scriptBody);
        if (!scriptBody.endsWith("\n")) {
            builder.append('\n');
        }
        return builder.toString();
    }

    private String loadAsset(String assetPath) throws IOException {
        StringBuilder builder = new StringBuilder();
        try (InputStream inputStream = context.getAssets().open(assetPath);
             BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                builder.append(line).append('\n');
            }
        }
        return builder.toString();
    }

    private String buildBundledAssetWriteSnippet(String assetPrefix, String targetVar) throws IOException {
        List<String> assetPaths = new ArrayList<>();
        collectAssetPaths(assetPrefix, assetPrefix, assetPaths);
        assetPaths.sort(String::compareTo);

        StringBuilder builder = new StringBuilder();
        int index = 0;
        for (String assetPath : assetPaths) {
            String relativePath = assetPath.substring(assetPrefix.length() + 1);
            int slashIndex = relativePath.lastIndexOf('/');
            if (slashIndex >= 0) {
                builder.append("mkdir -p \"${").append(targetVar).append("}/")
                    .append(relativePath.substring(0, slashIndex)).append("\"\n");
            }
            String delimiter = "__OPENHOUSE_ASSET_" + index++ + "__";
            builder.append("cat > \"${").append(targetVar).append("}/").append(relativePath)
                .append("\" <<'").append(delimiter).append("'\n");
            String content = loadAsset(assetPath);
            builder.append(content);
            if (!content.endsWith("\n")) {
                builder.append('\n');
            }
            builder.append(delimiter).append('\n');
        }
        return builder.toString();
    }

    private void collectAssetPaths(String rootPrefix, String currentPrefix, List<String> collector) throws IOException {
        String[] children = context.getAssets().list(currentPrefix);
        if (children == null || children.length == 0) {
            if (!currentPrefix.equals(rootPrefix)) {
                collector.add(currentPrefix);
            }
            return;
        }

        Arrays.sort(children);
        for (String child : children) {
            String next = currentPrefix + "/" + child;
            String[] nested = context.getAssets().list(next);
            if (nested == null || nested.length == 0) {
                collector.add(next);
            } else {
                collectAssetPaths(rootPrefix, next, collector);
            }
        }
    }

    private OpenCodeInstallSpec resolveOpenCodeInstallSpec() {
        OpenCodeDownloadSourceSettings.Mode mode = OpenCodeDownloadSourceSettings.getMode(context);
        String primarySourceId = getPreferredOpenCodeSourceId(mode);
        String secondarySourceId = OpenCodeDownloadSourceSettings.SOURCE_OFFICIAL.equals(primarySourceId)
            ? OpenCodeDownloadSourceSettings.SOURCE_MIRROR
            : OpenCodeDownloadSourceSettings.SOURCE_OFFICIAL;
        boolean allowFallback = mode == OpenCodeDownloadSourceSettings.Mode.AUTO;

        return new OpenCodeInstallSpec(
            getDownloadSourceLabel(primarySourceId),
            OpenCodeDownloadSourceSettings.getInstallUrlForSource(primarySourceId),
            getDownloadSourceLabel(secondarySourceId),
            OpenCodeDownloadSourceSettings.getInstallUrlForSource(secondarySourceId),
            allowFallback
        );
    }

    private String getPreferredOpenCodeSourceId(OpenCodeDownloadSourceSettings.Mode mode) {
        switch (mode) {
            case OFFICIAL_ONLY:
                return OpenCodeDownloadSourceSettings.SOURCE_OFFICIAL;
            case MIRROR_ONLY:
                return OpenCodeDownloadSourceSettings.SOURCE_MIRROR;
            case AUTO:
            default:
                return OpenCodeDownloadSourceSettings.getLastSelectedSourceId(context);
        }
    }

    private String getDownloadSourceLabel(String sourceId) {
        return OpenCodeDownloadSourceSettings.SOURCE_MIRROR.equals(OpenCodeDownloadSourceSettings.normalizeSourceId(sourceId))
            ? context.getString(R.string.download_source_label_mirror)
            : context.getString(R.string.download_source_label_official);
    }

    private String readLogTail(int charLimit) {
        File logFile = getManifestLogFile();
        if (!logFile.isFile()) {
            return "";
        }

        int byteLimit = Math.max(4096, Math.min(charLimit * 4 + 4096, 512000));
        try (RandomAccessFile randomAccessFile = new RandomAccessFile(logFile, "r")) {
            long length = randomAccessFile.length();
            long start = Math.max(0L, length - byteLimit);
            randomAccessFile.seek(start);
            byte[] buffer = new byte[(int) (length - start)];
            randomAccessFile.readFully(buffer);
            String content = new String(buffer, StandardCharsets.UTF_8);
            if (content.length() <= charLimit) {
                return content;
            }

            int contentStart = content.length() - charLimit;
            int newlineIndex = content.indexOf('\n', contentStart);
            if (newlineIndex >= 0 && newlineIndex + 1 < content.length()) {
                return content.substring(newlineIndex + 1);
            }
            return content.substring(contentStart);
        } catch (IOException e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to read one-click install log", e);
            return "";
        }
    }

    private void appendCompletionMarkerIfMissing(int exitCode) {
        try {
            File logDir = ensureLogDir();
            File logFile = new File(logDir, MANIFEST_FULL_SLUG + ".log");
            String tail = readLogTail(4096);
            if (readLastExitCode(tail) != null) {
                return;
            }

            try (FileOutputStream outputStream = new FileOutputStream(logFile, true)) {
                String marker = "__TERMUX_MAINT_DONE__:" + MANIFEST_FULL_SLUG + ":" + Math.max(0, exitCode) + "\n";
                outputStream.write(marker.getBytes(StandardCharsets.UTF_8));
            }
        } catch (Exception e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to append one-click install completion marker", e);
        }
    }

    private String redactSecrets(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }

        String redacted = SECRET_PATTERN.matcher(value).replaceAll("$1$2***");
        return OPENAI_STYLE_KEY_PATTERN.matcher(redacted).replaceAll("sk-***");
    }

    private String safeMessage(Exception e) {
        String message = e == null ? "" : e.getMessage();
        return redactSecrets(message == null || message.isEmpty() ? "unknown error" : message);
    }

    private static String shellQuote(String value) {
        if (value == null || value.isEmpty()) {
            return "''";
        }
        return "'" + value.replace("'", "'\"'\"'") + "'";
    }

    private static final class StageMarkerInfo {
        final Stage stage;
        final boolean found;
        final int markerCount;

        StageMarkerInfo(Stage stage, boolean found, int markerCount) {
            this.stage = stage == null ? Stage.PREPARE : stage;
            this.found = found;
            this.markerCount = Math.max(0, markerCount);
        }
    }

    private static final class RunningMarker {
        final boolean exists;
        final long startedAtMs;
        final Stage stage;
        final long stageStartedAtMs;
        final long lastModifiedAtMs;
        final boolean remoteSchedule;

        RunningMarker(boolean exists, long startedAtMs, Stage stage, long stageStartedAtMs, long lastModifiedAtMs, boolean remoteSchedule) {
            this.exists = exists;
            this.startedAtMs = startedAtMs;
            this.stage = stage;
            this.stageStartedAtMs = stageStartedAtMs;
            this.lastModifiedAtMs = lastModifiedAtMs;
            this.remoteSchedule = remoteSchedule;
        }

        static RunningMarker missing() {
            return new RunningMarker(false, 0L, null, 0L, 0L, false);
        }
    }

    private enum Stage {
        PREPARE("prepare", "prepare-product.sh", "准备本机目录", "正在创建文档目录和工作区。"),
        TERMUX_PACKAGES("termux_packages", "update-termux-packages.sh", "准备 Linux 环境", "正在安装 Termux 基础包。"),
        INSTALL_UBUNTU("install_ubuntu", "install-ubuntu.sh", "下载 Linux 系统", "正在下载并安装 Ubuntu。"),
        SYNC_OFFICIAL_DOCS("sync_official_docs", "sync-official-docs.sh", "同步使用文档", "正在同步 OpenHouseAI 使用文档。"),
        UBUNTU_PACKAGES("ubuntu_packages", "update-ubuntu-packages.sh", "安装 Linux 基础工具", "正在安装 curl、git 等基础工具。"),
        CONFIGURE_ENTRY_UBUNTU("entry_ubuntu", "configure-entry-ubuntu.sh", "设置启动方式", "正在配置默认进入 Ubuntu。"),
        INSTALL_NODE("install_node", "install-node.sh", "安装 Node.js 24 LTS", "正在安装或检查 Node.js 24 LTS，后续 AI 工具会复用这一套 Node 运行时。"),
        RUNTIME_COMPONENTS("runtime_components", "install-runtime-components.sh", "安装 SmallPhone 运行栈", "正在从 APK 内置 payload 安装 service-manager、cc-connect 和 SmallPhone。"),
        INSTALL_HERMES("install_hermes", "install-hermes.sh", "安装 AI 伙伴：Hermes", "正在从 APK 内置 payload 安装 Hermes Agent / Hermes WebUI，并注册到 service-manager。"),
        SYNC_OPENHOUSE_REGISTRY("sync_openhouse_registry", "sync-openhouse-registry.sh", "同步 OpenHouseAI 注册表", "正在把 Ubuntu mirror 同步到 Termux canonical，供 App、SmallPhone 和 AI 读取。"),
        START_SMALLPHONE("start_smallphone", "start-smallphone.sh", "启动 SmallPhone", "正在启动 SmallPhone 入口和运行组件。"),
        INSTALL_OPENCODE("install_opencode", "install-opencode.sh", "安装 AI 工具：OpenCode", "正在安装 OpenCode，预计耗时较长，请保持网络连接。"),
        INSTALL_CODEX("install_codex", "install-codex.sh", "安装 AI 工具：Codex", "正在安装 Codex CLI。"),
        INSTALL_CLAUDE_CODE("install_claude_code", "install-claude-code.sh", "安装 AI 工具：Claude Code", "正在安装 Claude Code。"),
        INSTALL_CLAUDE_CODE_UI("install_claude_code_ui", "install-claude-code-ui.sh", "安装 AI 工具：ClaudeCodeUI", "正在安装 ClaudeCodeUI / CloudCLI，并固定端口 23083。"),
        INSTALL_REASONIX("install_reasonix", "install-reasonix.sh", "安装 AI 工具：Reasonix", "正在安装 Reasonix。");

        final String slug;
        final String assetName;
        final String phaseLabel;
        final String detail;

        Stage(String slug, String assetName, String phaseLabel, String detail) {
            this.slug = slug;
            this.assetName = assetName;
            this.phaseLabel = phaseLabel;
            this.detail = detail;
        }

        static Stage fromSlug(String slug) {
            for (Stage stage : values()) {
                if (stage.slug.equals(slug)) {
                    return stage;
                }
            }
            return null;
        }
    }

    private static final class OpenCodeInstallSpec {
        final String primaryLabel;
        final String primaryUrl;
        final String secondaryLabel;
        final String secondaryUrl;
        final boolean allowFallback;

        OpenCodeInstallSpec(String primaryLabel, String primaryUrl,
                            String secondaryLabel, String secondaryUrl,
                            boolean allowFallback) {
            this.primaryLabel = primaryLabel;
            this.primaryUrl = primaryUrl;
            this.secondaryLabel = secondaryLabel;
            this.secondaryUrl = secondaryUrl;
            this.allowFallback = allowFallback;
        }

        static OpenCodeInstallSpec defaultSpec(Context context) {
            return new OpenCodeInstallSpec(
                context.getString(R.string.download_source_label_official),
                OpenCodeDownloadSourceSettings.OFFICIAL_INSTALL_URL,
                context.getString(R.string.download_source_label_mirror),
                OpenCodeDownloadSourceSettings.MIRROR_INSTALL_URL,
                false
            );
        }
    }
}
