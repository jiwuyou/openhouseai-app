package com.wuxianpi.browser.host;

import org.json.JSONException;
import org.json.JSONObject;

public final class BrowserHostEvent {
    public final String name;
    public final JSONObject data;
    public final long timestamp;

    public BrowserHostEvent(String name, JSONObject data) {
        this.name = name;
        this.data = data == null ? new JSONObject() : data;
        this.timestamp = System.currentTimeMillis();
    }

    public JSONObject toJson() {
        JSONObject value = new JSONObject();
        try {
            value.put("type", "browser.event");
            value.put("event", name);
            value.put("at", timestamp);
            value.put("data", data);
        } catch (JSONException error) {
            throw new IllegalStateException(error);
        }
        return value;
    }
}
