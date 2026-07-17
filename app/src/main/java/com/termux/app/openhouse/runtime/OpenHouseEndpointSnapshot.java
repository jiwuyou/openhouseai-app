package com.termux.app.openhouse.runtime;

import com.termux.shared.termux.TermuxConstants;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.net.URL;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * Read-only resolver for the OpenHouse runtime endpoint snapshot.
 *
 * The snapshot is a public projection written by the runtime control plane. It
 * deliberately contains no service-manager token. Consumers must never guess a
 * port when the file is missing, expired, malformed, or not ready.
 */
public final class OpenHouseEndpointSnapshot {

    public static final int SCHEMA_VERSION = 1;
    public static final String DEFAULT_PATH = TermuxConstants.TERMUX_HOME_DIR_PATH
        + "/.config/openhouseai/runtime/endpoints.json";

    private final File file;
    private final LongSupplier nowMillis;
    private final Object lock = new Object();
    private long cachedMtime;
    private long cachedLength = -1L;
    private Snapshot cachedSnapshot;

    public OpenHouseEndpointSnapshot() {
        this(new File(resolvePathFromEnvironment()), System::currentTimeMillis);
    }

    OpenHouseEndpointSnapshot(File file, LongSupplier nowMillis) {
        this.file = file == null ? new File(DEFAULT_PATH) : file;
        this.nowMillis = nowMillis == null ? System::currentTimeMillis : nowMillis;
    }

    public Resolution resolve(String serviceId, String endpointName) {
        String cleanServiceId = sanitizeId(serviceId);
        String cleanEndpointName = sanitizeId(endpointName);
        if (cleanServiceId.isEmpty() || cleanEndpointName.isEmpty()) {
            return Resolution.unknown("endpoint 引用无效");
        }
        Snapshot snapshot = readSnapshot();
        if (snapshot == null) {
            return Resolution.unknown("runtime endpoint snapshot 不可用");
        }
        Record record = snapshot.records.get(key(cleanServiceId, cleanEndpointName));
        if (record == null) {
            return Resolution.unknown("endpoint 尚未发布");
        }
        if (!"ready".equalsIgnoreCase(record.state)) {
            return Resolution.unknown("endpoint 状态不是 ready");
        }
        if (record.expiresAtMillis > 0 && nowMillis.getAsLong() >= record.expiresAtMillis) {
            return Resolution.unknown("endpoint snapshot 已过期");
        }
        return Resolution.ready(record.url, snapshot.generation);
    }

    public void invalidate() {
        synchronized (lock) {
            cachedMtime = 0L;
            cachedLength = -1L;
            cachedSnapshot = null;
        }
    }

    public String path() {
        return file.getAbsolutePath();
    }

    private Snapshot readSnapshot() {
        long mtime = file.isFile() ? file.lastModified() : 0L;
        long length = file.isFile() ? file.length() : -1L;
        synchronized (lock) {
            if (cachedSnapshot != null && cachedMtime == mtime && cachedLength == length) {
                return cachedSnapshot;
            }
            if (mtime == 0L || length < 0L) {
                cachedMtime = mtime;
                cachedLength = length;
                cachedSnapshot = null;
                return null;
            }
            try {
                Snapshot parsed = parse(readText(file));
                cachedMtime = mtime;
                cachedLength = length;
                cachedSnapshot = parsed;
                return parsed;
            } catch (IOException | JSONException | RuntimeException e) {
                cachedMtime = mtime;
                cachedLength = length;
                cachedSnapshot = null;
                return null;
            }
        }
    }

    private Snapshot parse(String text) throws JSONException {
        Object value = new JSONTokener(text == null ? "" : text.trim()).nextValue();
        if (!(value instanceof JSONObject)) {
            throw new JSONException("endpoint snapshot must be an object");
        }
        JSONObject root = (JSONObject) value;
        if (root.optInt("schemaVersion", -1) != SCHEMA_VERSION) {
            throw new JSONException("unsupported endpoint snapshot schema");
        }
        if (!"ready".equalsIgnoreCase(root.optString("state", ""))) {
            throw new JSONException("endpoint snapshot is not ready");
        }
        long expiresAt = parseExpiry(root.optString("expiresAt", ""));
        if (expiresAt <= nowMillis.getAsLong()) {
            throw new JSONException("endpoint snapshot expired");
        }
        if (root.optString("managerInstanceId", "").trim().isEmpty()) {
            throw new JSONException("endpoint snapshot managerInstanceId is missing");
        }
        parseExpiry(root.optString("generatedAt", ""));
        long generation = root.optLong("snapshotRevision", -1L);
        if (generation < 0L) {
            throw new JSONException("endpoint snapshot generation is invalid");
        }
        long portStateGeneration = root.optLong("portStateGeneration", -1L);
        if (portStateGeneration < 0L) {
            throw new JSONException("endpoint snapshot portStateGeneration is invalid");
        }

        Map<String, Record> records = new HashMap<>();
        Object rawEndpoints = root.opt("endpoints");
        if (rawEndpoints instanceof JSONArray) {
            JSONArray array = (JSONArray) rawEndpoints;
            for (int i = 0; i < array.length(); i++) {
                addRecord(records, array.optJSONObject(i), expiresAt);
            }
        } else if (rawEndpoints instanceof JSONObject) {
            JSONObject object = (JSONObject) rawEndpoints;
            JSONArray names = object.names();
            if (names != null) {
                for (int i = 0; i < names.length(); i++) {
                    JSONObject item = object.optJSONObject(names.optString(i));
                    addRecord(records, item, expiresAt);
                }
            }
        } else {
            throw new JSONException("endpoint snapshot endpoints is missing");
        }
        return new Snapshot(generation, records);
    }

