package com.termux.app.openhouse.onboarding;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.material.button.MaterialButton;
import com.termux.R;
import com.termux.app.openhouse.OpenHouseInstallController;
import com.termux.app.openhouse.OpenHouseInstallState;
import com.termux.app.openhouse.OpenHouseStatus;

public final class OpenHouseOnboardingOverlay {

    private static final String PREFS_NAME = "openhouse_onboarding";
    private static final String KEY_STEP = "step";
    private static final String KEY_CURRENT_STEP = "current_step";
    private static final String KEY_BATTERY_SKIPPED = "battery_skipped";
    private static final String KEY_GUIDE_DISMISSED = "guide_dismissed";

    private static final int COLOR_PRIMARY = Color.rgb(30, 111, 82);
    private static final int COLOR_PRIMARY_DARK = Color.rgb(21, 95, 67);
    private static final int COLOR_PRIMARY_SOFT = Color.rgb(237, 247, 241);
    private static final int COLOR_TEXT = Color.rgb(23, 33, 28);
    private static final int COLOR_MUTED = Color.rgb(95, 108, 101);
    private static final int COLOR_BORDER = Color.rgb(202, 213, 204);
    private static final int COLOR_WARN = Color.rgb(143, 55, 42);
    private static final int COLOR_GOLD = Color.rgb(240, 216, 138);

    private final Activity activity;
    private final SharedPreferences preferences;
    private final OpenHouseOnboardingRuntime runtime;
    private final Callbacks callbacks;

    private final FrameLayout rootView;
    private final LinearLayout progressContainer;
    private final TextView kickerView;
    private final TextView titleView;
    private final TextView bodyView;
    private final LinearLayout contentView;
    private final LinearLayout actionsView;
    private final MaterialButton previousButton;
    private final TextView stepCountView;
    private final MaterialButton nextButton;
    private final TextView skipRiskView;
    private final MaterialButton skipButton;

    private OpenHouseInstallState installState = OpenHouseInstallState.idle();
    private OpenHouseStatus status = OpenHouseStatus.checking();
    private Step currentStep;
    private boolean initialRevealElapsed;
    private boolean statusLoading;
    private boolean actionBusy;
    private boolean autoStartInstallRequested;

    private final OpenHouseInstallController.Listener installListener = state -> {
        installState = state;
        if (state.completed) {
            refreshStatus();
            if (currentStep == Step.WAITING_INSTALL) {
                setCurrentStep(Step.LAUNCH_CONFIG);
            }
        } else {
            render();
        }
    };

    public interface Callbacks {
        void onOpenDetail();
        void onStartTerminalTutorial(boolean restartEntrySession);
        void onEnterTerminal(boolean restartEntrySession);
    }

    public OpenHouseOnboardingOverlay(Activity activity, ViewGroup container, Callbacks callbacks) {
        this.activity = activity;
        this.preferences = activity.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        this.runtime = new OpenHouseOnboardingRuntime(activity);
        this.callbacks = callbacks;

        rootView = (FrameLayout) LayoutInflater.from(activity)
            .inflate(R.layout.openhouse_onboarding_overlay, container, false);
        container.addView(rootView);

        progressContainer = rootView.findViewById(R.id.openhouse_onboarding_progress_container);
        kickerView = rootView.findViewById(R.id.openhouse_onboarding_kicker);
        titleView = rootView.findViewById(R.id.openhouse_onboarding_title);
        bodyView = rootView.findViewById(R.id.openhouse_onboarding_body);
        contentView = rootView.findViewById(R.id.openhouse_onboarding_content);
        actionsView = rootView.findViewById(R.id.openhouse_onboarding_actions);
        previousButton = rootView.findViewById(R.id.openhouse_onboarding_previous);
        stepCountView = rootView.findViewById(R.id.openhouse_onboarding_step_count);
        nextButton = rootView.findViewById(R.id.openhouse_onboarding_next);
        skipRiskView = rootView.findViewById(R.id.openhouse_onboarding_skip_risk);
        skipButton = rootView.findViewById(R.id.openhouse_onboarding_skip);

        currentStep = readSavedStep();
        setupStaticActions();
        styleNavigationButton(previousButton);
        styleNavigationButton(nextButton);
        styleDangerButton(skipButton);
    }

    public void attach() {
        installState = runtime.getInstallState();
        runtime.getInstallController().addListener(installListener);
        refreshStatus();
        rootView.postDelayed(() -> {
            initialRevealElapsed = true;
            render();
        }, 280);
    }

    public void onResume() {
        installState = runtime.getInstallState();
        refreshStatus();
    }

    public void revealFromMenu() {
        preferences.edit().putBoolean(KEY_GUIDE_DISMISSED, false).apply();
        initialRevealElapsed = true;
        installState = runtime.getInstallState();
        refreshStatus();
        render();
    }

