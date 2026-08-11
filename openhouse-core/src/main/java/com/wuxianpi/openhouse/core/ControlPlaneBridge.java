package com.wuxianpi.openhouse.core;

/** Host adapter for the single stable command that starts service-manager. */
@FunctionalInterface
public interface ControlPlaneBridge {
    ControlPlaneCommandResult start(ControlPlaneOutputListener listener);
}
