package com.termux.app.openhouse.servicecontrol;

public final class ServiceManagerServiceStatus {

    private final boolean success;
    private final int code;
    private final String body;
    private final String message;
    private final String serviceId;
    private final String displayName;
    private final String state;
    private final String provider;
    private final int pid;

    ServiceManagerServiceStatus(String serviceId, ServiceManagerResult result) {
        this.success = result != null && result.success;
        this.code = result == null ? 0 : result.code;
        this.body = result == null ? "" : result.body;
        this.message = result == null ? "" : result.message;
        this.serviceId = serviceId == null ? "" : serviceId;
        this.displayName = this.serviceId;
        this.state = result == null || result.state.isEmpty() ? "unknown" : result.state;
        this.provider = result == null ? "" : result.provider;
        this.pid = result == null || result.pid == null ? -1 : result.pid;
    }

    public boolean success() {
        return success;
    }

    public int code() {
        return code;
    }

    public String body() {
        return body;
    }

    public String message() {
        return message;
    }

    public String serviceId() {
        return serviceId;
    }

    public String displayName() {
        return displayName;
    }

    public String state() {
        return state;
    }

    public String provider() {
        return provider;
    }

    public int pid() {
        return pid;
    }
}
