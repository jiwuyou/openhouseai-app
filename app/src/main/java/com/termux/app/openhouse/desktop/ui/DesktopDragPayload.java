package com.termux.app.openhouse.desktop.ui;

final class DesktopDragPayload {

    final String entryId;
    final int fromIndex;
    final int fromSlot;

    DesktopDragPayload(String entryId, int fromSlot) {
        this.entryId = DesktopUiEntry.safeTrim(entryId);
        this.fromSlot = fromSlot;
        this.fromIndex = fromSlot;
    }
}
