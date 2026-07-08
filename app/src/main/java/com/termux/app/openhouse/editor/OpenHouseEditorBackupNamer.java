package com.termux.app.openhouse.editor;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

public final class OpenHouseEditorBackupNamer {

    public static final String BACKUP_DIR_NAME = "editor-backups";

    private OpenHouseEditorBackupNamer() {
    }

    public static File buildBackupFile(File backupRoot, String displayName, long timeMillis) {
        if (backupRoot == null) throw new IllegalArgumentException("backupRoot == null");
        return new File(backupRoot, buildBackupFileName(displayName, timeMillis));
    }

    public static String buildBackupFileName(String displayName, long timeMillis) {
        String safeName = sanitizeDisplayName(displayName);
        String stamp = timestamp(timeMillis);
        return safeName + "." + stamp + ".bak";
    }

    public static String sanitizeDisplayName(String displayName) {
        String value = displayName == null ? "" : displayName.trim();
        if (value.isEmpty()) value = "untitled";
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < 32 || c == '/' || c == '\\' || c == ':' || c == '*' || c == '?' || c == '"'
                || c == '<' || c == '>' || c == '|') {
                builder.append('_');
            } else {
                builder.append(c);
            }
        }
        String sanitized = builder.toString();
        while (sanitized.contains("__")) {
            sanitized = sanitized.replace("__", "_");
        }
        if (sanitized.length() > 96) {
            sanitized = sanitized.substring(0, 96);
        }
        sanitized = sanitized.trim();
        return sanitized.isEmpty() ? "untitled" : sanitized;
    }

    private static String timestamp(long timeMillis) {
        SimpleDateFormat format = new SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US);
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        return format.format(new Date(timeMillis));
    }
}
