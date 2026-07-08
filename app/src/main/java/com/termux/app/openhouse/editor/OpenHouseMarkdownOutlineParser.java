package com.termux.app.openhouse.editor;

import java.util.ArrayList;
import java.util.List;

public final class OpenHouseMarkdownOutlineParser {

    private OpenHouseMarkdownOutlineParser() {
    }

    public static List<OpenHouseMarkdownHeading> parse(String markdown) {
        List<OpenHouseMarkdownHeading> headings = new ArrayList<>();
        if (markdown == null || markdown.isEmpty()) {
            return headings;
        }

        String[] lines = markdown.split("\\r\\n|\\n|\\r", -1);
        boolean inFence = false;
        String previousContentLine = null;
        int previousContentLineNumber = -1;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            int lineNumber = i + 1;
            if (isFenceLine(line)) {
                inFence = !inFence;
                previousContentLine = null;
                previousContentLineNumber = -1;
                continue;
            }
            if (inFence) {
                continue;
            }

            OpenHouseMarkdownHeading heading = parseAtxHeading(line, lineNumber);
            if (heading != null) {
                headings.add(heading);
                previousContentLine = null;
                previousContentLineNumber = -1;
                continue;
            }

            int setextLevel = parseSetextLevel(line);
            if (setextLevel > 0 && previousContentLine != null) {
                headings.add(new OpenHouseMarkdownHeading(setextLevel, previousContentLine.trim(), previousContentLineNumber));
                previousContentLine = null;
                previousContentLineNumber = -1;
                continue;
            }

            if (line.trim().isEmpty()) {
                previousContentLine = null;
                previousContentLineNumber = -1;
            } else {
                previousContentLine = line;
                previousContentLineNumber = lineNumber;
            }
        }
        return headings;
    }

    private static OpenHouseMarkdownHeading parseAtxHeading(String line, int lineNumber) {
        int index = leadingSpaces(line);
        if (index > 3 || index >= line.length() || line.charAt(index) != '#') {
            return null;
        }
        int level = 0;
        while (index + level < line.length() && line.charAt(index + level) == '#') {
            level++;
        }
        if (level < 1 || level > 6) {
            return null;
        }
        int afterHashes = index + level;
        if (afterHashes < line.length() && !Character.isWhitespace(line.charAt(afterHashes))) {
            return null;
        }
        String title = afterHashes >= line.length() ? "" : line.substring(afterHashes).trim();
        title = stripClosingHashes(title).trim();
        if (title.isEmpty()) {
            return null;
        }
        return new OpenHouseMarkdownHeading(level, title, lineNumber);
    }

    private static int parseSetextLevel(String line) {
        String trimmed = line.trim();
        if (trimmed.length() < 2) return -1;
        char marker = trimmed.charAt(0);
        if (marker != '=' && marker != '-') return -1;
        for (int i = 0; i < trimmed.length(); i++) {
            if (trimmed.charAt(i) != marker) return -1;
        }
        return marker == '=' ? 1 : 2;
    }

    private static boolean isFenceLine(String line) {
        int index = leadingSpaces(line);
        if (index > 3 || index >= line.length()) {
            return false;
        }
        return startsWithFence(line, index, "```") || startsWithFence(line, index, "~~~");
    }

    private static boolean startsWithFence(String line, int index, String fence) {
        if (!line.startsWith(fence, index)) return false;
        int count = 0;
        while (index + count < line.length() && line.charAt(index + count) == fence.charAt(0)) {
            count++;
        }
        return count >= 3;
    }

    private static int leadingSpaces(String line) {
        int spaces = 0;
        while (spaces < line.length() && line.charAt(spaces) == ' ') {
            spaces++;
        }
        return spaces;
    }

    private static String stripClosingHashes(String title) {
        int end = title.length();
        while (end > 0 && title.charAt(end - 1) == '#') {
            end--;
        }
        if (end == title.length()) {
            return title;
        }
        if (end > 0 && Character.isWhitespace(title.charAt(end - 1))) {
            return title.substring(0, end);
        }
        return title;
    }
}
