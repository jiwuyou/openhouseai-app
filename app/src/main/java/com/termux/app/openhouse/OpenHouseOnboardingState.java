package com.termux.app.openhouse;

public final class OpenHouseOnboardingState {

    public static final int TOTAL_STEPS = 4;

    public final Step step;
    public final int currentStep;
    public final String currentStepSlug;
    public final String currentStepLabel;
    public final boolean permissionsSkipped;
    public final boolean launchConfirmed;
    public final boolean oneClickInstallRunning;
    public final boolean oneClickInstallCompleted;
    public final boolean oneClickInstallFailed;
    public final int installPercent;
    public final String installPhaseLabel;
    public final String installDetailText;
    public final String installStageSlug;
    public final boolean launchAllowed;

    public OpenHouseOnboardingState(Step step,
                                    boolean permissionsSkipped,
                                    boolean launchConfirmed,
                                    OpenHouseInstallState installState,
                                    OpenHouseStatus status) {
        this.step = step == null ? Step.PERMISSIONS : step;
        this.currentStep = this.step.number;
        this.currentStepSlug = this.step.slug;
        this.currentStepLabel = this.step.label;
        this.permissionsSkipped = permissionsSkipped;
        this.launchConfirmed = launchConfirmed;

        OpenHouseInstallState resolvedInstallState = installState == null
            ? OpenHouseInstallState.idle()
            : installState;
        this.oneClickInstallRunning = resolvedInstallState.running;
        this.oneClickInstallCompleted = resolvedInstallState.completed;
        this.oneClickInstallFailed = resolvedInstallState.failed;
        this.installPercent = resolvedInstallState.percent;
        this.installPhaseLabel = resolvedInstallState.phaseLabel;
        this.installDetailText = resolvedInstallState.detailText;
        this.installStageSlug = resolvedInstallState.currentStageSlug;

        boolean launchInstallSatisfied = oneClickInstallCompleted
            || (status != null && status.isCoreDeploymentComplete());
        this.launchAllowed = launchInstallSatisfied;
    }

    public Step getStep() {
        return step;
    }

    public int getCurrentStep() {
        return currentStep;
    }

    public String getCurrentStepSlug() {
        return currentStepSlug;
    }

    public String getCurrentStepLabel() {
        return currentStepLabel;
    }

    public boolean isPermissionsSkipped() {
        return permissionsSkipped;
    }

    public boolean isLaunchConfirmed() {
        return launchConfirmed;
    }

    public boolean isLaunchAllowed() {
        return launchAllowed;
    }

    public boolean isComplete() {
        return launchConfirmed || step.number >= Step.READY_TO_USE.number;
    }

    public enum Step {
        PERMISSIONS(1, "permissions", "权限确认"),
        ONE_CLICK_INSTALL(2, "one_click_install", "一键初始化"),
        WAITING_INSTALL(3, "waiting_install", "等待安装完成"),
        READY_TO_USE(4, "ready_to_use", "使用说明");

        public final int number;
        public final String slug;
        public final String label;

        Step(int number, String slug, String label) {
            this.number = number;
            this.slug = slug;
            this.label = label;
        }

        public static Step fromNumber(int number) {
            for (Step step : values()) {
                if (step.number == number) {
                    return step;
                }
            }
            if (number >= 3 && number < 7) {
                return WAITING_INSTALL;
            }
            if (number >= 7) {
                return READY_TO_USE;
            }
            return PERMISSIONS;
        }

        public static Step fromSlug(String slug) {
            if (slug == null) {
                return PERMISSIONS;
            }
            for (Step step : values()) {
                if (step.slug.equals(slug)) {
                    return step;
                }
            }
            if ("reading_guide".equals(slug)) {
                return WAITING_INSTALL;
            }
            return PERMISSIONS;
        }
    }
}
