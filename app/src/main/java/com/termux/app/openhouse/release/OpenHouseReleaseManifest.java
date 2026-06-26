package com.termux.app.openhouse.release;

import org.json.JSONException;
import org.json.JSONObject;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.Locale;

public final class OpenHouseReleaseManifest {

    public static final int SUPPORTED_SCHEMA_VERSION = 1;

    public final String manifestUrl;
    public final int schemaVersion;
    public final long latestVersionCode;
    public final String latestVersionName;
    public final String apkUrl;
    public final String apkSha256;
    public final long apkSizeBytes;
    public final String packageName;
    public final String channel;
    public final String releaseNotes;
    public final String runtimePayloadVersion;
    public final boolean forceUpdate;
    public final String signingCertificateSha256;

    private OpenHouseReleaseManifest(
        String manifestUrl,
        int schemaVersion,
        long latestVersionCode,
        String latestVersionName,
        String apkUrl,
        String apkSha256,
        long apkSizeBytes,
        String packageName,
        String channel,
        String releaseNotes,
        String runtimePayloadVersion,
        boolean forceUpdate,
        String signingCertificateSha256
    ) {
        this.manifestUrl = manifestUrl;
        this.schemaVersion = schemaVersion;
        this.latestVersionCode = latestVersionCode;
        this.latestVersionName = latestVersionName;
        this.apkUrl = apkUrl;
        this.apkSha256 = apkSha256;
        this.apkSizeBytes = apkSizeBytes;
        this.packageName = packageName;
        this.channel = channel;
        this.releaseNotes = releaseNotes;
        this.runtimePayloadVersion = runtimePayloadVersion;
        this.forceUpdate = forceUpdate;
        this.signingCertificateSha256 = signingCertificateSha256;
    }

    public static OpenHouseReleaseManifest fromJson(String manifestUrl, String json) throws OpenHouseReleaseException {
        try {
            JSONObject root = new JSONObject(json);
            int schemaVersion = requireInt(root, "schemaVersion");
            if (schemaVersion != SUPPORTED_SCHEMA_VERSION) {
                throw new OpenHouseReleaseException("不支持的发布 manifest schemaVersion: " + schemaVersion);
            }

            long latestVersionCode = requireLong(root, "latestVersionCode");
            if (latestVersionCode <= 0) {
                throw new OpenHouseReleaseException("latestVersionCode 必须大于 0");
            }

            String latestVersionName = requireString(root, "latestVersionName", false);
            String apkUrl = resolveUrl(manifestUrl, requireString(root, "apkUrl", false));
            String apkSha256 = normalizeSha256(requireString(root, "apkSha256", false), "apkSha256");
            long apkSizeBytes = requireLong(root, "apkSizeBytes");
            if (apkSizeBytes <= 0) {
                throw new OpenHouseReleaseException("apkSizeBytes 必须大于 0");
            }

            String packageName = requireString(root, "packageName", false);
            String channel = requireString(root, "channel", false);
            String releaseNotes = optionalString(root, "releaseNotes");
            String runtimePayloadVersion = optionalString(root, "runtimePayloadVersion");
            boolean forceUpdate = root.optBoolean("forceUpdate", false);
            String signingCertificateSha256 = null;
            if (root.has("signingCertificateSha256") && !root.isNull("signingCertificateSha256")) {
                String rawSigningCertificateSha256 = root.optString("signingCertificateSha256", "").trim();
                if (!rawSigningCertificateSha256.isEmpty()) {
                    signingCertificateSha256 = normalizeSha256(rawSigningCertificateSha256, "signingCertificateSha256");
                }
            }

            return new OpenHouseReleaseManifest(
                manifestUrl,
                schemaVersion,
                latestVersionCode,
                latestVersionName,
                apkUrl,
                apkSha256,
                apkSizeBytes,
                packageName,
                channel,
                releaseNotes,
                runtimePayloadVersion,
                forceUpdate,
                signingCertificateSha256
            );
        } catch (JSONException e) {
            throw new OpenHouseReleaseException("发布 manifest 不是有效 JSON", e);
        }
    }

    public boolean hasSigningCertificatePin() {
        return signingCertificateSha256 != null && !signingCertificateSha256.isEmpty();
    }

    static String normalizeSha256(String value, String fieldName) throws OpenHouseReleaseException {
        String normalized = value == null
            ? ""
            : value.replace(":", "").replace(" ", "").replace("\n", "").replace("\r", "").toLowerCase(Locale.US);
        if (!normalized.matches("[0-9a-f]{64}")) {
            throw new OpenHouseReleaseException(fieldName + " 必须是 64 位十六进制 SHA-256");
        }
        return normalized;
    }

    private static int requireInt(JSONObject root, String name) throws JSONException, OpenHouseReleaseException {
        if (!root.has(name)) {
            throw new OpenHouseReleaseException("发布 manifest 缺少字段: " + name);
        }
        return root.getInt(name);
    }

    private static long requireLong(JSONObject root, String name) throws JSONException, OpenHouseReleaseException {
        if (!root.has(name)) {
            throw new OpenHouseReleaseException("发布 manifest 缺少字段: " + name);
        }
        return root.getLong(name);
    }

    private static String requireString(JSONObject root, String name, boolean allowEmpty) throws JSONException, OpenHouseReleaseException {
        if (!root.has(name)) {
            throw new OpenHouseReleaseException("发布 manifest 缺少字段: " + name);
        }
        String value = root.getString(name).trim();
        if (!allowEmpty && value.isEmpty()) {
            throw new OpenHouseReleaseException("发布 manifest 字段不能为空: " + name);
        }
        return value;
    }

    private static String optionalString(JSONObject root, String name) {
        if (!root.has(name) || root.isNull(name)) {
            return "";
        }
        return root.optString(name, "").trim();
    }

    private static String resolveUrl(String manifestUrl, String url) throws OpenHouseReleaseException {
        try {
            URL resolved = new URL(new URL(manifestUrl), url);
            String protocol = resolved.getProtocol();
            if (!"http".equalsIgnoreCase(protocol) && !"https".equalsIgnoreCase(protocol)) {
                throw new OpenHouseReleaseException("APK URL 必须是 http 或 https");
            }
            return resolved.toString();
        } catch (MalformedURLException e) {
            throw new OpenHouseReleaseException("APK URL 无效", e);
        }
    }
}
