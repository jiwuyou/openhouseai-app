package com.termux.app.activities;

import android.content.Intent;
import android.os.Bundle;

import androidx.appcompat.app.AppCompatActivity;

import com.termux.R;
import com.termux.app.smallphone.SmallPhoneFirstLaunchGate;
import com.termux.app.smallphone.SmallPhoneHostController;

public final class SmallPhoneHostActivity extends AppCompatActivity {

    private SmallPhoneHostController controller;
    private boolean firstLaunchGateForwarded;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_smallphone_host);

        controller = new SmallPhoneHostController(this, findViewById(R.id.smallphoneHostRoot));
        if (routeFirstLaunchGateIfNeeded()) {
            return;
        }
        controller.refreshStatus(SmallPhoneHostController.shouldOpenWhenHealthy(getIntent()));
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (routeFirstLaunchGateIfNeeded()) {
            return;
        }
        if (controller != null) {
            controller.onResume(SmallPhoneHostController.shouldOpenWhenHealthy(getIntent()));
        }
    }

    @Override
    protected void onPause() {
        firstLaunchGateForwarded = false;
        if (controller != null) {
            controller.onPause();
        }
        super.onPause();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (controller != null) {
            controller.showRecovery(null);
        }
        if (routeFirstLaunchGateIfNeeded()) {
            return;
        }
        if (controller != null) {
            controller.refreshStatus(SmallPhoneHostController.shouldOpenWhenHealthy(intent));
        }
    }

    @Override
    protected void onDestroy() {
        if (controller != null) {
            controller.onDestroy();
        }
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (controller != null && controller.handleBackPressed()) {
            return;
        }
        super.onBackPressed();
    }

    private boolean routeFirstLaunchGateIfNeeded() {
        if (firstLaunchGateForwarded) {
            return true;
        }
        if (SmallPhoneFirstLaunchGate.launchIfNeeded(this)) {
            firstLaunchGateForwarded = true;
            return true;
        }
        return false;
    }
}
