package com.termux.app.activities;

import android.content.Intent;
import android.os.Bundle;
import android.view.ViewGroup;

import androidx.appcompat.app.AppCompatActivity;

import com.termux.R;
import com.termux.app.TermuxActivity;
import com.termux.app.openhouse.onboarding.OpenHouseOnboardingOverlay;
import com.termux.shared.activity.ActivityUtils;

public class OpenHouseOnboardingActivity extends AppCompatActivity {

    private OpenHouseOnboardingOverlay onboarding;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_openhouse_onboarding);

        ViewGroup container = findViewById(R.id.openhouse_onboarding_activity_container);
        onboarding = new OpenHouseOnboardingOverlay(this, container, new OpenHouseOnboardingOverlay.Callbacks() {
            @Override
            public void onOpenDetail() {
                ActivityUtils.startActivity(OpenHouseOnboardingActivity.this,
                    new Intent(OpenHouseOnboardingActivity.this, MaintenanceCenterActivity.class));
            }

            @Override
            public void onStartTerminalTutorial() {
                openTerminal(true);
            }

            @Override
            public void onEnterTerminal() {
                openTerminal(false);
            }
        });
        onboarding.attach();
        onboarding.revealFromMenu();
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
        openTerminal(false);
    }

    private void openTerminal(boolean teaching) {
        Intent intent = new Intent(this, TermuxActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        if (teaching) {
            intent.putExtra(TermuxActivity.EXTRA_OPENHOUSE_TERMINAL_TUTORIAL, true);
        }
        ActivityUtils.startActivity(this, intent);
        finish();
    }
}
