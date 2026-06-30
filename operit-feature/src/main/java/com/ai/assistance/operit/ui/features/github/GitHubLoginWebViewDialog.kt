package com.ai.assistance.operit.ui.features.github

import android.content.Intent
import android.content.Context
import android.net.Uri
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.ai.assistance.operit.R
import com.ai.assistance.operit.data.preferences.GitHubAuthPreferences
import com.ai.assistance.operit.ui.components.CustomScaffold
import com.ai.assistance.operit.ui.features.token.webview.WebViewConfig
import com.ai.assistance.operit.util.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

private const val TAG = "GitHubLoginWebView"
private enum class GitHubLoginMode {
    CHOOSER,
    EMBEDDED
}

@Composable
fun GitHubLoginWebViewDialog(
    onDismissRequest: () -> Unit,
    onLoginSuccess: (() -> Unit)? = null
) {
    var loginMode by rememberSaveable { mutableStateOf(GitHubLoginMode.CHOOSER) }

    when (loginMode) {
        GitHubLoginMode.CHOOSER -> GitHubLoginMethodDialog(
            onDismissRequest = onDismissRequest,
            onUseEmbeddedLogin = { loginMode = GitHubLoginMode.EMBEDDED }
        )
        GitHubLoginMode.EMBEDDED -> GitHubEmbeddedLoginWebViewDialog(
            onDismissRequest = onDismissRequest,
            onLoginSuccess = onLoginSuccess
        )
    }
}

@Composable
private fun GitHubLoginMethodDialog(
    onDismissRequest: () -> Unit,
    onUseEmbeddedLogin: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val coordinator = remember { GitHubOAuthCoordinator(context) }
    var isLaunchingExternal by remember { mutableStateOf(false) }

    fun launchExternalLogin() {
        if (isLaunchingExternal) {
            return
        }

        isLaunchingExternal = true
        scope.launch {
            try {
                val authorizationUrl = coordinator.createExternalAuthorizationUrl()
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(authorizationUrl)).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                Toast.makeText(
                    context,
                    context.getString(R.string.github_login_external_waiting),
                    Toast.LENGTH_SHORT
                ).show()
                onDismissRequest()
            } catch (e: Exception) {
                isLaunchingExternal = false
                AppLogger.e(TAG, "Failed to launch external GitHub login", e)
                Toast.makeText(
                    context,
                    context.getString(R.string.main_github_login_error, e.message ?: ""),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(stringResource(R.string.login_github)) },
        text = {
            Column {
                Text(stringResource(R.string.github_login_method_description))
                Spacer(modifier = Modifier.height(16.dp))
                Button(
                    onClick = onUseEmbeddedLogin,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Language, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.github_login_method_embedded)
                    )
                }
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedButton(
                    onClick = ::launchExternalLogin,
                    enabled = !isLaunchingExternal,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.OpenInBrowser, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.github_login_method_external)
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismissRequest) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GitHubEmbeddedLoginWebViewDialog(
    onDismissRequest: () -> Unit,
    onLoginSuccess: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val coordinator = remember { GitHubOAuthCoordinator(context) }
    val githubAuth = remember { GitHubAuthPreferences.getInstance(context) }
    val expectedState = rememberSaveable { GitHubAuthPreferences.createOAuthState() }
    val authorizationUrl = remember(expectedState) { githubAuth.getAuthorizationUrl(state = expectedState) }
    val webView = remember {
        WebViewConfig.createWebView(context).apply {
            settings.setSupportMultipleWindows(false)
            settings.javaScriptCanOpenWindowsAutomatically = false
        }
    }

    var isLoading by remember { mutableStateOf(true) }
    var isCompletingLogin by remember { mutableStateOf(false) }
    var hasHandledOAuthRedirect by remember { mutableStateOf(false) }

    fun reportFailure(message: String) {
        isCompletingLogin = false
        isLoading = false
        AppLogger.e(TAG, message)
        scope.launch {
            snackbarHostState.showSnackbar(message)
        }
    }

    DisposableEffect(webView) {
        webView.webViewClient =
            object : WebViewClient() {
                override fun shouldOverrideUrlLoading(
                    view: WebView?,
                    request: WebResourceRequest?
                ): Boolean {
                    if (request?.isForMainFrame != false && handleOAuthRedirect(
                            uri = request?.url,
                            expectedState = expectedState,
                            coordinator = coordinator,
                            onDismissRequest = onDismissRequest,
                            onLoginSuccess = onLoginSuccess,
                            onFailure = ::reportFailure,
                            shouldHandle = { !hasHandledOAuthRedirect },
                            onStartHandling = {
                                hasHandledOAuthRedirect = true
                                isLoading = true
                                isCompletingLogin = true
                            },
                            scope = scope,
                            context = context
                        )) {
                        return true
                    }
                    return false
                }

                override fun onPageStarted(
                    view: WebView?,
                    url: String?,
                    favicon: android.graphics.Bitmap?
                ) {
                    super.onPageStarted(view, url, favicon)
                    isLoading = true
                    if (handleOAuthRedirect(
                            uri = url?.let(Uri::parse),
                            expectedState = expectedState,
                            coordinator = coordinator,
                            onDismissRequest = onDismissRequest,
                            onLoginSuccess = onLoginSuccess,
                            onFailure = ::reportFailure,
                            shouldHandle = { !hasHandledOAuthRedirect },
                            onStartHandling = {
                                hasHandledOAuthRedirect = true
                                isLoading = true
                                isCompletingLogin = true
                            },
                            scope = scope,
                            context = context
                        )) {
                        view?.stopLoading()
                    }
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    if (!isCompletingLogin) {
                        isLoading = false
                    }
                }
            }

        onDispose {
            releaseWebView(webView)
        }
    }

    LaunchedEffect(authorizationUrl) {
        webView.loadUrl(authorizationUrl)
    }

    Dialog(
        onDismissRequest = {
            if (!isCompletingLogin) {
                onDismissRequest()
            }
        },
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnClickOutside = false
        )
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            CustomScaffold(
                topBar = {
                    TopAppBar(
                        title = { Text(stringResource(R.string.login_github)) },
                        navigationIcon = {
                            IconButton(
                                onClick = onDismissRequest,
                                enabled = !isCompletingLogin
                            ) {
                                Icon(Icons.Default.Close, contentDescription = null)
                            }
                        },
                        actions = {
                            IconButton(
                                onClick = { webView.reload() },
                                enabled = !isCompletingLogin
                            ) {
                                Icon(Icons.Default.Refresh, contentDescription = null)
                            }
                        }
                    )
                },
                snackbarHost = { SnackbarHost(hostState = snackbarHostState) }
            ) { paddingValues ->
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues)
                ) {
                    AndroidView(
                        factory = { webView },
                        modifier = Modifier.fillMaxSize()
                    )

                    if (isLoading || isCompletingLogin) {
                        LinearProgressIndicator(
                            modifier = Modifier
                                .fillMaxWidth()
                                .align(Alignment.TopCenter)
                        )
                    }
                }
            }
        }
    }
}

