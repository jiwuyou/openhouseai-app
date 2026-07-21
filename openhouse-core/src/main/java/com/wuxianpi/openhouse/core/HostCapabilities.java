package com.wuxianpi.openhouse.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;

public final class HostCapabilities {
    public final boolean basicMode;
    public final boolean advancedMode;
    public final boolean repairMode;
    public final boolean serviceControl;
    public final boolean terminal;
    public final boolean hostMaintenance;
    public final boolean controlPlaneStart;
    public final boolean controlPlaneStop;
    public final boolean registryFileFallback;

    public HostCapabilities(boolean basicMode,
                            boolean advancedMode,
                            boolean repairMode,
                            boolean serviceControl,
                            boolean terminal,
                            boolean hostMaintenance,
                            boolean controlPlaneStart,
                            boolean controlPlaneStop) {
        this(basicMode, advancedMode, repairMode, serviceControl, terminal, hostMaintenance,
            controlPlaneStart, controlPlaneStop, false);
    }

    public HostCapabilities(boolean basicMode,
                            boolean advancedMode,
                            boolean repairMode,
                            boolean serviceControl,
                            boolean terminal,
                            boolean hostMaintenance,
                            boolean controlPlaneStart,
                            boolean controlPlaneStop,
                            boolean registryFileFallback) {
        this.basicMode = basicMode;
        this.advancedMode = advancedMode;
        this.repairMode = repairMode;
        this.serviceControl = serviceControl;
        this.terminal = terminal;
        this.hostMaintenance = hostMaintenance;
        this.controlPlaneStart = controlPlaneStart;
        this.controlPlaneStop = controlPlaneStop;
        this.registryFileFallback = registryFileFallback;
    }

    public static HostCapabilities full() {
        return new HostCapabilities(true, true, true, true, true, true, true, true, true);
    }

    public boolean supports(ProductRoute route) {
        if (route == null) return false;
        switch (route) {
            case BASIC: return basicMode;
            case ADVANCED: return advancedMode;
            case REPAIR: return repairMode;
            case SERVICE_CONTROL: return serviceControl;
            case DESKTOP:
            case SETUP:
            case SETTINGS:
            default: return true;
        }
    }

    public List<ProductRoute> trimRoutes(Iterable<ProductRoute> routes) {
        if (routes == null) return Collections.emptyList();
        LinkedHashSet<ProductRoute> result = new LinkedHashSet<>();
        for (ProductRoute route : routes) {
            if (supports(route)) result.add(route);
        }
        return Collections.unmodifiableList(new ArrayList<>(result));
    }
}
