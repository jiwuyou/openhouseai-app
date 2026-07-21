package com.wuxianpi.openhouse.core;

public enum StartupTarget {
    LAST_PAGE("last"),
    DESKTOP("desktop"),
    BASIC("basic"),
    ADVANCED("advanced");

    private final String persistenceKey;

    StartupTarget(String persistenceKey) {
        this.persistenceKey = persistenceKey;
    }

    public String persistenceKey() {
        return persistenceKey;
    }

    public ProductRoute resolve(ProductRoute lastPage, HostCapabilities capabilities) {
        HostCapabilities caps = capabilities == null ? HostCapabilities.full() : capabilities;
        ProductRoute route;
        switch (this) {
            case BASIC: route = ProductRoute.BASIC; break;
            case ADVANCED: route = ProductRoute.ADVANCED; break;
            case LAST_PAGE:
                route = lastPage != null && lastPage.isOrdinaryStartupRoute() ? lastPage : ProductRoute.DESKTOP;
                break;
            case DESKTOP:
            default: route = ProductRoute.DESKTOP;
        }
        return caps.supports(route) ? route : ProductRoute.DESKTOP;
    }

    public static StartupTarget fromPersistenceKey(String value) {
        String normalized = value == null ? "" : value.trim();
        for (StartupTarget target : values()) {
            if (target.persistenceKey.equals(normalized)) return target;
        }
        return DESKTOP;
    }
}
