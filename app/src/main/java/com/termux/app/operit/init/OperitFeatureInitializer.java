package com.termux.app.operit.init;

import android.content.Context;

public final class OperitFeatureInitializer {

    private static final OperitFeatureInitializer INSTANCE = new OperitFeatureInitializer();

    private static final String STATE_NOT_INITIALIZED = "not_initialized";
    private static final String STATE_INITIALIZED = "initialized";
    private static final String STATE_FAILED = "failed";
    private static final String MIGRATION_MODE = "hosted_full_migration_skeleton";
    private static final String ENABLED_COMPONENTS = "core_facade,tool_router,runtime_bridge,service_manager_query";
    private static final String SUPPORTED_COMMANDS = "/termux <command>\n"
        + "/ubuntu <command>\n"
        + "/service-manager health\n"
        + "/service-manager status <serviceId>";

    private final Object lock = new Object();

    private boolean initialized;
    private String state = STATE_NOT_INITIALIZED;
    private String hostPackageName = "";
    private String applicationContextClassName = "";
    private long initializedAtMs;
    private long lastUpdatedAtMs;
    private int initializationCount;
    private String summary = "Operit feature skeleton has not been initialized by the SmallPhoneAI host.";
    private String error = "";

    private OperitFeatureInitializer() {}

    public static OperitFeatureInitializer getInstance() {
        return INSTANCE;
    }

    public OperitFeatureSnapshot initialize(Context context) {
        long now = System.currentTimeMillis();
        if (context == null) {
            synchronized (lock) {
                initialized = false;
                state = STATE_FAILED;
                lastUpdatedAtMs = now;
                error = "Context is required to initialize the Operit feature skeleton.";
                summary = "Operit feature skeleton was not initialized.";
                return snapshotLocked();
            }
        }

        Context applicationContext = context.getApplicationContext();
        if (applicationContext == null) {
            applicationContext = context;
        }

        synchronized (lock) {
            if (initialized) {
                return snapshotLocked();
            }

            initialized = true;
            state = STATE_INITIALIZED;
            hostPackageName = safe(applicationContext.getPackageName());
            applicationContextClassName = applicationContext.getClass().getName();
            initializedAtMs = now;
            lastUpdatedAtMs = now;
            initializationCount++;
            error = "";
            summary = "Operit core migration skeleton is initialized inside the SmallPhoneAI host. "
                + "Full LLM, MCP, Compose UI, native inference, and the original Operit Application are not loaded here. "
                + "No background service is started by this initializer.";
            return snapshotLocked();
        }
    }

    public boolean isInitialized() {
        synchronized (lock) {
            return initialized;
        }
    }

    public OperitFeatureSnapshot snapshot() {
        synchronized (lock) {
            return snapshotLocked();
        }
    }

    private OperitFeatureSnapshot snapshotLocked() {
        return new OperitFeatureSnapshot(
            initialized,
            state,
            hostPackageName,
            applicationContextClassName,
            initializedAtMs,
            lastUpdatedAtMs,
            initializationCount,
            summary,
            error,
            MIGRATION_MODE,
            ENABLED_COMPONENTS,
            SUPPORTED_COMMANDS,
            false,
            false
        );
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
