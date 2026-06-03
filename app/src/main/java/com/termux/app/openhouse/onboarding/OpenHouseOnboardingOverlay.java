package com.termux.app.openhouse.onboarding;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.material.button.MaterialButton;
import com.termux.R;
import com.termux.app.openhouse.OpenHouseInstallController;
import com.termux.app.openhouse.OpenHouseInstallState;
import com.termux.app.openhouse.OpenHouseMaintainerRunner;
import com.termux.app.openhouse.OpenHouseStatus;

public final class OpenHouseOnboardingOverlay {

    private static final String PREFS_NAME = "openhouse_onboarding";
    private static final String KEY_STEP = "step";
    private static final String KEY_BATTERY_SKIPPED = "battery_skipped";
    private static final String KEY_DEEPSEEK_KEY_SKIPPED = "deepseek_key_skipped";
    private static final String KEY_DEEPSEEK_CONFIG_SKIPPED = "deepseek_config_skipped";
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
    private String deepSeekDraft = "";
    private boolean initialRevealElapsed;
    private boolean statusLoading;
    private boolean actionBusy;
    private boolean deepSeekInputFocused;

    private final OpenHouseInstallController.Listener installListener = state -> {
        installState = state;
        if (shouldKeepDeepSeekInputStable()) {
            return;
        }
        if (state.completed) {
            refreshStatus();
            if (currentStep == Step.WAITING_INSTALL) {
                setCurrentStep(Step.CONFIGURE_DEEPSEEK);
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
            if (currentStep == Step.DEEPSEEK_KEY && deepSeekInputFocused) {
                return;
            }
            normalizeCurrentStep();
            render();
        });
    }

