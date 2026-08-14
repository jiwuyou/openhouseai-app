package com.ai.assistance.operit.host.setup;

import android.content.Context;
import android.util.AtomicFile;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

/** Small private cache of the canonical service-manager connection returned by Termux. */
public final class WuxianPiConnectionStore {

    private static final String FILE_NAME = "wuxianpi-service-manager-connection.json";
    private static final String KEY_URL = "url";
    private static final String KEY_TOKEN = "token";
    private static final String KEY_UPDATED_AT = "updated_at";

    public static final class Connection {
        public final String serviceManagerBaseUrl;
        public final String token;
        public final long updatedAt;

        Connection(String serviceManagerBaseUrl, String token, long updatedAt) {
            this.serviceManagerBaseUrl = serviceManagerBaseUrl;
            this.token = token;
            this.updatedAt = updatedAt;
        }

        public boolean isReady() {
            return !serviceManagerBaseUrl.isEmpty() && !token.isEmpty();
        }
    }

    private final AtomicFile file;

    private WuxianPiConnectionStore(Context context) {
        Context app = context.getApplicationContext();
        Context safeContext = app == null ? context : app;
        file = new AtomicFile(new File(safeContext.getFilesDir(), FILE_NAME));
    }

    public static WuxianPiConnectionStore get(Context context) {
        return new WuxianPiConnectionStore(context);
    }

    public Connection load() {
        try {
            JSONObject value = new JSONObject(new String(file.readFully(), StandardCharsets.UTF_8));
            return new Connection(
                value.optString(KEY_URL, ""),
                value.optString(KEY_TOKEN, ""),
                value.optLong(KEY_UPDATED_AT, 0L)
            );
        } catch (Exception ignored) {
            return new Connection("", "", 0L);
        }
    }

    public void save(String serviceManagerBaseUrl, String token) {
        if (serviceManagerBaseUrl == null || serviceManagerBaseUrl.trim().isEmpty()
            || token == null || token.trim().isEmpty()) {
            throw new IllegalArgumentException("service-manager connection is incomplete");
        }
        FileOutputStream output = null;
        try {
            output = file.startWrite();
            JSONObject value = new JSONObject()
                .put(KEY_URL, serviceManagerBaseUrl.trim())
                .put(KEY_TOKEN, token.trim())
                .put(KEY_UPDATED_AT, System.currentTimeMillis());
            output.write(value.toString().getBytes(StandardCharsets.UTF_8));
            file.finishWrite(output);
        } catch (Exception exception) {
            if (output != null) {
                file.failWrite(output);
            }
            throw new IllegalStateException("Unable to save service-manager connection", exception);
        }
    }
}
