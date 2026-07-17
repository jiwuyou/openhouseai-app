package com.termux.app.smallphone;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import com.termux.R;
import com.termux.app.TermuxActivity;
import com.termux.app.activities.MaintenanceCenterActivity;
import com.termux.app.activities.OpenHouseHomeActivity;
import com.termux.app.openhouse.OpenHouseMaintainerRunner;
import com.termux.shared.activity.ActivityUtils;
import com.termux.shared.logger.Logger;

import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

public final class SmallPhoneHostController {

    public static final String ACTION_SMALLPHONE_RECOVERY = "com.termux.SMALLPHONE_RECOVERY";

    private static final String LOG_TAG = "SmallPhoneHost";
    private static final int ACTION_OUTPUT_LIMIT = 700;

    public interface NavigationDelegate {
        default boolean openSmallPhoneMenu() {
            return false;
        }

        default boolean openMaintenanceCenter() {
            return false;
        }

        default boolean openTerminal() {
            return false;
        }

        default boolean openExternal(String url) {
            return false;
        }
    }

    private final Activity activity;
    private final View rootView;
    private final ExecutorService backgroundExecutor = Executors.newSingleThreadExecutor();
    private final SmallPhoneRuntime runtime;
    private final NavigationDelegate defaultNavigationDelegate;
    private final WebView webView;
    private final View hostPanel;
    private final TextView statusPillView;
    private final TextView headlineView;
    private final TextView detailView;
    private final TextView serviceManagerStatusView;
    private final TextView smallPhoneStatusView;
    private final TextView ccConnectStatusView;
    private final TextView smallPhoneCoreStatusView;
    private final EditText browserAddressView;
    private final Button browserBackButton;
    private final Button browserForwardButton;
    private final Button browserRefreshButton;
    private final Button browserGoButton;
    private final Button primaryButton;
    private final Button startButton;
    private final Button repairButton;
    private final Button externalButton;

    private NavigationDelegate navigationDelegate;
    private volatile boolean actionInFlight;
    private volatile boolean destroyed;
    private SmallPhoneRuntime.Status lastStatus;

    public SmallPhoneHostController(Activity activity, View rootView) {
        if (activity == null) {
            throw new IllegalArgumentException("activity must not be null");
        }
        if (rootView == null) {
            throw new IllegalArgumentException("rootView must not be null");
        }

        this.activity = activity;
        this.rootView = rootView;
        this.runtime = new SmallPhoneRuntime(activity);
        this.defaultNavigationDelegate = new DefaultNavigationDelegate(activity);
        webView = requireView(R.id.smallphoneWebView);
        hostPanel = requireView(R.id.smallphoneHostPanel);
        statusPillView = requireView(R.id.smallphoneStatusPill);
        headlineView = requireView(R.id.smallphoneHostHeadline);
        detailView = requireView(R.id.smallphoneHostDetail);
        serviceManagerStatusView = requireView(R.id.smallphoneStatusServiceManager);
        smallPhoneStatusView = requireView(R.id.smallphoneStatusSmallPhone);
        ccConnectStatusView = requireView(R.id.smallphoneStatusCcConnect);
        smallPhoneCoreStatusView = requireView(R.id.smallphoneStatusCore);
        browserAddressView = requireView(R.id.smallphoneBrowserAddress);
        browserBackButton = requireView(R.id.buttonSmallphoneBrowserBack);
        browserForwardButton = requireView(R.id.buttonSmallphoneBrowserForward);
        browserRefreshButton = requireView(R.id.buttonSmallphoneBrowserRefresh);
        browserGoButton = requireView(R.id.buttonSmallphoneBrowserGo);
        primaryButton = requireView(R.id.buttonSmallphonePrimary);
        startButton = requireView(R.id.buttonSmallphoneStart);
        repairButton = requireView(R.id.buttonSmallphoneRepair);
        externalButton = requireView(R.id.buttonSmallphoneExternal);

        configureWebView();
        bindActions();
        // The SmallPhone web port is allocated by service-manager. Keep the
        // address empty until the published endpoint has been resolved.
        browserAddressView.setText("");
        updateBrowserButtons();
    }

    public void setNavigationDelegate(NavigationDelegate navigationDelegate) {
        this.navigationDelegate = navigationDelegate;
    }

    public void onResume() {
        onResume(false);
    }

    public void onResume(boolean openWhenHealthy) {
        if (destroyed) {
            return;
        }
        webView.onResume();
        if (!actionInFlight && (lastStatus == null || !lastStatus.isHealthy())) {
            refreshStatus(openWhenHealthy);
        }
    }

    public void onPause() {
        if (!destroyed) {
            webView.onPause();
        }
    }

    public void onDestroy() {
        if (destroyed) {
            return;
        }
        destroyed = true;
        backgroundExecutor.shutdownNow();
        webView.stopLoading();
        webView.destroy();
    }

