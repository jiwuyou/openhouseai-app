package com.termux.app.openhouse;

public final class OpenHouseInstallState {

    public enum Status {
        PENDING("pending"),
        RUNNING("running"),
        SUCCEEDED("succeeded"),
        FAILED("failed"),
        SKIPPED("skipped"),
        RETRYING("retrying");

        public final String value;

        Status(String value) {
            this.value = value;
        }

        public static Status fromValue(String value) {
            if (value == null) {
                return PENDING;
            }
            for (Status status : values()) {
                if (status.value.equalsIgnoreCase(value) || status.name().equalsIgnoreCase(value)) {
                    return status;
                }
            }
            return PENDING;
        }
    }

    public enum RetryMode {
        GENERAL("general", "常规重试"),
        CN("cn", "国内网络重试");

        public final String value;
        public final String label;

        RetryMode(String value, String label) {
            this.value = value;
            this.label = label;
        }

        public static RetryMode fromValue(String value) {
            if (value == null) {
                return GENERAL;
            }
            for (RetryMode mode : values()) {
                if (mode.value.equalsIgnoreCase(value) || mode.name().equalsIgnoreCase(value)) {
                    return mode;
                }
            }
            return GENERAL;
        }
    }

    public final Status status;
    public final boolean running;
    public final boolean completed;
    public final boolean failed;
    public final int percent;
    public final String phaseLabel;
    public final String detailText;
    public final String currentStageSlug;
    public final RetryMode retryMode;
    public final int attempt;
    public final String logPath;
    public final String safeError;

    public OpenHouseInstallState(boolean running,
                                 boolean completed,
                                 boolean failed,
                                 int percent,
                                 String phaseLabel,
                                 String detailText,
                                 String currentStageSlug) {
        this(resolveStatus(running, completed, failed),
            percent,
            phaseLabel,
            detailText,
            currentStageSlug,
            RetryMode.GENERAL,
            running || completed || failed ? 1 : 0,
            "",
            failed ? detailText : "");
    }

    public OpenHouseInstallState(Status status,
                                 int percent,
                                 String phaseLabel,
                                 String detailText,
                                 String currentStageSlug,
                                 RetryMode retryMode,
                                 int attempt,
                                 String logPath,
                                 String safeError) {
        Status resolvedStatus = status == null ? Status.PENDING : status;
        this.status = resolvedStatus;
        this.running = resolvedStatus == Status.RUNNING || resolvedStatus == Status.RETRYING;
        this.completed = resolvedStatus == Status.SUCCEEDED || resolvedStatus == Status.SKIPPED;
        this.failed = resolvedStatus == Status.FAILED;
        this.percent = Math.max(0, Math.min(100, percent));
        this.phaseLabel = phaseLabel == null ? "" : phaseLabel;
        this.detailText = detailText == null ? "" : detailText;
        this.currentStageSlug = currentStageSlug == null ? "" : currentStageSlug;
        this.retryMode = retryMode == null ? RetryMode.GENERAL : retryMode;
        this.attempt = Math.max(0, attempt);
        this.logPath = logPath == null ? "" : logPath;
        this.safeError = safeError == null ? "" : safeError;
    }

    private static Status resolveStatus(boolean running, boolean completed, boolean failed) {
        if (failed) {
            return Status.FAILED;
        }
        if (completed) {
            return Status.SUCCEEDED;
        }
        if (running) {
            return Status.RUNNING;
        }
        return Status.PENDING;
    }

    public static OpenHouseInstallState idle() {
        return new OpenHouseInstallState(
            Status.PENDING,
            0,
            "等待初始化",
            "点击“一键初始化”后会开始准备 Linux 环境和安装 AI 工具。",
            "manifest_full",
            RetryMode.GENERAL,
            0,
            "",
            ""
        );
    }

    public Status getStatus() {
        return status;
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

    public boolean isSkipped() {
        return status == Status.SKIPPED;
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

    public RetryMode getRetryMode() {
        return retryMode;
    }

    public int getAttempt() {
        return attempt;
    }

    public String getLogPath() {
        return logPath;
    }

    public String getSafeError() {
        return safeError;
    }
}
