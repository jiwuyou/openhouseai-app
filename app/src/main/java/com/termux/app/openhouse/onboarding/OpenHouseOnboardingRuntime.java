package com.termux.app.openhouse.onboarding;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.widget.Toast;

import com.termux.app.OpenCodeSettings;
import com.termux.app.openhouse.OpenHouseDeepSeekController;
import com.termux.app.openhouse.OpenHouseInstallController;
import com.termux.app.openhouse.OpenHouseInstallState;
import com.termux.app.openhouse.OpenHouseMaintainerRunner;
import com.termux.app.openhouse.OpenHouseOpenCodeController;
import com.termux.app.openhouse.OpenHouseStatus;
import com.termux.app.openhouse.OpenHouseStatusRepository;
import com.termux.shared.logger.Logger;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class OpenHouseOnboardingRuntime {

    private static final String LOG_TAG = "OpenHouseOnboarding";
    private static final String DEEPSEEK_API_KEYS_URL = "https://platform.deepseek.com/api_keys";

    private final Activity activity;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final OpenHouseInstallController installController;
    private final OpenHouseStatusRepository statusRepository;
    private final OpenHouseDeepSeekController deepSeekController;
    private final OpenHouseOpenCodeController openCodeController;

    private volatile boolean destroyed;

    public interface StatusCallback {
        void onStatus(OpenHouseStatus status);
    }

    public interface ResultCallback {
        void onResult(RuntimeResult result);
    }

    public OpenHouseOnboardingRuntime(Activity activity) {
        this.activity = activity;
        this.installController = OpenHouseInstallController.getInstance(activity);
        this.statusRepository = new OpenHouseStatusRepository(activity);
        this.deepSeekController = OpenHouseDeepSeekController.getInstance(activity);
        this.openCodeController = OpenHouseOpenCodeController.getInstance(activity);
    }

    public OpenHouseInstallController getInstallController() {
        return installController;
    }

    public OpenHouseInstallState getInstallState() {
        return installController.getState();
    }

    public boolean startOneClickInstall() {
        return installController.startOneClickInstall();
    }

    public void forceRestartOneClickInstall(ResultCallback callback) {
        executor.execute(() -> {
            boolean started = installController.forceRestartOneClickInstall();
            post(() -> callback.onResult(started
                ? RuntimeResult.success("已强制重启安装任务，会从第一个未完成阶段继续。")
                : RuntimeResult.failure("无法重新启动初始化，请进入详细进度查看日志。")));
        });
    }

    public void refreshStatus(StatusCallback callback) {
        executor.execute(() -> {
            OpenHouseStatus status = statusRepository.loadStatus();
            post(() -> callback.onStatus(status));
        });
    }

    public void saveDeepSeekKey(String apiKey, ResultCallback callback) {
        executor.execute(() -> {
            String normalized = apiKey == null ? "" : apiKey.replace("\r", "").replace("\n", "").trim();
            if (normalized.length() < 8) {
                post(() -> callback.onResult(RuntimeResult.failure("DeepSeek Key 至少需要 8 个字符。")));
                return;
            }

            OpenHouseDeepSeekController.SaveResult result = deepSeekController.saveKey(normalized);
            post(() -> callback.onResult(result.isSuccess()
                ? RuntimeResult.success("Key 已保存，安装完成后可继续配置。")
                : RuntimeResult.failure(result.message.isEmpty() ? "保存 Key 失败。" : result.message)));
        });
    }

    public boolean hasSavedDeepSeekKey() {
        return deepSeekController.hasSavedKey();
    }

    public void configureDeepSeek(ResultCallback callback) {
        executor.execute(() -> {
            if (!deepSeekController.hasSavedKey()) {
                post(() -> callback.onResult(RuntimeResult.failure("没有找到已保存的 DeepSeek Key，请重新填写。")));
                return;
            }

            OpenHouseMaintainerRunner.Result result = deepSeekController.configureSavedKey();
            post(() -> callback.onResult(result.isSuccess()
                ? RuntimeResult.success("DeepSeek 已配置到 OpenCode、Claude Code 和 Reasonix。")
                : RuntimeResult.failure(result.output.isEmpty() ? "DeepSeek 配置失败，请进入维护器查看详细进度。" : result.output)));
        });
    }

    public void runOpenCodeAction(OpenHouseMaintainerRunner.Action action, ResultCallback callback) {
        executor.execute(() -> {
            OpenHouseMaintainerRunner.Result result;
            switch (action) {
                case STOP:
                    result = openCodeController.stop();
                    break;
                case RESTART:
                    result = openCodeController.restart();
                    break;
                case START:
                default:
                    result = openCodeController.start();
                    break;
            }
            String message = result.isSuccess()
                ? openCodeSuccessMessage(action)
                : result.output.isEmpty() ? "OpenCode 操作失败，请进入维护器查看详细进度。" : result.output;
            post(() -> callback.onResult(result.isSuccess()
                ? RuntimeResult.success(message)
                : RuntimeResult.failure(message)));
        });
    }

    public void openBatteryOptimizationSettings() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            Toast.makeText(activity, "当前 Android 版本无需单独设置电池优化。", Toast.LENGTH_SHORT).show();
            return;
        }

        try {
            Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
            intent.setData(Uri.parse("package:" + activity.getPackageName()));
            activity.startActivity(intent);
        } catch (ActivityNotFoundException | SecurityException e) {
            try {
                activity.startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
                Toast.makeText(activity, "已打开电池优化设置，请找到 openhouse 并允许忽略优化。", Toast.LENGTH_LONG).show();
            } catch (Exception fallbackError) {
                Logger.logStackTraceWithMessage(LOG_TAG, "Failed to open battery optimization settings", fallbackError);
                Toast.makeText(activity, "无法打开电池优化设置。", Toast.LENGTH_SHORT).show();
            }
        }
    }

    public void openDeepSeekKeyPage() {
        try {
            activity.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(DEEPSEEK_API_KEYS_URL)));
        } catch (Exception e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to open DeepSeek API key page", e);
            Toast.makeText(activity, "无法打开 DeepSeek 页面，请复制网址到浏览器。", Toast.LENGTH_SHORT).show();
        }
    }

    public void copyDeepSeekKeyPageUrl() {
        copyText("DeepSeek API Keys", DEEPSEEK_API_KEYS_URL, "DeepSeek API Keys 网址已复制。");
    }

    public void copyOpenCodeAddress() {
        copyText("OpenCode", openCodeController.getRootProjectUrl(), "OpenCode 地址已复制。");
    }

    public void openOpenCodeInBrowser() {
        String url = openCodeController.getRootProjectUrl();
        try {
            activity.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Exception e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to open OpenCode URL", e);
            copyText("OpenCode", url, "无法打开浏览器，OpenCode 地址已复制。");
        }
    }

    public void confirmLaunch() {
        openCodeController.confirmLaunch();
    }

    private void copyText(String label, String text, String successMessage) {
        ClipboardManager clipboard = (ClipboardManager) activity.getSystemService(android.content.Context.CLIPBOARD_SERVICE);
        if (clipboard == null) {
            Toast.makeText(activity, "无法访问剪贴板。", Toast.LENGTH_SHORT).show();
            return;
        }

        clipboard.setPrimaryClip(ClipData.newPlainText(label, text));
        Toast.makeText(activity, successMessage, Toast.LENGTH_SHORT).show();
    }

    public int getOpenCodePort() {
        return OpenCodeSettings.DEFAULT_OPENCODE_PORT;
    }

    public String getOpenCodeLoopbackUrl() {
        return openCodeController.getLoopbackUrl();
    }

    public void destroy() {
        destroyed = true;
        executor.shutdownNow();
    }

    private void post(Runnable runnable) {
        if (!destroyed) {
            mainHandler.post(runnable);
        }
    }

    private static String openCodeSuccessMessage(OpenHouseMaintainerRunner.Action action) {
        switch (action) {
            case START:
                return "OpenCode 已启动。";
            case RESTART:
                return "OpenCode 已重启。";
            case STOP:
                return "OpenCode 已停止。";
            default:
                return "OpenCode 操作完成。";
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

}