    private void addRecord(Map<String, Record> records, JSONObject item, long snapshotExpiry) throws JSONException {
        if (item == null) {
            return;
        }
        String serviceId = sanitizeId(item.optString("serviceId", ""));
        String endpointName = sanitizeId(item.optString("name", item.optString("endpointName", "")));
        if (serviceId.isEmpty() || endpointName.isEmpty()) {
            throw new JSONException("endpoint serviceId/endpointName is invalid");
        }
        String state = item.optString("state", "ready").trim();
        if (!state.isEmpty() && !"ready".equalsIgnoreCase(state)) {
            return;
        }
        String url = normalizeLoopbackUrl(item.optString("url", ""));
        if (url.isEmpty()) {
            throw new JSONException("endpoint URL is not a loopback HTTP URL");
        }
        String expiryText = item.optString("expiresAt", "").trim();
        long expiry = expiryText.isEmpty() ? snapshotExpiry : parseExpiry(expiryText);
        if (expiry <= nowMillis.getAsLong()) {
            return;
        }
        records.put(key(serviceId, endpointName), new Record(serviceId, endpointName, state, url, expiry));
    }

    static String normalizeLoopbackUrl(String value) {
        String raw = value == null ? "" : value.trim();
        if (raw.isEmpty() || raw.indexOf('@') >= 0) {
            return "";
        }
        try {
            URL parsed = new URL(raw);
            if (!"http".equalsIgnoreCase(parsed.getProtocol())
                || parsed.getHost() == null
                || !isLoopback(parsed.getHost())
                || parsed.getQuery() != null
                || parsed.getRef() != null) {
                return "";
            }
            String host = parsed.getHost();
            if (host.indexOf(':') >= 0 && !host.startsWith("[")) {
                host = "[" + host + "]";
            }
            int port = parsed.getPort();
            if (port <= 0 || port > 65535) {
                return "";
            }
            String path = parsed.getPath();
            if (path == null || path.isEmpty()) {
                path = "/";
            } else if (!path.startsWith("/")) {
                path = "/" + path;
            }
            return "http://" + host + ":" + port + path;
        } catch (IOException e) {
            return "";
        }
    }

    private static boolean isLoopback(String host) {
        return "127.0.0.1".equals(host)
            || "localhost".equalsIgnoreCase(host)
            || "::1".equals(host)
            || "[::1]".equals(host);
    }

    private static long parseExpiry(String value) throws JSONException {
        String text = value == null ? "" : value.trim();
        if (text.isEmpty()) {
            throw new JSONException("endpoint snapshot expiresAt is missing");
        }
        try {
            return Instant.parse(text).toEpochMilli();
        } catch (DateTimeParseException e) {
            try {
                long millis = Long.parseLong(text);
                return millis > 10_000_000_000L ? millis : millis * 1000L;
            } catch (NumberFormatException ignored) {
                throw new JSONException("endpoint snapshot expiresAt is invalid");
            }
        }
    }

    private static String resolvePathFromEnvironment() {
        String value = System.getenv("OPENHOUSE_ENDPOINTS_FILE");
        return value == null || value.trim().isEmpty() ? DEFAULT_PATH : value.trim();
    }

    private static String readText(File target) throws IOException {
        StringBuilder builder = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new FileReader(target))) {
            String line;
            while ((line = reader.readLine()) != null) {
                builder.append(line).append('\n');
            }
        }
        return builder.toString();
    }

    private static String key(String serviceId, String endpointName) {
        return serviceId + '\u0000' + endpointName;
    }

    private static String sanitizeId(String value) {
        String text = value == null ? "" : value.trim();
        if (text.isEmpty()) {
            return "";
        }
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (!((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                || (c >= '0' && c <= '9') || c == '-' || c == '_' || c == '.')) {
                return "";
            }
        }
        return text;
    }

    public static final class Resolution {
        public final boolean ready;
        public final String url;
        public final long generation;
        public final String message;

        private Resolution(boolean ready, String url, long generation, String message) {
            this.ready = ready;
            this.url = url == null ? "" : url;
            this.generation = generation;
            this.message = message == null ? "" : message;
        }

        static Resolution ready(String url, long generation) {
            return new Resolution(true, url, generation, "endpoint ready");
        }

        static Resolution unknown(String message) {
            return new Resolution(false, "", 0L, message);
        }
    }

    private static final class Snapshot {
        final long generation;
        final Map<String, Record> records;

        Snapshot(long generation, Map<String, Record> records) {
            this.generation = generation;
            this.records = Collections.unmodifiableMap(new HashMap<>(records));
        }
    }

    private static final class Record {
        final String serviceId;
        final String endpointName;
        final String state;
        final String url;
        final long expiresAtMillis;

        Record(String serviceId, String endpointName, String state, String url, long expiresAtMillis) {
            this.serviceId = serviceId;
            this.endpointName = endpointName;
            this.state = state;
            this.url = url;
            this.expiresAtMillis = expiresAtMillis;
        }
    }
}
