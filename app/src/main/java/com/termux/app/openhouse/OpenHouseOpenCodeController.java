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
        return start(OpenCodeSettings.DEFAULT_OPENCODE_PORT);
    }

    public OpenHouseMaintainerRunner.Result start(int port) {
        int sanitizedPort = requireValidPort(port);
        OpenHouseMaintainerRunner.Result result = maintainerRunner.run(
            OpenHouseMaintainerRunner.Action.START,
            sanitizedPort);
        if (result.isSuccess()) {
            statusRepository.setCurrentOnboardingStep(OpenHouseOnboardingState.Step.OPENCODE_LAUNCH);
        }
        return result;
    }

    public OpenHouseMaintainerRunner.Result stop() {
        return stop(OpenCodeSettings.DEFAULT_OPENCODE_PORT);
    }

    public OpenHouseMaintainerRunner.Result stop(int port) {
        return maintainerRunner.run(
            OpenHouseMaintainerRunner.Action.STOP,
            requireValidPort(port));
    }

    public OpenHouseMaintainerRunner.Result restart() {
        return restart(OpenCodeSettings.DEFAULT_OPENCODE_PORT);
    }

    public OpenHouseMaintainerRunner.Result restart(int port) {
        int sanitizedPort = requireValidPort(port);
        OpenHouseMaintainerRunner.Result result = maintainerRunner.run(
            OpenHouseMaintainerRunner.Action.RESTART,
            sanitizedPort);
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
        return getLoopbackUrl(OpenCodeSettings.DEFAULT_OPENCODE_PORT);
    }

    public String getLoopbackUrl(int port) {
        return OpenCodeSettings.getLoopbackUrl(requireValidPort(port));
    }

    public String getRootProjectUrl() {
        return getRootProjectUrl(OpenCodeSettings.DEFAULT_OPENCODE_PORT);
    }

    public String getRootProjectUrl(int port) {
        return OpenCodeSettings.getRootProjectUrl(requireValidPort(port));
    }

    private int requireValidPort(int port) {
        if (!OpenCodeSettings.isValidPort(port)) {
            throw new IllegalArgumentException("Invalid OpenCode port: " + port);
        }
        return port;
    }
}
