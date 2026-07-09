package com.termux.app.openhouse.files.importing;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public enum OpenHouseInboxGrouping {
    NONE("none", ""),
    DAY("day", "yyyy-MM-dd"),
    MONTH("month", "yyyy-MM"),
    YEAR("year", "yyyy");

    public static final OpenHouseInboxGrouping DEFAULT = MONTH;

    private final String preferenceValue;
    private final String datePattern;

    OpenHouseInboxGrouping(String preferenceValue, String datePattern) {
        this.preferenceValue = preferenceValue;
        this.datePattern = datePattern;
    }

    public String getPreferenceValue() {
        return preferenceValue;
    }

    public String getDirectoryName(Date date) {
        if (this == NONE) {
            return "";
        }
        Date safeDate = date == null ? new Date() : date;
        return new SimpleDateFormat(datePattern, Locale.US).format(safeDate);
    }

    public static OpenHouseInboxGrouping fromPreferenceValue(String value) {
        if (value == null) {
            return DEFAULT;
        }
        String normalized = value.trim();
        if (normalized.isEmpty()) {
            return DEFAULT;
        }
        for (OpenHouseInboxGrouping grouping : values()) {
            if (grouping.preferenceValue.equalsIgnoreCase(normalized)
                || grouping.name().equalsIgnoreCase(normalized)) {
                return grouping;
            }
        }
        return DEFAULT;
    }
}