    public void destroy() {
        runtime.getInstallController().removeListener(installListener);
        runtime.destroy();
    }

    public boolean isShowing() {
        return rootView.getVisibility() == View.VISIBLE;
    }

    private void setupStaticActions() {
        previousButton.setOnClickListener(v -> {
            Step previous = getPreviousStep(currentStep);
            if (previous != null) {
                setCurrentStep(previous);
            }
        });

        nextButton.setOnClickListener(v -> {
            Step next = getNextStep(currentStep);
            if (next != null) {
                setCurrentStep(next);
            }
        });

        skipButton.setOnClickListener(v -> confirmSkipCurrentStep());
    }

    private void refreshStatus() {
        if (statusLoading) {
            return;
        }

        statusLoading = true;
        runtime.refreshStatus(loadedStatus -> {
            statusLoading = false;
            status = loadedStatus;
            normalizeCurrentStep();
            maybeAutoStartInstall();
            render();
        });
    }

    private void render() {
        normalizeCurrentStep();

        if (!initialRevealElapsed) {
            return;
        }

        if (!shouldShowGuide()) {
            rootView.setVisibility(View.GONE);
            return;
        }

        rootView.setVisibility(View.VISIBLE);
        contentView.removeAllViews();
        actionsView.removeAllViews();

        renderProgress();
        kickerView.setText((currentStep.ordinal() + 1) + "/" + Step.values().length + " " + currentStep.label);
        titleView.setText(currentStep.title);
        bodyView.setText(currentStep.body);

        switch (currentStep) {
            case PERMISSION:
                renderPermissionStep();
                break;
            case INSTALL:
                renderInstallStep();
                break;
            case WAITING_INSTALL:
                renderWaitingInstallStep();
                break;
            case LAUNCH_CONFIG:
                renderLaunchConfigStep();
                break;
            default:
                break;
        }

        renderNavigation();
        renderSkipPanel();
    }

    private void renderProgress() {
        progressContainer.removeAllViews();
        Step[] steps = Step.values();
        int rowCount = steps.length <= 4 ? 1 : 2;
        for (int rowIndex = 0; rowIndex < rowCount; rowIndex++) {
            LinearLayout row = new LinearLayout(activity);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER);
            progressContainer.addView(row, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

            int start = rowIndex == 0 ? 0 : 4;
            int end = rowIndex == 0 ? Math.min(4, steps.length) : steps.length;
            for (int i = start; i < end; i++) {
                TextView chip = new TextView(activity);
                chip.setText((i + 1) + ". " + steps[i].label);
                chip.setSingleLine(true);
                chip.setGravity(Gravity.CENTER);
                chip.setTextSize(11);
                chip.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
                chip.setIncludeFontPadding(false);
                chip.setPadding(dp(6), dp(5), dp(6), dp(5));
                applyProgressChipStyle(chip, i);

                LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
                params.setMargins(i == start ? 0 : dp(3), rowIndex == 0 ? 0 : dp(6), i == end - 1 ? 0 : dp(3), 0);
                row.addView(chip, params);
            }
        }
    }

    private void renderPermissionStep() {
        boolean ready = isBatteryReady();
        addStatusCard(
            ready ? "后台权限已确认" : "等待系统授权",
            isBatterySkipped()
                ? "已手动跳过检测；如果系统实际未允许，安装可能被中断。"
                : "先允许 openhouse 忽略电池优化，再进入启动管理/后台保活设置。启动管理无法由 Android 统一检测，返回后可继续。"
        );
        addPrimaryHeroButton(
            ready ? "后台运行权限已开启" : "开启后台运行权限",
            true,
            ready ? R.drawable.ic_openhouse_toggle_on : R.drawable.ic_openhouse_toggle_off,
            v -> runtime.openBatteryOptimizationSettings());
        addStatusCard(
            "启动管理/后台保活",
            "请在厂商设置中允许自启动、后台运行或锁定后台。这个权限没有统一检测 API，因此这里只提供入口，不会阻塞初始化。"
        );
        addActionButton("打开启动管理/后台保活设置", true, true, v -> runtime.openStartupPermissionSettings());
        addActionButton(ready ? "重新检查状态" : "我已允许，重新检查", true, true, v -> refreshStatus());
    }

    private void renderInstallStep() {
        if (installState.failed) {
            renderInstallFailedStep();
            return;
        }

        String title = getInstallProgressTitle();
        addStatusCard(title, "将安装 Ubuntu、Node.js、pi-agent、pi-web、service-manager、openhouse-connect 和 SmallPhone 兼容服务。");
        addStatusCard("网络提醒", "初始化安装预计会下载约 500M 的文件内容，推荐在 Wi-Fi 网络下进行。");
        addProgressBar(getDisplayedInstallPercent(), getDisplayedInstallDetail());
        addReadingGuide(false, true);
        boolean canStart = isBatteryReady() && !installState.running && !isInstallDone();
        addPrimaryHeroButton(
            isInstallDone() ? "安装已完成" : installState.running ? "正在安装中" : "开始安装",
            canStart,
            R.drawable.ic_openhouse_play,
            v -> startInstall());
        addActionButton("查看详细进度", true, false, v -> callbacks.onOpenDetail());
        addForceRestartInstallButtonIfNeeded();
    }

