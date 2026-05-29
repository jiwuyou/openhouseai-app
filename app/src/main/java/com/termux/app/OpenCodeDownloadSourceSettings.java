package com.termux.app;

import android.content.Context;
import android.content.SharedPreferences;

public final class OpenCodeDownloadSourceSettings {

    public static final String SOURCE_OFFICIAL = "official";
    public static final String SOURCE_MIRROR = "mirror";
    public static final String OFFICIAL_INSTALL_URL = "https://opencode.ai/install";
    public static final String MIRROR_INSTALL_URL = "https://raw.githubusercontent.com/sst/opencode/refs/heads/dev/install";
    public static final long PROBE_FRESH_DURATION_MS = 24L * 60L * 60L * 1000L;

    private static final String PREFS_NAME = "opencode_download_source_settings";
    private static final String KEY_MODE = "mode";
    private static final String KEY_LAST_SELECTED_SOURCE = "last_selected_source";
    private static final String KEY_LAST_PROBE_AT = "last_probe_at";
    private static final String KEY_LAST_PROBE_SUMMARY = "last_probe_summary";

    private OpenCodeDownloadSourceSettings() {
    }

    public enum Mode {
        AUTO("auto"),
        OFFICIAL_ONLY("official_only"),
        MIRROR_ONLY("mirror_only");

        private final String value;

        Mode(String value) {
            this.value = value;
        }

        public String getValue() {
            return value;
        }

        public static Mode fromValue(String value) {
            for (Mode mode : values()) {
                if (mode.value.equals(value)) {
                    return mode;
                }
            }
            return AUTO;
        }
    }

    public static Mode getMode(Context context) {
        SharedPreferences preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return Mode.fromValue(preferences.getString(KEY_MODE, Mode.AUTO.getValue()));
    }

    public static void setMode(Context context, Mode mode) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_MODE, mode.getValue())
            .apply();
    }

    public static String getLastSelectedSourceId(Context context) {
        SharedPreferences preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return normalizeSourceId(preferences.getString(KEY_LAST_SELECTED_SOURCE, SOURCE_OFFICIAL));
    }

    public static void setLastSelectedSourceId(Context context, String sourceId) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LAST_SELECTED_SOURCE, normalizeSourceId(sourceId))
            .apply();
    }

    public static long getLastProbeAt(Context context) {
        SharedPreferences preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return preferences.getLong(KEY_LAST_PROBE_AT, 0L);
    }

    public static String getLastProbeSummary(Context context) {
        SharedPreferences preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return preferences.getString(KEY_LAST_PROBE_SUMMARY, "");
    }

    public static void setLastProbeResult(Context context, String selectedSourceId, String summary, long timestamp) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LAST_SELECTED_SOURCE, normalizeSourceId(selectedSourceId))
            .putString(KEY_LAST_PROBE_SUMMARY, summary == null ? "" : summary)
            .putLong(KEY_LAST_PROBE_AT, Math.max(0L, timestamp))
            .apply();
    }

    public static boolean isLastProbeFresh(Context context) {
        long lastProbeAt = getLastProbeAt(context);
        return lastProbeAt > 0L && (System.currentTimeMillis() - lastProbeAt) <= PROBE_FRESH_DURATION_MS;
    }

    public static String normalizeSourceId(String sourceId) {
        if (SOURCE_MIRROR.equals(sourceId)) {
            return SOURCE_MIRROR;
        }
        return SOURCE_OFFICIAL;
    }

    public static String getInstallUrlForSource(String sourceId) {
        return SOURCE_MIRROR.equals(normalizeSourceId(sourceId)) ? MIRROR_INSTALL_URL : OFFICIAL_INSTALL_URL;
    }
}
