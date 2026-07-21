package com.wuxianpi.openhouse.core.registry;

import java.util.List;

public interface RegistryCache {
    RegistryCacheEntry load();
    void save(String revision, long savedAtEpochMillis, List<RegistryManifest> manifests);
    void clear();
}
