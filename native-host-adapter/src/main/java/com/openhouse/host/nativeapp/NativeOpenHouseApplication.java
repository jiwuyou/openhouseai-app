package com.openhouse.host.nativeapp;

import android.app.Application;
import android.content.Context;
import android.os.Build;
import android.webkit.WebView;

import com.wuxianpi.openhouse.feature.OpenHouseFeatureHost;
import com.wuxianpi.openhouse.feature.OpenHouseFeatureHostProvider;

/** Native APK application entry; Android creates one instance for every declared UI process. */
public final class NativeOpenHouseApplication extends Application implements OpenHouseFeatureHostProvider {
    private NativeProductHost productHost;

    @Override protected void attachBaseContext(Context base) {
        super.attachBaseContext(base);
        configureWebViewDataDirectory(base);
    }

    @Override public void onCreate() {
        super.onCreate();
        productHost = new NativeProductHost(this);
        productHost.install();
    }

    private static void configureWebViewDataDirectory(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return;

        String packageName = context.getPackageName();
        String processName = Application.getProcessName();
        if (processName == null || processName.equals(packageName)) return;

        int separator = processName.indexOf(':');
        String suffix = separator >= 0 ? processName.substring(separator + 1) : processName;
        suffix = suffix.replaceAll("[^A-Za-z0-9._-]", "_");
        if (!suffix.isEmpty()) WebView.setDataDirectorySuffix(suffix);
    }

    @Override public OpenHouseFeatureHost openHouseFeatureHost() {
        if (productHost == null) {
            productHost = new NativeProductHost(this);
            productHost.install();
        }
        return productHost;
    }
}
