package com.wuxianpi.browser.host;

import androidx.annotation.NonNull;

import com.termux.app.browser.ControlledBrowserCommandResult;

import org.json.JSONException;
import org.json.JSONObject;

public final class BrowserHostResponse {
    public final String requestId;
    public final boolean ok;
    public final JSONObject result;
    public final JSONObject error;

    public BrowserHostResponse(String requestId, boolean ok, JSONObject result, JSONObject error) {
        this.requestId = requestId == null ? "" : requestId;
        this.ok = ok;
        this.result = result == null ? new JSONObject() : result;
        this.error = error;
    }

    @NonNull
    public static BrowserHostResponse fromLegacy(@NonNull ControlledBrowserCommandResult result) {
        JSONObject value = result.getData() == null ? new JSONObject() : result.getData();
        try {
            value.put("message", result.getMessage());
            value.put("activeTabId", result.getActiveTabId() == null ? JSONObject.NULL : result.getActiveTabId());
            value.put("activeUrl", result.getActiveUrl() == null ? JSONObject.NULL : result.getActiveUrl());
            value.put("tabCount", result.getTabCount());
            value.put("durationMs", result.getDurationMs());
        } catch (JSONException error) {
            throw new IllegalStateException(error);
        }
        return new BrowserHostResponse(result.getRequestId(), result.isSuccessful(), value, result.getError());
    }

    @NonNull
    public static BrowserHostResponse error(String requestId, String code, String message) {
        JSONObject error = new JSONObject();
        try {
            error.put("code", code);
            error.put("message", message == null ? "" : message);
        } catch (JSONException impossible) {
            throw new IllegalStateException(impossible);
        }
        return new BrowserHostResponse(requestId, false, new JSONObject(), error);
    }

    @NonNull
    public JSONObject toJson() {
        JSONObject value = new JSONObject();
        try {
            value.put("type", "browser.result");
            value.put("id", requestId);
            value.put("ok", ok);
            if (ok) value.put("result", result);
            else value.put("error", error);
        } catch (JSONException error) {
            throw new IllegalStateException(error);
        }
        return value;
    }

    @NonNull
    public ControlledBrowserCommandResult toLegacyResult() {
        String message = result.optString("message", error == null ? "" : error.optString("message", ""));
        String activeTabId = result.optString("activeTabId", null);
        String activeUrl = result.optString("activeUrl", null);
        int tabCount = result.optInt("tabCount", 0);
        long durationMs = result.optLong("durationMs", 0L);
        JSONObject data;
        try {
            data = new JSONObject(result.toString());
        } catch (JSONException ignored) {
            data = new JSONObject();
        }
        data.remove("message");
        data.remove("activeTabId");
        data.remove("activeUrl");
        data.remove("tabCount");
        data.remove("durationMs");
        return ok
            ? ControlledBrowserCommandResult.ok(message, activeTabId, activeUrl, tabCount,
                requestId, data, durationMs)
            : ControlledBrowserCommandResult.error(message, activeTabId, activeUrl, tabCount,
                requestId, error, durationMs);
    }
}
