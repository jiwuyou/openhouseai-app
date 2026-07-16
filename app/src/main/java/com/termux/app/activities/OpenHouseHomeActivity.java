package com.termux.app.activities;

import android.Manifest;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
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

import androidx.appcompat.app.AlertDialog;
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
import com.termux.app.openhouse.OpenHousePiWebRescueController;
import com.termux.app.openhouse.OpenHouseRuntimePreferences;
import com.termux.app.openhouse.shizuku.OpenHouseShizukuManager;
import com.termux.app.openhouse.components.OpenHouseComponent;
import com.termux.app.openhouse.components.OpenHouseComponentRegistry;
import com.termux.app.openhouse.desktop.DesktopAppAction;
import com.termux.app.openhouse.desktop.DesktopAppActionResult;
import com.termux.app.openhouse.desktop.DesktopAppDescriptor;
import com.termux.app.openhouse.desktop.DesktopAppLaunchIntent;
import com.termux.app.openhouse.desktop.DesktopAppLauncher;
import com.termux.app.openhouse.desktop.DesktopAppStatusSheetModel;
import com.termux.app.openhouse.desktop.DesktopIconOverride;
import com.termux.app.openhouse.desktop.DesktopLaunchTarget;
import com.termux.app.openhouse.desktop.DesktopLayoutEntry;
import com.termux.app.openhouse.desktop.DesktopLayoutState;
import com.termux.app.openhouse.desktop.DesktopLayoutStore;
import com.termux.app.openhouse.desktop.ui.DesktopUiEntry;
import com.termux.app.openhouse.desktop.ui.OpenHouseDesktopView;
import com.termux.app.openhouse.tutorial.GuidedTutorialOverlay;
import com.termux.app.operit.OperitHomeIntegration;
import com.termux.app.smallphone.SmallPhoneFirstLaunchGate;
import com.termux.app.smallphone.SmallPhoneHostController;
import com.termux.shared.activity.ActivityUtils;
import com.termux.shared.logger.Logger;
import com.termux.shared.termux.TermuxConstants;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.json.JSONObject;

public class OpenHouseHomeActivity extends AppCompatActivity {

    private static final String LOG_TAG = "OpenHouseHome";
    private static final String PREFS_NAME = "openhouse_home";
    private static final String PREF_HOME_PAGE = "home_page";
    private static final String PREF_START_PAGE_MODE = "start_page_mode";
    private static final String PREF_LAST_PAGE = "last_page";
    private static final String PREF_TOP_ACTION_BAR_COLLAPSED = "top_action_bar_collapsed";
    private static final String PREF_TOP_ACTION_BAR_BUBBLE_EDGE = "top_action_bar_bubble_edge";
    private static final String PREF_TOP_ACTION_BAR_BUBBLE_Y_RATIO = "top_action_bar_bubble_y_ratio";
    private static final String PREF_DYNAMIC_WEBVIEW_RETAIN_COUNT = "dynamic_webview_retain_count";
    private static final String PREF_AI_RESCUE_PORT = "ai_rescue_port";
    private static final String PREF_AI_RESCUE_CONTROLS_COLLAPSED = "ai_rescue_controls_collapsed";
    private static final String PREF_AI_RESCUE_BUBBLE_EDGE = "ai_rescue_bubble_edge";
    private static final String PREF_AI_RESCUE_BUBBLE_Y_RATIO = "ai_rescue_bubble_y_ratio";
    private static final int MIN_AI_RESCUE_PORT = 1024;
    private static final int MAX_AI_RESCUE_PORT = 65535;
    private static final int MIN_DYNAMIC_WEBVIEW_RETAIN_COUNT = 0;
    private static final int MAX_DYNAMIC_WEBVIEW_RETAIN_COUNT = 5;
    private static final int DEFAULT_DYNAMIC_WEBVIEW_RETAIN_COUNT = 2;
    private static final String START_MODE_DESKTOP = "desktop";
    private static final String START_MODE_HOME = "home";
    private static final String START_MODE_LAST = "last";
    private static final String START_MODE_PAGE = "page";
    private static final String PAGE_HOME = "home";
    private static final String PAGE_DESKTOP = "desktop";
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
    private static final String PAGE_AI_RESCUE = "ai_rescue";
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
    private static final String USAGE_STAGE_START_CORE = TUTORIAL_START_CORE_SERVICES;
    private static final String CC_CODEX_SERVICE_NAME = "cloudcli";
    private static final String SMALLPHONE_HOME_TARGET = "messages";
    private static final String MENU_OVERRIDES_RELATIVE_PATH = ".config/openhouseai/menu-overrides.json";
    private static final String CC_CODEX_TITLE = "cc/codex";
    private static final String PI_WEB_TITLE = "pi-agent";
    private static final String PI_WEB_DEFAULT_URL = "http://127.0.0.1:30141/";
    private static final String HOME_USAGE_TUTORIAL_TAG = "openhouse_home_usage_tutorial";
    private static final String HOME_CORE_SERVICES_BUTTON_TAG = "openhouse_core_services_start";
    private static final String AI_FRIEND_HELP_ENTRY_TAG = "ai_friend_help_entry";
    private static final String DESKTOP_DRAWER_ENTRY_TAG = "openhouse_desktop_drawer_entry";
    private static final String OPERIT_DESKTOP_APP_ID = "operit-help";
    private static final int DESKTOP_GRID_COLUMNS = 3;
    private static final int DESKTOP_GRID_ROWS = 3;
    private static final int DESKTOP_PAGE_SIZE = DESKTOP_GRID_COLUMNS * DESKTOP_GRID_ROWS;
    private static final int BUBBLE_EDGE_START = 0;
    private static final int BUBBLE_EDGE_END = 1;

    private final ExecutorService backgroundExecutor = Executors.newSingleThreadExecutor();

    private DrawerLayout drawerLayout;
    private ScrollView scrollContentView;
    private FrameLayout embeddedContentView;
    private LinearLayout contentView;
    private LinearLayout dynamicQuickNavView;
    private LinearLayout dynamicNavView;
    private FrameLayout pageHostView;
    private View topActionBarView;
    private TextView topActionBarBubbleView;
    private TextView homeStatusView;
    private Button setCurrentHomeButton;
    private Button copyCurrentButton;
    private Button openCurrentBrowserButton;
    private Button openCurrentControlButton;
    private Button returnDesktopButton;
    private Button collapseTopActionButton;
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
    private String currentPage = PAGE_DESKTOP;
    private List<OpenHouseComponent> dynamicComponents = Collections.emptyList();
    private List<OpenHouseComponent> desktopComponents = Collections.emptyList();
    private OpenHouseComponentRegistry.LoadResult dynamicRegistryResult;
    private DesktopAppLauncher desktopAppLauncher;
    private DesktopLayoutStore desktopLayoutStore;
    private DesktopLayoutState desktopLayoutState;
    private OpenHouseDesktopView desktopView;
    private boolean bindingDesktopView;
    private OpenHouseShizukuManager shizukuManager;
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
    private FrameLayout aiRescuePageView;
    private LinearLayout aiRescueControlsView;
    private TextView aiRescueBubbleView;
    private WebView aiRescueWebView;
    private LinearLayout aiRescueFallbackView;
    private TextView aiRescueStatusView;
    private EditText aiRescuePortInput;
    private boolean aiRescueLoadFailed = false;
    private String renderedAiRescueUrl;
    private ControlledBrowserView controlledBrowserView;
    private LinearLayout dynamicWebPageView;
    private WebView dynamicWebView;
    private LinearLayout dynamicWebFallbackView;
    private TextView dynamicWebStatusView;
    private OpenHouseComponent dynamicWebComponent;
    private boolean dynamicWebLoadFailed = false;
    private final Map<String, DynamicWebPageRecord> dynamicWebPagePool = new HashMap<>();
    private DynamicWebPageRecord activeDynamicWebPage;
    private long dynamicWebUseSequence = 0L;
    private boolean firstLaunchGateForwarded;
    private String renderedCloudCliUrl;
    private String renderedPiWebUrl;
    private GuidedTutorialOverlay usageTutorialOverlay;
    private boolean usageCoreServicesMode = false;
    private boolean usageCoreServicesFailed = false;
    private TextView usageCoreServicesProgressView;
    private Button usageCoreServicesStartButton;
    private long aiFriendHelpLaunchRequestedAtMs;
    private long aiFriendHelpShutdownRequestedAtMs;
    private boolean aiFriendHelpLaunchFailureNotified;
    private String pendingDesktopOpenAppId;

    private static final class DynamicWebPageRecord {
        final String key;
        OpenHouseComponent component;
        LinearLayout pageView;
        WebView webView;
        LinearLayout fallbackView;
        TextView statusView;
        boolean loadFailed;
        long lastUsedOrder;

        DynamicWebPageRecord(String key, OpenHouseComponent component) {
            this.key = key;
            this.component = component;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (OperitHomeIntegration.forwardExternalEntryIntentIfNeeded(this, getIntent())) {
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
        pageHostView = findViewById(R.id.openhousePageHost);
        homeStatusView = findViewById(R.id.openhouseHomeStatus);
        setCurrentHomeButton = findViewById(R.id.buttonSetCurrentHome);
        copyCurrentButton = findViewById(R.id.buttonCopyCurrent);
        openCurrentBrowserButton = findViewById(R.id.buttonOpenCurrentBrowser);
        openCurrentControlButton = findViewById(R.id.buttonOpenCurrentControl);
        returnDesktopButton = findViewById(R.id.buttonReturnDesktop);
        collapseTopActionButton = findViewById(R.id.buttonCollapseTopAction);
        refreshCurrentButton = findViewById(R.id.buttonRefreshCurrent);
        pageTitleView = findViewById(R.id.openhousePageTitle);
        pageSubtitleView = findViewById(R.id.openhousePageSubtitle);
        desktopAppLauncher = new DesktopAppLauncher(this);
        desktopLayoutStore = new DesktopLayoutStore(this);
        shizukuManager = new OpenHouseShizukuManager(this, this::onShizukuStateChanged);
        shizukuManager.start();
        shizukuManager.ensureRishInstalled();

        View openDrawerButton = findViewById(R.id.buttonOpenDrawer);
        topActionBarView = openDrawerButton == null ? null : (View) openDrawerButton.getParent();
        initTopActionChrome();
        if (openDrawerButton != null) {
            openDrawerButton.setOnClickListener(v -> drawerLayout.openDrawer(GravityCompat.START));
        }
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
        if (returnDesktopButton != null) {
            returnDesktopButton.setOnClickListener(v -> selectPage(PAGE_DESKTOP));
        }
        if (collapseTopActionButton != null) {
            collapseTopActionButton.setOnClickListener(v -> setTopActionBarCollapsed(true));
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
        if (shizukuManager != null) {
            shizukuManager.stop();
            shizukuManager = null;
        }
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
        if (aiRescueWebView != null) {
            aiRescueWebView.destroy();
            aiRescueWebView = null;
        }
        if (controlledBrowserView != null) {
            controlledBrowserView.setExternalNavigationHandler(null);
            if (controlledBrowserView.getParent() instanceof ViewGroup) {
                ((ViewGroup) controlledBrowserView.getParent()).removeView(controlledBrowserView);
            }
            controlledBrowserView = null;
        }
        releaseAllDynamicWebPages();
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
            resumeActiveDynamicWebPage();
        } else if (PAGE_SMALLPHONE.equals(currentPage) && smallPhoneController != null) {
            smallPhoneController.onResume(false);
        } else if (PAGE_PI_WEB.equals(currentPage) && piWebView != null) {
            piWebView.onResume();
        } else if (PAGE_AI_RESCUE.equals(currentPage) && aiRescueWebView != null) {
            aiRescueWebView.onResume();
        } else if (PAGE_AI.equals(currentPage) && cloudCliWebView != null) {
            cloudCliWebView.onResume();
        } else if (PAGE_CONTROLLED_BROWSER.equals(currentPage)
            && isCurrentDynamicWebComponent(findControlledBrowserComponent())
            && dynamicWebView != null) {
            resumeActiveDynamicWebPage();
        } else if (PAGE_CONTROLLED_BROWSER.equals(currentPage) && controlledBrowserView != null) {
            controlledBrowserView.onHostResume();
        } else if (isComponentPage(currentPage) && dynamicWebView != null) {
            resumeActiveDynamicWebPage();
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
        if (OperitHomeIntegration.forwardExternalEntryIntentIfNeeded(this, intent)) {
            finish();
            return;
        }
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
        if (PAGE_AI_RESCUE.equals(currentPage)
            && aiRescueWebView != null
            && aiRescueWebView.canGoBack()) {
            aiRescueWebView.goBack();
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
        String backTargetPage = getBackTargetPage();
        if (!currentPage.equals(backTargetPage)) {
            selectPage(backTargetPage);
            return;
        }
        super.onBackPressed();
    }

    private void bindNavigation() {
        addDesktopDrawerEntry();
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
        findViewById(R.id.buttonNavAiRescue).setOnClickListener(v -> selectPage(PAGE_AI_RESCUE));
        addAiFriendHelpDrawerEntry();
        if (setCurrentHomeButton != null) {
            setCurrentHomeButton.setOnClickListener(v -> setCurrentPageAsHome());
        }
        updateHomePreferenceViews();
    }

    private void initTopActionChrome() {
        createTopActionBarBubble();
        View.OnLongClickListener collapseListener = v -> {
            setTopActionBarCollapsed(true);
            return true;
        };
        if (topActionBarView != null) {
            topActionBarView.setOnLongClickListener(collapseListener);
        }
        int[] actionButtonIds = new int[] {
            R.id.buttonOpenDrawer,
            R.id.buttonReturnDesktop,
            R.id.buttonCopyCurrent,
            R.id.buttonOpenCurrentBrowser,
            R.id.buttonOpenCurrentControl,
            R.id.buttonCollapseTopAction,
            R.id.buttonRefreshCurrent
        };
        for (int id : actionButtonIds) {
            View button = findViewById(id);
            if (button != null) {
                button.setOnLongClickListener(collapseListener);
            }
        }
        updateTopActionChrome();
    }

    private void createTopActionBarBubble() {
        if (pageHostView == null || topActionBarBubbleView != null) {
            return;
        }
        topActionBarBubbleView = new TextView(this);
        topActionBarBubbleView.setContentDescription("展开顶部控制栏");
        topActionBarBubbleView.setGravity(Gravity.CENTER);
        topActionBarBubbleView.setText("");
        topActionBarBubbleView.setClickable(true);
        topActionBarBubbleView.setFocusable(true);
        topActionBarBubbleView.setElevation(dp(8));

        GradientDrawable background = new GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            new int[] { Color.WHITE, Color.DKGRAY, Color.BLACK });
        background.setShape(GradientDrawable.OVAL);
        background.setStroke(dp(1), 0x66FFFFFF);
        topActionBarBubbleView.setBackground(background);
        topActionBarBubbleView.setOnClickListener(v -> setTopActionBarCollapsed(false));
        attachTopActionBarBubbleDrag(topActionBarBubbleView);

        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(dp(52), dp(52));
        params.gravity = Gravity.TOP | Gravity.START;
        pageHostView.addView(topActionBarBubbleView, params);
        topActionBarBubbleView.setVisibility(View.GONE);
        pageHostView.post(this::applyTopActionBarBubblePosition);
    }

    private void attachTopActionBarBubbleDrag(View bubble) {
        if (bubble == null) {
            return;
        }
        final int touchSlop = ViewConfiguration.get(this).getScaledTouchSlop();
        final float[] downRaw = new float[2];
        final int[] startMargins = new int[2];
        final boolean[] dragging = new boolean[1];
        bubble.setOnTouchListener((v, event) -> {
            if (!(v.getLayoutParams() instanceof FrameLayout.LayoutParams)) {
                return false;
            }
            FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) v.getLayoutParams();
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downRaw[0] = event.getRawX();
                    downRaw[1] = event.getRawY();
                    startMargins[0] = params.leftMargin;
                    startMargins[1] = params.topMargin;
                    dragging[0] = false;
                    v.getParent().requestDisallowInterceptTouchEvent(true);
                    return true;
                case MotionEvent.ACTION_MOVE:
                    float dx = event.getRawX() - downRaw[0];
                    float dy = event.getRawY() - downRaw[1];
                    if (!dragging[0] && Math.hypot(dx, dy) > touchSlop) {
                        dragging[0] = true;
                    }
                    if (dragging[0]) {
                        moveTopActionBarBubbleTo(startMargins[0] + Math.round(dx), startMargins[1] + Math.round(dy), false);
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    v.getParent().requestDisallowInterceptTouchEvent(false);
                    if (dragging[0]) {
                        snapAndSaveTopActionBarBubble();
                    } else if (event.getActionMasked() == MotionEvent.ACTION_UP) {
                        setTopActionBarCollapsed(false);
                    }
                    return true;
                default:
                    return true;
            }
        });
    }

    private void applyTopActionBarBubblePosition() {
        if (pageHostView == null || topActionBarBubbleView == null) {
            return;
        }
        int hostWidth = pageHostView.getWidth();
        int hostHeight = pageHostView.getHeight();
        int bubbleSize = topActionBarBubbleView.getWidth() > 0 ? topActionBarBubbleView.getWidth() : dp(52);
        if (hostWidth <= 0 || hostHeight <= 0) {
            pageHostView.post(this::applyTopActionBarBubblePosition);
            return;
        }
        SharedPreferences prefs = getOpenHouseHomePrefs();
        int edge = prefs.getInt(PREF_TOP_ACTION_BAR_BUBBLE_EDGE, BUBBLE_EDGE_END);
        float yRatio = prefs.getFloat(PREF_TOP_ACTION_BAR_BUBBLE_Y_RATIO, 0.78f);
        int margin = dp(14);
        int left = edge == BUBBLE_EDGE_START ? margin : hostWidth - bubbleSize - margin;
        int top = Math.round(clampFloat(yRatio, 0f, 1f) * Math.max(0, hostHeight - bubbleSize - margin * 2)) + margin;
        moveTopActionBarBubbleTo(left, top, false);
    }

    private void moveTopActionBarBubbleTo(int left, int top, boolean save) {
        if (pageHostView == null || topActionBarBubbleView == null
            || !(topActionBarBubbleView.getLayoutParams() instanceof FrameLayout.LayoutParams)) {
            return;
        }
        int hostWidth = pageHostView.getWidth();
        int hostHeight = pageHostView.getHeight();
        int bubbleWidth = topActionBarBubbleView.getWidth() > 0 ? topActionBarBubbleView.getWidth() : dp(52);
        int bubbleHeight = topActionBarBubbleView.getHeight() > 0 ? topActionBarBubbleView.getHeight() : dp(52);
        int margin = dp(10);
        int maxLeft = Math.max(margin, hostWidth - bubbleWidth - margin);
        int maxTop = Math.max(margin, hostHeight - bubbleHeight - margin);
        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) topActionBarBubbleView.getLayoutParams();
        params.gravity = Gravity.TOP | Gravity.START;
        params.leftMargin = clampInt(left, margin, maxLeft);
        params.topMargin = clampInt(top, margin, maxTop);
        params.rightMargin = 0;
        params.bottomMargin = 0;
        topActionBarBubbleView.setLayoutParams(params);
        if (save) {
            saveTopActionBarBubblePosition(params.leftMargin, params.topMargin);
        }
    }

