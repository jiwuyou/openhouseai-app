package com.termux.app.openhouse.desktop.ui;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
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
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class OpenHouseDesktopView extends LinearLayout {

    public interface EntryViewReadyCallback {
        void onEntryViewReady(View entryView);
    }

    public interface Callbacks {
        default void onOpen(DesktopUiEntry entry) {}
        default void onEdit(DesktopUiEntry entry) {}
        default void onReorder(List<DesktopUiEntry> orderedEntries, DesktopUiEntry movedEntry, int fromPosition, int toPosition) {}
        default void onMoveToSlot(DesktopUiEntry movedEntry, int fromSlot, int toSlot, boolean targetOccupied, boolean createsNewPage) {}
        default void onPageChanged(int pageIndex, int pageCount) {}
        default void onBlankLongPress() {}
        default void onEditModeChanged(boolean editMode) {}
    }

    private static final int EDGE_NONE = 0;
    private static final int EDGE_LEFT = -1;
    private static final int EDGE_RIGHT = 1;
    private static final long AUTO_PAGE_DELAY_MS = 520L;

    private final DesktopViewPager pager;
    private final DesktopPagerAdapter adapter;
    private final TextView pageIndicator;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable autoPageRunnable = new Runnable() {
        @Override
        public void run() {
            runAutoPageStep();
        }
    };
    private final List<DesktopUiEntry> entries = new ArrayList<>();
    private Callbacks callbacks = new Callbacks() {};
    private int columns = 3;
    private int rows = 4;
    private boolean editMode;
    private boolean dragInProgress;
    private int pendingAutoPageDirection = EDGE_NONE;

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
        indicatorParams.setMargins(0, dp(8), 0, 0);
        addView(pageIndicator, indicatorParams);
        updatePageIndicator();
    }

    public void setCallbacks(Callbacks callbacks) {
        this.callbacks = callbacks == null ? new Callbacks() {} : callbacks;
    }

    public void setEntries(List<DesktopUiEntry> newEntries) {
        entries.clear();
        entries.addAll(normalizeEntries(newEntries));
        int page = Math.min(pager.getCurrentItem(), Math.max(0, getPageCount() - 1));
        adapter.notifyDataSetChanged();
        pager.setCurrentItem(page, false);
        updatePageIndicator();
        callbacks.onPageChanged(pager.getCurrentItem(), getPageCount());
    }

    public List<DesktopUiEntry> getEntries() {
        return Collections.unmodifiableList(snapshotEntriesBySlot());
    }

    public void setEditMode(boolean editMode) {
        if (this.editMode == editMode) {
            return;
        }
        this.editMode = editMode;
        cancelAutoPaging();
        adapter.notifyDataSetChanged();
        clampCurrentPage();
        updatePageIndicator();
        callbacks.onEditModeChanged(editMode);
    }

    public boolean isEditMode() {
        return editMode;
    }

    public void setGridSize(int columns, int rows) {
        this.columns = Math.max(1, columns);
        this.rows = Math.max(1, rows);
        List<DesktopUiEntry> currentEntries = new ArrayList<>(entries);
        entries.clear();
        entries.addAll(normalizeEntries(currentEntries));
        ViewGroup.LayoutParams params = pager.getLayoutParams();
        params.height = calculatePagerHeight();
        pager.setLayoutParams(params);
        adapter.notifyDataSetChanged();
        clampCurrentPage();
        updatePageIndicator();
    }

    public int getCurrentPage() {
        return pager.getCurrentItem();
    }

    /**
     * Makes the page containing {@code entryId} visible and reports the laid-out tile view.
     * Tutorial callers can then use {@link #findEntryView(String)} as a stable target supplier.
     */
    public boolean revealEntry(String entryId, EntryViewReadyCallback callback) {
        String normalizedId = DesktopUiEntry.safeTrim(entryId);
        DesktopUiEntry entry = findEntry(normalizedId);
        if (entry == null) {
            return false;
        }
        setCurrentPage(entry.slotIndex / getPageSize(), false);
        dispatchEntryViewWhenReady(normalizedId, callback);
        return true;
    }

    public View findEntryView(String entryId) {
        String normalizedId = DesktopUiEntry.safeTrim(entryId);
        if (normalizedId.isEmpty()) {
            return null;
        }
        return findViewWithTag(DesktopAppTileView.entryTag(normalizedId));
    }

    public void setCurrentPage(int page, boolean smoothScroll) {
        int target = Math.max(0, Math.min(page, getPageCount() - 1));
        pager.setCurrentItem(target, smoothScroll);
        updatePageIndicator();
    }

    public int getPageCount() {
        int actualCount = getActualPageCount();
        if (editMode && !entries.isEmpty()) {
            return actualCount + 1;
        }
        return actualCount;
    }

    private int getActualPageCount() {
        int pageSize = getPageSize();
        int maxSlot = -1;
        for (DesktopUiEntry entry : entries) {
            if (entry != null && entry.slotIndex > maxSlot) {
                maxSlot = entry.slotIndex;
            }
        }
        if (maxSlot < 0) {
            return 1;
        }
        return (maxSlot / pageSize) + 1;
    }

    private int getPageSize() {
        return Math.max(1, columns * rows);
    }

    private int calculatePagerHeight() {
        return dp(20) + rows * dp(116) + Math.max(0, rows - 1) * dp(10);
    }

    private void requestEditModeFromGesture() {
        if (!editMode) {
            setEditMode(true);
        }
    }

    private void moveEntryToSlot(String draggedId, int targetSlot) {
        String normalizedId = DesktopUiEntry.safeTrim(draggedId);
        if (normalizedId.isEmpty() || entries.isEmpty()) {
            return;
        }
        int fromIndex = findEntryIndex(normalizedId);
        if (fromIndex < 0) {
            return;
        }
        int safeTargetSlot = Math.max(0, targetSlot);
        DesktopUiEntry moved = entries.get(fromIndex);
        int fromSlot = moved.slotIndex;
        if (fromSlot == safeTargetSlot) {
            return;
        }

        int actualPageCountBeforeMove = getActualPageCount();
        List<DesktopUiEntry> updated = new ArrayList<>();
        boolean targetOccupied = false;
        for (DesktopUiEntry entry : entries) {
            if (entry == null || normalizedId.equals(entry.id)) {
                continue;
            }
            if (entry.slotIndex == safeTargetSlot) {
                targetOccupied = true;
                updated.add(entry.withSlotIndex(fromSlot));
            } else {
                updated.add(entry);
            }
        }

        DesktopUiEntry movedToTarget = moved.withSlotIndex(safeTargetSlot);
        updated.add(movedToTarget);
        entries.clear();
        entries.addAll(normalizeEntries(updated));
        adapter.notifyDataSetChanged();
        setCurrentPage(safeTargetSlot / getPageSize(), false);

        boolean createsNewPage = safeTargetSlot >= actualPageCountBeforeMove * getPageSize();
        callbacks.onMoveToSlot(movedToTarget, fromSlot, safeTargetSlot, targetOccupied, createsNewPage);
        callbacks.onReorder(Collections.unmodifiableList(snapshotEntriesBySlot()), movedToTarget, fromSlot, safeTargetSlot);
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

    private DesktopUiEntry findEntry(String id) {
        int index = findEntryIndex(id);
        return index < 0 ? null : entries.get(index);
    }

    private void dispatchEntryViewWhenReady(String entryId, EntryViewReadyCallback callback) {
        if (callback == null) {
            return;
        }
        pager.post(() -> {
            View entryView = findEntryView(entryId);
            if (isReadyTarget(entryView)) {
                callback.onEntryViewReady(entryView);
                return;
            }
            getViewTreeObserver().addOnGlobalLayoutListener(new android.view.ViewTreeObserver.OnGlobalLayoutListener() {
                @Override
                public void onGlobalLayout() {
                    View laidOutEntry = findEntryView(entryId);
                    if (!isReadyTarget(laidOutEntry)) {
                        return;
                    }
                    if (getViewTreeObserver().isAlive()) {
                        getViewTreeObserver().removeOnGlobalLayoutListener(this);
                    }
                    callback.onEntryViewReady(laidOutEntry);
                }
            });
        });
    }

    private boolean isReadyTarget(View view) {
        return view != null && view.getWidth() > 0 && view.getHeight() > 0 && view.isShown();
    }

    private List<DesktopUiEntry> normalizeEntries(List<DesktopUiEntry> source) {
        if (source == null || source.isEmpty()) {
            return Collections.emptyList();
        }

        List<EntrySeed> positionedSeeds = new ArrayList<>();
        List<EntrySeed> implicitSeeds = new ArrayList<>();
        int inputIndex = 0;
        for (DesktopUiEntry entry : source) {
            if (entry == null || DesktopUiEntry.safeTrim(entry.id).isEmpty()) {
                inputIndex++;
                continue;
            }
            EntrySeed seed = new EntrySeed(entry, inputIndex);
            if (entry.hasExplicitSlotIndex() || entry.hasExplicitOrder()) {
                positionedSeeds.add(seed);
            } else {
                implicitSeeds.add(seed);
            }
            inputIndex++;
        }

        Collections.sort(positionedSeeds, (left, right) -> {
            int slotCompare = Integer.compare(left.entry.requestedSlotIndex(left.inputIndex), right.entry.requestedSlotIndex(right.inputIndex));
            return slotCompare != 0 ? slotCompare : Integer.compare(left.inputIndex, right.inputIndex);
        });
        Collections.sort(implicitSeeds, (left, right) -> {
            int leftOrder = left.entry.hasExplicitOrder() ? left.entry.order : left.inputIndex;
            int rightOrder = right.entry.hasExplicitOrder() ? right.entry.order : right.inputIndex;
            int orderCompare = Integer.compare(leftOrder, rightOrder);
            return orderCompare != 0 ? orderCompare : Integer.compare(left.inputIndex, right.inputIndex);
        });

        List<DesktopUiEntry> placed = new ArrayList<>();
        Set<Integer> usedSlots = new HashSet<>();
        List<EntrySeed> overflow = new ArrayList<>();
        for (EntrySeed seed : positionedSeeds) {
            int slot = seed.entry.requestedSlotIndex(seed.inputIndex);
            if (usedSlots.add(slot)) {
                placed.add(seed.entry.withSlotIndex(slot));
            } else {
                overflow.add(seed);
            }
        }
        overflow.addAll(implicitSeeds);
        int nextFreeSlot = 0;
        for (EntrySeed seed : overflow) {
            while (usedSlots.contains(nextFreeSlot)) {
                nextFreeSlot++;
            }
            usedSlots.add(nextFreeSlot);
            placed.add(seed.entry.withSlotIndex(nextFreeSlot));
            nextFreeSlot++;
        }
        Collections.sort(placed, (left, right) -> Integer.compare(left.slotIndex, right.slotIndex));
        return placed;
    }

    private List<DesktopUiEntry> snapshotEntriesBySlot() {
        List<DesktopUiEntry> snapshot = new ArrayList<>(entries);
        Collections.sort(snapshot, (left, right) -> Integer.compare(left.slotIndex, right.slotIndex));
        return snapshot;
    }

    private List<DesktopUiEntry> buildPageSlots(int pageIndex) {
        int pageSize = getPageSize();
        int baseSlot = Math.max(0, pageIndex) * pageSize;
        List<DesktopUiEntry> slots = new ArrayList<>(Collections.nCopies(pageSize, null));
        for (DesktopUiEntry entry : entries) {
            if (entry == null) {
                continue;
            }
            int offset = entry.slotIndex - baseSlot;
            if (offset >= 0 && offset < pageSize) {
                slots.set(offset, entry);
            }
        }
        return slots;
    }

    private void handleDragStarted() {
        dragInProgress = true;
        pager.setDragInProgress(true);
    }

    private void handleDragEnded() {
        dragInProgress = false;
        pager.setDragInProgress(false);
        cancelAutoPaging();
    }

    private void handleDragLocation(float rawX) {
        if (!editMode || !dragInProgress || pager.getWidth() <= 0) {
            cancelAutoPaging();
            return;
        }
        int[] pagerLocation = new int[2];
        pager.getLocationOnScreen(pagerLocation);
        int edgeSize = dp(54);
        int pagerLeft = pagerLocation[0];
        int pagerRight = pagerLeft + pager.getWidth();
        if (rawX <= pagerLeft + edgeSize) {
            scheduleAutoPaging(EDGE_LEFT);
        } else if (rawX >= pagerRight - edgeSize) {
            scheduleAutoPaging(EDGE_RIGHT);
        } else {
            cancelAutoPaging();
        }
    }

    private void scheduleAutoPaging(int direction) {
        if (direction == EDGE_NONE) {
            cancelAutoPaging();
            return;
        }
        if (pendingAutoPageDirection == direction) {
            return;
        }
        cancelAutoPaging();
        pendingAutoPageDirection = direction;
        handler.postDelayed(autoPageRunnable, AUTO_PAGE_DELAY_MS);
    }

    private void runAutoPageStep() {
        int direction = pendingAutoPageDirection;
        if (direction == EDGE_NONE || !dragInProgress) {
            cancelAutoPaging();
            return;
        }
        int current = pager.getCurrentItem();
        int target = current + direction;
        if (target >= 0 && target < getPageCount()) {
            pager.setCurrentItem(target, true);
            handler.postDelayed(autoPageRunnable, AUTO_PAGE_DELAY_MS);
        } else {
            cancelAutoPaging();
        }
    }

    private void cancelAutoPaging() {
        pendingAutoPageDirection = EDGE_NONE;
        handler.removeCallbacks(autoPageRunnable);
    }

    private void clampCurrentPage() {
        int target = Math.max(0, Math.min(pager.getCurrentItem(), getPageCount() - 1));
        if (target != pager.getCurrentItem()) {
            pager.setCurrentItem(target, false);
        }
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

    private static final class EntrySeed {
        final DesktopUiEntry entry;
        final int inputIndex;

        EntrySeed(DesktopUiEntry entry, int inputIndex) {
            this.entry = entry;
            this.inputIndex = inputIndex;
        }
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
            int baseSlot = position * pageSize;
            DesktopPageView page = new DesktopPageView(container.getContext());
            page.bind(buildPageSlots(position), position, baseSlot, columns, rows, editMode, new DesktopPageView.Callback() {
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
                    handleDragStarted();
                }

                @Override
                public void onDragEnded() {
                    handleDragEnded();
                }

                @Override
                public void onDragLocation(float rawX) {
                    handleDragLocation(rawX);
                }

                @Override
                public void onMove(String draggedId, int targetSlot) {
                    moveEntryToSlot(draggedId, targetSlot);
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
