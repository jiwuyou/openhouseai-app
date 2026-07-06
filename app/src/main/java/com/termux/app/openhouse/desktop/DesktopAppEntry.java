package com.termux.app.openhouse.desktop;

import com.termux.app.openhouse.components.OpenHouseComponent;

import java.util.Locale;

public final class DesktopAppEntry {

    public enum Type {
        WEBVIEW,
        NATIVE_PAGE,
        TERMINAL,
        SERVICE_CONTROL,
        ANDROID_ACTIVITY,
        UNKNOWN
    }

    public final Type type;
    public final String rawType;
    public final String url;
    public final String nativePage;
    public final String className;

    private DesktopAppEntry(Type type, String rawType, String url, String nativePage, String className) {
        this.type = type == null ? Type.UNKNOWN : type;
        this.rawType = safeTrim(rawType);
        this.url = safeTrim(url);
        this.nativePage = safeTrim(nativePage);
        this.className = safeTrim(className);
    }

    public static DesktopAppEntry webview(String url) {
        return new DesktopAppEntry(Type.WEBVIEW, "webview", url, "", "");
    }

    public static DesktopAppEntry nativePage(String nativePage) {
        return new DesktopAppEntry(Type.NATIVE_PAGE, "native-page", "", nativePage, "");
    }

    public static DesktopAppEntry terminal() {
        return new DesktopAppEntry(Type.TERMINAL, "terminal", "", "", "");
    }

    public static DesktopAppEntry serviceControl() {
        return new DesktopAppEntry(Type.SERVICE_CONTROL, "service-control", "", "", "");
    }

    public static DesktopAppEntry androidActivity(String className) {
        return new DesktopAppEntry(Type.ANDROID_ACTIVITY, "android-activity", "", "", className);
    }

    public static DesktopAppEntry unknown(String rawType) {
        return new DesktopAppEntry(Type.UNKNOWN, rawType, "", "", "");
    }

    public static DesktopAppEntry fromComponent(OpenHouseComponent component) {
        if (component == null || component.entryType == null) {
            return DesktopAppEntry.unknown("");
        }
        String rawType = component.entryType.name().toLowerCase(Locale.US).replace('_', '-');
        Type type = parseType(rawType);
        switch (type) {
            case WEBVIEW:
                return webview(component.url);
            case NATIVE_PAGE:
                return nativePage(component.nativePage);
            case TERMINAL:
                return terminal();
            case SERVICE_CONTROL:
                return serviceControl();
            case ANDROID_ACTIVITY:
                return androidActivity(readOptionalStringField(component, "className", "activityClassName", "activity"));
            case UNKNOWN:
            default:
                return unknown(rawType);
        }
    }

    public boolean canOpenDirectly() {
        switch (type) {
            case WEBVIEW:
                return !url.isEmpty();
            case NATIVE_PAGE:
                return !nativePage.isEmpty();
            case TERMINAL:
            case SERVICE_CONTROL:
                return true;
            case ANDROID_ACTIVITY:
                return !className.isEmpty();
            case UNKNOWN:
            default:
                return false;
        }
    }

    static Type parseType(String value) {
        String normalized = safeTrim(value).toLowerCase(Locale.US).replace('_', '-');
        if ("webview".equals(normalized) || "web-view".equals(normalized) || "web".equals(normalized)) {
            return Type.WEBVIEW;
        }
        if ("native-page".equals(normalized) || "native".equals(normalized) || "page".equals(normalized)) {
            return Type.NATIVE_PAGE;
        }
        if ("terminal".equals(normalized) || "termux".equals(normalized)) {
            return Type.TERMINAL;
        }
        if ("service-control".equals(normalized) || "service-manager".equals(normalized)) {
            return Type.SERVICE_CONTROL;
        }
        if ("android-activity".equals(normalized) || "activity".equals(normalized)) {
            return Type.ANDROID_ACTIVITY;
        }
        return Type.UNKNOWN;
    }

    private static String readOptionalStringField(Object target, String... fieldNames) {
        if (target == null || fieldNames == null) {
            return "";
        }
        for (String fieldName : fieldNames) {
            if (fieldName == null || fieldName.trim().isEmpty()) {
                continue;
            }
            try {
                java.lang.reflect.Field field = target.getClass().getField(fieldName);
                Object value = field.get(target);
                if (value != null) {
                    String text = safeTrim(String.valueOf(value));
                    if (!text.isEmpty()) {
                        return text;
                    }
                }
            } catch (IllegalAccessException | NoSuchFieldException ignored) {
                // Forward compatible with richer component models.
            }
        }
        return "";
    }

    static String safeTrim(String value) {
        return value == null ? "" : value.trim();
    }
}
