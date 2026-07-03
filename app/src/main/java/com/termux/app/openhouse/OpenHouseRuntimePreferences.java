package com.termux.app.openhouse;

import android.content.Context;
import android.content.SharedPreferences;

public final class OpenHouseRuntimePreferences {

    public static final String PREFS_NAME = "openhouse_runtime_preferences";
    public static final String KEY_SERVICE_MANAGER_KEEP_ALIVE_ENABLED =
        "service_manager_keep_alive_enabled";

    private OpenHouseRuntimePreferences() {}

    public static boolean isServiceManagerKeepAliveEnabled(Context context) {
        if (context == null) {
            return true;
        }
        return prefs(context).getBoolean(KEY_SERVICE_MANAGER_KEEP_ALIVE_ENABLED, true);
    }

    public static void setServiceManagerKeepAliveEnabled(Context context, boolean enabled) {
        if (context == null) {
            return;
        }
        prefs(context).edit().putBoolean(KEY_SERVICE_MANAGER_KEEP_ALIVE_ENABLED, enabled).apply();
    }

    public static void resetServiceManagerKeepAliveEnabled(Context context) {
        if (context == null) {
            return;
        }
        prefs(context).edit().remove(KEY_SERVICE_MANAGER_KEEP_ALIVE_ENABLED).apply();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext()
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }
}
