package com.wuxianpi.openhouse.core.registry;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class RegistryRemoteResult {
    public final boolean success;
    public final int httpCode;
    public final String revision;
    public final List<RegistryManifest> manifests;
    public final String message;

    public RegistryRemoteResult(boolean success, int httpCode, String revision,
                                List<RegistryManifest> manifests, String message) {
        this.success = success;
        this.httpCode = httpCode;
        this.revision = revision == null ? "" : revision;
        this.manifests = manifests == null ? Collections.emptyList()
            : Collections.unmodifiableList(new ArrayList<>(manifests));
        this.message = message == null ? "" : message;
    }
}
