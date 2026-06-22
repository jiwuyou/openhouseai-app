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
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;

public final class OpenHouseComponentRegistry {

    private static final String LOG_TAG = "OpenHouseComponents";
    private static final String COMPONENTS_DIR = ".config/openhouseai/components.d";
    private static final String CONTROL_ENTRY_TYPE_SERVICE_CONTROL = "service-control";

    private OpenHouseComponentRegistry() {
    }

    public static List<OpenHouseComponent> load() {
        File dir = new File(TermuxConstants.TERMUX_HOME_DIR_PATH, COMPONENTS_DIR);
        if (!dir.isDirectory()) {
            return Collections.emptyList();
        }

        File[] files = dir.listFiles((file, name) -> name != null && name.endsWith(".json"));
        if (files == null || files.length == 0) {
            return Collections.emptyList();
        }

        List<OpenHouseComponent> components = new ArrayList<>();
        for (File file : files) {
            try {
                OpenHouseComponent component = parseComponent(readTextFile(file));
                if (component != null) {
                    components.add(component);
                }
            } catch (Exception e) {
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
        return components;
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
}
