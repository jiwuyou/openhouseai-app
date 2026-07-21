package com.wuxianpi.openhouse.core;

import com.wuxianpi.openhouse.core.registry.LegacyRegistrySnapshot;

/** Host-provided local registry fallback, normally backed by the current runtime files. */
public interface LegacyRegistrySource {
    LegacyRegistrySnapshot load();

    static LegacyRegistrySource unavailable() {
        return new LegacyRegistrySource() {
            @Override public LegacyRegistrySnapshot load() {
                return LegacyRegistrySnapshot.unavailable();
            }
        };
    }
}
