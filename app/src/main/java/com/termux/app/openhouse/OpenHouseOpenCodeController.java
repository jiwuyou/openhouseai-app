package com.termux.app.openhouse;

import android.content.Context;

import com.termux.app.OpenCodeSettings;

public final class OpenHouseOpenCodeController {

    private static volatile OpenHouseOpenCodeController instance;

    private final Context context;
    private final OpenHouseMaintainerRunner maintainerRunner;
    private final OpenHouseStatusRepository statusRepository;

    public static OpenHouseOpenCodeController getInstance(Context context) {
        if (instance == null) {
            synchronized (OpenHouseOpenCodeController.class) {
                if (instance == null) {
                    instance = new OpenHouseOpenCodeController(context);
                }
            }
        }
        return instance;
    }

    public OpenHouseOpenCodeController(Context context) {
        this.context = context.getApplicationContext();
        this.maintainerRunner = new OpenHouseMaintainerRunner(this.context);
        this.statusRepository = new OpenHouseStatusRepository(this.context);
    }

    public OpenHouseMaintainerRunner.Result start() {
        OpenHouseMaintainerRunner.Result result = maintainerRunner.run(
            OpenHouseMaintainerRunner.Action.START,
            OpenCodeSettings.DEFAULT_OPENCODE_PORT);
        if (result.isSuccess()) {
            statusRepository.setCurrentOnboardingStep(OpenHouseOnboardingState.Step.OPENCODE_LAUNCH);
        }
        return result;
    }

    public OpenHouseMaintainerRunner.Result stop() {
        return maintainerRunner.run(
            OpenHouseMaintainerRunner.Action.STOP,
            OpenCodeSettings.DEFAULT_OPENCODE_PORT);
    }

    public OpenHouseMaintainerRunner.Result restart() {
        OpenHouseMaintainerRunner.Result result = maintainerRunner.run(
            OpenHouseMaintainerRunner.Action.RESTART,
            OpenCodeSettings.DEFAULT_OPENCODE_PORT);
        if (result.isSuccess()) {
            statusRepository.setCurrentOnboardingStep(OpenHouseOnboardingState.Step.OPENCODE_LAUNCH);
        }
        return result;
    }

    public OpenHouseOnboardingState confirmLaunch() {
        return statusRepository.markLaunchConfirmed(true);
    }

    public OpenHouseOnboardingState clearLaunchConfirmation() {
        return statusRepository.markLaunchConfirmed(false);
    }

    public OpenHouseOnboardingState getOnboardingState() {
        return statusRepository.loadOnboardingState();
    }

    public int getPort() {
        return OpenCodeSettings.DEFAULT_OPENCODE_PORT;
    }

    public String getProjectDirectory() {
        return OpenCodeSettings.DEFAULT_PROJECT_DIRECTORY;
    }

    public String getLoopbackUrl() {
        return OpenCodeSettings.getLoopbackUrl(OpenCodeSettings.DEFAULT_OPENCODE_PORT);
    }

    public String getRootProjectUrl() {
        return OpenCodeSettings.getRootProjectUrl(OpenCodeSettings.DEFAULT_OPENCODE_PORT);
    }
}
