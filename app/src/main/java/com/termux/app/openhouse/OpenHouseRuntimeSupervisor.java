package com.termux.app.openhouse;

import android.content.Context;
import android.os.SystemClock;

import com.termux.app.openhouse.servicecontrol.ServiceManagerClient;
import com.termux.app.openhouse.servicecontrol.ServiceManagerControlClient;
import com.termux.app.openhouse.servicecontrol.ServiceManagerRedactor;
import com.termux.app.openhouse.servicecontrol.ServiceManagerResult;
import com.termux.shared.termux.TermuxConstants;

import java.io.File;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class OpenHouseRuntimeSupervisor {

    private static final long MIN_FOREGROUND_TICK_MS = 15_000L;
    private static final long MIN_REPAIR_INTERVAL_MS = 60_000L;

    private static final Object LOCK = new Object();
    private static long lastForegroundTickMs;
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
        StringBuilder message = new StringBuilder();
        appendLine(message, "正在通过受控维护入口修复 Termux native service-manager。");

        OpenHouseMaintainerRunner.Result repair = runControlPlaneRepair();
        boolean repairSuccess = repair.isSuccess();
        appendLine(message, repairSuccess
            ? "控制中枢修复命令已完成。"
            : "控制中枢修复失败，退出码 " + repair.exitCode + formatOutput(repair.output));

        ServiceManagerResult health = controlClient.healthCheck();
        if (!repairSuccess) {
            int failureCount = recordControlPlaneFailure();
            if (health.success) {
                appendLine(message, "service-manager 健康接口可达，但 runit 常驻修复未成功；本次不报告控制中枢已恢复。");
            } else {
                appendLine(message, "修复后 service-manager 仍不可达：" + safeText(health.message));
            }
            appendLine(message, "连续失败次数：" + failureCount);
            return MaintenanceReport.failure(
                message.toString(),
                health.success,
                true,
                false,
                failureCount,
                true
            );
        }
        if (!health.success) {
            int failureCount = recordControlPlaneFailure();
            appendLine(message, "修复后 service-manager 仍不可达：" + safeText(health.message));
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
        appendLine(message, "Termux native service-manager 已恢复并通过健康检查。");
        return MaintenanceReport.success(message.toString(), true, true, repairSuccess);
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
                    ? "已执行控制中枢受控修复。"
                    : "控制中枢修复失败，退出码 " + repair.exitCode + formatOutput(repair.output));

                health = controlClient.healthCheck();
                if (health.success && repairSuccess) {
                    resetControlPlaneFailures();
                    appendLine(message, "Termux native service-manager 已恢复并通过健康检查。");
                    return MaintenanceReport.success(
                        message.toString(),
                        true,
                        true,
                        repairSuccess
                    );
                }
                if (health.success) {
                    appendLine(message, "service-manager 健康接口可达，但 runit 常驻修复未成功；本次不报告控制中枢已恢复。");
                    return MaintenanceReport.failure(
                        message.toString(),
                        true,
                        true,
                        false,
                        failureCount,
                        true
                    );
                }
                appendLine(message, "修复后 service-manager 仍不可达：" + safeText(health.message));
            } else {
                appendLine(message, "控制中枢修复距离上次执行不足 60 秒，本次跳过。");
            }
            return MaintenanceReport.failure(
                message.toString(),
                false,
                repairAttempted,
                repairSuccess,
                failureCount,
                true
            );
        }

        resetControlPlaneFailures();
        return MaintenanceReport.success(
            userInitiated
                ? "Termux native service-manager 可达；已交由常驻策略管理业务服务。"
                : "Termux native service-manager 可达；前台仅保活控制中枢。",
            true,
            false,
            false
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
            return new OpenHouseMaintainerRunner(context).run(
                OpenHouseMaintainerRunner.Action.REPAIR_CONTROL_PLANE,
                0,
                controlPlaneRepairEnvironment()
            );
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

    private static List<String> copyDefaultServices() {
        return Collections.emptyList();
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
