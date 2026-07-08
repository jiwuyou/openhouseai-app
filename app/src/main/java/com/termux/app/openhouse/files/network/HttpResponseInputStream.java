package com.termux.app.openhouse.files.network;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;

import okhttp3.Response;

public final class HttpResponseInputStream extends FilterInputStream {

    private final Response response;

    public HttpResponseInputStream(Response response, InputStream input) {
        super(input);
        this.response = response;
    }

    @Override
    public void close() throws IOException {
        try {
            super.close();
        } finally {
            response.close();
        }
    }
}
