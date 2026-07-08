package com.termux.app.openhouse.files.ui;

import com.termux.app.openhouse.files.model.FileRepository;
import com.termux.app.openhouse.files.model.FileSpace;

public final class OpenHouseFileSpaceEntry {

    private final String id;
    private final String displayName;
    private final String summary;
    private final FileRepository repository;

    OpenHouseFileSpaceEntry(FileRepository repository) {
        if (repository == null) throw new IllegalArgumentException("repository == null");
        FileSpace space = repository.getSpace();
        this.id = space.getId();
        this.displayName = space.getDisplayName();
        this.summary = space.getLocationSummary();
        this.repository = repository;
    }

    public String getId() {
        return id;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getSummary() {
        return summary;
    }

    public FileRepository getRepository() {
        return repository;
    }

    @Override
    public String toString() {
        if (summary == null || summary.trim().isEmpty()) {
            return displayName;
        }
        return displayName + "\n" + summary;
    }
}
