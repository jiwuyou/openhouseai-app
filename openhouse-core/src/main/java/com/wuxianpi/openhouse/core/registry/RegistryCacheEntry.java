package com.wuxianpi.openhouse.core.registry;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class RegistryCacheEntry {
    public final boolean present;
    public final boolean valid;
    public final String revision;
    public final long savedAtEpochMillis;
    public final List<RegistryManifest> manifests;
    public final String error;

    private RegistryCacheEntry(boolean present, boolean valid, String revision, long savedAtEpochMillis,
                               List<RegistryManifest> manifests, String error) {
        this.present = present;
        this.valid = valid;
        this.revision = revision == null ? "" : revision;
        this.savedAtEpochMillis = savedAtEpochMillis;
        this.manifests = manifests == null ? Collections.emptyList()
            : Collections.unmodifiableList(new ArrayList<>(manifests));
        this.error = error == null ? "" : error;
    }

    public static RegistryCacheEntry missing() { return new RegistryCacheEntry(false, false, "", 0, null, ""); }
    public static RegistryCacheEntry corrupt(String error) { return new RegistryCacheEntry(true, false, "", 0, null, error); }
    public static RegistryCacheEntry valid(String revision, long savedAtEpochMillis, List<RegistryManifest> manifests) {
        return new RegistryCacheEntry(true, true, revision, savedAtEpochMillis, manifests, "");
    }
}
