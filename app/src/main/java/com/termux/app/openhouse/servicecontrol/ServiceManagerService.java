package com.termux.app.openhouse.servicecontrol;

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

    ServiceManagerService(
        String id,
        String name,
        String title,
        String description,
        String provider,
        String state,
        Integer pid,
        String message,
        String url,
        List<String> tags,
        String raw
    ) {
        this.id = id == null ? "" : id;
        this.name = name == null ? "" : name;
        this.title = title == null ? "" : title;
        this.description = description == null ? "" : description;
        this.provider = provider == null ? "" : provider;
        this.state = state == null ? "" : state;
        this.pid = pid;
        this.message = message == null ? "" : message;
        this.url = normalizeUrl(url);
        this.tags = tags == null ? Collections.emptyList() : Collections.unmodifiableList(new ArrayList<>(tags));
        this.raw = raw == null ? "" : raw;
    }

    static ServiceManagerService fromJson(JSONObject json) {
        if (json == null) {
            return new ServiceManagerService("", "", "", "", "", "", null, "", "", Collections.emptyList(), "");
        }
        JSONObject spec = json.optJSONObject("spec");
        JSONObject service = json.optJSONObject("service");
        JSONObject source = spec != null ? spec : (service != null ? service : json);
        JSONObject status = json.optJSONObject("status");

        String id = firstNonBlank(
            source.optString("id", ""),
            firstNonBlank(json.optString("id", ""), source.optString("serviceId", ""))
        );
        String name = firstNonBlank(source.optString("name", ""), json.optString("name", ""));
        String title = firstNonBlank(
            source.optString("title", ""),
            firstNonBlank(source.optString("label", ""), firstNonBlank(name, id))
        );
        String provider = firstNonBlank(
            source.optString("provider", ""),
            firstNonBlank(json.optString("provider", ""), status != null ? status.optString("provider", "") : "")
        );
        String state = firstNonBlank(
            json.optString("state", ""),
            status != null ? status.optString("state", "") : ""
        );
        Integer pid = readPid(json);
        if (pid == null && status != null) {
            pid = readPid(status);
        }
        String message = firstNonBlank(
            json.optString("message", ""),
            firstNonBlank(json.optString("error", ""), status != null ? status.optString("message", "") : "")
        );

        return new ServiceManagerService(
            id,
            name,
            title,
            firstNonBlank(source.optString("description", ""), json.optString("description", "")),
            provider,
            state,
            pid,
            message,
            firstNonBlank(
                readUrl(source),
                firstNonBlank(readUrl(json), status != null ? readUrl(status) : "")
            ),
            readStringList(source.optJSONArray("tags")),
            json.toString()
        );
    }

    public String id() {
        return id;
    }

    public String name() {
        return name;
    }

    public String title() {
        return title;
    }

    public String displayName() {
        return firstNonBlank(title, firstNonBlank(name, id));
    }

    public String description() {
        return description;
    }

    public String provider() {
        return provider;
    }

    public String state() {
        return state;
    }

    public int pid() {
        return pid == null ? -1 : pid;
    }

    public String message() {
        return message;
    }

    public String url() {
        return url;
    }

    public List<String> tags() {
        return tags;
    }

    public String raw() {
        return raw;
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

    private static List<String> readStringList(JSONArray array) {
        if (array == null || array.length() == 0) {
            return Collections.emptyList();
        }
        List<String> values = new ArrayList<>();
        for (int i = 0; i < array.length(); i++) {
            String value = safeTrim(array.optString(i, ""));
            if (!value.isEmpty() && !values.contains(value)) {
                values.add(value);
            }
        }
        return values;
    }

    private static String firstNonBlank(String first, String second) {
        String value = safeTrim(first);
        return value.isEmpty() ? safeTrim(second) : value;
    }

    private static String readUrl(JSONObject json) {
        if (json == null) {
            return "";
        }
        return normalizeUrl(firstNonBlank(
            json.optString("url", ""),
            firstNonBlank(
                json.optString("openUrl", ""),
                firstNonBlank(
                    json.optString("open_url", ""),
                    firstNonBlank(json.optString("webUrl", ""), json.optString("web_url", ""))
                )
            )
        ));
    }

    private static String normalizeUrl(String value) {
        String trimmed = safeTrim(value);
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            return trimmed;
        }
        return "";
    }

    private static String safeTrim(String value) {
        return value == null ? "" : value.trim();
    }
}
