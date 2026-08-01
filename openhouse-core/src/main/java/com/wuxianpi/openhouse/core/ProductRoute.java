package com.wuxianpi.openhouse.core;

public enum ProductRoute {
    DESKTOP("desktop"),
    BASIC("basic"),
    ADVANCED("advanced"),
    REPAIR("repair"),
    SERVICE_CONTROL("service_control"),
    SETUP("setup"),
    PERMISSIONS("permissions"),
    SETTINGS("settings"),
    ABOUT("about");

    private final String persistenceKey;

    ProductRoute(String persistenceKey) {
        this.persistenceKey = persistenceKey;
    }

    public String persistenceKey() {
        return persistenceKey;
    }

    public boolean isOrdinaryStartupRoute() {
        return this == DESKTOP || this == BASIC || this == ADVANCED;
    }

    public static ProductRoute fromPersistenceKey(String value) {
        return fromPersistenceKey(value, DESKTOP);
    }

    public static ProductRoute fromPersistenceKey(String value, ProductRoute fallback) {
        String normalized = value == null ? "" : value.trim();
        for (ProductRoute route : values()) {
            if (route.persistenceKey.equals(normalized)) {
                return route;
            }
        }
        return fallback == null ? DESKTOP : fallback;
    }
}
