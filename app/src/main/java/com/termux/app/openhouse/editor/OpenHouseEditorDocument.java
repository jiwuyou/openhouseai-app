package com.termux.app.openhouse.editor;

import android.net.Uri;

import com.termux.app.openhouse.files.core.OpenHouseWorkspacePaths;

import java.io.File;

public final class OpenHouseEditorDocument {

    private final File localFile;
    private final Uri uri;
    private final String androidUri;
    private final String displayName;
    private final String mimeType;
    private final long sizeBytes;
    private final String nativeLocation;
    private final String androidDisplayLocation;
    private final String termuxPath;
    private final String ubuntuPath;
    private final String workspacePath;
    private final String sourceSpaceId;
    private final String sourceFileId;
    private final boolean readOnlyRequested;
    private final boolean repositoryExport;

    private OpenHouseEditorDocument(Builder builder) {
        this.localFile = builder.localFile;
        this.uri = builder.uri;
        this.androidUri = resolveAndroidUri(builder.androidUri, builder.localFile, builder.uri);
        this.displayName = isBlank(builder.displayName) ? inferDisplayName(builder.localFile, builder.uri) : builder.displayName;
        this.mimeType = isBlank(builder.mimeType)
            ? OpenHouseEditorTextTypes.guessMimeType(this.displayName)
            : builder.mimeType;
        this.sizeBytes = builder.sizeBytes;
        this.nativeLocation = builder.nativeLocation;
        this.androidDisplayLocation = builder.androidDisplayLocation;
        this.termuxPath = builder.termuxPath;
        this.ubuntuPath = builder.ubuntuPath;
        this.workspacePath = builder.workspacePath;
        this.sourceSpaceId = builder.sourceSpaceId;
        this.sourceFileId = builder.sourceFileId;
        this.readOnlyRequested = builder.readOnlyRequested;
        this.repositoryExport = builder.repositoryExport;
    }

    public static Builder builder() {
        return new Builder();
    }

    public Builder buildUpon() {
        return builder()
            .setLocalFile(localFile)
            .setUri(uri)
            .setAndroidUri(androidUri)
            .setDisplayName(displayName)
            .setMimeType(mimeType)
            .setSizeBytes(sizeBytes)
            .setNativeLocation(nativeLocation)
            .setAndroidDisplayLocation(androidDisplayLocation)
            .setTermuxPath(termuxPath)
            .setUbuntuPath(ubuntuPath)
            .setWorkspacePath(workspacePath)
            .setSourceSpaceId(sourceSpaceId)
            .setSourceFileId(sourceFileId)
            .setReadOnlyRequested(readOnlyRequested)
            .setRepositoryExport(repositoryExport);
    }

    public File getLocalFile() {
        return localFile;
    }

    public Uri getUri() {
        return uri;
    }

    public String getAndroidUri() {
        return androidUri;
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

    public String getNativeLocation() {
        return nativeLocation;
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

    public String getSourceSpaceId() {
        return sourceSpaceId;
    }

    public String getSourceFileId() {
        return sourceFileId;
    }

    public boolean isReadOnlyRequested() {
        return readOnlyRequested;
    }

    public boolean isRepositoryExport() {
        return repositoryExport;
    }

    public boolean hasLocalFile() {
        return localFile != null;
    }

    public boolean hasUri() {
        return uri != null;
    }

    public boolean hasContentUri() {
        return OpenHouseWorkspacePaths.isContentUri(androidUri);
    }

    public boolean hasSource() {
        return hasLocalFile() || hasUri();
    }

    public String androidUriString() {
        return androidUri;
    }

    public String sourceSummary() {
        if (!isBlank(nativeLocation)) return nativeLocation;
        if (uri != null) return uri.toString();
        if (localFile != null) return OpenHouseWorkspacePaths.normalize(localFile.getAbsolutePath());
        return "";
    }

    private static String inferDisplayName(File file, Uri uri) {
        if (file != null && !isBlank(file.getName())) return file.getName();
        if (uri != null && !isBlank(uri.getLastPathSegment())) return uri.getLastPathSegment();
        return "untitled.md";
    }

    private static String resolveAndroidUri(String explicitAndroidUri, File file, Uri uri) {
        if (!isBlank(explicitAndroidUri)) return explicitAndroidUri;
        if (uri != null) return uri.toString();
        if (file != null) return file.toURI().toString();
        return null;
    }

    static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    public static final class Builder {
        private File localFile;
        private Uri uri;
        private String androidUri;
        private String displayName;
        private String mimeType;
        private long sizeBytes = -1L;
        private String nativeLocation;
        private String androidDisplayLocation;
        private String termuxPath;
        private String ubuntuPath;
        private String workspacePath;
        private String sourceSpaceId;
        private String sourceFileId;
        private boolean readOnlyRequested;
        private boolean repositoryExport;

        private Builder() {
        }

        public Builder setLocalFile(File localFile) {
            this.localFile = localFile;
            return this;
        }

        public Builder setUri(Uri uri) {
            this.uri = uri;
            return this;
        }

        public Builder setAndroidUri(String androidUri) {
            this.androidUri = androidUri;
            return this;
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

        public Builder setNativeLocation(String nativeLocation) {
            this.nativeLocation = nativeLocation;
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

        public Builder setSourceSpaceId(String sourceSpaceId) {
            this.sourceSpaceId = sourceSpaceId;
            return this;
        }

        public Builder setSourceFileId(String sourceFileId) {
            this.sourceFileId = sourceFileId;
            return this;
        }

        public Builder setReadOnlyRequested(boolean readOnlyRequested) {
            this.readOnlyRequested = readOnlyRequested;
            return this;
        }

        public Builder setRepositoryExport(boolean repositoryExport) {
            this.repositoryExport = repositoryExport;
            return this;
        }

        public OpenHouseEditorDocument build() {
            return new OpenHouseEditorDocument(this);
        }
    }
}