    private void snapAndSaveTopActionBarBubble() {
        if (pageHostView == null || topActionBarBubbleView == null
            || !(topActionBarBubbleView.getLayoutParams() instanceof FrameLayout.LayoutParams)) {
            return;
        }
        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) topActionBarBubbleView.getLayoutParams();
        int bubbleWidth = topActionBarBubbleView.getWidth() > 0 ? topActionBarBubbleView.getWidth() : dp(52);
        int margin = dp(14);
        int hostWidth = pageHostView.getWidth();
        int center = params.leftMargin + bubbleWidth / 2;
        int snappedLeft = center < hostWidth / 2 ? margin : Math.max(margin, hostWidth - bubbleWidth - margin);
        moveTopActionBarBubbleTo(snappedLeft, params.topMargin, true);
    }

    private void saveTopActionBarBubblePosition(int left, int top) {
        if (pageHostView == null || topActionBarBubbleView == null) {
            return;
        }
        int hostWidth = pageHostView.getWidth();
        int hostHeight = pageHostView.getHeight();
        int bubbleWidth = topActionBarBubbleView.getWidth() > 0 ? topActionBarBubbleView.getWidth() : dp(52);
        int bubbleHeight = topActionBarBubbleView.getHeight() > 0 ? topActionBarBubbleView.getHeight() : dp(52);
        int edge = left + bubbleWidth / 2 < hostWidth / 2 ? BUBBLE_EDGE_START : BUBBLE_EDGE_END;
        int margin = dp(14);
        float yRatio = hostHeight <= bubbleHeight + margin * 2
            ? 0.78f
            : (float) (top - margin) / (float) Math.max(1, hostHeight - bubbleHeight - margin * 2);
        getOpenHouseHomePrefs().edit()
            .putInt(PREF_TOP_ACTION_BAR_BUBBLE_EDGE, edge)
            .putFloat(PREF_TOP_ACTION_BAR_BUBBLE_Y_RATIO, clampFloat(yRatio, 0f, 1f))
            .apply();
    }

    private void setTopActionBarCollapsed(boolean collapsed) {
        if (PAGE_DESKTOP.equals(currentPage)) {
            collapsed = false;
        }
        getOpenHouseHomePrefs().edit()
            .putBoolean(PREF_TOP_ACTION_BAR_COLLAPSED, collapsed)
            .apply();
        updateTopActionChrome();
        if (collapsed) {
            Toast.makeText(this, "顶部控制栏已收起，点击悬浮球可展开。", Toast.LENGTH_SHORT).show();
        }
    }

    private boolean isTopActionBarCollapsed() {
        return getOpenHouseHomePrefs().getBoolean(PREF_TOP_ACTION_BAR_COLLAPSED, false);
    }

    private void updateTopActionChrome() {
        boolean isDesktop = PAGE_DESKTOP.equals(currentPage);
        boolean collapsed = !isDesktop && isTopActionBarCollapsed();
        if (topActionBarView != null) {
            topActionBarView.setVisibility(!isDesktop && !collapsed ? View.VISIBLE : View.GONE);
        }
        if (topActionBarBubbleView != null) {
            topActionBarBubbleView.setVisibility(!isDesktop && collapsed ? View.VISIBLE : View.GONE);
            if (!isDesktop && collapsed) {
                topActionBarBubbleView.post(this::applyTopActionBarBubblePosition);
            }
        }
    }

    private void addAiFriendHelpDrawerEntry() {
        if (!OperitHomeIntegration.isAvailable()) {
            return;
        }
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
        desktopComponents = dynamicRegistryResult.desktopComponents;
        setFallbackNavigationVisible(dynamicRegistryResult.shouldShowFallbackNavigation());
        updateBuiltinNavigationLabels();
        updateStaticNavigationLabels();
        refreshAiFriendHelpEntryState();
        renderDynamicQuickNavigation();
        renderDynamicNavigation();
        if (PAGE_DESKTOP.equals(currentPage)) {
            renderPage();
        }
        updateHomePreferenceViews();
        updateTopActionState();
    }

