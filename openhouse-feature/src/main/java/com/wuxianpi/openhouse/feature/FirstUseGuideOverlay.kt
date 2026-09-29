package com.wuxianpi.openhouse.feature

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.wuxianpi.openhouse.feature.R
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** A bounded, in-app first-use guide that never covers DeepSeek's bottom tabs. */
class FirstUseGuideOverlay(
    context: Context,
    private val callbacks: Callbacks,
) : FrameLayout(context) {
    enum class Step { INTRO, LOGIN, BALANCE, RECHARGE, API_KEYS, PASTE_KEY, ENTER_RESCUE }

    interface Callbacks {
        fun openDeepSeekLogin()
        fun openDeepSeekApiKeys()
        fun openDeepSeekUsage()
        fun openDeepSeekRecharge()
        fun openRescue(apiKey: String)
        fun onDismissGuide()
    }

    private val card = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(10), dp(16), dp(14))
        background = android.graphics.drawable.GradientDrawable().apply {
            setColor(ContextCompat.getColor(context, R.color.oh_panel))
            cornerRadius = dp(14).toFloat()
            setStroke(dp(1), ContextCompat.getColor(context, R.color.oh_border))
        }
        elevation = dp(8).toFloat()
    }
    private val dragHandle = TextView(context).apply {
        text = "⠿  首次使用引导                         ×"
        setTextColor(ContextCompat.getColor(context, R.color.oh_text))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        setTypeface(typeface, Typeface.BOLD)
        setPadding(0, 0, 0, dp(8))
        setOnClickListener { if (isExpanded) collapse() else expand() }
    }
    private val status = TextView(context).apply {
        setTextColor(ContextCompat.getColor(context, R.color.oh_text_secondary))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        setLineSpacing(0f, 1.15f)
    }
    private val actions = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
    }
    private var editKey: EditText? = null
    private var step = Step.INTRO
    private var isExpanded = true
    private var downX = 0f
    private var downY = 0f
    private var startX = 0f
    private var startY = 0f
    private var dragging = false

    init {
        visibility = View.GONE
        addView(card, FrameLayout.LayoutParams(dp(304), LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            rightMargin = dp(12)
        })
        card.addView(dragHandle, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        card.addView(status, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        card.addView(actions, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        dragHandle.setOnTouchListener(::handleDrag)
        render()
    }

    fun show(initialStep: Step = Step.INTRO) {
        step = initialStep
        isExpanded = true
        visibility = View.VISIBLE
        val params = card.layoutParams as FrameLayout.LayoutParams
        params.width = dp(304)
        params.height = LayoutParams.WRAP_CONTENT
        card.layoutParams = params
        dragHandle.text = "⠿  首次使用引导                         ×"
        status.visibility = View.VISIBLE
        actions.visibility = View.VISIBLE
        card.visibility = View.VISIBLE
        post { placeDefaultIfNeeded() }
        render()
    }

    fun hide() {
        visibility = View.GONE
        editKey = null
    }

    fun setStep(next: Step) {
        step = next
        if (visibility != View.VISIBLE) visibility = View.VISIBLE
        render()
    }

    fun step(): Step = step

    private fun render() {
        status.text = when (step) {
            Step.INTRO -> "维修助手需要一个可用的 DeepSeek API Key。下面会引导你登录、确认余额并创建密钥。"
            Step.LOGIN -> "请先登录 DeepSeek。登录完成后返回这里继续。"
            Step.BALANCE -> "请确认账户中有可用余额。OpenHouse 不读取 DeepSeek 私有支付接口。"
            Step.RECHARGE -> "账户余额不足或无法确认，请先完成充值。"
            Step.API_KEYS -> "请创建一个 API Key，复制完整密钥后返回这里。"
            Step.PASTE_KEY -> "请将刚才复制的 DeepSeek API Key 粘贴到下面。"
            Step.ENTER_RESCUE -> "Key 已准备好，进入维修助手后会自动保存并开始首次安装。"
        }
        actions.removeAllViews()
        editKey = null
        when (step) {
            Step.INTRO -> {
                addAction("开始获取 DeepSeek Key") { setStep(Step.LOGIN); callbacks.openDeepSeekLogin() }
                addAction("我已经有 Key，粘贴并进入维修助手") { setStep(Step.PASTE_KEY) }
            }
            Step.LOGIN -> {
                addAction("打开 DeepSeek 登录页面") { callbacks.openDeepSeekLogin() }
                addAction("我已经登录，查看余额") { setStep(Step.BALANCE); callbacks.openDeepSeekUsage() }
            }
            Step.BALANCE -> {
                addAction("查看余额") { callbacks.openDeepSeekUsage() }
                addAction("去充值") { setStep(Step.RECHARGE); callbacks.openDeepSeekRecharge() }
                addAction("余额已足够，打开 API Keys") { setStep(Step.API_KEYS); callbacks.openDeepSeekApiKeys() }
            }
            Step.RECHARGE -> {
                addAction("选择充值方式") { callbacks.openDeepSeekRecharge() }
                addAction("我已完成充值，打开 API Keys") { setStep(Step.API_KEYS); callbacks.openDeepSeekApiKeys() }
            }
            Step.API_KEYS -> {
                addAction("打开 API Keys 页面") { callbacks.openDeepSeekApiKeys() }
                addAction("我已经复制，粘贴 Key") { setStep(Step.PASTE_KEY) }
            }
            Step.PASTE_KEY -> {
                val input = EditText(context).apply {
                    hint = "DeepSeek API Key"
                    inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                    maxLines = 1
                }
                editKey = input
                actions.addView(input, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(52)))
                addAction("从剪贴板粘贴") { pasteClipboard(input) }
                addAction("进入维修助手") {
                    val key = input.text?.toString()?.trim().orEmpty()
                    if (key.isNotEmpty()) {
                        setStep(Step.ENTER_RESCUE)
                        callbacks.openRescue(key)
                    } else {
                        input.error = "请先粘贴或输入 API Key"
                    }
                }
            }
            Step.ENTER_RESCUE -> addAction("返回首次使用说明") { setStep(Step.INTRO) }
        }
        addAction("收起") { collapse() }
        addAction("关闭引导") { callbacks.onDismissGuide(); hide() }
        card.visibility = if (isExpanded) View.VISIBLE else View.GONE
    }

    private fun addAction(label: String, action: () -> Unit) {
        actions.addView(Button(context).apply {
            text = label
            isAllCaps = false
            setTextColor(ContextCompat.getColor(context, R.color.oh_text))
            setBackgroundResource(R.drawable.oh_button_background)
            setOnClickListener { action() }
        }, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(44)).apply {
            topMargin = dp(6)
        })
    }

    private fun pasteClipboard(input: EditText) {
        val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        val text = manager.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
        if (text.isBlank()) input.error = "剪贴板没有可用内容" else input.setText(text.trim())
    }

    private fun collapse() {
        isExpanded = false
        card.visibility = View.GONE
        val params = card.layoutParams as FrameLayout.LayoutParams
        params.width = dp(54)
        params.height = dp(54)
        card.layoutParams = params
        card.visibility = View.VISIBLE
        dragHandle.text = "引导"
        status.visibility = View.GONE
        actions.visibility = View.GONE
    }

    private fun expand() {
        isExpanded = true
        val params = card.layoutParams as FrameLayout.LayoutParams
        params.width = dp(304)
        params.height = LayoutParams.WRAP_CONTENT
        card.layoutParams = params
        dragHandle.text = "⠿  首次使用引导                         ×"
        status.visibility = View.VISIBLE
        actions.visibility = View.VISIBLE
        render()
    }

    private fun handleDrag(view: View, event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.rawX; downY = event.rawY
                startX = card.translationX; startY = card.translationY
                dragging = false
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - downX; val dy = event.rawY - downY
                if (!dragging && (abs(dx) > dp(6) || abs(dy) > dp(6))) dragging = true
                if (dragging) {
                    card.translationX = startX + dx
                    card.translationY = startY + dy
                    clampCard()
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (dragging) snapCard()
                else if (event.actionMasked == MotionEvent.ACTION_UP) {
                    if (isExpanded) collapse() else expand()
                }
                return true
            }
        }
        return false
    }

    private fun placeDefaultIfNeeded() {
        if (card.translationX == 0f && card.translationY == 0f) {
            card.translationX = 0f
            card.translationY = 0f
            clampCard()
        }
    }

    private fun snapCard() {
        clampCard()
        val parentWidth = width.toFloat()
        val cardWidth = card.width.toFloat()
        val target = if (card.translationX + cardWidth / 2f > parentWidth / 2f) {
            0f
        } else {
            -(parentWidth - cardWidth - dp(12))
        }
        animate().setDuration(150).translationX(target).start()
    }

    private fun clampCard() {
        val safeBottom = dp(88)
        val maxX = 0f
        val minX = -(width - card.width - dp(12)).toFloat().coerceAtLeast(0f)
        val maxY = (height - card.height - safeBottom).toFloat().coerceAtLeast(0f)
        val minY = dp(12).toFloat()
        card.translationX = min(maxX, max(minX, card.translationX))
        card.translationY = min(maxY, max(minY, card.translationY))
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()
}
