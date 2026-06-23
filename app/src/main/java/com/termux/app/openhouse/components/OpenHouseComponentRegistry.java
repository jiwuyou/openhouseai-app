package com.termux.app.openhouse.components;

import com.termux.shared.logger.Logger;
import com.termux.shared.termux.TermuxConstants;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;

public final class OpenHouseComponentRegistry {

    private static final String LOG_TAG = "OpenHouseComponents";
    private static final String CONFIG_DIR = ".config/openhouseai";
    private static final String COMPONENTS_DIR = "components.d";
    private static final String REGISTRY_STATE_FILE = "registry-state.json";
    private static final String CONTROL_ENTRY_TYPE_SERVICE_CONTROL = "service-control";

    private OpenHouseComponentRegistry() {
    }

    public static List<OpenHouseComponent> load() {
        return loadWithDiagnostics().components;
    }

    public static LoadResult loadWithDiagnostics() {
        File configDir = new File(TermuxConstants.TERMUX_HOME_DIR_PATH, CONFIG_DIR);
        File dir = new File(configDir, COMPONENTS_DIR);
        RegistryState registryState = readRegistryState(new File(configDir, REGISTRY_STATE_FILE));
        List<String> warnings = new ArrayList<>();
        if (!dir.isDirectory()) {
            warnings.add("components.d 不存在：" + dir.getAbsolutePath());
            return new LoadResult(Collections.emptyList(), registryState, dir, false, 0, 0, warnings);
        }

        File[] files = dir.listFiles((file, name) -> name != null && name.endsWith(".json"));
        if (files == null || files.length == 0) {
            warnings.add("components.d 中没有 JSON 注册项");
            return new LoadResult(Collections.emptyList(), registryState, dir, true, 0, 0, warnings);
        }
        Arrays.sort(files, (left, right) -> left.getName().compareToIgnoreCase(right.getName()));

        List<OpenHouseComponent> components = new ArrayList<>();
        int skippedFiles = 0;
        for (File file : files) {
            try {
                OpenHouseComponent component = parseComponent(readTextFile(file));
                if (component != null) {
                    components.add(component);
                }
            } catch (Exception e) {
                skippedFiles++;
                warnings.add(file.getName() + " 无法读取：" + compactError(e));
                Logger.logStackTraceWithMessage(LOG_TAG,
                    "Ignoring invalid component registry file: " + file.getAbsolutePath(), e);
            }
        }

        Collections.sort(components, new Comparator<OpenHouseComponent>() {
            @Override
            public int compare(OpenHouseComponent left, OpenHouseComponent right) {
                int orderCompare = Integer.compare(left.order, right.order);
                if (orderCompare != 0) {
                    return orderCompare;
                }
                return left.title.compareToIgnoreCase(right.title);
            }
        });
        if (!filesAreEmpty(files) && components.isEmpty()) {
            warnings.add("没有可用的菜单注册项，继续显示内置菜单");
        }
        return new LoadResult(components, registryState, dir, true, files.length, skippedFiles, warnings);
    }

