package com.termux.app.openhouse;

public final class OpenHouseOnboardingState {

    public static final int TOTAL_STEPS = 7;

    public final Step step;
    public final int currentStep;
    public final String currentStepSlug;
    public final String currentStepLabel;
    public final boolean permissionsSkipped;
    public final boolean keySkipped;
    public final boolean configurationSkipped;
    public final boolean keySaved;
    public final boolean deepSeekConfigured;
    public final boolean launchConfirmed;
    public final boolean oneClickInstallRunning;
    public final boolean oneClickInstallCompleted;
    public final boolean oneClickInstallFailed;
    public final int installPercent;
    public final String installPhaseLabel;
    public final String installDetailText;
    public final String installStageSlug;
    public final boolean openCodeInstalled;
    public final boolean openCodeReachable;
    public final boolean openCodeRunningInRoot;
    public final boolean launchAllowed;

    public OpenHouseOnboardingState(Step step,
                                    boolean permissionsSkipped,
                                    boolean keySkipped,
                                    boolean configurationSkipped,
                                    boolean keySaved,
                                    boolean deepSeekConfigured,
                                    boolean launchConfirmed,
                                    OpenHouseInstallState installState,
                                    OpenHouseStatus status) {
        this.step = step == null ? Step.PERMISSIONS : step;
        this.currentStep = this.step.number;
        this.currentStepSlug = this.step.slug;
        this.currentStepLabel = this.step.label;
        this.permissionsSkipped = permissionsSkipped;
        this.keySkipped = keySkipped;
        this.configurationSkipped = configurationSkipped;
        this.keySaved = keySaved;
        this.deepSeekConfigured = deepSeekConfigured;
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

        this.openCodeInstalled = status != null && status.openCodeInstalled;
        this.openCodeReachable = status != null && status.openCodeReachable;
        this.openCodeRunningInRoot = status != null && status.openCodeRunningInRoot;
        boolean launchConfigurationSatisfied = deepSeekConfigured || keySkipped || configurationSkipped;
        boolean launchInstallSatisfied = status == null ? oneClickInstallCompleted : openCodeInstalled;
        this.launchAllowed = launchConfigurationSatisfied && launchInstallSatisfied;
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

    public boolean isKeySkipped() {
        return keySkipped;
    }

    public boolean isConfigurationSkipped() {
        return configurationSkipped;
    }

    public boolean isKeySaved() {
        return keySaved;
    }

    public boolean isDeepSeekConfigured() {
        return deepSeekConfigured;
    }

    public boolean isLaunchConfirmed() {
        return launchConfirmed;
    }

    public boolean isLaunchAllowed() {
        return launchAllowed;
    }

    public boolean canConfigureDeepSeek() {
        return keySaved && !keySkipped && !deepSeekConfigured;
    }

    public boolean isComplete() {
        return launchConfirmed || step.number >= Step.OPENCODE_LAUNCH.number;
    }

    public enum Step {
        PERMISSIONS(1, "permissions", "权限确认"),
        ONE_CLICK_INSTALL(2, "one_click_install", "一键初始化"),
        READING_GUIDE(3, "reading_guide", "建议阅读"),
        DEEPSEEK_KEY(4, "deepseek_key", "保存 DeepSeek Key"),
        WAITING_INSTALL(5, "waiting_install", "等待安装完成"),
        DEEPSEEK_CONFIGURATION(6, "deepseek_configuration", "配置 DeepSeek"),
        OPENCODE_LAUNCH(7, "opencode_launch", "启动配置");

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
            return PERMISSIONS;
        }
    }
}
