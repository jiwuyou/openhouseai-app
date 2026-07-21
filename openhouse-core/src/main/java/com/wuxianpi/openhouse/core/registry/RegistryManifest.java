package com.wuxianpi.openhouse.core.registry;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Locale;

public final class RegistryManifest {
    public final String id;
    public final String relativePath;
    public final String normalizedJson;

    private RegistryManifest(String id, String relativePath, String normalizedJson) {
        this.id = id;
        this.relativePath = relativePath;
        this.normalizedJson = normalizedJson;
    }

    public static RegistryManifest fromManifestJson(String manifestJson) throws JSONException {
        return fromManifestJson("", manifestJson);
    }

    public static RegistryManifest fromManifestJson(String relativePath, String manifestJson) throws JSONException {
        JSONObject manifest = new JSONObject(manifestJson == null ? "" : manifestJson);
        String id = requireId(manifest.optString("id", ""));
        return new RegistryManifest(id, normalizeRelativePath(relativePath), JsonNormalizer.normalizeObject(manifest));
    }

    public static RegistryManifest fromApiRecord(JSONObject record) throws JSONException {
        if (record == null) throw new JSONException("registry record is missing");
        JSONObject manifest = record.optJSONObject("manifest");
        if (manifest == null) throw new JSONException("registry record manifest is missing");
        String id = requireId(record.optString("id", manifest.optString("id", "")));
        String manifestId = requireId(manifest.optString("id", ""));
        if (!id.equals(manifestId)) throw new JSONException("registry record id does not match manifest id");
        return new RegistryManifest(
            id,
            normalizeRelativePath(record.optString("path", "")),
            JsonNormalizer.normalizeObject(manifest)
        );
    }

    public JSONObject asJsonObject() throws JSONException {
        return new JSONObject(normalizedJson);
    }

    private static String requireId(String value) throws JSONException {
        String id = value == null ? "" : value.trim();
        if (id.isEmpty() || id.length() > 128) throw new JSONException("component id is invalid");
        for (int i = 0; i < id.length(); i++) {
            char c = id.charAt(i);
            if (!((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                || c == '_' || c == '-' || c == '.')) {
                throw new JSONException("component id is invalid");
            }
        }
        return id;
    }

    private static String normalizeRelativePath(String value) throws JSONException {
        String path = value == null ? "" : value.trim().replace('\\', '/');
        if (path.isEmpty()) return "";
        String lower = path.toLowerCase(Locale.US);
        if (path.startsWith("/") || lower.matches("^[a-z]:/.*") || path.contains("../") || path.equals("..")) {
            throw new JSONException("registry path must be relative");
        }
        while (path.startsWith("./")) path = path.substring(2);
        return path;
    }

    @Override public String toString() {
        return "RegistryManifest{id='" + id + "', relativePath='" + relativePath + "'}";
    }
}
