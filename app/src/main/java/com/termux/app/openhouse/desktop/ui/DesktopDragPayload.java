package com.termux.app.openhouse.desktop.ui;

final class DesktopDragPayload {

    final String entryId;
    final int fromIndex;

    DesktopDragPayload(String entryId, int fromIndex) {
        this.entryId = DesktopUiEntry.safeTrim(entryId);
        this.fromIndex = fromIndex;
    }
}
