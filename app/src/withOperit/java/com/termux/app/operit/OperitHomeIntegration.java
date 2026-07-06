package com.termux.app.operit;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.widget.Toast;

import com.ai.assistance.operit.host.control.OperitControlProtocol;
import com.ai.assistance.operit.host.control.OperitControlStateSnapshot;
import com.ai.assistance.operit.host.control.OperitControlStateStore;
import com.ai.assistance.operit.host.control.OperitProcessState;
import com.termux.app.operit.runtime.SmallPhoneOperitHost;
import com.termux.shared.activity.ActivityUtils;
import com.termux.shared.logger.Logger;

import java.util.List;

public final class OperitHomeIntegration {

    private static final String LOG_TAG = "OperitHomeIntegration";
    private static final String MAIN_ACTIVITY_CLASS = "com.ai.assistance.operit.ui.main.MainActivity";
    private static final String EXTRA_HOSTED_MODE = "com.ai.assistance.operit.extra.HOSTED_MODE";
    private static final String EXTRA_HELP_MODE = "com.ai.assistance.operit.extra.HELP_MODE";
    private static final int FORWARD_GRANT_FLAGS =
        Intent.FLAG_GRANT_READ_URI_PERMISSION
            | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
            | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION;

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
        return true;
    }

    public static boolean forwardExternalEntryIntentIfNeeded(Activity activity, Intent sourceIntent) {
        if (activity == null || !isExternalOperitEntryIntent(sourceIntent)) {
            return false;
        }

        Intent targetIntent = createHostedOperitIntent(activity);
        targetIntent.setAction(sourceIntent.getAction());
        if (sourceIntent.getData() != null || sourceIntent.getType() != null) {
            targetIntent.setDataAndType(sourceIntent.getData(), sourceIntent.getType());
        }
        if (sourceIntent.getExtras() != null) {
            targetIntent.putExtras(sourceIntent.getExtras());
        }
        ClipData clipData = sourceIntent.getClipData();
        if (clipData != null) {
            targetIntent.setClipData(clipData);
        }
        targetIntent.addFlags(sourceIntent.getFlags() & FORWARD_GRANT_FLAGS);
        targetIntent.putExtra(EXTRA_HOSTED_MODE, true);
        return startHostedOperitActivity(activity, targetIntent);
    }

    public static boolean openAiFriendHelp(Activity activity) {
        if (activity == null) {
            return false;
        }
        Intent intent = createHostedOperitIntent(activity);
        intent.putExtra(EXTRA_HOSTED_MODE, true);
        intent.putExtra(EXTRA_HELP_MODE, true);
        return startHostedOperitActivity(activity, intent);
    }

    public static DisplayState readDisplayState(Context context) {
        if (context == null) {
            return DisplayState.NOT_RUNNING;
        }
        try {
            OperitControlStateSnapshot snapshot = OperitControlStateStore.read(context);
            OperitProcessState effectiveState = snapshot.getEffectiveState();
            if (isMissingLiveOperitProcess(context, snapshot, effectiveState)) {
                OperitControlStateStore.markStopped(context);
                effectiveState = OperitProcessState.NOT_RUNNING;
            }
            return toDisplayState(effectiveState);
        } catch (Throwable throwable) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to read Operit control state", throwable);
            return DisplayState.NOT_RUNNING;
        }
    }

    public static boolean isBackground(Context context) {
        if (context == null) {
            return false;
        }
        try {
            return OperitControlStateStore.read(context).isBackground();
        } catch (Throwable throwable) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to read Operit background state", throwable);
            return false;
        }
    }

    public static boolean requestShutdown(Context context) {
        if (context == null) {
            return false;
        }
        try {
            context.sendBroadcast(OperitControlProtocol.createShutdownIntent(context));
            return true;
        } catch (Throwable throwable) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to request Operit shutdown", throwable);
            return false;
        }
    }

    public static boolean isOperitProcessAlive(Context context, int expectedPid) {
        if (context == null) {
            return false;
        }
        ActivityManager activityManager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        if (activityManager == null) {
            return false;
        }
        List<ActivityManager.RunningAppProcessInfo> processes = activityManager.getRunningAppProcesses();
        if (processes == null) {
            return false;
        }
        String operitProcessName = OperitControlProtocol.operitProcessName(context.getPackageName());
        for (ActivityManager.RunningAppProcessInfo process : processes) {
            if (process == null || !operitProcessName.equals(process.processName)) {
                continue;
            }
            if (expectedPid <= 0 || process.pid == expectedPid) {
                return true;
            }
        }
        return false;
    }

    private static Intent createHostedOperitIntent(Activity activity) {
        Intent intent = new Intent();
        intent.setClassName(activity.getPackageName(), MAIN_ACTIVITY_CLASS);
        intent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return intent;
    }

    private static boolean startHostedOperitActivity(Activity activity, Intent intent) {
        SmallPhoneOperitHost.install(activity.getApplicationContext());
        try {
            com.termux.shared.errors.Error error = ActivityUtils.startActivity(activity, intent, true, false);
            if (error != null) {
                Logger.logError(LOG_TAG, "Failed to start Operit hosted entry activity: " + error.getMessage());
                Toast.makeText(activity, "AI朋友 Help 启动请求失败", Toast.LENGTH_LONG).show();
                return false;
            }
            return true;
        } catch (ActivityNotFoundException e) {
            Logger.logError(LOG_TAG, "Operit hosted entry activity is not available: " + e.getMessage());
            Toast.makeText(activity, "AI朋友 Help 启动请求失败", Toast.LENGTH_LONG).show();
            return false;
        } catch (Throwable throwable) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to start Operit hosted entry activity", throwable);
            Toast.makeText(activity, "AI朋友 Help 启动请求失败", Toast.LENGTH_LONG).show();
            return false;
        }
    }

    private static boolean isExternalOperitEntryIntent(Intent intent) {
        if (intent == null) {
            return false;
        }
        String action = intent.getAction();
        return Intent.ACTION_VIEW.equals(action)
            || Intent.ACTION_SEND.equals(action)
            || Intent.ACTION_SEND_MULTIPLE.equals(action);
    }

    private static boolean isMissingLiveOperitProcess(
        Context context,
        OperitControlStateSnapshot snapshot,
        OperitProcessState effectiveState
    ) {
        if (snapshot == null || !effectiveState.isRunningLike()) {
            return false;
        }
        if (snapshot.getPid() <= 0 && isBlank(snapshot.getProcessName())) {
            return false;
        }
        return !isOperitProcessAlive(context, snapshot.getPid());
    }

    private static DisplayState toDisplayState(OperitProcessState state) {
        if (state == OperitProcessState.FOREGROUND) {
            return DisplayState.FOREGROUND;
        }
        if (state == OperitProcessState.BACKGROUND) {
            return DisplayState.BACKGROUND;
        }
        if (state == OperitProcessState.STOPPING) {
            return DisplayState.STOPPING;
        }
        return DisplayState.NOT_RUNNING;
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
