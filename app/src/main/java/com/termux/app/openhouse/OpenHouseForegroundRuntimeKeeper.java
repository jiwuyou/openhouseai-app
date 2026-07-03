package com.termux.app.openhouse;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import com.termux.shared.logger.Logger;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class OpenHouseForegroundRuntimeKeeper implements Application.ActivityLifecycleCallbacks {

    private static final String LOG_TAG = "OpenHouseForegroundRuntime";
    private static final long FIRST_TICK_DELAY_MS = 1_500L;
    private static final long FOREGROUND_TICK_INTERVAL_MS = 30_000L;

    private static boolean registered;

    private final Application application;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final OpenHouseRuntimeSupervisor runtimeSupervisor;
    private final Runnable maintenanceRunnable = new Runnable() {
        @Override
        public void run() {
            runMaintenanceTick();
        }
    };

    private int startedActivityCount;
    private boolean foreground;
    private boolean maintenanceRunning;

    private OpenHouseForegroundRuntimeKeeper(Application application) {
        this.application = application;
        this.runtimeSupervisor = new OpenHouseRuntimeSupervisor(application);
    }

    public static synchronized void register(Application application) {
        if (application == null || registered) {
            return;
        }
        registered = true;
        application.registerActivityLifecycleCallbacks(new OpenHouseForegroundRuntimeKeeper(application));
    }

    @Override
    public void onActivityStarted(Activity activity) {
        startedActivityCount++;
        if (startedActivityCount == 1) {
            foreground = true;
            scheduleMaintenance(FIRST_TICK_DELAY_MS);
        }
    }

    @Override
    public void onActivityStopped(Activity activity) {
        startedActivityCount = Math.max(0, startedActivityCount - 1);
        if (startedActivityCount == 0) {
            foreground = false;
            mainHandler.removeCallbacks(maintenanceRunnable);
        }
    }

    @Override
    public void onActivityResumed(Activity activity) {
        if (foreground && !maintenanceRunning) {
            scheduleMaintenance(FIRST_TICK_DELAY_MS);
        }
    }

    @Override
    public void onActivityPaused(Activity activity) {
    }

    @Override
    public void onActivityCreated(Activity activity, Bundle savedInstanceState) {
    }

    @Override
    public void onActivitySaveInstanceState(Activity activity, Bundle outState) {
    }

    @Override
    public void onActivityDestroyed(Activity activity) {
    }

    private void runMaintenanceTick() {
        if (!foreground || maintenanceRunning) {
            return;
        }
        maintenanceRunning = true;
        executor.execute(() -> {
            try {
                OpenHouseRuntimeSupervisor.MaintenanceReport report =
                    runtimeSupervisor.runForegroundMaintenanceTick();
                if (report != null && report.userActionRequired) {
                    Logger.logWarn(LOG_TAG, report.message);
                }
            } catch (Exception e) {
                Logger.logStackTraceWithMessage(LOG_TAG, "Foreground runtime maintenance failed", e);
            } finally {
                mainHandler.post(() -> {
                    maintenanceRunning = false;
                    if (foreground) {
                        scheduleMaintenance(FOREGROUND_TICK_INTERVAL_MS);
                    }
                });
            }
        });
    }

    private void scheduleMaintenance(long delayMs) {
        mainHandler.removeCallbacks(maintenanceRunnable);
        mainHandler.postDelayed(maintenanceRunnable, Math.max(0L, delayMs));
    }
}
