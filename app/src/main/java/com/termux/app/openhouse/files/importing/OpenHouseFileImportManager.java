package com.termux.app.openhouse.files.importing;

import com.termux.app.openhouse.files.core.OpenHouseFileNameSanitizer;
import com.termux.app.openhouse.files.core.OpenHouseWorkspacePaths;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Date;

public final class OpenHouseFileImportManager {

    private static final int BUFFER_SIZE = 16 * 1024;

    private final OpenHouseWorkspacePaths paths;
    private final OpenHouseInboxGrouping defaultGrouping;
    private final DateProvider dateProvider;

    public OpenHouseFileImportManager(OpenHouseWorkspacePaths paths) {
        this(paths, OpenHouseInboxGrouping.DEFAULT);
    }

    public OpenHouseFileImportManager(OpenHouseWorkspacePaths paths, OpenHouseInboxGrouping defaultGrouping) {
        this(paths, defaultGrouping, Date::new);
    }

    OpenHouseFileImportManager(OpenHouseWorkspacePaths paths, OpenHouseInboxGrouping defaultGrouping, DateProvider dateProvider) {
        if (paths == null) throw new IllegalArgumentException("paths == null");
        this.paths = paths;
        this.defaultGrouping = defaultGrouping == null ? OpenHouseInboxGrouping.DEFAULT : defaultGrouping;
        this.dateProvider = dateProvider == null ? Date::new : dateProvider;
    }

    public OpenHouseImportedFile importStream(InputStream inputStream, OpenHouseImportSource source) throws IOException {
        return importStream(inputStream, source, defaultGrouping);
    }

    public OpenHouseImportedFile importStream(InputStream inputStream, OpenHouseImportSource source,
                                              OpenHouseInboxGrouping grouping) throws IOException {
        if (inputStream == null) {
            throw new IOException("Input stream is null");
        }
        if (source == null) {
            source = OpenHouseImportSource.builder().build();
        }

        paths.ensureTermuxWorkspaceDirs();
        File inboxDir = resolveInboxDir(grouping);
        ensureDirectory(inboxDir);
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

    private File resolveInboxDir(OpenHouseInboxGrouping grouping) {
        OpenHouseInboxGrouping safeGrouping = grouping == null ? OpenHouseInboxGrouping.DEFAULT : grouping;
        String child = safeGrouping.getDirectoryName(dateProvider.now());
        if (child.isEmpty()) {
            return paths.getInboxDir();
        }
        return new File(paths.getInboxDir(), child);
    }

    private static void ensureDirectory(File directory) throws IOException {
        if (directory.isDirectory()) {
            return;
        }
        if (directory.exists()) {
            throw new IOException("Path exists but is not a directory: " + directory.getAbsolutePath());
        }
        if (!directory.mkdirs() && !directory.isDirectory()) {
            throw new IOException("Cannot create directory: " + directory.getAbsolutePath());
        }
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

    interface DateProvider {
        Date now();
    }
}
