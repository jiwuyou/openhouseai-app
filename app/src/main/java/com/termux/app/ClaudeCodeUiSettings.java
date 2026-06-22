package com.termux.app;

public final class ClaudeCodeUiSettings {

    public static final int DEFAULT_PORT = 23083;
    public static final String DEFAULT_HOST = "127.0.0.1";

    private ClaudeCodeUiSettings() {
    }

    public static boolean isValidPort(int port) {
        return port > 0 && port <= 65535;
    }

    public static String getLoopbackUrl() {
        return getLoopbackUrl(DEFAULT_PORT);
    }

    public static String getLoopbackUrl(int port) {
        if (!isValidPort(port)) {
            throw new IllegalArgumentException("Invalid ClaudeCodeUI port: " + port);
        }
        return "http://" + DEFAULT_HOST + ":" + port;
    }
}
