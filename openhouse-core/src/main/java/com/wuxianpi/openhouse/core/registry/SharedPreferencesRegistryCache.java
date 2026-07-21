package com.wuxianpi.openhouse.core.registry;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.List;

public final class SharedPreferencesRegistryCache implements RegistryCache {
    private static final String PREFS = "openhouse_core_registry_cache";
    private static final String KEY = "snapshot_json";
    private final SharedPreferences preferences;

    public SharedPreferencesRegistryCache(Context context) {
        if (context == null) throw new IllegalArgumentException("context is required");
        preferences = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    @Override public RegistryCacheEntry load() {
        return RegistryCacheCodec.decode(preferences.getString(KEY, ""));
    }

    @Override public void save(String revision, long savedAtEpochMillis, List<RegistryManifest> manifests) {
        preferences.edit().putString(KEY, RegistryCacheCodec.encode(revision, savedAtEpochMillis, manifests)).apply();
    }

    @Override public void clear() {
        preferences.edit().remove(KEY).apply();
    }
}
