package com.termux.app.openhouse.desktop.ui;

import com.termux.app.openhouse.components.OpenHouseComponent;
import com.termux.app.openhouse.desktop.DesktopAppDescriptor;

public final class DesktopUiEntry {

    public static final int NO_SLOT = -1;

    public final String id;
    public final String title;
    public final String subtitle;
    public final String iconLabel;
    public final String iconKey;
    public final int order;
    public final int slotIndex;
    public final boolean enabled;
    private final boolean explicitSlotIndex;
    private final boolean explicitOrder;

    private DesktopUiEntry(Builder builder) {
        this.id = safeTrim(builder.id);
        this.title = safeTrim(builder.title);
        this.subtitle = safeTrim(builder.subtitle);
        this.iconLabel = safeTrim(builder.iconLabel);
        this.iconKey = safeTrim(builder.iconKey);
        this.order = builder.order;
        this.slotIndex = builder.explicitSlotIndex ? Math.max(0, builder.slotIndex) : NO_SLOT;
        this.enabled = builder.enabled;
        this.explicitSlotIndex = builder.explicitSlotIndex;
        this.explicitOrder = builder.explicitOrder;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static DesktopUiEntry fromComponent(OpenHouseComponent component) {
        if (component == null) {
            return builder().build();
        }
        return builder()
            .id(component.id)
            .title(component.title)
            .subtitle(component.subtitle)
            .iconLabel(component.iconLabel)
            .iconKey(component.iconKey)
            .order(component.desktopOrder)
            .enabled(component.hasEntry())
            .build();
    }

    public static DesktopUiEntry fromDescriptor(DesktopAppDescriptor descriptor) {
        if (descriptor == null) {
            return builder().build();
        }
        OpenHouseComponent component = descriptor.component;
        return builder()
            .id(descriptor.id)
            .title(descriptor.displayTitle())
            .subtitle(descriptor.subtitle)
            .iconLabel(component == null ? descriptor.displayTitle() : component.iconLabel)
            .iconKey(component == null ? "" : component.iconKey)
            .order(component == null ? descriptor.order : component.desktopOrder)
            .enabled(descriptor.hasEntry())
            .build();
    }

    public String displayTitle() {
        if (!title.isEmpty()) {
            return title;
        }
        if (!id.isEmpty()) {
            return id;
        }
        return "App";
    }

    public String displayIconLabel() {
        String label = firstNonBlank(iconLabel, iconKey, title, id, "App");
        return truncateForIcon(label);
    }

    public DesktopUiEntry withTitle(String newTitle) {
        return toBuilder().title(newTitle).build();
    }

    public DesktopUiEntry withIcon(String newIconLabel, String newIconKey) {
        return toBuilder().iconLabel(newIconLabel).iconKey(newIconKey).build();
    }

    public Builder toBuilder() {
        return builder()
            .id(id)
            .title(title)
            .subtitle(subtitle)
            .iconLabel(iconLabel)
            .iconKey(iconKey)
            .order(order)
            .copySlot(slotIndex, explicitSlotIndex)
            .copyOrder(order, explicitOrder)
            .enabled(enabled);
    }

    public boolean hasExplicitSlotIndex() {
        return explicitSlotIndex && slotIndex >= 0;
    }

    public boolean hasExplicitOrder() {
        return explicitOrder;
    }

    public int requestedSlotIndex(int fallbackSlot) {
        if (hasExplicitSlotIndex()) {
            return slotIndex;
        }
        if (hasExplicitOrder()) {
            return Math.max(0, order);
        }
        return Math.max(0, fallbackSlot);
    }

    public DesktopUiEntry withSlotIndex(int newSlotIndex) {
        return toBuilder().slotIndex(newSlotIndex).build();
    }

    public static final class Builder {
        private String id = "";
        private String title = "";
        private String subtitle = "";
        private String iconLabel = "";
        private String iconKey = "";
        private int order;
        private int slotIndex = NO_SLOT;
        private boolean enabled = true;
        private boolean explicitSlotIndex;
        private boolean explicitOrder;

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

        public Builder iconLabel(String iconLabel) {
            this.iconLabel = iconLabel;
            return this;
        }

        public Builder iconKey(String iconKey) {
            this.iconKey = iconKey;
            return this;
        }

        public Builder order(int order) {
            this.order = order;
            this.explicitOrder = true;
            return this;
        }

        public Builder slotIndex(int slotIndex) {
            if (slotIndex >= 0) {
                this.slotIndex = slotIndex;
                this.explicitSlotIndex = true;
            } else {
                this.slotIndex = NO_SLOT;
                this.explicitSlotIndex = false;
            }
            return this;
        }

        public Builder position(int position) {
            return slotIndex(position);
        }

        public Builder absoluteSlot(int absoluteSlot) {
            return slotIndex(absoluteSlot);
        }

        public Builder enabled(boolean enabled) {
            this.enabled = enabled;
            return this;
        }

        private Builder copySlot(int slotIndex, boolean explicitSlotIndex) {
            this.slotIndex = slotIndex;
            this.explicitSlotIndex = explicitSlotIndex && slotIndex >= 0;
            return this;
        }

        private Builder copyOrder(int order, boolean explicitOrder) {
            this.order = order;
            this.explicitOrder = explicitOrder;
            return this;
        }

        public DesktopUiEntry build() {
            return new DesktopUiEntry(this);
        }
    }

    static String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            String text = safeTrim(value);
            if (!text.isEmpty()) {
                return text;
            }
        }
        return "";
    }

    static String safeTrim(String value) {
        return value == null ? "" : value.trim();
    }

    private static String truncateForIcon(String value) {
        String text = safeTrim(value);
        if (text.isEmpty()) {
            return "App";
        }
        int codePoints = text.codePointCount(0, text.length());
        if (codePoints <= 2) {
            return text;
        }
        int endIndex = text.offsetByCodePoints(0, 1);
        return text.substring(0, endIndex);
    }
}
