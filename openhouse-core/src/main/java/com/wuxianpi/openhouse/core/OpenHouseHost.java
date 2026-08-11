package com.wuxianpi.openhouse.core;

public interface OpenHouseHost {
    HostEdition edition();
    HostCapabilities capabilities();
    SetupState setupState();
    SetupResult ensureConfigured();
    RuntimeConnection runtimeConnection();
    ControlPlaneStarter controlPlaneStarter();
    default ControlPlaneBridge controlPlaneBridge() {
        return listener -> {
            ControlPlaneResult result = controlPlaneStarter().startControlPlane();
            String message = result == null ? "Control-plane start returned no result" : result.message;
            if (listener != null && !message.isEmpty()) listener.onOutput("stdout", message);
            return new ControlPlaneCommandResult(
                result != null && result.isSuccess() ? 0 : 1,
                result != null && result.isSuccess() ? message : "",
                result != null && !result.isSuccess() ? message : ""
            );
        };
    }
    default LegacyRegistrySource legacyRegistrySource() {
        return LegacyRegistrySource.unavailable();
    }
    HostActionResult openTerminal();
    HostActionResult openHostMaintenance();
}
