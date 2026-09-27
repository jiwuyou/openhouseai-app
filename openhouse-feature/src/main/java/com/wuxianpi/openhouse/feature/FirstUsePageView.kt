package com.wuxianpi.openhouse.feature

import android.content.Context
import android.graphics.Color
import android.util.TypedValue
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** The local first-use guide. It deliberately does not own setup or home state. */
class FirstUsePageView(
    context: Context,
    private val callbacks: Callbacks,
) : ScrollView(context) {
    interface Callbacks {
        fun onOpenDeepSeekApiKeys()
        fun onOpenRescue()
    }

    private val body = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(20), dp(24), dp(20), dp(32))
    }

    init {
        isFillViewport = true
        addView(body, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        renderIntro()
    }

    private fun renderIntro() {
        body.removeAllViews()
        addHeading("欢迎使用 OpenHouse")
        addParagraph(
            "左上角有侧栏。如果你迷路了，可以打开左上角侧栏，\n" +
                "然后点击“首次使用”回到这里。"
        )
        addParagraph(
            "你需要先获取一个大模型 API Key，并填写到维修助手中。\n" +
                "之后，维修助手会帮助你完成首次安装，也可以解答安装过程中的问题。\n\n" +
                "首次安装时，维修助手配置好 API 后，会先帮助你完成完整的首次安装配置。"
        )
        addSubheading("1. 如果你还不知道怎么获取大模型 API Key：")
        addButton("查看 DeepSeek 配置引导") { renderGuide() }
        addSubheading("2. 如果你已经拿到了 DeepSeek API Key，\n" +
            "或者你熟悉大模型 API 配置：")
        addButton("进入维修助手", callbacks::onOpenRescue)
    }

    private fun renderGuide() {
        body.removeAllViews()
        addHeading("获取 DeepSeek API Key")
        addParagraph(
            "1. 登录 DeepSeek 官方平台。\n\n" +
                "2. 在 DeepSeek 充值 5 元。\n" +
                "请注意，OpenHouse/WuxianPi 与 DeepSeek 没有合作关系，\n" +
                "这里只提供一条固定路线，帮助新用户快速上手。\n\n" +
                "3. 选择支付方式时，推荐使用支付宝，\n" +
                "因为通常可以自动跳转支付宝。\n\n" +
                "如果使用微信支付：\n" +
                "DeepSeek 页面显示二维码后，请先截图；\n" +
                "然后打开微信扫一扫，从相册中选择二维码截图完成支付。\n\n" +
                "4. 打开 API Keys 页面。\n\n" +
                "5. 创建 API Key，名称可以任意填写。\n\n" +
                "6. 复制 API Key。\n\n" +
                "7. 打开左上角侧栏，进入维修模式，\n" +
                "将 API Key 粘贴到维修助手中。"
        )
        addButton("获取 DeepSeek API Keys 页面", callbacks::onOpenDeepSeekApiKeys)
        addButton("我已经复制，进入维修模式", callbacks::onOpenRescue)
        addButton("返回首次使用") { renderIntro() }
    }

    private fun addHeading(text: String) {
        body.addView(TextView(context).apply {
            this.text = text
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
            setTextColor(Color.BLACK)
            setPadding(0, 0, 0, dp(16))
        }, matchWrap())
    }

    private fun addSubheading(text: String) {
        body.addView(TextView(context).apply {
            this.text = text
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setTextColor(Color.BLACK)
            setPadding(0, dp(14), 0, dp(8))
        }, matchWrap())
    }

    private fun addParagraph(text: String) {
        body.addView(TextView(context).apply {
            this.text = text
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setTextColor(Color.DKGRAY)
            setLineSpacing(0f, 1.18f)
            setPadding(0, 0, 0, dp(12))
        }, matchWrap())
    }

    private fun addButton(text: String, action: () -> Unit) {
        body.addView(Button(context).apply {
            this.text = text
            isAllCaps = false
            setOnClickListener { action() }
        }, matchWrap().apply { topMargin = dp(6); bottomMargin = dp(6) })
    }

    private fun matchWrap() = LinearLayout.LayoutParams(
        LayoutParams.MATCH_PARENT,
        LayoutParams.WRAP_CONTENT,
    )

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density + 0.5f).toInt()
}
