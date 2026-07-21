package com.wuxianpi.openhouse.feature.desktop.ui

import android.content.ClipData
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.DragEvent
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.viewpager.widget.PagerAdapter
import androidx.viewpager.widget.ViewPager
import com.wuxianpi.openhouse.feature.R
import com.wuxianpi.openhouse.feature.desktop.DesktopLayoutEntry
import java.util.Collections

data class DesktopUiEntry(
    val id: String,
    val title: String,
    val subtitle: String,
    val iconLabel: String,
    val iconKey: String,
    val slotIndex: Int,
    val enabled: Boolean,
) {
    fun displayTitle(): String = title.ifBlank { id }

    fun displayIconLabel(): String {
        val text = iconLabel.ifBlank { iconKey.ifBlank { displayTitle() } }
        return if (text.codePointCount(0, text.length) <= 2) text
        else text.substring(0, text.offsetByCodePoints(0, 1))
    }

    companion object {
        fun from(entry: DesktopLayoutEntry) = DesktopUiEntry(
            id = entry.id,
            title = entry.title,
            subtitle = entry.subtitle,
            iconLabel = entry.iconLabel,
            iconKey = entry.iconKey,
            slotIndex = entry.slotIndex,
            enabled = entry.component.enabled,
        )
    }
}

class OpenHouseDesktopView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : LinearLayout(context, attrs) {
    interface Callbacks {
        fun onOpen(entry: DesktopUiEntry) = Unit
        fun onEdit(entry: DesktopUiEntry) = Unit
        fun onMove(entry: DesktopUiEntry, fromSlot: Int, toSlot: Int) = Unit
        fun onPageChanged(pageIndex: Int, pageCount: Int) = Unit
        fun onBlankLongPress() = Unit
        fun onEditModeChanged(editMode: Boolean) = Unit
    }

    private val pager = DesktopViewPager(context)
    private val adapter = DesktopPagerAdapter()
    private val indicator = TextView(context)
    private val entries = mutableListOf<DesktopUiEntry>()
    private val handler = Handler(Looper.getMainLooper())
    private var callbacks: Callbacks = object : Callbacks {}
    private var columns = 3
    private var rows = 4
    private var editMode = false
    private var dragInProgress = false
    private var pendingPageDirection = 0
    private val autoPageTask = object : Runnable {
        override fun run() {
            if (!dragInProgress || pendingPageDirection == 0) return
            val target = pager.currentItem + pendingPageDirection
            if (target in 0 until pageCount()) {
                pager.setCurrentItem(target, true)
                handler.postDelayed(this, AUTO_PAGE_DELAY_MS)
            } else {
                cancelAutoPage()
            }
        }
    }

