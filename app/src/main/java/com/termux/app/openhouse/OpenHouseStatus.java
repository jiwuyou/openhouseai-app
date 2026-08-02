package com.termux.app.openhouse;

public final class OpenHouseStatus {

    public final boolean termuxReady;
    public final boolean batteryOptimizationIgnored;
    public final boolean productPrepared;
    public final boolean ubuntuInstalled;
    public final boolean officialDocsSynced;
    public final boolean entryUbuntuConfigured;
    public final boolean termuxNodeInstalled;
    public final boolean ubuntuNodeInstalled;
    public final boolean nodeInstalled;
    public final boolean codexInstalled;
    public final boolean claudeCodeInstalled;
    public final boolean cloudCliInstalled;
    public final boolean serviceManagerInstalled;
    public final boolean piAgentInstalled;
    public final boolean piWebInstalled;
    public final boolean openhouseConnectInstalled;
    public final boolean smallPhoneRuntimeInstalled;
    public final boolean aionUiInstalled;
    public final boolean registrySynced;
    public final boolean serviceManagerReachable;
    public final boolean piWebReachable;
    public final boolean openhouseConnectReachable;
    public final boolean smallPhoneReachable;
    public final boolean aionUiReachable;
    public final String aionUiUrl;
    public final boolean launchConfirmed;
    public final String diagnostic;

    public OpenHouseStatus(boolean termuxReady,
                           boolean batteryOptimizationIgnored,
                           boolean productPrepared,
                           boolean ubuntuInstalled,
                           boolean officialDocsSynced,
                           boolean entryUbuntuConfigured,
                           boolean termuxNodeInstalled,
                           boolean ubuntuNodeInstalled,
                           boolean nodeInstalled,
                           boolean codexInstalled,
                           boolean claudeCodeInstalled,
                           boolean cloudCliInstalled,
                           boolean serviceManagerInstalled,
                           boolean piAgentInstalled,
                           boolean piWebInstalled,
                           boolean openhouseConnectInstalled,
                           boolean smallPhoneRuntimeInstalled,
                           boolean aionUiInstalled,
                           boolean registrySynced,
                           boolean serviceManagerReachable,
                           boolean piWebReachable,
                           boolean openhouseConnectReachable,
                           boolean smallPhoneReachable,
                           boolean aionUiReachable,
                           String aionUiUrl,
                           boolean launchConfirmed,
                           String diagnostic) {
        this.termuxReady = termuxReady;
        this.batteryOptimizationIgnored = batteryOptimizationIgnored;
        this.productPrepared = productPrepared;
        this.ubuntuInstalled = ubuntuInstalled;
        this.officialDocsSynced = officialDocsSynced;
        this.entryUbuntuConfigured = entryUbuntuConfigured;
        this.termuxNodeInstalled = termuxNodeInstalled;
        this.ubuntuNodeInstalled = ubuntuNodeInstalled;
        this.nodeInstalled = nodeInstalled;
        this.codexInstalled = codexInstalled;
        this.claudeCodeInstalled = claudeCodeInstalled;
        this.cloudCliInstalled = cloudCliInstalled;
        this.serviceManagerInstalled = serviceManagerInstalled;
        this.piAgentInstalled = piAgentInstalled;
        this.piWebInstalled = piWebInstalled;
        this.openhouseConnectInstalled = openhouseConnectInstalled;
        this.smallPhoneRuntimeInstalled = smallPhoneRuntimeInstalled;
        this.aionUiInstalled = aionUiInstalled;
        this.registrySynced = registrySynced;
        this.serviceManagerReachable = serviceManagerReachable;
        this.piWebReachable = piWebReachable;
        this.openhouseConnectReachable = openhouseConnectReachable;
        this.smallPhoneReachable = smallPhoneReachable;
        this.aionUiReachable = aionUiReachable;
        this.aionUiUrl = aionUiUrl == null ? "" : aionUiUrl;
        this.launchConfirmed = launchConfirmed;
        this.diagnostic = diagnostic == null ? "" : diagnostic;
    }

    public static OpenHouseStatus checking() {
        return new OpenHouseStatus(false, false, false, false, false, false,
            false, false, false, false, false, false, false, false, false,
            false, false, false, false, false, false, false, false, false, "", false, "");
    }

    public boolean isDeploymentComplete() {
        return isCoreDeploymentComplete();
    }

    public boolean isCoreDeploymentComplete() {
        return isFirstUseReady();
    }

    public boolean isFirstUseReady() {
        return serviceManagerReachable && piAgentInstalled && piWebReachable;
    }

    public boolean isRuntimeEnvironmentPrepared() {
        return termuxReady
            && productPrepared
            && termuxNodeInstalled
            && serviceManagerInstalled
            && piAgentInstalled
            && ubuntuInstalled;
    }

    public boolean isAiFeaturesReady() {
        return isFirstUseReady();
    }

    public boolean isFullAiToolsDeploymentComplete() {
        return isCoreDeploymentComplete()
            && codexInstalled
            && claudeCodeInstalled
            && cloudCliInstalled;
    }

    public int getAgentReadyCount() {
        int count = 0;
        if (codexInstalled) count++;
        if (claudeCodeInstalled) count++;
        if (cloudCliInstalled) count++;
        return count;
    }

    public int getProgressPercent() {
        if (isFirstUseReady()) {
            return 100;
        }

        int done = 0;
        int total = 7;
        if (termuxReady) done++;
        if (productPrepared) done++;
        if (termuxNodeInstalled) done++;
        if (serviceManagerInstalled) done++;
        if (piAgentInstalled) done++;
        if (serviceManagerReachable) done++;
        if (piWebReachable) done++;
        return Math.round((done * 100f) / total);
    }

    public String getNextStepLabel() {
        if (isFirstUseReady()) return "WuxianPi 核心服务已可使用";
        if (!termuxReady || !productPrepared) return "准备基础组件";
        if (!termuxNodeInstalled) return "准备 Termux Node.js 24 LTS/npm";
        if (!serviceManagerInstalled || !piAgentInstalled) return "安装 WuxianPi 核心服务";
        if (!serviceManagerReachable || !piWebReachable) return "启动 WuxianPi 核心服务";
        return "WuxianPi 核心服务已就绪";
    }
}
