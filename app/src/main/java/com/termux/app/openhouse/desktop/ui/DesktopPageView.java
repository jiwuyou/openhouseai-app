package com.termux.app.openhouse.desktop.ui;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.DragEvent;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

final class DesktopPageView extends FrameLayout {

    interface Callback extends DesktopAppTileView.Callback {
        void onMove(String draggedId, int targetSlot);
        void onDragLocation(float rawX);
        void onBlankLongPress();
    }

    private final LinearLayout grid;
    private final List<DesktopUiEntry> slotEntries = new ArrayList<>();
    private int pageIndex;
    private int baseSlot;
    private int columns = 3;
    private int rows = 4;
    private boolean editMode;
    private Callback callback;

    DesktopPageView(Context context) {
        super(context);
        setPadding(dp(10), dp(8), dp(10), dp(8));
        setLongClickable(true);
        setOnLongClickListener(v -> {
            if (callback != null) {
                callback.onBlankLongPress();
                return true;
            }
            return false;
        });
        setOnDragListener((v, event) -> handleDropTargetDrag(v, event, baseSlot));

        grid = new LinearLayout(context);
        grid.setOrientation(LinearLayout.VERTICAL);
        grid.setGravity(Gravity.CENTER);
        addView(grid, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
    }

    void bind(
        List<DesktopUiEntry> slotEntries,
        int pageIndex,
        int baseSlot,
        int columns,
        int rows,
        boolean editMode,
        Callback callback
    ) {
        this.slotEntries.clear();
        int pageSize = Math.max(1, columns) * Math.max(1, rows);
        for (int i = 0; i < pageSize; i++) {
            DesktopUiEntry entry = slotEntries != null && i < slotEntries.size() ? slotEntries.get(i) : null;
            this.slotEntries.add(entry);
        }
        this.pageIndex = Math.max(0, pageIndex);
        this.baseSlot = Math.max(0, baseSlot);
        this.columns = Math.max(1, columns);
        this.rows = Math.max(1, rows);
        this.editMode = editMode;
        this.callback = callback;
        render();
    }

    private void render() {
        grid.removeAllViews();
        int slotInPage = 0;
        for (int rowIndex = 0; rowIndex < rows; rowIndex++) {
            LinearLayout row = new LinearLayout(getContext());
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f);
            if (rowIndex > 0) {
                rowParams.setMargins(0, dp(10), 0, 0);
            }
            grid.addView(row, rowParams);

            for (int columnIndex = 0; columnIndex < columns; columnIndex++) {
                DesktopUiEntry entry = slotInPage < slotEntries.size() ? slotEntries.get(slotInPage) : null;
                int absoluteSlot = baseSlot + slotInPage;
                View child = entry == null
                    ? createBlankSlot(absoluteSlot)
                    : createTile(entry, absoluteSlot);
                LinearLayout.LayoutParams childParams = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f);
                childParams.setMargins(dp(7), 0, dp(7), 0);
                row.addView(child, childParams);
                slotInPage++;
            }
        }
    }

    private View createTile(DesktopUiEntry entry, int absoluteSlot) {
        DesktopAppTileView tile = new DesktopAppTileView(getContext());
        tile.bind(entry, absoluteSlot, editMode, callback);
        tile.setOnDragListener((v, event) -> handleDropTargetDrag(v, event, absoluteSlot));
        return tile;
    }

    private View createBlankSlot(int absoluteSlot) {
        FrameLayout blank = new FrameLayout(getContext());
        blank.setMinimumHeight(dp(108));
        blank.setLongClickable(true);
        blank.setAlpha(editMode ? 1f : 0f);
        blank.setBackground(editMode ? createBlankSlotBackground(false) : null);
        blank.setOnLongClickListener(v -> {
            if (callback != null) {
                callback.onBlankLongPress();
                return true;
            }
            return false;
        });
        blank.setOnDragListener((v, event) -> handleDropTargetDrag(v, event, absoluteSlot));
        return blank;
    }

    private boolean handleDropTargetDrag(View target, DragEvent event, int targetSlot) {
        if (event == null || !(event.getLocalState() instanceof DesktopDragPayload)) {
            return false;
        }
        if (!editMode) {
            return event.getAction() == DragEvent.ACTION_DRAG_STARTED;
        }
        switch (event.getAction()) {
            case DragEvent.ACTION_DRAG_STARTED:
                return true;
            case DragEvent.ACTION_DRAG_LOCATION:
                if (callback != null) {
                    callback.onDragLocation(rawX(target, event));
                }
                return true;
            case DragEvent.ACTION_DRAG_ENTERED:
                setDragHighlight(target, true);
                return true;
            case DragEvent.ACTION_DRAG_EXITED:
                setDragHighlight(target, false);
                return true;
            case DragEvent.ACTION_DROP:
                setDragHighlight(target, false);
                DesktopDragPayload payload = (DesktopDragPayload) event.getLocalState();
                if (callback != null) {
                    callback.onMove(payload.entryId, targetSlot);
                }
                return true;
            case DragEvent.ACTION_DRAG_ENDED:
                setDragHighlight(target, false);
                if (callback != null) {
                    callback.onDragEnded();
                }
                return true;
            default:
                return true;
        }
    }

    private float rawX(View target, DragEvent event) {
        if (target == null || event == null) {
            return 0f;
        }
        int[] location = new int[2];
        target.getLocationOnScreen(location);
        return location[0] + event.getX();
    }

    private void setDragHighlight(View target, boolean highlighted) {
        if (target == null) {
            return;
        }
        float scale = highlighted ? 1.045f : 1f;
        target.setScaleX(scale);
        target.setScaleY(scale);
        if (target instanceof FrameLayout && !(target instanceof DesktopAppTileView)) {
            target.setBackground(editMode ? createBlankSlotBackground(highlighted) : null);
        }
    }

    private GradientDrawable createBlankSlotBackground(boolean highlighted) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setShape(GradientDrawable.RECTANGLE);
        drawable.setCornerRadius(dp(12));
        drawable.setColor(highlighted ? Color.argb(36, 80, 80, 80) : Color.argb(14, 80, 80, 80));
        drawable.setStroke(dp(1), highlighted ? Color.argb(150, 80, 80, 80) : Color.argb(70, 80, 80, 80));
        return drawable;
    }

    List<DesktopUiEntry> getPageEntries() {
        List<DesktopUiEntry> entries = new ArrayList<>();
        for (DesktopUiEntry entry : slotEntries) {
            if (entry != null) {
                entries.add(entry);
            }
        }
        return Collections.unmodifiableList(entries);
    }

    int getPageIndex() {
        return pageIndex;
    }

    private int dp(int value) {
        float density = getResources().getDisplayMetrics().density;
        return Math.round(value * density);
    }
}
