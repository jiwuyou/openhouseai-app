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
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class OpenHouseComponentRegistry {

    private static final String LOG_TAG = "OpenHouseComponents";
    private static final String CONFIG_DIR = ".config/openhouseai";
    private static final String COMPONENTS_DIR = "components.d";
    private static final String MENU_OVERRIDES_FILE = "menu-overrides.json";
    private static final String REGISTRY_STATE_FILE = "registry-state.json";
    private static final String CONTROL_ENTRY_TYPE_SERVICE_CONTROL = "service-control";
    private static final String DEFAULT_PI_AGENT_URL = "http://127.0.0.1:30141/";
    private static final String DEFAULT_CLOUDCLI_URL = "http://127.0.0.1:23083/";
    private static final String DEFAULT_SMALLPHONE_URL = "http://127.0.0.1:22082/";
    private static final String DEFAULT_HOME_TARGET = "pi-web";

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
        MenuOverrides overrides = readMenuOverrides(new File(configDir, MENU_OVERRIDES_FILE), warnings);
        Map<String, OpenHouseComponent> byId = new LinkedHashMap<>();
        for (OpenHouseComponent component : createBuiltinComponents()) {
            byId.put(component.id, component);
        }

        boolean componentsDirExists = dir.isDirectory();
        File[] files = null;
        if (!componentsDirExists) {
            warnings.add("components.d 不存在：" + dir.getAbsolutePath());
        } else {
            files = dir.listFiles((file, name) -> name != null && name.endsWith(".json"));
            if (files == null || files.length == 0) {
                warnings.add("components.d 中没有 JSON 注册项");
            } else {
                Arrays.sort(files, (left, right) -> left.getName().compareToIgnoreCase(right.getName()));
            }
        }

        int skippedFiles = 0;
        if (files != null) {
            for (File file : files) {
                try {
                    OpenHouseComponent component = parseComponent(readTextFile(file));
                    if (component != null) {
                        OpenHouseComponent existing = byId.get(component.id);
                        if (existing != null && existing.protectedEntry) {
                            byId.put(existing.id, mergeProtectedBuiltin(existing, component));
                        } else {
                            byId.put(component.id, component);
                        }
                    }
                } catch (Exception e) {
                    skippedFiles++;
                    warnings.add(file.getName() + " 无法读取：" + compactError(e));
                    Logger.logStackTraceWithMessage(LOG_TAG,
                        "Ignoring invalid component registry file: " + file.getAbsolutePath(), e);
                }
            }
        }

        List<OpenHouseComponent> components = new ArrayList<>();
        for (OpenHouseComponent component : byId.values()) {
            components.add(applyMenuOverride(component, overrides));
        }
        Collections.sort(components, new Comparator<OpenHouseComponent>() {
            @Override
            public int compare(OpenHouseComponent left, OpenHouseComponent right) {
                int sectionCompare = Integer.compare(sectionRank(left.section), sectionRank(right.section));
                if (sectionCompare != 0) {
                    return sectionCompare;
                }
                int orderCompare = Integer.compare(left.order, right.order);
                if (orderCompare != 0) {
                    return orderCompare;
                }
                return left.title.compareToIgnoreCase(right.title);
            }
        });
        if (!filesAreEmpty(files) && byId.isEmpty()) {
            warnings.add("没有可用的菜单注册项，继续显示内置菜单");
        }
        return new LoadResult(
            components,
            registryState,
            dir,
            componentsDirExists,
            files == null ? 0 : files.length,
            skippedFiles,
            warnings,
            overrides);
    }

    private static OpenHouseComponent parseComponent(String json) throws Exception {
        JSONObject root = new JSONObject(json);
        assertNoForbiddenKeys(root, "$");
        if (!root.optBoolean("enabled", true)) {
            return null;
        }

        JSONObject shellMenu = root.optJSONObject("shellMenu");
        JSONObject smallphoneApp = root.optJSONObject("smallphoneApp");
        JSONObject menuLayer = shellMenu != null ? shellMenu : smallphoneApp;

        JSONObject entry = firstObject(
            menuLayer == null ? null : menuLayer.optJSONObject("entry"),
            smallphoneApp == null ? null : smallphoneApp.optJSONObject("entry"),
            root.optJSONObject("entry"));
        JSONObject controlEntry = firstObject(
            menuLayer == null ? null : menuLayer.optJSONObject("controlEntry"),
            smallphoneApp == null ? null : smallphoneApp.optJSONObject("controlEntry"),
            root.optJSONObject("controlEntry"));
        if (entry == null && controlEntry == null) {
            return null;
        }

        String id = sanitizeId(firstNonBlank(
            root.optString("id", ""),
            menuLayer == null ? "" : menuLayer.optString("id", ""),
            smallphoneApp == null ? "" : smallphoneApp.optString("id", "")));
        if (isBlank(id)) {
            return null;
        }

        EntryFields entryFields = parseEntryFields(entry);
        if (entry != null && entryFields == null) {
            return null;
        }

        ControlFields controlFields = parseControlFields(controlEntry, root);
        if (controlEntry != null && controlFields == null) {
            return null;
        }
        if (entryFields == null && (controlFields == null || !controlFields.hasControlEntry())) {
            return null;
        }

        String title = firstNonBlank(
            menuLayer == null ? "" : menuLayer.optString("title", ""),
            menuLayer == null ? "" : menuLayer.optString("label", ""),
            smallphoneApp == null ? "" : smallphoneApp.optString("title", ""),
            smallphoneApp == null ? "" : smallphoneApp.optString("label", ""),
            root.optString("title", ""),
            root.optString("name", ""),
            id);
        String subtitle = firstNonBlank(
            menuLayer == null ? "" : menuLayer.optString("subtitle", ""),
            menuLayer == null ? "" : menuLayer.optString("description", ""),
            smallphoneApp == null ? "" : smallphoneApp.optString("subtitle", ""),
            smallphoneApp == null ? "" : smallphoneApp.optString("description", ""),
            root.optString("subtitle", ""),
            root.optString("description", ""));
        String section = sanitizeId(firstNonBlank(
            menuLayer == null ? "" : menuLayer.optString("section", ""),
            smallphoneApp == null ? "" : smallphoneApp.optString("section", ""),
            root.optString("kind", ""),
            "apps"));
        int order = menuLayer == null ? root.optInt("order", 1000) : menuLayer.optInt("order", root.optInt("order", 1000));
        boolean visible = readBoolean(firstPresentValue(
            menuLayer, smallphoneApp, root, "visible"), true);
        boolean favorite = readBoolean(firstPresentValue(
            menuLayer, smallphoneApp, root, "favorite", "pinned"), false);
        boolean home = readBoolean(firstPresentValue(
            menuLayer, smallphoneApp, root, "home"), false);

        OpenHouseComponent component = new OpenHouseComponent(
            id,
            title,
            subtitle,
            section,
            order,
            entryFields == null ? null : entryFields.entryType,
            entryFields == null ? null : entryFields.url,
            entryFields == null ? null : entryFields.nativePage,
            controlFields == null ? "" : controlFields.controlTitle,
            visible,
            favorite,
            home,
            false,
            "extension",
            controlFields == null ? Collections.emptyList() : controlFields.serviceNames,
            controlFields == null ? Collections.emptyList() : controlFields.serviceRefs);
        return isRetiredDefaultUiComponent(component) ? null : component;
    }

    private static boolean isRetiredDefaultUiComponent(OpenHouseComponent component) {
        if (component == null) {
            return false;
        }
        if (isRetiredDefaultUiId(component.id) || isRetiredDefaultUiId(component.nativePage)) {
            return true;
        }
        for (String serviceName : component.serviceNames) {
            if (isRetiredDefaultUiId(serviceName)) {
                return true;
            }
        }
        for (String serviceRef : component.serviceRefs) {
            if (isRetiredDefaultUiServiceRef(serviceRef)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isRetiredDefaultUiId(String value) {
        String normalized = normalizeId(value);
        return "hermes".equals(normalized)
            || "hermes-webui".equals(normalized)
            || "opencode".equals(normalized)
            || "reasonix".equals(normalized)
            || "deepseek".equals(normalized);
    }

    private static boolean isRetiredDefaultUiServiceRef(String value) {
        String normalized = normalizeId(value);
        return normalized.endsWith("/hermes")
            || normalized.endsWith("/hermes-webui")
            || normalized.endsWith("/opencode")
            || normalized.endsWith("/reasonix")
            || normalized.endsWith("/deepseek");
    }

    private static List<OpenHouseComponent> createBuiltinComponents() {
        List<OpenHouseComponent> components = new ArrayList<>();
        components.add(createComponent(
            "pi-agent",
            "pi-agent",
            "首次配置助手和插件入口",
            "ai",
            10,
            OpenHouseComponent.EntryType.WEBVIEW,
            DEFAULT_PI_AGENT_URL,
            null,
            "控制",
            true,
            true,
            true,
            true,
            "builtin",
            Arrays.asList("pi-agent", "pi-web"),
            Arrays.asList(
                "service-manager://services/pi-agent",
                "service-manager://services/pi-web")));
        components.add(createComponent(
            "cloudcli",
            "cc/codex",
            "后置 AI 能力：请进入 pi-agent 完成安装配置",
            "ai",
            20,
            OpenHouseComponent.EntryType.WEBVIEW,
            DEFAULT_CLOUDCLI_URL,
            null,
            "控制",
            true,
            true,
            true,
            true,
            "builtin",
            Collections.singletonList("cloudcli"),
            Collections.singletonList("service-manager://services/cloudcli")));
        components.add(createComponent(
            "messages",
            "SmallPhone",
            "小手机页面和运行栈修复",
            "smallphone",
            30,
            OpenHouseComponent.EntryType.WEBVIEW,
            DEFAULT_SMALLPHONE_URL,
            null,
            "控制",
            true,
            true,
            false,
            true,
            "builtin",
            Arrays.asList("smallphone-frontend-beta", "smallphone-core"),
            Arrays.asList(
                "service-manager://services/smallphone-frontend-beta",
                "service-manager://services/smallphone-core")));
        components.add(createComponent(
            "controlled-browser",
            "受控浏览器",
            "多标签，可由 Termux 命令控制",
            "tools",
            50,
            OpenHouseComponent.EntryType.NATIVE_PAGE,
            null,
            "controlled-browser",
            "控制",
            true,
            true,
            false,
            true,
            "builtin",
            Collections.singletonList("controlled-browser"),
            Collections.singletonList("service-manager://services/controlled-browser")));
        return components;
    }

    private static OpenHouseComponent createComponent(String id,
                                                      String title,
                                                      String subtitle,
                                                      String section,
                                                      int order,
                                                      OpenHouseComponent.EntryType entryType,
                                                      String url,
                                                      String nativePage,
                                                      String controlTitle,
                                                      boolean visible,
                                                      boolean favorite,
                                                      boolean home,
                                                      boolean protectedEntry,
                                                      String source,
                                                      List<String> serviceNames,
                                                      List<String> serviceRefs) {
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
            visible,
            favorite,
            home,
            protectedEntry,
            source,
            sanitizeList(serviceNames),
            sanitizeServiceRefs(serviceRefs));
    }

    private static OpenHouseComponent mergeProtectedBuiltin(OpenHouseComponent builtin, OpenHouseComponent extension) {
        OpenHouseComponent.EntryType entryType = extension.entryType == null ? builtin.entryType : extension.entryType;
        String title = "cloudcli".equals(normalizeId(builtin.id))
            ? builtin.title
            : firstNonBlank(extension.title, builtin.title);
        return createComponent(
            builtin.id,
            title,
            firstNonBlank(extension.subtitle, builtin.subtitle),
            firstNonBlank(extension.section, builtin.section),
            extension.order == 1000 ? builtin.order : extension.order,
            entryType,
            firstNonBlank(extension.url, builtin.url),
            firstNonBlank(extension.nativePage, builtin.nativePage),
            firstNonBlank(extension.controlTitle, builtin.controlTitle),
            builtin.visible || extension.visible,
            builtin.favorite || extension.favorite,
            builtin.home || extension.home,
            true,
            "builtin+extension",
            mergeLists(builtin.serviceNames, extension.serviceNames),
            mergeLists(builtin.serviceRefs, extension.serviceRefs));
    }

    private static OpenHouseComponent applyMenuOverride(OpenHouseComponent component, MenuOverrides overrides) {
        JSONObject item = overrides.findItem(component);
        JSONObject shellMenu = item == null ? null : item.optJSONObject("shellMenu");
        JSONObject smallphoneApp = item == null ? null : item.optJSONObject("smallphoneApp");
        boolean visible = component.visible;
        boolean favorite = component.favorite || overrides.matches(overrides.favorites, component);
        boolean home = isBlank(overrides.homeTarget) ? component.home : overrides.matchesHome(component);

        if (item != null) {
            String title = firstNonBlank(
                readOptionalString(item, "title", "name"),
                readOptionalString(shellMenu, "title", "label"),
                readOptionalString(smallphoneApp, "title", "label"),
                component.title);
            String subtitle = firstNonBlank(
                readOptionalString(item, "description", "subtitle"),
                readOptionalString(shellMenu, "description", "subtitle"),
                readOptionalString(smallphoneApp, "description", "subtitle"),
                component.subtitle);
            String section = sanitizeId(firstNonBlank(
                readOptionalString(item, "section"),
                readOptionalString(shellMenu, "section"),
                readOptionalString(smallphoneApp, "section"),
                component.section));
            int order = firstInt(component.order, item, shellMenu, smallphoneApp);

            if (hasAnyKey(item, "visible")) {
                visible = readBoolean(item.opt("visible"), visible);
            }
            if (hasAnyKey(shellMenu, "visible")) {
                visible = readBoolean(shellMenu.opt("visible"), visible);
            }
            if (hasAnyKey(smallphoneApp, "visible")) {
                visible = readBoolean(smallphoneApp.opt("visible"), visible);
            }
            if (hasAnyKey(item, "hidden") && readBoolean(item.opt("hidden"), false)) {
                visible = false;
            }
            if (hasAnyKey(shellMenu, "hidden") && readBoolean(shellMenu.opt("hidden"), false)) {
                visible = false;
            }
            if (hasAnyKey(smallphoneApp, "hidden") && readBoolean(smallphoneApp.opt("hidden"), false)) {
                visible = false;
            }
            if (hasAnyKey(item, "favorite", "pinned")) {
                favorite = readBoolean(firstPresentValue(item, "favorite", "pinned"), favorite);
            }
            if (hasAnyKey(shellMenu, "favorite", "pinned")) {
                favorite = readBoolean(firstPresentValue(shellMenu, "favorite", "pinned"), favorite);
            }
            if (hasAnyKey(smallphoneApp, "favorite", "pinned")) {
                favorite = readBoolean(firstPresentValue(smallphoneApp, "favorite", "pinned"), favorite);
            }
            if (hasAnyKey(item, "home")) {
                home = readBoolean(item.opt("home"), home);
            }
            if (hasAnyKey(shellMenu, "home")) {
                home = readBoolean(shellMenu.opt("home"), home);
            }
            if (hasAnyKey(smallphoneApp, "home")) {
                home = readBoolean(smallphoneApp.opt("home"), home);
            }

            EntryFields entryFields = firstEntryFields(
                parseEntryFields(item.optJSONObject("entry")),
                parseTopLevelWebEntryFields(item),
                parseEntryFields(firstObject(shellMenu == null ? null : shellMenu.optJSONObject("entry"))),
                parseEntryFields(firstObject(smallphoneApp == null ? null : smallphoneApp.optJSONObject("entry"))));
            ControlFields controlFields = firstControlFields(
                parseControlFields(shellMenu == null ? null : shellMenu.optJSONObject("controlEntry"), null),
                parseControlFields(smallphoneApp == null ? null : smallphoneApp.optJSONObject("controlEntry"), null),
                parseControlFields(item.optJSONObject("controlEntry"), null));

            if (overrides.matches(overrides.hidden, component)) {
                visible = false;
            }
            return createComponent(
                component.id,
                title,
                subtitle,
                section,
                order,
                entryFields == null ? component.entryType : entryFields.entryType,
                entryFields == null ? component.url : entryFields.url,
                entryFields == null ? component.nativePage : entryFields.nativePage,
                controlFields == null ? component.controlTitle : controlFields.controlTitle,
                visible,
                favorite,
                home,
                component.protectedEntry,
                component.source,
                controlFields == null ? component.serviceNames : controlFields.serviceNames,
                controlFields == null ? component.serviceRefs : controlFields.serviceRefs);
        }

        if (overrides.matches(overrides.hidden, component)) {
            visible = false;
        }
        return createComponent(
            component.id,
            component.title,
            component.subtitle,
            component.section,
            component.order,
            component.entryType,
            component.url,
            component.nativePage,
            component.controlTitle,
            visible,
            favorite,
            home,
            component.protectedEntry,
            component.source,
            component.serviceNames,
            component.serviceRefs);
    }

    private static int firstInt(int fallback, JSONObject... objects) {
        if (objects == null) {
            return fallback;
        }
        for (JSONObject object : objects) {
            if (object != null && object.has("order")) {
                return object.optInt("order", fallback);
            }
        }
        return fallback;
    }

    private static EntryFields firstEntryFields(EntryFields... entries) {
        if (entries == null) {
            return null;
        }
        for (EntryFields entry : entries) {
            if (entry != null) {
                return entry;
            }
        }
        return null;
    }

    private static ControlFields firstControlFields(ControlFields... entries) {
        if (entries == null) {
            return null;
        }
        for (ControlFields entry : entries) {
            if (entry != null) {
                return entry;
            }
        }
        return null;
    }

    private static EntryFields parseEntryFields(JSONObject entry) {
        if (entry == null) {
            return null;
        }
        OpenHouseComponent.EntryType entryType = parseEntryType(entry.optString("type", ""));
        if (entryType == null && !isBlank(firstNonBlank(
            entry.optString("url", ""),
            entry.optString("href", ""),
            entry.optString("uri", "")))) {
            entryType = OpenHouseComponent.EntryType.WEBVIEW;
        }
        if (entryType == null) {
            return null;
        }
        String url = null;
        String nativePage = null;
        if (entryType == OpenHouseComponent.EntryType.WEBVIEW) {
            url = normalizeWebUrl(firstNonBlank(
                entry.optString("url", ""),
                entry.optString("href", ""),
                entry.optString("uri", "")));
            if (isBlank(url)) {
                return null;
            }
        } else if (entryType == OpenHouseComponent.EntryType.NATIVE_PAGE) {
            nativePage = sanitizeId(firstNonBlank(
                entry.optString("page", ""),
                entry.optString("pageId", ""),
                entry.optString("nativePage", ""),
                entry.optString("view", ""),
                entry.optString("nativeView", ""),
                entry.optString("native_view", "")));
            if (isBlank(nativePage)) {
                return null;
            }
        }
        return new EntryFields(entryType, url, nativePage);
    }

    private static EntryFields parseTopLevelWebEntryFields(JSONObject item) {
        if (item == null) {
            return null;
        }
        String url = normalizeWebUrl(firstNonBlank(
            item.optString("url", ""),
            item.optString("href", ""),
            item.optString("uri", "")));
        if (isBlank(url)) {
            return null;
        }
        return new EntryFields(OpenHouseComponent.EntryType.WEBVIEW, url, null);
    }

    private static ControlFields parseControlFields(JSONObject controlEntry, JSONObject root) {
        List<String> serviceNames = new ArrayList<>();
        List<String> serviceRefs = new ArrayList<>();
        String controlTitle = "控制";
        if (controlEntry != null) {
            String controlType = normalizeType(controlEntry.optString("type", ""));
            if (!isBlank(controlType) && !CONTROL_ENTRY_TYPE_SERVICE_CONTROL.equals(controlType)) {
                return null;
            }
            controlTitle = firstNonBlank(
                controlEntry.optString("title", ""),
                controlEntry.optString("label", ""),
                controlTitle);
            serviceNames.addAll(readStringList(controlEntry, "serviceNames"));
            serviceNames.addAll(readStringList(controlEntry, "serviceName"));
            serviceNames.addAll(readStringList(controlEntry, "service_names"));
            serviceRefs.addAll(readStringList(controlEntry, "serviceRefs"));
            serviceRefs.addAll(readStringList(controlEntry, "serviceRef"));
            serviceRefs.addAll(readStringList(controlEntry, "service_refs"));
        }
        if (root != null) {
            JSONObject serviceManager = root.optJSONObject("serviceManager");
            JSONArray services = serviceManager == null ? null : serviceManager.optJSONArray("services");
            if (services != null) {
                for (int i = 0; i < services.length(); i++) {
                    JSONObject service = services.optJSONObject(i);
                    if (service == null) {
                        continue;
                    }
                    String serviceName = sanitizeServiceName(service.optString("name", ""));
                    if (!isBlank(serviceName)) {
                        serviceNames.add(serviceName);
                    }
                    String serviceRef = sanitizeServiceRef(service.optString("serviceRef", ""));
                    if (!isBlank(serviceRef)) {
                        serviceRefs.add(serviceRef);
                    }
                }
            }
        }
        serviceNames = sanitizeList(serviceNames);
        serviceRefs = sanitizeServiceRefs(serviceRefs);
        if (serviceNames.isEmpty() && serviceRefs.isEmpty()) {
            return null;
        }
        return new ControlFields(controlTitle, serviceNames, serviceRefs);
    }

    private static OpenHouseComponent.EntryType parseEntryType(String value) {
        String normalized = normalizeType(value);
        if ("webview".equals(normalized) || "web-view".equals(normalized)) {
            return OpenHouseComponent.EntryType.WEBVIEW;
        }
        if ("native-page".equals(normalized)
            || "native-view".equals(normalized)
            || "native".equals(normalized)) {
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

    private static String normalizeId(String value) {
        return isBlank(value) ? "" : value.trim().toLowerCase(Locale.US).replace('_', '-');
    }

    private static int sectionRank(String section) {
        String normalized = normalizeId(section);
        if ("common".equals(normalized)) {
            return 0;
        }
        if ("ai".equals(normalized)) {
            return 10;
        }
        if ("smallphone".equals(normalized)) {
            return 20;
        }
        if ("desktop".equals(normalized)) {
            return 30;
        }
        if ("tools".equals(normalized)) {
            return 40;
        }
        if ("apps".equals(normalized)) {
            return 50;
        }
        return 100;
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

    private static List<String> mergeLists(List<String> left, List<String> right) {
        List<String> merged = new ArrayList<>();
        if (left != null) {
            merged.addAll(left);
        }
        if (right != null) {
            merged.addAll(right);
        }
        return merged;
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

    private static List<String> readIdList(JSONObject object, String... keys) {
        if (object == null || keys == null) {
            return Collections.emptyList();
        }
        List<String> ids = new ArrayList<>();
        for (String key : keys) {
            for (String value : readStringList(object, key)) {
                String id = sanitizeId(value);
                if (!isBlank(id) && !ids.contains(id)) {
                    ids.add(id);
                }
            }
        }
        return ids;
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

    private static JSONObject firstObject(JSONObject... values) {
        if (values == null) {
            return null;
        }
        for (JSONObject value : values) {
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private static boolean hasAnyKey(JSONObject object, String... keys) {
        if (object == null || keys == null) {
            return false;
        }
        for (String key : keys) {
            if (!isBlank(key) && object.has(key)) {
                return true;
            }
        }
        return false;
    }

    private static Object firstPresentValue(JSONObject object, String... keys) {
        if (object == null || keys == null) {
            return null;
        }
        for (String key : keys) {
            if (!isBlank(key) && object.has(key)) {
                return object.opt(key);
            }
        }
        return null;
    }

    private static Object firstPresentValue(JSONObject first, JSONObject second, JSONObject third, String... keys) {
        Object value = firstPresentValue(first, keys);
        if (value != null) {
            return value;
        }
        value = firstPresentValue(second, keys);
        if (value != null) {
            return value;
        }
        return firstPresentValue(third, keys);
    }

    private static boolean readBoolean(Object value, boolean fallback) {
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        if (value instanceof Number) {
            return ((Number) value).intValue() != 0;
        }
        if (value instanceof String) {
            String normalized = ((String) value).trim().toLowerCase(Locale.US);
            if ("true".equals(normalized)
                || "1".equals(normalized)
                || "yes".equals(normalized)
                || "on".equals(normalized)
                || "visible".equals(normalized)
                || "favorite".equals(normalized)
                || "home".equals(normalized)
                || "pinned".equals(normalized)) {
                return true;
            }
            if ("false".equals(normalized)
                || "0".equals(normalized)
                || "no".equals(normalized)
                || "off".equals(normalized)
                || "hidden".equals(normalized)) {
                return false;
            }
        }
        return fallback;
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

    private static MenuOverrides readMenuOverrides(File file, List<String> warnings) {
        if (file == null || !file.isFile()) {
            return MenuOverrides.empty(file);
        }
        try {
            JSONObject root = new JSONObject(readTextFile(file));
            String homeTarget = sanitizeId(firstNonBlank(
                root.optString("homeTarget", ""),
                root.optString("home_target", ""),
                root.optString("defaultHome", ""),
                root.optString("default_home", ""),
                root.optString("home", "")));
            Set<String> favorites = new HashSet<>(readIdList(root, "favorites", "favoriteIds", "favorite_ids"));
            Set<String> hidden = new HashSet<>(readIdList(root, "hidden", "hiddenIds", "hidden_ids"));
            Map<String, JSONObject> items = new LinkedHashMap<>();
            JSONObject objectItems = root.optJSONObject("items");
            if (objectItems == null) {
                objectItems = root.optJSONObject("components");
            }
            if (objectItems != null) {
                Iterator<String> keys = objectItems.keys();
                while (keys.hasNext()) {
                    String key = keys.next();
                    String id = sanitizeId(key);
                    JSONObject item = objectItems.optJSONObject(key);
                    if (isBlank(id) || item == null) {
                        continue;
                    }
                    assertNoForbiddenKeys(item, "$.items." + id);
                    items.put(id, item);
                }
            }
            JSONArray entries = root.optJSONArray("entries");
            if (entries != null) {
                for (int i = 0; i < entries.length(); i++) {
                    JSONObject item = entries.optJSONObject(i);
                    if (item == null) {
                        continue;
                    }
                    String id = sanitizeId(firstNonBlank(
                        item.optString("id", ""),
                        item.optString("componentId", ""),
                        item.optString("component_id", "")));
                    if (isBlank(id)) {
                        continue;
                    }
                    assertNoForbiddenKeys(item, "$.entries[" + i + "]");
                    items.put(id, item);
                }
            }
            return new MenuOverrides(file, homeTarget, favorites, hidden, items, true, true, "");
        } catch (Exception e) {
            if (warnings != null) {
                warnings.add("menu-overrides.json 无法读取：" + compactError(e));
            }
            Logger.logStackTraceWithMessage(LOG_TAG,
                "Ignoring invalid menu overrides file: " + file.getAbsolutePath(), e);
            return new MenuOverrides(file, "", Collections.emptySet(), Collections.emptySet(),
                Collections.emptyMap(), true, false, compactError(e));
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

    private static final class EntryFields {
        final OpenHouseComponent.EntryType entryType;
        final String url;
        final String nativePage;

        EntryFields(OpenHouseComponent.EntryType entryType, String url, String nativePage) {
            this.entryType = entryType;
            this.url = url;
            this.nativePage = nativePage;
        }
    }

    private static final class ControlFields {
        final String controlTitle;
        final List<String> serviceNames;
        final List<String> serviceRefs;

        ControlFields(String controlTitle, List<String> serviceNames, List<String> serviceRefs) {
            this.controlTitle = isBlank(controlTitle) ? "控制" : controlTitle;
            this.serviceNames = serviceNames == null
                ? Collections.emptyList()
                : Collections.unmodifiableList(new ArrayList<>(serviceNames));
            this.serviceRefs = serviceRefs == null
                ? Collections.emptyList()
                : Collections.unmodifiableList(new ArrayList<>(serviceRefs));
        }

        boolean hasControlEntry() {
            return !serviceNames.isEmpty() || !serviceRefs.isEmpty();
        }
    }

    private static final class MenuOverrides {
        final File file;
        final String homeTarget;
        final Set<String> favorites;
        final Set<String> hidden;
        final Map<String, JSONObject> items;
        final boolean exists;
        final boolean valid;
        final String readError;

        MenuOverrides(File file,
                      String homeTarget,
                      Set<String> favorites,
                      Set<String> hidden,
                      Map<String, JSONObject> items,
                      boolean exists,
                      boolean valid,
                      String readError) {
            this.file = file;
            this.homeTarget = homeTarget == null ? "" : homeTarget;
            this.favorites = favorites == null
                ? Collections.emptySet()
                : Collections.unmodifiableSet(new HashSet<>(favorites));
            this.hidden = hidden == null
                ? Collections.emptySet()
                : Collections.unmodifiableSet(new HashSet<>(hidden));
            this.items = items == null
                ? Collections.emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<>(items));
            this.exists = exists;
            this.valid = valid;
            this.readError = readError == null ? "" : readError;
        }

        static MenuOverrides empty(File file) {
            return new MenuOverrides(file, "", Collections.emptySet(), Collections.emptySet(),
                Collections.emptyMap(), false, true, "");
        }

        boolean matchesHome(OpenHouseComponent component) {
            return matches(homeTarget, component);
        }

        boolean matches(Set<String> ids, OpenHouseComponent component) {
            if (ids == null || ids.isEmpty()) {
                return false;
            }
            for (String id : ids) {
                if (matches(id, component)) {
                    return true;
                }
            }
            return false;
        }

        JSONObject findItem(OpenHouseComponent component) {
            if (component == null || items.isEmpty()) {
                return null;
            }
            JSONObject exact = items.get(component.id);
            if (exact != null) {
                return exact;
            }
            for (Map.Entry<String, JSONObject> entry : items.entrySet()) {
                if (matches(entry.getKey(), component)) {
                    return entry.getValue();
                }
            }
            return null;
        }

        private boolean matches(String id, OpenHouseComponent component) {
            String normalized = normalizeId(id);
            if (isBlank(normalized) || component == null) {
                return false;
            }
            if (normalized.equals(normalizeId(component.id))
                || normalized.equals(normalizeId(component.nativePage))) {
                return true;
            }
            for (String name : component.serviceNames) {
                if (normalized.equals(normalizeId(name))) {
                    return true;
                }
            }
            for (String ref : component.serviceRefs) {
                if (normalizeId(ref).endsWith("/" + normalized)) {
                    return true;
                }
            }
            return false;
        }
    }

    public static final class LoadResult {
        public final List<OpenHouseComponent> components;
        public final RegistryState registryState;
        public final String homeTarget;
        public final File menuOverridesFile;
        public final boolean menuOverridesExists;
        public final boolean menuOverridesValid;
        public final String menuOverridesReadError;
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
                           List<String> warnings,
                           MenuOverrides menuOverrides) {
            this.components = components == null
                ? Collections.emptyList()
                : Collections.unmodifiableList(new ArrayList<>(components));
            this.registryState = registryState == null ? RegistryState.missing(null) : registryState;
            MenuOverrides overrides = menuOverrides == null ? MenuOverrides.empty(null) : menuOverrides;
            this.homeTarget = isBlank(overrides.homeTarget) ? DEFAULT_HOME_TARGET : overrides.homeTarget;
            this.menuOverridesFile = overrides.file;
            this.menuOverridesExists = overrides.exists;
            this.menuOverridesValid = overrides.valid;
            this.menuOverridesReadError = overrides.readError;
            this.componentsDir = componentsDir;
            this.componentsDirExists = componentsDirExists;
            this.totalFiles = Math.max(0, totalFiles);
            this.skippedFiles = Math.max(0, skippedFiles);
            this.warnings = warnings == null
                ? Collections.emptyList()
                : Collections.unmodifiableList(new ArrayList<>(warnings));
        }

        public boolean hasWarnings() {
            return !warnings.isEmpty() || registryState.hasProblem() || (menuOverridesExists && !menuOverridesValid);
        }

        public boolean shouldShowFallbackNavigation() {
            return components.isEmpty();
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
            if (menuOverridesExists) {
                builder.append("；override=").append(menuOverridesValid ? "ok" : "invalid");
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
            if (menuOverridesExists && !menuOverridesValid) {
                builder.append('\n').append("menu-overrides.json：无法解析");
                if (!isBlank(menuOverridesReadError)) {
                    builder.append("（").append(menuOverridesReadError).append("）");
                }
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