    private void renderWaitingInstallStep() {
        if (installState.failed) {
            renderInstallFailedStep();
            return;
        }

        addStatusCard(
            "正在安装核心运行环境",
            "请保持应用在后台可运行。这里不会要求你填写模型或 Key，安装完成后再按需要配置。"
        );
        addStatusCard(
            "安装完成后怎么用",
            "service-manager 会负责后台服务；菜单侧边栏里 SmallPhone、pi-agent、运行控制和终端是基础入口。Codex、Claude Code 和 CloudCLI 稍后由 pi-agent 引导安装配置。"
        );
        addProgressBar(getDisplayedInstallPercent(), getDisplayedInstallDetail());
        addReadingGuide(true, false);
        addActionButton("查看详细进度", true, false, v -> callbacks.onOpenDetail());
        addForceRestartInstallButtonIfNeeded();
    }

    private void renderInstallFailedStep() {
        addStatusCard(
            "安装没有完成",
            installState.safeError.isEmpty()
                ? getDisplayedInstallDetail()
                : installState.safeError
        );
        addStatusCard(
            "选择重试方式",
            "常规重试会沿用默认路径和已有缓存；国内网络重试会把安装任务切到固定国内镜像路径。两种方式都只继续安装流程，不会删除用户数据、模型配置或工作目录。"
        );
        addProgressBar(getDisplayedInstallPercent(), getDisplayedInstallDetail());
        if (!installState.logPath.isEmpty()) {
            addStatusCard("日志位置", installState.logPath);
        }
        addActionButton("常规重试", true, true,
            v -> startInstallAsync(true, OpenHouseInstallState.RetryMode.GENERAL));
        addActionButton("国内网络重试", true, false,
            v -> startInstallAsync(true, OpenHouseInstallState.RetryMode.CN));
        addActionButton("查看详细进度", true, false, v -> callbacks.onOpenDetail());
        addForceRestartInstallButtonIfNeeded();
    }

    private void renderLaunchConfigStep() {
        addStatusCard(
            "核心运行栈已安装完成",
            "Ubuntu、Node.js、pi-agent、pi-web、service-manager、openhouse-connect 和 SmallPhone 兼容服务已作为默认核心栈就绪。"
        );
        addStatusCard(
            "service-manager 接管运行期",
            "首次安装阶段已经完成。之后后台服务的启动、停止和健康检查由 service-manager 管理。"
        );
        addStatusCard(
            "终端只是备用入口",
            "一般用户不需要直接使用终端。后续需要命令行时，可以从菜单单独查看终端详细教学，再回到菜单。"
        );
        addStatusCard(
            "pi-agent 的作用",
            "pi-agent 是首次配置助手和插件入口。接下来请进入 pi-agent，让它按内置文档引导你配置模型、安装 Codex、Claude Code、CloudCLI 或 Hermes。"
        );
        addStatusCard(
            "菜单里的大服务",
            "进入使用教学后先点击菜单。教学会带你认识 pi-agent、运行控制和终端；cc/codex 未安装时会提示先去 pi-agent 完成安装配置。"
        );

        addActionButton("进入使用教学", isSetupComplete(), true, v -> {
            dismissGuide();
            callbacks.onStartTerminalTutorial(true);
        });
        addActionButton("进入 Ubuntu 终端", isSetupComplete(), false, v -> {
            dismissGuide();
            callbacks.onEnterTerminal(true);
        });
    }

    private void renderNavigation() {
        Step previous = getPreviousStep(currentStep);
        Step next = getNextStep(currentStep);
        previousButton.setEnabled(previous != null);
        nextButton.setEnabled(next != null);
        stepCountView.setText((currentStep.ordinal() + 1) + " / " + Step.values().length);
    }

    private void renderSkipPanel() {
        ForceSkipInfo info = getForceSkipInfo(currentStep);
        skipRiskView.setText(info.risk);
        skipButton.setText(info.label);
        skipButton.setEnabled(info.enabled && !actionBusy);
    }

    private void startInstall() {
        if (!isBatteryReady()) {
            Toast.makeText(activity, "请先完成后台权限，或确认风险后跳过当前屏。", Toast.LENGTH_LONG).show();
            return;
        }
        if (actionBusy) {
            return;
        }

        startInstallAsync(true, OpenHouseInstallState.RetryMode.GENERAL);
    }

