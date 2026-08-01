package com.openhouse.host.nativeapp;

import android.content.Context;
import android.content.ContextWrapper;

import com.wuxianpi.openhouse.core.registry.RegistryCache;
import com.wuxianpi.openhouse.core.registry.RegistryCacheEntry;
import com.wuxianpi.openhouse.core.registry.RegistryManifest;
import com.wuxianpi.openhouse.core.registry.RegistryRemoteResult;
import com.wuxianpi.openhouse.core.registry.RegistryRepository;
import com.wuxianpi.openhouse.core.registry.RegistrySnapshot;
import com.wuxianpi.openhouse.core.registry.RegistrySource;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class NativeOpenHouseHostTest {
    @Rule public final TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void normalizesAndLoadsSafLegacyRegistryFallback() throws Exception {
        assertEquals("http://127.0.0.1:20765", NativeOpenHouseHost.DEFAULT_PI_RUNTIME_URL);
        assertEquals("http://127.0.0.1:20087",
            NativeOpenHouseHost.normalizeServiceManagerUrl("0.0.0.0:20087"));
        assertEquals("http://127.0.0.1:21000",
            NativeOpenHouseHost.normalizeServiceManagerUrl(":21000"));

        Map<String, String> files = new HashMap<>();
        files.put(".config/openhouseai/components.d/memo.json", dynamicManifest());
        files.put(".config/openhouseai/registry-state.json",
            "{\"revision\":\"native-saf-r1\"}");
        NativeOpenHouseHost.RegistryFileAccess access = new NativeOpenHouseHost.RegistryFileAccess() {
            @Override public List<String> listJsonFiles(String relativeDirectory) {
                assertEquals(".config/openhouseai/components.d", relativeDirectory);
                return Collections.singletonList("memo.json");
            }

            @Override public String readText(String relativePath) {
                return files.getOrDefault(relativePath, "");
            }
        };
        NativeOpenHouseHost host = new NativeOpenHouseHost(
            new TestContext(temporaryFolder.newFolder("context")), access);
        final boolean[] cacheSaved = {false};
        RegistrySnapshot snapshot = new RegistryRepository(
            () -> new RegistryRemoteResult(false, 503, "", null, "offline"),
            missingCache(cacheSaved),
            host.legacyRegistrySource()
        ).load();

        assertEquals(RegistrySource.LEGACY_FILE, snapshot.source);
        assertEquals("native-saf-r1", snapshot.revision);
        assertTrue(snapshot.components.stream().anyMatch(component -> "memo".equals(component.id)));
        assertTrue(cacheSaved[0]);
    }

    private static RegistryCache missingCache(boolean[] saved) {
        return new RegistryCache() {
            @Override public RegistryCacheEntry load() {
                return RegistryCacheEntry.missing();
            }

            @Override public void save(String revision, long savedAtEpochMillis,
                                       List<RegistryManifest> manifests) {
                saved[0] = true;
            }

            @Override public void clear() {
            }
        };
    }

    private static String dynamicManifest() {
        return "{\n"
            + "  \"id\": \"memo\",\n"
            + "  \"shellMenu\": {\n"
            + "    \"title\": \"Memo\",\n"
            + "    \"entry\": {\"type\": \"webview\", \"url\": \"http://127.0.0.1:23110/\"}\n"
            + "  }\n"
            + "}";
    }

    private static final class TestContext extends ContextWrapper {
        private final File directory;

        private TestContext(File directory) {
            super(null);
            this.directory = directory;
        }

        @Override public Context getApplicationContext() {
            return this;
        }

        @Override public File getFilesDir() {
            return directory;
        }
    }
}
