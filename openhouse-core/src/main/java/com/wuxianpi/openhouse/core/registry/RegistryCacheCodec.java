package com.wuxianpi.openhouse.core.registry;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public final class RegistryCacheCodec {
    private static final int SCHEMA_VERSION = 1;

    private RegistryCacheCodec() {}

    public static String encode(String revision, long savedAtEpochMillis, List<RegistryManifest> manifests) {
        try {
            JSONObject root = new JSONObject();
            root.put("schemaVersion", SCHEMA_VERSION);
            root.put("revision", revision == null ? "" : revision);
            root.put("savedAt", savedAtEpochMillis);
            JSONArray array = new JSONArray();
            if (manifests != null) {
                for (RegistryManifest manifest : manifests) {
                    if (manifest == null) continue;
                    JSONObject item = new JSONObject();
                    item.put("id", manifest.id);
                    item.put("path", manifest.relativePath);
                    item.put("manifest", new JSONObject(manifest.normalizedJson));
                    array.put(item);
                }
            }
            root.put("components", array);
            return root.toString();
        } catch (Exception error) {
            throw new IllegalArgumentException("registry cache could not be encoded", error);
        }
    }

    public static RegistryCacheEntry decode(String value) {
        if (value == null || value.trim().isEmpty()) return RegistryCacheEntry.missing();
        try {
            JSONObject root = new JSONObject(value);
            if (root.optInt("schemaVersion", -1) != SCHEMA_VERSION) {
                return RegistryCacheEntry.corrupt("unsupported cache schema");
            }
            JSONArray array = root.optJSONArray("components");
            if (array == null) return RegistryCacheEntry.corrupt("cache components missing");
            List<RegistryManifest> manifests = new ArrayList<>();
            for (int i = 0; i < array.length(); i++) {
                manifests.add(RegistryManifest.fromApiRecord(array.getJSONObject(i)));
            }
            return RegistryCacheEntry.valid(root.optString("revision", ""), root.optLong("savedAt", 0), manifests);
        } catch (Exception error) {
            return RegistryCacheEntry.corrupt(error.getMessage());
        }
    }
}
