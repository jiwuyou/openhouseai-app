package com.termux.app.operit.init;

import android.content.Context;

public final class OperitHostBootstrap {

    private OperitHostBootstrap() {}

    public static void installHostBridge(Context context) {
    }

    public static boolean isHostBridgeInstalled() {
        return false;
    }

    public static String getProcessName(Context context) {
        return "";
    }

    public static boolean isOperitProcess(Context context) {
        return false;
    }

    public static boolean isMainProcess(Context context) {
        return true;
    }
}