    private void maybeAutoStartInstall() {
        if (autoStartInstallRequested
            || actionBusy
            || !isBatteryReady()
            || isInstallStarted()
            || isInstallDone()) {
            return;
        }
        if (currentStep != Step.INSTALL && currentStep != Step.WAITING_INSTALL) {
            return;
        }

        autoStartInstallRequested = true;
        startInstallAsync(false, OpenHouseInstallState.RetryMode.GENERAL);
    }

    private void startInstallAsync(boolean showToast, OpenHouseInstallState.RetryMode retryMode) {
        actionBusy = true;
        installState = new OpenHouseInstallState(
            installState.failed || installState.attempt > 0
                ? OpenHouseInstallState.Status.RETRYING
                : OpenHouseInstallState.Status.RUNNING,
            Math.max(1, getDisplayedInstallPercent()),
            "正在启动安装",
            retryMode == OpenHouseInstallState.RetryMode.CN
                ? "正在启动国内网络重试，请保持应用可后台运行。"
                : "正在启动一键初始化任务，请保持应用可后台运行。",
            "manifest_full",
            retryMode,
            Math.max(1, installState.attempt + 1),
            installState.logPath,
            ""
        );
        currentStep = Step.WAITING_INSTALL;
        persistStep();
        render();
        Thread starter = new Thread(() -> {
            boolean started = false;
            OpenHouseInstallState current;
            String message;
            try {
                started = runtime.getInstallController().startOneClickInstall(retryMode);
                current = runtime.getInstallState();
                boolean usable = !current.failed && (started || current.running || current.completed);
                if (current.failed) {
                    message = current.safeError.isEmpty()
                        ? "初始化启动失败，请进入详细进度查看日志。"
                        : current.safeError;
                } else if (current.completed) {
                    message = "核心运行环境已安装完成。";
                } else if (current.running) {
                    message = started ? "安装已开始，请等待完成。" : "安装已经在运行。";
                } else {
                    message = usable ? "安装控制器已检查状态。" : "安装任务尚未启动，请进入详细进度查看状态。";
                }
            } catch (Exception e) {
                current = runtime.getInstallState();
                message = "无法启动初始化任务：" + safeMessage(e);
            }

            OpenHouseInstallState finalCurrent = current;
            String finalMessage = message;
            boolean finalStarted = started;
            rootView.post(() -> {
                actionBusy = false;
                installState = finalCurrent;
                boolean success = !finalCurrent.failed && (finalStarted || finalCurrent.running || finalCurrent.completed);
                if (showToast || !success) {
                    Toast.makeText(activity, finalMessage, success ? Toast.LENGTH_SHORT : Toast.LENGTH_LONG).show();
                }
                refreshStatus();
            });
        }, "openhouse-install-start");
        starter.start();
    }

    private void confirmForceRestartInstall() {
        if (actionBusy) return;

        new AlertDialog.Builder(activity)
            .setTitle("强制重试当前阶段")
            .setMessage("只有确认安装已经长时间没有变化时才使用。\n\n这会终止当前卡住的一键初始化任务，清理运行标记，然后重新触发安装。不会删除用户数据、模型配置或工作目录。已完成的阶段会按状态检测跳过，从第一个未完成阶段继续。")
            .setNegativeButton("取消", null)
            .setPositiveButton("强制重启并继续", (dialog, which) -> forceRestartInstall())
            .show();
    }

    private void forceRestartInstall() {
        if (actionBusy) return;

        actionBusy = true;
        render();
        OpenHouseInstallState.RetryMode retryMode = installState.retryMode == null
            ? OpenHouseInstallState.RetryMode.GENERAL
            : installState.retryMode;
        Thread starter = new Thread(() -> {
            boolean started = runtime.getInstallController().forceRestartOneClickInstall(retryMode);
            OpenHouseInstallState current = runtime.getInstallState();
            rootView.post(() -> {
                actionBusy = false;
                installState = current;
                Toast.makeText(
                    activity,
                    started ? "已强制重试当前阶段，会从第一个未完成阶段继续。" : "无法重新启动初始化，请进入详细进度查看日志。",
                    started ? Toast.LENGTH_SHORT : Toast.LENGTH_LONG
                ).show();
                setCurrentStep(Step.WAITING_INSTALL);
                refreshStatus();
            });
        }, "openhouse-install-force-retry");
        starter.start();
    }

    private void confirmSkipCurrentStep() {
        ForceSkipInfo info = getForceSkipInfo(currentStep);
        if (!info.enabled) return;

        new AlertDialog.Builder(activity)
            .setTitle("确认跳过当前屏")
            .setMessage("确定要" + info.label + "吗？\n\n风险：" + info.risk)
            .setNegativeButton("返回", null)
            .setPositiveButton("继续跳过", (dialog, which) -> applySkip(currentStep))
            .show();
    }

