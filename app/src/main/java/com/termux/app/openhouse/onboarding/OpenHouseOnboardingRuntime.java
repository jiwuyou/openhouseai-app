package com.termux.app.openhouse.onboarding;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import com.termux.app.openhouse.OpenHouseBackgroundRunPermission;
import com.termux.app.openhouse.OpenHouseInstallController;
import com.termux.app.openhouse.OpenHouseInstallState;
import com.termux.app.openhouse.OpenHouseNetworkLine;
import com.termux.app.openhouse.OpenHouseNetworkLinePreferences;
import com.termux.app.openhouse.OpenHouseStandardLineProbe;
import com.termux.app.openhouse.OpenHouseStartupPermissionHelper;
import com.termux.app.openhouse.OpenHouseStatus;
import com.termux.app.openhouse.OpenHouseStatusRepository;
import com.termux.shared.logger.Logger;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class OpenHouseOnboardingRuntime {

    private static final String LOG_TAG = "OpenHouseOnboarding";

    private final Activity activity;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final OpenHouseInstallController installController;
    private final OpenHouseStatusRepository statusRepository;

    private volatile boolean destroyed;

    public interface StatusCallback {
        void onStatus(OpenHouseStatus status);
    }

    public interface ResultCallback {
        void onResult(RuntimeResult result);
    }

    public interface NetworkCheckCallback {
        void onResult(NetworkCheckResult result);
    }

    public OpenHouseOnboardingRuntime(Activity activity) {
        this.activity = activity;
        this.installController = OpenHouseInstallController.getInstance(activity);
        this.statusRepository = new OpenHouseStatusRepository(activity);
    }

    public OpenHouseInstallController getInstallController() {
        return installController;
    }

    public OpenHouseInstallState getInstallState() {
        return installController.getState();
    }

    public NetworkLine getNetworkLine() {
        return NetworkLine.fromOpenHouseLine(OpenHouseNetworkLinePreferences.getSelectedLine(activity));
    }

    public void setNetworkLine(NetworkLine line) {
        NetworkLine resolvedLine = line == null ? NetworkLine.CN : line;
        OpenHouseNetworkLinePreferences.setSelectedLine(activity, resolvedLine.toOpenHouseLine());
    }

    public boolean startOneClickInstall() {
        return installController.startOneClickInstall();
    }

    public void startOneClickInstall(ResultCallback callback) {
        executor.execute(() -> {
            RuntimeResult result;
            try {
                boolean started = installController.startOneClickInstall();
                result = buildStartResult(started, "安装已开始，请等待完成。");
            } catch (Exception e) {
                Logger.logStackTraceWithMessage(LOG_TAG, "Failed to start legacy install", e);
                result = RuntimeResult.failure("无法启动安装任务：" + safeMessage(e));
            }
            RuntimeResult finalResult = result;
            post(() -> callback.onResult(finalResult));
        });
    }

    public void startRuntimeEnvironmentInstall(ResultCallback callback) {
        executor.execute(() -> {
            RuntimeResult result;
            try {
                NetworkLine line = getNetworkLine();
                Boolean started = tryInvokeControllerBoolean(
                    new String[]{"startRuntimeEnvironmentInstall", "startRuntimeInstall", "startEnvironmentInstall"},
                    line.retryMode
                );
                if (started == null) {
                    result = RuntimeResult.failure("分步安装接口尚未接入，请稍后重试。");
                } else {
                    result = buildStartResult(started, "运行环境准备已开始。");
                }
            } catch (Exception e) {
                Logger.logStackTraceWithMessage(LOG_TAG, "Failed to start runtime environment install", e);
                result = RuntimeResult.failure("无法启动运行环境准备：" + safeMessage(e));
            }
            RuntimeResult finalResult = result;
            post(() -> callback.onResult(finalResult));
        });
    }

    public void startAiFeaturesInstall(ResultCallback callback) {
        executor.execute(() -> {
            RuntimeResult result;
            try {
                NetworkLine line = getNetworkLine();
                Boolean started = tryInvokeControllerBoolean(
                    new String[]{"startAiFeaturesInstall", "startAiInstall", "startFeatureInstall"},
                    line.retryMode
                );
                if (started == null) {
                    result = RuntimeResult.failure("分步安装接口尚未接入，请稍后重试。");
                } else {
                    result = buildStartResult(started, "AI 功能安装已开始。");
                }
            } catch (Exception e) {
                Logger.logStackTraceWithMessage(LOG_TAG, "Failed to start AI feature install", e);
                result = RuntimeResult.failure("无法启动 AI 功能安装：" + safeMessage(e));
            }
            RuntimeResult finalResult = result;
            post(() -> callback.onResult(finalResult));
        });
    }

    public void forceRestartOneClickInstall(ResultCallback callback) {
        executor.execute(() -> {
            boolean started = installController.forceRestartOneClickInstall();
            post(() -> callback.onResult(started
                ? RuntimeResult.success("已重新启动安装任务。")
                : RuntimeResult.failure("无法重新启动安装，请进入详细进度查看日志。")));
        });
    }

    public void forceRestartCurrentTask(ResultCallback callback) {
        executor.execute(() -> {
            RuntimeResult result;
            try {
                Boolean started = tryInvokeControllerBoolean(
                    new String[]{"forceRestartCurrentTask", "forceRestartCurrentInstallTask"},
                    getNetworkLine().retryMode
                );
                if (started == null) {
                    result = RuntimeResult.failure("当前步骤重试接口尚未接入，请稍后重试。");
                } else {
                    result = started
                        ? RuntimeResult.success("已重新启动当前步骤。")
                        : RuntimeResult.failure("无法重新启动当前步骤，请进入详细进度查看日志。");
                }
            } catch (Exception e) {
                Logger.logStackTraceWithMessage(LOG_TAG, "Failed to restart current install task", e);
                result = RuntimeResult.failure("无法重新启动当前步骤：" + safeMessage(e));
            }
            RuntimeResult finalResult = result;
            post(() -> callback.onResult(finalResult));
        });
    }

    public void checkStandardNetworkLine(NetworkCheckCallback callback) {
        executor.execute(() -> {
            NetworkCheckResult result;
            try (OpenHouseStandardLineProbe probe = new OpenHouseStandardLineProbe()) {
                result = fromStandardProbeResult(probe.runBlocking());
            } catch (Exception e) {
                Logger.logStackTraceWithMessage(LOG_TAG, "Failed to run standard line probe", e);
                result = NetworkCheckResult.notRecommended("标准线路检测失败。", defaultUncheckedCategories());
            }
            NetworkCheckResult finalResult = result;
            post(() -> callback.onResult(finalResult));
        });
    }

    public void refreshStatus(StatusCallback callback) {
        executor.execute(() -> {
            OpenHouseStatus status = statusRepository.loadStatus();
            post(() -> callback.onStatus(status));
        });
    }

    public boolean isRuntimeEnvironmentPrepared(OpenHouseStatus status) {
        Boolean helperResult = tryInvokeStatusBoolean(status, "isRuntimeEnvironmentPrepared", "isRuntimePrepared", "isEnvironmentPrepared");
        if (helperResult != null) {
            return helperResult;
        }
        return status != null
            && status.termuxReady
            && status.productPrepared
            && status.ubuntuInstalled
            && status.entryUbuntuConfigured;
    }

    public boolean isAiFeaturesReady(OpenHouseStatus status) {
        Boolean helperResult = tryInvokeStatusBoolean(status, "isAiFeaturesReady", "isAiReady", "isFirstUseReady");
        if (helperResult != null) {
            return helperResult;
        }
        return status != null && status.isAiFeaturesReady();
    }

    public boolean isBackgroundRunReady(OpenHouseStatus status) {
        return OpenHouseBackgroundRunPermission.isBackgroundRunReady(activity, status);
    }

    public boolean isBackgroundRunConfirmed() {
        return OpenHouseBackgroundRunPermission.isBackgroundRunConfirmed(activity);
    }

    public String getBackgroundRunStatusText(OpenHouseStatus status) {
        if (OpenHouseBackgroundRunPermission.isBatteryOptimizationIgnored(activity, status)) {
            return "后台运行权限已开启";
        }
        if (isBackgroundRunConfirmed()) {
            return "已确认后台运行设置";
        }
        return "请先允许后台运行";
    }

    public int getRuntimeEnvironmentProgressPercent(OpenHouseStatus status) {
        if (status == null) {
            return 0;
        }
        int done = 0;
        int total = 4;
        if (status.termuxReady) done++;
        if (status.productPrepared) done++;
        if (status.ubuntuInstalled) done++;
        if (status.entryUbuntuConfigured) done++;
        return Math.round((done * 100f) / total);
    }

    public int getAiFeaturesProgressPercent(OpenHouseStatus status) {
        if (isAiFeaturesReady(status)) {
            return 100;
        }
        if (status == null) {
            return 0;
        }
        int done = 0;
        int total = 11;
        if (status.termuxNodeInstalled) done++;
        if (status.ubuntuNodeInstalled) done++;
        if (status.officialDocsSynced) done++;
        if (status.serviceManagerInstalled) done++;
        if (status.piAgentInstalled) done++;
        if (status.piWebInstalled) done++;
        if (status.smallPhoneRuntimeInstalled) done++;
        if (status.aionUiInstalled) done++;
        if (status.registrySynced) done++;
        if (status.piWebReachable) done++;
        if (status.aionUiReachable) done++;
        return Math.round((done * 100f) / total);
    }

    public String getRuntimeEnvironmentProgressText(OpenHouseStatus status) {
        if (isRuntimeEnvironmentPrepared(status)) {
            return "运行环境已准备好";
        }
        if (status == null || !status.termuxReady || !status.productPrepared) {
            return "正在准备基础组件";
        }
        if (!status.ubuntuInstalled) {
            return "正在准备运行环境";
        }
        if (!status.entryUbuntuConfigured) {
            return "正在配置启动入口";
        }
        return "正在确认运行环境";
    }

    public String getAiFeaturesProgressText(OpenHouseStatus status) {
        if (isAiFeaturesReady(status)) {
            return "AI 功能已安装";
        }
        if (status == null) {
            return "正在安装 AI 功能";
        }
        if (!status.termuxNodeInstalled || !status.serviceManagerInstalled || !status.piAgentInstalled || !status.piWebInstalled) {
            return "正在安装 Termux Pi 运行栈";
        }
        if (!status.ubuntuNodeInstalled) {
            return "正在安装 Ubuntu Node.js 24 LTS 工作台运行时";
        }
        if (!status.aionUiInstalled) {
            return "正在安装本地 AI 页面";
        }
        if (!status.officialDocsSynced || !status.registrySynced || !status.smallPhoneRuntimeInstalled) {
            return "正在同步组件信息";
        }
        if (!status.piWebReachable || !status.aionUiReachable) {
            return "正在启动 AI 功能";
        }
        return "正在确认 AI 功能";
    }

    public InstallTask getInstallTask(OpenHouseInstallState state) {
        if (state == null) {
            return InstallTask.UNKNOWN;
        }
        String value = firstNonEmpty(
            getFieldOrGetterText(state, "taskScope"),
            getFieldOrGetterText(state, "scope"),
            getFieldOrGetterText(state, "installTask"),
            getFieldOrGetterText(state, "task"),
            state.currentStageSlug
        );
        return InstallTask.fromValue(value);
    }

    public void openBackgroundRunPermission() {
        OpenHouseBackgroundRunPermission.openBatteryOptimizationSettings(activity);
    }

    public void openBatteryOptimizationSettings() {
        openBackgroundRunPermission();
    }

    public void openStartupPermissionSettings() {
        OpenHouseStartupPermissionHelper.openStartupPermissionSettings(activity);
    }

    public void markBackgroundRunConfirmed() {
        OpenHouseBackgroundRunPermission.setBackgroundRunConfirmed(activity, true);
        Toast.makeText(activity, "已确认后台运行设置。", Toast.LENGTH_SHORT).show();
    }

    public void resetBackgroundRunConfirmation() {
        OpenHouseBackgroundRunPermission.resetBackgroundRunConfirmed(activity);
    }

    public void confirmLaunch() {
        statusRepository.markLaunchConfirmed(true);
    }

    public void confirmLaunch(OpenHouseStatus knownStatus) {
        statusRepository.markLaunchConfirmed(true, knownStatus);
    }

    public void destroy() {
        destroyed = true;
        executor.shutdownNow();
    }

    private RuntimeResult buildStartResult(boolean started, String startedMessage) {
        OpenHouseInstallState current = installController.getState();
        boolean usable = !current.failed && (started || current.running || current.completed);
        String message;
        if (current.failed) {
            message = "安装没有完成，请查看详细日志。";
        } else if (current.completed) {
            message = "当前步骤已完成。";
        } else if (current.running) {
            message = started ? startedMessage : "安装已经在运行。";
        } else {
            message = usable ? "安装控制器已检查状态。" : "安装任务尚未启动，请进入详细进度查看状态。";
        }
        return usable ? RuntimeResult.success(message) : RuntimeResult.failure(message);
    }

    private Boolean tryInvokeControllerBoolean(String[] methodNames, OpenHouseInstallState.RetryMode retryMode) throws Exception {
        for (String methodName : methodNames) {
            Method retryModeMethod = findMethod(installController.getClass(), methodName, OpenHouseInstallState.RetryMode.class);
            if (retryModeMethod != null) {
                return coerceBoolean(retryModeMethod.invoke(installController, retryMode));
            }
        }
        for (String methodName : methodNames) {
            Method noArgMethod = findMethod(installController.getClass(), methodName);
            if (noArgMethod != null) {
                return coerceBoolean(noArgMethod.invoke(installController));
            }
        }
        return null;
    }

    private Method findMethod(Class<?> type, String name, Class<?>... parameters) {
        try {
            return type.getMethod(name, parameters);
        } catch (NoSuchMethodException e) {
            return null;
        }
    }

    private Boolean coerceBoolean(Object result) {
        if (result instanceof Boolean) {
            return (Boolean) result;
        }
        if (result instanceof OpenHouseInstallState) {
            OpenHouseInstallState state = (OpenHouseInstallState) result;
            return !state.failed && (state.running || state.completed);
        }
        if (result == null) {
            return true;
        }
        return null;
    }

    private Boolean tryInvokeStatusBoolean(OpenHouseStatus status, String... methodNames) {
        if (status == null) {
            return null;
        }
        for (String methodName : methodNames) {
            Method method = findMethod(status.getClass(), methodName);
            if (method == null) {
                continue;
            }
            try {
                Object result = method.invoke(status);
                if (result instanceof Boolean) {
                    return (Boolean) result;
                }
            } catch (Exception e) {
                Logger.logStackTraceWithMessage(LOG_TAG, "Failed to invoke status helper", e);
                return null;
            }
        }
        return null;
    }

    private NetworkCheckResult fromStandardProbeResult(OpenHouseStandardLineProbe.Result result) {
        if (result == null) {
            return NetworkCheckResult.notRecommended("标准线路检测失败。", defaultUncheckedCategories());
        }
        List<NetworkCheckCategory> categories = new ArrayList<>();
        for (OpenHouseStandardLineProbe.CategoryResult categoryResult : result.getCategoryResults()) {
            boolean ok = categoryResult.isSuccessful()
                && !categoryResult.isTimedOut()
                && !categoryResult.isTooSlow()
                && !categoryResult.isCancelled();
            categories.add(new NetworkCheckCategory(
                categoryResult.getLabel(),
                ok,
                categoryResult.getStatusLabel()
            ));
        }
        return result.isRecommended()
            ? NetworkCheckResult.recommended(result.getMessage(), categories)
            : NetworkCheckResult.notRecommended(result.getMessage(), categories);
    }

    private String getFieldOrGetterText(Object object, String name) {
        if (object == null) {
            return "";
        }
        try {
            Field field = object.getClass().getField(name);
            Object value = field.get(object);
            return value == null ? "" : String.valueOf(value);
        } catch (Exception ignored) {
        }
        try {
            Method method = findMethod(object.getClass(), getterFor(name));
            if (method != null) {
                Object value = method.invoke(object);
                return value == null ? "" : String.valueOf(value);
            }
        } catch (Exception ignored) {
        }
        return "";
    }

    private static String getterFor(String fieldName) {
        if (fieldName == null || fieldName.isEmpty()) {
            return "";
        }
        return "get" + Character.toUpperCase(fieldName.charAt(0)) + fieldName.substring(1);
    }

    private static String firstNonEmpty(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (value != null && !value.trim().isEmpty()) {
                return value.trim();
            }
        }
        return "";
    }

    private static List<NetworkCheckCategory> defaultUncheckedCategories() {
        List<NetworkCheckCategory> categories = new ArrayList<>();
        categories.add(new NetworkCheckCategory("网页连通性", false, "未完成检测"));
        categories.add(new NetworkCheckCategory("代码下载源", false, "未完成检测"));
        categories.add(new NetworkCheckCategory("AI 工具源", false, "未完成检测"));
        categories.add(new NetworkCheckCategory("系统组件源", false, "未完成检测"));
        categories.add(new NetworkCheckCategory("OpenHouse 下载源", false, "未完成检测"));
        return categories;
    }

    private static String safeMessage(Throwable throwable) {
        String message = throwable == null || throwable.getMessage() == null
            ? "未知错误"
            : throwable.getMessage().trim();
        if (message.isEmpty()) {
            return "未知错误";
        }
        String redacted = message.replaceAll("(?i)\\b(api[_-]?key|authorization|bearer|token|password)([=:\"' ]+)([^\\s\"']{8,})", "$1$2***");
        return redacted.replaceAll("\\bsk-[A-Za-z0-9_-]{12,}\\b", "sk-***");
    }

    private void post(Runnable runnable) {
        if (!destroyed) {
            mainHandler.post(runnable);
        }
    }

    public enum NetworkLine {
        CN("cn", "国内加速", OpenHouseInstallState.RetryMode.CN),
        STANDARD("standard", "标准线路", OpenHouseInstallState.RetryMode.GENERAL);

        public final String value;
        public final String label;
        public final OpenHouseInstallState.RetryMode retryMode;

        NetworkLine(String value, String label, OpenHouseInstallState.RetryMode retryMode) {
            this.value = value;
            this.label = label;
            this.retryMode = retryMode;
        }

        static NetworkLine fromValue(String value) {
            if (value == null) {
                return CN;
            }
            for (NetworkLine line : values()) {
                if (line.value.equalsIgnoreCase(value) || line.name().equalsIgnoreCase(value)) {
                    return line;
                }
            }
            return CN;
        }

        static NetworkLine fromOpenHouseLine(OpenHouseNetworkLine line) {
            return line == OpenHouseNetworkLine.STANDARD ? STANDARD : CN;
        }

        OpenHouseNetworkLine toOpenHouseLine() {
            return this == STANDARD ? OpenHouseNetworkLine.STANDARD : OpenHouseNetworkLine.CN;
        }
    }

    public enum InstallTask {
        RUNTIME_ENVIRONMENT,
        AI_FEATURES,
        UNKNOWN;

        static InstallTask fromValue(String value) {
            if (value == null) {
                return UNKNOWN;
            }
            String normalized = value.toLowerCase(Locale.US);
            if (normalized.contains("ai") || normalized.contains("feature")) {
                return AI_FEATURES;
            }
            if (normalized.contains("runtime") || normalized.contains("environment") || normalized.contains("entry")) {
                return RUNTIME_ENVIRONMENT;
            }
            return UNKNOWN;
        }
    }

    public static final class RuntimeResult {
        public final boolean success;
        public final String message;

        private RuntimeResult(boolean success, String message) {
            this.success = success;
            this.message = message == null ? "" : message;
        }

        static RuntimeResult success(String message) {
            return new RuntimeResult(true, message);
        }

        static RuntimeResult failure(String message) {
            return new RuntimeResult(false, message);
        }
    }

    public static final class NetworkCheckResult {
        public final boolean recommended;
        public final String message;
        public final List<NetworkCheckCategory> categories;

        private NetworkCheckResult(boolean recommended, String message, List<NetworkCheckCategory> categories) {
            this.recommended = recommended;
            this.message = message == null ? "" : message;
            this.categories = categories == null
                ? Collections.emptyList()
                : Collections.unmodifiableList(new ArrayList<>(categories));
        }

        static NetworkCheckResult recommended(String message, List<NetworkCheckCategory> categories) {
            return new NetworkCheckResult(true, message, categories);
        }

        static NetworkCheckResult notRecommended(String message, List<NetworkCheckCategory> categories) {
            return new NetworkCheckResult(false, message, categories);
        }

        public String toUserMessage(String fallback) {
            StringBuilder builder = new StringBuilder();
            builder.append(message.isEmpty() ? fallback : message);
            if (!categories.isEmpty()) {
                builder.append("\n\n");
                for (NetworkCheckCategory category : categories) {
                    builder.append(category.success ? "通过：" : "未通过：")
                        .append(category.label);
                    if (!category.detail.isEmpty()) {
                        builder.append("，").append(category.detail);
                    }
                    builder.append("\n");
                }
            }
            return builder.toString().trim();
        }
    }

    public static final class NetworkCheckCategory {
        public final String label;
        public final boolean success;
        public final String detail;

        public NetworkCheckCategory(String label, boolean success, String detail) {
            this.label = label == null ? "" : label;
            this.success = success;
            this.detail = detail == null ? "" : detail;
        }
    }
}
