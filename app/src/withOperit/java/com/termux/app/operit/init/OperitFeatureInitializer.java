package com.termux.app.operit.init;

import android.content.Context;

public final class OperitFeatureInitializer {

    private static final OperitFeatureInitializer INSTANCE = new OperitFeatureInitializer();

    private static final String STATE_NOT_INITIALIZED = "not_initialized";
    private static final String STATE_INITIALIZED = "initialized";
    private static final String STATE_FAILED = "failed";
    private static final String MIGRATION_MODE = "hosted_full_operit";
    private static final String ENABLED_COMPONENTS =
        "full_operit_feature_module,host_bridge,tool_router,runtime_bridge,service_manager_query";
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
    private String summary = "Operit host bridge has not been initialized by the SmallPhoneAI host.";
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
                error = "Context is required to initialize the Operit host bridge.";
                summary = "Operit host bridge was not initialized.";
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
            summary = "Full Operit is available in the withOperit build and the SmallPhoneAI host bridge is initialized. "
                + "The original Operit Compose UI, LLM, MCP, and native capabilities are launched through the hosted :operit process. "
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
            true
        );
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
