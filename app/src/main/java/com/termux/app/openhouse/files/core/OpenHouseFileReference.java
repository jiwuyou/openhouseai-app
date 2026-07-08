package com.termux.app.openhouse.files.core;

public final class OpenHouseFileReference {

    private final String displayName;
    private final String mimeType;
    private final long sizeBytes;
    private final String androidUri;
    private final String androidDisplayLocation;
    private final String termuxPath;
    private final String ubuntuPath;
    private final String workspacePath;

    private OpenHouseFileReference(Builder builder) {
        this.displayName = builder.displayName;
        this.mimeType = builder.mimeType;
        this.sizeBytes = builder.sizeBytes;
        this.androidUri = builder.androidUri;
        this.androidDisplayLocation = builder.androidDisplayLocation;
        this.termuxPath = builder.termuxPath;
        this.ubuntuPath = builder.ubuntuPath;
        this.workspacePath = builder.workspacePath;
    }

    public static Builder builder() {
        return new Builder();
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getMimeType() {
        return mimeType;
    }

    public long getSizeBytes() {
        return sizeBytes;
    }

    public String getAndroidUri() {
        return androidUri;
    }

    public String getAndroidDisplayLocation() {
        return androidDisplayLocation;
    }

    public String getTermuxPath() {
        return termuxPath;
    }

    public String getUbuntuPath() {
        return ubuntuPath;
    }

    public String getWorkspacePath() {
        return workspacePath;
    }

    public boolean hasContentUri() {
        return OpenHouseWorkspacePaths.isContentUri(androidUri);
    }

    public static final class Builder {
        private String displayName;
        private String mimeType;
        private long sizeBytes = -1;
        private String androidUri;
        private String androidDisplayLocation;
        private String termuxPath;
        private String ubuntuPath;
        private String workspacePath;

        private Builder() {
        }

        public Builder setDisplayName(String displayName) {
            this.displayName = displayName;
            return this;
        }

        public Builder setMimeType(String mimeType) {
            this.mimeType = mimeType;
            return this;
        }

        public Builder setSizeBytes(long sizeBytes) {
            this.sizeBytes = sizeBytes;
            return this;
        }

        public Builder setAndroidUri(String androidUri) {
            this.androidUri = androidUri;
            return this;
        }

        public Builder setAndroidDisplayLocation(String androidDisplayLocation) {
            this.androidDisplayLocation = androidDisplayLocation;
            return this;
        }

        public Builder setTermuxPath(String termuxPath) {
            this.termuxPath = termuxPath;
            return this;
        }

        public Builder setUbuntuPath(String ubuntuPath) {
            this.ubuntuPath = ubuntuPath;
            return this;
        }

        public Builder setWorkspacePath(String workspacePath) {
            this.workspacePath = workspacePath;
            return this;
        }

        public OpenHouseFileReference build() {
            return new OpenHouseFileReference(this);
        }
    }
}