    public boolean handleBackPressed() {
        if (!destroyed && webView.getVisibility() == View.VISIBLE && webView.canGoBack()) {
            webView.goBack();
            updateBrowserButtons();
            return true;
        }
        return false;
    }

    public void openWhenHealthy() {
        if (destroyed) {
            return;
        }
        if (lastStatus != null && lastStatus.isHealthy()) {
            openSmallPhoneWebView(true);
        } else {
            refreshStatus(true);
        }
    }

    public void refreshStatus(boolean openWhenHealthy) {
        if (destroyed || actionInFlight) {
            return;
        }
        setChecking();
        try {
            backgroundExecutor.execute(() -> {
                SmallPhoneRuntime.Status status = runtime.loadStatus();
                runOnUiThreadIfAlive(() -> renderStatus(status, openWhenHealthy));
            });
        } catch (RejectedExecutionException e) {
            Logger.logError(LOG_TAG, "SmallPhone status refresh rejected: " + e.getMessage());
        }
    }

    public void showRecovery(String overrideDetail) {
        if (destroyed) {
            return;
        }
        webView.setVisibility(View.GONE);
        hostPanel.setVisibility(View.VISIBLE);
        if (browserAddressView.getText() == null || browserAddressView.getText().toString().trim().isEmpty()) {
            browserAddressView.setText(publishedSmallPhoneUrl());
        }
        updateBrowserButtons();
        if (overrideDetail != null && !overrideDetail.trim().isEmpty()) {
            detailView.setText(overrideDetail);
        }
    }

    public SmallPhoneRuntime.Status getLastStatus() {
        return lastStatus;
    }

    public boolean isActionInFlight() {
        return actionInFlight;
    }

