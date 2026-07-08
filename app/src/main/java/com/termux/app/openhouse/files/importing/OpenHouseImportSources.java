package com.termux.app.openhouse.files.importing;

import android.net.Uri;

import java.nio.charset.StandardCharsets;

public final class OpenHouseImportSources {

    public static final String DEFAULT_SHARED_TEXT_FILE_NAME = "shared-text.txt";
    public static final String DEFAULT_SHARED_TEXT_MIME_TYPE = "text/plain";
    public static final String SHARED_TEXT_ANDROID_LOCATION = "Android share sheet text payload";

    private OpenHouseImportSources() {
    }

    public static byte[] sharedTextBytes(String sharedText) {
        String text = sharedText == null ? "" : sharedText;
        return text.getBytes(StandardCharsets.UTF_8);
    }

    public static String sharedTextFileName(String subject) {
        if (isBlank(subject)) {
            return DEFAULT_SHARED_TEXT_FILE_NAME;
        }
        if (subject.contains(".")) {
            return subject;
        }
        return subject + ".txt";
    }

    public static OpenHouseImportSource forSharedText(String sharedText, String subject, String mimeType) {
        byte[] bytes = sharedTextBytes(sharedText);
        return OpenHouseImportSource.builder()
            .setSuggestedFileName(sharedTextFileName(subject))
            .setMimeType(isBlank(mimeType) ? DEFAULT_SHARED_TEXT_MIME_TYPE : mimeType)
            .setSizeBytes(bytes.length)
            .setAndroidDisplayLocation(SHARED_TEXT_ANDROID_LOCATION)
            .build();
    }

    public static String chooseContentFileName(String displayName, String subjectFromIntent, String uriBasename) {
        if (!isBlank(displayName)) {
            return displayName;
        }
        if (!isBlank(subjectFromIntent)) {
            return subjectFromIntent;
        }
        if (!isBlank(uriBasename)) {
            return uriBasename;
        }
        return null;
    }

    public static String buildAndroidDisplayLocation(Uri uri, String fileName) {
        String authority = uri == null ? null : uri.getAuthority();
        return buildAndroidDisplayLocation(authority, fileName);
    }

    public static String buildAndroidDisplayLocation(String authority, String fileName) {
        StringBuilder builder = new StringBuilder();
        if (!isBlank(authority)) {
            builder.append(authority);
        } else {
            builder.append("Android content provider");
        }
        if (!isBlank(fileName)) {
            builder.append(" / ").append(fileName);
        }
        return builder.toString();
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
