package com.termux.app.openhouse.files.importing;

public final class OpenHouseImportSource {

    private final String suggestedFileName;
    private final String mimeType;
    private final long sizeBytes;
    private final String androidUri;
    private final String androidDisplayLocation;

    private OpenHouseImportSource(Builder builder) {
        this.suggestedFileName = builder.suggestedFileName;
        this.mimeType = builder.mimeType;
        this.sizeBytes = builder.sizeBytes;
        this.androidUri = builder.androidUri;
        this.androidDisplayLocation = builder.androidDisplayLocation;
    }

    public static Builder builder() {
        return new Builder();
    }

    public String getSuggestedFileName() {
        return suggestedFileName;
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

    public static final class Builder {
        private String suggestedFileName;
        private String mimeType;
        private long sizeBytes = -1;
        private String androidUri;
        private String androidDisplayLocation;

        private Builder() {
        }

        public Builder setSuggestedFileName(String suggestedFileName) {
            this.suggestedFileName = suggestedFileName;
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

        public OpenHouseImportSource build() {
            return new OpenHouseImportSource(this);
        }
    }
}
