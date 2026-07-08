package com.termux.app.openhouse.files.network.webdav;

import okhttp3.HttpUrl;

public final class WebDavConfig {

    private final String id;
    private final String displayName;
    private final HttpUrl baseUrl;
    private final String username;
    private final String password;

    public WebDavConfig(String id, String displayName, String baseUrl, String username, String password) {
        if (id == null || id.trim().isEmpty()) throw new IllegalArgumentException("id is empty");
        if (displayName == null || displayName.trim().isEmpty()) throw new IllegalArgumentException("displayName is empty");
        HttpUrl parsed = HttpUrl.get(baseUrl);
        this.id = id;
        this.displayName = displayName;
        this.baseUrl = ensureTrailingSlash(parsed);
        this.username = username == null ? "" : username;
        this.password = password == null ? "" : password;
    }

    public String getId() {
        return id;
    }

    public String getDisplayName() {
        return displayName;
    }

    public HttpUrl getBaseUrl() {
        return baseUrl;
    }

    public String getUsername() {
        return username;
    }

    public String getPassword() {
        return password;
    }

    public boolean hasBasicAuth() {
        return !username.isEmpty();
    }

    private static HttpUrl ensureTrailingSlash(HttpUrl url) {
        String encodedPath = url.encodedPath();
        if (encodedPath.endsWith("/")) return url;
        return url.newBuilder().addPathSegment("").build();
    }
}
