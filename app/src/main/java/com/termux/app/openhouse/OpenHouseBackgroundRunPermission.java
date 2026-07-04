package com.termux.app.openhouse;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.PowerManager;
import android.provider.Settings;
import android.widget.Toast;

import com.termux.shared.logger.Logger;

public final class OpenHouseBackgroundRunPermission {

    public static final String PREFS_NAME = "openhouse_onboarding";
    public static final String KEY_BACKGROUND_RUN_CONFIRMED = "background_run_confirmed";

    private static final String LOG_TAG = "OpenHouseBackgroundRun";

    private OpenHouseBackgroundRunPermission() {
    }

    public static boolean isBackgroundRunReady(Context context, OpenHouseStatus status) {
        return isBatteryOptimizationIgnored(context, status) || isBackgroundRunConfirmed(context);
    }

    public static boolean isBatteryOptimizationIgnored(Context context, OpenHouseStatus status) {
        return status != null && status.batteryOptimizationIgnored
            || isBatteryOptimizationIgnored(context);
    }

    public static boolean isBatteryOptimizationIgnored(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return true;
        }
        Context appContext = appContext(context);
        if (appContext == null) {
            return false;
        }
        PowerManager powerManager = (PowerManager) appContext.getSystemService(Context.POWER_SERVICE);
        return powerManager != null && powerManager.isIgnoringBatteryOptimizations(appContext.getPackageName());
    }

    public static boolean isBackgroundRunConfirmed(Context context) {
        Context appContext = appContext(context);
        return appContext != null
            && prefs(appContext).getBoolean(KEY_BACKGROUND_RUN_CONFIRMED, false);
    }

    public static void setBackgroundRunConfirmed(Context context, boolean confirmed) {
        Context appContext = appContext(context);
        if (appContext == null) {
            return;
        }
        prefs(appContext).edit()
            .putBoolean(KEY_BACKGROUND_RUN_CONFIRMED, confirmed)
            .apply();
    }

    public static void resetBackgroundRunConfirmed(Context context) {
        Context appContext = appContext(context);
        if (appContext == null) {
            return;
        }
        prefs(appContext).edit()
            .remove(KEY_BACKGROUND_RUN_CONFIRMED)
            .apply();
    }

    public static boolean openBatteryOptimizationSettings(Activity activity) {
        if (activity == null) {
            return false;
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            Toast.makeText(activity, "当前系统无需额外设置后台运行。", Toast.LENGTH_SHORT).show();
            return true;
        }
        if (isBatteryOptimizationIgnored(activity)) {
            Toast.makeText(activity, "后台运行权限已开启。", Toast.LENGTH_SHORT).show();
            return true;
        }

        try {
            Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
            intent.setData(Uri.parse("package:" + activity.getPackageName()));
            activity.startActivity(intent);
            return true;
        } catch (ActivityNotFoundException | SecurityException e) {
            Logger.logDebug(LOG_TAG, "Battery optimization request unavailable: " + e.getMessage());
            return openBatteryOptimizationList(activity);
        } catch (Exception e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to open background run permission", e);
            return openBatteryOptimizationList(activity);
        }
    }

    private static boolean openBatteryOptimizationList(Activity activity) {
        try {
            activity.startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
            Toast.makeText(activity, "已打开系统设置，请允许后台运行后返回。", Toast.LENGTH_LONG).show();
            return true;
        } catch (Exception fallbackError) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to open battery optimization settings", fallbackError);
            Toast.makeText(activity, "无法打开后台运行设置，请在系统设置中手动允许。", Toast.LENGTH_LONG).show();
            return false;
        }
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    private static Context appContext(Context context) {
        return context == null ? null : context.getApplicationContext();
    }
}