    private void applySkip(Step step) {
        SharedPreferences.Editor editor = preferences.edit().putBoolean(KEY_GUIDE_DISMISSED, false);
        if (step == Step.PERMISSION) {
            editor.putBoolean(KEY_BATTERY_SKIPPED, true);
            editor.apply();
            setCurrentStep(Step.INSTALL);
            return;
        }
        editor.apply();
    }

    private void dismissGuide() {
        if (!isSetupComplete()) {
            return;
        }

        runtime.confirmLaunch();
        preferences.edit().putBoolean(KEY_GUIDE_DISMISSED, true).apply();
        render();
    }

    private void normalizeCurrentStep() {
        if (!isBatteryReady()) {
            currentStep = Step.PERMISSION;
            persistStep();
            return;
        }

        if (isSetupComplete()) {
            currentStep = Step.LAUNCH_CONFIG;
            persistStep();
            return;
        }

        if (currentStep == Step.PERMISSION) {
            currentStep = Step.INSTALL;
        }
        if (currentStep == Step.INSTALL && isInstallStarted() && !isInstallDone()) {
            currentStep = Step.WAITING_INSTALL;
        }
        if (currentStep == Step.WAITING_INSTALL && isInstallDone()) {
            currentStep = Step.LAUNCH_CONFIG;
        }
        if (currentStep == Step.LAUNCH_CONFIG && !isSetupComplete()) {
            currentStep = firstIncompleteStep();
        }
        persistStep();
    }

    private Step firstIncompleteStep() {
        if (!isBatteryReady()) return Step.PERMISSION;
        if (!isInstallStarted()) return Step.INSTALL;
        if (!isInstallDone()) return Step.WAITING_INSTALL;
        return Step.LAUNCH_CONFIG;
    }

    private Step getPreviousStep(Step step) {
        int index = step.ordinal();
        return index > 0 ? Step.values()[index - 1] : null;
    }

    private Step getNextStep(Step step) {
        if (step == Step.PERMISSION) return isBatteryReady() ? Step.INSTALL : null;
        if (step == Step.INSTALL) return isInstallStarted() ? Step.WAITING_INSTALL : null;
        if (step == Step.WAITING_INSTALL) return isInstallDone() ? Step.LAUNCH_CONFIG : null;
        return null;
    }

    private boolean shouldShowGuide() {
        return !isSetupComplete() || !preferences.getBoolean(KEY_GUIDE_DISMISSED, false);
    }

    private boolean isBatterySkipped() {
        return preferences.getBoolean(KEY_BATTERY_SKIPPED, false);
    }

    private boolean isBatteryReady() {
        return status.batteryOptimizationIgnored || isBatterySkipped();
    }

    private boolean isInstallDone() {
        return installState.completed || isCoreDeploymentComplete();
    }

    private boolean isInstallStarted() {
        return installState.running || installState.completed || installState.failed || installState.percent > 0 || getCoreProgressPercent() > 0;
    }

    private boolean isSetupComplete() {
        return isBatteryReady() && isInstallDone();
    }

    private int getDisplayedInstallPercent() {
        if (installState.running || installState.completed || installState.failed || installState.percent > 0) {
            return installState.percent;
        }
        return getCoreProgressPercent();
    }

    private String getInstallProgressTitle() {
        if (isInstallDone()) return "安装完成";
        if (installState.running) return installState.phaseLabel;
        if (installState.failed) return installState.phaseLabel;
        return "等待开始安装";
    }

    private String getDisplayedInstallDetail() {
        if (installState.running || installState.completed || installState.failed) {
            return installState.detailText;
        }
        return getCoreNextStepLabel();
    }

    private boolean isCoreDeploymentComplete() {
        return status.termuxReady
            && status.productPrepared
            && status.ubuntuInstalled
            && status.entryUbuntuConfigured
            && status.nodeInstalled
            && status.officialDocsSynced
            && status.serviceManagerInstalled
            && status.openhouseConnectInstalled
            && status.smallPhoneRuntimeInstalled
            && status.registrySynced
            && status.serviceManagerReachable
            && status.openhouseConnectReachable
            && status.smallPhoneReachable;
    }

    private int getCoreProgressPercent() {
        int done = 0;
        int total = 13;
        if (status.termuxReady) done++;
        if (status.productPrepared) done++;
        if (status.ubuntuInstalled) done++;
        if (status.entryUbuntuConfigured) done++;
        if (status.nodeInstalled) done++;
        if (status.officialDocsSynced) done++;
        if (status.serviceManagerInstalled) done++;
        if (status.openhouseConnectInstalled) done++;
        if (status.smallPhoneRuntimeInstalled) done++;
        if (status.registrySynced) done++;
        if (status.serviceManagerReachable) done++;
        if (status.openhouseConnectReachable) done++;
        if (status.smallPhoneReachable) done++;
        return Math.round((done * 100f) / total);
    }

