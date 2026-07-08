package com.termux.app.openhouse.files.storage;

import com.termux.app.openhouse.files.model.FileOperationException;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLConnection;

public final class FileRepositoryUtils {

    public static final int DEFAULT_BUFFER_SIZE = 64 * 1024;

    private FileRepositoryUtils() {
    }

    public static long copy(InputStream input, OutputStream output) throws IOException {
        byte[] buffer = new byte[DEFAULT_BUFFER_SIZE];
        long total = 0;
        int read;
        while ((read = input.read(buffer)) != -1) {
            output.write(buffer, 0, read);
            total += read;
        }
        return total;
    }

    public static String guessMimeType(String name, boolean directory) {
        if (directory) return "vnd.android.document/directory";
        String mimeType = URLConnection.guessContentTypeFromName(name);
        return mimeType == null ? "application/octet-stream" : mimeType;
    }

    public static String requireDisplayName(String displayName) throws FileOperationException {
        if (displayName == null || displayName.trim().isEmpty()) {
            throw new FileOperationException(FileOperationException.Code.INVALID_PATH, "File name is empty");
        }
        if (displayName.contains("/") || displayName.contains("\\")) {
            throw new FileOperationException(FileOperationException.Code.INVALID_PATH, "File name cannot contain path separators");
        }
        if (".".equals(displayName) || "..".equals(displayName)) {
            throw new FileOperationException(FileOperationException.Code.INVALID_PATH, "File name is reserved");
        }
        return displayName;
    }
}