private fun handleOAuthRedirect(
    uri: Uri?,
    expectedState: String,
    coordinator: GitHubOAuthCoordinator,
    onDismissRequest: () -> Unit,
    onLoginSuccess: (() -> Unit)?,
    onFailure: (String) -> Unit,
    shouldHandle: () -> Boolean,
    onStartHandling: () -> Unit,
    scope: CoroutineScope,
    context: Context
): Boolean {
    if (!GitHubAuthPreferences.isOAuthRedirectUri(uri)) {
        return false
    }

    if (!shouldHandle()) {
        return true
    }

    if (uri == null) {
        onFailure(context.getString(R.string.main_github_login_failed, "Missing OAuth redirect"))
        return true
    }

    onStartHandling()

    scope.launch {
        val result = coordinator.completeLoginFromRedirect(uri, expectedState)
        result.fold(
            onSuccess = { user ->
                Toast.makeText(
                    context,
                    context.getString(R.string.main_github_login_success, user.login),
                    Toast.LENGTH_LONG
                ).show()
                onLoginSuccess?.invoke()
                onDismissRequest()
            },
            onFailure = { error ->
                val message = error.message.orEmpty()
                if (uri.getQueryParameter("error") == "access_denied") {
                    onDismissRequest()
                } else {
                    onFailure(context.getString(R.string.main_github_login_failed, message))
                }
            }
        )
    }
    return true
}

private fun releaseWebView(webView: WebView) {
    try {
        webView.stopLoading()
        webView.loadUrl("about:blank")
        webView.clearHistory()
        webView.removeAllViews()
        (webView.parent as? ViewGroup)?.removeView(webView)
        webView.destroy()
    } catch (e: Exception) {
        AppLogger.e(TAG, "Failed to release GitHub login WebView", e)
    }
}
