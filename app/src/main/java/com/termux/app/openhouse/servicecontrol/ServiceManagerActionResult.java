package com.termux.app.openhouse.servicecontrol;

public final class ServiceManagerActionResult {

    private final boolean success;
    private final int code;
    private final String body;
    private final String message;
    private final String serviceId;
    private final String action;
    private final String state;
    private final String provider;
    private final int pid;

    ServiceManagerActionResult(ServiceManagerResult result) {
        this.success = result != null && result.success;
        this.code = result == null ? 0 : result.code;
        this.body = result == null ? "" : result.body;
        this.message = result == null ? "" : result.message;
        this.serviceId = result == null ? "" : result.serviceId;
        this.action = result == null ? "" : result.action;
        this.state = result == null ? "" : result.state;
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

    public String action() {
        return action;
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
