package com.termux.app.activities;

import android.annotation.SuppressLint;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.webkit.WebSettings;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.termux.R;
import com.termux.app.TermuxActivity;
import com.termux.app.openhouse.OpenHouseMaintainerRunner;
import com.termux.app.smallphone.SmallPhoneRuntime;
import com.termux.shared.activity.ActivityUtils;
import com.termux.shared.logger.Logger;

import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class SmallPhoneHostActivity extends AppCompatActivity {

    private static final String LOG_TAG = "SmallPhoneHost";
    private static final String ACTION_SMALLPHONE_RECOVERY = "com.termux.SMALLPHONE_RECOVERY";
    private static final int ACTION_OUTPUT_LIMIT = 700;

    private final ExecutorService backgroundExecutor = Executors.newSingleThreadExecutor();

    private SmallPhoneRuntime runtime;
    private WebView webView;
    private View hostPanel;
    private TextView statusPillView;
    private TextView headlineView;
    private TextView detailView;
    private TextView serviceManagerStatusView;
    private TextView smallPhoneStatusView;
    private TextView ccConnectStatusView;
    private TextView smallPhoneCoreStatusView;
    private EditText browserAddressView;
    private Button browserBackButton;
    private Button browserForwardButton;
    private Button browserRefreshButton;
    private Button browserGoButton;
    private Button primaryButton;
    private Button startButton;
    private Button repairButton;
    private Button externalButton;
    private volatile boolean actionInFlight;
    private SmallPhoneRuntime.Status lastStatus;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_smallphone_host);

        runtime = new SmallPhoneRuntime(this);
        webView = findViewById(R.id.smallphoneWebView);
        hostPanel = findViewById(R.id.smallphoneHostPanel);
        statusPillView = findViewById(R.id.smallphoneStatusPill);
        headlineView = findViewById(R.id.smallphoneHostHeadline);
        detailView = findViewById(R.id.smallphoneHostDetail);
        serviceManagerStatusView = findViewById(R.id.smallphoneStatusServiceManager);
        smallPhoneStatusView = findViewById(R.id.smallphoneStatusSmallPhone);
        ccConnectStatusView = findViewById(R.id.smallphoneStatusCcConnect);
        smallPhoneCoreStatusView = findViewById(R.id.smallphoneStatusCore);
        browserAddressView = findViewById(R.id.smallphoneBrowserAddress);
        browserBackButton = findViewById(R.id.buttonSmallphoneBrowserBack);
        browserForwardButton = findViewById(R.id.buttonSmallphoneBrowserForward);
        browserRefreshButton = findViewById(R.id.buttonSmallphoneBrowserRefresh);
        browserGoButton = findViewById(R.id.buttonSmallphoneBrowserGo);
        primaryButton = findViewById(R.id.buttonSmallphonePrimary);
        startButton = findViewById(R.id.buttonSmallphoneStart);
        repairButton = findViewById(R.id.buttonSmallphoneRepair);
        externalButton = findViewById(R.id.buttonSmallphoneExternal);

        configureWebView();
        bindActions();
        refreshStatus(shouldOpenWhenHealthy(getIntent()));
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!actionInFlight && (lastStatus == null || !lastStatus.isHealthy())) {
            refreshStatus(shouldOpenWhenHealthy(getIntent()));
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        showRecovery(null);
        refreshStatus(shouldOpenWhenHealthy(intent));
    }

    @Override
    protected void onDestroy() {
        backgroundExecutor.shutdownNow();
        if (webView != null) {
            webView.destroy();
        }
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.getVisibility() == View.VISIBLE && webView.canGoBack()) {
            webView.goBack();
            return;
        }
        super.onBackPressed();
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void configureWebView() {
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);
        settings.setSupportZoom(true);
        settings.setBuiltInZoomControls(true);
        settings.setDisplayZoomControls(false);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            settings.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        }

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return openOutsideWebViewIfNeeded(request == null ? null : request.getUrl());
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return openOutsideWebViewIfNeeded(url == null ? null : Uri.parse(url));
            }

            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                statusPillView.setText(R.string.smallphone_host_status_loading);
                if (url != null && !url.trim().isEmpty()) {
                    browserAddressView.setText(url);
                }
                updateBrowserButtons();
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                statusPillView.setText(R.string.smallphone_host_status_open);
                if (url != null && !url.trim().isEmpty()) {
                    browserAddressView.setText(url);
                }
                updateBrowserButtons();
            }

            @Override
            public void onReceivedError(WebView view, int errorCode, String description, String failingUrl) {
                Logger.logError(LOG_TAG, "SmallPhone WebView error " + errorCode + ": " + description);
                showRecovery(getString(R.string.smallphone_host_browser_error, description));
                refreshStatus(false);
            }
        });
    }

    private void bindActions() {
        browserBackButton.setOnClickListener(v -> {
            if (webView.getVisibility() == View.VISIBLE && webView.canGoBack()) {
                webView.goBack();
            }
            updateBrowserButtons();
        });
        browserForwardButton.setOnClickListener(v -> {
            if (webView.getVisibility() == View.VISIBLE && webView.canGoForward()) {
                webView.goForward();
            }
            updateBrowserButtons();
        });
        findViewById(R.id.buttonSmallphoneBrowserHome).setOnClickListener(v -> {
            browserAddressView.setText(SmallPhoneRuntime.SMALLPHONE_URL);
            if (lastStatus != null && lastStatus.isHealthy()) {
                openSmallPhoneWebView(true);
            } else {
                refreshStatus(true);
            }
        });
        browserGoButton.setOnClickListener(v -> loadAddressBarUrl());
        browserAddressView.setOnEditorActionListener((v, actionId, event) -> {
            boolean enterPressed = event != null
                && event.getKeyCode() == KeyEvent.KEYCODE_ENTER
                && event.getAction() == KeyEvent.ACTION_UP;
            if (actionId == EditorInfo.IME_ACTION_GO || enterPressed) {
                loadAddressBarUrl();
                return true;
            }
            return false;
        });
        browserRefreshButton.setOnClickListener(v -> {
            if (webView.getVisibility() == View.VISIBLE) {
                webView.reload();
                updateBrowserButtons();
            } else {
                refreshStatus(true);
            }
        });
        findViewById(R.id.buttonSmallphoneTopMaintenance).setOnClickListener(v -> openMaintenanceCenter());
        findViewById(R.id.buttonSmallphoneTopTerminal).setOnClickListener(v -> openTerminal());
        primaryButton.setOnClickListener(v -> {
            if (lastStatus != null && lastStatus.isHealthy()) {
                openSmallPhoneWebView(true);
            } else {
                refreshStatus(true);
            }
        });
        startButton.setOnClickListener(v -> runStartHook());
        repairButton.setOnClickListener(v -> runRepairHook());
        externalButton.setOnClickListener(v -> openExternal(SmallPhoneRuntime.SMALLPHONE_URL));
        findViewById(R.id.buttonSmallphoneMaintenance).setOnClickListener(v -> openMaintenanceCenter());
        findViewById(R.id.buttonSmallphoneTerminal).setOnClickListener(v -> openTerminal());
    }

    private void refreshStatus(boolean openWhenHealthy) {
        if (actionInFlight) {
            return;
        }
        setChecking();
        backgroundExecutor.execute(() -> {
            SmallPhoneRuntime.Status status = runtime.loadStatus();
            runOnUiThread(() -> renderStatus(status, openWhenHealthy));
        });
    }

    private void setChecking() {
        statusPillView.setText(R.string.smallphone_host_status_checking);
        headlineView.setText(R.string.smallphone_host_checking_title);
        detailView.setText(R.string.smallphone_host_checking_detail);
        primaryButton.setEnabled(false);
        startButton.setEnabled(false);
        repairButton.setEnabled(false);
    }

    private void renderStatus(SmallPhoneRuntime.Status status, boolean openWhenHealthy) {
        lastStatus = status;
        serviceManagerStatusView.setText(status.serviceManager.display());
        smallPhoneStatusView.setText(status.smallPhone.display());
        ccConnectStatusView.setText(status.ccConnect.display());
        smallPhoneCoreStatusView.setText(status.smallPhoneCore.display());
        headlineView.setText(status.headline());
        detailView.setText(status.detail());

        primaryButton.setEnabled(true);
        startButton.setEnabled(true);
        repairButton.setEnabled(true);
        primaryButton.setText(status.isHealthy()
            ? R.string.smallphone_host_open_smallphone
            : R.string.smallphone_host_refresh);
        statusPillView.setText(status.isHealthy()
            ? R.string.smallphone_host_status_ready
            : R.string.smallphone_host_status_recovery);

        if (status.isHealthy() && openWhenHealthy) {
            openSmallPhoneWebView(false);
        } else if (!status.isHealthy()) {
            showRecovery(null);
        }
    }

    private void openSmallPhoneWebView(boolean forceReload) {
        hostPanel.setVisibility(View.GONE);
        webView.setVisibility(View.VISIBLE);
        String currentUrl = webView.getUrl();
        if (forceReload || currentUrl == null || !isSmallPhoneUrl(currentUrl)) {
            loadBrowserUrl(SmallPhoneRuntime.SMALLPHONE_URL);
        } else {
            browserAddressView.setText(currentUrl);
        }
        updateBrowserButtons();
    }

    private void showRecovery(String overrideDetail) {
        webView.setVisibility(View.GONE);
        hostPanel.setVisibility(View.VISIBLE);
        if (browserAddressView.getText() == null || browserAddressView.getText().toString().trim().isEmpty()) {
            browserAddressView.setText(SmallPhoneRuntime.SMALLPHONE_URL);
        }
        updateBrowserButtons();
        if (overrideDetail != null && !overrideDetail.trim().isEmpty()) {
            detailView.setText(overrideDetail);
        }
    }

    private void loadAddressBarUrl() {
        loadBrowserUrl(normalizeBrowserTarget(browserAddressView.getText() == null
            ? ""
            : browserAddressView.getText().toString()));
    }

    private void loadBrowserUrl(String target) {
        if (target == null || target.trim().isEmpty()) {
            target = SmallPhoneRuntime.SMALLPHONE_URL;
        }
        browserAddressView.setText(target);
        hideSoftKeyboard();
        if (openOutsideWebViewIfNeeded(Uri.parse(target))) {
            return;
        }
        hostPanel.setVisibility(View.GONE);
        webView.setVisibility(View.VISIBLE);
        webView.loadUrl(target);
        updateBrowserButtons();
    }

    private String normalizeBrowserTarget(String rawTarget) {
        String target = rawTarget == null ? "" : rawTarget.trim();
        if (target.isEmpty()) {
            return SmallPhoneRuntime.SMALLPHONE_URL;
        }

        String lowerTarget = target.toLowerCase(Locale.US);
        if (lowerTarget.startsWith("http://") || lowerTarget.startsWith("https://")) {
            return target;
        }
        if (looksLikeWebAddress(target, lowerTarget)) {
            return "http://" + target;
        }
        if (hasExplicitScheme(target)) {
            return target;
        }
        return "https://www.google.com/search?q=" + Uri.encode(target);
    }

    private boolean looksLikeWebAddress(String target, String lowerTarget) {
        if (target.indexOf(' ') >= 0 || target.indexOf('\t') >= 0) {
            return false;
        }
        return lowerTarget.startsWith("localhost")
            || lowerTarget.startsWith("127.")
            || lowerTarget.startsWith("10.")
            || lowerTarget.startsWith("192.168.")
            || lowerTarget.startsWith("[::1]")
            || target.contains(".");
    }

    private boolean hasExplicitScheme(String target) {
        return target.matches("^[A-Za-z][A-Za-z0-9+.-]*:.*");
    }

    private boolean openOutsideWebViewIfNeeded(Uri uri) {
        if (uri == null) {
            return false;
        }
        String scheme = uri.getScheme();
        if (scheme == null
            || "http".equalsIgnoreCase(scheme)
            || "https".equalsIgnoreCase(scheme)) {
            return false;
        }
        openExternal(uri.toString());
        return true;
    }

    private boolean isSmallPhoneUrl(String url) {
        return url != null && url.startsWith(SmallPhoneRuntime.SMALLPHONE_URL);
    }

    private void hideSoftKeyboard() {
        InputMethodManager inputMethodManager =
            (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (inputMethodManager != null) {
            inputMethodManager.hideSoftInputFromWindow(browserAddressView.getWindowToken(), 0);
        }
        browserAddressView.clearFocus();
    }

    private void updateBrowserButtons() {
        boolean webVisible = webView != null && webView.getVisibility() == View.VISIBLE;
        browserBackButton.setEnabled(webVisible && webView.canGoBack());
        browserForwardButton.setEnabled(webVisible && webView.canGoForward());
    }

    private void runStartHook() {
        runRuntimeAction(R.string.smallphone_host_starting, true);
    }

    private void runRepairHook() {
        runRuntimeAction(R.string.smallphone_host_repairing, false);
    }

    private void runRuntimeAction(int busyMessageRes, boolean startOnly) {
        if (actionInFlight) {
            Toast.makeText(this, R.string.smallphone_host_action_busy, Toast.LENGTH_SHORT).show();
            return;
        }

        actionInFlight = true;
        statusPillView.setText(busyMessageRes);
        primaryButton.setEnabled(false);
        startButton.setEnabled(false);
        repairButton.setEnabled(false);
        Toast.makeText(this, busyMessageRes, Toast.LENGTH_SHORT).show();

        backgroundExecutor.execute(() -> {
            OpenHouseMaintainerRunner.Result result = startOnly
                ? runtime.startStack()
                : runtime.repairStack();
            SmallPhoneRuntime.Status status = runtime.loadStatus();
            runOnUiThread(() -> {
                actionInFlight = false;
                boolean healthyAfterAction = result.isSuccess() && status.isHealthy();
                renderStatus(status, healthyAfterAction);
                if (healthyAfterAction) {
                    Toast.makeText(this, R.string.smallphone_host_action_done, Toast.LENGTH_SHORT).show();
                } else {
                    showRecovery(shortOutput(result.output));
                    Toast.makeText(this, R.string.smallphone_host_action_failed, Toast.LENGTH_LONG).show();
                }
            });
        });
    }

    private boolean shouldOpenWhenHealthy(Intent intent) {
        return intent == null || !ACTION_SMALLPHONE_RECOVERY.equals(intent.getAction());
    }

    private String shortOutput(String output) {
        if (output == null || output.trim().isEmpty()) {
            return getString(R.string.smallphone_host_action_failed_detail);
        }
        String trimmed = output.trim();
        if (trimmed.length() <= ACTION_OUTPUT_LIMIT) {
            return trimmed;
        }
        return trimmed.substring(0, ACTION_OUTPUT_LIMIT) + "\n...";
    }

    private void openMaintenanceCenter() {
        ActivityUtils.startActivity(this, new Intent(this, MaintenanceCenterActivity.class));
    }

    private void openTerminal() {
        ActivityUtils.startActivity(this, new Intent(this, TermuxActivity.class));
    }

    private void openExternal(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (ActivityNotFoundException e) {
            ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (clipboard != null) {
                clipboard.setPrimaryClip(ClipData.newPlainText("SmallPhone URL", url));
            }
            Toast.makeText(this, R.string.openhouse_browser_unavailable, Toast.LENGTH_LONG).show();
        }
    }
}
