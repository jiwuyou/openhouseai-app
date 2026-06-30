package com.termux.app.activities;

import android.Manifest;
import android.app.ActivityManager;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.app.ActivityCompat;
import androidx.core.view.GravityCompat;
import androidx.drawerlayout.widget.DrawerLayout;

import com.termux.R;
import com.termux.app.ClaudeCodeUiSettings;
import com.termux.app.OpenCodeSettings;
import com.termux.app.OpenHouseAgreement;
import com.termux.app.TermuxActivity;
import com.termux.app.browser.ControlledBrowserCommandDispatcher;
import com.termux.app.browser.ControlledBrowserContract;
import com.termux.app.browser.ControlledBrowserRpcFiles;
import com.termux.app.browser.ControlledBrowserRuntime;
import com.termux.app.browser.ControlledBrowserView;
import com.termux.app.openhouse.OpenHouseClaudeCodeUiController;
import com.termux.app.openhouse.OpenHouseDeepSeekController;
import com.termux.app.openhouse.OpenHouseMaintainerRunner;
import com.termux.app.openhouse.OpenHouseOpenCodeController;
import com.termux.app.openhouse.components.OpenHouseComponent;
import com.termux.app.openhouse.components.OpenHouseComponentRegistry;
import com.termux.app.operit.runtime.SmallPhoneOperitHost;
import com.termux.app.smallphone.SmallPhoneFirstLaunchGate;
import com.termux.app.smallphone.SmallPhoneHostController;
import com.termux.shared.activity.ActivityUtils;
import com.termux.shared.logger.Logger;
import com.termux.shared.termux.TermuxConstants;
import com.ai.assistance.operit.host.control.OperitControlProtocol;
import com.ai.assistance.operit.host.control.OperitControlStateSnapshot;
import com.ai.assistance.operit.host.control.OperitControlStateStore;
import com.ai.assistance.operit.host.control.OperitProcessState;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.json.JSONObject;

public class OpenHouseHomeActivity extends AppCompatActivity {

    private static final String LOG_TAG = "OpenHouseHome";
    private static final String PREFS_NAME = "openhouse_home";
    private static final String PREF_HOME_PAGE = "home_page";
    private static final String PAGE_HOME = "home";
    private static final String PAGE_HERMES = "hermes";
    private static final String PAGE_AI = "ai";
    private static final String PAGE_SMALLPHONE = "smallphone";
    private static final String PAGE_CONTROLLED_BROWSER = ControlledBrowserContract.PAGE_CONTROLLED_BROWSER;
    private static final String PAGE_MANUAL = "manual";
    private static final String PAGE_OPENCODE = "opencode";
    private static final String PAGE_DEEPSEEK = "deepseek";
    private static final String PAGE_PERMISSIONS = "permissions";
    private static final String PAGE_ABOUT = "about";
    private static final String PAGE_TERMINAL_GUIDE = "terminal_guide";
    private static final String PAGE_SHORTCUTS = "shortcuts";
    private static final String PAGE_REPAIR = "repair";
    private static final String PAGE_LOGS = "logs";
    private static final String PAGE_ADVANCED = "advanced";
    private static final String PAGE_COMPONENT_PREFIX = "component:";
    private static final String EXTRA_SERVICE_CONTROL_COMPONENT_ID = "openhouse_component_id";
    private static final String EXTRA_SERVICE_CONTROL_TITLE = "openhouse_component_title";
    private static final String EXTRA_SERVICE_CONTROL_URL = "openhouse_component_url";
    private static final String EXTRA_SERVICE_CONTROL_SERVICE_NAMES = "openhouse_service_names";
    private static final String EXTRA_SERVICE_CONTROL_SERVICE_REFS = "openhouse_service_refs";
    private static final String EXTRA_SERVICE_CONTROL_MODE = "openhouse_service_control_mode";
    private static final String SERVICE_CONTROL_MODE_COMPONENT = "component";
    private static final String SERVICE_CONTROL_MODE_ALL = "all";
    private static final String HERMES_URL = "http://127.0.0.1:23084/";
    private static final String HERMES_SERVICE_NAME = "hermes-webui";
    private static final String CC_CODEX_SERVICE_NAME = "cloudcli";
    private static final String SMALLPHONE_HOME_TARGET = "messages";
    private static final String MENU_OVERRIDES_RELATIVE_PATH = ".config/openhouseai/menu-overrides.json";
    private static final String CC_CODEX_TITLE = "CC/Codex";
    private static final String OPERIT_MAIN_ACTIVITY_CLASS = "com.ai.assistance.operit.ui.main.MainActivity";
    private static final String OPERIT_EXTRA_HOSTED_MODE = "com.ai.assistance.operit.extra.HOSTED_MODE";
    private static final String OPERIT_EXTRA_HELP_MODE = "com.ai.assistance.operit.extra.HELP_MODE";
    private static final String AI_FRIEND_HELP_ENTRY_TAG = "ai_friend_help_entry";
    private static final long OPERIT_SHUTDOWN_PENDING_UI_MS = 5000L;
    private static final long OPERIT_LAUNCH_PENDING_UI_MS = 7000L;
    private static final long OPERIT_LAUNCH_PROCESS_GRACE_MS = 1500L;
    private static final int OPERIT_FORWARD_GRANT_FLAGS =
        Intent.FLAG_GRANT_READ_URI_PERMISSION
            | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
            | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION;

    private enum AiFriendHelpUiState {
        NOT_RUNNING,
        STARTING,
        FOREGROUND,
        BACKGROUND,
        STOPPING
    }

    private final ExecutorService backgroundExecutor = Executors.newSingleThreadExecutor();

    private DrawerLayout drawerLayout;
    private ScrollView scrollContentView;
    private FrameLayout embeddedContentView;
    private LinearLayout contentView;
    private LinearLayout dynamicQuickNavView;
    private LinearLayout dynamicNavView;
    private TextView homeStatusView;
    private Button setCurrentHomeButton;
    private Button copyCurrentButton;
    private Button openCurrentBrowserButton;
    private Button openCurrentControlButton;
    private Button refreshCurrentButton;
    private TextView pageTitleView;
    private TextView pageSubtitleView;
    private LinearLayout aiFriendHelpNavContainer;
    private TextView aiFriendHelpNavStatusView;
    private Button aiFriendHelpNavOpenButton;
    private Button aiFriendHelpNavCloseButton;
    private TextView aiFriendHelpHomeStatusView;
    private Button aiFriendHelpHomeOpenButton;
    private Button aiFriendHelpHomeCloseButton;
    private String currentPage = PAGE_HOME;
    private List<OpenHouseComponent> dynamicComponents = Collections.emptyList();
    private OpenHouseComponentRegistry.LoadResult dynamicRegistryResult;
    private int openCodePort = OpenCodeSettings.DEFAULT_OPENCODE_PORT;
    private String lastOpenCodeUrl = OpenCodeSettings.getRootProjectUrl(OpenCodeSettings.DEFAULT_OPENCODE_PORT);
    private final String cloudCliUrl = ClaudeCodeUiSettings.getLoopbackUrl();
    private SmallPhoneHostController smallPhoneController;
    private View smallPhoneView;
    private LinearLayout cloudCliPageView;
    private WebView cloudCliWebView;
    private LinearLayout cloudCliControlPanel;
    private LinearLayout cloudCliFallbackView;
    private TextView cloudCliStatusView;
    private boolean cloudCliControlsVisible = false;
    private boolean cloudCliLoadFailed = false;
    private LinearLayout hermesPageView;
    private WebView hermesWebView;
    private LinearLayout hermesFallbackView;
    private TextView hermesStatusView;
    private String renderedHermesUrl;
    private boolean hermesLoadFailed = false;
    private ControlledBrowserView controlledBrowserView;
    private LinearLayout dynamicWebPageView;
    private WebView dynamicWebView;
    private LinearLayout dynamicWebFallbackView;
    private TextView dynamicWebStatusView;
    private OpenHouseComponent dynamicWebComponent;
    private boolean dynamicWebLoadFailed = false;
    private boolean firstLaunchGateForwarded;
    private String renderedCloudCliUrl;
    private long aiFriendHelpShutdownRequestedAtMs = 0L;
    private long aiFriendHelpLaunchRequestedAtMs = 0L;
    private boolean aiFriendHelpLaunchFailureNotified = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (forwardExternalOperitIntentIfNeeded(getIntent())) {
            finish();
            return;
        }
        setContentView(R.layout.activity_openhouse_home);

        drawerLayout = findViewById(R.id.openhouseDrawer);
        scrollContentView = findViewById(R.id.openhouseScrollContent);
        embeddedContentView = findViewById(R.id.openhouseEmbeddedContent);
        contentView = findViewById(R.id.openhouseContent);
        dynamicQuickNavView = findViewById(R.id.openhouseDynamicQuickNav);
        dynamicNavView = findViewById(R.id.openhouseDynamicNav);
        homeStatusView = findViewById(R.id.openhouseHomeStatus);
        setCurrentHomeButton = findViewById(R.id.buttonSetCurrentHome);
        copyCurrentButton = findViewById(R.id.buttonCopyCurrent);
        openCurrentBrowserButton = findViewById(R.id.buttonOpenCurrentBrowser);
        openCurrentControlButton = findViewById(R.id.buttonOpenCurrentControl);
        refreshCurrentButton = findViewById(R.id.buttonRefreshCurrent);
        pageTitleView = findViewById(R.id.openhousePageTitle);
        pageSubtitleView = findViewById(R.id.openhousePageSubtitle);

