package com.termux.app.openhouse.files.model;

public final class FileItem {

    public static final String ROOT_ID = "";

    private final String spaceId;
    private final String id;
    private final String parentId;
    private final String displayName;
    private final boolean directory;
    private final long size;
    private final long lastModifiedMillis;
    private final String mimeType;
    private final boolean readable;
    private final boolean writable;
    private final boolean deletable;
    private final String nativeLocation;

    private FileItem(Builder builder) {
        this.spaceId = builder.spaceId;
        this.id = builder.id == null ? ROOT_ID : builder.id;
        this.parentId = builder.parentId == null ? ROOT_ID : builder.parentId;
        this.displayName = builder.displayName == null ? "" : builder.displayName;
        this.directory = builder.directory;
        this.size = builder.size;
        this.lastModifiedMillis = builder.lastModifiedMillis;
        this.mimeType = builder.mimeType == null ? "application/octet-stream" : builder.mimeType;
        this.readable = builder.readable;
        this.writable = builder.writable;
        this.deletable = builder.deletable;
        this.nativeLocation = builder.nativeLocation == null ? "" : builder.nativeLocation;
    }

    public String getSpaceId() {
        return spaceId;
    }

    public String getId() {
        return id;
    }

    public String getParentId() {
        return parentId;
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

    public String getMimeType() {
        return mimeType;
    }

    public boolean isReadable() {
        return readable;
    }

    public boolean isWritable() {
        return writable;
    }

    public boolean isDeletable() {
        return deletable;
    }

    public String getNativeLocation() {
        return nativeLocation;
    }

    public static Builder builder(String spaceId, String id, String displayName, boolean directory) {
        return new Builder(spaceId, id, displayName, directory);
    }

    public static final class Builder {
        private final String spaceId;
        private final String id;
        private final String displayName;
        private final boolean directory;
        private String parentId;
        private long size = -1;
        private long lastModifiedMillis = -1;
        private String mimeType;
        private boolean readable = true;
        private boolean writable = true;
        private boolean deletable = true;
        private String nativeLocation;

        private Builder(String spaceId, String id, String displayName, boolean directory) {
            this.spaceId = spaceId;
            this.id = id;
            this.displayName = displayName;
            this.directory = directory;
        }

        public Builder parentId(String parentId) {
            this.parentId = parentId;
            return this;
        }

        public Builder size(long size) {
            this.size = size;
            return this;
        }

        public Builder lastModifiedMillis(long lastModifiedMillis) {
            this.lastModifiedMillis = lastModifiedMillis;
            return this;
        }

        public Builder mimeType(String mimeType) {
            this.mimeType = mimeType;
            return this;
        }

        public Builder readable(boolean readable) {
            this.readable = readable;
            return this;
        }

        public Builder writable(boolean writable) {
            this.writable = writable;
            return this;
        }

        public Builder deletable(boolean deletable) {
            this.deletable = deletable;
            return this;
        }

        public Builder nativeLocation(String nativeLocation) {
            this.nativeLocation = nativeLocation;
            return this;
        }

        public FileItem build() {
            return new FileItem(this);
        }
    }
}
