package com.termux.app.openhouse.desktop.ui;

import android.content.Context;
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
        void onMove(String draggedId, int targetIndex);
        void onBlankLongPress();
    }

    private final LinearLayout grid;
    private final List<DesktopUiEntry> pageEntries = new ArrayList<>();
    private int baseIndex;
    private int columns = 3;
    private int rows = 4;
    private boolean editMode;
    private Callback callback;

    DesktopPageView(Context context) {
        super(context);
        setPadding(dp(4), dp(4), dp(4), dp(4));
        setLongClickable(true);
        setOnLongClickListener(v -> {
            if (callback != null) {
                callback.onBlankLongPress();
                return true;
            }
            return false;
        });
        setOnDragListener((v, event) -> handleDropTargetDrag(v, event, baseIndex + pageEntries.size()));

        grid = new LinearLayout(context);
        grid.setOrientation(LinearLayout.VERTICAL);
        grid.setGravity(Gravity.CENTER);
        addView(grid, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
    }

    void bind(
        List<DesktopUiEntry> entries,
        int baseIndex,
        int columns,
        int rows,
        boolean editMode,
        Callback callback
    ) {
        this.pageEntries.clear();
        if (entries != null) {
            this.pageEntries.addAll(entries);
        }
        this.baseIndex = Math.max(0, baseIndex);
        this.columns = Math.max(1, columns);
        this.rows = Math.max(1, rows);
        this.editMode = editMode;
        this.callback = callback;
        render();
    }

    private void render() {
        grid.removeAllViews();
        int slot = 0;
        int pageSize = columns * rows;
        for (int rowIndex = 0; rowIndex < rows; rowIndex++) {
            LinearLayout row = new LinearLayout(getContext());
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f);
            if (rowIndex > 0) {
                rowParams.setMargins(0, dp(4), 0, 0);
            }
            grid.addView(row, rowParams);

            for (int columnIndex = 0; columnIndex < columns; columnIndex++) {
                View child;
                if (slot < pageEntries.size() && slot < pageSize) {
                    child = createTile(pageEntries.get(slot), baseIndex + slot);
                } else {
                    child = createBlankSlot(baseIndex + slot);
                }
                LinearLayout.LayoutParams childParams = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f);
                childParams.setMargins(dp(3), 0, dp(3), 0);
                row.addView(child, childParams);
                slot++;
            }
        }
    }

    private View createTile(DesktopUiEntry entry, int absoluteIndex) {
        DesktopAppTileView tile = new DesktopAppTileView(getContext());
        tile.bind(entry, absoluteIndex, editMode, callback);
        tile.setOnDragListener((v, event) -> handleDropTargetDrag(v, event, absoluteIndex));
        return tile;
    }

    private View createBlankSlot(int absoluteIndex) {
        FrameLayout blank = new FrameLayout(getContext());
        blank.setMinimumHeight(dp(96));
        blank.setLongClickable(true);
        blank.setOnLongClickListener(v -> {
            if (callback != null) {
                callback.onBlankLongPress();
                return true;
            }
            return false;
        });
        blank.setOnDragListener((v, event) -> handleDropTargetDrag(v, event, absoluteIndex));
        return blank;
    }

    private boolean handleDropTargetDrag(View target, DragEvent event, int targetIndex) {
        if (!editMode || event == null || !(event.getLocalState() instanceof DesktopDragPayload)) {
            return false;
        }
        switch (event.getAction()) {
            case DragEvent.ACTION_DRAG_STARTED:
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
                    callback.onMove(payload.entryId, targetIndex);
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

    private void setDragHighlight(View target, boolean highlighted) {
        if (target != null) {
            float scale = highlighted ? 1.035f : 1f;
            target.setScaleX(scale);
            target.setScaleY(scale);
        }
    }

    List<DesktopUiEntry> getPageEntries() {
        return Collections.unmodifiableList(pageEntries);
    }

    private int dp(int value) {
        float density = getResources().getDisplayMetrics().density;
        return Math.round(value * density);
    }
}