    private static OpenHouseComponent parseComponent(String json) throws Exception {
        JSONObject root = new JSONObject(json);
        assertNoForbiddenKeys(root, "$");
        if (!root.optBoolean("enabled", true)) {
            return null;
        }

        JSONObject shellMenu = root.optJSONObject("shellMenu");
        if (shellMenu == null || !shellMenu.optBoolean("visible", true)) {
            return null;
        }

        JSONObject entry = shellMenu.optJSONObject("entry");
        JSONObject controlEntry = shellMenu.optJSONObject("controlEntry");
        if (entry == null && controlEntry == null) {
            return null;
        }

        String id = sanitizeId(firstNonBlank(root.optString("id", ""), shellMenu.optString("id", "")));
        if (isBlank(id)) {
            return null;
        }

        OpenHouseComponent.EntryType entryType = null;
        String url = null;
        String nativePage = null;
        if (entry != null) {
            entryType = parseEntryType(entry.optString("type", ""));
            if (entryType == null) {
                return null;
            }
            if (entryType == OpenHouseComponent.EntryType.WEBVIEW) {
                url = normalizeWebUrl(entry.optString("url", ""));
                if (isBlank(url)) {
                    return null;
                }
            } else if (entryType == OpenHouseComponent.EntryType.NATIVE_PAGE) {
                nativePage = sanitizeId(firstNonBlank(
                    entry.optString("page", ""),
                    entry.optString("pageId", ""),
                    entry.optString("nativePage", "")));
                if (isBlank(nativePage)) {
                    return null;
                }
            }
        }

        List<String> serviceNames = new ArrayList<>();
        List<String> serviceRefs = new ArrayList<>();
        String controlTitle = "";
        if (controlEntry != null) {
            String controlType = normalizeType(controlEntry.optString("type", ""));
            if (!CONTROL_ENTRY_TYPE_SERVICE_CONTROL.equals(controlType)) {
                return null;
            }
            controlTitle = firstNonBlank(
                controlEntry.optString("title", ""),
                controlEntry.optString("label", ""),
                "控制");
            serviceNames.addAll(readStringList(controlEntry, "serviceNames"));
            serviceNames.addAll(readStringList(controlEntry, "serviceName"));
            serviceRefs.addAll(readStringList(controlEntry, "serviceRefs"));
            serviceRefs.addAll(readStringList(controlEntry, "serviceRef"));
            serviceNames = sanitizeList(serviceNames);
            serviceRefs = sanitizeServiceRefs(serviceRefs);
        }
        if (entryType == null && serviceNames.isEmpty() && serviceRefs.isEmpty()) {
            return null;
        }

        String title = firstNonBlank(
            shellMenu.optString("title", ""),
            shellMenu.optString("label", ""),
            root.optString("title", ""),
            root.optString("name", ""),
            id);
        String subtitle = firstNonBlank(
            shellMenu.optString("subtitle", ""),
            shellMenu.optString("description", ""),
            root.optString("subtitle", ""),
            root.optString("description", ""));
        String section = sanitizeId(firstNonBlank(
            shellMenu.optString("section", ""),
            root.optString("kind", ""),
            "apps"));
        int order = shellMenu.optInt("order", root.optInt("order", 1000));

        return new OpenHouseComponent(
            id,
            title,
            subtitle,
            section,
            order,
            entryType,
            url,
            nativePage,
            controlTitle,
            serviceNames,
            serviceRefs);
    }

    private static OpenHouseComponent.EntryType parseEntryType(String value) {
        String normalized = normalizeType(value);
        if ("webview".equals(normalized) || "web-view".equals(normalized)) {
            return OpenHouseComponent.EntryType.WEBVIEW;
        }
        if ("native-page".equals(normalized) || "native".equals(normalized)) {
            return OpenHouseComponent.EntryType.NATIVE_PAGE;
        }
        if ("terminal".equals(normalized)) {
            return OpenHouseComponent.EntryType.TERMINAL;
        }
        return null;
    }

    private static String normalizeType(String value) {
        if (isBlank(value)) {
            return "";
        }
        return value.trim().toLowerCase(Locale.US).replace('_', '-');
    }