    init {
        orientation = VERTICAL
        clipToPadding = false
        pager.adapter = adapter
        pager.offscreenPageLimit = 1
        pager.addOnPageChangeListener(object : ViewPager.SimpleOnPageChangeListener() {
            override fun onPageSelected(position: Int) {
                updateIndicator()
                callbacks.onPageChanged(position, pageCount())
            }
        })
        addView(pager, LayoutParams(LayoutParams.MATCH_PARENT, pagerHeight()))

        indicator.gravity = Gravity.CENTER
        indicator.setTextColor(ContextCompat.getColor(context, R.color.oh_text_secondary))
        indicator.textSize = 12f
        addView(indicator, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(8)
        })
        updateIndicator()
    }

    fun setCallbacks(value: Callbacks?) {
        callbacks = value ?: object : Callbacks {}
    }

    fun setEntries(value: List<DesktopUiEntry>?) {
        entries.clear()
        entries += normalize(value.orEmpty())
        adapter.notifyDataSetChanged()
        setCurrentPage(pager.currentItem, false)
        updateIndicator()
    }

    fun getEntries(): List<DesktopUiEntry> = entries.sortedBy { it.slotIndex }

    fun setGridSize(columns: Int, rows: Int) {
        this.columns = columns.coerceAtLeast(1)
        this.rows = rows.coerceAtLeast(1)
        pager.layoutParams = pager.layoutParams.apply { height = pagerHeight() }
        adapter.notifyDataSetChanged()
        setCurrentPage(pager.currentItem, false)
    }

    fun setEditMode(enabled: Boolean) {
        if (editMode == enabled) return
        editMode = enabled
        cancelAutoPage()
        adapter.notifyDataSetChanged()
        setCurrentPage(pager.currentItem, false)
        callbacks.onEditModeChanged(enabled)
    }

    fun isEditMode(): Boolean = editMode

    fun currentPage(): Int = pager.currentItem

    fun setCurrentPage(page: Int, smooth: Boolean) {
        pager.setCurrentItem(page.coerceIn(0, pageCount() - 1), smooth)
        updateIndicator()
    }

    fun releaseResources() {
        cancelAutoPage()
        dragInProgress = false
        callbacks = object : Callbacks {}
        entries.clear()
        pager.adapter = null
        removeAllViews()
    }

    private fun normalize(source: List<DesktopUiEntry>): List<DesktopUiEntry> {
        val used = mutableSetOf<Int>()
        val result = mutableListOf<DesktopUiEntry>()
        source.filter { it.id.isNotBlank() }.sortedBy { it.slotIndex }.forEach { entry ->
            var slot = entry.slotIndex.coerceAtLeast(0)
            while (slot in used) slot++
            used += slot
            result += entry.copy(slotIndex = slot)
        }
        return result
    }

    private fun pageCount(): Int {
        val actual = ((entries.maxOfOrNull { it.slotIndex } ?: -1) / pageSize()) + 1
        return (if (editMode && entries.isNotEmpty()) actual + 1 else actual).coerceAtLeast(1)
    }

    private fun pageEntries(page: Int): List<DesktopUiEntry?> {
        val base = page * pageSize()
        return MutableList<DesktopUiEntry?>(pageSize()) { null }.also { slots ->
            entries.forEach { entry ->
                val offset = entry.slotIndex - base
                if (offset in slots.indices) slots[offset] = entry
            }
        }
    }

    private fun move(entryId: String, targetSlot: Int) {
        val index = entries.indexOfFirst { it.id == entryId }
        if (index < 0) return
        val moved = entries[index]
        val from = moved.slotIndex
        val target = targetSlot.coerceAtLeast(0)
        if (from == target) return
        val occupantIndex = entries.indexOfFirst { it.slotIndex == target && it.id != entryId }
        if (occupantIndex >= 0) entries[occupantIndex] = entries[occupantIndex].copy(slotIndex = from)
        entries[index] = moved.copy(slotIndex = target)
        entries.sortBy { it.slotIndex }
        adapter.notifyDataSetChanged()
        setCurrentPage(target / pageSize(), false)
        callbacks.onMove(entries.first { it.id == entryId }, from, target)
    }

    private fun onDragLocation(rawX: Float) {
        if (!editMode || !dragInProgress || pager.width <= 0) return cancelAutoPage()
        val location = IntArray(2)
        pager.getLocationOnScreen(location)
        val edge = dp(54)
        val direction = when {
            rawX <= location[0] + edge -> -1
            rawX >= location[0] + pager.width - edge -> 1
            else -> 0
        }
        if (direction == 0) return cancelAutoPage()
        if (pendingPageDirection == direction) return
        cancelAutoPage()
        pendingPageDirection = direction
        handler.postDelayed(autoPageTask, AUTO_PAGE_DELAY_MS)
    }

    private fun cancelAutoPage() {
        pendingPageDirection = 0
        handler.removeCallbacks(autoPageTask)
    }

    private fun updateIndicator() {
        val count = pageCount()
        indicator.text = "${pager.currentItem + 1} / $count"
        indicator.visibility = if (count > 1) View.VISIBLE else View.INVISIBLE
    }

    private fun pageSize() = columns * rows
    private fun pagerHeight() = dp(20) + rows * dp(116) + (rows - 1) * dp(10)
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private inner class DesktopPagerAdapter : PagerAdapter() {
        override fun getCount(): Int = pageCount()
        override fun isViewFromObject(view: View, item: Any): Boolean = view === item
        override fun getItemPosition(item: Any): Int = POSITION_NONE

        override fun instantiateItem(container: ViewGroup, position: Int): Any {
            val page = DesktopPageView(container.context)
            page.bind(pageEntries(position), position * pageSize(), columns, rows, editMode, object : PageCallbacks {
                override fun open(entry: DesktopUiEntry) = callbacks.onOpen(entry)
                override fun edit(entry: DesktopUiEntry) = callbacks.onEdit(entry)
                override fun requestEdit(entry: DesktopUiEntry?) {
                    if (!editMode) setEditMode(true)
                    if (entry == null) callbacks.onBlankLongPress()
                }
                override fun dragStarted() {
                    dragInProgress = true
                    pager.dragInProgress = true
                }
                override fun dragEnded() {
                    dragInProgress = false
                    pager.dragInProgress = false
                    cancelAutoPage()
                }
                override fun dragLocation(rawX: Float) = onDragLocation(rawX)
                override fun move(entryId: String, targetSlot: Int) {
                    this@OpenHouseDesktopView.move(entryId, targetSlot)
                }
            })
            container.addView(page)
            return page
        }

        override fun destroyItem(container: ViewGroup, position: Int, item: Any) {
            container.removeView(item as View)
        }
    }

    private class DesktopViewPager(context: Context) : ViewPager(context) {
        var dragInProgress = false
            set(value) {
                field = value
                requestDisallowInterceptTouchEvent(value)
            }

        override fun onInterceptTouchEvent(event: android.view.MotionEvent): Boolean =
            !dragInProgress && super.onInterceptTouchEvent(event)

        override fun onTouchEvent(event: android.view.MotionEvent): Boolean =
            !dragInProgress && super.onTouchEvent(event)
    }

    companion object {
        private const val AUTO_PAGE_DELAY_MS = 520L
    }
}

