package com.termux.app.openhouse.files.network;

import java.io.IOException;
import java.io.InputStream;

import okhttp3.MediaType;
import okhttp3.RequestBody;
import okio.BufferedSink;

public final class InputStreamRequestBody extends RequestBody {

    private final MediaType contentType;
    private final InputStream input;
    private final long contentLength;

    public InputStreamRequestBody(MediaType contentType, InputStream input, long contentLength) {
        this.contentType = contentType;
        this.input = input;
        this.contentLength = contentLength;
    }

    @Override
    public MediaType contentType() {
        return contentType;
    }

    @Override
    public long contentLength() {
        return contentLength >= 0 ? contentLength : -1;
    }

    @Override
    public void writeTo(BufferedSink sink) throws IOException {
        byte[] buffer = new byte[64 * 1024];
        int read;
        try {
            while ((read = input.read(buffer)) != -1) {
                sink.write(buffer, 0, read);
            }
        } finally {
            input.close();
        }
    }
}
