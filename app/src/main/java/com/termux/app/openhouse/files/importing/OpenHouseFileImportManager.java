package com.termux.app.openhouse.files.importing;

import com.termux.app.openhouse.files.core.OpenHouseFileNameSanitizer;
import com.termux.app.openhouse.files.core.OpenHouseWorkspacePaths;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

public final class OpenHouseFileImportManager {

    private static final int BUFFER_SIZE = 16 * 1024;

    private final OpenHouseWorkspacePaths paths;

    public OpenHouseFileImportManager(OpenHouseWorkspacePaths paths) {
        this.paths = paths;
    }

    public OpenHouseImportedFile importStream(InputStream inputStream, OpenHouseImportSource source) throws IOException {
        if (inputStream == null) {
            throw new IOException("Input stream is null");
        }
        if (source == null) {
            source = OpenHouseImportSource.builder().build();
        }

        paths.ensureTermuxWorkspaceDirs();
        File inboxDir = paths.getInboxDir();
        String safeFileName = OpenHouseFileNameSanitizer.sanitize(source.getSuggestedFileName());
        File target = createNonConflictingFile(inboxDir, safeFileName);

        try (InputStream in = inputStream; FileOutputStream out = new FileOutputStream(target)) {
            byte[] buffer = new byte[BUFFER_SIZE];
            int readBytes;
            while ((readBytes = in.read(buffer)) != -1) {
                out.write(buffer, 0, readBytes);
            }
        } catch (IOException e) {
            //noinspection ResultOfMethodCallIgnored
            target.delete();
            throw e;
        }

        return new OpenHouseImportedFile(target, source, paths);
    }

    static File createNonConflictingFile(File directory, String fileName) throws IOException {
        File target = new File(directory, fileName);
        if (!target.exists()) {
            return target;
        }

        String baseName = fileName;
        String extension = "";
        int dot = fileName.lastIndexOf('.');
        if (dot > 0) {
            baseName = fileName.substring(0, dot);
            extension = fileName.substring(dot);
        }

        for (int i = 1; i < 10000; i++) {
            target = new File(directory, baseName + " (" + i + ")" + extension);
            if (!target.exists()) {
                return target;
            }
        }
        throw new IOException("Cannot create a non-conflicting file name in " + directory.getAbsolutePath());
    }
}
