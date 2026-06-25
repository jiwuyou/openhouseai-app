package com.termux.app.openhouse.servicecontrol;

import com.termux.shared.termux.TermuxConstants;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

public final class ServiceManagerClient {

    public static final String DEFAULT_BASE_URL = "http://127.0.0.1:20087";

    private static final int CONNECT_TIMEOUT_MS = 2500;
    private static final int READ_TIMEOUT_MS = 7000;
    private static final int MAX_BODY_CHARS = 256 * 1024;
    private static final int DEFAULT_LOG_LIMIT = 80;
    private static final int MAX_LOG_LIMIT = 500;
    private static final String[] ENDPOINT_CONFIG_KEYS = new String[] {
        "listen_addr",
        "listenAddr",
        "url",
        "base_url",
        "baseUrl",
        "baseURL"
    };
    private static final String[] NESTED_ENDPOINT_CONFIG_KEYS = new String[] {
        "server",
        "http",
        "api",
        "service_manager",
        "serviceManager"
    };

    private final String baseUrl;

    public ServiceManagerClient() {
        this(DEFAULT_BASE_URL);
    }

    public ServiceManagerClient(String baseUrl) {
        this.baseUrl = normalizeBaseUrl(baseUrl);
    }

    public ServiceManagerResult listServices() {
        try {
            HttpResponse response = request("GET", "/api/v1/services");
            if (!response.isSuccess()) {
                return ServiceManagerResult.fromHttpFailure(
                    response.code,
                    response.body,
                    "service-manager 服务列表读取失败"
                );
            }
            List<ServiceManagerService> services = parseServices(response.body);
            return ServiceManagerResult.builder(true)
                .code(response.code)
                .body(response.body)
                .message("已读取 " + services.size() + " 个服务。")
                .services(services)
                .build();
        } catch (Exception e) {
            return errorResult(e, "service-manager 服务列表读取失败");
        }
    }

    public ServiceManagerResult getStatus(String serviceId) {
        String cleanServiceId = sanitizeServiceId(serviceId);
        if (cleanServiceId.isEmpty()) {
            return ServiceManagerResult.invalid("服务 ID 无效。");
        }
        try {
            HttpResponse response = request("GET", "/api/v1/services/" + cleanServiceId + "/status");
            if (!response.isSuccess()) {
                return ServiceManagerResult.fromHttpFailure(
                    response.code,
                    response.body,
                    cleanServiceId + " 状态读取失败"
                );
            }
            JSONObject json = parseObject(response.body);
            return ServiceManagerResult.builder(true)
                .code(response.code)
                .body(response.body)
                .message(firstNonBlank(json.optString("message", ""), cleanServiceId + " 状态读取成功。"))
                .serviceId(cleanServiceId)
                .state(json.optString("state", "unknown"))
                .provider(json.optString("provider", ""))
                .pid(readPid(json))
                .build();
        } catch (Exception e) {
            return errorResult(e, cleanServiceId + " 状态读取失败");
        }
    }

    public ServiceManagerResult runAction(String serviceId, String action) {
        String cleanServiceId = sanitizeServiceId(serviceId);
        String cleanAction = sanitizeAction(action);
        if (cleanServiceId.isEmpty()) {
            return ServiceManagerResult.invalid("服务 ID 无效。");
        }
        if (cleanAction.isEmpty()) {
            return ServiceManagerResult.invalid("服务动作无效。");
        }
        try {
            HttpResponse response = request(
                "POST",
                "/api/v1/services/" + cleanServiceId + "/" + cleanAction
            );
            if (!response.isSuccess()) {
                return ServiceManagerResult.fromHttpFailure(
                    response.code,
                    response.body,
                    cleanServiceId + " " + cleanActionLabel(cleanAction) + "失败"
                ).withTarget(cleanServiceId, cleanAction);
            }
            StatusFields fields = parseOptionalStatusFields(response.body);
            return ServiceManagerResult.builder(true)
                .code(response.code)
                .body(response.body)
                .message(cleanServiceId + " 已提交" + cleanActionLabel(cleanAction) + "请求。")
                .serviceId(cleanServiceId)
                .action(cleanAction)
                .state(fields.state)
                .provider(fields.provider)
                .pid(fields.pid)
                .build();
        } catch (Exception e) {
            return errorResult(e, cleanServiceId + " " + cleanActionLabel(cleanAction) + "失败")
                .withTarget(cleanServiceId, cleanAction);
        }
    }

