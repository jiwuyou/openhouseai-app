package com.termux.app.activities;

import android.Manifest;
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
import android.text.method.PasswordTransformationMethod;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
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
import com.termux.app.OpenCodeCdpBridge;
import com.termux.app.OpenCodeDownloadSourceSettings;
import com.termux.app.OpenCodeSettings;
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

    private static final String LOG_TAG = "MaintenanceCenter";
    private static final int LOG_CHAR_LIMIT = 24000;
    private static final long TERMINAL_UI_UPDATE_DELAY_MS = 250;
    private static final long TERMINAL_LIVE_LOG_REFRESH_MIN_INTERVAL_MS = 1000;
    private static final long TERMINAL_COMPLETION_SCAN_MIN_INTERVAL_MS = 500;
    private static final long TERMINAL_COMPLETION_POLL_INTERVAL_MS = 1000;
    private static final Pattern DONE_PATTERN = Pattern.compile("__TERMUX_MAINT_DONE__:([a-zA-Z0-9_-]+):(\\d+)");
    private static final String OFFICIAL_DOCS_ASSET_DIR = "openhouse/docs-public";
    private static final String BUNDLED_MAINTENANCE_PLUGIN_ASSET = "openhouse/plugins/original/openhouse-manifest.json";
    private static final String PREFS_MAINTENANCE = "maintenance_center";
    private static final String PREF_DISABLE_BATTERY_REQUIREMENT = "disable_battery_requirement";
    private static final String PREF_MAINTENANCE_PLUGIN_MODE = "maintenance_plugin_mode";
    private static final String PREF_MAINTENANCE_SOURCE_URL = "maintenance_source_url";
    private static final String PREF_USER_PLUGIN_PATH = "maintenance_user_plugin_path";
    private static final String PREF_LOCAL_MAINTENANCE_WEB_PORT = "local_maintenance_web_port";
    private static final String DEFAULT_MAINTENANCE_MANIFEST_URL = "https://raw.githubusercontent.com/jiwuyou/openhouseai-bootstrap/main/openhouseai-manifest.json";
    private static final String DEFAULT_BOOTSTRAP_URL = "https://raw.githubusercontent.com/jiwuyou/openhouseai-bootstrap/main/bootstrap.sh";
    private static final String DEEPSEEK_API_KEYS_URL = "https://platform.deepseek.com/api_keys";
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
        StageAction.INSTALL_OPENCODE,
        StageAction.INSTALL_CODEX,
        StageAction.INSTALL_CLAUDE_CODE,
        StageAction.START
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
    private LinearLayout dynamicPluginSectionsContainer;
    private NestedScrollView liveLogScrollView;
    private SwitchCompat permissionBatteryButton;
    private SwitchCompat permissionOverlayButton;
    private SwitchCompat permissionStorageButton;
    private Button configureDefaultPortButton;
    private Button configureDownloadSourceButton;
    private Button probeDownloadSourceButton;
    private Button configureMaintenanceSourceButton;
    private Button refreshMaintenanceSourceButton;
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
    private boolean openMaintenanceWebAfterStage;
    private boolean terminalTextUpdateScheduled;
    private boolean terminalCompletionPollScheduled;
    private long lastLiveLogRefreshUptimeMs;
    private long lastCompletionScanUptimeMs;
    private TerminalSession pendingTerminalUpdateSession;
    private MaintenanceManifest activeManifest;
    private String activeManifestError;
    private SharedPreferences maintenancePreferences;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
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
        dynamicPluginSectionsContainer = findViewById(R.id.dynamicPluginSections);
        permissionBatteryButton = findViewById(R.id.buttonPermissionBattery);
        permissionOverlayButton = findViewById(R.id.buttonPermissionOverlay);
        permissionStorageButton = findViewById(R.id.buttonPermissionStorage);
        disableBatteryRequirementSwitch = findViewById(R.id.switchDisableBatteryRequirement);
        configureDefaultPortButton = findViewById(R.id.buttonConfigureDefaultPort);
        configureDownloadSourceButton = findViewById(R.id.buttonConfigureDownloadSource);
        probeDownloadSourceButton = findViewById(R.id.buttonProbeDownloadSource);
        configureMaintenanceSourceButton = findViewById(R.id.buttonConfigureMaintenanceSource);
        refreshMaintenanceSourceButton = findViewById(R.id.buttonRefreshMaintenanceSource);
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
        maintenancePreferences = getSharedPreferences(PREFS_MAINTENANCE, MODE_PRIVATE);

        helpBodyView.setText(getString(R.string.help_body));
        currentStageView.setText(R.string.current_stage_placeholder);
        liveLogView.setText(R.string.result_placeholder);

        bindPermissionButtons();
        bindExecutionModeButtons();
        bindStageButtons();
        initializeStagePresentations();
        configureDefaultPortButton.setOnClickListener(v -> showDefaultPortDialog());
        configureDownloadSourceButton.setOnClickListener(v -> showDownloadSourceModeDialog());
        probeDownloadSourceButton.setOnClickListener(v -> runOpenCodeSourceProbe(false));
        configureMaintenanceSourceButton.setOnClickListener(v -> showMaintenanceSourceDialog());
        refreshMaintenanceSourceButton.setOnClickListener(v -> refreshMaintenanceManifest(true));
        viewFullLogButton.setOnClickListener(v -> openFullLog());
        updateLogButtonState();
        updateMaintenanceSourceCard();
        refreshMaintenanceManifest(false);
        refreshStatus();
        requestStageStatusRefresh();
    }

    @Override
    protected void onResume() {
        super.onResume();
        scheduleMaintenanceSessionInit();
        refreshStatus();
        requestStageStatusRefresh();
        scheduleTerminalCompletionPoll();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        maintenanceSessionInitPosted = false;
        terminalCompletionPollScheduled = false;
        mainHandler.removeCallbacks(terminalCompletionPollRunnable);
        backgroundExecutor.shutdownNow();
        try {
            if (maintenanceSession != null && maintenanceSession.getTerminalSession() != null) {
                maintenanceSession.getTerminalSession().finishIfRunning();
                maintenanceSession = null;
            }
        } catch (Throwable throwable) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to finish maintenance terminal session on destroy", throwable);
        }
    }

    private void bindStageButtons() {
        bindStageButton(StageAction.PREPARE, R.id.buttonPrepare);
        bindStageButton(StageAction.TERMUX_PACKAGES, R.id.buttonTermuxPackages);
        bindStageButton(StageAction.INSTALL_UBUNTU, R.id.buttonInstallUbuntu);
        bindStageButton(StageAction.SYNC_OFFICIAL_DOCS, R.id.buttonSyncOfficialDocs);
        bindStageButton(StageAction.UBUNTU_PACKAGES, R.id.buttonUbuntuPackages);
        bindStageButton(StageAction.CONFIGURE_ENTRY_UBUNTU, R.id.buttonConfigureEntryUbuntu);
        bindStageButton(StageAction.INSTALL_OPENCODE, R.id.buttonInstallOpenCode);
        bindStageButton(StageAction.INSTALL_CODEX, R.id.buttonInstallCodex);
        bindStageButton(StageAction.INSTALL_CLAUDE_CODE, R.id.buttonInstallClaudeCode);
        bindStageButton(StageAction.START, R.id.buttonStart);
        bindStageButton(StageAction.RESTART, R.id.buttonRestart);
        deepSeekKeyGuideButton.setOnClickListener(v -> showDeepSeekKeyGuideDialog());
        stageButtons.put(StageAction.CONFIGURE_DEEPSEEK, deepSeekKeyConfigButton);
        deepSeekKeyConfigButton.setOnClickListener(v -> showDeepSeekKeyConfigDialog());
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
        disableBatteryRequirementSwitch.setChecked(!isBatteryRequirementEnabled());
        disableBatteryRequirementSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            maintenancePreferences.edit().putBoolean(PREF_DISABLE_BATTERY_REQUIREMENT, isChecked).apply();
            refreshStatus();
            applyStagePresentations();
        });
    }

    private void showMaintenanceSourceDialog() {
        if (commandInFlight) {
            Toast.makeText(this, R.string.command_busy, Toast.LENGTH_SHORT).show();
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
            refreshMaintenanceSourceButton.setEnabled(!commandInFlight);
            refreshMaintenanceSourceButton.setAlpha(refreshMaintenanceSourceButton.isEnabled() ? 1.0f : 0.78f);
        }
    }

    private void renderDynamicPluginSections() {
        if (dynamicPluginSectionsContainer == null) return;
        dynamicPluginSectionsContainer.removeAllViews();
        if (activeManifest == null || activeManifest.dynamicSections.isEmpty()) {
            dynamicPluginSectionsContainer.setVisibility(View.GONE);
            return;
        }

        dynamicPluginSectionsContainer.setVisibility(View.VISIBLE);
        for (DynamicSection section : activeManifest.dynamicSections) {
            View sectionView = createDynamicSectionView(section);
            if (sectionView != null) {
                dynamicPluginSectionsContainer.addView(sectionView);
            }
        }
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
        button.setEnabled(!commandInFlight);
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
        setOneClickStageMode(true);
    }

    private void setOneClickStageMode(boolean enabled) {
        oneClickStageMode = enabled;
        updateExecutionModeViews();
    }

    private void startOneClickStages() {
        if (commandInFlight) {
            Toast.makeText(this, R.string.command_busy, Toast.LENGTH_SHORT).show();
            return;
        }

        if (isBatteryRequirementBlocking()) {
            currentStageView.setText(getString(R.string.permission_requirement_state_required));
            Toast.makeText(this, R.string.permission_battery_required_toast, Toast.LENGTH_LONG).show();
            return;
        }

        if (activeManifest != null && activeManifest.defaultOneClickAction != null) {
            runRemoteBootstrapAction(
                "manifest_full",
                getString(R.string.button_stage_one_click_mode),
                activeManifest.defaultOneClickAction
            );
            return;
        }

        oneClickStagesInFlight = true;
        setOneClickStageMode(true);
        oneClickStageSummaryView.setText(getString(R.string.one_click_stage_summary_running, "刷新阶段状态"));
        requestStageStatusRefresh();
    }

    private void stopOneClickStages(String message) {
        oneClickStagesInFlight = false;
        if (oneClickStageSummaryView != null) {
            oneClickStageSummaryView.setText(getString(R.string.one_click_stage_summary_waiting, message));
        }
        updateExecutionModeViews();
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    private void continueOneClickStages() {
        if (!oneClickStagesInFlight || commandInFlight || stageStatusCheckInFlight) return;

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
        oneClickStageSummaryView.setText(R.string.one_click_stage_summary_complete);
        updateExecutionModeViews();
        Toast.makeText(this, R.string.one_click_stage_toast_complete, Toast.LENGTH_SHORT).show();
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
            startOneClickStagesButton.setText(oneClickStagesInFlight
                ? R.string.button_stop_one_click_stages
                : R.string.button_start_one_click_stages);
            startOneClickStagesButton.setEnabled(oneClickStagesInFlight || (!commandInFlight && !isBatteryRequirementBlocking()));
            startOneClickStagesButton.setAlpha(startOneClickStagesButton.isEnabled() ? 1.0f : 0.78f);
            startOneClickStagesButton.setBackgroundTintList(ColorStateList.valueOf(ContextCompat.getColor(
                this,
                oneClickStagesInFlight ? R.color.stageRunning : R.color.stageComplete
            )));
            startOneClickStagesButton.setTextColor(ContextCompat.getColor(
                this,
                oneClickStagesInFlight ? R.color.stageRunningText : R.color.stageOnDark
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

                if (stageAction == StageAction.INSTALL_CLAUDE_CODE) {
                    TextView deepSeekRow = createOneClickStageItemView(false);
                    updateDeepSeekKeyConfigItem(deepSeekRow, displayNumber++);
                    oneClickStageItemsContainer.addView(deepSeekRow);
                }
            }
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

    private void updateDeepSeekKeyConfigItem(TextView view, int number) {
        if (view == null) return;
        StagePresentation presentation = stagePresentations.get(StageAction.CONFIGURE_DEEPSEEK);
        boolean complete = presentation != null && presentation.state == StageUiState.COMPLETE;
        view.setText(getString(
            R.string.one_click_auto_item_text,
            number,
            getString(R.string.button_configure_deepseek_key),
            complete ? getString(R.string.one_click_auto_status_done) : getString(R.string.one_click_auto_status_optional),
            complete ? getString(R.string.stage_detail_configure_deepseek_complete) : getString(R.string.deepseek_key_config_one_click_detail)
        ));
        view.setBackgroundTintList(ColorStateList.valueOf(ContextCompat.getColor(
            this,
            complete ? R.color.stageComplete : R.color.stageReady
        )));
        view.setTextColor(ContextCompat.getColor(
            this,
            complete ? R.color.stageOnDark : R.color.stageReadyText
        ));
        view.setClickable(true);
        view.setOnClickListener(v -> showDeepSeekKeyConfigDialog());
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
        return sequence;
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
            getString(R.string.one_click_auto_agents_title),
            getString(R.string.one_click_auto_agents_detail),
            new StageAction[] {
                StageAction.INSTALL_OPENCODE,
                StageAction.INSTALL_CODEX,
                StageAction.INSTALL_CLAUDE_CODE
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
            case INSTALL_OPENCODE:
                return getString(R.string.button_install_opencode);
            case INSTALL_CODEX:
                return getString(R.string.button_install_codex);
            case INSTALL_CLAUDE_CODE:
                return getString(R.string.button_install_claude_code);
            case CONFIGURE_DEEPSEEK:
                return getString(R.string.button_configure_deepseek_key);
            case RESTART:
                return getString(R.string.button_restart);
            case START:
            default:
                return getString(R.string.button_start);
        }
    }

    private void applyModeButtonState(Button button, boolean active) {
        if (button == null) return;
        button.setEnabled(!commandInFlight && !oneClickStagesInFlight);
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
            null,
            null,
            null,
            TermuxConstants.TERMUX_HOME_DIR_PATH,
            ExecutionCommand.Runner.TERMINAL_SESSION.getName(),
            false
        );
        command.shellName = "maintainer";
        command.commandLabel = "Maintainer Terminal";
        command.setShellCommandShellEnvironment = true;

        maintenanceSession = TermuxSession.execute(
            this,
            command,
            terminalSessionClient,
            terminalSessionClient,
            new TermuxShellEnvironment(),
            null,
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
        ManifestStage manifestStage = activeManifest == null ? null : activeManifest.stages.get(stageAction.slug);
        if (manifestStage != null) {
            OpenCodeInstallSpec installSpec = stageAction == StageAction.INSTALL_OPENCODE
                ? resolveOpenCodeInstallSpec()
                : OpenCodeInstallSpec.defaultSpec(this);
            String fallbackScriptBody = buildAssetScriptBody(stageAction, stageAction.assetName, getDefaultOpenCodePort(), installSpec);
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

    private void runRemoteBootstrapAction(String stageSlug, String stageLabel, BootstrapAction action) {
        if (commandInFlight) {
            Toast.makeText(this, R.string.command_busy, Toast.LENGTH_SHORT).show();
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
            String command = buildRemoteBootstrapExecutionCommand(stageLabel, stageSlug, action, fallbackScriptBody, postRemoteScriptBody);
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
        if (commandInFlight) {
            Toast.makeText(this, R.string.command_busy, Toast.LENGTH_SHORT).show();
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
        if (commandInFlight) {
            Toast.makeText(this, R.string.command_busy, Toast.LENGTH_SHORT).show();
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
        scriptBody.append("  run_logged env OPENHOUSEAI_PORT=").append(shellQuote(Integer.toString(getDefaultOpenCodePort())))
            .append(" OPENHOUSEAI_WEB_PORT=").append(shellQuote(Integer.toString(getLocalMaintenanceWebPort())));
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

    private String getBootstrapUrlForLocalMaintenance() {
        if (activeManifest != null && activeManifest.bootstrapUrl != null && !activeManifest.bootstrapUrl.trim().isEmpty()) {
            return activeManifest.bootstrapUrl;
        }
        return DEFAULT_BOOTSTRAP_URL;
    }

    private void showLocalMaintenanceWebPortDialog() {
        if (commandInFlight) {
            Toast.makeText(this, R.string.command_busy, Toast.LENGTH_SHORT).show();
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
            .replace("__BOOTSTRAP_URL__", getBootstrapUrlForLocalMaintenance())
            .replace("__REQUIRED_COMPONENT_TARGETS__", stageAction.requiredComponentTargets == null ? "" : stageAction.requiredComponentTargets)
            .replace("__LOCAL_MAINTENANCE_WEB_PORT__", Integer.toString(getLocalMaintenanceWebPort()))
            .replace("__DEEPSEEK_KEY_FILE__", getDeepSeekKeyTempFile().getAbsolutePath())
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
        scriptBody.append("log '远程一键安装完成，继续通过 APK 内置脚本启动 OpenCode。'\n");
        scriptBody.append("run_environment_probe\n");
        scriptBody.append(buildAssetScriptBody(
            StageAction.START,
            StageAction.START.assetName,
            getDefaultOpenCodePort(),
            OpenCodeInstallSpec.defaultSpec(this)
        ));
        if (scriptBody.charAt(scriptBody.length() - 1) != '\n') {
            scriptBody.append('\n');
        }
        return scriptBody.toString();
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
        if (commandInFlight) {
            Toast.makeText(this, R.string.command_busy, Toast.LENGTH_SHORT).show();
            return;
        }

        EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        input.setTransformationMethod(PasswordTransformationMethod.getInstance());
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
        if (commandInFlight) {
            Toast.makeText(this, R.string.command_busy, Toast.LENGTH_SHORT).show();
            return;
        }

        try {
            writeDeepSeekKeyTempFile(apiKey);
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

    private void writeDeepSeekKeyTempFile(String apiKey) throws IOException {
        File keyFile = getDeepSeekKeyTempFile();
        File parent = keyFile.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new IOException("无法创建目录：" + parent.getAbsolutePath());
        }
        try (FileOutputStream outputStream = new FileOutputStream(keyFile, false)) {
            outputStream.write(apiKey.getBytes(StandardCharsets.UTF_8));
        }
        keyFile.setReadable(false, false);
        keyFile.setWritable(false, false);
        keyFile.setReadable(true, true);
        keyFile.setWritable(true, true);
    }

    private File getDeepSeekKeyTempFile() {
        return new File(TermuxConstants.TERMUX_HOME_DIR_PATH, ".maintainer-logs/deepseek-api-key.tmp");
    }

    private void showCustomPortDialog() {
        if (commandInFlight) {
            Toast.makeText(this, R.string.command_busy, Toast.LENGTH_SHORT).show();
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

    private void showDefaultPortDialog() {
        EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        input.setHint(getString(R.string.custom_port_dialog_hint));
        input.setText(Integer.toString(getDefaultOpenCodePort()));
        input.setSelection(input.getText().length());

        new AlertDialog.Builder(this)
            .setTitle(R.string.button_configure_default_port)
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
                if (!OpenCodeSettings.isValidPort(port)) {
                    Toast.makeText(this, R.string.custom_port_invalid, Toast.LENGTH_SHORT).show();
                    return;
                }

                OpenCodeSettings.setDefaultPort(this, port);
                Toast.makeText(this, getString(R.string.default_port_saved, port), Toast.LENGTH_SHORT).show();
                refreshStatus();
                requestStageStatusRefresh();
            })
            .show();
    }

    private void showDownloadSourceModeDialog() {
        if (commandInFlight) {
            Toast.makeText(this, R.string.command_busy, Toast.LENGTH_SHORT).show();
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
        if (commandInFlight) {
            Toast.makeText(this, R.string.command_busy, Toast.LENGTH_SHORT).show();
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

        if (disableBatteryRequirementSwitch != null) {
            boolean disableRequirement = !isBatteryRequirementEnabled();
            disableBatteryRequirementSwitch.setChecked(disableRequirement);
            disableBatteryRequirementSwitch.setEnabled(!commandInFlight);
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
        button.setEnabled(!commandInFlight);
        button.setAlpha(button.isEnabled() ? 1.0f : 0.78f);
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
            configureDownloadSourceButton.setEnabled(!commandInFlight);
            configureDownloadSourceButton.setAlpha(configureDownloadSourceButton.isEnabled() ? 1.0f : 0.78f);
        }

        if (probeDownloadSourceButton != null) {
            probeDownloadSourceButton.setEnabled(!commandInFlight);
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
            configureMaintenanceWebPortButton.setEnabled(!commandInFlight);
            configureMaintenanceWebPortButton.setAlpha(configureMaintenanceWebPortButton.isEnabled() ? 1.0f : 0.78f);
        }
        if (openMaintenanceWebButton != null) {
            openMaintenanceWebButton.setEnabled(!commandInFlight);
            openMaintenanceWebButton.setAlpha(openMaintenanceWebButton.isEnabled() ? 1.0f : 0.78f);
        }
        if (stopMaintenanceWebButton != null) {
            stopMaintenanceWebButton.setEnabled(!commandInFlight);
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
        return OpenCodeDownloadSourceSettings.getMode(this) == OpenCodeDownloadSourceSettings.Mode.AUTO
            && !OpenCodeDownloadSourceSettings.isLastProbeFresh(this);
    }

    private void runOpenCodeSourceProbe(boolean continueWithInstall) {
        if (commandInFlight) {
            Toast.makeText(this, R.string.command_busy, Toast.LENGTH_SHORT).show();
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
            configureDefaultPortButton.setText(getString(R.string.button_configure_default_port_with_value, getDefaultOpenCodePort()));
            configureDefaultPortButton.setEnabled(!commandInFlight);
            configureDefaultPortButton.setAlpha(configureDefaultPortButton.isEnabled() ? 1.0f : 0.78f);
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
            button.setEnabled(!commandInFlight
                && !batteryRequirementBlocking
                && presentation.state != StageUiState.CHECKING
                && presentation.state != StageUiState.BLOCKED);
            button.setAlpha(button.isEnabled() ? 1.0f : 0.78f);
        }

        if (customPortButton != null) {
            customPortButton.setEnabled(!commandInFlight && !batteryRequirementBlocking);
            customPortButton.setAlpha(customPortButton.isEnabled() ? 1.0f : 0.78f);
        }
    }

    private StageCheckSnapshot inspectStageStatuses() {
        StageCheckSnapshot snapshot = new StageCheckSnapshot();

        boolean prepareComplete = isPrepareStageComplete();
        boolean termuxPackagesComplete = isTermuxPackagesStageComplete();
        boolean ubuntuInstalled = termuxPackagesComplete && isUbuntuInstalled();
        boolean officialDocsSynced = ubuntuInstalled && isOfficialDocsSynced();
        boolean ubuntuPackagesComplete = officialDocsSynced && isUbuntuPackagesStageComplete();
        boolean entryUbuntuConfigured = ubuntuPackagesComplete && isEntryUbuntuConfigured();
        boolean openCodeInstalled = entryUbuntuConfigured && isOpenCodeInstalled();
        boolean codexInstalled = openCodeInstalled && isCodexInstalled();
        boolean claudeCodeInstalled = openCodeInstalled && isClaudeCodeInstalled();
        boolean deepSeekConfigured = ubuntuInstalled && isDeepSeekConfigured();
        boolean openCodeRunning = openCodeInstalled && isOpenCodeWebReachable();

        snapshot.opencodeReachable = openCodeRunning;

        Integer prepareExitCode = readLastExitCode(StageAction.PREPARE);
        Integer termuxPackagesExitCode = readLastExitCode(StageAction.TERMUX_PACKAGES);
        Integer installUbuntuExitCode = readLastExitCode(StageAction.INSTALL_UBUNTU);
        Integer syncOfficialDocsExitCode = readLastExitCode(StageAction.SYNC_OFFICIAL_DOCS);
        Integer ubuntuPackagesExitCode = readLastExitCode(StageAction.UBUNTU_PACKAGES);
        Integer configureEntryUbuntuExitCode = readLastExitCode(StageAction.CONFIGURE_ENTRY_UBUNTU);
        Integer installOpenCodeExitCode = readLastExitCode(StageAction.INSTALL_OPENCODE);
        Integer installCodexExitCode = readLastExitCode(StageAction.INSTALL_CODEX);
        Integer installClaudeCodeExitCode = readLastExitCode(StageAction.INSTALL_CLAUDE_CODE);

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
            StageAction.INSTALL_OPENCODE,
            openCodeInstalled
                ? StagePresentation.complete(this, getString(R.string.stage_detail_install_opencode_complete))
                : (!entryUbuntuConfigured
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
            StageAction.CONFIGURE_DEEPSEEK,
            deepSeekConfigured
                ? StagePresentation.complete(this, getString(R.string.stage_detail_configure_deepseek_complete))
                : (!ubuntuInstalled
                    ? StagePresentation.blocked(this, getString(R.string.stage_detail_configure_deepseek_blocked))
                    : failedOrReady(readLastExitCode(StageAction.CONFIGURE_DEEPSEEK),
                        getString(R.string.stage_detail_configure_deepseek_failed),
                        getString(R.string.stage_detail_configure_deepseek_ready)))
        );

        snapshot.presentations.put(
            StageAction.START,
            openCodeRunning
                ? StagePresentation.complete(this, getString(R.string.stage_detail_start_complete))
                : (!openCodeInstalled
                    ? StagePresentation.blocked(this, getString(R.string.stage_detail_start_blocked))
                    : failedOrReady(readLastExitCode(StageAction.START),
                        getString(R.string.stage_detail_start_failed),
                        getString(R.string.stage_detail_start_ready)))
        );

        snapshot.presentations.put(
            StageAction.RESTART,
            !openCodeInstalled
                ? StagePresentation.blocked(this, getString(R.string.stage_detail_restart_blocked))
                : failedOrReady(readLastExitCode(StageAction.RESTART),
                    getString(R.string.stage_detail_restart_failed),
                    openCodeRunning
                        ? getString(R.string.stage_detail_restart_ready_running)
                        : getString(R.string.stage_detail_restart_ready_stopped))
        );

        return snapshot;
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
            "proot-distro login ubuntu -- bash -lc 'export PATH=\"$HOME/.opencode/bin:$HOME/.local/bin:$PATH\"; (command -v opencode >/dev/null 2>&1 || test -x \"$HOME/.opencode/bin/opencode\") && test -f \"$HOME/openhouseai-links/docs-path.txt\" && test -f \"$HOME/openhouseai-links/workspace-path.txt\"'"
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

    private boolean isEntryUbuntuConfigured() {
        return runTermuxCommand(
            "test \"$(tr -d '[:space:]' < \"$HOME/.openhouseai/entry-mode\" 2>/dev/null || true)\" = ubuntu && test -f \"$HOME/.openhouseai/entry.sh\" && grep -Fq '# OpenHouseAI startup entry' \"$HOME/.bashrc\""
        ).isSuccess();
    }

    private boolean isDeepSeekConfigured() {
        return runTermuxCommand(
            "proot-distro login ubuntu -- bash -lc 'test -s \"$HOME/.config/openhouseai/deepseek-api-key\" && test -f \"$HOME/.config/opencode/opencode.json\" && grep -Fq \"ANTHROPIC_BASE_URL=https://api.deepseek.com/anthropic\" \"$HOME/.bashrc\"'"
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
        return OpenCodeSettings.getLoopbackUrl(OpenCodeSettings.DEFAULT_OPENCODE_PORT);
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
                if (foundExitCode == 0 && "manifest_full".equals(foundSlug)) {
                    oneClickStagesInFlight = false;
                    if (oneClickStageSummaryView != null) {
                        oneClickStageSummaryView.setText(R.string.one_click_stage_summary_complete);
                    }
                    Toast.makeText(this, R.string.one_click_stage_toast_complete, Toast.LENGTH_SHORT).show();
                } else if (foundExitCode != 0) {
                    oneClickStagesInFlight = false;
                    if (oneClickStageSummaryView != null) {
                        oneClickStageSummaryView.setText(getString(R.string.one_click_stage_summary_waiting, currentStageLabel));
                    }
                    Toast.makeText(this, getString(R.string.one_click_stage_toast_blocked, currentStageLabel), Toast.LENGTH_LONG).show();
                }
            }
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
        INSTALL_OPENCODE("install_opencode", "install-opencode.sh"),
        INSTALL_CODEX("install_codex", "install-codex.sh"),
        INSTALL_CLAUDE_CODE("install_claude_code", "install-claude-code.sh"),
        CONFIGURE_DEEPSEEK("configure_deepseek", "configure-deepseek-key.sh"),
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
                || this == CONFIGURE_ENTRY_UBUNTU
                || this == INSTALL_CODEX
                || this == INSTALL_CLAUDE_CODE
                || this == START
                || this == RESTART;
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
