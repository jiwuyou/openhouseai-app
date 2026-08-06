package com.wuxianpi.openhouse.feature.workspace

import android.annotation.SuppressLint
import android.content.ComponentCallbacks2
import android.graphics.Bitmap
import android.graphics.Typeface
import android.net.Uri
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.wuxianpi.openhouse.core.workspace.HttpUrlNormalizer
import com.wuxianpi.openhouse.core.workspace.WorkspaceDestination
import com.wuxianpi.openhouse.feature.ComponentWebLaunchArgs
import com.wuxianpi.openhouse.feature.ComponentWebLoadPhase
import com.wuxianpi.openhouse.feature.ComponentWebPageState
import com.wuxianpi.openhouse.feature.R

/** Shared, bounded WebView pool used by both the workspace and legacy Web Activity. */
internal class EmbeddedWebPagePool(
    private val context: android.content.Context,
    private val callbacks: Callbacks,
    private val maxRetainedPages: Int = DEFAULT_MAX_RETAINED_PAGES,
) {
    interface Callbacks {
        fun onStateChanged(args: ComponentWebLaunchArgs, state: ComponentWebPageState) = Unit
        fun onOpenControl(args: ComponentWebLaunchArgs) = Unit
        fun onOpenMaintenance() = Unit
        fun onOpenExternal(uri: Uri) = Unit
        fun onCopyAddress(args: ComponentWebLaunchArgs, address: String) = Unit
    }

    private val pages = LinkedHashMap<String, PageRecord>()
    private var activePage: PageRecord? = null
    private var attachedHost: FrameLayout? = null
    private var useSequence = 0L
    private var resumed = false
    private var destroyed = false

    val activeArgs: ComponentWebLaunchArgs?
        get() = activePage?.args

    val activeAddress: String
        get() = activePage?.let { it.state.url.ifEmpty { pageAddress(it.args) } }.orEmpty()

    internal val retainedPageCount: Int
        get() = pages.size

    internal val activeLoadGeneration: Long
        get() = activePage?.loadGeneration ?: 0

    internal fun acceptsActiveLoadGeneration(generation: Long): Boolean =
        activePage?.let { !it.disposed && it.loadGeneration == generation } == true

    fun show(args: ComponentWebLaunchArgs, host: FrameLayout) {
        if (destroyed) return
        val safeArgs = args.withNormalizedUrls()
        val key = pageKey(safeArgs)
        val componentId = WorkspaceDestination.normalizeId(safeArgs.componentId)
        pages.entries
            .filter { (existingKey, page) ->
                WorkspaceDestination.normalizeId(page.args.componentId) == componentId &&
                    (existingKey != key || page.args.catalogFingerprint != safeArgs.catalogFingerprint)
            }
            .toList()
            .forEach { (existingKey, page) ->
                pages.remove(existingKey)
                if (page === activePage) activePage = null
                destroyPage(page)
            }
        val page = pages[key] ?: createPage(safeArgs).also { pages[key] = it }
        page.args = safeArgs
        page.lastUsedOrder = ++useSequence
        attachPage(page, host)
        if (page.webView.url == null && page.state.phase != ComponentWebLoadPhase.FAILED) {
            reload(page)
        } else {
            renderState(page)
        }
        trimTo(maxRetainedPages)
    }

    fun reloadActive() {
        activePage?.let(::reload)
    }

    fun canGoBack(): Boolean = activePage?.webView?.canGoBack() == true

    fun goBack(): Boolean {
        val webView = activePage?.webView ?: return false
        if (!webView.canGoBack()) return false
        webView.goBack()
        return true
    }

    fun onResume() {
        resumed = true
        activePage?.webView?.onResume()
    }

    fun onPause() {
        resumed = false
        activePage?.webView?.onPause()
    }

    fun onTrimMemory(level: Int) {
        when {
            level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL -> trimTo(1)
            level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND -> trimTo(1)
        }
    }

    fun destroy() {
        if (destroyed) return
        destroyed = true
        pages.values.toList().forEach(::destroyPage)
        pages.clear()
        activePage = null
        attachedHost = null
    }

    private fun createPage(args: ComponentWebLaunchArgs): PageRecord {
        val pageView = FrameLayout(context)
        val webView = WebView(context)
        lateinit var record: PageRecord
        val fallback = createFallbackView { record }
        record = PageRecord(
            args = args,
            pageView = pageView,
            webView = webView,
            fallbackView = fallback.root,
            fallbackPrimaryButton = fallback.primaryButton,
            state = ComponentWebPageState(pageAddress(args)),
        )
        pageView.addView(webView, matchParent())
        pageView.addView(fallback.root, matchParent())
        fallback.root.visibility = View.GONE
        return record
    }

    private fun createFallbackView(record: () -> PageRecord): FallbackView {
        lateinit var primaryButton: Button
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(24), dp(24), dp(24))
            setBackgroundColor(ContextCompat.getColor(context, R.color.oh_surface))

            addView(TextView(context).apply {
                id = R.id.oh_component_web_error_title
                setTextColor(ContextCompat.getColor(context, R.color.oh_text))
                textSize = 20f
                setTypeface(typeface, Typeface.BOLD)
                gravity = Gravity.CENTER
            })
            addView(TextView(context).apply {
                id = R.id.oh_component_web_error_detail
                setTextColor(ContextCompat.getColor(context, R.color.oh_text_secondary))
                textSize = 14f
                gravity = Gravity.CENTER
                setPadding(0, dp(12), 0, dp(14))
            }, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

            val actions = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
            }
            primaryButton = fallbackButton(context.getString(R.string.oh_service_control)) {
                val page = record()
                if (page.args.hasControlEntry) callbacks.onOpenControl(page.args)
                else callbacks.onOpenMaintenance()
            }
            actions.addView(primaryButton)
            actions.addView(fallbackButton(context.getString(R.string.oh_refresh)) { reload(record()) })
            addView(actions)
            val copyButton = fallbackButton(context.getString(R.string.oh_copy_address)) {
                val page = record()
                callbacks.onCopyAddress(page.args, page.state.url.ifEmpty { pageAddress(page.args) })
            }
            copyButton.layoutParams = (copyButton.layoutParams as LinearLayout.LayoutParams).apply {
                topMargin = dp(8)
            }
            addView(copyButton)
        }
        return FallbackView(root, primaryButton)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView(record: PageRecord, webView: WebView, generation: Long) {
        webView.settings.apply {
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
        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                if (!isCurrentLoad(record, view, generation)) return
                record.state = record.state.loading(normalizeOrEmpty(url).ifEmpty { pageAddress(record.args) })
                record.fallbackView.visibility = View.GONE
                renderState(record)
            }

            override fun onPageFinished(view: WebView, url: String?) {
                if (!isCurrentLoad(record, view, generation) || record.state.phase == ComponentWebLoadPhase.FAILED) return
                record.state = record.state.connected(normalizeOrEmpty(url).ifEmpty { pageAddress(record.args) })
                record.fallbackView.visibility = View.GONE
                renderState(record)
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError?) {
                if (isCurrentLoad(record, view, generation) && request.isForMainFrame) {
                    markUnavailable(record, request.url?.toString().orEmpty())
                }
            }

            @Deprecated("Deprecated in Java")
            override fun onReceivedError(view: WebView, errorCode: Int, description: String?, failingUrl: String?) {
                if (isCurrentLoad(record, view, generation)) markUnavailable(record, failingUrl.orEmpty())
            }

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                if (isCurrentLoad(record, view, generation)) handleNavigation(request.url) else true

            @Deprecated("Deprecated in Java")
            override fun shouldOverrideUrlLoading(view: WebView, url: String?): Boolean =
                if (isCurrentLoad(record, view, generation)) handleNavigation(Uri.parse(url.orEmpty())) else true
        }
    }

    private fun isCurrentLoad(record: PageRecord, view: WebView, generation: Long): Boolean =
        !destroyed && !record.disposed && record.webView === view && record.loadGeneration == generation

    private fun handleNavigation(uri: Uri?): Boolean {
        val value = uri?.toString().orEmpty()
        if (HttpUrlNormalizer.normalize(value) != null) return false
        val scheme = uri?.scheme.orEmpty().lowercase()
        if (scheme.isNotEmpty() && scheme != "http" && scheme != "https") {
            callbacks.onOpenExternal(uri!!)
        }
        return true
    }

    private fun attachPage(page: PageRecord, host: FrameLayout) {
        if (activePage !== page) activePage?.webView?.onPause()
        activePage = page
        attachedHost = host
        (page.pageView.parent as? ViewGroup)?.removeView(page.pageView)
        host.removeAllViews()
        host.addView(page.pageView, matchParent())
        if (resumed) page.webView.onResume()
        renderState(page)
    }

    private fun reload(page: PageRecord) {
        if (destroyed) return
        val url = pageAddress(page.args)
        if (url.isEmpty()) {
            markUnavailable(page, "")
            return
        }
        page.state = page.state.loading(url)
        page.fallbackView.visibility = View.GONE
        renderState(page)
        val oldWebView = page.webView
        val generation = ++page.loadGeneration
        val newWebView = WebView(context)
        page.webView = newWebView
        configureWebView(page, newWebView, generation)
        page.pageView.removeView(oldWebView)
        page.pageView.addView(newWebView, 0, matchParent())
        destroyWebView(oldWebView)
        if (resumed && page === activePage) newWebView.onResume()
        newWebView.loadUrl(url)
    }

    private fun markUnavailable(page: PageRecord, failingUrl: String) {
        if (destroyed) return
        val url = normalizeOrEmpty(failingUrl).ifEmpty {
            pageAddress(page.args).ifEmpty {
                if (page.args.hasControlEntry) "" else normalizeOrEmpty(page.args.fallbackUrl)
            }
        }
        page.state = page.state.failed(url)
        page.fallbackView.visibility = View.VISIBLE
        page.fallbackPrimaryButton.text = if (page.args.hasControlEntry) {
            page.args.controlTitle.ifEmpty { context.getString(R.string.oh_service_control) }
        } else {
            context.getString(R.string.oh_maintenance)
        }
        page.fallbackView.findViewById<TextView>(R.id.oh_component_web_error_title).text =
            context.getString(R.string.oh_component_web_unavailable, page.args.title)
        page.fallbackView.findViewById<TextView>(R.id.oh_component_web_error_detail).text =
            if (pageAddress(page.args).isEmpty()) {
                context.getString(R.string.oh_component_web_missing_endpoint)
            } else {
                context.getString(R.string.oh_component_web_unavailable_detail, url)
            }
        renderState(page)
    }

    private fun renderState(page: PageRecord) {
        if (page === activePage) callbacks.onStateChanged(page.args, page.state)
    }

    private fun trimTo(limit: Int) {
        val retained = limit.coerceAtLeast(1)
        while (pages.size > retained) {
            val oldest = pages.values
                .filterNot { it === activePage }
                .minByOrNull { it.lastUsedOrder }
                ?: return
            pages.remove(pageKey(oldest.args))
            destroyPage(oldest)
        }
    }

    private fun destroyPage(page: PageRecord) {
        page.disposed = true
        page.loadGeneration++
        (page.pageView.parent as? ViewGroup)?.removeView(page.pageView)
        destroyWebView(page.webView)
    }

    private fun destroyWebView(webView: WebView) {
        runCatching { webView.stopLoading() }
        runCatching { webView.onPause() }
        webView.webViewClient = WebViewClient()
        runCatching { webView.clearHistory() }
        runCatching { webView.removeAllViews() }
        (webView.parent as? ViewGroup)?.removeView(webView)
        runCatching { webView.destroy() }
    }

    private fun ComponentWebLaunchArgs.withNormalizedUrls(): ComponentWebLaunchArgs = copy(
        fallbackUrl = normalizeOrEmpty(fallbackUrl),
        resolvedUrl = normalizeOrEmpty(resolvedUrl),
    )

    private fun pageAddress(args: ComponentWebLaunchArgs): String = normalizeOrEmpty(args.loadUrl)

    private fun pageKey(args: ComponentWebLaunchArgs): String = "${args.componentId}\n${pageAddress(args)}"

    private fun normalizeOrEmpty(value: String?): String = HttpUrlNormalizer.normalize(value).orEmpty()

    private fun fallbackButton(label: String, action: () -> Unit): Button = Button(context).apply {
        text = label
        isAllCaps = false
        minWidth = 0
        setBackgroundResource(R.drawable.oh_button_background)
        setOnClickListener { action() }
        layoutParams = LinearLayout.LayoutParams(dp(136), dp(46)).apply {
            marginStart = dp(4)
            marginEnd = dp(4)
        }
    }

    private fun matchParent(): FrameLayout.LayoutParams = FrameLayout.LayoutParams(
        FrameLayout.LayoutParams.MATCH_PARENT,
        FrameLayout.LayoutParams.MATCH_PARENT,
    )

    private fun dp(value: Int): Int = (value * context.resources.displayMetrics.density + 0.5f).toInt()

    private data class PageRecord(
        var args: ComponentWebLaunchArgs,
        val pageView: FrameLayout,
        var webView: WebView,
        val fallbackView: LinearLayout,
        val fallbackPrimaryButton: Button,
        var state: ComponentWebPageState,
        var lastUsedOrder: Long = 0,
        var loadGeneration: Long = 0,
        var disposed: Boolean = false,
    )

    private data class FallbackView(
        val root: LinearLayout,
        val primaryButton: Button,
    )

    companion object {
        const val DEFAULT_MAX_RETAINED_PAGES = 2
    }
}
