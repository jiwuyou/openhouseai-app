package com.wuxianpi.openhouse.core;

import java.net.URI;
import java.util.Locale;

public final class RuntimeConnection {
    public static final String DEFAULT_SERVICE_MANAGER_URL = "http://127.0.0.1:20087";

    public final String serviceManagerBaseUrl;
    public final String serviceManagerToken;
    public final String piRuntimeBaseUrl;

    public RuntimeConnection(String serviceManagerBaseUrl, String serviceManagerToken, String piRuntimeBaseUrl) {
        this.serviceManagerBaseUrl = normalizeBaseUrl(serviceManagerBaseUrl, DEFAULT_SERVICE_MANAGER_URL);
        this.serviceManagerToken = serviceManagerToken == null ? "" : serviceManagerToken.trim();
        this.piRuntimeBaseUrl = normalizeBaseUrl(piRuntimeBaseUrl, "");
    }

    private static String normalizeBaseUrl(String value, String fallback) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty()) return fallback;
        if (!normalized.contains("://")) normalized = "http://" + normalized;
        try {
            URI uri = URI.create(normalized);
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.US);
            if ((!"http".equals(scheme) && !"https".equals(scheme)) || uri.getHost() == null) {
                throw new IllegalArgumentException("Runtime URL must be an absolute HTTP(S) URL");
            }
            String result = uri.toString();
            while (result.endsWith("/")) result = result.substring(0, result.length() - 1);
            return result;
        } catch (RuntimeException error) {
            throw new IllegalArgumentException("Invalid runtime URL", error);
        }
    }

    public boolean hasServiceManagerToken() {
        return !serviceManagerToken.isEmpty();
    }

    @Override public String toString() {
        return "RuntimeConnection{serviceManagerBaseUrl='" + serviceManagerBaseUrl
            + "', serviceManagerToken=[REDACTED], piRuntimeBaseUrl='" + piRuntimeBaseUrl + "'}";
    }
}
