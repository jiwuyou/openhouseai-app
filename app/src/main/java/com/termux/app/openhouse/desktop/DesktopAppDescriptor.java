package com.termux.app.openhouse.desktop;

import com.termux.app.openhouse.components.OpenHouseComponent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class DesktopAppDescriptor {

    public final String id;
    public final String title;
    public final String subtitle;
    public final String section;
    public final int order;
    public final boolean visible;
    public final boolean favorite;
    public final boolean home;
    public final DesktopAppEntry entry;
    public final String controlTitle;
    public final List<String> serviceNames;
    public final List<String> serviceRefs;
    public final String source;
    public final OpenHouseComponent component;

    private DesktopAppDescriptor(Builder builder) {
        this.id = safeTrim(builder.id);
        this.title = safeTrim(builder.title);
        this.subtitle = safeTrim(builder.subtitle);
        this.section = safeTrim(builder.section);
        this.order = builder.order;
        this.visible = builder.visible;
        this.favorite = builder.favorite;
        this.home = builder.home;
        this.entry = builder.entry == null ? DesktopAppEntry.unknown("") : builder.entry;
        this.controlTitle = safeTrim(builder.controlTitle);
        this.serviceNames = immutableStrings(builder.serviceNames);
        this.serviceRefs = immutableStrings(builder.serviceRefs);
        this.source = safeTrim(builder.source);
        this.component = builder.component;
    }

    public static DesktopAppDescriptor fromComponent(OpenHouseComponent component) {
        if (component == null) {
            return builder().build();
        }
        return builder()
            .id(component.id)
            .title(component.title)
            .subtitle(component.subtitle)
            .section(component.section)
            .order(component.order)
            .visible(component.visible)
            .favorite(component.favorite)
            .home(component.home)
            .entry(DesktopAppEntry.fromComponent(component))
            .controlTitle(component.controlTitle)
            .serviceNames(component.serviceNames)
            .serviceRefs(component.serviceRefs)
            .source(component.source)
            .component(component)
            .build();
    }

    public static Builder builder() {
        return new Builder();
    }

    public boolean hasEntry() {
        return entry != null && entry.type != DesktopAppEntry.Type.UNKNOWN && entry.canOpenDirectly();
    }

    public boolean hasControlEntry() {
        return !serviceNames.isEmpty() || !serviceRefs.isEmpty();
    }

    public List<String> serviceIds() {
        return DesktopAppServices.resolveServiceIds(serviceNames, serviceRefs);
    }

    public String displayTitle() {
        if (!title.isEmpty()) {
            return title;
        }
        if (!id.isEmpty()) {
            return id;
        }
        return "应用";
    }

    public static final class Builder {
        private String id = "";
        private String title = "";
        private String subtitle = "";
        private String section = "";
        private int order;
        private boolean visible = true;
        private boolean favorite;
        private boolean home;
        private DesktopAppEntry entry = DesktopAppEntry.unknown("");
        private String controlTitle = "";
        private List<String> serviceNames = Collections.emptyList();
        private List<String> serviceRefs = Collections.emptyList();
        private String source = "";
        private OpenHouseComponent component;

        public Builder id(String id) {
            this.id = id;
            return this;
        }

        public Builder title(String title) {
            this.title = title;
            return this;
        }

        public Builder subtitle(String subtitle) {
            this.subtitle = subtitle;
            return this;
        }

        public Builder section(String section) {
            this.section = section;
            return this;
        }

        public Builder order(int order) {
            this.order = order;
            return this;
        }

        public Builder visible(boolean visible) {
            this.visible = visible;
            return this;
        }

        public Builder favorite(boolean favorite) {
            this.favorite = favorite;
            return this;
        }

        public Builder home(boolean home) {
            this.home = home;
            return this;
        }

        public Builder entry(DesktopAppEntry entry) {
            this.entry = entry;
            return this;
        }

        public Builder controlTitle(String controlTitle) {
            this.controlTitle = controlTitle;
            return this;
        }

        public Builder serviceNames(List<String> serviceNames) {
            this.serviceNames = serviceNames;
            return this;
        }

        public Builder serviceRefs(List<String> serviceRefs) {
            this.serviceRefs = serviceRefs;
            return this;
        }

        public Builder source(String source) {
            this.source = source;
            return this;
        }

        public Builder component(OpenHouseComponent component) {
            this.component = component;
            return this;
        }

        public DesktopAppDescriptor build() {
            return new DesktopAppDescriptor(this);
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
        if (out.isEmpty()) {
            return Collections.emptyList();
        }
        return Collections.unmodifiableList(out);
    }

    private static String safeTrim(String value) {
        return value == null ? "" : value.trim();
    }
}
