package com.termux.app.openhouse.release;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;

public final class OpenHouseReleaseDownloader {

    private static final int CONNECT_TIMEOUT_MS = 10000;
    private static final int READ_TIMEOUT_MS = 30000;
    private static final int MAX_MANIFEST_BYTES = 256 * 1024;
    private static final int BUFFER_SIZE = 64 * 1024;

    public OpenHouseReleaseManifest fetchManifest(String manifestUrl) throws OpenHouseReleaseException {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(manifestUrl).openConnection();
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setInstanceFollowRedirects(true);
            connection.setRequestProperty("Accept", "application/json");

            int responseCode = connection.getResponseCode();
            if (responseCode < 200 || responseCode >= 300) {
                throw new OpenHouseReleaseException("发布 manifest 请求失败: HTTP " + responseCode);
            }

            String json;
            try (InputStream inputStream = connection.getInputStream()) {
                json = readUtf8WithLimit(inputStream, MAX_MANIFEST_BYTES);
            }
            return OpenHouseReleaseManifest.fromJson(manifestUrl, json);
        } catch (IOException e) {
            throw new OpenHouseReleaseException("发布 manifest 读取失败: " + e.getMessage(), e);
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    public DownloadResult downloadApk(OpenHouseReleaseManifest manifest, File outputFile, ProgressListener listener)
        throws OpenHouseReleaseException {
        HttpURLConnection connection = null;
        File partFile = new File(outputFile.getParentFile(), outputFile.getName() + ".part");
        boolean completed = false;
        try {
            File parentFile = outputFile.getParentFile();
            if (parentFile != null && !parentFile.isDirectory() && !parentFile.mkdirs()) {
                throw new OpenHouseReleaseException("无法创建 APK 下载目录: " + parentFile.getAbsolutePath());
            }

            connection = (HttpURLConnection) new URL(manifest.apkUrl).openConnection();
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setInstanceFollowRedirects(true);
            connection.setRequestProperty("Accept", "application/vnd.android.package-archive,*/*");

            int responseCode = connection.getResponseCode();
            if (responseCode < 200 || responseCode >= 300) {
                throw new OpenHouseReleaseException("APK 下载失败: HTTP " + responseCode);
            }

            long progressTotalBytes = manifest.apkSizeBytes > 0 ? manifest.apkSizeBytes : getContentLength(connection);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            long bytesReadTotal = 0;
            byte[] buffer = new byte[BUFFER_SIZE];

            try (InputStream inputStream = connection.getInputStream();
                 FileOutputStream outputStream = new FileOutputStream(partFile)) {
                int bytesRead;
                while ((bytesRead = inputStream.read(buffer)) != -1) {
                    outputStream.write(buffer, 0, bytesRead);
                    digest.update(buffer, 0, bytesRead);
                    bytesReadTotal += bytesRead;
                    if (manifest.apkSizeBytes > 0 && bytesReadTotal > manifest.apkSizeBytes) {
                        throw new OpenHouseReleaseException("APK 大小超过 manifest 声明值");
                    }
                    if (listener != null) {
                        listener.onProgress(bytesReadTotal, progressTotalBytes);
                    }
                }
            }

            if (manifest.apkSizeBytes > 0 && bytesReadTotal != manifest.apkSizeBytes) {
                throw new OpenHouseReleaseException("APK 大小不一致: 期望 "
                    + manifest.apkSizeBytes + " 字节，实际 " + bytesReadTotal + " 字节");
            }

            String actualSha256 = toHex(digest.digest());
            if (!manifest.apkSha256.equals(actualSha256)) {
                throw new OpenHouseReleaseException("APK SHA-256 不一致: 期望 "
                    + manifest.apkSha256 + "，实际 " + actualSha256);
            }

            if (outputFile.exists() && !outputFile.delete()) {
                throw new OpenHouseReleaseException("无法替换旧 APK 文件: " + outputFile.getAbsolutePath());
            }
            if (!partFile.renameTo(outputFile)) {
                throw new OpenHouseReleaseException("无法保存 APK 文件: " + outputFile.getAbsolutePath());
            }

            completed = true;
            return new DownloadResult(outputFile, bytesReadTotal, actualSha256);
        } catch (IOException e) {
            throw new OpenHouseReleaseException("APK 下载失败: " + e.getMessage(), e);
        } catch (NoSuchAlgorithmException e) {
            throw new OpenHouseReleaseException("系统缺少 SHA-256 支持", e);
        } finally {
            if (!completed && partFile.exists()) {
                //noinspection ResultOfMethodCallIgnored
                partFile.delete();
            }
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private static long getContentLength(HttpURLConnection connection) {
        try {
            return connection.getContentLengthLong();
        } catch (NoSuchMethodError e) {
            return connection.getContentLength();
        }
    }

    private static String readUtf8WithLimit(InputStream inputStream, int maxBytes) throws IOException, OpenHouseReleaseException {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int totalBytes = 0;
        int bytesRead;
        while ((bytesRead = inputStream.read(buffer)) != -1) {
            totalBytes += bytesRead;
            if (totalBytes > maxBytes) {
                throw new OpenHouseReleaseException("发布 manifest 过大");
            }
            outputStream.write(buffer, 0, bytesRead);
        }
        return new String(outputStream.toByteArray(), StandardCharsets.UTF_8);
    }

    private static String toHex(byte[] bytes) {
        StringBuilder builder = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            builder.append(String.format(Locale.US, "%02x", value & 0xff));
        }
        return builder.toString();
    }

    public interface ProgressListener {
        void onProgress(long bytesRead, long totalBytes);
    }

    public static final class DownloadResult {
        public final File apkFile;
        public final long bytesRead;
        public final String sha256;

        DownloadResult(File apkFile, long bytesRead, String sha256) {
            this.apkFile = apkFile;
            this.bytesRead = bytesRead;
            this.sha256 = sha256;
        }
    }
}
