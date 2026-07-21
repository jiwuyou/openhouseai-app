package com.termux.app.operit;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.widget.Toast;

import com.ai.assistance.operit.host.control.OperitControlProtocol;
import com.ai.assistance.operit.host.control.OperitControlStateSnapshot;
import com.ai.assistance.operit.host.control.OperitControlStateStore;
import com.ai.assistance.operit.host.control.OperitProcessState;
import com.ai.assistance.operit.launcher.OperitModeLauncher;

/**
 * Compatibility shim for the existing OpenHouse desktop entry.
 *
 * <p>This keeps the established desktop wiring stable while routing both APK hosts to the shared
 * Operit Basic activity in hosted mode. PiChatEngine owns the loopback Pi Runtime connection.</p>
 */
public final class OperitHomeIntegration {

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
        try {
            activity.startActivity(OperitModeLauncher.createBasicIntent(
                activity, "com.termux.app.activities.OpenHouseHomeActivity"));
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
        OperitControlStateSnapshot snapshot = OperitControlStateStore.read(context);
        OperitProcessState state = snapshot.getEffectiveState();
        if (state == OperitProcessState.FOREGROUND) return DisplayState.FOREGROUND;
        if (state == OperitProcessState.BACKGROUND) return DisplayState.BACKGROUND;
        if (state == OperitProcessState.STOPPING) return DisplayState.STOPPING;
        return DisplayState.NOT_RUNNING;
    }

    public static boolean isBackground(Context context) {
        return readDisplayState(context) == DisplayState.BACKGROUND;
    }

    public static boolean requestShutdown(Context context) {
        if (context == null) return false;
        context.sendBroadcast(OperitControlProtocol.createShutdownIntent(context));
        return true;
    }

    public static boolean isOperitProcessAlive(Context context, int expectedPid) {
        OperitControlStateSnapshot snapshot = OperitControlStateStore.read(context);
        return snapshot.isRunning() && (expectedPid <= 0 || snapshot.getPid() == expectedPid);
    }

    private static String safeMessage(Throwable error) {
        String message = error.getMessage();
        return message == null || message.trim().isEmpty()
            ? error.getClass().getSimpleName()
            : message;
    }
}
