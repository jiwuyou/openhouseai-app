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
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.material.button.MaterialButton;
import com.termux.R;
import com.termux.app.openhouse.OpenHouseInstallController;
import com.termux.app.openhouse.OpenHouseInstallState;
import com.termux.app.openhouse.OpenHouseStatus;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;

public final class OpenHouseOnboardingOverlay {

    private static final String PREFS_NAME = "openhouse_onboarding";
    private static final String KEY_STEP = "step";
    private static final String KEY_CURRENT_STEP = "current_step";
    private static final String KEY_GUIDE_DISMISSED = "guide_dismissed";

    private static final int COLOR_PRIMARY = Color.rgb(30, 111, 82);
    private static final int COLOR_PRIMARY_DARK = Color.rgb(21, 95, 67);
    private static final int COLOR_PRIMARY_SOFT = Color.rgb(237, 247, 241);
    private static final int COLOR_TEXT = Color.rgb(23, 33, 28);
    private static final int COLOR_MUTED = Color.rgb(95, 108, 101);
    private static final int COLOR_BORDER = Color.rgb(202, 213, 204);
    private static final int COLOR_WARN = Color.rgb(143, 55, 42);
    private static final int INSTALL_LOG_TAIL_BYTES = 48 * 1024;

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

    private OpenHouseInstallState installState = OpenHouseInstallState.idle();
    private OpenHouseStatus status = OpenHouseStatus.checking();
    private Step currentStep;
    private boolean initialRevealElapsed;
    private boolean statusLoading;
    private boolean actionBusy;
    private boolean networkCheckBusy;

    private final OpenHouseInstallController.Listener installListener = state -> {
        installState = state;
        if (state.completed || state.failed) {
            refreshStatus();
        } else {
            render();
        }
    };

    public interface Callbacks {
        void onOpenDetail();
        void onOpenAiRescue();
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

        currentStep = readSavedStep();
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

    public boolean shouldBlockBackNavigation() {
        return actionBusy || networkCheckBusy || isInstallRunning();
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
        kickerView.setText("手动安装向导");
        titleView.setText("安装 OpenHouse AI");
        bodyView.setText("先允许后台运行，再准备运行环境、安装 AI 功能。每一步都由你手动开始。");

        if (installState.failed) {
            bodyView.setText("首次安装没有完成。请先发送错误报告，或进入紧急 AI 救援处理后再重试。");
            renderFailedStatus();
            return;
        }

        renderBackgroundRunPreflight();
        renderNetworkLineRow();
        renderInstallSteps();

        if (isInstallRunning()) {
            renderRunningStatus();
        } else if (isAiFeaturesReady()) {
            renderCompleteStatus();
        }
    }

    private void renderProgress() {
        progressContainer.removeAllViews();
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);
        progressContainer.addView(row, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT));

