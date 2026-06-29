package com.termux.app.operit.runtime;

public final class OperitServiceManagerResult {

    public final boolean success;
    public final int code;
    public final String url;
    public final String body;
    public final String message;
    public final String serviceId;
    public final String state;
    public final String provider;
    public final int pid;
    public final String serviceUrl;
    public final String error;
    public final long durationMs;

    public OperitServiceManagerResult(
        boolean success,
        int code,
        String url,
        String body,
        String message,
        String serviceId,
        String state,
        String provider,
        int pid,
        String serviceUrl,
        String error,
        long durationMs
    ) {
        this.success = success;
        this.code = code;
        this.url = url == null ? "" : url;
        this.body = body == null ? "" : body;
        this.message = message == null ? "" : message;
        this.serviceId = serviceId == null ? "" : serviceId;
        this.state = state == null ? "" : state;
        this.provider = provider == null ? "" : provider;
        this.pid = pid;
        this.serviceUrl = serviceUrl == null ? "" : serviceUrl;
        this.error = error == null ? "" : error;
        this.durationMs = Math.max(0L, durationMs);
    }
}
