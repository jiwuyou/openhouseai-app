package com.wuxianpi.openhouse.core.service;

import com.wuxianpi.openhouse.core.RuntimeConnection;
import com.wuxianpi.openhouse.core.registry.RegistryManifest;
import com.wuxianpi.openhouse.core.registry.RegistryRemoteResult;
import com.wuxianpi.openhouse.core.registry.RegistryRemoteSource;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

public final class ServiceManagerClient implements RegistryRemoteSource {
    public static final int DEFAULT_LOG_LIMIT = 80;
    public static final int MAX_LOG_LIMIT = 500;
    private static final int CONNECT_TIMEOUT_MS = 2500;
    private static final int READ_TIMEOUT_MS = 7000;

    private final RuntimeConnection runtime;
    private final HttpTransport transport;

    public ServiceManagerClient(RuntimeConnection runtime) {
        this(runtime, new UrlConnectionHttpTransport());
    }

    public ServiceManagerClient(RuntimeConnection runtime, HttpTransport transport) {
        if (runtime == null) throw new IllegalArgumentException("runtime is required");
        if (transport == null) throw new IllegalArgumentException("transport is required");
        this.runtime = runtime; this.transport = transport;
    }

    public RuntimeConnection runtimeConnection() { return runtime; }

    public ServiceManagerResult healthCheck() {
        try {
            HttpResponseSpec response = execute("GET", "/api/v1/health", false, 1200, 1800);
            if (!response.isSuccess()) response = execute("GET", "/health", false, 1200, 1800);
            return response.isSuccess() ? ServiceManagerResult.builder(true).code(response.code).body(response.body)
                .message("service-manager health check ok").build()
                : ServiceManagerResult.failure(response.code, response.body, "service-manager health check failed");
        } catch (Exception error) { return failed(error, "service-manager health check failed"); }
    }

    public ServiceManagerResult listServices() {
        try {
            HttpResponseSpec response = execute("GET", "/api/v1/services", true);
            if (!response.isSuccess()) return ServiceManagerResult.failure(response.code, response.body, "service list failed");
            List<ServiceManagerService> services = parseServices(response.body);
            return ServiceManagerResult.builder(true).code(response.code).body(response.body)
                .message("loaded " + services.size() + " services").services(services).build();
        } catch (Exception error) { return failed(error, "service list failed"); }
    }

    public ServiceManagerResult getStatus(String serviceId) {
        String id = sanitizeServiceId(serviceId);
        if (id.isEmpty()) return ServiceManagerResult.failure(0, "", "invalid service id");
        try {
            HttpResponseSpec response = execute("GET", "/api/v1/services/" + id + "/status", true);
            if (!response.isSuccess()) return ServiceManagerResult.failure(response.code, response.body, "status failed");
            JSONObject json = object(response.body);
            return ServiceManagerResult.builder(true).code(response.code).body(response.body).message(json.optString("message", ""))
                .serviceId(id).state(json.optString("state", "unknown")).provider(json.optString("provider", ""))
                .pid(ServiceManagerService.readPid(json)).url(ServiceManagerService.readUrl(json)).build();
        } catch (Exception error) { return failed(error, "status failed"); }
    }

    /** Business service lifecycle, including the main Node runtime, always goes through this HTTP API. */
    public ServiceManagerResult runAction(String serviceId, ServiceAction action) {
        String id = sanitizeServiceId(serviceId);
        if (id.isEmpty() || action == null) return ServiceManagerResult.failure(0, "", "invalid service action");
        try {
            HttpResponseSpec response = execute("POST", buildServiceActionPath(id, action), true);
            if (!response.isSuccess()) return ServiceManagerResult.failure(response.code, response.body, "service action failed");
            JSONObject json = optionalObject(response.body);
            return ServiceManagerResult.builder(true).code(response.code).body(response.body).message("action submitted")
                .serviceId(id).action(action.apiName()).state(json.optString("state", ""))
                .provider(json.optString("provider", "")).pid(ServiceManagerService.readPid(json))
                .url(ServiceManagerService.readUrl(json)).build();
        } catch (Exception error) { return failed(error, "service action failed"); }
    }

