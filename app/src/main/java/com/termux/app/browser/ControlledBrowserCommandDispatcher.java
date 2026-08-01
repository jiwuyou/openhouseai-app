package com.termux.app.browser;

import android.content.Context;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.wuxianpi.browser.host.BrowserHostDispatcher;
import com.wuxianpi.browser.host.BrowserHostRequest;

/** Compatibility adapter from the historical Bundle command surface to Browser Host v1. */
public final class ControlledBrowserCommandDispatcher {
    public interface CommandCallback {
        void onResult(@NonNull ControlledBrowserCommandResult result);
    }

    private static final ControlledBrowserCommandDispatcher INSTANCE =
        new ControlledBrowserCommandDispatcher();

    private ControlledBrowserCommandDispatcher() {}

    @NonNull
    public static ControlledBrowserCommandDispatcher getInstance() { return INSTANCE; }

    public void enqueue(@NonNull Context context, @Nullable Bundle command) {
        enqueue(context, command, result -> {});
    }

    public void enqueue(
        @NonNull Context context,
        @Nullable Bundle command,
        @NonNull CommandCallback callback
    ) {
        BrowserHostRequest request = BrowserHostRequest.fromLegacyBundle(command);
        BrowserHostDispatcher.getInstance().dispatch(
            request,
            response -> callback.onResult(response.toLegacyResult()));
    }
}
