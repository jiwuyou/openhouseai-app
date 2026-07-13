package com.termux.app.openhouse.release;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;

import com.termux.app.openhouse.resources.OpenHouseBundledResourceDelivery;
import com.termux.shared.logger.Logger;
import com.termux.shared.termux.TermuxConstants;

import java.io.File;
import java.nio.file.Files;

public final class OpenHousePostUpdateSync {

    private static final String LOG_TAG = "OpenHousePostUpdateSync";
    private static final String PREFS_NAME = "openhouse_post_update_sync";
    private static final String KEY_SYNCED_VERSION_CODE = "synced_version_code";
    private static final String KEY_ATTEMPT_VERSION_CODE = "attempt_version_code";
    private static final String KEY_ATTEMPT_TIME_MS = "attempt_time_ms";
    private static final String KEY_PENDING_VERSION_CODE = "pending_version_code";
    private static final String KEY_PENDING_REASON = "pending_reason";
    private static final String KEY_FIRST_INSTALL_COMPLETED = "first_install_completed";
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
        if (isVersionSynced(preferences, currentVersionCode)) {
            clearInternalPendingWhenAiMarkerIsGone(preferences, currentVersionCode);
            return;
        }

        boolean packageWasUpdated = packageInfo.lastUpdateTime > packageInfo.firstInstallTime + 1000L;
        OpenHouseBundledResourceDelivery.Reason reason = pendingReason(
            preferences, currentVersionCode, syncedVersionCode, packageWasUpdated);
        if (!recordPending(preferences, currentVersionCode, reason)) {
            return;
        }
        if (reason == OpenHouseBundledResourceDelivery.Reason.APK_UPDATE
            && !isFirstInstallCompleted(preferences, syncedVersionCode)) {
            return;
        }
        if (!isTermuxHomeReady()) {
            return;
        }

