package com.wuxianpi.openhouse.core;

public final class HostActionResult {
    public enum Status { COMPLETED, USER_ACTION_REQUIRED, UNSUPPORTED, FAILED }

    public final Status status;
    public final String message;

    public HostActionResult(Status status, String message) {
        this.status = status == null ? Status.FAILED : status;
        this.message = message == null ? "" : message;
    }

    public boolean isSuccess() {
        return status == Status.COMPLETED;
    }
}