    public static boolean shouldOpenWhenHealthy(Intent intent) {
        return intent == null || !ACTION_SMALLPHONE_RECOVERY.equals(intent.getAction());
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
                showRecovery(activity.getString(R.string.smallphone_host_browser_error, description));
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
        requireView(R.id.buttonSmallphoneBrowserHome).setOnClickListener(v -> {
            openWhenHealthy();
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
        requireView(R.id.buttonSmallphoneTopMenu).setOnClickListener(v -> openSmallPhoneMenu());
        requireView(R.id.buttonSmallphoneTopMaintenance).setOnClickListener(v -> openMaintenanceCenter());
        requireView(R.id.buttonSmallphoneTopTerminal).setOnClickListener(v -> openTerminal());
        primaryButton.setOnClickListener(v -> openWhenHealthy());
        startButton.setOnClickListener(v -> runStartHook());
        repairButton.setOnClickListener(v -> runRepairHook());
        externalButton.setOnClickListener(v -> openExternal(publishedSmallPhoneUrl()));
        requireView(R.id.buttonSmallphoneMaintenance).setOnClickListener(v -> openMaintenanceCenter());
        requireView(R.id.buttonSmallphoneTerminal).setOnClickListener(v -> openTerminal());
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
        String publishedUrl = publishedSmallPhoneUrl();
        if (publishedUrl.isEmpty()) {
            showRecovery("SmallPhone 动态 web endpoint 尚不可用，请先检查 service-manager 和 SmallPhone 服务。");
            return;
        }
        hostPanel.setVisibility(View.GONE);
        webView.setVisibility(View.VISIBLE);
        String currentUrl = webView.getUrl();
        if (forceReload || currentUrl == null || !isSmallPhoneUrl(currentUrl)) {
            loadBrowserUrl(publishedUrl);
        } else {
            browserAddressView.setText(currentUrl);
        }
        updateBrowserButtons();
    }

    private void loadAddressBarUrl() {
        loadBrowserUrl(normalizeBrowserTarget(browserAddressView.getText() == null
            ? ""
            : browserAddressView.getText().toString()));
    }

    private void loadBrowserUrl(String target) {
        if (target == null || target.trim().isEmpty()) {
            target = publishedSmallPhoneUrl();
        }
        if (target == null || target.trim().isEmpty()) {
            showRecovery("SmallPhone 动态 web endpoint 尚不可用，请先检查 service-manager 和 SmallPhone 服务。");
            return;
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
            return publishedSmallPhoneUrl();
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
        String publishedUrl = publishedSmallPhoneUrl();
        return !publishedUrl.isEmpty() && url != null && url.startsWith(publishedUrl);
    }

    private String publishedSmallPhoneUrl() {
        SmallPhoneRuntime.Status status = lastStatus;
        if (status == null || status.smallPhone == null || !status.smallPhone.reachable) {
            return "";
        }
        String url = status.smallPhone.url == null ? "" : status.smallPhone.url.trim();
        return url;
    }

    private void hideSoftKeyboard() {
        InputMethodManager inputMethodManager =
            (InputMethodManager) activity.getSystemService(Context.INPUT_METHOD_SERVICE);
        if (inputMethodManager != null) {
            inputMethodManager.hideSoftInputFromWindow(browserAddressView.getWindowToken(), 0);
        }
        browserAddressView.clearFocus();
    }

    private void updateBrowserButtons() {
        boolean webVisible = webView.getVisibility() == View.VISIBLE;
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
            Toast.makeText(activity, R.string.smallphone_host_action_busy, Toast.LENGTH_SHORT).show();
            return;
        }

        actionInFlight = true;
        statusPillView.setText(busyMessageRes);
        primaryButton.setEnabled(false);
        startButton.setEnabled(false);
        repairButton.setEnabled(false);
        Toast.makeText(activity, busyMessageRes, Toast.LENGTH_SHORT).show();

        try {
            backgroundExecutor.execute(() -> {
                OpenHouseMaintainerRunner.Result result = startOnly
                    ? runtime.startStack()
                    : runtime.repairStack();
                SmallPhoneRuntime.Status status = runtime.loadStatus();
                runOnUiThreadIfAlive(() -> {
                    actionInFlight = false;
                    boolean healthyAfterAction = result.isSuccess() && status.isHealthy();
                    renderStatus(status, healthyAfterAction);
                    if (healthyAfterAction) {
                        Toast.makeText(activity, R.string.smallphone_host_action_done, Toast.LENGTH_SHORT).show();
                    } else {
                        showRecovery(shortOutput(result.output));
                        Toast.makeText(activity, R.string.smallphone_host_action_failed, Toast.LENGTH_LONG).show();
                    }
                });
            });
        } catch (RejectedExecutionException e) {
            actionInFlight = false;
            Logger.logError(LOG_TAG, "SmallPhone runtime action rejected: " + e.getMessage());
        }
    }

    private String shortOutput(String output) {
        if (output == null || output.trim().isEmpty()) {
            return activity.getString(R.string.smallphone_host_action_failed_detail);
        }
        String trimmed = output.trim();
        if (trimmed.length() <= ACTION_OUTPUT_LIMIT) {
            return trimmed;
        }
        return trimmed.substring(0, ACTION_OUTPUT_LIMIT) + "\n...";
    }

    private void openSmallPhoneMenu() {
        if (navigationDelegate != null && navigationDelegate.openSmallPhoneMenu()) {
            return;
        }
        defaultNavigationDelegate.openSmallPhoneMenu();
    }

    private void openMaintenanceCenter() {
        if (navigationDelegate != null && navigationDelegate.openMaintenanceCenter()) {
            return;
        }
        defaultNavigationDelegate.openMaintenanceCenter();
    }

    private void openTerminal() {
        if (navigationDelegate != null && navigationDelegate.openTerminal()) {
            return;
        }
        defaultNavigationDelegate.openTerminal();
    }

    private void openExternal(String url) {
        if (navigationDelegate != null && navigationDelegate.openExternal(url)) {
            return;
        }
        defaultNavigationDelegate.openExternal(url);
    }

    private void runOnUiThreadIfAlive(Runnable runnable) {
        if (destroyed) {
            return;
        }
        activity.runOnUiThread(() -> {
            if (!destroyed) {
                runnable.run();
            }
        });
    }

    private <T extends View> T requireView(int id) {
        T view = rootView.findViewById(id);
        if (view == null) {
            throw new IllegalArgumentException("Missing SmallPhone host view id: " + resourceName(id));
        }
        return view;
    }

    private String resourceName(int id) {
        try {
            return activity.getResources().getResourceEntryName(id);
        } catch (Resources.NotFoundException e) {
            return Integer.toString(id);
        }
    }

    private static final class DefaultNavigationDelegate implements NavigationDelegate {
        private final Activity activity;

        private DefaultNavigationDelegate(Activity activity) {
            this.activity = activity;
        }

        @Override
        public boolean openSmallPhoneMenu() {
            ActivityUtils.startActivity(activity, new Intent(activity, OpenHouseHomeActivity.class));
            return true;
        }

        @Override
        public boolean openMaintenanceCenter() {
            ActivityUtils.startActivity(activity, new Intent(activity, MaintenanceCenterActivity.class));
            return true;
        }

        @Override
        public boolean openTerminal() {
            ActivityUtils.startActivity(activity, new Intent(activity, TermuxActivity.class));
            return true;
        }

        @Override
        public boolean openExternal(String url) {
            String target = url == null ? "" : url.trim();
            if (target.isEmpty()) {
                Toast.makeText(activity,
                    "SmallPhone 动态 web endpoint 尚不可用，请先检查 service-manager。",
                    Toast.LENGTH_LONG).show();
                return true;
            }
            try {
                activity.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(target)));
            } catch (ActivityNotFoundException e) {
                ClipboardManager clipboard =
                    (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
                if (clipboard != null) {
                    clipboard.setPrimaryClip(ClipData.newPlainText("SmallPhone URL", target));
                }
                Toast.makeText(activity, R.string.openhouse_browser_unavailable, Toast.LENGTH_LONG).show();
            }
            return true;
        }
    }
}
