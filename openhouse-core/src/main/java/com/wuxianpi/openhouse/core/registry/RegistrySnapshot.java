package com.wuxianpi.openhouse.core.registry;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class RegistrySnapshot {
    public final List<OpenHouseComponent> components;
    public final RegistrySource source;
    public final boolean stale;
    public final String revision;
    public final long loadedAtEpochMillis;
    public final List<RegistryDiagnostic> diagnostics;

    public RegistrySnapshot(List<OpenHouseComponent> components, RegistrySource source, boolean stale,
                            String revision, long loadedAtEpochMillis, List<RegistryDiagnostic> diagnostics) {
        this.components = immutable(components);
        this.source = source == null ? RegistrySource.BUILTINS_ONLY : source;
        this.stale = stale;
        this.revision = revision == null ? "" : revision;
        this.loadedAtEpochMillis = loadedAtEpochMillis;
        this.diagnostics = immutable(diagnostics);
    }

    public OpenHouseComponent find(String id) {
        if (id == null) return null;
        for (OpenHouseComponent component : components) if (id.equals(component.id)) return component;
        return null;
    }

    private static <T> List<T> immutable(List<T> values) {
        return values == null || values.isEmpty() ? Collections.emptyList()
            : Collections.unmodifiableList(new ArrayList<>(values));
    }
}
