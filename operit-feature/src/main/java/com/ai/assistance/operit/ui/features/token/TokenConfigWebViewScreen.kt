package com.ai.assistance.operit.ui.features.token

import android.content.Intent
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.ai.assistance.operit.R
import com.ai.assistance.operit.ui.components.CustomScaffold
import com.ai.assistance.operit.ui.features.token.components.DeepSeekRechargeChooser
import com.ai.assistance.operit.ui.features.token.components.UrlConfigDialog
import com.ai.assistance.operit.ui.features.token.model.NavDestination
import com.ai.assistance.operit.ui.features.token.model.getIconForIndex
import com.ai.assistance.operit.ui.features.token.payment.DeepSeekPaymentMode
import com.ai.assistance.operit.ui.features.token.payment.isDeepSeekTopUpPage
import com.ai.assistance.operit.ui.features.token.preferences.UrlConfigManager
import com.ai.assistance.operit.ui.features.token.webview.WebViewConfig
import com.wuxianpi.openhouse.feature.WebFileChooserController
import com.ai.assistance.operit.ui.main.LocalTopBarActions
import com.ai.assistance.operit.ui.main.components.LocalAppBarContentColor
import com.ai.assistance.operit.ui.main.components.LocalIsCurrentScreen
import com.ai.assistance.operit.util.AppLogger
import kotlinx.coroutines.launch

