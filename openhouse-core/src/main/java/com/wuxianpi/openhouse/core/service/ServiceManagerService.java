package com.wuxianpi.openhouse.core.service;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class ServiceManagerService {
    public final String id;
    public final String name;
    public final String title;
    public final String description;
    public final String provider;
    public final String state;
    public final Integer pid;
    public final String message;
    public final String url;
    public final List<String> tags;
    public final String raw;

    ServiceManagerService(String id, String name, String title, String description, String provider,
                          String state, Integer pid, String message, String url, List<String> tags, String raw) {
        this.id = text(id); this.name = text(name); this.title = text(title); this.description = text(description);
        this.provider = text(provider); this.state = text(state); this.pid = pid; this.message = text(message);
        this.url = httpUrl(url); this.tags = tags == null ? Collections.emptyList()
            : Collections.unmodifiableList(new ArrayList<>(tags)); this.raw = text(raw);
    }

    static ServiceManagerService fromJson(JSONObject json) {
        JSONObject spec = json.optJSONObject("spec");
        JSONObject service = json.optJSONObject("service");
        JSONObject nestedSpec = service == null ? null : service.optJSONObject("spec");
        JSONObject source = spec != null ? spec : (nestedSpec != null ? nestedSpec : (service != null ? service : json));
        JSONObject status = json.optJSONObject("status");
        String id = first(service == null ? "" : service.optString("id", ""), source.optString("id", ""),
            json.optString("id", ""), source.optString("serviceId", ""),
            status == null ? "" : status.optString("service_id", ""));
        String name = first(source.optString("name", ""), json.optString("name", ""));
        String title = first(source.optString("title", ""), source.optString("label", ""), name, id);
        String provider = first(source.optString("provider", ""), json.optString("provider", ""),
            status == null ? "" : status.optString("provider", ""));
        String state = first(json.optString("state", ""), status == null ? "" : status.optString("state", ""));
        Integer pid = readPid(json);
        if (pid == null && status != null) pid = readPid(status);
        return new ServiceManagerService(id, name, title,
            first(source.optString("description", ""), json.optString("description", "")), provider, state, pid,
            first(json.optString("message", ""), json.optString("error", ""), status == null ? "" : status.optString("message", "")),
            first(readUrl(source), readUrl(json), status == null ? "" : readUrl(status)), readTags(source.optJSONArray("tags")), json.toString());
    }

    public String displayName() { return first(title, name, id); }

    static Integer readPid(JSONObject json) {
        if (json == null || !json.has("pid") || json.isNull("pid")) return null;
        Object value = json.opt("pid");
        if (value instanceof Number) return ((Number) value).intValue();
        try { return Integer.valueOf(String.valueOf(value).trim()); } catch (Exception ignored) { return null; }
    }

    static String readUrl(JSONObject json) {
        if (json == null) return "";
        return httpUrl(first(json.optString("url", ""), json.optString("openUrl", ""),
            json.optString("open_url", ""), json.optString("webUrl", ""), json.optString("web_url", "")));
    }

    private static List<String> readTags(JSONArray array) {
        if (array == null) return Collections.emptyList();
        List<String> values = new ArrayList<>();
        for (int i = 0; i < array.length(); i++) {
            String value = array.optString(i, "").trim();
            if (!value.isEmpty() && !values.contains(value)) values.add(value);
        }
        return values;
    }

    private static String httpUrl(String value) {
        String text = text(value);
        return text.startsWith("http://") || text.startsWith("https://") ? text : "";
    }
    private static String first(String... values) { for (String value : values) if (!text(value).isEmpty()) return text(value); return ""; }
    private static String text(String value) { return value == null ? "" : value.trim(); }
}
