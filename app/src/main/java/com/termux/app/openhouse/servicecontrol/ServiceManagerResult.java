package com.termux.app.openhouse.servicecontrol;

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

    private ServiceManagerResult(Builder builder) {
        this.success = builder.success;
        this.code = builder.code;
        this.body = builder.body == null ? "" : builder.body;
        this.message = builder.message == null ? "" : builder.message;
        this.serviceId = builder.serviceId == null ? "" : builder.serviceId;
        this.action = builder.action == null ? "" : builder.action;
        this.state = builder.state == null ? "" : builder.state;
        this.provider = builder.provider == null ? "" : builder.provider;
        this.pid = builder.pid;
        this.url = builder.url == null ? "" : builder.url;
        this.services = immutableCopy(builder.services);
        this.logLines = immutableCopy(builder.logLines);
    }

    static Builder builder(boolean success) {
        return new Builder(success);
    }

    static ServiceManagerResult error(String message) {
        return builder(false).code(0).message(message).build();
    }

    static ServiceManagerResult invalid(String message) {
        return builder(false).code(0).message(message).build();
    }

    static ServiceManagerResult fromHttpFailure(int code, String body, String fallbackMessage) {
        return builder(false)
            .code(code)
            .body(body)
            .message(extractErrorMessage(body, fallbackMessage))
            .build();
    }

    ServiceManagerResult withTarget(String serviceId, String action) {
        return builder(success)
            .code(code)
            .body(body)
            .message(message)
            .serviceId(serviceId)
            .action(action)
            .state(state)
            .provider(provider)
            .pid(pid)
            .url(url)
            .services(services)
            .logLines(logLines)
            .build();
    }

    private static String extractErrorMessage(String body, String fallbackMessage) {
        String trimmed = body == null ? "" : body.trim();
        if (!trimmed.isEmpty()) {
            return trimmed;
        }
        return fallbackMessage == null ? "service-manager request failed" : fallbackMessage;
    }

    private static <T> List<T> immutableCopy(List<T> values) {
        if (values == null || values.isEmpty()) {
            return Collections.emptyList();
        }
        return Collections.unmodifiableList(new ArrayList<>(values));
    }

    static final class Builder {
        private final boolean success;
        private int code;
        private String body;
        private String message;
        private String serviceId;
        private String action;
        private String state;
        private String provider;
        private Integer pid;
        private String url;
        private List<ServiceManagerService> services = Collections.emptyList();
        private List<ServiceManagerLogLine> logLines = Collections.emptyList();

        private Builder(boolean success) {
            this.success = success;
        }

        Builder code(int code) {
            this.code = code;
            return this;
        }

        Builder body(String body) {
            this.body = body;
            return this;
        }

        Builder message(String message) {
            this.message = message;
            return this;
        }

        Builder serviceId(String serviceId) {
            this.serviceId = serviceId;
            return this;
        }

        Builder action(String action) {
            this.action = action;
            return this;
        }

        Builder state(String state) {
            this.state = state;
            return this;
        }

        Builder provider(String provider) {
            this.provider = provider;
            return this;
        }

        Builder pid(Integer pid) {
            this.pid = pid;
            return this;
        }

        Builder url(String url) {
            this.url = url;
            return this;
        }

        Builder services(List<ServiceManagerService> services) {
            this.services = services;
            return this;
        }

        Builder logLines(List<ServiceManagerLogLine> logLines) {
            this.logLines = logLines;
            return this;
        }

        ServiceManagerResult build() {
            return new ServiceManagerResult(this);
        }
    }
}
