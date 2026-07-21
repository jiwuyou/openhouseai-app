package com.wuxianpi.openhouse.core;

public final class SetupState {
    public enum Status { NOT_CONFIGURED, CONFIGURING, READY, NEEDS_REPAIR }

    public final Status status;
    public final int progressPercent;
    public final String message;

    public SetupState(Status status, int progressPercent, String message) {
        if (progressPercent < -1 || progressPercent > 100) {
            throw new IllegalArgumentException("progressPercent must be -1 or 0..100");
        }
        this.status = status == null ? Status.NOT_CONFIGURED : status;
        this.progressPercent = progressPercent;
        this.message = message == null ? "" : message;
    }

    public static SetupState ready() {
        return new SetupState(Status.READY, 100, "");
    }

    public boolean isReady() {
        return status == Status.READY;
    }
}