    private void render() {
        if (shouldKeepDeepSeekInputStable()) {
            return;
        }

        normalizeCurrentStep();

        if (!initialRevealElapsed) {
            return;
        }

        if (!shouldShowGuide()) {
            rootView.setVisibility(View.GONE);
            return;
        }

        if (currentStep != Step.DEEPSEEK_KEY) {
            deepSeekInputFocused = false;
        }
        rootView.setVisibility(View.VISIBLE);
        contentView.removeAllViews();
        actionsView.removeAllViews();

        renderProgress();
        kickerView.setText((currentStep.ordinal() + 1) + "/7 " + currentStep.label);
        titleView.setText(currentStep.title);
        bodyView.setText(currentStep.body);

        switch (currentStep) {
            case PERMISSION:
                renderPermissionStep();
                break;
            case INSTALL:
                renderInstallStep();
                break;
            case READING_GUIDE:
                renderReadingGuideStep();
                break;
            case DEEPSEEK_KEY:
                renderDeepSeekKeyStep();
                break;
            case WAITING_INSTALL:
                renderWaitingInstallStep();
                break;
            case CONFIGURE_DEEPSEEK:
                renderConfigureDeepSeekStep();
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
        for (int rowIndex = 0; rowIndex < 2; rowIndex++) {
            LinearLayout row = new LinearLayout(activity);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER);
            progressContainer.addView(row, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

            int start = rowIndex == 0 ? 0 : 4;
            int end = rowIndex == 0 ? 4 : steps.length;
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
                : "点击打开 Android 授权页，允许 openhouse 忽略电池优化。返回后可重新检查状态。"
        );
        addPrimaryHeroButton(
            ready ? "后台运行权限已开启" : "开启后台运行权限",
            true,
            ready ? R.drawable.ic_openhouse_toggle_on : R.drawable.ic_openhouse_toggle_off,
            v -> runtime.openBatteryOptimizationSettings());
        addActionButton(ready ? "重新检查状态" : "我已允许，重新检查", true, true, v -> refreshStatus());
    }

    private void renderInstallStep() {
        String title = getInstallProgressTitle();
        addStatusCard(title, "OpenCode、Codex、Claude Code、Reasonix 会写入 Ubuntu /root 环境。");
        addStatusCard("网络提醒", "初始化安装预计会下载约 500M 的文件内容，推荐在 Wi-Fi 网络下进行。");
        addProgressBar(getDisplayedInstallPercent(), getDisplayedInstallDetail());
        addReadingGuide(false, true);
        boolean canStart = isBatteryReady() && !installState.running && !isInstallDone();
        addPrimaryHeroButton(
            isInstallDone() ? "安装已完成" : installState.running ? "正在安装中" : "开始一键初始化",
            canStart,
            R.drawable.ic_openhouse_play,
            v -> startInstall());
        addActionButton("查看详细进度", true, false, v -> callbacks.onOpenDetail());
        addForceRestartInstallButtonIfNeeded();
    }

    private void renderReadingGuideStep() {
        addReadingGuide(true, false);
        addActionButton("继续填写 Key", true, true, v -> setCurrentStep(Step.DEEPSEEK_KEY));
        addActionButton("查看详细进度", true, false, v -> callbacks.onOpenDetail());
    }

    private void renderDeepSeekKeyStep() {
        MaterialButton openDeepSeekButton = createButton("打开 DeepSeek 平台申请 Key", true, true);
        openDeepSeekButton.setOnClickListener(v -> runtime.openDeepSeekKeyPage());
        contentView.addView(openDeepSeekButton, topMarginParams(0, LinearLayout.LayoutParams.MATCH_PARENT, dp(42)));
        addUrlCard("如果没有自动跳转，复制这个网址到浏览器打开", "https://platform.deepseek.com/api_keys", "复制网址", v -> runtime.copyDeepSeekKeyPageUrl());
        addStatusCard("申请 Key 简要步骤", "充值后进入 API keys，创建 API key。名称可以任意填写，比如 op；创建后复制生成的 Key。");

        TextView label = addSmallLabel("DeepSeek API Key");
        label.setLayoutParams(topMarginParams(0));
        EditText input = new EditText(activity);
        input.setSingleLine(true);
        input.setText(deepSeekDraft);
        input.setHint("sk-...");
        input.setTextSize(14);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_NORMAL);
        input.setBackgroundResource(R.drawable.openhouse_onboarding_input);
        input.setPadding(dp(10), 0, dp(10), 0);
        input.setOnFocusChangeListener((v, hasFocus) -> {
            deepSeekInputFocused = hasFocus;
            if (!hasFocus) {
                refreshStatus();
            }
        });
        contentView.addView(input, topMarginParams(dp(5), LinearLayout.LayoutParams.MATCH_PARENT, dp(42)));

        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        contentView.addView(row, topMarginParams(dp(10)));

        TextView hint = smallBody(status.deepSeekKeySaved ? "Key 已保存，可等待安装完成。" : "至少输入 8 个字符后可保存。");
        row.addView(hint, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));

        MaterialButton saveButton = createButton("保存 Key", deepSeekDraft.trim().length() >= 8, true);
        LinearLayout.LayoutParams saveParams = new LinearLayout.LayoutParams(dp(112), dp(42));
        saveParams.setMargins(dp(10), 0, 0, 0);
        row.addView(saveButton, saveParams);

