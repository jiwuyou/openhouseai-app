package com.termux.app.openhouse.desktop.ui;

import android.content.ClipData;
import android.content.Context;
import android.os.Build;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

import com.termux.R;

final class DesktopAppTileView extends LinearLayout {

    interface Callback {
        void onOpen(DesktopUiEntry entry);
        void onEdit(DesktopUiEntry entry);
        void onRequestEditMode(DesktopUiEntry entry);
        void onDragStarted(DesktopDragPayload payload);
        void onDragEnded();
    }

    private final TextView iconView;
    private final TextView nameView;
    private DesktopUiEntry entry;
    private boolean editMode;
    private int absoluteIndex;
    private Callback callback;

    DesktopAppTileView(Context context) {
        super(context);
        setOrientation(VERTICAL);
        setGravity(Gravity.CENTER);
        setPadding(dp(6), dp(8), dp(6), dp(7));
        setBackgroundResource(R.drawable.one_click_item_bg);
        setClickable(true);
        setFocusable(true);
        setMinimumHeight(dp(104));

        iconView = new TextView(context);
        iconView.setGravity(Gravity.CENTER);
        iconView.setTextColor(ContextCompat.getColor(context, R.color.textPrimary));
        iconView.setTypeface(iconView.getTypeface(), android.graphics.Typeface.BOLD);
        iconView.setBackgroundResource(R.drawable.panel_bg);
        LayoutParams iconParams = new LayoutParams(dp(48), dp(48));
        addView(iconView, iconParams);

        nameView = new TextView(context);
        nameView.setGravity(Gravity.CENTER);
        nameView.setTextColor(ContextCompat.getColor(context, R.color.textPrimary));
        nameView.setTextSize(12);
        nameView.setMaxLines(2);
        LayoutParams nameParams = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        nameParams.setMargins(0, dp(7), 0, 0);
        addView(nameView, nameParams);

        setOnClickListener(v -> {
            if (entry == null || callback == null) {
                return;
            }
            if (editMode) {
                callback.onEdit(entry);
            } else {
                callback.onOpen(entry);
            }
        });

        setOnLongClickListener(v -> {
            if (entry == null || callback == null) {
                return false;
            }
            if (!editMode) {
                callback.onRequestEditMode(entry);
                return true;
            }
            return startTileDrag();
        });
    }

    void bind(DesktopUiEntry entry, int absoluteIndex, boolean editMode, Callback callback) {
        this.entry = entry;
        this.absoluteIndex = absoluteIndex;
        this.editMode = editMode;
        this.callback = callback;

        String iconLabel = entry == null ? "App" : entry.displayIconLabel();
        iconView.setText(iconLabel);
        iconView.setTextSize(iconLabel.codePointCount(0, iconLabel.length()) > 1 ? 19 : 24);
        nameView.setText(entry == null ? "App" : entry.displayTitle());
        setEnabled(entry == null || entry.enabled);
        setAlpha(entry != null && !entry.enabled ? 0.55f : 1f);
        setScaleX(1f);
        setScaleY(1f);
    }

    private boolean startTileDrag() {
        DesktopDragPayload payload = new DesktopDragPayload(entry.id, absoluteIndex);
        ClipData data = ClipData.newPlainText("openhouse-desktop-entry", entry.id);
        DragShadowBuilder shadow = new DragShadowBuilder(this);
        boolean started;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            started = startDragAndDrop(data, shadow, payload, 0);
        } else {
            started = startDrag(data, shadow, payload, 0);
        }
        if (started) {
            callback.onDragStarted(payload);
        } else {
            callback.onDragEnded();
        }
        return started;
    }

    private int dp(int value) {
        float density = getResources().getDisplayMetrics().density;
        return Math.round(value * density);
    }
}
