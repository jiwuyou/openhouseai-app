package com.wuxianpi.openhouse.feature;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.os.Bundle;

import com.wuxianpi.openhouse.core.ControlPlaneBridge;
import com.wuxianpi.openhouse.core.ControlPlaneStartCoordinator;

import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/** Keeps the Termux control plane available only while this Android process is foreground. */
public final class ControlPlaneForegroundSupervisor implements Application.ActivityLifecycleCallbacks {
    static final String HEALTH_URL = "http://127.0.0.1:20087/api/v1/health";
    static final long ONLINE_INTERVAL_MS = 15_000L;
    static final long[] VERIFY_DELAYS_MS = {1_000L, 2_000L, 4_000L, 8_000L};
    static final long[] FAILURE_BACKOFF_MS = {5_000L, 15_000L, 30_000L, 60_000L};

    private static final Object INSTANCE_LOCK = new Object();
    private static ControlPlaneForegroundSupervisor instance;

    private final ControlPlaneBridge bridge;
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "openhouse-control-plane-foreground");
        thread.setDaemon(true);
        return thread;
    });
    private final Object stateLock = new Object();
    private ScheduledFuture<?> scheduled;
    private int startedActivities;
    private int failureIndex;
    private boolean foreground;

    private ControlPlaneForegroundSupervisor(ControlPlaneBridge bridge) {
        this.bridge = bridge;
    }

    public static void register(Context context, ControlPlaneBridge bridge) {
        Context applicationContext = context == null ? null : context.getApplicationContext();
        if (!(applicationContext instanceof Application) || bridge == null) return;
        synchronized (INSTANCE_LOCK) {
            if (instance != null) return;
            instance = new ControlPlaneForegroundSupervisor(bridge);
            ((Application) applicationContext).registerActivityLifecycleCallbacks(instance);
        }
    }

    private void enterForeground() {
        synchronized (stateLock) {
            foreground = true;
            scheduleLocked(0L);
        }
    }

    private void leaveForeground() {
        synchronized (stateLock) {
            foreground = false;
            if (scheduled != null) scheduled.cancel(false);
            scheduled = null;
        }
    }

    private void schedule(long delayMs) {
        synchronized (stateLock) {
            scheduleLocked(delayMs);
        }
    }

    private void scheduleLocked(long delayMs) {
        if (!foreground) return;
        if (scheduled != null) scheduled.cancel(false);
        scheduled = executor.schedule(this::runCycle, Math.max(0L, delayMs), TimeUnit.MILLISECONDS);
    }

    private void runCycle() {
        if (!isForeground()) return;
        if (isHealthy()) {
            failureIndex = 0;
            schedule(ONLINE_INTERVAL_MS);
            return;
        }

        if (!isForeground()) return;
        ControlPlaneStartCoordinator.start(bridge, "foreground");
        for (long delay : VERIFY_DELAYS_MS) {
            if (!waitWhileForeground(delay)) return;
            if (isHealthy()) {
                failureIndex = 0;
                schedule(ONLINE_INTERVAL_MS);
                return;
            }
        }
        long retryDelay = FAILURE_BACKOFF_MS[Math.min(failureIndex, FAILURE_BACKOFF_MS.length - 1)];
        if (failureIndex < FAILURE_BACKOFF_MS.length - 1) failureIndex++;
        schedule(retryDelay);
    }

    private boolean waitWhileForeground(long delayMs) {
        long deadline = System.currentTimeMillis() + delayMs;
        while (isForeground()) {
            long remaining = deadline - System.currentTimeMillis();
            if (remaining <= 0L) return true;
            try {
                Thread.sleep(Math.min(remaining, 250L));
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    private boolean isForeground() {
        synchronized (stateLock) {
            return foreground;
        }
    }

    private boolean isHealthy() {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(HEALTH_URL).openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(1_200);
            connection.setReadTimeout(1_200);
            connection.setUseCaches(false);
            int status = connection.getResponseCode();
            return status >= 200 && status < 300;
        } catch (Exception ignored) {
            return false;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    @Override public void onActivityStarted(Activity activity) {
        boolean enter;
        synchronized (stateLock) {
            enter = startedActivities++ == 0;
        }
        if (enter) enterForeground();
    }

    @Override public void onActivityStopped(Activity activity) {
        boolean leave;
        synchronized (stateLock) {
            startedActivities = Math.max(0, startedActivities - 1);
            leave = startedActivities == 0;
        }
        if (leave) leaveForeground();
    }

    @Override public void onActivityCreated(Activity activity, Bundle state) {}
    @Override public void onActivityResumed(Activity activity) {}
    @Override public void onActivityPaused(Activity activity) {}
    @Override public void onActivitySaveInstanceState(Activity activity, Bundle state) {}
    @Override public void onActivityDestroyed(Activity activity) {}
}