        schedule(appContext, packageInfo, preferences, currentVersionCode, reason, true);
    }

    public static void onFirstInstallCompleted(Context context) {
        Context appContext = context.getApplicationContext();
        PackageInfo packageInfo;
        try {
            packageInfo = appContext.getPackageManager().getPackageInfo(appContext.getPackageName(), 0);
        } catch (PackageManager.NameNotFoundException e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Unable to read package info after first install", e);
            return;
        }
        long currentVersionCode = getVersionCode(packageInfo);
        if (currentVersionCode <= 0L) {
            return;
        }
        SharedPreferences preferences = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        if (!OpenHouseBundledResourceDelivery.isStagedPackage(packageInfo)) {
            Logger.logError(LOG_TAG,
                "First install completed without a verified staged resource directory for versionCode="
                    + currentVersionCode);
            return;
        }
        if (isVersionSynced(preferences, currentVersionCode)) {
            OpenHouseBundledResourceDelivery.clearPendingAiUpdateMarker(
                currentVersionCode, OpenHouseBundledResourceDelivery.Reason.FIRST_INSTALL);
            clearInternalPendingWhenAiMarkerIsGone(preferences, currentVersionCode);
            return;
        }
        completeFirstInstall(preferences, currentVersionCode,
            () -> OpenHouseBundledResourceDelivery.clearPendingAiUpdateMarker(
                currentVersionCode, OpenHouseBundledResourceDelivery.Reason.FIRST_INSTALL));
    }

    private static void schedule(Context appContext,
                                 PackageInfo packageInfo,
                                 SharedPreferences preferences,
                                 long currentVersionCode,
                                 OpenHouseBundledResourceDelivery.Reason reason,
                                 boolean throttle) {

        long now = System.currentTimeMillis();
        long attemptVersionCode = preferences.getLong(KEY_ATTEMPT_VERSION_CODE, 0L);
        long attemptTimeMs = preferences.getLong(KEY_ATTEMPT_TIME_MS, 0L);
        if (throttle && attemptVersionCode == currentVersionCode
            && now >= attemptTimeMs && now - attemptTimeMs < RETRY_INTERVAL_MS) {
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
                OpenHouseBundledResourceDelivery.Result result =
                    OpenHouseBundledResourceDelivery.deliver(appContext, packageInfo, reason);
                if (result.isSuccess()) {
                    if (finalizeSuccessfulDelivery(preferences, currentVersionCode, reason)) {
                        Logger.logInfo(LOG_TAG,
                            "APK resources delivered for versionCode=" + currentVersionCode);
                    } else {
                        Logger.logError(LOG_TAG,
                            "Unable to finalize delivered APK resources; pending state is retained");
                    }
                } else {
                    Logger.logError(LOG_TAG, "APK resource delivery failed: " + result.output);
                }
            } catch (Exception e) {
                Logger.logStackTraceWithMessage(LOG_TAG, "APK resource delivery failed", e);
            } finally {
                synchronized (LOCK) {
                    running = false;
                }
            }
        }, "OpenHousePostUpdateSync");
        thread.setDaemon(true);
        thread.start();
    }

    static OpenHouseBundledResourceDelivery.Reason pendingReason(
        SharedPreferences preferences,
        long currentVersionCode,
        long syncedVersionCode,
        boolean packageWasUpdated) {
        if (preferences.getLong(KEY_PENDING_VERSION_CODE, 0L) == currentVersionCode) {
            String value = preferences.getString(KEY_PENDING_REASON, "");
            if (OpenHouseBundledResourceDelivery.Reason.FIRST_INSTALL.name().equals(value)) {
                return OpenHouseBundledResourceDelivery.Reason.FIRST_INSTALL;
            }
            if (OpenHouseBundledResourceDelivery.Reason.APK_UPDATE.name().equals(value)) {
                return OpenHouseBundledResourceDelivery.Reason.APK_UPDATE;
            }
        }
        return syncedVersionCode <= 0L && !packageWasUpdated
            ? OpenHouseBundledResourceDelivery.Reason.FIRST_INSTALL
            : OpenHouseBundledResourceDelivery.Reason.APK_UPDATE;
    }

    static boolean recordPending(SharedPreferences preferences,
                                 long currentVersionCode,
                                 OpenHouseBundledResourceDelivery.Reason reason) {
        if (preferences.getLong(KEY_PENDING_VERSION_CODE, 0L) == currentVersionCode
            && reason.name().equals(preferences.getString(KEY_PENDING_REASON, ""))) {
            return true;
        }
        return preferences.edit()
            .putLong(KEY_PENDING_VERSION_CODE, currentVersionCode)
            .putString(KEY_PENDING_REASON, reason.name())
            .commit();
    }

    private static void clearInternalPendingWhenAiMarkerIsGone(SharedPreferences preferences,
                                                                long currentVersionCode) {
        if (preferences.getLong(KEY_PENDING_VERSION_CODE, 0L) != currentVersionCode) {
            return;
        }
        File home = new File(TermuxConstants.TERMUX_HOME_DIR_PATH);
        if (!home.isDirectory() || Files.isSymbolicLink(home.toPath())) {
            return;
        }
        File marker = new File(home,
            OpenHouseBundledResourceDelivery.ROOT_RELATIVE_PATH + "/"
                + OpenHouseBundledResourceDelivery.PENDING_MARKER_NAME);
        clearInternalPendingWhenMarkerGone(preferences, currentVersionCode, true, marker.exists());
    }

    static void clearInternalPendingWhenMarkerGone(SharedPreferences preferences,
                                                     long currentVersionCode,
                                                     boolean homeReady,
                                                     boolean markerExists) {
        if (homeReady && !markerExists
            && preferences.getLong(KEY_PENDING_VERSION_CODE, 0L) == currentVersionCode) {
            preferences.edit().remove(KEY_PENDING_VERSION_CODE).remove(KEY_PENDING_REASON).commit();
        }
    }

    static boolean isVersionSynced(SharedPreferences preferences, long currentVersionCode) {
        return preferences.getLong(KEY_SYNCED_VERSION_CODE, 0L) == currentVersionCode;
    }

    static boolean isFirstInstallCompleted(SharedPreferences preferences, long syncedVersionCode) {
        return syncedVersionCode > 0L || preferences.getBoolean(KEY_FIRST_INSTALL_COMPLETED, false);
    }

    static boolean completeFirstInstall(SharedPreferences preferences,
                                        long currentVersionCode,
                                        MarkerClearer markerClearer) {
        if (!markerClearer.clear()) {
            return false;
        }
        return preferences.edit()
            .putBoolean(KEY_FIRST_INSTALL_COMPLETED, true)
            .putLong(KEY_SYNCED_VERSION_CODE, currentVersionCode)
            .remove(KEY_PENDING_VERSION_CODE)
            .remove(KEY_PENDING_REASON)
            .commit();
    }

    static boolean finalizeSuccessfulDelivery(SharedPreferences preferences,
                                              long currentVersionCode,
                                              OpenHouseBundledResourceDelivery.Reason reason) {
        SharedPreferences.Editor editor = preferences.edit()
            .putLong(KEY_ATTEMPT_VERSION_CODE, currentVersionCode)
            .putLong(KEY_ATTEMPT_TIME_MS, System.currentTimeMillis());
        if (reason == OpenHouseBundledResourceDelivery.Reason.APK_UPDATE) {
            editor.putLong(KEY_SYNCED_VERSION_CODE, currentVersionCode);
        }
        return editor.commit();
    }

    interface MarkerClearer {
        boolean clear();
    }

    private static boolean isTermuxHomeReady() {
        File home = new File(TermuxConstants.TERMUX_HOME_DIR_PATH);
        return home.isDirectory() && !Files.isSymbolicLink(home.toPath()) && home.canWrite();
    }

    private static long getVersionCode(PackageInfo packageInfo) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            return packageInfo.getLongVersionCode();
        }
        return packageInfo.versionCode;
    }
}
