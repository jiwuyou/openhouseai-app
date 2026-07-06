package com.termux.app.operit;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;

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
        return false;
    }

    public static boolean forwardExternalEntryIntentIfNeeded(Activity activity, Intent sourceIntent) {
        return false;
    }

    public static boolean openAiFriendHelp(Activity activity) {
        return false;
    }

    public static DisplayState readDisplayState(Context context) {
        return DisplayState.UNAVAILABLE;
    }

    public static boolean isBackground(Context context) {
        return false;
    }

    public static boolean requestShutdown(Context context) {
        return false;
    }

    public static boolean isOperitProcessAlive(Context context, int expectedPid) {
        return false;
    }
}
