package com.termux.app.openhouse.files.importing;

import com.termux.app.openhouse.files.core.OpenHouseAiFileDescriptionBuilder;
import com.termux.app.openhouse.files.core.OpenHouseFileReference;
import com.termux.app.openhouse.files.core.OpenHouseWorkspacePaths;

import java.io.File;

public final class OpenHouseImportedFile {

    private final File file;
    private final OpenHouseImportSource source;
    private final OpenHouseWorkspacePaths paths;

    OpenHouseImportedFile(File file, OpenHouseImportSource source, OpenHouseWorkspacePaths paths) {
        this.file = file;
        this.source = source;
        this.paths = paths;
    }

    public File getFile() {
        return file;
    }

    public OpenHouseImportSource getSource() {
        return source;
    }

    public String getDisplayName() {
        return file.getName();
    }

    public String getMimeType() {
        return source.getMimeType();
    }

    public long getSizeBytes() {
        long sourceSize = source.getSizeBytes();
        return sourceSize >= 0 ? sourceSize : file.length();
    }

    public String getTermuxPath() {
        return OpenHouseWorkspacePaths.normalize(file.getAbsolutePath());
    }

    public String getUbuntuPath() {
        return paths.getUbuntuPathForTermuxFile(file);
    }

    public String getWorkspacePath() {
        return paths.getOpenHouseWorkspacePath(file);
    }

    public OpenHouseFileReference toFileReference() {
        return OpenHouseFileReference.builder()
            .setDisplayName(getDisplayName())
            .setMimeType(getMimeType())
            .setSizeBytes(getSizeBytes())
            .setAndroidUri(source.getAndroidUri())
            .setAndroidDisplayLocation(source.getAndroidDisplayLocation())
            .setTermuxPath(getTermuxPath())
            .setUbuntuPath(getUbuntuPath())
            .setWorkspacePath(getWorkspacePath())
            .build();
    }

    public String buildAiDescription() {
        return OpenHouseAiFileDescriptionBuilder.build(toFileReference());
    }
}
