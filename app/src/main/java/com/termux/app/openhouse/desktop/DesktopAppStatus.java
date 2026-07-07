package com.termux.app.openhouse.desktop;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class DesktopAppStatus {

    public enum State {
        READY,
        RUNNING,
        STARTING,
        STOPPED,
        UNREACHABLE,
        FAILED,
        UNKNOWN
    }

    public final String appId;
    public final String title;
    public final State state;
    public final String headline;
    public final String detail;
    public final String url;
    public final boolean serviceManagerReachable;
    public final long checkedAtMillis;
    public final List<String> serviceIds;
    public final List<DesktopAppServiceStatus> services;

    private DesktopAppStatus(Builder builder) {
        this.appId = safeTrim(builder.appId);
        this.title = safeTrim(builder.title);
        this.state = builder.state == null ? State.UNKNOWN : builder.state;
        this.headline = safeTrim(builder.headline);
        this.detail = safeTrim(builder.detail);
        this.url = safeTrim(builder.url);
        this.serviceManagerReachable = builder.serviceManagerReachable;
        this.checkedAtMillis = builder.checkedAtMillis <= 0 ? System.currentTimeMillis() : builder.checkedAtMillis;
        this.serviceIds = immutableStrings(builder.serviceIds);
        this.services = immutableServices(builder.services);
    }

    public static Builder builder() {
        return new Builder();
    }

    public boolean hasBlockingIssue() {
        return state == State.UNREACHABLE || state == State.FAILED;
    }

    public boolean canRetry() {
        return state == State.STOPPED
            || state == State.UNREACHABLE
            || state == State.FAILED
            || state == State.UNKNOWN;
    }

    public static final class Builder {
        private String appId = "";
        private String title = "";
        private State state = State.UNKNOWN;
        private String headline = "";
        private String detail = "";
        private String url = "";
        private boolean serviceManagerReachable;
        private long checkedAtMillis;
        private List<String> serviceIds = Collections.emptyList();
        private List<DesktopAppServiceStatus> services = Collections.emptyList();

        public Builder app(DesktopAppDescriptor app) {
            if (app != null) {
                this.appId = app.id;
                this.title = app.displayTitle();
                this.url = app.entry == null ? "" : app.entry.url;
                this.serviceIds = app.serviceIds();
            }
            return this;
        }

        public Builder state(State state) {
            this.state = state;
            return this;
        }

        public Builder headline(String headline) {
            this.headline = headline;
            return this;
        }

        public Builder detail(String detail) {
            this.detail = detail;
            return this;
        }

        public Builder url(String url) {
            this.url = url;
            return this;
        }

        public Builder serviceManagerReachable(boolean serviceManagerReachable) {
            this.serviceManagerReachable = serviceManagerReachable;
            return this;
        }

        public Builder checkedAtMillis(long checkedAtMillis) {
            this.checkedAtMillis = checkedAtMillis;
            return this;
        }

        public Builder services(List<DesktopAppServiceStatus> services) {
            this.services = services;
            return this;
        }

        public Builder serviceIds(List<String> serviceIds) {
            this.serviceIds = serviceIds;
            return this;
        }

        public DesktopAppStatus build() {
            return new DesktopAppStatus(this);
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

    private static List<DesktopAppServiceStatus> immutableServices(List<DesktopAppServiceStatus> values) {
        if (values == null || values.isEmpty()) {
            return Collections.emptyList();
        }
        List<DesktopAppServiceStatus> out = new ArrayList<>();
        for (DesktopAppServiceStatus value : values) {
            if (value != null) {
                out.add(value);
            }
        }
        return out.isEmpty() ? Collections.emptyList() : Collections.unmodifiableList(out);
    }

    private static String safeTrim(String value) {
        return value == null ? "" : value.trim();
    }
}
