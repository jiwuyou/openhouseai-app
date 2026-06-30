package com.termux.app.openhouse.onboarding;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.widget.Toast;

import com.termux.app.openhouse.OpenHouseInstallController;
import com.termux.app.openhouse.OpenHouseInstallState;
import com.termux.app.openhouse.OpenHouseStartupPermissionHelper;
import com.termux.app.openhouse.OpenHouseStatus;
import com.termux.app.openhouse.OpenHouseStatusRepository;
import com.termux.shared.logger.Logger;

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

    public boolean startOneClickInstall() {
        return installController.startOneClickInstall();
    }

    public void startOneClickInstall(ResultCallback callback) {
        executor.execute(() -> {
            RuntimeResult result;
            try {
                boolean started = installController.startOneClickInstall();
                OpenHouseInstallState current = installController.getState();
                boolean usable = !current.failed && (started || current.running || current.completed);
                String message;
                if (current.failed) {
                    message = current.detailText.isEmpty() ? "初始化启动失败，请进入详细进度查看日志。" : current.detailText;
                } else if (current.completed) {
                    message = "核心运行环境已安装完成。";
                } else if (current.running) {
                    message = started ? "安装已开始，请等待完成。" : "安装已经在运行。";
                } else {
                    message = "安装任务尚未启动，请进入详细进度查看状态。";
                }
                result = usable
                    ? RuntimeResult.success(message)
                    : RuntimeResult.failure(message);
            } catch (Exception e) {
                Logger.logStackTraceWithMessage(LOG_TAG, "Failed to start one-click install", e);
                result = RuntimeResult.failure("无法启动初始化任务：" + safeMessage(e));
            }
            RuntimeResult finalResult = result;
            post(() -> callback.onResult(finalResult));
        });
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

    private static String safeMessage(Throwable throwable) {
        if (throwable == null || throwable.getMessage() == null || throwable.getMessage().trim().isEmpty()) {
            return "未知错误";
        }
        return throwable.getMessage().trim();
    }

    public void openStartupPermissionSettings() {
        OpenHouseStartupPermissionHelper.openStartupPermissionSettings(activity);
    }

    public void confirmLaunch() {
        statusRepository.markLaunchConfirmed(true);
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
