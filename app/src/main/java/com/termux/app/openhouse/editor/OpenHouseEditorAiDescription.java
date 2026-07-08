package com.termux.app.openhouse.editor;

import com.termux.app.openhouse.files.core.OpenHouseAiFileDescriptionBuilder;
import com.termux.app.openhouse.files.core.OpenHouseFileReference;

import java.util.Locale;

public final class OpenHouseEditorAiDescription {

    private OpenHouseEditorAiDescription() {
    }

    public static String build(OpenHouseEditorDocument document, OpenHouseEditorLoadResult loadResult) {
        OpenHouseFileReference reference = OpenHouseFileReference.builder()
            .setDisplayName(document == null ? null : document.getDisplayName())
            .setMimeType(document == null ? null : document.getMimeType())
            .setSizeBytes(resolveSize(document, loadResult))
            .setAndroidUri(redact(document == null ? null : document.androidUriString()))
            .setAndroidDisplayLocation(redact(document == null ? null : document.getAndroidDisplayLocation()))
            .setTermuxPath(document == null ? null : document.getTermuxPath())
            .setUbuntuPath(document == null ? null : document.getUbuntuPath())
            .setWorkspacePath(document == null ? null : document.getWorkspacePath())
            .build();

        StringBuilder builder = new StringBuilder(OpenHouseAiFileDescriptionBuilder.build(reference));
        builder.append("\n\n编辑器状态\n");
        appendLine(builder, "来源原生位置", valueOrUnknown(redact(document == null ? null : document.sourceSummary())));
        appendLine(builder, "来源空间", valueOrUnknown(document == null ? null : document.getSourceSpaceId()));
        appendLine(builder, "来源文件 ID", valueOrUnknown(redact(document == null ? null : document.getSourceFileId())));
        appendLine(builder, "只读", loadResult != null && loadResult.isReadOnly() ? "是" : "否");
        appendLine(builder, "内容截断", loadResult != null && loadResult.isContentTruncated() ? "是" : "否");
        appendLine(builder, "已加载内容大小", formatSize(loadResult == null ? -1L : loadResult.getLoadedBytes()));
        if (loadResult != null && !loadResult.getNote().trim().isEmpty()) {
            appendLine(builder, "编辑器提示", loadResult.getNote());
        }
        return builder.toString();
    }

    private static long resolveSize(OpenHouseEditorDocument document, OpenHouseEditorLoadResult loadResult) {
        if (document != null && document.getSizeBytes() >= 0) return document.getSizeBytes();
        if (loadResult != null && loadResult.getSourceSizeBytes() >= 0) return loadResult.getSourceSizeBytes();
        return -1L;
    }

    private static void appendLine(StringBuilder builder, String label, String value) {
        builder.append("- ").append(label).append(": ").append(value).append('\n');
    }

    private static String valueOrUnknown(String value) {
        if (value == null || value.trim().isEmpty()) return "未知";
        return value;
    }

    private static String redact(String value) {
        return OpenHouseEditorSecretRedactor.redact(value);
    }

    private static String formatSize(long sizeBytes) {
        if (sizeBytes < 0) return "未知";
        if (sizeBytes < 1024) return sizeBytes + " B";
        double kib = sizeBytes / 1024d;
        if (kib < 1024d) return String.format(Locale.US, "%.1f KiB", kib);
        double mib = kib / 1024d;
        if (mib < 1024d) return String.format(Locale.US, "%.1f MiB", mib);
        return String.format(Locale.US, "%.1f GiB", mib / 1024d);
    }
}
