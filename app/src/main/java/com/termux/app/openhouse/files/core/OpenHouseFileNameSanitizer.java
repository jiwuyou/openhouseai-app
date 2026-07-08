package com.termux.app.openhouse.files.core;

public final class OpenHouseFileNameSanitizer {

    private static final int MAX_FILE_NAME_LENGTH = 120;
    private static final String DEFAULT_FILE_NAME = "shared-file";

    private OpenHouseFileNameSanitizer() {
    }

    public static String sanitize(String requestedName) {
        return sanitize(requestedName, DEFAULT_FILE_NAME);
    }

    public static String sanitize(String requestedName, String fallbackName) {
        String sanitized = sanitizeOnce(requestedName);
        if (isUnsafeEmptyOrDot(sanitized)) {
            sanitized = sanitizeOnce(fallbackName);
        }
        if (isUnsafeEmptyOrDot(sanitized)) {
            sanitized = DEFAULT_FILE_NAME;
        }
        return trimFileName(sanitized);
    }

    private static String sanitizeOnce(String value) {
        if (value == null) {
            return "";
        }

        String name = value.trim();
        int lastSlash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        if (lastSlash >= 0 && lastSlash < name.length() - 1) {
            name = name.substring(lastSlash + 1);
        }

        StringBuilder builder = new StringBuilder(name.length());
        boolean lastWasReplacement = false;
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            boolean replace = c < 32 || c == 127 || c == '/' || c == '\\'
                || c == ':' || c == '*' || c == '?' || c == '"' || c == '<'
                || c == '>' || c == '|';
            if (replace) {
                if (!lastWasReplacement) {
                    builder.append('_');
                    lastWasReplacement = true;
                }
            } else {
                builder.append(c);
                lastWasReplacement = false;
            }
        }
        return trimEdgeDotsAndSpaces(builder.toString());
    }

    private static String trimEdgeDotsAndSpaces(String value) {
        int start = 0;
        int end = value.length();
        while (start < end && isTrimmedEdgeChar(value.charAt(start))) {
            start++;
        }
        while (end > start && isTrimmedEdgeChar(value.charAt(end - 1))) {
            end--;
        }
        return value.substring(start, end);
    }

    private static boolean isTrimmedEdgeChar(char c) {
        return c == '.' || Character.isWhitespace(c);
    }

    private static boolean isUnsafeEmptyOrDot(String name) {
        return name == null || name.isEmpty() || ".".equals(name) || "..".equals(name);
    }

    private static String trimFileName(String name) {
        if (name.length() <= MAX_FILE_NAME_LENGTH) {
            return name;
        }

        int dot = name.lastIndexOf('.');
        String extension = "";
        if (dot > 0 && name.length() - dot <= 16) {
            extension = name.substring(dot);
        }
        int baseLength = Math.max(1, MAX_FILE_NAME_LENGTH - extension.length());
        return name.substring(0, baseLength) + extension;
    }
}
