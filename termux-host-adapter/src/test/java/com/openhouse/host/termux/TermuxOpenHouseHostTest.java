package com.openhouse.host.termux;

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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class TermuxOpenHouseHostTest {
    @Rule public final TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void normalizesAndLoadsCanonicalLegacyRegistryFallback() throws Exception {
        assertEquals("http://127.0.0.1:20087",
            TermuxOpenHouseHost.normalizeServiceManagerUrl("0.0.0.0:20087"));
        assertEquals("http://127.0.0.1:21000",
            TermuxOpenHouseHost.normalizeServiceManagerUrl(":21000"));

        File configDir = temporaryFolder.newFolder("openhouseai");
        File componentsDir = new File(configDir, "components.d");
        assertTrue(componentsDir.mkdirs());
        Files.write(new File(componentsDir, "memo.json").toPath(),
            dynamicManifest().getBytes(StandardCharsets.UTF_8));
        Files.write(new File(configDir, "registry-state.json").toPath(),
            "{\"generatedAt\":\"2026-07-22T10:00:00Z\"}".getBytes(StandardCharsets.UTF_8));

        TermuxOpenHouseHost host = new TermuxOpenHouseHost(
            new TestContext(temporaryFolder.newFolder("context")), configDir);
        final boolean[] cacheSaved = {false};
        RegistrySnapshot snapshot = new RegistryRepository(
            () -> new RegistryRemoteResult(false, 503, "", null, "offline"),
            missingCache(cacheSaved),
            host.legacyRegistrySource()
        ).load();

        assertEquals(RegistrySource.LEGACY_FILE, snapshot.source);
        assertEquals("2026-07-22T10:00:00Z", snapshot.revision);
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