    private void addDesktopDrawerEntry() {
        View anchor = findViewById(R.id.buttonNavHome);
        if (anchor == null || !(anchor.getParent() instanceof LinearLayout)) {
            return;
        }
        LinearLayout parent = (LinearLayout) anchor.getParent();
        if (parent.findViewWithTag(DESKTOP_DRAWER_ENTRY_TAG) != null) {
            return;
        }
        Button desktopButton = new Button(this);
        desktopButton.setTag(DESKTOP_DRAWER_ENTRY_TAG);
        desktopButton.setText("桌面");
        desktopButton.setAllCaps(false);
        desktopButton.setOnClickListener(v -> selectPage(PAGE_DESKTOP));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(52));
        params.setMargins(0, dp(8), 0, 0);
        parent.addView(desktopButton, parent.indexOfChild(anchor), params);
    }

    private void updateStaticNavigationLabels() {
        View homeButton = findViewById(R.id.buttonNavHome);
        if (homeButton instanceof Button) {
            ((Button) homeButton).setText(getDesktopNativePageTitle(PAGE_HOME, "菜单总览"));
        }
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
            R.id.buttonNavTerminal,
            R.id.buttonNavAiRescue
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
        String targetPage = isBlank(page) ? PAGE_DESKTOP : page;
        if (!targetPage.equals(currentPage)) {
            pauseCurrentEmbeddedPage();
            releaseEmbeddedPagesExcept(targetPage);
        }
        currentPage = targetPage;
        rememberLastOpenHousePage(targetPage);
        if (drawerLayout != null) {
            drawerLayout.closeDrawer(GravityCompat.START);
        }
        renderPage();
        updateHomePreferenceViews();
        updateTopActionChrome();
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
            releaseEmbeddedPagesExcept(PAGE_DESKTOP);
            currentPage = PAGE_DESKTOP;
        }

        switch (currentPage) {
            case PAGE_PI_WEB:
                setHeader(getPiWebTitle(), getPiWebSubtitle("默认 agent 和插件入口"));
                renderPiWebPage();
                break;
            case PAGE_AI:
                setHeader(getCcCodexTitle(), getCcCodexSubtitle("后置 AI 能力：请进入 pi-agent 完成安装配置"));
                renderAiPage();
                break;
            case PAGE_AI_RESCUE:
                setHeader("AI救援", "Termux native 中独立运行的原始 pi-web");
                renderAiRescuePage();
                break;
            case PAGE_SMALLPHONE:
                setHeader(getSmallPhoneTitle(), getSmallPhoneSubtitle("小手机页面和运行栈修复"));
                renderSmallPhonePage();
                break;
            case PAGE_CONTROLLED_BROWSER:
                setHeader(getControlledBrowserTitle(), getControlledBrowserSubtitle("多标签，可由 Termux 命令控制"));
                renderControlledBrowserPage();
                break;
            case PAGE_DESKTOP:
                releaseEmbeddedPagesExcept(PAGE_DESKTOP);
                showScrollContent();
                setHeader("桌面", "OpenHouse apps");
                renderDesktopPage();
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
        boolean isDesktop = PAGE_DESKTOP.equals(currentPage);
        if (copyCurrentButton != null) {
            copyCurrentButton.setEnabled(!isBlank(browserUrl) || !isBlank(getCurrentCopyText()));
        }
        if (openCurrentBrowserButton != null) {
            boolean hasBrowserUrl = !isBlank(browserUrl);
            openCurrentBrowserButton.setText(R.string.openhouse_action_open_in_browser);
            openCurrentBrowserButton.setEnabled(hasBrowserUrl);
            openCurrentBrowserButton.setAlpha(hasBrowserUrl ? 1f : 0.45f);
        }
        if (returnDesktopButton != null) {
            returnDesktopButton.setEnabled(!isDesktop);
        }
        if (openCurrentControlButton != null) {
            openCurrentControlButton.setEnabled(!isDesktop);
        }
        if (collapseTopActionButton != null) {
            collapseTopActionButton.setEnabled(!isDesktop);
        }
        if (refreshCurrentButton != null) {
            refreshCurrentButton.setEnabled(!isDesktop);
        }
        updateTopActionChrome();
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
        if (PAGE_AI_RESCUE.equals(currentPage)) {
            runPiWebRescueAction("status");
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
        if (PAGE_AI_RESCUE.equals(currentPage)) {
            reloadAiRescueWebView();
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
        if (PAGE_AI_RESCUE.equals(currentPage)) {
            return getAiRescueUrl();
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
        String key = getDynamicWebPageKey(component);
        return !isBlank(key)
            && component.entryType == OpenHouseComponent.EntryType.WEBVIEW
            && activeDynamicWebPage != null
            && key.equals(activeDynamicWebPage.key);
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
                    clearPendingDesktopOpenForNativePage(PAGE_PI_WEB);
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
        notifyDesktopOpenFailedForNativePage(PAGE_PI_WEB, findPiWebComponent(),
            getPiWebTitle() + " 没有响应，可以重启服务或进入服务控制查看状态。");
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

    private void renderAiRescuePage() {
        showEmbeddedContent();
        if (embeddedContentView == null) {
            return;
        }
        String rescueUrl = getAiRescueUrl();
        if (aiRescuePageView == null || !rescueUrl.equals(renderedAiRescueUrl)) {
            releaseAiRescuePage();
            aiRescuePageView = createAiRescuePageView();
            renderedAiRescueUrl = rescueUrl;
        }
        attachEmbeddedView(aiRescuePageView);
        if (aiRescueWebView != null) {
            aiRescueWebView.onResume();
            if (aiRescueWebView.getUrl() == null) {
                reloadAiRescueWebView();
            }
        }
    }

    private FrameLayout createAiRescuePageView() {
        FrameLayout pageHost = new FrameLayout(this);
        pageHost.setBackgroundColor(ContextCompat.getColor(this, R.color.surface));

        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setBackgroundColor(ContextCompat.getColor(this, R.color.surface));
        pageHost.addView(page, new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT));

        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(12), dp(10), dp(12), dp(12));
        panel.setBackgroundColor(ContextCompat.getColor(this, R.color.panel));
        aiRescueControlsView = panel;

        LinearLayout statusRow = new LinearLayout(this);
        statusRow.setOrientation(LinearLayout.HORIZONTAL);
        statusRow.setGravity(Gravity.TOP);

        aiRescueStatusView = new TextView(this);
        aiRescueStatusView.setText("AI救援地址：" + getAiRescueUrl()
            + "\n这个入口不依赖 service-manager 或 Ubuntu，直接在 Termux native 中临时运行原始 pi-web。");
        aiRescueStatusView.setTextColor(ContextCompat.getColor(this, R.color.textSecondary));
        aiRescueStatusView.setTextSize(13);
        aiRescueStatusView.setLineSpacing(dp(2), 1.0f);
        statusRow.addView(aiRescueStatusView, new LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        Button collapseButton = compactButton("收起", v -> setAiRescueControlsCollapsed(true), true);
        LinearLayout.LayoutParams collapseParams = new LinearLayout.LayoutParams(dp(72), dp(40));
        collapseParams.setMargins(dp(8), 0, 0, 0);
        statusRow.addView(collapseButton, collapseParams);
        panel.addView(statusRow);

        TextView portLabel = new TextView(this);
        portLabel.setText("救援端口");
        portLabel.setTextColor(ContextCompat.getColor(this, R.color.textPrimary));
        portLabel.setTextSize(13);
        portLabel.setTypeface(portLabel.getTypeface(), android.graphics.Typeface.BOLD);
        LinearLayout.LayoutParams portLabelParams = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT);
        portLabelParams.setMargins(0, dp(10), 0, 0);
        panel.addView(portLabel, portLabelParams);

        LinearLayout portRow = new LinearLayout(this);
        portRow.setOrientation(LinearLayout.HORIZONTAL);
        portRow.setGravity(Gravity.CENTER_VERTICAL);
        aiRescuePortInput = new EditText(this);
        aiRescuePortInput.setInputType(InputType.TYPE_CLASS_NUMBER);
        aiRescuePortInput.setSingleLine(true);
        aiRescuePortInput.setSelectAllOnFocus(true);
        aiRescuePortInput.setText(Integer.toString(getAiRescuePort()));
        aiRescuePortInput.setHint(Integer.toString(OpenHousePiWebRescueController.DEFAULT_PORT));
        aiRescuePortInput.setTextColor(ContextCompat.getColor(this, R.color.textPrimary));
        aiRescuePortInput.setHintTextColor(ContextCompat.getColor(this, R.color.textSecondary));
        portRow.addView(aiRescuePortInput, new LinearLayout.LayoutParams(0, dp(48), 1));
        Button savePortButton = compactButton("保存端口", v -> saveAiRescuePortFromInput(true), true);
        LinearLayout.LayoutParams savePortParams = new LinearLayout.LayoutParams(dp(112), dp(48));
        savePortParams.setMargins(dp(8), 0, 0, 0);
        portRow.addView(savePortButton, savePortParams);
        LinearLayout.LayoutParams portRowParams = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT);
        portRowParams.setMargins(0, dp(6), 0, 0);
        panel.addView(portRow, portRowParams);

        addButtonRow(panel,
            compactButton("启动救援", v -> runPiWebRescueAction("start"), true),
            compactButton("重启救援", v -> runPiWebRescueAction("restart"), true));
        addButtonRow(panel,
            compactButton("停止救援", v -> runPiWebRescueAction("stop"), true),
            compactButton("检查状态", v -> runPiWebRescueAction("status"), true));
        addButtonRow(panel,
            compactButton("复制地址", v -> copyAiRescueUrlFromInput(), true),
            compactButton("刷新页面", v -> reloadAiRescueWebView(), true));
        page.addView(panel, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT));

        FrameLayout browserHost = new FrameLayout(this);
        aiRescueWebView = new WebView(this);
        configureAiRescueWebView(aiRescueWebView);
        browserHost.addView(aiRescueWebView, new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT));

        aiRescueFallbackView = createAiRescueFallbackView();
        aiRescueFallbackView.setVisibility(View.GONE);
        browserHost.addView(aiRescueFallbackView, new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT));
        page.addView(browserHost, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            0,
            1));

        aiRescueBubbleView = new TextView(this);
        aiRescueBubbleView.setText("救援");
        aiRescueBubbleView.setTextColor(Color.WHITE);
        aiRescueBubbleView.setTextSize(13);
        aiRescueBubbleView.setGravity(Gravity.CENTER);
        aiRescueBubbleView.setContentDescription("展开 AI 救援控制");
        aiRescueBubbleView.setClickable(true);
        aiRescueBubbleView.setFocusable(true);
        aiRescueBubbleView.setElevation(dp(8));
        GradientDrawable bubbleBackground = new GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            new int[] { 0xFF1E6F52, 0xFF155F43 });
        bubbleBackground.setShape(GradientDrawable.OVAL);
        bubbleBackground.setStroke(dp(1), 0x66FFFFFF);
        aiRescueBubbleView.setBackground(bubbleBackground);
        aiRescueBubbleView.setOnClickListener(v -> setAiRescueControlsCollapsed(false));
        attachAiRescueBubbleDrag(aiRescueBubbleView);
        FrameLayout.LayoutParams bubbleParams = new FrameLayout.LayoutParams(dp(52), dp(52));
        bubbleParams.gravity = Gravity.TOP | Gravity.START;
        pageHost.addView(aiRescueBubbleView, bubbleParams);
        aiRescueBubbleView.setVisibility(View.GONE);
        pageHost.post(this::updateAiRescueControlsChrome);
        return pageHost;
    }

    private void setAiRescueControlsCollapsed(boolean collapsed) {
        getOpenHouseHomePrefs().edit()
            .putBoolean(PREF_AI_RESCUE_CONTROLS_COLLAPSED, collapsed)
            .apply();
        updateAiRescueControlsChrome();
    }

    private boolean isAiRescueControlsCollapsed() {
        return getOpenHouseHomePrefs().getBoolean(PREF_AI_RESCUE_CONTROLS_COLLAPSED, false);
    }

    private void updateAiRescueControlsChrome() {
        boolean collapsed = isAiRescueControlsCollapsed();
        if (aiRescueControlsView != null) {
            aiRescueControlsView.setVisibility(collapsed ? View.GONE : View.VISIBLE);
        }
        if (aiRescueBubbleView != null) {
            aiRescueBubbleView.setVisibility(collapsed ? View.VISIBLE : View.GONE);
            if (collapsed) {
                aiRescueBubbleView.bringToFront();
                aiRescueBubbleView.post(this::applyAiRescueBubblePosition);
            }
        }
    }

    private void attachAiRescueBubbleDrag(View bubble) {
        if (bubble == null) {
            return;
        }
        final int touchSlop = ViewConfiguration.get(this).getScaledTouchSlop();
        final float[] downRaw = new float[2];
        final int[] startMargins = new int[2];
        final boolean[] dragging = new boolean[1];
        bubble.setOnTouchListener((view, event) -> {
            if (!(view.getLayoutParams() instanceof FrameLayout.LayoutParams)) {
                return false;
            }
            FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) view.getLayoutParams();
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downRaw[0] = event.getRawX();
                    downRaw[1] = event.getRawY();
                    startMargins[0] = params.leftMargin;
                    startMargins[1] = params.topMargin;
                    dragging[0] = false;
                    view.getParent().requestDisallowInterceptTouchEvent(true);
                    return true;
                case MotionEvent.ACTION_MOVE:
                    float dx = event.getRawX() - downRaw[0];
                    float dy = event.getRawY() - downRaw[1];
                    if (!dragging[0] && Math.hypot(dx, dy) > touchSlop) {
                        dragging[0] = true;
                    }
                    if (dragging[0]) {
                        moveAiRescueBubbleTo(
                            startMargins[0] + Math.round(dx),
                            startMargins[1] + Math.round(dy),
                            false);
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    view.getParent().requestDisallowInterceptTouchEvent(false);
                    if (dragging[0]) {
                        snapAndSaveAiRescueBubble();
                    } else if (event.getActionMasked() == MotionEvent.ACTION_UP) {
                        setAiRescueControlsCollapsed(false);
                    }
                    return true;
                default:
                    return true;
            }
        });
    }

    private void applyAiRescueBubblePosition() {
        if (aiRescuePageView == null || aiRescueBubbleView == null) {
            return;
        }
        int hostWidth = aiRescuePageView.getWidth();
        int hostHeight = aiRescuePageView.getHeight();
        int bubbleSize = aiRescueBubbleView.getWidth() > 0 ? aiRescueBubbleView.getWidth() : dp(52);
        if (hostWidth <= 0 || hostHeight <= 0) {
            aiRescuePageView.post(this::applyAiRescueBubblePosition);
            return;
        }
        SharedPreferences prefs = getOpenHouseHomePrefs();
        int edge = prefs.getInt(PREF_AI_RESCUE_BUBBLE_EDGE, BUBBLE_EDGE_START);
        float yRatio = prefs.getFloat(PREF_AI_RESCUE_BUBBLE_Y_RATIO, 0.16f);
        int margin = dp(14);
        int left = edge == BUBBLE_EDGE_END ? hostWidth - bubbleSize - margin : margin;
        int top = Math.round(clampFloat(yRatio, 0f, 1f)
            * Math.max(0, hostHeight - bubbleSize - margin * 2)) + margin;
        moveAiRescueBubbleTo(left, top, false);
    }

    private void moveAiRescueBubbleTo(int left, int top, boolean save) {
        if (aiRescuePageView == null || aiRescueBubbleView == null
            || !(aiRescueBubbleView.getLayoutParams() instanceof FrameLayout.LayoutParams)) {
            return;
        }
        int hostWidth = aiRescuePageView.getWidth();
        int hostHeight = aiRescuePageView.getHeight();
        int bubbleWidth = aiRescueBubbleView.getWidth() > 0 ? aiRescueBubbleView.getWidth() : dp(52);
        int bubbleHeight = aiRescueBubbleView.getHeight() > 0 ? aiRescueBubbleView.getHeight() : dp(52);
        int margin = dp(10);
        int maxLeft = Math.max(margin, hostWidth - bubbleWidth - margin);
        int maxTop = Math.max(margin, hostHeight - bubbleHeight - margin);
        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) aiRescueBubbleView.getLayoutParams();
        params.gravity = Gravity.TOP | Gravity.START;
        params.leftMargin = clampInt(left, margin, maxLeft);
        params.topMargin = clampInt(top, margin, maxTop);
        params.rightMargin = 0;
        params.bottomMargin = 0;
        aiRescueBubbleView.setLayoutParams(params);
        if (save) {
            saveAiRescueBubblePosition(params.leftMargin, params.topMargin);
        }
    }

    private void snapAndSaveAiRescueBubble() {
        if (aiRescuePageView == null || aiRescueBubbleView == null
            || !(aiRescueBubbleView.getLayoutParams() instanceof FrameLayout.LayoutParams)) {
            return;
        }
        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) aiRescueBubbleView.getLayoutParams();
        int bubbleWidth = aiRescueBubbleView.getWidth() > 0 ? aiRescueBubbleView.getWidth() : dp(52);
        int margin = dp(14);
        int center = params.leftMargin + bubbleWidth / 2;
        int snappedLeft = center < aiRescuePageView.getWidth() / 2
            ? margin
            : Math.max(margin, aiRescuePageView.getWidth() - bubbleWidth - margin);
        moveAiRescueBubbleTo(snappedLeft, params.topMargin, true);
    }

    private void saveAiRescueBubblePosition(int left, int top) {
        if (aiRescuePageView == null || aiRescueBubbleView == null) {
            return;
        }
        int hostWidth = aiRescuePageView.getWidth();
        int hostHeight = aiRescuePageView.getHeight();
        int bubbleWidth = aiRescueBubbleView.getWidth() > 0 ? aiRescueBubbleView.getWidth() : dp(52);
        int bubbleHeight = aiRescueBubbleView.getHeight() > 0 ? aiRescueBubbleView.getHeight() : dp(52);
        int edge = left + bubbleWidth / 2 < hostWidth / 2 ? BUBBLE_EDGE_START : BUBBLE_EDGE_END;
        int margin = dp(14);
        float yRatio = hostHeight <= bubbleHeight + margin * 2
            ? 0.16f
            : (float) (top - margin) / (float) Math.max(1, hostHeight - bubbleHeight - margin * 2);
        getOpenHouseHomePrefs().edit()
            .putInt(PREF_AI_RESCUE_BUBBLE_EDGE, edge)
            .putFloat(PREF_AI_RESCUE_BUBBLE_Y_RATIO, clampFloat(yRatio, 0f, 1f))
            .apply();
    }

    private LinearLayout createAiRescueFallbackView() {
        LinearLayout fallback = new LinearLayout(this);
        fallback.setOrientation(LinearLayout.VERTICAL);
        fallback.setGravity(Gravity.CENTER);
        fallback.setPadding(dp(22), dp(22), dp(22), dp(22));
        fallback.setBackgroundColor(ContextCompat.getColor(this, R.color.surface));

        TextView title = new TextView(this);
        title.setText("AI救援未连接");
        title.setTextColor(ContextCompat.getColor(this, R.color.textPrimary));
        title.setTextSize(20);
        title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
        fallback.addView(title);

        TextView body = new TextView(this);
        body.setText("没有连接到 " + getAiRescueUrl()
            + "。点击“启动救援”会直接在 Termux native 中启动原始 pi-web，不依赖 service-manager 或 Ubuntu。");
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
            compactButton("启动救援", v -> runPiWebRescueAction("start"), true),
            compactButton("刷新", v -> reloadAiRescueWebView(), true));
        fallback.addView(button("复制地址", v -> copyAiRescueUrlFromInput()));
        return fallback;
    }

    private void configureAiRescueWebView(WebView webView) {
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
                aiRescueLoadFailed = false;
                setAiRescueFallbackVisible(false);
                setAiRescueStatus("正在连接 AI救援：" + getAiRescueUrl());
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                if (!aiRescueLoadFailed) {
                    setAiRescueFallbackVisible(false);
                    setAiRescueStatus("AI救援已连接：" + url);
                    clearPendingDesktopOpenForNativePage(PAGE_AI_RESCUE);
                }
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && request != null && request.isForMainFrame()) {
                    showAiRescueUnavailable();
                }
            }

            @SuppressWarnings("deprecation")
            @Override
            public void onReceivedError(WebView view, int errorCode, String description, String failingUrl) {
                showAiRescueUnavailable();
            }
        });
    }

    private void reloadAiRescueWebView() {
        Integer port = readAiRescuePortFromInput(true);
        if (port == null) {
            return;
        }
        aiRescueLoadFailed = false;
        setAiRescueFallbackVisible(false);
        String url = getAiRescueUrl(port);
        setAiRescueStatus("正在刷新 AI救援：" + url);
        if (aiRescueWebView != null) {
            aiRescueWebView.loadUrl(url);
        }
    }

    private void showAiRescueUnavailable() {
        aiRescueLoadFailed = true;
        setAiRescueStatus("AI救援未连接：" + getAiRescueUrl());
        setAiRescueFallbackVisible(true);
        notifyDesktopOpenFailedForNativePage(PAGE_AI_RESCUE, findDesktopComponentForNativePage(PAGE_AI_RESCUE),
            "AI救援没有响应，可以启动救援或进入维护中心查看状态。");
    }

    private void setAiRescueFallbackVisible(boolean visible) {
        if (aiRescueFallbackView != null) {
            aiRescueFallbackView.setVisibility(visible ? View.VISIBLE : View.GONE);
        }
    }

    private void setAiRescueStatus(String text) {
        if (aiRescueStatusView != null) {
            aiRescueStatusView.setText(text);
        }
    }

    private void runPiWebRescueAction(String action) {
        Integer port = readAiRescuePortFromInput(true);
        if (port == null) {
            return;
        }
        String url = getAiRescueUrl(port);
        setAiRescueStatus("AI救援执行中：" + action + "\n地址：" + url);
        Toast.makeText(this, "AI救援执行中，请稍候。", Toast.LENGTH_SHORT).show();
        backgroundExecutor.execute(() -> {
            OpenHouseMaintainerRunner.Result result = runPiWebRescueMaintainerAction(action, port);
            runOnUiThread(() -> {
                String output = result.output == null ? "" : result.output.trim();
                if (output.length() > 700) {
                    output = output.substring(output.length() - 700);
                }
                setAiRescueStatus((result.isSuccess() ? "AI救援完成：" : "AI救援失败：")
                    + action
                    + "\n地址：" + url
                    + (isBlank(output) ? "" : "\n" + output));
                if (result.isSuccess() && ("start".equals(action) || "restart".equals(action))) {
                    reloadAiRescueWebView();
                } else if (result.isSuccess() && "stop".equals(action)) {
                    showAiRescueUnavailable();
                }
                Toast.makeText(this,
                    result.isSuccess() ? "AI救援已完成" : "AI救援失败，请查看日志。",
                    Toast.LENGTH_LONG).show();
            });
        });
    }

    private OpenHouseMaintainerRunner.Result runPiWebRescueMaintainerAction(String action, int port) {
        Map<String, String> environment = new HashMap<>();
        environment.put("OPENHOUSE_PI_WEB_RESCUE_ACTION", normalizeAiRescueAction(action));
        environment.put("OPENHOUSE_PI_WEB_RESCUE_PORT", Integer.toString(port));
        return new OpenHouseMaintainerRunner(this)
            .run(OpenHouseMaintainerRunner.Action.PI_WEB_RESCUE, port, environment);
    }

    private String normalizeAiRescueAction(String action) {
        if ("stop".equals(action) || "restart".equals(action) || "status".equals(action)) {
            return action;
        }
        return "start";
    }

    private void saveAiRescuePortFromInput(boolean rebuildPage) {
        Integer port = readAiRescuePortFromInput(true);
        if (port == null) {
            return;
        }
        Toast.makeText(this, "AI救援端口已保存：" + port, Toast.LENGTH_SHORT).show();
        if (rebuildPage) {
            renderedAiRescueUrl = null;
            renderPage();
        }
    }

    private void copyAiRescueUrlFromInput() {
        Integer port = readAiRescuePortFromInput(false);
        if (port == null) {
            return;
        }
        copyText("AI救援地址", getAiRescueUrl(port));
    }

    private Integer readAiRescuePortFromInput(boolean persist) {
        String value = aiRescuePortInput == null ? Integer.toString(getAiRescuePort()) : aiRescuePortInput.getText().toString();
        Integer port = parseAiRescuePort(value);
        if (port == null) {
            Toast.makeText(this, "端口必须是 " + MIN_AI_RESCUE_PORT + "-" + MAX_AI_RESCUE_PORT + " 的数字。", Toast.LENGTH_LONG).show();
            return null;
        }
        if (persist && port != getAiRescuePort()) {
            getOpenHouseHomePrefs().edit().putInt(PREF_AI_RESCUE_PORT, port).apply();
            renderedAiRescueUrl = null;
        }
        return port;
    }

    private Integer parseAiRescuePort(String value) {
        if (isBlank(value)) {
            return null;
        }
        try {
            int port = Integer.parseInt(value.trim());
            return isValidAiRescuePort(port) ? port : null;
        } catch (NumberFormatException e) {
            return null;
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
        cloudCliStatusView.setText(ccCodexTitle + " 地址：" + ccCodexUrl
            + "\n未安装时请先进入 pi-agent 完成安装配置；已安装后可在这里手动启动、停止或刷新。");
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
        body.setText("没有连接到 " + ccCodexUrl + "。如果这是首次使用，请先进入 pi-agent 完成 Codex、Claude Code 和 CloudCLI 的安装配置；如果已经配置好，可以从运行控制启动后刷新。");
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
            compactButton("进入 pi-agent", v -> openPiAgent(), true),
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
                    clearPendingDesktopOpenForNativePage(PAGE_AI);
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
        notifyDesktopOpenFailedForNativePage(PAGE_AI, findCcCodexComponent(),
            getCcCodexTitle() + " 没有响应，可以重启服务或进入服务控制查看状态。");
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
        String key = getDynamicWebPageKey(component);
        if (isBlank(key)) {
            return;
        }
        DynamicWebPageRecord record = dynamicWebPagePool.get(key);
        if (record == null || record.pageView == null || record.webView == null) {
            record = createDynamicWebPageRecord(key, component);
            dynamicWebPagePool.put(key, record);
        } else {
            record.component = component;
            if (record.statusView != null && record.webView.getUrl() == null && !record.loadFailed) {
                record.statusView.setText(component.title + " 地址：" + component.url);
            }
        }
        setActiveDynamicWebPage(record);
        markDynamicWebPageUsed(record);
        attachEmbeddedView(record.pageView);
        resumeActiveDynamicWebPage();
        if (record.webView != null && record.webView.getUrl() == null) {
            reloadDynamicWebView(record);
        }
        trimDynamicWebPagePool();
    }

    private DynamicWebPageRecord createDynamicWebPageRecord(String key, OpenHouseComponent component) {
        DynamicWebPageRecord record = new DynamicWebPageRecord(key, component);
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setBackgroundColor(ContextCompat.getColor(this, R.color.surface));

        record.statusView = new TextView(this);
        record.statusView.setText(component.title + " 地址：" + component.url);
        record.statusView.setTextColor(ContextCompat.getColor(this, R.color.textSecondary));
        record.statusView.setTextSize(12);
        record.statusView.setPadding(dp(12), dp(6), dp(12), dp(6));
        record.statusView.setBackgroundColor(ContextCompat.getColor(this, R.color.panel));
        page.addView(record.statusView, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT));

        FrameLayout browserHost = new FrameLayout(this);
        record.webView = new WebView(this);
        configureDynamicWebView(record);
        browserHost.addView(record.webView, new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT));

        record.fallbackView = createDynamicWebFallbackView(record);
        record.fallbackView.setVisibility(View.GONE);
        browserHost.addView(record.fallbackView, new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT));
        page.addView(browserHost, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            0,
            1));
        record.pageView = page;
        return record;
    }

    private LinearLayout createDynamicWebFallbackView(DynamicWebPageRecord record) {
        OpenHouseComponent component = record.component;
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
                    OpenHouseComponent current = record.component;
                    if (current != null && current.hasControlEntry()) {
                        openComponentControl(current);
                    } else {
                        openMaintenanceCenter();
                    }
                },
                true),
            compactButton("刷新", v -> reloadDynamicWebView(record), true));
        fallback.addView(button("复制地址", v -> {
            OpenHouseComponent current = record.component;
            if (current != null) {
                copyText(current.title, current.url);
            }
        }));
        return fallback;
    }

    private void configureDynamicWebView(DynamicWebPageRecord record) {
        WebView webView = record.webView;
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
                setDynamicWebLoadFailed(record, false);
                setDynamicWebFallbackVisible(record, false);
                setDynamicWebStatus(record, "正在连接：" + url);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                if (!record.loadFailed) {
                    setDynamicWebFallbackVisible(record, false);
                    setDynamicWebStatus(record, "已连接：" + url);
                    clearPendingDesktopOpenIfMatches(record.component);
                }
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && request != null && request.isForMainFrame()) {
                    showDynamicWebUnavailable(record);
                }
            }

            @SuppressWarnings("deprecation")
            @Override
            public void onReceivedError(WebView view, int errorCode, String description, String failingUrl) {
                showDynamicWebUnavailable(record);
            }
        });
    }

    private void reloadDynamicWebView() {
        reloadDynamicWebView(activeDynamicWebPage);
    }

    private void reloadDynamicWebView(DynamicWebPageRecord record) {
        if (record == null || record.component == null) {
            return;
        }
        markDynamicWebPageUsed(record);
        setDynamicWebLoadFailed(record, false);
        setDynamicWebFallbackVisible(record, false);
        setDynamicWebStatus(record, "正在刷新：" + record.component.url);
        if (record.webView != null) {
            record.webView.loadUrl(record.component.url);
        }
    }

    private void showDynamicWebUnavailable() {
        showDynamicWebUnavailable(activeDynamicWebPage);
    }

    private void showDynamicWebUnavailable(DynamicWebPageRecord record) {
        if (record == null || record.component == null) {
            return;
        }
        setDynamicWebLoadFailed(record, true);
        setDynamicWebStatus(record, "未连接：" + record.component.url);
        setDynamicWebFallbackVisible(record, true);
        notifyDesktopOpenFailedIfNeeded(record.component, "网页没有响应，可以重启服务或进入服务控制查看状态。");
    }

    private void setDynamicWebFallbackVisible(boolean visible) {
        setDynamicWebFallbackVisible(activeDynamicWebPage, visible);
    }

    private void setDynamicWebFallbackVisible(DynamicWebPageRecord record, boolean visible) {
        if (record != null && record.fallbackView != null) {
            record.fallbackView.setVisibility(visible ? View.VISIBLE : View.GONE);
        }
    }

    private void setDynamicWebStatus(String text) {
        setDynamicWebStatus(activeDynamicWebPage, text);
    }

    private void setDynamicWebStatus(DynamicWebPageRecord record, String text) {
        if (record != null && record.statusView != null) {
            record.statusView.setText(text);
        }
    }

    private String getDynamicWebPageKey(OpenHouseComponent component) {
        if (component == null || isBlank(component.id) || isBlank(component.url)) {
            return null;
        }
        return component.id.trim() + "\n" + component.url.trim();
    }

    private void setActiveDynamicWebPage(DynamicWebPageRecord record) {
        activeDynamicWebPage = record;
        if (record == null) {
            dynamicWebPageView = null;
            dynamicWebView = null;
            dynamicWebFallbackView = null;
            dynamicWebStatusView = null;
            dynamicWebComponent = null;
            dynamicWebLoadFailed = false;
            return;
        }
        dynamicWebPageView = record.pageView;
        dynamicWebView = record.webView;
        dynamicWebFallbackView = record.fallbackView;
        dynamicWebStatusView = record.statusView;
        dynamicWebComponent = record.component;
        dynamicWebLoadFailed = record.loadFailed;
    }

    private void clearActiveDynamicWebPage() {
        setActiveDynamicWebPage(null);
    }

    private void markDynamicWebPageUsed(DynamicWebPageRecord record) {
        if (record != null) {
            record.lastUsedOrder = ++dynamicWebUseSequence;
        }
    }

    private void resumeActiveDynamicWebPage() {
        if (activeDynamicWebPage != null && activeDynamicWebPage.webView != null) {
            activeDynamicWebPage.webView.onResume();
        }
    }

    private void setDynamicWebLoadFailed(DynamicWebPageRecord record, boolean loadFailed) {
        if (record == null) {
            return;
        }
        record.loadFailed = loadFailed;
        if (record == activeDynamicWebPage) {
            dynamicWebLoadFailed = loadFailed;
        }
    }

    private int getDynamicWebViewRetainCount() {
        int value = getOpenHouseHomePrefs().getInt(
            PREF_DYNAMIC_WEBVIEW_RETAIN_COUNT,
            DEFAULT_DYNAMIC_WEBVIEW_RETAIN_COUNT);
        return clampInt(value, MIN_DYNAMIC_WEBVIEW_RETAIN_COUNT, MAX_DYNAMIC_WEBVIEW_RETAIN_COUNT);
    }

    private void setDynamicWebViewRetainCount(int value) {
        int count = clampInt(value, MIN_DYNAMIC_WEBVIEW_RETAIN_COUNT, MAX_DYNAMIC_WEBVIEW_RETAIN_COUNT);
        getOpenHouseHomePrefs().edit()
            .putInt(PREF_DYNAMIC_WEBVIEW_RETAIN_COUNT, count)
            .apply();
        trimDynamicWebPagePool();
        Toast.makeText(this, "已设置保留 WebView 窗口：" + count + " 个", Toast.LENGTH_SHORT).show();
    }

    private void trimDynamicWebPagePool() {
        int retainCount = getDynamicWebViewRetainCount();
        int allowedCount = activeDynamicWebPage == null ? retainCount : Math.max(1, retainCount);
        while (dynamicWebPagePool.size() > allowedCount) {
            DynamicWebPageRecord oldest = null;
            for (DynamicWebPageRecord record : dynamicWebPagePool.values()) {
                if (record == activeDynamicWebPage) {
                    continue;
                }
                if (oldest == null || record.lastUsedOrder < oldest.lastUsedOrder) {
                    oldest = record;
                }
            }
            if (oldest == null) {
                break;
            }
            dynamicWebPagePool.remove(oldest.key);
            destroyDynamicWebPageRecord(oldest);
        }
    }

    private void releaseAllDynamicWebPages() {
        List<DynamicWebPageRecord> records = new ArrayList<>(dynamicWebPagePool.values());
        dynamicWebPagePool.clear();
        for (DynamicWebPageRecord record : records) {
            destroyDynamicWebPageRecord(record);
        }
        clearActiveDynamicWebPage();
    }

    private void destroyDynamicWebPageRecord(DynamicWebPageRecord record) {
        if (record == null) {
            return;
        }
        if (record.webView != null) {
            record.webView.onPause();
            record.webView.destroy();
            record.webView = null;
        }
        if (record.pageView != null && record.pageView.getParent() instanceof ViewGroup) {
            ((ViewGroup) record.pageView.getParent()).removeView(record.pageView);
        }
        record.pageView = null;
        record.fallbackView = null;
        record.statusView = null;
        if (record == activeDynamicWebPage) {
            clearActiveDynamicWebPage();
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
        } else if (PAGE_AI_RESCUE.equals(currentPage) && aiRescueWebView != null) {
            aiRescueWebView.onPause();
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

    private void releaseEmbeddedPagesExcept(String targetPage) {
        if (!shouldKeepPiWebPage(targetPage)) {
            releasePiWebPage();
        }
        if (!PAGE_AI_RESCUE.equals(targetPage)) {
            releaseAiRescuePage();
        }
        if (!PAGE_AI.equals(targetPage)) {
            releaseCloudCliPage();
        }
        if (!shouldKeepControlledBrowserView(targetPage)) {
            releaseControlledBrowserView();
        }
        if (!shouldKeepDynamicWebPage(targetPage)) {
            if (activeDynamicWebPage != null && activeDynamicWebPage.webView != null) {
                activeDynamicWebPage.webView.onPause();
            }
            clearActiveDynamicWebPage();
        }
        trimDynamicWebPagePool();
    }

    private boolean shouldKeepPiWebPage(String targetPage) {
        return PAGE_PI_WEB.equals(targetPage);
    }

    private boolean shouldKeepControlledBrowserView(String targetPage) {
        if (!PAGE_CONTROLLED_BROWSER.equals(targetPage)) {
            return false;
        }
        OpenHouseComponent component = findControlledBrowserComponent();
        return component == null || component.entryType != OpenHouseComponent.EntryType.WEBVIEW;
    }

    private boolean shouldKeepDynamicWebPage(String targetPage) {
        if (isComponentPage(targetPage)) {
            return true;
        }
        OpenHouseComponent component = null;
        if (PAGE_SMALLPHONE.equals(targetPage)) {
            component = findSmallPhoneComponent();
        } else if (PAGE_CONTROLLED_BROWSER.equals(targetPage)) {
            component = findControlledBrowserComponent();
        }
        return component != null
            && component.entryType == OpenHouseComponent.EntryType.WEBVIEW
            && !isBlank(component.url);
    }

    private void releasePiWebPage() {
        if (piWebView != null) {
            piWebView.onPause();
            piWebView.destroy();
            piWebView = null;
        }
        piWebPageView = null;
        piWebFallbackView = null;
        piWebStatusView = null;
        renderedPiWebUrl = null;
        piWebLoadFailed = false;
    }

    private void releaseAiRescuePage() {
        if (aiRescueWebView != null) {
            aiRescueWebView.onPause();
            aiRescueWebView.destroy();
            aiRescueWebView = null;
        }
        aiRescuePageView = null;
        aiRescueControlsView = null;
        aiRescueBubbleView = null;
        aiRescueFallbackView = null;
        aiRescueStatusView = null;
        aiRescuePortInput = null;
        renderedAiRescueUrl = null;
        aiRescueLoadFailed = false;
    }

    private void releaseCloudCliPage() {
        if (cloudCliWebView != null) {
            cloudCliWebView.onPause();
            cloudCliWebView.destroy();
            cloudCliWebView = null;
        }
        cloudCliPageView = null;
        cloudCliControlPanel = null;
        cloudCliFallbackView = null;
        cloudCliStatusView = null;
        renderedCloudCliUrl = null;
        cloudCliLoadFailed = false;
    }

    private void releaseControlledBrowserView() {
        if (controlledBrowserView == null) {
            return;
        }
        controlledBrowserView.setExternalNavigationHandler(null);
        if (controlledBrowserView.getParent() instanceof ViewGroup) {
            ((ViewGroup) controlledBrowserView.getParent()).removeView(controlledBrowserView);
        }
        controlledBrowserView = null;
    }

    private void releaseDynamicWebPage() {
        releaseAllDynamicWebPages();
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
            return;
        }
        if (component.entryType == OpenHouseComponent.EntryType.SERVICE_CONTROL) {
            if (component.hasControlEntry()) {
                openComponentControl(component);
            } else {
                openAllServiceControl();
            }
            return;
        }
        if (component.entryType == OpenHouseComponent.EntryType.ANDROID_ACTIVITY) {
            Intent intent = new Intent();
            intent.setClassName(this, component.activityClassName);
            openAndroidActivityComponent(component, intent);
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
        if (component.entryType == OpenHouseComponent.EntryType.SERVICE_CONTROL) {
            openComponent(component);
            return true;
        }
        if (component.entryType == OpenHouseComponent.EntryType.ANDROID_ACTIVITY
            && !isBlank(component.activityClassName)) {
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
                "这里是 OpenHouse 的主要入口。SmallPhone、pi-agent、cc/codex 是同级服务；应用在前台时会尽量保持核心服务运行。")
            .build());
        steps.add(GuidedTutorialOverlay.Step
            .requiredClick(
                "SmallPhone",
                "请点击 SmallPhone。它是小手机页面和兼容运行栈入口，后续也会由运行控制统一管理。",
                GuidedTutorialOverlay.targetById(root, R.id.buttonNavSmallPhone))
            .onTargetClick((overlay, step) -> {
                openSmallPhone();
                return true;
            })
            .build());
        steps.add(GuidedTutorialOverlay.Step
            .explanation(
                "SmallPhone 是一个主服务",
                "这里可以进入小手机侧的页面。普通用户不需要先理解后台服务，OpenHouse 会在前台自动保持核心服务可用。")
            .build());
        steps.add(GuidedTutorialOverlay.Step
            .requiredClick(
                "回到菜单",
                "请点击菜单，回到侧边栏继续认识 pi-agent。",
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
                "进入 pi-agent",
                "请点击 pi-agent。它和 SmallPhone、cc/codex 一样是侧边栏一级服务，也是首次配置助手。",
                GuidedTutorialOverlay.targetById(root, R.id.buttonNavPiAgent))
            .onTargetClick((overlay, step) -> {
                openPiAgent();
                return true;
            })
            .build());
        steps.add(GuidedTutorialOverlay.Step
            .explanation(
                "pi-agent 是配置起点",
                "如果 pi-agent 还没有模型配置，先按页面提示配置模型。之后点击“首次使用 OpenHouse”，让它读取 /root/openhouse/docs 并继续引导你。")
            .build());
        steps.add(GuidedTutorialOverlay.Step
            .requiredClick(
                "回到菜单",
                "请点击菜单，回到侧边栏继续认识 cc/codex。",
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
                "cc/codex",
                "请点击 cc/codex。它是 Claude Code / Codex 这类主力 AI 的统一入口，首次配置通常由 pi-agent 引导完成。",
                GuidedTutorialOverlay.targetById(root, R.id.buttonNavAi))
            .onTargetClick((overlay, step) -> {
                openBuiltinComponentOrFallback(findCcCodexComponent(), PAGE_AI);
                return true;
            })
            .build());
        steps.add(GuidedTutorialOverlay.Step
            .explanation(
                "cc/codex 后置配置",
                "如果这里还不可用，先回到 pi-agent 配置模型并安装 Claude Code、Codex 或 CloudCLI。配置好以后，这里就是常用 AI 入口。")
            .build());
        steps.add(GuidedTutorialOverlay.Step
            .requiredClick(
                "回到菜单",
                "请点击菜单，最后认识运行控制和全部退出入口。",
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
                "运行控制和全部退出",
                "请点一下运行控制入口。本步只认识入口，不会跳转。以后需要一键修复、关闭服务或全部退出，从这里进入。",
                GuidedTutorialOverlay.targetById(root, R.id.buttonNavServiceControl))
            .onTargetClick((overlay, step) -> true)
            .build());
        steps.add(GuidedTutorialOverlay.Step
            .explanation(
                "终端教学是单独入口",
                "首次教学到这里结束。普通使用不需要进入终端；需要命令行时，可以从菜单里的“终端教学”单独学习。")
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
        if (USAGE_STAGE_START_CORE.equals(stage)) {
            startCoreServicesTeachingStage();
            return true;
        }
        return false;
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
                "通常应用在前台会自动保持核心服务运行。如果页面提示未运行，可以点击启动核心服务，这一步会真实启动后台服务。",
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
                "核心服务已启动。请点击 pi-agent，接下来在 pi-agent 里按页面提示完成首次配置。",
                GuidedTutorialOverlay.targetById(root, R.id.buttonNavPiAgent))
            .onTargetClick((overlay, step) -> {
                openPiAgent();
                return true;
            })
            .build());
        steps.add(GuidedTutorialOverlay.Step
            .explanation(
                "在 pi-agent 里继续",
                "进入后请按 pi-agent 页面提示操作：点侧边栏三横线，项目选 /root，配置模型，新建会话，选择“首次使用 OpenHouse”。如果要配置 Claude Code，第一次消息把 URL、key/token 和模型 id 发给 AI。")
            .build());
        steps.add(GuidedTutorialOverlay.Step
            .explanation(
                "配置完成后的检查",
                "pi-agent 会按 /root/openhouse/docs 的文档引导安装和测通 Codex、Claude Code、CloudCLI 或 Hermes。cc/codex 未安装时会提示先回 pi-agent 完成安装配置。")
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
            case PAGE_DESKTOP:
            case "openhouse-desktop":
                return PAGE_DESKTOP;
            case PAGE_HOME:
            case "openhouse-home":
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
            case "ai-rescue":
            case "pi-web-rescue":
            case "rescue":
                return PAGE_AI_RESCUE;
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

    private String getAiRescueUrl() {
        return getAiRescueUrl(getAiRescuePort());
    }

    private String getAiRescueUrl(int port) {
        return OpenHousePiWebRescueController.getInstance(this).getLoopbackUrl(port);
    }

    private int getAiRescuePort() {
        int port = getOpenHouseHomePrefs().getInt(
            PREF_AI_RESCUE_PORT,
            OpenHousePiWebRescueController.DEFAULT_PORT);
        return isValidAiRescuePort(port) ? port : OpenHousePiWebRescueController.DEFAULT_PORT;
    }

    private boolean isValidAiRescuePort(int port) {
        return port >= MIN_AI_RESCUE_PORT && port <= MAX_AI_RESCUE_PORT;
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
        DesktopLaunchTarget target = getConfiguredDesktopLaunchTarget();
        if (!openDesktopLaunchTarget(target)) {
            selectPage(PAGE_DESKTOP);
        }
    }

    private String getBackTargetPage() {
        String mode = getStartPageMode();
        if (START_MODE_HOME.equals(mode)) {
            return PAGE_HOME;
        }
        return PAGE_DESKTOP;
    }

    private String getLaunchPage() {
        String mode = getStartPageMode();
        if (START_MODE_HOME.equals(mode)) {
            return PAGE_HOME;
        }
        if (START_MODE_LAST.equals(mode)) {
            String lastPage = getOpenHouseHomePrefs().getString(PREF_LAST_PAGE, PAGE_DESKTOP);
            return isLaunchCandidate(lastPage) ? lastPage : PAGE_DESKTOP;
        }
        if (START_MODE_PAGE.equals(mode)) {
            String page = getConfiguredHomePage();
            return isLaunchCandidate(page) ? page : PAGE_DESKTOP;
        }
        return PAGE_DESKTOP;
    }

    private String getStartPageMode() {
        String mode = getOpenHouseHomePrefs().getString(PREF_START_PAGE_MODE, START_MODE_DESKTOP);
        if (START_MODE_HOME.equals(mode)
            || START_MODE_LAST.equals(mode)
            || START_MODE_PAGE.equals(mode)) {
            return mode;
        }
        return START_MODE_DESKTOP;
    }

    private void setStartPageMode(String mode) {
        String normalizedMode = START_MODE_HOME.equals(mode)
            || START_MODE_LAST.equals(mode)
            || START_MODE_PAGE.equals(mode)
            ? mode
            : START_MODE_DESKTOP;
        getOpenHouseHomePrefs().edit()
            .putString(PREF_START_PAGE_MODE, normalizedMode)
            .apply();
        if (START_MODE_HOME.equals(normalizedMode)) {
            getDesktopLayoutStore().setDefaultTarget(getDesktopAppsForDisplay(), DesktopLaunchTarget.page(PAGE_HOME));
        } else if (START_MODE_LAST.equals(normalizedMode)) {
            getDesktopLayoutStore().setDefaultLastExited();
        } else if (START_MODE_DESKTOP.equals(normalizedMode)) {
            getDesktopLayoutStore().setDefaultDesktop();
        }
        updateHomePreferenceViews();
        if (PAGE_ADVANCED.equals(currentPage) || PAGE_DESKTOP.equals(currentPage)) {
            renderPage();
        }
        Toast.makeText(this, "默认打开已设为：" + getLaunchModeDisplayTitle(), Toast.LENGTH_SHORT).show();
    }

    private void rememberLastOpenHousePage(String page) {
        if (isLaunchCandidate(page)) {
            getOpenHouseHomePrefs().edit().putString(PREF_LAST_PAGE, page).apply();
            getDesktopLayoutStore().recordLastExitedTarget(pageToDesktopLaunchTarget(page));
        }
    }

    private String getConfiguredHomePage() {
        String localPage = getOpenHouseHomePrefs().getString(PREF_HOME_PAGE, null);
        if (isLaunchCandidate(localPage)) {
            return localPage;
        }
        String configuredTarget = readConfiguredHomeTarget();
        String configured = homeTargetToPage(configuredTarget);
        if (isLaunchCandidate(configured)) {
            return configured;
        }
        return PAGE_DESKTOP;
    }

    private String firstVisibleHomePage() {
        if (isLaunchCandidate(PAGE_PI_WEB)) {
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
        if (!isLaunchCandidate(currentPage)) {
            Toast.makeText(this, "当前页面不能设为默认打开。", Toast.LENGTH_SHORT).show();
            updateHomePreferenceViews();
            return;
        }
        setLaunchPage(currentPage);
    }

    private void setLaunchPage(String page) {
        if (!isLaunchCandidate(page)) {
            Toast.makeText(this, "这个入口暂时不能设为默认打开。", Toast.LENGTH_SHORT).show();
            return;
        }
        if (PAGE_DESKTOP.equals(page)) {
            setStartPageMode(START_MODE_DESKTOP);
            return;
        }
        if (PAGE_HOME.equals(page)) {
            setStartPageMode(START_MODE_HOME);
            return;
        }
        DesktopLaunchTarget target = pageToDesktopLaunchTarget(page);
        getDesktopLayoutStore().setDefaultTarget(getDesktopAppsForDisplay(), target);
        getOpenHouseHomePrefs().edit()
            .putString(PREF_START_PAGE_MODE, START_MODE_PAGE)
            .putString(PREF_HOME_PAGE, page)
            .apply();
        updateHomePreferenceViews();
        String message = "默认打开已设为：" + getHomeDisplayTitle(page);
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    private void setLaunchAppAsDefault(String appId) {
        DesktopLaunchTarget target = getDesktopLayoutStore().setDefaultApp(getDesktopAppsForDisplay(), appId);
        String page = launchTargetToPage(target);
        SharedPreferences.Editor editor = getOpenHouseHomePrefs().edit()
            .putString(PREF_START_PAGE_MODE, START_MODE_PAGE);
        if (!isBlank(page)) {
            editor.putString(PREF_HOME_PAGE, page);
        } else {
            editor.putString(PREF_HOME_PAGE, PAGE_DESKTOP);
        }
        editor.apply();
        updateHomePreferenceViews();
    }

    private DesktopLaunchTarget getConfiguredDesktopLaunchTarget() {
        DesktopLayoutState state = getDesktopLayoutStore().load(getDesktopAppsForDisplay());
        if (state != null && state.defaultTarget != null && !state.defaultTarget.isDesktop()) {
            return state.resolvedDefaultTarget;
        }
        String legacyMode = getStartPageMode();
        if (!START_MODE_DESKTOP.equals(legacyMode)) {
            return pageToDesktopLaunchTarget(getLaunchPage());
        }
        return state == null ? DesktopLaunchTarget.desktop() : state.resolvedDefaultTarget;
    }

    private boolean openDesktopLaunchTarget(DesktopLaunchTarget target) {
        DesktopLaunchTarget cleanTarget = target == null ? DesktopLaunchTarget.desktop() : target;
        if (cleanTarget.isDesktop()) {
            selectPage(PAGE_DESKTOP);
            return true;
        }
        if (cleanTarget.isApp()) {
            DesktopLayoutEntry entry = findDesktopLayoutEntry(cleanTarget.value);
            if (entry != null && entry.component != null) {
                openDesktopApp(entry.component);
                return true;
            }
            return false;
        }
        if (cleanTarget.isPage()) {
            String page = resolveNativePage(cleanTarget.value);
            if (isLaunchCandidate(page)) {
                selectPage(page);
                return true;
            }
        }
        return false;
    }

    private DesktopLaunchTarget pageToDesktopLaunchTarget(String page) {
        if (PAGE_DESKTOP.equals(page)) {
            return DesktopLaunchTarget.desktop();
        }
        if (isComponentPage(page)) {
            return DesktopLaunchTarget.app(extractComponentId(page));
        }
        OpenHouseComponent component = findDesktopComponentForNativePage(page);
        if (component != null && !isBlank(component.id)) {
            return DesktopLaunchTarget.app(component.id);
        }
        return DesktopLaunchTarget.page(page);
    }

    private String launchTargetToPage(DesktopLaunchTarget target) {
        if (target == null || target.isDesktop()) {
            return PAGE_DESKTOP;
        }
        if (target.isPage()) {
            String page = resolveNativePage(target.value);
            return isLaunchCandidate(page) ? page : PAGE_DESKTOP;
        }
        if (target.isApp()) {
            DesktopLayoutEntry entry = findDesktopLayoutEntry(target.value);
            if (entry == null || entry.component == null) {
                return PAGE_DESKTOP;
            }
            OpenHouseComponent component = entry.component;
            if (component.entryType == OpenHouseComponent.EntryType.NATIVE_PAGE) {
                String page = resolveNativePage(component.nativePage);
                return isLaunchCandidate(page) ? page : PAGE_DESKTOP;
            }
            if (component.entryType == OpenHouseComponent.EntryType.WEBVIEW) {
                return PAGE_COMPONENT_PREFIX + component.id;
            }
        }
        return PAGE_DESKTOP;
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
        if (PAGE_DESKTOP.equals(normalized) || "openhouse-desktop".equals(normalized)) {
            return PAGE_DESKTOP;
        }
        if (PAGE_HOME.equals(normalized) || "openhouse-home".equals(normalized)) {
            return PAGE_HOME;
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
        if ("ai-rescue".equals(normalized)
            || "pi-web-rescue".equals(normalized)
            || "rescue".equals(normalized)) {
            return PAGE_AI_RESCUE;
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
        if (PAGE_AI_RESCUE.equals(page)) {
            return "ai-rescue";
        }
        if (PAGE_DESKTOP.equals(page)) {
            return PAGE_DESKTOP;
        }
        if (PAGE_HOME.equals(page)) {
            return PAGE_HOME;
        }
        if (isComponentPage(page)) {
            return extractComponentId(page);
        }
        return null;
    }

    private boolean isLaunchCandidate(String page) {
        if (isBlank(page)) {
            return false;
        }
        if (PAGE_DESKTOP.equals(page) || PAGE_HOME.equals(page)) {
            return true;
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
        if (PAGE_AI_RESCUE.equals(page)) {
            return true;
        }
        if (isComponentPage(page)) {
            OpenHouseComponent component = findDynamicComponent(extractComponentId(page));
            return component != null && component.hasEntry() && isComponentVisible(component);
        }
        return false;
    }

    private void updateHomePreferenceViews() {
        String launchPage = launchTargetToPage(getConfiguredDesktopLaunchTarget());
        if (homeStatusView != null) {
            homeStatusView.setText("默认打开：" + getLaunchModeDisplayTitle());
        }
        if (setCurrentHomeButton != null) {
            boolean canSet = isLaunchCandidate(currentPage);
            setCurrentHomeButton.setEnabled(canSet);
            setCurrentHomeButton.setText(canSet && currentPage.equals(launchPage) ? "已是默认打开" : "设当前页为默认打开");
        }
    }

    private String getHomeDisplayTitle(String page) {
        if (PAGE_DESKTOP.equals(page)) {
            return getDesktopComponentTitle("openhouse-desktop", "桌面");
        }
        if (PAGE_HOME.equals(page)) {
            return getDesktopNativePageTitle(PAGE_HOME, "菜单总览");
        }
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
        if (PAGE_AI_RESCUE.equals(page)) {
            return "AI救援";
        }
        if (isComponentPage(page)) {
            OpenHouseComponent component = findDynamicComponent(extractComponentId(page));
            if (component != null && !isBlank(component.title)) {
                return component.title;
            }
        }
        return "桌面";
    }

    private String getLaunchModeDisplayTitle() {
        DesktopLayoutState state = getDesktopLayoutStore().load(getDesktopAppsForDisplay());
        DesktopLaunchTarget target = state == null ? DesktopLaunchTarget.desktop() : state.defaultTarget;
        DesktopLaunchTarget resolved = state == null ? DesktopLaunchTarget.desktop() : state.resolvedDefaultTarget;
        if (target != null && target.isLastExited()) {
            return "上次退出页（当前：" + getLaunchTargetDisplayTitle(resolved) + "）";
        }
        if (target != null && !target.isDesktop()) {
            return getLaunchTargetDisplayTitle(resolved);
        }
        return "桌面";
    }

    private String getLaunchTargetDisplayTitle(DesktopLaunchTarget target) {
        if (target == null || target.isDesktop()) {
            return "桌面";
        }
        if (target.isApp()) {
            DesktopLayoutEntry entry = findDesktopLayoutEntry(target.value);
            if (entry != null && !isBlank(entry.title)) {
                return entry.title;
            }
            return "桌面";
        }
        if (target.isPage()) {
            return getHomeDisplayTitle(resolveNativePage(target.value));
        }
        return "桌面";
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

    private void renderDesktopPage() {
        List<OpenHouseComponent> apps = getDesktopAppsForDisplay();
        desktopLayoutState = getDesktopLayoutStore().merge(apps);
        List<DesktopUiEntry> entries = toDesktopUiEntries(desktopLayoutState);
        if (entries.isEmpty()) {
            LinearLayout empty = panel();
            addTitle(empty, "桌面", 19);
            addBody(empty, "没有可显示的桌面应用。可以从菜单进入" + getHomeDisplayTitle(PAGE_HOME) + "或高级设置检查组件注册。");
            empty.addView(button("进入" + getHomeDisplayTitle(PAGE_HOME), v -> selectPage(PAGE_HOME)));
            contentView.addView(empty);
            return;
        }

        desktopView = new OpenHouseDesktopView(this);
        desktopView.setGridSize(DESKTOP_GRID_COLUMNS, DESKTOP_GRID_ROWS);
        desktopView.setCallbacks(new OpenHouseDesktopView.Callbacks() {
            @Override
            public void onOpen(DesktopUiEntry entry) {
                openDesktopUiEntry(entry);
            }

            @Override
            public void onEdit(DesktopUiEntry entry) {
                enterDesktopEditMode();
                showDesktopEditDialog(entry);
            }

            @Override
            public void onReorder(List<DesktopUiEntry> orderedEntries, DesktopUiEntry movedEntry, int fromPosition, int toPosition) {
                persistDesktopMove(orderedEntries, movedEntry, fromPosition, toPosition);
            }

            @Override
            public void onPageChanged(int pageIndex, int pageCount) {
                if (!bindingDesktopView) {
                    desktopLayoutState = getDesktopLayoutStore().saveCurrentPage(getDesktopAppsForDisplay(), pageIndex);
                }
            }

            @Override
            public void onBlankLongPress() {
                enterDesktopEditMode();
            }
        });
        bindDesktopView(entries, desktopLayoutState.currentPage);
        contentView.addView(desktopView, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT));
    }

    private List<OpenHouseComponent> getDesktopAppsForDisplay() {
        List<OpenHouseComponent> source = getDesktopComponentSource();
        List<OpenHouseComponent> out = new ArrayList<>();
        for (OpenHouseComponent app : source) {
            if (app == null
                || "openhouse-desktop".equals(normalizeId(app.id))) {
                continue;
            }
            out.add(app);
        }
        addOperitDesktopComponentIfAvailable(out);
        return out;
    }

    private DesktopLayoutStore getDesktopLayoutStore() {
        if (desktopLayoutStore == null) {
            desktopLayoutStore = new DesktopLayoutStore(this);
        }
        return desktopLayoutStore;
    }

    private List<DesktopUiEntry> toDesktopUiEntries(DesktopLayoutState state) {
        if (state == null || state.entries == null || state.entries.isEmpty()) {
            return Collections.emptyList();
        }
        List<DesktopUiEntry> entries = new ArrayList<>();
        for (DesktopLayoutEntry entry : state.entries) {
            if (entry == null) {
                continue;
            }
            entries.add(DesktopUiEntry.builder()
                .id(entry.id)
                .title(entry.title)
                .subtitle(entry.subtitle)
                .iconLabel(entry.iconLabel)
                .iconKey(entry.iconKey)
                .order(entry.position)
                .enabled(entry.component != null && (entry.component.hasEntry() || OPERIT_DESKTOP_APP_ID.equals(entry.id)))
                .build());
        }
        return entries;
    }

    private void bindDesktopView(List<DesktopUiEntry> entries, int currentPage) {
        if (desktopView == null) {
            return;
        }
        bindingDesktopView = true;
        try {
            desktopView.setEntries(entries);
            desktopView.setCurrentPage(currentPage, false);
        } finally {
            bindingDesktopView = false;
        }
    }

    private void refreshDesktopViewFromState(DesktopLayoutState state) {
        desktopLayoutState = state == null
            ? getDesktopLayoutStore().load(getDesktopAppsForDisplay())
            : state;
        bindDesktopView(toDesktopUiEntries(desktopLayoutState), desktopLayoutState.currentPage);
        updateHomePreferenceViews();
    }

    private void enterDesktopEditMode() {
        if (desktopView != null && !desktopView.isEditMode()) {
            desktopView.setEditMode(true);
            Toast.makeText(this, "桌面编辑模式：点击图标可改名、改图标或隐藏。", Toast.LENGTH_SHORT).show();
        }
    }

    private void openDesktopUiEntry(DesktopUiEntry entry) {
        DesktopLayoutEntry layoutEntry = findDesktopLayoutEntry(entry == null ? null : entry.id);
        if (layoutEntry == null || layoutEntry.component == null) {
            Toast.makeText(this, "应用不存在或已被移除。", Toast.LENGTH_SHORT).show();
            return;
        }
        openDesktopApp(layoutEntry.component);
    }

    private void persistDesktopMove(List<DesktopUiEntry> orderedEntries, DesktopUiEntry movedEntry, int fromPosition, int toPosition) {
        String appId = movedEntry == null ? "" : movedEntry.id;
        int targetSlot = Math.max(0, toPosition);
        DesktopLayoutState state = persistDesktopMoveToSparseSlot(appId, targetSlot);
        if (state != null) {
            refreshDesktopViewFromState(state);
            return;
        }
        if (!isBlank(appId)) {
            state = getDesktopLayoutStore().move(getDesktopAppsForDisplay(), appId, targetSlot);
            refreshDesktopViewFromState(state);
            return;
        }
        persistDesktopReorder(orderedEntries);
    }

    private DesktopLayoutState persistDesktopMoveToSparseSlot(String appId, int absoluteSlot) {
        if (isBlank(appId)) {
            return null;
        }
        List<OpenHouseComponent> apps = getDesktopAppsForDisplay();
        int targetPage = absoluteSlot / DESKTOP_PAGE_SIZE;
        int targetSlot = absoluteSlot % DESKTOP_PAGE_SIZE;
        DesktopLayoutState state = invokeDesktopLayoutStoreState(
            "moveToPageSlot",
            new Class<?>[] { List.class, String.class, int.class, int.class },
            apps,
            appId,
            targetPage,
            targetSlot);
        if (state != null) {
            return state;
        }
        state = invokeDesktopLayoutStoreState(
            "moveToPageAndSlot",
            new Class<?>[] { List.class, String.class, int.class, int.class },
            apps,
            appId,
            targetPage,
            targetSlot);
        if (state != null) {
            return state;
        }
        state = invokeDesktopLayoutStoreState(
            "moveToSlot",
            new Class<?>[] { List.class, String.class, int.class },
            apps,
            appId,
            absoluteSlot);
        if (state != null) {
            return state;
        }
        state = invokeDesktopLayoutStoreState(
            "moveToAbsoluteSlot",
            new Class<?>[] { List.class, String.class, int.class },
            apps,
            appId,
            absoluteSlot);
        if (state != null) {
            return state;
        }
        return invokeDesktopLayoutStoreState(
            "moveToPosition",
            new Class<?>[] { List.class, String.class, int.class },
            apps,
            appId,
            absoluteSlot);
    }

    private DesktopLayoutState invokeDesktopLayoutStoreState(String methodName, Class<?>[] parameterTypes, Object... args) {
        try {
            Method method = DesktopLayoutStore.class.getMethod(methodName, parameterTypes);
            Object result = method.invoke(getDesktopLayoutStore(), args);
            return result instanceof DesktopLayoutState ? (DesktopLayoutState) result : null;
        } catch (NoSuchMethodException e) {
            return null;
        } catch (Exception e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Desktop layout store call failed: " + methodName, e);
            return null;
        }
    }

    private void persistDesktopReorder(List<DesktopUiEntry> orderedEntries) {
        List<String> orderedIds = new ArrayList<>();
        if (orderedEntries != null) {
            for (DesktopUiEntry entry : orderedEntries) {
                if (entry != null && !isBlank(entry.id)) {
                    orderedIds.add(entry.id);
                }
            }
        }
        DesktopLayoutState state = getDesktopLayoutStore().reorder(getDesktopAppsForDisplay(), orderedIds);
        refreshDesktopViewFromState(state);
    }

    private DesktopLayoutEntry findDesktopLayoutEntry(String appId) {
        String id = normalizeId(appId);
        if (id.isEmpty()) {
            return null;
        }
        DesktopLayoutState state = desktopLayoutState == null
            ? getDesktopLayoutStore().load(getDesktopAppsForDisplay())
            : desktopLayoutState;
        DesktopLayoutEntry entry = state.find(id);
        if (entry != null) {
            return entry;
        }
        return getDesktopLayoutStore().load(getDesktopAppsForDisplay()).find(id);
    }

    private void addOperitDesktopComponentIfAvailable(List<OpenHouseComponent> out) {
        if (!OperitHomeIntegration.isAvailable() || out == null || hasDesktopComponent(out, OPERIT_DESKTOP_APP_ID)) {
            return;
        }
        OpenHouseComponent component = createOperitDesktopComponent();
        if (component != null) {
            out.add(component);
        }
    }

    private boolean hasDesktopComponent(List<OpenHouseComponent> components, String id) {
        String normalized = normalizeId(id);
        if (components == null || normalized.isEmpty()) {
            return false;
        }
        for (OpenHouseComponent component : components) {
            if (component != null && normalized.equals(normalizeId(component.id))) {
                return true;
            }
        }
        return false;
    }

    private OpenHouseComponent createOperitDesktopComponent() {
        try {
            Constructor<OpenHouseComponent> constructor = OpenHouseComponent.class.getDeclaredConstructor(
                String.class,
                String.class,
                String.class,
                String.class,
                int.class,
                String.class,
                String.class,
                int.class,
                boolean.class,
                boolean.class,
                boolean.class,
                OpenHouseComponent.EntryType.class,
                String.class,
                String.class,
                String.class,
                String.class,
                boolean.class,
                boolean.class,
                boolean.class,
                boolean.class,
                String.class,
                List.class,
                List.class);
            constructor.setAccessible(true);
            return constructor.newInstance(
                OPERIT_DESKTOP_APP_ID,
                "AI朋友 Help",
                "Operit",
                "AI",
                45,
                "operit",
                "AI",
                45,
                false,
                true,
                true,
                OpenHouseComponent.EntryType.ANDROID_ACTIVITY,
                "",
                "",
                "",
                "控制",
                false,
                true,
                false,
                true,
                "withOperit",
                Collections.emptyList(),
                Collections.emptyList());
        } catch (Exception e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Unable to create Operit desktop component", e);
            return null;
        }
    }

    private List<OpenHouseComponent> getDesktopComponentSource() {
        if (dynamicRegistryResult != null && dynamicRegistryResult.desktopComponents != null) {
            return dynamicRegistryResult.desktopComponents;
        }
        if (desktopComponents != null && !desktopComponents.isEmpty()) {
            return desktopComponents;
        }
        return OpenHouseComponentRegistry.loadDesktopApps();
    }

    private String getDesktopComponentTitle(String id, String fallback) {
        OpenHouseComponent component = findDesktopComponentById(id);
        return component != null && !isBlank(component.title) ? component.title : fallback;
    }

    private String getDesktopNativePageTitle(String page, String fallback) {
        OpenHouseComponent component = findDesktopComponentForNativePage(page);
        return component != null && !isBlank(component.title) ? component.title : fallback;
    }

    private OpenHouseComponent findDesktopComponentById(String id) {
        String normalized = normalizeId(id);
        for (OpenHouseComponent component : getDesktopAppsForDisplay()) {
            if (component != null && normalized.equals(normalizeId(component.id))) {
                return component;
            }
        }
        return null;
    }

    private OpenHouseComponent findDesktopComponentForNativePage(String page) {
        String resolvedPage = resolveNativePage(page);
        for (OpenHouseComponent component : getDesktopComponentSource()) {
            if (component == null || component.entryType != OpenHouseComponent.EntryType.NATIVE_PAGE) {
                continue;
            }
            if (resolvedPage != null && resolvedPage.equals(resolveNativePage(component.nativePage))) {
                return component;
            }
        }
        return null;
    }

    private void showDesktopEditDialog(DesktopUiEntry uiEntry) {
        DesktopLayoutEntry entry = findDesktopLayoutEntry(uiEntry == null ? null : uiEntry.id);
        if (entry == null || entry.component == null) {
            Toast.makeText(this, "应用不存在或已被移除。", Toast.LENGTH_SHORT).show();
            return;
        }

        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(18), dp(12), dp(18), dp(4));
        addTitle(panel, "编辑桌面 App", 19);
        addStatusRow(panel, "应用", entry.originalTitle);

        TextView titleLabel = desktopEditLabel("显示名称");
        panel.addView(titleLabel, desktopEditLabelParams());
        EditText titleInput = desktopEditInput(entry.hasTitleOverride() ? entry.title : "", entry.originalTitle);
        panel.addView(titleInput, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(48)));

        TextView iconLabel = desktopEditLabel("图标标签");
        panel.addView(iconLabel, desktopEditLabelParams());
        EditText iconLabelInput = desktopEditInput(entry.hasIconOverride() ? entry.iconLabel : "", entry.iconLabel);
        panel.addView(iconLabelInput, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(48)));

        TextView iconKeyLabel = desktopEditLabel("图标 key");
        panel.addView(iconKeyLabel, desktopEditLabelParams());
        EditText iconKeyInput = desktopEditInput(entry.hasIconOverride() ? entry.iconKey : "", entry.iconKey);
        panel.addView(iconKeyInput, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(48)));

        final AlertDialog[] dialogHolder = new AlertDialog[1];
        addButtonRow(panel,
            compactButton("设为默认入口", v -> {
                setLaunchAppAsDefault(entry.id);
                Toast.makeText(this, "默认打开已设为：" + entry.title, Toast.LENGTH_SHORT).show();
            }, true),
            compactButton("查看状态", v -> {
                dismissDialog(dialogHolder);
                if (OPERIT_DESKTOP_APP_ID.equals(entry.id)) {
                    showOperitStatusSheet("");
                } else {
                    showDesktopStatusSheet(entry.component, "");
                }
            }, true));
        addButtonRow(panel,
            compactButton("隐藏", v -> {
                DesktopLayoutState state = getDesktopLayoutStore().hide(getDesktopAppsForDisplay(), entry.id, true);
                refreshDesktopViewFromState(state);
                dismissDialog(dialogHolder);
                Toast.makeText(this, "已从桌面隐藏：" + entry.title, Toast.LENGTH_SHORT).show();
            }, true),
            compactButton("重置", v -> {
                DesktopLayoutState state = getDesktopLayoutStore().resetApp(getDesktopAppsForDisplay(), entry.id);
                refreshDesktopViewFromState(state);
                dismissDialog(dialogHolder);
                Toast.makeText(this, "已重置：" + entry.originalTitle, Toast.LENGTH_SHORT).show();
            }, true));

        ScrollView scrollView = new ScrollView(this);
        scrollView.addView(panel);
        AlertDialog dialog = new AlertDialog.Builder(this)
            .setView(scrollView)
            .setPositiveButton("保存", null)
            .setNegativeButton("取消", null)
            .create();
        dialogHolder[0] = dialog;
        dialog.setOnShowListener(d -> {
            Button saveButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            if (saveButton != null) {
                saveButton.setOnClickListener(v -> {
                    String title = titleInput.getText() == null ? "" : titleInput.getText().toString();
                    String icon = iconLabelInput.getText() == null ? "" : iconLabelInput.getText().toString();
                    String iconKey = iconKeyInput.getText() == null ? "" : iconKeyInput.getText().toString();
                    DesktopLayoutState state = getDesktopLayoutStore().updateTitleOverride(
                        getDesktopAppsForDisplay(),
                        entry.id,
                        title);
                    state = getDesktopLayoutStore().updateIconOverride(
                        getDesktopAppsForDisplay(),
                        entry.id,
                        DesktopIconOverride.of(iconKey, icon, ""));
                    refreshDesktopViewFromState(state);
                    dialog.dismiss();
                });
            }
        });
        dialog.show();
    }

    private TextView desktopEditLabel(String text) {
        TextView label = new TextView(this);
        label.setText(text);
        label.setTextColor(ContextCompat.getColor(this, R.color.textSecondary));
        label.setTextSize(13);
        return label;
    }

    private LinearLayout.LayoutParams desktopEditLabelParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT);
        params.setMargins(0, dp(12), 0, 0);
        return params;
    }

    private EditText desktopEditInput(String text, String hint) {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setText(text);
        input.setHint(hint);
        input.setTextColor(ContextCompat.getColor(this, R.color.textPrimary));
        input.setHintTextColor(ContextCompat.getColor(this, R.color.textSecondary));
        return input;
    }

    private void openDesktopApp(OpenHouseComponent app) {
        if (app == null) {
            Toast.makeText(this, "应用不存在。", Toast.LENGTH_SHORT).show();
            return;
        }
        getDesktopLayoutStore().recordLastExitedTarget(DesktopLaunchTarget.app(app.id));
        if (OPERIT_DESKTOP_APP_ID.equals(app.id)) {
            openAiFriendHelp();
            return;
        }
        if (desktopAppLauncher == null) {
            desktopAppLauncher = new DesktopAppLauncher(this);
        }
        DesktopAppLaunchIntent launchIntent = desktopAppLauncher.buildOpenIntent(app);
        if (launchIntent == null || !launchIntent.launchable) {
            showDesktopStatusSheet(app, launchIntent == null ? "无法打开应用。" : launchIntent.message);
            return;
        }
        try {
            switch (launchIntent.kind) {
                case WEBVIEW:
                    pendingDesktopOpenAppId = app.id;
                    openComponent(app);
                    return;
                case NATIVE_PAGE:
                    openNativeDesktopPage(app, launchIntent.nativePage);
                    return;
                case TERMINAL:
                    openTerminal(false);
                    return;
                case SERVICE_CONTROL:
                    if (app.hasControlEntry()) {
                        openComponentControl(app);
                    } else {
                        openAllServiceControl();
                    }
                    return;
                case ANDROID_ACTIVITY:
                    openAndroidActivityComponent(app, launchIntent.intent);
                    return;
                case STATUS_PANEL:
                case UNSUPPORTED:
                default:
                    showDesktopStatusSheet(app, launchIntent.message);
            }
        } catch (Exception e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Desktop app launch failed: " + app.id, e);
            showDesktopStatusSheet(app, "打开失败：" + safeMessage(e));
        }
    }

    private void openNativeDesktopPage(OpenHouseComponent app, String nativePage) {
        String page = resolveNativePage(nativePage);
        if (isBlank(page)) {
            showDesktopStatusSheet(app, "不支持的原生页面：" + nativePage);
            return;
        }
        if (isWebBackedNativePage(page)) {
            pendingDesktopOpenAppId = app == null ? null : app.id;
        }
        selectPage(page);
    }

    private void openAndroidActivityComponent(OpenHouseComponent app, Intent intent) {
        if (intent == null) {
            showDesktopStatusSheet(app, "Android Activity 入口不可用。");
            return;
        }
        try {
            if (drawerLayout != null) {
                drawerLayout.closeDrawer(GravityCompat.START);
            }
            ActivityUtils.startActivity(this, intent);
        } catch (Exception e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Android activity desktop launch failed: " + app.id, e);
            showDesktopStatusSheet(app, "Activity 打开失败：" + safeMessage(e));
        }
    }

    private void showDesktopStatusSheet(OpenHouseComponent app, String leadingMessage) {
        if (desktopAppLauncher == null) {
            desktopAppLauncher = new DesktopAppLauncher(this);
        }
        DesktopAppDescriptor descriptor = DesktopAppDescriptor.fromComponent(app);
        DesktopAppStatusSheetModel model = desktopAppLauncher.buildStatusSheetModel(descriptor);
        showDesktopStatusDialog(model, leadingMessage);
    }

    private void showDesktopStatusDialog(DesktopAppStatusSheetModel model, String leadingMessage) {
        if (model == null || isFinishing()) {
            return;
        }
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(18), dp(14), dp(18), dp(6));
        addTitle(panel, model.title(), 20);
        if (!isBlank(leadingMessage)) {
            addBody(panel, leadingMessage);
        }
        addStatusRow(panel, "状态", model.headline());
        if (model.status != null && !isBlank(model.status.detail)) {
            addBody(panel, model.status.detail);
        }
        addDesktopStatusLines(panel, "详细信息", model.detailLines, 8);
        addDesktopStatusLines(panel, "最近日志", model.recentLogLines, 5);

        final AlertDialog[] dialogHolder = new AlertDialog[1];
        addDesktopStatusActionRows(panel, model, dialogHolder);

        ScrollView scrollView = new ScrollView(this);
        scrollView.addView(panel);
        AlertDialog dialog = new AlertDialog.Builder(this)
            .setView(scrollView)
            .setNegativeButton("关闭", null)
            .create();
        dialogHolder[0] = dialog;
        dialog.show();
    }

    private void addDesktopStatusLines(LinearLayout panel, String title, List<String> lines, int limit) {
        if (lines == null || lines.isEmpty()) {
            return;
        }
        addTitle(panel, title, 15);
        StringBuilder body = new StringBuilder();
        int count = 0;
        for (String line : lines) {
            if (isBlank(line)) {
                continue;
            }
            if (body.length() > 0) {
                body.append('\n');
            }
            body.append(line.trim());
            count++;
            if (count >= limit) {
                break;
            }
        }
        if (body.length() > 0) {
            addBody(panel, body.toString());
        }
    }

    private void addDesktopStatusActionRows(
        LinearLayout panel,
        DesktopAppStatusSheetModel model,
        AlertDialog[] dialogHolder
    ) {
        DesktopAppAction openAction = findDesktopAction(model, DesktopAppAction.Type.OPEN);
        DesktopAppAction restartAction = findDesktopAction(model, DesktopAppAction.Type.RESTART);
        DesktopAppAction logAction = findDesktopAction(model, DesktopAppAction.Type.LOG);
        DesktopAppAction serviceAction = findDesktopAction(model, DesktopAppAction.Type.SERVICE_CONTROL);
        DesktopAppAction repairAction = findDesktopAction(model, DesktopAppAction.Type.REPAIR);

        OpenHouseComponent component = model.app == null ? null : model.app.component;
        addButtonRow(panel,
            compactButton("打开", v -> {
                dismissDialog(dialogHolder);
                openDesktopApp(component);
            }, openAction == null || openAction.enabled),
            compactButton("重启", v -> runDesktopServiceAction(restartAction), restartAction != null && restartAction.enabled));
        addButtonRow(panel,
            compactButton("日志", v -> {
                dismissDialog(dialogHolder);
                openDesktopLogs(model);
            }, logAction != null && logAction.enabled),
            compactButton("服务控制", v -> {
                dismissDialog(dialogHolder);
                openDesktopServiceControl(model);
            }, serviceAction == null || serviceAction.enabled));
        addButtonRow(panel,
            compactButton("修复", v -> runDesktopServiceAction(repairAction), repairAction != null && repairAction.enabled),
            compactButton("维护中心", v -> {
                dismissDialog(dialogHolder);
                openMaintenanceCenter();
            }, true));
    }

    private DesktopAppAction findDesktopAction(DesktopAppStatusSheetModel model, DesktopAppAction.Type type) {
        if (model == null || type == null || model.actions == null) {
            return null;
        }
        for (DesktopAppAction action : model.actions) {
            if (action != null && action.type == type) {
                return action;
            }
        }
        return null;
    }

    private void runDesktopServiceAction(DesktopAppAction action) {
        if (desktopAppLauncher == null) {
            desktopAppLauncher = new DesktopAppLauncher(this);
        }
        if (action == null || !action.enabled) {
            Toast.makeText(this, "这个动作当前不可用。", Toast.LENGTH_SHORT).show();
            return;
        }
        Toast.makeText(this, action.label + "执行中。", Toast.LENGTH_SHORT).show();
        backgroundExecutor.execute(() -> {
            DesktopAppActionResult result = desktopAppLauncher.performAction(action);
            runOnUiThread(() -> Toast.makeText(this,
                result.success
                    ? action.label + "完成" + (isBlank(result.state) ? "" : "：" + result.state)
                    : action.label + "失败：" + firstNonBlank(result.message, "请查看服务控制。"),
                Toast.LENGTH_LONG).show());
        });
    }

    private void openDesktopLogs(DesktopAppStatusSheetModel model) {
        if (model != null && model.app != null && model.app.component != null && model.app.component.hasControlEntry()) {
            openComponentControl(model.app.component);
            return;
        }
        selectPage(PAGE_LOGS);
    }

    private void openDesktopServiceControl(DesktopAppStatusSheetModel model) {
        if (model != null && model.app != null && model.app.component != null && model.app.component.hasControlEntry()) {
            openComponentControl(model.app.component);
            return;
        }
        openAllServiceControl();
    }

    private void dismissDialog(AlertDialog[] dialogHolder) {
        if (dialogHolder != null && dialogHolder.length > 0 && dialogHolder[0] != null) {
            dialogHolder[0].dismiss();
        }
    }

    private void showOperitStatusSheet(String leadingMessage) {
        if (!OperitHomeIntegration.isAvailable()) {
            return;
        }
        OperitHomeIntegration.DisplayState state = getAiFriendHelpDisplayState();
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(18), dp(14), dp(18), dp(6));
        addTitle(panel, "AI朋友 Help", 20);
        if (!isBlank(leadingMessage)) {
            addBody(panel, leadingMessage);
        }
        addStatusRow(panel, "状态", getAiFriendHelpStateLabel(state));
        addBody(panel, "这是 withOperit 构建中的 Android 侧完整 Operit 入口；withoutOperit 构建不会显示。");
        final AlertDialog[] dialogHolder = new AlertDialog[1];
        addButtonRow(panel,
            compactButton(getAiFriendHelpOpenActionLabel(state), v -> {
                dismissDialog(dialogHolder);
                openAiFriendHelp();
            }, state != OperitHomeIntegration.DisplayState.STARTING && state != OperitHomeIntegration.DisplayState.STOPPING),
            compactButton("关闭后台运行", v -> requestCloseBackgroundAiFriendHelp(),
                state == OperitHomeIntegration.DisplayState.BACKGROUND));
        addButtonRow(panel,
            compactButton("日志", v -> {
                dismissDialog(dialogHolder);
                selectPage(PAGE_LOGS);
            }, true),
            compactButton("服务控制", v -> {
                dismissDialog(dialogHolder);
                openAllServiceControl();
            }, true));
        ScrollView scrollView = new ScrollView(this);
        scrollView.addView(panel);
        AlertDialog dialog = new AlertDialog.Builder(this)
            .setView(scrollView)
            .setNegativeButton("关闭", null)
            .create();
        dialogHolder[0] = dialog;
        dialog.show();
    }

    private void notifyDesktopOpenFailedIfNeeded(OpenHouseComponent app, String message) {
        if (app == null || isBlank(pendingDesktopOpenAppId) || !pendingDesktopOpenAppId.equals(app.id)) {
            return;
        }
        pendingDesktopOpenAppId = null;
        View anchor = contentView != null ? contentView : scrollContentView;
        if (anchor == null) {
            showDesktopStatusSheet(app, message);
            return;
        }
        anchor.post(() -> showDesktopStatusSheet(app, message));
    }

    private void clearPendingDesktopOpenIfMatches(OpenHouseComponent app) {
        if (app != null && app.id.equals(pendingDesktopOpenAppId)) {
            pendingDesktopOpenAppId = null;
        }
    }

    private void notifyDesktopOpenFailedForNativePage(String page, OpenHouseComponent fallbackApp, String message) {
        if (isBlank(pendingDesktopOpenAppId)) {
            return;
        }
        OpenHouseComponent pendingApp = findDesktopComponentById(pendingDesktopOpenAppId);
        OpenHouseComponent app = pendingApp != null ? pendingApp : fallbackApp;
        if (app == null || !isPendingDesktopOpenForNativePage(app, page)) {
            return;
        }
        pendingDesktopOpenAppId = null;
        View anchor = contentView != null ? contentView : scrollContentView;
        if (anchor == null) {
            showDesktopStatusSheet(app, message);
            return;
        }
        anchor.post(() -> showDesktopStatusSheet(app, message));
    }

    private void clearPendingDesktopOpenForNativePage(String page) {
        if (isBlank(pendingDesktopOpenAppId)) {
            return;
        }
        OpenHouseComponent app = findDesktopComponentById(pendingDesktopOpenAppId);
        if (app != null && isPendingDesktopOpenForNativePage(app, page)) {
            pendingDesktopOpenAppId = null;
        }
    }

    private boolean isPendingDesktopOpenForNativePage(OpenHouseComponent app, String page) {
        if (app == null || isBlank(page)) {
            return false;
        }
        if (pendingDesktopOpenAppId.equals(app.id)) {
            String appPage = resolveNativePage(app.nativePage);
            if (page.equals(appPage)) {
                return true;
            }
            if (PAGE_PI_WEB.equals(page) && isPiWebComponent(app)) {
                return true;
            }
            if (PAGE_AI.equals(page) && isCcCodexComponent(app)) {
                return true;
            }
            if (PAGE_SMALLPHONE.equals(page) && isSmallPhoneComponent(app)) {
                return true;
            }
            if (PAGE_CONTROLLED_BROWSER.equals(page) && isControlledBrowserComponent(app)) {
                return true;
            }
            return PAGE_AI_RESCUE.equals(page)
                && PAGE_AI_RESCUE.equals(resolveNativePage(app.nativePage));
        }
        return false;
    }

    private boolean isWebBackedNativePage(String page) {
        if (PAGE_PI_WEB.equals(page)
            || PAGE_AI.equals(page)
            || PAGE_AI_RESCUE.equals(page)) {
            return true;
        }
        if (PAGE_SMALLPHONE.equals(page)) {
            OpenHouseComponent component = findSmallPhoneComponent();
            return component != null && component.entryType == OpenHouseComponent.EntryType.WEBVIEW;
        }
        if (PAGE_CONTROLLED_BROWSER.equals(page)) {
            OpenHouseComponent component = findControlledBrowserComponent();
            return component != null && component.entryType == OpenHouseComponent.EntryType.WEBVIEW;
        }
        return false;
    }

    private String safeMessage(Exception e) {
        return e == null || isBlank(e.getMessage()) ? "未知错误" : e.getMessage().trim();
    }

    private void renderHomePage() {
        LinearLayout panel = panel();
        addTitle(panel, "菜单总览", 19);
        addBody(panel, "这里保留主入口：SmallPhone、pi-agent、cc/codex、运行控制、终端教学、文档、日志和维护中心。安装完成后，应用在前台会自动保持核心服务运行，运行控制由 service-manager 负责。");
        panel.addView(createPiAgentControlBlock());
        if (OperitHomeIntegration.isAvailable()) {
            panel.addView(createAiFriendHelpControlBlock(true));
        }
        addButtonRow(panel,
            compactButton("进入 AI 软件安装引导", v -> openInstallGuide(), true),
            compactButton("使用教学", v -> startUsageTeachingFlow(), true));
        addButtonRow(panel,
            compactButton("进入桌面", v -> selectPage(PAGE_DESKTOP), true),
            compactButton("设默认打开桌面", v -> setStartPageMode(START_MODE_DESKTOP), true));
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
        addButtonRow(panel,
            compactButton("终端教学", v -> selectPage(PAGE_TERMINAL_GUIDE), true),
            compactButton("全部退出/运行控制", v -> openAllServiceControl(), true));
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
        refreshAiFriendHelpEntryState();
    }

    private void renderUsageTutorialPage() {
        LinearLayout panel = panel();
        addTitle(panel, usageCoreServicesMode ? "启动核心服务" : "使用教学", 19);
        if (usageCoreServicesMode) {
            addBody(panel, "通常应用在前台会自动保持核心服务运行。这里保留手动启动入口，用于页面提示未运行或需要主动修复时使用。这个动作会拉起 service-manager、pi-agent/pi-web 和 SmallPhone 兼容入口；openhouse-connect 会作为可修复连接服务尝试启动；cc/codex 后续由 pi-agent 安装配置。");
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
            addBody(panel, "使用教学会在菜单内带你认识 SmallPhone、pi-agent、cc/codex、运行控制、全部退出和终端教学入口。首次教学不进入终端；需要真实点击的步骤 20 秒后才允许跳过。");
            Button startButton = button("开始使用教学", v -> startUsageTeachingFlow());
            startButton.setTag(HOME_USAGE_TUTORIAL_TAG);
            panel.addView(startButton);
            panel.addView(button("单独查看终端教学", v -> selectPage(PAGE_TERMINAL_GUIDE)));
        }
        contentView.addView(panel);
    }

    private void renderManualPage() {
        addManualSection("安装时建议阅读",
            "第一次安装通常需要 10 分钟到半小时，期间会下载较大的运行环境，建议在 Wi-Fi 下进行。openhouse ai 会准备 Ubuntu、Node、pi-agent、pi-web、service-manager 和 SmallPhone 兼容服务；openhouse-connect 保留为可修复连接服务。");
        addManualSection("首次安装之后",
            "安装链路只负责把环境装好。安装完成后，service-manager 才是运行控制平面，用于查看、启动、停止和修复内置服务。");
        addManualSection("终端里的 AI 怎么用",
            "Codex、Claude Code 和 CloudCLI 改为后置能力。先进入 pi-agent 完成安装配置；配置好后可在 Ubuntu 终端使用 claude、codex 或对应命令。");
        addManualSection("Termux 和 Ubuntu",
            "新装后普通 Termux 终端默认停留在 Termux native。openhouse ai 会在 Termux 里安装 Ubuntu proot，Codex、Claude Code 和 CloudCLI 主要安装在 Ubuntu 的 /root 环境；只有用户在手动模式显式执行“启动后直接进入 Ubuntu”后，普通入口终端才会自动进入 Ubuntu。维护中心底部终端始终固定为 Termux。");
        addManualSection("CloudCLI 和 SmallPhone",
            "CloudCLI 提供 cc/codex 统一入口，但不再是首次安装必需项。SmallPhone 是本机页面和兼容运行栈入口。两者的服务状态可从运行控制或维护中心查看。");
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

        renderShizukuPermissionPanel();
    }

    private void renderShizukuPermissionPanel() {
        LinearLayout panel = panel();
        addTitle(panel, getString(R.string.shizuku_permission_title), 19);
        addBody(panel, getString(R.string.shizuku_permission_description));

        OpenHouseShizukuManager.Snapshot snapshot = shizukuManager == null
            ? null
            : shizukuManager.snapshot();
        if (snapshot == null) {
            addStatusRow(panel, getString(R.string.shizuku_status_label), "正在检测");
            contentView.addView(panel);
            return;
        }

        addStatusRow(panel,
            getString(R.string.shizuku_status_label),
            snapshot.statusLabel);
        addStatusRow(panel,
            getString(R.string.shizuku_version_label),
            snapshot.installed && !isBlank(snapshot.version)
                ? snapshot.version
                : getString(snapshot.installed
                    ? R.string.shizuku_version_unknown
                    : R.string.shizuku_not_installed));
        addStatusRow(panel,
            getString(R.string.shizuku_identity_label),
            snapshot.running && snapshot.uid >= 0
                ? getString(R.string.shizuku_identity_uid, snapshot.uid)
                : getString(R.string.shizuku_identity_unavailable));
        addStatusRow(panel,
            getString(R.string.shizuku_rish_label),
            getString(snapshot.rishReady
                ? R.string.shizuku_rish_ready
                : R.string.shizuku_rish_not_ready));

        if (!snapshot.installed) {
            panel.addView(button(getString(R.string.shizuku_install_bundled),
                v -> runShizukuAction(() -> shizukuManager.installBundledShizuku())));
        } else if (!snapshot.running) {
            panel.addView(button(getString(R.string.shizuku_open),
                v -> runShizukuAction(() -> shizukuManager.openShizuku())));
        } else if (!snapshot.authorized) {
            addBody(panel, getString(R.string.shizuku_permission_vendor_hint));
            addButtonRow(panel,
                compactButton(getString(R.string.shizuku_request_permission),
                    v -> runShizukuAction(() -> shizukuManager.requestPermission()), true),
                compactButton(getString(R.string.shizuku_open),
                    v -> runShizukuAction(() -> shizukuManager.openShizuku()), true));
        } else {
            addButtonRow(panel,
                compactButton(getString(R.string.shizuku_open),
                    v -> runShizukuAction(() -> shizukuManager.openShizuku()), true),
                compactButton(getString(R.string.shizuku_refresh),
                    v -> refreshShizukuState(), true));
        }

        String rishTestCommand = getString(R.string.shizuku_rish_test_command);
        addBody(panel, getString(R.string.shizuku_rish_test_hint, rishTestCommand));
        panel.addView(button(getString(R.string.shizuku_copy_rish_test),
            v -> copyText("rish", rishTestCommand)));
        contentView.addView(panel);
    }

    private void onShizukuStateChanged() {
        runOnUiThread(() -> {
            if (isFinishing() || (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1 && isDestroyed())) {
                return;
            }
            if (PAGE_PERMISSIONS.equals(currentPage)) {
                renderPage();
            }
        });
    }

    private void refreshShizukuState() {
        if (shizukuManager == null) {
            return;
        }
        runShizukuAction(() -> shizukuManager.ensureRishInstalled());
        if (PAGE_PERMISSIONS.equals(currentPage)) {
            renderPage();
        }
    }

    private void runShizukuAction(Runnable action) {
        if (shizukuManager == null) {
            return;
        }
        try {
            action.run();
        } catch (Exception e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Shizuku action failed", e);
            String detail = isBlank(e.getMessage()) ? e.getClass().getSimpleName() : e.getMessage();
            Toast.makeText(this, getString(R.string.shizuku_action_failed, detail), Toast.LENGTH_LONG).show();
        }
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
        addTitle(panel, "终端教学", 19);
        addBody(panel, "终端教学是独立入口，不属于首次使用教学。普通用户暂时不需要使用终端；需要命令行、Termux 或 Ubuntu 时，再从这里进入详细教学。");
        addButtonRow(panel,
            compactButton("开始终端教学", v -> openTerminal(true), true),
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
        addStatusRow(panel, "默认打开", getLaunchModeDisplayTitle());
        addStatusRow(panel, "控制平面", "service-manager");
        addStatusRow(panel, "菜单注册", registryResult.toShortStatusText());
        int dynamicWebViewRetainCount = getDynamicWebViewRetainCount();
        addStatusRow(panel, "保留 WebView 窗口", dynamicWebViewRetainCount + " 个");
        addStatusRow(panel, "当前保留窗口", dynamicWebPagePool.size() + " 个");
        addBody(panel, "只影响动态 App WebView。0 表示离开页面后销毁，默认保留最近 2 个窗口。");
        addDynamicWebViewRetainButtons(panel, dynamicWebViewRetainCount);
        addBody(panel, registryResult.toDiagnosticText());
        addButtonRow(panel,
            compactButton("默认桌面", v -> setStartPageMode(START_MODE_DESKTOP), true),
            compactButton("默认" + getHomeDisplayTitle(PAGE_HOME), v -> setStartPageMode(START_MODE_HOME), true));
        addButtonRow(panel,
            compactButton("默认上次退出页", v -> setStartPageMode(START_MODE_LAST), true),
            compactButton("进入桌面", v -> selectPage(PAGE_DESKTOP), true));
        CheckBox keepAliveToggle = checkbox("自动保持控制中枢运行", OpenHouseRuntimePreferences.isServiceManagerKeepAliveEnabled(this));
        keepAliveToggle.setOnCheckedChangeListener((buttonView, isChecked) -> {
            OpenHouseRuntimePreferences.setServiceManagerKeepAliveEnabled(this, isChecked);
            Toast.makeText(this,
                isChecked ? "已开启 service-manager 自动保活。" : "已关闭自动保活，可手动恢复默认核心服务。",
                Toast.LENGTH_SHORT).show();
        });
        panel.addView(keepAliveToggle);
        addBody(panel, "默认开启。关闭后，App 在前台也不会自动拉起 service-manager；运行控制里的“恢复默认核心服务”和“启动运行中枢”仍可手动使用。");
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

    private void addDynamicWebViewRetainButtons(LinearLayout panel, int currentCount) {
        addButtonRow(panel,
            dynamicWebViewRetainButton(0, currentCount),
            dynamicWebViewRetainButton(1, currentCount));
        addButtonRow(panel,
            dynamicWebViewRetainButton(2, currentCount),
            dynamicWebViewRetainButton(3, currentCount));
        addButtonRow(panel,
            dynamicWebViewRetainButton(4, currentCount),
            dynamicWebViewRetainButton(5, currentCount));
    }

    private Button dynamicWebViewRetainButton(int count, int currentCount) {
        String label = count + " 个" + (count == currentCount ? "（当前）" : "");
        return compactButton(label, v -> {
            setDynamicWebViewRetainCount(count);
            renderPage();
        }, true);
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
        setUsageCoreServicesProgress("正在启动 service-manager、pi-agent、pi-web 和 SmallPhone 兼容入口，并尝试拉起 openhouse-connect...");
        backgroundExecutor.execute(() -> {
            OpenHouseMaintainerRunner runner = new OpenHouseMaintainerRunner(this);
            OpenHouseMaintainerRunner.Result stackResult =
                runner.run(OpenHouseMaintainerRunner.Action.START_SMALLPHONE, 0);
            if (!stackResult.isSuccess()) {
                runOnUiThread(() -> showCoreServicesStartFailed(
                    "核心运行栈启动失败。请重试启动核心服务，或返回菜单后从运行控制/维护与修复处理。"));
                return;
            }

            runOnUiThread(() -> {
                setUsageCoreServicesProgress("核心运行栈启动完成。\ncc/codex 会由 pi-agent 后续安装配置。");
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

        Button closeButton = compactButton("关闭后台运行", v -> requestCloseBackgroundAiFriendHelp(), true);
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
        if (!OperitHomeIntegration.isAvailable()) {
            return;
        }
        OperitHomeIntegration.DisplayState displayState = getAiFriendHelpDisplayState();
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

    private OperitHomeIntegration.DisplayState getAiFriendHelpDisplayState() {
        OperitHomeIntegration.DisplayState state =
            OperitHomeIntegration.readDisplayState(getApplicationContext());
        if (state == OperitHomeIntegration.DisplayState.NOT_RUNNING
            || state == OperitHomeIntegration.DisplayState.UNAVAILABLE) {
            aiFriendHelpShutdownRequestedAtMs = 0L;
            return getPendingAiFriendHelpLaunchState();
        }
        if (state == OperitHomeIntegration.DisplayState.STOPPING) {
            clearAiFriendHelpLaunchPending();
            return OperitHomeIntegration.DisplayState.STOPPING;
        }
        if (aiFriendHelpShutdownRequestedAtMs <= 0L) {
            clearAiFriendHelpLaunchPending();
            return state;
        }
        long elapsed = System.currentTimeMillis() - aiFriendHelpShutdownRequestedAtMs;
        if (elapsed <= OperitHomeIntegration.SHUTDOWN_PENDING_UI_MS) {
            clearAiFriendHelpLaunchPending();
            return OperitHomeIntegration.DisplayState.STOPPING;
        }
        aiFriendHelpShutdownRequestedAtMs = 0L;
        clearAiFriendHelpLaunchPending();
        return state;
    }

    private OperitHomeIntegration.DisplayState getPendingAiFriendHelpLaunchState() {
        if (aiFriendHelpLaunchRequestedAtMs <= 0L) {
            aiFriendHelpLaunchFailureNotified = false;
            return OperitHomeIntegration.DisplayState.NOT_RUNNING;
        }

        long elapsed = System.currentTimeMillis() - aiFriendHelpLaunchRequestedAtMs;
        if (elapsed <= OperitHomeIntegration.LAUNCH_PROCESS_GRACE_MS
            || (elapsed <= OperitHomeIntegration.LAUNCH_PENDING_UI_MS
            && OperitHomeIntegration.isOperitProcessAlive(getApplicationContext(), -1))) {
            return OperitHomeIntegration.DisplayState.STARTING;
        }

        aiFriendHelpLaunchRequestedAtMs = 0L;
        if (!aiFriendHelpLaunchFailureNotified) {
            aiFriendHelpLaunchFailureNotified = true;
            Toast.makeText(this, "AI朋友 Help 没有成功启动，已回到主菜单。请查看崩溃日志。", Toast.LENGTH_LONG).show();
        }
        return OperitHomeIntegration.DisplayState.NOT_RUNNING;
    }

    private void clearAiFriendHelpLaunchPending() {
        aiFriendHelpLaunchRequestedAtMs = 0L;
        aiFriendHelpLaunchFailureNotified = false;
    }

    private void updateAiFriendHelpControlBlock(
        TextView statusView,
        Button openButton,
        Button closeButton,
        OperitHomeIntegration.DisplayState displayState
    ) {
        if (statusView == null || openButton == null || closeButton == null) {
            return;
        }
        statusView.setText("AI朋友 Help（状态：" + getAiFriendHelpStateLabel(displayState) + "）");

        boolean starting = displayState == OperitHomeIntegration.DisplayState.STARTING;
        boolean stopping = displayState == OperitHomeIntegration.DisplayState.STOPPING;
        boolean background = displayState == OperitHomeIntegration.DisplayState.BACKGROUND;
        openButton.setText(getAiFriendHelpOpenActionLabel(displayState));
        openButton.setEnabled(!starting && !stopping);
        closeButton.setText(stopping ? "停止中" : "关闭后台运行");
        closeButton.setVisibility(background || stopping ? View.VISIBLE : View.GONE);
        closeButton.setEnabled(background && !stopping);
    }

    private String getAiFriendHelpStateLabel(OperitHomeIntegration.DisplayState state) {
        if (state == OperitHomeIntegration.DisplayState.STARTING) {
            return "启动中";
        }
        if (state == OperitHomeIntegration.DisplayState.FOREGROUND) {
            return "前台运行";
        }
        if (state == OperitHomeIntegration.DisplayState.BACKGROUND) {
            return "后台运行";
        }
        if (state == OperitHomeIntegration.DisplayState.STOPPING) {
            return "停止中";
        }
        return "未运行";
    }

    private String getAiFriendHelpOpenActionLabel(OperitHomeIntegration.DisplayState state) {
        if (state == OperitHomeIntegration.DisplayState.NOT_RUNNING
            || state == OperitHomeIntegration.DisplayState.UNAVAILABLE) {
            return "打开进入";
        }
        if (state == OperitHomeIntegration.DisplayState.STARTING) {
            return "启动中";
        }
        if (state == OperitHomeIntegration.DisplayState.STOPPING) {
            return "停止中";
        }
        return "进入";
    }

    private void requestCloseBackgroundAiFriendHelp() {
        clearAiFriendHelpLaunchPending();
        if (!OperitHomeIntegration.isBackground(getApplicationContext())) {
            refreshAiFriendHelpEntryState();
            Toast.makeText(this, "AI朋友 Help 当前不是后台运行状态", Toast.LENGTH_SHORT).show();
            return;
        }

        aiFriendHelpShutdownRequestedAtMs = System.currentTimeMillis();
        if (OperitHomeIntegration.requestShutdown(getApplicationContext())) {
            Toast.makeText(this, "已请求关闭 AI朋友 Help 后台运行", Toast.LENGTH_SHORT).show();
        } else {
            aiFriendHelpShutdownRequestedAtMs = 0L;
            Toast.makeText(this, "关闭 AI朋友 Help 请求失败", Toast.LENGTH_SHORT).show();
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
        anchor.postDelayed(this::refreshAiFriendHelpEntryState, 2_000);
        anchor.postDelayed(this::refreshAiFriendHelpEntryState,
            OperitHomeIntegration.SHUTDOWN_PENDING_UI_MS + 250);
        anchor.postDelayed(this::refreshAiFriendHelpEntryState,
            OperitHomeIntegration.LAUNCH_PENDING_UI_MS + 250);
    }

    private void openAiFriendHelp() {
        if (drawerLayout != null) {
            drawerLayout.closeDrawer(GravityCompat.START);
        }
        aiFriendHelpShutdownRequestedAtMs = 0L;
        aiFriendHelpLaunchRequestedAtMs = System.currentTimeMillis();
        aiFriendHelpLaunchFailureNotified = false;
        refreshAiFriendHelpEntryState();
        if (OperitHomeIntegration.openAiFriendHelp(this)) {
            refreshAiFriendHelpEntryState();
            scheduleAiFriendHelpStateRefresh();
        } else {
            aiFriendHelpLaunchRequestedAtMs = 0L;
            aiFriendHelpLaunchFailureNotified = true;
            refreshAiFriendHelpEntryState();
        }
    }

    private LinearLayout createPiAgentControlBlock() {
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

    private int clampInt(int value, int min, int max) {
        if (max < min) {
            return min;
        }
        return Math.max(min, Math.min(max, value));
    }

    private float clampFloat(float value, float min, float max) {
        if (max < min) {
            return min;
        }
        return Math.max(min, Math.min(max, value));
    }
}
