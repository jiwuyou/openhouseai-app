package com.termux.app.operit.init;

import android.content.Context;

import com.ai.assistance.operit.host.control.OperitControlProtocol;
import com.termux.shared.logger.Logger;
import com.termux.app.operit.runtime.SmallPhoneOperitHost;

public final class OperitHostBootstrap {

    private static final String LOG_TAG = "OperitHostBootstrap";

    private static volatile boolean hostBridgeInstalled;
    private static volatile String processName = "";

    private OperitHostBootstrap() {}

    public static void installHostBridge(Context context) {
        if (context == null) {
            Logger.logError(LOG_TAG, "Cannot install Operit host bridge without context.");
            return;
        }

        Context applicationContext = context.getApplicationContext();
        if (applicationContext == null) {
            applicationContext = context;
        }

        processName = OperitControlProtocol.resolveCurrentProcessName(applicationContext);

        if (hostBridgeInstalled) {
            return;
        }

        synchronized (OperitHostBootstrap.class) {
            if (hostBridgeInstalled) {
                return;
            }
            try {
                SmallPhoneOperitHost.installProviderOnly(applicationContext);
                hostBridgeInstalled = true;
                Logger.logInfo(LOG_TAG, "Installed Operit host bridge for process: " + processName);
            } catch (Throwable throwable) {
                Logger.logError(LOG_TAG, "Failed to install Operit host bridge: " + throwable.getMessage());
            }
        }
    }

    public static boolean isHostBridgeInstalled() {
        return hostBridgeInstalled;
    }

    public static String getProcessName(Context context) {
        if (processName == null || processName.trim().isEmpty()) {
            processName = context == null ? "" : OperitControlProtocol.resolveCurrentProcessName(context);
        }
        return processName;
    }

    public static boolean isOperitProcess(Context context) {
        if (context == null) {
            return false;
        }
        return OperitControlProtocol.isOperitProcessName(context.getPackageName(), getProcessName(context));
    }

    public static boolean isMainProcess(Context context) {
        if (context == null) {
            return false;
        }
        return OperitControlProtocol.isMainProcessName(context.getPackageName(), getProcessName(context));
    }
}
