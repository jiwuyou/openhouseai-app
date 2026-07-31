package com.wuxianpi.openhouse.core.registry;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;

public final class OpenHouseComponentParser {
    public OpenHouseComponent parse(RegistryManifest manifest, String source) throws JSONException {
        if (manifest == null) throw new JSONException("manifest is required");
        JSONObject root = manifest.asJsonObject();
        assertNoForbiddenKeys(root, "$");
        if (!root.optBoolean("enabled", true)) return null;

        JSONObject shellMenu = root.optJSONObject("shellMenu");
        JSONObject smallphoneApp = root.optJSONObject("smallphoneApp");
        JSONObject menu = shellMenu != null ? shellMenu : (smallphoneApp != null ? smallphoneApp : root);
        JSONObject entry = firstObject(menu.optJSONObject("entry"), root.optJSONObject("entry"));
        JSONObject control = firstObject(menu.optJSONObject("controlEntry"), root.optJSONObject("controlEntry"));

        EntryFields entryFields = parseEntry(entry);
        ControlFields controlFields = parseControl(control, root);
        if (entry != null && entryFields == null) throw new JSONException("invalid component entry");
        if (control != null && controlFields == null) throw new JSONException("invalid component controlEntry");
        if (entryFields == null && controlFields == null) return null;

        String title = firstNonBlank(menu.optString("title", ""), menu.optString("label", ""),
            root.optString("title", ""), root.optString("name", ""), manifest.id);
        String subtitle = firstNonBlank(menu.optString("subtitle", ""), menu.optString("description", ""),
            root.optString("subtitle", ""), root.optString("description", ""));
        String section = safeId(firstNonBlank(menu.optString("section", ""), root.optString("kind", ""), "apps"));
        int order = menu.has("order") ? menu.optInt("order", 1000) : root.optInt("order", 1000);
        boolean visible = readBoolean(firstPresent(menu, root, "visible"), true);
        boolean favorite = readBoolean(firstPresent(menu, root, "favorite", "pinned"), false);
        boolean home = readBoolean(firstPresent(menu, root, "home"), false);

        JSONObject desktop = firstObject(menu.optJSONObject("desktop"), root.optJSONObject("desktop"));
        int desktopOrder = desktop == null ? order : desktop.optInt("order", order);
        boolean desktopVisible = desktop == null ? visible : readBoolean(firstPresent(desktop, null, "visible"), visible);
        boolean desktopPinned = desktop == null ? favorite : readBoolean(firstPresent(desktop, null, "pinned", "favorite"), favorite);
        boolean desktopHome = desktop == null ? home : readBoolean(firstPresent(desktop, null, "home"), home);
        String iconKey = safeIcon(firstNonBlank(optional(desktop, "iconKey", "icon_key", "icon"),
            optional(menu, "iconKey", "icon_key", "icon"), defaultIcon(manifest.id, section, entryFields)));
        String iconLabel = firstNonBlank(optional(desktop, "iconLabel", "icon_label", "label"),
            optional(menu, "iconLabel", "icon_label"), deriveIconLabel(title));
        if (iconLabel.length() > 4) iconLabel = iconLabel.substring(0, 4);

        return new OpenHouseComponent(
            manifest.id, title, subtitle, section, order, iconKey, iconLabel, desktopOrder,
            desktopPinned, desktopHome, desktopVisible,
            entryFields == null ? null : entryFields.type,
            entryFields == null ? "" : entryFields.url,
            entryFields == null ? "" : entryFields.nativePage,
            entryFields == null ? "" : entryFields.activityClassName,
            controlFields == null ? "" : controlFields.title,
            visible, favorite, home, false, source,
            controlFields == null ? Collections.emptyList() : controlFields.serviceNames,
            controlFields == null ? Collections.emptyList() : controlFields.serviceRefs
        );
    }

