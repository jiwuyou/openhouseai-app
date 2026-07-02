package com.termux.app.activities;

import android.Manifest;
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
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
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
import com.termux.app.OpenHouseAgreement;
import com.termux.app.TermuxActivity;
import com.termux.app.browser.ControlledBrowserCommandDispatcher;
import com.termux.app.browser.ControlledBrowserContract;
import com.termux.app.browser.ControlledBrowserRpcFiles;
import com.termux.app.browser.ControlledBrowserRuntime;
import com.termux.app.browser.ControlledBrowserView;
import com.termux.app.openhouse.OpenHouseClaudeCodeUiController;
import com.termux.app.openhouse.OpenHouseMaintainerRunner;
import com.termux.app.openhouse.components.OpenHouseComponent;
import com.termux.app.openhouse.components.OpenHouseComponentRegistry;
import com.termux.app.openhouse.tutorial.GuidedTutorialOverlay;
import com.termux.app.smallphone.SmallPhoneFirstLaunchGate;
import com.termux.app.smallphone.SmallPhoneHostController;
import com.termux.shared.activity.ActivityUtils;
import com.termux.shared.logger.Logger;
import com.termux.shared.termux.TermuxConstants;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
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
    private static final String PAGE_PI_WEB = "pi-web";
    private static final String PAGE_AI = "ai";
    private static final String PAGE_SMALLPHONE = "smallphone";
    private static final String PAGE_CONTROLLED_BROWSER = ControlledBrowserContract.PAGE_CONTROLLED_BROWSER;
    private static final String PAGE_MANUAL = "manual";
    private static final String PAGE_PERMISSIONS = "permissions";
    private static final String PAGE_ABOUT = "about";
    private static final String PAGE_TERMINAL_GUIDE = "terminal_guide";
    private static final String PAGE_SHORTCUTS = "shortcuts";
    private static final String PAGE_REPAIR = "repair";
    private static final String PAGE_LOGS = "logs";
    private static final String PAGE_ADVANCED = "advanced";
    private static final String PAGE_USAGE_TUTORIAL = "usage_tutorial";
    private static final String PAGE_COMPONENT_PREFIX = "component:";
    private static final String EXTRA_SERVICE_CONTROL_COMPONENT_ID = "openhouse_component_id";
    private static final String EXTRA_SERVICE_CONTROL_TITLE = "openhouse_component_title";
    private static final String EXTRA_SERVICE_CONTROL_URL = "openhouse_component_url";
    private static final String EXTRA_SERVICE_CONTROL_SERVICE_NAMES = "openhouse_service_names";
    private static final String EXTRA_SERVICE_CONTROL_SERVICE_REFS = "openhouse_service_refs";
    private static final String EXTRA_SERVICE_CONTROL_MODE = "openhouse_service_control_mode";
    private static final String SERVICE_CONTROL_MODE_COMPONENT = "component";
    private static final String SERVICE_CONTROL_MODE_ALL = "all";
    public static final String EXTRA_OPENHOUSE_TUTORIAL = "openhouse_tutorial";
    public static final String TUTORIAL_OPENHOUSE_USAGE = "openhouse_usage";
    public static final String TUTORIAL_START_CORE_SERVICES = "start_core_services";
    public static final String TUTORIAL_CC_CODEX_CONTROL = "cc_codex_control";
    private static final String PREF_USAGE_TUTORIAL_STAGE = "usage_tutorial_stage";
    private static final String USAGE_STAGE_AFTER_CONTROL = "after_control";
    private static final String USAGE_STAGE_START_CORE = TUTORIAL_START_CORE_SERVICES;
    private static final String CC_CODEX_SERVICE_NAME = "cloudcli";
    private static final String SMALLPHONE_HOME_TARGET = "messages";
    private static final String MENU_OVERRIDES_RELATIVE_PATH = ".config/openhouseai/menu-overrides.json";
    private static final String CC_CODEX_TITLE = "cc/codex";
    private static final String PI_WEB_TITLE = "pi-agent";
    private static final String PI_WEB_DEFAULT_URL = "http://127.0.0.1:30141/";
    private static final String HOME_USAGE_TUTORIAL_TAG = "openhouse_home_usage_tutorial";
    private static final String HOME_CORE_SERVICES_BUTTON_TAG = "openhouse_core_services_start";

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
    private String currentPage = PAGE_HOME;
    private List<OpenHouseComponent> dynamicComponents = Collections.emptyList();
    private OpenHouseComponentRegistry.LoadResult dynamicRegistryResult;
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
    private LinearLayout piWebPageView;
    private WebView piWebView;
    private LinearLayout piWebFallbackView;
    private TextView piWebStatusView;
    private boolean piWebLoadFailed = false;
    private ControlledBrowserView controlledBrowserView;
    private LinearLayout dynamicWebPageView;
    private WebView dynamicWebView;
    private LinearLayout dynamicWebFallbackView;
    private TextView dynamicWebStatusView;
    private OpenHouseComponent dynamicWebComponent;
    private boolean dynamicWebLoadFailed = false;
    private boolean firstLaunchGateForwarded;
    private String renderedCloudCliUrl;
    private String renderedPiWebUrl;
    private GuidedTutorialOverlay usageTutorialOverlay;
    private boolean usageCoreServicesMode = false;
    private boolean usageCoreServicesFailed = false;
    private TextView usageCoreServicesProgressView;
    private Button usageCoreServicesStartButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
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
        destroyUsageTutorialOverlay();
        if (smallPhoneController != null) {
            smallPhoneController.onDestroy();
            smallPhoneController = null;
        }
        if (cloudCliWebView != null) {
            cloudCliWebView.destroy();
            cloudCliWebView = null;
        }
        if (piWebView != null) {
            piWebView.destroy();
            piWebView = null;
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
        if (!hasExplicitOpenHouseTarget(getIntent()) && routeFirstLaunchGateIfNeeded()) {
            return;
        }
        if (PAGE_PERMISSIONS.equals(currentPage)) {
            renderPage();
        }
        if (PAGE_SMALLPHONE.equals(currentPage)
            && isCurrentDynamicWebComponent(findSmallPhoneComponent())
            && dynamicWebView != null) {
            dynamicWebView.onResume();
        } else if (PAGE_SMALLPHONE.equals(currentPage) && smallPhoneController != null) {
            smallPhoneController.onResume(false);
        } else if (PAGE_PI_WEB.equals(currentPage) && piWebView != null) {
            piWebView.onResume();
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
        scheduleResumePendingUsageTutorial();
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
        if (handleOpenHouseIntent(intent)) {
            scheduleResumePendingUsageTutorial();
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
        if (PAGE_PI_WEB.equals(currentPage)
            && piWebView != null
            && piWebView.canGoBack()) {
            piWebView.goBack();
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
        findViewById(R.id.buttonNavAi).setOnClickListener(
            v -> openBuiltinComponentOrFallback(findCcCodexComponent(), PAGE_AI));
        findViewById(R.id.buttonNavAiControl).setOnClickListener(v -> openCcCodexControlOrToggle());
        findViewById(R.id.buttonNavSmallPhone).setOnClickListener(
            v -> openBuiltinComponentOrFallback(findSmallPhoneComponent(), PAGE_SMALLPHONE));
        findViewById(R.id.buttonNavSmallPhoneControl).setOnClickListener(v -> openComponentControl(findSmallPhoneComponent()));
        findViewById(R.id.buttonNavPiAgent).setOnClickListener(v -> openPiAgent());
        findViewById(R.id.buttonNavPiAgentControl).setOnClickListener(v -> openPiWebControlOrAll());
        findViewById(R.id.buttonNavControlledBrowser).setOnClickListener(
            v -> openBuiltinComponentOrFallback(findControlledBrowserComponent(), PAGE_CONTROLLED_BROWSER));
        findViewById(R.id.buttonNavControlledBrowserControl).setOnClickListener(v -> openComponentControl(findControlledBrowserComponent()));
        findViewById(R.id.buttonNavServiceControl).setOnClickListener(v -> openAllServiceControl());
        findViewById(R.id.buttonNavManual).setOnClickListener(v -> selectPage(PAGE_MANUAL));
        findViewById(R.id.buttonNavUsageTutorial).setOnClickListener(v -> startUsageTeachingFlow());
        findViewById(R.id.buttonNavPermissions).setOnClickListener(v -> selectPage(PAGE_PERMISSIONS));
        findViewById(R.id.buttonNavAbout).setOnClickListener(v -> selectPage(PAGE_ABOUT));
        findViewById(R.id.buttonNavTerminalGuide).setOnClickListener(v -> selectPage(PAGE_TERMINAL_GUIDE));
        findViewById(R.id.buttonNavShortcuts).setOnClickListener(v -> selectPage(PAGE_SHORTCUTS));
        findViewById(R.id.buttonNavRepair).setOnClickListener(v -> selectPage(PAGE_REPAIR));
        findViewById(R.id.buttonNavLogs).setOnClickListener(v -> selectPage(PAGE_LOGS));
        findViewById(R.id.buttonNavAdvanced).setOnClickListener(v -> selectPage(PAGE_ADVANCED));
        findViewById(R.id.buttonNavTerminal).setOnClickListener(v -> openTerminal(false));
        if (setCurrentHomeButton != null) {
            setCurrentHomeButton.setOnClickListener(v -> setCurrentPageAsHome());
        }
        updateHomePreferenceViews();
    }

    private void refreshDynamicComponents() {
        dynamicRegistryResult = OpenHouseComponentRegistry.loadWithDiagnostics();
        dynamicComponents = dynamicRegistryResult.components;
        setFallbackNavigationVisible(dynamicRegistryResult.shouldShowFallbackNavigation());
        updateBuiltinNavigationLabels();
        renderDynamicQuickNavigation();
        renderDynamicNavigation();
        updateHomePreferenceViews();
        updateTopActionState();
    }

    private void updateBuiltinNavigationLabels() {
        setBuiltinNavigationRowState(R.id.rowNavAi, R.id.buttonNavAi, R.id.buttonNavAiControl,
            findCcCodexComponent(), getCcCodexTitle());
        setBuiltinNavigationRowState(R.id.rowNavSmallPhone, R.id.buttonNavSmallPhone, R.id.buttonNavSmallPhoneControl,
            findSmallPhoneComponent(), getSmallPhoneTitle());
        updatePiAgentNavigationRowState();
        setBuiltinNavigationRowState(R.id.rowNavControlledBrowser, R.id.buttonNavControlledBrowser, R.id.buttonNavControlledBrowserControl,
            findControlledBrowserComponent(), getControlledBrowserTitle());
    }

    private void updatePiAgentNavigationRowState() {
        View row = findViewById(R.id.rowNavPiAgent);
        if (row == null) {
            return;
        }
        OpenHouseComponent component = findPiWebComponent();
        boolean visible = isComponentVisible(component);
        row.setVisibility(visible ? View.VISIBLE : View.GONE);
        View openButton = findViewById(R.id.buttonNavPiAgent);
        if (openButton instanceof Button) {
            ((Button) openButton).setText(getPiWebTitle());
        }
        View controlButton = findViewById(R.id.buttonNavPiAgentControl);
        if (controlButton != null) {
            controlButton.setVisibility(visible ? View.VISIBLE : View.GONE);
        }
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
            R.id.rowNavAi,
            R.id.rowNavPiAgent,
            R.id.rowNavSmallPhone,
            R.id.rowNavControlledBrowser,
            R.id.buttonNavHome,
            R.id.buttonNavServiceControl,
            R.id.buttonNavManual,
            R.id.buttonNavUsageTutorial,
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
            case PAGE_PI_WEB:
                setHeader(getPiWebTitle(), getPiWebSubtitle("默认 agent 和插件入口"));
                renderPiWebPage();
                break;
            case PAGE_AI:
                setHeader(getCcCodexTitle(), getCcCodexSubtitle("Claude Code / Codex 统一入口"));
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
            case PAGE_USAGE_TUTORIAL:
                showScrollContent();
                setHeader("使用教学", usageCoreServicesMode ? "启动核心服务" : "按箭头认识 OpenHouse");
                renderUsageTutorialPage();
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
                setHeader("日志", "安装、启动和维护日志");
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
        if (PAGE_PI_WEB.equals(currentPage)) {
            openPiWebControlOrAll();
            return;
        }
        openAllServiceControl();
    }

    private void refreshCurrentTarget() {
        if (PAGE_PI_WEB.equals(currentPage)) {
            reloadPiWebView();
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
        if (PAGE_PI_WEB.equals(currentPage)) {
            return getPiWebUrl();
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
        if (PAGE_PI_WEB.equals(currentPage)) {
            return findPiWebComponent();
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

    private void renderPiWebPage() {
        showEmbeddedContent();
        if (embeddedContentView == null) {
            return;
        }
        String piWebUrl = getPiWebUrl();
        if (piWebPageView == null || !piWebUrl.equals(renderedPiWebUrl)) {
            if (piWebView != null) {
                piWebView.destroy();
                piWebView = null;
            }
            piWebPageView = createPiWebPageView();
            renderedPiWebUrl = piWebUrl;
        }
        attachEmbeddedView(piWebPageView);
        if (piWebView != null) {
            piWebView.onResume();
            if (piWebView.getUrl() == null) {
                reloadPiWebView();
            }
        }
    }

    private LinearLayout createPiWebPageView() {
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setBackgroundColor(ContextCompat.getColor(this, R.color.surface));

        piWebStatusView = new TextView(this);
        piWebStatusView.setText(getPiWebTitle() + " 地址：" + getPiWebUrl());
        piWebStatusView.setTextColor(ContextCompat.getColor(this, R.color.textSecondary));
        piWebStatusView.setTextSize(12);
        piWebStatusView.setPadding(dp(12), dp(6), dp(12), dp(6));
        piWebStatusView.setBackgroundColor(ContextCompat.getColor(this, R.color.panel));
        page.addView(piWebStatusView, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT));

        FrameLayout browserHost = new FrameLayout(this);
        piWebView = new WebView(this);
        configurePiWebView(piWebView);
        browserHost.addView(piWebView, new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT));

        piWebFallbackView = createPiWebFallbackView();
        piWebFallbackView.setVisibility(View.GONE);
        browserHost.addView(piWebFallbackView, new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT));
        page.addView(browserHost, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            0,
            1));
        return page;
    }

    private LinearLayout createPiWebFallbackView() {
        LinearLayout fallback = new LinearLayout(this);
        fallback.setOrientation(LinearLayout.VERTICAL);
        fallback.setGravity(Gravity.CENTER);
        fallback.setPadding(dp(22), dp(22), dp(22), dp(22));
        fallback.setBackgroundColor(ContextCompat.getColor(this, R.color.surface));

        TextView title = new TextView(this);
        title.setText(getPiWebTitle() + " 未连接");
        title.setTextColor(ContextCompat.getColor(this, R.color.textPrimary));
        title.setTextSize(20);
        title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
        fallback.addView(title);

        TextView body = new TextView(this);
        body.setText("没有连接到 " + getPiWebUrl() + "。可以先进入运行控制启动或修复 pi-web，然后回到本页刷新。");
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
            compactButton("运行控制", v -> openPiWebControlOrAll(), true),
            compactButton("刷新", v -> reloadPiWebView(), true));
        fallback.addView(button("复制地址", v -> copyText(getPiWebTitle(), getPiWebUrl())));
        return fallback;
    }

    private void configurePiWebView(WebView webView) {
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
                piWebLoadFailed = false;
                setPiWebFallbackVisible(false);
                setPiWebStatus("正在连接 " + getPiWebTitle() + "：" + getPiWebUrl());
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                if (!piWebLoadFailed) {
                    setPiWebFallbackVisible(false);
                    setPiWebStatus(getPiWebTitle() + " 已连接：" + url);
                }
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && request != null && request.isForMainFrame()) {
                    showPiWebUnavailable();
                }
            }

            @SuppressWarnings("deprecation")
            @Override
            public void onReceivedError(WebView view, int errorCode, String description, String failingUrl) {
                showPiWebUnavailable();
            }
        });
    }

    private void openPiWebControlOrAll() {
        OpenHouseComponent component = findPiWebComponent();
        if (component != null && component.hasControlEntry()) {
            openComponentControl(component);
            return;
        }
        openAllServiceControl();
    }

    private void reloadPiWebView() {
        piWebLoadFailed = false;
        setPiWebFallbackVisible(false);
        String piWebUrl = getPiWebUrl();
        setPiWebStatus("正在刷新 " + getPiWebTitle() + "：" + piWebUrl);
        if (piWebView != null) {
            piWebView.loadUrl(piWebUrl);
        }
    }

    private void showPiWebUnavailable() {
        piWebLoadFailed = true;
        setPiWebStatus(getPiWebTitle() + " 未连接：" + getPiWebUrl());
        setPiWebFallbackVisible(true);
    }

    private void setPiWebFallbackVisible(boolean visible) {
        if (piWebFallbackView != null) {
            piWebFallbackView.setVisibility(visible ? View.VISIBLE : View.GONE);
        }
    }

    private void setPiWebStatus(String text) {
        if (piWebStatusView != null) {
            piWebStatusView.setText(text);
        }
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
        if (PAGE_SMALLPHONE.equals(currentPage)
            && isCurrentDynamicWebComponent(findSmallPhoneComponent())
            && dynamicWebView != null) {
            dynamicWebView.onPause();
        } else if (PAGE_SMALLPHONE.equals(currentPage) && smallPhoneController != null) {
            smallPhoneController.onPause();
        } else if (PAGE_PI_WEB.equals(currentPage) && piWebView != null) {
            piWebView.onPause();
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
        openComponentControl(component, false);
    }

    private void openComponentControl(OpenHouseComponent component, boolean ccCodexTeaching) {
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
        if (ccCodexTeaching) {
            intent.putExtra(OpenHouseServiceControlActivity.EXTRA_SERVICE_CONTROL_TUTORIAL,
                OpenHouseServiceControlActivity.TUTORIAL_CC_CODEX_CONTROL);
        }
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

    private boolean isUsageTutorialIntent(Intent intent) {
        if (intent == null) {
            return false;
        }
        String tutorial = intent.getStringExtra(EXTRA_OPENHOUSE_TUTORIAL);
        return TUTORIAL_OPENHOUSE_USAGE.equals(tutorial)
            || TUTORIAL_START_CORE_SERVICES.equals(tutorial)
            || TUTORIAL_CC_CODEX_CONTROL.equals(tutorial);
    }

    private boolean isStartCoreServicesTutorialIntent(Intent intent) {
        return intent != null
            && TUTORIAL_START_CORE_SERVICES.equals(intent.getStringExtra(EXTRA_OPENHOUSE_TUTORIAL));
    }

    private void startUsageTeachingFlow() {
        clearPendingUsageTutorialStage();
        usageCoreServicesMode = false;
        destroyUsageTutorialOverlay();
        selectPage(PAGE_HOME);
        if (drawerLayout != null) {
            drawerLayout.post(() -> {
                drawerLayout.openDrawer(GravityCompat.START);
                startMenuUsageTutorialOverlay();
            });
        } else {
            startMenuUsageTutorialOverlay();
        }
    }

    private void startMenuUsageTutorialOverlay() {
        if (isFinishing()) {
            return;
        }
        if (drawerLayout != null && !drawerLayout.isDrawerOpen(GravityCompat.START)) {
            drawerLayout.openDrawer(GravityCompat.START);
        }

        View root = findViewById(android.R.id.content);
        if (!(root instanceof ViewGroup)) {
            return;
        }

        List<GuidedTutorialOverlay.Step> steps = new ArrayList<>();
        steps.add(GuidedTutorialOverlay.Step
            .explanation(
                "先认识菜单里的大服务",
                "这里是 OpenHouse 的主要入口。SmallPhone、pi-agent、cc/codex 在同一个大服务区，不是彼此的二级页面。")
            .build());
        steps.add(GuidedTutorialOverlay.Step
            .requiredClick(
                "这是 SmallPhone",
                "请点一下箭头指向的位置。本步只用于认识入口，不会切换页面。",
                GuidedTutorialOverlay.targetById(root, R.id.buttonNavSmallPhone))
            .onTargetClick((overlay, step) -> true)
            .build());
        steps.add(GuidedTutorialOverlay.Step
            .requiredClick(
                "这是 pi-agent",
                "请点一下箭头指向的位置。pi-agent 是默认 agent 和插件入口，稍后会真正进入它。",
                GuidedTutorialOverlay.targetById(root, R.id.buttonNavPiAgent))
            .onTargetClick((overlay, step) -> true)
            .build());
        steps.add(GuidedTutorialOverlay.Step
            .requiredClick(
                "点击 cc/codex",
                "请点击 cc/codex。点击服务本身会切换到对应页面。",
                GuidedTutorialOverlay.targetById(root, R.id.buttonNavAi))
            .onTargetClick((overlay, step) -> {
                openBuiltinComponentOrFallback(findCcCodexComponent(), PAGE_AI);
                return true;
            })
            .build());
        steps.add(GuidedTutorialOverlay.Step
            .explanation(
                "cc/codex 是统一入口",
                "这里承接 Claude Code / Codex 的网页使用入口。服务启动、关闭和修复不在这个页面直接做，而是在控制页里做。")
            .build());
        steps.add(GuidedTutorialOverlay.Step
            .requiredClick(
                "回到菜单",
                "请点击菜单，回到侧边栏继续看 cc/codex 的控制入口。",
                GuidedTutorialOverlay.targetById(root, R.id.buttonOpenDrawer))
            .onTargetClick((overlay, step) -> {
                if (drawerLayout != null) {
                    drawerLayout.openDrawer(GravityCompat.START);
                }
                overlay.refreshTarget();
                return true;
            })
            .build());
        steps.add(GuidedTutorialOverlay.Step
            .requiredClick(
                "进入 cc/codex 控制",
                "请点击 cc/codex 右侧的控制。下一页会真实控制服务，运行中就关闭，未运行就启动，完成后记得刷新。",
                GuidedTutorialOverlay.targetById(root, R.id.buttonNavAiControl))
            .onTargetClick((overlay, step) -> {
                savePendingUsageTutorialStage(USAGE_STAGE_AFTER_CONTROL);
                overlay.destroy();
                openCcCodexControlForTeaching();
                return true;
            })
            .advanceAfterTargetClick(false)
            .build());

        usageTutorialOverlay = new GuidedTutorialOverlay(
            this,
            (ViewGroup) root,
            steps,
            new GuidedTutorialOverlay.SimpleListener() {
                @Override
                public void onFinished(GuidedTutorialOverlay overlay) {
                    usageTutorialOverlay = null;
                }

                @Override
                public void onSkipped(GuidedTutorialOverlay overlay, GuidedTutorialOverlay.Step step) {
                    usageTutorialOverlay = null;
                }
            });
        usageTutorialOverlay.start();
    }

    private void openCcCodexControlForTeaching() {
        OpenHouseComponent component = findCcCodexComponent();
        if (component != null && component.hasControlEntry()) {
            openComponentControl(component, true);
            return;
        }
        Intent intent = new Intent(this, OpenHouseServiceControlActivity.class);
        intent.putExtra(EXTRA_SERVICE_CONTROL_MODE, SERVICE_CONTROL_MODE_COMPONENT);
        intent.putExtra(EXTRA_SERVICE_CONTROL_COMPONENT_ID, "cc-codex");
        intent.putExtra(EXTRA_SERVICE_CONTROL_TITLE, getCcCodexTitle());
        intent.putExtra(EXTRA_SERVICE_CONTROL_URL, getCcCodexUrl());
        intent.putExtra(EXTRA_SERVICE_CONTROL_SERVICE_NAMES, CC_CODEX_SERVICE_NAME);
        intent.putExtra(OpenHouseServiceControlActivity.EXTRA_SERVICE_CONTROL_TUTORIAL,
            OpenHouseServiceControlActivity.TUTORIAL_CC_CODEX_CONTROL);
        if (drawerLayout != null) {
            drawerLayout.closeDrawer(GravityCompat.START);
        }
        ActivityUtils.startActivity(this, intent);
    }

    private void scheduleResumePendingUsageTutorial() {
        if (drawerLayout == null) {
            resumePendingUsageTutorialIfNeeded();
            return;
        }
        drawerLayout.postDelayed(this::resumePendingUsageTutorialIfNeeded, 180);
    }

    private boolean resumePendingUsageTutorialIfNeeded() {
        if (usageTutorialOverlay != null && usageTutorialOverlay.isShowing()) {
            return false;
        }
        String stage = getPendingUsageTutorialStage();
        if (USAGE_STAGE_AFTER_CONTROL.equals(stage)) {
            clearPendingUsageTutorialStage();
            startAfterControlUsageTutorial();
            return true;
        }
        if (USAGE_STAGE_START_CORE.equals(stage)) {
            startCoreServicesTeachingStage();
            return true;
        }
        return false;
    }

    private void startAfterControlUsageTutorial() {
        usageCoreServicesMode = false;
        destroyUsageTutorialOverlay();
        selectPage(PAGE_HOME);
        if (drawerLayout != null) {
            drawerLayout.post(() -> {
                drawerLayout.openDrawer(GravityCompat.START);
                startAfterControlUsageTutorialOverlay();
            });
        } else {
            startAfterControlUsageTutorialOverlay();
        }
    }

    private void startAfterControlUsageTutorialOverlay() {
        if (isFinishing()) {
            return;
        }
        if (drawerLayout != null && !drawerLayout.isDrawerOpen(GravityCompat.START)) {
            drawerLayout.openDrawer(GravityCompat.START);
        }
        View root = findViewById(android.R.id.content);
        if (!(root instanceof ViewGroup)) {
            return;
        }

        List<GuidedTutorialOverlay.Step> steps = new ArrayList<>();
        steps.add(GuidedTutorialOverlay.Step
            .requiredClick(
                "回到终端",
                "请点击回到终端。终端一般只是备用入口，后续有需要可以单独看终端详细教学。",
                GuidedTutorialOverlay.targetById(root, R.id.buttonNavTerminal))
            .onTargetClick((overlay, step) -> {
                savePendingUsageTutorialStage(USAGE_STAGE_START_CORE);
                overlay.destroy();
                openTerminal(true);
                return true;
            })
            .advanceAfterTargetClick(false)
            .build());

        usageTutorialOverlay = new GuidedTutorialOverlay(
            this,
            (ViewGroup) root,
            steps,
            new GuidedTutorialOverlay.SimpleListener() {
                @Override
                public void onFinished(GuidedTutorialOverlay overlay) {
                    usageTutorialOverlay = null;
                }

                @Override
                public void onSkipped(GuidedTutorialOverlay overlay, GuidedTutorialOverlay.Step step) {
                    usageTutorialOverlay = null;
                }
            });
        usageTutorialOverlay.start();
    }

    private void startCoreServicesTeachingStage() {
        clearPendingUsageTutorialStage();
        destroyUsageTutorialOverlay();
        usageCoreServicesMode = true;
        usageCoreServicesFailed = false;
        selectPage(PAGE_USAGE_TUTORIAL);
        View root = findViewById(android.R.id.content);
        if (!(root instanceof ViewGroup)) {
            return;
        }
        List<GuidedTutorialOverlay.Step> steps = new ArrayList<>();
        steps.add(GuidedTutorialOverlay.Step
            .sideEffectClick(
                "启动核心服务",
                "终端教学结束后，需要把内置核心服务拉起来。请点击启动核心服务，这一步会真实启动后台服务。",
                GuidedTutorialOverlay.targetByTag(root, HOME_CORE_SERVICES_BUTTON_TAG))
            .onTargetClick((overlay, step) -> {
                overlay.destroy();
                startCoreServicesFromTutorial();
                return true;
            })
            .build());

        usageTutorialOverlay = new GuidedTutorialOverlay(
            this,
            (ViewGroup) root,
            steps,
            new GuidedTutorialOverlay.SimpleListener() {
                @Override
                public void onFinished(GuidedTutorialOverlay overlay) {
                    usageTutorialOverlay = null;
                }

                @Override
                public void onSkipped(GuidedTutorialOverlay overlay, GuidedTutorialOverlay.Step step) {
                    usageTutorialOverlay = null;
                }
            });
        usageTutorialOverlay.start();
    }

    private void startAfterCoreServicesTutorial() {
        usageCoreServicesMode = false;
        usageCoreServicesFailed = false;
        selectPage(PAGE_HOME);
        if (drawerLayout != null) {
            drawerLayout.post(() -> {
                drawerLayout.openDrawer(GravityCompat.START);
                startPiAgentFinalTutorialOverlay();
            });
        } else {
            startPiAgentFinalTutorialOverlay();
        }
    }

    private void startPiAgentFinalTutorialOverlay() {
        if (isFinishing()) {
            return;
        }
        if (drawerLayout != null && !drawerLayout.isDrawerOpen(GravityCompat.START)) {
            drawerLayout.openDrawer(GravityCompat.START);
        }
        View root = findViewById(android.R.id.content);
        if (!(root instanceof ViewGroup)) {
            return;
        }

        List<GuidedTutorialOverlay.Step> steps = new ArrayList<>();
        steps.add(GuidedTutorialOverlay.Step
            .requiredClick(
                "进入 pi-agent",
                "核心服务已启动。请点击 pi-agent，接下来在 pi-agent 里按页面提示配置模型和 Claude Code。",
                GuidedTutorialOverlay.targetById(root, R.id.buttonNavPiAgent))
            .onTargetClick((overlay, step) -> {
                openPiAgent();
                return true;
            })
            .build());
        steps.add(GuidedTutorialOverlay.Step
            .explanation(
                "在 pi-agent 里继续",
                "进入后请按 pi-agent 页面提示操作：点侧边栏三横线，项目选 /root，配置模型，新建会话，选择“配置 Claude Code”。第一次消息把 URL、key/token 和模型 id 发给 AI。")
            .build());
        steps.add(GuidedTutorialOverlay.Step
            .explanation(
                "配置完成后的检查",
                "测通目标是 CloudCLI 中的 Claude Code。配置好后，可以从菜单进入 cc/codex；默认账号密码是 admin / 123456，仅限本机使用，后续可修改密码。")
            .build());

        usageTutorialOverlay = new GuidedTutorialOverlay(
            this,
            (ViewGroup) root,
            steps,
            new GuidedTutorialOverlay.SimpleListener() {
                @Override
                public void onFinished(GuidedTutorialOverlay overlay) {
                    usageTutorialOverlay = null;
                }

                @Override
                public void onSkipped(GuidedTutorialOverlay overlay, GuidedTutorialOverlay.Step step) {
                    usageTutorialOverlay = null;
                }
            });
        usageTutorialOverlay.start();
    }

    private void destroyUsageTutorialOverlay() {
        if (usageTutorialOverlay != null) {
            usageTutorialOverlay.destroy();
            usageTutorialOverlay = null;
        }
    }

    private void savePendingUsageTutorialStage(String stage) {
        if (isBlank(stage)) {
            clearPendingUsageTutorialStage();
            return;
        }
        getOpenHouseHomePrefs().edit().putString(PREF_USAGE_TUTORIAL_STAGE, stage).apply();
    }

    private String getPendingUsageTutorialStage() {
        return getOpenHouseHomePrefs().getString(PREF_USAGE_TUTORIAL_STAGE, "");
    }

    private void clearPendingUsageTutorialStage() {
        getOpenHouseHomePrefs().edit().remove(PREF_USAGE_TUTORIAL_STAGE).apply();
    }

    private String resolveNativePage(String page) {
        if (isBlank(page)) {
            return null;
        }
        String normalized = page.trim().toLowerCase(java.util.Locale.US).replace('_', '-');
        switch (normalized) {
            case PAGE_HOME:
                return PAGE_HOME;
            case PAGE_PI_WEB:
            case "pi-agent":
            case "pi":
            case "piweb":
                return PAGE_PI_WEB;
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
            case PAGE_USAGE_TUTORIAL:
            case "usage-tutorial":
            case "tutorial":
                return PAGE_USAGE_TUTORIAL;
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
        if (PAGE_PI_WEB.equals(normalized)
            || "pi-agent".equals(normalized)
            || "pi".equals(normalized)
            || "piweb".equals(normalized)) {
            return findPiWebComponent();
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

    private OpenHouseComponent findPiWebComponent() {
        for (OpenHouseComponent component : dynamicComponents) {
            if (component == null) {
                continue;
            }
            if (isPiWebComponent(component)) {
                return component;
            }
        }
        return null;
    }

    private boolean isBuiltinNavigationComponent(OpenHouseComponent component) {
        if (component == null) {
            return false;
        }
        return isCcCodexComponent(component)
            || isSmallPhoneComponent(component)
            || isControlledBrowserComponent(component)
            || isPiWebComponent(component)
            || isNativeBuiltinComponent(component);
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

    private boolean isPiWebComponent(OpenHouseComponent component) {
        if (component == null) {
            return false;
        }
        String id = normalizeId(component.id);
        if ("pi-web".equals(id)
            || "piweb".equals(id)
            || "pi-agent".equals(id)
            || "pi".equals(id)) {
            return true;
        }
        if (sameUrl(component.url, PI_WEB_DEFAULT_URL)) {
            return true;
        }
        for (String name : component.serviceNames) {
            String normalizedName = normalizeId(name);
            if ("pi-web".equals(normalizedName) || "pi-agent".equals(normalizedName)) {
                return true;
            }
        }
        return false;
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

    private String getSmallPhoneUrl() {
        OpenHouseComponent component = findSmallPhoneComponent();
        if (component != null
            && component.entryType == OpenHouseComponent.EntryType.WEBVIEW
            && !isBlank(component.url)) {
            return component.url;
        }
        return "http://127.0.0.1:22082/";
    }

    private String getPiWebTitle() {
        return componentTitleOrDefault(findPiWebComponent(), PI_WEB_TITLE);
    }

    private String getPiWebSubtitle(String fallback) {
        return componentSubtitleOrDefault(findPiWebComponent(), fallback);
    }

    private String getPiWebUrl() {
        OpenHouseComponent component = findPiWebComponent();
        if (component != null
            && component.entryType == OpenHouseComponent.EntryType.WEBVIEW
            && !isBlank(component.url)) {
            return component.url;
        }
        return PI_WEB_DEFAULT_URL;
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

        if (isUsageTutorialIntent(intent)) {
            if (isStartCoreServicesTutorialIntent(intent)
                || USAGE_STAGE_START_CORE.equals(getPendingUsageTutorialStage())) {
                startCoreServicesTeachingStage();
            } else {
                startUsageTeachingFlow();
            }
            return true;
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
        if (isUsageTutorialIntent(intent)) {
            return true;
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
        configured = getOpenHouseHomePrefs().getString(PREF_HOME_PAGE, null);
        if (isHomeCandidate(configured)) {
            return configured;
        }
        return firstVisibleHomePage();
    }

    private String firstVisibleHomePage() {
        if (isHomeCandidate(PAGE_PI_WEB)) {
            return PAGE_PI_WEB;
        }
        if (isComponentVisible(findCcCodexComponent())) {
            return PAGE_AI;
        }
        if (isComponentVisible(findSmallPhoneComponent())) {
            return PAGE_SMALLPHONE;
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
        if (PAGE_PI_WEB.equals(normalized)
            || "pi-agent".equals(normalized)
            || "pi".equals(normalized)
            || "piweb".equals(normalized)) {
            return PAGE_PI_WEB;
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
        if (PAGE_PI_WEB.equals(page)) {
            return PAGE_PI_WEB;
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
        if (PAGE_PI_WEB.equals(page)) {
            OpenHouseComponent component = findPiWebComponent();
            return component == null || isComponentVisible(component);
        }
        if (PAGE_SMALLPHONE.equals(page)
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
        if (PAGE_PI_WEB.equals(page)) {
            return getPiWebTitle();
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
        return getPiWebTitle();
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
        addBody(panel, "这里保留主入口：SmallPhone、pi-agent、cc/codex、service-manager、终端、文档、日志和维护中心。安装完成后，运行控制由 service-manager 负责。");
        panel.addView(createPiWorkbenchControlBlock());
        addButtonRow(panel,
            compactButton("进入 AI 软件安装引导", v -> openInstallGuide(), true),
            compactButton("使用教学", v -> startUsageTeachingFlow(), true));
        addButtonRow(panel,
            compactButton("打开 " + getCcCodexTitle(),
                v -> openBuiltinComponentOrFallback(findCcCodexComponent(), PAGE_AI),
                true),
            compactButton("进入 " + getPiWebTitle(), v -> openPiAgent(), true));
        addButtonRow(panel,
            compactButton("打开 " + getSmallPhoneTitle(), v -> openSmallPhone(), true),
            compactButton("运行控制", v -> openAllServiceControl(), true));
        addButtonRow(panel,
            compactButton("维护中心", v -> openMaintenanceCenter(), true),
            compactButton("使用手册", v -> selectPage(PAGE_MANUAL), true));
        panel.addView(button("退出菜单，回到终端", v -> openTerminal(false)));
        contentView.addView(panel);

        LinearLayout quick = panel();
        addTitle(quick, "快速状态", 17);
        addStatusRow(quick, getCcCodexTitle() + " 地址", getCcCodexUrl());
        addStatusRow(quick, getSmallPhoneTitle() + " 地址", getSmallPhoneUrl());
        addStatusRow(quick, getPiWebTitle() + " 地址", getPiWebUrl());
        addStatusRow(quick, "运行环境", "AI 工具安装在 Ubuntu /root");
        addStatusRow(quick, "控制平面", "service-manager");
        contentView.addView(quick);
    }

    private void renderUsageTutorialPage() {
        LinearLayout panel = panel();
        addTitle(panel, usageCoreServicesMode ? "启动核心服务" : "使用教学", 19);
        if (usageCoreServicesMode) {
            addBody(panel, "终端教学结束后，请明确点击下面的按钮，OpenHouse 才会启动内置核心服务。这个动作会拉起 service-manager、pi-agent/pi-web、SmallPhone 兼容入口和 cc/codex。");
            usageCoreServicesProgressView = new TextView(this);
            usageCoreServicesProgressView.setText(usageCoreServicesFailed
                ? "上次启动没有完成。请重试启动核心服务，或返回菜单稍后从运行控制处理。"
                : "等待点击“启动核心服务”。");
            usageCoreServicesProgressView.setTextColor(ContextCompat.getColor(this, R.color.textSecondary));
            usageCoreServicesProgressView.setTextSize(14);
            usageCoreServicesProgressView.setLineSpacing(dp(2), 1.0f);
            LinearLayout.LayoutParams progressParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
            progressParams.setMargins(0, dp(10), 0, 0);
            panel.addView(usageCoreServicesProgressView, progressParams);

            usageCoreServicesStartButton = button(
                usageCoreServicesFailed ? "重试启动核心服务" : "启动核心服务",
                v -> startCoreServicesFromTutorial());
            usageCoreServicesStartButton.setTag(HOME_CORE_SERVICES_BUTTON_TAG);
            panel.addView(usageCoreServicesStartButton);
            panel.addView(button("返回菜单", v -> {
                usageCoreServicesMode = false;
                selectPage(PAGE_HOME);
            }));
        } else {
            addBody(panel, "使用教学会带你认识菜单里的 SmallPhone、pi-agent、cc/codex，进入 cc/codex 控制，回到终端，再启动核心服务。需要真实点击的步骤不会显示“下一步”。");
            Button startButton = button("开始使用教学", v -> startUsageTeachingFlow());
            startButton.setTag(HOME_USAGE_TUTORIAL_TAG);
            panel.addView(startButton);
        }
        contentView.addView(panel);
    }

    private void renderManualPage() {
        addManualSection("安装时建议阅读",
            "第一次安装通常需要 10 分钟到半小时，期间会下载较大的运行环境，建议在 Wi-Fi 下进行。openhouse ai 会准备 Ubuntu、Node、Codex、Claude Code、CloudCLI、service-manager、openhouse-connect 和 SmallPhone。");
        addManualSection("首次安装之后",
            "安装链路只负责把环境装好。安装完成后，service-manager 才是运行控制平面，用于查看、启动、停止和修复内置服务。");
        addManualSection("终端里的 AI 怎么用",
            "以 Claude Code 为例，在 Ubuntu 终端输入 claude 再按回车即可使用；想继续上次对话，可以输入 claude --continue。Codex 可在 Ubuntu 终端中直接使用 codex。");
        addManualSection("Termux 和 Ubuntu",
            "启动后看到的是 Termux 终端。openhouse ai 会在 Termux 里安装 Ubuntu proot，Codex、Claude Code 和 CloudCLI 主要安装在 Ubuntu 的 /root 环境。普通入口终端可以默认进入 Ubuntu，维护中心底部终端固定为 Termux。");
        addManualSection("CloudCLI 和 SmallPhone",
            "CloudCLI 提供 cc/codex 统一入口。SmallPhone 是本机页面和运行栈入口。两者的服务状态可从运行控制或维护中心查看。");
        addManualSection("pi-agent",
            "pi-agent 是默认 agent 和插件体系入口，和 SmallPhone、cc/codex 一样是菜单侧边栏一级服务。完成安装后，它会由 service-manager 管理，默认地址为 " + PI_WEB_DEFAULT_URL + "。");
        addManualSection("底部快捷键",
            "底部按键包含 ESC、TAB、CTRL、ALT、方向键、键盘、Termux、Ubuntu、exit、clear，以及常用 AI 快捷键。exit 用于退出当前 shell；Ubuntu 用于进入 Ubuntu /root。按键支持自定义和多页，可以直接让 AI 帮你修改常用命令。");
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
        addTitle(panel, "使用教学", 19);
        addBody(panel, "使用教学会在终端上教你打开菜单、认识 SmallPhone、pi-agent、cc/codex 这些一级服务，回到终端，并查看运行控制。终端一般不需要直接使用，后续有需要可以单独看详细教学。");
        addButtonRow(panel,
            compactButton("打开使用教学", v -> openTerminal(true), true),
            compactButton("直接回到终端", v -> openTerminal(false), true));
        contentView.addView(panel);
    }

    private void renderShortcutsPage() {
        LinearLayout panel = panel();
        addTitle(panel, "底部 Termux Toolbar", 19);
        addBody(panel, "第一排和第二排保留常用终端控制键：ESC、TAB、CTRL、ALT、方向键、键盘、Termux、Ubuntu、exit、clear。");
        addBody(panel, "第三排是 AI 快捷键：claude、codex、--continue。第二页可以放完整命令，例如 claude --continue。");
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
            compactButton("启动日志", v -> openMaintenanceLog("start", "启动服务"), true),
            compactButton("重启日志", v -> openMaintenanceLog("restart", "重启服务"), true));
        panel.addView(button("查看详细进度", v -> openMaintenanceCenter()));
        contentView.addView(panel);
    }

    private void renderAdvancedPage() {
        LinearLayout panel = panel();
        addTitle(panel, "高级设置", 19);
        OpenHouseComponentRegistry.LoadResult registryResult = dynamicRegistryResult == null
            ? OpenHouseComponentRegistry.loadWithDiagnostics()
            : dynamicRegistryResult;
        addStatusRow(panel, "默认首页", getHomeDisplayTitle(getConfiguredHomePage()));
        addStatusRow(panel, "控制平面", "service-manager");
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

    private CheckBox checkbox(String text, boolean checked) {
        CheckBox box = new CheckBox(this);
        box.setText(text);
        box.setTextColor(ContextCompat.getColor(this, R.color.textPrimary));
        box.setTextSize(14);
        box.setChecked(checked);
        return box;
    }

    private void startCoreServicesFromTutorial() {
        destroyUsageTutorialOverlay();
        usageCoreServicesMode = true;
        usageCoreServicesFailed = false;
        if (!PAGE_USAGE_TUTORIAL.equals(currentPage)) {
            selectPage(PAGE_USAGE_TUTORIAL);
        }
        if (usageCoreServicesStartButton != null) {
            usageCoreServicesStartButton.setEnabled(false);
            usageCoreServicesStartButton.setText("启动中...");
        }
        setUsageCoreServicesProgress("正在启动 service-manager、openhouse-connect、pi-agent、pi-web 和 SmallPhone 兼容入口...");
        backgroundExecutor.execute(() -> {
            OpenHouseMaintainerRunner runner = new OpenHouseMaintainerRunner(this);
            OpenHouseMaintainerRunner.Result stackResult =
                runner.run(OpenHouseMaintainerRunner.Action.START_SMALLPHONE, 0);
            if (!stackResult.isSuccess()) {
                runOnUiThread(() -> showCoreServicesStartFailed(
                    "核心运行栈启动失败。请重试启动核心服务，或返回菜单后从运行控制/维护与修复处理。"));
                return;
            }

            runOnUiThread(() -> setUsageCoreServicesProgress("核心运行栈已启动，正在启动 cc/codex..."));

            OpenHouseMaintainerRunner.Result ccCodexResult =
                runner.run(OpenHouseMaintainerRunner.Action.START_CLAUDE_CODE_UI,
                    ClaudeCodeUiSettings.DEFAULT_PORT);
            runOnUiThread(() -> {
                if (!ccCodexResult.isSuccess()) {
                    showCoreServicesStartFailed(
                        "核心运行栈已启动，但 cc/codex 启动失败。请重试启动核心服务，或返回菜单后从运行控制/维护与修复处理。");
                    return;
                }
                setUsageCoreServicesProgress("核心运行栈启动完成。\ncc/codex 启动完成。");
                Toast.makeText(this, "核心服务已启动", Toast.LENGTH_LONG).show();
                startAfterCoreServicesTutorial();
            });
        });
    }

    private void setUsageCoreServicesProgress(String text) {
        if (usageCoreServicesProgressView != null) {
            usageCoreServicesProgressView.setText(text);
        }
    }

    private void showCoreServicesStartFailed(String message) {
        usageCoreServicesFailed = true;
        usageCoreServicesMode = true;
        setUsageCoreServicesProgress(message);
        if (usageCoreServicesStartButton != null) {
            usageCoreServicesStartButton.setEnabled(true);
            usageCoreServicesStartButton.setText("重试启动核心服务");
        }
        Toast.makeText(this, "核心服务启动未完成，请重试或返回菜单", Toast.LENGTH_LONG).show();
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

    private LinearLayout createPiWorkbenchControlBlock() {
        LinearLayout block = new LinearLayout(this);
        block.setOrientation(LinearLayout.VERTICAL);
        block.setPadding(0, dp(4), 0, dp(2));

        TextView titleView = new TextView(this);
        titleView.setText(PI_WEB_TITLE);
        titleView.setTextColor(ContextCompat.getColor(this, R.color.textPrimary));
        titleView.setTextSize(14);
        titleView.setTypeface(titleView.getTypeface(), android.graphics.Typeface.BOLD);
        block.addView(titleView, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView bodyView = new TextView(this);
        bodyView.setText("默认地址：" + getPiWebUrl());
        bodyView.setTextColor(ContextCompat.getColor(this, R.color.textSecondary));
        bodyView.setTextSize(12);
        LinearLayout.LayoutParams bodyParams = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT);
        bodyParams.setMargins(0, dp(4), 0, 0);
        block.addView(bodyView, bodyParams);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT);
        rowParams.setMargins(0, dp(8), 0, 0);

        Button openButton = compactButton("进入 pi-agent", v -> openPiAgent(), true);
        row.addView(openButton, new LinearLayout.LayoutParams(0, dp(44), 1));

        Button controlButton = compactButton("控制", v -> openPiWebControlOrAll(), true);
        LinearLayout.LayoutParams controlParams = new LinearLayout.LayoutParams(dp(96), dp(44));
        controlParams.setMargins(dp(8), 0, 0, 0);
        row.addView(controlButton, controlParams);
        block.addView(row, rowParams);
        return block;
    }

    private void openPiAgent() {
        if (drawerLayout != null) {
            drawerLayout.closeDrawer(GravityCompat.START);
        }
        selectPage(PAGE_PI_WEB);
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
