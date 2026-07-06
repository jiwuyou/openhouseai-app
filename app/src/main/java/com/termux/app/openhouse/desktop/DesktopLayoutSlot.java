package com.termux.app.openhouse.desktop;

public final class DesktopLayoutSlot {

    public final int slotIndex;
    public final int pageIndex;
    public final int indexInPage;
    public final DesktopLayoutEntry entry;

    DesktopLayoutSlot(int slotIndex, int pageSize, DesktopLayoutEntry entry) {
        int safeSlot = Math.max(0, slotIndex);
        int safePageSize = pageSize <= 0 ? DesktopLayoutState.DEFAULT_PAGE_SIZE : pageSize;
        this.slotIndex = safeSlot;
        this.pageIndex = safeSlot / safePageSize;
        this.indexInPage = safeSlot % safePageSize;
        this.entry = entry;
    }

    public boolean isOccupied() {
        return entry != null;
    }
}