        input.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                deepSeekDraft = s == null ? "" : s.toString();
                boolean ready = deepSeekDraft.trim().length() >= 8;
                saveButton.setEnabled(ready && !actionBusy);
                hint.setText(status.deepSeekKeySaved ? "Key 已保存。再次保存会覆盖已保存的 Key。" : ready ? "Key 格式可用，请保存。" : "至少输入 8 个字符后可保存。");
            }

            @Override
            public void afterTextChanged(Editable s) {
            }
        });

        saveButton.setOnClickListener(v -> saveDeepSeekKey(input.getText() == null ? "" : input.getText().toString()));
        addDeepSeekKeyHelpForKeyStep();
        addReadingGuide(false, false);
    }

    private void renderWaitingInstallStep() {
        addStatusCard(
            isDeepSeekKeySkipped()
                ? "已暂不配置 Key，等待安装完成"
                : "Key 已保存，等待安装完成",
            isDeepSeekKeySkipped()
                ? "安装完成后建议先启动 OpenCode Web，再在网页里配置模型和其他 AI Agent。"
                : "现在不需要重复填写 Key。安装完成后会自动进入下一步配置。"
        );
        addProgressBar(getDisplayedInstallPercent(), "OpenCode 安装约 12 分钟，占整体进度 40%；其他阶段均分剩余时间。");
        addReadingGuide(true, false);
        addActionButton("查看详细进度", true, false, v -> callbacks.onOpenDetail());
        addForceRestartInstallButtonIfNeeded();
    }

    private void renderConfigureDeepSeekStep() {
        boolean keySkipped = isDeepSeekKeySkipped();
        boolean configured = status.deepSeekConfigured;
        addStatusCard(
            configured || isDeepSeekConfigSkipped() ? "DeepSeek 配置步骤已处理" : "等待配置到 AI 工具",
            isDeepSeekConfigSkipped()
                ? "已跳过自动配置；后续可在 OpenCode Web 中手动配置模型和其他 Agent。"
                : "这里只显示配置目标，不显示真实 API Key。"
        );

        if (keySkipped) {
            addInfoRow(1, "先启动 OpenCode Web：进入浏览器页面后，在 OpenCode 里配置你的模型 API。");
            addInfoRow(2, "再配置其他 Agent：可以让 OpenCode 帮你配置 Claude Code、Reasonix、Codex 或其他 AI Agent。");
            addInfoRow(3, "后续仍可补 Key：需要 DeepSeek 兜底时，可以回来填写并配置 DeepSeek Key。");
        } else {
            addInfoRow(1, "OpenCode：将使用已保存的 DeepSeek Key。");
            addInfoRow(2, "Claude Code：将使用已保存的 DeepSeek Key。");
            addInfoRow(3, "Reasonix：将使用已保存的 DeepSeek Key。");
        }

        boolean canConfigure = isInstallDone() && !keySkipped && !configured && status.deepSeekKeySaved;
        addActionButton(keySkipped ? "已跳过 Key" : configured ? "已配置" : "配置 DeepSeek",
            canConfigure, true, v -> configureDeepSeek());
        addActionButton("查看详细进度", true, false, v -> callbacks.onOpenDetail());
    }

    private void renderLaunchConfigStep() {
        addPathCard("项目启动目录", status.openCodeProjectDirectory);
        addPathCard("OpenCode Web 端口", Integer.toString(runtime.getOpenCodePort()));
        addStatusCard(
            "终端里怎么用 AI",
            "以 Claude 为例：输入 claude，再按回车就可以使用；如果想接着上次 Claude 的对话，输入 claude --continue。"
        );
        addStatusCard(
            "记不住命令也没关系",
            "下方终端已经准备了快捷键。点击 claude，就等同于自己手打 claude；--continue 也设置了快捷键，可以直接组合使用。"
        );
        addStatusCard(
            "OpenCode Web 怎么用",
            "OpenCode 可以复制网址到浏览器中使用。OpenCode 的打开、关闭、重启已经放到菜单中，可以随时控制。"
        );
        addStatusCard(status.openCodeReachable ? "OpenCode 运行中" : "OpenCode 未启动",
            "访问地址：" + runtime.getOpenCodeLoopbackUrl());

        addServiceActionRow(
            createButton(status.openCodeReachable ? "已启动" : "启动 OpenCode", isSetupComplete(), false),
            createButton("重启", status.openCodeReachable, false)
        );
        MaterialButton startButton = (MaterialButton) ((ViewGroup) actionsView.getChildAt(actionsView.getChildCount() - 1)).getChildAt(0);
        MaterialButton restartButton = (MaterialButton) ((ViewGroup) actionsView.getChildAt(actionsView.getChildCount() - 1)).getChildAt(1);
        startButton.setOnClickListener(v -> runOpenCodeAction(OpenHouseMaintainerRunner.Action.START));
        restartButton.setOnClickListener(v -> runOpenCodeAction(OpenHouseMaintainerRunner.Action.RESTART));

        addServiceActionRow(
            createButton("停止", status.openCodeReachable, false),
            createButton("复制地址", true, false)
        );
        MaterialButton stopButton = (MaterialButton) ((ViewGroup) actionsView.getChildAt(actionsView.getChildCount() - 1)).getChildAt(0);
        MaterialButton copyButton = (MaterialButton) ((ViewGroup) actionsView.getChildAt(actionsView.getChildCount() - 1)).getChildAt(1);
        stopButton.setOnClickListener(v -> runOpenCodeAction(OpenHouseMaintainerRunner.Action.STOP));
        copyButton.setOnClickListener(v -> runtime.copyOpenCodeAddress());

        addActionButton("使用演示", isSetupComplete(), false, v -> {
            dismissGuide();
            callbacks.onStartTerminalTutorial(true);
        });
        addActionButton("进入终端", isSetupComplete(), true, v -> {
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

        boolean started = runtime.startOneClickInstall();
        installState = runtime.getInstallState();
        Toast.makeText(activity, started ? "初始化已开始。" : "初始化已经在运行。", Toast.LENGTH_SHORT).show();
        setCurrentStep(Step.READING_GUIDE);
    }

    private void confirmForceRestartInstall() {
        if (actionBusy) return;

        new AlertDialog.Builder(activity)
            .setTitle("强制重启并继续安装")
            .setMessage("只有确认安装已经长时间没有变化时才使用。\n\n这会终止当前卡住的一键初始化任务，清理运行标记，然后重新触发安装。已完成的阶段会按状态检测跳过，从第一个未完成阶段继续。")
            .setNegativeButton("取消", null)
            .setPositiveButton("强制重启并继续", (dialog, which) -> forceRestartInstall())
            .show();
    }

    private void forceRestartInstall() {
        if (actionBusy) return;

        deepSeekInputFocused = false;
        actionBusy = true;
        render();
        runtime.forceRestartOneClickInstall(result -> {
            actionBusy = false;
            installState = runtime.getInstallState();
            Toast.makeText(activity, result.message, result.success ? Toast.LENGTH_SHORT : Toast.LENGTH_LONG).show();
            setCurrentStep(Step.READING_GUIDE);
            refreshStatus();
        });
    }

    private void saveDeepSeekKey(String key) {
        if (actionBusy) return;
        deepSeekInputFocused = false;
        actionBusy = true;
        renderSkipPanel();
        runtime.saveDeepSeekKey(key, result -> {
            actionBusy = false;
            Toast.makeText(activity, result.message, result.success ? Toast.LENGTH_SHORT : Toast.LENGTH_LONG).show();
            if (result.success) {
                preferences.edit()
                    .putBoolean(KEY_DEEPSEEK_KEY_SKIPPED, false)
                    .putBoolean(KEY_DEEPSEEK_CONFIG_SKIPPED, false)
                    .putBoolean(KEY_GUIDE_DISMISSED, false)
                    .apply();
                deepSeekDraft = "";
                setCurrentStep(isInstallDone() ? Step.CONFIGURE_DEEPSEEK : Step.WAITING_INSTALL);
                refreshStatus();
            } else {
                render();
            }
        });
    }

    private void configureDeepSeek() {
        if (actionBusy) return;
        deepSeekInputFocused = false;
        actionBusy = true;
        render();
        runtime.configureDeepSeek(result -> {
            actionBusy = false;
            Toast.makeText(activity, result.message, result.success ? Toast.LENGTH_SHORT : Toast.LENGTH_LONG).show();
            if (result.success) {
                preferences.edit()
                    .putBoolean(KEY_DEEPSEEK_CONFIG_SKIPPED, false)
                    .putBoolean(KEY_GUIDE_DISMISSED, false)
                    .apply();
                setCurrentStep(Step.LAUNCH_CONFIG);
                refreshStatus();
            } else {
                render();
            }
        });
    }

    private void runOpenCodeAction(OpenHouseMaintainerRunner.Action action) {
        if (actionBusy) return;
        deepSeekInputFocused = false;
        actionBusy = true;
        render();
        runtime.runOpenCodeAction(action, result -> {
            actionBusy = false;
            Toast.makeText(activity, result.message, result.success ? Toast.LENGTH_SHORT : Toast.LENGTH_LONG).show();
            refreshStatus();
            if (result.success && action != OpenHouseMaintainerRunner.Action.STOP) {
                runtime.openOpenCodeInBrowser();
            }
        });
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
        if (step == Step.READING_GUIDE) {
            editor.apply();
            setCurrentStep(Step.DEEPSEEK_KEY);
            return;
        }
        if (step == Step.DEEPSEEK_KEY) {
            editor.putBoolean(KEY_DEEPSEEK_KEY_SKIPPED, true);
            editor.putBoolean(KEY_DEEPSEEK_CONFIG_SKIPPED, true);
            editor.apply();
            setCurrentStep(isInstallDone() ? Step.LAUNCH_CONFIG : Step.WAITING_INSTALL);
            return;
        }
        if (step == Step.CONFIGURE_DEEPSEEK) {
            editor.putBoolean(KEY_DEEPSEEK_CONFIG_SKIPPED, true);
            editor.apply();
            setCurrentStep(Step.LAUNCH_CONFIG);
        }
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
        if (currentStep == Step.WAITING_INSTALL && isInstallDone()) {
            currentStep = Step.CONFIGURE_DEEPSEEK;
        }
        if (isKeyStepReady()
            && !isInstallDone()
            && currentStep != Step.DEEPSEEK_KEY
            && currentStep.ordinal() > Step.DEEPSEEK_KEY.ordinal()) {
            currentStep = Step.WAITING_INSTALL;
        }
        if (currentStep == Step.CONFIGURE_DEEPSEEK && isDeepSeekStepReady()) {
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
        if (!isKeyStepReady()) return Step.DEEPSEEK_KEY;
        if (!isInstallDone()) return Step.WAITING_INSTALL;
        if (!isDeepSeekStepReady()) return Step.CONFIGURE_DEEPSEEK;
        return Step.LAUNCH_CONFIG;
    }

    private Step getPreviousStep(Step step) {
        int index = step.ordinal();
        return index > 0 ? Step.values()[index - 1] : null;
    }

    private Step getNextStep(Step step) {
        if (step == Step.PERMISSION) return isBatteryReady() ? Step.INSTALL : null;
        if (step == Step.INSTALL) return isInstallStarted() ? Step.READING_GUIDE : null;
        if (step == Step.READING_GUIDE) return Step.DEEPSEEK_KEY;
        if (step == Step.DEEPSEEK_KEY) {
            if (!isKeyStepReady()) return null;
            return isInstallDone() ? Step.CONFIGURE_DEEPSEEK : Step.WAITING_INSTALL;
        }
        if (step == Step.WAITING_INSTALL) return isInstallDone() ? Step.CONFIGURE_DEEPSEEK : null;
        if (step == Step.CONFIGURE_DEEPSEEK) return isDeepSeekStepReady() ? Step.LAUNCH_CONFIG : null;
        return null;
    }

    private boolean shouldShowGuide() {
        return !isSetupComplete() || !preferences.getBoolean(KEY_GUIDE_DISMISSED, false);
    }

    private boolean isBatterySkipped() {
        return preferences.getBoolean(KEY_BATTERY_SKIPPED, false);
    }

    private boolean isDeepSeekKeySkipped() {
        return preferences.getBoolean(KEY_DEEPSEEK_KEY_SKIPPED, false);
    }

    private boolean isDeepSeekConfigSkipped() {
        return preferences.getBoolean(KEY_DEEPSEEK_CONFIG_SKIPPED, false);
    }

    private boolean isBatteryReady() {
        return status.batteryOptimizationIgnored || isBatterySkipped();
    }

    private boolean isInstallDone() {
        return installState.completed || status.isDeploymentComplete();
    }

    private boolean isInstallStarted() {
        return installState.running || installState.completed || installState.failed || installState.percent > 0 || status.getProgressPercent() > 0;
    }

    private boolean isKeyStepReady() {
        return status.deepSeekConfigured || status.deepSeekKeySaved || isDeepSeekKeySkipped();
    }

    private boolean isDeepSeekStepReady() {
        return status.deepSeekConfigured || isDeepSeekConfigSkipped() || isDeepSeekKeySkipped();
    }

    private boolean isSetupComplete() {
        return isBatteryReady() && isInstallDone() && isKeyStepReady() && isDeepSeekStepReady();
    }

    private int getDisplayedInstallPercent() {
        if (installState.running || installState.completed || installState.failed || installState.percent > 0) {
            return installState.percent;
        }
        return status.getProgressPercent();
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
        return status.getNextStepLabel();
    }

    private ForceSkipInfo getForceSkipInfo(Step step) {
        if (step == Step.PERMISSION) {
            return new ForceSkipInfo(true, "强行跳过权限检测", "如果系统实际没有允许后台运行，初始化安装可能在息屏、切换应用或省电策略下中断。");
        }
        if (step == Step.INSTALL) {
            return new ForceSkipInfo(false, "先启动一键初始化", "初始化安装不能直接跳过。请先点击一键初始化；启动后会进入下一屏。");
        }
        if (step == Step.READING_GUIDE) {
            return new ForceSkipInfo(true, "跳过阅读，继续填写 Key", "跳过阅读不会影响安装，但你可能错过 Key 获取、OpenCode Web 和其他 AI Agent 配置方式说明。");
        }
        if (step == Step.DEEPSEEK_KEY) {
            return new ForceSkipInfo(true, "暂不配置 Key，下一步", "跳过 DeepSeek Key 后，OpenCode 可以先启动，但模型 API 需要稍后在 OpenCode Web 中手动配置。");
        }
        if (step == Step.WAITING_INSTALL) {
            return new ForceSkipInfo(false, "等待安装完成", isDeepSeekKeySkipped()
                ? "你已跳过 Key。安装完成后建议先启动 OpenCode Web，再在网页里配置模型和其他 AI Agent。"
                : "安装仍在进行，暂时不能跳过到启动配置。");
        }
        if (step == Step.CONFIGURE_DEEPSEEK) {
            return new ForceSkipInfo(!isDeepSeekConfigSkipped(), isDeepSeekKeySkipped() ? "去启动 OpenCode Web" : "跳过自动配置",
                isDeepSeekKeySkipped()
                    ? "你已跳过 DeepSeek Key。下一步建议先启动 OpenCode Web，在网页里配置模型 API。"
                    : "跳过自动配置后，OpenCode 可启动，但 Claude Code、Reasonix 等工具不会自动写入 DeepSeek Key。");
        }
        return new ForceSkipInfo(false, "已到最后一屏", "启动配置页不能再跳过；请启动 OpenCode，或进入终端继续。");
    }

    private void setCurrentStep(Step step) {
        deepSeekInputFocused = false;
        currentStep = step;
        persistStep();
        render();
    }

    private Step readSavedStep() {
        String saved = preferences.getString(KEY_STEP, Step.PERMISSION.name());
        try {
            return Step.valueOf(saved);
        } catch (Exception e) {
            return Step.PERMISSION;
        }
    }

    private void persistStep() {
        preferences.edit().putString(KEY_STEP, currentStep.name()).apply();
    }

    private boolean shouldKeepDeepSeekInputStable() {
        return currentStep == Step.DEEPSEEK_KEY && deepSeekInputFocused && !actionBusy;
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

    private void addUrlCard(String label, String url, String copyLabel, View.OnClickListener copyListener) {
        LinearLayout card = panel(R.drawable.openhouse_onboarding_status_panel);
        card.addView(smallBody(label));
        TextView code = codeText(url);
        code.setTextIsSelectable(true);
        card.addView(code, topMarginParams(dp(8)));
        MaterialButton copyButton = createButton(copyLabel, true, false);
        copyButton.setOnClickListener(copyListener);
        card.addView(copyButton, topMarginParams(dp(8), LinearLayout.LayoutParams.MATCH_PARENT, dp(40)));
        contentView.addView(card, topMarginParams(dp(10)));
    }

    private void addPathCard(String label, String value) {
        LinearLayout card = panel(R.drawable.openhouse_onboarding_panel);
        card.addView(smallBody(label));
        card.addView(codeText(value), topMarginParams(dp(8)));
        contentView.addView(card, topMarginParams(dp(10)));
    }

    private void addInfoRow(int number, String text) {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.TOP);
        row.setPadding(dp(10), dp(9), dp(10), dp(9));
        row.setBackgroundResource(R.drawable.openhouse_onboarding_panel);

        TextView badge = new TextView(activity);
        badge.setText(Integer.toString(number));
        badge.setGravity(Gravity.CENTER);
        badge.setTextColor(Color.WHITE);
        badge.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        badge.setBackground(roundRect(COLOR_PRIMARY, COLOR_PRIMARY, dp(13)));
        row.addView(badge, new LinearLayout.LayoutParams(dp(26), dp(26)));

        TextView body = smallBody(text);
        LinearLayout.LayoutParams bodyParams = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
        bodyParams.setMargins(dp(10), 0, 0, 0);
        row.addView(body, bodyParams);
        contentView.addView(row, topMarginParams(dp(8)));
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
        title.setText("安装期间建议阅读");
        title.setTextColor(COLOR_PRIMARY_DARK);
        title.setTextSize(12);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        title.setIncludeFontPadding(false);
        card.addView(title);
        TextView summary = new TextView(activity);
        summary.setText("点开了解 AI、Key 和可用工具");
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
            addReadingSection(body, "为什么需要 DeepSeek Key",
                "AI Agent 运行时通常都需要模型 API。没有可用 API，软件可以启动，但 AI 不能正常调用模型完成任务。",
                "这里推荐 DeepSeek，是因为它相对实惠，适合作为第一次安装和配置的统一引导。",
                "OpenHouseAI 不限制你长期使用哪一个 API。后续可以让 OpenCode、Claude Code 或 Reasonix 帮你配置自己的模型 API。");
            addReadingSection(body, "DeepSeek Key 怎么拿",
                "初始化开始后，可以点击申请 Key 跳转到 DeepSeek 官方网站。你可以先充值 5 元，或者 1 元也可以；然后进入 API Keys，创建 API Key，名称可以随便填，比如 op。",
                "创建后复制 Key，回到 OpenHouseAI，点击“填写 Key”并粘贴。后台会在后续步骤中把这个 Key 配置到 OpenCode、Claude Code 和 Reasonix。");
            addReadingSection(body, "为什么是这几个 AI Agent",
                "Claude Code 是非常顶级的 AI Agent 软件，适合改代码、解释代码、修复问题和持续协作。",
                "OpenCode 的优势是模型接入范围广，并且原生支持 Web 页面。已经在电脑上用 AI 编程软件的用户，可以把已有 API 接入 OpenCode；浏览器访问也能减少终端压力。",
                "Reasonix 专门适配 DeepSeek，使用 DeepSeek API 比较省钱。它作为兜底，保证至少有一个 AI Agent 可以直接使用。",
                "Codex 也已安装，是很强的 AI Agent；但 DeepSeek 官方没有直接给出接入 Codex 的方式，所以当前不默认配置。");
            addReadingSection(body, "还能用其他软件或模型吗",
                "可以。配置好之后，你可以让 OpenCode、Claude Code 或 Reasonix 帮你安装和配置 OpenClaw、Hermes 或其他 AI Agent 软件。",
                "也可以接入其他大模型 API。仍建议先充值少量 DeepSeek API 并完成基础配置，再让 AI 帮你配置自己的 API。");
            addReadingSection(body, "关于 Key 和官方登录",
                "不建议把 API Key 直接发到聊天里。这里的预期方式是由 OpenHouseAI 保存 Key，并自动写入对应工具配置。",
                "Codex 和 Claude Code 的官方登录也支持，但需要你所在地区支持使用。具体登录方式可以继续询问 AI。",
                "如果使用中转站 API，也可以让 OpenCode、Claude Code 或 Reasonix 帮你把它接入 Codex，或者使用官方 GPT 账号登录。");
        } else {
            addReadingSection(body, "点开了解 AI、Key 和可用工具", "完整说明在第 3 屏“建议阅读”默认展开。");
        }
        contentView.addView(card, topMarginParams(dp(10)));
    }

    private void addDeepSeekKeyHelpForKeyStep() {
        LinearLayout card = panel(R.drawable.openhouse_onboarding_panel);
        TextView title = new TextView(activity);
        title.setText("保存前建议确认");
        title.setTextColor(COLOR_PRIMARY_DARK);
        title.setTextSize(12);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        title.setIncludeFontPadding(false);
        card.addView(title);

        addReadingSection(card, "为什么需要 DeepSeek Key",
            "AI Agent 运行时通常都需要模型 API。没有可用 API，软件可以启动，但 AI 不能正常调用模型完成任务。",
            "这里推荐 DeepSeek，是因为它相对实惠，适合作为第一次安装和配置的统一引导。",
            "OpenHouseAI 不限制你长期使用哪一个 API。后续可以让 OpenCode、Claude Code 或 Reasonix 帮你配置自己的模型 API。");
        addReadingSection(card, "DeepSeek Key 怎么拿",
            "点击上方按钮打开 DeepSeek 官方平台。你可以先充值 5 元，或者 1 元也可以；然后进入 API Keys，创建 API Key，名称可以随便填，比如 op。",
            "创建后复制 Key，回到 OpenHouseAI，在本页粘贴并点击“保存 Key”。后台会在后续步骤中把这个 Key 配置到 OpenCode、Claude Code 和 Reasonix。");

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

    private void addServiceActionRow(MaterialButton first, MaterialButton second) {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        actionsView.addView(row, topMarginParams(actionsView.getChildCount() == 0 ? 0 : dp(8)));

        LinearLayout.LayoutParams firstParams = new LinearLayout.LayoutParams(0, dp(42), 1);
        firstParams.setMargins(0, 0, dp(5), 0);
        LinearLayout.LayoutParams secondParams = new LinearLayout.LayoutParams(0, dp(42), 1);
        secondParams.setMargins(dp(5), 0, 0, 0);
        row.addView(first, firstParams);
        row.addView(second, secondParams);
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

    private TextView addSmallLabel(String text) {
        TextView view = new TextView(activity);
        view.setText(text);
        view.setTextColor(Color.rgb(101, 113, 106));
        view.setTextSize(12);
        view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        contentView.addView(view, topMarginParams(dp(10)));
        return view;
    }

    private TextView smallBody(String text) {
        TextView view = new TextView(activity);
        view.setText(text);
        view.setTextColor(COLOR_MUTED);
        view.setTextSize(12);
        view.setLineSpacing(dp(2), 1.0f);
        return view;
    }

    private TextView codeText(String text) {
        TextView view = new TextView(activity);
        view.setText(text);
        view.setTextColor(Color.rgb(23, 73, 52));
        view.setTextSize(12);
        view.setTypeface(Typeface.MONOSPACE);
        view.setPadding(dp(10), dp(8), dp(10), dp(8));
        view.setBackground(roundRect(Color.rgb(234, 244, 238), Color.rgb(234, 244, 238), dp(8)));
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

    private enum Step {
        PERMISSION("后台权限", "允许后台完成初始化", "初始化会安装 Ubuntu 和 AI 工具。请先允许忽略电池优化，避免息屏或切换应用后中断。"),
        INSTALL("初始化安装", "开始一键初始化", "点击后会立刻进入建议阅读屏。安装继续在后台进行，不需要等它完成。"),
        READING_GUIDE("建议阅读", "先读完这几件事", "安装正在后台继续。建议先了解安装时间、DeepSeek Key、OpenCode Web 和几个 AI Agent，再进入 Key 页面。"),
        DEEPSEEK_KEY("保存 Key", "获取并保存 DeepSeek Key", "安装未完成时也可以先填写。只有点击保存后，Key 才会被视为已保存。"),
        WAITING_INSTALL("等待安装", "Key 已保存，等待安装完成", "现在不需要重复填写 Key。安装完成后会自动进入下一步配置。"),
        CONFIGURE_DEEPSEEK("配置 DeepSeek", "配置 AI 工具", "Key 已保存，安装也已完成。现在把 DeepSeek Key 写入 OpenCode、Claude Code 和 Reasonix。"),
        LAUNCH_CONFIG("启动配置", "配置 OpenCode 启动方式", "初始化完成后，OpenCode 不会自动启动。你可以在这里启动 OpenCode，或进入终端教学。");

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
