package com.openhouse.host.nativeapp;

import android.app.Application;

import com.wuxianpi.openhouse.feature.OpenHouseFeatureHost;
import com.wuxianpi.openhouse.feature.OpenHouseFeatureHostProvider;

/** Native APK application entry; Android creates one instance for every declared UI process. */
public final class NativeOpenHouseApplication extends Application implements OpenHouseFeatureHostProvider {
    private NativeProductHost productHost;

    @Override public void onCreate() {
        super.onCreate();
        productHost = new NativeProductHost(this);
        productHost.install();
    }

    @Override public OpenHouseFeatureHost openHouseFeatureHost() {
        if (productHost == null) {
            productHost = new NativeProductHost(this);
            productHost.install();
        }
        return productHost;
    }
}
