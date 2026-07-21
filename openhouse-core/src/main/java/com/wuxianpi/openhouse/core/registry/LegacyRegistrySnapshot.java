package com.wuxianpi.openhouse.core.registry;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Parsed registry data supplied by a host adapter when the HTTP registry is unavailable. */
public final class LegacyRegistrySnapshot {
    public final boolean available;
    public final String revision;
    public final List<RegistryManifest> manifests;
    public final String message;

    private LegacyRegistrySnapshot(boolean available, String revision,
                                   List<RegistryManifest> manifests, String message) {
        this.available = available;
        this.revision = revision == null ? "" : revision;
        this.manifests = manifests == null ? Collections.emptyList()
            : Collections.unmodifiableList(new ArrayList<>(manifests));
        this.message = message == null ? "" : message;
    }

    public static LegacyRegistrySnapshot unavailable() {
        return new LegacyRegistrySnapshot(false, "", null, "legacy registry unavailable");
    }

    public static LegacyRegistrySnapshot available(String revision, List<RegistryManifest> manifests) {
        return new LegacyRegistrySnapshot(true, revision, manifests, "");
    }

    public static LegacyRegistrySnapshot failure(String message) {
        return new LegacyRegistrySnapshot(false, "", null, message);
    }
}
