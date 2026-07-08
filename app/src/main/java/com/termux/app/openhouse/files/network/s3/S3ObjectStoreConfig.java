package com.termux.app.openhouse.files.network.s3;

import okhttp3.HttpUrl;

public final class S3ObjectStoreConfig {

    private final String id;
    private final String displayName;
    private final HttpUrl endpoint;
    private final String region;
    private final String bucket;
    private final String accessKey;
    private final String secretKey;
    private final String sessionToken;
    private final boolean pathStyleAccess;

    public S3ObjectStoreConfig(String id, String displayName, String endpoint, String region,
                               String bucket, String accessKey, String secretKey,
                               String sessionToken, boolean pathStyleAccess) {
        if (id == null || id.trim().isEmpty()) throw new IllegalArgumentException("id is empty");
        if (displayName == null || displayName.trim().isEmpty()) throw new IllegalArgumentException("displayName is empty");
        if (region == null || region.trim().isEmpty()) throw new IllegalArgumentException("region is empty");
        if (bucket == null || bucket.trim().isEmpty()) throw new IllegalArgumentException("bucket is empty");
        if (accessKey == null || accessKey.trim().isEmpty()) throw new IllegalArgumentException("accessKey is empty");
        if (secretKey == null || secretKey.trim().isEmpty()) throw new IllegalArgumentException("secretKey is empty");
        this.id = id;
        this.displayName = displayName;
        this.endpoint = HttpUrl.get(endpoint);
        this.region = region;
        this.bucket = bucket;
        this.accessKey = accessKey;
        this.secretKey = secretKey;
        this.sessionToken = sessionToken == null ? "" : sessionToken;
        this.pathStyleAccess = pathStyleAccess;
    }

    public String getId() {
        return id;
    }

    public String getDisplayName() {
        return displayName;
    }

    public HttpUrl getEndpoint() {
        return endpoint;
    }

    public String getRegion() {
        return region;
    }

    public String getBucket() {
        return bucket;
    }

    public String getAccessKey() {
        return accessKey;
    }

    public String getSecretKey() {
        return secretKey;
    }

    public String getSessionToken() {
        return sessionToken;
    }

    public boolean hasSessionToken() {
        return !sessionToken.isEmpty();
    }

    public boolean isPathStyleAccess() {
        return pathStyleAccess;
    }
}
