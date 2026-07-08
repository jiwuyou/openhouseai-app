package com.termux.app.openhouse.files.model;

import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

public final class FileSpace {

    private final String id;
    private final FileSpaceType type;
    private final String displayName;
    private final String rootLabel;
    private final String locationSummary;
    private final Set<FileOperation> supportedOperations;
    private final Map<String, String> metadata;

    private FileSpace(Builder builder) {
        this.id = requireNonEmpty(builder.id, "id");
        this.type = builder.type;
        if (this.type == null) throw new IllegalArgumentException("type == null");
        this.displayName = requireNonEmpty(builder.displayName, "displayName");
        this.rootLabel = builder.rootLabel == null ? "" : builder.rootLabel;
        this.locationSummary = builder.locationSummary == null ? "" : builder.locationSummary;
        this.supportedOperations = builder.supportedOperations.isEmpty()
            ? Collections.<FileOperation>emptySet()
            : Collections.unmodifiableSet(EnumSet.copyOf(builder.supportedOperations));
        this.metadata = Collections.unmodifiableMap(new LinkedHashMap<>(builder.metadata));
    }

    public String getId() {
        return id;
    }

    public FileSpaceType getType() {
        return type;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getRootLabel() {
        return rootLabel;
    }

    public String getLocationSummary() {
        return locationSummary;
    }

    public boolean supports(FileOperation operation) {
        return supportedOperations.contains(operation);
    }

    public Set<FileOperation> getSupportedOperations() {
        return supportedOperations;
    }

    public Map<String, String> getMetadata() {
        return metadata;
    }

    public static Builder builder(String id, FileSpaceType type, String displayName) {
        return new Builder(id, type, displayName);
    }

    private static String requireNonEmpty(String value, String name) {
        if (value == null || value.trim().isEmpty()) throw new IllegalArgumentException(name + " is empty");
        return value;
    }

    public static final class Builder {
        private final String id;
        private final FileSpaceType type;
        private final String displayName;
        private String rootLabel;
        private String locationSummary;
        private final Set<FileOperation> supportedOperations = EnumSet.allOf(FileOperation.class);
        private final Map<String, String> metadata = new LinkedHashMap<>();

        private Builder(String id, FileSpaceType type, String displayName) {
            this.id = id;
            this.type = type;
            this.displayName = displayName;
        }

        public Builder rootLabel(String rootLabel) {
            this.rootLabel = rootLabel;
            return this;
        }

        public Builder locationSummary(String locationSummary) {
            this.locationSummary = locationSummary;
            return this;
        }

        public Builder supportedOperations(Set<FileOperation> operations) {
            this.supportedOperations.clear();
            if (operations != null) this.supportedOperations.addAll(operations);
            return this;
        }

        public Builder metadata(String key, String value) {
            if (key != null && value != null) this.metadata.put(key, value);
            return this;
        }

        public FileSpace build() {
            return new FileSpace(this);
        }
    }
}
