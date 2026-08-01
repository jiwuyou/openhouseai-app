package com.wuxianpi.browser.host;

import androidx.annotation.NonNull;

public interface BrowserHost {
    interface Callback { void onResult(@NonNull BrowserHostResponse response); }
    interface EventListener { void onBrowserHostEvent(@NonNull BrowserHostEvent event); }

    @NonNull BrowserHostDescription describe();
    void dispatch(@NonNull BrowserHostRequest request, @NonNull Callback callback);
    void addEventListener(@NonNull EventListener listener);
    void removeEventListener(@NonNull EventListener listener);
}