    private static EntryFields parseEntry(JSONObject entry) {
        if (entry == null) return null;
        String type = normalizeType(entry.optString("type", ""));
        if (type.isEmpty() && !firstNonBlank(entry.optString("url", ""), entry.optString("href", "")).isEmpty()) {
            type = "webview";
        }
        if ("webview".equals(type) || "web-view".equals(type)) {
            String url = normalizeHttpUrl(firstNonBlank(entry.optString("url", ""), entry.optString("href", ""), entry.optString("uri", "")));
            return url.isEmpty() ? null : new EntryFields(OpenHouseComponent.EntryType.WEBVIEW, url, "", "");
        }
        if ("native-page".equals(type) || "native-view".equals(type) || "native".equals(type)) {
            String page = safeId(firstNonBlank(entry.optString("page", ""), entry.optString("pageId", ""),
                entry.optString("nativePage", ""), entry.optString("view", "")));
            return page.isEmpty() ? null : new EntryFields(OpenHouseComponent.EntryType.NATIVE_PAGE, "", page, "");
        }
        if ("terminal".equals(type)) return new EntryFields(OpenHouseComponent.EntryType.TERMINAL, "", "", "");
        if ("files".equals(type)) return new EntryFields(OpenHouseComponent.EntryType.FILES, "", "", "");
        if ("service-control".equals(type) || "servicecontrol".equals(type) || "services".equals(type)) {
            return new EntryFields(OpenHouseComponent.EntryType.SERVICE_CONTROL, "", "", "");
        }
        if ("android-activity".equals(type) || "activity".equals(type) || "android".equals(type)) {
            String className = safeClassName(firstNonBlank(entry.optString("className", ""), entry.optString("class", ""),
                entry.optString("activity", ""), entry.optString("activityClass", "")));
            return className.isEmpty() ? null : new EntryFields(OpenHouseComponent.EntryType.ANDROID_ACTIVITY, "", "", className);
        }
        return null;
    }

    private static ControlFields parseControl(JSONObject control, JSONObject root) {
        List<String> names = new ArrayList<>();
        List<String> refs = new ArrayList<>();
        String title = "控制";
        if (control != null) {
            String type = normalizeType(control.optString("type", ""));
            if (!type.isEmpty() && !"service-control".equals(type) && !"servicecontrol".equals(type)) return null;
            title = firstNonBlank(control.optString("title", ""), control.optString("label", ""), title);
            addValues(names, control, "serviceNames", "serviceName", "service_names");
            addValues(refs, control, "serviceRefs", "serviceRef", "service_refs");
        }
        JSONObject serviceManager = root.optJSONObject("serviceManager");
        JSONArray services = serviceManager == null ? null : serviceManager.optJSONArray("services");
        if (services != null) {
            for (int i = 0; i < services.length(); i++) {
                JSONObject service = services.optJSONObject(i);
                if (service == null) continue;
                names.add(service.optString("name", ""));
                refs.add(service.optString("serviceRef", ""));
            }
        }
        names = sanitizeIds(names);
        refs = sanitizeRefs(refs);
        if (names.isEmpty() && refs.isEmpty() && control == null) return null;
        return new ControlFields(title, names, refs);
    }

    private static void addValues(List<String> output, JSONObject object, String... keys) {
        if (object == null) return;
        for (String key : keys) {
            Object value = object.opt(key);
            if (value instanceof JSONArray) {
                JSONArray array = (JSONArray) value;
                for (int i = 0; i < array.length(); i++) output.add(array.optString(i, ""));
            } else if (value != null) output.add(String.valueOf(value));
        }
    }

    private static List<String> sanitizeIds(List<String> values) {
        List<String> result = new ArrayList<>();
        for (String value : values) {
            String id = safeId(value);
            if (!id.isEmpty() && !result.contains(id)) result.add(id);
        }
        return result;
    }

    private static List<String> sanitizeRefs(List<String> values) {
        List<String> result = new ArrayList<>();
        for (String value : values) {
            String ref = value == null ? "" : value.trim();
            if (!ref.startsWith("service-manager://")) continue;
            boolean valid = true;
            for (int i = 0; i < ref.length(); i++) {
                char c = ref.charAt(i);
                if (c <= 0x20 || c == '"' || c == '\'' || c == '\\') { valid = false; break; }
            }
            if (valid && !result.contains(ref)) result.add(ref);
        }
        return result;
    }

