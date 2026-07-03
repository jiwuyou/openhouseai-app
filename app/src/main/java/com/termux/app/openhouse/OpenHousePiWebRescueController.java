package com.termux.app.openhouse;

import android.content.Context;

import java.util.HashMap;
import java.util.Map;

public final class OpenHousePiWebRescueController {

    public static final int DEFAULT_PORT = 30142;

    private static volatile OpenHousePiWebRescueController instance;

    private final OpenHouseMaintainerRunner maintainerRunner;

    public static OpenHousePiWebRescueController getInstance(Context context) {
        if (instance == null) {
            synchronized (OpenHousePiWebRescueController.class) {
                if (instance == null) {
                    instance = new OpenHousePiWebRescueController(context);
                }
            }
        }
        return instance;
    }

    private OpenHousePiWebRescueController(Context context) {
        this.maintainerRunner = new OpenHouseMaintainerRunner(context.getApplicationContext());
    }

    public OpenHouseMaintainerRunner.Result start() {
        return run("start", DEFAULT_PORT);
    }

    public OpenHouseMaintainerRunner.Result start(int port) {
        return run("start", port);
    }

    public OpenHouseMaintainerRunner.Result restart() {
        return run("restart", DEFAULT_PORT);
    }

    public OpenHouseMaintainerRunner.Result restart(int port) {
        return run("restart", port);
    }

    public OpenHouseMaintainerRunner.Result stop() {
        return run("stop", DEFAULT_PORT);
    }

    public OpenHouseMaintainerRunner.Result stop(int port) {
        return run("stop", port);
    }

    public OpenHouseMaintainerRunner.Result status() {
        return run("status", DEFAULT_PORT);
    }

    public OpenHouseMaintainerRunner.Result status(int port) {
        return run("status", port);
    }

    public OpenHouseMaintainerRunner.Result check() {
        return run("check", DEFAULT_PORT);
    }

    public OpenHouseMaintainerRunner.Result check(int port) {
        return run("check", port);
    }

    public int getDefaultPort() {
        return DEFAULT_PORT;
    }

    public String getLoopbackUrl() {
        return getLoopbackUrl(DEFAULT_PORT);
    }

    public String getLoopbackUrl(int port) {
        return "http://127.0.0.1:" + requireValidPort(port) + "/";
    }

    private OpenHouseMaintainerRunner.Result run(String action, int port) {
        int resolvedPort = requireValidPort(port);
        Map<String, String> environment = new HashMap<>();
        environment.put("OPENHOUSE_PI_WEB_RESCUE_ACTION", action);
        environment.put("OPENHOUSE_PI_WEB_RESCUE_PORT", Integer.toString(resolvedPort));
        return maintainerRunner.run(
            OpenHouseMaintainerRunner.Action.PI_WEB_RESCUE,
            resolvedPort,
            environment);
    }

    private int requireValidPort(int port) {
        if (port < 1024 || port > 65535) {
            throw new IllegalArgumentException("Invalid pi-web rescue port: " + port);
        }
        return port;
    }
}