    public ServiceManagerResult runAction(String serviceId, String action) {
        return runAction(serviceId, ServiceAction.parse(action));
    }

    public ServiceManagerResult runGroupAction(String groupId, ServiceAction action) {
        String id = sanitizeServiceId(groupId);
        if (id.isEmpty() || action == null) return ServiceManagerResult.failure(0, "", "invalid group action");
        try {
            HttpResponseSpec response = execute("POST", "/api/v1/groups/" + id + "/" + action.apiName(), true);
            if (!response.isSuccess()) return ServiceManagerResult.failure(response.code, response.body, "group action failed");
            return ServiceManagerResult.builder(true).code(response.code).body(response.body).message("group action submitted")
                .serviceId(id).action(action.apiName()).build();
        } catch (Exception error) { return failed(error, "group action failed"); }
    }

    public ServiceManagerResult getLogs(String serviceId, int limit) {
        String id = sanitizeServiceId(serviceId);
        if (id.isEmpty()) return ServiceManagerResult.failure(0, "", "invalid service id");
        int cleanLimit = limit <= 0 ? DEFAULT_LOG_LIMIT : Math.min(limit, MAX_LOG_LIMIT);
        try {
            HttpResponseSpec response = execute("GET", "/api/v1/services/" + id + "/logs?limit=" + cleanLimit, true);
            if (!response.isSuccess()) return ServiceManagerResult.failure(response.code, response.body, "logs failed");
            List<ServiceManagerLogLine> lines = parseLogs(response.body);
            return ServiceManagerResult.builder(true).code(response.code).body(response.body).message("loaded " + lines.size() + " log lines")
                .serviceId(id).logLines(lines).build();
        } catch (Exception error) { return failed(error, "logs failed"); }
    }

    @Override public RegistryRemoteResult loadRegistry() {
        try {
            HttpResponseSpec components = execute("GET", "/api/v1/registry/components", true);
            if (!components.isSuccess()) return new RegistryRemoteResult(false, components.code, "", null, components.body);
            List<RegistryManifest> manifests = parseRegistryComponents(components.body);
            String revision = Integer.toHexString(components.body.hashCode());
            try {
                HttpResponseSpec state = execute("GET", "/api/v1/registry/state", true);
                if (state.isSuccess()) {
                    JSONObject object = object(state.body);
                    revision = object.optInt("version", 0) + ":" + object.optString("generatedAt", revision);
                }
            } catch (Exception ignored) {
                // Component data is authoritative; registry state only improves diagnostics/revision.
            }
            return new RegistryRemoteResult(true, components.code, revision, manifests, "");
        } catch (Exception error) {
            return new RegistryRemoteResult(false, 0, "", null, message(error));
        }
    }

    public static String buildServiceActionPath(String serviceId, ServiceAction action) {
        String id = sanitizeServiceId(serviceId);
        if (id.isEmpty() || action == null) throw new IllegalArgumentException("invalid service action");
        return "/api/v1/services/" + id + "/" + action.apiName();
    }

    public static ServiceManagerTarget parseServiceManagerRef(String value) {
        String ref = value == null ? "" : value.trim();
        String servicePrefix = "service-manager://services/";
        if (ref.startsWith(servicePrefix)) {
            String id = sanitizeServiceId(ref.substring(servicePrefix.length()));
            return id.isEmpty() ? ServiceManagerTarget.invalid("invalid service id") : ServiceManagerTarget.valid(id, null);
        }
        String actionPrefix = "service-manager://actions/";
        if (ref.startsWith(actionPrefix)) {
            String target = ref.substring(actionPrefix.length());
            int dot = target.lastIndexOf('.');
            String id = sanitizeServiceId(dot > 0 ? target.substring(0, dot) : target);
            ServiceAction action = dot > 0 ? ServiceAction.parse(target.substring(dot + 1)) : null;
            return id.isEmpty() || action == null ? ServiceManagerTarget.invalid("invalid action reference")
                : ServiceManagerTarget.valid(id, action);
        }
        return ServiceManagerTarget.invalid("unsupported service-manager reference");
    }

