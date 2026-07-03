package com.termux.app.openhouse.servicecontrol;

import org.json.JSONObject;

public final class ServiceManagerLogLine {

    public final String time;
    public final String stream;
    public final String message;
    public final String raw;

    ServiceManagerLogLine(String time, String stream, String message, String raw) {
        this.time = time == null ? "" : time;
        this.stream = stream == null ? "" : stream;
        this.message = message == null ? "" : message;
        this.raw = raw == null ? "" : raw;
    }

    static ServiceManagerLogLine fromJson(JSONObject json) {
        if (json == null) {
            return new ServiceManagerLogLine("", "", "", "");
        }
        return new ServiceManagerLogLine(
            firstNonBlank(json.optString("time", ""), json.optString("timestamp", "")),
            json.optString("stream", ""),
            ServiceManagerRedactor.redact(firstNonBlank(json.optString("message", ""), json.optString("line", ""))),
            ServiceManagerRedactor.redact(json.toString())
        );
    }

    private static String firstNonBlank(String first, String second) {
        String value = safeTrim(first);
        return value.isEmpty() ? safeTrim(second) : value;
    }

    private static String safeTrim(String value) {
        return value == null ? "" : value.trim();
    }
}
