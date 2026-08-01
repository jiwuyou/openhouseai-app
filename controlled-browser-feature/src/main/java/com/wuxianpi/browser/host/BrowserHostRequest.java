package com.wuxianpi.browser.host;

import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.termux.app.browser.ControlledBrowserContract;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Iterator;
import java.util.UUID;

public final class BrowserHostRequest {
    public final String requestId;
    public final String method;
    public final String hostId;
    public final String tabId;
    public final JSONObject params;
    public final long timeoutMs;

    public BrowserHostRequest(
        @Nullable String requestId,
        @NonNull String method,
        @Nullable String hostId,
        @Nullable String tabId,
        @Nullable JSONObject params,
        long timeoutMs
    ) {
        this.requestId = text(requestId).isEmpty() ? UUID.randomUUID().toString() : text(requestId);
        this.method = text(method);
        this.hostId = text(hostId);
        this.tabId = text(tabId);
        this.params = params == null ? new JSONObject() : copy(params);
        this.timeoutMs = timeoutMs <= 0L ? 15_000L : Math.min(timeoutMs, 120_000L);
    }

    @NonNull
    public static BrowserHostRequest fromLegacyBundle(@Nullable Bundle extras) {
        Bundle source = extras == null ? new Bundle() : new Bundle(extras);
        String command = firstNonBlank(
            source.getString(ControlledBrowserContract.EXTRA_COMMAND),
            source.getString(ControlledBrowserContract.FIELD_COMMAND));
        String method = BrowserHostContract.methodForLegacyCommand(command);
        if (method == null) method = text(command);
        JSONObject params = bundleToJson(source);
        String tabId = firstNonBlank(
            source.getString(ControlledBrowserContract.EXTRA_TAB_ID),
            source.getString(ControlledBrowserContract.FIELD_TAB_ID));
        return new BrowserHostRequest(
            firstNonBlank(source.getString(ControlledBrowserContract.EXTRA_REQUEST_ID),
                source.getString(ControlledBrowserContract.FIELD_REQUEST_ID)),
            method,
            "",
            tabId,
            params,
            number(source.get(ControlledBrowserContract.EXTRA_TIMEOUT_MS), 15_000L));
    }

    @NonNull
    public static BrowserHostRequest fromJson(@NonNull JSONObject envelope) throws JSONException {
        JSONObject request = envelope.optJSONObject("request");
        if (request == null) request = envelope.optJSONObject("payload");
        if (request == null) request = envelope;
        JSONObject target = request.optJSONObject("target");
        JSONObject params = request.optJSONObject("params");
        if (params == null) params = new JSONObject();
        return new BrowserHostRequest(
            firstNonBlank(request.optString("requestId", null), request.optString("id", null),
                envelope.optString("requestId", null), envelope.optString("id", null)),
            request.optString("method", ""),
            firstNonBlank(target == null ? null : target.optString("hostId", null),
                request.optString("hostId", null)),
            firstNonBlank(target == null ? null : target.optString("tabId", null),
                request.optString("tabId", null), params.optString("tabId", null)),
            params,
            request.optLong("timeoutMs", envelope.optLong("timeoutMs", 15_000L)));
    }

    @NonNull
    public Bundle toLegacyBundle() {
        Bundle bundle = new Bundle();
        String command = BrowserHostContract.legacyCommandForMethod(method);
        bundle.putString(ControlledBrowserContract.EXTRA_COMMAND, command == null ? method : command);
        bundle.putString(ControlledBrowserContract.EXTRA_REQUEST_ID, requestId);
        bundle.putLong(ControlledBrowserContract.EXTRA_TIMEOUT_MS, timeoutMs);
        if (!tabId.isEmpty()) bundle.putString(ControlledBrowserContract.EXTRA_TAB_ID, tabId);
        bundle.putString(ControlledBrowserContract.EXTRA_PARAMS, params.toString());
        copyParam(bundle, params, "url", ControlledBrowserContract.EXTRA_URL);
        copyParam(bundle, params, "title", ControlledBrowserContract.EXTRA_TITLE);
        copyParam(bundle, params, "payload", ControlledBrowserContract.EXTRA_PAYLOAD);
        copyParam(bundle, params, "output", ControlledBrowserContract.EXTRA_OUTPUT);
        copyParam(bundle, params, "method", ControlledBrowserContract.EXTRA_METHOD);
        if (params.has("activate")) bundle.putBoolean(ControlledBrowserContract.EXTRA_ACTIVATE,
            params.optBoolean("activate", true));
        if (params.has("tabIndex")) bundle.putInt(ControlledBrowserContract.EXTRA_TAB_INDEX,
            params.optInt("tabIndex", -1));
        return bundle;
    }

