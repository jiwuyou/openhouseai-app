package com.wuxianpi.openhouse.core.service;

import com.wuxianpi.openhouse.core.RuntimeConnection;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

public final class UrlConnectionHttpTransport implements HttpTransport {
    private static final int MAX_BODY_CHARS = 256 * 1024;

    @Override public HttpResponseSpec execute(RuntimeConnection runtime, HttpRequestSpec request) throws IOException {
        if (runtime == null) throw new IllegalArgumentException("runtime connection is required");
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(runtime.serviceManagerBaseUrl + request.path).openConnection();
            connection.setConnectTimeout(request.connectTimeoutMillis);
            connection.setReadTimeout(request.readTimeoutMillis);
            connection.setRequestMethod(request.method);
            connection.setRequestProperty("Accept", "application/json");
            if (request.authenticated) {
                if (!runtime.hasServiceManagerToken()) throw new IOException("service-manager token is missing");
                connection.setRequestProperty("Authorization", "Bearer " + runtime.serviceManagerToken);
            }
            if ("POST".equals(request.method)) connection.setRequestProperty("Content-Length", "0");
            int code = connection.getResponseCode();
            return new HttpResponseSpec(code, readBody(connection, code >= 400));
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static String readBody(HttpURLConnection connection, boolean error) throws IOException {
        InputStream input = error ? connection.getErrorStream() : connection.getInputStream();
        if (input == null) return "";
        StringBuilder result = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                int remaining = MAX_BODY_CHARS - result.length();
                if (remaining <= 0) break;
                if (line.length() > remaining) {
                    result.append(line, 0, remaining);
                    break;
                }
                result.append(line).append('\n');
            }
        }
        return result.toString().trim();
    }
}
