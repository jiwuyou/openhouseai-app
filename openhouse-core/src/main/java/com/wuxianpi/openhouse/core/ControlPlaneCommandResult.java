package com.wuxianpi.openhouse.core;

/** Raw result of the stable Android-to-Termux control-plane command. */
public final class ControlPlaneCommandResult {
    public final int exitCode;
    public final String stdout;
    public final String stderr;

    public ControlPlaneCommandResult(int exitCode, String stdout, String stderr) {
        this.exitCode = exitCode;
        this.stdout = stdout == null ? "" : stdout;
        this.stderr = stderr == null ? "" : stderr;
    }

    public boolean isSuccess() {
        return exitCode == 0;
    }

    public String combinedOutput() {
        if (stdout.isEmpty()) return stderr;
        if (stderr.isEmpty()) return stdout;
        return stdout + (stdout.endsWith("\n") ? "" : "\n") + stderr;
    }
}