private interface PageCallbacks {
    fun open(entry: DesktopUiEntry)
    fun edit(entry: DesktopUiEntry)
    fun requestEdit(entry: DesktopUiEntry?)
    fun dragStarted()
    fun dragEnded()
    fun dragLocation(rawX: Float)
    fun move(entryId: String, targetSlot: Int)
}

private data class DragPayload(val entryId: String, val fromSlot: Int)

private class DesktopPageView(context: Context) : FrameLayout(context) {
    private val grid = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
    }
    private var callback: PageCallbacks? = null
    private var editMode = false
    private var baseSlot = 0

    init {
        setPadding(dp(10), dp(8), dp(10), dp(8))
        addView(grid, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        setOnLongClickListener {
            callback?.requestEdit(null)
            callback != null
        }
    }

    fun bind(
        entries: List<DesktopUiEntry?>,
        baseSlot: Int,
        columns: Int,
        rows: Int,
        editMode: Boolean,
        callback: PageCallbacks,
    ) {
        this.callback = callback
        this.editMode = editMode
        this.baseSlot = baseSlot
        grid.removeAllViews()
        var index = 0
        repeat(rows) { rowIndex ->
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
            }
            grid.addView(row, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f).apply {
                if (rowIndex > 0) topMargin = dp(10)
            })
            repeat(columns) {
                val entry = entries.getOrNull(index)
                val slot = baseSlot + index
                val child = if (entry == null) blankSlot(slot) else tile(entry, slot)
                row.addView(child, LinearLayout.LayoutParams(0, LayoutParams.MATCH_PARENT, 1f).apply {
                    leftMargin = dp(7)
                    rightMargin = dp(7)
                })
                index++
            }
        }
    }

    private fun tile(entry: DesktopUiEntry, slot: Int): View = DesktopTileView(context).apply {
        bind(entry, slot, editMode, callback!!)
        setOnDragListener { view, event -> handleDrag(view, event, slot) }
    }

    private fun blankSlot(slot: Int): View = FrameLayout(context).apply {
        minimumHeight = dp(104)
        alpha = if (editMode) 1f else 0f
        background = if (editMode) blankBackground(false) else null
        setOnLongClickListener {
            callback?.requestEdit(null)
            callback != null
        }
        setOnDragListener { view, event -> handleDrag(view, event, slot) }
    }

    private fun handleDrag(target: View, event: DragEvent, targetSlot: Int): Boolean {
        val payload = event.localState as? DragPayload ?: return false
        if (!editMode) return event.action == DragEvent.ACTION_DRAG_STARTED
        return when (event.action) {
            DragEvent.ACTION_DRAG_STARTED -> true
            DragEvent.ACTION_DRAG_LOCATION -> {
                val location = IntArray(2)
                target.getLocationOnScreen(location)
                callback?.dragLocation(location[0] + event.x)
                true
            }
            DragEvent.ACTION_DRAG_ENTERED -> { highlight(target, true); true }
            DragEvent.ACTION_DRAG_EXITED -> { highlight(target, false); true }
            DragEvent.ACTION_DROP -> {
                highlight(target, false)
                callback?.move(payload.entryId, targetSlot)
                true
            }
            DragEvent.ACTION_DRAG_ENDED -> {
                highlight(target, false)
                callback?.dragEnded()
                true
            }
            else -> true
        }
    }

    private fun highlight(view: View, highlighted: Boolean) {
        val scale = if (highlighted) 1.045f else 1f
        view.scaleX = scale
        view.scaleY = scale
        if (view is FrameLayout && view !is DesktopTileView) {
            view.background = if (editMode) blankBackground(highlighted) else null
        }
    }

    private fun blankBackground(highlighted: Boolean) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(6).toFloat()
        setColor(if (highlighted) Color.argb(38, 20, 125, 100) else Color.argb(12, 80, 80, 80))
        setStroke(dp(1), if (highlighted) Color.argb(150, 20, 125, 100) else Color.argb(60, 80, 80, 80))
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}

