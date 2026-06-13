package com.termux.app.smallphone;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;

import com.termux.app.OpenHouseAgreement;
import com.termux.app.TermuxActivity;
import com.termux.app.activities.OpenHouseAgreementActivity;
import com.termux.app.activities.OpenHouseOnboardingActivity;
import com.termux.app.activities.SmallPhoneHostActivity;
import com.termux.app.openhouse.OpenHouseOnboardingState;
import com.termux.app.openhouse.OpenHouseStatusRepository;
import com.termux.shared.activity.ActivityUtils;
import com.termux.shared.termux.TermuxConstants;

import java.io.File;

public final class SmallPhoneFirstLaunchGate {

    public static final String EXTRA_FIRST_LAUNCH_ONBOARDING =
        "com.termux.app.extra.OPENHOUSE_FIRST_LAUNCH_ONBOARDING";

    private SmallPhoneFirstLaunchGate() {}

    public static boolean launchIfNeeded(Activity activity) {
        Intent intent = getGateIntent(activity);
        if (intent == null) {
            return false;
        }

        ActivityUtils.startActivity(activity, intent);
        return true;
    }

    public static Intent getGateIntent(Context context) {
        if (!isBootstrapComplete()) {
            return newBootstrapIntent(context);
        }

        OpenHouseOnboardingState state = new OpenHouseStatusRepository(context).loadOnboardingState();
        if (state.isComplete()) {
            return null;
        }

        if (!OpenHouseAgreement.hasAcceptedCurrentVersion(context)) {
            return newAgreementIntent(context);
        }

        return newOnboardingIntent(context);
    }

    public static boolean isFirstLaunchSource(Intent intent) {
        return intent != null && intent.getBooleanExtra(EXTRA_FIRST_LAUNCH_ONBOARDING, false);
    }

    public static void markFirstLaunchSource(Intent intent) {
        if (intent != null) {
            intent.putExtra(EXTRA_FIRST_LAUNCH_ONBOARDING, true);
        }
    }

    public static Intent newSmallPhoneHostIntent(Context context) {
        Intent intent = new Intent(context, SmallPhoneHostActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return intent;
    }

    public static Intent newOnboardingIntent(Context context) {
        Intent intent = new Intent(context, OpenHouseOnboardingActivity.class);
        markFirstLaunchSource(intent);
        return intent;
    }

    private static boolean isBootstrapComplete() {
        File prefixDir = new File(TermuxConstants.TERMUX_PREFIX_DIR_PATH);
        File bash = new File(TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH, "bash");
        return prefixDir.isDirectory() && bash.isFile();
    }

    private static Intent newBootstrapIntent(Context context) {
        Intent intent = new Intent(context, TermuxActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        intent.putExtra(TermuxActivity.EXTRA_OPEN_INSTALL_GUIDE_AFTER_BOOTSTRAP, true);
        markFirstLaunchSource(intent);
        return intent;
    }

    private static Intent newAgreementIntent(Context context) {
        Intent intent = new Intent(context, OpenHouseAgreementActivity.class);
        intent.putExtra(OpenHouseAgreementActivity.EXTRA_OPEN_INSTALL_GUIDE_AFTER_ACCEPT, true);
        markFirstLaunchSource(intent);
        return intent;
    }
}
