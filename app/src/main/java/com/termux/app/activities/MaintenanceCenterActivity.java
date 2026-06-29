package com.termux.app.activities;

import android.Manifest;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.res.ColorStateList;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.text.InputType;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import android.provider.Settings;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;
import androidx.core.content.ContextCompat;
import androidx.core.widget.NestedScrollView;

import com.termux.R;
import com.termux.app.ClaudeCodeUiSettings;
import com.termux.app.OpenCodeCdpBridge;
import com.termux.app.OpenCodeDownloadSourceSettings;
import com.termux.app.OpenCodeSettings;
import com.termux.app.TermuxActivity;
import com.termux.app.openhouse.OpenHouseDeepSeekController;
import com.termux.app.openhouse.OpenHouseBundledRuntimeSync;
import com.termux.app.openhouse.OpenHouseStartupPermissionHelper;
import com.termux.app.openhouse.OpenHouseStatusRepository;
import com.termux.app.openhouse.components.OpenHouseComponentRegistry;
import com.termux.app.openhouse.release.OpenHouseReleaseDownloader;
import com.termux.app.openhouse.release.OpenHouseReleaseException;
import com.termux.app.openhouse.release.OpenHouseReleaseInstaller;
import com.termux.app.openhouse.release.OpenHouseReleaseManifest;
import com.termux.app.openhouse.release.OpenHouseReleaseSettings;
import com.termux.app.openhouse.release.OpenHouseReleaseValidator;
import com.termux.app.openhouse.servicecontrol.ServiceManagerClient;
import com.termux.shared.activity.ActivityUtils;
import com.termux.shared.logger.Logger;
import com.termux.shared.shell.ShellUtils;
import com.termux.shared.shell.command.ExecutionCommand;
import com.termux.shared.termux.TermuxConstants;
import com.termux.shared.termux.shell.TermuxShellManager;
import com.termux.shared.termux.shell.command.environment.TermuxShellEnvironment;
import com.termux.shared.termux.shell.command.runner.terminal.TermuxSession;
import com.termux.shared.termux.terminal.TermuxTerminalSessionClientBase;
import com.termux.shared.termux.terminal.TermuxTerminalViewClientBase;
import com.termux.shared.view.KeyboardUtils;
import com.termux.terminal.TerminalSession;
import com.termux.view.TerminalView;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

public class MaintenanceCenterActivity extends AppCompatActivity {

    public static final String EXTRA_RETURN_TO_ONBOARDING = "return_to_onboarding";
    public static final String EXTRA_SERVICE_CONTROL_COMPONENT_ID = "openhouse_component_id";
    public static final String EXTRA_SERVICE_CONTROL_TITLE = "openhouse_component_title";
    public static final String EXTRA_SERVICE_CONTROL_SERVICE_NAMES = "openhouse_service_names";
    public static final String EXTRA_SERVICE_CONTROL_SERVICE_REFS = "openhouse_service_refs";

    private static final String LOG_TAG = "MaintenanceCenter";
    private static final int LOG_CHAR_LIMIT = 24000;
    private static final long TERMINAL_UI_UPDATE_DELAY_MS = 250;
    private static final long TERMINAL_LIVE_LOG_REFRESH_MIN_INTERVAL_MS = 1000;
    private static final long TERMINAL_COMPLETION_SCAN_MIN_INTERVAL_MS = 500;
    private static final long TERMINAL_COMPLETION_POLL_INTERVAL_MS = 1000;
    private static final long ONE_CLICK_STATUS_REFRESH_INTERVAL_MS = 5000;
    private static final long SHARED_INSTALL_PROGRESS_REFRESH_INTERVAL_MS = 1200;
    private static final int SHARED_INSTALL_LOG_CHAR_LIMIT = 16000;
    private static final Pattern DONE_PATTERN = Pattern.compile("__TERMUX_MAINT_DONE__:([a-zA-Z0-9_-]+):(\\d+)");
    private static final String OFFICIAL_DOCS_ASSET_DIR = "openhouse/docs-public";
    private static final String BUNDLED_MAINTENANCE_PLUGIN_ASSET = "openhouse/plugins/original/openhouse-manifest.json";
    private static final String PREFS_MAINTENANCE = "maintenance_center";
    private static final String PREF_DISABLE_BATTERY_REQUIREMENT = "disable_battery_requirement";
    private static final String PREF_MAINTENANCE_PLUGIN_MODE = "maintenance_plugin_mode";
    private static final String PREF_MAINTENANCE_SOURCE_URL = "maintenance_source_url";
    private static final String PREF_USER_PLUGIN_PATH = "maintenance_user_plugin_path";
    private static final String PREF_LOCAL_MAINTENANCE_WEB_PORT = "local_maintenance_web_port";
    private static final String PREF_USE_REMOTE_BOOTSTRAP = "maintenance_use_remote_bootstrap";
    private static final String DEFAULT_MAINTENANCE_MANIFEST_URL = "https://raw.githubusercontent.com/jiwuyou/openhouseai-bootstrap/main/openhouseai-manifest.json";
    private static final String DEFAULT_BOOTSTRAP_URL = "https://raw.githubusercontent.com/jiwuyou/openhouseai-bootstrap/main/bootstrap.sh";
    private static final String DEEPSEEK_API_KEYS_URL = "https://platform.deepseek.com/api_keys";
    private static final String SERVICE_MANAGER_BASE_URL = "http://127.0.0.1:20087";
    private static final String DEFAULT_USER_PLUGIN_PATH = TermuxConstants.TERMUX_HOME_DIR_PATH + "/.openhouseai/plugins/user/openhouseai-manifest.json";
    private static final int DEFAULT_LOCAL_MAINTENANCE_WEB_PORT = 38423;
    private static final int MIN_LOCAL_MAINTENANCE_WEB_PORT = 10000;
    private static final int MAX_LOCAL_MAINTENANCE_WEB_PORT = 65535;
    private static final int MANIFEST_CONNECT_TIMEOUT_MS = 5000;
    private static final int MANIFEST_READ_TIMEOUT_MS = 9000;
    private static final String PROBE_OPENCODE_SOURCE_SLUG = "probe_opencode_source";
    private static final int SOURCE_PROBE_CONNECT_TIMEOUT_MS = 3000;
    private static final int SOURCE_PROBE_READ_TIMEOUT_MS = 8000;
    private static final StageAction[] ONE_CLICK_STAGE_SEQUENCE = new StageAction[] {
        StageAction.PREPARE,
        StageAction.TERMUX_PACKAGES,
        StageAction.INSTALL_UBUNTU,
        StageAction.SYNC_OFFICIAL_DOCS,
        StageAction.UBUNTU_PACKAGES,
        StageAction.CONFIGURE_ENTRY_UBUNTU,
        StageAction.INSTALL_NODE,
        StageAction.INSTALL_OPENCODE,
        StageAction.INSTALL_CODEX,
        StageAction.INSTALL_CLAUDE_CODE,
        StageAction.INSTALL_CLAUDE_CODE_UI,
        StageAction.INSTALL_REASONIX,
        StageAction.RUNTIME_COMPONENTS,
        StageAction.INSTALL_HERMES,
        StageAction.SYNC_OPENHOUSE_REGISTRY
    };

    private TextView statusHeadlineView;
    private TextView statusBodyView;
    private TextView currentStageView;
    private TextView liveLogView;
    private TextView helpBodyView;
    private TextView terminalStatusView;
    private TextView permissionRequirementHintView;
    private TextView downloadSourceSummaryView;
    private TextView maintenanceSourceSummaryView;
    private TextView localMaintenanceWebSummaryView;
    private TextView releaseUpdateServerSummaryView;
    private TextView releaseUpdateStatusView;
    private TextView sharedInstallProgressView;
    private TextView sharedInstallDetailView;
    private TextView sharedInstallLogView;
    private Button forceRestartSharedInstallButton;
    private LinearLayout dynamicPluginSectionsContainer;
    private NestedScrollView liveLogScrollView;
    private NestedScrollView sharedInstallLogScrollView;
    private SwitchCompat permissionBatteryButton;
    private SwitchCompat permissionOverlayButton;
    private SwitchCompat permissionStorageButton;
    private Button permissionStartupButton;
    private Button returnHomeButton;
    private Button configureDefaultPortButton;
    private Button configureDownloadSourceButton;
    private Button probeDownloadSourceButton;
    private Button configureMaintenanceSourceButton;
    private Button refreshMaintenanceSourceButton;
    private Button configureReleaseServerButton;
    private Button checkReleaseUpdateButton;
    private Button downloadReleaseUpdateButton;
    private Button restartEntryTerminalButton;
    private Button stageManualModeButton;
    private Button stageOneClickModeButton;
    private Button startOneClickStagesButton;
    private Button deepSeekKeyGuideButton;
    private Button deepSeekKeyConfigButton;
    private Button startButton;
    private Button restartButton;
    private Button customPortButton;
    private Button openMaintenanceWebButton;
    private Button stopMaintenanceWebButton;
    private Button configureMaintenanceWebPortButton;
    private Button viewFullLogButton;
    private Button openBrowserButton;
    private TextView oneClickStageSummaryView;
    private TextView oneClickPrepareItemView;
    private TextView oneClickUbuntuPackagesItemView;
    private TextView oneClickOpenCodeItemView;
    private TextView oneClickCodexItemView;
    private TextView oneClickClaudeCodeItemView;
    private TextView oneClickSkillItemView;
    private TextView oneClickStartItemView;
    private LinearLayout oneClickStageItemsContainer;
    private View oneClickStagePanel;
    private View stageActionsPanel;
    private View serviceControlPanelView;
    private TextView serviceControlStatusView;
    private FrameLayout terminalContainer;
    private TerminalView terminalView;
    private SwitchCompat disableBatteryRequirementSwitch;

    private TermuxSession maintenanceSession;
    private String currentStageSlug;
    private String currentStageLabel;
    private String lastHandledMarker;
    private StageAction pendingStageAction;
    private boolean commandInFlight;
    private boolean maintenanceSessionInitPosted;
    private String terminalFailureMessage;
    private Boolean opencodeReachable;
    private boolean stageStatusCheckInFlight;
    private boolean stageStatusCheckQueued;
    private boolean oneClickStageMode;
    private boolean oneClickStagesInFlight;
    private boolean oneClickRemoteProbeInFlight;
    private boolean oneClickUseBundledStages;
    private boolean currentStageUsedRemote;
    private boolean openMaintenanceWebAfterStage;
    private boolean terminalTextUpdateScheduled;
    private boolean terminalCompletionPollScheduled;
    private boolean sharedInstallControllerInitialized;
    private boolean sharedInstallListening;
    private boolean sharedInstallRunning;
    private boolean sharedInstallStarted;
    private boolean sharedInstallCompleted;
    private boolean releaseUpdateInFlight;
    private boolean serviceControlFocusPending;
    private String serviceControlComponentId;
    private String serviceControlTitle;
    private List<String> serviceControlServiceNames = new ArrayList<>();
    private List<String> serviceControlServiceRefs = new ArrayList<>();
    private long lastLiveLogRefreshUptimeMs;
    private long lastCompletionScanUptimeMs;
    private long lastOneClickStatusRefreshUptimeMs;
    private long lastReleaseProgressUpdateUptimeMs;
    private TerminalSession pendingTerminalUpdateSession;
    private MaintenanceManifest activeManifest;
    private String activeManifestError;
    private OpenHouseReleaseManifest checkedReleaseManifest;
    private String releaseUpdateStatusText;
    private SharedPreferences maintenancePreferences;
    private Object sharedInstallController;
    private Object sharedInstallListener;
    private Class<?> sharedInstallListenerClass;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Runnable sharedInstallProgressRefreshRunnable = new Runnable() {
        @Override
        public void run() {
            if (!sharedInstallListening || !sharedInstallRunning || isFinishing() || isDestroyed()) {
                return;
            }
            refreshSharedInstallState();
            scheduleSharedInstallProgressRefresh();
        }
    };
    private final Object terminalUpdateLock = new Object();
    private final Runnable terminalCompletionPollRunnable = new Runnable() {
        @Override
        public void run() {
            terminalCompletionPollScheduled = false;
            if (isFinishing() || isDestroyed() || !commandInFlight) {
                return;
            }

            try {
                refreshLiveLogThrottled(false);
                TerminalSession terminalSession = maintenanceSession == null
                    ? null
                    : maintenanceSession.getTerminalSession();
                if (terminalSession != null) {
                    inspectCompletionThrottled(terminalSession, false);
                } else {
                    inspectCurrentLogForCompletion();
                }
                requestOneClickStatusRefreshIfDue();
            } catch (Throwable throwable) {
                terminalFailureMessage = throwable.getClass().getSimpleName() + ": " + throwable.getMessage();
                Logger.logStackTraceWithMessage(LOG_TAG, "Failed to poll maintenance stage completion", throwable);
                refreshStatus();
            }

            if (commandInFlight) {
                scheduleTerminalCompletionPoll();
            }
        }
    };
    private final ExecutorService backgroundExecutor = Executors.newSingleThreadExecutor();
    private final EnumMap<StageAction, Button> stageButtons = new EnumMap<>(StageAction.class);
    private final EnumMap<StageAction, StagePresentation> stagePresentations = new EnumMap<>(StageAction.class);

