package com.termux.app.openhouse;

import android.content.Context;

import com.termux.app.ClaudeCodeUiSettings;

public final class OpenHouseClaudeCodeUiController {

    private static volatile OpenHouseClaudeCodeUiController instance;

    private final OpenHouseMaintainerRunner maintainerRunner;

    public static OpenHouseClaudeCodeUiController getInstance(Context context) {
        if (instance == null) {
            synchronized (OpenHouseClaudeCodeUiController.class) {
                if (instance == null) {
                    instance = new OpenHouseClaudeCodeUiController(context);
                }
            }
        }
        return instance;
    }

    private OpenHouseClaudeCodeUiController(Context context) {
        this.maintainerRunner = new OpenHouseMaintainerRunner(context.getApplicationContext());
    }

    public OpenHouseMaintainerRunner.Result install() {
        return install(ClaudeCodeUiSettings.DEFAULT_PORT);
    }

    public OpenHouseMaintainerRunner.Result install(int port) {
        return maintainerRunner.run(
            OpenHouseMaintainerRunner.Action.INSTALL_CLAUDE_CODE_UI,
            requireValidPort(port));
    }

    public OpenHouseMaintainerRunner.Result start() {
        return start(ClaudeCodeUiSettings.DEFAULT_PORT);
    }

    public OpenHouseMaintainerRunner.Result start(int port) {
        return maintainerRunner.run(
            OpenHouseMaintainerRunner.Action.START_CLAUDE_CODE_UI,
            requireValidPort(port));
    }

    public OpenHouseMaintainerRunner.Result stop() {
        return stop(ClaudeCodeUiSettings.DEFAULT_PORT);
    }

    public OpenHouseMaintainerRunner.Result stop(int port) {
        return maintainerRunner.run(
            OpenHouseMaintainerRunner.Action.STOP_CLAUDE_CODE_UI,
            requireValidPort(port));
    }

    public OpenHouseMaintainerRunner.Result restart() {
        return restart(ClaudeCodeUiSettings.DEFAULT_PORT);
    }

    public OpenHouseMaintainerRunner.Result restart(int port) {
        return maintainerRunner.run(
            OpenHouseMaintainerRunner.Action.RESTART_CLAUDE_CODE_UI,
            requireValidPort(port));
    }

    public String getLoopbackUrl() {
        return ClaudeCodeUiSettings.getLoopbackUrl();
    }

    private int requireValidPort(int port) {
        if (!ClaudeCodeUiSettings.isValidPort(port)) {
            throw new IllegalArgumentException("Invalid ClaudeCodeUI port: " + port);
        }
        return port;
    }
}
