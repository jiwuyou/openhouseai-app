package com.termux.app.openhouse;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

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
    private static final int DEFAULT_LOCAL_MAINTENANCE_WEB_PORT = 38423;
    private static final String DEFAULT_CLAUDE_CODE_UI_PORT = "23083";
    private static final String DEFAULT_AIONUI_WEB_PORT = "25808";
    private static final long POLL_INTERVAL_MS = 2000L;
    private static final long STALE_RUNNING_MARKER_MS = 6 * 60 * 60 * 1000L;
    private static final long FINAL_PI_WEB_READY_TIMEOUT_MS = 3 * 60 * 1000L;
    private static final long FINAL_PI_WEB_READY_POLL_INTERVAL_MS = 3000L;
    private static final long STUCK_NO_LOG_MS = 30L * 60L * 1000L;
    private static final long TOTAL_INSTALL_DURATION_MS = 30L * 60L * 1000L;
    private static final double FAST_STAGE_PROGRESS_RATIO = 0.90d;
    private static final double SLOW_STAGE_PROGRESS_RATIO = 1.0d - FAST_STAGE_PROGRESS_RATIO;
    private static final Pattern DONE_PATTERN = Pattern.compile("__TERMUX_MAINT_DONE__:manifest_full:(\\d+)");
    private static final Pattern TASK_PATTERN = Pattern.compile("__OPENHOUSE_INSTALL_TASK__:([a-zA-Z0-9_-]+)");
    private static final Pattern STAGE_PATTERN = Pattern.compile("__OPENHOUSE_INSTALL_STAGE__:([a-zA-Z0-9_-]+):(.+)");
    private static final Pattern SECRET_PATTERN = Pattern.compile("(?i)\\b(api[_-]?key|authorization|bearer|token|password)([=:\"' ]+)([^\\s\"']{8,})");
    private static final Pattern OPENAI_STYLE_KEY_PATTERN = Pattern.compile("\\bsk-[A-Za-z0-9_-]{12,}\\b");
    private static final Stage[] RUNTIME_ENVIRONMENT_STAGE_SEQUENCE = new Stage[] {
        Stage.PREPARE,
        Stage.TERMUX_PACKAGES,
        Stage.INSTALL_TERMUX_NODE,
        Stage.RUNTIME_COMPONENTS,
        Stage.START_SMALLPHONE,
        Stage.INSTALL_UBUNTU,
        Stage.UBUNTU_PACKAGES,
        Stage.CONFIGURE_ENTRY_UBUNTU
    };
    private static final Stage[] AI_FEATURES_STAGE_SEQUENCE = new Stage[] {
        Stage.RUNTIME_COMPONENTS,
        Stage.START_SMALLPHONE,
        Stage.INSTALL_NODE,
        Stage.SYNC_OFFICIAL_DOCS,
        Stage.INSTALL_AIONUI,
        Stage.SYNC_OPENHOUSE_REGISTRY,
    };
    private static final Stage[] FULL_STAGE_SEQUENCE = new Stage[] {
        Stage.PREPARE,
        Stage.TERMUX_PACKAGES,
        Stage.INSTALL_TERMUX_NODE,
        Stage.RUNTIME_COMPONENTS,
        Stage.START_SMALLPHONE,
        Stage.INSTALL_UBUNTU,
        Stage.UBUNTU_PACKAGES,
        Stage.CONFIGURE_ENTRY_UBUNTU,
        Stage.INSTALL_NODE,
        Stage.SYNC_OFFICIAL_DOCS,
        Stage.INSTALL_AIONUI,
        Stage.SYNC_OPENHOUSE_REGISTRY,
    };

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
            if (state.running && shouldFailStuckRun()) {
                executor.execute(() -> failStuckRunRequiringManualRetry());
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
    private volatile OpenHouseInstallState.RetryMode currentRetryMode = OpenHouseInstallState.RetryMode.GENERAL;
    private volatile OpenHouseInstallState.TaskScope currentTaskScope = OpenHouseInstallState.TaskScope.FULL;
    private volatile int currentAttempt = 0;

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
        if (!state.completed && (currentProcess == null || !state.running) && hasFreshRunningMarker()) {
            updateState(readRunningStateFromLog());
            schedulePollIfRunning();
            return state;
        }
        if (state.running && currentProcess == null) {
            if (hasFreshRunningMarker()) {
                updateState(readRunningStateFromLog());
            } else {
                updateState(buildState(
                    OpenHouseInstallState.Status.FAILED,
                    state.percent,
                    "初始化状态异常",
                    "安装进程状态已过期，请查看详细进度后重新初始化。",
                    state.currentStageSlug
                ));
            }
            schedulePollIfRunning();
        }
        return state;
    }

    private void schedulePollIfRunning() {
        if (!state.running) {
            return;
        }

        mainHandler.removeCallbacks(pollRunnable);
        mainHandler.postDelayed(pollRunnable, POLL_INTERVAL_MS);
    }

    public OpenHouseOnboardingState getOnboardingState() {
        return statusRepository.loadOnboardingState(getState(), null);
    }

    public boolean startOneClickInstall() {
        return startOneClickInstall(OpenHouseInstallState.RetryMode.GENERAL);
    }

    public boolean startOneClickInstall(OpenHouseInstallState.RetryMode retryMode) {
        return startInstallInternal(OpenHouseInstallState.TaskScope.FULL, retryMode, 0);
    }

    public boolean startRuntimeEnvironmentInstall() {
        return startRuntimeEnvironmentInstall(OpenHouseInstallState.RetryMode.GENERAL);
    }

    public boolean startRuntimeEnvironmentInstall(OpenHouseInstallState.RetryMode retryMode) {
        return startInstallInternal(OpenHouseInstallState.TaskScope.RUNTIME_ENVIRONMENT, retryMode, 0);
    }

    public boolean startAiFeaturesInstall() {
        return startAiFeaturesInstall(OpenHouseInstallState.RetryMode.GENERAL);
    }

    public boolean startAiFeaturesInstall(OpenHouseInstallState.RetryMode retryMode) {
        return startInstallInternal(OpenHouseInstallState.TaskScope.AI_FEATURES, retryMode, 0);
    }

    private boolean startInstallInternal(OpenHouseInstallState.TaskScope taskScope,
                                         OpenHouseInstallState.RetryMode retryMode,
                                         int requestedAttempt) {
        synchronized (processLock) {
            OpenHouseInstallState.TaskScope resolvedTaskScope = normalizeTaskScope(taskScope);
            OpenHouseInstallState.RetryMode resolvedRetryMode = normalizeRetryMode(retryMode);
            if (hasFreshRunningMarker()) {
                OpenHouseInstallState runningState = readRunningStateFromLog();
                updateState(runningState);
                if (runningState.running) {
                    statusRepository.markOneClickInstallStarted();
                    return false;
                }
            }
            if (currentProcess != null || (state.running && requestedAttempt <= 0)) {
                return false;
            }

            currentTaskScope = resolvedTaskScope;
            if (markCompletedIfAlreadyDeployed(resolvedTaskScope)) {
                return false;
            }
            if (resolvedTaskScope == OpenHouseInstallState.TaskScope.AI_FEATURES
                && !statusRepository.isRuntimeEnvironmentPrepared()) {
                updateState(buildState(
                    OpenHouseInstallState.Status.FAILED,
                    0,
                    "运行环境尚未准备完成",
                    "请先完成运行环境准备，再安装 AI 功能。",
                    firstStageForTask(resolvedTaskScope).slug
                ));
                return true;
            }

            currentRetryMode = resolvedRetryMode;
            currentAttempt = requestedAttempt > 0 ? requestedAttempt : nextAttemptForNewRun();

            updateState(buildState(
                currentAttempt > 1
                    ? OpenHouseInstallState.Status.RETRYING
                    : OpenHouseInstallState.Status.RUNNING,
                1,
                taskStartPhaseLabel(resolvedTaskScope, currentAttempt),
                retryModeStartDetail(resolvedTaskScope, currentRetryMode),
                firstStageForTask(resolvedTaskScope).slug
            ));

            try {
                File bash = new File(TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH, "bash");
                if (!bash.isFile()) {
                    updateState(buildState(
                        OpenHouseInstallState.Status.FAILED,
                        0,
                        "初始化失败",
                        "Termux 基础环境尚未安装完成，缺少 bash。",
                        firstStageForTask(resolvedTaskScope).slug
                    ));
                    return true;
                }

                File logDir = ensureLogDir();
                File tempScript = new File(logDir, "run-" + MANIFEST_FULL_SLUG + ".sh");
                OpenHouseBundledRuntimeSync.Result runtimeSync = prepareBundledRuntimeAssets();
                writeScript(tempScript, buildInstallScript(resolvedTaskScope));
                resetManifestLogForNewRun(resolvedTaskScope);
                long startedAtMs = System.currentTimeMillis();
                writeRunningMarker(startedAtMs, resolvedTaskScope);
                resetProgressSimulation(startedAtMs, firstStageForTask(resolvedTaskScope));

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
                statusRepository.markOneClickInstallStarted();
                mainHandler.removeCallbacks(pollRunnable);
                mainHandler.postDelayed(pollRunnable, 500L);
                executor.execute(() -> waitForInstallProcess(process, outputFile));
                return true;
            } catch (Exception e) {
                Logger.logStackTraceWithMessage(LOG_TAG, "Failed to start OpenHouseAI one-click install", e);
                appendCompletionMarkerIfMissing(1);
                clearRunningMarker();
                updateState(buildState(
                    OpenHouseInstallState.Status.FAILED,
                    state.percent,
                    "初始化失败",
                    "无法启动初始化任务：" + safeMessage(e),
                    firstStageForTask(resolvedTaskScope).slug
                ));
                return true;
            }
        }
    }

    public boolean forceRestartOneClickInstall() {
        return forceRestartOneClickInstall(currentRetryMode);
    }

    public boolean forceRestartOneClickInstall(OpenHouseInstallState.RetryMode retryMode) {
        return forceRestartTask(OpenHouseInstallState.TaskScope.FULL, retryMode);
    }

    public boolean forceRestartCurrentTask() {
        return forceRestartCurrentTask(currentRetryMode);
    }

    public boolean forceRestartCurrentTask(OpenHouseInstallState.RetryMode retryMode) {
        OpenHouseInstallState current = state;
        OpenHouseInstallState.TaskScope taskScope = current == null
            ? currentTaskScope
            : current.taskScope;
        return forceRestartTask(taskScope, retryMode);
    }

    private boolean forceRestartTask(OpenHouseInstallState.TaskScope taskScope,
                                     OpenHouseInstallState.RetryMode retryMode) {
        synchronized (processLock) {
            OpenHouseInstallState.TaskScope resolvedTaskScope = normalizeTaskScope(taskScope);
            int nextAttempt = Math.max(1, Math.max(currentAttempt, state.attempt) + 1);
            currentRetryMode = normalizeRetryMode(retryMode);
            currentTaskScope = resolvedTaskScope;
            stopTrackedInstallProcess();
            terminateExistingInstallProcesses();
            clearRunningMarker();
            updateState(buildState(
                OpenHouseInstallState.Status.RETRYING,
                Math.max(1, state.percent),
                "正在强制重试当前阶段",
                "只会终止卡住的安装任务并清理运行标记，不会删除用户数据、模型配置或工作目录。",
                state.currentStageSlug
            ));
            return startInstallInternal(resolvedTaskScope, currentRetryMode, nextAttempt);
        }
    }

    private boolean markCompletedIfAlreadyDeployed(OpenHouseInstallState.TaskScope taskScope) {
        OpenHouseInstallState.TaskScope resolvedTaskScope = normalizeTaskScope(taskScope);
        if (isTaskAlreadyComplete(resolvedTaskScope)) {
            OpenHouseInstallState completedState = buildState(
                OpenHouseInstallState.Status.SKIPPED,
                100,
                taskCompletedPhaseLabel(resolvedTaskScope),
                taskAlreadyCompleteDetail(resolvedTaskScope),
                lastStageForTask(resolvedTaskScope).slug
            );
            clearRunningMarker();
            updateState(completedState);
            if (resolvedTaskScope != OpenHouseInstallState.TaskScope.RUNTIME_ENVIRONMENT) {
                statusRepository.markOneClickInstallCompleted();
            }
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
            updateState(buildState(
                OpenHouseInstallState.Status.FAILED,
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

        appendCompletionMarkerIfMissing(exitCode);
        OpenHouseInstallState finishedState = readFinishedStateFromLog(exitCode);
        updateState(finishedState);
        if (finishedState.completed
            && finishedState.taskScope != OpenHouseInstallState.TaskScope.RUNTIME_ENVIRONMENT) {
            statusRepository.markOneClickInstallCompleted();
        }
    }

    private void failStuckRunRequiringManualRetry() {
        synchronized (processLock) {
            if (!state.running || !shouldFailStuckRun()) {
                return;
            }
            OpenHouseInstallState.TaskScope taskScope = normalizeTaskScope(state.taskScope);
            Stage stage = Stage.fromSlug(state.currentStageSlug);
            if (stage == null) {
                stage = firstStageForTask(taskScope);
            }
            stopTrackedInstallProcess();
            terminateExistingInstallProcesses();
            appendCompletionMarkerIfMissing(124);
            clearRunningMarker();
            updateState(buildState(
                OpenHouseInstallState.Status.FAILED,
                failurePercent(stage, taskScope),
                "安装等待人工重试",
                "安装任务长时间没有新进展，已停止卡住的任务。请查看详细日志后手动重试当前步骤。",
                stage.slug
            ));
        }
    }

    private OpenHouseInstallState readInitialStateFromLog() {
        String logTail = readLogTail(24000);
        if (hasFreshRunningMarker()) {
            return readRunningStateFromLog();
        }
        currentTaskScope = inferTaskScopeFromLog(logTail);
        Integer exitCode = readLastExitCode(logTail);
        if (exitCode == null) {
            return buildState(
                OpenHouseInstallState.Status.PENDING,
                0,
                "等待初始化",
                "点击“一键初始化”后会开始准备 Linux 环境和安装 AI 工具。",
                MANIFEST_FULL_SLUG
            );
        }
        return buildFinishedState(logTail, exitCode, VerificationMode.CHECK_ONCE);
    }

    private OpenHouseInstallState readRunningStateFromLog() {
        String logTail = readLogTail(24000);
        Integer exitCode = readLastExitCode(logTail);
        if (exitCode != null) {
            clearRunningMarker();
            return buildFinishedState(logTail, exitCode, VerificationMode.CHECK_ONCE);
        }

        RunningMarker runningMarker = readRunningMarker();
        OpenHouseInstallState.TaskScope taskScope = normalizeTaskScope(runningMarker.taskScope);
        currentTaskScope = taskScope;
        if (runningMarker.retryMode != null) {
            currentRetryMode = runningMarker.retryMode;
        }
        if (runningMarker.attempt > 0) {
            currentAttempt = runningMarker.attempt;
        }
        StageMarkerInfo stageMarkerInfo = inferCurrentStageMarker(logTail, taskScope);
        Stage stage = stageMarkerInfo.found
            ? stageMarkerInfo.stage
            : runningMarker.stage == null ? firstStageForTask(taskScope) : runningMarker.stage;
        stage = normalizeStageForTask(stage, taskScope);
        if (shouldUseScheduledRemoteStage(stageMarkerInfo, runningMarker)) {
            stage = inferScheduledStage(runningMarker, System.currentTimeMillis());
        }
        stage = resolveMonotonicStage(stage, stageMarkerInfo, runningMarker, taskScope);
        String detail = latestReadableLogLine(logTail);
        if (detail.isEmpty()) {
            detail = stage.detail;
        }
        int percent = Math.max(simulateRunningPercent(stage, runningMarker, taskScope), currentRunningPercentFloor());
        return new OpenHouseInstallState(
            OpenHouseInstallState.Status.RUNNING,
            percent,
            stage.phaseLabel,
            detail,
            stage.slug,
            currentRetryMode,
            Math.max(1, currentAttempt),
            getManifestLogPath(),
            "",
            taskScope
        );
    }

    private OpenHouseInstallState readFinishedStateFromLog(int processExitCode) {
        String logTail = readLogTail(24000);
        Integer markerExitCode = readLastExitCode(logTail);
        return buildFinishedState(logTail, markerExitCode == null ? processExitCode : markerExitCode, VerificationMode.WAIT_BRIEFLY);
    }

    private OpenHouseInstallState buildFinishedState(String logTail, int exitCode, VerificationMode verificationMode) {
        OpenHouseInstallState.TaskScope taskScope = inferTaskScopeFromLog(logTail);
        currentTaskScope = taskScope;
        if (exitCode == 0) {
            if (!verifyTaskComplete(taskScope, verificationMode)) {
                StageMarkerInfo stageMarkerInfo = inferCurrentStageMarker(logTail, taskScope);
                Stage stage = resolveMonotonicStage(stageMarkerInfo.stage, stageMarkerInfo, null, taskScope);
                String detail = latestReadableLogLine(logTail);
                if (detail.isEmpty()) {
                    detail = taskIncompleteAfterExitDetail(taskScope);
                } else {
                    detail = taskIncompleteAfterExitDetail(taskScope) + "最后日志：" + detail;
                }
                return buildState(
                    OpenHouseInstallState.Status.FAILED,
                    Math.max(90, failurePercent(stage, taskScope)),
                    "安装未完全就绪",
                    detail,
                    stage.slug
                );
            }
            return buildState(
                OpenHouseInstallState.Status.SUCCEEDED,
                100,
                taskCompletedPhaseLabel(taskScope),
                taskCompletedDetail(taskScope),
                lastStageForTask(taskScope).slug
            );
        }

        StageMarkerInfo stageMarkerInfo = inferCurrentStageMarker(logTail, taskScope);
        Stage stage = resolveMonotonicStage(stageMarkerInfo.stage, stageMarkerInfo, null, taskScope);
        String detail = latestReadableLogLine(logTail);
        if (detail.isEmpty()) {
            detail = "请点击“查看详细进度”查看完整日志。";
        }
        return buildState(
            OpenHouseInstallState.Status.FAILED,
            failurePercent(stage, taskScope),
            "初始化失败",
            detail,
            stage.slug
        );
    }

    private boolean verifyCoreDeploymentComplete(VerificationMode mode) {
        long deadlineMs = mode == VerificationMode.WAIT_BRIEFLY
            ? System.currentTimeMillis() + FINAL_PI_WEB_READY_TIMEOUT_MS
            : System.currentTimeMillis();
        while (true) {
            try {
                if (statusRepository.isFirstUseReady()) {
                    return true;
                }
            } catch (Exception e) {
                Logger.logStackTraceWithMessage(LOG_TAG, "Failed to verify OpenHouseAI first-use readiness", e);
            }

            long remainingMs = deadlineMs - System.currentTimeMillis();
            if (mode != VerificationMode.WAIT_BRIEFLY || remainingMs <= 0L) {
                return false;
            }

            try {
                Thread.sleep(Math.min(FINAL_PI_WEB_READY_POLL_INTERVAL_MS, remainingMs));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
    }

    private boolean verifyTaskComplete(OpenHouseInstallState.TaskScope taskScope, VerificationMode mode) {
        OpenHouseInstallState.TaskScope resolvedTaskScope = normalizeTaskScope(taskScope);
        if (resolvedTaskScope == OpenHouseInstallState.TaskScope.RUNTIME_ENVIRONMENT) {
            try {
                return statusRepository.isRuntimeEnvironmentPrepared();
            } catch (Exception e) {
                Logger.logStackTraceWithMessage(LOG_TAG, "Failed to verify runtime environment readiness", e);
                return false;
            }
        }
        return verifyCoreDeploymentComplete(mode);
    }

    private boolean isTaskAlreadyComplete(OpenHouseInstallState.TaskScope taskScope) {
        OpenHouseInstallState.TaskScope resolvedTaskScope = normalizeTaskScope(taskScope);
        try {
            if (resolvedTaskScope == OpenHouseInstallState.TaskScope.RUNTIME_ENVIRONMENT) {
                return statusRepository.isRuntimeEnvironmentPrepared();
            }
            return statusRepository.isAiFeaturesReady();
        } catch (Exception e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to check OpenHouseAI install completion", e);
            return false;
        }
    }

    private enum VerificationMode {
        CHECK_ONCE,
        WAIT_BRIEFLY
    }

    private Integer readLastExitCode(String logContent) {
        Matcher matcher = DONE_PATTERN.matcher(logContent == null ? "" : logContent);
        Integer exitCode = null;
        while (matcher.find()) {
            exitCode = Integer.parseInt(matcher.group(1));
        }
        return exitCode;
    }

    private OpenHouseInstallState.TaskScope inferTaskScopeFromLog(String logContent) {
        Matcher matcher = TASK_PATTERN.matcher(logContent == null ? "" : logContent);
        OpenHouseInstallState.TaskScope taskScope = null;
        while (matcher.find()) {
            taskScope = OpenHouseInstallState.TaskScope.fromValue(matcher.group(1));
        }
        if (taskScope != null) {
            return taskScope;
        }
        OpenHouseInstallState current = state;
        if (current != null && current.taskScope != null) {
            return current.taskScope;
        }
        return normalizeTaskScope(currentTaskScope);
    }

    private StageMarkerInfo inferCurrentStageMarker(String logContent,
                                                    OpenHouseInstallState.TaskScope taskScope) {
        Matcher matcher = STAGE_PATTERN.matcher(logContent == null ? "" : logContent);
        OpenHouseInstallState.TaskScope resolvedTaskScope = normalizeTaskScope(taskScope);
        Stage stage = firstStageForTask(resolvedTaskScope);
        boolean found = false;
        int markerCount = 0;
        while (matcher.find()) {
            Stage matched = Stage.fromSlug(matcher.group(1));
            if (matched != null && containsStage(resolvedTaskScope, matched)) {
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
        OpenHouseInstallState.TaskScope taskScope = normalizeTaskScope(runningMarker.taskScope);
        Stage scheduledStage = firstStageForTask(taskScope);
        long boundaryMs = 0L;
        for (Stage candidate : getStageSequence(taskScope)) {
            scheduledStage = candidate;
            boundaryMs += getStageDurationMs(candidate, taskScope);
            if (elapsedMs < boundaryMs) {
                break;
            }
        }
        return scheduledStage;
    }

    private int simulateRunningPercent(Stage stage,
                                       RunningMarker runningMarker,
                                       OpenHouseInstallState.TaskScope taskScope) {
        OpenHouseInstallState.TaskScope resolvedTaskScope = normalizeTaskScope(taskScope);
        int stageStartPercent = getStageStartPercent(stage, resolvedTaskScope);
        int stageEndPercent = getStageEndPercent(stage, resolvedTaskScope);
        if (stageEndPercent <= stageStartPercent) {
            return stageStartPercent;
        }

        long nowMs = System.currentTimeMillis();
        long stageStartedAtMs = resolveStageStartedAtMs(stage, runningMarker, nowMs, resolvedTaskScope);
        long elapsedMs = Math.max(0L, nowMs - stageStartedAtMs);
        long stageDurationMs = Math.max(1L, getStageDurationMs(stage, resolvedTaskScope));
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

    private long resolveStageStartedAtMs(Stage stage,
                                         RunningMarker runningMarker,
                                         long nowMs,
                                         OpenHouseInstallState.TaskScope taskScope) {
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

        long estimatedStartedAtMs = estimateStageStartedAtMs(stage, runningMarker, nowMs, taskScope);
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

    private long estimateStageStartedAtMs(Stage stage,
                                          RunningMarker runningMarker,
                                          long nowMs,
                                          OpenHouseInstallState.TaskScope taskScope) {
        if (runningMarker.startedAtMs <= 0L) {
            return nowMs;
        }

        long scheduledStartedAtMs = runningMarker.startedAtMs + getCumulativeDurationBeforeStageMs(stage, taskScope);
        return Math.min(scheduledStartedAtMs, nowMs);
    }

    private long getCumulativeDurationBeforeStageMs(Stage stage,
                                                    OpenHouseInstallState.TaskScope taskScope) {
        long durationMs = 0L;
        OpenHouseInstallState.TaskScope resolvedTaskScope = normalizeTaskScope(taskScope);
        for (Stage candidate : getStageSequence(resolvedTaskScope)) {
            if (candidate == stage) {
                return durationMs;
            }
            durationMs += getStageDurationMs(candidate, resolvedTaskScope);
        }
        return durationMs;
    }

    private long getStageDurationMs(Stage stage, OpenHouseInstallState.TaskScope taskScope) {
        return Math.max(1L, TOTAL_INSTALL_DURATION_MS / Math.max(1, getStageSequence(taskScope).length));
    }

    private int getStageStartPercent(Stage stage, OpenHouseInstallState.TaskScope taskScope) {
        return clampPercent((int) Math.round(getCumulativeWeightBeforeStage(stage, taskScope)));
    }

    private int getStageEndPercent(Stage stage, OpenHouseInstallState.TaskScope taskScope) {
        return clampPercent((int) Math.round(getCumulativeWeightBeforeStage(stage, taskScope) + getStageWeightPercent(stage, taskScope)));
    }

    private double getCumulativeWeightBeforeStage(Stage stage,
                                                  OpenHouseInstallState.TaskScope taskScope) {
        double percent = 0.0d;
        OpenHouseInstallState.TaskScope resolvedTaskScope = normalizeTaskScope(taskScope);
        for (Stage candidate : getStageSequence(resolvedTaskScope)) {
            if (candidate == stage) {
                return percent;
            }
            percent += getStageWeightPercent(candidate, resolvedTaskScope);
        }
        return percent;
    }

    private double getStageWeightPercent(Stage stage, OpenHouseInstallState.TaskScope taskScope) {
        return 100.0d / Math.max(1, getStageSequence(taskScope).length);
    }

    private Stage resolveMonotonicStage(Stage stage,
                                        StageMarkerInfo stageMarkerInfo,
                                        RunningMarker runningMarker,
                                        OpenHouseInstallState.TaskScope taskScope) {
        OpenHouseInstallState.TaskScope resolvedTaskScope = normalizeTaskScope(taskScope);
        stage = normalizeStageForTask(stage, resolvedTaskScope);
        if (runningMarker != null && !runningMarker.remoteSchedule && stageMarkerInfo != null && stageMarkerInfo.found) {
            return stage;
        }
        if (stageMarkerInfo != null && stageMarkerInfo.markerCount > 1) {
            return stage;
        }
        OpenHouseInstallState current = state;
        Stage currentStage = current == null || !current.running || current.taskScope != resolvedTaskScope
            ? null
            : Stage.fromSlug(current.currentStageSlug);
        if (currentStage != null && getStageIndex(stage, resolvedTaskScope) < getStageIndex(currentStage, resolvedTaskScope)) {
            return currentStage;
        }
        return stage;
    }

    private int getStageIndex(Stage stage, OpenHouseInstallState.TaskScope taskScope) {
        Stage[] sequence = getStageSequence(taskScope);
        for (int i = 0; i < sequence.length; i++) {
            if (sequence[i] == stage) {
                return i;
            }
        }
        return 0;
    }

    private int failurePercent(Stage stage, OpenHouseInstallState.TaskScope taskScope) {
        OpenHouseInstallState.TaskScope resolvedTaskScope = normalizeTaskScope(taskScope);
        int percent = getStageStartPercent(stage, resolvedTaskScope);
        OpenHouseInstallState current = state;
        if (current != null && current.running && current.taskScope == resolvedTaskScope) {
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

    private boolean shouldFailStuckRun() {
        RunningMarker runningMarker = readRunningMarker();
        if (!runningMarker.exists) {
            return false;
        }

        File logFile = getManifestLogFile();
        if (!logFile.isFile()) {
            return false;
        }

        long nowMs = System.currentTimeMillis();
        long thresholdMs = STUCK_NO_LOG_MS;
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

    private void resetProgressSimulation(long startedAtMs, Stage firstStage) {
        observedProgressStage = firstStage == null ? Stage.PREPARE : firstStage;
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
                || line.startsWith("__OPENHOUSE_INSTALL_TASK__:")
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

    private OpenHouseInstallState buildState(OpenHouseInstallState.Status status,
                                             int percent,
                                             String phaseLabel,
                                             String detailText,
                                             String currentStageSlug) {
        OpenHouseInstallState.Status resolvedStatus = status == null
            ? OpenHouseInstallState.Status.PENDING
            : status;
        String safeDetail = redactSecrets(detailText == null ? "" : detailText);
        return new OpenHouseInstallState(
            resolvedStatus,
            percent,
            phaseLabel,
            safeDetail,
            currentStageSlug,
            currentRetryMode,
            resolvedStatus == OpenHouseInstallState.Status.PENDING ? 0 : Math.max(1, currentAttempt),
            getManifestLogPath(),
            resolvedStatus == OpenHouseInstallState.Status.FAILED ? safeDetail : "",
            currentTaskScope
        );
    }

    private OpenHouseInstallState.RetryMode normalizeRetryMode(OpenHouseInstallState.RetryMode retryMode) {
        return retryMode == null ? OpenHouseInstallState.RetryMode.GENERAL : retryMode;
    }

    private OpenHouseInstallState.TaskScope normalizeTaskScope(OpenHouseInstallState.TaskScope taskScope) {
        return taskScope == null ? OpenHouseInstallState.TaskScope.FULL : taskScope;
    }

    private Stage[] getStageSequence(OpenHouseInstallState.TaskScope taskScope) {
        OpenHouseInstallState.TaskScope resolvedTaskScope = normalizeTaskScope(taskScope);
        if (resolvedTaskScope == OpenHouseInstallState.TaskScope.RUNTIME_ENVIRONMENT) {
            return RUNTIME_ENVIRONMENT_STAGE_SEQUENCE;
        }
        if (resolvedTaskScope == OpenHouseInstallState.TaskScope.AI_FEATURES) {
            return AI_FEATURES_STAGE_SEQUENCE;
        }
        return FULL_STAGE_SEQUENCE;
    }

    private boolean containsStage(OpenHouseInstallState.TaskScope taskScope, Stage stage) {
        if (stage == null) {
            return false;
        }
        for (Stage candidate : getStageSequence(taskScope)) {
            if (candidate == stage) {
                return true;
            }
        }
        return false;
    }

    private Stage normalizeStageForTask(Stage stage, OpenHouseInstallState.TaskScope taskScope) {
        return containsStage(taskScope, stage) ? stage : firstStageForTask(taskScope);
    }

    private Stage firstStageForTask(OpenHouseInstallState.TaskScope taskScope) {
        Stage[] sequence = getStageSequence(taskScope);
        return sequence.length == 0 ? Stage.PREPARE : sequence[0];
    }

    private Stage lastStageForTask(OpenHouseInstallState.TaskScope taskScope) {
        Stage[] sequence = getStageSequence(taskScope);
        return sequence.length == 0 ? Stage.PREPARE : sequence[sequence.length - 1];
    }

    private int nextAttemptForNewRun() {
        OpenHouseInstallState current = state;
        if (current == null || current.status == OpenHouseInstallState.Status.PENDING) {
            return Math.max(1, currentAttempt <= 0 ? 1 : currentAttempt);
        }
        if (current.failed || current.status == OpenHouseInstallState.Status.RETRYING) {
            return Math.max(1, Math.max(currentAttempt, current.attempt) + 1);
        }
        return Math.max(1, current.attempt > 0 ? current.attempt : currentAttempt > 0 ? currentAttempt : 1);
    }

    private String taskStartPhaseLabel(OpenHouseInstallState.TaskScope taskScope, int attempt) {
        OpenHouseInstallState.TaskScope resolvedTaskScope = normalizeTaskScope(taskScope);
        if (attempt > 1) {
            return "正在重试安装";
        }
        if (resolvedTaskScope == OpenHouseInstallState.TaskScope.RUNTIME_ENVIRONMENT) {
            return "准备运行环境";
        }
        if (resolvedTaskScope == OpenHouseInstallState.TaskScope.AI_FEATURES) {
            return "准备安装 AI 功能";
        }
        return "准备初始化";
    }

    private String retryModeStartDetail(OpenHouseInstallState.TaskScope taskScope,
                                        OpenHouseInstallState.RetryMode retryMode) {
        String taskLabel = taskLogLabel(taskScope);
        if (retryMode == OpenHouseInstallState.RetryMode.CN) {
            return "正在启动" + taskLabel + "任务，会使用固定国内镜像路径并从第一个未完成阶段继续。";
        }
        return "正在启动" + taskLabel + "任务，会复用已有缓存并从第一个未完成阶段继续。";
    }

    private String taskCompletedPhaseLabel(OpenHouseInstallState.TaskScope taskScope) {
        OpenHouseInstallState.TaskScope resolvedTaskScope = normalizeTaskScope(taskScope);
        if (resolvedTaskScope == OpenHouseInstallState.TaskScope.RUNTIME_ENVIRONMENT) {
            return "运行环境准备完成";
        }
        if (resolvedTaskScope == OpenHouseInstallState.TaskScope.AI_FEATURES) {
            return "AI 功能安装完成";
        }
        return "初始化安装完成";
    }

    private String taskCompletedDetail(OpenHouseInstallState.TaskScope taskScope) {
        OpenHouseInstallState.TaskScope resolvedTaskScope = normalizeTaskScope(taskScope);
        if (resolvedTaskScope == OpenHouseInstallState.TaskScope.RUNTIME_ENVIRONMENT) {
            return "已检测到基础环境和启动入口配置完成，可以继续安装 AI 功能。";
        }
        return "已检测到 AI 功能和本地 AI 页面可用，首次安装完成；SmallPhone、openhouse-connect 等附属服务可稍后在运行控制中查看或修复。";
    }

    private String taskAlreadyCompleteDetail(OpenHouseInstallState.TaskScope taskScope) {
        OpenHouseInstallState.TaskScope resolvedTaskScope = normalizeTaskScope(taskScope);
        if (resolvedTaskScope == OpenHouseInstallState.TaskScope.RUNTIME_ENVIRONMENT) {
            return "已检测到基础环境和启动入口配置完成，无需再次执行。";
        }
        if (resolvedTaskScope == OpenHouseInstallState.TaskScope.AI_FEATURES) {
            return "已检测到 AI 功能可用，无需再次执行安装。";
        }
        return "已检测到核心控制平面安装完成，无需再次执行初始化。";
    }

    private String taskIncompleteAfterExitDetail(OpenHouseInstallState.TaskScope taskScope) {
        OpenHouseInstallState.TaskScope resolvedTaskScope = normalizeTaskScope(taskScope);
        if (resolvedTaskScope == OpenHouseInstallState.TaskScope.RUNTIME_ENVIRONMENT) {
            return "安装脚本已经退出，但尚未确认基础环境和启动入口配置完成。请等待状态刷新；如果仍停留在这里，请手动重试当前步骤。";
        }
        return "安装脚本已经退出，但 3 分钟内没有确认 AI 功能和本地 AI 页面可用。请等待状态刷新；如果仍停留在这里，请手动重试当前步骤。";
    }

    private String taskLogLabel(OpenHouseInstallState.TaskScope taskScope) {
        OpenHouseInstallState.TaskScope resolvedTaskScope = normalizeTaskScope(taskScope);
        if (resolvedTaskScope == OpenHouseInstallState.TaskScope.RUNTIME_ENVIRONMENT) {
            return "运行环境准备";
        }
        if (resolvedTaskScope == OpenHouseInstallState.TaskScope.AI_FEATURES) {
            return "AI 功能安装";
        }
        return "一键初始化";
    }

    private String shellRetryMode(OpenHouseInstallState.RetryMode retryMode) {
        return retryMode == OpenHouseInstallState.RetryMode.CN ? "cn" : "normal";
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

    private String getManifestLogPath() {
        return getManifestLogFile().getAbsolutePath();
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
        OpenHouseInstallState.RetryMode retryMode = OpenHouseInstallState.RetryMode.GENERAL;
        OpenHouseInstallState.TaskScope taskScope = currentTaskScope;
        int attempt = 0;
        String logPath = getManifestLogPath();
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
            } else if ("retry_mode".equals(key)) {
                retryMode = OpenHouseInstallState.RetryMode.fromValue(value);
            } else if ("task_scope".equals(key)) {
                taskScope = OpenHouseInstallState.TaskScope.fromValue(value);
            } else if ("attempt".equals(key)) {
                long parsedAttempt = parsePositiveLong(value);
                attempt = parsedAttempt > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) parsedAttempt;
            } else if ("log_path".equals(key)) {
                logPath = value;
            }
        }

        if (startedAtMs <= 0L) {
            startedAtMs = lastModifiedAtMs;
        }
        return new RunningMarker(true, startedAtMs, stage, stageStartedAtMs, lastModifiedAtMs, remoteSchedule, retryMode, taskScope, attempt, logPath);
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

    private void writeRunningMarker(long startedAtMs,
                                    OpenHouseInstallState.TaskScope taskScope) throws IOException {
        OpenHouseInstallState.TaskScope resolvedTaskScope = normalizeTaskScope(taskScope);
        File marker = getRunningMarkerFile();
        try (FileOutputStream outputStream = new FileOutputStream(marker, false)) {
            outputStream.write(("started_at_ms=" + startedAtMs + "\n").getBytes(StandardCharsets.UTF_8));
            outputStream.write(("stage_slug=" + firstStageForTask(resolvedTaskScope).slug + "\n").getBytes(StandardCharsets.UTF_8));
            outputStream.write(("stage_started_at_ms=" + startedAtMs + "\n").getBytes(StandardCharsets.UTF_8));
            outputStream.write("remote_schedule=1\n".getBytes(StandardCharsets.UTF_8));
            outputStream.write(("retry_mode=" + currentRetryMode.value + "\n").getBytes(StandardCharsets.UTF_8));
            outputStream.write(("task_scope=" + resolvedTaskScope.value + "\n").getBytes(StandardCharsets.UTF_8));
            outputStream.write(("attempt=" + Math.max(1, currentAttempt) + "\n").getBytes(StandardCharsets.UTF_8));
            outputStream.write(("log_path=" + getManifestLogPath() + "\n").getBytes(StandardCharsets.UTF_8));
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

    private void resetManifestLogForNewRun(OpenHouseInstallState.TaskScope taskScope) throws IOException {
        OpenHouseInstallState.TaskScope resolvedTaskScope = normalizeTaskScope(taskScope);
        File logFile = getManifestLogFile();
        File parent = logFile.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }
        try (FileOutputStream outputStream = new FileOutputStream(logFile, false)) {
            String taskLine = "__OPENHOUSE_INSTALL_TASK__:" + resolvedTaskScope.value + "\n";
            outputStream.write(taskLine.getBytes(StandardCharsets.UTF_8));
            outputStream.write(("开始执行 SmallPhoneAI " + taskLogLabel(resolvedTaskScope) + "。\n").getBytes(StandardCharsets.UTF_8));
            String modeLine = "重试模式：" + currentRetryMode.label + "；尝试次数：" + Math.max(1, currentAttempt) + "\n";
            outputStream.write(modeLine.getBytes(StandardCharsets.UTF_8));
        }
    }

    private void configureEnvironment(Map<String, String> environment,
                                      long startedAtMs,
                                      OpenHouseBundledRuntimeSync.Result runtimeSync) {
        environment.put("HOME", TermuxConstants.TERMUX_HOME_DIR_PATH);
        environment.put("PREFIX", TermuxConstants.TERMUX_PREFIX_DIR_PATH);
        environment.put("PATH", TermuxConstants.TERMUX_HOME_DIR_PATH + "/.npm-global/bin:"
            + TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + ":/system/bin");
        environment.put("LD_LIBRARY_PATH", TermuxConstants.TERMUX_LIB_PREFIX_DIR_PATH);
        environment.put("TMPDIR", TermuxConstants.TERMUX_TMP_PREFIX_DIR_PATH);
        environment.put("LANG", "C.UTF-8");
        environment.put("OPENHOUSEAI_NO_AUTO_UBUNTU", "1");
        environment.put("TERMUX_NO_AUTO_UBUNTU", "1");
        environment.put("OPENHOUSE_RUN_STARTED_AT_MS", Long.toString(startedAtMs));
        String shellRetryMode = shellRetryMode(currentRetryMode);
        environment.put("OPENHOUSE_RETRY_MODE", shellRetryMode);
        environment.put("OPENHOUSEAI_RETRY_MODE", shellRetryMode);
        environment.put("SMALLPHONEAI_RETRY_MODE", shellRetryMode);
        environment.put("OPENHOUSE_INSTALL_ATTEMPT", Integer.toString(Math.max(1, currentAttempt)));
        environment.put("OPENHOUSE_INSTALL_TASK_SCOPE", normalizeTaskScope(currentTaskScope).value);
        environment.put("OPENHOUSE_INSTALL_LOG_PATH", getManifestLogPath());
        if (currentRetryMode == OpenHouseInstallState.RetryMode.CN) {
            environment.put("OPENHOUSEAI_TERMUX_MAIN_REPO", "https://mirrors.tuna.tsinghua.edu.cn/termux/apt/termux-main");
            environment.put("SMALLPHONEAI_TERMUX_MAIN_REPO", "https://mirrors.tuna.tsinghua.edu.cn/termux/apt/termux-main");
            environment.put("OPENHOUSEAI_UBUNTU_ROOTFS_URL", "https://mirrors.ustc.edu.cn/ubuntu-cloud-images/noble/current/noble-server-cloudimg-arm64-root.tar.xz");
            environment.put("OPENHOUSEAI_NODE_DIST_BASE", "https://cdn.npmmirror.com/binaries/node/latest-v24.x");
            environment.put("SMALLPHONEAI_NODE_DIST_BASE", "https://cdn.npmmirror.com/binaries/node/latest-v24.x");
            environment.put("NPM_REGISTRY", "https://registry.npmmirror.com/");
            environment.put("NPM_CONFIG_REGISTRY", "https://registry.npmmirror.com/");
        }
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

    private String buildInstallScript(OpenHouseInstallState.TaskScope taskScope) throws IOException {
        OpenHouseInstallState.TaskScope resolvedTaskScope = normalizeTaskScope(taskScope);
        String taskLabel = taskLogLabel(resolvedTaskScope);
        StringBuilder bundledBody = new StringBuilder();
        bundledBody.append("log ").append(shellQuote("__OPENHOUSE_INSTALL_TASK__:" + resolvedTaskScope.value)).append('\n');
        bundledBody.append("log ").append(shellQuote("开始执行 APK 内置 SmallPhoneAI " + taskLabel + "流程。")).append('\n');
        for (Stage stage : getStageSequence(resolvedTaskScope)) {
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
        bundledBody.append("log ").append(shellQuote("APK 内置 SmallPhoneAI " + taskLabel + "已完成。")).append('\n');

        StringBuilder scriptBody = new StringBuilder();
        scriptBody.append("log ").append(shellQuote("开始执行 SmallPhoneAI " + taskLabel + "。")).append('\n');
        scriptBody.append(bundledBody);
        if (!bundledBody.toString().endsWith("\n")) {
            scriptBody.append('\n');
        }
        scriptBody.append("log ").append(shellQuote("SmallPhoneAI " + taskLabel + "已完成。")).append('\n');
        return buildWrapperScript(taskLabel, MANIFEST_FULL_SLUG, scriptBody.toString());
    }

    private String buildAssetScriptBody(Stage stage) throws IOException {
        return loadAsset("maintainer/" + stage.assetName)
            .replace("__PORT__", DEFAULT_CLAUDE_CODE_UI_PORT)
            .replace("__CLAUDE_CODE_UI_PORT__", DEFAULT_CLAUDE_CODE_UI_PORT)
            .replace("__AIONUI_WEB_PORT__", DEFAULT_AIONUI_WEB_PORT)
            .replace("__REQUIRED_COMPONENT_TARGETS__", "")
            .replace("__LOCAL_MAINTENANCE_WEB_PORT__", Integer.toString(DEFAULT_LOCAL_MAINTENANCE_WEB_PORT))
            .replace("__BUNDLED_OFFICIAL_DOCS__", buildBundledAssetWriteSnippet(OFFICIAL_DOCS_ASSET_DIR, "OFFICIAL_DOC_DIR"));
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
        builder.append("export PATH=\"$HOME/.npm-global/bin:$PREFIX/bin:/system/bin:${PATH:-}\"\n");
        builder.append("export LD_LIBRARY_PATH=\"$PREFIX/lib:${LD_LIBRARY_PATH:-}\"\n");
        builder.append("export TMPDIR=\"${TMPDIR:-$PREFIX/tmp}\"\n");
        builder.append("export TERM=\"xterm-256color\"\n");
        builder.append("export OPENHOUSEAI_NO_AUTO_UBUNTU=1\n");
        builder.append("export TERMUX_NO_AUTO_UBUNTU=1\n");
        builder.append("export OPENHOUSE_RETRY_MODE=\"${OPENHOUSE_RETRY_MODE:-normal}\"\n");
        builder.append("export OPENHOUSEAI_RETRY_MODE=\"${OPENHOUSEAI_RETRY_MODE:-$OPENHOUSE_RETRY_MODE}\"\n");
        builder.append("export SMALLPHONEAI_RETRY_MODE=\"${SMALLPHONEAI_RETRY_MODE:-$OPENHOUSE_RETRY_MODE}\"\n");
        builder.append("export OPENHOUSE_INSTALL_ATTEMPT=\"${OPENHOUSE_INSTALL_ATTEMPT:-1}\"\n");
        builder.append("export OPENHOUSE_INSTALL_TASK_SCOPE=\"${OPENHOUSE_INSTALL_TASK_SCOPE:-full}\"\n");
        builder.append("export OPENHOUSE_INSTALL_LOG_PATH=\"${OPENHOUSE_INSTALL_LOG_PATH:-$HOME/.maintainer-logs/").append(MANIFEST_FULL_SLUG).append(".log}\"\n");
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
        builder.append("mark_stage_marker(){ case \"$1\" in __OPENHOUSE_INSTALL_STAGE__:*) local payload=\"${1#__OPENHOUSE_INSTALL_STAGE__:}\"; local stage_slug=\"${payload%%:*}\"; local now_ms=\"$(current_epoch_ms)\"; { printf 'started_at_ms=%s\\n' \"$RUN_STARTED_AT_MS\"; printf 'stage_slug=%s\\n' \"$stage_slug\"; printf 'stage_started_at_ms=%s\\n' \"$now_ms\"; printf 'remote_schedule=%s\\n' \"${REMOTE_SCHEDULE_ACTIVE:-1}\"; printf 'retry_mode=%s\\n' \"${OPENHOUSE_RETRY_MODE:-normal}\"; printf 'task_scope=%s\\n' \"${OPENHOUSE_INSTALL_TASK_SCOPE:-full}\"; printf 'attempt=%s\\n' \"${OPENHOUSE_INSTALL_ATTEMPT:-1}\"; printf 'log_path=%s\\n' \"${OPENHOUSE_INSTALL_LOG_PATH:-$LOG_FILE}\"; } > \"$RUNNING_FILE\" || true;; esac; }\n");
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
        final OpenHouseInstallState.RetryMode retryMode;
        final OpenHouseInstallState.TaskScope taskScope;
        final int attempt;
        final String logPath;

        RunningMarker(boolean exists,
                      long startedAtMs,
                      Stage stage,
                      long stageStartedAtMs,
                      long lastModifiedAtMs,
                      boolean remoteSchedule,
                      OpenHouseInstallState.RetryMode retryMode,
                      OpenHouseInstallState.TaskScope taskScope,
                      int attempt,
                      String logPath) {
            this.exists = exists;
            this.startedAtMs = startedAtMs;
            this.stage = stage;
            this.stageStartedAtMs = stageStartedAtMs;
            this.lastModifiedAtMs = lastModifiedAtMs;
            this.remoteSchedule = remoteSchedule;
            this.retryMode = retryMode == null ? OpenHouseInstallState.RetryMode.GENERAL : retryMode;
            this.taskScope = taskScope == null ? OpenHouseInstallState.TaskScope.FULL : taskScope;
            this.attempt = Math.max(0, attempt);
            this.logPath = logPath == null ? "" : logPath;
        }

        static RunningMarker missing() {
            return new RunningMarker(false, 0L, null, 0L, 0L, false, OpenHouseInstallState.RetryMode.GENERAL, OpenHouseInstallState.TaskScope.FULL, 0, "");
        }
    }

    private enum Stage {
        PREPARE("prepare", "prepare-product.sh", "准备本机目录", "正在创建文档目录和工作区。"),
        TERMUX_PACKAGES("termux_packages", "update-termux-packages.sh", "准备 Linux 环境", "正在安装 Termux 基础包。"),
        INSTALL_TERMUX_NODE("install_termux_node", "install-termux-node.sh", "安装 Termux Node.js 24 LTS", "正在安装或检查 Termux native Node.js 24 LTS/npm，供 pi-agent 和 pi-web 常驻服务使用。"),
        INSTALL_UBUNTU("install_ubuntu", "install-ubuntu.sh", "下载 Linux 系统", "正在下载并安装 Ubuntu。"),
        SYNC_OFFICIAL_DOCS("sync_official_docs", "sync-official-docs.sh", "同步使用文档", "正在同步 OpenHouseAI 使用文档。"),
        UBUNTU_PACKAGES("ubuntu_packages", "update-ubuntu-packages.sh", "安装 Linux 基础工具", "正在安装 curl、git 等基础工具。"),
        CONFIGURE_ENTRY_UBUNTU("entry_ubuntu", "configure-entry-ubuntu.sh", "设置启动方式", "正在配置默认进入 Ubuntu。"),
        INSTALL_NODE("install_node", "install-node.sh", "安装 Ubuntu Node.js 24 LTS", "正在 Ubuntu AI 工作台层安装或检查 Node.js 24 LTS，供 AionUI、OpenHouseAI 工作台和 AI CLI 工具使用。"),
        RUNTIME_COMPONENTS("runtime_components", "install-runtime-components.sh", "安装本机 Agent 运行栈", "正在先确保 Termux native service-manager 可用，再按 pi-agent、pi-web 顺序安装并逐个注册；Ubuntu 工作台仍属于后续准备运行环境。"),
        SYNC_OPENHOUSE_REGISTRY("sync_openhouse_registry", "sync-openhouse-registry.sh", "同步 OpenHouseAI 注册表", "正在把 Ubuntu mirror 同步到 Termux canonical，供 App、SmallPhone 和 AI 读取。"),
        START_SMALLPHONE("start_smallphone", "start-smallphone.sh", "启动本机 pi-agent", "正在通过 Termux native service-manager 先启动 pi-agent 和 pi-web，让主入口先可用；后续继续准备 Ubuntu 工作台。"),
        INSTALL_AIONUI("install_aionui", "install-aionui.sh", "安装 AI 工作台", "正在从 APK 内置离线包安装 AionUi 工作台，并检查本机入口。"),
        INSTALL_CODEX("install_codex", "install-codex.sh", "安装 AI 工具：Codex", "正在安装 Codex CLI。"),
        INSTALL_CLAUDE_CODE("install_claude_code", "install-claude-code.sh", "安装 AI 工具：Claude Code", "正在安装 Claude Code。"),
        INSTALL_CLAUDE_CODE_UI("install_claude_code_ui", "install-claude-code-ui.sh", "安装 AI 工具：ClaudeCodeUI", "正在安装 ClaudeCodeUI / CloudCLI，并固定端口 23083。");

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

}
