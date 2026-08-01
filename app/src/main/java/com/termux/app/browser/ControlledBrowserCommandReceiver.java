package com.termux.app.browser;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import com.termux.shared.logger.Logger;

public final class ControlledBrowserCommandReceiver extends BroadcastReceiver {

    private static final String LOG_TAG = "ControlledBrowserRx";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (context == null
            || intent == null
            || !ControlledBrowserContract.ACTION_CONTROLLED_BROWSER_COMMAND.equals(intent.getAction())) {
            return;
        }

        if (!ControlledBrowserRpcFiles.hasBrowserCommand(intent)) {
            return;
        }

        Logger.logDebug(LOG_TAG, "received controlled browser command");
        android.os.Bundle command = ControlledBrowserRpcFiles.normalizeCommand(context, intent);
        if (command == null) {
            Logger.logDebug(LOG_TAG, "command rejected before enqueue");
            return;
        }
        Logger.logDebug(LOG_TAG, "enqueue command="
            + safe(command.getString(ControlledBrowserContract.EXTRA_COMMAND))
            + " requestId=" + safe(command.getString(ControlledBrowserContract.EXTRA_REQUEST_ID)));
        ControlledBrowserRuntime.getInstance().ensureStarted(context);
        ControlledBrowserCommandDispatcher.getInstance().enqueue(
            context,
            command,
            result -> ControlledBrowserRpcFiles.writeResultIfRequested(context, command, result));
    }

    private String safe(String value) {
        return value == null || value.trim().isEmpty() ? "-" : value;
    }
}