private class DesktopTileView(context: Context) : FrameLayout(context) {
    private val column = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        setPadding(dp(6), dp(8), dp(6), dp(7))
    }
    private val icon = TextView(context).apply {
        gravity = Gravity.CENTER
        setTextColor(ContextCompat.getColor(context, R.color.oh_text))
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        setBackgroundResource(R.drawable.oh_icon_background)
    }
    private val title = TextView(context).apply {
        gravity = Gravity.CENTER
        setTextColor(ContextCompat.getColor(context, R.color.oh_text))
        textSize = 12f
        maxLines = 2
    }

    init {
        setBackgroundResource(R.drawable.oh_button_background)
        isClickable = true
        isFocusable = true
        minimumHeight = dp(104)
        addView(column, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        column.addView(icon, LinearLayout.LayoutParams(dp(48), dp(48)))
        column.addView(title, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(7)
        })
    }

    fun bind(entry: DesktopUiEntry, slot: Int, editMode: Boolean, callback: PageCallbacks) {
        icon.text = entry.displayIconLabel()
        val iconText = icon.text.toString()
        icon.textSize = if (iconText.codePointCount(0, iconText.length) > 1) 19f else 24f
        title.text = entry.displayTitle()
        isEnabled = entry.enabled
        alpha = if (entry.enabled) 1f else .55f
        setOnClickListener { if (editMode) callback.edit(entry) else callback.open(entry) }
        setOnLongClickListener {
            if (!editMode) {
                callback.requestEdit(entry)
                true
            } else {
                val payload = DragPayload(entry.id, slot)
                val data = ClipData.newPlainText("openhouse-desktop-entry", entry.id)
                val started = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    startDragAndDrop(data, DragShadowBuilder(this), payload, 0)
                } else {
                    @Suppress("DEPRECATION")
                    startDrag(data, DragShadowBuilder(this), payload, 0)
                }
                if (started) callback.dragStarted() else callback.dragEnded()
                started
            }
        }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
