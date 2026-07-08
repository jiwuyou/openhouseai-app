package com.termux.app.openhouse.files.network.webdav;

public final class WebDavResource {

    private final String href;
    private final String displayName;
    private final boolean directory;
    private final long size;
    private final long lastModifiedMillis;
    private final String contentType;

    public WebDavResource(String href, String displayName, boolean directory, long size, long lastModifiedMillis, String contentType) {
        this.href = href == null ? "" : href;
        this.displayName = displayName == null ? "" : displayName;
        this.directory = directory;
        this.size = size;
        this.lastModifiedMillis = lastModifiedMillis;
        this.contentType = contentType == null ? "" : contentType;
    }

    public String getHref() {
        return href;
    }

    public String getDisplayName() {
        return displayName;
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

    public String getContentType() {
        return contentType;
    }
}
