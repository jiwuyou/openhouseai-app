package com.termux.app.openhouse.desktop;

import android.content.Intent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class DesktopAppLaunchIntent {

    public enum Kind {
        WEBVIEW,
        NATIVE_PAGE,
        TERMINAL,
        SERVICE_CONTROL,
        ANDROID_ACTIVITY,
        STATUS_PANEL,
        UNSUPPORTED
    }

    public final boolean launchable;
    public final Kind kind;
    public final String appId;
    public final String title;
    public final String url;
    public final String nativePage;
    public final String className;
    public final Intent intent;
    public final List<String> serviceIds;
    public final String message;

    private DesktopAppLaunchIntent(Builder builder) {
        this.launchable = builder.launchable;
        this.kind = builder.kind == null ? Kind.UNSUPPORTED : builder.kind;
        this.appId = safeTrim(builder.appId);
        this.title = safeTrim(builder.title);
        this.url = safeTrim(builder.url);
        this.nativePage = safeTrim(builder.nativePage);
        this.className = safeTrim(builder.className);
        this.intent = builder.intent;
        this.serviceIds = immutableStrings(builder.serviceIds);
        this.message = safeTrim(builder.message);
    }

    static Builder builder(Kind kind) {
        return new Builder(kind);
    }

    public static final class Builder {
        private boolean launchable;
        private final Kind kind;
        private String appId = "";
        private String title = "";
        private String url = "";
        private String nativePage = "";
        private String className = "";
        private Intent intent;
        private List<String> serviceIds = Collections.emptyList();
        private String message = "";

        private Builder(Kind kind) {
            this.kind = kind;
        }

        Builder launchable(boolean launchable) {
            this.launchable = launchable;
            return this;
        }

        Builder app(DesktopAppDescriptor app) {
            if (app != null) {
                this.appId = app.id;
                this.title = app.displayTitle();
                this.serviceIds = app.serviceIds();
            }
            return this;
        }

        Builder url(String url) {
            this.url = url;
            return this;
        }

        Builder nativePage(String nativePage) {
            this.nativePage = nativePage;
            return this;
        }

        Builder className(String className) {
            this.className = className;
            return this;
        }

        Builder intent(Intent intent) {
            this.intent = intent;
            return this;
        }

        Builder message(String message) {
            this.message = message;
            return this;
        }

        DesktopAppLaunchIntent build() {
            return new DesktopAppLaunchIntent(this);
        }
    }

    private static List<String> immutableStrings(List<String> values) {
        if (values == null || values.isEmpty()) {
            return Collections.emptyList();
        }
        List<String> out = new ArrayList<>();
        for (String value : values) {
            String text = safeTrim(value);
            if (!text.isEmpty() && !out.contains(text)) {
                out.add(text);
            }
        }
        return out.isEmpty() ? Collections.emptyList() : Collections.unmodifiableList(out);
    }

    private static String safeTrim(String value) {
        return value == null ? "" : value.trim();
    }
}
