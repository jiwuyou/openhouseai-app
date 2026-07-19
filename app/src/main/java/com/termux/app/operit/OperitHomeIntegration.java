package com.termux.app.operit;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.widget.Toast;

import com.wuxianpi.ai.AiFeatureStatus;
import com.wuxianpi.ai.WuxianPiActivity;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * Compatibility shim for the existing OpenHouse desktop entry.
 *
 * <p>The old Operit process/agent host is no longer part of the production build. This class keeps
 * the established desktop wiring stable while routing it to the shared WuxianPi UI and the
 * Termux-native Pi Rust runtime.</p>
 */
public final class OperitHomeIntegration {

    private static final String RUNTIME_URL = "http://127.0.0.1:8765/";
    private static final String RUNTIME_TOKEN_PATH =
        "/data/data/com.termux/files/home/.local/share/openhouseai/runtime/state/token";

    public static final long SHUTDOWN_PENDING_UI_MS = 5_000L;
    public static final long LAUNCH_PENDING_UI_MS = 7_000L;
    public static final long LAUNCH_PROCESS_GRACE_MS = 1_500L;

    public enum DisplayState {
        UNAVAILABLE,
        NOT_RUNNING,
        STARTING,
        FOREGROUND,
        BACKGROUND,
        STOPPING
    }

    private OperitHomeIntegration() {}

    public static boolean isAvailable() {
        return true;
    }

    public static boolean forwardExternalEntryIntentIfNeeded(Activity activity, Intent sourceIntent) {
        if (activity == null || sourceIntent == null) return false;
        String action = sourceIntent.getAction();
        if (!Intent.ACTION_VIEW.equals(action)
            && !Intent.ACTION_SEND.equals(action)
            && !Intent.ACTION_SEND_MULTIPLE.equals(action)) {
            return false;
        }
        return openAiFriendHelp(activity);
    }

    public static boolean openAiFriendHelp(Activity activity) {
        if (activity == null) return false;
        String token = readRuntimeToken();
        if (token == null) {
            Toast.makeText(
                activity,
                "WuxianPi Runtime 尚未就绪，请先完成运行时安装或修复。",
                Toast.LENGTH_LONG
            ).show();
            return false;
        }
        try {
            activity.startActivity(WuxianPiActivity.createBundledIntent(
                activity,
                RUNTIME_URL,
                token,
                "wuxianpi-bundled"
            ));
            return true;
        } catch (Throwable error) {
            Toast.makeText(
                activity,
                "WuxianPi 启动失败：" + safeMessage(error),
                Toast.LENGTH_LONG
            ).show();
            return false;
        }
    }

    public static DisplayState readDisplayState(Context context) {
        return AiFeatureStatus.isVisible() ? DisplayState.FOREGROUND : DisplayState.NOT_RUNNING;
    }

    public static boolean isBackground(Context context) {
        return false;
    }

    public static boolean requestShutdown(Context context) {
        return false;
    }

    public static boolean isOperitProcessAlive(Context context, int expectedPid) {
        return AiFeatureStatus.isRunning();
    }

    private static String readRuntimeToken() {
        try {
            File file = new File(RUNTIME_TOKEN_PATH);
            if (!file.isFile()) return null;
            String token = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8).trim();
            return token.length() >= 24 ? token : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String safeMessage(Throwable error) {
        String message = error.getMessage();
        return message == null || message.trim().isEmpty()
            ? error.getClass().getSimpleName()
            : message;
    }
}
