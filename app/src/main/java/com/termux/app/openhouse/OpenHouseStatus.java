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
    public final boolean openhouseConnectInstalled;
    public final boolean smallPhoneRuntimeInstalled;
    public final boolean registrySynced;
    public final boolean serviceManagerReachable;
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
                           boolean openhouseConnectInstalled,
                           boolean smallPhoneRuntimeInstalled,
                           boolean registrySynced,
                           boolean serviceManagerReachable,
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
        this.openhouseConnectInstalled = openhouseConnectInstalled;
        this.smallPhoneRuntimeInstalled = smallPhoneRuntimeInstalled;
        this.registrySynced = registrySynced;
        this.serviceManagerReachable = serviceManagerReachable;
        this.openhouseConnectReachable = openhouseConnectReachable;
        this.smallPhoneReachable = smallPhoneReachable;
        this.launchConfirmed = launchConfirmed;
        this.diagnostic = diagnostic == null ? "" : diagnostic;
    }

    public static OpenHouseStatus checking() {
        return new OpenHouseStatus(false, false, false, false, false, false,
            false, false, false, false, false, false, false, false, false,
            false, false, false, "");
    }

    public boolean isDeploymentComplete() {
        return isCoreDeploymentComplete();
    }

    public boolean isCoreDeploymentComplete() {
        return termuxReady
            && productPrepared
            && ubuntuInstalled
            && entryUbuntuConfigured
            && nodeInstalled
            && officialDocsSynced
            && serviceManagerInstalled
            && openhouseConnectInstalled
            && smallPhoneRuntimeInstalled
            && registrySynced
            && serviceManagerReachable
            && openhouseConnectReachable
            && smallPhoneReachable;
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
        int done = 0;
        int total = 13;
        if (termuxReady) done++;
        if (productPrepared) done++;
        if (ubuntuInstalled) done++;
        if (entryUbuntuConfigured) done++;
        if (nodeInstalled) done++;
        if (officialDocsSynced) done++;
        if (serviceManagerInstalled) done++;
        if (openhouseConnectInstalled) done++;
        if (smallPhoneRuntimeInstalled) done++;
        if (registrySynced) done++;
        if (serviceManagerReachable) done++;
        if (openhouseConnectReachable) done++;
        if (smallPhoneReachable) done++;
        return Math.round((done * 100f) / total);
    }

    public String getNextStepLabel() {
        if (!termuxReady) return "准备 Termux 基础环境";
        if (!productPrepared) return "准备本机目录";
        if (!ubuntuInstalled) return "准备 Linux 环境";
        if (!entryUbuntuConfigured) return "设置启动方式";
        if (!nodeInstalled) return "安装 Node.js";
        if (!officialDocsSynced) return "同步使用文档";
        if (!serviceManagerInstalled) return "安装 service-manager";
        if (!openhouseConnectInstalled) return "安装 openhouse-connect";
        if (!smallPhoneRuntimeInstalled) return "安装 SmallPhone runtime";
        if (!registrySynced) return "同步 service-manager 注册表";
        if (!serviceManagerReachable) return "启动 service-manager";
        if (!openhouseConnectReachable) return "启动 openhouse-connect";
        if (!smallPhoneReachable) return "启动 SmallPhone";
        return "OpenHouse 控制平面已就绪";
    }
}
