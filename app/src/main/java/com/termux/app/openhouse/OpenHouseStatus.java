package com.termux.app.openhouse;

import com.termux.app.OpenCodeSettings;

public final class OpenHouseStatus {

    public final boolean batteryOptimizationIgnored;
    public final boolean productPrepared;
    public final boolean ubuntuInstalled;
    public final boolean officialDocsSynced;
    public final boolean entryUbuntuConfigured;
    public final boolean openCodeInstalled;
    public final boolean codexInstalled;
    public final boolean claudeCodeInstalled;
    public final boolean reasonixInstalled;
    public final boolean deepSeekConfigured;
    public final boolean openCodeReachable;
    public final boolean deepSeekKeySaved;
    public final boolean launchConfirmed;
    public final boolean openCodeRunningInRoot;
    public final int openCodePort;
    public final String openCodeProjectDirectory;
    public final String diagnostic;

    public OpenHouseStatus(boolean batteryOptimizationIgnored,
                           boolean productPrepared,
                           boolean ubuntuInstalled,
                           boolean officialDocsSynced,
                           boolean entryUbuntuConfigured,
                           boolean openCodeInstalled,
                           boolean codexInstalled,
                           boolean claudeCodeInstalled,
                           boolean reasonixInstalled,
                           boolean deepSeekConfigured,
                           boolean openCodeReachable,
                           String diagnostic) {
        this(
            batteryOptimizationIgnored,
            productPrepared,
            ubuntuInstalled,
            officialDocsSynced,
            entryUbuntuConfigured,
            openCodeInstalled,
            codexInstalled,
            claudeCodeInstalled,
            reasonixInstalled,
            deepSeekConfigured,
            openCodeReachable,
            false,
            false,
            false,
            OpenCodeSettings.DEFAULT_OPENCODE_PORT,
            OpenCodeSettings.DEFAULT_PROJECT_DIRECTORY,
            diagnostic
        );
    }

    public OpenHouseStatus(boolean batteryOptimizationIgnored,
                           boolean productPrepared,
                           boolean ubuntuInstalled,
                           boolean officialDocsSynced,
                           boolean entryUbuntuConfigured,
                           boolean openCodeInstalled,
                           boolean codexInstalled,
                           boolean claudeCodeInstalled,
                           boolean reasonixInstalled,
                           boolean deepSeekConfigured,
                           boolean openCodeReachable,
                           boolean deepSeekKeySaved,
                           boolean launchConfirmed,
                           boolean openCodeRunningInRoot,
                           int openCodePort,
                           String openCodeProjectDirectory,
                           String diagnostic) {
        this.batteryOptimizationIgnored = batteryOptimizationIgnored;
        this.productPrepared = productPrepared;
        this.ubuntuInstalled = ubuntuInstalled;
        this.officialDocsSynced = officialDocsSynced;
        this.entryUbuntuConfigured = entryUbuntuConfigured;
        this.openCodeInstalled = openCodeInstalled;
        this.codexInstalled = codexInstalled;
        this.claudeCodeInstalled = claudeCodeInstalled;
        this.reasonixInstalled = reasonixInstalled;
        this.deepSeekConfigured = deepSeekConfigured;
        this.openCodeReachable = openCodeReachable;
        this.deepSeekKeySaved = deepSeekKeySaved;
        this.launchConfirmed = launchConfirmed;
        this.openCodeRunningInRoot = openCodeRunningInRoot;
        this.openCodePort = OpenCodeSettings.isValidPort(openCodePort)
            ? openCodePort
            : OpenCodeSettings.DEFAULT_OPENCODE_PORT;
        this.openCodeProjectDirectory = openCodeProjectDirectory == null || openCodeProjectDirectory.isEmpty()
            ? OpenCodeSettings.DEFAULT_PROJECT_DIRECTORY
            : openCodeProjectDirectory;
        this.diagnostic = diagnostic == null ? "" : diagnostic;
    }

    public static OpenHouseStatus checking() {
        return new OpenHouseStatus(false, false, false, false, false, false,
            false, false, false, false, false, "");
    }

    public boolean isDeploymentComplete() {
        return openCodeInstalled && codexInstalled && claudeCodeInstalled && reasonixInstalled;
    }

    public int getAgentReadyCount() {
        int count = 0;
        if (openCodeInstalled) count++;
        if (codexInstalled) count++;
        if (claudeCodeInstalled) count++;
        if (reasonixInstalled) count++;
        return count;
    }

    public int getProgressPercent() {
        int done = 0;
        int total = 9;
        if (productPrepared) done++;
        if (ubuntuInstalled) done++;
        if (officialDocsSynced) done++;
        if (entryUbuntuConfigured) done++;
        if (openCodeInstalled) done++;
        if (codexInstalled) done++;
        if (claudeCodeInstalled) done++;
        if (reasonixInstalled) done++;
        if (deepSeekConfigured) done++;
        return Math.round((done * 100f) / total);
    }

    public String getNextStepLabel() {
        if (!productPrepared) return "准备本机目录";
        if (!ubuntuInstalled) return "准备 Linux 环境";
        if (!officialDocsSynced) return "同步使用文档";
        if (!entryUbuntuConfigured) return "设置启动方式";
        if (!openCodeInstalled) return "安装 AI 工具：OpenCode";
        if (!codexInstalled) return "安装 AI 工具：Codex";
        if (!claudeCodeInstalled) return "安装 AI 工具：Claude Code";
        if (!reasonixInstalled) return "安装 AI 工具：Reasonix";
        if (!deepSeekConfigured && !deepSeekKeySaved) return "填写 DeepSeek Key";
        if (!deepSeekConfigured) return "配置 DeepSeek Key";
        if (!openCodeReachable) return "启动 OpenCode Web";
        if (!launchConfirmed) return "确认 OpenCode 启动";
        return "可以开始使用 AI";
    }

    public boolean isOpenCodeLaunchReady() {
        return openCodeInstalled && (deepSeekConfigured || deepSeekKeySaved);
    }
}