        String[] labels = {"1. 后台运行权限", "2. 准备运行环境", "3. 安装 AI 功能"};
        boolean[] done = {isBackgroundRunReady(), isRuntimeEnvironmentPrepared(), isAiFeaturesReady()};
        boolean[] active = {
            !done[0],
            done[0] && !done[1] && (currentStep == Step.RUNTIME_ENVIRONMENT || currentStep == null),
            done[1] && !done[2] && currentStep == Step.AI_FEATURES
        };
        for (int i = 0; i < labels.length; i++) {
            TextView chip = new TextView(activity);
            chip.setText(labels[i]);
            chip.setSingleLine(true);
            chip.setGravity(Gravity.CENTER);
            chip.setTextSize(11);
            chip.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            chip.setIncludeFontPadding(false);
            chip.setPadding(dp(5), dp(6), dp(5), dp(6));
            applyProgressChipStyle(chip, active[i], done[i]);

            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
            params.setMargins(i == 0 ? 0 : dp(3), 0, i == labels.length - 1 ? 0 : dp(3), 0);
            row.addView(chip, params);
        }
    }

    private void renderBackgroundRunPreflight() {
        boolean ready = isBackgroundRunReady();

        LinearLayout card = panel(R.drawable.openhouse_onboarding_panel);

        LinearLayout header = new LinearLayout(activity);
        header.setGravity(Gravity.CENTER_VERTICAL);

        TextView badge = new TextView(activity);
        badge.setText("1");
        badge.setGravity(Gravity.CENTER);
        badge.setTextColor(Color.WHITE);
        badge.setTextSize(12);
        badge.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        badge.setIncludeFontPadding(false);
        badge.setBackground(roundRect(ready ? COLOR_PRIMARY_DARK : COLOR_PRIMARY, ready ? COLOR_PRIMARY_DARK : COLOR_PRIMARY, dp(16)));
        header.addView(badge, new LinearLayout.LayoutParams(dp(28), dp(28)));

        TextView title = new TextView(activity);
        title.setText("获取后台运行权限");
        title.setTextColor(COLOR_TEXT);
        title.setTextSize(16);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        title.setIncludeFontPadding(false);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
        titleParams.setMargins(dp(10), 0, dp(8), 0);
        header.addView(title, titleParams);

        TextView state = new TextView(activity);
        state.setText(ready ? "已完成" : "先完成");
        state.setTextColor(ready ? COLOR_PRIMARY_DARK : COLOR_PRIMARY);
        state.setTextSize(12);
        state.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        state.setIncludeFontPadding(false);
        header.addView(state);

        card.addView(header);
        card.addView(smallBody("允许应用在安装和运行时保持后台运行，避免息屏或切换应用后中断。"), topMarginParams(dp(9)));

        if (ready) {
            MaterialButton doneButton = createButton(runtime.getBackgroundRunStatusText(status), false, false);
            card.addView(doneButton, topMarginParams(dp(12), LinearLayout.LayoutParams.MATCH_PARENT, dp(44)));
        } else {
            MaterialButton batteryButton = createButton("允许后台运行", !actionBusy && !networkCheckBusy && !isInstallRunning(), true);
            batteryButton.setOnClickListener(v -> runtime.openBackgroundRunPermission());
            card.addView(batteryButton, topMarginParams(dp(12), LinearLayout.LayoutParams.MATCH_PARENT, dp(44)));

            LinearLayout secondaryRow = new LinearLayout(activity);
            secondaryRow.setOrientation(LinearLayout.HORIZONTAL);

            MaterialButton startupButton = createButton("打开后台设置", !actionBusy && !networkCheckBusy && !isInstallRunning(), false);
            startupButton.setOnClickListener(v -> runtime.openStartupPermissionSettings());
            secondaryRow.addView(startupButton, new LinearLayout.LayoutParams(0, dp(40), 1));

            MaterialButton confirmButton = createButton("我已完成", !actionBusy && !networkCheckBusy && !isInstallRunning(), false);
            confirmButton.setOnClickListener(v -> {
                runtime.markBackgroundRunConfirmed();
                refreshStatus();
            });
            LinearLayout.LayoutParams confirmParams = new LinearLayout.LayoutParams(0, dp(40), 1);
            confirmParams.setMargins(dp(8), 0, 0, 0);
            secondaryRow.addView(confirmButton, confirmParams);

            card.addView(secondaryRow, topMarginParams(dp(8)));
        }

        contentView.addView(card, topMarginParams(dp(0)));
    }

    private void renderNetworkLineRow() {
        LinearLayout row = panel(R.drawable.openhouse_onboarding_status_panel);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout copy = new LinearLayout(activity);
        copy.setOrientation(LinearLayout.VERTICAL);
        TextView title = new TextView(activity);
        title.setText("网络线路：" + runtime.getNetworkLine().label);
        title.setTextColor(COLOR_TEXT);
        title.setTextSize(14);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        title.setIncludeFontPadding(false);
        copy.addView(title);
        TextView hint = smallBody("默认适合国内网络。海外网络可切换为标准线路，切换前会先检测。");
        copy.addView(hint, topMarginParams(dp(5)));

        MaterialButton change = createButton("更改", !actionBusy && !networkCheckBusy && !isInstallRunning(), false);
        change.setOnClickListener(v -> showNetworkLineDialog());

        row.addView(copy, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        LinearLayout.LayoutParams buttonParams = new LinearLayout.LayoutParams(dp(74), dp(38));
        buttonParams.setMargins(dp(10), 0, 0, 0);
        row.addView(change, buttonParams);
        contentView.addView(row, topMarginParams(contentView.getChildCount() == 0 ? dp(0) : dp(10)));
    }

    private void renderInstallSteps() {
        addStepCard(Step.RUNTIME_ENVIRONMENT);
        addStepCard(Step.AI_FEATURES);
    }

    private void addStepCard(Step step) {
        LinearLayout card = panel(R.drawable.openhouse_onboarding_panel);

        LinearLayout header = new LinearLayout(activity);
        header.setGravity(Gravity.CENTER_VERTICAL);

        TextView badge = new TextView(activity);
        badge.setText(step.number);
        badge.setGravity(Gravity.CENTER);
        badge.setTextColor(Color.WHITE);
        badge.setTextSize(12);
        badge.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        badge.setIncludeFontPadding(false);
        badge.setBackground(roundRect(getStepAccentColor(step), getStepAccentColor(step), dp(16)));
        header.addView(badge, new LinearLayout.LayoutParams(dp(28), dp(28)));

        TextView title = new TextView(activity);
        title.setText(step.label);
        title.setTextColor(COLOR_TEXT);
        title.setTextSize(16);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        title.setIncludeFontPadding(false);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
        titleParams.setMargins(dp(10), 0, dp(8), 0);
        header.addView(title, titleParams);

        TextView state = new TextView(activity);
        state.setText(getStepStatusLabel(step));
        state.setTextColor(getStepAccentColor(step));
        state.setTextSize(12);
        state.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        state.setIncludeFontPadding(false);
        header.addView(state);

        card.addView(header);
        card.addView(smallBody(step.body), topMarginParams(dp(9)));

        MaterialButton button = createButton(getStepButtonText(step), isStepButtonEnabled(step), step == currentStep);
        button.setOnClickListener(v -> startStep(step));
        card.addView(button, topMarginParams(dp(12), LinearLayout.LayoutParams.MATCH_PARENT, dp(44)));

        contentView.addView(card, topMarginParams(dp(10)));
    }

    private void renderRunningStatus() {
        addProgressBar(getDisplayedInstallPercent(), getDisplayedInstallDetail());
        if (runtime.isPiWebRescueAvailable()) {
            addCompactActionButton("紧急 AI 救援已可用", true, v -> callbacks.onOpenAiRescue());
        }
        addActionButton("查看详细进度", true, false, v -> callbacks.onOpenDetail());
        addActionButton("查看详细日志", true, false, v -> showInstallLogDialog());
    }

    private void renderFailedStatus() {
        String report = getFailureReportText();
        addStatusCard("安装没有完成", buildFailureSummary(report));
        addProgressBar(getDisplayedInstallPercent(), "当前步骤没有完成");
        addActionButton("一键复制错误报告", true, true,
            v -> OpenHouseInstallReportActions.copyReport(activity, getFailureReportText()));
        addActionButton("导出并发送完整日志", true, false,
            v -> OpenHouseInstallReportActions.exportAndShare(activity, getFailureReportText()));
        if (runtime.isPiWebRescueAvailable()) {
            addActionButton("进入紧急 AI 救援", true, false, v -> callbacks.onOpenAiRescue());
        }
        addActionButton("查看详细日志", true, false, v -> showInstallLogDialog());
        addActionButton("查看详细进度", true, false, v -> callbacks.onOpenDetail());
        addActionButton("重试当前步骤", true, false, v -> retryCurrentStep());
        if (runtime.getNetworkLine() == OpenHouseOnboardingRuntime.NetworkLine.CN) {
            addActionButton("检测并切换标准线路", true, false, v -> beginStandardLineCheck());
        }
    }

    private void retryCurrentStep() {
        if (actionBusy || networkCheckBusy || isInstallRunning()) {
            return;
        }
        actionBusy = true;
        render();
        runtime.forceRestartCurrentTask(result -> {
            actionBusy = false;
            installState = runtime.getInstallState();
            if (!result.message.isEmpty()) {
                Toast.makeText(activity, result.message, result.success ? Toast.LENGTH_SHORT : Toast.LENGTH_LONG).show();
            }
            refreshStatus();
        });
    }

    private String getFailureReportText() {
        String fallback = "OpenHouse 首次安装错误报告\n\n"
            + "错误结论：当前安装步骤没有完成。\n"
            + "失败阶段：" + (installState.currentStageSlug == null ? "未知" : installState.currentStageSlug) + "\n"
            + "状态说明：" + (installState.detailText == null ? "未提供" : installState.detailText) + "\n\n"
            + "===== 当前可见日志 =====\n"
            + readInstallLogTail();
        return OpenHouseInstallReportActions.normalizeReport(runtime.getFailureReportText(), fallback);
    }

    private String buildFailureSummary(String report) {
        String summary = OpenHouseInstallReportActions.normalizeReport(report, null);
        String[] fullLogMarkers = {
            "\n四、完整日志",
            "\n五、完整原始日志",
            "\n===== 完整日志",
            "\n===== 完整原始日志",
            "\n===== manifest_full.log"
        };
        for (String marker : fullLogMarkers) {
            int markerIndex = summary.indexOf(marker);
            if (markerIndex > 0) {
                summary = summary.substring(0, markerIndex).trim();
                break;
            }
        }
        if (summary.length() > 1800) {
            summary = summary.substring(0, 1800).trim() + "\n\n…完整内容请复制错误报告或发送日志文件。";
        }
        return summary;
    }

    private void renderCompleteStatus() {
        addStatusCard("安装完成", "OpenHouse AI 已准备好，可以开始使用。");
        addActionButton("开始使用", true, true, v -> {
            dismissGuide();
            callbacks.onStartTerminalTutorial(true);
        });
        addActionButton("查看运行控制", true, false, v -> callbacks.onOpenDetail());
    }

    private void startStep(Step step) {
        if (!isStepButtonEnabled(step)) {
            return;
        }
        if (!isBackgroundRunReady()) {
            Toast.makeText(activity, "请先完成后台运行权限。", Toast.LENGTH_SHORT).show();
            return;
        }
        if (step == Step.AI_FEATURES && !isRuntimeEnvironmentPrepared()) {
            Toast.makeText(activity, "请先完成运行环境准备。", Toast.LENGTH_SHORT).show();
            return;
        }

        actionBusy = true;
        currentStep = step;
        persistStep();
        installState = new OpenHouseInstallState(
            installState.failed || installState.attempt > 0
                ? OpenHouseInstallState.Status.RETRYING
                : OpenHouseInstallState.Status.RUNNING,
            Math.max(1, getDisplayedInstallPercent()),
            step.runningTitle,
            step.runningDetail,
            step.stageSlug,
            runtime.getNetworkLine().retryMode,
            Math.max(1, installState.attempt + 1),
            installState.logPath,
            ""
        );
        render();

        OpenHouseOnboardingRuntime.ResultCallback callback = result -> {
            actionBusy = false;
            installState = runtime.getInstallState();
            if (!result.message.isEmpty()) {
                Toast.makeText(activity, result.message, result.success ? Toast.LENGTH_SHORT : Toast.LENGTH_LONG).show();
            }
            refreshStatus();
        };

        if (step == Step.RUNTIME_ENVIRONMENT) {
            runtime.startRuntimeEnvironmentInstall(callback);
        } else if (step == Step.AI_FEATURES) {
            runtime.startAiFeaturesInstall(callback);
        }
    }

    private void showNetworkLineDialog() {
        OpenHouseOnboardingRuntime.NetworkLine current = runtime.getNetworkLine();
        showStyledDialog(new AlertDialog.Builder(activity)
            .setTitle("选择网络线路")
            .setMessage("当前线路：" + current.label + "\n\n国内加速：默认线路，适合国内网络。\n\n标准线路：适合海外网络，切换前会先检测。")
            .setNegativeButton("取消", null)
            .setNeutralButton("国内加速", (dialog, which) -> {
                runtime.setNetworkLine(OpenHouseOnboardingRuntime.NetworkLine.CN);
                Toast.makeText(activity, "已使用国内加速。", Toast.LENGTH_SHORT).show();
                render();
            })
            .setPositiveButton("标准线路", (dialog, which) -> beginStandardLineCheck()));
    }

    private void beginStandardLineCheck() {
        if (networkCheckBusy || actionBusy || isInstallRunning()) {
            return;
        }

        networkCheckBusy = true;
        AlertDialog checkingDialog = new AlertDialog.Builder(activity)
            .setTitle("正在检测标准线路")
            .setMessage("最多 30 秒。检测完成后会告诉你是否推荐切换。")
            .setCancelable(false)
            .create();
        showStyledDialog(checkingDialog);

        runtime.checkStandardNetworkLine(result -> {
            networkCheckBusy = false;
            checkingDialog.dismiss();
            showStandardLineCheckResult(result);
            render();
        });
    }

    private void showStandardLineCheckResult(OpenHouseOnboardingRuntime.NetworkCheckResult result) {
        if (result.recommended) {
            showStyledDialog(new AlertDialog.Builder(activity)
                .setTitle("标准线路可用")
                .setMessage(result.toUserMessage("检测通过，可以使用标准线路安装。"))
                .setNegativeButton("取消", null)
                .setPositiveButton("切换为标准线路", (dialog, which) -> {
                    runtime.setNetworkLine(OpenHouseOnboardingRuntime.NetworkLine.STANDARD);
                    Toast.makeText(activity, "已切换为标准线路。", Toast.LENGTH_SHORT).show();
                    render();
                }));
            return;
        }

        showStyledDialog(new AlertDialog.Builder(activity)
            .setTitle("标准线路检测不稳定")
            .setMessage(result.toUserMessage("检测到部分下载来源连接失败或速度较慢。继续切换可能导致安装中断。"))
            .setPositiveButton("保持国内加速", (dialog, which) -> {
                runtime.setNetworkLine(OpenHouseOnboardingRuntime.NetworkLine.CN);
                render();
            })
            .setNegativeButton("仍然切换标准线路", (dialog, which) -> {
                runtime.setNetworkLine(OpenHouseOnboardingRuntime.NetworkLine.STANDARD);
                Toast.makeText(activity, "已切换为标准线路。", Toast.LENGTH_SHORT).show();
                render();
            }));
    }

    private void showInstallLogDialog() {
        LinearLayout panel = new LinearLayout(activity);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(16), dp(8), dp(16), dp(0));

        TextView hint = smallBody("这里只读取详细日志尾部内容，不会停止或重启安装任务。");
        panel.addView(hint);

        TextView logView = new TextView(activity);
        logView.setText(readInstallLogTail());
        logView.setTextColor(COLOR_TEXT);
        logView.setTextSize(11);
        logView.setTypeface(Typeface.MONOSPACE);
        logView.setLineSpacing(dp(2), 1.0f);
        logView.setTextIsSelectable(true);
        logView.setPadding(0, dp(8), 0, dp(8));

        ScrollView scrollView = new ScrollView(activity);
        scrollView.addView(logView, new ScrollView.LayoutParams(
            ScrollView.LayoutParams.MATCH_PARENT,
            ScrollView.LayoutParams.WRAP_CONTENT));
        panel.addView(scrollView, topMarginParams(dp(8), LinearLayout.LayoutParams.MATCH_PARENT, dp(320)));

        AlertDialog dialog = new AlertDialog.Builder(activity)
            .setTitle("详细日志")
            .setView(panel)
            .setNegativeButton("关闭", null)
            .setPositiveButton("刷新日志", null)
            .create();
        dialog.setOnShowListener(shownDialog -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            .setOnClickListener(v -> {
                logView.setText(readInstallLogTail());
                scrollView.post(() -> scrollView.fullScroll(View.FOCUS_DOWN));
            }));
        showStyledDialog(dialog);
        scrollView.post(() -> scrollView.fullScroll(View.FOCUS_DOWN));
    }

    private AlertDialog showStyledDialog(AlertDialog.Builder builder) {
        AlertDialog dialog = builder.create();
        return showStyledDialog(dialog);
    }

    private AlertDialog showStyledDialog(AlertDialog dialog) {
        if (dialog == null) {
            return null;
        }
        dialog.show();
        applyDialogButtonColors(dialog);
        return dialog;
    }

    private void applyDialogButtonColors(AlertDialog dialog) {
        if (dialog.getButton(AlertDialog.BUTTON_POSITIVE) != null) {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(COLOR_PRIMARY_DARK);
        }
        if (dialog.getButton(AlertDialog.BUTTON_NEGATIVE) != null) {
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(COLOR_TEXT);
        }
        if (dialog.getButton(AlertDialog.BUTTON_NEUTRAL) != null) {
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setTextColor(COLOR_PRIMARY_DARK);
        }
    }

    private String readInstallLogTail() {
        String path = installState.logPath == null ? "" : installState.logPath.trim();
        if (path.isEmpty()) {
            return "详细日志还没有生成。请等待安装任务启动后再刷新。";
        }

        File logFile = new File(path);
        if (!logFile.exists()) {
            return "详细日志文件暂时不存在：\n" + path + "\n\n请等待安装任务写入日志后再刷新。";
        }
        if (!logFile.isFile()) {
            return "详细日志路径不是普通文件：\n" + path;
        }

        try (RandomAccessFile file = new RandomAccessFile(logFile, "r")) {
            long length = file.length();
            long start = Math.max(0, length - INSTALL_LOG_TAIL_BYTES);
            byte[] buffer = new byte[(int) (length - start)];
            file.seek(start);
            file.readFully(buffer);
            String content = new String(buffer);
            if (start > 0) {
                content = "... 已省略前面的日志，只显示最后 " + (INSTALL_LOG_TAIL_BYTES / 1024) + "KB ...\n\n" + content;
            }
            return content.trim().isEmpty() ? "详细日志目前是空的。请稍后刷新。" : safeLogText(content);
        } catch (IOException e) {
            return "读取详细日志失败：\n" + safeMessage(e) + "\n\n日志路径：\n" + path;
        }
    }

    private void normalizeCurrentStep() {
        if (isAiFeaturesReady()) {
            currentStep = Step.COMPLETE;
            persistStep();
            return;
        }

        if (installState.failed) {
            currentStep = getStateTaskStep();
            persistStep();
            return;
        }

        if (isInstallRunning()) {
            currentStep = getStateTaskStep();
            persistStep();
            return;
        }

        if (!isRuntimeEnvironmentPrepared()) {
            currentStep = Step.RUNTIME_ENVIRONMENT;
        } else if (currentStep == null || currentStep == Step.RUNTIME_ENVIRONMENT || currentStep == Step.COMPLETE) {
            currentStep = Step.AI_FEATURES;
        }
        persistStep();
    }

    private Step getStateTaskStep() {
        OpenHouseOnboardingRuntime.InstallTask task = runtime.getInstallTask(installState);
        if (task == OpenHouseOnboardingRuntime.InstallTask.AI_FEATURES) {
            return Step.AI_FEATURES;
        }
        if (task == OpenHouseOnboardingRuntime.InstallTask.RUNTIME_ENVIRONMENT) {
            return Step.RUNTIME_ENVIRONMENT;
        }
        return isRuntimeEnvironmentPrepared() ? Step.AI_FEATURES : Step.RUNTIME_ENVIRONMENT;
    }

    private boolean shouldShowGuide() {
        return isInstallRunning()
            || !isAiFeaturesReady()
            || !preferences.getBoolean(KEY_GUIDE_DISMISSED, false);
    }

    private boolean isRuntimeEnvironmentPrepared() {
        return runtime.isRuntimeEnvironmentPrepared(status);
    }

    private boolean isAiFeaturesReady() {
        return runtime.isAiFeaturesReady(status);
    }

    private boolean isBackgroundRunReady() {
        return runtime.isBackgroundRunReady(status);
    }

    private boolean isInstallRunning() {
        return installState != null && installState.running;
    }

    private boolean isStepRunning(Step step) {
        return isInstallRunning() && currentStep == step;
    }

    private boolean isStepFailed(Step step) {
        return installState.failed && currentStep == step;
    }

    private boolean isStepComplete(Step step) {
        if (step == Step.RUNTIME_ENVIRONMENT) {
            return isRuntimeEnvironmentPrepared();
        }
        if (step == Step.AI_FEATURES) {
            return isAiFeaturesReady();
        }
        return isAiFeaturesReady();
    }

    private boolean isStepButtonEnabled(Step step) {
        if (actionBusy || networkCheckBusy || isInstallRunning() || isStepComplete(step)) {
            return false;
        }
        if (!isBackgroundRunReady()) {
            return false;
        }
        if (step == Step.AI_FEATURES && !isRuntimeEnvironmentPrepared()) {
            return false;
        }
        return currentStep == step || isStepFailed(step);
    }

    private String getStepButtonText(Step step) {
        if (isStepComplete(step)) {
            return step.completeButton;
        }
        if (isStepRunning(step)) {
            return step.runningButton;
        }
        if (isStepFailed(step)) {
            return "重试当前步骤";
        }
        if (!isBackgroundRunReady()) {
            return "请先完成后台权限";
        }
        if (step == Step.AI_FEATURES && !isRuntimeEnvironmentPrepared()) {
            return "请先完成运行环境准备";
        }
        return step.startButton;
    }

    private String getStepStatusLabel(Step step) {
        if (isStepComplete(step)) {
            return "已完成";
        }
        if (isStepRunning(step)) {
            return "进行中";
        }
        if (isStepFailed(step)) {
            return "未完成";
        }
        if (!isBackgroundRunReady()) {
            return "等待权限";
        }
        if (step == Step.AI_FEATURES && !isRuntimeEnvironmentPrepared()) {
            return "等待上一步";
        }
        return "可开始";
    }

    private int getStepAccentColor(Step step) {
        if (isStepFailed(step)) {
            return COLOR_WARN;
        }
        if (isStepComplete(step)) {
            return COLOR_PRIMARY_DARK;
        }
        if (step == currentStep) {
            return COLOR_PRIMARY;
        }
        return COLOR_MUTED;
    }

    private int getDisplayedInstallPercent() {
        if (installState.running || installState.completed || installState.failed || installState.percent > 0) {
            return installState.percent;
        }
        if (currentStep == Step.AI_FEATURES) {
            return runtime.getAiFeaturesProgressPercent(status);
        }
        return runtime.getRuntimeEnvironmentProgressPercent(status);
    }

    private String getInstallProgressTitle() {
        if (installState.failed) {
            return "当前步骤未完成";
        }
        if (currentStep == Step.AI_FEATURES) {
            return isAiFeaturesReady() ? "AI 功能已安装" : "正在安装 AI 功能";
        }
        return isRuntimeEnvironmentPrepared() ? "运行环境已准备好" : "正在准备运行环境";
    }

    private String getDisplayedInstallDetail() {
        if (installState.failed) {
            return "当前步骤没有完成";
        }
        if (currentStep == Step.AI_FEATURES) {
            return runtime.getAiFeaturesProgressText(status);
        }
        return runtime.getRuntimeEnvironmentProgressText(status);
    }

    private Step readSavedStep() {
        if (preferences.contains(KEY_CURRENT_STEP)) {
            Step step = stepFromNumber(preferences.getInt(KEY_CURRENT_STEP, Step.RUNTIME_ENVIRONMENT.ordinal() + 1));
            if (step != null) {
                return step;
            }
        }

        String saved = preferences.getString(KEY_STEP, Step.RUNTIME_ENVIRONMENT.name());
        if ("PERMISSION".equals(saved) || "INSTALL".equals(saved) || "WAITING_INSTALL".equals(saved) || "READING_GUIDE".equals(saved)) {
            return Step.RUNTIME_ENVIRONMENT;
        }
        if ("LAUNCH_CONFIG".equals(saved)) {
            return Step.AI_FEATURES;
        }
        try {
            return Step.valueOf(saved);
        } catch (Exception e) {
            return Step.RUNTIME_ENVIRONMENT;
        }
    }

    private void persistStep() {
        preferences.edit()
            .putString(KEY_STEP, currentStep.name())
            .putInt(KEY_CURRENT_STEP, currentStep.ordinal() + 1)
            .apply();
    }

    private Step stepFromNumber(int number) {
        if (number <= 1) {
            return Step.RUNTIME_ENVIRONMENT;
        }
        if (number == 2) {
            return Step.AI_FEATURES;
        }
        return Step.COMPLETE;
    }

    private void dismissGuide() {
        if (!isAiFeaturesReady()) {
            return;
        }

        runtime.confirmLaunch(status);
        preferences.edit().putBoolean(KEY_GUIDE_DISMISSED, true).apply();
        render();
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
        contentView.addView(card, topMarginParams(dp(10)));
    }

    private void addProgressBar(int progress, String detail) {
        LinearLayout panel = panel(R.drawable.openhouse_onboarding_panel);
        LinearLayout meta = new LinearLayout(activity);
        meta.setGravity(Gravity.CENTER_VERTICAL);
        TextView label = smallBody(getInstallProgressTitle());
        TextView percent = smallBody(Math.max(0, Math.min(100, progress)) + "%");
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

    private void addActionButton(String text, boolean enabled, boolean primary, View.OnClickListener listener) {
        MaterialButton button = createButton(text, enabled && !actionBusy && !networkCheckBusy, primary);
        button.setOnClickListener(listener);
        actionsView.addView(button, topMarginParams(actionsView.getChildCount() == 0 ? dp(2) : dp(8), LinearLayout.LayoutParams.MATCH_PARENT, dp(42)));
    }

    private void addCompactActionButton(String text, boolean enabled, View.OnClickListener listener) {
        MaterialButton button = createButton(text, enabled && !actionBusy && !networkCheckBusy, false);
        button.setTextSize(12);
        button.setOnClickListener(listener);
        actionsView.addView(button, topMarginParams(
            actionsView.getChildCount() == 0 ? dp(2) : dp(8),
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(36)
        ));
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

    private void applyProgressChipStyle(TextView chip, boolean active, boolean done) {
        int background = active ? COLOR_PRIMARY : done ? Color.rgb(185, 217, 200) : Color.rgb(227, 234, 228);
        int text = active ? Color.WHITE : done ? COLOR_PRIMARY_DARK : Color.rgb(82, 96, 88);
        chip.setTextColor(text);
        chip.setBackground(roundRect(background, background, dp(14)));
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

    private String safeLogText(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        String redacted = text.replaceAll("(?i)\\b(api[_-]?key|authorization|bearer|token|password)([=:\"' ]+)([^\\s\"']{8,})", "$1$2***");
        return redacted.replaceAll("\\bsk-[A-Za-z0-9_-]{12,}\\b", "sk-***");
    }

    private enum Step {
        RUNTIME_ENVIRONMENT(
            "2",
            "准备运行环境",
            "安装 AI 运行所需的基础环境。完成后即可继续安装 AI 功能。",
            "开始准备",
            "正在准备",
            "运行环境已准备好",
            "正在准备运行环境",
            "正在准备基础组件，请保持应用可后台运行。",
            "runtime_environment"
        ),
        AI_FEATURES(
            "3",
            "安装 AI 功能",
            "安装 AI 助手、本地 AI 页面和手机端运行组件。",
            "继续安装 AI 功能",
            "正在安装",
            "AI 功能已安装",
            "正在安装 AI 功能",
            "正在安装 AI 功能，请保持应用可后台运行。",
            "ai_features"
        ),
        COMPLETE(
            "3",
            "安装完成",
            "OpenHouse AI 已准备好。",
            "开始使用",
            "正在完成",
            "安装完成",
            "安装完成",
            "OpenHouse AI 已准备好。",
            "complete"
        );

        final String number;
        final String label;
        final String body;
        final String startButton;
        final String runningButton;
        final String completeButton;
        final String runningTitle;
        final String runningDetail;
        final String stageSlug;

        Step(String number,
             String label,
             String body,
             String startButton,
             String runningButton,
             String completeButton,
             String runningTitle,
             String runningDetail,
             String stageSlug) {
            this.number = number;
            this.label = label;
            this.body = body;
            this.startButton = startButton;
            this.runningButton = runningButton;
            this.completeButton = completeButton;
            this.runningTitle = runningTitle;
            this.runningDetail = runningDetail;
            this.stageSlug = stageSlug;
        }
    }
}
