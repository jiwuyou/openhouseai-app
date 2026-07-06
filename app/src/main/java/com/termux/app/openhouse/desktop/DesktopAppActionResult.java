package com.termux.app.openhouse.desktop;

public final class DesktopAppActionResult {

    public final boolean success;
    public final boolean handled;
    public final DesktopAppAction.Type actionType;
    public final String serviceId;
    public final String message;
    public final String state;
    public final int pid;
    public final int code;

    private DesktopAppActionResult(
        boolean success,
        boolean handled,
        DesktopAppAction.Type actionType,
        String serviceId,
        String message,
        String state,
        int pid,
        int code
    ) {
        this.success = success;
        this.handled = handled;
        this.actionType = actionType == null ? DesktopAppAction.Type.OPEN : actionType;
        this.serviceId = safeTrim(serviceId);
        this.message = safeTrim(message);
        this.state = safeTrim(state);
        this.pid = pid;
        this.code = code;
    }

    public static DesktopAppActionResult success(DesktopAppAction action, String message, String state, int pid, int code) {
        return new DesktopAppActionResult(true, true, action == null ? null : action.type,
            action == null ? "" : action.serviceId, message, state, pid, code);
    }

    public static DesktopAppActionResult failure(DesktopAppAction action, String message, int code) {
        return new DesktopAppActionResult(false, true, action == null ? null : action.type,
            action == null ? "" : action.serviceId, message, "", -1, code);
    }

    public static DesktopAppActionResult unhandled(DesktopAppAction action, String message) {
        return new DesktopAppActionResult(false, false, action == null ? null : action.type,
            action == null ? "" : action.serviceId, message, "", -1, 0);
    }

    private static String safeTrim(String value) {
        return value == null ? "" : value.trim();
    }
}
