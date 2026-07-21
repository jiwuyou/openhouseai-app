package com.wuxianpi.openhouse.feature

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.os.Bundle
import android.view.View
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class AdvancedUiActivity : AppCompatActivity() {
    private lateinit var webView: WebView
    private lateinit var errorPanel: View
    private lateinit var status: TextView
    private lateinit var detail: TextView
    private lateinit var endpoints: AdvancedUiEndpoints
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val resolver = AdvancedEndpointResolver()
    private var target = AdvancedEndpointResolver.Target.UNAVAILABLE
    private var fallbackAttempted = false
    private var destroyed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_advanced_ui)
        endpoints = OpenHouseFeatureHosts.from(this).advancedUiEndpoints()
        webView = findViewById(R.id.oh_advanced_webview)
        errorPanel = findViewById(R.id.oh_advanced_error_panel)
        status = findViewById(R.id.oh_advanced_status)
        detail = findViewById(R.id.oh_advanced_detail)
        configureWebView()
        findViewById<Button>(R.id.oh_advanced_back).setOnClickListener { handleBack() }
        findViewById<Button>(R.id.oh_advanced_refresh).setOnClickListener { refresh() }
        findViewById<Button>(R.id.oh_advanced_retry).setOnClickListener { refresh() }
        refresh()
    }

    override fun onResume() {
        super.onResume()
        webView.onResume()
    }

    override fun onPause() {
        webView.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        destroyed = true
        executor.shutdownNow()
        webView.stopLoading()
        webView.webChromeClient = null
        webView.webViewClient = WebViewClient()
        webView.clearHistory()
        webView.removeAllViews()
        webView.destroy()
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() = handleBack()

    private fun handleBack() {
        if (webView.visibility == View.VISIBLE && webView.canGoBack()) webView.goBack() else finish()
    }

    private fun refresh() {
        fallbackAttempted = false
        target = AdvancedEndpointResolver.Target.UNAVAILABLE
        showLoading("正在检查 AionUI；不可用时将自动回退到 ai-web-ui。")
        executor.execute {
            val resolution = resolver.resolve(endpoints)
            runOnUiThread {
                if (!destroyed) renderResolution(resolution, "两个高级 UI endpoint 都不可达。")
            }
        }
    }

    private fun renderResolution(resolution: AdvancedEndpointResolver.Resolution, failure: String) {
        if (resolution.target == AdvancedEndpointResolver.Target.UNAVAILABLE) {
            showError(failure)
            return
        }
        target = resolution.target
        errorPanel.visibility = View.GONE
        webView.visibility = View.VISIBLE
        webView.loadUrl(resolution.url)
    }

    private fun handleMainFrameFailure(message: String) {
        if (destroyed) return
        if (target == AdvancedEndpointResolver.Target.AION_UI && !fallbackAttempted) {
            fallbackAttempted = true
            showLoading("AionUI 页面加载失败，正在切换 ai-web-ui。")
            executor.execute {
                val fallback = resolver.fallbackAfterLoadFailure(target, endpoints)
                runOnUiThread {
                    if (!destroyed) renderResolution(fallback, "AionUI 与 ai-web-ui 都无法加载。\n$message")
                }
            }
        } else {
            showError(message)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView() {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            cacheMode = WebSettings.LOAD_DEFAULT
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            setSupportMultipleWindows(false)
            javaScriptCanOpenWindowsAutomatically = false
        }
        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                status.setText(R.string.oh_advanced_loading)
            }

            override fun onPageFinished(view: WebView, url: String?) {
                if (view.visibility == View.VISIBLE) {
                    status.text = if (target == AdvancedEndpointResolver.Target.AION_UI) "AionUI" else "ai-web-ui"
                }
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError?) {
                if (request.isForMainFrame) handleMainFrameFailure(error?.description?.toString().orEmpty())
            }

            @Deprecated("Deprecated in Java")
            override fun onReceivedError(view: WebView, errorCode: Int, description: String?, failingUrl: String?) {
                handleMainFrameFailure(description.orEmpty())
            }
        }
    }

    private fun showLoading(message: String) {
        webView.stopLoading()
        webView.visibility = View.GONE
        errorPanel.visibility = View.VISIBLE
        status.setText(R.string.oh_advanced_loading)
        detail.text = message
        findViewById<Button>(R.id.oh_advanced_retry).visibility = View.GONE
    }

    private fun showError(message: String) {
        webView.stopLoading()
        webView.visibility = View.GONE
        errorPanel.visibility = View.VISIBLE
        status.setText(R.string.oh_advanced_failed)
        detail.text = message
        findViewById<Button>(R.id.oh_advanced_retry).visibility = View.VISIBLE
    }
}
