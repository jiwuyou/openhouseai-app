package com.wuxianpi.openhouse.core.service;

public final class ServiceManagerTarget {
    public final boolean valid;
    public final String serviceId;
    public final ServiceAction action;
    public final String message;

    private ServiceManagerTarget(boolean valid, String serviceId, ServiceAction action, String message) {
        this.valid = valid; this.serviceId = serviceId == null ? "" : serviceId;
        this.action = action; this.message = message == null ? "" : message;
    }

    static ServiceManagerTarget valid(String id, ServiceAction action) { return new ServiceManagerTarget(true, id, action, ""); }
    static ServiceManagerTarget invalid(String message) { return new ServiceManagerTarget(false, "", null, message); }
}
