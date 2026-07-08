package com.termux.app.openhouse.files.ui;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import okhttp3.HttpUrl;

public final class OpenHouseFilesConfigStore {

    static final String PREFS_NAME = "openhouse_files";
    private static final String KEY_SAF_CONTAINERS = "saf_containers";
    private static final String KEY_WEBDAV_SPACES = "webdav_spaces";
    private static final String KEY_S3_SPACES = "s3_spaces";

    private final SharedPreferences prefs;

    public OpenHouseFilesConfigStore(Context context) {
        if (context == null) throw new IllegalArgumentException("context == null");
        this.prefs = context.getApplicationContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    OpenHouseFilesConfigStore(SharedPreferences prefs) {
        if (prefs == null) throw new IllegalArgumentException("prefs == null");
        this.prefs = prefs;
    }

    public List<SafContainerRecord> getSafContainers() {
        JSONArray array = readArray(KEY_SAF_CONTAINERS);
        List<SafContainerRecord> records = new ArrayList<>();
        for (int i = 0; i < array.length(); i++) {
            JSONObject item = array.optJSONObject(i);
            if (item == null) continue;
            String id = item.optString("id", "");
            String displayName = item.optString("displayName", "");
            String treeUri = item.optString("treeUri", "");
            if (!id.trim().isEmpty() && !displayName.trim().isEmpty() && !treeUri.trim().isEmpty()) {
                records.add(new SafContainerRecord(id, displayName, treeUri));
            }
        }
        return records;
    }

    public SafContainerRecord addSafContainer(String displayName, Uri treeUri) {
        if (treeUri == null) throw new IllegalArgumentException("treeUri == null");
        String uri = treeUri.toString();
        SafContainerRecord record = new SafContainerRecord(
            stableId("saf", uri),
            firstNonBlank(displayName, buildSafDisplayName(uri)),
            uri);
        JSONArray array = upsert(KEY_SAF_CONTAINERS, record.id, record.toJson());
        writeArray(KEY_SAF_CONTAINERS, array);
        return record;
    }

    public List<WebDavRecord> getWebDavRecords() {
        JSONArray array = readArray(KEY_WEBDAV_SPACES);
        List<WebDavRecord> records = new ArrayList<>();
        for (int i = 0; i < array.length(); i++) {
            JSONObject item = array.optJSONObject(i);
            if (item == null) continue;
            WebDavRecord record = WebDavRecord.fromJson(item);
            if (record != null) records.add(record);
        }
        return records;
    }

    public WebDavRecord addWebDav(String displayName, String baseUrl, String username, String password) {
        HttpUrl parsedUrl = HttpUrl.get(requireNonBlank(baseUrl, "url"));
        HttpUrl normalizedUrl = stripSensitiveUrlParts(parsedUrl);
        String normalizedUsername = firstNonBlank(username, parsedUrl.username());
        String normalizedPassword = firstNonBlank(password, parsedUrl.password());
        WebDavRecord record = new WebDavRecord(
            stableId("webdav", normalizedUrl.toString() + "|" + normalizedUsername),
            firstNonBlank(displayName, "WebDAV"),
            normalizedUrl.toString(),
            normalizedUsername,
            normalizedPassword);
        JSONArray array = upsert(KEY_WEBDAV_SPACES, record.id, record.toJson());
        writeArray(KEY_WEBDAV_SPACES, array);
        return record;
    }

    public List<S3Record> getS3Records() {
        JSONArray array = readArray(KEY_S3_SPACES);
        List<S3Record> records = new ArrayList<>();
        for (int i = 0; i < array.length(); i++) {
            JSONObject item = array.optJSONObject(i);
            if (item == null) continue;
            S3Record record = S3Record.fromJson(item);
            if (record != null) records.add(record);
        }
        return records;
    }

    public S3Record addS3(String displayName, String endpoint, String region, String bucket,
                          String accessKey, String secretKey, String sessionToken, boolean pathStyleAccess) {
        HttpUrl normalizedEndpoint = normalizeHttpUrl(firstNonBlank(endpoint, ""));
        String normalizedRegion = firstNonBlank(region, "us-east-1");
        String normalizedBucket = requireNonBlank(bucket, "bucket");
        String normalizedAccessKey = requireNonBlank(accessKey, "accessKey");
        String normalizedSecretKey = requireNonBlank(secretKey, "secretKey");
        S3Record record = new S3Record(
            stableId("s3", normalizedEndpoint.toString() + "|" + normalizedBucket + "|" + normalizedAccessKey),
            firstNonBlank(displayName, "S3"),
            normalizedEndpoint.toString(),
            normalizedRegion,
            normalizedBucket,
            normalizedAccessKey,
            normalizedSecretKey,
            firstNonBlank(sessionToken, ""),
            pathStyleAccess);
        JSONArray array = upsert(KEY_S3_SPACES, record.id, record.toJson());
        writeArray(KEY_S3_SPACES, array);
        return record;
    }

    public static String sanitizedWebDavSummary(String baseUrl, String username) {
        String safeUrl = sanitizeUrl(baseUrl);
        if (username == null || username.trim().isEmpty()) {
            return safeUrl;
        }
        return safeUrl + " · user=" + username.trim();
    }

    public static String sanitizedS3Summary(String endpoint, String bucket, String region, boolean pathStyleAccess) {
        StringBuilder builder = new StringBuilder();
        builder.append(sanitizeUrl(endpoint));
        if (bucket != null && !bucket.trim().isEmpty()) {
            builder.append(" · bucket=").append(bucket.trim());
        }
        if (region != null && !region.trim().isEmpty()) {
            builder.append(" · region=").append(region.trim());
        }
        if (pathStyleAccess) {
            builder.append(" · path-style");
        }
        return builder.toString();
    }

    static String stableId(String prefix, String seed) {
        String cleanPrefix = prefix == null || prefix.trim().isEmpty() ? "space" : prefix.trim().toLowerCase(Locale.US);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(firstNonBlank(seed, "").getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder(cleanPrefix).append('-');
            for (int i = 0; i < 6 && i < bytes.length; i++) {
                builder.append(String.format(Locale.US, "%02x", bytes[i] & 0xff));
            }
            return builder.toString();
        } catch (Exception e) {
            return cleanPrefix + "-" + Math.abs(firstNonBlank(seed, "").hashCode());
        }
    }

    private JSONArray upsert(String key, String id, JSONObject value) {
        JSONArray source = readArray(key);
        JSONArray out = new JSONArray();
        boolean replaced = false;
        for (int i = 0; i < source.length(); i++) {
            JSONObject existing = source.optJSONObject(i);
            if (existing == null) continue;
            if (id.equals(existing.optString("id", ""))) {
                out.put(value);
                replaced = true;
            } else {
                out.put(existing);
            }
        }
        if (!replaced) out.put(value);
        return out;
    }

    private JSONArray readArray(String key) {
        try {
            return new JSONArray(prefs.getString(key, "[]"));
        } catch (JSONException e) {
            return new JSONArray();
        }
    }

    private void writeArray(String key, JSONArray array) {
        prefs.edit().putString(key, array.toString()).commit();
    }

    private static String sanitizeUrl(String value) {
        String url = firstNonBlank(value, "");
        if (url.isEmpty()) return "";
        try {
            return normalizeHttpUrl(url).toString();
        } catch (Exception e) {
            Uri uri = Uri.parse(url);
            StringBuilder builder = new StringBuilder();
            if (uri.getScheme() != null) builder.append(uri.getScheme()).append("://");
            if (uri.getHost() != null) builder.append(uri.getHost());
            if (uri.getPort() >= 0) builder.append(':').append(uri.getPort());
            if (uri.getPath() != null) builder.append(uri.getPath());
            String sanitized = builder.toString();
            return sanitized.isEmpty() ? url.replaceAll("(?i)(password|secret|token)=([^&\\s]+)", "$1=<redacted>") : sanitized;
        }
    }

    private static HttpUrl normalizeHttpUrl(String url) {
        HttpUrl parsed = HttpUrl.get(requireNonBlank(url, "url"));
        return stripSensitiveUrlParts(parsed);
    }

    private static HttpUrl stripSensitiveUrlParts(HttpUrl parsed) {
        return parsed.newBuilder()
            .username("")
            .password("")
            .query(null)
            .fragment(null)
            .build();
    }

    private static String buildSafDisplayName(String uri) {
        if (uri == null || uri.trim().isEmpty()) {
            return "SAF 容器";
        }
        Uri parsed = Uri.parse(uri);
        String treeId = "";
        try {
            treeId = android.provider.DocumentsContract.getTreeDocumentId(parsed);
        } catch (Exception ignored) {
        }
        if (treeId == null || treeId.trim().isEmpty()) {
            treeId = parsed.getAuthority();
        }
        return firstNonBlank(treeId, "SAF 容器");
    }

    private static String firstNonBlank(String value, String fallback) {
        return value == null || value.trim().isEmpty() ? fallback : value.trim();
    }

    private static String requireNonBlank(String value, String name) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(name + " is empty");
        }
        return value.trim();
    }

    public static final class SafContainerRecord {
        public final String id;
        public final String displayName;
        public final String treeUri;

        SafContainerRecord(String id, String displayName, String treeUri) {
            this.id = id;
            this.displayName = displayName;
            this.treeUri = treeUri;
        }

        JSONObject toJson() {
            JSONObject json = new JSONObject();
            try {
                json.put("id", id);
                json.put("displayName", displayName);
                json.put("treeUri", treeUri);
            } catch (JSONException ignored) {
            }
            return json;
        }
    }

    public static final class WebDavRecord {
        public final String id;
        public final String displayName;
        public final String baseUrl;
        public final String username;
        public final String password;

        WebDavRecord(String id, String displayName, String baseUrl, String username, String password) {
            this.id = id;
            this.displayName = displayName;
            this.baseUrl = baseUrl;
            this.username = username;
            this.password = password;
        }

        static WebDavRecord fromJson(JSONObject json) {
            String id = json.optString("id", "");
            String displayName = json.optString("displayName", "");
            String baseUrl = json.optString("baseUrl", "");
            if (id.trim().isEmpty() || displayName.trim().isEmpty() || baseUrl.trim().isEmpty()) {
                return null;
            }
            return new WebDavRecord(
                id,
                displayName,
                baseUrl,
                json.optString("username", ""),
                json.optString("password", ""));
        }

        JSONObject toJson() {
            JSONObject json = new JSONObject();
            try {
                json.put("id", id);
                json.put("displayName", displayName);
                json.put("baseUrl", baseUrl);
                json.put("username", username);
                json.put("password", password);
            } catch (JSONException ignored) {
            }
            return json;
        }

        public String sanitizedSummary() {
            return sanitizedWebDavSummary(baseUrl, username);
        }
    }

    public static final class S3Record {
        public final String id;
        public final String displayName;
        public final String endpoint;
        public final String region;
        public final String bucket;
        public final String accessKey;
        public final String secretKey;
        public final String sessionToken;
        public final boolean pathStyleAccess;

        S3Record(String id, String displayName, String endpoint, String region, String bucket,
                 String accessKey, String secretKey, String sessionToken, boolean pathStyleAccess) {
            this.id = id;
            this.displayName = displayName;
            this.endpoint = endpoint;
            this.region = region;
            this.bucket = bucket;
            this.accessKey = accessKey;
            this.secretKey = secretKey;
            this.sessionToken = sessionToken;
            this.pathStyleAccess = pathStyleAccess;
        }

        static S3Record fromJson(JSONObject json) {
            String id = json.optString("id", "");
            String displayName = json.optString("displayName", "");
            String endpoint = json.optString("endpoint", "");
            String region = json.optString("region", "");
            String bucket = json.optString("bucket", "");
            String accessKey = json.optString("accessKey", "");
            String secretKey = json.optString("secretKey", "");
            if (id.trim().isEmpty() || displayName.trim().isEmpty() || endpoint.trim().isEmpty()
                || region.trim().isEmpty() || bucket.trim().isEmpty()
                || accessKey.trim().isEmpty() || secretKey.trim().isEmpty()) {
                return null;
            }
            return new S3Record(
                id,
                displayName,
                endpoint,
                region,
                bucket,
                accessKey,
                secretKey,
                json.optString("sessionToken", ""),
                json.optBoolean("pathStyleAccess", true));
        }

        JSONObject toJson() {
            JSONObject json = new JSONObject();
            try {
                json.put("id", id);
                json.put("displayName", displayName);
                json.put("endpoint", endpoint);
                json.put("region", region);
                json.put("bucket", bucket);
                json.put("accessKey", accessKey);
                json.put("secretKey", secretKey);
                json.put("sessionToken", sessionToken);
                json.put("pathStyleAccess", pathStyleAccess);
            } catch (JSONException ignored) {
            }
            return json;
        }

        public String sanitizedSummary() {
            return sanitizedS3Summary(endpoint, bucket, region, pathStyleAccess);
        }
    }
}