    public static String sanitizeServiceId(String value) {
        String input = value == null ? "" : value.trim();
        if (input.isEmpty()) return "";
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (!((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                || c == '_' || c == '-' || c == '.')) return "";
        }
        return input;
    }

    private HttpResponseSpec execute(String method, String path, boolean authenticated) throws Exception {
        return execute(method, path, authenticated, CONNECT_TIMEOUT_MS, READ_TIMEOUT_MS);
    }
    private HttpResponseSpec execute(String method, String path, boolean authenticated, int connect, int read) throws Exception {
        return transport.execute(runtime, new HttpRequestSpec(method, path, authenticated, connect, read));
    }

    private static List<ServiceManagerService> parseServices(String body) throws Exception {
        Object value = new JSONTokener(text(body)).nextValue();
        JSONArray array = value instanceof JSONArray ? (JSONArray) value : value instanceof JSONObject
            ? firstArray((JSONObject) value, "services", "items") : new JSONArray();
        List<ServiceManagerService> services = new ArrayList<>();
        for (int i = 0; i < array.length(); i++) {
            JSONObject item = array.optJSONObject(i);
            if (item != null) {
                ServiceManagerService service = ServiceManagerService.fromJson(item);
                if (!service.id.isEmpty()) services.add(service);
            }
        }
        return services;
    }

    private static List<ServiceManagerLogLine> parseLogs(String body) throws Exception {
        Object value = new JSONTokener(text(body)).nextValue();
        JSONArray array = value instanceof JSONArray ? (JSONArray) value : value instanceof JSONObject
            ? firstArray((JSONObject) value, "logs", "entries") : new JSONArray();
        List<ServiceManagerLogLine> lines = new ArrayList<>();
        for (int i = 0; i < array.length(); i++) {
            JSONObject item = array.optJSONObject(i);
            if (item != null) lines.add(ServiceManagerLogLine.fromJson(item));
        }
        return lines;
    }

    private static List<RegistryManifest> parseRegistryComponents(String body) throws Exception {
        Object value = new JSONTokener(text(body)).nextValue();
        JSONArray array = value instanceof JSONArray ? (JSONArray) value : value instanceof JSONObject
            ? firstArray((JSONObject) value, "components", "items") : new JSONArray();
        List<RegistryManifest> manifests = new ArrayList<>();
        for (int i = 0; i < array.length(); i++) manifests.add(RegistryManifest.fromApiRecord(array.getJSONObject(i)));
        return manifests;
    }

    private static JSONArray firstArray(JSONObject object, String... keys) {
        for (String key : keys) {
            JSONArray value = object.optJSONArray(key);
            if (value != null) return value;
        }
        return new JSONArray();
    }
    private static JSONObject object(String body) throws Exception {
        Object value = new JSONTokener(text(body)).nextValue();
        if (!(value instanceof JSONObject)) throw new IllegalArgumentException("response is not a JSON object");
        return (JSONObject) value;
    }
    private static JSONObject optionalObject(String body) {
        if (text(body).isEmpty()) return new JSONObject();
        try { return object(body); } catch (Exception ignored) { return new JSONObject(); }
    }
    private static ServiceManagerResult failed(Exception error, String fallback) {
        return ServiceManagerResult.failure(0, "", fallback + ": " + message(error));
    }
    private static String message(Throwable error) {
        String value = error == null ? "" : error.getMessage();
        return value == null || value.trim().isEmpty() ? error == null ? "" : error.getClass().getSimpleName() : value.trim();
    }
    private static String text(String value) { return value == null ? "" : value.trim(); }
}
