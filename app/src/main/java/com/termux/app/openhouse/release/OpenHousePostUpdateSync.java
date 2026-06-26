package com.termux.app.openhouse.release;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;

import com.termux.app.openhouse.OpenHouseMaintainerRunner;
import com.termux.shared.logger.Logger;

public final class OpenHousePostUpdateSync {

    private static final String LOG_TAG = "OpenHousePostUpdateSync";
    private static final String PREFS_NAME = "openhouse_post_update_sync";
    private static final String KEY_SYNCED_VERSION_CODE = "synced_version_code";
    private static final String KEY_ATTEMPT_VERSION_CODE = "attempt_version_code";
    private static final String KEY_ATTEMPT_TIME_MS = "attempt_time_ms";
    private static final long RETRY_INTERVAL_MS = 10 * 60 * 1000L;

    private static final Object LOCK = new Object();
    private static boolean running;

    private OpenHousePostUpdateSync() {
    }

    public static void maybeRun(Context context) {
        Context appContext = context.getApplicationContext();
        PackageInfo packageInfo;
        try {
            packageInfo = appContext.getPackageManager().getPackageInfo(appContext.getPackageName(), 0);
        } catch (PackageManager.NameNotFoundException e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Unable to read package info for post-update sync", e);
            return;
        }

        long currentVersionCode = getVersionCode(packageInfo);
        if (currentVersionCode <= 0) {
            return;
        }

        SharedPreferences preferences = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        long syncedVersionCode = preferences.getLong(KEY_SYNCED_VERSION_CODE, 0L);
        if (syncedVersionCode == currentVersionCode) {
            return;
        }

        boolean packageWasUpdated = packageInfo.lastUpdateTime > packageInfo.firstInstallTime + 1000L;
        if (syncedVersionCode <= 0L && !packageWasUpdated) {
            preferences.edit().putLong(KEY_SYNCED_VERSION_CODE, currentVersionCode).apply();
            return;
        }

        long now = System.currentTimeMillis();
        long attemptVersionCode = preferences.getLong(KEY_ATTEMPT_VERSION_CODE, 0L);
        long attemptTimeMs = preferences.getLong(KEY_ATTEMPT_TIME_MS, 0L);
        if (attemptVersionCode == currentVersionCode && now - attemptTimeMs < RETRY_INTERVAL_MS) {
            return;
        }

        synchronized (LOCK) {
            if (running) {
                return;
            }
            running = true;
        }

        preferences.edit()
            .putLong(KEY_ATTEMPT_VERSION_CODE, currentVersionCode)
            .putLong(KEY_ATTEMPT_TIME_MS, now)
            .apply();

        Thread thread = new Thread(() -> {
            try {
                OpenHouseMaintainerRunner.Result result = new OpenHouseMaintainerRunner(appContext)
                    .run(OpenHouseMaintainerRunner.Action.POST_APK_UPDATE, 0);
                if (result.isSuccess()) {
                    preferences.edit()
                        .putLong(KEY_SYNCED_VERSION_CODE, currentVersionCode)
                        .putLong(KEY_ATTEMPT_VERSION_CODE, currentVersionCode)
                        .putLong(KEY_ATTEMPT_TIME_MS, System.currentTimeMillis())
                        .apply();
                    Logger.logInfo(LOG_TAG, "Post-APK update sync completed for versionCode=" + currentVersionCode);
                } else {
                    Logger.logError(LOG_TAG, "Post-APK update sync failed with exitCode="
                        + result.exitCode + ": " + result.output);
                }
            } finally {
                synchronized (LOCK) {
                    running = false;
                }
            }
        }, "OpenHousePostUpdateSync");
        thread.setDaemon(true);
        thread.start();
    }

    private static long getVersionCode(PackageInfo packageInfo) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            return packageInfo.getLongVersionCode();
        }
        return packageInfo.versionCode;
    }
}
