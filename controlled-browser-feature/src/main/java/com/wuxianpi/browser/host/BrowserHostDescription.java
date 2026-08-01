package com.wuxianpi.browser.host;

import org.json.JSONException;
import org.json.JSONObject;

public final class BrowserHostDescription {
    public final String hostId;
    public final String implementation;
    public final int priority;
    public final JSONObject capabilities;

    public BrowserHostDescription(String hostId, String implementation, int priority, JSONObject capabilities) {
        this.hostId = hostId;
        this.implementation = implementation;
        this.priority = priority;
        this.capabilities = capabilities;
    }

    public static BrowserHostDescription nativeHost() {
        return standard("native-browser", "wuxianpi-native", 200);
    }

    public static BrowserHostDescription allInOneHost() {
        return standard("all-in-one-browser", "wuxianpi-all-in-one", 100);
    }

    private static BrowserHostDescription standard(String hostId, String implementation, int priority) {
        JSONObject capabilities = new JSONObject();
        try {
            capabilities.put("tabs", true);
            capabilities.put("javascript", true);
            capabilities.put("dom", true);
            capabilities.put("touch", true);
            capabilities.put("screenshot", true);
            capabilities.put("workflow", true);
            capabilities.put("cdp", true);
            capabilities.put("frontendActions", true);
        } catch (JSONException error) {
            throw new IllegalStateException(error);
        }
        return new BrowserHostDescription(hostId, implementation, priority, capabilities);
    }

    public JSONObject toJson() {
        JSONObject value = new JSONObject();
        try {
            value.put("hostId", hostId);
            value.put("protocol", BrowserHostContract.PROTOCOL_NAME);
            value.put("protocolVersion", BrowserHostContract.PROTOCOL_VERSION);
            value.put("implementation", implementation);
            value.put("priority", priority);
            value.put("preferred", "native-browser".equals(hostId));
            value.put("capabilities", capabilities);
        } catch (JSONException error) {
            throw new IllegalStateException(error);
        }
        return value;
    }
}