    private static void assertNoForbiddenKeys(Object value, String path) throws Exception {
        if (value instanceof JSONObject) {
            JSONObject object = (JSONObject) value;
            Iterator<String> keys = object.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                if (isForbiddenManifestKey(key)) {
                    throw new IllegalArgumentException("Forbidden component manifest key at " + path + "." + key);
                }
                assertNoForbiddenKeys(object.opt(key), path + "." + key);
            }
        } else if (value instanceof JSONArray) {
            JSONArray array = (JSONArray) value;
            for (int i = 0; i < array.length(); i++) {
                assertNoForbiddenKeys(array.opt(i), path + "[" + i + "]");
            }
        }
    }

    private static boolean isForbiddenManifestKey(String key) {
        if (key == null) {
            return false;
        }
        String normalized = key.trim().toLowerCase(Locale.US);
        return "command".equals(normalized)
            || "shell".equals(normalized)
            || "script".equals(normalized)
            || "args".equals(normalized);
    }

    private static String normalizeWebUrl(String value) {
        if (isBlank(value)) {
            return null;
        }
        try {
            URI uri = URI.create(value.trim());
            String scheme = uri.getScheme();
            if (scheme == null) {
                return null;
            }
            String normalizedScheme = scheme.toLowerCase(Locale.US);
            if (!"http".equals(normalizedScheme) && !"https".equals(normalizedScheme)) {
                return null;
            }
            if (isBlank(uri.getHost())) {
                return null;
            }
            return uri.toString();
        } catch (Exception e) {
            return null;
        }
    }

    private static String sanitizeId(String value) {
        if (isBlank(value)) {
            return null;
        }
        StringBuilder builder = new StringBuilder();
        String trimmed = value.trim();
        for (int i = 0; i < trimmed.length(); i++) {
            char current = trimmed.charAt(i);
            if ((current >= 'a' && current <= 'z')
                || (current >= 'A' && current <= 'Z')
                || (current >= '0' && current <= '9')
                || current == '_'
                || current == '-'
                || current == '.') {
                builder.append(current);
            }
        }
        return builder.length() == 0 ? null : builder.toString();
    }

    private static String sanitizeServiceName(String value) {
        return sanitizeId(value);
    }

    private static List<String> readStringList(JSONObject object, String key) {
        if (object == null || isBlank(key) || !object.has(key)) {
            return Collections.emptyList();
        }
        List<String> values = new ArrayList<>();
        Object rawValue = object.opt(key);
        if (rawValue instanceof JSONArray) {
            JSONArray array = (JSONArray) rawValue;
            for (int i = 0; i < array.length(); i++) {
                String value = array.optString(i, "");
                if (!isBlank(value)) {
                    values.add(value.trim());
                }
            }
        } else {
            String value = object.optString(key, "");
            if (!isBlank(value)) {
                values.add(value.trim());
            }
        }
        return values;
    }

    private static List<String> sanitizeList(List<String> values) {
        if (values == null || values.isEmpty()) {
            return Collections.emptyList();
        }
        List<String> sanitized = new ArrayList<>();
        for (String value : values) {
            String cleanValue = sanitizeServiceName(value);
            if (!isBlank(cleanValue) && !sanitized.contains(cleanValue)) {
                sanitized.add(cleanValue);
            }
        }
        return sanitized;
    }

    private static List<String> sanitizeServiceRefs(List<String> values) {
        if (values == null || values.isEmpty()) {
            return Collections.emptyList();
        }
        List<String> sanitized = new ArrayList<>();
        for (String value : values) {
            String cleanValue = sanitizeServiceRef(value);
            if (!isBlank(cleanValue) && !sanitized.contains(cleanValue)) {
                sanitized.add(cleanValue);
            }
        }
        return sanitized;
    }

    private static String sanitizeServiceRef(String value) {
        if (isBlank(value)) {
            return null;
        }
        String trimmed = value.trim();
        if (!trimmed.startsWith("service-manager://")) {
            return null;
        }
        for (int i = 0; i < trimmed.length(); i++) {
            char current = trimmed.charAt(i);
            if (current <= 0x20 || current == '"' || current == '\'' || current == '\\') {
                return null;
            }
        }
        return trimmed;
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (!isBlank(value)) {
                return value.trim();
            }
        }
        return null;
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private static String readTextFile(File file) throws IOException {
        StringBuilder builder = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
            new FileInputStream(file), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                builder.append(line).append('\n');
            }
        }
        return builder.toString();
    }

    private static RegistryState readRegistryState(File file) {
        if (file == null || !file.isFile()) {
            return RegistryState.missing(file);
        }
        try {
            return RegistryState.parse(file, readTextFile(file));
        } catch (Exception e) {
            Logger.logStackTraceWithMessage(LOG_TAG,
                "Ignoring invalid openhouse registry state file: " + file.getAbsolutePath(), e);
            return RegistryState.invalid(file, compactError(e));
        }
    }

    private static boolean filesAreEmpty(File[] files) {
        return files == null || files.length == 0;
    }

    private static String compactError(Throwable throwable) {
        if (throwable == null) {
            return "unknown";
        }
        String message = throwable.getMessage();
        if (isBlank(message)) {
            message = throwable.getClass().getSimpleName();
        }
        message = message.replace('\n', ' ').replace('\r', ' ').trim();
        return message.length() > 160 ? message.substring(0, 160) + "..." : message;
    }

    private static String readOptionalString(JSONObject object, String... keys) {
        if (object == null || keys == null) {
            return "";
        }
        for (String key : keys) {
            if (isBlank(key) || !object.has(key)) {
                continue;
            }
            String value = object.optString(key, "");
            if (!isBlank(value)) {
                return value.trim();
            }
        }
        return "";
    }

    private static int countJsonFiles(JSONObject root) {
        if (root == null || !root.has("files")) {
            return -1;
        }
        Object files = root.opt("files");
        if (files instanceof JSONArray) {
            return ((JSONArray) files).length();
        }
        if (files instanceof JSONObject) {
            return ((JSONObject) files).length();
        }
        return -1;
    }

    private static List<String> readErrorList(JSONObject root) {
        if (root == null) {
            return Collections.emptyList();
        }
        List<String> errors = new ArrayList<>();
        Object rawErrors = root.opt("errors");
        if (rawErrors instanceof JSONArray) {
            JSONArray array = (JSONArray) rawErrors;
            for (int i = 0; i < array.length(); i++) {
                String value = array.optString(i, "");
                if (!isBlank(value)) {
                    errors.add(value.trim());
                }
            }
        } else if (rawErrors != null) {
            String value = root.optString("errors", "");
            if (!isBlank(value)) {
                errors.add(value.trim());
            }
        }
        String error = root.optString("error", "");
        if (!isBlank(error)) {
            errors.add(error.trim());
        }
        return errors;
    }

    private static String joinLimited(List<String> values, int limit) {
        if (values == null || values.isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        int count = Math.min(values.size(), Math.max(1, limit));
        for (int i = 0; i < count; i++) {
            if (i > 0) {
                builder.append("; ");
            }
            builder.append(values.get(i));
        }
        if (values.size() > count) {
            builder.append("; 另有 ").append(values.size() - count).append(" 个问题");
        }
        String text = builder.toString();
        return text.length() > 360 ? text.substring(0, 360) + "..." : text;
    }

    public static final class LoadResult {
        public final List<OpenHouseComponent> components;
        public final RegistryState registryState;
        public final File componentsDir;
        public final boolean componentsDirExists;
        public final int totalFiles;
        public final int skippedFiles;
        public final List<String> warnings;

        private LoadResult(List<OpenHouseComponent> components,
                           RegistryState registryState,
                           File componentsDir,
                           boolean componentsDirExists,
                           int totalFiles,
                           int skippedFiles,
                           List<String> warnings) {
            this.components = components == null
                ? Collections.emptyList()
                : Collections.unmodifiableList(new ArrayList<>(components));
            this.registryState = registryState == null ? RegistryState.missing(null) : registryState;
            this.componentsDir = componentsDir;
            this.componentsDirExists = componentsDirExists;
            this.totalFiles = Math.max(0, totalFiles);
            this.skippedFiles = Math.max(0, skippedFiles);
            this.warnings = warnings == null
                ? Collections.emptyList()
                : Collections.unmodifiableList(new ArrayList<>(warnings));
        }

        public boolean hasWarnings() {
            return !warnings.isEmpty() || registryState.hasProblem();
        }

        public boolean shouldShowFallbackNavigation() {
            return components.isEmpty() || hasWarnings();
        }

        public String toShortStatusText() {
            StringBuilder builder = new StringBuilder();
            if (components.isEmpty()) {
                builder.append("使用内置菜单");
            } else {
                builder.append("扩展 ").append(components.size()).append(" 个");
            }
            if (totalFiles > 0) {
                builder.append(" / JSON ").append(totalFiles).append(" 个");
            }
            if (skippedFiles > 0) {
                builder.append("，跳过 ").append(skippedFiles).append(" 个");
            }
            if (registryState.exists) {
                builder.append("；").append(registryState.toCompactStatusText());
            }
            return builder.toString();
        }

        public String toDiagnosticText() {
            StringBuilder builder = new StringBuilder();
            builder.append(toShortStatusText());
            if (!componentsDirExists && componentsDir != null) {
                builder.append('\n').append("components.d：").append(componentsDir.getAbsolutePath());
            }
            if (!warnings.isEmpty()) {
                builder.append('\n').append("加载提示：").append(joinLimited(warnings, 3));
            }
            String stateText = registryState.toDiagnosticText();
            if (!isBlank(stateText)) {
                builder.append('\n').append(stateText);
            }
            return builder.toString();
        }
    }

    public static final class RegistryState {
        public final File file;
        public final boolean exists;
        public final boolean valid;
        public final String status;
        public final String generatedAt;
        public final String sourcePath;
        public final String targetPath;
        public final int fileCount;
        public final List<String> errors;
        public final String readError;

        private RegistryState(File file,
                              boolean exists,
                              boolean valid,
                              String status,
                              String generatedAt,
                              String sourcePath,
                              String targetPath,
                              int fileCount,
                              List<String> errors,
                              String readError) {
            this.file = file;
            this.exists = exists;
            this.valid = valid;
            this.status = status == null ? "" : status.trim();
            this.generatedAt = generatedAt == null ? "" : generatedAt.trim();
            this.sourcePath = sourcePath == null ? "" : sourcePath.trim();
            this.targetPath = targetPath == null ? "" : targetPath.trim();
            this.fileCount = fileCount;
            this.errors = errors == null
                ? Collections.emptyList()
                : Collections.unmodifiableList(new ArrayList<>(errors));
            this.readError = readError == null ? "" : readError.trim();
        }

        private static RegistryState missing(File file) {
            return new RegistryState(file, false, false, "", "", "", "", -1, Collections.emptyList(), "");
        }

        private static RegistryState invalid(File file, String readError) {
            return new RegistryState(file, true, false, "", "", "", "", -1, Collections.emptyList(), readError);
        }

        private static RegistryState parse(File file, String json) throws Exception {
            JSONObject root = new JSONObject(json);
            return new RegistryState(
                file,
                true,
                true,
                readOptionalString(root, "status", "state", "phase"),
                readOptionalString(root, "generatedAt", "generated_at", "syncedAt", "synced_at", "updatedAt", "updated_at", "completedAt", "completed_at"),
                readOptionalString(root, "sourcePath", "source_path", "source"),
                readOptionalString(root, "targetPath", "target_path", "target"),
                countJsonFiles(root),
                readErrorList(root),
                "");
        }

        public boolean hasProblem() {
            if (!exists) {
                return false;
            }
            if (!valid || !errors.isEmpty()) {
                return true;
            }
            String normalized = status.trim().toLowerCase(Locale.US);
            return normalized.contains("syncing")
                || normalized.contains("progress")
                || normalized.contains("running")
                || normalized.contains("pending")
                || normalized.contains("partial")
                || normalized.contains("fail")
                || normalized.contains("error");
        }

        public String toCompactStatusText() {
            if (!exists) {
                return "state 未生成";
            }
            if (!valid) {
                return "state 无法解析";
            }
            String state = isBlank(status) ? "unknown" : status;
            StringBuilder builder = new StringBuilder("state=").append(state);
            if (!isBlank(generatedAt)) {
                builder.append(" @ ").append(generatedAt);
            }
            if (fileCount >= 0) {
                builder.append("，files=").append(fileCount);
            }
            if (!errors.isEmpty()) {
                builder.append("，errors=").append(errors.size());
            }
            return builder.toString();
        }

        public String toDiagnosticText() {
            if (!exists) {
                return "registry-state.json：未生成";
            }
            if (!valid) {
                return "registry-state.json：无法解析"
                    + (isBlank(readError) ? "" : "（" + readError + "）");
            }
            StringBuilder builder = new StringBuilder("registry-state.json：");
            builder.append(toCompactStatusText());
            if (!isBlank(sourcePath)) {
                builder.append('\n').append("source：").append(sourcePath);
            }
            if (!isBlank(targetPath)) {
                builder.append('\n').append("target：").append(targetPath);
            }
            if (!errors.isEmpty()) {
                builder.append('\n').append("errors：").append(joinLimited(errors, 3));
            }
            return builder.toString();
        }
    }
}