    private static void assertNoForbiddenKeys(Object value, String path) throws JSONException {
        if (value instanceof JSONObject) {
            JSONObject object = (JSONObject) value;
            Iterator<String> keys = object.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                String normalized = key.trim().toLowerCase(Locale.US);
                if ("command".equals(normalized) || "shell".equals(normalized)
                    || "script".equals(normalized) || "args".equals(normalized)) {
                    throw new JSONException("forbidden component manifest key at " + path + "." + key);
                }
                assertNoForbiddenKeys(object.opt(key), path + "." + key);
            }
        } else if (value instanceof JSONArray) {
            JSONArray array = (JSONArray) value;
            for (int i = 0; i < array.length(); i++) assertNoForbiddenKeys(array.opt(i), path + "[" + i + "]");
        }
    }

    private static String normalizeHttpUrl(String value) {
        try {
            URI uri = URI.create(value == null ? "" : value.trim());
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.US);
            return ("http".equals(scheme) || "https".equals(scheme)) && uri.getHost() != null ? uri.toString() : "";
        } catch (RuntimeException ignored) { return ""; }
    }

    private static String safeId(String value) {
        String input = value == null ? "" : value.trim();
        StringBuilder output = new StringBuilder();
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                || c == '_' || c == '-' || c == '.') output.append(c);
        }
        return output.length() == input.length() ? output.toString() : "";
    }

    private static String safeClassName(String value) {
        String input = value == null ? "" : value.trim();
        StringBuilder output = new StringBuilder();
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (Character.isLetterOrDigit(c) || c == '_' || c == '.' || c == '$') output.append(c);
        }
        return output.length() == input.length() ? output.toString() : "";
    }

    private static String safeIcon(String value) {
        String id = safeId(value);
        return id.isEmpty() ? "app" : id.toLowerCase(Locale.US).replace('_', '-');
    }

    private static String defaultIcon(String id, String section, EntryFields fields) {
        String normalized = id.toLowerCase(Locale.US);
        if (normalized.contains("pi") || normalized.contains("agent")) return "brain";
        if (normalized.contains("browser")) return "globe";
        if (fields != null && fields.type == OpenHouseComponent.EntryType.TERMINAL) return "terminal";
        if (fields != null && fields.type == OpenHouseComponent.EntryType.FILES) return "folder";
        if (fields != null && fields.type == OpenHouseComponent.EntryType.SERVICE_CONTROL) return "settings";
        return "ai".equals(section) ? "sparkles" : "app";
    }

    private static String deriveIconLabel(String title) {
        String text = title == null ? "" : title.trim();
        return text.length() <= 2 ? text : text.substring(0, 1);
    }

    private static String normalizeType(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.US).replace('_', '-');
    }

    private static String optional(JSONObject object, String... keys) {
        if (object == null) return "";
        for (String key : keys) if (object.has(key)) return object.optString(key, "");
        return "";
    }

    private static Object firstPresent(JSONObject first, JSONObject second, String... keys) {
        if (first != null) for (String key : keys) if (first.has(key)) return first.opt(key);
        if (second != null) for (String key : keys) if (second.has(key)) return second.opt(key);
        return null;
    }

    private static boolean readBoolean(Object value, boolean fallback) {
        if (value instanceof Boolean) return (Boolean) value;
        if (value instanceof Number) return ((Number) value).intValue() != 0;
        if (value instanceof String) {
            String normalized = ((String) value).trim().toLowerCase(Locale.US);
            if ("true".equals(normalized) || "1".equals(normalized) || "yes".equals(normalized)
                || "on".equals(normalized) || "visible".equals(normalized) || "pinned".equals(normalized)) return true;
            if ("false".equals(normalized) || "0".equals(normalized) || "no".equals(normalized)
                || "off".equals(normalized) || "hidden".equals(normalized)) return false;
        }
        return fallback;
    }

    private static JSONObject firstObject(JSONObject... values) {
        for (JSONObject value : values) if (value != null) return value;
        return null;
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) if (value != null && !value.trim().isEmpty()) return value.trim();
        return "";
    }

    private static final class EntryFields {
        final OpenHouseComponent.EntryType type; final String url; final String nativePage; final String activityClassName;
        EntryFields(OpenHouseComponent.EntryType type, String url, String nativePage, String activityClassName) {
            this.type = type; this.url = url; this.nativePage = nativePage; this.activityClassName = activityClassName;
        }
    }

    private static final class ControlFields {
        final String title; final List<String> serviceNames; final List<String> serviceRefs;
        ControlFields(String title, List<String> serviceNames, List<String> serviceRefs) {
            this.title = title; this.serviceNames = serviceNames; this.serviceRefs = serviceRefs;
        }
    }
}
