package com.termux.app.openhouse.runtime;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;

@RunWith(RobolectricTestRunner.class)
public class OpenHouseEndpointSnapshotTest {

    @Test
    public void resolvesReadyDynamicEndpointAndCachesByFileMetadata() throws Exception {
        File file = File.createTempFile("openhouse-endpoints", ".json");
        file.deleteOnExit();
        String expiresAt = Instant.ofEpochMilli(System.currentTimeMillis() + 60_000L).toString();
        Files.write(file.toPath(), snapshot(expiresAt, "http://127.0.0.1:24001/" ).getBytes(StandardCharsets.UTF_8));

        OpenHouseEndpointSnapshot reader = new OpenHouseEndpointSnapshot(
            file,
            System::currentTimeMillis
        );
        OpenHouseEndpointSnapshot.Resolution result = reader.resolve("smallphone-frontend-beta", "web");

        assertTrue(result.message, result.ready);
        assertEquals("http://127.0.0.1:24001/", result.url);
        assertFalse(result.url.contains("22082"));
    }

    @Test
    public void rejectsExpiredMalformedAndNonReadySnapshotsWithoutFallback() throws Exception {
        File file = File.createTempFile("openhouse-endpoints", ".json");
        file.deleteOnExit();
        Files.write(file.toPath(), snapshot(
            Instant.ofEpochMilli(System.currentTimeMillis() - 1_000L).toString(),
            "http://127.0.0.1:22082/"
        ).getBytes(StandardCharsets.UTF_8));
        OpenHouseEndpointSnapshot reader = new OpenHouseEndpointSnapshot(file, System::currentTimeMillis);
        assertFalse(reader.resolve("smallphone-frontend-beta", "web").ready);

        Files.write(file.toPath(), snapshot(
            Instant.ofEpochMilli(System.currentTimeMillis() + 60_000L).toString(),
            "http://example.com:24001/"
        ).getBytes(StandardCharsets.UTF_8));
        reader.invalidate();
        assertFalse(reader.resolve("smallphone-frontend-beta", "web").ready);

        Files.write(file.toPath(), "{\"schema\":\"wrong\",\"state\":\"ready\"}".getBytes(StandardCharsets.UTF_8));
        reader.invalidate();
        assertFalse(reader.resolve("smallphone-frontend-beta", "web").ready);
    }

    @Test
    public void normalizesOnlyLoopbackHttpUrls() {
        assertEquals("http://127.0.0.1:24000/api", OpenHouseEndpointSnapshot.normalizeLoopbackUrl(
            "http://127.0.0.1:24000/api"
        ));
        assertEquals("", OpenHouseEndpointSnapshot.normalizeLoopbackUrl(
            "http://127.0.0.1:24000/?token=secret"
        ));
        assertEquals("", OpenHouseEndpointSnapshot.normalizeLoopbackUrl(
            "http://user:secret@127.0.0.1:24000/"
        ));
    }

    private static String snapshot(String expiresAt, String url) {
        return "{"
            + "\"schemaVersion\":1,"
            + "\"state\":\"ready\","
            + "\"managerInstanceId\":\"test-manager\","
            + "\"snapshotRevision\":7,"
            + "\"portStateGeneration\":7,"
            + "\"generatedAt\":\"" + Instant.now().toString() + "\","
            + "\"expiresAt\":\"" + expiresAt + "\","
            + "\"endpoints\":[{"
            + "\"serviceId\":\"smallphone-frontend-beta\","
            + "\"name\":\"web\","
            + "\"url\":\"" + url + "\""
            + "}]"
            + "}";
    }
}
