package com.wuxianpi.openhouse.core.service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class ServiceManagerResult {
    public final boolean success;
    public final int code;
    public final String body;
    public final String message;
    public final String serviceId;
    public final String action;
    public final String state;
    public final String provider;
    public final Integer pid;
    public final String url;
    public final List<ServiceManagerService> services;
    public final List<ServiceManagerLogLine> logLines;

    private ServiceManagerResult(Builder value) {
        success = value.success; code = value.code; body = text(value.body); message = text(value.message);
        serviceId = text(value.serviceId); action = text(value.action); state = text(value.state);
        provider = text(value.provider); pid = value.pid; url = text(value.url);
        services = immutable(value.services); logLines = immutable(value.logLines);
    }

    static Builder builder(boolean success) { return new Builder(success); }
    static ServiceManagerResult failure(int code, String body, String message) {
        String detail = text(body); return builder(false).code(code).body(body).message(detail.isEmpty() ? message : detail).build();
    }

    private static <T> List<T> immutable(List<T> values) {
        return values == null || values.isEmpty() ? Collections.emptyList() : Collections.unmodifiableList(new ArrayList<>(values));
    }
    private static String text(String value) { return value == null ? "" : value.trim(); }

    static final class Builder {
        final boolean success; int code; String body; String message; String serviceId; String action;
        String state; String provider; Integer pid; String url; List<ServiceManagerService> services;
        List<ServiceManagerLogLine> logLines;
        Builder(boolean success) { this.success = success; }
        Builder code(int v) { code=v; return this; } Builder body(String v) { body=v; return this; }
        Builder message(String v) { message=v; return this; } Builder serviceId(String v) { serviceId=v; return this; }
        Builder action(String v) { action=v; return this; } Builder state(String v) { state=v; return this; }
        Builder provider(String v) { provider=v; return this; } Builder pid(Integer v) { pid=v; return this; }
        Builder url(String v) { url=v; return this; } Builder services(List<ServiceManagerService> v) { services=v; return this; }
        Builder logLines(List<ServiceManagerLogLine> v) { logLines=v; return this; }
        ServiceManagerResult build() { return new ServiceManagerResult(this); }
    }
}
