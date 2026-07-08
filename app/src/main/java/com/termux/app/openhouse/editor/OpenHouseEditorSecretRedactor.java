package com.termux.app.openhouse.editor;

import java.net.URI;
import java.net.URISyntaxException;

public final class OpenHouseEditorSecretRedactor {

    private OpenHouseEditorSecretRedactor() {
    }

    public static String redact(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        if (trimmed.isEmpty()) return value;

        URI uri = parseUri(trimmed);
        if (uri != null && uri.getScheme() != null) {
            return redactUri(uri);
        }
        return redactPathLikeValue(value);
    }

    private static URI parseUri(String value) {
        try {
            return new URI(value);
        } catch (URISyntaxException e) {
            return null;
        }
    }

    private static String redactUri(URI uri) {
        if (uri.isOpaque()) {
            return uri.getScheme() + ":[redacted]";
        }

        StringBuilder builder = new StringBuilder();
        builder.append(uri.getScheme()).append(':');
        String authority = stripUserInfo(uri.getRawAuthority());
        if (authority != null) {
            builder.append("//").append(authority);
        }
        String path = uri.getRawPath();
        if (path != null) {
            builder.append(path);
        }
        return builder.toString();
    }

    private static String stripUserInfo(String authority) {
        if (authority == null) return null;
        int at = authority.lastIndexOf('@');
        if (at < 0) return authority;
        return authority.substring(at + 1);
    }

    private static String redactPathLikeValue(String value) {
        int queryIndex = firstIndexOf(value, '?', '#');
        if (queryIndex < 0) {
            return value;
        }
        String prefix = value.substring(0, queryIndex);
        return prefix + "?[redacted]";
    }

    private static int firstIndexOf(String value, char left, char right) {
        int leftIndex = value.indexOf(left);
        int rightIndex = value.indexOf(right);
        if (leftIndex < 0) return rightIndex;
        if (rightIndex < 0) return leftIndex;
        return Math.min(leftIndex, rightIndex);
    }
}
