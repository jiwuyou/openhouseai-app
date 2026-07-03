package com.termux.app.openhouse;

public final class OpenHouseStatus {

    public final boolean termuxReady;
    public final boolean batteryOptimizationIgnored;
    public final boolean productPrepared;
    public final boolean ubuntuInstalled;
    public final boolean officialDocsSynced;
    public final boolean entryUbuntuConfigured;
    public final boolean nodeInstalled;
    public final boolean codexInstalled;
    public final boolean claudeCodeInstalled;
    public final boolean cloudCliInstalled;
    public final boolean serviceManagerInstalled;
    public final boolean piAgentInstalled;
    public final boolean piWebInstalled;
    public final boolean openhouseConnectInstalled;
    public final boolean smallPhoneRuntimeInstalled;
    public final boolean registrySynced;
    public final boolean serviceManagerReachable;
    public final boolean piWebReachable;
    public final boolean openhouseConnectReachable;
    public final boolean smallPhoneReachable;
    public final boolean launchConfirmed;
    public final String diagnostic;

    public OpenHouseStatus(boolean termuxReady,
                           boolean batteryOptimizationIgnored,
                           boolean productPrepared,
                           boolean ubuntuInstalled,
                           boolean officialDocsSynced,
                           boolean entryUbuntuConfigured,
                           boolean nodeInstalled,
                           boolean codexInstalled,
                           boolean claudeCodeInstalled,
                           boolean cloudCliInstalled,
                           boolean serviceManagerInstalled,
                           boolean piAgentInstalled,
                           boolean piWebInstalled,
                           boolean openhouseConnectInstalled,
                           boolean smallPhoneRuntimeInstalled,
                           boolean registrySynced,
                           boolean serviceManagerReachable,
                           boolean piWebReachable,
                           boolean openhouseConnectReachable,
                           boolean smallPhoneReachable,
                           boolean launchConfirmed,
                           String diagnostic) {
        this.termuxReady = termuxReady;
        this.batteryOptimizationIgnored = batteryOptimizationIgnored;
        this.productPrepared = productPrepared;
        this.ubuntuInstalled = ubuntuInstalled;
        this.officialDocsSynced = officialDocsSynced;
        this.entryUbuntuConfigured = entryUbuntuConfigured;
        this.nodeInstalled = nodeInstalled;
        this.codexInstalled = codexInstalled;
        this.claudeCodeInstalled = claudeCodeInstalled;
        this.cloudCliInstalled = cloudCliInstalled;
        this.serviceManagerInstalled = serviceManagerInstalled;
        this.piAgentInstalled = piAgentInstalled;
        this.piWebInstalled = piWebInstalled;
        this.openhouseConnectInstalled = openhouseConnectInstalled;
        this.smallPhoneRuntimeInstalled = smallPhoneRuntimeInstalled;
        this.registrySynced = registrySynced;
        this.serviceManagerReachable = serviceManagerReachable;
        this.piWebReachable = piWebReachable;
        this.openhouseConnectReachable = openhouseConnectReachable;
        this.smallPhoneReachable = smallPhoneReachable;
        this.launchConfirmed = launchConfirmed;
        this.diagnostic = diagnostic == null ? "" : diagnostic;
    }

    public static OpenHouseStatus checking() {
        return new OpenHouseStatus(false, false, false, false, false, false,
            false, false, false, false, false, false, false, false, false,
            false, false, false, false, false, false, "");
    }

    public boolean isDeploymentComplete() {
        return isCoreDeploymentComplete();
    }

    public boolean isCoreDeploymentComplete() {
        return isFirstUseReady();
    }

    public boolean isFirstUseReady() {
        return piWebReachable;
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
        int total = 13;
        if (termuxReady) done++;
        if (productPrepared) done++;
        if (ubuntuInstalled) done++;
        if (nodeInstalled) done++;
        if (officialDocsSynced) done++;
        if (serviceManagerInstalled) done++;
        if (piAgentInstalled) done++;
        if (piWebInstalled) done++;
        if (smallPhoneRuntimeInstalled) done++;
        if (registrySynced) done++;
        if (serviceManagerReachable) done++;
        if (piWebReachable) done++;
        if (smallPhoneReachable) done++;
        return Math.round((done * 100f) / total);
    }

    public String getNextStepLabel() {
        if (isFirstUseReady()) return "pi-web 已可访问";
        if (!termuxReady) return "准备 Termux 基础环境";
        if (!productPrepared) return "准备本机目录";
        if (!ubuntuInstalled) return "准备 Linux 环境";
        if (!nodeInstalled) return "安装 Node.js";
        if (!officialDocsSynced) return "同步使用文档";
        if (!serviceManagerInstalled) return "安装 service-manager";
        if (!piAgentInstalled) return "安装 pi-agent";
        if (!piWebInstalled) return "安装 pi-web";
        if (!smallPhoneRuntimeInstalled) return "安装 SmallPhone 兼容服务";
        if (!registrySynced) return "同步 service-manager 注册表";
        if (!serviceManagerReachable) return "启动 service-manager";
        if (!piWebReachable) return "启动 pi-web";
        if (!smallPhoneReachable) return "启动 SmallPhone 兼容服务";
        return "OpenHouse 核心控制平面已就绪";
    }
}
