package com.openhouse.host.nativeapp;

import android.app.Application;

import com.ai.assistance.operit.host.OperitHostProvider;

/** Native APK application entry; Android creates one instance for every declared UI process. */
public final class NativeOpenHouseApplication extends Application {
    @Override public void onCreate() {
        super.onCreate();
        OperitHostProvider.INSTANCE.installOperations(new NativeOperitHostOperations(this));
    }
}
