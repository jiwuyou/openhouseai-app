package com.wuxianpi.openhouse.core.registry;

import com.wuxianpi.openhouse.core.LegacyRegistrySource;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class RegistryRepository {
    private final RegistryRemoteSource remote;
    private final RegistryCache cache;
    private final LegacyRegistrySource legacy;
    private final OpenHouseComponentParser parser;

    public RegistryRepository(RegistryRemoteSource remote, RegistryCache cache) {
        this(remote, cache, LegacyRegistrySource.unavailable());
    }

    public RegistryRepository(RegistryRemoteSource remote, RegistryCache cache,
                              LegacyRegistrySource legacy) {
        if (remote == null) throw new IllegalArgumentException("remote is required");
        if (cache == null) throw new IllegalArgumentException("cache is required");
        this.remote = remote;
        this.cache = cache;
        this.legacy = legacy == null ? LegacyRegistrySource.unavailable() : legacy;
        this.parser = new OpenHouseComponentParser();
    }

    public RegistrySnapshot load() {
        long now = System.currentTimeMillis();
        List<RegistryDiagnostic> diagnostics = new ArrayList<>();
        List<RegistryManifest> selected = Collections.emptyList();
        RegistrySource source = RegistrySource.BUILTINS_ONLY;
        String revision = "";
        boolean stale = true;

        RegistryRemoteResult remoteResult;
        try {
            remoteResult = remote.loadRegistry();
        } catch (RuntimeException error) {
            remoteResult = new RegistryRemoteResult(false, 0, "", null, compact(error));
        }
        if (remoteResult != null && remoteResult.success) {
            selected = remoteResult.manifests;
            revision = remoteResult.revision;
            source = RegistrySource.SERVICE_MANAGER_API;
            stale = false;
            try {
                cache.save(revision, now, selected);
            } catch (RuntimeException error) {
                diagnostics.add(warning("CACHE_WRITE_FAILED", compact(error)));
            }
        } else {
            diagnostics.add(warning("REGISTRY_API_FAILED", remoteResult == null ? "no API result" : remoteResult.message));
            RegistryCacheEntry cached;
            try {
                cached = cache.load();
            } catch (RuntimeException error) {
                cached = RegistryCacheEntry.corrupt(compact(error));
            }
            if (cached.present && cached.valid) {
                selected = cached.manifests;
                revision = cached.revision;
                source = RegistrySource.APP_CACHE;
                diagnostics.add(info("CACHE_FALLBACK", "using app-private registry cache"));
            } else {
                if (cached.present) diagnostics.add(warning("CACHE_CORRUPT", cached.error));
                LegacyRegistrySnapshot legacyResult;
                try {
                    legacyResult = legacy.load();
                } catch (RuntimeException error) {
                    legacyResult = LegacyRegistrySnapshot.failure(compact(error));
                }
                if (legacyResult != null && legacyResult.available) {
                    selected = legacyResult.manifests;
                    revision = legacyResult.revision;
                    source = RegistrySource.LEGACY_FILE;
                    stale = true;
                    diagnostics.add(info("LEGACY_FALLBACK", "using host-provided registry files"));
                    try {
                        cache.save(revision, now, selected);
                    } catch (RuntimeException error) {
                        diagnostics.add(warning("CACHE_WRITE_FAILED", compact(error)));
                    }
                } else if (legacyResult != null && !legacyResult.message.isEmpty()) {
                    diagnostics.add(info("LEGACY_UNAVAILABLE", legacyResult.message));
                }
            }
        }

        Map<String, OpenHouseComponent> merged = new LinkedHashMap<>();
        for (OpenHouseComponent builtin : OpenHouseBuiltins.components()) merged.put(builtin.id, builtin);
        for (RegistryManifest manifest : selected) {
            if (manifest == null) continue;
            if (OpenHouseBuiltins.isProtectedId(manifest.id)) {
                diagnostics.add(warning("PROTECTED_ID_IGNORED", manifest.id));
                continue;
            }
            try {
                OpenHouseComponent component = parser.parse(manifest, source.name().toLowerCase());
                if (component != null && !merged.containsKey(component.id)) merged.put(component.id, component);
                else if (component != null) diagnostics.add(warning("DUPLICATE_ID_IGNORED", component.id));
            } catch (Exception error) {
                diagnostics.add(warning("INVALID_MANIFEST", manifest.id + ": " + compact(error)));
            }
        }
        List<OpenHouseComponent> components = new ArrayList<>(merged.values());
        Collections.sort(components, COMPONENT_ORDER);
        return new RegistrySnapshot(components, source, stale, revision, now, diagnostics);
    }

    private static RegistryDiagnostic warning(String code, String message) {
        return new RegistryDiagnostic(RegistryDiagnostic.Severity.WARNING, code, message);
    }
    private static RegistryDiagnostic info(String code, String message) {
        return new RegistryDiagnostic(RegistryDiagnostic.Severity.INFO, code, message);
    }
    private static String compact(Throwable error) {
        String message = error == null ? "" : error.getMessage();
        return message == null || message.trim().isEmpty() ? error == null ? "" : error.getClass().getSimpleName() : message.trim();
    }

    private static final Comparator<OpenHouseComponent> COMPONENT_ORDER = new Comparator<OpenHouseComponent>() {
        @Override public int compare(OpenHouseComponent left, OpenHouseComponent right) {
            int section = Integer.compare(sectionRank(left.section), sectionRank(right.section));
            if (section != 0) return section;
            int order = Integer.compare(left.order, right.order);
            return order != 0 ? order : left.title.compareToIgnoreCase(right.title);
        }
    };

    private static int sectionRank(String section) {
        String value = section == null ? "" : section.trim().toLowerCase();
        if ("desktop".equals(value)) return 0;
        if ("ai".equals(value)) return 10;
        if ("tools".equals(value)) return 20;
        if ("apps".equals(value)) return 30;
        return 100;
    }
}