    private String getCoreNextStepLabel() {
        if (!status.termuxReady) return "准备 Termux 基础环境";
        if (!status.productPrepared) return "准备本机目录";
        if (!status.ubuntuInstalled) return "准备 Linux 环境";
        if (!status.entryUbuntuConfigured) return "设置启动方式";
        if (!status.nodeInstalled) return "安装 Node.js";
        if (!status.officialDocsSynced) return "同步使用文档";
        if (!status.serviceManagerInstalled) return "安装 service-manager";
        if (!status.openhouseConnectInstalled) return "安装 openhouse-connect";
        if (!status.smallPhoneRuntimeInstalled) return "安装 SmallPhone 兼容服务";
        if (!status.registrySynced) return "同步 service-manager 注册表";
        if (!status.serviceManagerReachable) return "启动 service-manager";
        if (!status.openhouseConnectReachable) return "启动 openhouse-connect";
        if (!status.smallPhoneReachable) return "启动 SmallPhone 兼容服务";
        return "核心控制平面已就绪，下一步进入 pi-agent";
    }

    private ForceSkipInfo getForceSkipInfo(Step step) {
        if (step == Step.PERMISSION) {
            return new ForceSkipInfo(true, "强行跳过权限检测", "如果系统实际没有允许后台运行，初始化安装可能在息屏、切换应用或省电策略下中断。");
        }
        if (step == Step.INSTALL) {
            return new ForceSkipInfo(false, "先启动安装", "初始化安装不能直接跳过。请先点击开始安装；启动后会进入等待页面。");
        }
        if (step == Step.WAITING_INSTALL) {
            return new ForceSkipInfo(false, "等待安装完成", "安装仍在进行，暂时不能跳过到使用说明。");
        }
        return new ForceSkipInfo(false, "已到最后一屏", "使用说明页不能再跳过；可以进入演示或 Ubuntu 终端。");
    }

    private void setCurrentStep(Step step) {
        currentStep = step;
        persistStep();
        render();
    }

    private Step readSavedStep() {
        if (preferences.contains(KEY_CURRENT_STEP)) {
            Step step = stepFromNumber(preferences.getInt(KEY_CURRENT_STEP, Step.PERMISSION.ordinal() + 1));
            if (step != null) {
                return step;
            }
        }

        String saved = preferences.getString(KEY_STEP, Step.PERMISSION.name());
        if ("READING_GUIDE".equals(saved)) {
            return Step.WAITING_INSTALL;
        }
        if ("LAUNCH_CONFIG".equals(saved)) {
            return Step.LAUNCH_CONFIG;
        }
        try {
            return Step.valueOf(saved);
        } catch (Exception e) {
            return Step.PERMISSION;
        }
    }

    private void persistStep() {
        preferences.edit()
            .putString(KEY_STEP, currentStep.name())
            .putInt(KEY_CURRENT_STEP, currentStep.ordinal() + 1)
            .apply();
    }

    private Step stepFromNumber(int number) {
        int index = number - 1;
        Step[] steps = Step.values();
        if (index >= 0 && index < steps.length) {
            return steps[index];
        }
        return null;
    }

    private void addStatusCard(String title, String body) {
        LinearLayout card = panel(R.drawable.openhouse_onboarding_status_panel);
        TextView titleView = new TextView(activity);
        titleView.setText(title);
        titleView.setTextColor(COLOR_TEXT);
        titleView.setTextSize(14);
        titleView.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        titleView.setIncludeFontPadding(false);
        card.addView(titleView);
        TextView bodyText = smallBody(body);
        card.addView(bodyText, topMarginParams(dp(7)));
        contentView.addView(card, topMarginParams(dp(0)));
    }