    @NonNull
    public JSONObject toJson() {
        JSONObject value = new JSONObject();
        try {
            value.put("protocolVersion", BrowserHostContract.PROTOCOL_VERSION);
            value.put("requestId", requestId);
            value.put("method", method);
            JSONObject target = new JSONObject();
            if (!hostId.isEmpty()) target.put("hostId", hostId);
            if (!tabId.isEmpty()) target.put("tabId", tabId);
            value.put("target", target);
            value.put("params", params);
            value.put("timeoutMs", timeoutMs);
        } catch (JSONException error) {
            throw new IllegalStateException(error);
        }
        return value;
    }

    private static JSONObject bundleToJson(Bundle source) {
        JSONObject params = new JSONObject();
        for (String key : source.keySet()) {
            if (ControlledBrowserContract.EXTRA_REQUEST_ID.equals(key)
                || ControlledBrowserContract.EXTRA_COMMAND.equals(key)
                || ControlledBrowserContract.EXTRA_TOKEN.equals(key)
                || ControlledBrowserContract.EXTRA_REQUEST_FILE.equals(key)
                || ControlledBrowserContract.EXTRA_RESULT_FILE.equals(key)) continue;
            Object value = source.get(key);
            String shortKey = key.substring(key.lastIndexOf('.') + 1);
            try {
                params.put(shortKey, JSONObject.wrap(value));
            } catch (JSONException ignored) {}
        }
        copyBundleValue(source, params, ControlledBrowserContract.EXTRA_URL, "url");
        copyBundleValue(source, params, ControlledBrowserContract.EXTRA_TITLE, "title");
        copyBundleValue(source, params, ControlledBrowserContract.EXTRA_ACTIVATE, "activate");
        copyBundleValue(source, params, ControlledBrowserContract.EXTRA_TAB_INDEX, "tabIndex");
        copyBundleValue(source, params, ControlledBrowserContract.EXTRA_PAYLOAD, "payload");
        copyBundleValue(source, params, ControlledBrowserContract.EXTRA_OUTPUT, "output");
        copyBundleValue(source, params, ControlledBrowserContract.EXTRA_METHOD, "method");
        Object encoded = source.get(ControlledBrowserContract.EXTRA_PARAMS);
        if (encoded instanceof String) {
            try {
                JSONObject parsed = new JSONObject((String) encoded);
                Iterator<String> keys = parsed.keys();
                while (keys.hasNext()) {
                    String key = keys.next();
                    params.put(key, parsed.opt(key));
                }
            } catch (JSONException ignored) {}
        }
        return params;
    }

    private static void copyBundleValue(Bundle source, JSONObject target, String extra, String field) {
        if (!source.containsKey(extra)) return;
        try { target.put(field, JSONObject.wrap(source.get(extra))); }
        catch (JSONException ignored) {}
    }

    private static void copyParam(Bundle bundle, JSONObject params, String name, String extra) {
        if (!params.has(name) || params.isNull(name)) return;
        bundle.putString(extra, params.optString(name, ""));
    }

    private static JSONObject copy(JSONObject source) {
        try { return new JSONObject(source.toString()); }
        catch (JSONException error) { return new JSONObject(); }
    }

    private static long number(Object value, long fallback) {
        if (value instanceof Number) return ((Number) value).longValue();
        try { return value == null ? fallback : Long.parseLong(value.toString()); }
        catch (NumberFormatException ignored) { return fallback; }
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) if (!text(value).isEmpty()) return text(value);
        return "";
    }

    private static String text(String value) { return value == null ? "" : value.trim(); }
}
