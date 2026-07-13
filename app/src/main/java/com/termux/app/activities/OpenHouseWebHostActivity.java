package com.termux.app.activities;

import android.content.Intent;
import android.os.Bundle;

import androidx.appcompat.app.AppCompatActivity;

import com.termux.R;
import com.termux.app.TermuxService;
import com.termux.app.openhouse.webhost.OpenHouseWebHostController;
import com.termux.app.smallphone.SmallPhoneFirstLaunchGate;
import com.termux.shared.logger.Logger;

public final class OpenHouseWebHostActivity extends AppCompatActivity {

    private static final String LOG_TAG = "OpenHouseWebHost";

    private OpenHouseWebHostController controller;
    private boolean firstLaunchGateForwarded;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_openhouse_web_host);
        startTermuxService();
        controller = new OpenHouseWebHostController(this, findViewById(R.id.openhouseWebHostRoot));
        if (!routeFirstLaunchGateIfNeeded()) {
            controller.refresh(true);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!routeFirstLaunchGateIfNeeded() && controller != null) {
            controller.onResume();
        }
    }

    @Override
    protected void onPause() {
        firstLaunchGateForwarded = false;
        if (controller != null) controller.onPause();
        super.onPause();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (!routeFirstLaunchGateIfNeeded() && controller != null) {
            controller.refresh(true);
        }
    }

    @Override
    protected void onDestroy() {
        if (controller != null) controller.onDestroy();
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (controller != null && controller.handleBackPressed()) return;
        super.onBackPressed();
    }

    private void startTermuxService() {
        try {
            startService(new Intent(this, TermuxService.class));
        } catch (Exception e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to start TermuxService", e);
        }
    }

    private boolean routeFirstLaunchGateIfNeeded() {
        if (firstLaunchGateForwarded) return true;
        if (SmallPhoneFirstLaunchGate.launchIfNeeded(this)) {
            firstLaunchGateForwarded = true;
            return true;
        }
        return false;
    }
}
