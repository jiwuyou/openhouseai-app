package com.termux.app.browser;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.termux.shared.logger.Logger;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class ControlledBrowserRpcFiles {

    private static final String LOG_TAG = "ControlledBrowserRpc";
    private static final String BROWSER_DIR = ".openhouse-browser";
    private static final String REQUESTS_DIR = "requests";
    private static final String RESULTS_DIR = "results";
    private static final String TOKEN_FILE = "token";

    private static final String LEGACY_REQUEST_ID = "com.termux.openhouse.browser.REQUEST_ID";
    private static final String LEGACY_REQUEST_FILE = "com.termux.openhouse.browser.REQUEST_FILE";
    private static final String LEGACY_RESULT_FILE = "com.termux.openhouse.browser.RESULT_FILE";
    private static final String LEGACY_TIMEOUT_MS = "com.termux.openhouse.browser.TIMEOUT_MS";
    private static final String LEGACY_TOKEN = "com.termux.openhouse.browser.TOKEN";

    private ControlledBrowserRpcFiles() {}

    public static boolean hasBrowserCommand(@Nullable Intent intent) {
        if (intent == null || intent.getExtras() == null) {
            return false;
        }
        Bundle extras = intent.getExtras();
        return !isBlank(firstNonBlank(
            extras.getString(ControlledBrowserContract.EXTRA_COMMAND),
            extras.getString(ControlledBrowserContract.LEGACY_EXTRA_COMMAND),
            extras.getString("browser_command"),
            extras.getString(ControlledBrowserContract.EXTRA_REQUEST_FILE),
            extras.getString(LEGACY_REQUEST_FILE)));
    }

    @Nullable
    public static Bundle normalizeCommand(@NonNull Context context, @Nullable Intent intent) {
        if (intent == null || intent.getExtras() == null) {
            return null;
        }
        Bundle extras = intent.getExtras();
        String requestFile = firstNonBlank(
            extras.getString(ControlledBrowserContract.EXTRA_REQUEST_FILE),
            extras.getString(LEGACY_REQUEST_FILE));
        String resultFile = firstNonBlank(
            extras.getString(ControlledBrowserContract.EXTRA_RESULT_FILE),
            extras.getString(LEGACY_RESULT_FILE));
        String requestId = firstNonBlank(
            extras.getString(ControlledBrowserContract.EXTRA_REQUEST_ID),
            extras.getString(LEGACY_REQUEST_ID));

        Bundle normalized = normalizeExtrasOnly(extras);
        if (!isBlank(requestFile)) {
            try {
                File request = validateRequestFile(context, requestFile);
                mergeJsonRequest(normalized, readRequestObject(request));
                requestId = firstNonBlank(
                    normalized.getString(ControlledBrowserContract.EXTRA_REQUEST_ID),
                    requestId);
                resultFile = firstNonBlank(
                    normalized.getString(ControlledBrowserContract.EXTRA_RESULT_FILE),
                    resultFile);
            } catch (Exception e) {
                writeErrorResult(context, resultFile, requestId, "invalid_request", e.getMessage());
                return null;
            }
        }

        String command = normalized.getString(ControlledBrowserContract.EXTRA_COMMAND);
        if (isBlank(command) && isBlank(requestFile)) {
            return null;
        }

        resultFile = normalized.getString(ControlledBrowserContract.EXTRA_RESULT_FILE);
        requestId = normalized.getString(ControlledBrowserContract.EXTRA_REQUEST_ID);
        try {
            if (!isBlank(resultFile)) {
                validateResultFile(context, resultFile);
            }
            if (isTokenRequired(command, requestFile, resultFile)
                && !isTokenValid(context, normalized.getString(ControlledBrowserContract.EXTRA_TOKEN))) {
                writeErrorResult(context, resultFile, requestId, "unauthorized", "Invalid browser RPC token");
                return null;
            }
        } catch (Exception e) {
            writeErrorResult(context, resultFile, requestId, "invalid_rpc_path", e.getMessage());
            return null;
        }

        normalized.remove(ControlledBrowserContract.EXTRA_REQUEST_FILE);
        return normalized;
    }

    public static void writeResultIfRequested(
        @NonNull Context context,
        @Nullable Bundle command,
        @NonNull ControlledBrowserCommandResult result
    ) {
        String resultFile = command == null ? null : command.getString(ControlledBrowserContract.EXTRA_RESULT_FILE);
        if (isBlank(resultFile)) {
            return;
        }
        try {
            writeResultFile(context, resultFile, result.toJsonString());
        } catch (Exception e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to write browser command result", e);
        }
    }

    public static void writeErrorResult(
        @NonNull Context context,
        @Nullable String resultFile,
        @Nullable String requestId,
        @Nullable String code,
        @Nullable String message
    ) {
        if (isBlank(resultFile)) {
            return;
        }
        try {
            JSONObject error = new JSONObject();
            error.put("code", isBlank(code) ? "browser_rpc_error" : code);
            error.put("message", message == null ? "" : message);

            JSONObject result = new JSONObject();
            result.put("ok", false);
            result.put("successful", false);
            result.put("requestId", isBlank(requestId) ? JSONObject.NULL : requestId);
            result.put("message", message == null ? "" : message);
            result.put("error", error);
            writeResultFile(context, resultFile, result.toString());
        } catch (Exception e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to write browser RPC error result", e);
        }
    }

    @NonNull
    private static Bundle normalizeExtrasOnly(@NonNull Bundle extras) {
        Bundle normalized = new Bundle();
        String command = firstNonBlank(
            extras.getString(ControlledBrowserContract.EXTRA_COMMAND),
            extras.getString(ControlledBrowserContract.LEGACY_EXTRA_COMMAND),
            extras.getString("browser_command"));
        if (!isBlank(command)) {
            normalized.putString(ControlledBrowserContract.EXTRA_COMMAND, command);
        }

        copyStringExtra(extras, normalized, ControlledBrowserContract.EXTRA_REQUEST_ID, LEGACY_REQUEST_ID);
        copyStringExtra(extras, normalized, ControlledBrowserContract.EXTRA_REQUEST_FILE, LEGACY_REQUEST_FILE);
        copyStringExtra(extras, normalized, ControlledBrowserContract.EXTRA_RESULT_FILE, LEGACY_RESULT_FILE);
        copyStringExtra(extras, normalized, ControlledBrowserContract.EXTRA_TOKEN, LEGACY_TOKEN);

        Integer timeoutMs = getIntegerExtra(extras, ControlledBrowserContract.EXTRA_TIMEOUT_MS);
        if (timeoutMs == null) {
            timeoutMs = getIntegerExtra(extras, LEGACY_TIMEOUT_MS);
        }
        if (timeoutMs != null) {
            normalized.putInt(ControlledBrowserContract.EXTRA_TIMEOUT_MS, timeoutMs);
        }

        String url = firstNonBlank(
            extras.getString(ControlledBrowserContract.EXTRA_URL),
            extras.getString(ControlledBrowserContract.LEGACY_EXTRA_URL),
            extras.getString("browser_url"));
        if (!isBlank(url)) {
            normalized.putString(ControlledBrowserContract.EXTRA_URL, url);
        }

        String tabId = extras.getString(ControlledBrowserContract.EXTRA_TAB_ID);
        if (!isBlank(tabId)) {
            normalized.putString(ControlledBrowserContract.EXTRA_TAB_ID, tabId);
        }
        Integer tabIndex = getIntegerExtra(extras, ControlledBrowserContract.EXTRA_TAB_INDEX);
        if (tabIndex != null) {
            normalized.putInt(ControlledBrowserContract.EXTRA_TAB_INDEX, tabIndex);
        }

        String legacyTab = firstNonBlank(
            extras.getString(ControlledBrowserContract.LEGACY_EXTRA_TAB),
            extras.getString("browser_tab"),
            extras.getString("browser_tab_id"));
        if (!isBlank(legacyTab)) {
            Integer legacyTabIndex = parseInteger(legacyTab);
            if (legacyTabIndex == null) {
                normalized.putString(ControlledBrowserContract.EXTRA_TAB_ID, legacyTab);
            } else {
                normalized.putInt(ControlledBrowserContract.EXTRA_TAB_INDEX, legacyTabIndex);
            }
        }
        Integer shortTabIndex = getIntegerExtra(extras, "browser_tab_index");
        if (shortTabIndex != null) {
            normalized.putInt(ControlledBrowserContract.EXTRA_TAB_INDEX, shortTabIndex);
        }

        copyStringExtra(extras, normalized, ControlledBrowserContract.EXTRA_TITLE);
        copyStringExtra(extras, normalized, ControlledBrowserContract.EXTRA_PAYLOAD);
        copyStringExtra(extras, normalized, ControlledBrowserContract.EXTRA_OUTPUT);
        copyStringExtra(extras, normalized, ControlledBrowserContract.EXTRA_METHOD);
        copyStringExtra(extras, normalized, ControlledBrowserContract.EXTRA_PARAMS);
        if (extras.containsKey(ControlledBrowserContract.EXTRA_ACTIVATE)) {
            normalized.putBoolean(ControlledBrowserContract.EXTRA_ACTIVATE,
                extras.getBoolean(ControlledBrowserContract.EXTRA_ACTIVATE, true));
        }
        return normalized;
    }

    private static void copyStringExtra(@NonNull Bundle source, @NonNull Bundle target, @NonNull String key) {
        String value = source.getString(key);
        if (!isBlank(value)) {
            target.putString(key, value);
        }
    }

    private static void copyStringExtra(
        @NonNull Bundle source,
        @NonNull Bundle target,
        @NonNull String officialKey,
        @NonNull String legacyKey
    ) {
        String value = firstNonBlank(source.getString(officialKey), source.getString(legacyKey));
        if (!isBlank(value)) {
            target.putString(officialKey, value);
        }
    }

    private static JSONObject readRequestObject(@NonNull File request) throws IOException, JSONException {
        StringBuilder builder = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
            new InputStreamReader(new FileInputStream(request), StandardCharsets.UTF_8))) {
            char[] buffer = new char[4096];
            int read;
            while ((read = reader.read(buffer)) >= 0) {
                builder.append(buffer, 0, read);
            }
        }
        Object parsed = new JSONTokener(builder.toString()).nextValue();
        if (!(parsed instanceof JSONObject)) {
            throw new JSONException("Browser request file must contain a JSON object");
        }
        return (JSONObject) parsed;
    }

    private static void mergeJsonRequest(@NonNull Bundle target, @NonNull JSONObject request)
        throws JSONException {
        copyJsonField(target, request, ControlledBrowserContract.FIELD_COMMAND, ControlledBrowserContract.EXTRA_COMMAND);
        copyJsonField(target, request, ControlledBrowserContract.FIELD_URL, ControlledBrowserContract.EXTRA_URL);
        copyJsonField(target, request, ControlledBrowserContract.FIELD_TAB_ID, ControlledBrowserContract.EXTRA_TAB_ID);
        copyJsonField(target, request, ControlledBrowserContract.FIELD_TAB_INDEX, ControlledBrowserContract.EXTRA_TAB_INDEX);
        copyJsonField(target, request, ControlledBrowserContract.FIELD_TITLE, ControlledBrowserContract.EXTRA_TITLE);
        copyJsonField(target, request, ControlledBrowserContract.FIELD_ACTIVATE, ControlledBrowserContract.EXTRA_ACTIVATE);
        copyJsonField(target, request, ControlledBrowserContract.FIELD_REQUEST_ID, ControlledBrowserContract.EXTRA_REQUEST_ID);
        copyJsonField(target, request, ControlledBrowserContract.FIELD_REQUEST_FILE, ControlledBrowserContract.EXTRA_REQUEST_FILE);
        copyJsonField(target, request, ControlledBrowserContract.FIELD_RESULT_FILE, ControlledBrowserContract.EXTRA_RESULT_FILE);
        copyJsonField(target, request, ControlledBrowserContract.FIELD_TIMEOUT_MS, ControlledBrowserContract.EXTRA_TIMEOUT_MS);
        copyJsonField(target, request, ControlledBrowserContract.FIELD_TOKEN, ControlledBrowserContract.EXTRA_TOKEN);
        copyJsonField(target, request, ControlledBrowserContract.FIELD_PAYLOAD, ControlledBrowserContract.EXTRA_PAYLOAD);
        copyJsonField(target, request, ControlledBrowserContract.FIELD_OUTPUT, ControlledBrowserContract.EXTRA_OUTPUT);
        copyJsonField(target, request, ControlledBrowserContract.FIELD_METHOD, ControlledBrowserContract.EXTRA_METHOD);
        copyJsonField(target, request, ControlledBrowserContract.FIELD_PARAMS, ControlledBrowserContract.EXTRA_PARAMS);

        JSONArray names = request.names();
        if (names == null) {
            return;
        }
        for (int i = 0; i < names.length(); i++) {
            String key = names.getString(i);
            if (!target.containsKey(key)) {
                putJsonValue(target, key, request.opt(key));
            }
        }
    }

    private static void copyJsonField(
        @NonNull Bundle target,
        @NonNull JSONObject request,
        @NonNull String jsonKey,
        @NonNull String bundleKey
    ) throws JSONException {
        if (request.has(jsonKey)) {
            putJsonValue(target, bundleKey, request.get(jsonKey));
        }
    }

    private static void putJsonValue(@NonNull Bundle target, @NonNull String key, @Nullable Object value)
        throws JSONException {
        if (value == null || value == JSONObject.NULL) {
            return;
        }
        if (value instanceof Boolean) {
            target.putBoolean(key, (Boolean) value);
        } else if (value instanceof Integer) {
            target.putInt(key, (Integer) value);
        } else if (value instanceof Long) {
            target.putLong(key, (Long) value);
        } else if (value instanceof Number) {
            target.putDouble(key, ((Number) value).doubleValue());
        } else if (value instanceof JSONObject || value instanceof JSONArray) {
            target.putString(key, value.toString());
        } else {
            target.putString(key, String.valueOf(value));
        }
    }

    private static boolean isTokenRequired(
        @Nullable String command,
        @Nullable String requestFile,
        @Nullable String resultFile
    ) {
        if (!isBlank(requestFile) || !isBlank(resultFile)) {
            return true;
        }
        if (isBlank(command)) {
            return false;
        }
        String normalized = command.trim().toLowerCase(Locale.US);
        return !ControlledBrowserContract.COMMAND_OPEN.equals(normalized)
            && !ControlledBrowserContract.COMMAND_NEW_TAB.equals(normalized)
            && !ControlledBrowserContract.COMMAND_SWITCH.equals(normalized)
            && !ControlledBrowserContract.COMMAND_CLOSE.equals(normalized)
            && !ControlledBrowserContract.COMMAND_RELOAD.equals(normalized)
            && !ControlledBrowserContract.COMMAND_BACK.equals(normalized)
            && !ControlledBrowserContract.COMMAND_FORWARD.equals(normalized);
    }

    private static boolean isTokenValid(@NonNull Context context, @Nullable String token) throws IOException {
        if (isBlank(token)) {
            return false;
        }
        for (File tokenFile : tokenFiles(context)) {
            if (!tokenFile.isFile()) {
                continue;
            }
            String expected = readTokenFile(tokenFile);
            if (!isBlank(expected) && expected.equals(token)) {
                return true;
            }
        }
        return false;
    }

    @NonNull
    private static String readTokenFile(@NonNull File tokenFile) throws IOException {
        byte[] buffer = new byte[(int) Math.min(tokenFile.length(), 8192)];
        int read;
        try (FileInputStream inputStream = new FileInputStream(tokenFile)) {
            read = inputStream.read(buffer);
        }
        return read <= 0 ? "" : new String(buffer, 0, read, StandardCharsets.UTF_8).trim();
    }

    @NonNull
    private static File validateRequestFile(@NonNull Context context, @NonNull String path)
        throws IOException {
        File file = validateRpcPath(context, path, REQUESTS_DIR);
        if (!file.isFile()) {
            throw new IOException("Browser request file does not exist: " + file.getPath());
        }
        return file;
    }

    @NonNull
    private static File validateResultFile(@NonNull Context context, @NonNull String path)
        throws IOException {
        return validateRpcPath(context, path, RESULTS_DIR);
    }

    @NonNull
    private static File validateRpcPath(
        @NonNull Context context,
        @NonNull String path,
        @NonNull String expectedLeaf
    ) throws IOException {
        if (isBlank(path)) {
            throw new IOException("Missing browser RPC path");
        }
        File target = new File(path);
        File parent = target.getParentFile();
        if (parent == null) {
            throw new IOException("Browser RPC path has no parent: " + path);
        }

        File canonicalParent = parent.getCanonicalFile();
        File canonicalTarget = new File(canonicalParent, target.getName());
        for (File allowedDir : allowedRpcDirs(context, expectedLeaf)) {
            File canonicalAllowed = allowedDir.getCanonicalFile();
            if (isSameOrChild(canonicalParent, canonicalAllowed)) {
                return canonicalTarget;
            }
        }
        throw new IOException("Browser RPC path outside ." + BROWSER_DIR + "/" + expectedLeaf
            + ": " + canonicalTarget.getPath());
    }

    private static void writeResultFile(
        @NonNull Context context,
        @NonNull String resultFile,
        @NonNull String json
    ) throws IOException {
        File outputFile = validateResultFile(context, resultFile);
        File parent = outputFile.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("Unable to create result directory: " + parent);
        }
        File tmpFile = new File(parent, outputFile.getName() + ".tmp." + android.os.Process.myPid());
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        try (FileOutputStream outputStream = new FileOutputStream(tmpFile)) {
            outputStream.write(bytes);
            outputStream.write('\n');
            outputStream.getFD().sync();
        }
        if (!tmpFile.renameTo(outputFile)) {
            throw new IOException("Unable to move result file into place: " + outputFile);
        }
    }

    @NonNull
    private static File termuxHomeDir(@NonNull Context context) {
        return new File(new File(context.getApplicationInfo().dataDir, "files"), "home");
    }

    @NonNull
    private static List<File> allowedRpcDirs(@NonNull Context context, @NonNull String leaf) {
        List<File> roots = new ArrayList<>();
        for (File homeDir : termuxHomeDirs(context)) {
            roots.add(new File(homeDir, BROWSER_DIR + File.separator + leaf));
        }
        return roots;
    }

    @NonNull
    private static List<File> tokenFiles(@NonNull Context context) {
        List<File> files = new ArrayList<>();
        for (File homeDir : termuxHomeDirs(context)) {
            files.add(new File(new File(homeDir, BROWSER_DIR), TOKEN_FILE));
        }
        return files;
    }

    @NonNull
    private static List<File> termuxHomeDirs(@NonNull Context context) {
        String packageName = context.getPackageName();
        List<File> roots = new ArrayList<>();
        roots.add(termuxHomeDir(context));
        roots.add(new File("/data/data/" + packageName + "/files/home"));
        roots.add(new File("/data/user/0/" + packageName + "/files/home"));
        return roots;
    }

    private static boolean isSameOrChild(@NonNull File child, @NonNull File parent) {
        String childPath = child.getPath();
        String parentPath = parent.getPath();
        return childPath.equals(parentPath) || childPath.startsWith(parentPath + File.separator);
    }

    @Nullable
    private static String firstNonBlank(@Nullable String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (!isBlank(value)) {
                return value;
            }
        }
        return null;
    }

    private static boolean isBlank(@Nullable String value) {
        return value == null || value.trim().isEmpty();
    }

    @Nullable
    private static Integer getIntegerExtra(@NonNull Bundle extras, @NonNull String key) {
        if (!extras.containsKey(key)) {
            return null;
        }
        Object value = extras.get(key);
        if (value instanceof Integer) {
            return (Integer) value;
        }
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        return value == null ? null : parseInteger(value.toString());
    }

    @Nullable
    private static Integer parseInteger(@Nullable String value) {
        if (isBlank(value)) {
            return null;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (Exception e) {
            return null;
        }
    }
}
