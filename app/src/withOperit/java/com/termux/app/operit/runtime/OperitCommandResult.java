package com.termux.app.operit.runtime;

public final class OperitCommandResult {

    public final String command;
    public final OperitRuntimeTarget target;
    public final String stdout;
    public final String stderr;
    public final int exitCode;
    public final String error;
    public final boolean timedOut;
    public final long durationMs;

    public OperitCommandResult(
        String command,
        OperitRuntimeTarget target,
        String stdout,
        String stderr,
        int exitCode,
        String error,
        boolean timedOut,
        long durationMs
    ) {
        this.command = command == null ? "" : command;
        this.target = target;
        this.stdout = stdout == null ? "" : stdout;
        this.stderr = stderr == null ? "" : stderr;
        this.exitCode = exitCode;
        this.error = error == null ? "" : error;
        this.timedOut = timedOut;
        this.durationMs = Math.max(0L, durationMs);
    }

    public boolean isSuccess() {
        return !timedOut && exitCode == 0 && error.isEmpty();
    }
}