    private final MaintenanceTerminalSessionClient terminalSessionClient = new MaintenanceTerminalSessionClient();
    private final MaintenanceTerminalViewClient terminalViewClient = new MaintenanceTerminalViewClient();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_maintenance_center);

        statusHeadlineView = findViewById(R.id.statusHeadline);
        statusBodyView = findViewById(R.id.statusBody);
        currentStageView = findViewById(R.id.currentStage);
        liveLogView = findViewById(R.id.liveLog);
        liveLogScrollView = findViewById(R.id.liveLogScroll);
        helpBodyView = findViewById(R.id.helpBody);
        terminalStatusView = findViewById(R.id.embeddedTerminalStatus);
        permissionRequirementHintView = findViewById(R.id.permissionRequirementHint);
        downloadSourceSummaryView = findViewById(R.id.downloadSourceSummary);
        maintenanceSourceSummaryView = findViewById(R.id.maintenanceSourceSummary);
        localMaintenanceWebSummaryView = findViewById(R.id.localMaintenanceWebSummary);
        releaseUpdateServerSummaryView = findViewById(R.id.releaseUpdateServerSummary);
        releaseUpdateStatusView = findViewById(R.id.releaseUpdateStatus);
        sharedInstallProgressView = findViewById(R.id.sharedInstallProgress);
        sharedInstallDetailView = findViewById(R.id.sharedInstallDetail);
        sharedInstallLogView = findViewById(R.id.sharedInstallLog);
        forceRestartSharedInstallButton = findViewById(R.id.buttonForceRestartSharedInstall);
        dynamicPluginSectionsContainer = findViewById(R.id.dynamicPluginSections);
        sharedInstallLogScrollView = findViewById(R.id.sharedInstallLogScroll);
        permissionBatteryButton = findViewById(R.id.buttonPermissionBattery);
        permissionOverlayButton = findViewById(R.id.buttonPermissionOverlay);
        permissionStorageButton = findViewById(R.id.buttonPermissionStorage);
        permissionStartupButton = findViewById(R.id.buttonPermissionStartup);
        disableBatteryRequirementSwitch = findViewById(R.id.switchDisableBatteryRequirement);
        configureDefaultPortButton = findViewById(R.id.buttonConfigureDefaultPort);
        configureDownloadSourceButton = findViewById(R.id.buttonConfigureDownloadSource);
        probeDownloadSourceButton = findViewById(R.id.buttonProbeDownloadSource);
        configureMaintenanceSourceButton = findViewById(R.id.buttonConfigureMaintenanceSource);
        refreshMaintenanceSourceButton = findViewById(R.id.buttonRefreshMaintenanceSource);
        configureReleaseServerButton = findViewById(R.id.buttonConfigureReleaseServer);
        checkReleaseUpdateButton = findViewById(R.id.buttonCheckReleaseUpdate);
        downloadReleaseUpdateButton = findViewById(R.id.buttonDownloadReleaseUpdate);
        restartEntryTerminalButton = findViewById(R.id.buttonRestartEntryTerminal);
        stageManualModeButton = findViewById(R.id.buttonStageManualMode);
        stageOneClickModeButton = findViewById(R.id.buttonStageOneClickMode);
        startOneClickStagesButton = findViewById(R.id.buttonStartOneClickStages);
        deepSeekKeyGuideButton = findViewById(R.id.buttonDeepSeekKeyGuide);
        deepSeekKeyConfigButton = findViewById(R.id.buttonDeepSeekKeyConfig);
        startButton = findViewById(R.id.buttonStart);
        restartButton = findViewById(R.id.buttonRestart);
        customPortButton = findViewById(R.id.buttonStartCustomPort);
        openMaintenanceWebButton = findViewById(R.id.buttonOpenMaintenanceWeb);
        stopMaintenanceWebButton = findViewById(R.id.buttonStopMaintenanceWeb);
        configureMaintenanceWebPortButton = findViewById(R.id.buttonConfigureMaintenanceWebPort);
        viewFullLogButton = findViewById(R.id.buttonViewFullLog);
        openBrowserButton = findViewById(R.id.buttonOpenBrowser);
        oneClickStageSummaryView = findViewById(R.id.oneClickStageSummary);
        oneClickPrepareItemView = findViewById(R.id.oneClickPrepareItem);
        oneClickUbuntuPackagesItemView = findViewById(R.id.oneClickUbuntuPackagesItem);
        oneClickOpenCodeItemView = findViewById(R.id.oneClickOpenCodeItem);
        oneClickCodexItemView = findViewById(R.id.oneClickCodexItem);
        oneClickClaudeCodeItemView = findViewById(R.id.oneClickClaudeCodeItem);
        oneClickSkillItemView = findViewById(R.id.oneClickSkillItem);
        oneClickStartItemView = findViewById(R.id.oneClickStartItem);
        oneClickStageItemsContainer = findViewById(R.id.oneClickStageItemsContainer);
        oneClickStagePanel = findViewById(R.id.oneClickStagePanel);
        stageActionsPanel = findViewById(R.id.stageActionsPanel);
        terminalContainer = findViewById(R.id.maintenanceTerminalContainer);
        returnHomeButton = findViewById(R.id.buttonReturnHome);
        maintenancePreferences = getSharedPreferences(PREFS_MAINTENANCE, MODE_PRIVATE);
        parseServiceControlIntent(getIntent());

        helpBodyView.setText(getString(R.string.help_body));
        currentStageView.setText(R.string.current_stage_placeholder);
        liveLogView.setText(R.string.result_placeholder);
        if (returnHomeButton != null) {
            if (shouldReturnToOnboarding()) {
                returnHomeButton.setText(R.string.openhouse_return_onboarding);
            }
            returnHomeButton.setOnClickListener(v -> returnToHome());
        }
        initializeSharedInstallController();

        bindPermissionButtons();
        bindExecutionModeButtons();
        bindReleaseUpdateButtons();
        bindStageButtons();
        initializeStagePresentations();
        configureDefaultPortButton.setVisibility(View.GONE);
        configureDownloadSourceButton.setOnClickListener(v -> showDownloadSourceModeDialog());
        probeDownloadSourceButton.setOnClickListener(v -> runOpenCodeSourceProbe(false));
        configureMaintenanceSourceButton.setOnClickListener(v -> showMaintenanceSourceDialog());
        refreshMaintenanceSourceButton.setOnClickListener(v -> refreshMaintenanceManifest(true));
        findViewById(R.id.buttonCopyOpenCodeUrl).setOnClickListener(v ->
            copyToClipboard(getString(R.string.button_copy_opencode_url), getOpenCodeUrl()));
        findViewById(R.id.buttonCopyDeepSeekKeyUrl).setOnClickListener(v ->
            copyToClipboard(getString(R.string.button_copy_deepseek_key_url), DEEPSEEK_API_KEYS_URL));
        viewFullLogButton.setOnClickListener(v -> openFullLog());
        updateLogButtonState();
        updateReleaseUpdateCard();
        updateMaintenanceSourceCard();
        refreshMaintenanceManifest(false);
        refreshStatus();
        requestStageStatusRefresh();
    }

    @Override
    protected void onResume() {
        super.onResume();
        startSharedInstallObservation();
        scheduleMaintenanceSessionInit();
        updateReleaseUpdateCard();
        refreshStatus();
        requestStageStatusRefresh();
        scheduleTerminalCompletionPoll();
    }

    @Override
    protected void onPause() {
        stopSharedInstallObservation();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        stopSharedInstallObservation();
        mainHandler.removeCallbacks(sharedInstallProgressRefreshRunnable);
        maintenanceSessionInitPosted = false;
        terminalCompletionPollScheduled = false;
        mainHandler.removeCallbacks(terminalCompletionPollRunnable);
        backgroundExecutor.shutdownNow();
        try {
            if (maintenanceSession != null && maintenanceSession.getTerminalSession() != null) {
                if (isPageOwnedTerminalIdle()) {
                    maintenanceSession.getTerminalSession().finishIfRunning();
                    maintenanceSession = null;
                }
            }
        } catch (Throwable throwable) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to finish maintenance terminal session on destroy", throwable);
        }
        super.onDestroy();
    }

    private void bindStageButtons() {
        bindStageButton(StageAction.PREPARE, R.id.buttonPrepare);
        bindStageButton(StageAction.TERMUX_PACKAGES, R.id.buttonTermuxPackages);
        bindStageButton(StageAction.INSTALL_UBUNTU, R.id.buttonInstallUbuntu);
        bindStageButton(StageAction.SYNC_OFFICIAL_DOCS, R.id.buttonSyncOfficialDocs);
        bindStageButton(StageAction.UBUNTU_PACKAGES, R.id.buttonUbuntuPackages);
        bindStageButton(StageAction.CONFIGURE_ENTRY_UBUNTU, R.id.buttonConfigureEntryUbuntu);
        bindStageButton(StageAction.INSTALL_NODE, R.id.buttonInstallNode);
        bindStageButton(StageAction.INSTALL_OPENCODE, R.id.buttonInstallOpenCode);
        bindStageButton(StageAction.INSTALL_CODEX, R.id.buttonInstallCodex);
        bindStageButton(StageAction.INSTALL_CLAUDE_CODE, R.id.buttonInstallClaudeCode);
        bindStageButton(StageAction.INSTALL_CLAUDE_CODE_UI, R.id.buttonInstallClaudeCodeUi);
        bindStageButton(StageAction.INSTALL_REASONIX, R.id.buttonInstallReasonix);
        bindStageButton(StageAction.START, R.id.buttonStart);
        bindStageButton(StageAction.RESTART, R.id.buttonRestart);
        bindStageButton(StageAction.REQUEST_DEEPSEEK_KEY, R.id.buttonDeepSeekKeyGuide);
        stageButtons.put(StageAction.CONFIGURE_DEEPSEEK, deepSeekKeyConfigButton);
        deepSeekKeyConfigButton.setOnClickListener(v -> showDeepSeekKeyConfigDialog());
        bindStageButton(StageAction.RESTART_ENTRY_TERMINAL, R.id.buttonRestartEntryTerminal);
        customPortButton.setOnClickListener(v -> showCustomPortDialog());
        openBrowserButton.setOnClickListener(v -> openBrowser());
        openMaintenanceWebButton.setOnClickListener(v -> startLocalMaintenanceWeb());
        stopMaintenanceWebButton.setOnClickListener(v -> stopLocalMaintenanceWeb());
        configureMaintenanceWebPortButton.setOnClickListener(v -> showLocalMaintenanceWebPortDialog());
    }

    private void bindPermissionButtons() {
        permissionBatteryButton.setOnClickListener(v -> requestBatteryOptimizationExemption());
        permissionOverlayButton.setOnClickListener(v -> openOverlayPermissionSettings());
        permissionStorageButton.setOnClickListener(v -> openStoragePermissionSettings());
        if (permissionStartupButton != null) {
            permissionStartupButton.setOnClickListener(v -> openStartupPermissionSettings());
        }
        disableBatteryRequirementSwitch.setChecked(!isBatteryRequirementEnabled());
        disableBatteryRequirementSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            maintenancePreferences.edit().putBoolean(PREF_DISABLE_BATTERY_REQUIREMENT, isChecked).apply();
            refreshStatus();
            applyStagePresentations();
        });
    }

    private void bindReleaseUpdateButtons() {
        if (configureReleaseServerButton != null) {
            configureReleaseServerButton.setOnClickListener(v -> showReleaseServerDialog());
        }
        if (checkReleaseUpdateButton != null) {
            checkReleaseUpdateButton.setOnClickListener(v -> checkReleaseUpdate(true));
        }
        if (downloadReleaseUpdateButton != null) {
            downloadReleaseUpdateButton.setOnClickListener(v -> downloadCheckedReleaseUpdate());
        }
    }

    private void showReleaseServerDialog() {
        EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        input.setSingleLine(false);
        input.setMinLines(2);
        input.setHint(getString(R.string.release_update_server_hint));
        input.setText(OpenHouseReleaseSettings.getServerUrl(this));
        input.setSelection(input.getText().length());

        new AlertDialog.Builder(this)
            .setTitle(R.string.release_update_server_dialog_title)
            .setView(input)
            .setNegativeButton(android.R.string.cancel, null)
            .setNeutralButton(R.string.release_update_clear_server, (dialog, which) -> {
                OpenHouseReleaseSettings.clearServerUrl(this);
                checkedReleaseManifest = null;
                setReleaseUpdateStatus(null);
                updateReleaseUpdateCard();
                Toast.makeText(this, R.string.release_update_server_cleared, Toast.LENGTH_SHORT).show();
            })
            .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                String value = input.getText() == null ? "" : input.getText().toString().trim();
                if (!OpenHouseReleaseSettings.isValidServerUrl(value)) {
                    Toast.makeText(this, R.string.release_update_server_invalid, Toast.LENGTH_LONG).show();
                    return;
                }
                OpenHouseReleaseSettings.setServerUrl(this, value);
                checkedReleaseManifest = null;
                setReleaseUpdateStatus(null);
                updateReleaseUpdateCard();
                Toast.makeText(this, R.string.release_update_server_saved, Toast.LENGTH_SHORT).show();
            })
            .show();
    }

    private void checkReleaseUpdate(boolean userInitiated) {
        if (releaseUpdateInFlight) {
            return;
        }
        if (isMaintenanceActionBlocked()) {
            showMaintenanceActionBlockedToast();
            updateReleaseUpdateCard();
            return;
        }

        String serverUrl = OpenHouseReleaseSettings.getServerUrl(this);
        final List<String> manifestUrls;
        try {
            manifestUrls = OpenHouseReleaseSettings.resolveManifestUrls(serverUrl, OpenHouseReleaseSettings.getChannel(this));
        } catch (OpenHouseReleaseException e) {
            checkedReleaseManifest = null;
            setReleaseUpdateStatus(e.getMessage());
            updateReleaseUpdateCard();
            if (userInitiated) {
                Toast.makeText(this, R.string.release_update_server_invalid, Toast.LENGTH_LONG).show();
            }
            return;
        }

        releaseUpdateInFlight = true;
        checkedReleaseManifest = null;
        setReleaseUpdateStatus(getString(R.string.release_update_status_checking, manifestUrls.get(0)));
        updateReleaseUpdateCard();

        backgroundExecutor.execute(() -> {
            OpenHouseReleaseManifest manifest = null;
            OpenHouseReleaseValidator.ManifestValidationResult validationResult = null;
            String error = null;
            OpenHouseReleaseDownloader downloader = new OpenHouseReleaseDownloader();
            for (String manifestUrl : manifestUrls) {
                try {
                    manifest = downloader.fetchManifest(manifestUrl);
                    validationResult = OpenHouseReleaseValidator.validateManifest(getApplicationContext(), manifest);
                    error = null;
                    break;
                } catch (Exception e) {
                    error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                    Logger.logStackTraceWithMessage(LOG_TAG, "Failed to check remote APK release manifest: " + manifestUrl, e);
                }
            }

            OpenHouseReleaseManifest finalManifest = manifest;
            OpenHouseReleaseValidator.ManifestValidationResult finalValidationResult = validationResult;
            String finalError = error;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                releaseUpdateInFlight = false;
                if (finalError != null) {
                    checkedReleaseManifest = null;
                    setReleaseUpdateStatus(getString(R.string.release_update_status_failed, finalError));
                    if (userInitiated) {
                        Toast.makeText(this, R.string.release_update_check_failed, Toast.LENGTH_SHORT).show();
                    }
                } else if (finalValidationResult != null && finalValidationResult.updateAvailable) {
                    checkedReleaseManifest = finalManifest;
                    setReleaseUpdateStatus(buildReleaseAvailableStatus(finalManifest));
                    if (userInitiated) {
                        Toast.makeText(this, R.string.release_update_check_available, Toast.LENGTH_SHORT).show();
                    }
                } else if (finalValidationResult != null) {
                    checkedReleaseManifest = null;
                    setReleaseUpdateStatus(finalValidationResult.message);
                    if (userInitiated) {
                        Toast.makeText(this, R.string.release_update_check_no_update, Toast.LENGTH_SHORT).show();
                    }
                }
                updateReleaseUpdateCard();
            });
        });
    }

    private void downloadCheckedReleaseUpdate() {
        final OpenHouseReleaseManifest manifest = checkedReleaseManifest;
        if (manifest == null) {
            checkReleaseUpdate(true);
            return;
        }
        if (releaseUpdateInFlight) {
            return;
        }
        if (isMaintenanceActionBlocked()) {
            showMaintenanceActionBlockedToast();
            updateReleaseUpdateCard();
            return;
        }
        if (!OpenHouseReleaseInstaller.canRequestPackageInstalls(this)) {
            showReleaseInstallPermissionDialog();
            return;
        }

        releaseUpdateInFlight = true;
        lastReleaseProgressUpdateUptimeMs = 0L;
        setReleaseUpdateStatus(getString(R.string.release_update_status_downloading, formatBytes(0), formatBytes(manifest.apkSizeBytes)));
        updateReleaseUpdateCard();

        OpenHouseReleaseDownloader.ProgressListener progressListener = (bytesRead, totalBytes) -> {
            long now = SystemClock.uptimeMillis();
            if (now - lastReleaseProgressUpdateUptimeMs < 500 && (totalBytes <= 0 || bytesRead < totalBytes)) {
                return;
            }
            lastReleaseProgressUpdateUptimeMs = now;
            runOnUiThread(() -> {
                if (!releaseUpdateInFlight || isFinishing() || isDestroyed()) return;
                String totalText = totalBytes > 0 ? formatBytes(totalBytes) : getString(R.string.release_update_size_unknown);
                setReleaseUpdateStatus(getString(R.string.release_update_status_downloading, formatBytes(bytesRead), totalText));
            });
        };

        backgroundExecutor.execute(() -> {
            OpenHouseReleaseDownloader.DownloadResult downloadResult = null;
            OpenHouseReleaseValidator.DownloadedApkInfo apkInfo = null;
            String error = null;
            try {
                OpenHouseReleaseDownloader downloader = new OpenHouseReleaseDownloader();
                File apkFile = OpenHouseReleaseInstaller.getDownloadFile(getApplicationContext());
                downloadResult = downloader.downloadApk(manifest, apkFile, progressListener);
                apkInfo = OpenHouseReleaseValidator.validateDownloadedApk(getApplicationContext(), manifest, downloadResult.apkFile);
            } catch (Exception e) {
                error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                Logger.logStackTraceWithMessage(LOG_TAG, "Failed to download or validate remote APK release", e);
            }

            OpenHouseReleaseDownloader.DownloadResult finalDownloadResult = downloadResult;
            OpenHouseReleaseValidator.DownloadedApkInfo finalApkInfo = apkInfo;
            String finalError = error;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                releaseUpdateInFlight = false;
                if (finalError != null) {
                    setReleaseUpdateStatus(getString(R.string.release_update_status_failed, finalError));
                    Toast.makeText(this, R.string.release_update_download_failed, Toast.LENGTH_SHORT).show();
                    updateReleaseUpdateCard();
                    return;
                }

                setReleaseUpdateStatus(getString(
                    R.string.release_update_ready_to_install,
                    finalApkInfo.versionName,
                    finalApkInfo.versionCode,
                    formatBytes(finalDownloadResult.bytesRead)
                ));
                updateReleaseUpdateCard();
                startReleaseInstaller(finalDownloadResult.apkFile);
            });
        });
    }

    private void startReleaseInstaller(File apkFile) {
        if (!OpenHouseReleaseInstaller.canRequestPackageInstalls(this)) {
            showReleaseInstallPermissionDialog();
            return;
        }

        try {
            Intent intent = OpenHouseReleaseInstaller.createInstallIntent(this, apkFile);
            startActivity(intent);
            setReleaseUpdateStatus(getString(R.string.release_update_installer_started));
        } catch (Exception e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to start APK installer", e);
            setReleaseUpdateStatus(getString(R.string.release_update_status_failed,
                e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
            Toast.makeText(this, R.string.release_update_installer_failed, Toast.LENGTH_SHORT).show();
        }
        updateReleaseUpdateCard();
    }

    private void showReleaseInstallPermissionDialog() {
        new AlertDialog.Builder(this)
            .setTitle(R.string.release_update_install_permission_title)
            .setMessage(R.string.release_update_install_permission_message)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.release_update_open_install_permission, (dialog, which) -> {
                try {
                    startActivity(OpenHouseReleaseInstaller.createUnknownSourcesSettingsIntent(this));
                } catch (Exception e) {
                    Logger.logStackTraceWithMessage(LOG_TAG, "Failed to open APK install permission settings", e);
                    Toast.makeText(this, R.string.release_update_install_permission_failed, Toast.LENGTH_SHORT).show();
                }
            })
            .show();
    }

    private void updateReleaseUpdateCard() {
        String serverUrl = OpenHouseReleaseSettings.getServerUrl(this);
        boolean hasServerUrl = serverUrl != null && !serverUrl.trim().isEmpty();
        boolean validServerUrl = hasServerUrl && OpenHouseReleaseSettings.isValidServerUrl(serverUrl);

        if (releaseUpdateServerSummaryView != null) {
            if (!hasServerUrl) {
                releaseUpdateServerSummaryView.setText(R.string.release_update_server_not_configured);
            } else {
                try {
                    releaseUpdateServerSummaryView.setText(getString(
                        R.string.release_update_server_summary,
                        serverUrl,
                        OpenHouseReleaseSettings.resolveManifestUrls(serverUrl, OpenHouseReleaseSettings.getChannel(this)).get(0)
                    ));
                } catch (OpenHouseReleaseException e) {
                    releaseUpdateServerSummaryView.setText(getString(
                        R.string.release_update_server_summary_invalid,
                        serverUrl,
                        e.getMessage()
                    ));
                }
            }
        }

        if (releaseUpdateStatusView != null) {
            if (releaseUpdateStatusText != null && !releaseUpdateStatusText.isEmpty()) {
                releaseUpdateStatusView.setText(releaseUpdateStatusText);
            } else if (checkedReleaseManifest != null) {
                releaseUpdateStatusView.setText(buildReleaseAvailableStatus(checkedReleaseManifest));
            } else if (!hasServerUrl) {
                releaseUpdateStatusView.setText(R.string.release_update_status_waiting_for_server);
            } else {
                releaseUpdateStatusView.setText(R.string.release_update_status_idle);
            }
        }

        setReleaseButtonEnabled(configureReleaseServerButton, !releaseUpdateInFlight);
        setReleaseButtonEnabled(checkReleaseUpdateButton, validServerUrl && !releaseUpdateInFlight && !isMaintenanceActionBlocked());
        setReleaseButtonEnabled(downloadReleaseUpdateButton, checkedReleaseManifest != null
            && !releaseUpdateInFlight
            && !isMaintenanceActionBlocked());
    }

    private void setReleaseUpdateStatus(String status) {
        releaseUpdateStatusText = status;
        if (releaseUpdateStatusView != null && status != null && !status.isEmpty()) {
            releaseUpdateStatusView.setText(status);
        }
    }

    private void setReleaseButtonEnabled(Button button, boolean enabled) {
        if (button == null) {
            return;
        }
        button.setEnabled(enabled);
        button.setAlpha(enabled ? 1.0f : 0.72f);
    }

    private String buildReleaseAvailableStatus(OpenHouseReleaseManifest manifest) {
        String releaseNotes = manifest.releaseNotes == null || manifest.releaseNotes.trim().isEmpty()
            ? getString(R.string.release_update_no_release_notes)
            : manifest.releaseNotes.trim();
        String runtimePayloadVersion = manifest.runtimePayloadVersion == null || manifest.runtimePayloadVersion.trim().isEmpty()
            ? getString(R.string.release_update_runtime_unknown)
            : manifest.runtimePayloadVersion.trim();
        return getString(
            R.string.release_update_available_status,
            manifest.latestVersionName,
            manifest.latestVersionCode,
            manifest.channel,
            runtimePayloadVersion,
            formatBytes(manifest.apkSizeBytes),
            manifest.forceUpdate ? getString(R.string.release_update_force_yes) : getString(R.string.release_update_force_no),
            releaseNotes
        );
    }

    private String formatBytes(long bytes) {
        if (bytes < 0) {
            return getString(R.string.release_update_size_unknown);
        }
        if (bytes < 1024) {
            return bytes + " B";
        }
        double value = bytes / 1024.0;
        if (value < 1024.0) {
            return String.format(Locale.US, "%.1f KB", value);
        }
        value = value / 1024.0;
        if (value < 1024.0) {
            return String.format(Locale.US, "%.1f MB", value);
        }
        return String.format(Locale.US, "%.1f GB", value / 1024.0);
    }

    private void showRestartEntryTerminalDialog() {
        new AlertDialog.Builder(this)
            .setTitle(R.string.entry_terminal_restart_dialog_title)
            .setMessage(R.string.entry_terminal_restart_dialog_message)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.entry_terminal_restart_dialog_confirm, (dialog, which) -> restartEntryTerminalSession())
            .show();
    }

    private void restartEntryTerminalSession() {
        Intent intent = TermuxActivity.newInstance(this);
        intent.putExtra(TermuxActivity.EXTRA_RESTART_ENTRY_SESSION, true);
        startActivity(intent);
    }

    private void showMaintenanceSourceDialog() {
        if (isMaintenanceActionBlocked()) {
            showMaintenanceActionBlockedToast();
            return;
        }

        String[] sourceChoices = new String[] {
            getString(R.string.plugin_source_choice_bundled),
            getString(R.string.plugin_source_choice_user),
            getString(R.string.plugin_source_choice_online)
        };

        new AlertDialog.Builder(this)
            .setTitle(R.string.maintenance_source_dialog_title)
            .setItems(sourceChoices, (dialog, which) -> {
                if (which == 0) {
                    setPluginSourceMode(PluginSourceMode.BUNDLED);
                    refreshMaintenanceManifest(true);
                } else if (which == 1) {
                    showUserPluginPathDialog();
                } else {
                    showOnlinePluginUrlDialog();
                }
            })
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    private void showOnlinePluginUrlDialog() {
        EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        input.setSingleLine(false);
        input.setMinLines(2);
        input.setHint(getString(R.string.maintenance_source_dialog_hint));
        input.setText(getOnlinePluginUrl());
        input.setSelection(input.getText().length());

        new AlertDialog.Builder(this)
            .setTitle(R.string.plugin_source_online_title)
            .setView(input)
            .setNegativeButton(android.R.string.cancel, null)
            .setNeutralButton(R.string.maintenance_source_reset, (dialog, which) -> {
                maintenancePreferences.edit()
                    .putString(PREF_MAINTENANCE_PLUGIN_MODE, PluginSourceMode.ONLINE.prefValue)
                    .remove(PREF_MAINTENANCE_SOURCE_URL)
                    .apply();
                refreshMaintenanceManifest(true);
            })
            .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                String value = input.getText() == null ? "" : input.getText().toString().trim();
                if (!isValidManifestUrl(value)) {
                    Toast.makeText(this, R.string.maintenance_source_invalid, Toast.LENGTH_LONG).show();
                    return;
                }
                maintenancePreferences.edit()
                    .putString(PREF_MAINTENANCE_PLUGIN_MODE, PluginSourceMode.ONLINE.prefValue)
                    .putString(PREF_MAINTENANCE_SOURCE_URL, value)
                    .apply();
                Toast.makeText(this, R.string.maintenance_source_saved, Toast.LENGTH_SHORT).show();
                refreshMaintenanceManifest(true);
            })
            .show();
    }

    private void showUserPluginPathDialog() {
        EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        input.setSingleLine(false);
        input.setMinLines(2);
        input.setHint(DEFAULT_USER_PLUGIN_PATH);
        input.setText(getUserPluginPath());
        input.setSelection(input.getText().length());

        new AlertDialog.Builder(this)
            .setTitle(R.string.plugin_source_user_title)
            .setMessage(R.string.plugin_source_user_message)
            .setView(input)
            .setNegativeButton(android.R.string.cancel, null)
            .setNeutralButton(R.string.plugin_source_use_default_path, (dialog, which) -> {
                maintenancePreferences.edit()
                    .putString(PREF_MAINTENANCE_PLUGIN_MODE, PluginSourceMode.USER.prefValue)
                    .remove(PREF_USER_PLUGIN_PATH)
                    .apply();
                refreshMaintenanceManifest(true);
            })
            .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                String value = input.getText() == null ? "" : input.getText().toString().trim();
                if (value.isEmpty()) {
                    Toast.makeText(this, R.string.plugin_source_user_path_invalid, Toast.LENGTH_LONG).show();
                    return;
                }
                maintenancePreferences.edit()
                    .putString(PREF_MAINTENANCE_PLUGIN_MODE, PluginSourceMode.USER.prefValue)
                    .putString(PREF_USER_PLUGIN_PATH, value)
                    .apply();
                Toast.makeText(this, R.string.maintenance_source_saved, Toast.LENGTH_SHORT).show();
                refreshMaintenanceManifest(true);
            })
            .show();
    }

    private void setPluginSourceMode(PluginSourceMode mode) {
        maintenancePreferences.edit().putString(PREF_MAINTENANCE_PLUGIN_MODE, mode.prefValue).apply();
        Toast.makeText(this, R.string.maintenance_source_saved, Toast.LENGTH_SHORT).show();
    }

    private PluginSourceMode getPluginSourceMode() {
        return PluginSourceMode.fromPrefValue(
            maintenancePreferences.getString(PREF_MAINTENANCE_PLUGIN_MODE, PluginSourceMode.BUNDLED.prefValue)
        );
    }

    private String getOnlinePluginUrl() {
        return maintenancePreferences.getString(PREF_MAINTENANCE_SOURCE_URL, DEFAULT_MAINTENANCE_MANIFEST_URL);
    }

    private String getUserPluginPath() {
        return maintenancePreferences.getString(PREF_USER_PLUGIN_PATH, DEFAULT_USER_PLUGIN_PATH);
    }

    private boolean isValidManifestUrl(String value) {
        return isHttpUrl(value);
    }

    private static boolean isHttpUrl(String value) {
        if (value == null || value.isEmpty()) return false;
        try {
            URL url = new URL(value);
            return "https".equalsIgnoreCase(url.getProtocol())
                || "http".equalsIgnoreCase(url.getProtocol());
        } catch (Exception e) {
            return false;
        }
    }

    private void refreshMaintenanceManifest(boolean userInitiated) {
        final PluginSourceMode pluginSourceMode = getPluginSourceMode();
        if (pluginSourceMode == PluginSourceMode.ONLINE && !isValidManifestUrl(getOnlinePluginUrl())) {
            activeManifest = null;
            activeManifestError = getString(R.string.maintenance_source_invalid);
            updateMaintenanceSourceCard();
            renderDynamicPluginSections();
            return;
        }

        if (refreshMaintenanceSourceButton != null) {
            refreshMaintenanceSourceButton.setEnabled(false);
            refreshMaintenanceSourceButton.setAlpha(0.78f);
        }

        backgroundExecutor.execute(() -> {
            MaintenanceManifest manifest = null;
            String error = null;
            try {
                manifest = loadMaintenanceManifest(pluginSourceMode);
            } catch (Exception e) {
                error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                Logger.logStackTraceWithMessage(LOG_TAG, "Failed to refresh maintenance manifest", e);
            }

            MaintenanceManifest finalManifest = manifest;
            String finalError = error;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                activeManifest = finalManifest;
                activeManifestError = finalError;
                updateMaintenanceSourceCard();
                renderDynamicPluginSections();
                applyStagePresentations();
                updateExecutionModeViews();
                if (userInitiated) {
                    Toast.makeText(this,
                        finalManifest != null ? R.string.maintenance_source_refresh_ok : R.string.maintenance_source_refresh_failed,
                        Toast.LENGTH_SHORT).show();
                }
            });
        });
    }

    private MaintenanceManifest loadMaintenanceManifest(PluginSourceMode mode) throws IOException, JSONException {
        if (mode == PluginSourceMode.BUNDLED) {
            String assetSource = "asset://" + BUNDLED_MAINTENANCE_PLUGIN_ASSET;
            return MaintenanceManifest.fromJson(assetSource, loadAssetText(BUNDLED_MAINTENANCE_PLUGIN_ASSET));
        }
        if (mode == PluginSourceMode.USER) {
            File userPluginFile = ensureUserPluginFile();
            return MaintenanceManifest.fromJson(userPluginFile.getAbsolutePath(), readTextFile(userPluginFile));
        }
        return fetchMaintenanceManifest(getOnlinePluginUrl());
    }

    private File ensureUserPluginFile() throws IOException {
        File userPluginFile = new File(getUserPluginPath());
        if (userPluginFile.isFile()) {
            return userPluginFile;
        }
        File parentFile = userPluginFile.getParentFile();
        if (parentFile != null && !parentFile.isDirectory() && !parentFile.mkdirs()) {
            throw new IOException("failed to create plugin directory: " + parentFile.getAbsolutePath());
        }
        try (FileOutputStream outputStream = new FileOutputStream(userPluginFile)) {
            outputStream.write(loadAssetText(BUNDLED_MAINTENANCE_PLUGIN_ASSET).getBytes(StandardCharsets.UTF_8));
        }
        return userPluginFile;
    }

    private String readTextFile(File file) throws IOException {
        StringBuilder builder = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                builder.append(line).append('\n');
                if (builder.length() > 200000) {
                    throw new IOException("manifest too large");
                }
            }
        }
        return builder.toString();
    }

    private MaintenanceManifest fetchMaintenanceManifest(String manifestUrl) throws IOException, JSONException {
        HttpURLConnection connection = (HttpURLConnection) new URL(manifestUrl).openConnection();
        connection.setConnectTimeout(MANIFEST_CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(MANIFEST_READ_TIMEOUT_MS);
        connection.setInstanceFollowRedirects(true);
        connection.setRequestProperty("Accept", "application/json");

        int responseCode = connection.getResponseCode();
        if (responseCode < 200 || responseCode >= 300) {
            throw new IOException("HTTP " + responseCode);
        }

        StringBuilder builder = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                builder.append(line).append('\n');
                if (builder.length() > 200000) {
                    throw new IOException("manifest too large");
                }
            }
        } finally {
            connection.disconnect();
        }

        return MaintenanceManifest.fromJson(manifestUrl, builder.toString());
    }

    private void updateMaintenanceSourceCard() {
        if (maintenanceSourceSummaryView == null) return;
        PluginSourceMode pluginSourceMode = getPluginSourceMode();
        String sourceLocation = getMaintenanceSourceLocation(pluginSourceMode);
        if (activeManifest != null) {
            maintenanceSourceSummaryView.setText(getString(
                R.string.maintenance_source_summary,
                getString(pluginSourceMode.labelRes) + " · " + activeManifest.sourceName,
                activeManifest.version,
                sourceLocation
            ));
        } else if (activeManifestError != null && !activeManifestError.isEmpty()) {
            maintenanceSourceSummaryView.setText(getString(
                R.string.maintenance_source_summary_failed,
                getString(pluginSourceMode.labelRes) + "\n" + sourceLocation,
                activeManifestError
            ));
        } else {
            maintenanceSourceSummaryView.setText(R.string.maintenance_source_summary_loading);
        }

        if (refreshMaintenanceSourceButton != null) {
            refreshMaintenanceSourceButton.setEnabled(!isMaintenanceActionBlocked());
            refreshMaintenanceSourceButton.setAlpha(refreshMaintenanceSourceButton.isEnabled() ? 1.0f : 0.78f);
        }
    }

    private void renderDynamicPluginSections() {
        if (dynamicPluginSectionsContainer == null) return;
        dynamicPluginSectionsContainer.removeAllViews();
        boolean hasServiceControl = hasServiceControlTargets();
        if (!hasServiceControl && (activeManifest == null || activeManifest.dynamicSections.isEmpty())) {
            dynamicPluginSectionsContainer.setVisibility(View.GONE);
            return;
        }

        dynamicPluginSectionsContainer.setVisibility(View.VISIBLE);
        if (hasServiceControl) {
            serviceControlPanelView = createServiceControlPanel();
            dynamicPluginSectionsContainer.addView(serviceControlPanelView);
            focusServiceControlPanelIfNeeded();
        }
        if (activeManifest != null) {
            for (DynamicSection section : activeManifest.dynamicSections) {
                View sectionView = createDynamicSectionView(section);
                if (sectionView != null) {
                    dynamicPluginSectionsContainer.addView(sectionView);
                }
            }
        }
    }

    private boolean hasServiceControlTargets() {
        return !serviceControlServiceNames.isEmpty() || !serviceControlServiceRefs.isEmpty();
    }

    private void parseServiceControlIntent(Intent intent) {
        serviceControlComponentId = "";
        serviceControlTitle = "";
        serviceControlServiceNames = new ArrayList<>();
        serviceControlServiceRefs = new ArrayList<>();
        serviceControlFocusPending = false;
        if (intent == null) {
            return;
        }

        serviceControlComponentId = sanitizeServiceTarget(intent.getStringExtra(EXTRA_SERVICE_CONTROL_COMPONENT_ID));
        serviceControlTitle = safeTrim(intent.getStringExtra(EXTRA_SERVICE_CONTROL_TITLE));
        for (String value : readIntentStringList(intent, EXTRA_SERVICE_CONTROL_SERVICE_NAMES)) {
            String serviceId = sanitizeServiceTarget(value);
            if (!serviceId.isEmpty() && !serviceControlServiceNames.contains(serviceId)) {
                serviceControlServiceNames.add(serviceId);
            }
        }
        for (String value : readIntentStringList(intent, EXTRA_SERVICE_CONTROL_SERVICE_REFS)) {
            String ref = sanitizeServiceManagerRef(value);
            if (ref.isEmpty() || serviceControlServiceRefs.contains(ref)) {
                continue;
            }
            serviceControlServiceRefs.add(ref);
            String serviceId = serviceIdFromServiceManagerRef(ref);
            if (!serviceId.isEmpty() && !serviceControlServiceNames.contains(serviceId)) {
                serviceControlServiceNames.add(serviceId);
            }
        }
        serviceControlFocusPending = hasServiceControlTargets();
    }

    private List<String> readIntentStringList(Intent intent, String key) {
        if (intent == null || key == null || key.isEmpty() || intent.getExtras() == null) {
            return new ArrayList<>();
        }
        List<String> out = new ArrayList<>();
        Object raw = intent.getExtras().get(key);
        if (raw instanceof String[]) {
            for (String value : (String[]) raw) {
                collectSplitValues(value, out);
            }
        } else if (raw instanceof ArrayList) {
            ArrayList<?> values = (ArrayList<?>) raw;
            for (Object value : values) {
                if (value instanceof String) {
                    collectSplitValues((String) value, out);
                }
            }
        } else {
            collectSplitValues(intent.getStringExtra(key), out);
        }
        return out;
    }

    private void collectSplitValues(String raw, List<String> out) {
        if (raw == null || out == null) {
            return;
        }
        for (String part : raw.split(",")) {
            String value = safeTrim(part);
            if (!value.isEmpty() && !out.contains(value)) {
                out.add(value);
            }
        }
    }

    private View createServiceControlPanel() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setFocusable(true);
        panel.setFocusableInTouchMode(true);
        panel.setBackgroundResource(R.drawable.panel_bg);
        panel.setPadding(dp(16), dp(16), dp(16), dp(16));
        LinearLayout.LayoutParams panelParams = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        );
        panelParams.topMargin = dp(16);
        panel.setLayoutParams(panelParams);

        TextView titleView = new TextView(this);
        titleView.setText("服务控制：" + safeServiceControlTitle());
        titleView.setTextColor(ContextCompat.getColor(this, R.color.textPrimary));
        titleView.setTextSize(18);
        titleView.setTypeface(titleView.getTypeface(), android.graphics.Typeface.BOLD);
        panel.addView(titleView);

        TextView descriptionView = createDynamicBodyText(
            "来自组件注册的 service-manager target。这里不会执行 maintainer shell，只调用本机 service-manager REST API。"
                + "\n服务端：" + SERVICE_MANAGER_BASE_URL);
        LinearLayout.LayoutParams descriptionParams = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        );
        descriptionParams.topMargin = dp(8);
        panel.addView(descriptionView, descriptionParams);

        serviceControlStatusView = createDynamicBodyText("等待操作。");
        LinearLayout.LayoutParams statusParams = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        );
        statusParams.topMargin = dp(10);
        panel.addView(serviceControlStatusView, statusParams);

        for (String serviceId : serviceControlServiceNames) {
            addServiceControlTarget(panel, serviceId);
        }
        return panel;
    }

    private void addServiceControlTarget(LinearLayout panel, String serviceId) {
        if (panel == null || serviceId == null || serviceId.isEmpty()) {
            return;
        }
        TextView serviceTitleView = createDynamicBodyText("服务：" + serviceId);
        serviceTitleView.setTextColor(ContextCompat.getColor(this, R.color.textPrimary));
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        );
        titleParams.topMargin = dp(14);
        panel.addView(serviceTitleView, titleParams);

        addServiceButtonRow(panel,
            serviceControlButton("状态", v -> refreshServiceManagerStatus(serviceId)),
            serviceControlButton("启动", v -> runServiceManagerAction(serviceId, "start")));
        addServiceButtonRow(panel,
            serviceControlButton("停止", v -> runServiceManagerAction(serviceId, "stop")),
            serviceControlButton("重启", v -> runServiceManagerAction(serviceId, "restart")));
        addServiceButtonRow(panel,
            serviceControlButton("修复", v -> runServiceManagerAction(serviceId, "repair")),
            serviceControlButton("日志", v -> fetchServiceManagerLogs(serviceId)));
    }

    private Button serviceControlButton(String text, View.OnClickListener listener) {
        Button button = new Button(this);
        button.setAllCaps(false);
        button.setText(text);
        button.setTextSize(13);
        button.setEnabled(!isMaintenanceActionBlocked());
        button.setAlpha(button.isEnabled() ? 1.0f : 0.78f);
        button.setOnClickListener(listener);
        return button;
    }

    private void addServiceButtonRow(LinearLayout parent, Button first, Button second) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        );
        rowParams.topMargin = dp(8);

        row.addView(first, new LinearLayout.LayoutParams(0, dp(44), 1));
        LinearLayout.LayoutParams secondParams = new LinearLayout.LayoutParams(0, dp(44), 1);
        secondParams.leftMargin = dp(8);
        row.addView(second, secondParams);
        parent.addView(row, rowParams);
    }

    private void focusServiceControlPanelIfNeeded() {
        if (!serviceControlFocusPending || serviceControlPanelView == null) {
            return;
        }
        serviceControlFocusPending = false;
        serviceControlPanelView.post(() -> {
            serviceControlPanelView.requestFocus();
            if (serviceControlPanelView.getParent() instanceof ViewGroup) {
                ((ViewGroup) serviceControlPanelView.getParent()).requestChildFocus(
                    serviceControlPanelView,
                    serviceControlPanelView);
            }
        });
    }

    private String safeServiceControlTitle() {
        if (!serviceControlTitle.isEmpty()) {
            return serviceControlTitle;
        }
        if (serviceControlComponentId != null && !serviceControlComponentId.isEmpty()) {
            return serviceControlComponentId;
        }
        return "注册组件";
    }

    private void refreshServiceManagerStatus(String serviceId) {
        String cleanServiceId = sanitizeServiceTarget(serviceId);
        if (cleanServiceId.isEmpty()) {
            setServiceControlStatus("服务 ID 无效。");
            return;
        }
        setServiceControlStatus("正在读取 " + cleanServiceId + " 状态...");
        backgroundExecutor.execute(() -> {
            String message;
            try {
                ServiceManagerResponse response = serviceManagerRequest(
                    "GET",
                    "/api/v1/services/" + cleanServiceId + "/status");
                if (!response.isSuccess()) {
                    message = "状态读取失败：" + cleanServiceId + "，HTTP " + response.code + "\n" + response.body;
                } else {
                    JSONObject json = new JSONObject(response.body);
                    String state = json.optString("state", "unknown");
                    String provider = json.optString("provider", "");
                    String detail = json.optString("message", "");
                    String pid = json.has("pid") && !json.isNull("pid") ? String.valueOf(json.optInt("pid")) : "";
                    message = cleanServiceId + " 状态：" + state
                        + (provider.isEmpty() ? "" : "；provider=" + provider)
                        + (pid.isEmpty() ? "" : "；pid=" + pid)
                        + (detail.isEmpty() ? "" : "\n" + detail);
                }
            } catch (Exception e) {
                message = "状态读取失败：" + cleanServiceId + "\n" + e.getMessage();
                Logger.logStackTraceWithMessage(LOG_TAG, "Failed to query service-manager status", e);
            }
            String finalMessage = message;
            runOnUiThread(() -> setServiceControlStatus(finalMessage));
        });
    }

    private void runServiceManagerAction(String serviceId, String action) {
        String cleanServiceId = sanitizeServiceTarget(serviceId);
        String cleanAction = sanitizeServiceAction(action);
        if (cleanServiceId.isEmpty() || cleanAction.isEmpty()) {
            setServiceControlStatus("服务 ID 或动作无效。");
            return;
        }
        setServiceControlStatus("正在请求 " + cleanServiceId + " " + serviceActionLabel(cleanAction) + "...");
        backgroundExecutor.execute(() -> {
            String message;
            try {
                ServiceManagerResponse response = serviceManagerRequest(
                    "POST",
                    "/api/v1/services/" + cleanServiceId + "/" + cleanAction);
                if (response.isSuccess()) {
                    message = cleanServiceId + " 已提交 " + serviceActionLabel(cleanAction) + " 请求。";
                } else {
                    message = cleanServiceId + " " + serviceActionLabel(cleanAction)
                        + " 失败：HTTP " + response.code + "\n" + response.body;
                }
            } catch (Exception e) {
                message = cleanServiceId + " " + serviceActionLabel(cleanAction) + " 失败。\n" + e.getMessage();
                Logger.logStackTraceWithMessage(LOG_TAG, "Failed to run service-manager action", e);
            }
            String finalMessage = message;
            runOnUiThread(() -> setServiceControlStatus(finalMessage));
        });
    }

    private void fetchServiceManagerLogs(String serviceId) {
        String cleanServiceId = sanitizeServiceTarget(serviceId);
        if (cleanServiceId.isEmpty()) {
            setServiceControlStatus("服务 ID 无效。");
            return;
        }
        setServiceControlStatus("正在读取 " + cleanServiceId + " 日志...");
        backgroundExecutor.execute(() -> {
            String message;
            try {
                ServiceManagerResponse response = serviceManagerRequest(
                    "GET",
                    "/api/v1/services/" + cleanServiceId + "/logs?limit=80");
                if (!response.isSuccess()) {
                    message = "日志读取失败：" + cleanServiceId + "，HTTP " + response.code + "\n" + response.body;
                } else {
                    JSONArray entries = new JSONArray(response.body);
                    StringBuilder builder = new StringBuilder();
                    builder.append(cleanServiceId).append(" 最近日志：");
                    int start = Math.max(0, entries.length() - 20);
                    for (int i = start; i < entries.length(); i++) {
                        JSONObject item = entries.optJSONObject(i);
                        if (item == null) {
                            continue;
                        }
                        builder.append('\n')
                            .append(item.optString("time", ""))
                            .append(' ')
                            .append(item.optString("stream", ""))
                            .append(" | ")
                            .append(item.optString("message", ""));
                    }
                    if (entries.length() == 0) {
                        builder.append("\n暂无日志。");
                    }
                    message = trimForStatus(builder.toString());
                }
            } catch (Exception e) {
                message = "日志读取失败：" + cleanServiceId + "\n" + e.getMessage();
                Logger.logStackTraceWithMessage(LOG_TAG, "Failed to query service-manager logs", e);
            }
            String finalMessage = message;
            runOnUiThread(() -> setServiceControlStatus(finalMessage));
        });
    }

    private ServiceManagerResponse serviceManagerRequest(String method, String path) throws IOException, JSONException {
        String token = resolveServiceManagerToken();
        if (token.isEmpty()) {
            throw new IOException("找不到 service-manager token。请先完成运行栈安装或启动 service-manager。");
        }
        HttpURLConnection connection = (HttpURLConnection) new URL(SERVICE_MANAGER_BASE_URL + path).openConnection();
        connection.setConnectTimeout(2500);
        connection.setReadTimeout(7000);
        connection.setRequestMethod(method);
        connection.setRequestProperty("Authorization", "Bearer " + token);
        connection.setRequestProperty("Accept", "application/json");
        int code = connection.getResponseCode();
        String body = readConnectionBody(connection, code >= 400);
        connection.disconnect();
        return new ServiceManagerResponse(code, body);
    }

    private String resolveServiceManagerToken() throws IOException, JSONException {
        return ServiceManagerClient.resolveTokenForBaseUrl(SERVICE_MANAGER_BASE_URL);
    }

    private String readConnectionBody(HttpURLConnection connection, boolean errorBody) throws IOException {
        InputStream inputStream = errorBody ? connection.getErrorStream() : connection.getInputStream();
        if (inputStream == null) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                builder.append(line).append('\n');
                if (builder.length() > LOG_CHAR_LIMIT) {
                    builder.append("\n...输出过长，已截断。");
                    break;
                }
            }
        }
        return builder.toString().trim();
    }

    private void setServiceControlStatus(String text) {
        if (serviceControlStatusView != null) {
            serviceControlStatusView.setText(text == null ? "" : text);
        }
    }

    private String serviceActionLabel(String action) {
        switch (action) {
            case "start":
                return "启动";
            case "stop":
                return "停止";
            case "restart":
                return "重启";
            case "repair":
                return "修复";
            default:
                return action;
        }
    }

    private String sanitizeServiceAction(String action) {
        String value = safeTrim(action).toLowerCase(Locale.US);
        if ("start".equals(value) || "stop".equals(value) || "restart".equals(value) || "repair".equals(value)) {
            return value;
        }
        return "";
    }

    private String sanitizeServiceTarget(String value) {
        String trimmed = safeTrim(value);
        if (trimmed.isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < trimmed.length(); i++) {
            char current = trimmed.charAt(i);
            if ((current >= 'a' && current <= 'z')
                || (current >= 'A' && current <= 'Z')
                || (current >= '0' && current <= '9')
                || current == '_'
                || current == '-'
                || current == '.') {
                builder.append(current);
            }
        }
        return builder.toString();
    }

    private String sanitizeServiceManagerRef(String ref) {
        String trimmed = safeTrim(ref);
        if (!trimmed.startsWith("service-manager://")) {
            return "";
        }
        for (int i = 0; i < trimmed.length(); i++) {
            char current = trimmed.charAt(i);
            if (current <= 0x20 || current == '"' || current == '\'' || current == '\\') {
                return "";
            }
        }
        return trimmed;
    }

    private String serviceIdFromServiceManagerRef(String ref) {
        String trimmed = sanitizeServiceManagerRef(ref);
        if (trimmed.isEmpty()) {
            return "";
        }
        String prefix = "service-manager://services/";
        if (trimmed.startsWith(prefix)) {
            return sanitizeServiceTarget(trimmed.substring(prefix.length()));
        }
        String actionPrefix = "service-manager://actions/";
        if (trimmed.startsWith(actionPrefix)) {
            String target = trimmed.substring(actionPrefix.length());
            int dotIndex = target.lastIndexOf('.');
            return sanitizeServiceTarget(dotIndex > 0 ? target.substring(0, dotIndex) : target);
        }
        return "";
    }

    private String safeTrim(String value) {
        return value == null ? "" : value.trim();
    }

    private String trimForStatus(String value) {
        if (value == null) {
            return "";
        }
        if (value.length() <= 8000) {
            return value;
        }
        return value.substring(0, 8000) + "\n...输出过长，已截断。";
    }

    private View createDynamicSectionView(DynamicSection section) {
        if (!"actions".equals(section.type) && !"setting".equals(section.type)) {
            return null;
        }

        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackgroundResource(R.drawable.panel_bg);
        panel.setPadding(dp(16), dp(16), dp(16), dp(16));
        LinearLayout.LayoutParams panelParams = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        );
        panelParams.topMargin = dp(16);
        panel.setLayoutParams(panelParams);

        TextView titleView = new TextView(this);
        titleView.setText(section.title);
        titleView.setTextColor(ContextCompat.getColor(this, R.color.textPrimary));
        titleView.setTextSize(18);
        titleView.setTypeface(titleView.getTypeface(), android.graphics.Typeface.BOLD);
        panel.addView(titleView);

        if (!section.description.isEmpty()) {
            TextView descriptionView = createDynamicBodyText(section.description);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            );
            params.topMargin = dp(8);
            descriptionView.setLayoutParams(params);
            panel.addView(descriptionView);
        }

        for (DynamicItem item : section.items) {
            if ("single_choice".equals(item.type)) {
                addDynamicSingleChoice(panel, item);
            } else {
                addDynamicActionButton(panel, item.label, item.description, item.id, item.action);
            }
        }

        return panel;
    }

    private TextView createDynamicBodyText(String text) {
        TextView textView = new TextView(this);
        textView.setText(text);
        textView.setTextColor(ContextCompat.getColor(this, R.color.textSecondary));
        textView.setTextSize(13);
        textView.setLineSpacing(dp(2), 1.0f);
        return textView;
    }

    private void addDynamicSingleChoice(LinearLayout panel, DynamicItem item) {
        TextView labelView = createDynamicBodyText(item.label);
        LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        );
        labelParams.topMargin = dp(12);
        labelView.setLayoutParams(labelParams);
        panel.addView(labelView);

        for (int i = 0; i < item.options.size(); i++) {
            DynamicOption option = item.options.get(i);
            String slug = sanitizeDynamicSlug(item.id + "_" + i);
            addDynamicActionButton(panel, option.label, option.description, slug, option.action);
        }
    }

    private void addDynamicActionButton(LinearLayout panel, String label, String description, String slug, BootstrapAction action) {
        if (action == null) return;
        Button button = new Button(this);
        button.setAllCaps(false);
        button.setText(description == null || description.isEmpty() ? label : label + "\n" + description);
        button.setEnabled(!isMaintenanceActionBlocked());
        button.setAlpha(button.isEnabled() ? 1.0f : 0.78f);
        button.setTextColor(ContextCompat.getColor(this, R.color.stageReadyText));
        button.setBackgroundTintList(ColorStateList.valueOf(ContextCompat.getColor(this, R.color.stageReady)));
        button.setOnClickListener(v -> runRemoteBootstrapAction(sanitizeDynamicSlug(slug), label, action));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        );
        params.topMargin = dp(10);
        button.setLayoutParams(params);
        panel.addView(button);
    }

    private String sanitizeDynamicSlug(String value) {
        if (value == null || value.isEmpty()) return "dynamic_action";
        String slug = value.replaceAll("[^a-zA-Z0-9_-]", "_");
        return slug.isEmpty() ? "dynamic_action" : slug;
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private String getMaintenanceSourceLocation(PluginSourceMode mode) {
        if (mode == PluginSourceMode.BUNDLED) {
            return "asset://" + BUNDLED_MAINTENANCE_PLUGIN_ASSET;
        }
        if (mode == PluginSourceMode.USER) {
            return getUserPluginPath();
        }
        return getOnlinePluginUrl();
    }

    private void bindExecutionModeButtons() {
        stageManualModeButton.setOnClickListener(v -> setOneClickStageMode(false));
        stageOneClickModeButton.setOnClickListener(v -> setOneClickStageMode(true));
        startOneClickStagesButton.setOnClickListener(v -> {
            if (oneClickStagesInFlight) {
                stopOneClickStages(getString(R.string.one_click_stage_toast_stopped));
                return;
            }
            startOneClickStages();
        });
        if (forceRestartSharedInstallButton != null) {
            forceRestartSharedInstallButton.setOnClickListener(v -> confirmForceRestartSharedInstall());
        }
        setOneClickStageMode(true);
    }

    private void setOneClickStageMode(boolean enabled) {
        oneClickStageMode = enabled;
        updateExecutionModeViews();
    }

    private void startOneClickStages() {
        if (sharedInstallRunning) {
            Toast.makeText(this, "主界面安装正在运行，本页只显示同一个安装进度。", Toast.LENGTH_SHORT).show();
            refreshSharedInstallState();
            return;
        }

        if (commandInFlight) {
            Toast.makeText(this, R.string.command_busy, Toast.LENGTH_SHORT).show();
            return;
        }

        if (isBatteryRequirementBlocking()) {
            currentStageView.setText(getString(R.string.permission_requirement_state_required));
            Toast.makeText(this, R.string.permission_battery_required_toast, Toast.LENGTH_LONG).show();
            return;
        }

        oneClickStagesInFlight = true;
        oneClickUseBundledStages = true;
        currentStageUsedRemote = false;
        setOneClickStageMode(true);
        if (shouldUseRemoteBootstrap()
            && activeManifest != null
            && activeManifest.bootstrapUrl != null
            && isHttpUrl(activeManifest.bootstrapUrl)) {
            startOneClickRemoteProbe();
        } else {
            oneClickUseBundledStages = true;
            oneClickStageSummaryView.setText(getString(R.string.one_click_stage_summary_running, "使用 APK 内置阶段"));
            requestStageStatusRefresh();
        }
    }

    private void stopOneClickStages(String message) {
        oneClickStagesInFlight = false;
        oneClickRemoteProbeInFlight = false;
        currentStageUsedRemote = false;
        if (oneClickStageSummaryView != null) {
            oneClickStageSummaryView.setText(getString(R.string.one_click_stage_summary_waiting, message));
        }
        updateExecutionModeViews();
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    private void confirmForceRestartSharedInstall() {
        if (sharedInstallController == null) {
            Toast.makeText(this, "共享安装控制器不可用，请返回安装引导页重试。", Toast.LENGTH_LONG).show();
            return;
        }

        new AlertDialog.Builder(this)
            .setTitle("强制重启并继续安装")
            .setMessage("只有确认安装已经长时间没有变化时才使用。\n\n这会终止当前卡住的一键初始化任务，清理运行标记，然后重新触发安装。已完成的阶段会按状态检测跳过，从第一个未完成阶段继续。")
            .setNegativeButton("取消", null)
            .setPositiveButton("强制重启并继续", (dialog, which) -> forceRestartSharedInstall())
            .show();
    }

    private void forceRestartSharedInstall() {
        if (sharedInstallController == null || commandInFlight || oneClickStagesInFlight || oneClickRemoteProbeInFlight) {
            Toast.makeText(this, R.string.command_busy, Toast.LENGTH_SHORT).show();
            return;
        }

        if (forceRestartSharedInstallButton != null) {
            forceRestartSharedInstallButton.setEnabled(false);
            forceRestartSharedInstallButton.setAlpha(0.7f);
        }
        setSharedInstallText(
            "详细进度：正在重启安装任务",
            "正在终止卡住的安装任务，随后会从第一个未完成阶段继续。"
        );

        backgroundExecutor.execute(() -> {
            boolean restarted = false;
            try {
                Method method = findMethod(sharedInstallController.getClass(), "forceRestartOneClickInstall");
                if (method == null) {
                    throw new NoSuchMethodException("OpenHouseInstallController.forceRestartOneClickInstall()");
                }
                Object value = method.invoke(sharedInstallController);
                restarted = value instanceof Boolean && (Boolean) value;
            } catch (Throwable throwable) {
                Logger.logStackTraceWithMessage(LOG_TAG, "Failed to force restart shared install", throwable);
            }

            boolean finalRestarted = restarted;
            runOnUiThread(() -> {
                Toast.makeText(
                    this,
                    finalRestarted ? "已强制重启安装任务，会从第一个未完成阶段继续。" : "无法重启安装，请查看日志。",
                    finalRestarted ? Toast.LENGTH_SHORT : Toast.LENGTH_LONG
                ).show();
                refreshSharedInstallState();
                updateExecutionModeViews();
            });
        });
    }

    private void startOneClickRemoteProbe() {
        if (oneClickRemoteProbeInFlight) return;

        oneClickRemoteProbeInFlight = true;
        currentStageSlug = "one_click_remote_probe";
        currentStageLabel = "探测远程维护源";
        currentStageView.setText("执行中：探测远程维护源");
        terminalStatusView.setText(R.string.embedded_terminal_status_ready);
        oneClickStageSummaryView.setText(getString(R.string.one_click_stage_summary_running, "探测远程维护源"));
        updateExecutionModeViews();
        refreshStatus();

        backgroundExecutor.execute(() -> {
            boolean available = isBootstrapSourceAvailable();
            runOnUiThread(() -> {
                oneClickRemoteProbeInFlight = false;
                if (!oneClickStagesInFlight || isFinishing() || isDestroyed()) return;

                oneClickUseBundledStages = !available;
                oneClickStageSummaryView.setText(getString(
                    R.string.one_click_stage_summary_running,
                    available ? "远程维护源可用，按阶段执行" : "远程维护源不可用，使用 APK 内置阶段"
                ));
                requestStageStatusRefresh();
                updateExecutionModeViews();
                refreshStatus();
            });
        });
    }

    private void continueOneClickStages() {
        if (!oneClickStagesInFlight || oneClickRemoteProbeInFlight || commandInFlight || stageStatusCheckInFlight) return;

        for (StageAction stageAction : getOneClickStageSequence()) {
            StagePresentation presentation = stagePresentations.get(stageAction);
            if (presentation == null || presentation.state == StageUiState.CHECKING) {
                requestStageStatusRefresh();
                return;
            }

            if (presentation.state == StageUiState.COMPLETE) {
                continue;
            }

            if (presentation.state == StageUiState.BLOCKED) {
                String reason = stageAction.label(this) + "：" + presentation.detail;
                oneClickStagesInFlight = false;
                oneClickStageSummaryView.setText(getString(R.string.one_click_stage_summary_waiting, reason));
                updateExecutionModeViews();
                Toast.makeText(this, getString(R.string.one_click_stage_toast_blocked, reason), Toast.LENGTH_LONG).show();
                return;
            }

            oneClickStageSummaryView.setText(getString(R.string.one_click_stage_summary_running, stageAction.label(this)));
            updateExecutionModeViews();
            runStage(stageAction, true);
            return;
        }

        oneClickStagesInFlight = false;
        currentStageUsedRemote = false;
        oneClickStageSummaryView.setText(R.string.one_click_stage_summary_complete);
        updateExecutionModeViews();
        Toast.makeText(this, R.string.one_click_stage_toast_complete, Toast.LENGTH_SHORT).show();
    }

    private void returnToHome() {
        if (isFinishing() || isDestroyed()) {
            return;
        }

        if (shouldReturnToOnboarding()) {
            finish();
            return;
        }

        Intent intent = new Intent(this, TermuxActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(intent);
        finish();
    }

    private boolean shouldReturnToOnboarding() {
        Intent intent = getIntent();
        return intent != null && intent.getBooleanExtra(EXTRA_RETURN_TO_ONBOARDING, false);
    }

    private void initializeSharedInstallController() {
        if (sharedInstallControllerInitialized) {
            return;
        }

        sharedInstallControllerInitialized = true;
        try {
            Class<?> controllerClass = Class.forName("com.termux.app.openhouse.OpenHouseInstallController");
            sharedInstallListenerClass = Class.forName("com.termux.app.openhouse.OpenHouseInstallController$Listener");
            Method getInstanceMethod = findMethod(controllerClass, "getInstance", android.content.Context.class);
            if (getInstanceMethod == null) {
                throw new NoSuchMethodException("OpenHouseInstallController.getInstance(Context)");
            }

            sharedInstallController = getInstanceMethod.invoke(null, getApplicationContext());
            if (sharedInstallController == null) {
                throw new IllegalStateException("OpenHouseInstallController.getInstance(Context) returned null");
            }
            InvocationHandler handler = (proxy, method, args) -> {
                String methodName = method.getName();
                if ("onInstallStateChanged".equals(methodName) && args != null && args.length == 1) {
                    Object state = args[0];
                    mainHandler.post(() -> applySharedInstallState(state));
                    return null;
                }
                if ("toString".equals(methodName)) {
                    return "MaintenanceCenter shared install listener";
                }
                if ("hashCode".equals(methodName)) {
                    return System.identityHashCode(proxy);
                }
                if ("equals".equals(methodName)) {
                    return proxy == (args == null || args.length == 0 ? null : args[0]);
                }
                return null;
            };
            sharedInstallListener = Proxy.newProxyInstance(
                sharedInstallListenerClass.getClassLoader(),
                new Class<?>[] { sharedInstallListenerClass },
                handler
            );
            refreshSharedInstallState();
        } catch (ClassNotFoundException e) {
            showSharedInstallControllerUnavailable();
        } catch (Throwable throwable) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to initialize shared install controller", throwable);
            showSharedInstallControllerUnavailable();
        }
    }

    private void startSharedInstallObservation() {
        initializeSharedInstallController();
        if (sharedInstallController == null || sharedInstallListener == null || sharedInstallListenerClass == null) {
            return;
        }

        if (!sharedInstallListening) {
            try {
                Method addListenerMethod = findMethod(
                    sharedInstallController.getClass(),
                    "addListener",
                    sharedInstallListenerClass
                );
                if (addListenerMethod == null) {
                    throw new NoSuchMethodException("OpenHouseInstallController.addListener(Listener)");
                }
                addListenerMethod.invoke(sharedInstallController, sharedInstallListener);
                sharedInstallListening = true;
            } catch (Throwable throwable) {
                Logger.logStackTraceWithMessage(LOG_TAG, "Failed to observe shared install controller", throwable);
            }
        }
        refreshSharedInstallState();
    }

    private void stopSharedInstallObservation() {
        mainHandler.removeCallbacks(sharedInstallProgressRefreshRunnable);
        if (!sharedInstallListening || sharedInstallController == null || sharedInstallListener == null
            || sharedInstallListenerClass == null) {
            sharedInstallListening = false;
            return;
        }

        try {
            Method removeListenerMethod = findMethod(
                sharedInstallController.getClass(),
                "removeListener",
                sharedInstallListenerClass
            );
            if (removeListenerMethod != null) {
                removeListenerMethod.invoke(sharedInstallController, sharedInstallListener);
            }
        } catch (Throwable throwable) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to stop observing shared install controller", throwable);
        } finally {
            sharedInstallListening = false;
        }
    }

    private void refreshSharedInstallState() {
        if (sharedInstallController == null) {
            return;
        }

        try {
            Method getStateMethod = findMethod(sharedInstallController.getClass(), "getState");
            if (getStateMethod == null) {
                throw new NoSuchMethodException("OpenHouseInstallController.getState()");
            }
            Object state = getStateMethod.invoke(sharedInstallController);
            applySharedInstallState(state);
        } catch (Throwable throwable) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to read shared install state", throwable);
        }
    }

    private void applySharedInstallState(Object state) {
        if (isFinishing() || isDestroyed()) {
            return;
        }

        if (state == null) {
            sharedInstallRunning = false;
            sharedInstallStarted = false;
            sharedInstallCompleted = false;
            setSharedInstallText(
                "详细进度：等待主界面安装状态",
                "从主界面启动安装后，这里会显示同一个安装过程。"
            );
            refreshSharedInstallLogTail(false);
            mainHandler.removeCallbacks(sharedInstallProgressRefreshRunnable);
            updateExecutionModeViews();
            return;
        }

        boolean running = readBooleanInstallState(state, "running", false);
        boolean completed = readBooleanInstallState(state, "completed", false);
        boolean failed = readBooleanInstallState(state, "failed", false);
        int percent = readIntInstallState(state, "percent", -1);
        String phaseLabel = readStringInstallState(state, "phaseLabel");
        String detailText = readStringInstallState(state, "detailText");
        String currentStage = readStringInstallState(state, "currentStageSlug");
        boolean openCodeInstallPhase = isOpenCodeInstallPhase(currentStage, phaseLabel);

        sharedInstallRunning = running;
        sharedInstallCompleted = completed;
        sharedInstallStarted = running || completed || failed || percent > 0;
        String stateLabel;
        if (running) {
            stateLabel = "执行中";
        } else if (completed) {
            stateLabel = "已完成";
        } else if (failed) {
            stateLabel = "失败";
        } else {
            stateLabel = "待开始";
        }

        StringBuilder progress = new StringBuilder("详细进度：").append(stateLabel);
        if (percent >= 0) {
            progress.append(" · ").append(Math.max(0, Math.min(100, percent))).append('%');
        }
        if (!isBlank(phaseLabel)) {
            progress.append(" · ").append(phaseLabel.trim());
        }
        if (openCodeInstallPhase) {
            progress.append(" · ").append(getString(R.string.openhouse_install_phase_opencode_hint));
        } else if (running || (!completed && !failed)) {
            progress.append(" · ").append(getString(R.string.openhouse_install_phase_total_hint));
        }

        StringBuilder detail = new StringBuilder();
        if (!isBlank(detailText)) {
            detail.append(detailText.trim());
        } else if (running) {
            detail.append("主界面安装正在运行，本页只观察同一个安装过程。");
        } else if (completed) {
            detail.append("安装已完成。可留在本页查看日志，或返回终端主界面。");
        } else if (failed) {
            detail.append("安装失败。请查看下方共享日志或维护终端输出。");
        } else {
            detail.append("从主界面启动安装后，这里会显示同一个安装过程。");
        }
        appendSharedInstallEstimate(detail, openCodeInstallPhase, running, completed, failed);
        if (!isBlank(currentStage)) {
            detail.append('\n').append("阶段：").append(currentStage.trim());
        }

        setSharedInstallText(progress.toString(), detail.toString());
        refreshSharedInstallLogTail(running || completed || failed);
        updateExecutionModeViews();
        if (running) {
            scheduleSharedInstallProgressRefresh();
        } else {
            mainHandler.removeCallbacks(sharedInstallProgressRefreshRunnable);
        }
    }

    private void showSharedInstallControllerUnavailable() {
        sharedInstallRunning = false;
        setSharedInstallText(
            "详细进度：等待主界面安装状态",
            "共享安装控制器尚未接入；接入后这里会显示主界面启动的同一个安装过程。"
        );
        if (sharedInstallLogView != null) {
            sharedInstallLogView.setText("暂无共享安装日志。");
        }
        updateExecutionModeViews();
    }

    private void setSharedInstallText(String progress, String detail) {
        if (sharedInstallProgressView != null) {
            sharedInstallProgressView.setText(progress);
        }
        if (sharedInstallDetailView != null) {
            sharedInstallDetailView.setText(detail);
        }
    }

    private void refreshSharedInstallLogTail(boolean expectLog) {
        if (sharedInstallLogView == null || sharedInstallController == null) {
            return;
        }

        String logTail = null;
        try {
            Method getLogTailMethod = findMethod(sharedInstallController.getClass(), "getLogTail", int.class);
            if (getLogTailMethod != null) {
                Object value = getLogTailMethod.invoke(sharedInstallController, SHARED_INSTALL_LOG_CHAR_LIMIT);
                if (value != null) {
                    logTail = String.valueOf(value);
                }
            }
        } catch (Throwable throwable) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to read shared install log tail", throwable);
        }

        if (isBlank(logTail)) {
            sharedInstallLogView.setText(expectLog ? "等待共享安装日志输出…" : "暂无共享安装日志。");
            return;
        }

        sharedInstallLogView.setText(logTail);
        if (sharedInstallLogScrollView != null) {
            sharedInstallLogScrollView.post(() -> sharedInstallLogScrollView.fullScroll(View.FOCUS_DOWN));
        }
    }

    private void scheduleSharedInstallProgressRefresh() {
        mainHandler.removeCallbacks(sharedInstallProgressRefreshRunnable);
        if (sharedInstallListening && sharedInstallRunning && !isFinishing() && !isDestroyed()) {
            mainHandler.postDelayed(sharedInstallProgressRefreshRunnable, SHARED_INSTALL_PROGRESS_REFRESH_INTERVAL_MS);
        }
    }

    private boolean readBooleanInstallState(Object state, String name, boolean defaultValue) {
        Object value = readInstallStateProperty(state, name);
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        if (value instanceof Number) {
            return ((Number) value).intValue() != 0;
        }
        if (value instanceof String) {
            return Boolean.parseBoolean((String) value);
        }
        return defaultValue;
    }

    private int readIntInstallState(Object state, String name, int defaultValue) {
        Object value = readInstallStateProperty(state, name);
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        if (value instanceof String) {
            try {
                return Integer.parseInt(((String) value).trim());
            } catch (NumberFormatException ignored) {
                return defaultValue;
            }
        }
        return defaultValue;
    }

    private String readStringInstallState(Object state, String name) {
        Object value = readInstallStateProperty(state, name);
        return value == null ? null : String.valueOf(value);
    }

    private Object readInstallStateProperty(Object state, String name) {
        if (state == null || isBlank(name)) {
            return null;
        }

        String suffix = Character.toUpperCase(name.charAt(0)) + name.substring(1);
        String[] methodNames = new String[] {
            "get" + suffix,
            "is" + suffix,
            name
        };

        for (String methodName : methodNames) {
            try {
                Method method = findMethod(state.getClass(), methodName);
                if (method != null) {
                    return method.invoke(state);
                }
            } catch (Throwable ignored) {
                // Try the next accessor shape.
            }
        }

        try {
            Field field = findField(state.getClass(), name);
            if (field != null) {
                return field.get(state);
            }
        } catch (Throwable ignored) {
            return null;
        }
        return null;
    }

    private Method findMethod(Class<?> type, String name, Class<?>... parameterTypes) {
        Class<?> current = type;
        while (current != null) {
            try {
                Method method = current.getDeclaredMethod(name, parameterTypes);
                method.setAccessible(true);
                return method;
            } catch (NoSuchMethodException ignored) {
                current = current.getSuperclass();
            }
        }
        return null;
    }

    private Field findField(Class<?> type, String name) {
        Class<?> current = type;
        while (current != null) {
            try {
                Field field = current.getDeclaredField(name);
                field.setAccessible(true);
                return field;
            } catch (NoSuchFieldException ignored) {
                current = current.getSuperclass();
            }
        }
        return null;
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private boolean isOpenCodeInstallPhase(String currentStage, String phaseLabel) {
        return "install_opencode".equals(currentStage)
            || (phaseLabel != null && (phaseLabel.contains("OpenCode") || phaseLabel.contains("opencode")));
    }

    private void appendSharedInstallEstimate(StringBuilder detail, boolean openCodeInstallPhase,
                                             boolean running, boolean completed, boolean failed) {
        if (detail == null) {
            return;
        }
        String existingDetail = detail.toString();
        if (existingDetail.contains("当前是 OpenCode 安装阶段")
            || (existingDetail.contains("全程预计约30分钟") && !openCodeInstallPhase)) {
            return;
        }

        String estimate = null;
        if (openCodeInstallPhase) {
            estimate = getString(R.string.openhouse_install_estimate_opencode);
        } else if (running) {
            estimate = getString(R.string.openhouse_install_estimate_total)
                + " "
                + getString(R.string.openhouse_install_estimate_tail);
        } else if (!completed && !failed) {
            estimate = getString(R.string.openhouse_install_estimate_total);
        }

        if (estimate == null) {
            return;
        }
        if (detail.length() > 0) {
            detail.append(' ');
        }
        detail.append(estimate);
    }

    private boolean isMaintenanceActionBlocked() {
        return commandInFlight || sharedInstallRunning;
    }

    private void showMaintenanceActionBlockedToast() {
        if (sharedInstallRunning) {
            Toast.makeText(this, "主界面安装正在运行，本页只显示同一个安装进度。", Toast.LENGTH_SHORT).show();
            refreshSharedInstallState();
            return;
        }
        Toast.makeText(this, R.string.command_busy, Toast.LENGTH_SHORT).show();
    }

    private boolean isPageOwnedTerminalIdle() {
        return !commandInFlight && !oneClickStagesInFlight && !oneClickRemoteProbeInFlight;
    }

    private void updateExecutionModeViews() {
        if (oneClickStagePanel != null) {
            oneClickStagePanel.setVisibility(oneClickStageMode ? View.VISIBLE : View.GONE);
        }
        if (stageActionsPanel != null) {
            stageActionsPanel.setVisibility(oneClickStageMode ? View.GONE : View.VISIBLE);
        }

        applyModeButtonState(stageManualModeButton, !oneClickStageMode);
        applyModeButtonState(stageOneClickModeButton, oneClickStageMode);

        if (startOneClickStagesButton != null) {
            if (sharedInstallRunning) {
                startOneClickStagesButton.setText("主界面安装进行中");
            } else {
                startOneClickStagesButton.setText(oneClickStagesInFlight
                    ? R.string.button_stop_one_click_stages
                    : R.string.button_start_one_click_stages);
            }
            startOneClickStagesButton.setEnabled(!sharedInstallRunning
                && (oneClickStagesInFlight || (!commandInFlight && !isBatteryRequirementBlocking())));
            startOneClickStagesButton.setAlpha(startOneClickStagesButton.isEnabled() ? 1.0f : 0.78f);
            startOneClickStagesButton.setBackgroundTintList(ColorStateList.valueOf(ContextCompat.getColor(
                this,
                sharedInstallRunning || oneClickStagesInFlight ? R.color.stageRunning : R.color.stageComplete
            )));
            startOneClickStagesButton.setTextColor(ContextCompat.getColor(
                this,
                sharedInstallRunning || oneClickStagesInFlight ? R.color.stageRunningText : R.color.stageOnDark
            ));
        }
        if (forceRestartSharedInstallButton != null) {
            boolean canForceRestartSharedInstall = sharedInstallStarted
                && !sharedInstallCompleted
                && !commandInFlight
                && !oneClickStagesInFlight
                && !oneClickRemoteProbeInFlight;
            forceRestartSharedInstallButton.setEnabled(canForceRestartSharedInstall);
            forceRestartSharedInstallButton.setAlpha(canForceRestartSharedInstall ? 1.0f : 0.72f);
            forceRestartSharedInstallButton.setBackgroundTintList(ColorStateList.valueOf(ContextCompat.getColor(
                this,
                canForceRestartSharedInstall ? R.color.stageFailed : R.color.stageBlocked
            )));
            forceRestartSharedInstallButton.setTextColor(ContextCompat.getColor(
                this,
                canForceRestartSharedInstall ? R.color.stageOnDark : R.color.stageBlockedText
            ));
        }
        updateOneClickStageItems();
    }

    private void updateOneClickStageItems() {
        StageAction nextStageAction = findNextOneClickStage();
        List<StageAction> sequence = getOneClickStageSequence();
        if (oneClickStageItemsContainer != null) {
            setLegacyOneClickStageItemsVisibility(View.GONE);
            oneClickStageItemsContainer.removeAllViews();
            int displayNumber = 1;
            for (int i = 0; i < sequence.size(); i++) {
                StageAction stageAction = sequence.get(i);
                TextView row = createOneClickStageItemView(displayNumber == 1);
                updateOneClickStageActionItem(row, displayNumber++, stageAction, nextStageAction);
                oneClickStageItemsContainer.addView(row);

                if (stageAction == StageAction.INSTALL_REASONIX) {
                    TextView deepSeekGuideRow = createOneClickStageItemView(false);
                    updateOneClickClickableStageItem(
                        deepSeekGuideRow,
                        displayNumber++,
                        StageAction.REQUEST_DEEPSEEK_KEY,
                        v -> showDeepSeekKeyGuideDialog()
                    );
                    oneClickStageItemsContainer.addView(deepSeekGuideRow);

                    TextView deepSeekConfigRow = createOneClickStageItemView(false);
                    updateOneClickClickableStageItem(
                        deepSeekConfigRow,
                        displayNumber++,
                        StageAction.CONFIGURE_DEEPSEEK,
                        v -> showDeepSeekKeyConfigDialog()
                    );
                    oneClickStageItemsContainer.addView(deepSeekConfigRow);

                    TextView restartEntryRow = createOneClickStageItemView(false);
                    updateOneClickClickableStageItem(
                        restartEntryRow,
                        displayNumber++,
                        StageAction.RESTART_ENTRY_TERMINAL,
                        v -> showRestartEntryTerminalDialog()
                    );
                    oneClickStageItemsContainer.addView(restartEntryRow);
                }
            }
            TextView startOpenCodeRow = createOneClickStageItemView(false);
            updateOneClickClickableStageItem(
                startOpenCodeRow,
                displayNumber++,
                StageAction.START,
                v -> runStage(StageAction.START)
            );
            oneClickStageItemsContainer.addView(startOpenCodeRow);

            TextView restartOpenCodeRow = createOneClickStageItemView(false);
            updateOneClickClickableStageItem(
                restartOpenCodeRow,
                displayNumber++,
                StageAction.RESTART,
                v -> runStage(StageAction.RESTART)
            );
            oneClickStageItemsContainer.addView(restartOpenCodeRow);
            return;
        }

        List<StageAction> visibleSequence = sequence.size() > 7
            ? sequence.subList(0, 7)
            : sequence;
        TextView[] itemViews = new TextView[] {
            oneClickPrepareItemView,
            oneClickUbuntuPackagesItemView,
            oneClickOpenCodeItemView,
            oneClickCodexItemView,
            oneClickClaudeCodeItemView,
            oneClickSkillItemView,
            oneClickStartItemView
        };

        for (int i = 0; i < itemViews.length; i++) {
            TextView view = itemViews[i];
            if (view == null) continue;
            if (i >= visibleSequence.size()) {
                view.setVisibility(View.GONE);
                continue;
            }
            view.setVisibility(View.VISIBLE);
            updateOneClickStageActionItem(view, i + 1, visibleSequence.get(i), nextStageAction);
        }
    }

    private TextView createOneClickStageItemView(boolean first) {
        TextView view = new TextView(this);
        LinearLayout.LayoutParams layoutParams = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        );
        layoutParams.topMargin = first ? 0 : dp(8);
        view.setLayoutParams(layoutParams);
        view.setBackgroundResource(R.drawable.one_click_item_bg);
        view.setLineSpacing(dp(2), 1.0f);
        view.setPadding(dp(12), dp(12), dp(12), dp(12));
        view.setTextSize(13);
        return view;
    }

    private void setLegacyOneClickStageItemsVisibility(int visibility) {
        TextView[] legacyViews = new TextView[] {
            oneClickPrepareItemView,
            oneClickUbuntuPackagesItemView,
            oneClickOpenCodeItemView,
            oneClickCodexItemView,
            oneClickClaudeCodeItemView,
            oneClickSkillItemView,
            oneClickStartItemView
        };
        for (TextView view : legacyViews) {
            if (view != null) {
                view.setVisibility(visibility);
            }
        }
    }

    private void updateOneClickStageActionItem(
        TextView view,
        int number,
        StageAction stageAction,
        StageAction nextStageAction
    ) {
        if (view == null) return;

        StagePresentation presentation = stagePresentations.get(stageAction);
        int statusRes;
        int backgroundColorRes;
        int textColorRes;
        String detail = presentation == null
            ? getString(R.string.stage_detail_checking)
            : getStageDescription(stageAction, presentation.detail);

        if (presentation == null || presentation.state == StageUiState.CHECKING) {
            statusRes = R.string.one_click_auto_status_checking;
            backgroundColorRes = R.color.stageChecking;
            textColorRes = R.color.stageCheckingText;
        } else if (presentation.state == StageUiState.COMPLETE) {
            statusRes = R.string.one_click_auto_status_done;
            backgroundColorRes = R.color.stageComplete;
            textColorRes = R.color.stageOnDark;
        } else if (presentation.state == StageUiState.RUNNING) {
            statusRes = R.string.one_click_auto_status_running;
            backgroundColorRes = R.color.stageRunning;
            textColorRes = R.color.stageRunningText;
        } else if (presentation.state == StageUiState.BLOCKED || presentation.state == StageUiState.FAILED) {
            statusRes = stageAction == nextStageAction
                ? R.string.one_click_auto_status_blocked
                : R.string.one_click_auto_status_waiting;
            backgroundColorRes = stageAction == nextStageAction ? R.color.stageFailed : R.color.stageBlocked;
            textColorRes = stageAction == nextStageAction ? R.color.stageOnDark : R.color.stageBlockedText;
        } else if (stageAction == nextStageAction) {
            statusRes = R.string.one_click_auto_status_next;
            backgroundColorRes = R.color.stageRunning;
            textColorRes = R.color.stageRunningText;
        } else {
            statusRes = R.string.one_click_auto_status_waiting;
            backgroundColorRes = R.color.stageBlocked;
            textColorRes = R.color.stageBlockedText;
        }

        view.setText(getString(
            R.string.one_click_auto_item_text,
            number,
            stageAction.label(this),
            getString(statusRes),
            detail
        ));
        view.setBackgroundTintList(ColorStateList.valueOf(ContextCompat.getColor(this, backgroundColorRes)));
        view.setTextColor(ContextCompat.getColor(this, textColorRes));
    }

    private void updateOneClickClickableStageItem(
        TextView view,
        int number,
        StageAction stageAction,
        View.OnClickListener listener
    ) {
        if (view == null) return;
        StagePresentation presentation = stagePresentations.get(stageAction);
        StageUiState state = presentation == null ? StageUiState.CHECKING : presentation.state;
        boolean complete = state == StageUiState.COMPLETE;
        boolean enabled = !commandInFlight
            && !sharedInstallRunning
            && !oneClickStagesInFlight
            && state != StageUiState.CHECKING
            && state != StageUiState.BLOCKED;
        int statusRes = complete
            ? R.string.one_click_auto_status_done
            : (enabled ? R.string.one_click_auto_status_optional : R.string.one_click_auto_status_waiting);
        int backgroundColorRes = complete
            ? R.color.stageComplete
            : (enabled ? R.color.stageReady : R.color.stageBlocked);
        int textColorRes = complete
            ? R.color.stageOnDark
            : (enabled ? R.color.stageReadyText : R.color.stageBlockedText);
        String detail = presentation == null
            ? getString(R.string.stage_detail_checking)
            : getStageDescription(stageAction, presentation.detail);

        view.setText(getString(
            R.string.one_click_auto_item_text,
            number,
            stageAction.label(this),
            getString(statusRes),
            detail
        ));
        view.setBackgroundTintList(ColorStateList.valueOf(ContextCompat.getColor(this, backgroundColorRes)));
        view.setTextColor(ContextCompat.getColor(this, textColorRes));
        view.setEnabled(enabled);
        view.setAlpha(enabled ? 1.0f : 0.78f);
        view.setClickable(enabled);
        view.setOnClickListener(enabled ? listener : null);
    }

    private StageAction findNextOneClickStage() {
        for (StageAction stageAction : getOneClickStageSequence()) {
            StagePresentation presentation = stagePresentations.get(stageAction);
            if (presentation == null || presentation.state != StageUiState.COMPLETE) {
                return stageAction;
            }
        }
        return null;
    }

    private List<StageAction> getOneClickStageSequence() {
        List<StageAction> sequence = new ArrayList<>();
        for (StageFlowGroup group : getStageFlowGroups()) {
            for (StageAction stageAction : group.stageActions) {
                if (!sequence.contains(stageAction)) {
                    sequence.add(stageAction);
                }
            }
        }
        if (sequence.isEmpty()) {
            sequence.addAll(Arrays.asList(ONE_CLICK_STAGE_SEQUENCE));
        }
        ensureStageAfter(sequence, StageAction.INSTALL_NODE, StageAction.CONFIGURE_ENTRY_UBUNTU);
        ensureStageAfter(sequence, StageAction.INSTALL_CLAUDE_CODE_UI, StageAction.INSTALL_CLAUDE_CODE);
        ensureStageAfter(sequence, StageAction.RUNTIME_COMPONENTS, StageAction.INSTALL_REASONIX);
        ensureStageAfter(sequence, StageAction.INSTALL_HERMES, StageAction.RUNTIME_COMPONENTS);
        ensureStageAfter(sequence, StageAction.SYNC_OPENHOUSE_REGISTRY, StageAction.INSTALL_HERMES);
        return sequence;
    }

    private void ensureStageAfter(List<StageAction> sequence, StageAction stageAction, StageAction dependency) {
        if (sequence.contains(stageAction)) {
            return;
        }
        int dependencyIndex = sequence.indexOf(dependency);
        if (dependencyIndex >= 0) {
            sequence.add(dependencyIndex + 1, stageAction);
            return;
        }
        sequence.add(stageAction);
    }

    private List<StageFlowGroup> getStageFlowGroups() {
        if (activeManifest != null && activeManifest.stageFlowGroups != null && !activeManifest.stageFlowGroups.isEmpty()) {
            return activeManifest.stageFlowGroups;
        }

        List<StageFlowGroup> groups = new ArrayList<>();
        groups.add(new StageFlowGroup(
            getString(R.string.one_click_auto_prepare_title),
            getString(R.string.one_click_auto_prepare_detail),
            new StageAction[] {
                StageAction.PREPARE,
                StageAction.TERMUX_PACKAGES,
                StageAction.INSTALL_UBUNTU,
                StageAction.SYNC_OFFICIAL_DOCS
            }
        ));
        groups.add(new StageFlowGroup(
            getString(R.string.one_click_auto_ubuntu_title),
            getString(R.string.one_click_auto_ubuntu_detail),
            new StageAction[] { StageAction.UBUNTU_PACKAGES }
        ));
        groups.add(new StageFlowGroup(
            getString(R.string.one_click_auto_entry_ubuntu_title),
            getString(R.string.one_click_auto_entry_ubuntu_detail),
            new StageAction[] { StageAction.CONFIGURE_ENTRY_UBUNTU }
        ));
        groups.add(new StageFlowGroup(
            getString(R.string.one_click_auto_node_title),
            getString(R.string.one_click_auto_node_detail),
            new StageAction[] { StageAction.INSTALL_NODE }
        ));
        groups.add(new StageFlowGroup(
            getString(R.string.one_click_auto_agents_title),
            getString(R.string.one_click_auto_agents_detail),
            new StageAction[] {
                StageAction.INSTALL_OPENCODE,
                StageAction.INSTALL_CODEX,
                StageAction.INSTALL_CLAUDE_CODE,
                StageAction.INSTALL_CLAUDE_CODE_UI,
                StageAction.INSTALL_REASONIX
            }
        ));
        groups.add(new StageFlowGroup(
            "安装 SmallPhone 运行组件",
            "安装运行栈、Hermes，并同步 OpenHouseAI registry。",
            new StageAction[] {
                StageAction.RUNTIME_COMPONENTS,
                StageAction.INSTALL_HERMES,
                StageAction.SYNC_OPENHOUSE_REGISTRY
            }
        ));
        return groups;
    }

    private void updateOneClickStageItem(
        TextView view,
        int number,
        int titleRes,
        int detailRes,
        StageAction nextStageAction,
        StageAction... stageActions
    ) {
        if (view == null) return;

        boolean allComplete = true;
        boolean containsNext = false;
        boolean hasRunning = false;
        boolean hasChecking = false;
        boolean hasBlocked = false;

        for (StageAction stageAction : stageActions) {
            if (stageAction == nextStageAction) {
                containsNext = true;
            }

            StagePresentation presentation = stagePresentations.get(stageAction);
            if (presentation == null) {
                allComplete = false;
                hasChecking = true;
                continue;
            }

            if (presentation.state != StageUiState.COMPLETE) {
                allComplete = false;
            }
            if (presentation.state == StageUiState.RUNNING) {
                hasRunning = true;
            } else if (presentation.state == StageUiState.CHECKING) {
                hasChecking = true;
            } else if (presentation.state == StageUiState.BLOCKED || presentation.state == StageUiState.FAILED) {
                hasBlocked = true;
            }
        }

        int statusRes;
        int backgroundColorRes;
        int textColorRes;
        if (allComplete) {
            statusRes = R.string.one_click_auto_status_done;
            backgroundColorRes = R.color.stageComplete;
            textColorRes = R.color.stageOnDark;
        } else if (hasRunning) {
            statusRes = R.string.one_click_auto_status_running;
            backgroundColorRes = R.color.stageRunning;
            textColorRes = R.color.stageRunningText;
        } else if (containsNext && hasBlocked) {
            statusRes = R.string.one_click_auto_status_blocked;
            backgroundColorRes = R.color.stageFailed;
            textColorRes = R.color.stageOnDark;
        } else if (containsNext) {
            statusRes = R.string.one_click_auto_status_next;
            backgroundColorRes = R.color.stageRunning;
            textColorRes = R.color.stageRunningText;
        } else if (hasChecking) {
            statusRes = R.string.one_click_auto_status_checking;
            backgroundColorRes = R.color.stageChecking;
            textColorRes = R.color.stageCheckingText;
        } else {
            statusRes = R.string.one_click_auto_status_waiting;
            backgroundColorRes = R.color.stageBlocked;
            textColorRes = R.color.stageBlockedText;
        }

        view.setText(getString(
            R.string.one_click_auto_item_text,
            number,
            getString(titleRes),
            getString(statusRes),
            getString(detailRes)
        ));
        view.setBackgroundTintList(ColorStateList.valueOf(ContextCompat.getColor(this, backgroundColorRes)));
        view.setTextColor(ContextCompat.getColor(this, textColorRes));
    }

    private void updateOneClickStageItem(
        TextView view,
        int number,
        String title,
        String detail,
        StageAction nextStageAction,
        StageAction... stageActions
    ) {
        if (view == null) return;

        boolean allComplete = true;
        boolean containsNext = false;
        boolean hasRunning = false;
        boolean hasChecking = false;
        boolean hasBlocked = false;

        for (StageAction stageAction : stageActions) {
            if (stageAction == nextStageAction) {
                containsNext = true;
            }

            StagePresentation presentation = stagePresentations.get(stageAction);
            if (presentation == null) {
                allComplete = false;
                hasChecking = true;
                continue;
            }

            if (presentation.state != StageUiState.COMPLETE) {
                allComplete = false;
            }
            if (presentation.state == StageUiState.RUNNING) {
                hasRunning = true;
            } else if (presentation.state == StageUiState.CHECKING) {
                hasChecking = true;
            } else if (presentation.state == StageUiState.BLOCKED || presentation.state == StageUiState.FAILED) {
                hasBlocked = true;
            }
        }

        int statusRes;
        int backgroundColorRes;
        int textColorRes;
        if (allComplete) {
            statusRes = R.string.one_click_auto_status_done;
            backgroundColorRes = R.color.stageComplete;
            textColorRes = R.color.stageOnDark;
        } else if (hasRunning) {
            statusRes = R.string.one_click_auto_status_running;
            backgroundColorRes = R.color.stageRunning;
            textColorRes = R.color.stageRunningText;
        } else if (containsNext && hasBlocked) {
            statusRes = R.string.one_click_auto_status_blocked;
            backgroundColorRes = R.color.stageFailed;
            textColorRes = R.color.stageOnDark;
        } else if (containsNext) {
            statusRes = R.string.one_click_auto_status_next;
            backgroundColorRes = R.color.stageRunning;
            textColorRes = R.color.stageRunningText;
        } else if (hasChecking) {
            statusRes = R.string.one_click_auto_status_checking;
            backgroundColorRes = R.color.stageChecking;
            textColorRes = R.color.stageCheckingText;
        } else {
            statusRes = R.string.one_click_auto_status_waiting;
            backgroundColorRes = R.color.stageBlocked;
            textColorRes = R.color.stageBlockedText;
        }

        view.setText(getString(
            R.string.one_click_auto_item_text,
            number,
            title,
            getString(statusRes),
            detail
        ));
        view.setBackgroundTintList(ColorStateList.valueOf(ContextCompat.getColor(this, backgroundColorRes)));
        view.setTextColor(ContextCompat.getColor(this, textColorRes));
    }

    private String getStageTitle(StageAction stageAction) {
        ManifestStage manifestStage = activeManifest == null ? null : activeManifest.stages.get(stageAction.slug);
        if (manifestStage != null && manifestStage.title != null && !manifestStage.title.isEmpty()) {
            return manifestStage.title;
        }
        return getBuiltInStageTitle(stageAction);
    }

    private String getStageDescription(StageAction stageAction, String builtInDetail) {
        ManifestStage manifestStage = activeManifest == null ? null : activeManifest.stages.get(stageAction.slug);
        if (manifestStage != null && manifestStage.description != null && !manifestStage.description.isEmpty()) {
            return manifestStage.description;
        }
        return builtInDetail;
    }

    private String getBuiltInStageTitle(StageAction stageAction) {
        switch (stageAction) {
            case PREPARE:
                return getString(R.string.button_prepare);
            case TERMUX_PACKAGES:
                return getString(R.string.button_termux_packages);
            case INSTALL_UBUNTU:
                return getString(R.string.button_install_ubuntu);
            case SYNC_OFFICIAL_DOCS:
                return getString(R.string.button_sync_official_docs);
            case UBUNTU_PACKAGES:
                return getString(R.string.button_ubuntu_packages);
            case CONFIGURE_ENTRY_UBUNTU:
                return getString(R.string.button_configure_entry_ubuntu);
            case INSTALL_NODE:
                return getString(R.string.button_install_node);
            case INSTALL_OPENCODE:
                return getString(R.string.button_install_opencode);
            case INSTALL_CODEX:
                return getString(R.string.button_install_codex);
            case INSTALL_CLAUDE_CODE:
                return getString(R.string.button_install_claude_code);
            case INSTALL_CLAUDE_CODE_UI:
                return getString(R.string.button_install_claude_code_ui);
            case INSTALL_REASONIX:
                return getString(R.string.button_install_reasonix);
            case RUNTIME_COMPONENTS:
                return "安装运行组件";
            case INSTALL_HERMES:
                return "安装 Hermes";
            case SYNC_OPENHOUSE_REGISTRY:
                return "同步 OpenHouseAI registry";
            case REQUEST_DEEPSEEK_KEY:
                return getString(R.string.button_deepseek_key_guide);
            case CONFIGURE_DEEPSEEK:
                return getString(R.string.button_configure_deepseek_key);
            case RESTART_ENTRY_TERMINAL:
                return getString(R.string.button_restart_entry_terminal);
            case RESTART:
                return getString(R.string.button_restart);
            case START:
            default:
                return getString(R.string.button_start);
        }
    }

    private void applyModeButtonState(Button button, boolean active) {
        if (button == null) return;
        button.setEnabled(!isMaintenanceActionBlocked() && !oneClickStagesInFlight);
        button.setAlpha(button.isEnabled() ? 1.0f : 0.78f);
        button.setBackgroundTintList(ColorStateList.valueOf(ContextCompat.getColor(
            this,
            active ? R.color.stageComplete : R.color.stageReady
        )));
        button.setTextColor(ContextCompat.getColor(
            this,
            active ? R.color.stageOnDark : R.color.stageReadyText
        ));
    }

    private void bindStageButton(StageAction stageAction, int buttonId) {
        Button button = findViewById(buttonId);
        stageButtons.put(stageAction, button);
        button.setOnClickListener(v -> runStage(stageAction));
    }

    private void initializeStagePresentations() {
        for (StageAction stageAction : StageAction.values()) {
            stagePresentations.put(stageAction, StagePresentation.checking(this));
        }
        applyStagePresentations();
    }

    private void scheduleMaintenanceSessionInit() {
        if (maintenanceSessionInitPosted) return;
        maintenanceSessionInitPosted = true;
        terminalContainer.post(() -> {
            maintenanceSessionInitPosted = false;
            if (isFinishing() || isDestroyed()) return;
            ensureMaintenanceSession();
            refreshStatus();
        });
    }

    private void ensureMaintenanceSession() {
        try {
            ensureTerminalViewCreated();
            ensureMaintenanceSessionLocked();
            terminalFailureMessage = null;
        } catch (Throwable throwable) {
            terminalFailureMessage = throwable.getClass().getSimpleName() + ": " + throwable.getMessage();
            maintenanceSession = null;
            terminalStatusView.setText(R.string.embedded_terminal_status_failed);
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to initialize maintenance terminal session", throwable);
        }
    }

    private void ensureTerminalViewCreated() {
        if (terminalView != null) return;
        terminalView = new TerminalView(this, null);
        terminalView.setLayoutParams(new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ));
        terminalView.setFocusableInTouchMode(true);
        terminalView.setVerticalScrollBarEnabled(true);
        terminalView.setTerminalViewClient(terminalViewClient);
        terminalView.setTextSize(14);
        terminalContainer.removeAllViews();
        terminalContainer.addView(terminalView);
    }

    private void ensureMaintenanceSessionLocked() {
        if (maintenanceSession != null && maintenanceSession.getTerminalSession() != null
            && maintenanceSession.getTerminalSession().isRunning()) {
            if (terminalView.getCurrentSession() != maintenanceSession.getTerminalSession()) {
                terminalView.attachSession(maintenanceSession.getTerminalSession());
            }
            terminalStatusView.setText(R.string.embedded_terminal_status_ready);
            return;
        }

        terminalStatusView.setText(R.string.embedded_terminal_status_starting);
        ExecutionCommand command = new ExecutionCommand(
            TermuxShellManager.getNextShellId(),
            TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + "/bash",
            new String[] { "--noprofile", "--norc", "-i" },
            null,
            TermuxConstants.TERMUX_HOME_DIR_PATH,
            ExecutionCommand.Runner.TERMINAL_SESSION.getName(),
            false
        );
        command.shellName = "maintainer";
        command.commandLabel = "Maintainer Terminal";
        command.setShellCommandShellEnvironment = true;

        HashMap<String, String> maintenanceEnvironment = new HashMap<>();
        maintenanceEnvironment.put("OPENHOUSEAI_NO_AUTO_UBUNTU", "1");
        maintenanceEnvironment.put("TERMUX_NO_AUTO_UBUNTU", "1");

        maintenanceSession = TermuxSession.execute(
            this,
            command,
            terminalSessionClient,
            terminalSessionClient,
            new TermuxShellEnvironment(),
            maintenanceEnvironment,
            false
        );

        if (maintenanceSession != null) {
            terminalView.attachSession(maintenanceSession.getTerminalSession());
            terminalStatusView.setText(R.string.embedded_terminal_status_ready);
        } else {
            terminalStatusView.setText(R.string.embedded_terminal_status_failed);
        }
    }

    private void runStage(StageAction stageAction) {
        runStage(stageAction, false);
    }

    private void runStage(StageAction stageAction, boolean skipPreflightRefresh) {
        if (isMaintenanceActionBlocked()) {
            showMaintenanceActionBlockedToast();
            return;
        }

        if (stageAction == StageAction.REQUEST_DEEPSEEK_KEY) {
            showDeepSeekKeyGuideDialog();
            return;
        }

        if (stageAction == StageAction.RESTART_ENTRY_TERMINAL) {
            showRestartEntryTerminalDialog();
            return;
        }

        if (commandInFlight) {
            Toast.makeText(this, R.string.command_busy, Toast.LENGTH_SHORT).show();
            return;
        }

        if (isBatteryRequirementBlocking()) {
            currentStageView.setText(getString(R.string.permission_requirement_state_required));
            Toast.makeText(this, R.string.permission_battery_required_toast, Toast.LENGTH_LONG).show();
            return;
        }

        if (!skipPreflightRefresh && stageAction.shouldRefreshBeforeRun()) {
            pendingStageAction = stageAction;
            currentStageView.setText("刷新状态后执行：" + stageAction.label(this));
            requestStageStatusRefresh();
            return;
        }

        boolean usesRemoteManifestStage = activeManifest != null && activeManifest.stages.containsKey(stageAction.slug);
        if (!usesRemoteManifestStage && stageAction == StageAction.INSTALL_OPENCODE && shouldProbeOpenCodeSourceBeforeInstall()) {
            runOpenCodeSourceProbe(true);
            return;
        }

        pendingStageAction = null;
        ensureMaintenanceSession();
        if (maintenanceSession == null || maintenanceSession.getTerminalSession() == null
            || !maintenanceSession.getTerminalSession().isRunning()) {
            Toast.makeText(this, R.string.status_terminal_failed, Toast.LENGTH_SHORT).show();
            refreshStatus();
            return;
        }

        currentStageSlug = stageAction.slug;
        currentStageLabel = stageAction.label(this);
        currentStageView.setText(currentStageLabel);
        commandInFlight = true;
        currentStageUsedRemote = shouldUseRemoteStage(stageAction);
        openMaintenanceWebAfterStage = false;
        lastHandledMarker = null;
        scheduleTerminalCompletionPoll();
        terminalStatusView.setText(R.string.embedded_terminal_status_busy);
        liveLogView.setText(getString(R.string.result_placeholder));
        stagePresentations.put(stageAction, StagePresentation.running(this));
        applyStagePresentations();
        updateLogButtonState();
        refreshStatus();

        try {
            String command = buildStageExecutionCommand(stageAction);
            maintenanceSession.getTerminalSession().write(command);
            if (!command.endsWith("\n")) {
                maintenanceSession.getTerminalSession().write("\n");
            }
        } catch (IOException e) {
            commandInFlight = false;
            terminalStatusView.setText(R.string.embedded_terminal_status_ready);
            liveLogView.setText(getString(R.string.full_log_error, e.getMessage()));
            refreshStatus();
        }
    }

    private String buildStageExecutionCommand(StageAction stageAction) throws IOException {
        ManifestStage manifestStage = shouldUseRemoteStage(stageAction) && activeManifest != null
            ? activeManifest.stages.get(stageAction.slug)
            : null;
        if (manifestStage != null) {
            OpenCodeInstallSpec installSpec = stageAction == StageAction.INSTALL_OPENCODE
                ? resolveOpenCodeInstallSpec()
                : OpenCodeInstallSpec.defaultSpec(this);
            String fallbackScriptBody = oneClickStagesInFlight
                ? null
                : buildAssetScriptBody(stageAction, stageAction.assetName, getDefaultOpenCodePort(), installSpec);
            return buildRemoteBootstrapExecutionCommand(
                manifestStage.title,
                stageAction.slug,
                manifestStage.action,
                fallbackScriptBody
            );
        }

        OpenCodeInstallSpec installSpec = stageAction == StageAction.INSTALL_OPENCODE
            ? resolveOpenCodeInstallSpec()
            : OpenCodeInstallSpec.defaultSpec(this);
        return buildAssetExecutionCommand(stageAction, stageAction.label(this), stageAction.slug, stageAction.assetName, getDefaultOpenCodePort(), installSpec);
    }

    private boolean shouldUseRemoteStage(StageAction stageAction) {
        return shouldUseRemoteBootstrap()
            && activeManifest != null
            && activeManifest.stages.containsKey(stageAction.slug)
            && (!oneClickStagesInFlight || !oneClickUseBundledStages);
    }

    private boolean shouldUseRemoteBootstrap() {
        return maintenancePreferences != null
            && maintenancePreferences.getBoolean(PREF_USE_REMOTE_BOOTSTRAP, false);
    }

    private void runRemoteBootstrapAction(String stageSlug, String stageLabel, BootstrapAction action) {
        if (isMaintenanceActionBlocked()) {
            showMaintenanceActionBlockedToast();
            return;
        }

        if (isBatteryRequirementBlocking()) {
            currentStageView.setText(getString(R.string.permission_requirement_state_required));
            Toast.makeText(this, R.string.permission_battery_required_toast, Toast.LENGTH_LONG).show();
            return;
        }

        ensureMaintenanceSession();
        if (maintenanceSession == null || maintenanceSession.getTerminalSession() == null
            || !maintenanceSession.getTerminalSession().isRunning()) {
            Toast.makeText(this, R.string.status_terminal_failed, Toast.LENGTH_SHORT).show();
            refreshStatus();
            return;
        }

        currentStageSlug = stageSlug;
        currentStageLabel = stageLabel;
        commandInFlight = true;
        oneClickStagesInFlight = "manifest_full".equals(stageSlug);
        lastOneClickStatusRefreshUptimeMs = 0L;
        openMaintenanceWebAfterStage = false;
        lastHandledMarker = null;
        scheduleTerminalCompletionPoll();
        terminalStatusView.setText(R.string.embedded_terminal_status_busy);
        liveLogView.setText(getString(R.string.result_placeholder));
        updateLogButtonState();
        refreshStatus();
        updateExecutionModeViews();

        try {
            String fallbackScriptBody = "manifest_full".equals(stageSlug)
                ? buildFullInstallFallbackScript()
                : null;
            String postRemoteScriptBody = "manifest_full".equals(stageSlug)
                ? buildPostRemoteOneClickScript()
                : null;
            String command = shouldUseRemoteBootstrap()
                ? buildRemoteBootstrapExecutionCommand(stageLabel, stageSlug, action, fallbackScriptBody, postRemoteScriptBody)
                : buildBundledBootstrapExecutionCommand(stageLabel, stageSlug, action);
            maintenanceSession.getTerminalSession().write(command);
            if (!command.endsWith("\n")) {
                maintenanceSession.getTerminalSession().write("\n");
            }
        } catch (IOException e) {
            commandInFlight = false;
            oneClickStagesInFlight = false;
            terminalStatusView.setText(R.string.embedded_terminal_status_ready);
            liveLogView.setText(getString(R.string.full_log_error, e.getMessage()));
            refreshStatus();
            updateExecutionModeViews();
        }
    }

    private void startLocalMaintenanceWeb() {
        BootstrapAction action = new BootstrapAction(new String[] { "web-start" });
        runBootstrapAction(
            "local_maintenance_web",
            getString(R.string.local_maintenance_web_title),
            action,
            getBootstrapUrlForLocalMaintenance()
        );
    }

    private void stopLocalMaintenanceWeb() {
        String scriptBody =
            "OPENHOUSE_DIR=\"$HOME/.openhouse\"\n"
                + "WEB_DIR=\"$OPENHOUSE_DIR/web\"\n"
                + "SERVER_FILE=\"$WEB_DIR/openhouse_web_server.py\"\n"
                + "PID_FILE=\"$OPENHOUSE_DIR/web.pid\"\n"
                + "PORT_FILE=\"$OPENHOUSE_DIR/web-port\"\n"
                + "PORT=\"" + getLocalMaintenanceWebPort() + "\"\n"
                + "if [ -f \"$PORT_FILE\" ]; then PORT=\"$(tr -d '[:space:]' < \"$PORT_FILE\" 2>/dev/null || printf '%s' \"$PORT\")\"; fi\n"
                + "stopped=0\n"
                + "if [ -f \"$PID_FILE\" ]; then\n"
                + "  pid=\"$(cat \"$PID_FILE\" 2>/dev/null || true)\"\n"
                + "  if [ -n \"$pid\" ] && kill -0 \"$pid\" >/dev/null 2>&1; then\n"
                + "    kill \"$pid\" >/dev/null 2>&1 || true\n"
                + "    stopped=1\n"
                + "  fi\n"
                + "  rm -f \"$PID_FILE\"\n"
                + "fi\n"
                + "if [ \"$stopped\" -eq 0 ] && command -v pkill >/dev/null 2>&1; then\n"
                + "  if pkill -f \"$SERVER_FILE\" >/dev/null 2>&1; then stopped=1; fi\n"
                + "fi\n"
                + "if [ \"$stopped\" -eq 1 ]; then\n"
                + "  log \"本地网页维护器已关闭。端口：$PORT\"\n"
                + "else\n"
                + "  log \"本地网页维护器未运行。端口：$PORT\"\n"
                + "fi\n";
        runLocalMaintenanceScript(
            "local_maintenance_web_stop",
            getString(R.string.button_stop_maintenance_web),
            scriptBody
        );
    }

    private void runLocalMaintenanceScript(String stageSlug, String stageLabel, String scriptBody) {
        if (isMaintenanceActionBlocked()) {
            showMaintenanceActionBlockedToast();
            return;
        }

        ensureMaintenanceSession();
        if (maintenanceSession == null || maintenanceSession.getTerminalSession() == null
            || !maintenanceSession.getTerminalSession().isRunning()) {
            Toast.makeText(this, R.string.status_terminal_failed, Toast.LENGTH_SHORT).show();
            refreshStatus();
            return;
        }

        currentStageSlug = stageSlug;
        currentStageLabel = stageLabel;
        commandInFlight = true;
        openMaintenanceWebAfterStage = false;
        lastHandledMarker = null;
        scheduleTerminalCompletionPoll();
        terminalStatusView.setText(R.string.embedded_terminal_status_busy);
        liveLogView.setText(getString(R.string.result_placeholder));
        updateLogButtonState();
        refreshStatus();
        updateExecutionModeViews();

        String wrapperScript = buildWrapperScript(stageLabel, stageSlug, scriptBody);
        String tempScriptPath = TermuxConstants.TERMUX_HOME_DIR_PATH + "/.maintainer-logs/run-" + stageSlug + ".sh";
        StringBuilder command = new StringBuilder();
        command.append("mkdir -p ").append(shellQuote(TermuxConstants.TERMUX_HOME_DIR_PATH + "/.maintainer-logs")).append('\n');
        command.append("cat > ").append(shellQuote(tempScriptPath)).append(" <<'__TERMUX_MAINT__'\n");
        command.append(wrapperScript);
        if (!wrapperScript.endsWith("\n")) {
            command.append('\n');
        }
        command.append("__TERMUX_MAINT__\n");
        command.append("/data/data/com.termux/files/usr/bin/bash ").append(shellQuote(tempScriptPath)).append('\n');
        command.append("rm -f ").append(shellQuote(tempScriptPath)).append('\n');

        maintenanceSession.getTerminalSession().write(command.toString());
    }

    private void runBootstrapAction(String stageSlug, String stageLabel, BootstrapAction action, String bootstrapUrl) {
        if (isMaintenanceActionBlocked()) {
            showMaintenanceActionBlockedToast();
            return;
        }

        if (isBatteryRequirementBlocking()) {
            currentStageView.setText(getString(R.string.permission_requirement_state_required));
            Toast.makeText(this, R.string.permission_battery_required_toast, Toast.LENGTH_LONG).show();
            return;
        }

        ensureMaintenanceSession();
        if (maintenanceSession == null || maintenanceSession.getTerminalSession() == null
            || !maintenanceSession.getTerminalSession().isRunning()) {
            Toast.makeText(this, R.string.status_terminal_failed, Toast.LENGTH_SHORT).show();
            refreshStatus();
            return;
        }

        currentStageSlug = stageSlug;
        currentStageLabel = stageLabel;
        commandInFlight = true;
        openMaintenanceWebAfterStage = "local_maintenance_web".equals(stageSlug);
        lastHandledMarker = null;
        scheduleTerminalCompletionPoll();
        terminalStatusView.setText(R.string.embedded_terminal_status_busy);
        liveLogView.setText(getString(R.string.result_placeholder));
        updateLogButtonState();
        refreshStatus();
        updateExecutionModeViews();

        try {
            String command = buildBootstrapExecutionCommand(stageLabel, stageSlug, action, bootstrapUrl);
            maintenanceSession.getTerminalSession().write(command);
            if (!command.endsWith("\n")) {
                maintenanceSession.getTerminalSession().write("\n");
            }
        } catch (IOException e) {
            commandInFlight = false;
            openMaintenanceWebAfterStage = false;
            terminalStatusView.setText(R.string.embedded_terminal_status_ready);
            liveLogView.setText(getString(R.string.full_log_error, e.getMessage()));
            refreshStatus();
            updateExecutionModeViews();
        }
    }

    private String buildRemoteBootstrapExecutionCommand(String stageLabel, String stageSlug, BootstrapAction action) throws IOException {
        return buildRemoteBootstrapExecutionCommand(stageLabel, stageSlug, action, null);
    }

    private String buildRemoteBootstrapExecutionCommand(String stageLabel, String stageSlug, BootstrapAction action, String fallbackScriptBody) throws IOException {
        return buildRemoteBootstrapExecutionCommand(stageLabel, stageSlug, action, fallbackScriptBody, null);
    }

    private String buildRemoteBootstrapExecutionCommand(String stageLabel, String stageSlug, BootstrapAction action, String fallbackScriptBody, String postRemoteScriptBody) throws IOException {
        if (!shouldUseRemoteBootstrap()) {
            return buildBundledBootstrapExecutionCommand(stageLabel, stageSlug, action);
        }
        if (activeManifest == null) {
            throw new IOException("远程维护源尚未加载");
        }

        return buildBootstrapExecutionCommand(stageLabel, stageSlug, action, activeManifest.bootstrapUrl, fallbackScriptBody, postRemoteScriptBody);
    }

    private String buildBootstrapExecutionCommand(String stageLabel, String stageSlug, BootstrapAction action, String bootstrapUrl) throws IOException {
        return buildBootstrapExecutionCommand(stageLabel, stageSlug, action, bootstrapUrl, null, null);
    }

    private String buildBootstrapExecutionCommand(String stageLabel, String stageSlug, BootstrapAction action, String bootstrapUrl, String fallbackScriptBody) throws IOException {
        return buildBootstrapExecutionCommand(stageLabel, stageSlug, action, bootstrapUrl, fallbackScriptBody, null);
    }

    private String buildBootstrapExecutionCommand(String stageLabel, String stageSlug, BootstrapAction action, String bootstrapUrl, String fallbackScriptBody, String postRemoteScriptBody) throws IOException {
        syncBundledRuntimeAssets();
        StringBuilder scriptBody = new StringBuilder();
        scriptBody.append("BOOTSTRAP_URL=").append(shellQuote(bootstrapUrl)).append('\n');
        scriptBody.append("select_fastest_termux_main_repo(){\n");
        scriptBody.append("  local candidates='https://packages-cf.termux.dev/apt/termux-main https://mirrors.tuna.tsinghua.edu.cn/termux/apt/termux-main https://mirrors.ustc.edu.cn/termux/apt/termux-main https://mirror.sunred.org/termux/termux-main'\n");
        scriptBody.append("  local repo best_repo='' best_time='' repo_time probe_url metrics http_code\n");
        scriptBody.append("  for repo in $candidates; do\n");
        scriptBody.append("    probe_url=\"$repo/dists/stable/InRelease\"\n");
        scriptBody.append("    metrics=\"$(curl -fsSL --connect-timeout 5 --max-time 12 -o /dev/null -w '%{time_total} %{http_code}' \"$probe_url\" 2>/dev/null || true)\"\n");
        scriptBody.append("    repo_time=\"${metrics%% *}\"\n");
        scriptBody.append("    http_code=\"${metrics##* }\"\n");
        scriptBody.append("    if [ \"$http_code\" = '200' ] && [ -n \"$repo_time\" ]; then\n");
        scriptBody.append("      printf '[OpenHouseAI] Termux 镜像测速：%s %ss\\n' \"$repo\" \"$repo_time\" >&2\n");
        scriptBody.append("      if [ -z \"$best_time\" ] || awk \"BEGIN{exit !($repo_time < $best_time)}\"; then best_time=\"$repo_time\"; best_repo=\"$repo\"; fi\n");
        scriptBody.append("    else\n");
        scriptBody.append("      printf '[OpenHouseAI] Termux 镜像不可用：%s\\n' \"$repo\" >&2\n");
        scriptBody.append("    fi\n");
        scriptBody.append("  done\n");
        scriptBody.append("  if [ -n \"$best_repo\" ]; then printf '[OpenHouseAI] 选择最快 Termux main 镜像源：%s\\n' \"$best_repo\" >&2; printf '%s\\n' \"$best_repo\"; else printf '[OpenHouseAI] Termux 镜像测速失败，回退到 packages-cf.termux.dev\\n' >&2; printf '%s\\n' 'https://packages-cf.termux.dev/apt/termux-main'; fi\n");
        scriptBody.append("}\n");
        scriptBody.append("configure_termux_main_repo(){\n");
        scriptBody.append("  local sources_file=\"${PREFIX:-/data/data/com.termux/files/usr}/etc/apt/sources.list\"\n");
        scriptBody.append("  local repo_url=\"${OPENHOUSEAI_TERMUX_MAIN_REPO:-}\"\n");
        scriptBody.append("  [ -d \"$(dirname \"$sources_file\")\" ] || return 0\n");
        scriptBody.append("  if [ -z \"$repo_url\" ]; then repo_url=\"$(select_fastest_termux_main_repo)\"; else log \"使用指定 Termux main 镜像源：$repo_url\"; fi\n");
        scriptBody.append("  if [ -f \"$sources_file\" ] && grep -Fq \"$repo_url\" \"$sources_file\"; then log \"Termux main 镜像源已是：$repo_url\"; return 0; fi\n");
        scriptBody.append("  log \"切换 Termux main 镜像源：$repo_url\"\n");
        scriptBody.append("  cp \"$sources_file\" \"$sources_file.openhouseai.bak\" 2>/dev/null || true\n");
        scriptBody.append("  printf 'deb %s stable main\\n' \"$repo_url\" > \"$sources_file\"\n");
        scriptBody.append("}\n");
        scriptBody.append("ensure_curl(){\n");
        scriptBody.append("  log '正在更新 Termux 包索引并修复 curl 网络依赖。'\n");
        scriptBody.append("  if command -v pkg >/dev/null 2>&1; then\n");
        scriptBody.append("    configure_termux_main_repo\n");
        scriptBody.append("    run_logged pkg update -y || true\n");
        scriptBody.append("    run_logged pkg install -y curl libcurl libngtcp2 libnghttp2 openssl ca-certificates || true\n");
        scriptBody.append("  else\n");
        scriptBody.append("    log '缺少 pkg，跳过自动修复 curl。'\n");
        scriptBody.append("  fi\n");
        scriptBody.append("  if ! curl --version >/dev/null 2>&1; then\n");
        scriptBody.append("    log 'curl 修复失败，请手动执行：pkg upgrade -y && pkg install -y curl libcurl libngtcp2 libnghttp2 openssl ca-certificates'\n");
        scriptBody.append("    exit 12\n");
        scriptBody.append("  fi\n");
        scriptBody.append("}\n");
        scriptBody.append("download_file(){\n");
        scriptBody.append("  local url=\"$1\" output=\"$2\" attempt=1\n");
        scriptBody.append("  while [ \"$attempt\" -le 5 ]; do\n");
        scriptBody.append("    log \"下载：$url（第 $attempt 次）\"\n");
        scriptBody.append("    if run_logged curl -fL --connect-timeout 10 --max-time 25 --speed-time 10 --speed-limit 1024 --retry 1 --retry-delay 2 --retry-all-errors \"$url\" -o \"$output\"; then return 0; fi\n");
        scriptBody.append("    attempt=$((attempt + 1))\n");
        scriptBody.append("    sleep 2\n");
        scriptBody.append("  done\n");
        scriptBody.append("  return 1\n");
        scriptBody.append("}\n");
        scriptBody.append("run_remote_bootstrap(){\n");
        scriptBody.append("  ensure_curl\n");
        scriptBody.append("  log \"正在探测远程维护脚本：$BOOTSTRAP_URL\"\n");
        scriptBody.append("  if ! download_file \"$BOOTSTRAP_URL\" \"$HOME/openhouseai-bootstrap.sh\"; then return 21; fi\n");
        scriptBody.append("  chmod +x \"$HOME/openhouseai-bootstrap.sh\"\n");
        scriptBody.append("  log \"正在执行远程维护动作：").append(action.toDisplayString()).append("\"\n");
        StageAction bootstrapStageAction = StageAction.fromSlug(stageSlug);
        OpenCodeInstallSpec remoteOpenCodeInstallSpec = resolveOpenCodeInstallSpec();
        scriptBody.append("  run_logged env OPENHOUSEAI_PORT=").append(shellQuote(Integer.toString(getDefaultOpenCodePort())))
            .append(" OPENHOUSEAI_WEB_PORT=").append(shellQuote(Integer.toString(getLocalMaintenanceWebPort())))
            .append(" OPENCODE_INSTALL_URL=").append(shellQuote(remoteOpenCodeInstallSpec.primaryUrl));
        if (bootstrapStageAction != null && bootstrapStageAction.requiredComponentTargets != null) {
            scriptBody.append(" OPENHOUSEAI_REQUIRED_COMPONENT_TARGETS=")
                .append(shellQuote(bootstrapStageAction.requiredComponentTargets));
        }
        scriptBody.append(" bash \"$HOME/openhouseai-bootstrap.sh\"");
        for (String arg : action.args) {
            scriptBody.append(' ').append(shellQuote(arg));
        }
        scriptBody.append('\n');
        scriptBody.append("}\n");
        if (fallbackScriptBody != null) {
            scriptBody.append("if run_remote_bootstrap; then\n");
            scriptBody.append("  log '远程维护动作完成。'\n");
            if (postRemoteScriptBody != null) {
                scriptBody.append(postRemoteScriptBody);
                if (!postRemoteScriptBody.endsWith("\n")) {
                    scriptBody.append('\n');
                }
            }
            scriptBody.append("else\n");
            scriptBody.append("  remote_status=\"$?\"\n");
            scriptBody.append("  log \"远程维护源不可用或执行失败（退出码：$remote_status），切换到 APK 内置阶段脚本。\"\n");
            scriptBody.append(fallbackScriptBody);
            if (!fallbackScriptBody.endsWith("\n")) {
                scriptBody.append('\n');
            }
            scriptBody.append("fi\n");
        } else {
            scriptBody.append("run_remote_bootstrap\n");
        }

        String wrapperScript = buildWrapperScript(stageLabel, stageSlug, scriptBody.toString());
        String tempScriptPath = TermuxConstants.TERMUX_HOME_DIR_PATH + "/.maintainer-logs/run-" + stageSlug + ".sh";

        StringBuilder builder = new StringBuilder();
        builder.append("mkdir -p ").append(shellQuote(TermuxConstants.TERMUX_HOME_DIR_PATH + "/.maintainer-logs")).append('\n');
        builder.append("cat > ").append(shellQuote(tempScriptPath)).append(" <<'__TERMUX_MAINT__'\n");
        builder.append(wrapperScript);
        if (!wrapperScript.endsWith("\n")) {
            builder.append('\n');
        }
        builder.append("__TERMUX_MAINT__\n");
        builder.append("/data/data/com.termux/files/usr/bin/bash ").append(shellQuote(tempScriptPath)).append('\n');
        builder.append("rm -f ").append(shellQuote(tempScriptPath)).append('\n');
        return builder.toString();
    }

    private String buildBundledBootstrapExecutionCommand(String stageLabel, String stageSlug, BootstrapAction action) throws IOException {
        syncBundledRuntimeAssets();
        StringBuilder scriptBody = new StringBuilder();
        scriptBody.append("bootstrap=\"${SMALLPHONEAI_BOOTSTRAP:-$HOME/.smallphoneai-bootstrap/bootstrap.sh}\"\n");
        scriptBody.append("payload_dir=\"${SMALLPHONEAI_OFFLINE_PAYLOAD_DIR:-$HOME/.smallphoneai-bootstrap/apk-assets/openhouse/product-payloads}\"\n");
        scriptBody.append("if [ ! -f \"$bootstrap\" ]; then log '未找到 APK 内置 SmallPhoneAI bootstrap，请重新安装或修复应用。'; exit 1; fi\n");
        scriptBody.append("if [ -d \"$payload_dir\" ]; then export SMALLPHONEAI_OFFLINE_PAYLOAD_DIR=\"$payload_dir\" SMALLPHONEAI_BUNDLED_PAYLOAD_ROOT=\"$payload_dir\"; fi\n");
        scriptBody.append("log \"正在执行 APK 内置维护动作：").append(action.toDisplayString()).append("\"\n");
        scriptBody.append("run_logged env OPENHOUSEAI_PORT=").append(shellQuote(Integer.toString(getDefaultOpenCodePort())))
            .append(" OPENHOUSEAI_WEB_PORT=").append(shellQuote(Integer.toString(getLocalMaintenanceWebPort())))
            .append(" OPENCODE_INSTALL_URL=").append(shellQuote(resolveOpenCodeInstallSpec().primaryUrl))
            .append(" SMALLPHONEAI_COMPONENT_SOURCE_MODE=bundle")
            .append(" SMALLPHONEAI_COMPONENTS_ALLOW_GIT_UPDATE=0")
            .append(" SMALLPHONEAI_COMPONENTS_AUTO_CLONE=0");
        StageAction bootstrapStageAction = StageAction.fromSlug(stageSlug);
        if (bootstrapStageAction != null && bootstrapStageAction.requiredComponentTargets != null) {
            scriptBody.append(" OPENHOUSEAI_REQUIRED_COMPONENT_TARGETS=")
                .append(shellQuote(bootstrapStageAction.requiredComponentTargets));
        }
        scriptBody.append(" bash \"$bootstrap\"");
        for (String arg : action.args) {
            scriptBody.append(' ').append(shellQuote(arg));
        }
        scriptBody.append('\n');

        String wrapperScript = buildWrapperScript(stageLabel, stageSlug, scriptBody.toString());
        String tempScriptPath = TermuxConstants.TERMUX_HOME_DIR_PATH + "/.maintainer-logs/run-" + stageSlug + ".sh";

        StringBuilder builder = new StringBuilder();
        builder.append("mkdir -p ").append(shellQuote(TermuxConstants.TERMUX_HOME_DIR_PATH + "/.maintainer-logs")).append('\n');
        builder.append("cat > ").append(shellQuote(tempScriptPath)).append(" <<'__TERMUX_MAINT__'\n");
        builder.append(wrapperScript);
        if (!wrapperScript.endsWith("\n")) {
            builder.append('\n');
        }
        builder.append("__TERMUX_MAINT__\n");
        builder.append("/data/data/com.termux/files/usr/bin/bash ").append(shellQuote(tempScriptPath)).append('\n');
        builder.append("rm -f ").append(shellQuote(tempScriptPath)).append('\n');
        return builder.toString();
    }

    private String getBootstrapUrlForLocalMaintenance() {
        if (activeManifest != null && activeManifest.bootstrapUrl != null && !activeManifest.bootstrapUrl.trim().isEmpty()) {
            return activeManifest.bootstrapUrl;
        }
        return DEFAULT_BOOTSTRAP_URL;
    }

    private void showLocalMaintenanceWebPortDialog() {
        if (isMaintenanceActionBlocked()) {
            showMaintenanceActionBlockedToast();
            return;
        }

        EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        input.setHint(getString(R.string.local_maintenance_web_port_dialog_hint));
        input.setText(Integer.toString(getLocalMaintenanceWebPort()));
        input.setSelection(input.getText().length());

        new AlertDialog.Builder(this)
            .setTitle(R.string.local_maintenance_web_port_dialog_title)
            .setView(input)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                String value = input.getText() == null ? "" : input.getText().toString().trim();
                int port;
                try {
                    port = Integer.parseInt(value);
                } catch (NumberFormatException e) {
                    Toast.makeText(this, R.string.local_maintenance_web_port_invalid, Toast.LENGTH_SHORT).show();
                    return;
                }
                if (!isValidLocalMaintenanceWebPort(port)) {
                    Toast.makeText(this, R.string.local_maintenance_web_port_invalid, Toast.LENGTH_SHORT).show();
                    return;
                }

                maintenancePreferences.edit().putInt(PREF_LOCAL_MAINTENANCE_WEB_PORT, port).apply();
                Toast.makeText(this, getString(R.string.local_maintenance_web_port_saved, port), Toast.LENGTH_SHORT).show();
                updateLocalMaintenanceWebCard();
            })
            .show();
    }

    private String buildAssetExecutionCommand(StageAction stageAction, String stageLabel, String stageSlug, String assetName, int port, OpenCodeInstallSpec installSpec) throws IOException {
        syncBundledRuntimeAssets();
        String scriptBody = buildAssetScriptBody(stageAction, assetName, port, installSpec);
        String wrapperScript = buildWrapperScript(stageLabel, stageSlug, scriptBody);
        String tempScriptPath = TermuxConstants.TERMUX_HOME_DIR_PATH + "/.maintainer-logs/run-" + stageSlug + ".sh";

        StringBuilder builder = new StringBuilder();
        builder.append("mkdir -p ").append(shellQuote(TermuxConstants.TERMUX_HOME_DIR_PATH + "/.maintainer-logs")).append('\n');
        builder.append("cat > ").append(shellQuote(tempScriptPath)).append(" <<'__TERMUX_MAINT__'\n");
        builder.append(wrapperScript);
        if (!wrapperScript.endsWith("\n")) {
            builder.append('\n');
        }
        builder.append("__TERMUX_MAINT__\n");
        builder.append("/data/data/com.termux/files/usr/bin/bash ").append(shellQuote(tempScriptPath)).append('\n');
        builder.append("rm -f ").append(shellQuote(tempScriptPath)).append('\n');
        return builder.toString();
    }

    private String buildAssetScriptBody(StageAction stageAction, String assetName, int port, OpenCodeInstallSpec installSpec) throws IOException {
        return loadAsset(assetName)
            .replace("__PORT__", Integer.toString(port))
            .replace("__CLAUDE_CODE_UI_PORT__", Integer.toString(ClaudeCodeUiSettings.DEFAULT_PORT))
            .replace("__BOOTSTRAP_URL__", getBootstrapUrlForLocalMaintenance())
            .replace("__REQUIRED_COMPONENT_TARGETS__", stageAction.requiredComponentTargets == null ? "" : stageAction.requiredComponentTargets)
            .replace("__LOCAL_MAINTENANCE_WEB_PORT__", Integer.toString(getLocalMaintenanceWebPort()))
            .replace("__DEEPSEEK_KEY_FILE__", OpenHouseStatusRepository.getDeepSeekKeyTempFile().getAbsolutePath())
            .replace("__BUNDLED_OFFICIAL_DOCS__", buildBundledAssetWriteSnippet(OFFICIAL_DOCS_ASSET_DIR, "OFFICIAL_DOC_DIR"))
            .replace("__OPENCODE_INSTALL_PRIMARY_URL__", installSpec.primaryUrl)
            .replace("__OPENCODE_INSTALL_PRIMARY_LABEL__", installSpec.primaryLabel)
            .replace("__OPENCODE_INSTALL_SECONDARY_URL__", installSpec.secondaryUrl)
            .replace("__OPENCODE_INSTALL_SECONDARY_LABEL__", installSpec.secondaryLabel)
            .replace("__OPENCODE_INSTALL_ALLOW_FALLBACK__", installSpec.allowFallback ? "1" : "0");
    }

    private String buildFullInstallFallbackScript() throws IOException {
        StringBuilder scriptBody = new StringBuilder();
        scriptBody.append("log '远程一键维护不可用，开始执行 APK 内置一键安装流程。'\n");
        for (StageAction stageAction : ONE_CLICK_STAGE_SEQUENCE) {
            OpenCodeInstallSpec installSpec = stageAction == StageAction.INSTALL_OPENCODE
                ? resolveOpenCodeInstallSpec()
                : OpenCodeInstallSpec.defaultSpec(this);
            scriptBody.append("log ").append(shellQuote("内置阶段开始：" + stageAction.label(this))).append('\n');
            scriptBody.append("run_environment_probe\n");
            scriptBody.append(buildAssetScriptBody(stageAction, stageAction.assetName, getDefaultOpenCodePort(), installSpec));
            if (scriptBody.charAt(scriptBody.length() - 1) != '\n') {
                scriptBody.append('\n');
            }
            scriptBody.append("log ").append(shellQuote("内置阶段完成：" + stageAction.label(this))).append('\n');
        }
        scriptBody.append("log 'APK 内置一键安装流程已完成。'\n");
        return scriptBody.toString();
    }

    private String buildPostRemoteOneClickScript() throws IOException {
        StringBuilder scriptBody = new StringBuilder();
        scriptBody.append("log '远程一键安装完成。OpenCode 需要点击启动按钮后再启动。'\n");
        return scriptBody.toString();
    }

    private OpenHouseBundledRuntimeSync.Result syncBundledRuntimeAssets() throws IOException {
        try {
            return OpenHouseBundledRuntimeSync.sync(this);
        } catch (IOException e) {
            throw new IOException("APK 内置 bootstrap/scripts/payload 同步失败：" + e.getMessage(), e);
        }
    }

    private void showDeepSeekKeyGuideDialog() {
        new AlertDialog.Builder(this)
            .setTitle(R.string.deepseek_key_guide_title)
            .setMessage(R.string.deepseek_key_guide_message)
            .setPositiveButton(R.string.deepseek_key_guide_open_button,
                (dialog, which) -> openUrl(DEEPSEEK_API_KEYS_URL, "DeepSeek API Keys"))
            .setNegativeButton(android.R.string.ok, null)
            .show();
    }

    private void showDeepSeekKeyConfigDialog() {
        if (isMaintenanceActionBlocked()) {
            showMaintenanceActionBlockedToast();
            return;
        }

        EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_NORMAL);
        input.setHint(getString(R.string.deepseek_key_config_hint));

        new AlertDialog.Builder(this)
            .setTitle(R.string.deepseek_key_config_title)
            .setMessage(R.string.deepseek_key_config_message)
            .setView(input)
            .setNeutralButton(R.string.deepseek_key_guide_open_button,
                (dialog, which) -> openUrl(DEEPSEEK_API_KEYS_URL, "DeepSeek API Keys"))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.deepseek_key_config_save_button, (dialog, which) -> {
                String apiKey = input.getText() == null ? "" : input.getText().toString().trim();
                if (apiKey.isEmpty()) {
                    Toast.makeText(this, R.string.deepseek_key_config_empty, Toast.LENGTH_SHORT).show();
                    return;
                }
                runDeepSeekKeyConfig(apiKey);
            })
            .show();
    }

    private void runDeepSeekKeyConfig(String apiKey) {
        if (isMaintenanceActionBlocked()) {
            showMaintenanceActionBlockedToast();
            return;
        }

        try {
            OpenHouseDeepSeekController.SaveResult saveResult =
                OpenHouseDeepSeekController.getInstance(this).prepareKeyForTerminalConfiguration(apiKey);
            if (!saveResult.isSuccess()) {
                Toast.makeText(this, saveResult.message, Toast.LENGTH_SHORT).show();
                refreshStatus();
                return;
            }
            String command = buildAssetExecutionCommand(
                StageAction.CONFIGURE_DEEPSEEK,
                StageAction.CONFIGURE_DEEPSEEK.label(this),
                StageAction.CONFIGURE_DEEPSEEK.slug,
                StageAction.CONFIGURE_DEEPSEEK.assetName,
                getDefaultOpenCodePort(),
                OpenCodeInstallSpec.defaultSpec(this)
            );

            ensureMaintenanceSession();
            if (maintenanceSession == null || maintenanceSession.getTerminalSession() == null
                || !maintenanceSession.getTerminalSession().isRunning()) {
                Toast.makeText(this, R.string.status_terminal_failed, Toast.LENGTH_SHORT).show();
                refreshStatus();
                return;
            }

            currentStageSlug = StageAction.CONFIGURE_DEEPSEEK.slug;
            currentStageLabel = StageAction.CONFIGURE_DEEPSEEK.label(this);
            commandInFlight = true;
            lastHandledMarker = null;
            scheduleTerminalCompletionPoll();
            terminalStatusView.setText(R.string.embedded_terminal_status_busy);
            liveLogView.setText(getString(R.string.result_placeholder));
            updateLogButtonState();
            refreshStatus();
            updateExecutionModeViews();

            maintenanceSession.getTerminalSession().write(command);
            if (!command.endsWith("\n")) {
                maintenanceSession.getTerminalSession().write("\n");
            }
        } catch (IOException e) {
            commandInFlight = false;
            liveLogView.setText(getString(R.string.full_log_error, e.getMessage()));
            refreshStatus();
            updateExecutionModeViews();
        }
    }

    private void showCustomPortDialog() {
        if (isMaintenanceActionBlocked()) {
            showMaintenanceActionBlockedToast();
            return;
        }

        EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        input.setHint(getString(R.string.custom_port_dialog_hint));
        input.setText(Integer.toString(getDefaultOpenCodePort()));
        input.setSelection(input.getText().length());

        new AlertDialog.Builder(this)
            .setTitle(R.string.custom_port_dialog_title)
            .setView(input)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                String value = input.getText() == null ? "" : input.getText().toString().trim();
                int port;
                try {
                    port = Integer.parseInt(value);
                } catch (NumberFormatException e) {
                    Toast.makeText(this, R.string.custom_port_invalid, Toast.LENGTH_SHORT).show();
                    return;
                }
                if (port < 1 || port > 65535) {
                    Toast.makeText(this, R.string.custom_port_invalid, Toast.LENGTH_SHORT).show();
                    return;
                }
                runCustomPortStart(port);
            })
            .show();
    }

    private void showDownloadSourceModeDialog() {
        if (isMaintenanceActionBlocked()) {
            showMaintenanceActionBlockedToast();
            return;
        }

        OpenCodeDownloadSourceSettings.Mode[] modes = OpenCodeDownloadSourceSettings.Mode.values();
        String[] labels = new String[] {
            getString(R.string.download_source_mode_auto),
            getString(R.string.download_source_mode_official_only),
            getString(R.string.download_source_mode_mirror_only)
        };

        int checkedItem = OpenCodeDownloadSourceSettings.getMode(this).ordinal();
        new AlertDialog.Builder(this)
            .setTitle(R.string.button_configure_download_source)
            .setSingleChoiceItems(labels, checkedItem, (dialog, which) -> {
                OpenCodeDownloadSourceSettings.Mode selectedMode = modes[which];
                OpenCodeDownloadSourceSettings.setMode(this, selectedMode);
                Toast.makeText(this, getString(R.string.download_source_mode_saved, getDownloadSourceModeLabel(selectedMode)), Toast.LENGTH_SHORT).show();
                refreshStatus();
                dialog.dismiss();
            })
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    private void runCustomPortStart(int port) {
        if (isMaintenanceActionBlocked()) {
            showMaintenanceActionBlockedToast();
            return;
        }

        if (isBatteryRequirementBlocking()) {
            currentStageView.setText(getString(R.string.permission_requirement_state_required));
            Toast.makeText(this, R.string.permission_battery_required_toast, Toast.LENGTH_LONG).show();
            return;
        }

        ensureMaintenanceSession();
        if (maintenanceSession == null || maintenanceSession.getTerminalSession() == null
            || !maintenanceSession.getTerminalSession().isRunning()) {
            Toast.makeText(this, R.string.status_terminal_failed, Toast.LENGTH_SHORT).show();
            refreshStatus();
            return;
        }

        currentStageSlug = "start_port_" + port;
        currentStageLabel = getString(R.string.custom_port_stage_label, port);
        commandInFlight = true;
        lastHandledMarker = null;
        scheduleTerminalCompletionPoll();
        terminalStatusView.setText(R.string.embedded_terminal_status_busy);
        liveLogView.setText(getString(R.string.result_placeholder));
        updateLogButtonState();
        refreshStatus();

        try {
            String command = buildAssetExecutionCommand(
                StageAction.START,
                currentStageLabel,
                currentStageSlug,
                "start-opencode.sh",
                port,
                OpenCodeInstallSpec.defaultSpec(this)
            );
            maintenanceSession.getTerminalSession().write(command);
            if (!command.endsWith("\n")) {
                maintenanceSession.getTerminalSession().write("\n");
            }
        } catch (IOException e) {
            commandInFlight = false;
            terminalStatusView.setText(R.string.embedded_terminal_status_ready);
            liveLogView.setText(getString(R.string.full_log_error, e.getMessage()));
            refreshStatus();
        }
    }

    private String buildWrapperScript(String stageLabel, String stageSlug, String scriptBody) {
        StringBuilder builder = new StringBuilder();
        builder.append("#!/data/data/com.termux/files/usr/bin/bash\n");
        builder.append("set -euo pipefail\n");
        builder.append("export HOME=\"${HOME:-/data/data/com.termux/files/home}\"\n");
        builder.append("export PREFIX=\"${PREFIX:-/data/data/com.termux/files/usr}\"\n");
        builder.append("export PATH=\"$PREFIX/bin:/system/bin:${PATH:-}\"\n");
        builder.append("export LD_LIBRARY_PATH=\"$PREFIX/lib:${LD_LIBRARY_PATH:-}\"\n");
        builder.append("export TMPDIR=\"${TMPDIR:-$PREFIX/tmp}\"\n");
        builder.append("export TERM=\"xterm-256color\"\n");
        builder.append("export SMALLPHONEAI_BOOTSTRAP=\"${SMALLPHONEAI_BOOTSTRAP:-$HOME/.smallphoneai-bootstrap/bootstrap.sh}\"\n");
        builder.append("export SMALLPHONEAI_OFFLINE_PAYLOAD_DIR=\"${SMALLPHONEAI_OFFLINE_PAYLOAD_DIR:-$HOME/.smallphoneai-bootstrap/apk-assets/openhouse/product-payloads}\"\n");
        builder.append("if [ -f \"$SMALLPHONEAI_OFFLINE_PAYLOAD_DIR/manifest.json\" ]; then export SMALLPHONEAI_OFFLINE_PAYLOAD_MANIFEST=\"${SMALLPHONEAI_OFFLINE_PAYLOAD_MANIFEST:-$SMALLPHONEAI_OFFLINE_PAYLOAD_DIR/manifest.json}\"; fi\n");
        builder.append("STAGE_NAME=").append(shellQuote(stageLabel)).append('\n');
        builder.append("STAGE_SLUG=").append(shellQuote(stageSlug)).append('\n');
        builder.append("LOG_DIR=\"$HOME/.maintainer-logs\"\n");
        builder.append("LOG_FILE=\"$LOG_DIR/$STAGE_SLUG.log\"\n");
        builder.append("mkdir -p \"$LOG_DIR\"\n");
        builder.append(": > \"$LOG_FILE\"\n");
        builder.append("log(){ printf '%s\\n' \"$1\" | tee -a \"$LOG_FILE\"; }\n");
        builder.append("run_logged(){ local status=0; set +e; \"$@\" 2>&1 | tee -a \"$LOG_FILE\"; status=${PIPESTATUS[0]}; set -e; return \"$status\"; }\n");
        builder.append("is_termux(){ [ -n \"${PREFIX:-}\" ] && [ -d \"${PREFIX:-}/bin\" ] && [ -d \"/data/data/com.termux/files\" ]; }\n");
        builder.append("is_current_ubuntu(){ [ -r /etc/os-release ] && grep -qi 'ubuntu' /etc/os-release; }\n");
        builder.append("detect_openhouseai_runtime(){ if is_current_ubuntu; then printf 'ubuntu'; return 0; fi; if [ -x \"${PREFIX:-/data/data/com.termux/files/usr}/bin/openhouseai-env-probe\" ]; then \"${PREFIX:-/data/data/com.termux/files/usr}/bin/openhouseai-env-probe\" 2>/dev/null | awk -F= '$1==\"OPENHOUSEAI_RUNTIME\"{print $2; found=1} END{if(!found) exit 1}' && return 0; fi; if is_termux; then printf 'termux'; return 0; fi; printf 'unknown'; }\n");
        builder.append("run_environment_probe(){ local probe=\"${PREFIX:-/data/data/com.termux/files/usr}/bin/openhouseai-env-probe\"; if [ -x \"$probe\" ]; then log \"正在执行环境探测命令：$probe\"; run_logged \"$probe\" || true; else log \"环境探测命令不存在，使用内置探测逻辑。\"; fi; CURRENT_RUNTIME=\"$(detect_openhouseai_runtime)\"; log \"当前运行环境：$CURRENT_RUNTIME\"; }\n");
        builder.append("run_ubuntu_logged(){ if is_current_ubuntu; then run_logged \"$@\"; else run_logged proot-distro login ubuntu -- \"$@\"; fi; }\n");
        builder.append("require_ubuntu(){ if is_current_ubuntu; then return 0; fi; if ! command -v proot-distro >/dev/null 2>&1; then log '缺少 proot-distro，请先执行“更新 Termux 软件包”。'; exit 2; fi; if ! proot-distro login ubuntu -- true >/dev/null 2>&1; then log 'Ubuntu 尚未安装，请先执行“下载 Ubuntu”。'; exit 3; fi; }\n");
        builder.append("__maint_finish(){ local exit_code=$?; printf '__TERMUX_MAINT_DONE__:%s:%s\\n' \"$STAGE_SLUG\" \"$exit_code\" | tee -a \"$LOG_FILE\"; }\n");
        builder.append("trap __maint_finish EXIT\n");
        builder.append("log \"==> $STAGE_NAME\"\n");
        builder.append("run_environment_probe\n");
        builder.append(scriptBody).append('\n');
        return builder.toString();
    }

    private void openFullLog() {
        if (currentStageSlug == null || currentStageSlug.isEmpty() || !MaintainerLogStore.hasLog(currentStageSlug)) {
            Toast.makeText(this, R.string.full_log_unavailable, Toast.LENGTH_SHORT).show();
            return;
        }

        Intent intent = new Intent(this, MaintenanceLogActivity.class);
        intent.putExtra(MaintenanceLogActivity.EXTRA_STAGE_SLUG, currentStageSlug);
        intent.putExtra(MaintenanceLogActivity.EXTRA_STAGE_LABEL, currentStageLabel);
        ActivityUtils.startActivity(this, intent);
    }

    private void openBrowser() {
        openUrl(getOpenCodeUrl(), "OpenCode browser URL");
    }

    private void copyToClipboard(String label, String text) {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (clipboard == null) {
            return;
        }
        clipboard.setPrimaryClip(ClipData.newPlainText(label, text));
        Toast.makeText(this, getString(R.string.clipboard_copy_toast, label), Toast.LENGTH_SHORT).show();
    }

    private void openUrl(String url, String label) {
        backgroundExecutor.execute(() -> {
            boolean openedViaCdp = OpenCodeCdpBridge.isCdpActive() && OpenCodeCdpBridge.openTab(url);
            runOnUiThread(() -> {
                if (openedViaCdp) {
                    Toast.makeText(this, R.string.quick_launch_browser_tab, Toast.LENGTH_SHORT).show();
                    return;
                }

                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
                    Toast.makeText(this, R.string.quick_launch_browser_fallback, Toast.LENGTH_SHORT).show();
                } catch (Exception e) {
                    Logger.logStackTraceWithMessage(LOG_TAG, "Failed to open " + label, e);
                    Toast.makeText(this, getString(R.string.full_log_error, e.getMessage()), Toast.LENGTH_SHORT).show();
                }
            });
        });
    }

    private void refreshStatus() {
        boolean terminalReady = maintenanceSession != null
            && maintenanceSession.getTerminalSession() != null
            && maintenanceSession.getTerminalSession().isRunning();
        String stageOverview = getStageOverviewText();
        String permissionOverview = getPermissionOverviewText();
        String downloadSourceStatus = getDownloadSourceStatusText();
        OpenHouseComponentRegistry.LoadResult registryResult = OpenHouseComponentRegistry.loadWithDiagnostics();

        if (commandInFlight) {
            statusHeadlineView.setText(R.string.status_running_title);
        } else {
            statusHeadlineView.setText(R.string.status_ready_title);
        }

        StringBuilder body = new StringBuilder();
        if (terminalReady) {
            body.append("维护终端：").append(getString(R.string.status_terminal_ready)).append('\n');
        } else if (terminalFailureMessage != null && !terminalFailureMessage.isEmpty()) {
            body.append("维护终端：").append(getString(R.string.status_terminal_closed)).append('\n');
            body.append("终端提示：").append(terminalFailureMessage).append('\n');
        } else {
            body.append("维护终端：").append(getString(R.string.status_terminal_starting)).append('\n');
        }
        body.append("阶段执行：").append(commandInFlight ? "进行中" : "空闲").append('\n');
        body.append("OpenCode 端点：").append(getOpenCodeStatusText()).append('\n');
        body.append(getString(R.string.default_port_label, getDefaultOpenCodePort())).append('\n');
        body.append(getString(R.string.default_browser_label, getOpenCodeUrl())).append('\n');
        body.append(downloadSourceStatus).append('\n');
        body.append(permissionOverview).append('\n');
        body.append("产品文档：").append(TermuxConstants.TERMUX_HOME_DIR_PATH).append("/openhouseai-docs").append('\n');
        body.append("工作区：").append(TermuxConstants.TERMUX_HOME_DIR_PATH).append("/workspace").append('\n');
        body.append("菜单注册：").append(registryResult.toDiagnosticText()).append('\n');
        body.append("阶段校验：").append(stageOverview);
        statusBodyView.setText(body.toString());
        updateCurrentStageSummary();
        updateOpenBrowserButtonState();
        updatePermissionButtons();
        updateDownloadSourceCard();
        updateLocalMaintenanceWebCard();
    }

    private String getOpenCodeStatusText() {
        if (opencodeReachable == null) {
            return "检测中";
        }
        return opencodeReachable ? "可访问" : "不可访问";
    }

    private String getPermissionOverviewText() {
        int enabledCount = 0;
        if (isBatteryOptimizationExempt()) enabledCount++;
        if (isOverlayPermissionGranted()) enabledCount++;
        if (isStoragePermissionGranted()) enabledCount++;
        return getString(R.string.permission_overview_label, enabledCount)
            + "；"
            + getString(R.string.permission_startup_manual_overview)
            + "；"
            + getString(isBatteryRequirementEnabled()
                ? R.string.permission_requirement_state_required
                : R.string.permission_requirement_state_disabled);
    }

    private void updatePermissionButtons() {
        applyPermissionButtonState(
            permissionBatteryButton,
            isBatteryOptimizationExempt(),
            getString(R.string.button_permission_battery),
            getString(R.string.permission_badge_required),
            getString(R.string.permission_detail_battery_complete),
            getString(R.string.permission_detail_battery_ready)
        );
        applyPermissionButtonState(
            permissionOverlayButton,
            isOverlayPermissionGranted(),
            getString(R.string.button_permission_overlay),
            getString(R.string.permission_badge_optional),
            getString(R.string.permission_detail_overlay_complete),
            getString(R.string.permission_detail_overlay_ready)
        );
        applyPermissionButtonState(
            permissionStorageButton,
            isStoragePermissionGranted(),
            getString(R.string.button_permission_storage),
            getString(R.string.permission_badge_optional),
            getString(R.string.permission_detail_storage_complete),
            getString(R.string.permission_detail_storage_ready)
        );
        applyStartupPermissionButtonState();

        if (disableBatteryRequirementSwitch != null) {
            boolean disableRequirement = !isBatteryRequirementEnabled();
            disableBatteryRequirementSwitch.setChecked(disableRequirement);
            disableBatteryRequirementSwitch.setEnabled(!isMaintenanceActionBlocked());
            disableBatteryRequirementSwitch.setAlpha(disableBatteryRequirementSwitch.isEnabled() ? 1.0f : 0.78f);
        }
        if (permissionRequirementHintView != null) {
            permissionRequirementHintView.setText(isBatteryRequirementEnabled()
                ? R.string.permission_requirement_hint
                : R.string.permission_requirement_state_disabled);
        }
    }

    private void applyPermissionButtonState(SwitchCompat button, boolean granted, String label, String tag, String grantedDetail, String missingDetail) {
        if (button == null) return;

        button.setText(label + " · " + tag + "\n" + (granted ? grantedDetail : missingDetail));
        button.setChecked(granted);
        button.setEnabled(!isMaintenanceActionBlocked());
        button.setAlpha(button.isEnabled() ? 1.0f : 0.78f);
    }

    private void applyStartupPermissionButtonState() {
        if (permissionStartupButton == null) return;

        permissionStartupButton.setText(getString(R.string.button_permission_startup)
            + " · "
            + getString(R.string.permission_badge_manual)
            + "\n"
            + getString(R.string.permission_detail_startup_manual));
        permissionStartupButton.setAllCaps(false);
        permissionStartupButton.setEnabled(!isMaintenanceActionBlocked());
        permissionStartupButton.setAlpha(permissionStartupButton.isEnabled() ? 1.0f : 0.78f);
    }

    private void updateDownloadSourceCard() {
        OpenCodeDownloadSourceSettings.Mode mode = OpenCodeDownloadSourceSettings.getMode(this);
        String currentSourceLabel = getDownloadSourceLabel(getPreferredOpenCodeSourceId());
        String summary = getDownloadSourceSummaryText(mode);

        if (downloadSourceSummaryView != null) {
            downloadSourceSummaryView.setText(
                getString(R.string.download_source_strategy_line, getDownloadSourceModeLabel(mode))
                    + "\n"
                    + getString(R.string.download_source_current_line, currentSourceLabel)
                    + "\n"
                    + summary
            );
        }

        if (configureDownloadSourceButton != null) {
            configureDownloadSourceButton.setText(getString(R.string.button_configure_download_source_with_value, getDownloadSourceModeLabel(mode)));
            configureDownloadSourceButton.setEnabled(!isMaintenanceActionBlocked());
            configureDownloadSourceButton.setAlpha(configureDownloadSourceButton.isEnabled() ? 1.0f : 0.78f);
        }

        if (probeDownloadSourceButton != null) {
            probeDownloadSourceButton.setEnabled(!isMaintenanceActionBlocked());
            probeDownloadSourceButton.setAlpha(probeDownloadSourceButton.isEnabled() ? 1.0f : 0.78f);
        }
    }

    private void updateLocalMaintenanceWebCard() {
        int port = getLocalMaintenanceWebPort();
        if (localMaintenanceWebSummaryView != null) {
            localMaintenanceWebSummaryView.setText(getString(
                R.string.local_maintenance_web_summary,
                port,
                getLocalMaintenanceWebUrl()
            ));
        }
        if (configureMaintenanceWebPortButton != null) {
            configureMaintenanceWebPortButton.setText(getString(R.string.button_configure_maintenance_web_port, port));
            configureMaintenanceWebPortButton.setEnabled(!isMaintenanceActionBlocked());
            configureMaintenanceWebPortButton.setAlpha(configureMaintenanceWebPortButton.isEnabled() ? 1.0f : 0.78f);
        }
        if (openMaintenanceWebButton != null) {
            openMaintenanceWebButton.setEnabled(!isMaintenanceActionBlocked());
            openMaintenanceWebButton.setAlpha(openMaintenanceWebButton.isEnabled() ? 1.0f : 0.78f);
        }
        if (stopMaintenanceWebButton != null) {
            stopMaintenanceWebButton.setEnabled(!isMaintenanceActionBlocked());
            stopMaintenanceWebButton.setAlpha(stopMaintenanceWebButton.isEnabled() ? 1.0f : 0.78f);
        }
    }

    private String getDownloadSourceStatusText() {
        return getString(R.string.download_source_strategy_line, getDownloadSourceModeLabel(OpenCodeDownloadSourceSettings.getMode(this)))
            + "；"
            + getString(R.string.download_source_current_line, getDownloadSourceLabel(getPreferredOpenCodeSourceId()));
    }

    private String getDownloadSourceSummaryText(OpenCodeDownloadSourceSettings.Mode mode) {
        if (PROBE_OPENCODE_SOURCE_SLUG.equals(currentStageSlug) && commandInFlight) {
            return getString(R.string.download_source_summary_running);
        }

        switch (mode) {
            case OFFICIAL_ONLY:
                return getString(R.string.download_source_summary_manual_official);
            case MIRROR_ONLY:
                return getString(R.string.download_source_summary_manual_mirror);
            case AUTO:
            default:
                String summary = OpenCodeDownloadSourceSettings.getLastProbeSummary(this);
                if (summary == null || summary.trim().isEmpty()) {
                    return getString(R.string.download_source_summary_pending);
                }
                return summary;
        }
    }

    private String getDownloadSourceModeLabel(OpenCodeDownloadSourceSettings.Mode mode) {
        switch (mode) {
            case OFFICIAL_ONLY:
                return getString(R.string.download_source_mode_official_only);
            case MIRROR_ONLY:
                return getString(R.string.download_source_mode_mirror_only);
            case AUTO:
            default:
                return getString(R.string.download_source_mode_auto);
        }
    }

    private String getDownloadSourceLabel(String sourceId) {
        return OpenCodeDownloadSourceSettings.SOURCE_MIRROR.equals(OpenCodeDownloadSourceSettings.normalizeSourceId(sourceId))
            ? getString(R.string.download_source_label_mirror)
            : getString(R.string.download_source_label_official);
    }

    private String getPreferredOpenCodeSourceId() {
        OpenCodeDownloadSourceSettings.Mode mode = OpenCodeDownloadSourceSettings.getMode(this);
        switch (mode) {
            case OFFICIAL_ONLY:
                return OpenCodeDownloadSourceSettings.SOURCE_OFFICIAL;
            case MIRROR_ONLY:
                return OpenCodeDownloadSourceSettings.SOURCE_MIRROR;
            case AUTO:
            default:
                return OpenCodeDownloadSourceSettings.getLastSelectedSourceId(this);
        }
    }

    private boolean shouldProbeOpenCodeSourceBeforeInstall() {
        return false;
    }

    private void runOpenCodeSourceProbe(boolean continueWithInstall) {
        if (isMaintenanceActionBlocked()) {
            showMaintenanceActionBlockedToast();
            return;
        }

        currentStageSlug = PROBE_OPENCODE_SOURCE_SLUG;
        currentStageLabel = getString(R.string.download_source_probe_stage_label);
        commandInFlight = true;
        lastHandledMarker = null;
        pendingStageAction = null;
        terminalStatusView.setText(R.string.embedded_terminal_status_busy);
        liveLogView.setText(R.string.download_source_summary_running);
        updateLogButtonState();
        refreshStatus();

        backgroundExecutor.execute(() -> {
            OpenCodeSourceProbeResult result = probeOpenCodeSource();
            runOnUiThread(() -> {
                commandInFlight = false;
                terminalStatusView.setText(R.string.embedded_terminal_status_ready);
                refreshLiveLog();
                refreshStatus();
                requestStageStatusRefresh();
                Toast.makeText(this,
                    result.success ? R.string.download_source_probe_success_toast : R.string.download_source_probe_failed_toast,
                    Toast.LENGTH_SHORT).show();
                if (continueWithInstall) {
                    runStage(StageAction.INSTALL_OPENCODE, true);
                }
            });
        });
    }

    private boolean isBatteryRequirementEnabled() {
        return !maintenancePreferences.getBoolean(PREF_DISABLE_BATTERY_REQUIREMENT, false);
    }

    private boolean isBatteryRequirementBlocking() {
        return isBatteryRequirementEnabled() && !isBatteryOptimizationExempt();
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
            Toast.makeText(this, getString(R.string.permission_detail_battery_complete), Toast.LENGTH_SHORT).show();
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
            Toast.makeText(this, getString(R.string.permission_detail_overlay_complete), Toast.LENGTH_SHORT).show();
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

            Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            intent.setData(Uri.parse("package:" + getPackageName()));
            startActivity(intent);
        } catch (Exception e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to open storage settings", e);
            Toast.makeText(this, R.string.permission_open_storage_failed, Toast.LENGTH_SHORT).show();
        }
    }

    private void openStartupPermissionSettings() {
        OpenHouseStartupPermissionHelper.openStartupPermissionSettings(this);
    }

    private String getStageOverviewText() {
        if (stagePresentations.isEmpty()) {
            return "检测中";
        }

        int completeCount = 0;
        int failedCount = 0;
        int blockedCount = 0;
        int runningCount = 0;
        int checkingCount = 0;
        for (StagePresentation presentation : stagePresentations.values()) {
            if (presentation == null) continue;
            switch (presentation.state) {
                case COMPLETE:
                    completeCount++;
                    break;
                case FAILED:
                    failedCount++;
                    break;
                case BLOCKED:
                    blockedCount++;
                    break;
                case RUNNING:
                    runningCount++;
                    break;
                case CHECKING:
                    checkingCount++;
                    break;
                case READY:
                default:
                    break;
            }
        }

        if (checkingCount == StageAction.values().length) {
            return "检测中";
        }

        StringBuilder builder = new StringBuilder();
        builder.append("已完成 ").append(completeCount).append('/').append(StageAction.values().length);
        if (runningCount > 0) builder.append("，执行中 ").append(runningCount);
        if (blockedCount > 0) builder.append("，待前置 ").append(blockedCount);
        if (failedCount > 0) builder.append("，需修复 ").append(failedCount);
        if (checkingCount > 0) builder.append("，检测中 ").append(checkingCount);
        return builder.toString();
    }

    private void updateCurrentStageSummary() {
        if (commandInFlight && currentStageLabel != null) {
            currentStageView.setText("执行中：" + currentStageLabel);
            return;
        }

        if (currentStageSlug != null && !currentStageSlug.isEmpty()) {
            StageAction currentStageAction = StageAction.fromSlug(currentStageSlug);
            if (currentStageAction != null) {
                StagePresentation presentation = stagePresentations.get(currentStageAction);
                if (presentation != null) {
                    currentStageView.setText(presentation.headline(this, currentStageAction));
                    return;
                }
            }

            Integer exitCode = readLastExitCode(currentStageSlug);
            if (currentStageLabel != null && exitCode != null) {
                currentStageView.setText((exitCode == 0 ? "已完成：" : "失败：") + currentStageLabel);
                return;
            }
            if (currentStageLabel != null) {
                currentStageView.setText(currentStageLabel);
                return;
            }
        }

        currentStageView.setText(getString(R.string.current_stage_placeholder));
    }

    private void updateOpenBrowserButtonState() {
        if (openBrowserButton == null) return;
        int backgroundColor;
        int textColor;
        if (Boolean.TRUE.equals(opencodeReachable)) {
            backgroundColor = ContextCompat.getColor(this, R.color.stageComplete);
            textColor = ContextCompat.getColor(this, R.color.stageOnDark);
            openBrowserButton.setEnabled(true);
        } else if (Boolean.FALSE.equals(opencodeReachable)) {
            backgroundColor = ContextCompat.getColor(this, R.color.stageBlocked);
            textColor = ContextCompat.getColor(this, R.color.stageBlockedText);
            openBrowserButton.setEnabled(false);
        } else {
            backgroundColor = ContextCompat.getColor(this, R.color.stageChecking);
            textColor = ContextCompat.getColor(this, R.color.stageCheckingText);
            openBrowserButton.setEnabled(false);
        }
        openBrowserButton.setBackgroundTintList(ColorStateList.valueOf(backgroundColor));
        openBrowserButton.setTextColor(textColor);
    }

    private void requestStageStatusRefresh() {
        if (backgroundExecutor.isShutdown()) return;
        if (stageStatusCheckInFlight) {
            stageStatusCheckQueued = true;
            return;
        }

        stageStatusCheckInFlight = true;
        backgroundExecutor.execute(() -> {
            StageCheckSnapshot snapshot = inspectStageStatuses();
            runOnUiThread(() -> {
                stageStatusCheckInFlight = false;
                if (isFinishing() || isDestroyed()) return;
                opencodeReachable = snapshot.opencodeReachable;
                for (Map.Entry<StageAction, StagePresentation> entry : snapshot.presentations.entrySet()) {
                    stagePresentations.put(entry.getKey(), entry.getValue());
                }
                if (commandInFlight && currentStageSlug != null) {
                    StageAction runningStage = StageAction.fromSlug(currentStageSlug);
                    if (runningStage != null) {
                        stagePresentations.put(runningStage, StagePresentation.running(this));
                    }
                }
                applyStagePresentations();
                refreshStatus();
                if (!commandInFlight && pendingStageAction != null) {
                    StageAction stageAction = pendingStageAction;
                    pendingStageAction = null;
                    runStage(stageAction, true);
                    return;
                }
                if (!commandInFlight && oneClickStagesInFlight) {
                    continueOneClickStages();
                    return;
                }
                if (stageStatusCheckQueued) {
                    stageStatusCheckQueued = false;
                    requestStageStatusRefresh();
                }
            });
        });
    }

    private void applyStagePresentations() {
        updatePermissionButtons();
        boolean batteryRequirementBlocking = isBatteryRequirementBlocking();

        if (configureDefaultPortButton != null) {
            configureDefaultPortButton.setVisibility(View.GONE);
        }

        updateDownloadSourceCard();
        updateMaintenanceSourceCard();
        updateLocalMaintenanceWebCard();
        updateExecutionModeViews();

        for (StageAction stageAction : StageAction.values()) {
            Button button = stageButtons.get(stageAction);
            StagePresentation presentation = stagePresentations.get(stageAction);
            if (button == null || presentation == null) continue;

            button.setText(presentation.buttonText(this, stageAction));
            button.setBackgroundTintList(ColorStateList.valueOf(ContextCompat.getColor(this, presentation.backgroundColorRes)));
            button.setTextColor(ContextCompat.getColor(this, presentation.textColorRes));
            button.setEnabled(!isMaintenanceActionBlocked()
                && (!batteryRequirementBlocking || stageAction.isUiOnly())
                && presentation.state != StageUiState.CHECKING
                && presentation.state != StageUiState.BLOCKED);
            button.setAlpha(button.isEnabled() ? 1.0f : 0.78f);
        }

        if (customPortButton != null) {
            customPortButton.setEnabled(!isMaintenanceActionBlocked() && !batteryRequirementBlocking);
            customPortButton.setAlpha(customPortButton.isEnabled() ? 1.0f : 0.78f);
        }
    }

    private StageCheckSnapshot inspectStageStatuses() {
        StageCheckSnapshot snapshot = new StageCheckSnapshot();

        Integer prepareExitCode = readLastExitCode(StageAction.PREPARE);
        Integer termuxPackagesExitCode = readLastExitCode(StageAction.TERMUX_PACKAGES);
        Integer installUbuntuExitCode = readLastExitCode(StageAction.INSTALL_UBUNTU);
        Integer syncOfficialDocsExitCode = readLastExitCode(StageAction.SYNC_OFFICIAL_DOCS);
        Integer ubuntuPackagesExitCode = readLastExitCode(StageAction.UBUNTU_PACKAGES);
        Integer configureEntryUbuntuExitCode = readLastExitCode(StageAction.CONFIGURE_ENTRY_UBUNTU);
        Integer installNodeExitCode = readLastExitCode(StageAction.INSTALL_NODE);
        Integer installOpenCodeExitCode = readLastExitCode(StageAction.INSTALL_OPENCODE);
        Integer installCodexExitCode = readLastExitCode(StageAction.INSTALL_CODEX);
        Integer installClaudeCodeExitCode = readLastExitCode(StageAction.INSTALL_CLAUDE_CODE);
        Integer installClaudeCodeUiExitCode = readLastExitCode(StageAction.INSTALL_CLAUDE_CODE_UI);
        Integer installReasonixExitCode = readLastExitCode(StageAction.INSTALL_REASONIX);
        Integer runtimeComponentsExitCode = readLastExitCode(StageAction.RUNTIME_COMPONENTS);
        Integer installHermesExitCode = readLastExitCode(StageAction.INSTALL_HERMES);
        Integer syncOpenHouseRegistryExitCode = readLastExitCode(StageAction.SYNC_OPENHOUSE_REGISTRY);
        Integer configureDeepSeekExitCode = readLastExitCode(StageAction.CONFIGURE_DEEPSEEK);
        Integer startExitCode = readLastExitCode(StageAction.START);
        Integer restartExitCode = readLastExitCode(StageAction.RESTART);

        boolean prepareComplete = isPrepareStageComplete() || isLastExitSuccess(prepareExitCode);
        boolean termuxPackagesComplete = isTermuxPackagesStageComplete() || isLastExitSuccess(termuxPackagesExitCode);
        boolean ubuntuInstalled = termuxPackagesComplete && (isUbuntuInstalled() || isLastExitSuccess(installUbuntuExitCode));
        boolean officialDocsSynced = ubuntuInstalled && (isOfficialDocsSynced() || isLastExitSuccess(syncOfficialDocsExitCode));
        boolean ubuntuPackagesComplete = officialDocsSynced && (isUbuntuPackagesStageComplete() || isLastExitSuccess(ubuntuPackagesExitCode));
        boolean entryUbuntuConfigured = ubuntuPackagesComplete && (isEntryUbuntuConfigured() || isLastExitSuccess(configureEntryUbuntuExitCode));
        boolean nodeInstalled = entryUbuntuConfigured && (isNodeInstalled() || isLastExitSuccess(installNodeExitCode));
        boolean openCodeInstalled = nodeInstalled && (isOpenCodeInstalled() || isLastExitSuccess(installOpenCodeExitCode));
        boolean codexInstalled = openCodeInstalled && (isCodexInstalled() || isLastExitSuccess(installCodexExitCode));
        boolean claudeCodeInstalled = openCodeInstalled && (isClaudeCodeInstalled() || isLastExitSuccess(installClaudeCodeExitCode));
        boolean claudeCodeUiInstalled = claudeCodeInstalled && (isClaudeCodeUiInstalled() || isLastExitSuccess(installClaudeCodeUiExitCode));
        boolean reasonixInstalled = claudeCodeUiInstalled && (isReasonixInstalled() || isLastExitSuccess(installReasonixExitCode));
        boolean runtimeComponentsInstalled = reasonixInstalled && (isRuntimeComponentsInstalled() || isLastExitSuccess(runtimeComponentsExitCode));
        boolean hermesInstalled = runtimeComponentsInstalled && (isHermesInstalled() || isLastExitSuccess(installHermesExitCode));
        boolean openHouseRegistrySynced = hermesInstalled && (isOpenHouseRegistrySynced() || isLastExitSuccess(syncOpenHouseRegistryExitCode));
        boolean deepSeekConfigured = ubuntuInstalled && reasonixInstalled && (isDeepSeekConfigured() || isLastExitSuccess(configureDeepSeekExitCode));
        boolean openCodeReachableNow = openCodeInstalled && isOpenCodeWebReachable();
        boolean startStageComplete = openCodeReachableNow || isLastExitSuccess(startExitCode);

        snapshot.opencodeReachable = openCodeReachableNow;

        snapshot.presentations.put(
            StageAction.PREPARE,
            prepareComplete
                ? StagePresentation.complete(this, getString(R.string.stage_detail_prepare_complete))
                : failedOrReady(prepareExitCode,
                    getString(R.string.stage_detail_prepare_failed),
                    getString(R.string.stage_detail_prepare_ready))
        );

        snapshot.presentations.put(
            StageAction.TERMUX_PACKAGES,
            termuxPackagesComplete
                ? StagePresentation.complete(this, getString(R.string.stage_detail_termux_packages_complete))
                : failedOrReady(termuxPackagesExitCode,
                    getString(R.string.stage_detail_termux_packages_failed),
                    getString(R.string.stage_detail_termux_packages_ready))
        );

        snapshot.presentations.put(
            StageAction.INSTALL_UBUNTU,
            ubuntuInstalled
                ? StagePresentation.complete(this, getString(R.string.stage_detail_install_ubuntu_complete))
                : (!termuxPackagesComplete
                    ? StagePresentation.blocked(this, getString(R.string.stage_detail_install_ubuntu_blocked))
                    : failedOrReady(installUbuntuExitCode,
                        getString(R.string.stage_detail_install_ubuntu_failed),
                        getString(R.string.stage_detail_install_ubuntu_ready)))
        );

        snapshot.presentations.put(
            StageAction.SYNC_OFFICIAL_DOCS,
            officialDocsSynced
                ? StagePresentation.complete(this, getString(R.string.stage_detail_sync_official_docs_complete))
                : (!ubuntuInstalled
                    ? StagePresentation.blocked(this, getString(R.string.stage_detail_sync_official_docs_blocked))
                    : failedOrReady(syncOfficialDocsExitCode,
                        getString(R.string.stage_detail_sync_official_docs_failed),
                        getString(R.string.stage_detail_sync_official_docs_ready)))
        );

        snapshot.presentations.put(
            StageAction.UBUNTU_PACKAGES,
            ubuntuPackagesComplete
                ? StagePresentation.complete(this, getString(R.string.stage_detail_ubuntu_packages_complete))
                : (!officialDocsSynced
                    ? StagePresentation.blocked(this, getString(R.string.stage_detail_ubuntu_packages_blocked))
                    : failedOrReady(ubuntuPackagesExitCode,
                        getString(R.string.stage_detail_ubuntu_packages_failed),
                        getString(R.string.stage_detail_ubuntu_packages_ready)))
        );

        snapshot.presentations.put(
            StageAction.CONFIGURE_ENTRY_UBUNTU,
            entryUbuntuConfigured
                ? StagePresentation.complete(this, getString(R.string.stage_detail_configure_entry_ubuntu_complete))
                : (!ubuntuPackagesComplete
                    ? StagePresentation.blocked(this, getString(R.string.stage_detail_configure_entry_ubuntu_blocked))
                    : failedOrReady(configureEntryUbuntuExitCode,
                        getString(R.string.stage_detail_configure_entry_ubuntu_failed),
                        getString(R.string.stage_detail_configure_entry_ubuntu_ready)))
        );

        snapshot.presentations.put(
            StageAction.INSTALL_NODE,
            nodeInstalled
                ? StagePresentation.complete(this, getString(R.string.stage_detail_install_node_complete))
                : (!entryUbuntuConfigured
                    ? StagePresentation.blocked(this, getString(R.string.stage_detail_install_node_blocked))
                    : failedOrReady(installNodeExitCode,
                        getString(R.string.stage_detail_install_node_failed),
                        getString(R.string.stage_detail_install_node_ready)))
        );

        snapshot.presentations.put(
            StageAction.INSTALL_OPENCODE,
            openCodeInstalled
                ? StagePresentation.complete(this, getString(R.string.stage_detail_install_opencode_complete))
                : (!nodeInstalled
                    ? StagePresentation.blocked(this, getString(R.string.stage_detail_install_opencode_blocked))
                    : failedOrReady(installOpenCodeExitCode,
                        getString(R.string.stage_detail_install_opencode_failed),
                        getString(R.string.stage_detail_install_opencode_ready)))
        );

        snapshot.presentations.put(
            StageAction.INSTALL_CODEX,
            codexInstalled
                ? StagePresentation.complete(this, getString(R.string.stage_detail_install_codex_complete))
                : (!openCodeInstalled
                    ? StagePresentation.blocked(this, getString(R.string.stage_detail_install_codex_blocked))
                    : failedOrReady(installCodexExitCode,
                        getString(R.string.stage_detail_install_codex_failed),
                        getString(R.string.stage_detail_install_codex_ready)))
        );

        snapshot.presentations.put(
            StageAction.INSTALL_CLAUDE_CODE,
            claudeCodeInstalled
                ? StagePresentation.complete(this, getString(R.string.stage_detail_install_claude_code_complete))
                : (!openCodeInstalled
                    ? StagePresentation.blocked(this, getString(R.string.stage_detail_install_claude_code_blocked))
                    : failedOrReady(installClaudeCodeExitCode,
                        getString(R.string.stage_detail_install_claude_code_failed),
                        getString(R.string.stage_detail_install_claude_code_ready)))
        );

        snapshot.presentations.put(
            StageAction.INSTALL_CLAUDE_CODE_UI,
            claudeCodeUiInstalled
                ? StagePresentation.complete(this, getString(R.string.stage_detail_install_claude_code_ui_complete))
                : (!claudeCodeInstalled
                    ? StagePresentation.blocked(this, getString(R.string.stage_detail_install_claude_code_ui_blocked))
                    : failedOrReady(installClaudeCodeUiExitCode,
                        getString(R.string.stage_detail_install_claude_code_ui_failed),
                        getString(R.string.stage_detail_install_claude_code_ui_ready)))
        );

        snapshot.presentations.put(
            StageAction.INSTALL_REASONIX,
            reasonixInstalled
                ? StagePresentation.complete(this, getString(R.string.stage_detail_install_reasonix_complete))
                : (!claudeCodeUiInstalled
                    ? StagePresentation.blocked(this, getString(R.string.stage_detail_install_reasonix_blocked))
                    : failedOrReady(installReasonixExitCode,
                        getString(R.string.stage_detail_install_reasonix_failed),
                        getString(R.string.stage_detail_install_reasonix_ready)))
        );

        snapshot.presentations.put(
            StageAction.RUNTIME_COMPONENTS,
            runtimeComponentsInstalled
                ? StagePresentation.complete(this, "service-manager、openhouse-connect 与 SmallPhone 已安装或已完成。")
                : (!reasonixInstalled
                    ? StagePresentation.blocked(this, "请先完成 AI 工具安装阶段。")
                    : failedOrReady(runtimeComponentsExitCode,
                        "运行组件安装失败，请查看该阶段日志。",
                        "准备安装 service-manager、openhouse-connect 与 SmallPhone。"))
        );

        snapshot.presentations.put(
            StageAction.INSTALL_HERMES,
            hermesInstalled
                ? StagePresentation.complete(this, "Hermes WebUI 注册文件和 AI 能力文档已就绪。")
                : (!runtimeComponentsInstalled
                    ? StagePresentation.blocked(this, "请先安装 SmallPhone 运行组件。")
                    : failedOrReady(installHermesExitCode,
                        "Hermes 安装失败，请查看该阶段日志。",
                        "准备从 APK 内置 payload 安装 Hermes Agent 和 Hermes WebUI。"))
        );

        snapshot.presentations.put(
            StageAction.SYNC_OPENHOUSE_REGISTRY,
            openHouseRegistrySynced
                ? StagePresentation.complete(this, "OpenHouseAI registry 已同步到 Termux canonical。")
                : (!hermesInstalled
                    ? StagePresentation.blocked(this, "请先完成 Hermes 安装注册。")
                    : failedOrReady(syncOpenHouseRegistryExitCode,
                        "OpenHouseAI registry 同步失败，请查看该阶段日志。",
                        "准备同步 components.d、service-manager/services.d 和 AI docs。"))
        );

        snapshot.presentations.put(
            StageAction.REQUEST_DEEPSEEK_KEY,
            deepSeekConfigured
                ? StagePresentation.complete(this, getString(R.string.stage_detail_deepseek_key_guide_complete))
                : StagePresentation.ready(this, getString(R.string.stage_detail_deepseek_key_guide_ready))
        );

        snapshot.presentations.put(
            StageAction.CONFIGURE_DEEPSEEK,
            deepSeekConfigured
                ? StagePresentation.complete(this, getString(R.string.stage_detail_configure_deepseek_complete))
                : (!ubuntuInstalled
                    ? StagePresentation.blocked(this, getString(R.string.stage_detail_configure_deepseek_blocked))
                    : failedOrReady(configureDeepSeekExitCode,
                        getString(R.string.stage_detail_configure_deepseek_failed),
                        getString(R.string.stage_detail_configure_deepseek_ready)))
        );

        snapshot.presentations.put(
            StageAction.RESTART_ENTRY_TERMINAL,
            StagePresentation.ready(this, getString(R.string.stage_detail_restart_entry_terminal_ready))
        );

        snapshot.presentations.put(
            StageAction.START,
            startStageComplete
                ? StagePresentation.complete(this, getString(R.string.stage_detail_start_complete))
                : (!openCodeInstalled
                    ? StagePresentation.blocked(this, getString(R.string.stage_detail_start_blocked))
                    : failedOrReady(startExitCode,
                        getString(R.string.stage_detail_start_failed),
                        getString(R.string.stage_detail_start_ready)))
        );

        snapshot.presentations.put(
            StageAction.RESTART,
            !openCodeInstalled
                ? StagePresentation.blocked(this, getString(R.string.stage_detail_restart_blocked))
                : failedOrReady(restartExitCode,
                    getString(R.string.stage_detail_restart_failed),
                    openCodeReachableNow
                        ? getString(R.string.stage_detail_restart_ready_running)
                        : getString(R.string.stage_detail_restart_ready_stopped))
        );

        return snapshot;
    }

    private boolean isLastExitSuccess(Integer exitCode) {
        return exitCode != null && exitCode == 0;
    }

    private StagePresentation failedOrReady(Integer exitCode, String failedDetail, String readyDetail) {
        if (exitCode != null && exitCode != 0) {
            return StagePresentation.failed(this, failedDetail);
        }
        return StagePresentation.ready(this, readyDetail);
    }

    private boolean isPrepareStageComplete() {
        File docsDir = new File(TermuxConstants.TERMUX_HOME_DIR_PATH, "openhouseai-docs");
        File workspaceDir = new File(TermuxConstants.TERMUX_HOME_DIR_PATH, "workspace");
        File readmeFile = new File(docsDir, "README.md");
        File environmentFile = new File(docsDir, "ENVIRONMENT.md");
        File modelApiSetupFile = new File(docsDir, "MODEL_API_SETUP.md");
        File propertiesFile = new File(TermuxConstants.TERMUX_PROPERTIES_PRIMARY_FILE_PATH);

        return docsDir.isDirectory()
            && workspaceDir.isDirectory()
            && readmeFile.isFile()
            && environmentFile.isFile()
            && modelApiSetupFile.isFile()
            && propertyContainsAllowExternalApps(propertiesFile);
    }

    private boolean propertyContainsAllowExternalApps(File propertiesFile) {
        if (!propertiesFile.isFile()) return false;

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
            new java.io.FileInputStream(propertiesFile), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String normalized = line.replace(" ", "");
                if (normalized.startsWith(TermuxConstants.PROP_ALLOW_EXTERNAL_APPS + "=")) {
                    return normalized.endsWith("=true");
                }
            }
        } catch (IOException e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to read termux.properties for maintenance verification", e);
        }

        return false;
    }

    private boolean isTermuxPackagesStageComplete() {
        return new File(TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH, "proot-distro").canExecute()
            && new File(TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH, "curl").canExecute();
    }

    private boolean isUbuntuInstalled() {
        if (!isTermuxPackagesStageComplete()) return false;
        return runTermuxCommand("proot-distro login ubuntu -- true >/dev/null 2>&1").isSuccess();
    }

    private boolean isUbuntuPackagesStageComplete() {
        return runTermuxCommand(
            "proot-distro login ubuntu -- bash -lc 'command -v curl >/dev/null 2>&1 && command -v git >/dev/null 2>&1 && command -v ps >/dev/null 2>&1 && test -e /etc/ssl/certs/ca-certificates.crt'"
        ).isSuccess();
    }

    private boolean isOfficialDocsSynced() {
        File officialDocsDir = new File(TermuxConstants.TERMUX_HOME_DIR_PATH, "openhouseai-docs/official");
        File agentNotesDir = new File(TermuxConstants.TERMUX_HOME_DIR_PATH, "openhouseai-docs/agent-notes");
        return officialDocsDir.isDirectory()
            && agentNotesDir.isDirectory()
            && new File(officialDocsDir, "START_HERE.md").isFile()
            && new File(officialDocsDir, "ENVIRONMENT.md").isFile()
            && new File(officialDocsDir, "MODEL_API_SETUP.md").isFile();
    }

    private boolean isOpenCodeInstalled() {
        return runTermuxCommand(
            "proot-distro login ubuntu -- bash -lc 'export PATH=\"$HOME/.local/node/bin:$HOME/.npm-global/bin:$HOME/.opencode/bin:$HOME/.local/bin:$PATH\"; (command -v opencode >/dev/null 2>&1 || test -x \"$HOME/.opencode/bin/opencode\") && test -f \"$HOME/openhouseai-links/docs-path.txt\" && test -f \"$HOME/openhouseai-links/workspace-path.txt\"'"
        ).isSuccess();
    }

    private boolean isNodeInstalled() {
        return runTermuxCommand(
            "proot-distro login ubuntu -- bash -lc 'export PATH=\"$HOME/.local/node/bin:$HOME/.npm-global/bin:$HOME/.local/bin:/usr/local/bin:$PATH\"; command -v node >/dev/null 2>&1 && command -v npm >/dev/null 2>&1 && test \"$(node -p \"process.versions.node.split(\\\".\\\")[0]\" 2>/dev/null || printf 0)\" -ge 24'"
        ).isSuccess();
    }

    private boolean isCodexInstalled() {
        return runTermuxCommand(
            "proot-distro login ubuntu -- bash -lc 'export PATH=\"$HOME/.local/node/bin:$HOME/.npm-global/bin:$HOME/.local/bin:$PATH\"; command -v codex >/dev/null 2>&1'"
        ).isSuccess();
    }

    private boolean isClaudeCodeInstalled() {
        return runTermuxCommand(
            "proot-distro login ubuntu -- bash -lc 'export PATH=\"$HOME/.local/node/bin:$HOME/.npm-global/bin:$HOME/.local/bin:$PATH\"; command -v claude >/dev/null 2>&1'"
        ).isSuccess();
    }

    private boolean isClaudeCodeUiInstalled() {
        return runTermuxCommand(
            "proot-distro login ubuntu -- bash -lc 'export PATH=\"$HOME/.local/node/bin:$HOME/.npm-global/bin:$HOME/.opencode/bin:$HOME/.local/bin:/usr/local/bin:$PATH\"; command -v cloudcli >/dev/null 2>&1 && test \"$(cat \"$HOME/.config/openhouseai/claude-code-ui-port\" 2>/dev/null || true)\" = \"" + ClaudeCodeUiSettings.DEFAULT_PORT + "\"'"
        ).isSuccess();
    }

    private boolean isReasonixInstalled() {
        return runTermuxCommand(
            "proot-distro login ubuntu -- bash -lc 'export PATH=\"$HOME/.local/node/bin:$HOME/.npm-global/bin:$HOME/.opencode/bin:$HOME/.local/bin:/usr/local/bin:$PATH\"; command -v reasonix >/dev/null 2>&1'"
        ).isSuccess();
    }

    private boolean isRuntimeComponentsInstalled() {
        return runTermuxCommand(
            "proot-distro login ubuntu -- bash -lc 'test -x \"$HOME/smallphoneai-repos/service-manager/service-manager\" -o -x \"$HOME/smallphoneai-repos/service-manager/target/release/service-manager\"; test -d \"$HOME/smallphoneai-repos/openhouse-connect\"; test -d \"$HOME/smallphoneai-repos/smallphone-active\"'"
        ).isSuccess();
    }

    private boolean isHermesInstalled() {
        return runTermuxCommand(
            "proot-distro login ubuntu -- bash -lc 'test -x \"$HOME/smallphoneai-repos/hermes/hermes-agent/venv/bin/python\" && test -f \"$HOME/smallphoneai-repos/hermes/hermes-webui/bootstrap.py\" && test -f \"$HOME/.config/openhouseai/components.d/hermes-webui.json\" && test -f \"$HOME/.config/openhouseai/service-manager/services.d/hermes-webui.json\" && test -f \"$HOME/.config/openhouseai/ai-docs/hermes-webui/capabilities.json\"'"
        ).isSuccess();
    }

    private boolean isOpenHouseRegistrySynced() {
        File termuxConfigDir = new File(TermuxConstants.TERMUX_HOME_DIR_PATH, ".config/openhouseai");
        return new File(termuxConfigDir, "components.d/hermes-webui.json").isFile()
            && new File(termuxConfigDir, "service-manager/services.d/hermes-webui.json").isFile()
            && new File(termuxConfigDir, "ai-docs/hermes-webui/capabilities.json").isFile();
    }

    private boolean isEntryUbuntuConfigured() {
        return runTermuxCommand(
            "test \"$(tr -d '[:space:]' < \"$HOME/.openhouseai/entry-mode\" 2>/dev/null || true)\" = ubuntu && test -f \"$HOME/.openhouseai/entry.sh\" && grep -Fq '# OpenHouseAI startup entry' \"$HOME/.bashrc\""
        ).isSuccess();
    }

    private boolean isDeepSeekConfigured() {
        return runTermuxCommand(
            "proot-distro login ubuntu -- bash -lc 'test -s \"$HOME/.config/openhouseai/deepseek-api-key\" && test -f \"$HOME/.config/opencode/opencode.json\" && test -f \"$HOME/.reasonix/config.json\" && { grep -Fq \"ANTHROPIC_BASE_URL=https://api.deepseek.com/anthropic\" \"$HOME/.config/openhouseai/claude-code-env\" 2>/dev/null || grep -Fq \"ANTHROPIC_BASE_URL=https://api.deepseek.com/anthropic\" \"$HOME/.bashrc\"; }'"
        ).isSuccess();
    }

    private ShellCheckResult runTermuxCommand(String command) {
        Process process = null;
        try {
            ProcessBuilder processBuilder = new ProcessBuilder(
                TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + "/bash",
                "-lc",
                command
            );
            processBuilder.directory(new File(TermuxConstants.TERMUX_HOME_DIR_PATH));
            processBuilder.redirectErrorStream(true);
            Map<String, String> environment = processBuilder.environment();
            environment.put("HOME", TermuxConstants.TERMUX_HOME_DIR_PATH);
            environment.put("PREFIX", TermuxConstants.TERMUX_PREFIX_DIR_PATH);
            environment.put("PATH", TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + ":/system/bin");
            environment.put("LD_LIBRARY_PATH", TermuxConstants.TERMUX_LIB_PREFIX_DIR_PATH);
            environment.put("TMPDIR", TermuxConstants.TERMUX_TMP_PREFIX_DIR_PATH);
            environment.put("LANG", "C.UTF-8");
            environment.put("OPENHOUSEAI_NO_AUTO_UBUNTU", "1");
            environment.put("TERMUX_NO_AUTO_UBUNTU", "1");

            process = processBuilder.start();
            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (output.length() < 600) {
                        if (output.length() > 0) output.append('\n');
                        output.append(line);
                    }
                }
            }

            if (!process.waitFor(12, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return new ShellCheckResult(124, output.toString());
            }

            return new ShellCheckResult(process.exitValue(), output.toString());
        } catch (Exception e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to run maintenance verification command", e);
            return new ShellCheckResult(1, e.getMessage());
        } finally {
            if (process != null) {
                process.destroy();
            }
        }
    }

    private Integer readLastExitCode(StageAction stageAction) {
        return readLastExitCode(stageAction.slug);
    }

    private Integer readLastExitCode(String stageSlug) {
        try {
            String content = MaintainerLogStore.readLog(stageSlug);
            Matcher matcher = DONE_PATTERN.matcher(content);
            Integer exitCode = null;
            while (matcher.find()) {
                exitCode = Integer.parseInt(matcher.group(2));
            }
            return exitCode;
        } catch (IOException e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to read maintenance stage log for verification", e);
            return null;
        }
    }

    private boolean isOpenCodeWebReachable() {
        return runTermuxCommand(
            "proot-distro login ubuntu -- bash -lc 'curl -fsS --max-time 3 http://127.0.0.1:" + getDefaultOpenCodePort() + "/ >/dev/null 2>&1'"
        ).isSuccess();
    }

    private OpenCodeInstallSpec resolveOpenCodeInstallSpec() {
        OpenCodeDownloadSourceSettings.Mode mode = OpenCodeDownloadSourceSettings.getMode(this);
        String primarySourceId = getPreferredOpenCodeSourceId();
        String secondarySourceId = OpenCodeDownloadSourceSettings.SOURCE_OFFICIAL.equals(primarySourceId)
            ? OpenCodeDownloadSourceSettings.SOURCE_MIRROR
            : OpenCodeDownloadSourceSettings.SOURCE_OFFICIAL;
        boolean allowFallback = mode == OpenCodeDownloadSourceSettings.Mode.AUTO;

        return new OpenCodeInstallSpec(
            primarySourceId,
            getDownloadSourceLabel(primarySourceId),
            OpenCodeDownloadSourceSettings.getInstallUrlForSource(primarySourceId),
            secondarySourceId,
            getDownloadSourceLabel(secondarySourceId),
            OpenCodeDownloadSourceSettings.getInstallUrlForSource(secondarySourceId),
            allowFallback
        );
    }

    private int getDefaultOpenCodePort() {
        return OpenCodeSettings.DEFAULT_OPENCODE_PORT;
    }

    private String getOpenCodeUrl() {
        return OpenCodeSettings.getRootProjectUrl(OpenCodeSettings.DEFAULT_OPENCODE_PORT);
    }

    private int getLocalMaintenanceWebPort() {
        int port = maintenancePreferences.getInt(PREF_LOCAL_MAINTENANCE_WEB_PORT, DEFAULT_LOCAL_MAINTENANCE_WEB_PORT);
        return isValidLocalMaintenanceWebPort(port) ? port : DEFAULT_LOCAL_MAINTENANCE_WEB_PORT;
    }

    private boolean isValidLocalMaintenanceWebPort(int port) {
        return port >= MIN_LOCAL_MAINTENANCE_WEB_PORT && port <= MAX_LOCAL_MAINTENANCE_WEB_PORT;
    }

    private String getLocalMaintenanceWebUrl() {
        return "http://127.0.0.1:" + getLocalMaintenanceWebPort() + "/";
    }

    private void updateLogButtonState() {
        viewFullLogButton.setEnabled(currentStageSlug != null && MaintainerLogStore.hasLog(currentStageSlug));
    }

    private void refreshLiveLogThrottled(boolean force) {
        long now = SystemClock.uptimeMillis();
        if (!force && now - lastLiveLogRefreshUptimeMs < TERMINAL_LIVE_LOG_REFRESH_MIN_INTERVAL_MS) {
            return;
        }
        lastLiveLogRefreshUptimeMs = now;
        refreshLiveLog();
    }

    private void scheduleTerminalCompletionPoll() {
        if (!commandInFlight || terminalCompletionPollScheduled || isFinishing() || isDestroyed()) {
            return;
        }

        terminalCompletionPollScheduled = true;
        mainHandler.postDelayed(terminalCompletionPollRunnable, TERMINAL_COMPLETION_POLL_INTERVAL_MS);
    }

    private void refreshLiveLog() {
        if (currentStageSlug == null || currentStageSlug.isEmpty()) {
            liveLogView.setText(R.string.result_placeholder);
            return;
        }

        try {
            String content = MaintainerLogStore.readTail(currentStageSlug, LOG_CHAR_LIMIT);
            liveLogView.setText(content.isEmpty() ? getString(R.string.result_placeholder) : content);
        } catch (IOException e) {
            liveLogView.setText(getString(R.string.full_log_error, e.getMessage()));
        }
        updateLogButtonState();
    }

    private void inspectCompletionThrottled(TerminalSession terminalSession, boolean force) {
        long now = SystemClock.uptimeMillis();
        if (!force && now - lastCompletionScanUptimeMs < TERMINAL_COMPLETION_SCAN_MIN_INTERVAL_MS) {
            return;
        }
        lastCompletionScanUptimeMs = now;
        inspectTranscriptForCompletion(terminalSession);
        inspectCurrentLogForCompletion();
    }

    private void requestOneClickStatusRefreshIfDue() {
        if (!commandInFlight || !oneClickStagesInFlight || !"manifest_full".equals(currentStageSlug)) {
            return;
        }

        long now = SystemClock.uptimeMillis();
        if (now - lastOneClickStatusRefreshUptimeMs < ONE_CLICK_STATUS_REFRESH_INTERVAL_MS) {
            return;
        }

        lastOneClickStatusRefreshUptimeMs = now;
        requestStageStatusRefresh();
    }

    private void inspectTranscriptForCompletion(TerminalSession terminalSession) {
        String transcript = ShellUtils.getTerminalSessionTranscriptText(terminalSession, false, false);
        Matcher matcher = DONE_PATTERN.matcher(transcript);
        handleCompletionMatcher(matcher);
    }

    private void inspectCurrentLogForCompletion() {
        if (currentStageSlug == null || currentStageSlug.isEmpty()) {
            return;
        }

        try {
            String content = MaintainerLogStore.readTail(currentStageSlug, 4096);
            Matcher matcher = DONE_PATTERN.matcher(content);
            handleCompletionMatcher(matcher);
        } catch (IOException e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to inspect maintenance log for completion", e);
        }
    }

    private void handleCompletionMatcher(Matcher matcher) {
        String foundMarker = null;
        String foundSlug = null;
        int foundExitCode = 0;
        while (matcher.find()) {
            foundMarker = matcher.group(0);
            foundSlug = matcher.group(1);
            foundExitCode = Integer.parseInt(matcher.group(2));
        }

        if (foundMarker == null || foundMarker.equals(lastHandledMarker)) {
            return;
        }

        lastHandledMarker = foundMarker;
        if (currentStageSlug != null && currentStageSlug.equals(foundSlug)) {
            commandInFlight = false;
            currentStageView.setText((foundExitCode == 0 ? "已完成：" : "失败：") + currentStageLabel);
            terminalStatusView.setText(R.string.embedded_terminal_status_ready);
            if (oneClickStagesInFlight) {
                if (foundExitCode != 0 && currentStageUsedRemote) {
                    StageAction failedStageAction = StageAction.fromSlug(foundSlug);
                    currentStageUsedRemote = false;
                    oneClickUseBundledStages = true;
                    if (oneClickStageSummaryView != null) {
                        oneClickStageSummaryView.setText(getString(
                            R.string.one_click_stage_summary_running,
                            "远程阶段失败，切换到 APK 内置阶段"
                        ));
                    }
                    refreshLiveLog();
                    updateExecutionModeViews();
                    if (failedStageAction != null) {
                        mainHandler.post(() -> runStage(failedStageAction, true));
                    }
                    return;
                }
                currentStageUsedRemote = false;
                if (foundExitCode == 0 && "manifest_full".equals(foundSlug)) {
                    oneClickStagesInFlight = false;
                    if (oneClickStageSummaryView != null) {
                        oneClickStageSummaryView.setText(R.string.one_click_stage_summary_complete);
                    }
                    Toast.makeText(this, R.string.one_click_stage_toast_complete, Toast.LENGTH_SHORT).show();
                    return;
                } else if (foundExitCode != 0) {
                    oneClickStagesInFlight = false;
                    if (oneClickStageSummaryView != null) {
                        oneClickStageSummaryView.setText(getString(R.string.one_click_stage_summary_waiting, currentStageLabel));
                    }
                    Toast.makeText(this, getString(R.string.one_click_stage_toast_blocked, currentStageLabel), Toast.LENGTH_LONG).show();
                }
            }
            currentStageUsedRemote = false;
            boolean shouldOpenMaintenanceWeb = openMaintenanceWebAfterStage && foundExitCode == 0;
            openMaintenanceWebAfterStage = false;
            refreshLiveLog();
            requestStageStatusRefresh();
            refreshStatus();
            updateExecutionModeViews();
            if (shouldOpenMaintenanceWeb) {
                openUrl(getLocalMaintenanceWebUrl(), "本地网页维护器");
            }
        }
    }

    private String loadAsset(String assetName) throws IOException {
        return loadAssetText("maintainer/" + assetName);
    }

    private String loadAssetText(String assetPath) throws IOException {
        StringBuilder builder = new StringBuilder();
        try (InputStream inputStream = getAssets().open(assetPath);
             BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                builder.append(line).append('\n');
            }
        }
        return builder.toString();
    }

    private String buildBundledAssetWriteSnippet(String assetPrefix, String targetVar) throws IOException {
        List<String> assetPaths = new ArrayList<>();
        collectAssetPaths(assetPrefix, assetPrefix, assetPaths);
        assetPaths.sort(String::compareTo);

        StringBuilder builder = new StringBuilder();
        int index = 0;
        for (String assetPath : assetPaths) {
            String relativePath = assetPath.substring(assetPrefix.length() + 1);
            int slashIndex = relativePath.lastIndexOf('/');
            if (slashIndex >= 0) {
                builder.append("mkdir -p \"${").append(targetVar).append("}/")
                    .append(relativePath.substring(0, slashIndex)).append("\"\n");
            }
            String delimiter = "__OPENHOUSE_ASSET_" + index++ + "__";
            builder.append("cat > \"${").append(targetVar).append("}/").append(relativePath)
                .append("\" <<'").append(delimiter).append("'\n");
            builder.append(loadAssetText(assetPath));
            if (!builder.toString().endsWith("\n")) {
                builder.append('\n');
            }
            builder.append(delimiter).append('\n');
        }
        return builder.toString();
    }

    private void collectAssetPaths(String rootPrefix, String currentPrefix, List<String> collector) throws IOException {
        String[] children = getAssets().list(currentPrefix);
        if (children == null || children.length == 0) {
            if (!currentPrefix.equals(rootPrefix)) {
                collector.add(currentPrefix);
            }
            return;
        }

        Arrays.sort(children);
        for (String child : children) {
            String next = currentPrefix + "/" + child;
            String[] nested = getAssets().list(next);
            if (nested == null || nested.length == 0) {
                collector.add(next);
            } else {
                collectAssetPaths(rootPrefix, next, collector);
            }
        }
    }

    private static String shellQuote(String value) {
        return "'" + value.replace("'", "'\"'\"'") + "'";
    }

    private boolean isBootstrapSourceAvailable() {
        if (activeManifest == null || activeManifest.bootstrapUrl == null || !isHttpUrl(activeManifest.bootstrapUrl)) {
            return false;
        }
        return measureSource("bootstrap", activeManifest.bootstrapUrl).success;
    }

    private OpenCodeSourceProbeResult probeOpenCodeSource() {
        long probeAt = System.currentTimeMillis();
        SourceMeasurement official = measureSource(
            OpenCodeDownloadSourceSettings.SOURCE_OFFICIAL,
            OpenCodeDownloadSourceSettings.OFFICIAL_INSTALL_URL
        );
        SourceMeasurement mirror = measureSource(
            OpenCodeDownloadSourceSettings.SOURCE_MIRROR,
            OpenCodeDownloadSourceSettings.MIRROR_INSTALL_URL
        );

        String selectedSourceId = OpenCodeDownloadSourceSettings.SOURCE_OFFICIAL;
        boolean success = false;
        if (official.success && mirror.success) {
            selectedSourceId = official.score <= mirror.score ? official.sourceId : mirror.sourceId;
            success = true;
        } else if (official.success) {
            selectedSourceId = official.sourceId;
            success = true;
        } else if (mirror.success) {
            selectedSourceId = mirror.sourceId;
            success = true;
        }

        String summary;
        if (success) {
            summary = getDownloadSourceLabel(selectedSourceId) + " 首包更快，已优先使用。";
        } else {
            summary = "探测失败，将在安装时先尝试官方源。";
        }

        OpenCodeDownloadSourceSettings.setLastProbeResult(this, selectedSourceId, summary, success ? probeAt : 0L);

        StringBuilder log = new StringBuilder();
        log.append("==> ").append(getString(R.string.download_source_probe_stage_label)).append('\n');
        log.append("策略：").append(getDownloadSourceModeLabel(OpenCodeDownloadSourceSettings.getMode(this))).append('\n');
        appendSourceMeasurementLog(log, official);
        appendSourceMeasurementLog(log, mirror);
        log.append("结论：").append(summary).append('\n');
        log.append("__TERMUX_MAINT_DONE__:").append(PROBE_OPENCODE_SOURCE_SLUG).append(':').append(success ? 0 : 1).append('\n');

        try {
            MaintainerLogStore.writeLog(this, PROBE_OPENCODE_SOURCE_SLUG, log.toString());
        } catch (IOException e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to write OpenCode source probe log", e);
        }

        return new OpenCodeSourceProbeResult(success);
    }

    private void appendSourceMeasurementLog(StringBuilder log, SourceMeasurement measurement) {
        log.append(getDownloadSourceLabel(measurement.sourceId))
            .append("：")
            .append(measurement.url)
            .append('\n');
        if (measurement.success) {
            log.append("HTTP ").append(measurement.httpCode)
                .append("，首包 ")
                .append(formatMillis(measurement.startTransferMs))
                .append("，总耗时 ")
                .append(formatMillis(measurement.totalMs))
                .append("，评分 ")
                .append(String.format(Locale.US, "%.2f", measurement.score))
                .append('\n');
        } else {
            log.append("失败：").append(measurement.errorMessage).append('\n');
        }
    }

    private String formatMillis(long millis) {
        return String.format(Locale.US, "%.2fs", millis / 1000.0d);
    }

    private SourceMeasurement measureSource(String sourceId, String url) {
        HttpURLConnection connection = null;
        long start = System.nanoTime();
        try {
            connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setInstanceFollowRedirects(true);
            connection.setConnectTimeout(SOURCE_PROBE_CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(SOURCE_PROBE_READ_TIMEOUT_MS);
            connection.setRequestProperty("User-Agent", "OpenHouseAI-Maintenance/1.0");
            connection.setRequestMethod("GET");

            int httpCode = connection.getResponseCode();
            long headerNanos = System.nanoTime();
            InputStream inputStream = httpCode >= 400 ? connection.getErrorStream() : connection.getInputStream();
            if (inputStream != null) {
                try (InputStream ignored = inputStream) {
                    ignored.read();
                }
            }
            long end = System.nanoTime();
            long startTransferMs = TimeUnit.NANOSECONDS.toMillis(headerNanos - start);
            long totalMs = Math.max(startTransferMs, TimeUnit.NANOSECONDS.toMillis(end - start));
            boolean acceptable = httpCode >= 200 && httpCode < 400;
            double score = startTransferMs * 0.7d + totalMs * 0.3d;
            if (!acceptable) {
                return SourceMeasurement.failure(sourceId, url, httpCode, "HTTP " + httpCode);
            }
            return SourceMeasurement.success(sourceId, url, httpCode, startTransferMs, totalMs, score);
        } catch (Exception e) {
            return SourceMeasurement.failure(sourceId, url, 0, e.getClass().getSimpleName() + ": " + e.getMessage());
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private static final class OpenCodeSourceProbeResult {
        final boolean success;

        OpenCodeSourceProbeResult(boolean success) {
            this.success = success;
        }
    }

    private static final class SourceMeasurement {
        final String sourceId;
        final String url;
        final boolean success;
        final int httpCode;
        final long startTransferMs;
        final long totalMs;
        final double score;
        final String errorMessage;

        private SourceMeasurement(String sourceId, String url, boolean success, int httpCode, long startTransferMs, long totalMs, double score, String errorMessage) {
            this.sourceId = sourceId;
            this.url = url;
            this.success = success;
            this.httpCode = httpCode;
            this.startTransferMs = startTransferMs;
            this.totalMs = totalMs;
            this.score = score;
            this.errorMessage = errorMessage;
        }

        static SourceMeasurement success(String sourceId, String url, int httpCode, long startTransferMs, long totalMs, double score) {
            return new SourceMeasurement(sourceId, url, true, httpCode, startTransferMs, totalMs, score, "");
        }

        static SourceMeasurement failure(String sourceId, String url, int httpCode, String errorMessage) {
            return new SourceMeasurement(sourceId, url, false, httpCode, 0L, 0L, Double.MAX_VALUE, errorMessage == null ? "unknown error" : errorMessage);
        }
    }

    private static final class OpenCodeInstallSpec {
        final String primarySourceId;
        final String primaryLabel;
        final String primaryUrl;
        final String secondarySourceId;
        final String secondaryLabel;
        final String secondaryUrl;
        final boolean allowFallback;

        OpenCodeInstallSpec(String primarySourceId, String primaryLabel, String primaryUrl,
                            String secondarySourceId, String secondaryLabel, String secondaryUrl,
                            boolean allowFallback) {
            this.primarySourceId = primarySourceId;
            this.primaryLabel = primaryLabel;
            this.primaryUrl = primaryUrl;
            this.secondarySourceId = secondarySourceId;
            this.secondaryLabel = secondaryLabel;
            this.secondaryUrl = secondaryUrl;
            this.allowFallback = allowFallback;
        }

        static OpenCodeInstallSpec defaultSpec(MaintenanceCenterActivity activity) {
            return new OpenCodeInstallSpec(
                OpenCodeDownloadSourceSettings.SOURCE_OFFICIAL,
                activity.getString(R.string.download_source_label_official),
                OpenCodeDownloadSourceSettings.OFFICIAL_INSTALL_URL,
                OpenCodeDownloadSourceSettings.SOURCE_MIRROR,
                activity.getString(R.string.download_source_label_mirror),
                OpenCodeDownloadSourceSettings.MIRROR_INSTALL_URL,
                false
            );
        }
    }

    private final class MaintenanceTerminalViewClient extends TermuxTerminalViewClientBase {
        @Override
        public void onSingleTapUp(MotionEvent e) {
            terminalView.requestFocus();
            KeyboardUtils.showSoftKeyboard(MaintenanceCenterActivity.this, terminalView);
        }

        @Override
        public boolean shouldEnforceCharBasedInput() {
            return false;
        }

        @Override
        public boolean onLongPress(MotionEvent event) {
            return false;
        }

        @Override
        public boolean isTerminalViewSelected() {
            return true;
        }

        @Override
        public boolean onKeyDown(int keyCode, KeyEvent e, TerminalSession session) {
            return false;
        }
    }

    private final class MaintenanceTerminalSessionClient extends TermuxTerminalSessionClientBase implements TermuxSession.TermuxSessionClient {
        @Override
        public void onTextChanged(TerminalSession changedSession) {
            synchronized (terminalUpdateLock) {
                pendingTerminalUpdateSession = changedSession;
                if (terminalTextUpdateScheduled) {
                    return;
                }
                terminalTextUpdateScheduled = true;
            }

            mainHandler.postDelayed(() -> {
                TerminalSession session;
                synchronized (terminalUpdateLock) {
                    session = pendingTerminalUpdateSession;
                    pendingTerminalUpdateSession = null;
                    terminalTextUpdateScheduled = false;
                }

                if (session == null || isFinishing() || isDestroyed()) {
                    return;
                }

                try {
                    terminalView.onScreenUpdated();
                    refreshLiveLogThrottled(false);
                    inspectCompletionThrottled(session, false);
                } catch (Throwable throwable) {
                    terminalFailureMessage = throwable.getClass().getSimpleName() + ": " + throwable.getMessage();
                    Logger.logStackTraceWithMessage(LOG_TAG, "Failed to process maintenance terminal text update", throwable);
                    refreshStatus();
                }
            }, TERMINAL_UI_UPDATE_DELAY_MS);
        }

        @Override
        public void onSessionFinished(TerminalSession finishedSession) {
            runOnUiThread(() -> {
                try {
                    terminalView.onScreenUpdated();
                    terminalStatusView.setText(R.string.embedded_terminal_status_closed);
                    commandInFlight = false;
                    refreshLiveLogThrottled(true);
                    inspectCompletionThrottled(finishedSession, true);
                    requestStageStatusRefresh();
                    refreshStatus();
                } catch (Throwable throwable) {
                    terminalFailureMessage = throwable.getClass().getSimpleName() + ": " + throwable.getMessage();
                    Logger.logStackTraceWithMessage(LOG_TAG, "Failed to handle maintenance terminal session finish", throwable);
                    refreshStatus();
                }
            });
        }

        @Override
        public void onTerminalCursorStateChange(boolean state) {
            try {
                terminalView.setTerminalCursorBlinkerState(state, false);
            } catch (Throwable throwable) {
                terminalFailureMessage = throwable.getClass().getSimpleName() + ": " + throwable.getMessage();
                Logger.logStackTraceWithMessage(LOG_TAG, "Failed to update maintenance terminal cursor state", throwable);
            }
        }

        @Override
        public void onColorsChanged(TerminalSession changedSession) {
            try {
                terminalView.onScreenUpdated();
            } catch (Throwable throwable) {
                terminalFailureMessage = throwable.getClass().getSimpleName() + ": " + throwable.getMessage();
                Logger.logStackTraceWithMessage(LOG_TAG, "Failed to update maintenance terminal colors", throwable);
            }
        }

        @Override
        public Integer getTerminalCursorStyle() {
            return null;
        }

        @Override
        public void onTermuxSessionExited(TermuxSession termuxSession) {
            Logger.logDebug(LOG_TAG, "Maintenance terminal session exited");
        }
    }

    private enum StageUiState {
        CHECKING,
        READY,
        RUNNING,
        COMPLETE,
        FAILED,
        BLOCKED
    }

    private static final class ShellCheckResult {
        final int exitCode;
        final String output;

        ShellCheckResult(int exitCode, String output) {
            this.exitCode = exitCode;
            this.output = output == null ? "" : output;
        }

        boolean isSuccess() {
            return exitCode == 0;
        }
    }

    private static final class StageCheckSnapshot {
        final EnumMap<StageAction, StagePresentation> presentations = new EnumMap<>(StageAction.class);
        Boolean opencodeReachable;
    }

    private enum PluginSourceMode {
        BUNDLED("bundled", R.string.plugin_source_label_bundled),
        USER("user", R.string.plugin_source_label_user),
        ONLINE("online", R.string.plugin_source_label_online);

        final String prefValue;
        final int labelRes;

        PluginSourceMode(String prefValue, int labelRes) {
            this.prefValue = prefValue;
            this.labelRes = labelRes;
        }

        static PluginSourceMode fromPrefValue(String value) {
            for (PluginSourceMode mode : values()) {
                if (mode.prefValue.equals(value)) {
                    return mode;
                }
            }
            return ONLINE;
        }
    }

    private static final class ServiceManagerResponse {
        final int code;
        final String body;

        ServiceManagerResponse(int code, String body) {
            this.code = code;
            this.body = body == null ? "" : body;
        }

        boolean isSuccess() {
            return code >= 200 && code < 300;
        }
    }

    private static final class MaintenanceManifest {
        final String sourceUrl;
        final String sourceName;
        final String version;
        final String bootstrapUrl;
        final BootstrapAction defaultOneClickAction;
        final Map<String, ManifestStage> stages;
        final List<StageFlowGroup> stageFlowGroups;
        final List<DynamicSection> dynamicSections;

        MaintenanceManifest(
            String sourceUrl,
            String sourceName,
            String version,
            String bootstrapUrl,
            BootstrapAction defaultOneClickAction,
            Map<String, ManifestStage> stages,
            List<StageFlowGroup> stageFlowGroups,
            List<DynamicSection> dynamicSections
        ) {
            this.sourceUrl = sourceUrl;
            this.sourceName = sourceName;
            this.version = version;
            this.bootstrapUrl = bootstrapUrl;
            this.defaultOneClickAction = defaultOneClickAction;
            this.stages = stages;
            this.stageFlowGroups = stageFlowGroups;
            this.dynamicSections = dynamicSections;
        }

        static MaintenanceManifest fromJson(String sourceUrl, String json) throws JSONException, IOException {
            JSONObject root = new JSONObject(json);
            int schema = root.optInt("schema", 0);
            if (schema != 1 && schema != 2) {
                throw new IOException("unsupported schema: " + schema);
            }

            String bootstrapUrl = root.optString("bootstrapUrl", DEFAULT_BOOTSTRAP_URL).trim();
            if (!isHttpUrl(bootstrapUrl)) {
                throw new IOException("invalid bootstrapUrl");
            }

            String defaultAction = root.optString("defaultOneClickAction", "full").trim();
            BootstrapAction defaultOneClickAction = BootstrapAction.fromSingle(defaultAction);

            Map<String, ManifestStage> stages = new HashMap<>();
            JSONArray stageArray = root.optJSONArray("stages");
            if (stageArray != null) {
                for (int i = 0; i < stageArray.length(); i++) {
                    JSONObject stageJson = stageArray.getJSONObject(i);
                    String id = stageJson.optString("id", "").trim();
                    if (StageAction.fromSlug(id) == null) {
                        continue;
                    }

                    JSONObject actionJson = stageJson.optJSONObject("action");
                    BootstrapAction action = BootstrapAction.fromJson(actionJson);
                    stages.put(id, new ManifestStage(
                        id,
                        stageJson.optString("title", id).trim(),
                        stageJson.optString("description", "").trim(),
                        action
                    ));
                }
            }

            List<StageFlowGroup> stageFlowGroups = new ArrayList<>();
            JSONArray stageFlowArray = root.optJSONArray("stageFlow");
            if (stageFlowArray != null) {
                for (int i = 0; i < stageFlowArray.length(); i++) {
                    StageFlowGroup group = StageFlowGroup.fromJson(stageFlowArray.optJSONObject(i));
                    if (group != null) {
                        stageFlowGroups.add(group);
                    }
                }
            }

            List<DynamicSection> dynamicSections = new ArrayList<>();
            JSONArray sectionArray = root.optJSONArray("sections");
            if (sectionArray != null) {
                for (int i = 0; i < sectionArray.length(); i++) {
                    DynamicSection section = DynamicSection.fromJson(sectionArray.optJSONObject(i));
                    if (section != null) {
                        dynamicSections.add(section);
                    }
                }
            }

            if (stages.isEmpty() && dynamicSections.isEmpty()) {
                throw new IOException("manifest contains no supported stages or sections");
            }

            return new MaintenanceManifest(
                sourceUrl,
                root.optString("sourceName", "自定义维护源").trim(),
                root.optString("version", "unknown").trim(),
                bootstrapUrl,
                defaultOneClickAction,
                stages,
                stageFlowGroups,
                dynamicSections
            );
        }
    }

    private static final class StageFlowGroup {
        final String title;
        final String description;
        final StageAction[] stageActions;

        StageFlowGroup(String title, String description, StageAction[] stageActions) {
            this.title = title;
            this.description = description;
            this.stageActions = stageActions;
        }

        static StageFlowGroup fromJson(JSONObject groupJson) {
            if (groupJson == null) return null;
            JSONArray stagesArray = groupJson.optJSONArray("stages");
            if (stagesArray == null || stagesArray.length() == 0) return null;

            List<StageAction> stageActions = new ArrayList<>();
            for (int i = 0; i < stagesArray.length(); i++) {
                StageAction stageAction = StageAction.fromSlug(stagesArray.optString(i, "").trim());
                if (stageAction != null && !stageActions.contains(stageAction)) {
                    stageActions.add(stageAction);
                }
            }
            if (stageActions.isEmpty()) return null;

            String title = groupJson.optString("title", "").trim();
            if (title.isEmpty()) {
                title = stageActions.get(0).slug;
            }
            String description = groupJson.optString("description", "").trim();
            return new StageFlowGroup(
                title,
                description,
                stageActions.toArray(new StageAction[0])
            );
        }
    }

    private static final class DynamicSection {
        final String id;
        final String type;
        final String title;
        final String description;
        final List<DynamicItem> items;

        DynamicSection(String id, String type, String title, String description, List<DynamicItem> items) {
            this.id = id;
            this.type = type;
            this.title = title;
            this.description = description;
            this.items = items;
        }

        static DynamicSection fromJson(JSONObject sectionJson) throws IOException {
            if (sectionJson == null) return null;
            String type = sectionJson.optString("type", "").trim();
            if (!"actions".equals(type) && !"setting".equals(type)) {
                return null;
            }
            List<DynamicItem> items = new ArrayList<>();
            JSONArray itemArray = sectionJson.optJSONArray("items");
            if (itemArray != null) {
                for (int i = 0; i < itemArray.length(); i++) {
                    DynamicItem item = DynamicItem.fromJson(itemArray.optJSONObject(i));
                    if (item != null) {
                        items.add(item);
                    }
                }
            }
            if (items.isEmpty()) return null;
            String id = sectionJson.optString("id", type).trim();
            String title = sectionJson.optString("title", id).trim();
            String description = sectionJson.optString("description", "").trim();
            return new DynamicSection(id, type, title, description, items);
        }
    }

    private static final class DynamicItem {
        final String id;
        final String type;
        final String label;
        final String description;
        final BootstrapAction action;
        final List<DynamicOption> options;

        DynamicItem(String id, String type, String label, String description, BootstrapAction action, List<DynamicOption> options) {
            this.id = id;
            this.type = type;
            this.label = label;
            this.description = description;
            this.action = action;
            this.options = options;
        }

        static DynamicItem fromJson(JSONObject itemJson) throws IOException {
            if (itemJson == null) return null;
            String id = itemJson.optString("id", "dynamic_item").trim();
            String type = itemJson.optString("type", "button").trim();
            String label = itemJson.optString("label", id).trim();
            String description = itemJson.optString("description", "").trim();
            if ("single_choice".equals(type)) {
                List<DynamicOption> options = new ArrayList<>();
                JSONArray optionArray = itemJson.optJSONArray("options");
                if (optionArray != null) {
                    for (int i = 0; i < optionArray.length(); i++) {
                        DynamicOption option = DynamicOption.fromJson(optionArray.optJSONObject(i));
                        if (option != null) {
                            options.add(option);
                        }
                    }
                }
                return options.isEmpty() ? null : new DynamicItem(id, type, label, description, null, options);
            }
            JSONObject actionJson = itemJson.optJSONObject("action");
            BootstrapAction action = actionJson == null ? null : BootstrapAction.fromJson(actionJson);
            return action == null ? null : new DynamicItem(id, type, label, description, action, new ArrayList<>());
        }
    }

    private static final class DynamicOption {
        final String label;
        final String description;
        final BootstrapAction action;

        DynamicOption(String label, String description, BootstrapAction action) {
            this.label = label;
            this.description = description;
            this.action = action;
        }

        static DynamicOption fromJson(JSONObject optionJson) throws IOException {
            if (optionJson == null) return null;
            JSONObject actionJson = optionJson.optJSONObject("action");
            if (actionJson == null) return null;
            String label = optionJson.optString("label", "选项").trim();
            String description = optionJson.optString("description", "").trim();
            return new DynamicOption(label, description, BootstrapAction.fromJson(actionJson));
        }
    }

    private static final class ManifestStage {
        final String id;
        final String title;
        final String description;
        final BootstrapAction action;

        ManifestStage(String id, String title, String description, BootstrapAction action) {
            this.id = id;
            this.title = title;
            this.description = description;
            this.action = action;
        }
    }

    private static final class BootstrapAction {
        final String[] args;

        BootstrapAction(String[] args) {
            this.args = args;
        }

        static BootstrapAction fromSingle(String arg) throws IOException {
            return new BootstrapAction(new String[] { arg });
        }

        static BootstrapAction fromJson(JSONObject actionJson) throws IOException {
            if (actionJson == null) {
                throw new IOException("missing action");
            }
            String type = actionJson.optString("type", "").trim();
            if (!"bootstrap".equals(type)) {
                throw new IOException("unsupported action type: " + type);
            }
            JSONArray argsArray = actionJson.optJSONArray("args");
            if (argsArray == null) {
                throw new IOException("missing bootstrap args");
            }
            String[] args = new String[argsArray.length()];
            for (int i = 0; i < argsArray.length(); i++) {
                args[i] = argsArray.optString(i, "");
            }
            return new BootstrapAction(args);
        }

        String toDisplayString() {
            StringBuilder builder = new StringBuilder("bootstrap");
            for (String arg : args) {
                builder.append(' ').append(arg);
            }
            return builder.toString();
        }
    }

    private static final class StagePresentation {
        final StageUiState state;
        final String badge;
        final String detail;
        final int backgroundColorRes;
        final int textColorRes;

        StagePresentation(StageUiState state, String badge, String detail, int backgroundColorRes, int textColorRes) {
            this.state = state;
            this.badge = badge;
            this.detail = detail;
            this.backgroundColorRes = backgroundColorRes;
            this.textColorRes = textColorRes;
        }

        static StagePresentation checking(MaintenanceCenterActivity activity) {
            return new StagePresentation(
                StageUiState.CHECKING,
                activity.getString(R.string.stage_badge_checking),
                activity.getString(R.string.stage_detail_checking),
                R.color.stageChecking,
                R.color.stageCheckingText
            );
        }

        static StagePresentation ready(MaintenanceCenterActivity activity, String detail) {
            return new StagePresentation(
                StageUiState.READY,
                activity.getString(R.string.stage_badge_ready),
                detail,
                R.color.stageReady,
                R.color.stageReadyText
            );
        }

        static StagePresentation running(MaintenanceCenterActivity activity) {
            return new StagePresentation(
                StageUiState.RUNNING,
                activity.getString(R.string.stage_badge_running),
                activity.getString(R.string.stage_detail_running),
                R.color.stageRunning,
                R.color.stageRunningText
            );
        }

        static StagePresentation complete(MaintenanceCenterActivity activity, String detail) {
            return new StagePresentation(
                StageUiState.COMPLETE,
                activity.getString(R.string.stage_badge_complete),
                detail,
                R.color.stageComplete,
                R.color.stageOnDark
            );
        }

        static StagePresentation failed(MaintenanceCenterActivity activity, String detail) {
            return new StagePresentation(
                StageUiState.FAILED,
                activity.getString(R.string.stage_badge_failed),
                detail,
                R.color.stageFailed,
                R.color.stageOnDark
            );
        }

        static StagePresentation blocked(MaintenanceCenterActivity activity, String detail) {
            return new StagePresentation(
                StageUiState.BLOCKED,
                activity.getString(R.string.stage_badge_blocked),
                detail,
                R.color.stageBlocked,
                R.color.stageBlockedText
            );
        }

        String buttonText(MaintenanceCenterActivity activity, StageAction stageAction) {
            return badge + " · " + stageAction.label(activity) + "\n" + activity.getStageDescription(stageAction, detail);
        }

        String headline(MaintenanceCenterActivity activity, StageAction stageAction) {
            return badge + "：" + stageAction.label(activity) + "；" + activity.getStageDescription(stageAction, detail);
        }
    }

    private enum StageAction {
        PREPARE("prepare", "prepare-product.sh"),
        TERMUX_PACKAGES("termux_packages", "update-termux-packages.sh"),
        INSTALL_UBUNTU("install_ubuntu", "install-ubuntu.sh"),
        SYNC_OFFICIAL_DOCS("sync_official_docs", "sync-official-docs.sh"),
        UBUNTU_PACKAGES("ubuntu_packages", "update-ubuntu-packages.sh"),
        CONFIGURE_ENTRY_UBUNTU("entry_ubuntu", "configure-entry-ubuntu.sh"),
        INSTALL_NODE("install_node", "install-node.sh"),
        INSTALL_OPENCODE("install_opencode", "install-opencode.sh"),
        INSTALL_CODEX("install_codex", "install-codex.sh"),
        INSTALL_CLAUDE_CODE("install_claude_code", "install-claude-code.sh"),
        INSTALL_CLAUDE_CODE_UI("install_claude_code_ui", "install-claude-code-ui.sh"),
        INSTALL_REASONIX("install_reasonix", "install-reasonix.sh"),
        RUNTIME_COMPONENTS("runtime_components", "install-runtime-components.sh"),
        INSTALL_HERMES("install_hermes", "install-hermes.sh"),
        SYNC_OPENHOUSE_REGISTRY("sync_openhouse_registry", "sync-openhouse-registry.sh"),
        REQUEST_DEEPSEEK_KEY("request_deepseek_key", null),
        CONFIGURE_DEEPSEEK("configure_deepseek", "configure-deepseek-key.sh"),
        RESTART_ENTRY_TERMINAL("restart_entry_terminal", null),
        START("start", "start-opencode.sh"),
        RESTART("restart", "restart-opencode.sh");

        final String slug;
        final String assetName;
        final String requiredComponentTargets;

        StageAction(String slug, String assetName) {
            this(slug, assetName, null);
        }

        StageAction(String slug, String assetName, String requiredComponentTargets) {
            this.slug = slug;
            this.assetName = assetName;
            this.requiredComponentTargets = requiredComponentTargets;
        }

        String label(MaintenanceCenterActivity activity) {
            return activity.getStageTitle(this);
        }

        boolean shouldRefreshBeforeRun() {
            return this == SYNC_OFFICIAL_DOCS
                || this == INSTALL_OPENCODE
                || this == INSTALL_NODE
                || this == CONFIGURE_ENTRY_UBUNTU
                || this == INSTALL_CODEX
                || this == INSTALL_CLAUDE_CODE
                || this == INSTALL_CLAUDE_CODE_UI
                || this == INSTALL_REASONIX
                || this == RUNTIME_COMPONENTS
                || this == INSTALL_HERMES
                || this == SYNC_OPENHOUSE_REGISTRY
                || this == START
                || this == RESTART;
        }

        boolean isUiOnly() {
            return this == REQUEST_DEEPSEEK_KEY
                || this == RESTART_ENTRY_TERMINAL;
        }

        static StageAction fromSlug(String slug) {
            for (StageAction stageAction : values()) {
                if (stageAction.slug.equals(slug)) {
                    return stageAction;
                }
            }
            return null;
        }
    }
}
