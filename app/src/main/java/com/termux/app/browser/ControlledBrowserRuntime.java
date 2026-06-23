package com.termux.app.browser;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;

import com.termux.shared.logger.Logger;

public final class ControlledBrowserRuntime {

    private static final String LOG_TAG = "ControlledBrowserRuntime";
    private static final ControlledBrowserRuntime INSTANCE = new ControlledBrowserRuntime();

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private ControlledBrowserView browserView;
    private boolean handlerRegistered;

    private ControlledBrowserRuntime() {}

    @NonNull
    public static ControlledBrowserRuntime getInstance() {
        return INSTANCE;
    }

    public void ensureStarted(@NonNull Context context) {
        Context appContext = context.getApplicationContext();
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post(() -> ensureStartedOnMain(appContext));
            return;
        }
        ensureStartedOnMain(appContext);
    }

    @NonNull
    public ControlledBrowserView getOrCreateView(@NonNull Context context) {
        Context appContext = context.getApplicationContext();
        if (Looper.myLooper() != Looper.getMainLooper()) {
            throw new IllegalStateException("Controlled browser runtime must be accessed on the main thread");
        }
        ensureStartedOnMain(appContext);
        return browserView;
    }

    private void ensureStartedOnMain(@NonNull Context appContext) {
        if (browserView == null) {
            browserView = new ControlledBrowserView(appContext);
            Logger.logDebug(LOG_TAG, "created background browser runtime");
        }
        if (!handlerRegistered) {
            ControlledBrowserCommandDispatcher.getInstance().registerHandler(appContext, (command, callback) -> {
                ensureStartedOnMain(appContext);
                browserView.handleCommandAsync(command, callback::onResult);
            });
            handlerRegistered = true;
            Logger.logDebug(LOG_TAG, "registered background browser handler");
        }
    }
}
