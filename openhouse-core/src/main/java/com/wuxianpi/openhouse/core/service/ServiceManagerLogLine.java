package com.wuxianpi.openhouse.core.service;

import org.json.JSONObject;

public final class ServiceManagerLogLine {
    public final String time;
    public final String stream;
    public final String message;
    public final String raw;

    ServiceManagerLogLine(String time, String stream, String message, String raw) {
        this.time = text(time); this.stream = text(stream); this.message = text(message); this.raw = text(raw);
    }

    static ServiceManagerLogLine fromJson(JSONObject json) {
        return new ServiceManagerLogLine(first(json.optString("time", ""), json.optString("timestamp", "")),
            json.optString("stream", ""), first(json.optString("message", ""), json.optString("line", "")), json.toString());
    }

    private static String first(String a, String b) { return text(a).isEmpty() ? text(b) : text(a); }
    private static String text(String value) { return value == null ? "" : value.trim(); }
}
