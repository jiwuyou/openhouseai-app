package com.termux.app.browser;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;

import com.termux.shared.logger.Logger;
import com.wuxianpi.browser.host.BrowserHost;
import com.wuxianpi.browser.host.BrowserHostContract;
import com.wuxianpi.browser.host.BrowserHostDescription;
import com.wuxianpi.browser.host.BrowserHostDispatcher;
import com.wuxianpi.browser.host.BrowserHostEvent;
import com.wuxianpi.browser.host.BrowserHostRequest;
import com.wuxianpi.browser.host.BrowserHostResponse;

import org.json.JSONObject;

import java.util.concurrent.CopyOnWriteArrayList;

/** Process-local browser execution engine shared by every transport adapter. */
public final class ControlledBrowserRuntime implements BrowserHost {
    private static final String LOG_TAG = "ControlledBrowserRuntime";
    private static final ControlledBrowserRuntime INSTANCE = new ControlledBrowserRuntime();

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final CopyOnWriteArrayList<EventListener> listeners = new CopyOnWriteArrayList<>();
    private ControlledBrowserView browserView;
    private BrowserHostDescription description = BrowserHostDescription.allInOneHost();
    private boolean hostRegistered;

    private ControlledBrowserRuntime() {}

    @NonNull
    public static ControlledBrowserRuntime getInstance() { return INSTANCE; }

    public void configureHost(@NonNull BrowserHostDescription value) {
        description = value;
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

    @Override @NonNull
    public BrowserHostDescription describe() { return description; }

    @Override
    public void dispatch(@NonNull BrowserHostRequest request, @NonNull Callback callback) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post(() -> dispatch(request, callback));
            return;
        }
        if (BrowserHostContract.HOST_DESCRIBE.equals(request.method)
            || BrowserHostContract.HOST_CAPABILITIES.equals(request.method)) {
            JSONObject result = description.toJson();
            if (browserView != null) {
                try { result.put("state", browserView.getStateSnapshot()); }
                catch (org.json.JSONException ignored) {}
            }
            callback.onResult(new BrowserHostResponse(request.requestId, true, result, null));
            return;
        }
        if (browserView == null) {
            callback.onResult(BrowserHostResponse.error(
                request.requestId, "browser_handler_unavailable", "Browser runtime is not started"));
            return;
        }
        browserView.handleCommandAsync(request.toLegacyBundle(), result ->
            callback.onResult(BrowserHostResponse.fromLegacy(result)));
    }

    @Override public void addEventListener(@NonNull EventListener listener) { listeners.addIfAbsent(listener); }
    @Override public void removeEventListener(@NonNull EventListener listener) { listeners.remove(listener); }

    private void ensureStartedOnMain(@NonNull Context appContext) {
        if (browserView == null) {
            browserView = new ControlledBrowserView(appContext);
            browserView.addBrowserEventListener((name, data) -> emit(new BrowserHostEvent(name, data)));
            Logger.logDebug(LOG_TAG, "created background browser runtime");
        }
        if (!hostRegistered) {
            BrowserHostDispatcher.getInstance().registerHost(this);
            hostRegistered = true;
            emit(new BrowserHostEvent("host.ready", description.toJson()));
            Logger.logDebug(LOG_TAG, "registered Browser Host v1 handler");
        }
    }

    private void emit(@NonNull BrowserHostEvent event) {
        for (EventListener listener : listeners) listener.onBrowserHostEvent(event);
    }
}
