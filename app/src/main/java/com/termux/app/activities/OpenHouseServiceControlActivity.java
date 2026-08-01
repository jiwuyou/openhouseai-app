package com.termux.app.activities;

import android.content.Intent;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.termux.R;
import com.termux.app.openhouse.OpenHouseExitAllController;
import com.termux.app.openhouse.OpenHouseMaintainerRunner;
import com.termux.app.openhouse.OpenHouseRuntimeSupervisor;
import com.termux.app.openhouse.servicecontrol.ServiceManagerActionResult;
import com.termux.app.openhouse.servicecontrol.ServiceManagerClient;
import com.termux.app.openhouse.servicecontrol.ServiceManagerControlClient;
import com.termux.app.openhouse.servicecontrol.ServiceManagerLogEntry;
import com.termux.app.openhouse.servicecontrol.ServiceManagerRedactor;
import com.termux.app.openhouse.servicecontrol.ServiceManagerService;
import com.termux.app.openhouse.servicecontrol.ServiceManagerServiceStatus;
import com.termux.app.openhouse.servicecontrol.ServiceManagerServiceResolver;
import com.termux.app.openhouse.tutorial.GuidedTutorialOverlay;
import com.termux.shared.logger.Logger;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class OpenHouseServiceControlActivity extends AppCompatActivity {

    public static final String EXTRA_SERVICE_CONTROL_MODE = "openhouse_service_control_mode";
    public static final String MODE_ALL = "all";
    public static final String EXTRA_SERVICE_CONTROL_COMPONENT_ID = "openhouse_component_id";
    public static final String EXTRA_SERVICE_CONTROL_TITLE = "openhouse_component_title";
    public static final String EXTRA_SERVICE_CONTROL_URL = "openhouse_component_url";
    public static final String EXTRA_SERVICE_CONTROL_SERVICE_NAMES = "openhouse_service_names";
    public static final String EXTRA_SERVICE_CONTROL_SERVICE_REFS = "openhouse_service_refs";
    public static final String EXTRA_SERVICE_CONTROL_TUTORIAL = "openhouse_service_control_tutorial";
    public static final String EXTRA_SERVICE_CONTROL_TUTORIAL_SERVICE_ID =
        "openhouse_service_control_tutorial_service_id";
    public static final String EXTRA_SERVICE_CONTROL_TUTORIAL_COMPLETED =
        "openhouse_service_control_tutorial_completed";
    public static final String TUTORIAL_CC_CODEX_CONTROL = "cc_codex_control";
    public static final String TUTORIAL_DESKTOP_APP_CONTROL = "desktop_app_control";

    private static final String LOG_TAG = "OpenHouseServiceControl";
    private static final String ACTION_START = "start";
    private static final String ACTION_STOP = "stop";
    private static final String ACTION_RESTART = "restart";
    private static final String ACTION_REPAIR = "repair";
    private static final String LOCKED_DISABLED_BUTTON_TAG = "openhouse_locked_disabled";
    private static final int LOG_LIMIT = 80;
    private static final int STATUS_TEXT_LIMIT = 10000;
    private static final long FOREGROUND_MAINTENANCE_INTERVAL_MS = 30_000L;
    private static final int DESKTOP_TUTORIAL_STATUS_POLL_ATTEMPTS = 20;
    private static final long DESKTOP_TUTORIAL_STATUS_POLL_DELAY_MS = 500L;

    private final ExecutorService backgroundExecutor = Executors.newSingleThreadExecutor();
    private final Map<String, ServiceCard> serviceCards = new LinkedHashMap<>();
    private final Handler foregroundHandler = new Handler(Looper.getMainLooper());
    private final Runnable foregroundMaintenanceRunnable = new Runnable() {
        @Override
        public void run() {
            runScheduledForegroundMaintenance();
        }
    };

    private ServiceManagerControlClient controlClient;
    private OpenHouseRuntimeSupervisor runtimeSupervisor;
    private ScrollView rootScrollView;
    private LinearLayout contentView;
    private LinearLayout serviceListView;
    private TextView statusView;
    private TextView controlPlaneStatusView;
    private Button returnMenuButton;
    private Button startControlPlaneButton;
    private Button maintenanceButton;
    private GuidedTutorialOverlay ccCodexTutorialOverlay;
    private GuidedTutorialOverlay desktopAppTutorialOverlay;
    private boolean allMode;
    private boolean ccCodexTutorialStarted;
    private boolean desktopAppTutorialStarted;
    private boolean desktopTutorialActionInFlight;
    private boolean desktopTutorialStopConfirmed;
    private boolean desktopTutorialFinalStartConfirmed;
    private boolean desktopTutorialCycleCompleted;
    private boolean foregroundMaintenanceEnabled;
    private boolean foregroundMaintenanceTaskRunning;
    private String componentId = "";
    private String componentTitle = "";
    private String componentUrl = "";
    private String tutorialMode = "";
    private String tutorialServiceId = "";
    private String resolvedTutorialServiceId = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        controlClient = new ServiceManagerControlClient(this);
        runtimeSupervisor = new OpenHouseRuntimeSupervisor(this);
        parseIntent(getIntent());
        if (isDesktopAppControlTutorial()) {
            setResult(RESULT_CANCELED);
        }
        buildContentView();
        loadInitialServices();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!isDesktopAppControlTutorial()) {
            startForegroundMaintenance();
        }
    }

    @Override
    protected void onPause() {
        stopForegroundMaintenance();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (ccCodexTutorialOverlay != null) {
            ccCodexTutorialOverlay.destroy();
            ccCodexTutorialOverlay = null;
        }
        if (desktopAppTutorialOverlay != null) {
            desktopAppTutorialOverlay.destroy();
            desktopAppTutorialOverlay = null;
        }
        stopForegroundMaintenance();
        backgroundExecutor.shutdownNow();
    }

    private void parseIntent(Intent intent) {
        if (intent == null) {
            allMode = false;
            return;
        }
        componentId = ServiceManagerClient.sanitizeServiceId(intent.getStringExtra(EXTRA_SERVICE_CONTROL_COMPONENT_ID));
        componentTitle = safeTrim(intent.getStringExtra(EXTRA_SERVICE_CONTROL_TITLE));
        componentUrl = normalizeOpenUrl(intent.getStringExtra(EXTRA_SERVICE_CONTROL_URL));
        tutorialMode = safeTrim(intent.getStringExtra(EXTRA_SERVICE_CONTROL_TUTORIAL));
        tutorialServiceId = ServiceManagerClient.sanitizeServiceId(
            intent.getStringExtra(EXTRA_SERVICE_CONTROL_TUTORIAL_SERVICE_ID));
        allMode = !isDesktopAppControlTutorial()
            && MODE_ALL.equalsIgnoreCase(safeTrim(intent.getStringExtra(EXTRA_SERVICE_CONTROL_MODE)));
    }

    private void buildContentView() {
        ScrollView scrollView = new ScrollView(this);
        rootScrollView = scrollView;
        scrollView.setFillViewport(true);
        scrollView.setBackgroundColor(ContextCompat.getColor(this, R.color.surface));

        contentView = new LinearLayout(this);
        contentView.setOrientation(LinearLayout.VERTICAL);
        contentView.setPadding(dp(18), dp(18), dp(18), dp(24));
        scrollView.addView(contentView, new ScrollView.LayoutParams(
            ScrollView.LayoutParams.MATCH_PARENT,
            ScrollView.LayoutParams.WRAP_CONTENT));

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setBackgroundResource(R.drawable.panel_bg);
        header.setPadding(dp(16), dp(16), dp(16), dp(16));
        contentView.addView(header, fullWidthParams(0));

        TextView titleView = new TextView(this);
        titleView.setText(allMode ? "全部服务控制" : safeComponentTitle() + " 控制");
        titleView.setTextColor(ContextCompat.getColor(this, R.color.textPrimary));
        titleView.setTextSize(22);
        titleView.setTypeface(titleView.getTypeface(), Typeface.BOLD);
        header.addView(titleView);

        TextView descriptionView = bodyText(allMode
            ? "日常运行控制页。这里显示控制中枢和各服务状态，只调用 service-manager，不展示首次安装详细进度。"
            : "日常组件控制页。这里只控制该组件注册的服务，不展示首次安装详细进度。");
        header.addView(descriptionView, topMarginParams(8));

        if (isCcCodexTutorial()) {
            addCcCodexTutorialPanel(header);
        }

        statusView = bodyText("正在读取服务状态...");
        statusView.setTextColor(ContextCompat.getColor(this, R.color.textPrimary));
        header.addView(statusView, topMarginParams(12));

        controlPlaneStatusView = bodyText("控制中枢：正在检查 service-manager...");
        controlPlaneStatusView.setTextColor(ContextCompat.getColor(this, R.color.textPrimary));
        header.addView(controlPlaneStatusView, topMarginParams(10));

        returnMenuButton = isDesktopAppControlTutorial()
            ? actionButton("返回应用", v -> finishDesktopTutorialAndReturn())
            : actionButton("返回菜单", v -> returnToOpenHouseMenu());
        header.addView(returnMenuButton, topMarginParams(10));

        if (isOpenHouseWebService(componentId) || !componentUrl.isEmpty()) {
            header.addView(actionButton(openActionLabel(componentId),
                v -> openServiceEntry(componentId, componentUrl)), topMarginParams(10));
        }

        startControlPlaneButton = actionButton("启动运行中枢", v -> runControlPlaneStart());
        startControlPlaneButton.setVisibility(View.GONE);
        header.addView(startControlPlaneButton, topMarginParams(10));

        maintenanceButton = actionButton("打开维护与修复", v -> openMaintenanceCenter());
        maintenanceButton.setVisibility(View.GONE);
        header.addView(maintenanceButton, topMarginParams(10));

        if (allMode) {
            addBulkControls();
        }

        serviceListView = new LinearLayout(this);
        serviceListView.setOrientation(LinearLayout.VERTICAL);
        contentView.addView(serviceListView, topMarginParams(14));

        setContentView(scrollView);
    }

    private void addCcCodexTutorialPanel(LinearLayout parent) {
        TextView title = sectionTitle("cc/codex 控制教学");
        parent.addView(title, topMarginParams(12));
        TextView body = bodyText("请真实执行一次服务控制：如果当前状态是运行中，点击“教学操作：关闭”；如果当前未运行，点击“教学操作：启动”。完成后点击“刷新”确认状态。\n\n浏览器也可以打开这个本机地址，但本教学不会自动跳转浏览器。");
        parent.addView(body, topMarginParams(6));
    }

    private void addBulkControls() {
        LinearLayout panel = panel();
        contentView.addView(panel, topMarginParams(14));

        TextView title = sectionTitle("批量控制");
        panel.addView(title);

        TextView note = bodyText("全部关闭和全部重启会跳过 service-manager；“停止运行栈”会停止业务服务并保留 service-manager 控制中枢；“全部退出 OpenHouse”会在停止业务运行栈后关闭 OpenHouse 界面，控制中枢继续保留。");
        panel.addView(note, topMarginParams(8));

        addButtonRow(panel,
            actionButton("全部启动", v -> runBulkAction(ACTION_START)),
            actionButton("全部关闭", v -> runBulkAction(ACTION_STOP)));
        addButtonRow(panel,
            actionButton("全部重启", v -> runBulkAction(ACTION_RESTART)),
            actionButton("刷新", v -> loadInitialServices()));
        addButtonRow(panel,
            actionButton("恢复默认核心服务", v -> runDefaultCoreServiceMaintenance()),
            actionButton("停止运行栈", v -> confirmStopRuntimeStack()));
        addFullWidthButton(panel, actionButton("全部退出 OpenHouse", v -> confirmExitAll()));
    }

    private void loadInitialServices() {
        serviceListView.removeAllViews();
        serviceCards.clear();
        setBusy(true);
        setStatus("正在读取服务列表...");
        setControlPlaneStatus("控制中枢：正在检查 service-manager...");
        hideMaintenanceFallback();
        if (allMode) {
            loadAllServices();
        } else {
            List<String> serviceIds = readComponentServiceIds(getIntent());
            if (isDesktopAppControlTutorial() && serviceIds.isEmpty()) {
                setBusy(false);
                setStatus("桌面教学目标无效：组件或服务没有正确声明，无法执行服务控制教学。");
                return;
            }
            loadComponentServices(serviceIds);
        }
    }

    private void loadAllServices() {
        backgroundExecutor.execute(() -> {
            try {
                List<ServiceManagerService> services = controlClient.listServices();
                List<ServiceSnapshot> snapshots = new ArrayList<>();
                for (ServiceManagerService service : services) {
                    String serviceId = ServiceManagerClient.sanitizeServiceId(service.id());
                    if (serviceId.isEmpty()) {
                        continue;
                    }
                    snapshots.add(new ServiceSnapshot(
                        serviceId,
                        firstNonBlank(service.displayName(), serviceId),
                        service.provider(),
                        service.state(),
                        service.pid(),
                        service.message(),
                        service.url(),
                        true));
                }
                runOnUiThread(() -> {
                    updateControlPlaneStatusFromSnapshots(snapshots);
                    renderServices(snapshots);
                    refreshAllStatuses();
                });
            } catch (Exception e) {
                Logger.logStackTraceWithMessage(LOG_TAG, "Failed to list services", e);
                runOnUiThread(() -> showServiceManagerError(
                    "控制中枢异常：无法读取 service-manager 服务列表。\n影响：运行控制无法统一查看、启动或关闭服务。\n推荐动作：请点击“启动运行中枢”。\n"
                        + safeErrorMessage(e)));
            }
        });
    }

    private void loadComponentServices(List<String> serviceIds) {
        boolean hasDeclaredServiceIds = serviceIds != null && !serviceIds.isEmpty();
        if (!hasDeclaredServiceIds && componentId.isEmpty()) {
            setBusy(false);
            setStatus("这个组件没有注册可控制的 service-manager 服务。");
            return;
        }
        setBusy(true);
        setStatus("正在匹配当前组件的 service-manager 服务...");
        setControlPlaneStatus("控制中枢：正在读取 service-manager 服务列表...");
        hideMaintenanceFallback();
        backgroundExecutor.execute(() -> {
            try {
                List<ServiceManagerService> registeredServices = controlClient.listServices();
                ServiceManagerServiceResolver.Resolution resolution =
                    ServiceManagerServiceResolver.resolve(
                        isDesktopAppControlTutorial() ? "" : componentId,
                        serviceIds,
                        registeredServices);
                List<ServiceSnapshot> snapshots = snapshotsFromResolution(resolution);
                String tutorialTarget = "";
                if (isDesktopAppControlTutorial()) {
                    if (resolution.serviceIds.size() == 1) {
                        tutorialTarget = ServiceManagerClient.sanitizeServiceId(
                            resolution.serviceIds.get(0));
                    }
                    if (tutorialTarget.isEmpty() || isControlPlaneService(tutorialTarget)) {
                        snapshots.clear();
                        tutorialTarget = "";
                    } else if (snapshots.size() > 1) {
                        ServiceSnapshot targetSnapshot = null;
                        for (ServiceSnapshot snapshot : snapshots) {
                            if (snapshot != null && tutorialTarget.equals(snapshot.id)) {
                                targetSnapshot = snapshot;
                                break;
                            }
                        }
                        snapshots.clear();
                        if (targetSnapshot != null) {
                            snapshots.add(targetSnapshot);
                        }
                    }
                }
                String resolvedDesktopTutorialTarget = tutorialTarget;
                runOnUiThread(() -> {
                    if (snapshots.isEmpty()) {
                        setBusy(false);
                        setControlPlaneStatus("控制中枢：已连接（service-manager），但当前组件注册的服务未出现在服务列表中。");
                        setStatus((hasDeclaredServiceIds
                                ? "这个组件注册的服务当前不可控。"
                                : "这个组件没有声明可控制服务，也未在当前服务列表中匹配到同名服务。")
                            + formatMissingServices(resolution.missingServiceIds)
                            + "\n可以打开“全部服务控制”查看当前可用服务，或点击“启动运行中枢”。");
                        showMaintenanceFallback();
                        return;
                    }
                    if (isDesktopAppControlTutorial()) {
                        resolvedTutorialServiceId = resolvedDesktopTutorialTarget;
                    }
                    setControlPlaneStatus("控制中枢：当前组件由 service-manager 控制。"
                        + formatIgnoredServices(resolution.missingServiceIds));
                    renderServices(snapshots);
                    refreshAllStatuses();
                });
            } catch (Exception e) {
                Logger.logStackTraceWithMessage(LOG_TAG, "Failed to resolve component services", e);
                runOnUiThread(() -> showServiceManagerError(
                    "控制中枢异常：无法匹配当前组件服务。\n"
                        + safeErrorMessage(e)));
            }
        });
    }

    private List<String> readComponentServiceIds(Intent intent) {
        List<String> out = new ArrayList<>();
        if (intent == null || intent.getExtras() == null) {
            return out;
        }
        for (String value : readIntentStringList(intent, EXTRA_SERVICE_CONTROL_SERVICE_NAMES)) {
            addServiceId(out, value);
        }
        for (String serviceId : ServiceManagerClient.serviceIdsFromRefs(
            readIntentStringList(intent, EXTRA_SERVICE_CONTROL_SERVICE_REFS))) {
            addServiceId(out, serviceId);
        }
        if (isDesktopAppControlTutorial()) {
            List<String> fixedTarget = new ArrayList<>();
            if (!componentId.isEmpty()
                && !tutorialServiceId.isEmpty()
                && !isControlPlaneService(tutorialServiceId)
                && out.contains(tutorialServiceId)) {
                fixedTarget.add(tutorialServiceId);
            }
            return fixedTarget;
        }
        return out;
    }

    private List<String> readIntentStringList(Intent intent, String key) {
        List<String> out = new ArrayList<>();
        if (intent == null || intent.getExtras() == null || key == null || key.isEmpty()) {
            return out;
        }
        Object raw = intent.getExtras().get(key);
        if (raw instanceof String[]) {
            for (String value : (String[]) raw) {
                collectSplitValues(out, value);
            }
        } else if (raw instanceof ArrayList) {
            ArrayList<?> values = (ArrayList<?>) raw;
            for (Object value : values) {
                if (value instanceof String) {
                    collectSplitValues(out, (String) value);
                }
            }
        } else {
            collectSplitValues(out, intent.getStringExtra(key));
        }
        return out;
    }

    private void collectSplitValues(List<String> out, String raw) {
        if (out == null || raw == null) {
            return;
        }
        for (String part : raw.split(",")) {
            String value = safeTrim(part);
            if (!value.isEmpty() && !out.contains(value)) {
                out.add(value);
            }
        }
    }

    private void renderServices(List<ServiceSnapshot> services) {
        serviceListView.removeAllViews();
        serviceCards.clear();
        if (services == null || services.isEmpty()) {
            setBusy(false);
            setStatus(allMode ? "service-manager 没有返回服务。" : "这个组件没有可控制的服务。");
            maybeStartCcCodexControlTutorial();
            return;
        }
        for (ServiceSnapshot snapshot : services) {
            ServiceCard card = createServiceCard(snapshot);
            serviceCards.put(snapshot.id, card);
            serviceListView.addView(card.root, topMarginParams(0));
        }
        setStatus("已加载 " + services.size() + " 个服务。");
    }

    private ServiceCard createServiceCard(ServiceSnapshot snapshot) {
        LinearLayout cardView = panel();

        TextView titleView = sectionTitle(snapshot.displayName);
        cardView.addView(titleView);

        TextView idView = bodyText("服务 ID：" + snapshot.id);
        cardView.addView(idView, topMarginParams(6));

        TextView detailView = bodyText("");
        cardView.addView(detailView, topMarginParams(8));

        TextView logView = bodyText("");
        logView.setVisibility(View.GONE);
        cardView.addView(logView, topMarginParams(10));

        Button openButton = actionButton(openActionLabel(snapshot.id),
            v -> openServiceEntry(snapshot.id, currentServiceUrl(snapshot.id)));
        Button tutorialActionButton = actionButton("教学操作：读取状态中", v -> refreshServiceStatus(snapshot.id));
        tutorialActionButton.setVisibility(isCcCodexTutorialService(snapshot.id) ? View.VISIBLE : View.GONE);
        if (tutorialActionButton.getVisibility() == View.VISIBLE) {
            cardView.addView(tutorialActionButton, topMarginParams(10));
        }

        Button startButton = actionButton("启动", v -> runSingleAction(snapshot.id, ACTION_START));
        Button stopButton = actionButton("关闭", v -> runSingleAction(snapshot.id, ACTION_STOP));
        Button refreshButton = actionButton("刷新", v -> refreshServiceStatus(snapshot.id));

        ServiceCard card = new ServiceCard(snapshot.id, cardView, titleView, detailView, logView,
            openButton, tutorialActionButton, startButton, stopButton, refreshButton);
        updateCard(card, snapshot);

        addButtonRow(cardView,
            openButton,
            actionButton("状态", v -> refreshServiceStatus(snapshot.id)));
        addButtonRow(cardView,
            startButton,
            stopButton);
        addButtonRow(cardView,
            actionButton("重启", v -> runSingleAction(snapshot.id, ACTION_RESTART)),
            actionButton("修复", v -> runSingleAction(snapshot.id, ACTION_REPAIR)));
        addButtonRow(cardView,
            actionButton("日志", v -> fetchLogs(snapshot.id)),
            refreshButton);

        return card;
    }

    private void refreshAllStatuses() {
        if (serviceCards.isEmpty()) {
            setBusy(false);
            return;
        }
        setBusy(true);
        setStatus("正在刷新服务状态...");
        hideMaintenanceFallback();
        List<String> serviceIds = new ArrayList<>(serviceCards.keySet());
        backgroundExecutor.execute(() -> {
            List<ServiceSnapshot> snapshots = new ArrayList<>();
            String error = "";
            for (String serviceId : serviceIds) {
                try {
                    ServiceSnapshot snapshot = snapshotFromStatus(serviceId, controlClient.getStatus(serviceId));
                    snapshots.add(snapshot);
                    if (!snapshot.success && error.isEmpty()) {
                        error = serviceId + " 状态读取失败：" + firstNonBlank(snapshot.message, "service-manager request failed");
                    }
                } catch (Exception e) {
                    Logger.logStackTraceWithMessage(LOG_TAG, "Failed to refresh service status: " + serviceId, e);
                    if (error.isEmpty()) {
                        error = safeErrorMessage(e);
                    }
                    snapshots.add(new ServiceSnapshot(serviceId, serviceId, "", "unknown", -1, safeErrorMessage(e), currentServiceUrl(serviceId), false));
                }
            }
            String firstError = error;
            runOnUiThread(() -> {
                for (ServiceSnapshot snapshot : snapshots) {
                    ServiceCard card = serviceCards.get(snapshot.id);
                    if (card != null) {
                        updateCard(card, snapshot);
                    }
                }
                updateControlPlaneStatusFromSnapshots(snapshots);
                setBusy(false);
                if (!firstError.isEmpty()) {
                    setStatus("部分状态读取失败。\n" + firstError
                        + "\n如果控制中枢异常，请点击“启动运行中枢”。");
                    showMaintenanceFallback();
                } else {
                    setStatus("状态已刷新。");
                }
                maybeStartCcCodexControlTutorial();
                maybeStartDesktopAppControlTutorial();
            });
        });
    }

    private void refreshServiceStatus(String serviceId) {
        String cleanServiceId = ServiceManagerClient.sanitizeServiceId(serviceId);
        if (cleanServiceId.isEmpty()) {
            setStatus("服务 ID 无效。");
            return;
        }
        setBusy(true);
        setStatus("正在读取 " + cleanServiceId + " 状态...");
        hideMaintenanceFallback();
        backgroundExecutor.execute(() -> {
            try {
                ServiceSnapshot snapshot = snapshotFromStatus(cleanServiceId, controlClient.getStatus(cleanServiceId));
                runOnUiThread(() -> {
                    ServiceCard card = serviceCards.get(cleanServiceId);
                    if (card != null) {
                        updateCard(card, snapshot);
                    }
                    setBusy(false);
                    if (snapshot.success) {
                        setStatus(cleanServiceId + " 状态已刷新。");
                    } else {
                        showServiceManagerError(cleanServiceId + " 状态读取失败。\n"
                            + firstNonBlank(snapshot.message, "service-manager request failed"));
                    }
                });
            } catch (Exception e) {
                Logger.logStackTraceWithMessage(LOG_TAG, "Failed to refresh service status: " + cleanServiceId, e);
                runOnUiThread(() -> {
                    setBusy(false);
                    showServiceManagerError(cleanServiceId + " 状态读取失败。\n" + safeErrorMessage(e));
                });
            }
        });
    }

    private void runSingleAction(String serviceId, String action) {
        String cleanServiceId = ServiceManagerClient.sanitizeServiceId(serviceId);
        if (cleanServiceId.isEmpty() || ServiceManagerClient.sanitizeAction(action).isEmpty()) {
            setStatus("服务 ID 或动作无效。");
            return;
        }
        setBusy(true);
        setStatus("正在请求 " + cleanServiceId + " " + actionLabel(action) + "...");
        hideMaintenanceFallback();
        backgroundExecutor.execute(() -> {
            try {
                ServiceManagerActionResult result = controlClient.runAction(cleanServiceId, action);
                if (!result.success()) {
                    runOnUiThread(() -> {
                        setBusy(false);
                        showServiceManagerError(formatActionResult(cleanServiceId, action, result));
                    });
                    return;
                }
                try {
                    ServiceManagerServiceStatus status = controlClient.getStatus(cleanServiceId);
                    ServiceSnapshot snapshot = snapshotFromStatus(cleanServiceId, status);
                    runOnUiThread(() -> {
                        ServiceCard card = serviceCards.get(cleanServiceId);
                        if (card != null) {
                            updateCard(card, snapshot);
                        }
                        setBusy(false);
                        if (snapshot.success) {
                            setStatus(formatActionResult(cleanServiceId, action, result));
                        } else {
                            showServiceManagerError(formatActionResult(cleanServiceId, action, result)
                                + "\n动作已提交，但状态刷新失败："
                                + firstNonBlank(snapshot.message, "service-manager request failed"));
                        }
                    });
                } catch (Exception statusError) {
                    Logger.logStackTraceWithMessage(LOG_TAG, "Failed to refresh status after service action: " + cleanServiceId, statusError);
                    runOnUiThread(() -> {
                        setBusy(false);
                        showServiceManagerError(formatActionResult(cleanServiceId, action, result)
                            + "\n动作已提交，但状态刷新失败：" + safeErrorMessage(statusError));
                    });
                }
            } catch (Exception e) {
                Logger.logStackTraceWithMessage(LOG_TAG, "Failed to run service action: " + cleanServiceId, e);
                runOnUiThread(() -> {
                    setBusy(false);
                    showServiceManagerError(cleanServiceId + " " + actionLabel(action) + "失败。\n" + safeErrorMessage(e));
                });
            }
        });
    }

    private void runBulkAction(String action) {
        String cleanAction = ServiceManagerClient.sanitizeAction(action);
        if (cleanAction.isEmpty()) {
            setStatus("批量动作无效。");
            return;
        }
        List<String> serviceIds = new ArrayList<>(serviceCards.keySet());
        if (serviceIds.isEmpty()) {
            setStatus("没有可操作的服务。");
            return;
        }
        setBusy(true);
        setStatus("正在执行全部" + actionLabel(cleanAction) + "...");
        hideMaintenanceFallback();
        backgroundExecutor.execute(() -> {
            StringBuilder report = new StringBuilder();
            int okCount = 0;
            int failCount = 0;
            int skipCount = 0;
            for (String serviceId : serviceIds) {
                if (shouldSkipForBulkAction(serviceId, cleanAction)) {
                    skipCount++;
                    appendLine(report, serviceId + "：已跳过，避免关闭控制中枢。");
                    continue;
                }
                try {
                    ServiceManagerActionResult result = controlClient.runAction(serviceId, cleanAction);
                    if (result.success()) {
                        okCount++;
                    } else {
                        failCount++;
                    }
                    appendLine(report, serviceId + "：" + actionLabel(cleanAction) + " " + resultLabel(result));
                } catch (Exception e) {
                    failCount++;
                    Logger.logStackTraceWithMessage(LOG_TAG, "Failed to run bulk action: " + serviceId, e);
                    appendLine(report, serviceId + "：" + actionLabel(cleanAction) + "失败，" + safeErrorMessage(e));
                }
            }

            List<ServiceSnapshot> snapshots = new ArrayList<>();
            for (String serviceId : serviceIds) {
                try {
                    ServiceSnapshot snapshot = snapshotFromStatus(serviceId, controlClient.getStatus(serviceId));
                    snapshots.add(snapshot);
                    if (!snapshot.success) {
                        failCount++;
                        appendLine(report, serviceId + "：状态刷新失败，"
                            + firstNonBlank(snapshot.message, "service-manager request failed"));
                    }
                } catch (Exception e) {
                    failCount++;
                    snapshots.add(new ServiceSnapshot(serviceId, serviceId, "", "unknown", -1, safeErrorMessage(e), currentServiceUrl(serviceId), false));
                    appendLine(report, serviceId + "：状态刷新失败，" + safeErrorMessage(e));
                }
            }
            int finalOkCount = okCount;
            int finalFailCount = failCount;
            int finalSkipCount = skipCount;
            String finalReport = trimForStatus(report.toString());
            runOnUiThread(() -> {
                for (ServiceSnapshot snapshot : snapshots) {
                    ServiceCard card = serviceCards.get(snapshot.id);
                    if (card != null) {
                        updateCard(card, snapshot);
                    }
                }
                setBusy(false);
                setStatus("全部" + actionLabel(cleanAction) + "完成：成功 " + finalOkCount
                    + "，失败 " + finalFailCount
                    + "，跳过 " + finalSkipCount
                    + "。\n如果你正在查看服务页面，请刷新页面确认最新状态。"
                    + (finalReport.isEmpty() ? "" : "\n" + finalReport));
                if (finalFailCount > 0) {
                    showMaintenanceFallback();
                }
            });
        });
    }

    private void fetchLogs(String serviceId) {
        String cleanServiceId = ServiceManagerClient.sanitizeServiceId(serviceId);
        if (cleanServiceId.isEmpty()) {
            setStatus("服务 ID 无效。");
            return;
        }
        setBusy(true);
        setStatus("正在读取 " + cleanServiceId + " 日志...");
        hideMaintenanceFallback();
        backgroundExecutor.execute(() -> {
            try {
                List<ServiceManagerLogEntry> logs = controlClient.getLogs(cleanServiceId, LOG_LIMIT);
                String text = formatLogs(cleanServiceId, logs);
                runOnUiThread(() -> {
                    ServiceCard card = serviceCards.get(cleanServiceId);
                    if (card != null) {
                        card.logView.setText(text);
                        card.logView.setVisibility(View.VISIBLE);
                    }
                    setBusy(false);
                    setStatus(cleanServiceId + " 日志已读取。");
                });
            } catch (Exception e) {
                Logger.logStackTraceWithMessage(LOG_TAG, "Failed to fetch service logs: " + cleanServiceId, e);
                runOnUiThread(() -> {
                    setBusy(false);
                    showServiceManagerError(cleanServiceId + " 日志读取失败。\n" + safeErrorMessage(e));
                });
            }
        });
    }

    private ServiceSnapshot snapshotFromStatus(String serviceId, ServiceManagerServiceStatus status) {
        String displayName = resolvedDisplayName(serviceId, status);
        if (status == null) {
            return new ServiceSnapshot(serviceId, displayName, "", "unknown", -1, "service-manager request failed", currentServiceUrl(serviceId), false);
        }
        if (!status.success()) {
            return new ServiceSnapshot(
                serviceId,
                displayName,
                status.provider(),
                "unknown",
                status.pid(),
                firstNonBlank(status.message(), "service-manager request failed"),
                firstNonBlank(status.url(), currentServiceUrl(serviceId)),
                false);
        }
        return new ServiceSnapshot(
            serviceId,
            displayName,
            status.provider(),
            status.state(),
            status.pid(),
            status.message(),
            firstNonBlank(status.url(), currentServiceUrl(serviceId)),
            true);
    }

    private String resolvedDisplayName(String serviceId, ServiceManagerServiceStatus status) {
        String cleanServiceId = ServiceManagerClient.sanitizeServiceId(serviceId);
        String statusName = status == null ? "" : safeTrim(status.displayName());
        String currentName = currentServiceDisplayName(cleanServiceId);
        if (!currentName.isEmpty() && (statusName.isEmpty() || statusName.equals(cleanServiceId))) {
            return currentName;
        }
        return firstNonBlank(statusName, cleanServiceId);
    }

    private String currentServiceDisplayName(String serviceId) {
        String cleanServiceId = ServiceManagerClient.sanitizeServiceId(serviceId);
        ServiceCard card = cleanServiceId.isEmpty() ? null : serviceCards.get(cleanServiceId);
        CharSequence text = card == null || card.titleView == null ? null : card.titleView.getText();
        return text == null ? "" : safeTrim(text.toString());
    }

    private List<ServiceSnapshot> snapshotsFromResolution(ServiceManagerServiceResolver.Resolution resolution) {
        if (resolution == null || resolution.serviceIds.isEmpty()) {
            return new ArrayList<>();
        }
        List<ServiceSnapshot> snapshots = new ArrayList<>();
        for (String serviceId : resolution.serviceIds) {
            ServiceManagerService service = resolution.serviceForId(serviceId);
            snapshots.add(new ServiceSnapshot(
                serviceId,
                service == null ? serviceId : firstNonBlank(service.displayName(), serviceId),
                service == null ? "" : service.provider(),
                service == null ? "unknown" : firstNonBlank(service.state(), "unknown"),
                service == null ? -1 : service.pid(),
                service == null ? "" : service.message(),
                service == null ? componentUrl : firstNonBlank(service.url(), componentUrl),
                true));
        }
        return snapshots;
    }

    private void updateCard(ServiceCard card, ServiceSnapshot snapshot) {
        if (card == null || snapshot == null) {
            return;
        }
        card.titleView.setText(snapshot.displayName);
        card.state = safeTrim(snapshot.state);
        card.url = firstNonBlank(snapshot.url, card.url);
        updateOpenButton(card.openButton, card.serviceId, card.url);
        updateTutorialActionButton(card, snapshot);
        String pid = snapshot.pid > 0 ? String.valueOf(snapshot.pid) : "-";
        card.detailView.setText(
            "状态：" + stateLabel(snapshot.state)
                + "\nprovider：" + firstNonBlank(snapshot.provider, "-")
                + "\npid：" + pid
                + "\n消息：" + ServiceManagerRedactor.redact(firstNonBlank(snapshot.message, "-")));
    }

    private void updateTutorialActionButton(ServiceCard card, ServiceSnapshot snapshot) {
        if (card == null || card.tutorialActionButton == null || snapshot == null) {
            return;
        }
        if (!isCcCodexTutorialService(snapshot.id)) {
            card.tutorialActionButton.setVisibility(View.GONE);
            return;
        }
        card.tutorialActionButton.setVisibility(View.VISIBLE);
        boolean running = isRunningState(snapshot.state);
        String action = running ? ACTION_STOP : ACTION_START;
        card.tutorialActionButton.setText(running
            ? "教学操作：当前运行，点击关闭"
            : "教学操作：当前未运行，点击运行");
        card.tutorialActionButton.setOnClickListener(v -> runSingleAction(snapshot.id, action));
    }

    private void showServiceManagerError(String message) {
        setBusy(false);
        setStatus(message);
        setControlPlaneStatus("控制中枢：异常或无法连接。请点击“启动运行中枢”。");
        showMaintenanceFallback();
        maybeStartCcCodexControlTutorial();
    }

    private void showMaintenanceFallback() {
        if (startControlPlaneButton != null) {
            startControlPlaneButton.setVisibility(View.VISIBLE);
        }
        if (maintenanceButton != null) {
            maintenanceButton.setVisibility(View.VISIBLE);
        }
    }

    private void hideMaintenanceFallback() {
        if (startControlPlaneButton != null) {
            startControlPlaneButton.setVisibility(View.GONE);
        }
        if (maintenanceButton != null) {
            maintenanceButton.setVisibility(View.GONE);
        }
    }

    private void openMaintenanceCenter() {
        Toast.makeText(this, "打开维护与修复", Toast.LENGTH_SHORT).show();
        startActivity(new Intent(this, MaintenanceCenterActivity.class));
    }

    private void openServiceEntry(String serviceId, String url) {
        if (isOpenHouseWebService(serviceId)) {
            startActivity(new Intent(this, OpenHouseWebHostActivity.class));
            return;
        }
        openBrowserUrl(url);
    }

    private static String openActionLabel(String serviceId) {
        return isOpenHouseWebService(serviceId) ? "打开 OpenHouse Web" : "浏览器打开";
    }

    private static boolean isOpenHouseWebService(String serviceId) {
        return "openhouse-web".equals(ServiceManagerClient.sanitizeServiceId(serviceId));
    }

    private void openBrowserUrl(String url) {
        String target = normalizeOpenUrl(url);
        if (target.isEmpty()) {
            Toast.makeText(this, "这个服务没有可打开的浏览器地址。", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(target)));
        } catch (Exception e) {
            Toast.makeText(this, "无法打开浏览器：" + safeErrorMessage(e), Toast.LENGTH_LONG).show();
        }
    }

    private void startForegroundMaintenance() {
        foregroundMaintenanceEnabled = true;
        foregroundHandler.removeCallbacks(foregroundMaintenanceRunnable);
        foregroundHandler.postDelayed(foregroundMaintenanceRunnable, 1200L);
    }

    private void stopForegroundMaintenance() {
        foregroundMaintenanceEnabled = false;
        foregroundHandler.removeCallbacks(foregroundMaintenanceRunnable);
    }

    private void runScheduledForegroundMaintenance() {
        if (!foregroundMaintenanceEnabled || isFinishing() || backgroundExecutor.isShutdown()) {
            return;
        }
        if (foregroundMaintenanceTaskRunning) {
            foregroundHandler.postDelayed(foregroundMaintenanceRunnable, FOREGROUND_MAINTENANCE_INTERVAL_MS);
            return;
        }
        foregroundMaintenanceTaskRunning = true;
        backgroundExecutor.execute(() -> {
            OpenHouseRuntimeSupervisor.MaintenanceReport report;
            try {
                report = runtimeSupervisor.runForegroundMaintenanceTick();
            } catch (Exception e) {
                Logger.logStackTraceWithMessage(LOG_TAG, "Foreground runtime maintenance failed", e);
                report = null;
            }
            final OpenHouseRuntimeSupervisor.MaintenanceReport finalReport = report;
            runOnUiThread(() -> {
                foregroundMaintenanceTaskRunning = false;
                applyForegroundMaintenanceReport(finalReport, false);
            });
        });
        foregroundHandler.postDelayed(foregroundMaintenanceRunnable, FOREGROUND_MAINTENANCE_INTERVAL_MS);
    }

    private void runDefaultCoreServiceMaintenance() {
        OpenHouseRuntimeSupervisor.clearExitAllRequested(this);
        foregroundMaintenanceTaskRunning = false;
        startForegroundMaintenance();
        setBusy(true);
        setControlPlaneStatus("控制中枢：正在恢复默认核心服务...");
        setStatus("正在恢复默认核心服务。\n会轻量检查 service-manager，并确保 smallphone、pi-agent 和 cloudcli 默认长期服务可用。");
        hideMaintenanceFallback();
        backgroundExecutor.execute(() -> {
            OpenHouseRuntimeSupervisor.MaintenanceReport report =
                runtimeSupervisor.ensureDefaultLongRunningServices();
            runOnUiThread(() -> {
                setBusy(false);
                applyForegroundMaintenanceReport(report, true);
                if (report != null && report.success) {
                    loadInitialServices();
                }
            });
        });
    }

    private void applyForegroundMaintenanceReport(
        OpenHouseRuntimeSupervisor.MaintenanceReport report,
        boolean userVisible
    ) {
        if (report == null) {
            return;
        }
        if (report.skipped && !userVisible) {
            return;
        }
        if (report.controlPlaneReachable && report.failedCount == 0) {
            setControlPlaneStatus("控制中枢：已连接（service-manager）。默认核心服务由前台保活轻量检查。");
            if (!userVisible && report.startedCount == 0 && !report.repairAttempted) {
                return;
            }
        }
        if (report.success) {
            hideMaintenanceFallback();
            if (userVisible || report.startedCount > 0 || report.repairAttempted) {
                setStatus(report.message);
            }
            return;
        }
        setControlPlaneStatus(report.userActionRequired
            ? "控制中枢：连续多次不可达，请点击“启动运行中枢”或“恢复默认核心服务”。"
            : "控制中枢：暂不可达，前台保活会按节流策略继续尝试。");
        showMaintenanceFallback();
        if (userVisible || report.userActionRequired) {
            setStatus(report.message);
        }
    }

    private void confirmStopRuntimeStack() {
        new AlertDialog.Builder(this)
            .setTitle("停止运行栈")
            .setMessage("将停止 service-manager 管理的业务服务和 OpenHouse 拉起的 Termux/Ubuntu 长期进程，但保留 service-manager 控制中枢。\n\nApp 会留在当前界面；本次会话会暂停自动保活。用户文件、模型配置、日志和已安装 payload 会保留。之后可点击“恢复默认核心服务”重新拉起业务服务。")
            .setNegativeButton("取消", null)
            .setPositiveButton("停止运行栈", (dialog, which) -> runStopRuntimeStack())
            .show();
    }

    private void runStopRuntimeStack() {
        stopForegroundMaintenance();
        foregroundMaintenanceTaskRunning = false;
        setBusy(true);
        setStatus("正在停止运行栈。\n会停止业务服务并保留 service-manager 控制中枢；App 会留在当前界面，用户数据会保留。");
        setControlPlaneStatus("控制中枢：正在停止运行栈...");
        backgroundExecutor.execute(() -> {
            OpenHouseExitAllController.ExitReport report =
                new OpenHouseExitAllController(this).stopRuntimeStack();
            runOnUiThread(() -> {
                setBusy(false);
                setControlPlaneStatus("控制中枢：运行栈已停止。点击“恢复默认核心服务”可恢复默认核心服务。");
                setStatus(report.message);
                showMaintenanceFallback();
            });
        });
    }

    private void confirmExitAll() {
        new AlertDialog.Builder(this)
            .setTitle("全部退出 OpenHouse")
            .setMessage("将先停止业务运行栈，再关闭 OpenHouse 界面；service-manager 控制中枢会保留。\n\n用户文件、模型配置、日志和已安装 payload 会保留。再次打开 App 后可重新拉起业务服务。")
            .setNegativeButton("取消", null)
            .setPositiveButton("全部退出 OpenHouse", (dialog, which) -> runExitAll())
            .show();
    }

    private void runExitAll() {
        stopForegroundMaintenance();
        foregroundMaintenanceTaskRunning = false;
        setBusy(true);
        setStatus("正在全部退出 OpenHouse。\n会先停止业务运行栈并保留 service-manager，再关闭 OpenHouse 界面。用户数据会保留。");
        setControlPlaneStatus("控制中枢：正在停止运行栈...");
        backgroundExecutor.execute(() -> {
            OpenHouseExitAllController.ExitReport report = new OpenHouseExitAllController(this).exitAll();
            runOnUiThread(() -> {
                setBusy(false);
                setControlPlaneStatus("控制中枢：运行栈已停止。OpenHouse 界面即将关闭。");
                setStatus(report.message);
                closeOpenHouseInterfacesAfterExit(report);
            });
        });
    }

    private void closeOpenHouseInterfacesAfterExit(OpenHouseExitAllController.ExitReport report) {
        Toast.makeText(this,
            report != null && report.success
                ? "全部退出 OpenHouse 已提交"
                : "全部退出 OpenHouse 已提交，部分停止项需要检查",
            Toast.LENGTH_LONG).show();
        View anchor = rootScrollView != null ? rootScrollView : contentView;
        if (anchor != null) {
            anchor.postDelayed(this::finishAffinity, 350L);
        } else {
            finishAffinity();
        }
    }

    private void runControlPlaneStart() {
        setBusy(true);
        setControlPlaneStatus("控制中枢：启动中...");
        setStatus("正在启动运行中枢。\n会使用固定的 Termux native 启动入口拉起 service-manager，再重新读取服务状态。\n不会启动默认业务服务，也不会执行全量安装。");
        backgroundExecutor.execute(() -> {
            OpenHouseMaintainerRunner.Result result = new OpenHouseMaintainerRunner(this)
                .run(OpenHouseMaintainerRunner.Action.START_CONTROL_PLANE, 0);
            runOnUiThread(() -> {
                setBusy(false);
                if (result.isSuccess()) {
                    setControlPlaneStatus("控制中枢：启动完成，正在刷新服务状态。");
                    setStatus("运行中枢启动完成。\n下一步：正在重新读取服务状态；回到服务页面后请刷新确认。"
                        + formatMaintainerOutput(result.output));
                    hideMaintenanceFallback();
                    loadInitialServices();
                } else {
                    showServiceManagerError("运行中枢启动失败。\n影响：运行控制可能无法启动、关闭或检查服务。\n下一步：可以打开维护与修复查看详细日志。\n退出码 " + result.exitCode
                        + formatMaintainerOutput(result.output));
                }
            });
        });
    }

    private boolean shouldSkipForBulkAction(String serviceId, String action) {
        if (!ACTION_STOP.equals(action) && !ACTION_RESTART.equals(action)) {
            return false;
        }
        return isControlPlaneService(serviceId);
    }

    private boolean isControlPlaneService(String serviceId) {
        String normalized = safeTrim(serviceId).toLowerCase(Locale.US);
        return normalized.equals("service-manager")
            || normalized.endsWith("-service-manager")
            || normalized.contains("service-manager");
    }

    private String formatActionResult(String serviceId, String action, ServiceManagerActionResult result) {
        return serviceId + " " + actionLabel(action) + (result.success() ? "已提交。" : "失败。")
            + (result.success() ? "\n如果你正在查看该服务页面，请刷新页面确认最新状态。" : "")
            + (safeTrim(result.message()).isEmpty() ? "" : "\n" + result.message());
    }

    private void maybeStartCcCodexControlTutorial() {
        if (!isCcCodexTutorial() || ccCodexTutorialStarted || isFinishing()) {
            return;
        }
        ViewGroup overlayContainer = findViewById(android.R.id.content);
        if (overlayContainer == null) {
            return;
        }
        ccCodexTutorialStarted = true;
        List<GuidedTutorialOverlay.Step> steps = new ArrayList<>();
        steps.add(GuidedTutorialOverlay.Step.explanation(
            "控制中枢状态",
            "这里显示 service-manager 控制中枢和 cc/codex 服务状态。启动、关闭、修复都会真实改变服务，只能由你亲自点击。"
        ).build());
        steps.add(GuidedTutorialOverlay.Step.sideEffectClick(
            "真实操作 cc/codex",
            "请点击箭头指向的教学操作按钮：当前运行就关闭，当前未运行就启动。点击后请等待页面提示完成。",
            this::findCcCodexTutorialActionButton
        ).onTargetClick((overlay, step) -> {
            Button actionButton = findCcCodexTutorialActionButton();
            if (actionButton == null) {
                Toast.makeText(this, "还没有找到 cc/codex 教学操作按钮，请稍后刷新。", Toast.LENGTH_SHORT).show();
                overlay.refreshTarget();
                return true;
            }
            if (!actionButton.isEnabled()) {
                Toast.makeText(this, "服务正在处理，请等待状态更新后再点击。", Toast.LENGTH_SHORT).show();
                overlay.refreshTarget();
                return true;
            }
            actionButton.performClick();
            overlay.next();
            return true;
        }).build());
        steps.add(GuidedTutorialOverlay.Step.requiredClick(
            "刷新确认状态",
            "服务操作提交后，请等待状态文字更新，再点击箭头指向的刷新按钮确认最新状态。",
            this::findCcCodexRefreshButton
        ).onTargetClick((overlay, step) -> {
            Button refreshButton = findCcCodexRefreshButton();
            if (refreshButton == null) {
                Toast.makeText(this, "还没有找到刷新按钮，请稍后再试。", Toast.LENGTH_SHORT).show();
                overlay.refreshTarget();
                return true;
            }
            if (!refreshButton.isEnabled()) {
                Toast.makeText(this, "正在处理，请稍等再点刷新。", Toast.LENGTH_SHORT).show();
                overlay.refreshTarget();
                return true;
            }
            refreshButton.performClick();
            overlay.next();
            return true;
        }).build());
        steps.add(GuidedTutorialOverlay.Step.explanation(
            "浏览器打开",
            "cc/codex 也可以用浏览器打开本机地址。本教学只说明这件事，不会自动跳转浏览器。"
        ).build());
        steps.add(GuidedTutorialOverlay.Step.requiredClick(
            "返回菜单",
            "请点击返回菜单，继续后面的 OpenHouse 使用教学。",
            () -> returnMenuButton
        ).build());

        ccCodexTutorialOverlay = new GuidedTutorialOverlay(
            this,
            overlayContainer,
            steps,
            new GuidedTutorialOverlay.SimpleListener() {
                @Override
                public void onStepChanged(GuidedTutorialOverlay overlay,
                                          GuidedTutorialOverlay.Step step,
                                          int stepIndex) {
                    scrollTutorialTargetIntoView(overlay, step);
                }

                @Override
                public void onSkipped(GuidedTutorialOverlay overlay,
                                      GuidedTutorialOverlay.Step step) {
                    ccCodexTutorialOverlay = null;
                }

                @Override
                public void onFinished(GuidedTutorialOverlay overlay) {
                    ccCodexTutorialOverlay = null;
                }
            }
        );
        ccCodexTutorialOverlay.start();
    }

    private Button findCcCodexTutorialActionButton() {
        ServiceCard card = findCcCodexTutorialCard();
        return card == null ? null : card.tutorialActionButton;
    }

    private Button findCcCodexRefreshButton() {
        ServiceCard card = findCcCodexTutorialCard();
        return card == null ? null : card.refreshButton;
    }

    private ServiceCard findCcCodexTutorialCard() {
        for (ServiceCard card : serviceCards.values()) {
            if (card != null && isCcCodexTutorialService(card.serviceId)) {
                return card;
            }
        }
        return null;
    }

    private void maybeStartDesktopAppControlTutorial() {
        if (!isDesktopAppControlTutorial() || desktopAppTutorialStarted || isFinishing()) {
            return;
        }
        if (componentId.isEmpty() || tutorialServiceId.isEmpty()
            || isControlPlaneService(tutorialServiceId)) {
            setStatus("桌面教学目标无效，不能对控制中枢执行应用启停教学。");
            return;
        }
        ServiceCard card = findDesktopTutorialCard();
        if (card == null || isControlPlaneService(card.serviceId)) {
            return;
        }
        ViewGroup overlayContainer = findViewById(android.R.id.content);
        if (overlayContainer == null) {
            return;
        }

        desktopAppTutorialStarted = true;
        boolean initiallyStopped = isStoppedState(card.state);
        List<GuidedTutorialOverlay.Step> steps = new ArrayList<>();
        steps.add(GuidedTutorialOverlay.Step.explanation(
            "应用运行控制",
            initiallyStopped
                ? "这里仅控制刚才打开的应用服务。当前服务已经关闭；你将先亲自启动它作为准备，再亲自关闭并完成最后一次启动。"
                : "这里仅控制刚才打开的应用服务。接下来由你亲自关闭并重新启动，教学不会自动执行任何副作用。"
        ).build());

        if (initiallyStopped) {
            steps.add(GuidedTutorialOverlay.Step.sideEffectClick(
                "先启动应用服务",
                "当前服务已经关闭。请先点击“启动”，等待 service-manager 确认运行，再继续练习关闭。",
                this::findDesktopTutorialStartButton
            ).onTargetClick((overlay, step) -> {
                runDesktopTutorialAction(overlay, ACTION_START, false);
                return true;
            }).build());
        }

        steps.add(GuidedTutorialOverlay.Step.sideEffectClick(
            "真实关闭应用服务",
            "请点击箭头指向的“关闭”。系统会等待 service-manager 确认服务已经停止，确认前不能进入下一步。",
            this::findDesktopTutorialStopButton
        ).onTargetClick((overlay, step) -> {
            runDesktopTutorialAction(overlay, ACTION_STOP, false);
            return true;
        }).build());

        steps.add(GuidedTutorialOverlay.Step.sideEffectClick(
            "最终启动应用服务",
            "关闭已经确认。请再次点击“启动”，系统确认恢复运行后才算完成启停教学。",
            this::findDesktopTutorialStartButton
        ).onTargetClick((overlay, step) -> {
            runDesktopTutorialAction(overlay, ACTION_START, true);
            return true;
        }).build());

        steps.add(GuidedTutorialOverlay.Step.requiredClick(
            "返回应用",
            "服务已经重新运行。请点击“返回应用”，回到刚才打开的应用页面。",
            () -> returnMenuButton
        ).onTargetClick((overlay, step) -> {
            Button button = returnMenuButton;
            if (button == null || !button.isEnabled()) {
                Toast.makeText(this, "正在处理，请稍后返回应用。", Toast.LENGTH_SHORT).show();
                overlay.refreshTarget();
                return true;
            }
            button.performClick();
            return true;
        }).build());

        desktopAppTutorialOverlay = new GuidedTutorialOverlay(
            this,
            overlayContainer,
            steps,
            new GuidedTutorialOverlay.SimpleListener() {
                @Override
                public void onStepChanged(GuidedTutorialOverlay overlay,
                                          GuidedTutorialOverlay.Step step,
                                          int stepIndex) {
                    scrollTutorialTargetIntoView(overlay, step);
                }

                @Override
                public void onSkipped(GuidedTutorialOverlay overlay,
                                      GuidedTutorialOverlay.Step step) {
                    desktopAppTutorialOverlay = null;
                }

                @Override
                public void onFinished(GuidedTutorialOverlay overlay) {
                    desktopAppTutorialOverlay = null;
                }
            }
        );
        desktopAppTutorialOverlay.start();
    }

    private void runDesktopTutorialAction(GuidedTutorialOverlay overlay, String action,
                                          boolean finalStart) {
        String cleanAction = ServiceManagerClient.sanitizeAction(action);
        ServiceCard card = findDesktopTutorialCard();
        String serviceId = card == null ? "" : ServiceManagerClient.sanitizeServiceId(card.serviceId);
        Button actionButton = ACTION_STOP.equals(cleanAction)
            ? (card == null ? null : card.stopButton)
            : (card == null ? null : card.startButton);
        if ((!ACTION_STOP.equals(cleanAction) && !ACTION_START.equals(cleanAction))
            || serviceId.isEmpty() || isControlPlaneService(serviceId)) {
            setStatus("桌面教学动作或服务无效。");
            return;
        }
        if (desktopTutorialActionInFlight || actionButton == null || !actionButton.isEnabled()) {
            Toast.makeText(this, "服务正在处理，请等待状态确认。", Toast.LENGTH_SHORT).show();
            if (overlay != null) {
                overlay.refreshTarget();
            }
            return;
        }

        desktopTutorialActionInFlight = true;
        setBusy(true);
        setStatus("正在请求 " + serviceId + " " + actionLabel(cleanAction) + "，并等待真实状态确认...");
        hideMaintenanceFallback();
        backgroundExecutor.execute(() -> {
            ServiceManagerActionResult result;
            try {
                result = controlClient.runAction(serviceId, cleanAction);
            } catch (Exception e) {
                Logger.logStackTraceWithMessage(LOG_TAG,
                    "Desktop tutorial service action failed: " + serviceId, e);
                runOnUiThread(() -> finishDesktopTutorialActionWithError(
                    serviceId + " " + actionLabel(cleanAction) + "失败。\n" + safeErrorMessage(e)));
                return;
            }
            if (!result.success()) {
                runOnUiThread(() -> finishDesktopTutorialActionWithError(
                    formatActionResult(serviceId, cleanAction, result)));
                return;
            }

            ServiceSnapshot lastSnapshot = null;
            String lastError = "";
            boolean confirmed = false;
            for (int attempt = 0; attempt < DESKTOP_TUTORIAL_STATUS_POLL_ATTEMPTS; attempt++) {
                try {
                    lastSnapshot = snapshotFromStatus(serviceId, controlClient.getStatus(serviceId));
                    if (lastSnapshot.success && desktopTutorialStateMatches(cleanAction, lastSnapshot.state)) {
                        confirmed = true;
                        break;
                    }
                    lastError = firstNonBlank(lastSnapshot.message,
                        "当前状态：" + stateLabel(lastSnapshot.state));
                } catch (Exception e) {
                    lastError = safeErrorMessage(e);
                }
                if (attempt + 1 < DESKTOP_TUTORIAL_STATUS_POLL_ATTEMPTS) {
                    try {
                        Thread.sleep(DESKTOP_TUTORIAL_STATUS_POLL_DELAY_MS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        lastError = "等待状态确认时被中断。";
                        break;
                    }
                }
            }

            ServiceSnapshot finalSnapshot = lastSnapshot;
            boolean finalConfirmed = confirmed;
            String finalError = lastError;
            runOnUiThread(() -> {
                ServiceCard currentCard = findDesktopTutorialCard();
                if (currentCard != null && finalSnapshot != null) {
                    updateCard(currentCard, finalSnapshot);
                }
                desktopTutorialActionInFlight = false;
                setBusy(false);
                if (!finalConfirmed) {
                    setStatus(serviceId + " " + actionLabel(cleanAction)
                        + "已提交，但未在等待时间内确认目标状态。\n"
                        + firstNonBlank(finalError, "请检查服务状态后重试。"));
                    return;
                }
                setStatus(serviceId + " 已确认" + (ACTION_STOP.equals(cleanAction)
                    ? "停止。"
                    : "恢复运行。"));
                if (ACTION_STOP.equals(cleanAction)) {
                    desktopTutorialStopConfirmed = true;
                } else if (ACTION_START.equals(cleanAction) && finalStart) {
                    desktopTutorialFinalStartConfirmed = true;
                }
                desktopTutorialCycleCompleted = desktopTutorialStopConfirmed
                    && desktopTutorialFinalStartConfirmed;
                if (desktopAppTutorialOverlay == overlay
                    && overlay != null && overlay.isShowing()) {
                    overlay.next();
                }
            });
        });
    }

    private void finishDesktopTutorialActionWithError(String message) {
        desktopTutorialActionInFlight = false;
        setBusy(false);
        setStatus(message);
        showMaintenanceFallback();
    }

    private void finishDesktopTutorialAndReturn() {
        if (!desktopTutorialStopConfirmed || !desktopTutorialFinalStartConfirmed
            || !desktopTutorialCycleCompleted) {
            Toast.makeText(this, "请先完成关闭和启动教学。", Toast.LENGTH_SHORT).show();
            return;
        }
        Intent result = new Intent();
        result.putExtra(EXTRA_SERVICE_CONTROL_TUTORIAL_COMPLETED, true);
        setResult(RESULT_OK, result);
        finish();
    }

    private boolean desktopTutorialStateMatches(String action, String state) {
        return ACTION_STOP.equals(action) ? isStoppedState(state) : isRunningState(state);
    }

    private Button findDesktopTutorialStartButton() {
        ServiceCard card = findDesktopTutorialCard();
        return card == null ? null : card.startButton;
    }

    private Button findDesktopTutorialStopButton() {
        ServiceCard card = findDesktopTutorialCard();
        return card == null ? null : card.stopButton;
    }

    private ServiceCard findDesktopTutorialCard() {
        String target = ServiceManagerClient.sanitizeServiceId(
            firstNonBlank(resolvedTutorialServiceId, tutorialServiceId));
        return target.isEmpty() ? null : serviceCards.get(target);
    }

    private void scrollTutorialTargetIntoView(GuidedTutorialOverlay overlay,
                                              GuidedTutorialOverlay.Step step) {
        if (rootScrollView == null || contentView == null || step == null
            || step.targetSupplier == null) {
            return;
        }
        rootScrollView.post(() -> {
            View target = step.targetSupplier.getTargetView();
            if (target == null) {
                if (overlay != null) {
                    overlay.refreshTarget();
                }
                return;
            }
            int targetTop = topRelativeToContent(target);
            rootScrollView.smoothScrollTo(0, Math.max(0, targetTop - dp(32)));
            if (overlay != null) {
                rootScrollView.postDelayed(overlay::refreshTarget, 260);
            }
        });
    }

    private int topRelativeToContent(View target) {
        int top = 0;
        View current = target;
        while (current != null && current != contentView) {
            top += current.getTop();
            if (!(current.getParent() instanceof View)) {
                break;
            }
            current = (View) current.getParent();
        }
        return top;
    }

    private void returnToOpenHouseMenu() {
        Intent intent = new Intent(this, OpenHouseHomeActivity.class);
        intent.putExtra("openhouse_page", "home");
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(intent);
        finish();
    }

    private void updateControlPlaneStatusFromSnapshots(List<ServiceSnapshot> snapshots) {
        ServiceSnapshot controlPlane = findControlPlaneSnapshot(snapshots);
        if (controlPlane == null) {
            if (allMode) {
                int serviceCount = snapshots == null ? 0 : snapshots.size();
                setControlPlaneStatus("控制中枢：已连接（service-manager）\n已读取 " + serviceCount
                    + " 个服务。service-manager 是控制进程，不要求出现在自己的服务列表中。");
                hideMaintenanceFallback();
            }
            return;
        }
        String state = stateLabel(controlPlane.state);
        String message = firstNonBlank(controlPlane.message, "-");
        setControlPlaneStatus("控制中枢：" + state + "（service-manager）\n消息：" + message);
        if (!controlPlane.success || isProblemState(controlPlane.state)) {
            showMaintenanceFallback();
        }
    }

    private ServiceSnapshot findControlPlaneSnapshot(List<ServiceSnapshot> snapshots) {
        if (snapshots == null) {
            return null;
        }
        for (ServiceSnapshot snapshot : snapshots) {
            if (snapshot != null && isControlPlaneService(snapshot.id)) {
                return snapshot;
            }
        }
        return null;
    }

    private boolean isProblemState(String state) {
        String normalized = safeTrim(state).toLowerCase(Locale.US);
        return normalized.isEmpty()
            || "unknown".equals(normalized)
            || "error".equals(normalized)
            || "failed".equals(normalized)
            || "unhealthy".equals(normalized)
            || "missing".equals(normalized)
            || "not-installed".equals(normalized)
            || "not_installed".equals(normalized);
    }

    private void setControlPlaneStatus(String text) {
        if (controlPlaneStatusView != null) {
            controlPlaneStatusView.setText(trimForStatus(ServiceManagerRedactor.redact(text)));
        }
    }

    private boolean isCcCodexTutorial() {
        return TUTORIAL_CC_CODEX_CONTROL.equals(tutorialMode);
    }

    private boolean isDesktopAppControlTutorial() {
        return TUTORIAL_DESKTOP_APP_CONTROL.equals(tutorialMode);
    }

    private boolean isCcCodexTutorialService(String serviceId) {
        if (!isCcCodexTutorial()) {
            return false;
        }
        String normalized = safeTrim(serviceId).toLowerCase(Locale.US).replace('_', '-');
        return normalized.equals("cloudcli")
            || normalized.equals("cloud-cli")
            || normalized.equals("cc-codex")
            || normalized.equals("claude-code-ui")
            || normalized.equals("claudecodeui")
            || normalized.contains("cloudcli")
            || normalized.contains("cloud-cli")
            || normalized.contains("cc-codex")
            || normalized.contains("claude-code");
    }

    private boolean isRunningState(String state) {
        String normalized = safeTrim(state).toLowerCase(Locale.US);
        return normalized.equals("running")
            || normalized.equals("active")
            || normalized.equals("up")
            || normalized.equals("healthy")
            || normalized.equals("ready");
    }

    private boolean isStoppedState(String state) {
        String normalized = safeTrim(state).toLowerCase(Locale.US);
        return normalized.equals("stopped")
            || normalized.equals("inactive")
            || normalized.equals("down");
    }

    private String resultLabel(ServiceManagerActionResult result) {
        String message = safeTrim(result.message());
        if (message.isEmpty()) {
            return result.success() ? "已提交" : "失败";
        }
        return (result.success() ? "已提交，" : "失败，") + message;
    }

    private String formatIgnoredServices(List<String> missingServiceIds) {
        if (missingServiceIds == null || missingServiceIds.isEmpty()) {
            return "\n如果启动、关闭或状态刷新失败，请点击“启动运行中枢”。";
        }
        return "\n已忽略当前未注册的服务：" + joinValues(missingServiceIds)
            + "\n如果需要这些服务，请点击“启动运行中枢”。";
    }

    private String formatMissingServices(List<String> missingServiceIds) {
        if (missingServiceIds == null || missingServiceIds.isEmpty()) {
            return "";
        }
        return "\n未找到：" + joinValues(missingServiceIds);
    }

    private String joinValues(List<String> values) {
        if (values == null || values.isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        for (String value : values) {
            String text = safeTrim(value);
            if (text.isEmpty()) {
                continue;
            }
            if (builder.length() > 0) {
                builder.append(", ");
            }
            builder.append(text);
        }
        return builder.toString();
    }

    private String formatLogs(String serviceId, List<ServiceManagerLogEntry> logs) {
        StringBuilder builder = new StringBuilder();
        builder.append(serviceId).append(" 最近日志：");
        if (logs == null || logs.isEmpty()) {
            builder.append("\n暂无日志。");
            return builder.toString();
        }
        int start = Math.max(0, logs.size() - 30);
        for (int i = start; i < logs.size(); i++) {
            ServiceManagerLogEntry entry = logs.get(i);
            builder.append('\n')
                .append(safeTrim(entry.time()))
                .append(' ')
                .append(safeTrim(entry.stream()))
                .append(" | ")
                .append(ServiceManagerRedactor.redact(safeTrim(entry.message())));
        }
        return trimForStatus(builder.toString());
    }

    private void setBusy(boolean busy) {
        setButtonsEnabled(contentView, !busy);
    }

    private void setButtonsEnabled(View view, boolean enabled) {
        if (view == null) {
            return;
        }
        if (view instanceof Button) {
            boolean lockedDisabled = LOCKED_DISABLED_BUTTON_TAG.equals(view.getTag());
            boolean buttonEnabled = enabled && !lockedDisabled;
            view.setEnabled(buttonEnabled);
            view.setAlpha(buttonEnabled ? 1.0f : 0.72f);
            return;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                setButtonsEnabled(group.getChildAt(i), enabled);
            }
        }
    }

    private void setStatus(String text) {
        if (statusView != null) {
            statusView.setText(trimForStatus(ServiceManagerRedactor.redact(text)));
        }
    }

    private LinearLayout panel() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackgroundResource(R.drawable.panel_bg);
        panel.setPadding(dp(16), dp(16), dp(16), dp(16));
        return panel;
    }

    private TextView sectionTitle(String text) {
        TextView textView = new TextView(this);
        textView.setText(text);
        textView.setTextColor(ContextCompat.getColor(this, R.color.textPrimary));
        textView.setTextSize(17);
        textView.setTypeface(textView.getTypeface(), Typeface.BOLD);
        return textView;
    }

    private TextView bodyText(String text) {
        TextView textView = new TextView(this);
        textView.setText(text);
        textView.setTextColor(ContextCompat.getColor(this, R.color.textSecondary));
        textView.setTextSize(14);
        textView.setLineSpacing(dp(2), 1.0f);
        return textView;
    }

    private Button actionButton(String text, View.OnClickListener listener) {
        Button button = new Button(this);
        button.setAllCaps(false);
        button.setText(text);
        button.setTextSize(13);
        button.setOnClickListener(listener);
        return button;
    }

    private void addButtonRow(LinearLayout parent, Button first, Button second) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.addView(first, new LinearLayout.LayoutParams(0, dp(44), 1));
        LinearLayout.LayoutParams secondParams = new LinearLayout.LayoutParams(0, dp(44), 1);
        secondParams.leftMargin = dp(8);
        row.addView(second, secondParams);
        parent.addView(row, topMarginParams(8));
    }

    private void addFullWidthButton(LinearLayout parent, Button button) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(44));
        params.topMargin = dp(8);
        parent.addView(button, params);
    }

    private void updateOpenButton(Button button, String serviceId, String url) {
        if (button == null) {
            return;
        }
        boolean canOpen = isOpenHouseWebService(serviceId) || !normalizeOpenUrl(url).isEmpty();
        button.setTag(canOpen ? null : LOCKED_DISABLED_BUTTON_TAG);
        button.setEnabled(canOpen);
        button.setAlpha(canOpen ? 1.0f : 0.72f);
    }

    private String currentServiceUrl(String serviceId) {
        String cleanServiceId = ServiceManagerClient.sanitizeServiceId(serviceId);
        ServiceCard card = cleanServiceId.isEmpty() ? null : serviceCards.get(cleanServiceId);
        return firstNonBlank(card == null ? "" : card.url, componentUrl);
    }

    private LinearLayout.LayoutParams fullWidthParams(int topMarginDp) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(topMarginDp);
        return params;
    }

    private LinearLayout.LayoutParams topMarginParams(int topMarginDp) {
        return fullWidthParams(topMarginDp);
    }

    private String safeComponentTitle() {
        if (!componentTitle.isEmpty()) {
            return componentTitle;
        }
        if (!componentId.isEmpty()) {
            return componentId;
        }
        return "组件";
    }

    private void addServiceId(List<String> out, String rawServiceId) {
        String serviceId = ServiceManagerClient.sanitizeServiceId(rawServiceId);
        if (!serviceId.isEmpty() && !out.contains(serviceId)) {
            out.add(serviceId);
        }
    }

    private String actionLabel(String action) {
        switch (action) {
            case ACTION_START:
                return "启动";
            case ACTION_STOP:
                return "关闭";
            case ACTION_RESTART:
                return "重启";
            case ACTION_REPAIR:
                return "修复";
            default:
                return action;
        }
    }

    private String stateLabel(String state) {
        String value = safeTrim(state);
        if (value.isEmpty()) {
            return "未知";
        }
        switch (value.toLowerCase(Locale.US)) {
            case "running":
            case "active":
            case "up":
                return "运行中";
            case "stopped":
            case "inactive":
            case "down":
                return "已停止";
            case "unknown":
                return "未知";
            case "starting":
                return "启动中";
            case "stopping":
                return "关闭中";
            case "restarting":
                return "重启中";
            case "repairing":
                return "修复中";
            case "error":
            case "failed":
            case "unhealthy":
                return "异常";
            case "healthy":
            case "ready":
                return "正常";
            case "disabled":
                return "已禁用";
            case "enabled":
                return "已启用";
            case "missing":
                return "缺失";
            case "installing":
                return "安装中";
            case "not-installed":
            case "not_installed":
                return "未安装";
            default:
                return value;
        }
    }

    private String normalizeOpenUrl(String value) {
        String trimmed = safeTrim(value);
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            return trimmed;
        }
        return "";
    }

    private String safeErrorMessage(Exception e) {
        String message = e == null ? "" : safeTrim(e.getMessage());
        if (message.isEmpty() && e != null) {
            message = e.getClass().getSimpleName();
        }
        return ServiceManagerRedactor.redact(message);
    }

    private String formatMaintainerOutput(String output) {
        String cleanOutput = ServiceManagerRedactor.redact(safeTrim(output));
        return cleanOutput.isEmpty() ? "" : "\n\n最近输出：\n" + cleanOutput;
    }

    private String firstNonBlank(String first, String fallback) {
        String cleanFirst = safeTrim(first);
        return cleanFirst.isEmpty() ? safeTrim(fallback) : cleanFirst;
    }

    private void appendLine(StringBuilder builder, String line) {
        if (builder.length() > 0) {
            builder.append('\n');
        }
        builder.append(line);
    }

    private String safeTrim(String value) {
        return value == null ? "" : value.trim();
    }

    private String trimForStatus(String value) {
        String text = ServiceManagerRedactor.redact(value == null ? "" : value);
        if (text.length() <= STATUS_TEXT_LIMIT) {
            return text;
        }
        return text.substring(0, STATUS_TEXT_LIMIT) + "\n...输出过长，已截断。";
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private static final class ServiceSnapshot {
        final String id;
        final String displayName;
        final String provider;
        final String state;
        final int pid;
        final String message;
        final String url;
        final boolean success;

        ServiceSnapshot(String id, String displayName, String provider, String state, int pid, String message, String url, boolean success) {
            this.id = id;
            this.displayName = displayName;
            this.provider = provider;
            this.state = state;
            this.pid = pid;
            this.message = message;
            this.url = url;
            this.success = success;
        }
    }

    private static final class ServiceCard {
        final String serviceId;
        final LinearLayout root;
        final TextView titleView;
        final TextView detailView;
        final TextView logView;
        final Button openButton;
        final Button tutorialActionButton;
        final Button startButton;
        final Button stopButton;
        final Button refreshButton;
        String state;
        String url;

        ServiceCard(String serviceId, LinearLayout root, TextView titleView, TextView detailView, TextView logView,
                    Button openButton, Button tutorialActionButton, Button startButton, Button stopButton,
                    Button refreshButton) {
            this.serviceId = serviceId;
            this.root = root;
            this.titleView = titleView;
            this.detailView = detailView;
            this.logView = logView;
            this.openButton = openButton;
            this.tutorialActionButton = tutorialActionButton;
            this.startButton = startButton;
            this.stopButton = stopButton;
            this.refreshButton = refreshButton;
            this.state = "";
            this.url = "";
        }
    }
}
