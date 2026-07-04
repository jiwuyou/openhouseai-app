package com.termux.app.openhouse;

import android.content.Context;
import android.content.SharedPreferences;

public final class OpenHouseNetworkLinePreferences {

    public static final String PREFS_NAME = "openhouse_network_line_preferences";
    public static final String KEY_SELECTED_LINE = "selected_line";

    private OpenHouseNetworkLinePreferences() {}

    public static OpenHouseNetworkLine getSelectedLine(Context context) {
        if (context == null) {
            return OpenHouseNetworkLine.CN;
        }
        String value = prefs(context).getString(
            KEY_SELECTED_LINE,
            OpenHouseNetworkLine.CN.getPreferenceValue()
        );
        return OpenHouseNetworkLine.fromPreferenceValue(value);
    }

    public static void setSelectedLine(Context context, OpenHouseNetworkLine line) {
        if (context == null) {
            return;
        }
        OpenHouseNetworkLine resolvedLine = line == null ? OpenHouseNetworkLine.CN : line;
        prefs(context).edit()
            .putString(KEY_SELECTED_LINE, resolvedLine.getPreferenceValue())
            .apply();
    }

    public static void resetSelectedLine(Context context) {
        if (context == null) {
            return;
        }
        prefs(context).edit().remove(KEY_SELECTED_LINE).apply();
    }

    public static String getSelectedLineLabel(Context context) {
        return getSelectedLine(context).getLabel();
    }

    public static OpenHouseInstallState.RetryMode getSelectedRetryMode(Context context) {
        return getSelectedLine(context).toRetryMode();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext()
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }
}
