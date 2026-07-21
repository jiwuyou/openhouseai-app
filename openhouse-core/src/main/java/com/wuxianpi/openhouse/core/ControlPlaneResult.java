package com.wuxianpi.openhouse.core;

public final class ControlPlaneResult {
    public enum Status { STARTED, STOPPED, ALREADY_RUNNING, ALREADY_STOPPED, USER_ACTION_REQUIRED, UNSUPPORTED, FAILED }

    public final Status status;
    public final String message;

    public ControlPlaneResult(Status status, String message) {
        this.status = status == null ? Status.FAILED : status;
        this.message = message == null ? "" : message;
    }

    public boolean isSuccess() {
        return status == Status.STARTED || status == Status.STOPPED
            || status == Status.ALREADY_RUNNING || status == Status.ALREADY_STOPPED;
    }
}