    public ServiceManagerResult getLogs(String serviceId, int limit) {
        String cleanServiceId = sanitizeServiceId(serviceId);
        if (cleanServiceId.isEmpty()) {
            return ServiceManagerResult.invalid("服务 ID 无效。");
        }
        int cleanLimit = sanitizeLogLimit(limit);
        try {
            HttpResponse response = request(
                "GET",
                "/api/v1/services/" + cleanServiceId + "/logs?limit=" + cleanLimit
            );
            if (!response.isSuccess()) {
                return ServiceManagerResult.fromHttpFailure(
                    response.code,
                    response.body,
                    cleanServiceId + " 日志读取失败"
                ).withTarget(cleanServiceId, "");
            }
            List<ServiceManagerLogLine> logLines = parseLogLines(response.body);
            return ServiceManagerResult.builder(true)
                .code(response.code)
                .body(response.body)
                .message(logLines.isEmpty() ? "暂无日志。" : "已读取 " + logLines.size() + " 行日志。")
                .serviceId(cleanServiceId)
                .logLines(logLines)
                .build();
        } catch (Exception e) {
            return errorResult(e, cleanServiceId + " 日志读取失败").withTarget(cleanServiceId, "");
        }
    }

    public ServiceManagerResult getLogs(String serviceId) {
        return getLogs(serviceId, DEFAULT_LOG_LIMIT);
    }

    public static ServiceManagerTarget parseServiceManagerRef(String ref) {
        String trimmed = sanitizeServiceManagerRef(ref);
        if (trimmed.isEmpty()) {
            return ServiceManagerTarget.invalid("service-manager 引用无效。");
        }
        String servicePrefix = "service-manager://services/";
        if (trimmed.startsWith(servicePrefix)) {
            String serviceId = sanitizeServiceId(trimmed.substring(servicePrefix.length()));
            return serviceId.isEmpty()
                ? ServiceManagerTarget.invalid("service-manager 服务 ID 无效。")
                : ServiceManagerTarget.valid(serviceId, "");
        }
        String actionPrefix = "service-manager://actions/";
        if (trimmed.startsWith(actionPrefix)) {
            String target = trimmed.substring(actionPrefix.length());
            int dotIndex = target.lastIndexOf('.');
            String serviceId = sanitizeServiceId(dotIndex > 0 ? target.substring(0, dotIndex) : target);
            String action = dotIndex > 0 ? sanitizeAction(target.substring(dotIndex + 1)) : "";
            return serviceId.isEmpty()
                ? ServiceManagerTarget.invalid("service-manager 服务 ID 无效。")
                : ServiceManagerTarget.valid(serviceId, action);
        }
        return ServiceManagerTarget.invalid("不支持的 service-manager 引用。");
    }

