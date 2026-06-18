package com.termux.app.browser;

import androidx.annotation.Nullable;

import org.json.JSONException;
import org.json.JSONObject;

public final class ControlledBrowserCommandResult {

    private final boolean successful;
    private final String message;
    private final String activeTabId;
    private final String activeUrl;
    private final int tabCount;
    private final String requestId;
    private final long durationMs;
    private final JSONObject data;
    private final JSONObject error;

    public static ControlledBrowserCommandResult ok(
        @Nullable String message,
        @Nullable String activeTabId,
        @Nullable String activeUrl,
        int tabCount
    ) {
        return ok(message, activeTabId, activeUrl, tabCount, null, null, 0L);
    }

    public static ControlledBrowserCommandResult ok(
        @Nullable String message,
        @Nullable String activeTabId,
        @Nullable String activeUrl,
        int tabCount,
        @Nullable String requestId,
        @Nullable JSONObject data,
        long durationMs
    ) {
        return new ControlledBrowserCommandResult(
            true, message, activeTabId, activeUrl, tabCount, requestId, durationMs, data, null);
    }

    public static ControlledBrowserCommandResult error(
        String message,
        @Nullable String activeTabId,
        @Nullable String activeUrl,
        int tabCount
    ) {
        return error(message, activeTabId, activeUrl, tabCount, null, null, 0L);
    }

    public static ControlledBrowserCommandResult error(
        String message,
        @Nullable String activeTabId,
        @Nullable String activeUrl,
        int tabCount,
        @Nullable String requestId,
        @Nullable JSONObject error,
        long durationMs
    ) {
        return new ControlledBrowserCommandResult(
            false, message, activeTabId, activeUrl, tabCount, requestId, durationMs, null,
            error == null ? buildErrorObject("browser_error", message) : error);
    }

    private ControlledBrowserCommandResult(
        boolean successful,
        @Nullable String message,
        @Nullable String activeTabId,
        @Nullable String activeUrl,
        int tabCount,
        @Nullable String requestId,
        long durationMs,
        @Nullable JSONObject data,
        @Nullable JSONObject error
    ) {
        this.successful = successful;
        this.message = message == null ? "" : message;
        this.activeTabId = activeTabId;
        this.activeUrl = activeUrl;
        this.tabCount = tabCount;
        this.requestId = requestId;
        this.durationMs = Math.max(0L, durationMs);
        this.data = data;
        this.error = error;
    }

    public boolean isSuccessful() {
        return successful;
    }

    public String getMessage() {
        return message;
    }

    @Nullable
    public String getActiveTabId() {
        return activeTabId;
    }

    @Nullable
    public String getActiveUrl() {
        return activeUrl;
    }

    public int getTabCount() {
        return tabCount;
    }

    @Nullable
    public String getRequestId() {
        return requestId;
    }

    public long getDurationMs() {
        return durationMs;
    }

    @Nullable
    public JSONObject getData() {
        return data;
    }

    @Nullable
    public JSONObject getError() {
        return error;
    }

    public ControlledBrowserCommandResult withRequest(@Nullable String requestId, long durationMs) {
        return new ControlledBrowserCommandResult(
            successful, message, activeTabId, activeUrl, tabCount, requestId, durationMs, data, error);
    }

    public JSONObject toJsonObject() {
        JSONObject object = new JSONObject();
        try {
            object.put("ok", successful);
            object.put("successful", successful);
            object.put("message", message);
            object.put("requestId", requestId == null ? JSONObject.NULL : requestId);
            object.put("durationMs", durationMs);
            object.put("activeTabId", activeTabId == null ? JSONObject.NULL : activeTabId);
            object.put("activeUrl", activeUrl == null ? JSONObject.NULL : activeUrl);
            object.put("tabCount", tabCount);
            object.put("data", data == null ? JSONObject.NULL : data);
            object.put("error", error == null ? JSONObject.NULL : error);
        } catch (JSONException e) {
            throw new IllegalStateException("Failed to build browser command result JSON", e);
        }
        return object;
    }

    public String toJsonString() {
        return toJsonObject().toString();
    }

    private static JSONObject buildErrorObject(String code, String message) {
        JSONObject object = new JSONObject();
        try {
            object.put("code", code);
            object.put("message", message == null ? "" : message);
        } catch (JSONException e) {
            throw new IllegalStateException("Failed to build browser command error JSON", e);
        }
        return object;
    }
}
