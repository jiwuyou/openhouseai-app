package com.termux.app.openhouse.webhost;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.TextView;

import com.termux.R;
import com.termux.app.TermuxActivity;
import com.termux.app.activities.OpenHouseHomeActivity;
import com.termux.app.browser.ControlledBrowserContract;
import com.termux.shared.activity.ActivityUtils;
import com.termux.shared.logger.Logger;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

public final class OpenHouseWebHostController {

    private static final String LOG_TAG = "OpenHouseWebHost";

    private final Activity activity;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final OpenHouseWebHostRuntime runtime;
    private final WebView webView;
    private final View fallbackPanel;
    private final TextView statusView;
    private final TextView detailView;
    private final Button retryButton;

    private volatile boolean destroyed;
    private volatile boolean loading;
    private boolean nativeRecoveryOpened;
    private OpenHouseWebHostRuntime.Target currentTarget;

    public OpenHouseWebHostController(Activity activity, View root) {
        if (activity == null || root == null) throw new IllegalArgumentException("activity and root are required");
        this.activity = activity;
        this.runtime = new OpenHouseWebHostRuntime(activity);
        webView = root.findViewById(R.id.openhouseWebView);
        fallbackPanel = root.findViewById(R.id.openhouseWebFallback);
        statusView = root.findViewById(R.id.openhouseWebStatus);
        detailView = root.findViewById(R.id.openhouseWebDetail);
        retryButton = root.findViewById(R.id.buttonOpenHouseWebRetry);
        configureWebView();
        bindActions(root);
    }

    public void onResume() {
        if (destroyed) return;
        webView.onResume();
        if (webView.getVisibility() != View.VISIBLE && !loading) refresh(true);
    }

    public void onPause() {
        if (!destroyed) webView.onPause();
    }

    public void onDestroy() {
        if (destroyed) return;
        destroyed = true;
        executor.shutdownNow();
        webView.stopLoading();
        webView.destroy();
    }

    public boolean handleBackPressed() {
        if (!destroyed && webView.getVisibility() == View.VISIBLE && webView.canGoBack()) {
            webView.goBack();
            return true;
        }
        return false;
    }

    public void refresh(boolean allowRepair) {
        if (destroyed || loading) return;
        loading = true;
        nativeRecoveryOpened = false;
        showFallback(activity.getString(R.string.openhouse_web_host_checking), activity.getString(R.string.openhouse_web_host_checking_detail));
        retryButton.setEnabled(false);
        try {
            executor.execute(() -> {
                OpenHouseWebHostRuntime.Result result = runtime.prepare(allowRepair);
                activity.runOnUiThread(() -> {
                    if (destroyed) return;
                    loading = false;
                    retryButton.setEnabled(true);
                    render(result);
                });
            });
        } catch (RejectedExecutionException ignored) {
            loading = false;
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void configureWebView() {
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setSupportMultipleWindows(OpenHouseWebHostRuntime.arePopupWindowsAllowed());
        settings.setJavaScriptCanOpenWindowsAutomatically(false);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            settings.setSafeBrowsingEnabled(true);
        }
        CookieManager.getInstance().setAcceptCookie(true);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return handleNavigation(request == null ? null : request.getUrl());
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return handleNavigation(url == null ? null : Uri.parse(url));
            }

            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                statusView.setText(R.string.openhouse_web_host_loading);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                statusView.setText(currentTarget == OpenHouseWebHostRuntime.Target.SERVICE_MANAGER
                    ? R.string.openhouse_web_host_service_manager_fallback
                    : R.string.openhouse_web_host_ready);
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request != null && !request.isForMainFrame()) return;
                handleMainFrameFailure(error == null ? "WebView load failed" : String.valueOf(error.getDescription()));
            }

            @Override
            public void onReceivedError(WebView view, int errorCode, String description, String failingUrl) {
                handleMainFrameFailure(description);
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onCreateWindow(WebView view, boolean isDialog, boolean isUserGesture, android.os.Message resultMsg) {
                return false;
            }
        });
    }

    private void bindActions(View root) {
        retryButton.setOnClickListener(v -> refresh(true));
        root.findViewById(R.id.buttonOpenHouseNativeRecovery).setOnClickListener(v -> openNativeRecovery());
        root.findViewById(R.id.buttonOpenHouseTerminal).setOnClickListener(v -> ActivityUtils.startActivity(activity, new Intent(activity, TermuxActivity.class)));
    }

    private void render(OpenHouseWebHostRuntime.Result result) {
        if (result.target == OpenHouseWebHostRuntime.Target.NATIVE_RECOVERY) {
            showFallback(activity.getString(R.string.openhouse_web_host_native_recovery), result.detail);
            openNativeRecovery();
            return;
        }
        if (!OpenHouseWebHostRuntime.isSafeWebViewTarget(result.target, result.url)) {
            Logger.logError(LOG_TAG, "Refusing unsafe WebView target");
            showFallback(
                activity.getString(R.string.openhouse_web_host_native_recovery),
                "Web 页面地址未通过本机回环安全校验。"
            );
            openNativeRecovery();
            return;
        }
        currentTarget = result.target;
        showWebView();
        webView.loadUrl(result.url);
    }

    private void handleMainFrameFailure(String detail) {
        if (destroyed || loading) return;
        Logger.logError(LOG_TAG, "Main frame failed: " + detail);
        if (currentTarget == OpenHouseWebHostRuntime.Target.OPENHOUSE_WEB) {
            loading = true;
            showFallback(activity.getString(R.string.openhouse_web_host_fallback_loading), detail);
            executor.execute(() -> {
                OpenHouseWebHostRuntime.Result result = runtime.serviceManagerFallback();
                activity.runOnUiThread(() -> {
                    if (destroyed) return;
                    loading = false;
                    render(result);
                });
            });
        } else {
            showFallback(activity.getString(R.string.openhouse_web_host_native_recovery), detail);
            openNativeRecovery();
        }
    }

    private boolean handleNavigation(Uri uri) {
        if (uri == null) return true;
        if (OpenHouseWebHostRuntime.isAllowedLoopbackUrl(uri.toString())) return false;
        if ("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme())) {
            try {
                activity.startActivity(new Intent(Intent.ACTION_VIEW, uri));
            } catch (ActivityNotFoundException ignored) {
            }
        }
        return true;
    }

    private void showWebView() {
        fallbackPanel.setVisibility(View.GONE);
        webView.setVisibility(View.VISIBLE);
    }

    private void showFallback(String headline, String detail) {
        webView.setVisibility(View.GONE);
        fallbackPanel.setVisibility(View.VISIBLE);
        statusView.setText(headline);
        detailView.setText(detail == null ? "" : detail);
    }

    private void openNativeRecovery() {
        if (nativeRecoveryOpened || destroyed) return;
        nativeRecoveryOpened = true;
        Intent intent = new Intent(activity, OpenHouseHomeActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        intent.putExtra(ControlledBrowserContract.EXTRA_OPENHOUSE_PAGE, "repair");
        ActivityUtils.startActivity(activity, intent);
    }
}
