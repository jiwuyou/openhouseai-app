package com.termux.app.openhouse.desktop;

public final class DesktopAppAction {

    public enum Type {
        OPEN,
        START,
        STOP,
        RESTART,
        LOG,
        SERVICE_CONTROL,
        REPAIR
    }

    public final Type type;
    public final String label;
    public final String serviceId;
    public final boolean enabled;
    public final boolean destructive;
    public final String reason;

    private DesktopAppAction(Type type, String label, String serviceId, boolean enabled, boolean destructive, String reason) {
        this.type = type == null ? Type.OPEN : type;
        this.label = safeTrim(label);
        this.serviceId = safeTrim(serviceId);
        this.enabled = enabled;
        this.destructive = destructive;
        this.reason = safeTrim(reason);
    }

    public static DesktopAppAction open(boolean enabled, String reason) {
        return new DesktopAppAction(Type.OPEN, "打开", "", enabled, false, reason);
    }

    public static DesktopAppAction start(String serviceId, boolean enabled, String reason) {
        return new DesktopAppAction(Type.START, "启动", serviceId, enabled, false, reason);
    }

    public static DesktopAppAction stop(String serviceId, boolean enabled, String reason) {
        return new DesktopAppAction(Type.STOP, "停止", serviceId, enabled, true, reason);
    }

    public static DesktopAppAction restart(String serviceId, boolean enabled, String reason) {
        return new DesktopAppAction(Type.RESTART, "重启", serviceId, enabled, true, reason);
    }

    public static DesktopAppAction log(String serviceId, boolean enabled, String reason) {
        return new DesktopAppAction(Type.LOG, "日志", serviceId, enabled, false, reason);
    }

    public static DesktopAppAction serviceControl(boolean enabled, String reason) {
        return new DesktopAppAction(Type.SERVICE_CONTROL, "服务控制", "", enabled, false, reason);
    }

    public static DesktopAppAction repair(boolean enabled, String reason) {
        return new DesktopAppAction(Type.REPAIR, "修复", "", enabled, false, reason);
    }

    public String serviceManagerAction() {
        switch (type) {
            case START:
                return "start";
            case STOP:
                return "stop";
            case RESTART:
                return "restart";
            case REPAIR:
                return "repair";
            case OPEN:
            case LOG:
            case SERVICE_CONTROL:
            default:
                return "";
        }
    }

    private static String safeTrim(String value) {
        return value == null ? "" : value.trim();
    }
}