        findViewById(R.id.buttonOpenDrawer).setOnClickListener(v -> drawerLayout.openDrawer(GravityCompat.START));
        findViewById(R.id.buttonCloseDrawer).setOnClickListener(v -> drawerLayout.closeDrawer(GravityCompat.START));
        if (copyCurrentButton != null) {
            copyCurrentButton.setOnClickListener(v -> copyCurrentTarget());
        }
        if (openCurrentBrowserButton != null) {
            openCurrentBrowserButton.setOnClickListener(v -> openCurrentTargetInBrowser());
        }
        if (openCurrentControlButton != null) {
            openCurrentControlButton.setOnClickListener(v -> openCurrentControl());
        }
        if (refreshCurrentButton != null) {
            refreshCurrentButton.setOnClickListener(v -> refreshCurrentTarget());
        }
        bindNavigation();
        refreshDynamicComponents();
        if (!handleOpenHouseIntent(getIntent())) {
            if (routeFirstLaunchGateIfNeeded()) {
                return;
            }
            selectConfiguredHomePage();
        }
    }

    @Override
    protected void onDestroy() {
        if (smallPhoneController != null) {
            smallPhoneController.onDestroy();
            smallPhoneController = null;
        }
        if (cloudCliWebView != null) {
            cloudCliWebView.destroy();
            cloudCliWebView = null;
        }
        if (hermesWebView != null) {
            hermesWebView.destroy();
            hermesWebView = null;
        }
        if (controlledBrowserView != null) {
            controlledBrowserView.setExternalNavigationHandler(null);
            if (controlledBrowserView.getParent() instanceof ViewGroup) {
                ((ViewGroup) controlledBrowserView.getParent()).removeView(controlledBrowserView);
            }
            controlledBrowserView = null;
        }
        if (dynamicWebView != null) {
            dynamicWebView.destroy();
            dynamicWebView = null;
        }
        backgroundExecutor.shutdownNow();
        super.onDestroy();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshDynamicComponents();
        refreshAiFriendHelpEntryState();
        if (!hasExplicitOpenHouseTarget(getIntent()) && routeFirstLaunchGateIfNeeded()) {
            return;
        }
        if (PAGE_PERMISSIONS.equals(currentPage)) {
            renderPage();
        }
        if (PAGE_HERMES.equals(currentPage) && hermesWebView != null) {
            hermesWebView.onResume();
        } else if (PAGE_SMALLPHONE.equals(currentPage)
            && isCurrentDynamicWebComponent(findSmallPhoneComponent())
            && dynamicWebView != null) {
            dynamicWebView.onResume();
        } else if (PAGE_SMALLPHONE.equals(currentPage) && smallPhoneController != null) {
            smallPhoneController.onResume(false);
        } else if (PAGE_AI.equals(currentPage) && cloudCliWebView != null) {
            cloudCliWebView.onResume();
        } else if (PAGE_CONTROLLED_BROWSER.equals(currentPage)
            && isCurrentDynamicWebComponent(findControlledBrowserComponent())
            && dynamicWebView != null) {
            dynamicWebView.onResume();
        } else if (PAGE_CONTROLLED_BROWSER.equals(currentPage) && controlledBrowserView != null) {
            controlledBrowserView.onHostResume();
        } else if (isComponentPage(currentPage) && dynamicWebView != null) {
            dynamicWebView.onResume();
        }
    }

    @Override
    protected void onPause() {
        firstLaunchGateForwarded = false;
        pauseCurrentEmbeddedPage();
        super.onPause();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (forwardExternalOperitIntentIfNeeded(intent)) {
            return;
        }
        if (handleOpenHouseIntent(intent)) {
            return;
        }
        if (routeFirstLaunchGateIfNeeded()) {
            return;
        }
        if (isLauncherOpenIntent(intent)) {
            selectConfiguredHomePage();
        }
    }

    @Override
    public void onBackPressed() {
        if (drawerLayout != null && drawerLayout.isDrawerOpen(GravityCompat.START)) {
            drawerLayout.closeDrawer(GravityCompat.START);
            return;
        }
        if (PAGE_SMALLPHONE.equals(currentPage)
            && isCurrentDynamicWebComponent(findSmallPhoneComponent())
            && dynamicWebView != null
            && dynamicWebView.canGoBack()) {
            dynamicWebView.goBack();
            return;
        }
        if (PAGE_SMALLPHONE.equals(currentPage)
            && smallPhoneController != null
            && smallPhoneController.handleBackPressed()) {
            return;
        }
        if (PAGE_HERMES.equals(currentPage)
            && hermesWebView != null
            && hermesWebView.canGoBack()) {
            hermesWebView.goBack();
            return;
        }
        if (PAGE_AI.equals(currentPage)
            && cloudCliWebView != null
            && cloudCliWebView.canGoBack()) {
            cloudCliWebView.goBack();
            return;
        }
        if (PAGE_CONTROLLED_BROWSER.equals(currentPage)
            && isCurrentDynamicWebComponent(findControlledBrowserComponent())
            && dynamicWebView != null
            && dynamicWebView.canGoBack()) {
            dynamicWebView.goBack();
            return;
        }
        if (PAGE_CONTROLLED_BROWSER.equals(currentPage)
            && controlledBrowserView != null
            && controlledBrowserView.goBack()) {
            return;
        }
        if (isComponentPage(currentPage)
            && dynamicWebView != null
            && dynamicWebView.canGoBack()) {
            dynamicWebView.goBack();
            return;
        }
        String configuredHomePage = getConfiguredHomePage();
        if (!PAGE_HOME.equals(currentPage) && !currentPage.equals(configuredHomePage)) {
            selectConfiguredHomePage();
            return;
        }
        super.onBackPressed();
    }

    private void bindNavigation() {
        findViewById(R.id.buttonNavHome).setOnClickListener(v -> selectPage(PAGE_HOME));
        findViewById(R.id.buttonNavHermes).setOnClickListener(
            v -> openBuiltinComponentOrFallback(findHermesComponent(), PAGE_HERMES));
        findViewById(R.id.buttonNavHermesControl).setOnClickListener(v -> openHermesControl());
        findViewById(R.id.buttonNavAi).setOnClickListener(
            v -> openBuiltinComponentOrFallback(findCcCodexComponent(), PAGE_AI));
        findViewById(R.id.buttonNavAiControl).setOnClickListener(v -> openCcCodexControlOrToggle());
        findViewById(R.id.buttonNavSmallPhone).setOnClickListener(
            v -> openBuiltinComponentOrFallback(findSmallPhoneComponent(), PAGE_SMALLPHONE));
        findViewById(R.id.buttonNavSmallPhoneControl).setOnClickListener(v -> openComponentControl(findSmallPhoneComponent()));
        findViewById(R.id.buttonNavControlledBrowser).setOnClickListener(
            v -> openBuiltinComponentOrFallback(findControlledBrowserComponent(), PAGE_CONTROLLED_BROWSER));
        findViewById(R.id.buttonNavControlledBrowserControl).setOnClickListener(v -> openComponentControl(findControlledBrowserComponent()));
        findViewById(R.id.buttonNavServiceControl).setOnClickListener(v -> openAllServiceControl());
        findViewById(R.id.buttonNavManual).setOnClickListener(v -> selectPage(PAGE_MANUAL));
        findViewById(R.id.buttonNavOpenCode).setOnClickListener(v -> selectPage(PAGE_OPENCODE));
        findViewById(R.id.buttonNavDeepSeek).setOnClickListener(v -> selectPage(PAGE_DEEPSEEK));
        findViewById(R.id.buttonNavPermissions).setOnClickListener(v -> selectPage(PAGE_PERMISSIONS));
        findViewById(R.id.buttonNavAbout).setOnClickListener(v -> selectPage(PAGE_ABOUT));
        findViewById(R.id.buttonNavTerminalGuide).setOnClickListener(v -> selectPage(PAGE_TERMINAL_GUIDE));
        findViewById(R.id.buttonNavShortcuts).setOnClickListener(v -> selectPage(PAGE_SHORTCUTS));
        findViewById(R.id.buttonNavRepair).setOnClickListener(v -> selectPage(PAGE_REPAIR));
        findViewById(R.id.buttonNavLogs).setOnClickListener(v -> selectPage(PAGE_LOGS));
        findViewById(R.id.buttonNavAdvanced).setOnClickListener(v -> selectPage(PAGE_ADVANCED));
        findViewById(R.id.buttonNavTerminal).setOnClickListener(v -> openTerminal(false));
        addAiFriendHelpDrawerEntry();
        if (setCurrentHomeButton != null) {
            setCurrentHomeButton.setOnClickListener(v -> setCurrentPageAsHome());
        }
        updateHomePreferenceViews();
    }

    private void addAiFriendHelpDrawerEntry() {
        View anchor = findViewById(R.id.buttonNavServiceControl);
        if (anchor == null || !(anchor.getParent() instanceof LinearLayout)) {
            return;
        }
        LinearLayout parent = (LinearLayout) anchor.getParent();
        View existing = parent.findViewWithTag(AI_FRIEND_HELP_ENTRY_TAG);
        if (existing != null) {
            refreshAiFriendHelpEntryState();
            return;
        }

        aiFriendHelpNavContainer = createAiFriendHelpControlBlock(false);
        aiFriendHelpNavContainer.setTag(AI_FRIEND_HELP_ENTRY_TAG);

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT);
        params.setMargins(0, dp(8), 0, 0);
        parent.addView(aiFriendHelpNavContainer, parent.indexOfChild(anchor) + 1, params);
        refreshAiFriendHelpEntryState();
    }

    private void refreshDynamicComponents() {
        dynamicRegistryResult = OpenHouseComponentRegistry.loadWithDiagnostics();
        dynamicComponents = dynamicRegistryResult.components;
        setFallbackNavigationVisible(dynamicRegistryResult.shouldShowFallbackNavigation());
        updateBuiltinNavigationLabels();
        refreshAiFriendHelpEntryState();
        renderDynamicQuickNavigation();
        renderDynamicNavigation();
        updateHomePreferenceViews();
        updateTopActionState();
    }

    private void updateBuiltinNavigationLabels() {
        setBuiltinNavigationRowState(R.id.rowNavHermes, R.id.buttonNavHermes, R.id.buttonNavHermesControl,
            findHermesComponent(), getHermesTitle());
        setBuiltinNavigationRowState(R.id.rowNavAi, R.id.buttonNavAi, R.id.buttonNavAiControl,
            findCcCodexComponent(), getCcCodexTitle());
        setBuiltinNavigationRowState(R.id.rowNavSmallPhone, R.id.buttonNavSmallPhone, R.id.buttonNavSmallPhoneControl,
            findSmallPhoneComponent(), getSmallPhoneTitle());
        setBuiltinNavigationRowState(R.id.rowNavControlledBrowser, R.id.buttonNavControlledBrowser, R.id.buttonNavControlledBrowserControl,
            findControlledBrowserComponent(), getControlledBrowserTitle());
    }

    private void setBuiltinNavigationRowState(int rowId, int openButtonId, int controlButtonId,
                                               OpenHouseComponent component, String text) {
        View row = findViewById(rowId);
        if (row == null) {
            return;
        }
        boolean visible = isComponentVisible(component);
        row.setVisibility(visible ? View.VISIBLE : View.GONE);
        View openButton = findViewById(openButtonId);
        if (openButton instanceof Button && !isBlank(text)) {
            ((Button) openButton).setText(text);
        }
        View controlButton = findViewById(controlButtonId);
        if (controlButton != null) {
            controlButton.setVisibility(visible && component != null && component.hasControlEntry()
                ? View.VISIBLE
                : View.GONE);
        }
    }

    private void setFallbackNavigationVisible(boolean visible) {
        // Built-in entries are the app shell fallback and must stay available even
        // when components.d is present. Dynamic registry entries only append below.
        int visibility = View.VISIBLE;
        int[] fallbackButtonIds = new int[] {
            R.id.rowNavHermes,
            R.id.rowNavAi,
            R.id.rowNavSmallPhone,
            R.id.rowNavControlledBrowser,
            R.id.buttonNavHome,
            R.id.buttonNavServiceControl,
            R.id.buttonNavManual,
            R.id.buttonNavOpenCode,
            R.id.buttonNavDeepSeek,
            R.id.buttonNavPermissions,
            R.id.buttonNavAbout,
            R.id.buttonNavTerminalGuide,
            R.id.buttonNavShortcuts,
            R.id.buttonNavRepair,
            R.id.buttonNavLogs,
            R.id.buttonNavAdvanced,
            R.id.buttonNavTerminal
        };
        for (int id : fallbackButtonIds) {
            View view = findViewById(id);
            if (view != null) {
                view.setVisibility(visibility);
            }
        }
    }

    private void renderDynamicQuickNavigation() {
        if (dynamicQuickNavView == null) {
            return;
        }
        dynamicQuickNavView.removeAllViews();
        boolean hasQuickEntry = false;
        for (OpenHouseComponent component : dynamicComponents) {
            if (!isComponentVisible(component)
                || isBuiltinNavigationComponent(component)
                || (!component.favorite && !component.home)
                || !component.hasEntry()) {
                continue;
            }
            hasQuickEntry = true;
            addComponentNavButton(dynamicQuickNavView, component);
        }
        dynamicQuickNavView.setVisibility(hasQuickEntry ? View.VISIBLE : View.GONE);
    }

    private void renderDynamicNavigation() {
        if (dynamicNavView == null) {
            return;
        }
        dynamicNavView.removeAllViews();
        boolean hasVisibleDynamicComponent = false;
        for (OpenHouseComponent component : dynamicComponents) {
            if (!isComponentVisible(component)) {
                continue;
            }
            if (!isBuiltinNavigationComponent(component)) {
                hasVisibleDynamicComponent = true;
            }
        }
        if (!hasVisibleDynamicComponent) {
            dynamicNavView.setVisibility(View.GONE);
            return;
        }

        dynamicNavView.setVisibility(View.VISIBLE);
        addDynamicSectionTitle("扩展应用");
        for (OpenHouseComponent component : dynamicComponents) {
            if (!isComponentVisible(component) || isBuiltinNavigationComponent(component)) {
                continue;
            }
            addComponentNavButton(dynamicNavView, component);
        }
    }

    private void addDynamicSectionTitle(String text) {
        TextView title = new TextView(this);
        title.setText(text);
        title.setTextColor(ContextCompat.getColor(this, R.color.textSecondary));
        title.setTextSize(12);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT);
        titleParams.setMargins(0, dp(14), 0, 0);
        dynamicNavView.addView(title, titleParams);
    }

    private void addComponentNavButton(LinearLayout parent, OpenHouseComponent component) {
        if (component.hasEntry() && component.hasControlEntry()) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);

            Button openButton = new Button(this);
            openButton.setText(component.title);
            openButton.setAllCaps(false);
            openButton.setOnClickListener(v -> openComponent(component));
            row.addView(openButton, new LinearLayout.LayoutParams(0, dp(52), 1));

            Button controlButton = compactButton(
                isBlank(component.controlTitle) ? "控制" : component.controlTitle,
                v -> openComponentControl(component),
                true);
            LinearLayout.LayoutParams controlParams = new LinearLayout.LayoutParams(dp(68), dp(52));
            controlParams.setMargins(dp(8), 0, 0, 0);
            row.addView(controlButton, controlParams);

            LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
            rowParams.setMargins(0, dp(8), 0, 0);
            parent.addView(row, rowParams);
            return;
        }

        Button button = new Button(this);
        button.setText(component.hasEntry() ? component.title : component.title + " 控制");
        button.setAllCaps(false);
        button.setOnClickListener(v -> {
            if (component.hasEntry()) {
                openComponent(component);
            } else {
                openComponentControl(component);
            }
        });
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(52));
        params.setMargins(0, dp(8), 0, 0);
        parent.addView(button, params);
    }

    private void selectPage(String page) {
        if (!page.equals(currentPage)) {
            pauseCurrentEmbeddedPage();
        }
        currentPage = page;
        drawerLayout.closeDrawer(GravityCompat.START);
        renderPage();
        updateHomePreferenceViews();
    }

    private void renderPage() {
        if (contentView == null) {
            return;
        }

        if (isComponentPage(currentPage)) {
            OpenHouseComponent component = findDynamicComponent(extractComponentId(currentPage));
            if (component != null && component.entryType == OpenHouseComponent.EntryType.WEBVIEW) {
                setHeader(component.title, isBlank(component.subtitle) ? component.url : component.subtitle);
                renderDynamicWebViewPage(component);
                return;
            }
            currentPage = PAGE_HOME;
        }

        switch (currentPage) {
            case PAGE_HERMES:
                setHeader(getHermesTitle(), getHermesSubtitle("默认 AI 伙伴"));
                renderHermesPage();
                break;
            case PAGE_AI:
                setHeader(getCcCodexTitle(), getCcCodexSubtitle("Claude Code / Codex 网页工作台"));
                renderAiPage();
                break;
            case PAGE_SMALLPHONE:
                setHeader(getSmallPhoneTitle(), getSmallPhoneSubtitle("小手机页面和运行栈修复"));
                renderSmallPhonePage();
                break;
            case PAGE_CONTROLLED_BROWSER:
                setHeader(getControlledBrowserTitle(), getControlledBrowserSubtitle("多标签，可由 Termux 命令控制"));
                renderControlledBrowserPage();
                break;
            case PAGE_MANUAL:
                showScrollContent();
                setHeader("使用手册", "离线基础说明和在线手册入口");
                renderManualPage();
                break;
            case PAGE_OPENCODE:
                showScrollContent();
                setHeader("OpenCode 控制", "启动、停止、重启和自定义端口");
                renderOpenCodePage();
                break;
            case PAGE_DEEPSEEK:
                showScrollContent();
                setHeader("DeepSeek Key", "一键替换 AI 软件配置");
                renderDeepSeekPage();
                break;
            case PAGE_PERMISSIONS:
                showScrollContent();
                setHeader("权限获取", "后台运行、文件访问和悬浮窗");
                renderPermissionsPage();
                break;
            case PAGE_ABOUT:
                showScrollContent();
                setHeader("软件说明", "源码地址和交流群");
                renderAboutPage();
                break;
            case PAGE_TERMINAL_GUIDE:
                showScrollContent();
                setHeader("终端教学", "回到终端后的手指教学");
                renderTerminalGuidePage();
                break;
            case PAGE_SHORTCUTS:
                showScrollContent();
                setHeader("终端快捷键", "底部按键说明");
                renderShortcutsPage();
                break;
            case PAGE_REPAIR:
                showScrollContent();
                setHeader("维护与修复", "详细进度和修复入口");
                renderRepairPage();
                break;
            case PAGE_LOGS:
                showScrollContent();
                setHeader("日志", "阶段日志和 OpenCode 日志");
                renderLogsPage();
                break;
            case PAGE_ADVANCED:
                showScrollContent();
                setHeader("高级设置", "显示和兼容设置");
                renderAdvancedPage();
                break;
            case PAGE_HOME:
            default:
                showScrollContent();
                setHeader("openhouse ai", "菜单总览");
                renderHomePage();
                break;
        }
    }

    private void setHeader(String title, String subtitle) {
        if (pageTitleView != null) {
            pageTitleView.setText(title);
        }
        if (pageSubtitleView != null) {
            pageSubtitleView.setText(subtitle);
        }
        updateTopActionState();
    }

    private void updateTopActionState() {
        String browserUrl = getCurrentBrowserUrl();
        if (copyCurrentButton != null) {
            copyCurrentButton.setEnabled(!isBlank(browserUrl) || !isBlank(getCurrentCopyText()));
        }
        if (openCurrentBrowserButton != null) {
            openCurrentBrowserButton.setEnabled(!isBlank(browserUrl));
        }
        if (openCurrentControlButton != null) {
            openCurrentControlButton.setEnabled(true);
        }
        if (refreshCurrentButton != null) {
            refreshCurrentButton.setEnabled(true);
        }
    }

    private void copyCurrentTarget() {
        String label = getCurrentDisplayTitle();
        String text = getCurrentCopyText();
        if (isBlank(text)) {
            Toast.makeText(this, "当前页面没有可复制内容。", Toast.LENGTH_SHORT).show();
            return;
        }
        copyText(isBlank(label) ? "当前页面" : label, text);
    }

    private void openCurrentTargetInBrowser() {
        String browserUrl = getCurrentBrowserUrl();
        if (isBlank(browserUrl)) {
            Toast.makeText(this, "当前页面没有浏览器地址。", Toast.LENGTH_SHORT).show();
            return;
        }
        openUrl(browserUrl);
    }

    private void openCurrentControl() {
        OpenHouseComponent component = getCurrentControlComponent();
        if (component != null && component.hasControlEntry()) {
            openComponentControl(component);
            return;
        }
        if (PAGE_AI.equals(currentPage)) {
            openCcCodexControlOrToggle();
            return;
        }
        if (PAGE_HERMES.equals(currentPage)) {
            openHermesControl();
            return;
        }
        openAllServiceControl();
    }

    private void refreshCurrentTarget() {
        if (PAGE_HERMES.equals(currentPage)) {
            reloadHermesWebView();
            return;
        }
        if (PAGE_AI.equals(currentPage)) {
            reloadCloudCliWebView();
            return;
        }
        if (PAGE_SMALLPHONE.equals(currentPage)) {
            OpenHouseComponent smallPhoneComponent = findSmallPhoneComponent();
            if (smallPhoneComponent != null && smallPhoneComponent.entryType == OpenHouseComponent.EntryType.WEBVIEW) {
                reloadDynamicWebView();
            } else if (smallPhoneController != null) {
                smallPhoneController.onResume(true);
            } else {
                renderPage();
            }
            return;
        }
        if (PAGE_CONTROLLED_BROWSER.equals(currentPage)) {
            OpenHouseComponent browserComponent = findControlledBrowserComponent();
            if (browserComponent != null && browserComponent.entryType == OpenHouseComponent.EntryType.WEBVIEW) {
                reloadDynamicWebView();
            } else if (controlledBrowserView != null) {
                controlledBrowserView.onHostResume();
            } else {
                renderPage();
            }
            return;
        }
        if (isComponentPage(currentPage)) {
            reloadDynamicWebView();
            return;
        }
        renderPage();
    }

    private String getCurrentBrowserUrl() {
        if (PAGE_HERMES.equals(currentPage)) {
            return getHermesUrl();
        }
        if (PAGE_AI.equals(currentPage)) {
            return getCcCodexUrl();
        }
        if (PAGE_SMALLPHONE.equals(currentPage)) {
            OpenHouseComponent component = findSmallPhoneComponent();
            if (component != null
                && component.entryType == OpenHouseComponent.EntryType.WEBVIEW
                && !isBlank(component.url)) {
                return component.url;
            }
            return null;
        }
        if (PAGE_CONTROLLED_BROWSER.equals(currentPage)) {
            OpenHouseComponent component = findControlledBrowserComponent();
            if (component != null
                && component.entryType == OpenHouseComponent.EntryType.WEBVIEW
                && !isBlank(component.url)) {
                return component.url;
            }
            return null;
        }
        if (PAGE_OPENCODE.equals(currentPage)) {
            return lastOpenCodeUrl;
        }
        if (PAGE_DEEPSEEK.equals(currentPage)) {
            return getString(R.string.openhouse_deepseek_url);
        }
        if (isComponentPage(currentPage)) {
            OpenHouseComponent component = findDynamicComponent(extractComponentId(currentPage));
            if (component != null
                && component.entryType == OpenHouseComponent.EntryType.WEBVIEW
                && !isBlank(component.url)) {
                return component.url;
            }
        }
        return null;
    }

    private String getCurrentCopyText() {
        String browserUrl = getCurrentBrowserUrl();
        if (!isBlank(browserUrl)) {
            return browserUrl;
        }
        String title = getCurrentDisplayTitle();
        String subtitle = pageSubtitleView == null ? "" : pageSubtitleView.getText().toString();
        if (isBlank(title)) {
            return subtitle;
        }
        if (isBlank(subtitle)) {
            return title;
        }
        return title + "\n" + subtitle;
    }

    private String getCurrentDisplayTitle() {
        if (pageTitleView != null && pageTitleView.getText() != null && !isBlank(pageTitleView.getText().toString())) {
            return pageTitleView.getText().toString();
        }
        return getHomeDisplayTitle(currentPage);
    }

    private OpenHouseComponent getCurrentControlComponent() {
        if (PAGE_HERMES.equals(currentPage)) {
            return findHermesComponent();
        }
        if (PAGE_AI.equals(currentPage)) {
            return findCcCodexComponent();
        }
        if (PAGE_SMALLPHONE.equals(currentPage)) {
            return findSmallPhoneComponent();
        }
        if (PAGE_CONTROLLED_BROWSER.equals(currentPage)) {
            return findControlledBrowserComponent();
        }
        if (isComponentPage(currentPage)) {
            return findDynamicComponent(extractComponentId(currentPage));
        }
        return null;
    }

    private boolean isCurrentDynamicWebComponent(OpenHouseComponent component) {
        return component != null
            && dynamicWebComponent != null
            && component.id.equals(dynamicWebComponent.id);
    }

    private void showScrollContent() {
        if (scrollContentView != null) {
            scrollContentView.setVisibility(View.VISIBLE);
        }
        if (embeddedContentView != null) {
            embeddedContentView.removeAllViews();
            embeddedContentView.setVisibility(View.GONE);
        }
        if (contentView != null) {
            contentView.removeAllViews();
        }
    }

    private void showEmbeddedContent() {
        if (contentView != null) {
            contentView.removeAllViews();
        }
        if (scrollContentView != null) {
            scrollContentView.setVisibility(View.GONE);
        }
        if (embeddedContentView != null) {
            embeddedContentView.setVisibility(View.VISIBLE);
        }
    }

    private void renderHermesPage() {
        showEmbeddedContent();
        if (embeddedContentView == null) {
            return;
        }
        String hermesUrl = getHermesUrl();
        if (hermesPageView == null || !hermesUrl.equals(renderedHermesUrl)) {
            if (hermesWebView != null) {
                hermesWebView.destroy();
                hermesWebView = null;
            }
            hermesPageView = createHermesPageView();
            renderedHermesUrl = hermesUrl;
        }
        attachEmbeddedView(hermesPageView);
        if (hermesWebView != null) {
            hermesWebView.onResume();
            if (hermesWebView.getUrl() == null) {
                reloadHermesWebView();
            }
        }
    }

    private LinearLayout createHermesPageView() {
        String hermesUrl = getHermesUrl();
        String hermesTitle = getHermesTitle();
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setBackgroundColor(ContextCompat.getColor(this, R.color.surface));

        hermesStatusView = new TextView(this);
        hermesStatusView.setText(hermesTitle + " 地址：" + hermesUrl);
        hermesStatusView.setTextColor(ContextCompat.getColor(this, R.color.textSecondary));
        hermesStatusView.setTextSize(12);
        hermesStatusView.setPadding(dp(12), dp(6), dp(12), dp(6));
        hermesStatusView.setBackgroundColor(ContextCompat.getColor(this, R.color.panel));
        page.addView(hermesStatusView, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT));

        FrameLayout browserHost = new FrameLayout(this);
        hermesWebView = new WebView(this);
        configureHermesWebView(hermesWebView);
        browserHost.addView(hermesWebView, new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT));

        hermesFallbackView = createHermesFallbackView();
        hermesFallbackView.setVisibility(View.GONE);
        browserHost.addView(hermesFallbackView, new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT));
        page.addView(browserHost, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            0,
            1));
        return page;
    }

    private LinearLayout createHermesFallbackView() {
        String hermesUrl = getHermesUrl();
        String hermesTitle = getHermesTitle();
        LinearLayout fallback = new LinearLayout(this);
        fallback.setOrientation(LinearLayout.VERTICAL);
        fallback.setGravity(Gravity.CENTER);
        fallback.setPadding(dp(22), dp(22), dp(22), dp(22));
        fallback.setBackgroundColor(ContextCompat.getColor(this, R.color.surface));

        TextView title = new TextView(this);
        title.setText(hermesTitle + " 未连接");
        title.setTextColor(ContextCompat.getColor(this, R.color.textPrimary));
        title.setTextSize(20);
        title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
        fallback.addView(title);

        TextView body = new TextView(this);
        body.setText(hermesTitle + " 是默认首页。没有连接到 " + hermesUrl + " 时，可以先进入控制页启动或修复服务。");
        body.setTextColor(ContextCompat.getColor(this, R.color.textSecondary));
        body.setTextSize(14);
        body.setGravity(Gravity.CENTER);
        body.setLineSpacing(dp(2), 1.0f);
        LinearLayout.LayoutParams bodyParams = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT);
        bodyParams.setMargins(0, dp(10), 0, dp(6));
        fallback.addView(body, bodyParams);

        addButtonRow(fallback,
            compactButton("服务控制", v -> openHermesControl(), true),
            compactButton("刷新", v -> reloadHermesWebView(), true));
        fallback.addView(button("进入安装引导", v -> openInstallGuide()));
        return fallback;
    }

    private void configureHermesWebView(WebView webView) {
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            settings.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        }
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                hermesLoadFailed = false;
                setHermesFallbackVisible(false);
                setHermesStatus("正在连接 " + getHermesTitle() + "：" + getHermesUrl());
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                if (!hermesLoadFailed) {
                    setHermesFallbackVisible(false);
                    setHermesStatus(getHermesTitle() + " 已连接：" + getHermesUrl());
                }
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && request != null && request.isForMainFrame()) {
                    showHermesUnavailable();
                }
            }

            @SuppressWarnings("deprecation")
            @Override
            public void onReceivedError(WebView view, int errorCode, String description, String failingUrl) {
                showHermesUnavailable();
            }
        });
    }

    private void reloadHermesWebView() {
        hermesLoadFailed = false;
        setHermesFallbackVisible(false);
        String hermesUrl = getHermesUrl();
        setHermesStatus("正在刷新 " + getHermesTitle() + "：" + hermesUrl);
        if (hermesWebView != null) {
            hermesWebView.loadUrl(hermesUrl);
        }
    }

    private void showHermesUnavailable() {
        hermesLoadFailed = true;
        setHermesStatus(getHermesTitle() + " 未连接：" + getHermesUrl());
        setHermesFallbackVisible(true);
    }

    private void setHermesFallbackVisible(boolean visible) {
        if (hermesFallbackView != null) {
            hermesFallbackView.setVisibility(visible ? View.VISIBLE : View.GONE);
        }
    }

    private void setHermesStatus(String text) {
        if (hermesStatusView != null) {
            hermesStatusView.setText(text);
        }
    }

    private void renderSmallPhonePage() {
        OpenHouseComponent component = findSmallPhoneComponent();
        if (component != null
            && component.entryType == OpenHouseComponent.EntryType.WEBVIEW
            && !isBlank(component.url)) {
            renderDynamicWebViewPage(component);
            return;
        }
        showEmbeddedContent();
        if (embeddedContentView == null) {
            return;
        }
        if (smallPhoneController == null || smallPhoneView == null) {
            smallPhoneView = getLayoutInflater().inflate(R.layout.view_smallphone_host, embeddedContentView, false);
            smallPhoneController = new SmallPhoneHostController(this, smallPhoneView);
            smallPhoneController.setNavigationDelegate(new SmallPhoneHostController.NavigationDelegate() {
                @Override
                public boolean openSmallPhoneMenu() {
                    drawerLayout.openDrawer(GravityCompat.START);
                    return true;
                }

                @Override
                public boolean openMaintenanceCenter() {
                    OpenHouseHomeActivity.this.openMaintenanceCenter();
                    return true;
                }

                @Override
                public boolean openTerminal() {
                    OpenHouseHomeActivity.this.openTerminal(false);
                    return true;
                }

                @Override
                public boolean openExternal(String url) {
                    OpenHouseHomeActivity.this.openUrl(url);
                    return true;
                }
            });
        }
        attachEmbeddedView(smallPhoneView);
        smallPhoneController.onResume(true);
    }

    private void renderAiPage() {
        showEmbeddedContent();
        if (embeddedContentView == null) {
            return;
        }
        String ccCodexUrl = getCcCodexUrl();
        if (cloudCliPageView == null || !ccCodexUrl.equals(renderedCloudCliUrl)) {
            if (cloudCliWebView != null) {
                cloudCliWebView.destroy();
                cloudCliWebView = null;
            }
            cloudCliPageView = createCloudCliPageView();
            renderedCloudCliUrl = ccCodexUrl;
        }
        attachEmbeddedView(cloudCliPageView);
        if (cloudCliWebView != null) {
            cloudCliWebView.onResume();
            if (cloudCliWebView.getUrl() == null) {
                reloadCloudCliWebView();
            }
        }
    }

    private LinearLayout createCloudCliPageView() {
        String ccCodexUrl = getCcCodexUrl();
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setBackgroundColor(ContextCompat.getColor(this, R.color.surface));

        cloudCliControlPanel = createCloudCliControlPanel();
        cloudCliControlPanel.setVisibility(cloudCliControlsVisible ? View.VISIBLE : View.GONE);
        page.addView(cloudCliControlPanel, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT));

        FrameLayout browserHost = new FrameLayout(this);
        cloudCliWebView = new WebView(this);
        configureCloudCliWebView(cloudCliWebView);
        browserHost.addView(cloudCliWebView, new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT));

        cloudCliFallbackView = createCloudCliFallbackView();
        cloudCliFallbackView.setVisibility(View.GONE);
        browserHost.addView(cloudCliFallbackView, new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT));
        page.addView(browserHost, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            0,
            1));
        return page;
    }

    private LinearLayout createCloudCliControlPanel() {
        String ccCodexTitle = getCcCodexTitle();
        String ccCodexUrl = getCcCodexUrl();
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(12), dp(10), dp(12), dp(12));
        panel.setBackgroundColor(ContextCompat.getColor(this, R.color.panel));

        cloudCliStatusView = new TextView(this);
        cloudCliStatusView.setText(ccCodexTitle + " 地址：" + ccCodexUrl);
        cloudCliStatusView.setTextColor(ContextCompat.getColor(this, R.color.textSecondary));
        cloudCliStatusView.setTextSize(13);
        panel.addView(cloudCliStatusView);

        addButtonRow(panel,
            compactButton("安装", v -> runClaudeCodeUiAction(OpenHouseMaintainerRunner.Action.INSTALL_CLAUDE_CODE_UI), true),
            compactButton("启动", v -> runClaudeCodeUiAction(OpenHouseMaintainerRunner.Action.START_CLAUDE_CODE_UI), true));
        addButtonRow(panel,
            compactButton("停止", v -> runClaudeCodeUiAction(OpenHouseMaintainerRunner.Action.STOP_CLAUDE_CODE_UI), true),
            compactButton("重启", v -> runClaudeCodeUiAction(OpenHouseMaintainerRunner.Action.RESTART_CLAUDE_CODE_UI), true));
        addButtonRow(panel,
            compactButton("复制地址", v -> copyText(getCcCodexTitle() + " 地址", getCcCodexUrl()), true),
            compactButton("刷新", v -> reloadCloudCliWebView(), true));
        return panel;
    }

    private LinearLayout createCloudCliFallbackView() {
        String ccCodexTitle = getCcCodexTitle();
        String ccCodexUrl = getCcCodexUrl();
        LinearLayout fallback = new LinearLayout(this);
        fallback.setOrientation(LinearLayout.VERTICAL);
        fallback.setGravity(Gravity.CENTER);
        fallback.setPadding(dp(22), dp(22), dp(22), dp(22));
        fallback.setBackgroundColor(ContextCompat.getColor(this, R.color.surface));

        TextView title = new TextView(this);
        title.setText(ccCodexTitle + " 未连接");
        title.setTextColor(ContextCompat.getColor(this, R.color.textPrimary));
        title.setTextSize(20);
        title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
        fallback.addView(title);

        TextView body = new TextView(this);
        body.setText("没有连接到 " + ccCodexUrl + "。可以先启动 " + ccCodexTitle + "，启动后本页会继续使用内置浏览器打开。");
        body.setTextColor(ContextCompat.getColor(this, R.color.textSecondary));
        body.setTextSize(14);
        body.setGravity(Gravity.CENTER);
        body.setLineSpacing(dp(2), 1.0f);
        LinearLayout.LayoutParams bodyParams = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT);
        bodyParams.setMargins(0, dp(10), 0, dp(6));
        fallback.addView(body, bodyParams);

        addButtonRow(fallback,
            compactButton("启动", v -> runClaudeCodeUiAction(OpenHouseMaintainerRunner.Action.START_CLAUDE_CODE_UI), true),
            compactButton("刷新", v -> reloadCloudCliWebView(), true));
        Button showControls = button("展开控制", v -> {
            cloudCliControlsVisible = true;
            if (cloudCliControlPanel != null) {
                cloudCliControlPanel.setVisibility(View.VISIBLE);
            }
        });
        fallback.addView(showControls);
        return fallback;
    }

    private void configureCloudCliWebView(WebView webView) {
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            settings.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        }
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                cloudCliLoadFailed = false;
                setCloudCliFallbackVisible(false);
                setCloudCliStatus("正在连接 " + getCcCodexTitle() + "：" + getCcCodexUrl());
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                if (!cloudCliLoadFailed) {
                    setCloudCliFallbackVisible(false);
                    setCloudCliStatus(getCcCodexTitle() + " 已连接：" + getCcCodexUrl());
                }
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && request != null && request.isForMainFrame()) {
                    showCloudCliUnavailable();
                }
            }

            @SuppressWarnings("deprecation")
            @Override
            public void onReceivedError(WebView view, int errorCode, String description, String failingUrl) {
                showCloudCliUnavailable();
            }
        });
    }

    private void toggleCloudCliControls() {
        cloudCliControlsVisible = !cloudCliControlsVisible;
        if (cloudCliControlPanel != null) {
            cloudCliControlPanel.setVisibility(cloudCliControlsVisible ? View.VISIBLE : View.GONE);
        }
    }

    private void openCcCodexControlOrToggle() {
        OpenHouseComponent ccCodexComponent = findCcCodexComponent();
        if (ccCodexComponent != null && ccCodexComponent.hasControlEntry()) {
            openComponentControl(ccCodexComponent);
            return;
        }
        toggleCloudCliControls();
    }

    private void reloadCloudCliWebView() {
        cloudCliLoadFailed = false;
        setCloudCliFallbackVisible(false);
        String ccCodexUrl = getCcCodexUrl();
        setCloudCliStatus("正在刷新 " + getCcCodexTitle() + "：" + ccCodexUrl);
        if (cloudCliWebView != null) {
            cloudCliWebView.loadUrl(ccCodexUrl);
        }
    }

    private void showCloudCliUnavailable() {
        cloudCliLoadFailed = true;
        setCloudCliStatus(getCcCodexTitle() + " 未连接：" + getCcCodexUrl());
        setCloudCliFallbackVisible(true);
    }

    private void setCloudCliFallbackVisible(boolean visible) {
        if (cloudCliFallbackView != null) {
            cloudCliFallbackView.setVisibility(visible ? View.VISIBLE : View.GONE);
        }
    }

    private void setCloudCliStatus(String text) {
        if (cloudCliStatusView != null) {
            cloudCliStatusView.setText(text);
        }
    }

    private void renderControlledBrowserPage() {
        OpenHouseComponent component = findControlledBrowserComponent();
        if (component != null
            && component.entryType == OpenHouseComponent.EntryType.WEBVIEW
            && !isBlank(component.url)) {
            renderDynamicWebViewPage(component);
            return;
        }
        showEmbeddedContent();
        if (embeddedContentView == null) {
            return;
        }
        controlledBrowserView = ControlledBrowserRuntime.getInstance().getOrCreateView(this);
        controlledBrowserView.setExternalNavigationHandler((browserView, uri) -> {
            openUrl(uri.toString());
            return true;
        });
        attachEmbeddedView(controlledBrowserView);
        controlledBrowserView.onHostResume();
    }

    private void renderDynamicWebViewPage(OpenHouseComponent component) {
        showEmbeddedContent();
        if (embeddedContentView == null) {
            return;
        }
        if (dynamicWebComponent == null
            || !dynamicWebComponent.id.equals(component.id)
            || !dynamicWebComponent.title.equals(component.title)
            || !dynamicWebComponent.url.equals(component.url)
            || dynamicWebPageView == null) {
            if (dynamicWebView != null) {
                dynamicWebView.destroy();
                dynamicWebView = null;
            }
            dynamicWebComponent = component;
            dynamicWebPageView = createDynamicWebViewPage(component);
        }
        attachEmbeddedView(dynamicWebPageView);
        if (dynamicWebView != null) {
            dynamicWebView.onResume();
            if (dynamicWebView.getUrl() == null) {
                reloadDynamicWebView();
            }
        }
    }

    private LinearLayout createDynamicWebViewPage(OpenHouseComponent component) {
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setBackgroundColor(ContextCompat.getColor(this, R.color.surface));

        dynamicWebStatusView = new TextView(this);
        dynamicWebStatusView.setText(component.title + " 地址：" + component.url);
        dynamicWebStatusView.setTextColor(ContextCompat.getColor(this, R.color.textSecondary));
        dynamicWebStatusView.setTextSize(12);
        dynamicWebStatusView.setPadding(dp(12), dp(6), dp(12), dp(6));
        dynamicWebStatusView.setBackgroundColor(ContextCompat.getColor(this, R.color.panel));
        page.addView(dynamicWebStatusView, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT));

        FrameLayout browserHost = new FrameLayout(this);
        dynamicWebView = new WebView(this);
        configureDynamicWebView(dynamicWebView);
        browserHost.addView(dynamicWebView, new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT));

        dynamicWebFallbackView = createDynamicWebFallbackView(component);
        dynamicWebFallbackView.setVisibility(View.GONE);
        browserHost.addView(dynamicWebFallbackView, new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT));
        page.addView(browserHost, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            0,
            1));
        return page;
    }

    private LinearLayout createDynamicWebFallbackView(OpenHouseComponent component) {
        LinearLayout fallback = new LinearLayout(this);
        fallback.setOrientation(LinearLayout.VERTICAL);
        fallback.setGravity(Gravity.CENTER);
        fallback.setPadding(dp(22), dp(22), dp(22), dp(22));
        fallback.setBackgroundColor(ContextCompat.getColor(this, R.color.surface));

        TextView title = new TextView(this);
        title.setText(component.title + " 未连接");
        title.setTextColor(ContextCompat.getColor(this, R.color.textPrimary));
        title.setTextSize(20);
        title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
        fallback.addView(title);

        TextView body = new TextView(this);
        body.setText("没有连接到 " + component.url + "。可以先进入服务控制或维护中心启动、修复对应服务。");
        body.setTextColor(ContextCompat.getColor(this, R.color.textSecondary));
        body.setTextSize(14);
        body.setGravity(Gravity.CENTER);
        body.setLineSpacing(dp(2), 1.0f);
        LinearLayout.LayoutParams bodyParams = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT);
        bodyParams.setMargins(0, dp(10), 0, dp(6));
        fallback.addView(body, bodyParams);

        addButtonRow(fallback,
            compactButton(component.hasControlEntry() ? "服务控制" : "维护中心",
                v -> {
                    if (component.hasControlEntry()) {
                        openComponentControl(component);
                    } else {
                        openMaintenanceCenter();
                    }
                },
                true),
            compactButton("刷新", v -> reloadDynamicWebView(), true));
        fallback.addView(button("复制地址", v -> copyText(component.title, component.url)));
        return fallback;
    }

    private void configureDynamicWebView(WebView webView) {
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            settings.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        }
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                dynamicWebLoadFailed = false;
                setDynamicWebFallbackVisible(false);
                setDynamicWebStatus("正在连接：" + url);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                if (!dynamicWebLoadFailed) {
                    setDynamicWebFallbackVisible(false);
                    setDynamicWebStatus("已连接：" + url);
                }
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && request != null && request.isForMainFrame()) {
                    showDynamicWebUnavailable();
                }
            }

            @SuppressWarnings("deprecation")
            @Override
            public void onReceivedError(WebView view, int errorCode, String description, String failingUrl) {
                showDynamicWebUnavailable();
            }
        });
    }

    private void reloadDynamicWebView() {
        dynamicWebLoadFailed = false;
        setDynamicWebFallbackVisible(false);
        if (dynamicWebComponent != null) {
            setDynamicWebStatus("正在刷新：" + dynamicWebComponent.url);
        }
        if (dynamicWebView != null && dynamicWebComponent != null) {
            dynamicWebView.loadUrl(dynamicWebComponent.url);
        }
    }

    private void showDynamicWebUnavailable() {
        dynamicWebLoadFailed = true;
        if (dynamicWebComponent != null) {
            setDynamicWebStatus("未连接：" + dynamicWebComponent.url);
        }
        setDynamicWebFallbackVisible(true);
    }

    private void setDynamicWebFallbackVisible(boolean visible) {
        if (dynamicWebFallbackView != null) {
            dynamicWebFallbackView.setVisibility(visible ? View.VISIBLE : View.GONE);
        }
    }

    private void setDynamicWebStatus(String text) {
        if (dynamicWebStatusView != null) {
            dynamicWebStatusView.setText(text);
        }
    }

    private void attachEmbeddedView(View view) {
        if (view == null || embeddedContentView == null) {
            return;
        }
        if (view.getParent() == embeddedContentView) {
            return;
        }
        if (view.getParent() instanceof ViewGroup) {
            ((ViewGroup) view.getParent()).removeView(view);
        }
        embeddedContentView.removeAllViews();
        embeddedContentView.addView(view, new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT));
    }

    private void pauseCurrentEmbeddedPage() {
        if (PAGE_HERMES.equals(currentPage) && hermesWebView != null) {
            hermesWebView.onPause();
        } else if (PAGE_SMALLPHONE.equals(currentPage)
            && isCurrentDynamicWebComponent(findSmallPhoneComponent())
            && dynamicWebView != null) {
            dynamicWebView.onPause();
        } else if (PAGE_SMALLPHONE.equals(currentPage) && smallPhoneController != null) {
            smallPhoneController.onPause();
        } else if (PAGE_AI.equals(currentPage) && cloudCliWebView != null) {
            cloudCliWebView.onPause();
        } else if (PAGE_CONTROLLED_BROWSER.equals(currentPage)
            && isCurrentDynamicWebComponent(findControlledBrowserComponent())
            && dynamicWebView != null) {
            dynamicWebView.onPause();
        } else if (isComponentPage(currentPage) && dynamicWebView != null) {
            dynamicWebView.onPause();
        }
    }

    private void openHermesControl() {
        OpenHouseComponent hermesComponent = findHermesComponent();
        if (hermesComponent != null && hermesComponent.hasControlEntry()) {
            openComponentControl(hermesComponent);
            return;
        }
        openAllServiceControl();
    }

    private void openComponent(OpenHouseComponent component) {
        if (component == null) {
            return;
        }
        if (!component.hasEntry()) {
            openComponentControl(component);
            return;
        }
        if (component.entryType == OpenHouseComponent.EntryType.WEBVIEW) {
            selectPage(PAGE_COMPONENT_PREFIX + component.id);
            return;
        }
        if (component.entryType == OpenHouseComponent.EntryType.NATIVE_PAGE) {
            String page = resolveNativePage(component.nativePage);
            if (page == null) {
                Toast.makeText(this, "不支持的内置页面：" + component.nativePage, Toast.LENGTH_SHORT).show();
                return;
            }
            selectPage(page);
            return;
        }
        if (component.entryType == OpenHouseComponent.EntryType.TERMINAL) {
            openTerminal(false);
        }
    }

    private void openBuiltinComponentOrFallback(OpenHouseComponent component, String fallbackPage) {
        if (openComponentEntryIfAvailable(component)) {
            return;
        }
        selectPage(fallbackPage);
    }

    private boolean openComponentEntryIfAvailable(OpenHouseComponent component) {
        if (component == null || !component.hasEntry()) {
            return false;
        }
        if (component.entryType == OpenHouseComponent.EntryType.WEBVIEW) {
            if (isBlank(component.url)) {
                return false;
            }
            openComponent(component);
            return true;
        }
        if (component.entryType == OpenHouseComponent.EntryType.NATIVE_PAGE) {
            if (isBlank(resolveNativePage(component.nativePage))) {
                return false;
            }
            openComponent(component);
            return true;
        }
        if (component.entryType == OpenHouseComponent.EntryType.TERMINAL) {
            openComponent(component);
            return true;
        }
        return false;
    }

    private void openComponentControl(OpenHouseComponent component) {
        if (component == null || !component.hasControlEntry()) {
            openMaintenanceCenter();
            return;
        }
        Intent intent = new Intent(this, OpenHouseServiceControlActivity.class);
        intent.putExtra(EXTRA_SERVICE_CONTROL_MODE, SERVICE_CONTROL_MODE_COMPONENT);
        intent.putExtra(EXTRA_SERVICE_CONTROL_COMPONENT_ID, component.id);
        intent.putExtra(EXTRA_SERVICE_CONTROL_TITLE, component.title);
        intent.putExtra(EXTRA_SERVICE_CONTROL_URL, firstNonBlank(component.url, getCurrentBrowserUrl()));
        intent.putExtra(EXTRA_SERVICE_CONTROL_SERVICE_NAMES, joinValues(component.serviceNames));
        intent.putExtra(EXTRA_SERVICE_CONTROL_SERVICE_REFS, joinValues(component.serviceRefs));
        if (drawerLayout != null) {
            drawerLayout.closeDrawer(GravityCompat.START);
        }
        ActivityUtils.startActivity(this, intent);
    }

    private void openAllServiceControl() {
        Intent intent = new Intent(this, OpenHouseServiceControlActivity.class);
        intent.putExtra(EXTRA_SERVICE_CONTROL_MODE, SERVICE_CONTROL_MODE_ALL);
        if (drawerLayout != null) {
            drawerLayout.closeDrawer(GravityCompat.START);
        }
        ActivityUtils.startActivity(this, intent);
    }

    private String resolveNativePage(String page) {
        if (isBlank(page)) {
            return null;
        }
        String normalized = page.trim().toLowerCase(java.util.Locale.US).replace('_', '-');
        switch (normalized) {
            case PAGE_HOME:
                return PAGE_HOME;
            case PAGE_HERMES:
            case "hermes-webui":
                return PAGE_HERMES;
            case PAGE_AI:
            case "cc-codex":
            case "cloudcli":
            case "claude-code-ui":
                return PAGE_AI;
            case PAGE_SMALLPHONE:
                return PAGE_SMALLPHONE;
            case "controlled-browser":
                return PAGE_CONTROLLED_BROWSER;
            case PAGE_MANUAL:
                return PAGE_MANUAL;
            case PAGE_OPENCODE:
                return PAGE_OPENCODE;
            case PAGE_DEEPSEEK:
                return PAGE_DEEPSEEK;
            case PAGE_PERMISSIONS:
                return PAGE_PERMISSIONS;
            case PAGE_ABOUT:
                return PAGE_ABOUT;
            case "terminal-guide":
                return PAGE_TERMINAL_GUIDE;
            case PAGE_SHORTCUTS:
                return PAGE_SHORTCUTS;
            case PAGE_REPAIR:
                return PAGE_REPAIR;
            case PAGE_LOGS:
                return PAGE_LOGS;
            case PAGE_ADVANCED:
                return PAGE_ADVANCED;
            default:
                return null;
        }
    }

    private boolean isComponentPage(String page) {
        return page != null && page.startsWith(PAGE_COMPONENT_PREFIX);
    }

    private String extractComponentId(String page) {
        if (!isComponentPage(page)) {
            return null;
        }
        return page.substring(PAGE_COMPONENT_PREFIX.length());
    }

    private OpenHouseComponent findDynamicComponent(String id) {
        if (isBlank(id)) {
            return null;
        }
        for (OpenHouseComponent component : dynamicComponents) {
            if (id.equals(component.id)) {
                return component;
            }
        }
        return null;
    }

    private boolean isComponentVisible(OpenHouseComponent component) {
        return component == null || component.visible;
    }

    private OpenHouseComponent findBuiltinComponent(String homeTarget) {
        String normalized = normalizeId(homeTarget);
        if (HERMES_SERVICE_NAME.equals(normalized) || PAGE_HERMES.equals(normalized)) {
            return findHermesComponent();
        }
        if (CC_CODEX_SERVICE_NAME.equals(normalized)
            || "cc-codex".equals(normalized)
            || "claude-code-ui".equals(normalized)
            || "claudecodeui".equals(normalized)
            || PAGE_AI.equals(normalized)) {
            return findCcCodexComponent();
        }
        if (SMALLPHONE_HOME_TARGET.equals(normalized)
            || PAGE_SMALLPHONE.equals(normalized)
            || "smallphone-core".equals(normalized)) {
            return findSmallPhoneComponent();
        }
        if (PAGE_CONTROLLED_BROWSER.equals(normalized)) {
            return findControlledBrowserComponent();
        }
        return null;
    }

    private OpenHouseComponent findHermesComponent() {
        for (OpenHouseComponent component : dynamicComponents) {
            if (component == null) {
                continue;
            }
            if (isHermesComponent(component)) {
                return component;
            }
        }
        return null;
    }

    private OpenHouseComponent findCcCodexComponent() {
        for (OpenHouseComponent component : dynamicComponents) {
            if (component == null) {
                continue;
            }
            if (isCcCodexComponent(component)) {
                return component;
            }
        }
        return null;
    }

    private OpenHouseComponent findSmallPhoneComponent() {
        for (OpenHouseComponent component : dynamicComponents) {
            if (component == null) {
                continue;
            }
            if (isSmallPhoneComponent(component)) {
                return component;
            }
        }
        return null;
    }

    private OpenHouseComponent findControlledBrowserComponent() {
        for (OpenHouseComponent component : dynamicComponents) {
            if (component == null) {
                continue;
            }
            if (isControlledBrowserComponent(component)) {
                return component;
            }
        }
        return null;
    }

    private boolean isBuiltinNavigationComponent(OpenHouseComponent component) {
        if (component == null) {
            return false;
        }
        return isHermesComponent(component)
            || isCcCodexComponent(component)
            || isSmallPhoneComponent(component)
            || isControlledBrowserComponent(component)
            || isNativeBuiltinComponent(component);
    }

    private boolean isHermesComponent(OpenHouseComponent component) {
        if (component == null) {
            return false;
        }
        String id = normalizeId(component.id);
        if (PAGE_HERMES.equals(id) || HERMES_SERVICE_NAME.equals(id)) {
            return true;
        }
        for (String name : component.serviceNames) {
            if (HERMES_SERVICE_NAME.equals(normalizeId(name))) {
                return true;
            }
        }
        return sameUrl(component.url, HERMES_URL);
    }

    private boolean isCcCodexComponent(OpenHouseComponent component) {
        if (component == null) {
            return false;
        }
        String id = normalizeId(component.id);
        if ("cc-codex".equals(id)
            || "cloudcli".equals(id)
            || "claude-code-ui".equals(id)
            || "claudecodeui".equals(id)) {
            return true;
        }
        return sameUrl(component.url, cloudCliUrl);
    }

    private boolean isSmallPhoneComponent(OpenHouseComponent component) {
        if (component == null) {
            return false;
        }
        String id = normalizeId(component.id);
        if (SMALLPHONE_HOME_TARGET.equals(id)
            || PAGE_SMALLPHONE.equals(id)
            || "smallphone-core".equals(id)) {
            return true;
        }
        if (PAGE_SMALLPHONE.equals(resolveNativePage(component.nativePage))) {
            return true;
        }
        for (String name : component.serviceNames) {
            if ("smallphone-core".equals(normalizeId(name))) {
                return true;
            }
        }
        return false;
    }

    private boolean isControlledBrowserComponent(OpenHouseComponent component) {
        if (component == null) {
            return false;
        }
        String id = normalizeId(component.id);
        if (PAGE_CONTROLLED_BROWSER.equals(id)) {
            return true;
        }
        if (PAGE_CONTROLLED_BROWSER.equals(resolveNativePage(component.nativePage))) {
            return true;
        }
        for (String name : component.serviceNames) {
            if (PAGE_CONTROLLED_BROWSER.equals(normalizeId(name))) {
                return true;
            }
        }
        return false;
    }

    private String getHermesTitle() {
        OpenHouseComponent component = findHermesComponent();
        return componentTitleOrDefault(component, "Hermes");
    }

    private String getHermesSubtitle(String fallback) {
        return componentSubtitleOrDefault(findHermesComponent(), fallback);
    }

    private String getHermesUrl() {
        OpenHouseComponent component = findHermesComponent();
        if (component != null
            && component.entryType == OpenHouseComponent.EntryType.WEBVIEW
            && !isBlank(component.url)) {
            return component.url;
        }
        return HERMES_URL;
    }

    private String getCcCodexTitle() {
        OpenHouseComponent component = findCcCodexComponent();
        return componentTitleOrDefault(component, CC_CODEX_TITLE);
    }

    private String getCcCodexSubtitle(String fallback) {
        return componentSubtitleOrDefault(findCcCodexComponent(), fallback);
    }

    private String getCcCodexUrl() {
        OpenHouseComponent component = findCcCodexComponent();
        if (component != null
            && component.entryType == OpenHouseComponent.EntryType.WEBVIEW
            && !isBlank(component.url)) {
            return component.url;
        }
        return cloudCliUrl;
    }

    private String getSmallPhoneTitle() {
        return componentTitleOrDefault(findSmallPhoneComponent(), "SmallPhone");
    }

    private String getSmallPhoneSubtitle(String fallback) {
        return componentSubtitleOrDefault(findSmallPhoneComponent(), fallback);
    }

    private String getControlledBrowserTitle() {
        return componentTitleOrDefault(findControlledBrowserComponent(), "受控浏览器");
    }

    private String getControlledBrowserSubtitle(String fallback) {
        return componentSubtitleOrDefault(findControlledBrowserComponent(), fallback);
    }

    private String componentTitleOrDefault(OpenHouseComponent component, String fallback) {
        return component != null && !isBlank(component.title) ? component.title : fallback;
    }

    private String componentSubtitleOrDefault(OpenHouseComponent component, String fallback) {
        return component != null && !isBlank(component.subtitle) ? component.subtitle : fallback;
    }

    private boolean isNativeBuiltinComponent(OpenHouseComponent component) {
        if (component == null || component.entryType != OpenHouseComponent.EntryType.NATIVE_PAGE) {
            return false;
        }
        return resolveNativePage(component.nativePage) != null;
    }

    private String normalizeId(String value) {
        return isBlank(value) ? "" : value.trim().toLowerCase(java.util.Locale.US).replace('_', '-');
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (!isBlank(value)) {
                return value.trim();
            }
        }
        return "";
    }

    private boolean sameUrl(String left, String right) {
        if (isBlank(left) || isBlank(right)) {
            return false;
        }
        String normalizedLeft = left.trim();
        String normalizedRight = right.trim();
        if (normalizedLeft.endsWith("/") && !normalizedRight.endsWith("/")) {
            normalizedLeft = normalizedLeft.substring(0, normalizedLeft.length() - 1);
        }
        if (normalizedRight.endsWith("/") && !normalizedLeft.endsWith("/")) {
            normalizedRight = normalizedRight.substring(0, normalizedRight.length() - 1);
        }
        return normalizedLeft.equalsIgnoreCase(normalizedRight);
    }

    private boolean handleOpenHouseIntent(Intent intent) {
        if (intent == null) {
            return false;
        }

        boolean hasBrowserCommand = ControlledBrowserRpcFiles.hasBrowserCommand(intent);
        Bundle browserCommand = hasBrowserCommand
            ? ControlledBrowserRpcFiles.normalizeCommand(this, intent)
            : null;
        String requestedPage = intent.getStringExtra(ControlledBrowserContract.EXTRA_OPENHOUSE_PAGE);
        if (isBlank(requestedPage)) {
            requestedPage = intent.getStringExtra("openhouse_page");
        }

        if (hasBrowserCommand || PAGE_CONTROLLED_BROWSER.equals(requestedPage)) {
            if (browserCommand != null) {
                ControlledBrowserRuntime.getInstance().ensureStarted(this);
                ControlledBrowserCommandDispatcher.getInstance().enqueue(this, browserCommand);
            }
            selectPage(PAGE_CONTROLLED_BROWSER);
            return true;
        }
        String resolvedPage = resolveNativePage(requestedPage);
        if (!isBlank(resolvedPage)) {
            selectPage(resolvedPage);
            return true;
        }
        return false;
    }

    private boolean hasExplicitOpenHouseTarget(Intent intent) {
        if (intent == null) {
            return false;
        }
        if (ControlledBrowserRpcFiles.hasBrowserCommand(intent)) {
            return true;
        }
        String requestedPage = intent.getStringExtra(ControlledBrowserContract.EXTRA_OPENHOUSE_PAGE);
        if (isBlank(requestedPage)) {
            requestedPage = intent.getStringExtra("openhouse_page");
        }
        return !isBlank(requestedPage);
    }

    private boolean isLauncherOpenIntent(Intent intent) {
        return intent != null
            && Intent.ACTION_MAIN.equals(intent.getAction())
            && !hasExplicitOpenHouseTarget(intent);
    }

    private boolean routeFirstLaunchGateIfNeeded() {
        if (firstLaunchGateForwarded) {
            return true;
        }
        if (SmallPhoneFirstLaunchGate.launchIfNeeded(this)) {
            firstLaunchGateForwarded = true;
            return true;
        }
        return false;
    }

    private void selectConfiguredHomePage() {
        selectPage(getConfiguredHomePage());
    }

    private String getConfiguredHomePage() {
        String configuredTarget = readConfiguredHomeTarget();
        String configured = homeTargetToPage(configuredTarget);
        if (isHomeCandidate(configured)) {
            return configured;
        }
        configured = getOpenHouseHomePrefs().getString(PREF_HOME_PAGE, PAGE_HERMES);
        if (isHomeCandidate(configured)) {
            return configured;
        }
        return firstVisibleHomePage();
    }

    private String firstVisibleHomePage() {
        if (isComponentVisible(findHermesComponent())) {
            return PAGE_HERMES;
        }
        if (isComponentVisible(findSmallPhoneComponent())) {
            return PAGE_SMALLPHONE;
        }
        if (isComponentVisible(findCcCodexComponent())) {
            return PAGE_AI;
        }
        if (isComponentVisible(findControlledBrowserComponent())) {
            return PAGE_CONTROLLED_BROWSER;
        }
        for (OpenHouseComponent component : dynamicComponents) {
            if (component != null && component.hasEntry() && isComponentVisible(component)) {
                if (component.entryType == OpenHouseComponent.EntryType.NATIVE_PAGE) {
                    String page = resolveNativePage(component.nativePage);
                    if (!isBlank(page)) {
                        return page;
                    }
                }
                return PAGE_COMPONENT_PREFIX + component.id;
            }
        }
        return PAGE_HOME;
    }

    private SharedPreferences getOpenHouseHomePrefs() {
        return getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
    }

    private void setCurrentPageAsHome() {
        if (!isHomeCandidate(currentPage)) {
            Toast.makeText(this, "当前页面不能设为首页。", Toast.LENGTH_SHORT).show();
            updateHomePreferenceViews();
            return;
        }
        setHomePage(currentPage);
    }

    private void setHomePage(String page) {
        if (!isHomeCandidate(page)) {
            Toast.makeText(this, "这个入口暂时不能设为首页。", Toast.LENGTH_SHORT).show();
            return;
        }
        String homeTarget = pageToHomeTarget(page);
        boolean wroteUnifiedConfig = !isBlank(homeTarget) && writeConfiguredHomeTarget(homeTarget);
        if (wroteUnifiedConfig) {
            getOpenHouseHomePrefs().edit().remove(PREF_HOME_PAGE).apply();
            refreshDynamicComponents();
        } else {
            getOpenHouseHomePrefs().edit().putString(PREF_HOME_PAGE, page).apply();
            updateHomePreferenceViews();
        }
        String message = "首页已设为：" + getHomeDisplayTitle(page);
        if (!wroteUnifiedConfig) {
            message += "（已保存到本地兜底配置）";
        }
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    private File getMenuOverridesFile() {
        return new File(TermuxConstants.TERMUX_HOME_DIR_PATH, MENU_OVERRIDES_RELATIVE_PATH);
    }

    private String readConfiguredHomeTarget() {
        if (dynamicRegistryResult != null && !isBlank(dynamicRegistryResult.homeTarget)) {
            return dynamicRegistryResult.homeTarget;
        }
        File file = getMenuOverridesFile();
        if (!file.isFile()) {
            return null;
        }
        try {
            JSONObject root = new JSONObject(readTextFile(file));
            return firstNonBlank(
                root.optString("homeTarget", ""),
                root.optString("home_target", ""),
                root.optString("defaultHome", ""),
                root.optString("default_home", ""),
                root.optString("home", ""));
        } catch (Exception e) {
            Logger.logStackTraceWithMessage(LOG_TAG,
                "Unable to read menu overrides from " + file.getAbsolutePath(), e);
            return null;
        }
    }

    private boolean writeConfiguredHomeTarget(String homeTarget) {
        File file = getMenuOverridesFile();
        try {
            File parent = file.getParentFile();
            if (parent != null && !parent.isDirectory() && !parent.mkdirs() && !parent.isDirectory()) {
                return false;
            }
            JSONObject root = file.isFile()
                ? new JSONObject(readTextFile(file))
                : new JSONObject();
            root.put("homeTarget", homeTarget);
            writeTextFile(file, root.toString(2) + "\n");
            return true;
        } catch (Exception e) {
            Logger.logStackTraceWithMessage(LOG_TAG,
                "Unable to write menu overrides to " + file.getAbsolutePath(), e);
            return false;
        }
    }

    private String readTextFile(File file) throws Exception {
        FileInputStream input = new FileInputStream(file);
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }
            return new String(output.toByteArray(), StandardCharsets.UTF_8);
        } finally {
            input.close();
        }
    }

    private void writeTextFile(File file, String text) throws Exception {
        FileOutputStream output = new FileOutputStream(file, false);
        try {
            output.write(text.getBytes(StandardCharsets.UTF_8));
        } finally {
            output.close();
        }
    }

    private String homeTargetToPage(String homeTarget) {
        String normalized = normalizeId(homeTarget);
        if (isBlank(normalized)) {
            return null;
        }
        if (HERMES_SERVICE_NAME.equals(normalized) || PAGE_HERMES.equals(normalized)) {
            return isComponentVisible(findHermesComponent()) ? PAGE_HERMES : null;
        }
        if (CC_CODEX_SERVICE_NAME.equals(normalized)
            || "cc-codex".equals(normalized)
            || "claude-code-ui".equals(normalized)
            || "claudecodeui".equals(normalized)
            || PAGE_AI.equals(normalized)) {
            return isComponentVisible(findCcCodexComponent()) ? PAGE_AI : null;
        }
        if (SMALLPHONE_HOME_TARGET.equals(normalized)
            || PAGE_SMALLPHONE.equals(normalized)
            || "smallphone-core".equals(normalized)) {
            return isComponentVisible(findSmallPhoneComponent()) ? PAGE_SMALLPHONE : null;
        }
        if (PAGE_CONTROLLED_BROWSER.equals(normalized)) {
            return isComponentVisible(findControlledBrowserComponent()) ? PAGE_CONTROLLED_BROWSER : null;
        }
        OpenHouseComponent builtin = findBuiltinComponent(normalized);
        if (builtin != null && isComponentVisible(builtin)) {
            String nativePage = resolveNativePage(builtin.nativePage);
            if (!isBlank(nativePage)) {
                return nativePage;
            }
            if (builtin.entryType == OpenHouseComponent.EntryType.WEBVIEW) {
                return PAGE_COMPONENT_PREFIX + builtin.id;
            }
        }
        OpenHouseComponent component = findDynamicComponent(normalized);
        if (component != null && component.hasEntry() && isComponentVisible(component)) {
            if (component.entryType == OpenHouseComponent.EntryType.NATIVE_PAGE) {
                return resolveNativePage(component.nativePage);
            }
            return PAGE_COMPONENT_PREFIX + component.id;
        }
        return null;
    }

    private String pageToHomeTarget(String page) {
        if (PAGE_HERMES.equals(page)) {
            return HERMES_SERVICE_NAME;
        }
        if (PAGE_AI.equals(page)) {
            return CC_CODEX_SERVICE_NAME;
        }
        if (PAGE_SMALLPHONE.equals(page)) {
            return SMALLPHONE_HOME_TARGET;
        }
        if (PAGE_CONTROLLED_BROWSER.equals(page)) {
            return PAGE_CONTROLLED_BROWSER;
        }
        if (isComponentPage(page)) {
            return extractComponentId(page);
        }
        return null;
    }

    private boolean isHomeCandidate(String page) {
        if (isBlank(page)) {
            return false;
        }
        if (PAGE_HERMES.equals(page)
            || PAGE_SMALLPHONE.equals(page)
            || PAGE_AI.equals(page)
            || PAGE_CONTROLLED_BROWSER.equals(page)) {
            return isComponentVisible(findBuiltinComponent(page));
        }
        if (isComponentPage(page)) {
            OpenHouseComponent component = findDynamicComponent(extractComponentId(page));
            return component != null && component.hasEntry() && isComponentVisible(component);
        }
        return false;
    }

    private void updateHomePreferenceViews() {
        String homePage = getConfiguredHomePage();
        if (homeStatusView != null) {
            homeStatusView.setText("当前首页：" + getHomeDisplayTitle(homePage));
        }
        if (setCurrentHomeButton != null) {
            boolean canSet = isHomeCandidate(currentPage);
            setCurrentHomeButton.setEnabled(canSet);
            setCurrentHomeButton.setText(canSet && currentPage.equals(homePage) ? "已是首页" : "设当前页为首页");
        }
    }

    private String getHomeDisplayTitle(String page) {
        if (PAGE_HERMES.equals(page)) {
            return getHermesTitle();
        }
        if (PAGE_SMALLPHONE.equals(page)) {
            return getSmallPhoneTitle();
        }
        if (PAGE_AI.equals(page)) {
            return getCcCodexTitle();
        }
        if (PAGE_CONTROLLED_BROWSER.equals(page)) {
            return getControlledBrowserTitle();
        }
        if (isComponentPage(page)) {
            OpenHouseComponent component = findDynamicComponent(extractComponentId(page));
            if (component != null && !isBlank(component.title)) {
                return component.title;
            }
        }
        return getHermesTitle();
    }

    private String joinValues(List<String> values) {
        if (values == null || values.isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        for (String value : values) {
            if (isBlank(value)) {
                continue;
            }
            if (builder.length() > 0) {
                builder.append(',');
            }
            builder.append(value.trim());
        }
        return builder.toString();
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private void renderHomePage() {
        LinearLayout panel = panel();
        addTitle(panel, "菜单总览", 19);
        addBody(panel, "这里保留主入口，具体内容请从左侧侧边栏进入：使用手册、OpenCode 控制、DeepSeek Key、权限获取、终端快捷键和高级设置。");
        panel.addView(createAiFriendHelpControlBlock(true));
        addButtonRow(panel,
            compactButton("进入 AI 软件安装引导", v -> openInstallGuide(), true),
            compactButton("打开 " + getCcCodexTitle(),
                v -> openBuiltinComponentOrFallback(findCcCodexComponent(), PAGE_AI),
                true));
        panel.addView(button("打开 " + getHermesTitle(),
            v -> openBuiltinComponentOrFallback(findHermesComponent(), PAGE_HERMES)));
        panel.addView(button("打开 " + getSmallPhoneTitle(), v -> openSmallPhone()));
        panel.addView(button("退出菜单，回到终端", v -> openTerminal(false)));
        addButtonRow(panel,
            compactButton("OpenCode 控制", v -> selectPage(PAGE_OPENCODE), true),
            compactButton("DeepSeek Key", v -> selectPage(PAGE_DEEPSEEK), true));
        contentView.addView(panel);

        LinearLayout quick = panel();
        addTitle(quick, "快速状态", 17);
        addStatusRow(quick, "OpenCode 默认地址", getOpenCodeUrl(openCodePort));
        addStatusRow(quick, getCcCodexTitle() + " 地址", getCcCodexUrl());
        addStatusRow(quick, getHermesTitle() + " 地址", getHermesUrl());
        addStatusRow(quick, "OpenCode 目录", OpenCodeSettings.DEFAULT_PROJECT_DIRECTORY);
        addStatusRow(quick, "运行环境", "AI 工具安装在 Ubuntu /root");
        contentView.addView(quick);
        refreshAiFriendHelpEntryState();
    }

    private void renderManualPage() {
        addManualSection("安装时建议阅读",
            "第一次安装通常需要 10 分钟到半小时，期间会下载约 500M 文件，建议在 Wi-Fi 下进行。openhouse ai 会准备 Ubuntu、OpenCode、Codex、Claude Code 和 Reasonix。AI 能做什么，取决于你想让它做什么。");
        addManualSection("为什么需要 DeepSeek Key",
            "AI 运行通常需要模型 API。这里推荐 DeepSeek，是因为它相对实惠，适合作为第一次统一安装和配置引导。openhouse ai 不限制长期使用哪一个 API，后续可以让 AI 帮你接入自己的模型。");
        addManualSection("终端里的 AI 怎么用",
            "以 Claude Code 为例，在 Ubuntu 终端输入 claude 再按回车即可使用；想继续上次对话，可以输入 claude --continue。记不住命令时，底部快捷键会准备 claude、reasonix、codex、oc 和 --continue。");
        addManualSection("Termux 和 Ubuntu",
            "启动后看到的是 Termux 终端。openhouse ai 会在 Termux 里安装 Ubuntu proot，OpenCode、Codex、Claude Code、Reasonix 等 AI 软件安装在 Ubuntu 的 /root 环境。普通入口终端可以默认进入 Ubuntu，维护中心底部终端固定为 Termux。");
        addManualSection("OpenCode Web",
            "OpenCode 原生支持网页访问，并且模型接入范围广。新增项目时先使用 /root，不要把 4096 当成项目路径。启动、停止、重启、自定义端口和复制网址，请查看侧边栏里的“OpenCode 控制”。");
        addManualSection("底部快捷键",
            "底部按键包含 ESC、TAB、CTRL、ALT、方向键、键盘、Termux、Ubuntu、exit、clear，以及第三排 AI 快捷键。exit 用于退出当前 shell；Ubuntu 用于进入 Ubuntu /root。按键支持自定义和多页，可以直接让 AI 帮你修改常用命令。");
        addManualSection("更多 AI Agent",
            "OpenClaw、Hermes 或其他 AI Agent 可以后续安装。配置好基础环境后，你可以让 OpenCode、Claude Code 或 Reasonix 帮你下载、安装和配置想用的软件。Codex 也已安装，但 DeepSeek 官方没有直接给出接入 Codex 的方式，因此当前不默认配置。");
    }

    private void renderOpenCodePage() {
        LinearLayout panel = panel();
        addTitle(panel, "OpenCode Web 控制", 19);
        addBody(panel, "OpenCode 会在 Ubuntu 的 /root 目录启动。默认端口是 4096，也可以临时使用自定义端口启动。启动成功后会自动打开浏览器，并在本页显示可复制的网址。");
        addStatusRow(panel, "当前端口", Integer.toString(openCodePort));
        addStatusRow(panel, "可复制网址", lastOpenCodeUrl);
        addButtonRow(panel,
            compactButton("启动", v -> runOpenCodeAction(OpenHouseMaintainerRunner.Action.START, openCodePort), true),
            compactButton("停止", v -> runOpenCodeAction(OpenHouseMaintainerRunner.Action.STOP, openCodePort), true));
        addButtonRow(panel,
            compactButton("重启", v -> runOpenCodeAction(OpenHouseMaintainerRunner.Action.RESTART, openCodePort), true),
            compactButton("复制网址", v -> copyText(getString(R.string.openhouse_url_opencode_label), lastOpenCodeUrl), true));
        addButtonRow(panel,
            compactButton("自定义端口启动", v -> showCustomPortDialog(), true),
            compactButton("打开浏览器", v -> openUrl(lastOpenCodeUrl), true));
        contentView.addView(panel);

        addManualSection("OpenCode Web 使用说明",
            "打开浏览器网址后，如果新增项目或选择项目，先填写 /root。OpenCode 可以接入非常广泛的大模型 API；如果你没有配置 DeepSeek Key，也可以先启动 OpenCode Web，在网页里配置模型，再让 OpenCode 帮你配置其他 AI Agent。");
    }

    private void renderDeepSeekPage() {
        LinearLayout panel = panel();
        addTitle(panel, "一键替换 DeepSeek Key", 19);
        addBody(panel, "Key 变化时，可以在这里粘贴新 Key，并选择要替换配置的 AI 软件。默认全选 OpenCode、Claude Code 和 Reasonix；不会把真实 Key 打印到日志或页面。");
        addButtonRow(panel,
            compactButton("打开 DeepSeek 平台", v -> openUrl(getString(R.string.openhouse_deepseek_url)), true),
            compactButton("复制平台网址", v -> copyText("DeepSeek API Keys", getString(R.string.openhouse_deepseek_url)), true));
        panel.addView(button("保存并替换配置", v -> showDeepSeekReplaceDialog()));
        contentView.addView(panel);

        addManualSection("替换后怎么生效",
            "替换 Key 后，需要重启 Claude 或重新进入 Ubuntu 终端，正在运行的 AI 会话不会立即切换 Key。回到终端后，点击底部 exit，直到最低行看不到 root；然后点击 Ubuntu，就会在当前终端重新进入 Ubuntu 并加载新配置。也可以直接关闭软件后重新进入。");
        addManualSection("为什么需要 DeepSeek Key",
            "AI 运行需要模型 API。DeepSeek 比较实惠，适合作为首次安装的统一配置入口。本软件不限制你接入哪一个 API，长期使用的 API 可以后续让 AI 自行配置。");
        addManualSection("DeepSeek Key 怎么拿",
            "打开 DeepSeek 平台后，可以充值 1 元或 5 元；充值完成后点击 API Keys，再点击创建 API Key，名称可以任意填，比如 op。创建后复制 Key，回到这里粘贴并保存。");
    }

    private void renderPermissionsPage() {
        LinearLayout panel = panel();
        addTitle(panel, "权限获取", 19);
        addBody(panel, "忽略电池优化会直接向系统请求。文件权限在支持的系统上会先弹出授权请求，无法直接弹出时会进入系统设置。悬浮窗和 Android 11 以上的所有文件访问权限只能进入系统授权页。");
        addStatusRow(panel, "忽略电池优化", isBatteryOptimizationExempt() ? "已开启" : "未开启");
        addStatusRow(panel, "文件/存储权限", isStoragePermissionGranted() ? "已开启" : "未开启");
        addStatusRow(panel, "悬浮窗权限", isOverlayPermissionGranted() ? "已开启" : "未开启");
        addButtonRow(panel,
            compactButton("忽略电池优化", v -> requestBatteryOptimizationExemption(), true),
            compactButton("文件权限", v -> openStoragePermissionSettings(), true));
        panel.addView(button("悬浮窗权限", v -> openOverlayPermissionSettings()));
        contentView.addView(panel);
    }

    private void renderAboutPage() {
        LinearLayout panel = panel();
        addTitle(panel, "openhouse ai", 19);
        addBody(panel, "openhouse ai 是开源项目，源码和预览页面会持续同步。");
        addStatusRow(panel, "GitHub 源码", "https://github.com/jiwuyou/openhouseai-app");
        addStatusRow(panel, "QQ 交流群", "538735275");
        addButtonRow(panel,
            compactButton("复制源码地址", v -> copyText("GitHub 源码", "https://github.com/jiwuyou/openhouseai-app"), true),
            compactButton("复制 QQ 群号", v -> copyText("QQ 交流群", "538735275"), true));
        contentView.addView(panel);
    }

    private void renderTerminalGuidePage() {
        LinearLayout panel = panel();
        addTitle(panel, "使用演示", 19);
        addBody(panel, "使用演示会在终端上教你打开终端列表、使用底部快捷键、输入 claude、打开菜单，并在最后控制 OpenCode 启动。");
        addButtonRow(panel,
            compactButton("打开使用演示", v -> openTerminal(true), true),
            compactButton("直接回到终端", v -> openTerminal(false), true));
        contentView.addView(panel);
    }

    private void renderShortcutsPage() {
        LinearLayout panel = panel();
        addTitle(panel, "底部 Termux Toolbar", 19);
        addBody(panel, "第一排和第二排保留常用终端控制键：ESC、TAB、CTRL、ALT、方向键、键盘、Termux、Ubuntu、exit、clear。");
        addBody(panel, "第三排是 AI 快捷键：claude、reasonix、codex、oc、--continue。第二页可以放完整命令，例如 claude --continue。");
        addBody(panel, "按键支持自定义和多页。你可以让 AI 修改配置，例如：把第三排改成我的常用命令，或者新增一页专门放 Claude Code 的完整指令。");
        panel.addView(button("回到终端", v -> openTerminal(false)));
        contentView.addView(panel);
    }

    private void renderRepairPage() {
        LinearLayout panel = panel();
        addTitle(panel, "维护与修复", 19);
        addBody(panel, "这里进入详细进度和维护工具。进入详细进度不会中断正在后台进行的安装过程。");
        addButtonRow(panel,
            compactButton("查看详细进度", v -> openMaintenanceCenter(), true),
            compactButton("进入安装引导", v -> openInstallGuide(), true));
        contentView.addView(panel);
    }

    private void renderLogsPage() {
        LinearLayout panel = panel();
        addTitle(panel, "日志", 19);
        addBody(panel, "阶段日志保存在维护日志目录。常用日志可从这里直接查看，完整过程请进入详细进度。");
        addButtonRow(panel,
            compactButton("启动日志", v -> openMaintenanceLog("start", "启动 OpenCode"), true),
            compactButton("重启日志", v -> openMaintenanceLog("restart", "重启 OpenCode"), true));
        panel.addView(button("查看详细进度", v -> openMaintenanceCenter()));
        contentView.addView(panel);
    }

    private void renderAdvancedPage() {
        LinearLayout panel = panel();
        addTitle(panel, "高级设置", 19);
        addStatusRow(panel, "OpenCode 默认端口", Integer.toString(OpenCodeSettings.DEFAULT_OPENCODE_PORT));
        addStatusRow(panel, "OpenCode 启动目录", OpenCodeSettings.DEFAULT_PROJECT_DIRECTORY);
        OpenHouseComponentRegistry.LoadResult registryResult = dynamicRegistryResult == null
            ? OpenHouseComponentRegistry.loadWithDiagnostics()
            : dynamicRegistryResult;
        addStatusRow(panel, "菜单注册", registryResult.toShortStatusText());
        addBody(panel, registryResult.toDiagnosticText());
        CheckBox hintToggle = checkbox("在终端显示半透明小字提示", TermuxActivity.isOpenHouseTerminalHintVisible(this));
        hintToggle.setOnCheckedChangeListener((buttonView, isChecked) -> {
            TermuxActivity.setOpenHouseTerminalHintVisible(this, isChecked);
            Toast.makeText(this, isChecked ? "终端小字提示已开启。" : "终端小字提示已关闭。", Toast.LENGTH_SHORT).show();
        });
        panel.addView(hintToggle);
        addBody(panel, "这个提示用于告诉第一次使用的用户点击菜单。关闭后，回到终端或重新打开软件时不再显示。");
        panel.addView(button("复制在线手册地址", v -> copyText("在线手册地址", getString(R.string.openhouse_url_manual))));
        contentView.addView(panel);
    }

    private void addManualSection(String title, String body) {
        LinearLayout section = panel();
        addTitle(section, title, 17);
        addBody(section, body);
        contentView.addView(section);
    }

    private void showCustomPortDialog() {
        EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        input.setHint("例如 4096 或 8766");
        input.setText(Integer.toString(openCodePort));
        input.setSelection(input.getText().length());

        AlertDialog dialog = new AlertDialog.Builder(this)
            .setTitle("自定义端口启动 OpenCode")
            .setMessage("端口仅影响本次控制页启动。启动成功后会打开浏览器，并显示可复制网址。")
            .setView(input)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton("启动", null)
            .create();

        dialog.setOnShowListener(dialogInterface -> {
            Button negativeButton = dialog.getButton(AlertDialog.BUTTON_NEGATIVE);
            Button positiveButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            if (negativeButton != null) {
                negativeButton.setTextColor(ContextCompat.getColor(this, R.color.textSecondary));
            }
            if (positiveButton != null) {
                positiveButton.setTextColor(ContextCompat.getColor(this, R.color.accent));
                positiveButton.setOnClickListener(v -> {
                    int port = parsePort(input.getText() == null ? "" : input.getText().toString());
                    if (!OpenCodeSettings.isValidPort(port)) {
                        Toast.makeText(this, "端口无效，请输入 1-65535。", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    openCodePort = port;
                    lastOpenCodeUrl = getOpenCodeUrl(port);
                    renderPage();
                    runOpenCodeAction(OpenHouseMaintainerRunner.Action.START, port);
                    dialog.dismiss();
                });
            }
        });
        dialog.show();
    }

    private void showDeepSeekReplaceDialog() {
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        int padding = dp(4);
        form.setPadding(padding, padding, padding, 0);

        EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_NORMAL);
        input.setHint(getString(R.string.deepseek_key_config_hint));
        form.addView(input);

        CheckBox openCode = checkbox("OpenCode", true);
        CheckBox claude = checkbox("Claude Code", true);
        CheckBox reasonix = checkbox("Reasonix", true);
        form.addView(openCode);
        form.addView(claude);
        form.addView(reasonix);

        AlertDialog dialog = new AlertDialog.Builder(this)
            .setTitle("保存并替换配置")
            .setMessage("默认全选。取消某项后，不会覆盖该软件当前配置。\n\n替换 Key 后，需要重启 Claude 或重新进入 Ubuntu 终端，正在运行的 AI 会话不会立即切换 Key。回到终端后，点击底部 exit，直到最低行看不到 root；然后点击 Ubuntu，就会在当前终端生效。也可以关闭软件后重新进入。")
            .setView(form)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton("保存并替换配置", null)
            .create();

        dialog.setOnShowListener(dialogInterface -> {
            Button negativeButton = dialog.getButton(AlertDialog.BUTTON_NEGATIVE);
            Button positiveButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            if (negativeButton != null) {
                negativeButton.setTextColor(ContextCompat.getColor(this, R.color.textSecondary));
            }
            if (positiveButton != null) {
                positiveButton.setTextColor(ContextCompat.getColor(this, R.color.accent));
                positiveButton.setOnClickListener(v -> {
                    String apiKey = input.getText() == null ? "" : input.getText().toString().trim();
                    if (apiKey.isEmpty()) {
                        Toast.makeText(this, R.string.deepseek_key_config_empty, Toast.LENGTH_SHORT).show();
                        return;
                    }
                    if (!openCode.isChecked() && !claude.isChecked() && !reasonix.isChecked()) {
                        Toast.makeText(this, "请至少选择一个 AI 软件。", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    replaceDeepSeekKey(apiKey, openCode.isChecked(), claude.isChecked(), reasonix.isChecked());
                    dialog.dismiss();
                });
            }
        });
        dialog.show();
    }

    private CheckBox checkbox(String text, boolean checked) {
        CheckBox box = new CheckBox(this);
        box.setText(text);
        box.setTextColor(ContextCompat.getColor(this, R.color.textPrimary));
        box.setTextSize(14);
        box.setChecked(checked);
        return box;
    }

    private void replaceDeepSeekKey(String apiKey, boolean openCode, boolean claude, boolean reasonix) {
        Toast.makeText(this, "正在保存并替换 DeepSeek Key。", Toast.LENGTH_SHORT).show();
        backgroundExecutor.execute(() -> {
            OpenHouseDeepSeekController controller = OpenHouseDeepSeekController.getInstance(this);
            OpenHouseDeepSeekController.SaveResult saveResult = controller.saveKey(apiKey);
            if (!saveResult.isSuccess()) {
                runOnUiThread(() -> Toast.makeText(this, saveResult.message, Toast.LENGTH_LONG).show());
                return;
            }

            OpenHouseMaintainerRunner.Result result = controller.configureSavedKey(openCode, claude, reasonix);
            runOnUiThread(() -> {
                Toast.makeText(this,
                    result.isSuccess() ? "DeepSeek Key 已按选择替换。" : "替换失败，请查看日志。",
                    Toast.LENGTH_LONG).show();
                renderPage();
            });
        });
    }

    private void runOpenCodeAction(OpenHouseMaintainerRunner.Action action, int port) {
        Toast.makeText(this, getString(R.string.openhouse_opencode_action_running), Toast.LENGTH_SHORT).show();
        backgroundExecutor.execute(() -> {
            OpenHouseOpenCodeController controller = OpenHouseOpenCodeController.getInstance(this);
            OpenHouseMaintainerRunner.Result result;
            if (action == OpenHouseMaintainerRunner.Action.STOP) {
                result = controller.stop(port);
            } else if (action == OpenHouseMaintainerRunner.Action.RESTART) {
                result = controller.restart(port);
            } else {
                result = controller.start(port);
            }
            runOnUiThread(() -> {
                lastOpenCodeUrl = getOpenCodeUrl(port);
                renderPage();
                if (result.isSuccess()) {
                    if (action == OpenHouseMaintainerRunner.Action.START || action == OpenHouseMaintainerRunner.Action.RESTART) {
                        openUrl(lastOpenCodeUrl);
                    }
                    Toast.makeText(this, result.action.label + "完成", Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(this, getString(R.string.openhouse_opencode_action_failed), Toast.LENGTH_LONG).show();
                }
            });
        });
    }

    private void runClaudeCodeUiAction(OpenHouseMaintainerRunner.Action action) {
        Toast.makeText(this, getString(R.string.openhouse_cloudcli_action_running), Toast.LENGTH_SHORT).show();
        backgroundExecutor.execute(() -> {
            OpenHouseClaudeCodeUiController controller = OpenHouseClaudeCodeUiController.getInstance(this);
            OpenHouseMaintainerRunner.Result result;
            if (action == OpenHouseMaintainerRunner.Action.INSTALL_CLAUDE_CODE_UI) {
                result = controller.install();
            } else if (action == OpenHouseMaintainerRunner.Action.STOP_CLAUDE_CODE_UI) {
                result = controller.stop();
            } else if (action == OpenHouseMaintainerRunner.Action.RESTART_CLAUDE_CODE_UI) {
                result = controller.restart();
            } else {
                result = controller.start();
            }
            runOnUiThread(() -> {
                setCloudCliStatus(result.isSuccess()
                    ? result.action.label + "完成：" + getCcCodexUrl()
                    : result.action.label + "失败，请查看维护日志。");
                if (result.isSuccess()
                    && (action == OpenHouseMaintainerRunner.Action.START_CLAUDE_CODE_UI
                    || action == OpenHouseMaintainerRunner.Action.RESTART_CLAUDE_CODE_UI)) {
                    reloadCloudCliWebView();
                } else if (result.isSuccess()
                    && action == OpenHouseMaintainerRunner.Action.STOP_CLAUDE_CODE_UI) {
                    showCloudCliUnavailable();
                }
                Toast.makeText(this,
                    result.isSuccess() ? result.action.label + "完成" : getString(R.string.openhouse_cloudcli_action_failed),
                    Toast.LENGTH_LONG).show();
            });
        });
    }

    private int parsePort(String value) {
        try {
            return Integer.parseInt(value.trim());
        } catch (Exception e) {
            return -1;
        }
    }

    private String getOpenCodeUrl(int port) {
        return OpenCodeSettings.getRootProjectUrl(port);
    }

    private void openMaintenanceCenter() {
        openMaintenanceCenter(new Intent(this, MaintenanceCenterActivity.class));
    }

    private void openMaintenanceCenter(Intent targetIntent) {
        if (OpenHouseAgreement.hasAcceptedCurrentVersion(this)) {
            ActivityUtils.startActivity(this,
                targetIntent == null ? new Intent(this, MaintenanceCenterActivity.class) : targetIntent);
            return;
        }

        Intent intent = new Intent(this, OpenHouseAgreementActivity.class);
        intent.putExtra(OpenHouseAgreementActivity.EXTRA_OPEN_MAINTENANCE_AFTER_ACCEPT, true);
        ActivityUtils.startActivity(this, intent);
    }

    private void openInstallGuide() {
        if (!OpenHouseAgreement.hasAcceptedCurrentVersion(this)) {
            Intent agreementIntent = new Intent(this, OpenHouseAgreementActivity.class);
            agreementIntent.putExtra(OpenHouseAgreementActivity.EXTRA_OPEN_INSTALL_GUIDE_AFTER_ACCEPT, true);
            ActivityUtils.startActivity(this, agreementIntent);
            return;
        }

        ActivityUtils.startActivity(this, new Intent(this, OpenHouseOnboardingActivity.class));
    }

    private void openSmallPhone() {
        openBuiltinComponentOrFallback(findSmallPhoneComponent(), PAGE_SMALLPHONE);
    }

    private LinearLayout createAiFriendHelpControlBlock(boolean homeBlock) {
        LinearLayout block = new LinearLayout(this);
        block.setOrientation(LinearLayout.VERTICAL);
        block.setPadding(0, dp(4), 0, dp(2));

        TextView statusView = new TextView(this);
        statusView.setTextColor(ContextCompat.getColor(this, R.color.textPrimary));
        statusView.setTextSize(14);
        statusView.setTypeface(statusView.getTypeface(), android.graphics.Typeface.BOLD);
        block.addView(statusView, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT));

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT);
        rowParams.setMargins(0, dp(8), 0, 0);

        Button openButton = compactButton("", v -> openAiFriendHelp(), true);
        row.addView(openButton, new LinearLayout.LayoutParams(0, dp(44), 1));

        Button closeButton = compactButton(
            getString(R.string.operit_ai_friend_help_action_close_background),
            v -> requestCloseBackgroundAiFriendHelp(),
            true);
        LinearLayout.LayoutParams closeParams = new LinearLayout.LayoutParams(dp(118), dp(44));
        closeParams.setMargins(dp(8), 0, 0, 0);
        row.addView(closeButton, closeParams);
        block.addView(row, rowParams);

        if (homeBlock) {
            aiFriendHelpHomeStatusView = statusView;
            aiFriendHelpHomeOpenButton = openButton;
            aiFriendHelpHomeCloseButton = closeButton;
        } else {
            aiFriendHelpNavStatusView = statusView;
            aiFriendHelpNavOpenButton = openButton;
            aiFriendHelpNavCloseButton = closeButton;
        }
        return block;
    }

    private void refreshAiFriendHelpEntryState() {
        OperitControlStateSnapshot snapshot = readAiFriendHelpState();
        AiFriendHelpUiState displayState = getAiFriendHelpDisplayState(snapshot);
        updateAiFriendHelpControlBlock(
            aiFriendHelpNavStatusView,
            aiFriendHelpNavOpenButton,
            aiFriendHelpNavCloseButton,
            displayState);
        updateAiFriendHelpControlBlock(
            aiFriendHelpHomeStatusView,
            aiFriendHelpHomeOpenButton,
            aiFriendHelpHomeCloseButton,
            displayState);
    }

    private OperitControlStateSnapshot readAiFriendHelpState() {
        try {
            return OperitControlStateStore.read(getApplicationContext());
        } catch (Exception e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to read Operit control state", e);
            return null;
        }
    }

    private AiFriendHelpUiState getAiFriendHelpDisplayState(OperitControlStateSnapshot snapshot) {
        OperitProcessState effectiveState = snapshot == null
            ? OperitProcessState.NOT_RUNNING
            : snapshot.getEffectiveState();
        if (isMissingLiveOperitProcess(snapshot, effectiveState)) {
            markAiFriendHelpStoppedAfterProcessLoss();
            effectiveState = OperitProcessState.NOT_RUNNING;
        }

        if (effectiveState == OperitProcessState.NOT_RUNNING) {
            aiFriendHelpShutdownRequestedAtMs = 0L;
            return getPendingAiFriendHelpLaunchState();
        }
        if (effectiveState == OperitProcessState.STOPPING) {
            aiFriendHelpLaunchRequestedAtMs = 0L;
            aiFriendHelpLaunchFailureNotified = false;
            return AiFriendHelpUiState.STOPPING;
        }
        if (aiFriendHelpShutdownRequestedAtMs <= 0L) {
            clearAiFriendHelpLaunchPending();
            return toAiFriendHelpUiState(effectiveState);
        }
        long elapsed = System.currentTimeMillis() - aiFriendHelpShutdownRequestedAtMs;
        if (elapsed <= OPERIT_SHUTDOWN_PENDING_UI_MS) {
            clearAiFriendHelpLaunchPending();
            return AiFriendHelpUiState.STOPPING;
        }
        aiFriendHelpShutdownRequestedAtMs = 0L;
        clearAiFriendHelpLaunchPending();
        return toAiFriendHelpUiState(effectiveState);
    }

    private AiFriendHelpUiState getPendingAiFriendHelpLaunchState() {
        if (aiFriendHelpLaunchRequestedAtMs <= 0L) {
            aiFriendHelpLaunchFailureNotified = false;
            return AiFriendHelpUiState.NOT_RUNNING;
        }

        long elapsed = System.currentTimeMillis() - aiFriendHelpLaunchRequestedAtMs;
        if (elapsed <= OPERIT_LAUNCH_PROCESS_GRACE_MS
            || (elapsed <= OPERIT_LAUNCH_PENDING_UI_MS && isOperitProcessAlive(-1))) {
            return AiFriendHelpUiState.STARTING;
        }

        aiFriendHelpLaunchRequestedAtMs = 0L;
        if (!aiFriendHelpLaunchFailureNotified) {
            aiFriendHelpLaunchFailureNotified = true;
            Toast.makeText(this, R.string.operit_ai_friend_help_launch_failed, Toast.LENGTH_LONG).show();
        }
        return AiFriendHelpUiState.NOT_RUNNING;
    }

    private AiFriendHelpUiState toAiFriendHelpUiState(OperitProcessState state) {
        if (state == OperitProcessState.FOREGROUND) {
            return AiFriendHelpUiState.FOREGROUND;
        }
        if (state == OperitProcessState.BACKGROUND) {
            return AiFriendHelpUiState.BACKGROUND;
        }
        if (state == OperitProcessState.STOPPING) {
            return AiFriendHelpUiState.STOPPING;
        }
        return AiFriendHelpUiState.NOT_RUNNING;
    }

    private void clearAiFriendHelpLaunchPending() {
        aiFriendHelpLaunchRequestedAtMs = 0L;
        aiFriendHelpLaunchFailureNotified = false;
    }

    private boolean isMissingLiveOperitProcess(
        OperitControlStateSnapshot snapshot,
        OperitProcessState effectiveState) {
        if (snapshot == null || !effectiveState.isRunningLike()) {
            return false;
        }
        if (snapshot.getPid() <= 0 && isBlank(snapshot.getProcessName())) {
            return false;
        }
        return !isOperitProcessAlive(snapshot.getPid());
    }

    private boolean isOperitProcessAlive(int expectedPid) {
        ActivityManager activityManager = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
        if (activityManager == null) {
            return false;
        }
        List<ActivityManager.RunningAppProcessInfo> processes = activityManager.getRunningAppProcesses();
        if (processes == null) {
            return false;
        }
        String operitProcessName = OperitControlProtocol.operitProcessName(getPackageName());
        for (ActivityManager.RunningAppProcessInfo process : processes) {
            if (process == null || !operitProcessName.equals(process.processName)) {
                continue;
            }
            if (expectedPid <= 0 || process.pid == expectedPid) {
                return true;
            }
        }
        return false;
    }

    private void markAiFriendHelpStoppedAfterProcessLoss() {
        try {
            OperitControlStateStore.markStopped(getApplicationContext());
        } catch (Exception e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to mark Operit stopped after process loss", e);
        }
    }

    private void updateAiFriendHelpControlBlock(
        TextView statusView,
        Button openButton,
        Button closeButton,
        AiFriendHelpUiState displayState) {
        if (statusView == null || openButton == null || closeButton == null) {
            return;
        }
        statusView.setText(getString(
            R.string.operit_ai_friend_help_state_title,
            getAiFriendHelpStateLabel(displayState)));

        boolean starting = displayState == AiFriendHelpUiState.STARTING;
        boolean stopping = displayState == AiFriendHelpUiState.STOPPING;
        boolean background = displayState == AiFriendHelpUiState.BACKGROUND;
        openButton.setText(getAiFriendHelpOpenActionLabel(displayState));
        openButton.setEnabled(!starting && !stopping);
        closeButton.setText(stopping
            ? getString(R.string.operit_ai_friend_help_action_stopping)
            : getString(R.string.operit_ai_friend_help_action_close_background));
        closeButton.setVisibility(background || stopping ? View.VISIBLE : View.GONE);
        closeButton.setEnabled(background && !stopping);
    }

    private String getAiFriendHelpStateLabel(AiFriendHelpUiState state) {
        if (state == AiFriendHelpUiState.STARTING) {
            return getString(R.string.operit_ai_friend_help_state_starting);
        }
        if (state == AiFriendHelpUiState.FOREGROUND) {
            return getString(R.string.operit_ai_friend_help_state_foreground);
        }
        if (state == AiFriendHelpUiState.BACKGROUND) {
            return getString(R.string.operit_ai_friend_help_state_background);
        }
        if (state == AiFriendHelpUiState.STOPPING) {
            return getString(R.string.operit_ai_friend_help_state_stopping);
        }
        return getString(R.string.operit_ai_friend_help_state_not_running);
    }

    private String getAiFriendHelpOpenActionLabel(AiFriendHelpUiState state) {
        if (state == AiFriendHelpUiState.NOT_RUNNING) {
            return getString(R.string.operit_ai_friend_help_action_open);
        }
        if (state == AiFriendHelpUiState.STARTING) {
            return getString(R.string.operit_ai_friend_help_action_starting);
        }
        if (state == AiFriendHelpUiState.STOPPING) {
            return getString(R.string.operit_ai_friend_help_action_stopping);
        }
        return getString(R.string.operit_ai_friend_help_action_enter);
    }

    private void requestCloseBackgroundAiFriendHelp() {
        clearAiFriendHelpLaunchPending();
        OperitControlStateSnapshot snapshot = readAiFriendHelpState();
        if (snapshot == null || !snapshot.isBackground()) {
            refreshAiFriendHelpEntryState();
            Toast.makeText(this, R.string.operit_ai_friend_help_not_background, Toast.LENGTH_SHORT).show();
            return;
        }

        aiFriendHelpShutdownRequestedAtMs = System.currentTimeMillis();
        try {
            sendBroadcast(OperitControlProtocol.createShutdownIntent(this));
            Toast.makeText(this, R.string.operit_ai_friend_help_shutdown_requested, Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            aiFriendHelpShutdownRequestedAtMs = 0L;
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to request Operit shutdown", e);
            Toast.makeText(this, R.string.operit_ai_friend_help_shutdown_failed, Toast.LENGTH_SHORT).show();
        }
        refreshAiFriendHelpEntryState();
        scheduleAiFriendHelpStateRefresh();
    }

    private void scheduleAiFriendHelpStateRefresh() {
        View anchor = aiFriendHelpNavContainer != null ? aiFriendHelpNavContainer : contentView;
        if (anchor == null) {
            return;
        }
        anchor.postDelayed(this::refreshAiFriendHelpEntryState, 750);
        anchor.postDelayed(this::refreshAiFriendHelpEntryState, 2000);
        anchor.postDelayed(this::refreshAiFriendHelpEntryState, OPERIT_SHUTDOWN_PENDING_UI_MS + 250);
        anchor.postDelayed(this::refreshAiFriendHelpEntryState, OPERIT_LAUNCH_PENDING_UI_MS + 250);
    }

    private void openAiFriendHelp() {
        if (drawerLayout != null) {
            drawerLayout.closeDrawer(GravityCompat.START);
        }
        aiFriendHelpShutdownRequestedAtMs = 0L;
        aiFriendHelpLaunchRequestedAtMs = System.currentTimeMillis();
        aiFriendHelpLaunchFailureNotified = false;
        refreshAiFriendHelpEntryState();
        Intent intent = createHostedOperitIntent();
        intent.putExtra(OPERIT_EXTRA_HOSTED_MODE, true);
        intent.putExtra(OPERIT_EXTRA_HELP_MODE, true);
        if (startHostedOperitActivity(intent)) {
            refreshAiFriendHelpEntryState();
            scheduleAiFriendHelpStateRefresh();
        } else {
            aiFriendHelpLaunchRequestedAtMs = 0L;
            aiFriendHelpLaunchFailureNotified = true;
            refreshAiFriendHelpEntryState();
        }
    }

    private boolean forwardExternalOperitIntentIfNeeded(Intent sourceIntent) {
        if (!isExternalOperitEntryIntent(sourceIntent)) {
            return false;
        }

        Intent targetIntent = createHostedOperitIntent();
        targetIntent.setAction(sourceIntent.getAction());
        if (sourceIntent.getData() != null || sourceIntent.getType() != null) {
            targetIntent.setDataAndType(sourceIntent.getData(), sourceIntent.getType());
        }
        if (sourceIntent.getExtras() != null) {
            targetIntent.putExtras(sourceIntent.getExtras());
        }
        ClipData clipData = sourceIntent.getClipData();
        if (clipData != null) {
            targetIntent.setClipData(clipData);
        }
        targetIntent.addFlags(sourceIntent.getFlags() & OPERIT_FORWARD_GRANT_FLAGS);
        targetIntent.putExtra(OPERIT_EXTRA_HOSTED_MODE, true);
        return startHostedOperitActivity(targetIntent);
    }

    private Intent createHostedOperitIntent() {
        Intent intent = new Intent();
        intent.setClassName(getPackageName(), OPERIT_MAIN_ACTIVITY_CLASS);
        intent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return intent;
    }

    private boolean startHostedOperitActivity(Intent intent) {
        SmallPhoneOperitHost.install(getApplicationContext());
        try {
            com.termux.shared.errors.Error error = ActivityUtils.startActivity(this, intent, true, false);
            if (error != null) {
                Logger.logError(LOG_TAG, "Failed to start Operit hosted entry activity: " + error.getMessage());
                Toast.makeText(this, R.string.operit_ai_friend_help_start_failed, Toast.LENGTH_LONG).show();
                return false;
            }
            return true;
        } catch (ActivityNotFoundException e) {
            Logger.logError(LOG_TAG, "Operit hosted entry activity is not available: " + e.getMessage());
            Toast.makeText(this, R.string.operit_ai_friend_help_start_failed, Toast.LENGTH_LONG).show();
            return false;
        } catch (Exception e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to start Operit hosted entry activity", e);
            Toast.makeText(this, R.string.operit_ai_friend_help_start_failed, Toast.LENGTH_LONG).show();
            return false;
        }
    }

    private boolean isExternalOperitEntryIntent(Intent intent) {
        if (intent == null) {
            return false;
        }
        String action = intent.getAction();
        return Intent.ACTION_VIEW.equals(action)
            || Intent.ACTION_SEND.equals(action)
            || Intent.ACTION_SEND_MULTIPLE.equals(action);
    }

    private void openMaintenanceLog(String stageSlug, String stageLabel) {
        Intent intent = new Intent(this, MaintenanceLogActivity.class);
        intent.putExtra(MaintenanceLogActivity.EXTRA_STAGE_SLUG, stageSlug);
        intent.putExtra(MaintenanceLogActivity.EXTRA_STAGE_LABEL, stageLabel);
        startActivity(intent);
    }

    private void openTerminal(boolean teaching) {
        Intent intent = new Intent(this, TermuxActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        if (teaching) {
            intent.putExtra(TermuxActivity.EXTRA_OPENHOUSE_TERMINAL_TUTORIAL, true);
        }
        startActivity(intent);
        finish();
    }

    private void openUrl(String url) {
        if (isBlank(url)) {
            Toast.makeText(this, "没有可打开的浏览器地址。", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            startActivity(intent);
        } catch (ActivityNotFoundException e) {
            copyText("URL", url);
            Toast.makeText(this, R.string.openhouse_browser_unavailable, Toast.LENGTH_LONG).show();
        }
    }

    private void copyText(String label, String text) {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (clipboard == null) {
            return;
        }
        clipboard.setPrimaryClip(ClipData.newPlainText(label, text));
        Toast.makeText(this, getString(R.string.openhouse_clipboard_copied, label), Toast.LENGTH_SHORT).show();
    }

    private boolean isBatteryOptimizationExempt() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true;
        PowerManager powerManager = getSystemService(PowerManager.class);
        return powerManager != null && powerManager.isIgnoringBatteryOptimizations(getPackageName());
    }

    private boolean isOverlayPermissionGranted() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true;
        return Settings.canDrawOverlays(this);
    }

    private boolean isStoragePermissionGranted() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return Environment.isExternalStorageManager();
        }
        return ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
            == PackageManager.PERMISSION_GRANTED;
    }

    private void requestBatteryOptimizationExemption() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            Toast.makeText(this, "当前系统不需要单独设置电池优化。", Toast.LENGTH_SHORT).show();
            return;
        }

        try {
            Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
            intent.setData(Uri.parse("package:" + getPackageName()));
            startActivity(intent);
        } catch (Exception primaryError) {
            try {
                startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
                Toast.makeText(this, R.string.permission_open_battery_fallback_hint, Toast.LENGTH_LONG).show();
            } catch (Exception fallbackError) {
                Logger.logStackTraceWithMessage(LOG_TAG, "Failed to open battery optimization settings", fallbackError);
                Toast.makeText(this, R.string.permission_open_battery_failed, Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void openOverlayPermissionSettings() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            Toast.makeText(this, "当前系统不需要单独设置悬浮窗权限。", Toast.LENGTH_SHORT).show();
            return;
        }

        try {
            Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName()));
            startActivity(intent);
        } catch (Exception e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to open overlay settings", e);
            Toast.makeText(this, R.string.permission_open_overlay_failed, Toast.LENGTH_SHORT).show();
        }
    }

    private void openStoragePermissionSettings() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                try {
                    Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                    intent.setData(Uri.parse("package:" + getPackageName()));
                    startActivity(intent);
                    return;
                } catch (Exception ignored) {
                    startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
                    return;
                }
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                && ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE},
                    6104);
                return;
            }

            Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            intent.setData(Uri.parse("package:" + getPackageName()));
            startActivity(intent);
        } catch (Exception e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to open storage settings", e);
            Toast.makeText(this, R.string.permission_open_storage_failed, Toast.LENGTH_SHORT).show();
        }
    }

    private LinearLayout panel() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackgroundResource(R.drawable.panel_bg);
        panel.setPadding(dp(16), dp(14), dp(16), dp(14));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT);
        params.setMargins(0, 0, 0, dp(14));
        panel.setLayoutParams(params);
        return panel;
    }

    private void addTitle(LinearLayout parent, String text, int sp) {
        TextView title = new TextView(this);
        title.setText(text);
        title.setTextColor(ContextCompat.getColor(this, R.color.textPrimary));
        title.setTextSize(sp);
        title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
        parent.addView(title);
    }

    private void addBody(LinearLayout parent, String text) {
        TextView body = new TextView(this);
        body.setText(text);
        body.setTextColor(ContextCompat.getColor(this, R.color.textSecondary));
        body.setTextSize(14);
        body.setLineSpacing(dp(2), 1.0f);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT);
        params.setMargins(0, dp(8), 0, 0);
        parent.addView(body, params);
    }

    private void addStatusRow(LinearLayout parent, String label, String value) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT);
        rowParams.setMargins(0, dp(10), 0, 0);

        TextView labelView = new TextView(this);
        labelView.setText(label);
        labelView.setTextColor(ContextCompat.getColor(this, R.color.textSecondary));
        labelView.setTextSize(13);
        row.addView(labelView, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));

        TextView valueView = new TextView(this);
        valueView.setText(value);
        valueView.setTextColor(ContextCompat.getColor(this, R.color.textPrimary));
        valueView.setTextSize(13);
        valueView.setGravity(Gravity.END);
        row.addView(valueView, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        parent.addView(row, rowParams);
    }

    private Button button(String text, View.OnClickListener listener) {
        Button button = new Button(this);
        button.setText(text);
        button.setAllCaps(false);
        button.setTextSize(14);
        button.setOnClickListener(listener);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(52));
        params.setMargins(0, dp(10), 0, 0);
        button.setLayoutParams(params);
        return button;
    }

    private Button compactButton(String text, View.OnClickListener listener, boolean enabled) {
        Button button = new Button(this);
        button.setText(text);
        button.setAllCaps(false);
        button.setEnabled(enabled);
        button.setMinHeight(0);
        button.setMinWidth(0);
        button.setTextSize(13);
        button.setOnClickListener(listener);
        return button;
    }

    private void addButtonRow(LinearLayout parent, Button first, Button second) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT);
        rowParams.setMargins(0, dp(8), 0, 0);

        LinearLayout.LayoutParams firstParams = new LinearLayout.LayoutParams(0, dp(44), 1);
        row.addView(first, firstParams);

        LinearLayout.LayoutParams secondParams = new LinearLayout.LayoutParams(0, dp(44), 1);
        secondParams.setMargins(dp(8), 0, 0, 0);
        row.addView(second, secondParams);
        parent.addView(row, rowParams);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
