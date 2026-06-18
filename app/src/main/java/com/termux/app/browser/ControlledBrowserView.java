package com.termux.app.browser;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Base64;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.termux.R;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

public class ControlledBrowserView extends LinearLayout {

    public interface ExternalNavigationHandler {
        boolean onExternalNavigationRequested(@NonNull ControlledBrowserView browserView, @NonNull Uri uri);
    }

    public interface CommandCallback {
        void onResult(@NonNull ControlledBrowserCommandResult result);
    }

    private static final long DEFAULT_WAIT_TIMEOUT_MS = 5000L;
    private static final long WAIT_POLL_INTERVAL_MS = 100L;
    private static final String UTF_8 = "UTF-8";

    private final List<BrowserTab> tabs = new ArrayList<>();
    private final Map<Integer, String> domNodeSelectors = new HashMap<>();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private int nextDomNodeId = 2;

    private LinearLayout tabStripView;
    private HorizontalScrollView tabScrollView;
    private Button newTabButton;
    private Button backButton;
    private Button forwardButton;
    private Button reloadButton;
    private EditText addressView;
    private ProgressBar progressView;
    private FrameLayout webContainerView;

    private ExternalNavigationHandler externalNavigationHandler;
    private String activeTabId;
    private boolean destroyed;
    private boolean internalAddressChange;

    public ControlledBrowserView(Context context) {
        super(context);
        init(context);
    }

