package com.termux.app.openhouse.desktop;

import java.util.Locale;

public final class DesktopLaunchTarget {

    public enum Kind {
        DESKTOP,
        APP,
        LAST_EXITED,
        PAGE
    }

    public static final String DESKTOP_VALUE = "desktop";

    public final Kind kind;
    public final String value;

    private DesktopLaunchTarget(Kind kind, String value) {
        this.kind = kind == null ? Kind.DESKTOP : kind;
        this.value = safeTrim(value);
    }

    public static DesktopLaunchTarget desktop() {
        return new DesktopLaunchTarget(Kind.DESKTOP, DESKTOP_VALUE);
    }

    public static DesktopLaunchTarget app(String appId) {
        String id = safeId(appId);
        return id.isEmpty() ? desktop() : new DesktopLaunchTarget(Kind.APP, id);
    }

    public static DesktopLaunchTarget lastExited() {
        return new DesktopLaunchTarget(Kind.LAST_EXITED, "");
    }

    public static DesktopLaunchTarget page(String page) {
        String pageName = safeId(page);
        return pageName.isEmpty() ? desktop() : new DesktopLaunchTarget(Kind.PAGE, pageName);
    }

    public static DesktopLaunchTarget of(Kind kind, String value) {
        if (kind == Kind.APP) {
            return app(value);
        }
        if (kind == Kind.LAST_EXITED) {
            return lastExited();
        }
        if (kind == Kind.PAGE) {
            return page(value);
        }
        return desktop();
    }

    public static DesktopLaunchTarget parse(String kind, String value) {
        String normalized = safeTrim(kind).toLowerCase(Locale.US).replace('_', '-');
        if ("app".equals(normalized) || "application".equals(normalized)) {
            return app(value);
        }
        if ("last-exited".equals(normalized) || "last".equals(normalized) || "last-page".equals(normalized)) {
            return lastExited();
        }
        if ("page".equals(normalized) || "native-page".equals(normalized)) {
            return page(value);
        }
        return desktop();
    }

    public String persistedKind() {
        return kind.name().toLowerCase(Locale.US).replace('_', '-');
    }

    public boolean isDesktop() {
        return kind == Kind.DESKTOP;
    }

    public boolean isApp() {
        return kind == Kind.APP && !value.isEmpty();
    }

    public boolean isLastExited() {
        return kind == Kind.LAST_EXITED;
    }

    public boolean isPage() {
        return kind == Kind.PAGE && !value.isEmpty();
    }

    private static String safeId(String value) {
        return safeTrim(value).toLowerCase(Locale.US).replace(' ', '-');
    }

    private static String safeTrim(String value) {
        return value == null ? "" : value.trim();
    }
}
