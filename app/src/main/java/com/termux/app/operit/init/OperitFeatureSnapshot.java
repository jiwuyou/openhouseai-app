package com.termux.app.operit.init;

public final class OperitFeatureSnapshot {

    private final boolean initialized;
    private final String state;
    private final String hostPackageName;
    private final String applicationContextClassName;
    private final long initializedAtMs;
    private final long lastUpdatedAtMs;
    private final int initializationCount;
    private final String summary;
    private final String error;
    private final String migrationMode;
    private final String enabledComponents;
    private final String supportedCommands;
    private final boolean startsBackgroundServices;
    private final boolean originalOperitShellLoaded;

    OperitFeatureSnapshot(
        boolean initialized,
        String state,
        String hostPackageName,
        String applicationContextClassName,
        long initializedAtMs,
        long lastUpdatedAtMs,
        int initializationCount,
        String summary,
        String error,
        String migrationMode,
        String enabledComponents,
        String supportedCommands,
        boolean startsBackgroundServices,
        boolean originalOperitShellLoaded
    ) {
        this.initialized = initialized;
        this.state = safe(state);
        this.hostPackageName = safe(hostPackageName);
        this.applicationContextClassName = safe(applicationContextClassName);
        this.initializedAtMs = initializedAtMs;
        this.lastUpdatedAtMs = lastUpdatedAtMs;
        this.initializationCount = initializationCount;
        this.summary = safe(summary);
        this.error = safe(error);
        this.migrationMode = safe(migrationMode);
        this.enabledComponents = safe(enabledComponents);
        this.supportedCommands = safe(supportedCommands);
        this.startsBackgroundServices = startsBackgroundServices;
        this.originalOperitShellLoaded = originalOperitShellLoaded;
    }

    public boolean isInitialized() {
        return initialized;
    }

    public String getState() {
        return state;
    }

    public String getHostPackageName() {
        return hostPackageName;
    }

    public String getApplicationContextClassName() {
        return applicationContextClassName;
    }

    public long getInitializedAtMs() {
        return initializedAtMs;
    }

    public long getLastUpdatedAtMs() {
        return lastUpdatedAtMs;
    }

    public int getInitializationCount() {
        return initializationCount;
    }

    public String getSummary() {
        return summary;
    }

    public String getError() {
        return error;
    }

    public String getMigrationMode() {
        return migrationMode;
    }

    public String getEnabledComponents() {
        return enabledComponents;
    }

    public String getSupportedCommands() {
        return supportedCommands;
    }

    public boolean startsBackgroundServices() {
        return startsBackgroundServices;
    }

    public boolean isOriginalOperitShellLoaded() {
        return originalOperitShellLoaded;
    }

    public String toDisplayText() {
        StringBuilder builder = new StringBuilder();
        append(builder, "state", state);
        append(builder, "initialized", String.valueOf(initialized));
        append(builder, "migrationMode", migrationMode);
        append(builder, "enabledComponents", enabledComponents);
        append(builder, "supportedCommands", supportedCommands);
        append(builder, "startsBackgroundServices", String.valueOf(startsBackgroundServices));
        append(builder, "originalOperitShellLoaded", String.valueOf(originalOperitShellLoaded));
        append(builder, "hostPackage", hostPackageName);
        append(builder, "context", applicationContextClassName);
        append(builder, "summary", summary);
        append(builder, "error", error);
        return builder.toString();
    }

    private static void append(StringBuilder builder, String label, String value) {
        if (value == null || value.trim().isEmpty()) {
            return;
        }
        if (builder.length() > 0) {
            builder.append('\n');
        }
        builder.append(label).append(": ").append(value);
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
