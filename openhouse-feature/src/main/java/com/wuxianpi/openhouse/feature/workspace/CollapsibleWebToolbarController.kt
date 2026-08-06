package com.wuxianpi.openhouse.feature.workspace

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import com.wuxianpi.openhouse.feature.StartupRouteStore
import kotlin.math.hypot
import kotlin.math.roundToInt

class CollapsibleWebToolbarController(
    context: Context,
    private val pageHost: FrameLayout,
    private val toolbar: View,
    private val bubble: View,
    private val onBubbleClicked: () -> Unit,
) {
    private val appContext = context.applicationContext
    private val preferences: SharedPreferences = appContext.getSharedPreferences(
        StartupRouteStore.PREFS_NAME,
        Context.MODE_PRIVATE,
    )
    private var webMode = false

    init {
        migrateLegacyPosition()
        bubble.setOnClickListener {
            setCollapsed(false)
            onBubbleClicked()
        }
        attachDrag()
        pageHost.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            if (bubble.visibility == View.VISIBLE) applySavedPosition()
        }
    }

    fun setWebMode(enabled: Boolean) {
        webMode = enabled
        render()
    }

    fun collapse() {
        if (webMode) setCollapsed(true)
    }

    private fun setCollapsed(collapsed: Boolean) {
        preferences.edit().putBoolean(KEY_COLLAPSED, collapsed).apply()
        render()
    }

    private fun render() {
        val collapsed = webMode && preferences.getBoolean(KEY_COLLAPSED, false)
        toolbar.visibility = if (collapsed) View.GONE else View.VISIBLE
        bubble.visibility = if (collapsed) View.VISIBLE else View.GONE
        if (collapsed) bubble.post(::applySavedPosition)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun attachDrag() {
        val touchSlop = ViewConfiguration.get(bubble.context).scaledTouchSlop
        var downX = 0f
        var downY = 0f
        var startLeft = 0
        var startTop = 0
        var dragging = false
        bubble.setOnTouchListener { view, event ->
            val params = view.layoutParams as? FrameLayout.LayoutParams ?: return@setOnTouchListener false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    startLeft = params.leftMargin
                    startTop = params.topMargin
                    dragging = false
                    view.parent.requestDisallowInterceptTouchEvent(true)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY
                    if (!dragging && hypot(dx.toDouble(), dy.toDouble()) > touchSlop) dragging = true
                    if (dragging) moveTo(startLeft + dx.roundToInt(), startTop + dy.roundToInt())
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    view.parent.requestDisallowInterceptTouchEvent(false)
                    if (dragging) snapAndSave() else if (event.actionMasked == MotionEvent.ACTION_UP) view.performClick()
                    true
                }
                else -> true
            }
        }
    }

    private fun applySavedPosition() {
        if (pageHost.width <= 0 || pageHost.height <= 0) return
        val size = bubble.width.takeIf { it > 0 } ?: dp(52)
        val margin = dp(14)
        val edge = preferences.getInt(KEY_EDGE, EDGE_END)
        val ratio = preferences.getFloat(KEY_Y_RATIO, 0.78f).coerceIn(0f, 1f)
        val left = if (edge == EDGE_START) margin else pageHost.width - size - margin
        val top = (ratio * (pageHost.height - size - margin * 2).coerceAtLeast(0)).roundToInt() + margin
        moveTo(left, top)
    }

    private fun moveTo(left: Int, top: Int) {
        val params = bubble.layoutParams as? FrameLayout.LayoutParams ?: return
        val width = bubble.width.takeIf { it > 0 } ?: dp(52)
        val height = bubble.height.takeIf { it > 0 } ?: dp(52)
        val margin = dp(10)
        params.gravity = Gravity.TOP or Gravity.START
        params.leftMargin = left.coerceIn(margin, (pageHost.width - width - margin).coerceAtLeast(margin))
        params.topMargin = top.coerceIn(margin, (pageHost.height - height - margin).coerceAtLeast(margin))
        params.rightMargin = 0
        params.bottomMargin = 0
        bubble.layoutParams = params
    }

    private fun snapAndSave() {
        val params = bubble.layoutParams as? FrameLayout.LayoutParams ?: return
        val width = bubble.width.takeIf { it > 0 } ?: dp(52)
        val height = bubble.height.takeIf { it > 0 } ?: dp(52)
        val margin = dp(14)
        val edge = if (params.leftMargin + width / 2 < pageHost.width / 2) EDGE_START else EDGE_END
        val left = if (edge == EDGE_START) margin else pageHost.width - width - margin
        val availableHeight = (pageHost.height - height - margin * 2).coerceAtLeast(1)
        val top = params.topMargin.coerceIn(margin, margin + availableHeight)
        moveTo(left, top)
        preferences.edit()
            .putInt(KEY_EDGE, edge)
            .putFloat(KEY_Y_RATIO, ((top - margin).toFloat() / availableHeight).coerceIn(0f, 1f))
            .apply()
    }

    private fun migrateLegacyPosition() {
        if (preferences.contains(KEY_COLLAPSED)) return
        val legacy = appContext.getSharedPreferences(LEGACY_PREFS_NAME, Context.MODE_PRIVATE)
        if (!legacy.contains(LEGACY_COLLAPSED)) return
        preferences.edit()
            .putBoolean(KEY_COLLAPSED, legacy.getBoolean(LEGACY_COLLAPSED, false))
            .putInt(KEY_EDGE, legacy.getInt(LEGACY_EDGE, EDGE_END))
            .putFloat(KEY_Y_RATIO, legacy.getFloat(LEGACY_Y_RATIO, 0.78f))
            .apply()
    }

    private fun dp(value: Int): Int =
        (value * bubble.resources.displayMetrics.density + 0.5f).toInt()

    companion object {
        private const val KEY_COLLAPSED = "web_toolbar_collapsed_v1"
        private const val KEY_EDGE = "web_toolbar_bubble_edge_v1"
        private const val KEY_Y_RATIO = "web_toolbar_bubble_y_ratio_v1"
        private const val LEGACY_PREFS_NAME = "openhouse_home"
        private const val LEGACY_COLLAPSED = "top_action_bar_collapsed"
        private const val LEGACY_EDGE = "top_action_bar_bubble_edge"
        private const val LEGACY_Y_RATIO = "top_action_bar_bubble_y_ratio"
        private const val EDGE_START = 0
        private const val EDGE_END = 1
    }
}
