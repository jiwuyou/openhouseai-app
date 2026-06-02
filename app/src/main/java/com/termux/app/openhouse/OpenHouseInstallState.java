package com.termux.app.openhouse;

public final class OpenHouseInstallState {

    public final boolean running;
    public final boolean completed;
    public final boolean failed;
    public final int percent;
    public final String phaseLabel;
    public final String detailText;
    public final String currentStageSlug;

    public OpenHouseInstallState(boolean running,
                                 boolean completed,
                                 boolean failed,
                                 int percent,
                                 String phaseLabel,
                                 String detailText,
                                 String currentStageSlug) {
        this.running = running;
        this.completed = completed;
        this.failed = failed;
        this.percent = Math.max(0, Math.min(100, percent));
        this.phaseLabel = phaseLabel == null ? "" : phaseLabel;
        this.detailText = detailText == null ? "" : detailText;
        this.currentStageSlug = currentStageSlug == null ? "" : currentStageSlug;
    }

    public static OpenHouseInstallState idle() {
        return new OpenHouseInstallState(
            false,
            false,
            false,
            0,
            "等待初始化",
            "点击“一键初始化”后会开始准备 Linux 环境和安装 AI 工具。",
            "manifest_full"
        );
    }

    public boolean isRunning() {
        return running;
    }

    public boolean isCompleted() {
        return completed;
    }

    public boolean isFailed() {
        return failed;
    }

    public int getPercent() {
        return percent;
    }

    public String getPhaseLabel() {
        return phaseLabel;
    }

    public String getDetailText() {
        return detailText;
    }

    public String getCurrentStageSlug() {
        return currentStageSlug;
    }
}
