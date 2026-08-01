package com.wuxianpi.browser.host;

import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;

import java.util.ArrayDeque;
import java.util.Queue;

public final class BrowserHostDispatcher {
    private static final BrowserHostDispatcher INSTANCE = new BrowserHostDispatcher();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Queue<PendingDispatch> pending = new ArrayDeque<>();
    private BrowserHost host;
    private boolean delivering;

    private BrowserHostDispatcher() {}

    public static BrowserHostDispatcher getInstance() { return INSTANCE; }

    public void registerHost(@NonNull BrowserHost value) {
        mainHandler.post(() -> {
            host = value;
            drain();
        });
    }

    public void dispatch(@NonNull BrowserHostRequest request, @NonNull BrowserHost.Callback callback) {
        mainHandler.post(() -> {
            PendingDispatch item = new PendingDispatch(request, callback);
            pending.offer(item);
            mainHandler.postDelayed(() -> failIfUnavailable(item),
                Math.min(4_000L, Math.max(1L, request.timeoutMs)));
            drain();
        });
    }

    private void drain() {
        if (delivering || host == null || pending.isEmpty()) return;
        PendingDispatch item = pending.poll();
        if (item == null || item.completed) {
            drain();
            return;
        }
        BrowserHost current = host;
        if (!item.request.hostId.isEmpty()
            && !item.request.hostId.equals(current.describe().hostId)) {
            item.completed = true;
            item.callback.onResult(BrowserHostResponse.error(
                item.request.requestId, "host_not_found", "Requested browser host is not active"));
            drain();
            return;
        }
        delivering = true;
        current.dispatch(item.request, response -> mainHandler.post(() -> {
            if (!item.completed) {
                item.completed = true;
                item.callback.onResult(response);
            }
            delivering = false;
            drain();
        }));
    }

    private void failIfUnavailable(PendingDispatch item) {
        if (item.completed || host != null || !pending.remove(item)) return;
        item.completed = true;
        item.callback.onResult(BrowserHostResponse.error(
            item.request.requestId, "browser_handler_unavailable", "Browser host is not ready"));
    }

    public BrowserHost currentHost() { return host; }

    private static final class PendingDispatch {
        final BrowserHostRequest request;
        final BrowserHost.Callback callback;
        boolean completed;

        PendingDispatch(BrowserHostRequest request, BrowserHost.Callback callback) {
            this.request = request;
            this.callback = callback;
        }
    }
}
