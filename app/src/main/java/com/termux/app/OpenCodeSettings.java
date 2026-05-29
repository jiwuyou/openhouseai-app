package com.termux.app;

import android.content.Context;
import android.content.SharedPreferences;

public final class OpenCodeSettings {

    public static final int DEFAULT_OPENCODE_PORT = 4096;
    public static final String DEFAULT_PROJECT_DIRECTORY = "/root";
    private static final String DEFAULT_PROJECT_ROUTE = "L3Jvb3Q";
    private static final String PREFS_NAME = "opencode_settings";
    private static final String KEY_DEFAULT_PORT = "default_port";

    private OpenCodeSettings() {
    }

    public static int getDefaultPort(Context context) {
        SharedPreferences preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        int port = preferences.getInt(KEY_DEFAULT_PORT, DEFAULT_OPENCODE_PORT);
        return isValidPort(port) ? port : DEFAULT_OPENCODE_PORT;
    }

    public static void setDefaultPort(Context context, int port) {
        if (!isValidPort(port)) {
            throw new IllegalArgumentException("invalid port: " + port);
        }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_DEFAULT_PORT, port)
            .apply();
    }

    public static boolean isValidPort(int port) {
        return port >= 1 && port <= 65535;
    }

    public static String getLoopbackUrl(int port) {
        return "http://127.0.0.1:" + port;
    }

    public static String getRootProjectUrl(int port) {
        return getLoopbackUrl(port) + "/" + DEFAULT_PROJECT_ROUTE + "/session";
    }

    public static String getDefaultLoopbackUrl(Context context) {
        return getLoopbackUrl(getDefaultPort(context));
    }

    public static String getDefaultRootProjectUrl(Context context) {
        return getRootProjectUrl(getDefaultPort(context));
    }
}
