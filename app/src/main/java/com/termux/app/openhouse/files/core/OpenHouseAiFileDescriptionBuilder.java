package com.termux.app.openhouse.files.core;

import java.util.Locale;

public final class OpenHouseAiFileDescriptionBuilder {

    private OpenHouseAiFileDescriptionBuilder() {
    }

    public static String build(OpenHouseFileReference reference) {
        StringBuilder builder = new StringBuilder();
        builder.append("OpenHouse 文件说明\n");
        appendLine(builder, "文件名", valueOrUnknown(reference.getDisplayName()));
        appendLine(builder, "类型", valueOrUnknown(reference.getMimeType()));
        appendLine(builder, "大小", formatSize(reference.getSizeBytes()));
        appendLine(builder, "Android URI", valueOrUnknown(reference.getAndroidUri()));
        appendLine(builder, "Android 显示位置", valueOrUnknown(reference.getAndroidDisplayLocation()));
        if (reference.hasContentUri()) {
            builder.append("- Android URI 说明: content:// 是 Android 内容提供者授权，不是 POSIX 文件路径；不要把它当作 /sdcard 或 Linux path 使用。\n");
        }
        appendLine(builder, "Termux 路径", valueOrUnknown(reference.getTermuxPath()));
        appendLine(builder, "Ubuntu 路径", valueOrUnknown(reference.getUbuntuPath()));
        appendLine(builder, "OpenHouse workspace 路径", valueOrUnknown(reference.getWorkspacePath()));
        builder.append("\n请根据这些位置读取、分析或处理这个文件。");
        return builder.toString();
    }

    private static void appendLine(StringBuilder builder, String label, String value) {
        builder.append("- ").append(label).append(": ").append(value).append('\n');
    }

    private static String valueOrUnknown(String value) {
        if (value == null || value.trim().isEmpty()) {
            return "未知";
        }
        return value;
    }

    private static String formatSize(long sizeBytes) {
        if (sizeBytes < 0) {
            return "未知";
        }
        if (sizeBytes < 1024) {
            return sizeBytes + " B";
        }
        double kib = sizeBytes / 1024d;
        if (kib < 1024d) {
            return String.format(Locale.US, "%.1f KiB", kib);
        }
        double mib = kib / 1024d;
        if (mib < 1024d) {
            return String.format(Locale.US, "%.1f MiB", mib);
        }
        return String.format(Locale.US, "%.1f GiB", mib / 1024d);
    }
}
