package com.wuxianpi.openhouse.core.service;

public final class HttpRequestSpec {
    public final String method;
    public final String path;
    public final boolean authenticated;
    public final int connectTimeoutMillis;
    public final int readTimeoutMillis;

    public HttpRequestSpec(String method, String path, boolean authenticated,
                           int connectTimeoutMillis, int readTimeoutMillis) {
        if (method == null || method.trim().isEmpty()) throw new IllegalArgumentException("method is required");
        if (path == null || !path.startsWith("/")) throw new IllegalArgumentException("path must be relative to the base URL");
        this.method = method.trim().toUpperCase();
        this.path = path;
        this.authenticated = authenticated;
        this.connectTimeoutMillis = connectTimeoutMillis;
        this.readTimeoutMillis = readTimeoutMillis;
    }
}
