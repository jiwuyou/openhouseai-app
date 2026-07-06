package com.termux.app.openhouse.desktop;

import org.json.JSONObject;

public final class DesktopAppOverride {

    public static final DesktopAppOverride EMPTY = new DesktopAppOverride("", DesktopIconOverride.EMPTY);

    public final String title;
    public final DesktopIconOverride icon;

    public DesktopAppOverride(String title, DesktopIconOverride icon) {
        this.title = safeTrim(title);
        this.icon = icon == null ? DesktopIconOverride.EMPTY : icon;
    }

    public static DesktopAppOverride of(String title, DesktopIconOverride icon) {
        DesktopAppOverride override = new DesktopAppOverride(title, icon);
        return override.isEmpty() ? EMPTY : override;
    }

    static DesktopAppOverride fromJson(JSONObject json) {
        if (json == null) {
            return EMPTY;
        }
        return of(
            json.optString("title", ""),
            DesktopIconOverride.fromJson(json.optJSONObject("icon")));
    }

    JSONObject toJson() {
        JSONObject json = new JSONObject();
        try {
            if (!title.isEmpty()) {
                json.put("title", title);
            }
            if (!icon.isEmpty()) {
                json.put("icon", icon.toJson());
            }
        } catch (Exception ignored) {
            // Ignore invalid JSON writes; desktop overrides are best-effort preferences.
        }
        return json;
    }

    public boolean isEmpty() {
        return title.isEmpty() && icon.isEmpty();
    }

    private static String safeTrim(String value) {
        return value == null ? "" : value.trim();
    }
}