/** Token配置屏幕 */
@Composable
fun TokenConfigWebViewScreen(onNavigateBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val urlConfigManager = remember { UrlConfigManager(context) }
    val fileChooserHost = remember(context) {
        (context as? ComponentActivity)?.let(::WebFileChooserController)
    }
    val urlConfig by urlConfigManager.urlConfigFlow.collectAsState(
        initial = com.ai.assistance.operit.ui.features.token.model.UrlConfig()
    )

    var isLoading by remember { mutableStateOf(true) }
    var isPaymentLoading by remember { mutableStateOf(false) }
    var selectedTabIndex by remember { mutableStateOf(0) }
    var showConfigDialog by remember { mutableStateOf(false) }
    var showRechargeChooser by remember { mutableStateOf(false) }
    var paymentMode by remember { mutableStateOf<DeepSeekPaymentMode?>(null) }
    var paymentWebView by remember { mutableStateOf<WebView?>(null) }
    val currentPaymentWebView by rememberUpdatedState(paymentWebView)

    val webView = remember { WebViewConfig.createWebView(context, fileChooserHost = fileChooserHost) }
    val navDestinations = remember(urlConfig) {
        urlConfig.tabs.take(4).mapIndexed { index, tabConfig ->
            NavDestination(
                title = tabConfig.title,
                url = tabConfig.url,
                icon = getIconForIndex(index),
            )
        }
    }

    fun createWebViewClient(
        onLoadingChanged: (Boolean) -> Unit,
        onPageFinished: (String?) -> Unit = {},
    ): WebViewClient = object : WebViewClient() {
        override fun shouldOverrideUrlLoading(
            view: WebView?,
            request: WebResourceRequest?,
        ): Boolean {
            val uri = request?.url ?: return false
            val url = uri.toString()
            if (url.startsWith("alipays:") || url.startsWith("alipay:") ||
                url.startsWith("weixin:") || url.startsWith("weixins:")
            ) {
                return try {
                    context.startActivity(Intent(Intent.ACTION_VIEW, uri).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    })
                    true
                } catch (error: Exception) {
                    AppLogger.e("TokenConfigWebView", "无法打开外部应用: ${error.message}")
                    false
                }
            }
            return false
        }

        override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
            super.onPageStarted(view, url, favicon)
            onLoadingChanged(true)
        }

        override fun onPageFinished(view: WebView?, url: String?) {
            super.onPageFinished(view, url)
            onLoadingChanged(false)
            onPageFinished(url)
        }
    }

    val webViewClient = remember(navDestinations) {
        createWebViewClient(
            onLoadingChanged = { isLoading = it },
            onPageFinished = { finishedUrl ->
                finishedUrl?.let { url ->
                    navDestinations.forEachIndexed { index, destination ->
                        if (url.contains(destination.url) || destination.url.contains(url)) {
                            selectedTabIndex = index
                        }
                    }
                }
            },
        )
    }

    fun closePaymentWebView() {
        paymentWebView?.stopLoading()
        paymentWebView?.destroy()
        paymentWebView = null
        paymentMode = null
        isPaymentLoading = false
    }

    fun openPaymentWebView(url: String, mode: DeepSeekPaymentMode) {
        closePaymentWebView()
        val userAgent = when (mode) {
            DeepSeekPaymentMode.MOBILE -> WebViewConfig.MOBILE_USER_AGENT
            DeepSeekPaymentMode.DESKTOP -> WebViewConfig.DESKTOP_USER_AGENT
        }
        val view = WebViewConfig.createWebView(context, userAgent, fileChooserHost)
        view.webViewClient = createWebViewClient(
            onLoadingChanged = { isPaymentLoading = it },
        )
        paymentMode = mode
        paymentWebView = view
        isPaymentLoading = true
        view.loadUrl(url)
    }

    fun navigateTo(url: String, index: Int) {
        closePaymentWebView()
        isLoading = true
        webView.loadUrl(url)
        selectedTabIndex = index
    }

    DisposableEffect(webView) {
        webView.webViewClient = webViewClient
        if (urlConfig.signInUrl.isNotEmpty() && webView.url.isNullOrBlank()) {
            webView.loadUrl(urlConfig.signInUrl)
        }
        onDispose {
            webView.stopLoading()
            webView.destroy()
        }
    }

    LaunchedEffect(webViewClient) {
        webView.webViewClient = webViewClient
    }

    DisposableEffect(Unit) {
        onDispose {
            currentPaymentWebView?.stopLoading()
            currentPaymentWebView?.destroy()
        }
    }

    BackHandler(enabled = showRechargeChooser || paymentWebView != null) {
        if (showRechargeChooser) showRechargeChooser = false else closePaymentWebView()
    }

    val setTopBarActions = LocalTopBarActions.current
    val appBarContentColor = LocalAppBarContentColor.current
    val isCurrentScreen = LocalIsCurrentScreen.current
    LaunchedEffect(isCurrentScreen, appBarContentColor) {
        if (isCurrentScreen) {
            setTopBarActions {
                IconButton(onClick = { showConfigDialog = true }) {
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = stringResource(R.string.settings_config),
                        tint = appBarContentColor,
                    )
                }
            }
        }
    }

    if (showConfigDialog) {
        UrlConfigDialog(
            currentConfig = urlConfig,
            onSave = { newConfig ->
                scope.launch {
                    urlConfigManager.saveUrlConfig(newConfig)
                    showConfigDialog = false
                }
            },
            onDismiss = { showConfigDialog = false },
        )
    }

    if (showRechargeChooser) {
        DeepSeekRechargeChooser(
            onAlipay = {
                showRechargeChooser = false
                navDestinations.getOrNull(selectedTabIndex)?.let {
                    openPaymentWebView(it.url, DeepSeekPaymentMode.MOBILE)
                }
            },
            onWechat = {
                showRechargeChooser = false
                navDestinations.getOrNull(selectedTabIndex)?.let {
                    openPaymentWebView(it.url, DeepSeekPaymentMode.DESKTOP)
                }
            },
            onDismiss = { showRechargeChooser = false },
        )
    }

    CustomScaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = Color.White,
                shadowElevation = 2.dp,
            ) {
                Column {
                    HorizontalDivider(
                        color = Color.LightGray.copy(alpha = 0.3f),
                        thickness = 0.5.dp,
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(60.dp)
                            .background(Color.White),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        navDestinations.forEachIndexed { index, destination ->
                            val isSelected = selectedTabIndex == index
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxSize()
                                    .clickable {
                                        selectedTabIndex = index
                                        if (isDeepSeekTopUpPage(destination.url)) {
                                            closePaymentWebView()
                                            showRechargeChooser = true
                                        } else {
                                            navigateTo(destination.url, index)
                                        }
                                    }
                                    .padding(4.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center,
                            ) {
                                Icon(
                                    imageVector = destination.icon,
                                    contentDescription = destination.title,
                                    modifier = Modifier.size(24.dp),
                                    tint = if (isSelected) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        Color.Gray
                                    },
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = destination.title,
                                    fontSize = 12.sp,
                                    fontWeight = if (isSelected) FontWeight.Medium else FontWeight.Normal,
                                    color = if (isSelected) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        Color.Gray
                                    },
                                )
                            }
                        }
                    }
                }
            }
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (paymentWebView == null) {
                AndroidView(factory = { webView }, modifier = Modifier.fillMaxSize())
                if (isLoading) {
                    LinearProgressIndicator(
                        modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth(),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = Color.Transparent,
                    )
                }
            } else {
                Column(modifier = Modifier.fillMaxSize()) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = if (paymentMode == DeepSeekPaymentMode.DESKTOP) {
                                stringResource(R.string.deepseek_wechat_recharge)
                            } else {
                                stringResource(R.string.deepseek_alipay_recharge)
                            },
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = ::closePaymentWebView) {
                            Icon(Icons.Default.Close, contentDescription = stringResource(R.string.common_close))
                            Text(stringResource(R.string.common_close))
                        }
                    }
                    Box(modifier = Modifier.weight(1f)) {
                        paymentWebView?.let { view ->
                            AndroidView(factory = { view }, modifier = Modifier.fillMaxSize())
                        }
                        if (isPaymentLoading) {
                            LinearProgressIndicator(
                                modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth(),
                                color = MaterialTheme.colorScheme.primary,
                                trackColor = Color.Transparent,
                            )
                        }
                    }
                }
            }
        }
    }
}
