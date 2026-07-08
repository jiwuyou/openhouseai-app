package com.termux.app.openhouse.editor;

public final class OpenHouseMarkdownHeading {

    private final int level;
    private final String title;
    private final int lineNumber;

    public OpenHouseMarkdownHeading(int level, String title, int lineNumber) {
        if (level < 1 || level > 6) throw new IllegalArgumentException("level out of range");
        this.level = level;
        this.title = title == null ? "" : title;
        this.lineNumber = lineNumber;
    }

    public int getLevel() {
        return level;
    }

    public String getTitle() {
        return title;
    }

    public int getLineNumber() {
        return lineNumber;
    }

    public String toDisplayString() {
        StringBuilder builder = new StringBuilder();
        for (int i = 1; i < level; i++) {
            builder.append("  ");
        }
        builder.append(title).append("  L").append(lineNumber);
        return builder.toString();
    }
}
