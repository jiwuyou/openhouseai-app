package com.termux.app.activities;

import android.content.Context;

import com.termux.shared.termux.TermuxConstants;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

final class MaintainerLogStore {

    private static final String LOG_DIR_PATH = TermuxConstants.TERMUX_HOME_DIR_PATH + "/.maintainer-logs";

    private MaintainerLogStore() {
    }

    static File getLogFile(String stageSlug) {
        return new File(LOG_DIR_PATH, sanitizeStageSlug(stageSlug) + ".log");
    }

    static boolean hasLog(String stageSlug) {
        File file = getLogFile(stageSlug);
        return file.isFile() && file.length() > 0;
    }

    static String readLog(String stageSlug) throws IOException {
        File file = getLogFile(stageSlug);
        if (!file.isFile()) {
            return "";
        }

        StringBuilder builder = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
            new FileInputStream(file), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                builder.append(line).append('\n');
            }
        }

        return builder.toString();
    }

    static String readTail(String stageSlug, int charLimit) throws IOException {
        String content = readLog(stageSlug);
        if (content.length() <= charLimit) {
            return content;
        }

        int start = content.length() - charLimit;
        int newlineIndex = content.indexOf('\n', start);
        if (newlineIndex >= 0 && newlineIndex + 1 < content.length()) {
            return content.substring(newlineIndex + 1);
        }

        return content.substring(start);
    }

    static void writeLog(Context context, String stageSlug, String content) throws IOException {
        File logDir = ensureLogDir(context);
        File file = new File(logDir, sanitizeStageSlug(stageSlug) + ".log");
        try (FileOutputStream outputStream = new FileOutputStream(file, false)) {
            outputStream.write((content == null ? "" : content).getBytes(StandardCharsets.UTF_8));
        }
    }

    static File ensureLogDir(Context context) {
        File logDir = new File(LOG_DIR_PATH);
        if (!logDir.exists()) {
            logDir.mkdirs();
        }
        return logDir;
    }

    private static String sanitizeStageSlug(String value) {
        if (value == null || value.trim().isEmpty()) {
            return "stage";
        }

        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char current = value.charAt(i);
            if ((current >= 'a' && current <= 'z')
                || (current >= 'A' && current <= 'Z')
                || (current >= '0' && current <= '9')
                || current == '_'
                || current == '-') {
                builder.append(current);
            } else {
                builder.append('_');
            }
        }

        return builder.length() == 0 ? "stage" : builder.toString();
    }
}
