package com.wuxianpi.openhouse.core;

public interface OpenHouseHost {
    HostEdition edition();
    HostCapabilities capabilities();
    SetupState setupState();
    SetupResult ensureConfigured();
    RuntimeConnection runtimeConnection();
    ControlPlaneStarter controlPlaneStarter();
    default LegacyRegistrySource legacyRegistrySource() {
        return LegacyRegistrySource.unavailable();
    }
    HostActionResult openTerminal();
    HostActionResult openHostMaintenance();
}
