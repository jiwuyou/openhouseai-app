package com.wuxianpi.openhouse.feature

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.PopupWindow

internal class WebPresentationMenu(private val context: Context) {
    data class Entry(val label: String, val action: () -> Unit)

    fun show(anchor: View, entries: List<Entry>) {
        if (entries.isEmpty()) return
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(10), dp(10), dp(10))
            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                cornerRadius = dp(12).toFloat()
            }
        }
        val popup = PopupWindow(
            content,
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
            true,
        ).apply {
            elevation = dp(8).toFloat()
            isOutsideTouchable = true
        }
        entries.forEach { entry ->
            content.addView(Button(context).apply {
                text = entry.label
                isAllCaps = false
                minWidth = 0
                setOnClickListener {
                    popup.dismiss()
                    entry.action()
                }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    dp(48),
                )
            })
        }
        popup.showAtLocation(anchor, Gravity.BOTTOM, 0, 0)
    }

    private fun dp(value: Int): Int =
        (value * context.resources.displayMetrics.density + 0.5f).toInt()
}
