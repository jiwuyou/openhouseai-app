package com.termux.app.openhouse.desktop;

import org.json.JSONObject;

public final class DesktopIconOverride {

    public static final DesktopIconOverride EMPTY = new DesktopIconOverride("", "", "");

    public final String key;
    public final String label;
    public final String color;

    public DesktopIconOverride(String key, String label, String color) {
        this.key = safeTrim(key);
        this.label = safeTrim(label);
        this.color = normalizeColor(color);
    }

    public static DesktopIconOverride of(String key, String label, String color) {
        DesktopIconOverride override = new DesktopIconOverride(key, label, color);
        return override.isEmpty() ? EMPTY : override;
    }

    static DesktopIconOverride fromJson(JSONObject json) {
        if (json == null) {
            return EMPTY;
        }
        return of(
            json.optString("key", ""),
            json.optString("label", ""),
            json.optString("color", ""));
    }

    JSONObject toJson() {
        JSONObject json = new JSONObject();
        putIfNotEmpty(json, "key", key);
        putIfNotEmpty(json, "label", label);
        putIfNotEmpty(json, "color", color);
        return json;
    }

    public boolean isEmpty() {
        return key.isEmpty() && label.isEmpty() && color.isEmpty();
    }

    private static void putIfNotEmpty(JSONObject json, String key, String value) {
        if (!safeTrim(value).isEmpty()) {
            try {
                json.put(key, safeTrim(value));
            } catch (Exception ignored) {
                // Ignore invalid JSON writes; icon overrides are best-effort preferences.
            }
        }
    }

    private static String normalizeColor(String value) {
        String text = safeTrim(value);
        if (text.isEmpty()) {
            return "";
        }
        if (!text.startsWith("#")) {
            text = "#" + text;
        }
        String hex = text.substring(1);
        if (hex.length() != 6 && hex.length() != 8) {
            return "";
        }
        for (int i = 0; i < hex.length(); i++) {
            char ch = hex.charAt(i);
            boolean digit = ch >= '0' && ch <= '9';
            boolean lower = ch >= 'a' && ch <= 'f';
            boolean upper = ch >= 'A' && ch <= 'F';
            if (!digit && !lower && !upper) {
                return "";
            }
        }
        return "#" + hex.toUpperCase();
    }

    private static String safeTrim(String value) {
        return value == null ? "" : value.trim();
    }
}
