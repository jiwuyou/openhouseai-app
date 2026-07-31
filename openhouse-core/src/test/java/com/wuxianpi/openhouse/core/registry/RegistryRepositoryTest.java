package com.wuxianpi.openhouse.core.registry;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.*;

public class RegistryRepositoryTest {
    @Test public void apiComponentsAreParsedCachedAndFixedEntriesRemainProtected() throws Exception {
        RegistryManifest dynamic = RegistryManifest.fromManifestJson("components.d/demo.json", manifest("demo", "Demo"));
        RegistryManifest attemptedOverride = RegistryManifest.fromManifestJson(manifest("desktop", "Fake Desktop"));
        FakeCache cache = new FakeCache();
        RegistryRepository repository = new RegistryRepository(
            () -> new RegistryRemoteResult(true, 200, "r1", Arrays.asList(dynamic, attemptedOverride), ""),
            cache
        );
        RegistrySnapshot snapshot = repository.load();
        assertSame(RegistrySource.SERVICE_MANAGER_API, snapshot.source);
        assertFalse(snapshot.stale);
        assertEquals("桌面", snapshot.find("desktop").title);
        assertEquals("Demo", snapshot.find("demo").title);
        assertTrue(cache.saved);
        assertAllFixedEntries(snapshot);
        assertTrue(hasDiagnostic(snapshot, "PROTECTED_ID_IGNORED"));
    }

    @Test public void apiFailureUsesValidAppCache() throws Exception {
        FakeCache cache = new FakeCache();
        cache.entry = RegistryCacheEntry.valid("cached-r", 12,
            Collections.singletonList(RegistryManifest.fromManifestJson(manifest("cached", "Cached"))));
        RegistryRepository repository = new RegistryRepository(
            () -> new RegistryRemoteResult(false, 503, "", null, "offline"), cache
        );
        RegistrySnapshot snapshot = repository.load();
        assertSame(RegistrySource.APP_CACHE, snapshot.source);
        assertTrue(snapshot.stale);
        assertEquals("Cached", snapshot.find("cached").title);
        assertTrue(hasDiagnostic(snapshot, "CACHE_FALLBACK"));
    }

    @Test public void corruptCacheFallsBackToFixedEntries() {
        FakeCache cache = new FakeCache();
        cache.entry = RegistryCacheEntry.corrupt("broken json");
        RegistryRepository repository = new RegistryRepository(
            () -> new RegistryRemoteResult(false, 0, "", null, "offline"), cache);
        RegistrySnapshot snapshot = repository.load();
        assertSame(RegistrySource.BUILTINS_ONLY, snapshot.source);
        assertAllFixedEntries(snapshot);
        assertTrue(hasDiagnostic(snapshot, "CACHE_CORRUPT"));
    }

    @Test public void missingAllDynamicSourcesStillReturnsFixedEntries() {
        FakeCache cache = new FakeCache();
        RegistryRepository repository = new RegistryRepository(
            () -> new RegistryRemoteResult(false, 0, "", null, "offline"), cache);
        RegistrySnapshot snapshot = repository.load();
        assertSame(RegistrySource.BUILTINS_ONLY, snapshot.source);
        assertAllFixedEntries(snapshot);
    }

    @Test public void unavailableApiAndCacheUsesHostRegistryFallback() throws Exception {
        RegistryManifest legacy = RegistryManifest.fromManifestJson(manifest("legacy", "Legacy"));
        RegistryRepository repository = new RegistryRepository(
            () -> new RegistryRemoteResult(false, 503, "", null, "offline"),
            new FakeCache(),
            () -> LegacyRegistrySnapshot.available("legacy-r", Collections.singletonList(legacy))
        );
        RegistrySnapshot snapshot = repository.load();
        assertSame(RegistrySource.LEGACY_FILE, snapshot.source);
        assertTrue(snapshot.stale);
        assertEquals("Legacy", snapshot.find("legacy").title);
        assertAllFixedEntries(snapshot);
    }

    @Test public void cacheCodecRoundTripsAndRejectsDamage() throws Exception {
        RegistryManifest manifest = RegistryManifest.fromManifestJson("components.d/demo.json", manifest("demo", "Demo"));
        String encoded = RegistryCacheCodec.encode("rev", 123L, Collections.singletonList(manifest));
        RegistryCacheEntry decoded = RegistryCacheCodec.decode(encoded);
        assertTrue(decoded.valid);
        assertEquals("rev", decoded.revision);
        assertEquals("demo", decoded.manifests.get(0).id);
        assertTrue(RegistryCacheCodec.decode("{broken").present);
        assertFalse(RegistryCacheCodec.decode("{broken").valid);
    }

