package com.openhouse.host.nativeapp;

import android.app.Application;
import android.content.Context;
import android.os.Build;
import android.webkit.WebView;

import com.ai.assistance.operit.host.setup.OpenHouseConnectionBridgeForegroundSupervisor;
import com.wuxianpi.openhouse.feature.OpenHouseFeatureHost;
import com.wuxianpi.openhouse.feature.OpenHouseFeatureHostProvider;
import com.ai.assistance.operit.rescue.resources.ApkResourceOfferStore;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;

/** Native APK application entry; Android creates one instance for every declared UI process. */
public final class NativeOpenHouseApplication extends Application implements OpenHouseFeatureHostProvider {
    private NativeProductHost productHost;

    @Override protected void attachBaseContext(Context base) {
        super.attachBaseContext(base);
        configureWebViewDataDirectory(base);
    }

    @Override public void onCreate() {
        super.onCreate();
        OpenHouseConnectionBridgeForegroundSupervisor.install(this, currentProcessName());
        productHost = new NativeProductHost(this);
        productHost.install();
        ApkResourceOfferStore.recordCurrentApk(this);
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

    private static String currentProcessName() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            return Application.getProcessName();
        }
        try (BufferedReader reader = new BufferedReader(new FileReader("/proc/self/cmdline"))) {
            String value = reader.readLine();
            return value == null ? "" : value.replace('\0', ' ').trim();
        } catch (IOException ignored) {
            return "";
        }
    }

    @Override public OpenHouseFeatureHost openHouseFeatureHost() {
        if (productHost == null) {
            productHost = new NativeProductHost(this);
            productHost.install();
        }
        return productHost;
    }
}
