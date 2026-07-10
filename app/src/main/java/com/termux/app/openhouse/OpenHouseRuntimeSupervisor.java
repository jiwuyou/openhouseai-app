package com.termux.app.openhouse;

import android.content.Context;
import android.os.SystemClock;

import com.termux.app.openhouse.servicecontrol.ServiceManagerActionResult;
import com.termux.app.openhouse.servicecontrol.ServiceManagerClient;
import com.termux.app.openhouse.servicecontrol.ServiceManagerControlClient;
import com.termux.app.openhouse.servicecontrol.ServiceManagerRedactor;
import com.termux.app.openhouse.servicecontrol.ServiceManagerResult;
import com.termux.app.openhouse.servicecontrol.ServiceManagerServiceStatus;
import com.termux.shared.logger.Logger;
import com.termux.shared.termux.TermuxConstants;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class OpenHouseRuntimeSupervisor {

    private static final String LOG_TAG = "OpenHouseRuntimeSupervisor";
    private static final String[] DEFAULT_LONG_RUNNING_SERVICES = new String[] {
        "smallphone-frontend-beta",
        "smallphone-core",
        "pi-agent",
        "pi-web",
        "cloudcli"
    };
    private static final long MIN_FOREGROUND_TICK_MS = 15_000L;
    private static final long MIN_SERVICE_CHECK_MS = 30_000L;
    private static final long MIN_REPAIR_INTERVAL_MS = 60_000L;

    private static final Object LOCK = new Object();
    private static long lastForegroundTickMs;
    private static long lastServiceCheckMs;
    private static long lastRepairMs;
    private static int consecutiveControlPlaneFailures;
    private static boolean runtimeStackStoppedForSession;
    private static boolean controlPlaneRepairInFlight;

    private final Context context;
    private final ServiceManagerControlClient controlClient;

    public OpenHouseRuntimeSupervisor(Context context) {
        this.context = context.getApplicationContext();
        this.controlClient = new ServiceManagerControlClient(this.context);
    }

    public MaintenanceReport runForegroundMaintenanceTick() {
        return maintainDefaultServices(false);
    }

    public MaintenanceReport ensureDefaultLongRunningServices() {
        clearRuntimeStackStoppedForSession(context);
        return maintainDefaultServices(true);
    }

    public MaintenanceReport recoverControlPlaneNow() {
        clearRuntimeStackStoppedForSession(context);
        StringBuilder message = new StringBuilder();
        appendLine(message, "正在通过受控维护入口修复 Termux native service-manager；此路径不依赖 service-manager HTTP API 已在线。");

        OpenHouseMaintainerRunner.Result repair = runControlPlaneRepair();
        boolean repairSuccess = repair.isSuccess();
        appendLine(message, repairSuccess
            ? "控制中枢修复命令已完成。"
            : "控制中枢修复失败，退出码 " + repair.exitCode + formatOutput(repair.output));

        ServiceManagerResult health = controlClient.healthCheck();
        if (!health.success) {
            int failureCount = recordControlPlaneFailure();
            appendLine(message, "修复后 Termux native service-manager 仍不可达：" + safeText(health.message));
            appendLine(message, "连续失败次数：" + failureCount);
            return MaintenanceReport.failure(
                message.toString(),
                false,
                true,
                repairSuccess,
                failureCount,
                true
            );
        }

        resetControlPlaneFailures();
        MaintenanceReport services = startDefaultServices(true, true);
        if (!services.success) {
            appendLine(message, "Termux native service-manager 已恢复；部分默认核心服务仍需要单独检查。");
        }
        appendLine(message, services.message);
        return new MaintenanceReport(
            true,
            false,
            true,
            true,
            repairSuccess,
            0,
            !services.success,
            services.startedCount,
            services.skippedCount,
            services.failedCount,
            message.toString(),
            services.defaultServiceIds
        );
    }

    public static boolean isExitAllRequested(Context context) {
        return isRuntimeStackStoppedForSession(context);
    }

    public static void setExitAllRequested(Context context, boolean requested) {
        setRuntimeStackStoppedForSession(context, requested);
    }

    public static void clearExitAllRequested(Context context) {
        clearRuntimeStackStoppedForSession(context);
    }

    public static boolean isRuntimeStackStoppedForSession(Context context) {
        synchronized (LOCK) {
            return runtimeStackStoppedForSession;
        }
    }

    public static void setRuntimeStackStoppedForSession(Context context, boolean stopped) {
        synchronized (LOCK) {
            runtimeStackStoppedForSession = stopped;
        }
    }

    public static void clearRuntimeStackStoppedForSession(Context context) {
        setRuntimeStackStoppedForSession(context, false);
    }

    private MaintenanceReport maintainDefaultServices(boolean userInitiated) {
        long now = SystemClock.elapsedRealtime();
        if (!userInitiated && !isForegroundMaintenanceEligible()) {
            return MaintenanceReport.skipped("核心运行栈尚未完成安装，前台保活本次跳过。");
        }
        if (!userInitiated && !OpenHouseRuntimePreferences.isServiceManagerKeepAliveEnabled(context)) {
            return MaintenanceReport.skipped("前台自动保活已在高级设置中关闭。");
        }
        if (!userInitiated && isRuntimeStackStoppedForSession(context)) {
            return MaintenanceReport.skipped("已停止运行栈；本次 App 进程内前台保活暂停，重新打开 App 进程或点击恢复默认核心服务后再拉起。");
        }
        if (!userInitiated && shouldSkipForegroundTick(now)) {
            return MaintenanceReport.skipped("前台保活已节流，跳过本次轻量检查。");
        }

        ServiceManagerResult health = controlClient.healthCheck();
        if (!health.success) {
            int failureCount = recordControlPlaneFailure();
            StringBuilder message = new StringBuilder();
            appendLine(message, "Termux native service-manager 暂不可达：" + safeText(health.message));
            appendLine(message, "连续失败次数：" + failureCount);
            boolean repairAttempted = false;
            boolean repairSuccess = false;
            if (shouldAttemptRepair(now)) {
                repairAttempted = true;
                OpenHouseMaintainerRunner.Result repair = runControlPlaneRepair();
                repairSuccess = repair.isSuccess();
                appendLine(message, repairSuccess
                    ? "已执行控制中枢轻量修复。"
                    : "控制中枢修复失败，退出码 " + repair.exitCode + formatOutput(repair.output));
                if (repairSuccess) {
                    health = controlClient.healthCheck();
                    if (health.success) {
                        resetControlPlaneFailures();
                        MaintenanceReport services = startDefaultServices(true, true);
                        return services.withControlPlane(true, repairAttempted, repairSuccess)
                            .withMessage(message + "\n" + services.message);
                    }
                }
            }
            return MaintenanceReport.failure(
                message.toString(),
                false,
                repairAttempted,
                repairSuccess,
                failureCount,
                failureCount >= 3
            );
        }

        resetControlPlaneFailures();
        boolean shouldCheckServices;
        synchronized (LOCK) {
            shouldCheckServices = userInitiated || now - lastServiceCheckMs >= MIN_SERVICE_CHECK_MS;
            if (shouldCheckServices) {
                lastServiceCheckMs = now;
            }
        }
        if (!shouldCheckServices) {
            return MaintenanceReport.success("Termux native service-manager 可达；默认长期服务检查已节流。", true, false, false);
        }
        return startDefaultServices(userInitiated, false).withControlPlane(true, false, false);
    }

    private MaintenanceReport startDefaultServices(boolean userInitiated, boolean afterRepair) {
        StringBuilder details = new StringBuilder();
        int startedCount = 0;
        int skippedCount = 0;
        int failedCount = 0;

        ServiceManagerActionResult groupStart = controlClient.runGroupAction("local-stack", "start");
        if (groupStart.success()) {
            appendLine(details, "local-stack：已提交启动。");
        } else if (userInitiated || afterRepair) {
            appendLine(details, "local-stack：启动失败或未注册，继续逐个检查。"
                + optionalMessage(groupStart.message()));
        }

        for (String serviceId : DEFAULT_LONG_RUNNING_SERVICES) {
            try {
                ServiceManagerServiceStatus status = controlClient.getStatus(serviceId);
                if (isRunningState(status.state())) {
                    skippedCount++;
                    appendLine(details, serviceId + "：已运行。");
                    continue;
                }
                ServiceManagerActionResult start = controlClient.runAction(serviceId, "start");
                if (start.success()) {
                    startedCount++;
                    appendLine(details, serviceId + "：已提交启动。");
                } else {
                    failedCount++;
                    appendLine(details, serviceId + "：启动失败。" + optionalMessage(start.message()));
                }
            } catch (Exception e) {
                failedCount++;
                Logger.logStackTraceWithMessage(LOG_TAG, "Failed to ensure default service: " + serviceId, e);
                appendLine(details, serviceId + "：状态检查失败。" + safeText(e.getMessage()));
            }
        }

        boolean success = failedCount == 0;
        String summary = "默认核心服务检查完成：启动 " + startedCount
            + "，已运行 " + skippedCount
            + "，失败 " + failedCount + "。";
        String message = details.length() == 0 ? summary : summary + "\n" + details;
        return new MaintenanceReport(
            success,
            false,
            true,
            false,
            false,
            0,
            false,
            startedCount,
            skippedCount,
            failedCount,
            message,
            Collections.unmodifiableList(copyDefaultServices())
        );
    }

    private OpenHouseMaintainerRunner.Result runControlPlaneRepair() {
        synchronized (LOCK) {
            if (controlPlaneRepairInFlight) {
                return new OpenHouseMaintainerRunner.Result(
                    OpenHouseMaintainerRunner.Action.REPAIR_CONTROL_PLANE,
                    125,
                    "控制中枢修复已在执行中，请稍后重试。"
                );
            }
            controlPlaneRepairInFlight = true;
            lastRepairMs = SystemClock.elapsedRealtime();
        }
        try {
            return new OpenHouseMaintainerRunner(context)
                .run(OpenHouseMaintainerRunner.Action.REPAIR_CONTROL_PLANE, 0, controlPlaneRepairEnvironment());
        } finally {
            synchronized (LOCK) {
                controlPlaneRepairInFlight = false;
            }
        }
    }

    private Map<String, String> controlPlaneRepairEnvironment() {
        Map<String, String> environment = new HashMap<>();
        String baseUrl = safeTrim(ServiceManagerClient.resolveConfiguredBaseUrl());
        if (!baseUrl.isEmpty()) {
            environment.put("SERVICE_MANAGER_URL", baseUrl);
        }
        String listenAddr = safeTrim(ServiceManagerClient.resolveConfiguredListenAddr());
        if (!listenAddr.isEmpty()) {
            environment.put("SMALLPHONEAI_SERVICE_MANAGER_BIND", listenAddr);
        }
        return environment;
    }

    private boolean isForegroundMaintenanceEligible() {
        File bash = new File(TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH, "bash");
        File serviceManagerConfig = new File(
            TermuxConstants.TERMUX_HOME_DIR_PATH,
            ".config/openhouseai/service-manager/config.json"
        );
        File serviceSpecsDir = new File(
            TermuxConstants.TERMUX_HOME_DIR_PATH,
            ".config/openhouseai/service-manager/services.d"
        );
        File componentsDir = new File(
            TermuxConstants.TERMUX_HOME_DIR_PATH,
            ".config/openhouseai/components.d"
        );
        return bash.isFile()
            && serviceManagerConfig.isFile()
            && hasJsonFile(serviceSpecsDir)
            && hasJsonFile(componentsDir);
    }

    private static boolean hasJsonFile(File dir) {
        File[] files = dir == null ? null : dir.listFiles((file, name) ->
            file.isFile() && name != null && name.endsWith(".json"));
        return files != null && files.length > 0;
    }

    private static boolean shouldSkipForegroundTick(long now) {
        synchronized (LOCK) {
            if (now - lastForegroundTickMs < MIN_FOREGROUND_TICK_MS) {
                return true;
            }
            lastForegroundTickMs = now;
            return false;
        }
    }

    private static boolean shouldAttemptRepair(long now) {
        synchronized (LOCK) {
            return lastRepairMs == 0L || now - lastRepairMs >= MIN_REPAIR_INTERVAL_MS;
        }
    }

    private static int recordControlPlaneFailure() {
        synchronized (LOCK) {
            consecutiveControlPlaneFailures++;
            return consecutiveControlPlaneFailures;
        }
    }

    private static void resetControlPlaneFailures() {
        synchronized (LOCK) {
            consecutiveControlPlaneFailures = 0;
        }
    }

    private static boolean isRunningState(String state) {
        String normalized = safeTrim(state).toLowerCase(Locale.US);
        return "running".equals(normalized)
            || "active".equals(normalized)
            || "up".equals(normalized)
            || "healthy".equals(normalized)
            || "ready".equals(normalized);
    }

    private static List<String> copyDefaultServices() {
        List<String> values = new ArrayList<>();
        Collections.addAll(values, DEFAULT_LONG_RUNNING_SERVICES);
        return values;
    }

    private static String optionalMessage(String message) {
        String clean = safeText(message);
        return clean.isEmpty() ? "" : " " + clean;
    }

    private static String formatOutput(String output) {
        String clean = safeText(output);
        return clean.isEmpty() ? "" : "\n" + clean;
    }

    private static void appendLine(StringBuilder builder, String line) {
        if (builder == null || line == null || line.isEmpty()) {
            return;
        }
        if (builder.length() > 0) {
            builder.append('\n');
        }
        builder.append(line);
    }

    private static String safeText(String value) {
        return ServiceManagerRedactor.redact(safeTrim(value));
    }

    private static String safeTrim(String value) {
        return value == null ? "" : value.trim();
    }

    public static final class MaintenanceReport {
        public final boolean success;
        public final boolean skipped;
        public final boolean controlPlaneReachable;
        public final boolean repairAttempted;
        public final boolean repairSuccess;
        public final int failureCount;
        public final boolean userActionRequired;
        public final int startedCount;
        public final int skippedCount;
        public final int failedCount;
        public final String message;
        public final List<String> defaultServiceIds;

        private MaintenanceReport(
            boolean success,
            boolean skipped,
            boolean controlPlaneReachable,
            boolean repairAttempted,
            boolean repairSuccess,
            int failureCount,
            boolean userActionRequired,
            int startedCount,
            int skippedCount,
            int failedCount,
            String message,
            List<String> defaultServiceIds
        ) {
            this.success = success;
            this.skipped = skipped;
            this.controlPlaneReachable = controlPlaneReachable;
            this.repairAttempted = repairAttempted;
            this.repairSuccess = repairSuccess;
            this.failureCount = failureCount;
            this.userActionRequired = userActionRequired;
            this.startedCount = startedCount;
            this.skippedCount = skippedCount;
            this.failedCount = failedCount;
            this.message = ServiceManagerRedactor.redact(message);
            this.defaultServiceIds = defaultServiceIds == null ? Collections.emptyList() : defaultServiceIds;
        }

        static MaintenanceReport skipped(String message) {
            return new MaintenanceReport(true, true, false, false, false, 0, false, 0, 0, 0, message, copyDefaultServices());
        }

        static MaintenanceReport success(String message, boolean controlPlaneReachable, boolean repairAttempted, boolean repairSuccess) {
            return new MaintenanceReport(true, false, controlPlaneReachable, repairAttempted, repairSuccess, 0, false, 0, 0, 0, message, copyDefaultServices());
        }

        static MaintenanceReport failure(
            String message,
            boolean controlPlaneReachable,
            boolean repairAttempted,
            boolean repairSuccess,
            int failureCount,
            boolean userActionRequired
        ) {
            return new MaintenanceReport(false, false, controlPlaneReachable, repairAttempted, repairSuccess, failureCount, userActionRequired, 0, 0, 1, message, copyDefaultServices());
        }

        MaintenanceReport withControlPlane(boolean reachable, boolean repairAttempted, boolean repairSuccess) {
            return new MaintenanceReport(success, skipped, reachable, repairAttempted, repairSuccess,
                failureCount, userActionRequired, startedCount, skippedCount, failedCount, message, defaultServiceIds);
        }

        MaintenanceReport withMessage(String message) {
            return new MaintenanceReport(success, skipped, controlPlaneReachable, repairAttempted, repairSuccess,
                failureCount, userActionRequired, startedCount, skippedCount, failedCount, message, defaultServiceIds);
        }
    }
}
