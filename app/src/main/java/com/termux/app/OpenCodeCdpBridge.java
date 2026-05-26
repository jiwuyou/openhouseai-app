package com.termux.app;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

public final class OpenCodeCdpBridge {

    private static final String CDP_BASE_URL = "http://127.0.0.1:9222";
    private static final int TIMEOUT_MS = 1500;

    private OpenCodeCdpBridge() {
    }

    public static boolean isCdpActive() {
        try {
            JSONObject response = requestJson("GET", CDP_BASE_URL + "/json/version");
            return response.has("webSocketDebuggerUrl");
        } catch (Exception ignored) {
            return false;
        }
    }

    public static boolean openTab(String url) {
        try {
            String webSocketDebuggerUrl = requestJson("GET", CDP_BASE_URL + "/json/version")
                .optString("webSocketDebuggerUrl", "");
            if (webSocketDebuggerUrl.isEmpty()) {
                return false;
            }

            return openTabWithWebSocket(webSocketDebuggerUrl, url);
        } catch (Exception ignored) {
            return false;
        }
    }

    private static boolean openTabWithWebSocket(String webSocketDebuggerUrl, String pageUrl) throws Exception {
        OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .build();

        CountDownLatch latch = new CountDownLatch(1);
        BridgeState state = new BridgeState();
        Request request = new Request.Builder().url(webSocketDebuggerUrl).build();
        WebSocket webSocket = client.newWebSocket(request, new WebSocketListener() {
            @Override
            public void onOpen(WebSocket webSocket, Response response) {
                try {
                    JSONObject payload = new JSONObject();
                    payload.put("id", 1);
                    payload.put("method", "Target.createTarget");
                    JSONObject params = new JSONObject();
                    params.put("url", pageUrl);
                    payload.put("params", params);
                    webSocket.send(payload.toString());
                } catch (Exception e) {
                    state.success = false;
                    latch.countDown();
                    webSocket.close(1000, "payload-error");
                }
            }

            @Override
            public void onMessage(WebSocket webSocket, String text) {
                try {
                    JSONObject message = new JSONObject(text);
                    if (message.optInt("id", -1) == 1) {
                        state.success = !message.has("error");
                        latch.countDown();
                        webSocket.close(1000, "done");
                    }
                } catch (Exception ignored) {
                }
            }

            @Override
            public void onFailure(WebSocket webSocket, Throwable t, Response response) {
                state.success = false;
                latch.countDown();
            }
        });

        boolean completed = latch.await(3, TimeUnit.SECONDS);
        webSocket.cancel();
        client.dispatcher().executorService().shutdown();
        return completed && state.success;
    }

    private static JSONObject requestJson(String method, String url) throws Exception {
        String body = requestText(method, url);
        return new JSONObject(body);
    }

    private static String requestText(String method, String url) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setRequestMethod(method);
        connection.setConnectTimeout(TIMEOUT_MS);
        connection.setReadTimeout(TIMEOUT_MS);
        connection.setUseCaches(false);
        if ("PUT".equals(method)) {
            connection.setDoOutput(true);
        }

        int responseCode = connection.getResponseCode();
        InputStream stream = responseCode >= 200 && responseCode < 400
            ? connection.getInputStream()
            : connection.getErrorStream();

        StringBuilder builder = new StringBuilder();
        if (stream != null) {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    builder.append(line);
                }
            }
        }

        if (responseCode < 200 || responseCode >= 400) {
            throw new IllegalStateException("HTTP " + responseCode + ": " + builder);
        }

        return builder.toString();
    }

    private static final class BridgeState {
        volatile boolean success;
    }
}
