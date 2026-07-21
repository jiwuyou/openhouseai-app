package com.wuxianpi.openhouse.core;

public final class SetupResult {
    public enum Status { CONFIGURED, ALREADY_CONFIGURED, USER_ACTION_REQUIRED, CANCELLED, FAILED }

    public final Status status;
    public final SetupState state;
    public final String message;

    public SetupResult(Status status, SetupState state, String message) {
        this.status = status == null ? Status.FAILED : status;
        this.state = state == null ? new SetupState(SetupState.Status.NOT_CONFIGURED, -1, "") : state;
        this.message = message == null ? "" : message;
    }

    public boolean isSuccess() {
        return status == Status.CONFIGURED || status == Status.ALREADY_CONFIGURED;
    }
}
