package com.termux.app.activities;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;

import com.termux.R;
import com.termux.app.TermuxActivity;
import com.termux.app.openhouse.OpenHouseInstallController;
import com.termux.app.openhouse.OpenHouseInstallState;
import com.termux.app.openhouse.onboarding.OpenHouseOnboardingOverlay;
import com.termux.app.smallphone.SmallPhoneFirstLaunchGate;
import com.termux.shared.activity.ActivityUtils;

public class OpenHouseOnboardingActivity extends AppCompatActivity {

    private static final String ONBOARDING_PREFS_NAME = "openhouse_onboarding";
    private static final String KEY_CURRENT_STEP = "current_step";
    private static final int STEP_WAITING_INSTALL = 3;

    private OpenHouseOnboardingOverlay onboarding;
    private boolean returnToSmallPhoneHost;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_openhouse_onboarding);
        returnToSmallPhoneHost = SmallPhoneFirstLaunchGate.isFirstLaunchSource(getIntent());

        ViewGroup container = findViewById(R.id.openhouse_onboarding_activity_container);
        onboarding = new OpenHouseOnboardingOverlay(this, container, new OpenHouseOnboardingOverlay.Callbacks() {
            @Override
            public void onOpenDetail() {
                Intent intent = new Intent(OpenHouseOnboardingActivity.this, MaintenanceCenterActivity.class);
                intent.putExtra(MaintenanceCenterActivity.EXTRA_RETURN_TO_ONBOARDING, true);
                ActivityUtils.startActivity(OpenHouseOnboardingActivity.this, intent);
            }

            @Override
            public void onStartTerminalTutorial(boolean restartEntrySession) {
                openUsageTeaching();
            }

            @Override
            public void onEnterTerminal(boolean restartEntrySession) {
                if (returnToSmallPhoneHost) {
                    openSmallPhoneHost();
                    return;
                }
                openTerminal(false, restartEntrySession);
            }
        });
        onboarding.attach();
        onboarding.revealFromMenu();
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                handleBackNavigation();
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (onboarding != null) {
            onboarding.onResume();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (onboarding != null) {
            onboarding.destroy();
            onboarding = null;
        }
    }

    @Override
    public void onBackPressed() {
        handleBackNavigation();
    }

    private void handleBackNavigation() {
        if (shouldBlockBackDuringInstall()) {
            Toast.makeText(this, "安装中，请不要退出界面", Toast.LENGTH_SHORT).show();
            return;
        }
        if (returnToSmallPhoneHost) {
            openSmallPhoneHost();
            return;
        }
        openTerminal(false, false);
    }

    private boolean shouldBlockBackDuringInstall() {
        if (onboarding != null && onboarding.shouldBlockBackNavigation()) {
            return true;
        }

        OpenHouseInstallState installState = OpenHouseInstallController.getInstance(this).getState();
        if (installState.running) {
            return true;
        }

        SharedPreferences preferences = getSharedPreferences(ONBOARDING_PREFS_NAME, Context.MODE_PRIVATE);
        boolean waitingInstallStep = preferences.getInt(KEY_CURRENT_STEP, 0) == STEP_WAITING_INSTALL;
        return waitingInstallStep && !installState.completed && !installState.failed;
    }

    private void openSmallPhoneHost() {
        ActivityUtils.startActivity(this,
            SmallPhoneFirstLaunchGate.newSmallPhoneHostIntent(this));
        finish();
    }

    private void openUsageTeaching() {
        Intent intent = new Intent(this, OpenHouseHomeActivity.class);
        intent.putExtra(OpenHouseHomeActivity.EXTRA_OPENHOUSE_TUTORIAL,
            OpenHouseHomeActivity.TUTORIAL_OPENHOUSE_USAGE);
        ActivityUtils.startActivity(this, intent);
        finish();
    }

    private void openTerminal(boolean teaching, boolean restartEntrySession) {
        Intent intent = new Intent(this, TermuxActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        if (teaching) {
            intent.putExtra(TermuxActivity.EXTRA_OPENHOUSE_TERMINAL_TUTORIAL, true);
        }
        if (restartEntrySession) {
            intent.putExtra(TermuxActivity.EXTRA_RESTART_ENTRY_SESSION, true);
        }
        ActivityUtils.startActivity(this, intent);
        finish();
    }
}
