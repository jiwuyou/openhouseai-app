package com.termux.app.openhouse.editor;

public final class OpenHouseEditorLoadResult {

    private final String text;
    private final long loadedBytes;
    private final long sourceSizeBytes;
    private final boolean contentTruncated;
    private final boolean readOnly;
    private final String note;

    private OpenHouseEditorLoadResult(String text, long loadedBytes, long sourceSizeBytes, boolean contentTruncated, boolean readOnly, String note) {
        this.text = text == null ? "" : text;
        this.loadedBytes = loadedBytes;
        this.sourceSizeBytes = sourceSizeBytes;
        this.contentTruncated = contentTruncated;
        this.readOnly = readOnly;
        this.note = note == null ? "" : note;
    }

    public static OpenHouseEditorLoadResult loaded(String text, long loadedBytes, long sourceSizeBytes, boolean contentTruncated, boolean readOnly, String note) {
        return new OpenHouseEditorLoadResult(text, loadedBytes, sourceSizeBytes, contentTruncated, readOnly, note);
    }

    public static OpenHouseEditorLoadResult unsupported(String note, long sourceSizeBytes) {
        return new OpenHouseEditorLoadResult("", 0L, sourceSizeBytes, false, true, note);
    }

    public String getText() {
        return text;
    }

    public long getLoadedBytes() {
        return loadedBytes;
    }

    public long getSourceSizeBytes() {
        return sourceSizeBytes;
    }

    public boolean isContentTruncated() {
        return contentTruncated;
    }

    public boolean isReadOnly() {
        return readOnly;
    }

    public String getNote() {
        return note;
    }
}
