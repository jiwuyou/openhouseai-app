package com.termux.app.openhouse.files.saf;

import android.net.Uri;

public final class SafFileSpaceConfig {

    private final String id;
    private final String displayName;
    private final Uri treeUri;

    public SafFileSpaceConfig(String id, String displayName, Uri treeUri) {
        if (id == null || id.trim().isEmpty()) throw new IllegalArgumentException("id is empty");
        if (displayName == null || displayName.trim().isEmpty()) throw new IllegalArgumentException("displayName is empty");
        if (treeUri == null) throw new IllegalArgumentException("treeUri == null");
        this.id = id;
        this.displayName = displayName;
        this.treeUri = treeUri;
    }

    public String getId() {
        return id;
    }

    public String getDisplayName() {
        return displayName;
    }

    public Uri getTreeUri() {
        return treeUri;
    }
}