    private void addProgressBar(int progress, String detail) {
        LinearLayout panel = panel(R.drawable.openhouse_onboarding_panel);
        LinearLayout meta = new LinearLayout(activity);
        meta.setGravity(Gravity.CENTER_VERTICAL);
        TextView label = smallBody(getInstallProgressTitle());
        TextView percent = smallBody(progress + "%");
        percent.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        percent.setTextColor(COLOR_PRIMARY_DARK);
        meta.addView(label, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        meta.addView(percent);
        panel.addView(meta);

        ProgressBar progressBar = new ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setMax(100);
        progressBar.setProgress(Math.max(0, Math.min(100, progress)));
        panel.addView(progressBar, topMarginParams(dp(8), LinearLayout.LayoutParams.MATCH_PARENT, dp(10)));
        panel.addView(smallBody(detail), topMarginParams(dp(8)));
        contentView.addView(panel, topMarginParams(dp(10)));
    }

    private void addReadingGuide(boolean openByDefault, boolean compact) {
        LinearLayout card = panel(compact ? R.drawable.openhouse_onboarding_status_panel : R.drawable.openhouse_onboarding_panel);
        TextView title = new TextView(activity);
        title.setText("安装期间可以先了解");
        title.setTextColor(COLOR_PRIMARY_DARK);
        title.setTextSize(12);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        title.setIncludeFontPadding(false);
        card.addView(title);
        TextView summary = new TextView(activity);
        summary.setText("安装完成后的基本使用方法");
        summary.setTextColor(COLOR_TEXT);
        summary.setTextSize(13);
        summary.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        card.addView(summary, topMarginParams(dp(4)));

        LinearLayout body = new LinearLayout(activity);
        body.setOrientation(LinearLayout.VERTICAL);
        card.addView(body, topMarginParams(dp(8)));

        if (openByDefault) {
            addReadingSection(body, "安装需要多久", "第一次安装通常需要 10 分钟到半小时。时间取决于手机性能、网络和包下载速度。");
            addReadingSection(body, "这款软件会做什么", "OpenHouseAI 会帮你配置一套顶级 AI 编程环境。它到底能做什么，取决于你想让它帮你做什么。");
            addReadingSection(body, "会安装哪些核心能力",
                "Ubuntu 是主要运行环境，Node.js 是 pi-agent 和 pi-web 的运行依赖。",
                "service-manager 是安装完成后的控制平面，负责后台服务启动、停止和健康检查。",
                "openhouse-connect、pi 和 pi-web 负责本机服务、pi-agent、插件体系和页面入口。");
            addReadingSection(body, "AI Agent 为什么后置安装",
                "Codex、Claude Code、CloudCLI 和 Hermes 不再阻塞首次安装。",
                "先让 pi-agent 可用，再由 pi-agent 读取内置文档，按用户选择安装和配置这些能力。");
            addReadingSection(body, "安装时不用填 Key",
                "首次安装只负责把控制平面装好，不要求现在选择模型或填写 API Key。",
                "需要使用模型时，先进入 pi-agent，再由 pi-agent 引导迁移配置或安装工作台。");
            addReadingSection(body, "安装完成后怎么开始",
                "从菜单进入 pi-agent，点击首次使用提示，让它先阅读 /root/openhouse/docs。",
                "后台服务由 service-manager 统一管理；终端只是后续排障和高级操作入口。");
        } else {
            addReadingSection(body, "安装完成后的基本使用方法", "等待页面会说明核心组件、service-manager、pi-agent 和后置 AI 能力的安装入口。");
        }
        contentView.addView(card, topMarginParams(dp(10)));
    }

    private void addReadingSection(LinearLayout parent, String title, String... paragraphs) {
        TextView heading = new TextView(activity);
        heading.setText(title);
        heading.setTextColor(COLOR_TEXT);
        heading.setTextSize(13);
        heading.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        parent.addView(heading, topMarginParams(dp(6)));
        for (String paragraph : paragraphs) {
            if (paragraph == null || paragraph.trim().isEmpty()) continue;
            parent.addView(smallBody(paragraph), topMarginParams(dp(4)));
        }
    }

    private void addActionButton(String text, boolean enabled, boolean primary, View.OnClickListener listener) {
        MaterialButton button = createButton(text, enabled && !actionBusy, primary);
        button.setOnClickListener(listener);
        actionsView.addView(button, topMarginParams(actionsView.getChildCount() == 0 ? 0 : dp(8), LinearLayout.LayoutParams.MATCH_PARENT, dp(42)));
    }

    private void addForceRestartInstallButtonIfNeeded() {
        if (isInstallDone() || !isInstallStarted()) {
            return;
        }

        MaterialButton button = createButton("安装卡住？强制重启并继续", !actionBusy, false);
        button.setTextColor(COLOR_WARN);
        button.setStrokeColor(ColorStateList.valueOf(COLOR_WARN));
        button.setOnClickListener(v -> confirmForceRestartInstall());
        actionsView.addView(button, topMarginParams(actionsView.getChildCount() == 0 ? 0 : dp(8), LinearLayout.LayoutParams.MATCH_PARENT, dp(42)));
    }

    private void addPrimaryHeroButton(String text, boolean enabled, int iconRes, View.OnClickListener listener) {
        MaterialButton button = createButton(text, enabled && !actionBusy, true);
        button.setTextSize(16);
        button.setCornerRadius(dp(10));
        button.setIconResource(iconRes);
        button.setIconGravity(MaterialButton.ICON_GRAVITY_TEXT_END);
        button.setIconPadding(dp(10));
        button.setIconTint(ColorStateList.valueOf(Color.WHITE));
        button.setOnClickListener(listener);
        actionsView.addView(button, topMarginParams(actionsView.getChildCount() == 0 ? 0 : dp(10), LinearLayout.LayoutParams.MATCH_PARENT, dp(56)));
    }

    private MaterialButton createButton(String text, boolean enabled, boolean primary) {
        MaterialButton button = new MaterialButton(activity);
        button.setText(text);
        button.setAllCaps(false);
        button.setTextSize(13);
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        button.setMinHeight(0);
        button.setMinWidth(0);
        button.setCornerRadius(dp(8));
        button.setEnabled(enabled && !actionBusy);
        if (primary) {
            button.setTextColor(Color.WHITE);
            button.setBackgroundTintList(ColorStateList.valueOf(COLOR_PRIMARY));
            button.setStrokeWidth(0);
        } else {
            button.setTextColor(COLOR_TEXT);
            button.setBackgroundTintList(ColorStateList.valueOf(Color.rgb(248, 250, 248)));
            button.setStrokeWidth(dp(1));
            button.setStrokeColor(ColorStateList.valueOf(COLOR_BORDER));
        }
        return button;
    }

    private LinearLayout panel(int backgroundRes) {
        LinearLayout panel = new LinearLayout(activity);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(12), dp(12), dp(12), dp(12));
        panel.setBackgroundResource(backgroundRes);
        return panel;
    }

