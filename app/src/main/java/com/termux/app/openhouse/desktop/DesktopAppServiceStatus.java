package com.termux.app.openhouse.desktop;

public final class DesktopAppServiceStatus {

    public final boolean success;
    public final String serviceId;
    public final String state;
    public final String provider;
    public final int pid;
    public final String url;
    public final String message;
    public final int code;

    public DesktopAppServiceStatus(
        boolean success,
        String serviceId,
        String state,
        String provider,
        int pid,
        String url,
        String message,
        int code
    ) {
        this.success = success;
        this.serviceId = safeTrim(serviceId);
        this.state = firstNonBlank(state, "unknown");
        this.provider = safeTrim(provider);
        this.pid = pid;
        this.url = safeTrim(url);
        this.message = safeTrim(message);
        this.code = code;
    }

    public boolean isRunning() {
        String normalized = state.toLowerCase(java.util.Locale.US);
        return success && ("running".equals(normalized) || "ready".equals(normalized) || "started".equals(normalized));
    }

    public boolean isStopped() {
        String normalized = state.toLowerCase(java.util.Locale.US);
        return "stopped".equals(normalized) || "exited".equals(normalized) || "inactive".equals(normalized);
    }

    public boolean isStarting() {
        String normalized = state.toLowerCase(java.util.Locale.US);
        return "starting".equals(normalized) || "pending".equals(normalized) || "loading".equals(normalized);
    }

    public String displayLine() {
        StringBuilder builder = new StringBuilder();
        builder.append(serviceId.isEmpty() ? "service" : serviceId);
        builder.append("：").append(state);
        if (pid > 0) {
            builder.append(" pid=").append(pid);
        }
        if (!message.isEmpty()) {
            builder.append("，").append(message);
        }
        return builder.toString();
    }

    private static String firstNonBlank(String first, String fallback) {
        String text = safeTrim(first);
        return text.isEmpty() ? safeTrim(fallback) : text;
    }

    private static String safeTrim(String value) {
        return value == null ? "" : value.trim();
    }
}
