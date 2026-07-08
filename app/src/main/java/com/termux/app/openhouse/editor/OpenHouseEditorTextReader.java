package com.termux.app.openhouse.editor;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

public final class OpenHouseEditorTextReader {

    private static final int BUFFER_SIZE = 16 * 1024;

    private OpenHouseEditorTextReader() {
    }

    public static OpenHouseEditorLoadResult readUtf8(InputStream input, long knownSizeBytes, long maxBytes) throws IOException {
        if (input == null) throw new IOException("Input stream is null");
        long safeMaxBytes = Math.max(0L, maxBytes);
        ByteArrayOutputStream output = new ByteArrayOutputStream((int) Math.min(safeMaxBytes, 64 * 1024));
        byte[] buffer = new byte[BUFFER_SIZE];
        long consumedBytes = 0L;
        boolean truncated = false;
        int read;
        while ((read = input.read(buffer)) != -1) {
            consumedBytes += read;
            long remaining = safeMaxBytes - output.size();
            if (remaining <= 0) {
                truncated = true;
                break;
            }
            int allowed = (int) Math.min(read, remaining);
            output.write(buffer, 0, allowed);
            if (allowed < read) {
                truncated = true;
                break;
            }
        }
        if (knownSizeBytes > safeMaxBytes) {
            truncated = true;
        }
        long sourceSize = knownSizeBytes >= 0 ? knownSizeBytes : (truncated ? -1L : consumedBytes);
        String text = new String(output.toByteArray(), StandardCharsets.UTF_8);
        return OpenHouseEditorLoadResult.loaded(text, output.size(), sourceSize, truncated, truncated, "");
    }
}