    private TextView smallBody(String text) {
        TextView view = new TextView(activity);
        view.setText(text);
        view.setTextColor(COLOR_MUTED);
        view.setTextSize(12);
        view.setLineSpacing(dp(2), 1.0f);
        return view;
    }

    private void applyProgressChipStyle(TextView chip, int stepIndex) {
        int currentIndex = currentStep.ordinal();
        boolean done = stepIndex < currentIndex || (isSetupComplete() && stepIndex == Step.LAUNCH_CONFIG.ordinal());
        boolean active = stepIndex == currentIndex;
        int background = active ? COLOR_PRIMARY : done ? Color.rgb(185, 217, 200) : Color.rgb(227, 234, 228);
        int text = active ? Color.WHITE : done ? COLOR_PRIMARY_DARK : Color.rgb(82, 96, 88);
        chip.setTextColor(text);
        chip.setBackground(roundRect(background, background, dp(14)));
    }

    private void styleNavigationButton(MaterialButton button) {
        button.setAllCaps(false);
        button.setCornerRadius(dp(8));
        button.setTextColor(COLOR_PRIMARY);
        button.setBackgroundTintList(ColorStateList.valueOf(Color.WHITE));
        button.setStrokeWidth(dp(1));
        button.setStrokeColor(ColorStateList.valueOf(Color.rgb(199, 213, 203)));
    }

    private void styleDangerButton(MaterialButton button) {
        button.setAllCaps(false);
        button.setCornerRadius(dp(8));
        button.setTextColor(Color.WHITE);
        button.setBackgroundTintList(ColorStateList.valueOf(COLOR_WARN));
        button.setStrokeWidth(0);
    }

    private GradientDrawable roundRect(int fill, int stroke, int radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setShape(GradientDrawable.RECTANGLE);
        drawable.setColor(fill);
        drawable.setStroke(dp(1), stroke);
        drawable.setCornerRadius(radius);
        return drawable;
    }

    private LinearLayout.LayoutParams topMarginParams(int topMargin) {
        return topMarginParams(topMargin, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams topMarginParams(int topMargin, int width, int height) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(width, height);
        params.setMargins(0, topMargin, 0, 0);
        return params;
    }

    private int dp(int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }

    private String safeMessage(Throwable throwable) {
        String message = throwable == null || throwable.getMessage() == null
            ? "未知错误"
            : throwable.getMessage().trim();
        if (message.isEmpty()) {
            return "未知错误";
        }
        String redacted = message.replaceAll("(?i)\\b(api[_-]?key|authorization|bearer|token|password)([=:\"' ]+)([^\\s\"']{8,})", "$1$2***");
        return redacted.replaceAll("\\bsk-[A-Za-z0-9_-]{12,}\\b", "sk-***");
    }

    private enum Step {
        PERMISSION("后台权限", "允许后台完成初始化", "初始化会安装 Ubuntu 和 AI 工具。请先允许忽略电池优化，并进入启动管理/后台保活设置，避免息屏或切换应用后中断。"),
        INSTALL("开始安装", "开始安装核心运行环境", "点击后会安装 Ubuntu、Node.js、pi-agent、pi-web、service-manager 和兼容服务。"),
        WAITING_INSTALL("等待安装", "等待安装完成", "安装继续在后台进行。这里会简要说明安装完成后的基本使用方法，不需要现在填写模型或 Key。"),
        LAUNCH_CONFIG("使用说明", "开始使用 openhouse ai", "核心栈已安装完成。service-manager 会接管运行期服务，pi-agent 是默认 agent 和插件入口。");

        final String label;
        final String title;
        final String body;

        Step(String label, String title, String body) {
            this.label = label;
            this.title = title;
            this.body = body;
        }
    }

    private static final class ForceSkipInfo {
        final boolean enabled;
        final String label;
        final String risk;

        ForceSkipInfo(boolean enabled, String label, String risk) {
            this.enabled = enabled;
            this.label = label;
            this.risk = risk;
        }
    }
}
