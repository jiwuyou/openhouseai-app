package com.wuxianpi.openhouse.feature

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.Color
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/** A single draggable WebView hosted inside the foreground OpenHouse Activity. */
internal class FloatingWebViewHost(
    private val context: android.content.Context,
    private val parent: FrameLayout,
    private val store: FloatingWindowStore,
    private val callbacks: Callbacks,
) {
    interface Callbacks {
        fun onBrowser(url: String)
    }

    private var container: LinearLayout? = null
    private var webView: WebView? = null
    private var snapshot: FloatingWindowSnapshot? = null
    private var fullscreen = false
    private var savedBounds: Bounds? = null

    val isOpen: Boolean
        get() = container != null

    fun open(snapshot: FloatingWindowSnapshot) {
        closeInternal(save = true)
        this.snapshot = snapshot
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            elevation = dp(10).toFloat()
        }
        val header = LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.WHITE)
            setPadding(dp(6), dp(3), dp(4), dp(3))
        }
        val title = TextView(context).apply {
            text = snapshot.title
            setTextColor(Color.DKGRAY)
            textSize = 14f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        header.addView(title, LinearLayout.LayoutParams(0, dp(42), 1f))
        header.addView(headerButton("全屏") { toggleFullscreen() })
        header.addView(headerButton("浏览器") {
            this.snapshot?.conversationUrl?.takeIf(String::isNotBlank)?.let(callbacks::onBrowser)
        })
        header.addView(headerButton("关闭") { close() })
        root.addView(header)

        val view = createWebView()
        root.addView(view, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            0,
            1f,
        ))
        root.addView(TextView(context).apply {
            text = "调整大小"
            gravity = Gravity.CENTER_HORIZONTAL
            setTextColor(Color.DKGRAY)
            textSize = 11f
            setPadding(0, dp(2), 0, dp(2))
            setOnTouchListener(ResizeListener(root))
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(22),
        ))
        header.setOnTouchListener(DragListener(root))
        container = root
        webView = view
        parent.addView(root, initialLayout(snapshot))
        root.post { applySnapshotBounds(root, snapshot) }
        view.loadUrl(snapshot.conversationUrl)
    }

    fun openLast(): Boolean = store.load()?.let { open(it); true } ?: false

    fun close() {
        closeInternal(save = true)
    }

    fun dispose() {
        closeInternal(save = true)
    }

    fun onResume() {
        webView?.onResume()
    }

    fun onPause() {
        webView?.onPause()
    }

    private fun createWebView(): WebView = WebView(context).also { view ->
        configureWebView(view)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView(view: WebView) {
        view.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            loadWithOverviewMode = true
            useWideViewPort = true
            cacheMode = WebSettings.LOAD_DEFAULT
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            setSupportMultipleWindows(false)
            allowFileAccess = false
            allowContentAccess = false
        }
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(view, true)
        }
        view.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                saveUrl(url)
            }

            override fun onPageFinished(view: WebView, url: String?) {
                saveUrl(url)
            }

            override fun onReceivedError(
                view: WebView,
                request: WebResourceRequest,
                error: WebResourceError?,
            ) {
                if (request.isForMainFrame) saveUrl(request.url?.toString())
            }
        }
    }

    private fun saveUrl(url: String?) {
        val current = snapshot ?: return
        val value = url?.trim().orEmpty().ifEmpty { current.conversationUrl }
        snapshot = current.copy(conversationUrl = value, updatedAt = System.currentTimeMillis())
        store.save(snapshot!!)
    }

    private fun closeInternal(save: Boolean) {
        val root = container ?: return
        if (save) {
            if (fullscreen && savedBounds != null) persistBounds(savedBounds!!)
            else saveBounds(root)
        }
        (root.parent as? ViewGroup)?.removeView(root)
        webView?.let { view ->
            runCatching { view.stopLoading() }
            runCatching { view.onPause() }
            runCatching { view.removeAllViews() }
            runCatching { view.destroy() }
        }
        container = null
        webView = null
        fullscreen = false
        savedBounds = null
    }

    private fun toggleFullscreen() {
        val root = container ?: return
        if (fullscreen) {
            val bounds = savedBounds ?: return
            root.layoutParams = FrameLayout.LayoutParams(bounds.width, bounds.height)
            root.x = bounds.x.toFloat()
            root.y = bounds.y.toFloat()
            fullscreen = false
            persistBounds(bounds)
        } else {
            saveBounds(root)
            root.layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            root.x = 0f
            root.y = 0f
            fullscreen = true
        }
        root.requestLayout()
    }

    private fun saveBounds(root: View) {
        if (snapshot == null) return
        val width = root.width.takeIf { it > 0 } ?: root.layoutParams?.width ?: dp(340)
        val height = root.height.takeIf { it > 0 } ?: root.layoutParams?.height ?: dp(460)
        persistBounds(Bounds(root.x.toInt(), root.y.toInt(), width, height))
    }

    private fun persistBounds(bounds: Bounds) {
        val current = snapshot ?: return
        savedBounds = bounds
        val next = current.copy(
            x = bounds.x,
            y = bounds.y,
            width = bounds.width,
            height = bounds.height,
            updatedAt = System.currentTimeMillis(),
        )
        snapshot = next
        store.save(next)
    }

    private fun initialLayout(snapshot: FloatingWindowSnapshot): FrameLayout.LayoutParams =
        FrameLayout.LayoutParams(
            snapshot.width.takeIf { it > 0 } ?: dp(340),
            snapshot.height.takeIf { it > 0 } ?: dp(460),
        )

    private fun applySnapshotBounds(root: View, snapshot: FloatingWindowSnapshot) {
        root.x = snapshot.x.toFloat()
        root.y = snapshot.y.toFloat()
    }

    private fun headerButton(label: String, action: () -> Unit): Button = Button(context).apply {
        text = label
        isAllCaps = false
        minWidth = 0
        setOnClickListener { action() }
        layoutParams = LinearLayout.LayoutParams(dp(58), dp(42))
    }

    private inner class DragListener(private val target: View) : View.OnTouchListener {
        private var offsetX = 0f
        private var offsetY = 0f

        override fun onTouch(view: View, event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    offsetX = event.rawX - target.x
                    offsetY = event.rawY - target.y
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    target.x = event.rawX - offsetX
                    target.y = event.rawY - offsetY
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    saveBounds(target)
                    return true
                }
            }
            return true
        }
    }

    private inner class ResizeListener(private val target: View) : View.OnTouchListener {
        private var startX = 0f
        private var startY = 0f
        private var startWidth = 0
        private var startHeight = 0

        override fun onTouch(view: View, event: MotionEvent): Boolean {
            if (fullscreen) return false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = event.rawX
                    startY = event.rawY
                    startWidth = target.width
                    startHeight = target.height
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    val params = target.layoutParams as? FrameLayout.LayoutParams ?: return false
                    params.width = (startWidth + (event.rawX - startX).toInt()).coerceAtLeast(dp(220))
                    params.height = (startHeight + (event.rawY - startY).toInt()).coerceAtLeast(dp(260))
                    target.layoutParams = params
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    saveBounds(target)
                    return true
                }
            }
            return true
        }
    }

    private fun dp(value: Int): Int =
        (value * context.resources.displayMetrics.density + 0.5f).toInt()

    private data class Bounds(val x: Int, val y: Int, val width: Int, val height: Int)
}
