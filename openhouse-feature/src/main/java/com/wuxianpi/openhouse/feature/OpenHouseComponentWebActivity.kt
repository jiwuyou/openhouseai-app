package com.wuxianpi.openhouse.feature

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
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
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.wuxianpi.openhouse.core.ProductRoute

class OpenHouseComponentWebActivity : AppCompatActivity() {
    private lateinit var host: OpenHouseFeatureHost
    private lateinit var titleView: TextView
    private lateinit var statusView: TextView
    private lateinit var browserHost: FrameLayout
    private lateinit var controlButton: Button
    private val pages = LinkedHashMap<String, PageRecord>()
    private var activePage: PageRecord? = null
    private var useSequence = 0L
    private var destroyed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_component_web)
        host = OpenHouseFeatureHosts.from(this)
        bindViews()
        showIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        showIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        activePage?.webView?.onResume()
    }

    override fun onPause() {
        activePage?.webView?.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        destroyed = true
        pages.values.toList().forEach(::destroyPage)
        pages.clear()
        activePage = null
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        val page = activePage
        if (page != null && page.webView.canGoBack()) page.webView.goBack() else super.onBackPressed()
    }

    private fun bindViews() {
        titleView = findViewById(R.id.oh_component_web_title)
        statusView = findViewById(R.id.oh_component_web_status)
        browserHost = findViewById(R.id.oh_component_web_host)
        controlButton = findViewById(R.id.oh_component_web_control)
        findViewById<Button>(R.id.oh_component_web_desktop).setOnClickListener { returnToDesktop() }
        findViewById<Button>(R.id.oh_component_web_close).setOnClickListener { finish() }
        findViewById<Button>(R.id.oh_component_web_refresh).setOnClickListener { reloadActivePage() }
        findViewById<Button>(R.id.oh_component_web_copy).setOnClickListener { copyActiveAddress() }
        findViewById<Button>(R.id.oh_component_web_maintenance).setOnClickListener {
            host.launchMaintenance(this)
        }
        controlButton.setOnClickListener { activePage?.let { host.launchComponentControl(this, it.args) } }
    }

    private fun showIntent(intent: Intent) {
        val args = ComponentWebLaunchArgs.fromIntent(intent)
        if (args == null) {
            Toast.makeText(this, R.string.oh_component_web_invalid, Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        titleView.text = args.title
        controlButton.text = args.controlTitle.ifEmpty { getString(R.string.oh_service_control) }
        controlButton.visibility = if (args.hasControlEntry) View.VISIBLE else View.GONE

        val key = pageKey(args)
        val page = pages[key] ?: createPage(args).also { pages[key] = it }
        page.args = args
        page.lastUsedOrder = ++useSequence
        attachPage(page)
        if (page.webView.url == null && page.state.phase != ComponentWebLoadPhase.FAILED) {
            reload(page)
        } else {
            renderState(page)
        }
        trimPagePool()
    }

    private fun createPage(args: ComponentWebLaunchArgs): PageRecord {
        val pageView = FrameLayout(this)
        val webView = WebView(this)
        val record = PageRecord(args, pageView, webView, createFallbackView(args), ComponentWebPageState(args.loadUrl))
        configureWebView(record)
        pageView.addView(webView, matchParent())
        pageView.addView(record.fallbackView, matchParent())
        record.fallbackView.visibility = View.GONE
        return record
    }

    private fun createFallbackView(args: ComponentWebLaunchArgs): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(24), dp(24), dp(24))
            setBackgroundColor(ContextCompat.getColor(context, R.color.oh_surface))

            addView(TextView(context).apply {
                id = R.id.oh_component_web_error_title
                text = getString(R.string.oh_component_web_unavailable, args.title)
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
            val primaryLabel = if (args.hasControlEntry) {
                args.controlTitle.ifEmpty { getString(R.string.oh_service_control) }
            } else {
                getString(R.string.oh_maintenance)
            }
            actions.addView(fallbackButton(primaryLabel) {
                val current = activePage
                if (current?.args?.hasControlEntry == true) {
                    host.launchComponentControl(this@OpenHouseComponentWebActivity, current.args)
                } else {
                    host.launchMaintenance(this@OpenHouseComponentWebActivity)
                }
            })
            actions.addView(fallbackButton(getString(R.string.oh_refresh)) { reloadActivePage() })
            addView(actions)
            val copyButton = fallbackButton(getString(R.string.oh_copy_address)) { copyActiveAddress() }
            copyButton.layoutParams = (copyButton.layoutParams as LinearLayout.LayoutParams).apply {
                topMargin = dp(8)
            }
            addView(copyButton)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView(record: PageRecord) {
        record.webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            loadWithOverviewMode = true
            useWideViewPort = true
            cacheMode = WebSettings.LOAD_DEFAULT
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            setSupportMultipleWindows(false)
        }
        record.webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                record.state = record.state.loading(url.orEmpty().ifEmpty { record.args.loadUrl })
                record.fallbackView.visibility = View.GONE
                if (record === activePage) renderState(record)
            }

            override fun onPageFinished(view: WebView, url: String?) {
                if (record.state.phase != ComponentWebLoadPhase.FAILED) {
                    record.state = record.state.connected(url.orEmpty().ifEmpty { record.args.loadUrl })
                    record.fallbackView.visibility = View.GONE
                    if (record === activePage) renderState(record)
                }
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError?) {
                if (request.isForMainFrame) markUnavailable(record, request.url?.toString().orEmpty())
            }

            @Deprecated("Deprecated in Java")
            override fun onReceivedError(view: WebView, errorCode: Int, description: String?, failingUrl: String?) {
                markUnavailable(record, failingUrl.orEmpty())
            }

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                !isWebUri(request.url)

            @Deprecated("Deprecated in Java")
            override fun shouldOverrideUrlLoading(view: WebView, url: String?): Boolean =
                !isWebUri(Uri.parse(url.orEmpty()))
        }
    }

    private fun attachPage(page: PageRecord) {
        if (activePage !== page) activePage?.webView?.onPause()
        activePage = page
        (page.pageView.parent as? ViewGroup)?.removeView(page.pageView)
        browserHost.removeAllViews()
        browserHost.addView(page.pageView, matchParent())
        if (hasWindowFocus()) page.webView.onResume()
        renderState(page)
    }

    private fun reloadActivePage() {
        activePage?.let(::reload)
    }

    private fun reload(page: PageRecord) {
        val url = page.args.loadUrl
        if (url.isEmpty()) {
            markUnavailable(page, page.args.fallbackUrl)
            return
        }
        page.state = page.state.loading(url)
        page.fallbackView.visibility = View.GONE
        renderState(page)
        page.webView.loadUrl(url)
    }

    private fun markUnavailable(page: PageRecord, failingUrl: String) {
        if (destroyed) return
        val url = failingUrl.ifEmpty { page.args.loadUrl.ifEmpty { page.args.fallbackUrl } }
        page.state = page.state.failed(url)
        page.fallbackView.visibility = View.VISIBLE
        page.fallbackView.findViewById<TextView>(R.id.oh_component_web_error_title).text =
            getString(R.string.oh_component_web_unavailable, page.args.title)
        page.fallbackView.findViewById<TextView>(R.id.oh_component_web_error_detail).text =
            if (page.args.loadUrl.isEmpty()) {
                getString(R.string.oh_component_web_missing_endpoint)
            } else {
                getString(R.string.oh_component_web_unavailable_detail, url)
            }
        if (page === activePage) renderState(page)
    }

    private fun renderState(page: PageRecord) {
        if (page !== activePage) return
        statusView.text = when (page.state.phase) {
            ComponentWebLoadPhase.IDLE -> getString(R.string.oh_component_web_address, page.state.url)
            ComponentWebLoadPhase.LOADING -> getString(R.string.oh_component_web_connecting, page.state.url)
            ComponentWebLoadPhase.CONNECTED -> getString(R.string.oh_component_web_connected, page.state.url)
            ComponentWebLoadPhase.FAILED -> getString(R.string.oh_component_web_disconnected, page.state.url)
        }
    }

    private fun copyActiveAddress() {
        val page = activePage ?: return
        val address = page.state.url.ifEmpty { page.args.fallbackUrl }
        if (address.isEmpty()) {
            Toast.makeText(this, R.string.oh_component_web_no_address, Toast.LENGTH_SHORT).show()
            return
        }
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        clipboard.setPrimaryClip(ClipData.newPlainText(page.args.title, address))
        Toast.makeText(this, R.string.oh_address_copied, Toast.LENGTH_SHORT).show()
    }

    private fun returnToDesktop() {
        startActivity(OpenHouseFeature.createIntent(this, ProductRoute.DESKTOP).apply {
            addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        })
    }

    private fun trimPagePool() {
        while (pages.size > MAX_RETAINED_PAGES) {
            val oldest = pages.values
                .filterNot { it === activePage }
                .minByOrNull { it.lastUsedOrder }
                ?: return
            pages.remove(pageKey(oldest.args))
            destroyPage(oldest)
        }
    }

    private fun destroyPage(page: PageRecord) {
        page.webView.stopLoading()
        page.webView.onPause()
        page.webView.webViewClient = WebViewClient()
        page.webView.clearHistory()
        page.webView.removeAllViews()
        (page.pageView.parent as? ViewGroup)?.removeView(page.pageView)
        page.webView.destroy()
    }

    private fun fallbackButton(label: String, action: () -> Unit): Button = Button(this).apply {
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

    private fun pageKey(args: ComponentWebLaunchArgs): String = "${args.componentId}\n${args.loadUrl}"

    private fun isWebUri(uri: Uri?): Boolean {
        val scheme = uri?.scheme.orEmpty().lowercase()
        return scheme == "http" || scheme == "https"
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()

    private data class PageRecord(
        var args: ComponentWebLaunchArgs,
        val pageView: FrameLayout,
        val webView: WebView,
        val fallbackView: LinearLayout,
        var state: ComponentWebPageState,
        var lastUsedOrder: Long = 0,
    )

    companion object {
        private const val MAX_RETAINED_PAGES = 2
    }
}
