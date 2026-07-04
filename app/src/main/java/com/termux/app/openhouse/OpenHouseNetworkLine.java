package com.termux.app.openhouse;

public enum OpenHouseNetworkLine {
    CN(
        "cn",
        "国内加速",
        "默认线路，适合国内网络。",
        false
    ),
    STANDARD(
        "standard",
        "标准线路",
        "适合海外网络。切换前会先检测。",
        true
    );

    private final String preferenceValue;
    private final String label;
    private final String description;
    private final boolean requiresProbeBeforeSwitch;

    OpenHouseNetworkLine(String preferenceValue,
                         String label,
                         String description,
                         boolean requiresProbeBeforeSwitch) {
        this.preferenceValue = preferenceValue;
        this.label = label;
        this.description = description;
        this.requiresProbeBeforeSwitch = requiresProbeBeforeSwitch;
    }

    public String getPreferenceValue() {
        return preferenceValue;
    }

    public String getLabel() {
        return label;
    }

    public String getDescription() {
        return description;
    }

    public boolean requiresProbeBeforeSwitch() {
        return requiresProbeBeforeSwitch;
    }

    public boolean usesDomesticAcceleration() {
        return this == CN;
    }

    public OpenHouseInstallState.RetryMode toRetryMode() {
        return this == CN
            ? OpenHouseInstallState.RetryMode.CN
            : OpenHouseInstallState.RetryMode.GENERAL;
    }

    public static OpenHouseNetworkLine fromPreferenceValue(String value) {
        if (value == null) {
            return CN;
        }
        if ("general".equalsIgnoreCase(value) || "normal".equalsIgnoreCase(value)) {
            return STANDARD;
        }
        for (OpenHouseNetworkLine line : values()) {
            if (line.preferenceValue.equalsIgnoreCase(value)
                || line.name().equalsIgnoreCase(value)) {
                return line;
            }
        }
        return CN;
    }

    public static OpenHouseNetworkLine fromRetryMode(OpenHouseInstallState.RetryMode retryMode) {
        return retryMode == OpenHouseInstallState.RetryMode.CN ? CN : STANDARD;
    }
}
