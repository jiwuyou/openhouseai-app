package com.termux.app.openhouse.files.network.s3;

public final class S3ObjectEntry {

    private final String key;
    private final boolean directory;
    private final long size;
    private final long lastModifiedMillis;
    private final String etag;

    public S3ObjectEntry(String key, boolean directory, long size, long lastModifiedMillis, String etag) {
        this.key = key == null ? "" : key;
        this.directory = directory;
        this.size = size;
        this.lastModifiedMillis = lastModifiedMillis;
        this.etag = etag == null ? "" : etag;
    }

    public String getKey() {
        return key;
    }

    public boolean isDirectory() {
        return directory;
    }

    public long getSize() {
        return size;
    }

    public long getLastModifiedMillis() {
        return lastModifiedMillis;
    }

    public String getEtag() {
        return etag;
    }
}