    @Test public void parserCopiesDesktopAndServiceBindings() throws Exception {
        RegistryManifest manifest = RegistryManifest.fromManifestJson("{"
            + "\"id\":\"memo\",\"shellMenu\":{\"title\":\"Memo\",\"section\":\"apps\",\"order\":120,"
            + "\"desktop\":{\"visible\":true,\"order\":9,\"icon\":\"edit\"},"
            + "\"entry\":{\"type\":\"webview\",\"url\":\"http://127.0.0.1:23110/\"},"
            + "\"controlEntry\":{\"type\":\"service-control\",\"serviceRefs\":[\"service-manager://services/memo\"]}},"
            + "\"smallphoneApp\":{},\"serviceManager\":{},\"ai\":{}}");
        OpenHouseComponent component = new OpenHouseComponentParser().parse(manifest, "test");
        assertEquals(OpenHouseComponent.EntryType.WEBVIEW, component.entryType);
        assertEquals(9, component.desktopOrder);
        assertEquals("edit", component.iconKey);
        assertEquals(Collections.singletonList("service-manager://services/memo"), component.serviceRefs);
    }

    @Test public void parserRecognizesFilesEntry() throws Exception {
        RegistryManifest manifest = RegistryManifest.fromManifestJson(manifestWithEntry("files-demo", "文件", "files"));
        OpenHouseComponent component = new OpenHouseComponentParser().parse(manifest, "test");
        assertEquals(OpenHouseComponent.EntryType.FILES, component.entryType);
        assertEquals("folder", component.iconKey);
    }

    @Test(expected = Exception.class)
    public void manifestRejectsAbsoluteRegistryPath() throws Exception {
        RegistryManifest.fromManifestJson("/private/components/demo.json", manifest("demo", "Demo"));
    }

    private static boolean hasDiagnostic(RegistrySnapshot snapshot, String code) {
        for (RegistryDiagnostic diagnostic : snapshot.diagnostics) if (code.equals(diagnostic.code)) return true;
        return false;
    }

    private static void assertAllFixedEntries(RegistrySnapshot snapshot) {
        for (String id : OpenHouseBuiltins.protectedIds()) assertNotNull(id, snapshot.find(id));
        assertEquals(10, OpenHouseBuiltins.protectedIds().size());
        OpenHouseComponent about = snapshot.find(OpenHouseBuiltins.ABOUT_ID);
        assertEquals("关于 WuxianPi", about.title);
        assertEquals(OpenHouseComponent.EntryType.NATIVE_PAGE, about.entryType);
        OpenHouseComponent files = snapshot.find(OpenHouseBuiltins.FILES_ID);
        assertEquals("文件", files.title);
        assertEquals("folder", files.iconKey);
        assertEquals(OpenHouseComponent.EntryType.FILES, files.entryType);
    }

    private static String manifest(String id, String title) {
        return "{\"schemaVersion\":1,\"id\":\"" + id + "\",\"title\":\"" + title + "\","
            + "\"shellMenu\":{\"title\":\"" + title + "\",\"entry\":{\"type\":\"native-page\",\"page\":\"demo\"}},"
            + "\"smallphoneApp\":{},\"serviceManager\":{},\"ai\":{}}";
    }

    private static String manifestWithEntry(String id, String title, String entryType) {
        return "{\"schemaVersion\":1,\"id\":\"" + id + "\",\"title\":\"" + title + "\","
            + "\"shellMenu\":{\"title\":\"" + title + "\",\"entry\":{\"type\":\"" + entryType + "\"}},"
            + "\"smallphoneApp\":{},\"serviceManager\":{},\"ai\":{}}";
    }

    private static final class FakeCache implements RegistryCache {
        RegistryCacheEntry entry = RegistryCacheEntry.missing();
        boolean saved;
        @Override public RegistryCacheEntry load() { return entry; }
        @Override public void save(String revision, long savedAtEpochMillis, List<RegistryManifest> manifests) {
            saved = true; entry = RegistryCacheEntry.valid(revision, savedAtEpochMillis, new ArrayList<>(manifests));
        }
        @Override public void clear() { entry = RegistryCacheEntry.missing(); }
    }
}
