package com.termux.app.openhouse.servicecontrol;

public final class ServiceManagerTarget {

    public final boolean valid;
    public final String serviceId;
    public final String action;
    public final String message;

    private ServiceManagerTarget(boolean valid, String serviceId, String action, String message) {
        this.valid = valid;
        this.serviceId = serviceId == null ? "" : serviceId;
        this.action = action == null ? "" : action;
        this.message = message == null ? "" : message;
    }

    static ServiceManagerTarget valid(String serviceId, String action) {
        return new ServiceManagerTarget(true, serviceId, action, "");
    }

    static ServiceManagerTarget invalid(String message) {
        return new ServiceManagerTarget(false, "", "", message);
    }
}