    public ControlledBrowserView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    public ControlledBrowserView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init(context);
    }

    public void setExternalNavigationHandler(@Nullable ExternalNavigationHandler externalNavigationHandler) {
        this.externalNavigationHandler = externalNavigationHandler;
    }

    @NonNull
    public ControlledBrowserCommandResult handleCommand(@Nullable Intent intent) {
        if (intent == null) {
            return errorResult("Missing intent");
        }
        return handleCommand(intent.getExtras());
    }

    @NonNull
    public ControlledBrowserCommandResult handleCommand(@Nullable Bundle extras) {
        if (extras == null) {
            return errorResult("Missing extras");
        }
        return handleCommand(extras.getString(ControlledBrowserContract.EXTRA_COMMAND), extras);
    }

    @NonNull
    public ControlledBrowserCommandResult handleCommand(@Nullable String command, @Nullable Bundle extras) {
        if (destroyed) {
            return errorResult("Browser has been destroyed");
        }
        if (command == null || command.trim().isEmpty()) {
            return errorResult("Missing browser command");
        }

        String normalizedCommand = command.trim().toLowerCase(Locale.US);
        switch (normalizedCommand) {
            case ControlledBrowserContract.COMMAND_OPEN:
                return handleOpenCommand(extras);
            case ControlledBrowserContract.COMMAND_NEW_TAB:
                return handleNewTabCommand(extras);
            case ControlledBrowserContract.COMMAND_SWITCH:
                return handleSwitchCommand(extras);
            case ControlledBrowserContract.COMMAND_CLOSE:
                return handleCloseCommand(extras);
            case ControlledBrowserContract.COMMAND_RELOAD:
                return reload() ? okResult("Reloaded") : errorResult("No active tab to reload");
            case ControlledBrowserContract.COMMAND_BACK:
                return goBack() ? okResult("Went back") : errorResult("Active tab cannot go back");
            case ControlledBrowserContract.COMMAND_FORWARD:
                return goForward() ? okResult("Went forward") : errorResult("Active tab cannot go forward");
            default:
                return errorResult("Unknown browser command: " + command);
        }
    }

    public void handleCommandAsync(@Nullable Intent intent, @NonNull CommandCallback callback) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post(() -> handleCommandAsync(intent, callback));
            return;
        }
        if (intent == null) {
            long startedAtMs = SystemClock.elapsedRealtime();
            PendingCommand pending = new PendingCommand(null, null, startedAtMs, callback);
            finishError(pending, "missing_intent", "Missing intent");
            return;
        }
        handleCommandAsync(intent.getExtras(), callback);
    }

    public void handleCommandAsync(@Nullable Bundle extras, @NonNull CommandCallback callback) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post(() -> handleCommandAsync(extras, callback));
            return;
        }
        long startedAtMs = SystemClock.elapsedRealtime();
        Bundle preparedExtras;
        try {
            preparedExtras = prepareAsyncExtras(extras);
        } catch (IOException | JSONException e) {
            PendingCommand pending = new PendingCommand(
                extras, getStringExtra(extras, ControlledBrowserContract.EXTRA_REQUEST_ID,
                    ControlledBrowserContract.FIELD_REQUEST_ID), startedAtMs, callback);
            finishError(pending, "request_file_error", "Unable to read browser request file: " + e.getMessage());
            return;
        }
        handleCommandAsync(
            getStringExtra(preparedExtras, ControlledBrowserContract.EXTRA_COMMAND,
                ControlledBrowserContract.FIELD_COMMAND),
            preparedExtras,
            callback,
            startedAtMs);
    }

    public void handleCommandAsync(
        @Nullable String command,
        @Nullable Bundle extras,
        @NonNull CommandCallback callback
    ) {
        handleCommandAsync(command, extras, callback, SystemClock.elapsedRealtime());
    }

    private void handleCommandAsync(
        @Nullable String command,
        @Nullable Bundle extras,
        @NonNull CommandCallback callback,
        long startedAtMs
    ) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post(() -> handleCommandAsync(command, extras, callback, startedAtMs));
            return;
        }

        PendingCommand pending = new PendingCommand(
            extras,
            getStringExtra(extras, ControlledBrowserContract.EXTRA_REQUEST_ID,
                ControlledBrowserContract.FIELD_REQUEST_ID),
            startedAtMs,
            callback);

        if (destroyed) {
            finishError(pending, "destroyed", "Browser has been destroyed");
            return;
        }
        if (command == null || command.trim().isEmpty()) {
            finishError(pending, "missing_command", "Missing browser command");
            return;
        }

        String normalizedCommand = command.trim().toLowerCase(Locale.US);
        try {
            switch (normalizedCommand) {
                case ControlledBrowserContract.COMMAND_STATUS:
                    finishOk(pending, "Status", buildStatusData());
                    return;
                case ControlledBrowserContract.COMMAND_TABS:
                    finishOk(pending, "Tabs", buildTabsData());
                    return;
                case ControlledBrowserContract.COMMAND_EVAL:
                    handleEvalCommand(pending);
                    return;
                case ControlledBrowserContract.COMMAND_TEXT:
                    handleTextCommand(pending);
                    return;
                case ControlledBrowserContract.COMMAND_HTML:
                    handleHtmlCommand(pending);
                    return;
                case ControlledBrowserContract.COMMAND_CLICK:
                    handleClickCommand(pending);
                    return;
                case ControlledBrowserContract.COMMAND_FILL:
                    handleFillCommand(pending);
                    return;
                case ControlledBrowserContract.COMMAND_WAIT:
                    handleWaitCommand(pending);
                    return;
                case ControlledBrowserContract.COMMAND_TAP:
                    handleTapCommand(pending);
                    return;
                case ControlledBrowserContract.COMMAND_TYPE:
                    handleTypeCommand(pending);
                    return;
                case ControlledBrowserContract.COMMAND_SCROLL:
                    handleScrollCommand(pending);
                    return;
                case ControlledBrowserContract.COMMAND_SCREENSHOT:
                    handleScreenshotCommand(pending, false);
                    return;
                case ControlledBrowserContract.COMMAND_CDP:
                    handleCdpCommand(pending);
                    return;
                case ControlledBrowserContract.COMMAND_RUN:
                    handleRunCommand(pending);
                    return;
                default:
                    ControlledBrowserCommandResult syncResult =
                        handleCommand(command, extras).withRequest(pending.requestId, elapsedMs(pending));
                    finishAsync(pending, syncResult);
            }
        } catch (RuntimeException | JSONException | IOException e) {
            finishError(pending, "command_error", "Browser command failed: " + e.getMessage());
        }
    }

    @NonNull
    public String open(@NonNull String url) {
        throwIfDestroyed();
        BrowserTab tab = getOrCreateActiveTab();
        loadInTab(tab, url);
        switchToTab(tab.id);
        return tab.id;
    }

    @NonNull
    public String newTab(@Nullable String url) {
        return newTab(url, true);
    }

    @NonNull
    public String newTab(@Nullable String url, boolean activate) {
        throwIfDestroyed();
        BrowserTab tab = createTab(url, null);
        if (activate) {
            switchToTab(tab.id);
        }
        renderTabs();
        return tab.id;
    }

    public boolean switchToTab(@Nullable String tabId) {
        throwIfDestroyed();
        BrowserTab tab = findTabById(tabId);
        if (tab == null) {
            return false;
        }
        BrowserTab previous = getActiveTab();
        if (previous != null && previous.webView.getParent() == webContainerView) {
            webContainerView.removeView(previous.webView);
        }
        activeTabId = tab.id;
        if (tab.webView.getParent() != webContainerView) {
            if (tab.webView.getParent() instanceof FrameLayout) {
                ((FrameLayout) tab.webView.getParent()).removeView(tab.webView);
            }
            webContainerView.addView(tab.webView, new FrameLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        }
        updateAddress(tab);
        renderTabs();
        updateNavigationButtons();
        return true;
    }

    public boolean switchToTab(int tabIndex) {
        BrowserTab tab = findTabByIndex(tabIndex);
        return tab != null && switchToTab(tab.id);
    }

    public boolean closeTab(@Nullable String tabId) {
        throwIfDestroyed();
        BrowserTab tab = tabId == null ? getActiveTab() : findTabById(tabId);
        if (tab == null) {
            return false;
        }

        int removedIndex = tabs.indexOf(tab);
        boolean wasActive = tab.id.equals(activeTabId);
        tabs.remove(tab);
        if (tab.webView.getParent() == webContainerView) {
            webContainerView.removeView(tab.webView);
        }
        destroyWebView(tab.webView);

        if (tabs.isEmpty()) {
            BrowserTab blankTab = createTab("about:blank", null);
            switchToTab(blankTab.id);
        } else if (wasActive) {
            int nextIndex = Math.min(Math.max(removedIndex - 1, 0), tabs.size() - 1);
            switchToTab(tabs.get(nextIndex).id);
        } else {
            renderTabs();
            updateNavigationButtons();
        }
        return true;
    }

    public boolean closeTab(int tabIndex) {
        BrowserTab tab = findTabByIndex(tabIndex);
        return tab != null && closeTab(tab.id);
    }

    public boolean reload() {
        throwIfDestroyed();
        BrowserTab tab = getActiveTab();
        if (tab == null) {
            return false;
        }
        tab.webView.reload();
        updateNavigationButtons();
        return true;
    }

    public boolean goBack() {
        throwIfDestroyed();
        BrowserTab tab = getActiveTab();
        if (tab == null || !tab.webView.canGoBack()) {
            updateNavigationButtons();
            return false;
        }
        tab.webView.goBack();
        updateNavigationButtons();
        return true;
    }

    public boolean goForward() {
        throwIfDestroyed();
        BrowserTab tab = getActiveTab();
        if (tab == null || !tab.webView.canGoForward()) {
            updateNavigationButtons();
            return false;
        }
        tab.webView.goForward();
        updateNavigationButtons();
        return true;
    }

    @Nullable
    public String getActiveTabId() {
        return activeTabId;
    }

    @Nullable
    public String getActiveUrl() {
        BrowserTab tab = getActiveTab();
        if (tab == null) {
            return null;
        }
        String webUrl = tab.webView.getUrl();
        return webUrl == null || webUrl.trim().isEmpty() ? tab.url : webUrl;
    }

    public int getTabCount() {
        return tabs.size();
    }

    @NonNull
    public List<String> getTabIds() {
        List<String> ids = new ArrayList<>(tabs.size());
        for (BrowserTab tab : tabs) {
            ids.add(tab.id);
        }
        return ids;
    }

    public void onHostPause() {
        for (BrowserTab tab : tabs) {
            tab.webView.onPause();
        }
    }

    public void onHostResume() {
        for (BrowserTab tab : tabs) {
            tab.webView.onResume();
        }
    }

    public void destroy() {
        if (destroyed) {
            return;
        }
        destroyed = true;
        if (webContainerView != null) {
            webContainerView.removeAllViews();
        }
        for (BrowserTab tab : tabs) {
            destroyWebView(tab.webView);
        }
        tabs.clear();
        domNodeSelectors.clear();
        nextDomNodeId = 2;
        activeTabId = null;
        if (tabStripView != null) {
            tabStripView.removeAllViews();
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
    }

    private void init(Context context) {
        setOrientation(VERTICAL);
        LayoutInflater.from(context).inflate(R.layout.view_controlled_browser, this, true);

        tabStripView = findViewById(R.id.controlledBrowserTabs);
        tabScrollView = findViewById(R.id.controlledBrowserTabScroll);
        newTabButton = findViewById(R.id.controlledBrowserNewTab);
        backButton = findViewById(R.id.controlledBrowserBack);
        forwardButton = findViewById(R.id.controlledBrowserForward);
        reloadButton = findViewById(R.id.controlledBrowserReload);
        addressView = findViewById(R.id.controlledBrowserAddress);
        progressView = findViewById(R.id.controlledBrowserProgress);
        webContainerView = findViewById(R.id.controlledBrowserWebContainer);

        bindUi();
        newTab("about:blank", true);
    }

    private void bindUi() {
        newTabButton.setOnClickListener(v -> newTab("about:blank", true));
        backButton.setOnClickListener(v -> goBack());
        forwardButton.setOnClickListener(v -> goForward());
        reloadButton.setOnClickListener(v -> reload());
        addressView.setOnEditorActionListener((view, actionId, event) -> {
            boolean enterPressed = event != null
                && event.getKeyCode() == KeyEvent.KEYCODE_ENTER
                && event.getAction() == KeyEvent.ACTION_UP;
            if (actionId == EditorInfo.IME_ACTION_GO || enterPressed) {
                open(addressView.getText() == null ? "" : addressView.getText().toString());
                hideSoftKeyboard();
                return true;
            }
            return false;
        });
        addressView.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {}

            @Override
            public void afterTextChanged(Editable s) {
                if (!internalAddressChange) {
                    updateNavigationButtons();
                }
            }
        });
    }

    private ControlledBrowserCommandResult handleOpenCommand(@Nullable Bundle extras) {
        String url = extras == null ? null : extras.getString(ControlledBrowserContract.EXTRA_URL);
        if (url == null || url.trim().isEmpty()) {
            return errorResult("Open command requires " + ControlledBrowserContract.EXTRA_URL);
        }

        BrowserTab target = resolveTargetTab(extras);
        if (target == null) {
            target = getOrCreateActiveTab();
        }
        loadInTab(target, url);
        switchToTab(target.id);
        return okResult("Opened");
    }

    private ControlledBrowserCommandResult handleNewTabCommand(@Nullable Bundle extras) {
        String url = extras == null ? null : extras.getString(ControlledBrowserContract.EXTRA_URL);
        String title = extras == null ? null : extras.getString(ControlledBrowserContract.EXTRA_TITLE);
        boolean activate = extras == null
            || !extras.containsKey(ControlledBrowserContract.EXTRA_ACTIVATE)
            || extras.getBoolean(ControlledBrowserContract.EXTRA_ACTIVATE);
        BrowserTab tab = createTab(url, title);
        if (activate) {
            switchToTab(tab.id);
        }
        renderTabs();
        return ControlledBrowserCommandResult.ok("Created tab", activeTabId, getActiveUrl(), tabs.size());
    }

    private ControlledBrowserCommandResult handleSwitchCommand(@Nullable Bundle extras) {
        BrowserTab tab = resolveTargetTab(extras);
        if (tab == null) {
            return errorResult("Switch command requires a valid "
                + ControlledBrowserContract.EXTRA_TAB_ID + " or "
                + ControlledBrowserContract.EXTRA_TAB_INDEX);
        }
        return switchToTab(tab.id) ? okResult("Switched tab") : errorResult("Unable to switch tab");
    }

    private ControlledBrowserCommandResult handleCloseCommand(@Nullable Bundle extras) {
        BrowserTab tab = resolveTargetTab(extras);
        boolean closed = tab == null ? closeTab((String) null) : closeTab(tab.id);
        return closed ? okResult("Closed tab") : errorResult("Unable to close tab");
    }

    private void handleEvalCommand(@NonNull PendingCommand pending) throws JSONException {
        BrowserTab tab = resolveRequiredCommandTab(pending);
        if (tab == null) {
            return;
        }
        JSONObject params = getParamsObject(pending.extras);
        String script = firstNonBlank(
            getStringParam(params, "expression", null),
            getStringParam(params, "code", null),
            getPayload(pending.extras));
        if (isBlank(script)) {
            finishError(pending, "missing_payload", "Eval command requires JavaScript in payload or params.expression");
            return;
        }
        evaluateJavascript(tab, script, rawResult -> {
            try {
                JSONObject data = new JSONObject();
                data.put("result", parseJavascriptValue(rawResult));
                data.put("rawResult", rawResult == null ? JSONObject.NULL : rawResult);
                finishOk(pending, "Evaluated", data);
            } catch (JSONException e) {
                finishError(pending, "json_error", "Unable to encode eval result: " + e.getMessage());
            }
        });
    }

    private void handleTextCommand(@NonNull PendingCommand pending) throws JSONException {
        BrowserTab tab = resolveRequiredCommandTab(pending);
        if (tab == null) {
            return;
        }
        JSONObject params = getParamsObject(pending.extras);
        String selector = firstNonBlank(getStringParam(params, "selector", null), getPayload(pending.extras));
        evaluateJavascript(tab, buildTextScript(selector), rawResult ->
            finishElementData(pending, "Read text", selector, rawResult));
    }

    private void handleHtmlCommand(@NonNull PendingCommand pending) throws JSONException {
        BrowserTab tab = resolveRequiredCommandTab(pending);
        if (tab == null) {
            return;
        }
        JSONObject params = getParamsObject(pending.extras);
        String selector = firstNonBlank(getStringParam(params, "selector", null), getPayload(pending.extras));
        evaluateJavascript(tab, buildHtmlScript(selector), rawResult ->
            finishElementData(pending, "Read HTML", selector, rawResult));
    }

    private void handleClickCommand(@NonNull PendingCommand pending) throws JSONException {
        BrowserTab tab = resolveRequiredCommandTab(pending);
        if (tab == null) {
            return;
        }
        JSONObject params = getParamsObject(pending.extras);
        String selector = firstNonBlank(
            getStringParam(params, "selector", null),
            getStringExtra(pending.extras, "selector"),
            getPayload(pending.extras));
        if (isBlank(selector)) {
            finishError(pending, "missing_selector", "Click command requires params.selector or payload");
            return;
        }
        evaluateJavascript(tab, buildClickScript(selector), rawResult ->
            finishElementData(pending, "Clicked", selector, rawResult));
    }

    private void handleFillCommand(@NonNull PendingCommand pending) throws JSONException {
        BrowserTab tab = resolveRequiredCommandTab(pending);
        if (tab == null) {
            return;
        }
        JSONObject params = getParamsObject(pending.extras);
        String payload = getPayload(pending.extras);
        String selector = firstNonBlank(
            getStringParam(params, "selector", null),
            getStringExtra(pending.extras, "selector"),
            payload);
        String value = firstNonBlank(
            getStringParam(params, "value", null),
            getStringParam(params, "text", null),
            getStringExtra(pending.extras, "value", "text"));
        if (value == null && !TextUtils.equals(selector, payload)) {
            value = payload;
        }
        if (value == null) {
            value = "";
        }
        if (isBlank(selector)) {
            finishError(pending, "missing_selector", "Fill command requires params.selector or payload");
            return;
        }
        evaluateJavascript(tab, buildFillScript(selector, value), rawResult ->
            finishElementData(pending, "Filled", selector, rawResult));
    }

    private void handleWaitCommand(@NonNull PendingCommand pending) throws JSONException {
        BrowserTab tab = resolveRequiredCommandTab(pending);
        if (tab == null) {
            return;
        }
        JSONObject params = getParamsObject(pending.extras);
        String payload = getPayload(pending.extras);
        String selector = firstNonBlank(
            getStringParam(params, "selector", null),
            getStringExtra(pending.extras, "selector"));
        String expression = getStringParam(params, "expression", null);
        String expectedText = firstNonBlank(
            getStringParam(params, "text", null),
            getStringParam(params, "containsText", null));
        if (selector == null && expression == null && expectedText == null) {
            selector = payload;
        }
        long timeoutMs = getTimeoutMs(pending.extras, DEFAULT_WAIT_TIMEOUT_MS);
        long deadlineMs = SystemClock.elapsedRealtime() + Math.max(0L, timeoutMs);
        pollWaitCondition(pending, tab, selector, expression, expectedText, deadlineMs);
    }

    private void pollWaitCondition(
        @NonNull PendingCommand pending,
        @NonNull BrowserTab tab,
        @Nullable String selector,
        @Nullable String expression,
        @Nullable String expectedText,
        long deadlineMs
    ) {
        if (isBlank(selector) && isBlank(expression) && isBlank(expectedText) && !tab.loading) {
            try {
                JSONObject data = new JSONObject();
                data.put("ready", true);
                data.put("loading", false);
                finishOk(pending, "Wait condition met", data);
            } catch (JSONException e) {
                finishError(pending, "json_error", "Unable to encode wait result: " + e.getMessage());
            }
            return;
        }

        evaluateJavascript(tab, buildWaitScript(selector, expression, expectedText), rawResult -> {
            try {
                JSONObject data = objectFromJavascriptResult(rawResult);
                if (data.optBoolean("error", false)) {
                    finishError(pending, "wait_expression_error", data.optString("message", "Wait expression failed"));
                    return;
                }
                if (data.optBoolean("ready", false)) {
                    finishOk(pending, "Wait condition met", data);
                    return;
                }
                if (SystemClock.elapsedRealtime() >= deadlineMs) {
                    finishError(pending, "timeout", "Timed out waiting for browser condition");
                    return;
                }
                mainHandler.postDelayed(
                    () -> pollWaitCondition(pending, tab, selector, expression, expectedText, deadlineMs),
                    WAIT_POLL_INTERVAL_MS);
            } catch (JSONException e) {
                finishError(pending, "json_error", "Unable to decode wait result: " + e.getMessage());
            }
        });
    }

    private void handleTapCommand(@NonNull PendingCommand pending) throws JSONException {
        BrowserTab tab = resolveRequiredCommandTab(pending);
        if (tab == null) {
            return;
        }
        JSONObject params = getParamsObject(pending.extras);
        double x = getDoubleParam(params, "x", Double.NaN);
        double y = getDoubleParam(params, "y", Double.NaN);
        if (isFinite(x) && isFinite(y)) {
            finishTapAt(pending, tab, (float) x, (float) y);
            return;
        }

        String selector = firstNonBlank(
            getStringParam(params, "selector", null),
            getStringExtra(pending.extras, "selector"),
            getPayload(pending.extras));
        if (isBlank(selector)) {
            finishError(pending, "missing_coordinates", "Tap command requires x/y coordinates or a selector");
            return;
        }
        evaluateJavascript(tab, buildElementCenterScript(selector), rawResult -> {
            try {
                JSONObject data = objectFromJavascriptResult(rawResult);
                if (!data.optBoolean("found", false)) {
                    finishError(pending, "selector_not_found", "No element matches selector: " + selector);
                    return;
                }
                finishTapAt(pending, tab, (float) data.optDouble("x"), (float) data.optDouble("y"));
            } catch (JSONException e) {
                finishError(pending, "json_error", "Unable to decode tap target: " + e.getMessage());
            }
        });
    }

    private void finishTapAt(@NonNull PendingCommand pending, @NonNull BrowserTab tab, float x, float y) {
        long now = SystemClock.uptimeMillis();
        MotionEvent down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x, y, 0);
        MotionEvent up = MotionEvent.obtain(now, now + 35L, MotionEvent.ACTION_UP, x, y, 0);
        try {
            tab.webView.dispatchTouchEvent(down);
            tab.webView.dispatchTouchEvent(up);
            JSONObject data = new JSONObject();
            data.put("x", x);
            data.put("y", y);
            finishOk(pending, "Tapped", data);
        } catch (JSONException e) {
            finishError(pending, "json_error", "Unable to encode tap result: " + e.getMessage());
        } finally {
            down.recycle();
            up.recycle();
        }
    }

    private void handleTypeCommand(@NonNull PendingCommand pending) throws JSONException {
        BrowserTab tab = resolveRequiredCommandTab(pending);
        if (tab == null) {
            return;
        }
        JSONObject params = getParamsObject(pending.extras);
        String text = firstNonBlank(
            getStringParam(params, "text", null),
            getStringParam(params, "value", null),
            getStringExtra(pending.extras, "text", "value"),
            getPayload(pending.extras));
        if (text == null) {
            text = "";
        }
        evaluateJavascript(tab, buildTypeScript(text), rawResult ->
            finishElementData(pending, "Typed", null, rawResult));
    }

    private void handleScrollCommand(@NonNull PendingCommand pending) throws JSONException {
        BrowserTab tab = resolveRequiredCommandTab(pending);
        if (tab == null) {
            return;
        }
        JSONObject params = getParamsObject(pending.extras);
        double deltaX = getDoubleParam(params, "deltaX",
            getDoubleParam(params, "dx", getDoubleParam(params, "x", 0D)));
        double deltaY = getDoubleParam(params, "deltaY",
            getDoubleParam(params, "dy", getDoubleParam(params, "y", 0D)));
        evaluateJavascript(tab, buildScrollScript(deltaX, deltaY), rawResult -> {
            try {
                finishOk(pending, "Scrolled", objectFromJavascriptResult(rawResult));
            } catch (JSONException e) {
                finishError(pending, "json_error", "Unable to decode scroll result: " + e.getMessage());
            }
        });
    }

    private void handleScreenshotCommand(@NonNull PendingCommand pending, boolean cdpResultShape)
        throws JSONException, IOException {
        BrowserTab tab = resolveRequiredCommandTab(pending);
        if (tab == null) {
            return;
        }
        String output = firstNonBlank(
            getOutput(pending.extras),
            getStringParam(getParamsObject(pending.extras), "output", null));
        JSONObject screenshot = captureScreenshot(tab, output);
        if (cdpResultShape) {
            JSONObject cdpResult = new JSONObject();
            cdpResult.put("data", screenshot.optString("data", ""));
            finishCdpOk(pending, "Page.captureScreenshot", cdpResult);
        } else {
            finishOk(pending, "Captured screenshot", screenshot);
        }
    }

    private void handleCdpCommand(@NonNull PendingCommand pending) throws JSONException, IOException {
        CdpRequest request = parseCdpRequest(pending);
        if (isBlank(request.method)) {
            finishError(pending, "missing_method", "CDP command requires method");
            return;
        }

        switch (request.method) {
            case "Browser.getVersion":
                finishCdpOk(pending, request.method, buildCdpBrowserVersion());
                return;
            case "Target.getTargets":
                finishCdpOk(pending, request.method, buildCdpTargets());
                return;
            case "Target.activateTarget":
                handleCdpActivateTarget(pending, request);
                return;
            case "Page.navigate":
                handleCdpNavigate(pending, request);
                return;
            case "Page.reload":
                handleCdpReload(pending, request);
                return;
            case "Page.captureScreenshot":
                handleScreenshotCommand(pending, true);
                return;
            case "Runtime.evaluate":
                handleCdpRuntimeEvaluate(pending, request);
                return;
            case "DOM.getDocument":
                finishCdpOk(pending, request.method, buildCdpDocument());
                return;
            case "DOM.querySelector":
                handleCdpQuerySelector(pending, request);
                return;
            case "DOM.getOuterHTML":
                handleCdpGetOuterHtml(pending, request);
                return;
            case "Input.dispatchMouseEvent":
                handleCdpDispatchMouseEvent(pending, request);
                return;
            case "Input.dispatchKeyEvent":
                handleCdpDispatchKeyEvent(pending, request);
                return;
            default:
                finishError(pending, "unsupported_cdp_method", "Unsupported CDP method: " + request.method);
        }
    }

    private void handleRunCommand(@NonNull PendingCommand pending) throws JSONException {
        String payload = getPayload(pending.extras);
        if (isBlank(payload)) {
            finishError(pending, "missing_payload", "Run command requires a JSON array or object payload");
            return;
        }

        Object parsed = new JSONTokener(payload).nextValue();
        JSONArray steps;
        if (parsed instanceof JSONArray) {
            steps = (JSONArray) parsed;
        } else if (parsed instanceof JSONObject) {
            steps = ((JSONObject) parsed).optJSONArray("steps");
            if (steps == null && ((JSONObject) parsed).has("command")) {
                steps = new JSONArray();
                steps.put(parsed);
            }
        } else {
            steps = null;
        }
        if (steps == null) {
            finishError(pending, "invalid_run_payload", "Run payload must be an array or contain a steps array");
            return;
        }
        runStep(pending, steps, 0, new JSONArray());
    }

    private void runStep(
        @NonNull PendingCommand parent,
        @NonNull JSONArray steps,
        int index,
        @NonNull JSONArray results
    ) {
        if (index >= steps.length()) {
            try {
                JSONObject data = new JSONObject();
                data.put("steps", results);
                data.put("stepCount", steps.length());
                finishOk(parent, "Run completed", data);
            } catch (JSONException e) {
                finishError(parent, "json_error", "Unable to encode run result: " + e.getMessage());
            }
            return;
        }

        JSONObject step = steps.optJSONObject(index);
        if (step == null) {
            finishError(parent, "invalid_run_step", "Run step " + index + " must be a JSON object");
            return;
        }

        String command = step.optString(ControlledBrowserContract.FIELD_COMMAND, "");
        if (isBlank(command)) {
            finishError(parent, "missing_step_command", "Run step " + index + " is missing command");
            return;
        }
        if (ControlledBrowserContract.COMMAND_RUN.equals(command)) {
            finishError(parent, "nested_run_not_supported", "Nested run commands are not supported");
            return;
        }

        Bundle stepExtras = new Bundle(parent.extras);
        stepExtras.remove(ControlledBrowserContract.EXTRA_REQUEST_FILE);
        stepExtras.remove(ControlledBrowserContract.EXTRA_RESULT_FILE);
        stepExtras.putString(ControlledBrowserContract.EXTRA_COMMAND, command);
        try {
            mergeJsonRequest(stepExtras, step);
        } catch (JSONException e) {
            finishError(parent, "invalid_run_step", "Unable to parse run step " + index + ": " + e.getMessage());
            return;
        }

        handleCommandAsync(stepExtras, stepResult -> {
            results.put(stepResult.toJsonObject());
            if (!stepResult.isSuccessful()) {
                try {
                    JSONObject data = new JSONObject();
                    data.put("failedStep", index);
                    data.put("steps", results);
                    finishAsync(parent, ControlledBrowserCommandResult.error(
                        "Run failed at step " + index,
                        activeTabId,
                        getActiveUrl(),
                        tabs.size(),
                        parent.requestId,
                        data,
                        elapsedMs(parent)));
                } catch (JSONException e) {
                    finishError(parent, "json_error", "Unable to encode failed run result: " + e.getMessage());
                }
                return;
            }
            runStep(parent, steps, index + 1, results);
        });
    }

    private Bundle prepareAsyncExtras(@Nullable Bundle extras) throws IOException, JSONException {
        Bundle prepared = extras == null ? new Bundle() : new Bundle(extras);
        String requestFile = getStringExtra(prepared, ControlledBrowserContract.EXTRA_REQUEST_FILE,
            ControlledBrowserContract.FIELD_REQUEST_FILE);
        if (isBlank(requestFile)) {
            return prepared;
        }

        String requestText = readTextFile(requestFile);
        Object parsed = new JSONTokener(requestText).nextValue();
        if (!(parsed instanceof JSONObject)) {
            throw new JSONException("Browser request file must contain a JSON object");
        }
        mergeJsonRequest(prepared, (JSONObject) parsed);
        return prepared;
    }

    private void mergeJsonRequest(@NonNull Bundle target, @NonNull JSONObject request) throws JSONException {
        copyJsonField(target, request, ControlledBrowserContract.FIELD_COMMAND, ControlledBrowserContract.EXTRA_COMMAND);
        copyJsonField(target, request, ControlledBrowserContract.FIELD_URL, ControlledBrowserContract.EXTRA_URL);
        copyJsonField(target, request, ControlledBrowserContract.FIELD_TAB_ID, ControlledBrowserContract.EXTRA_TAB_ID);
        copyJsonField(target, request, ControlledBrowserContract.FIELD_TAB_INDEX, ControlledBrowserContract.EXTRA_TAB_INDEX);
        copyJsonField(target, request, ControlledBrowserContract.FIELD_TITLE, ControlledBrowserContract.EXTRA_TITLE);
        copyJsonField(target, request, ControlledBrowserContract.FIELD_ACTIVATE, ControlledBrowserContract.EXTRA_ACTIVATE);
        copyJsonField(target, request, ControlledBrowserContract.FIELD_REQUEST_ID, ControlledBrowserContract.EXTRA_REQUEST_ID);
        copyJsonField(target, request, ControlledBrowserContract.FIELD_REQUEST_FILE, ControlledBrowserContract.EXTRA_REQUEST_FILE);
        copyJsonField(target, request, ControlledBrowserContract.FIELD_RESULT_FILE, ControlledBrowserContract.EXTRA_RESULT_FILE);
        copyJsonField(target, request, ControlledBrowserContract.FIELD_TIMEOUT_MS, ControlledBrowserContract.EXTRA_TIMEOUT_MS);
        copyJsonField(target, request, ControlledBrowserContract.FIELD_PAYLOAD, ControlledBrowserContract.EXTRA_PAYLOAD);
        copyJsonField(target, request, ControlledBrowserContract.FIELD_OUTPUT, ControlledBrowserContract.EXTRA_OUTPUT);
        copyJsonField(target, request, ControlledBrowserContract.FIELD_METHOD, ControlledBrowserContract.EXTRA_METHOD);
        copyJsonField(target, request, ControlledBrowserContract.FIELD_PARAMS, ControlledBrowserContract.EXTRA_PARAMS);

        JSONArray names = request.names();
        if (names == null) {
            return;
        }
        for (int i = 0; i < names.length(); i++) {
            String key = names.getString(i);
            if (!target.containsKey(key)) {
                putJsonValue(target, key, request.opt(key));
            }
        }
    }

    private void copyJsonField(
        @NonNull Bundle target,
        @NonNull JSONObject request,
        @NonNull String jsonKey,
        @NonNull String bundleKey
    ) throws JSONException {
        if (request.has(jsonKey)) {
            putJsonValue(target, bundleKey, request.get(jsonKey));
        }
    }

    private void putJsonValue(@NonNull Bundle target, @NonNull String key, @Nullable Object value)
        throws JSONException {
        if (value == null || value == JSONObject.NULL) {
            return;
        }
        if (value instanceof Boolean) {
            target.putBoolean(key, (Boolean) value);
        } else if (value instanceof Integer) {
            target.putInt(key, (Integer) value);
        } else if (value instanceof Long) {
            target.putLong(key, (Long) value);
        } else if (value instanceof Number) {
            target.putDouble(key, ((Number) value).doubleValue());
        } else if (value instanceof JSONObject || value instanceof JSONArray) {
            target.putString(key, value.toString());
        } else {
            target.putString(key, String.valueOf(value));
        }
    }

    private void finishElementData(
        @NonNull PendingCommand pending,
        @NonNull String message,
        @Nullable String selector,
        @Nullable String rawResult
    ) {
        try {
            JSONObject data = objectFromJavascriptResult(rawResult);
            if (!isBlank(selector) && data.has("found") && !data.optBoolean("found")) {
                finishError(pending, "selector_not_found", "No element matches selector: " + selector);
                return;
            }
            finishOk(pending, message, data);
        } catch (JSONException e) {
            finishError(pending, "json_error", "Unable to decode browser result: " + e.getMessage());
        }
    }

    private void evaluateJavascript(
        @NonNull BrowserTab tab,
        @NonNull String script,
        @NonNull JsResultCallback callback
    ) {
        tab.webView.evaluateJavascript(script, callback::onResult);
    }

    private BrowserTab resolveRequiredCommandTab(@NonNull PendingCommand pending) {
        BrowserTab tab = resolveTargetTab(pending.extras);
        if (tab == null) {
            tab = getActiveTab();
        }
        if (tab == null) {
            finishError(pending, "missing_tab", "No active browser tab");
        }
        return tab;
    }

    private JSONObject captureScreenshot(@NonNull BrowserTab tab, @Nullable String output)
        throws IOException, JSONException {
        int width = tab.webView.getWidth();
        int height = tab.webView.getHeight();
        if (width <= 0) {
            width = Math.max(1, webContainerView.getWidth());
        }
        if (height <= 0) {
            height = Math.max(1, webContainerView.getHeight());
        }

        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        try {
            Canvas canvas = new Canvas(bitmap);
            tab.webView.draw(canvas);
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, outputStream);
        } finally {
            bitmap.recycle();
        }

        byte[] pngBytes = outputStream.toByteArray();
        if (!isBlank(output)) {
            writeBytes(output, pngBytes);
        }

        JSONObject data = new JSONObject();
        data.put("mimeType", "image/png");
        data.put("width", width);
        data.put("height", height);
        data.put("data", Base64.encodeToString(pngBytes, Base64.NO_WRAP));
        data.put("output", isBlank(output) ? JSONObject.NULL : output);
        return data;
    }

    private JSONObject buildStatusData() throws JSONException {
        BrowserTab activeTab = getActiveTab();
        JSONObject data = new JSONObject();
        data.put("activeTabId", activeTabId == null ? JSONObject.NULL : activeTabId);
        data.put("activeUrl", getActiveUrl() == null ? JSONObject.NULL : getActiveUrl());
        data.put("tabCount", tabs.size());
        data.put("loading", activeTab != null && activeTab.loading);
        data.put("canGoBack", activeTab != null && activeTab.webView.canGoBack());
        data.put("canGoForward", activeTab != null && activeTab.webView.canGoForward());
        data.put("tabs", buildTabArray());
        return data;
    }

    private JSONObject buildTabsData() throws JSONException {
        JSONObject data = new JSONObject();
        data.put("tabs", buildTabArray());
        data.put("activeTabId", activeTabId == null ? JSONObject.NULL : activeTabId);
        data.put("tabCount", tabs.size());
        return data;
    }

    private JSONArray buildTabArray() throws JSONException {
        JSONArray array = new JSONArray();
        for (int i = 0; i < tabs.size(); i++) {
            array.put(buildTabData(tabs.get(i), i));
        }
        return array;
    }

    private JSONObject buildTabData(@NonNull BrowserTab tab, int index) throws JSONException {
        JSONObject object = new JSONObject();
        object.put("id", tab.id);
        object.put("targetId", tab.id);
        object.put("index", index);
        object.put("title", tab.title);
        object.put("url", currentUrl(tab));
        object.put("active", tab.id.equals(activeTabId));
        object.put("loading", tab.loading);
        object.put("progress", tab.progress);
        return object;
    }

    private JSONObject buildCdpBrowserVersion() throws JSONException {
        BrowserTab tab = getActiveTab();
        JSONObject result = new JSONObject();
        result.put("protocolVersion", "1.3");
        result.put("product", "Android WebView");
        result.put("revision", Build.VERSION.RELEASE);
        result.put("userAgent", tab == null ? "" : tab.webView.getSettings().getUserAgentString());
        result.put("jsVersion", "");
        return result;
    }

    private JSONObject buildCdpTargets() throws JSONException {
        JSONObject result = new JSONObject();
        JSONArray targetInfos = new JSONArray();
        for (BrowserTab tab : tabs) {
            JSONObject target = new JSONObject();
            target.put("targetId", tab.id);
            target.put("type", "page");
            target.put("title", tab.title);
            target.put("url", currentUrl(tab));
            target.put("attached", tab.id.equals(activeTabId));
            target.put("canAccessOpener", false);
            target.put("browserContextId", "default");
            targetInfos.put(target);
        }
        result.put("targetInfos", targetInfos);
        return result;
    }

    private JSONObject buildCdpDocument() throws JSONException {
        JSONObject root = new JSONObject();
        root.put("nodeId", 1);
        root.put("backendNodeId", 1);
        root.put("nodeType", 9);
        root.put("nodeName", "#document");
        root.put("localName", "");
        root.put("nodeValue", "");
        root.put("documentURL", getActiveUrl() == null ? "" : getActiveUrl());

        JSONObject result = new JSONObject();
        result.put("root", root);
        return result;
    }

    private void handleCdpActivateTarget(@NonNull PendingCommand pending, @NonNull CdpRequest request)
        throws JSONException {
        String targetId = getStringParam(request.params, "targetId", null);
        if (isBlank(targetId) || !switchToTab(targetId)) {
            finishError(pending, "target_not_found", "No tab matches targetId: " + targetId);
            return;
        }
        finishCdpOk(pending, request.method, new JSONObject());
    }

    private void handleCdpNavigate(@NonNull PendingCommand pending, @NonNull CdpRequest request)
        throws JSONException {
        String url = getStringParam(request.params, "url", null);
        if (isBlank(url)) {
            finishError(pending, "missing_url", "Page.navigate requires params.url");
            return;
        }
        BrowserTab tab = resolveCdpTab(request.params);
        if (tab == null) {
            tab = getOrCreateActiveTab();
        }
        loadInTab(tab, url);
        switchToTab(tab.id);

        JSONObject result = new JSONObject();
        result.put("frameId", tab.id);
        result.put("loaderId", UUID.randomUUID().toString());
        finishCdpOk(pending, request.method, result);
    }

    private void handleCdpReload(@NonNull PendingCommand pending, @NonNull CdpRequest request)
        throws JSONException {
        BrowserTab tab = resolveCdpTab(request.params);
        if (tab == null) {
            tab = getActiveTab();
        }
        if (tab == null) {
            finishError(pending, "missing_tab", "No active browser tab");
            return;
        }
        tab.webView.reload();
        finishCdpOk(pending, request.method, new JSONObject());
    }

    private void handleCdpRuntimeEvaluate(@NonNull PendingCommand pending, @NonNull CdpRequest request)
        throws JSONException {
        BrowserTab tab = resolveCdpTab(request.params);
        if (tab == null) {
            tab = resolveRequiredCommandTab(pending);
        }
        if (tab == null) {
            return;
        }
        String expression = getStringParam(request.params, "expression", null);
        if (isBlank(expression)) {
            finishError(pending, "missing_expression", "Runtime.evaluate requires params.expression");
            return;
        }
        evaluateJavascript(tab, expression, rawResult -> {
            try {
                Object value = parseJavascriptValue(rawResult);
                JSONObject remoteObject = new JSONObject();
                putRemoteObjectValue(remoteObject, value);
                JSONObject result = new JSONObject();
                result.put("result", remoteObject);
                finishCdpOk(pending, request.method, result);
            } catch (JSONException e) {
                finishError(pending, "json_error", "Unable to encode Runtime.evaluate result: " + e.getMessage());
            }
        });
    }

    private void handleCdpQuerySelector(@NonNull PendingCommand pending, @NonNull CdpRequest request)
        throws JSONException {
        BrowserTab tab = resolveRequiredCommandTab(pending);
        if (tab == null) {
            return;
        }
        String selector = getStringParam(request.params, "selector", null);
        if (isBlank(selector)) {
            finishError(pending, "missing_selector", "DOM.querySelector requires params.selector");
            return;
        }
        evaluateJavascript(tab, buildElementExistsScript(selector), rawResult -> {
            try {
                JSONObject data = objectFromJavascriptResult(rawResult);
                JSONObject result = new JSONObject();
                if (data.optBoolean("found", false)) {
                    int nodeId = nextDomNodeId++;
                    domNodeSelectors.put(nodeId, selector);
                    result.put("nodeId", nodeId);
                } else {
                    result.put("nodeId", 0);
                }
                finishCdpOk(pending, request.method, result);
            } catch (JSONException e) {
                finishError(pending, "json_error", "Unable to encode DOM.querySelector result: " + e.getMessage());
            }
        });
    }

    private void handleCdpGetOuterHtml(@NonNull PendingCommand pending, @NonNull CdpRequest request)
        throws JSONException {
        BrowserTab tab = resolveRequiredCommandTab(pending);
        if (tab == null) {
            return;
        }
        int nodeId = getIntParam(request.params, "nodeId", 1);
        String selector = getStringParam(request.params, "selector", domNodeSelectors.get(nodeId));
        evaluateJavascript(tab, buildHtmlScript(nodeId == 1 ? null : selector), rawResult -> {
            try {
                JSONObject htmlData = objectFromJavascriptResult(rawResult);
                JSONObject result = new JSONObject();
                result.put("outerHTML", htmlData.optString("html", ""));
                finishCdpOk(pending, request.method, result);
            } catch (JSONException e) {
                finishError(pending, "json_error", "Unable to encode DOM.getOuterHTML result: " + e.getMessage());
            }
        });
    }

    private void handleCdpDispatchMouseEvent(@NonNull PendingCommand pending, @NonNull CdpRequest request)
        throws JSONException {
        BrowserTab tab = resolveCdpTab(request.params);
        if (tab == null) {
            tab = resolveRequiredCommandTab(pending);
        }
        if (tab == null) {
            return;
        }

        String type = getStringParam(request.params, "type", "");
        float x = (float) getDoubleParam(request.params, "x", 0D);
        float y = (float) getDoubleParam(request.params, "y", 0D);
        if ("mouseWheel".equals(type)) {
            double deltaX = getDoubleParam(request.params, "deltaX", 0D);
            double deltaY = getDoubleParam(request.params, "deltaY", 0D);
            evaluateJavascript(tab, buildScrollScript(deltaX, deltaY), rawResult -> {
                try {
                    finishCdpOk(pending, request.method, objectFromJavascriptResult(rawResult));
                } catch (JSONException e) {
                    finishError(pending, "json_error", "Unable to encode mouse wheel result: " + e.getMessage());
                }
            });
            return;
        }

        int action;
        if ("mousePressed".equals(type)) {
            action = MotionEvent.ACTION_DOWN;
        } else if ("mouseReleased".equals(type)) {
            action = MotionEvent.ACTION_UP;
        } else {
            action = MotionEvent.ACTION_MOVE;
        }
        long now = SystemClock.uptimeMillis();
        MotionEvent event = MotionEvent.obtain(now, now, action, x, y, 0);
        try {
            tab.webView.dispatchTouchEvent(event);
        } finally {
            event.recycle();
        }
        finishCdpOk(pending, request.method, new JSONObject());
    }

    private void handleCdpDispatchKeyEvent(@NonNull PendingCommand pending, @NonNull CdpRequest request)
        throws JSONException {
        BrowserTab tab = resolveCdpTab(request.params);
        if (tab == null) {
            tab = resolveRequiredCommandTab(pending);
        }
        if (tab == null) {
            return;
        }

        String type = getStringParam(request.params, "type", "");
        String text = firstNonBlank(
            getStringParam(request.params, "text", null),
            getStringParam(request.params, "unmodifiedText", null));
        if (!isBlank(text) && ("char".equals(type) || "keyDown".equals(type) || "rawKeyDown".equals(type))) {
            evaluateJavascript(tab, buildTypeScript(text), rawResult -> {
                try {
                    finishCdpOk(pending, request.method, objectFromJavascriptResult(rawResult));
                } catch (JSONException e) {
                    finishError(pending, "json_error", "Unable to encode key result: " + e.getMessage());
                }
            });
            return;
        }

        int windowsVirtualKeyCode = getIntParam(request.params, "windowsVirtualKeyCode", 0);
        int nativeVirtualKeyCode = getIntParam(request.params, "nativeVirtualKeyCode", windowsVirtualKeyCode);
        if (nativeVirtualKeyCode > 0) {
            int action = "keyUp".equals(type) ? KeyEvent.ACTION_UP : KeyEvent.ACTION_DOWN;
            tab.webView.dispatchKeyEvent(new KeyEvent(action, nativeVirtualKeyCode));
        }
        finishCdpOk(pending, request.method, new JSONObject());
    }

    private void finishCdpOk(
        @NonNull PendingCommand pending,
        @NonNull String method,
        @NonNull JSONObject cdpResult
    ) throws JSONException {
        JSONObject data = new JSONObject();
        data.put("method", method);
        data.put("result", cdpResult);
        finishOk(pending, "CDP " + method, data);
    }

    private void finishOk(
        @NonNull PendingCommand pending,
        @NonNull String message,
        @Nullable JSONObject data
    ) {
        finishAsync(pending, ControlledBrowserCommandResult.ok(
            message, activeTabId, getActiveUrl(), tabs.size(), pending.requestId, data, elapsedMs(pending)));
    }

    private void finishError(
        @NonNull PendingCommand pending,
        @NonNull String code,
        @NonNull String message
    ) {
        JSONObject error = new JSONObject();
        try {
            error.put("code", code);
            error.put("message", message);
        } catch (JSONException e) {
            throw new IllegalStateException("Failed to build browser error JSON", e);
        }
        finishAsync(pending, ControlledBrowserCommandResult.error(
            message, activeTabId, getActiveUrl(), tabs.size(), pending.requestId, error, elapsedMs(pending)));
    }

    private void finishAsync(
        @NonNull PendingCommand pending,
        @NonNull ControlledBrowserCommandResult result
    ) {
        pending.callback.onResult(result);
    }

    private long elapsedMs(@NonNull PendingCommand pending) {
        return Math.max(0L, SystemClock.elapsedRealtime() - pending.startedAtMs);
    }

    private CdpRequest parseCdpRequest(@NonNull PendingCommand pending) throws JSONException {
        JSONObject payload = getPayloadObject(pending.extras);
        JSONObject params = getParamsObject(pending.extras);
        String method = firstNonBlank(
            getStringExtra(pending.extras, ControlledBrowserContract.EXTRA_METHOD, ControlledBrowserContract.FIELD_METHOD),
            payload == null ? null : payload.optString("method", null));
        if (payload != null && payload.optJSONObject("params") != null && params.length() == 0) {
            params = payload.optJSONObject("params");
        }
        Object id = payload == null ? null : payload.opt("id");
        return new CdpRequest(method, params == null ? new JSONObject() : params, id);
    }

    private BrowserTab resolveCdpTab(@Nullable JSONObject params) {
        if (params == null) {
            return null;
        }
        String targetId = firstNonBlank(
            getStringParam(params, "targetId", null),
            getStringParam(params, "tabId", null));
        if (!isBlank(targetId)) {
            return findTabById(targetId);
        }
        int tabIndex = getIntParam(params, "tabIndex", -1);
        return tabIndex >= 0 ? findTabByIndex(tabIndex) : null;
    }

    private JSONObject getParamsObject(@Nullable Bundle extras) throws JSONException {
        Object raw = getFirstBundleValue(extras, ControlledBrowserContract.EXTRA_PARAMS,
            ControlledBrowserContract.FIELD_PARAMS, "params");
        JSONObject parsed = asJsonObject(raw);
        if (parsed != null) {
            return parsed;
        }
        JSONObject payload = getPayloadObject(extras);
        if (payload != null && payload.optJSONObject("params") != null) {
            return payload.optJSONObject("params");
        }
        if (payload != null) {
            return payload;
        }
        return new JSONObject();
    }

    @Nullable
    private JSONObject getPayloadObject(@Nullable Bundle extras) throws JSONException {
        String payload = getPayload(extras);
        if (isBlank(payload)) {
            return null;
        }
        try {
            return asJsonObject(payload);
        } catch (JSONException ignored) {
            return null;
        }
    }

    @Nullable
    private JSONObject asJsonObject(@Nullable Object value) throws JSONException {
        if (value == null || value == JSONObject.NULL) {
            return null;
        }
        if (value instanceof JSONObject) {
            return (JSONObject) value;
        }
        if (value instanceof Bundle) {
            return bundleToJson((Bundle) value);
        }
        if (value instanceof String) {
            String text = ((String) value).trim();
            if (text.isEmpty()) {
                return null;
            }
            Object parsed = new JSONTokener(text).nextValue();
            return parsed instanceof JSONObject ? (JSONObject) parsed : null;
        }
        return null;
    }

    private JSONObject bundleToJson(@NonNull Bundle bundle) throws JSONException {
        JSONObject object = new JSONObject();
        for (String key : bundle.keySet()) {
            Object value = bundle.get(key);
            object.put(key, value == null ? JSONObject.NULL : value);
        }
        return object;
    }

    @Nullable
    private Object getFirstBundleValue(@Nullable Bundle extras, @NonNull String... keys) {
        if (extras == null) {
            return null;
        }
        for (String key : keys) {
            if (extras.containsKey(key)) {
                Object value = extras.get(key);
                if (value != null) {
                    return value;
                }
            }
        }
        return null;
    }

    @Nullable
    private String getPayload(@Nullable Bundle extras) {
        return getStringExtra(extras, ControlledBrowserContract.EXTRA_PAYLOAD,
            ControlledBrowserContract.FIELD_PAYLOAD, "payload");
    }

    @Nullable
    private String getOutput(@Nullable Bundle extras) {
        return getStringExtra(extras, ControlledBrowserContract.EXTRA_OUTPUT,
            ControlledBrowserContract.FIELD_OUTPUT, "output");
    }

    private long getTimeoutMs(@Nullable Bundle extras, long defaultValue) {
        Object value = getFirstBundleValue(extras, ControlledBrowserContract.EXTRA_TIMEOUT_MS,
            ControlledBrowserContract.FIELD_TIMEOUT_MS, "timeoutMs");
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        if (value instanceof String && !isBlank((String) value)) {
            try {
                return Long.parseLong(((String) value).trim());
            } catch (NumberFormatException ignored) {
            }
        }
        return defaultValue;
    }

    @Nullable
    private String getStringExtra(@Nullable Bundle extras, @NonNull String... keys) {
        Object value = getFirstBundleValue(extras, keys);
        if (value == null || value == JSONObject.NULL) {
            return null;
        }
        return value instanceof String ? (String) value : String.valueOf(value);
    }

    @Nullable
    private String getStringParam(
        @Nullable JSONObject params,
        @NonNull String key,
        @Nullable String defaultValue
    ) {
        if (params == null || !params.has(key) || params.isNull(key)) {
            return defaultValue;
        }
        Object value = params.opt(key);
        return value == null || value == JSONObject.NULL ? defaultValue : String.valueOf(value);
    }

    private int getIntParam(@Nullable JSONObject params, @NonNull String key, int defaultValue) {
        if (params == null || !params.has(key) || params.isNull(key)) {
            return defaultValue;
        }
        Object value = params.opt(key);
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private double getDoubleParam(@Nullable JSONObject params, @NonNull String key, double defaultValue) {
        if (params == null || !params.has(key) || params.isNull(key)) {
            return defaultValue;
        }
        Object value = params.opt(key);
        if (value instanceof Number) {
            return ((Number) value).doubleValue();
        }
        try {
            return Double.parseDouble(String.valueOf(value));
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private JSONObject objectFromJavascriptResult(@Nullable String rawResult) throws JSONException {
        Object parsed = parseJavascriptValue(rawResult);
        if (parsed instanceof JSONObject) {
            return (JSONObject) parsed;
        }
        if (parsed instanceof String) {
            String text = ((String) parsed).trim();
            if (!text.isEmpty()) {
                Object nested = new JSONTokener(text).nextValue();
                if (nested instanceof JSONObject) {
                    return (JSONObject) nested;
                }
            }
        }
        JSONObject object = new JSONObject();
        object.put("value", parsed == null ? JSONObject.NULL : parsed);
        return object;
    }

    @Nullable
    private Object parseJavascriptValue(@Nullable String rawResult) throws JSONException {
        if (rawResult == null || "undefined".equals(rawResult)) {
            return JSONObject.NULL;
        }
        Object parsed = new JSONTokener(rawResult).nextValue();
        return parsed == null ? JSONObject.NULL : parsed;
    }

    private void putRemoteObjectValue(@NonNull JSONObject remoteObject, @Nullable Object value)
        throws JSONException {
        if (value == null || value == JSONObject.NULL) {
            remoteObject.put("type", "object");
            remoteObject.put("subtype", "null");
            remoteObject.put("value", JSONObject.NULL);
        } else if (value instanceof Boolean) {
            remoteObject.put("type", "boolean");
            remoteObject.put("value", value);
        } else if (value instanceof Number) {
            remoteObject.put("type", "number");
            remoteObject.put("value", value);
        } else if (value instanceof String) {
            remoteObject.put("type", "string");
            remoteObject.put("value", value);
        } else {
            remoteObject.put("type", "object");
            remoteObject.put("value", value);
        }
        remoteObject.put("description", value == null || value == JSONObject.NULL ? "null" : String.valueOf(value));
    }

    private String buildTextScript(@Nullable String selector) {
        return "(function(){"
            + "var s=" + jsonString(selector) + ";"
            + "var e=s?document.querySelector(s):(document.body||document.documentElement);"
            + "if(!e){return JSON.stringify({found:false,selector:s,text:''});}"
            + "var t=(typeof e.innerText==='string'?e.innerText:e.textContent)||'';"
            + "return JSON.stringify({found:true,selector:s,text:t});"
            + "})()";
    }

    private String buildHtmlScript(@Nullable String selector) {
        return "(function(){"
            + "var s=" + jsonString(selector) + ";"
            + "var e=s?document.querySelector(s):document.documentElement;"
            + "if(!e){return JSON.stringify({found:false,selector:s,html:''});}"
            + "return JSON.stringify({found:true,selector:s,html:e.outerHTML||''});"
            + "})()";
    }

    private String buildClickScript(@NonNull String selector) {
        return "(function(){"
            + "var s=" + jsonString(selector) + ";"
            + "var e=document.querySelector(s);"
            + "if(!e){return JSON.stringify({found:false,selector:s});}"
            + "if(e.scrollIntoView){e.scrollIntoView({block:'center',inline:'center'});}"
            + "e.focus&&e.focus();"
            + "e.click();"
            + "return JSON.stringify({found:true,selector:s});"
            + "})()";
    }

    private String buildFillScript(@NonNull String selector, @NonNull String value) {
        return "(function(){"
            + "var s=" + jsonString(selector) + ";"
            + "var v=" + jsonString(value) + ";"
            + "var e=document.querySelector(s);"
            + "if(!e){return JSON.stringify({found:false,selector:s});}"
            + "if(e.scrollIntoView){e.scrollIntoView({block:'center',inline:'center'});}"
            + "e.focus&&e.focus();"
            + "if('value' in e){e.value=v;}else{e.textContent=v;}"
            + "e.dispatchEvent(new Event('input',{bubbles:true}));"
            + "e.dispatchEvent(new Event('change',{bubbles:true}));"
            + "return JSON.stringify({found:true,selector:s,value:v});"
            + "})()";
    }

    private String buildWaitScript(
        @Nullable String selector,
        @Nullable String expression,
        @Nullable String expectedText
    ) {
        return "(function(){"
            + "var selector=" + jsonString(selector) + ";"
            + "var expression=" + jsonString(expression) + ";"
            + "var expectedText=" + jsonString(expectedText) + ";"
            + "var ready=true;"
            + "var found=true;"
            + "if(selector){found=!!document.querySelector(selector);ready=ready&&found;}"
            + "if(expectedText){ready=ready&&(((document.body&&document.body.innerText)||document.documentElement.innerText||'').indexOf(expectedText)>=0);}"
            + "if(expression){try{var fn;try{fn=new Function('return ('+expression+');');}catch(e){fn=new Function(expression);}ready=ready&&!!fn();}catch(e){return JSON.stringify({ready:false,error:true,message:String(e)});}}"
            + "if(!selector&&!expectedText&&!expression){ready=document.readyState==='complete'||document.readyState==='interactive';}"
            + "return JSON.stringify({ready:!!ready,found:!!found,readyState:document.readyState});"
            + "})()";
    }

    private String buildElementCenterScript(@NonNull String selector) {
        return "(function(){"
            + "var s=" + jsonString(selector) + ";"
            + "var e=document.querySelector(s);"
            + "if(!e){return JSON.stringify({found:false,selector:s});}"
            + "var r=e.getBoundingClientRect();"
            + "return JSON.stringify({found:true,selector:s,x:r.left+r.width/2,y:r.top+r.height/2});"
            + "})()";
    }

    private String buildElementExistsScript(@NonNull String selector) {
        return "(function(){"
            + "var s=" + jsonString(selector) + ";"
            + "return JSON.stringify({found:!!document.querySelector(s),selector:s});"
            + "})()";
    }

    private String buildTypeScript(@NonNull String text) {
        return "(function(){"
            + "var t=" + jsonString(text) + ";"
            + "var e=document.activeElement;"
            + "if(!e||e===document.body){return JSON.stringify({found:false,text:t});}"
            + "if('value' in e){"
            + "var start=typeof e.selectionStart==='number'?e.selectionStart:e.value.length;"
            + "var end=typeof e.selectionEnd==='number'?e.selectionEnd:start;"
            + "e.value=e.value.slice(0,start)+t+e.value.slice(end);"
            + "var pos=start+t.length;if(e.setSelectionRange){e.setSelectionRange(pos,pos);}"
            + "}else{e.textContent=(e.textContent||'')+t;}"
            + "e.dispatchEvent(new Event('input',{bubbles:true}));"
            + "e.dispatchEvent(new Event('change',{bubbles:true}));"
            + "return JSON.stringify({found:true,text:t});"
            + "})()";
    }

    private String buildScrollScript(double deltaX, double deltaY) {
        return "(function(){"
            + "window.scrollBy(" + deltaX + "," + deltaY + ");"
            + "return JSON.stringify({scrollX:window.scrollX,scrollY:window.scrollY,deltaX:"
            + deltaX + ",deltaY:" + deltaY + "});"
            + "})()";
    }

    private String jsonString(@Nullable String value) {
        return value == null ? "null" : JSONObject.quote(value);
    }

    private boolean isFinite(double value) {
        return !Double.isNaN(value) && !Double.isInfinite(value);
    }

    private String currentUrl(@NonNull BrowserTab tab) {
        String webUrl = tab.webView.getUrl();
        return webUrl == null || webUrl.trim().isEmpty() ? tab.url : webUrl;
    }

    private String readTextFile(@NonNull String path) throws IOException {
        File file = validateAppFilesPath(path, ".openhouse-browser" + File.separator + "requests");
        StringBuilder builder = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
            new InputStreamReader(new FileInputStream(file), UTF_8))) {
            char[] buffer = new char[4096];
            int read;
            while ((read = reader.read(buffer)) >= 0) {
                builder.append(buffer, 0, read);
            }
        }
        return builder.toString();
    }

    private void writeBytes(@NonNull String path, @NonNull byte[] bytes) throws IOException {
        File file = validateAppFilesPath(path, null);
        File parent = file.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("Unable to create directory " + parent);
        }
        try (FileOutputStream outputStream = new FileOutputStream(file)) {
            outputStream.write(bytes);
        }
    }

    private File validateAppFilesPath(@NonNull String path, @Nullable String requiredSegment)
        throws IOException {
        File file = new File(path).getCanonicalFile();
        File dataDir = new File(getContext().getApplicationInfo().dataDir, "files").getCanonicalFile();
        String filePath = file.getPath();
        String allowedPrefix = dataDir.getPath() + File.separator;
        if (!filePath.startsWith(allowedPrefix)) {
            throw new IOException("Path outside app files directory: " + filePath);
        }
        if (requiredSegment != null
            && !filePath.contains(File.separator + requiredSegment + File.separator)) {
            throw new IOException("Path outside " + requiredSegment + ": " + filePath);
        }
        return file;
    }

    @Nullable
    private String firstNonBlank(@Nullable String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (!isBlank(value)) {
                return value;
            }
        }
        return null;
    }

    private boolean isBlank(@Nullable String value) {
        return value == null || value.trim().isEmpty();
    }

    private BrowserTab getOrCreateActiveTab() {
        BrowserTab tab = getActiveTab();
        if (tab != null) {
            return tab;
        }
        tab = createTab("about:blank", null);
        switchToTab(tab.id);
        return tab;
    }

    private BrowserTab createTab(@Nullable String url, @Nullable String title) {
        BrowserTab tab = new BrowserTab(
            UUID.randomUUID().toString(),
            title == null || title.trim().isEmpty() ? getContext().getString(R.string.controlled_browser_new_tab) : title,
            "about:blank",
            createWebView());
        tabs.add(tab);
        if (url != null && !url.trim().isEmpty()) {
            loadInTab(tab, url);
        }
        return tab;
    }

    private void loadInTab(@NonNull BrowserTab tab, @NonNull String rawUrl) {
        String url = normalizeBrowserTarget(rawUrl);
        tab.url = url;
        tab.webView.loadUrl(url);
        if (tab.id.equals(activeTabId)) {
            updateAddress(tab);
        }
        updateNavigationButtons();
    }

    @SuppressLint("SetJavaScriptEnabled")
    private WebView createWebView() {
        WebView webView = new WebView(getContext());
        webView.setLayoutParams(new FrameLayout.LayoutParams(
            LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

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
                return shouldOverrideNavigation(request == null ? null : request.getUrl());
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return shouldOverrideNavigation(url == null ? null : Uri.parse(url));
            }

            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                BrowserTab tab = findTabByWebView(view);
                if (tab != null) {
                    tab.url = url == null ? tab.url : url;
                    tab.loading = true;
                    if (tab.id.equals(activeTabId)) {
                        updateAddress(tab);
                    }
                }
                updateNavigationButtons();
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                BrowserTab tab = findTabByWebView(view);
                if (tab != null) {
                    tab.url = url == null ? tab.url : url;
                    tab.loading = false;
                    if (tab.id.equals(activeTabId)) {
                        updateAddress(tab);
                    }
                }
                updateNavigationButtons();
                renderTabs();
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onReceivedTitle(WebView view, String title) {
                BrowserTab tab = findTabByWebView(view);
                if (tab == null || title == null || title.trim().isEmpty()) {
                    return;
                }
                tab.title = title;
                renderTabs();
            }

            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                BrowserTab tab = findTabByWebView(view);
                if (tab != null) {
                    tab.progress = newProgress;
                    tab.loading = newProgress < 100;
                }
                BrowserTab activeTab = getActiveTab();
                if (activeTab != null && activeTab.webView == view) {
                    progressView.setProgress(newProgress);
                    progressView.setVisibility(newProgress >= 100 ? View.GONE : View.VISIBLE);
                }
                updateNavigationButtons();
            }
        });

        return webView;
    }

    private boolean shouldOverrideNavigation(@Nullable Uri uri) {
        if (uri == null) {
            return false;
        }
        String scheme = uri.getScheme();
        if (scheme == null
            || "http".equalsIgnoreCase(scheme)
            || "https".equalsIgnoreCase(scheme)
            || "about".equalsIgnoreCase(scheme)) {
            return false;
        }
        return externalNavigationHandler == null
            || externalNavigationHandler.onExternalNavigationRequested(this, uri);
    }

    private void renderTabs() {
        tabStripView.removeAllViews();
        for (BrowserTab tab : tabs) {
            tabStripView.addView(createTabView(tab));
        }
        tabScrollView.post(() -> {
            int activeIndex = indexOfTab(activeTabId);
            if (activeIndex >= 0 && activeIndex < tabStripView.getChildCount()) {
                View activeView = tabStripView.getChildAt(activeIndex);
                tabScrollView.smoothScrollTo(activeView.getLeft(), 0);
            }
        });
    }

    private View createTabView(BrowserTab tab) {
        boolean active = tab.id.equals(activeTabId);
        LinearLayout tabView = new LinearLayout(getContext());
        tabView.setOrientation(HORIZONTAL);
        tabView.setGravity(Gravity.CENTER_VERTICAL);
        tabView.setPadding(dp(10), 0, dp(4), 0);
        tabView.setBackground(makeTabBackground(active));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(172), dp(40));
        params.setMargins(0, 0, dp(8), 0);
        tabView.setLayoutParams(params);
        tabView.setOnClickListener(v -> switchToTab(tab.id));

        TextView titleView = new TextView(getContext());
        titleView.setText(tab.loading ? getContext().getString(R.string.controlled_browser_loading) : tab.title);
        titleView.setTextColor(active ? Color.WHITE : getColor(R.color.textPrimary));
        titleView.setTextSize(13);
        titleView.setSingleLine(true);
        titleView.setEllipsize(TextUtils.TruncateAt.END);
        titleView.setTypeface(Typeface.DEFAULT, active ? Typeface.BOLD : Typeface.NORMAL);
        titleView.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(0, LayoutParams.MATCH_PARENT, 1);
        tabView.addView(titleView, titleParams);

        Button closeButton = new Button(getContext());
        closeButton.setText(R.string.controlled_browser_close_tab_text);
        closeButton.setTextColor(active ? Color.WHITE : getColor(R.color.textPrimary));
        closeButton.setTextSize(13);
        closeButton.setAllCaps(false);
        closeButton.setMinWidth(0);
        closeButton.setPadding(0, 0, 0, 0);
        closeButton.setBackgroundColor(Color.TRANSPARENT);
        closeButton.setContentDescription(getContext().getString(R.string.controlled_browser_close_tab));
        closeButton.setOnClickListener(v -> closeTab(tab.id));
        tabView.addView(closeButton, new LinearLayout.LayoutParams(dp(32), dp(36)));

        return tabView;
    }

    private GradientDrawable makeTabBackground(boolean active) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setCornerRadius(dp(8));
        drawable.setColor(active ? getColor(R.color.accent) : getColor(R.color.stageChecking));
        drawable.setStroke(dp(1), active ? getColor(R.color.accent) : getColor(R.color.stageBlocked));
        return drawable;
    }

    private void updateAddress(@Nullable BrowserTab tab) {
        if (tab == null || addressView == null || addressView.hasFocus()) {
            return;
        }
        internalAddressChange = true;
        String url = tab.webView.getUrl();
        addressView.setText(url == null || url.trim().isEmpty() ? tab.url : url);
        internalAddressChange = false;
    }

    private void updateNavigationButtons() {
        BrowserTab tab = getActiveTab();
        boolean hasTab = tab != null;
        backButton.setEnabled(hasTab && tab.webView.canGoBack());
        forwardButton.setEnabled(hasTab && tab.webView.canGoForward());
        reloadButton.setEnabled(hasTab);
        progressView.setVisibility(hasTab && tab.loading ? View.VISIBLE : View.GONE);
        if (hasTab) {
            progressView.setProgress(tab.progress);
        }
    }

    private BrowserTab resolveTargetTab(@Nullable Bundle extras) {
        if (extras == null) {
            return null;
        }
        String tabId = getStringExtra(extras, ControlledBrowserContract.EXTRA_TAB_ID,
            ControlledBrowserContract.FIELD_TAB_ID, "tabId", "targetId");
        if (tabId != null && !tabId.trim().isEmpty()) {
            return findTabById(tabId);
        }
        Object tabIndex = getFirstBundleValue(extras, ControlledBrowserContract.EXTRA_TAB_INDEX,
            ControlledBrowserContract.FIELD_TAB_INDEX, "tabIndex");
        if (tabIndex instanceof Number) {
            return findTabByIndex(((Number) tabIndex).intValue());
        }
        if (tabIndex instanceof String && !isBlank((String) tabIndex)) {
            try {
                return findTabByIndex(Integer.parseInt(((String) tabIndex).trim()));
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    @Nullable
    private BrowserTab getActiveTab() {
        return findTabById(activeTabId);
    }

    @Nullable
    private BrowserTab findTabById(@Nullable String tabId) {
        if (tabId == null) {
            return null;
        }
        for (BrowserTab tab : tabs) {
            if (tab.id.equals(tabId)) {
                return tab;
            }
        }
        return null;
    }

    @Nullable
    private BrowserTab findTabByIndex(int tabIndex) {
        if (tabIndex < 0 || tabIndex >= tabs.size()) {
            return null;
        }
        return tabs.get(tabIndex);
    }

    @Nullable
    private BrowserTab findTabByWebView(WebView webView) {
        for (BrowserTab tab : tabs) {
            if (tab.webView == webView) {
                return tab;
            }
        }
        return null;
    }

    private int indexOfTab(@Nullable String tabId) {
        for (int i = 0; i < tabs.size(); i++) {
            if (tabs.get(i).id.equals(tabId)) {
                return i;
            }
        }
        return -1;
    }

    private String normalizeBrowserTarget(@Nullable String rawTarget) {
        String target = rawTarget == null ? "" : rawTarget.trim();
        if (target.isEmpty()) {
            return "about:blank";
        }

        String lowerTarget = target.toLowerCase(Locale.US);
        if (lowerTarget.startsWith("about:")
            || lowerTarget.startsWith("http://")
            || lowerTarget.startsWith("https://")) {
            return target;
        }
        if (looksLikeLocalAddress(target, lowerTarget)) {
            return "http://" + target;
        }
        if (hasExplicitScheme(target)) {
            return "https://www.google.com/search?q=" + Uri.encode(target);
        }
        if (looksLikeWebAddress(target, lowerTarget)) {
            return "https://" + target;
        }
        return "https://www.google.com/search?q=" + Uri.encode(target);
    }

    private boolean looksLikeLocalAddress(String target, String lowerTarget) {
        return lowerTarget.startsWith("localhost")
            || lowerTarget.startsWith("127.")
            || lowerTarget.startsWith("10.")
            || lowerTarget.startsWith("192.168.")
            || lowerTarget.startsWith("[::1]")
            || target.matches("^[A-Za-z0-9.-]+:[0-9]+(/.*)?$");
    }

    private boolean looksLikeWebAddress(String target, String lowerTarget) {
        if (target.indexOf(' ') >= 0 || target.indexOf('\t') >= 0) {
            return false;
        }
        return lowerTarget.contains(".") || lowerTarget.contains(":");
    }

    private boolean hasExplicitScheme(String target) {
        return target.matches("^[A-Za-z][A-Za-z0-9+.-]*:.*");
    }

    private void hideSoftKeyboard() {
        InputMethodManager inputMethodManager =
            (InputMethodManager) getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        if (inputMethodManager != null) {
            inputMethodManager.hideSoftInputFromWindow(addressView.getWindowToken(), 0);
        }
        addressView.clearFocus();
    }

    private void destroyWebView(WebView webView) {
        webView.stopLoading();
        webView.setWebChromeClient(null);
        webView.setWebViewClient(null);
        webView.loadUrl("about:blank");
        webView.clearHistory();
        webView.removeAllViews();
        webView.destroy();
    }

    private void throwIfDestroyed() {
        if (destroyed) {
            throw new IllegalStateException("ControlledBrowserView has been destroyed");
        }
    }

    private ControlledBrowserCommandResult okResult(String message) {
        return ControlledBrowserCommandResult.ok(message, activeTabId, getActiveUrl(), tabs.size());
    }

    private ControlledBrowserCommandResult errorResult(String message) {
        return ControlledBrowserCommandResult.error(message, activeTabId, getActiveUrl(), tabs.size());
    }

    private int getColor(int colorRes) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return getResources().getColor(colorRes, getContext().getTheme());
        }
        return getResources().getColor(colorRes);
    }

    private int dp(int value) {
        float density = getResources().getDisplayMetrics().density;
        return (int) (value * density + 0.5f);
    }

    private interface JsResultCallback {
        void onResult(@Nullable String result);
    }

    private static final class PendingCommand {
        final Bundle extras;
        final String requestId;
        final long startedAtMs;
        final CommandCallback callback;

        PendingCommand(
            @Nullable Bundle extras,
            @Nullable String requestId,
            long startedAtMs,
            @NonNull CommandCallback callback
        ) {
            this.extras = extras == null ? new Bundle() : extras;
            this.requestId = requestId;
            this.startedAtMs = startedAtMs;
            this.callback = callback;
        }
    }

    private static final class CdpRequest {
        final String method;
        final JSONObject params;
        final Object id;

        CdpRequest(@Nullable String method, @NonNull JSONObject params, @Nullable Object id) {
            this.method = method;
            this.params = params;
            this.id = id;
        }
    }

    private static final class BrowserTab {
        final String id;
        final WebView webView;
        String title;
        String url;
        boolean loading;
        int progress = 100;

        BrowserTab(String id, String title, String url, WebView webView) {
            this.id = id;
            this.title = title;
            this.url = url;
            this.webView = webView;
        }
    }
}
