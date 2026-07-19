package com.termux.app.operit.init;

import android.content.Context;

/** Historical bootstrap hook retained for source compatibility after removing operit-host. */
public final class OperitHostBootstrap {

    private OperitHostBootstrap() {}

    public static void installHostBridge(Context context) {
        // Pi runs in the Termux-native runtime. The Android process no longer installs an agent host.
    }

    public static boolean isHostBridgeInstalled() {
        return false;
    }

    public static String getProcessName(Context context) {
        return context == null ? "" : context.getPackageName();
    }

    public static boolean isOperitProcess(Context context) {
        return false;
    }

    public static boolean isMainProcess(Context context) {
        return true;
    }
}
