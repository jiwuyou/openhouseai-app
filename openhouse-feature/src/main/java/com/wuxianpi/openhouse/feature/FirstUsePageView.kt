package com.wuxianpi.openhouse.feature

import android.content.Context
import android.graphics.Color
import android.util.TypedValue
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** The first-use page explains the flow; the floating guide owns its actions. */
class FirstUsePageView(context: Context) : ScrollView(context) {
    private val body = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(20), dp(24), dp(20), dp(32))
    }

    init {
        isFillViewport = true
        addView(body, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        body.addView(TextView(context).apply {
            text = "欢迎使用 OpenHouse"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
            setTextColor(Color.BLACK)
            setPadding(0, 0, 0, dp(16))
        }, matchWrap())
        body.addView(TextView(context).apply {
            text = "左上角有侧栏。如果你迷路了，可以打开左上角侧栏，\n" +
                "然后点击“首次使用”回到这里。\n\n" +
                "首次使用需要先为维修助手准备一个可用的大模型 API Key。\n" +
                "请按照右侧的“首次安装引导”一步一步操作。\n\n" +
                "引导收起后，可以点击“引导”气泡重新展开。\n" +
                "如果退出引导，之后仍可从左上角侧栏 → 首次使用重新打开。"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setTextColor(Color.DKGRAY)
            setLineSpacing(0f, 1.18f)
        }, matchWrap())
    }

    private fun matchWrap() = LinearLayout.LayoutParams(
        LayoutParams.MATCH_PARENT,
        LayoutParams.WRAP_CONTENT,
    )

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density + 0.5f).toInt()
}