    public static String sanitizeServiceId(String value) {
        String trimmed = safeTrim(value);
        if (trimmed.isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
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
        String cleaned = builder.toString();
        return cleaned.equals(trimmed) ? cleaned : "";
    }

    public static String sanitizeAction(String action) {
        String value = safeTrim(action).toLowerCase(Locale.US);
        if ("start".equals(value) || "stop".equals(value) || "restart".equals(value) || "repair".equals(value)) {
            return value;
        }
        return "";
    }

    public static String sanitizeServiceManagerRef(String ref) {
        String trimmed = safeTrim(ref);
        if (!trimmed.startsWith("service-manager://")) {
            return "";
        }
        for (int i = 0; i < trimmed.length(); i++) {
            char current = trimmed.charAt(i);
            if (current <= 0x20 || current == '"' || current == '\'' || current == '\\') {
                return "";
            }
        }
        return trimmed;
    }

    public static List<String> serviceIdsFromRefs(List<String> refs) {
        if (refs == null || refs.isEmpty()) {
            return Collections.emptyList();
        }
        List<String> ids = new ArrayList<>();
        for (String ref : refs) {
            ServiceManagerTarget target = parseServiceManagerRef(ref);
            if (target.valid && !target.serviceId.isEmpty() && !ids.contains(target.serviceId)) {
                ids.add(target.serviceId);
            }
        }
        return ids;
    }

    private HttpResponse request(String method, String path) throws IOException, JSONException {
        String token = resolveTokenForBaseUrl(baseUrl);
        if (token.isEmpty()) {
            throw new MissingTokenException("找不到 service-manager token。请先完成运行栈安装或启动 service-manager。");
        }
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(baseUrl + path).openConnection();
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setRequestMethod(method);
            connection.setRequestProperty("Authorization", "Bearer " + token);
            connection.setRequestProperty("Accept", "application/json");
            if ("POST".equals(method)) {
                connection.setRequestProperty("Content-Length", "0");
            }
            int code = connection.getResponseCode();
            String body = readConnectionBody(connection, code >= 400);
            return new HttpResponse(code, body);
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    public static String resolveTokenForBaseUrl(String baseUrl) {
        String normalizedBaseUrl = normalizeBaseUrl(baseUrl);
        ServiceManagerConfigCandidate[] candidates = new ServiceManagerConfigCandidate[] {
            new ServiceManagerConfigCandidate(
                new File(TermuxConstants.TERMUX_HOME_DIR_PATH, ".config/openhouseai/service-manager/config.json"),
                true
            ),
            new ServiceManagerConfigCandidate(
                new File(TermuxConstants.TERMUX_PREFIX_DIR_PATH,
                    "var/lib/proot-distro/installed-rootfs/ubuntu/root/.config/service-manager/config.json"),
                false
            ),
            new ServiceManagerConfigCandidate(
                new File(TermuxConstants.TERMUX_HOME_DIR_PATH, ".config/service-manager/config.json"),
                false
            )
        };
        for (ServiceManagerConfigCandidate candidate : candidates) {
            if (!candidate.file.isFile()) {
                continue;
            }
            try {
                JSONObject json = new JSONObject(readTextFile(candidate.file));
                String token = tokenFromConfig(json);
                if (!token.isEmpty()
                    && configMatchesBaseUrl(json, normalizedBaseUrl, candidate.allowImplicitDefaultBaseUrl)) {
                    return token;
                }
            } catch (IOException | JSONException ignored) {
                // Candidate config files can be half-written or stale. Try the next path.
            }
        }
        return "";
    }

    private static boolean configMatchesBaseUrl(
        JSONObject json,
        String normalizedBaseUrl,
        boolean allowImplicitDefaultBaseUrl
    ) {
        List<String> endpoints = endpointValuesFromConfig(json);
        if (endpoints.isEmpty()) {
            return allowImplicitDefaultBaseUrl && DEFAULT_BASE_URL.equals(normalizedBaseUrl);
        }
        for (String endpoint : endpoints) {
            if (endpointMatchesBaseUrl(endpoint, normalizedBaseUrl)) {
                return true;
            }
        }
        return false;
    }

    private static List<String> endpointValuesFromConfig(JSONObject json) {
        if (json == null) {
            return Collections.emptyList();
        }
        List<String> endpoints = new ArrayList<>();
        collectEndpointValues(json, endpoints);
        for (String nestedKey : NESTED_ENDPOINT_CONFIG_KEYS) {
            JSONObject nested = json.optJSONObject(nestedKey);
            if (nested != null) {
                collectEndpointValues(nested, endpoints);
            }
        }
        return endpoints;
    }

    private static void collectEndpointValues(JSONObject json, List<String> out) {
        for (String key : ENDPOINT_CONFIG_KEYS) {
            String value = safeTrim(json.optString(key, ""));
            if (!value.isEmpty()) {
                out.add(value);
            }
        }
    }

    private static boolean endpointMatchesBaseUrl(String endpoint, String normalizedBaseUrl) {
        int expectedPort = extractPort(normalizedBaseUrl);
        int actualPort = extractPort(endpoint);
        return expectedPort > 0 && actualPort == expectedPort;
    }

    private static int extractPort(String value) {
        String trimmed = safeTrim(value);
        if (trimmed.isEmpty()) {
            return -1;
        }
        try {
            URL url = new URL(trimmed.contains("://") ? trimmed : "http://" + addressForUrl(trimmed));
            int port = url.getPort();
            if (port > 0) {
                return port;
            }
            int defaultPort = url.getDefaultPort();
            return defaultPort > 0 ? defaultPort : -1;
        } catch (IOException ignored) {
            return extractTrailingPort(trimmed);
        }
    }

    private static String addressForUrl(String value) {
        String trimmed = safeTrim(value);
        return trimmed.startsWith(":") ? "127.0.0.1" + trimmed : trimmed;
    }

    private static int extractTrailingPort(String value) {
        String trimmed = safeTrim(value);
        int end = trimmed.length();
        for (int i = 0; i < trimmed.length(); i++) {
            char current = trimmed.charAt(i);
            if (current == '/' || current == '?' || current == '#') {
                end = i;
                break;
            }
        }
        String address = trimmed.substring(0, end);
        int colonIndex = address.lastIndexOf(':');
        String portText = colonIndex >= 0 ? address.substring(colonIndex + 1) : address;
        try {
            int port = Integer.parseInt(portText);
            return port > 0 && port <= 65535 ? port : -1;
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }

    private static String tokenFromConfig(JSONObject json) {
        return firstNonBlank(
            json.optString("auth_token", ""),
            firstNonBlank(json.optString("authToken", ""), json.optString("token", ""))
        );
    }

    private static String readConnectionBody(HttpURLConnection connection, boolean errorBody) throws IOException {
        InputStream inputStream = errorBody ? connection.getErrorStream() : connection.getInputStream();
        if (inputStream == null) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (builder.length() + line.length() + 1 > MAX_BODY_CHARS) {
                    int remaining = Math.max(0, MAX_BODY_CHARS - builder.length());
                    if (remaining > 0) {
                        builder.append(line, 0, Math.min(line.length(), remaining));
                    }
                    builder.append("\n...输出过长，已截断。");
                    break;
                }
                builder.append(line).append('\n');
            }
        }
        return builder.toString().trim();
    }

    private static List<ServiceManagerService> parseServices(String body) throws JSONException {
        Object parsed = new JSONTokener(safeTrim(body)).nextValue();
        JSONArray array;
        if (parsed instanceof JSONArray) {
            array = (JSONArray) parsed;
        } else if (parsed instanceof JSONObject) {
            JSONObject object = (JSONObject) parsed;
            array = object.optJSONArray("services");
            if (array == null) {
                array = object.optJSONArray("items");
            }
            if (array == null && object.has("service")) {
                array = new JSONArray();
                array.put(object.optJSONObject("service"));
            }
            if (array == null) {
                array = new JSONArray();
            }
        } else {
            array = new JSONArray();
        }
        List<ServiceManagerService> services = new ArrayList<>();
        for (int i = 0; i < array.length(); i++) {
            JSONObject item = array.optJSONObject(i);
            if (item == null) {
                continue;
            }
            ServiceManagerService service = ServiceManagerService.fromJson(item);
            if (!service.id.isEmpty()) {
                services.add(service);
            }
        }
        return services;
    }

    private static JSONObject parseObject(String body) throws JSONException {
        Object parsed = new JSONTokener(safeTrim(body)).nextValue();
        if (parsed instanceof JSONObject) {
            return (JSONObject) parsed;
        }
        throw new JSONException("service-manager 返回的不是 JSON object");
    }

    private static List<ServiceManagerLogLine> parseLogLines(String body) throws JSONException {
        Object parsed = new JSONTokener(safeTrim(body)).nextValue();
        JSONArray array;
        if (parsed instanceof JSONArray) {
            array = (JSONArray) parsed;
        } else if (parsed instanceof JSONObject) {
            JSONObject object = (JSONObject) parsed;
            array = object.optJSONArray("logs");
            if (array == null) {
                array = object.optJSONArray("entries");
            }
            if (array == null) {
                array = new JSONArray();
            }
        } else {
            array = new JSONArray();
        }
        List<ServiceManagerLogLine> lines = new ArrayList<>();
        for (int i = 0; i < array.length(); i++) {
            JSONObject item = array.optJSONObject(i);
            if (item != null) {
                lines.add(ServiceManagerLogLine.fromJson(item));
            }
        }
        return lines;
    }

    private static StatusFields parseOptionalStatusFields(String body) {
        String trimmed = safeTrim(body);
        if (trimmed.isEmpty()) {
            return new StatusFields("", "", null);
        }
        try {
            Object parsed = new JSONTokener(trimmed).nextValue();
            if (!(parsed instanceof JSONObject)) {
                return new StatusFields("", "", null);
            }
            JSONObject json = (JSONObject) parsed;
            return new StatusFields(json.optString("state", ""), json.optString("provider", ""), readPid(json));
        } catch (JSONException ignored) {
            return new StatusFields("", "", null);
        }
    }

    private static ServiceManagerResult errorResult(Exception e, String fallbackMessage) {
        String message;
        if (e instanceof MissingTokenException) {
            message = e.getMessage();
        } else if (e instanceof SocketTimeoutException) {
            message = fallbackMessage + "：service-manager 请求超时。";
        } else if (e instanceof IOException) {
            message = fallbackMessage + "：service-manager 不可用或网络请求失败。"
                + compactExceptionMessage(e);
        } else if (e instanceof JSONException) {
            message = fallbackMessage + "：service-manager 响应解析失败。"
                + compactExceptionMessage(e);
        } else {
            message = fallbackMessage + "。"
                + compactExceptionMessage(e);
        }
        return ServiceManagerResult.error(message);
    }

    private static String compactExceptionMessage(Exception e) {
        String value = safeTrim(e == null ? "" : e.getMessage());
        return value.isEmpty() ? "" : "\n" + value;
    }

    private static int sanitizeLogLimit(int limit) {
        if (limit <= 0) {
            return DEFAULT_LOG_LIMIT;
        }
        return Math.min(limit, MAX_LOG_LIMIT);
    }

    private static String cleanActionLabel(String action) {
        switch (safeTrim(action)) {
            case "start":
                return "启动";
            case "stop":
                return "关闭";
            case "restart":
                return "重启";
            case "repair":
                return "修复";
            default:
                return safeTrim(action);
        }
    }

    private static Integer readPid(JSONObject json) {
        if (json == null || !json.has("pid") || json.isNull("pid")) {
            return null;
        }
        Object value = json.opt("pid");
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        String text = safeTrim(String.valueOf(value));
        if (text.isEmpty()) {
            return null;
        }
        try {
            return Integer.parseInt(text);
        } catch (NumberFormatException ignored) {
            return null;
        }
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

    private static String firstNonBlank(String first, String second) {
        String value = safeTrim(first);
        return value.isEmpty() ? safeTrim(second) : value;
    }

    private static String safeTrim(String value) {
        return value == null ? "" : value.trim();
    }

    private static String normalizeBaseUrl(String value) {
        String normalized = safeTrim(value);
        if (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized.isEmpty() ? DEFAULT_BASE_URL : normalized;
    }

    private static final class ServiceManagerConfigCandidate {
        final File file;
        final boolean allowImplicitDefaultBaseUrl;

        ServiceManagerConfigCandidate(File file, boolean allowImplicitDefaultBaseUrl) {
            this.file = file;
            this.allowImplicitDefaultBaseUrl = allowImplicitDefaultBaseUrl;
        }
    }

    private static final class HttpResponse {
        final int code;
        final String body;

        HttpResponse(int code, String body) {
            this.code = code;
            this.body = body == null ? "" : body;
        }

        boolean isSuccess() {
            return code >= 200 && code < 300;
        }
    }

    private static final class StatusFields {
        final String state;
        final String provider;
        final Integer pid;

        StatusFields(String state, String provider, Integer pid) {
            this.state = state == null ? "" : state;
            this.provider = provider == null ? "" : provider;
            this.pid = pid;
        }
    }

    private static final class MissingTokenException extends IOException {
        MissingTokenException(String message) {
            super(message);
        }
    }
}
