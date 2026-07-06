package com.termux.app.openhouse.desktop.ui;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.viewpager.widget.PagerAdapter;
import androidx.viewpager.widget.ViewPager;

import com.termux.R;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class OpenHouseDesktopView extends LinearLayout {

    public interface Callbacks {
        default void onOpen(DesktopUiEntry entry) {}
        default void onEdit(DesktopUiEntry entry) {}
        default void onReorder(List<DesktopUiEntry> orderedEntries, DesktopUiEntry movedEntry, int fromPosition, int toPosition) {}
        default void onPageChanged(int pageIndex, int pageCount) {}
        default void onBlankLongPress() {}
        default void onEditModeChanged(boolean editMode) {}
    }

    private final DesktopViewPager pager;
    private final DesktopPagerAdapter adapter;
    private final TextView pageIndicator;
    private final List<DesktopUiEntry> entries = new ArrayList<>();
    private Callbacks callbacks = new Callbacks() {};
    private int columns = 3;
    private int rows = 4;
    private boolean editMode;

    public OpenHouseDesktopView(Context context) {
        this(context, null);
    }

    public OpenHouseDesktopView(Context context, AttributeSet attrs) {
        super(context, attrs);
        setOrientation(VERTICAL);
        setClipToPadding(false);

        pager = new DesktopViewPager(context);
        pager.setOffscreenPageLimit(1);
        adapter = new DesktopPagerAdapter();
        pager.setAdapter(adapter);
        pager.addOnPageChangeListener(new ViewPager.SimpleOnPageChangeListener() {
            @Override
            public void onPageSelected(int position) {
                updatePageIndicator();
                callbacks.onPageChanged(position, getPageCount());
            }
        });
        addView(pager, new LayoutParams(LayoutParams.MATCH_PARENT, calculatePagerHeight()));

        pageIndicator = new TextView(context);
        pageIndicator.setGravity(android.view.Gravity.CENTER);
        pageIndicator.setTextColor(ContextCompat.getColor(context, R.color.textSecondary));
        pageIndicator.setTextSize(12);
        LayoutParams indicatorParams = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        indicatorParams.setMargins(0, dp(6), 0, 0);
        addView(pageIndicator, indicatorParams);
        updatePageIndicator();
    }

    public void setCallbacks(Callbacks callbacks) {
        this.callbacks = callbacks == null ? new Callbacks() {} : callbacks;
    }

    public void setEntries(List<DesktopUiEntry> newEntries) {
        entries.clear();
        if (newEntries != null) {
            for (DesktopUiEntry entry : newEntries) {
                if (entry != null) {
                    entries.add(entry);
                }
            }
        }
        int page = Math.min(pager.getCurrentItem(), Math.max(0, getPageCount() - 1));
        adapter.notifyDataSetChanged();
        pager.setCurrentItem(page, false);
        updatePageIndicator();
        callbacks.onPageChanged(pager.getCurrentItem(), getPageCount());
    }

    public List<DesktopUiEntry> getEntries() {
        return Collections.unmodifiableList(new ArrayList<>(entries));
    }

    public void setEditMode(boolean editMode) {
        if (this.editMode == editMode) {
            return;
        }
        this.editMode = editMode;
        adapter.notifyDataSetChanged();
        callbacks.onEditModeChanged(editMode);
    }

    public boolean isEditMode() {
        return editMode;
    }

    public void setGridSize(int columns, int rows) {
        this.columns = Math.max(1, columns);
        this.rows = Math.max(1, rows);
        ViewGroup.LayoutParams params = pager.getLayoutParams();
        params.height = calculatePagerHeight();
        pager.setLayoutParams(params);
        adapter.notifyDataSetChanged();
        updatePageIndicator();
    }

    public int getCurrentPage() {
        return pager.getCurrentItem();
    }

    public void setCurrentPage(int page, boolean smoothScroll) {
        int target = Math.max(0, Math.min(page, getPageCount() - 1));
        pager.setCurrentItem(target, smoothScroll);
        updatePageIndicator();
    }

    public int getPageCount() {
        int pageSize = getPageSize();
        if (entries.isEmpty()) {
            return 1;
        }
        return (entries.size() + pageSize - 1) / pageSize;
    }

    private int getPageSize() {
        return Math.max(1, columns * rows);
    }

    private int calculatePagerHeight() {
        return dp(16) + rows * dp(108) + Math.max(0, rows - 1) * dp(4);
    }

    private void requestEditModeFromGesture() {
        if (!editMode) {
            setEditMode(true);
        }
    }

    private void moveEntry(String draggedId, int targetIndex) {
        if (draggedId == null || entries.isEmpty()) {
            return;
        }
        int from = findEntryIndex(draggedId);
        if (from < 0) {
            return;
        }
        int insertionIndex = Math.max(0, Math.min(targetIndex, entries.size()));
        DesktopUiEntry moved = entries.remove(from);
        if (insertionIndex > from) {
            insertionIndex--;
        }
        insertionIndex = Math.max(0, Math.min(insertionIndex, entries.size()));
        entries.add(insertionIndex, moved);
        adapter.notifyDataSetChanged();
        callbacks.onReorder(Collections.unmodifiableList(new ArrayList<>(entries)), moved, from, insertionIndex);
    }

    private int findEntryIndex(String id) {
        String normalized = DesktopUiEntry.safeTrim(id);
        for (int i = 0; i < entries.size(); i++) {
            if (normalized.equals(entries.get(i).id)) {
                return i;
            }
        }
        return -1;
    }

    private void updatePageIndicator() {
        int pageCount = getPageCount();
        pageIndicator.setText((pager.getCurrentItem() + 1) + " / " + pageCount);
        pageIndicator.setVisibility(pageCount > 1 ? View.VISIBLE : View.INVISIBLE);
    }

    private int dp(int value) {
        float density = getResources().getDisplayMetrics().density;
        return Math.round(value * density);
    }

    private final class DesktopPagerAdapter extends PagerAdapter {

        @Override
        public int getCount() {
            return getPageCount();
        }

        @Override
        public int getItemPosition(@NonNull Object object) {
            return POSITION_NONE;
        }

        @Override
        public boolean isViewFromObject(@NonNull View view, @NonNull Object object) {
            return view == object;
        }

        @NonNull
        @Override
        public Object instantiateItem(@NonNull ViewGroup container, int position) {
            int pageSize = getPageSize();
            int start = position * pageSize;
            int end = Math.min(entries.size(), start + pageSize);
            List<DesktopUiEntry> pageEntries = start < end
                ? new ArrayList<>(entries.subList(start, end))
                : Collections.emptyList();
            DesktopPageView page = new DesktopPageView(container.getContext());
            page.bind(pageEntries, start, columns, rows, editMode, new DesktopPageView.Callback() {
                @Override
                public void onOpen(DesktopUiEntry entry) {
                    callbacks.onOpen(entry);
                }

                @Override
                public void onEdit(DesktopUiEntry entry) {
                    callbacks.onEdit(entry);
                }

                @Override
                public void onRequestEditMode(DesktopUiEntry entry) {
                    requestEditModeFromGesture();
                }

                @Override
                public void onDragStarted(DesktopDragPayload payload) {
                    pager.setDragInProgress(true);
                }

                @Override
                public void onDragEnded() {
                    pager.setDragInProgress(false);
                }

                @Override
                public void onMove(String draggedId, int targetIndex) {
                    moveEntry(draggedId, targetIndex);
                }

                @Override
                public void onBlankLongPress() {
                    requestEditModeFromGesture();
                    callbacks.onBlankLongPress();
                }
            });
            container.addView(page);
            return page;
        }

        @Override
        public void destroyItem(@NonNull ViewGroup container, int position, @NonNull Object object) {
            container.removeView((View) object);
        }
    }

    private static final class DesktopViewPager extends ViewPager {

        private boolean dragInProgress;

        DesktopViewPager(Context context) {
            super(context);
        }

        void setDragInProgress(boolean dragInProgress) {
            this.dragInProgress = dragInProgress;
            requestDisallowInterceptTouchEvent(dragInProgress);
        }

        @Override
        public boolean onInterceptTouchEvent(android.view.MotionEvent ev) {
            return !dragInProgress && super.onInterceptTouchEvent(ev);
        }

        @Override
        public boolean onTouchEvent(android.view.MotionEvent ev) {
            return !dragInProgress && super.onTouchEvent(ev);
        }
    }
}
