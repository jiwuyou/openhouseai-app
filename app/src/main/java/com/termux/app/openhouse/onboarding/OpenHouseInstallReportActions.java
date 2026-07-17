package com.termux.app.openhouse.onboarding;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.widget.Toast;

import com.termux.shared.logger.Logger;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** User-facing actions for copying or exporting a first-install failure report. */
public final class OpenHouseInstallReportActions {

    private static final String LOG_TAG = "OpenHouseInstallReport";
    private static final String MIME_TYPE = "text/plain";
    private static final String DOWNLOAD_SUBDIRECTORY = "OpenHouseAI";
    private static final ExecutorService EXPORT_EXECUTOR = Executors.newSingleThreadExecutor();

    private OpenHouseInstallReportActions() {
    }

    public static String normalizeReport(String report, String fallback) {
        String value = report == null ? "" : report.trim();
        if (value.isEmpty()) {
            value = fallback == null ? "" : fallback.trim();
        }
        if (value.isEmpty()) {
            value = "OpenHouse 首次安装错误报告\n\n"
                + "错误结论：当前没有读取到安装控制器的错误报告。\n"
                + "请保留此报告，并在详细进度页同时查看共享安装日志。";
        }
        return redact(value);
    }

    public static void copyReport(Activity activity, String report) {
        if (activity == null) {
            return;
        }
        String content = normalizeReport(report, null);
        ClipboardManager clipboard = (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard == null) {
            Toast.makeText(activity, "无法访问系统剪贴板", Toast.LENGTH_LONG).show();
            return;
        }
        clipboard.setPrimaryClip(ClipData.newPlainText("OpenHouse 首次安装错误报告", content));
        Toast.makeText(activity, "错误报告文字已复制，可直接粘贴发送", Toast.LENGTH_SHORT).show();
    }

    public static void exportAndShare(Activity activity, String report) {
        if (activity == null) {
            return;
        }
        String content = normalizeReport(report, null);
        Toast.makeText(activity, "正在导出完整日志到 Download/OpenHouseAI…", Toast.LENGTH_SHORT).show();
        EXPORT_EXECUTOR.execute(() -> {
            try {
                ExportResult result = writeToDownloads(activity.getApplicationContext(), content);
                activity.runOnUiThread(() -> {
                    if (activity.isFinishing() || activity.isDestroyed()) {
                        return;
                    }
                    shareExport(activity, result);
                });
            } catch (Throwable throwable) {
                Logger.logStackTraceWithMessage(LOG_TAG, "Failed to export first-install report", throwable);
                String message = throwable.getMessage();
                activity.runOnUiThread(() -> {
                    if (activity.isFinishing() || activity.isDestroyed()) {
                        return;
                    }
                    Toast.makeText(
                        activity,
                        "导出完整日志失败：" + (message == null || message.trim().isEmpty() ? "请先开启文件访问权限" : message),
                        Toast.LENGTH_LONG
                    ).show();
                });
            }
        });
    }

    private static ExportResult writeToDownloads(Context context, String content) throws Exception {
        String displayName = buildDisplayName(context);
        ContentResolver resolver = context.getContentResolver();
        Uri collection;
        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, displayName);
        values.put(MediaStore.MediaColumns.MIME_TYPE, MIME_TYPE);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI;
            values.put(
                MediaStore.MediaColumns.RELATIVE_PATH,
                Environment.DIRECTORY_DOWNLOADS + "/" + DOWNLOAD_SUBDIRECTORY
            );
            values.put(MediaStore.MediaColumns.IS_PENDING, 1);
        } else {
            File directory = new File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                DOWNLOAD_SUBDIRECTORY
            );
            if (!directory.exists() && !directory.mkdirs()) {
                throw new IOException("无法创建 Download/" + DOWNLOAD_SUBDIRECTORY + "，请开启文件访问权限");
            }
            collection = MediaStore.Files.getContentUri("external");
            values.put(MediaStore.MediaColumns.DATA, new File(directory, displayName).getAbsolutePath());
        }

        Uri uri = resolver.insert(collection, values);
        if (uri == null) {
            throw new IOException("系统未能创建 Download 日志文件，请开启文件访问权限");
        }

        boolean success = false;
        try (OutputStream output = resolver.openOutputStream(uri, "w")) {
            if (output == null) {
                throw new IOException("无法写入 Download 日志文件");
            }
            output.write(content.getBytes(StandardCharsets.UTF_8));
            output.flush();
            success = true;
        } finally {
            if (!success) {
                resolver.delete(uri, null, null);
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContentValues published = new ContentValues();
            published.put(MediaStore.MediaColumns.IS_PENDING, 0);
            resolver.update(uri, published, null, null);
        }
        return new ExportResult(uri, displayName);
    }

    private static void shareExport(Activity activity, ExportResult result) {
        Intent intent = new Intent(Intent.ACTION_SEND);
        intent.setType(MIME_TYPE);
        intent.putExtra(Intent.EXTRA_SUBJECT, "OpenHouse 首次安装错误报告");
        intent.putExtra(Intent.EXTRA_TEXT, "OpenHouse 首次安装未完成，附件是完整诊断日志。\n文件：" + result.displayName);
        intent.putExtra(Intent.EXTRA_STREAM, result.uri);
        intent.setClipData(ClipData.newRawUri(result.displayName, result.uri));
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            activity.startActivity(Intent.createChooser(intent, "发送 OpenHouse 完整诊断日志"));
            Toast.makeText(
                activity,
                "日志已保存到 Download/" + DOWNLOAD_SUBDIRECTORY + "，请选择应用发送",
                Toast.LENGTH_LONG
            ).show();
        } catch (ActivityNotFoundException e) {
            Toast.makeText(
                activity,
                "日志已保存到 Download/" + DOWNLOAD_SUBDIRECTORY + "，但没有可用的分享应用",
                Toast.LENGTH_LONG
            ).show();
        }
    }

    private static String buildDisplayName(Context context) {
        String version = "unknown";
        try {
            PackageInfo packageInfo = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            if (packageInfo.versionName != null && !packageInfo.versionName.trim().isEmpty()) {
                version = packageInfo.versionName.trim();
            }
        } catch (Exception ignored) {
        }
        version = version.replaceAll("[^A-Za-z0-9._-]", "_");
        String timestamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date());
        return "openhouse-first-install-error-" + version + "-" + timestamp + ".txt";
    }

    private static String redact(String text) {
        String redacted = text.replaceAll(
            "(?i)\\b(api[_-]?key|authorization|bearer|token|password)([=:\\\"' ]+)([^\\s\\\"']{4,})",
            "$1$2***"
        );
        return redacted.replaceAll("\\bsk-[A-Za-z0-9_-]{8,}\\b", "sk-***");
    }

    private static final class ExportResult {
        final Uri uri;
        final String displayName;

        ExportResult(Uri uri, String displayName) {
            this.uri = uri;
            this.displayName = displayName;
        }
    }
}
