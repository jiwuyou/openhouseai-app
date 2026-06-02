package com.termux.app.activities;

import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.view.GravityCompat;
import androidx.drawerlayout.widget.DrawerLayout;

import com.termux.R;
import com.termux.app.OpenHouseAgreement;
import com.termux.app.OpenCodeSettings;
import com.termux.app.TermuxActivity;
import com.termux.shared.activity.ActivityUtils;

public class OpenHouseHomeActivity extends AppCompatActivity {

    private static final String PAGE_HOME = "home";
    private static final String PAGE_MANUAL = "manual";
    private static final String PAGE_OPENCODE = "opencode";
    private static final String PAGE_TERMINAL_GUIDE = "terminal_guide";
    private static final String PAGE_SHORTCUTS = "shortcuts";
    private static final String PAGE_REPAIR = "repair";
    private static final String PAGE_LOGS = "logs";
    private static final String PAGE_ADVANCED = "advanced";

    private DrawerLayout drawerLayout;
    private LinearLayout contentView;
    private TextView pageTitleView;
    private TextView pageSubtitleView;
    private String currentPage = PAGE_HOME;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_openhouse_home);

        drawerLayout = findViewById(R.id.openhouseDrawer);
        contentView = findViewById(R.id.openhouseContent);
        pageTitleView = findViewById(R.id.openhousePageTitle);
        pageSubtitleView = findViewById(R.id.openhousePageSubtitle);

        findViewById(R.id.buttonOpenDrawer).setOnClickListener(v -> drawerLayout.openDrawer(GravityCompat.START));
        findViewById(R.id.buttonCloseDrawer).setOnClickListener(v -> drawerLayout.closeDrawer(GravityCompat.START));
        findViewById(R.id.buttonOpenAdvanced).setOnClickListener(v -> selectPage(PAGE_ADVANCED));
        bindNavigation();
        renderPage();
    }

    private void bindNavigation() {
        findViewById(R.id.buttonNavHome).setOnClickListener(v -> selectPage(PAGE_HOME));
        findViewById(R.id.buttonNavManual).setOnClickListener(v -> selectPage(PAGE_MANUAL));
        findViewById(R.id.buttonNavOpenCode).setOnClickListener(v -> selectPage(PAGE_OPENCODE));
        findViewById(R.id.buttonNavTerminalGuide).setOnClickListener(v -> selectPage(PAGE_TERMINAL_GUIDE));
        findViewById(R.id.buttonNavShortcuts).setOnClickListener(v -> selectPage(PAGE_SHORTCUTS));
        findViewById(R.id.buttonNavRepair).setOnClickListener(v -> selectPage(PAGE_REPAIR));
        findViewById(R.id.buttonNavLogs).setOnClickListener(v -> selectPage(PAGE_LOGS));
        findViewById(R.id.buttonNavAdvanced).setOnClickListener(v -> selectPage(PAGE_ADVANCED));
        findViewById(R.id.buttonNavTerminal).setOnClickListener(v -> openTerminal(false));
    }

    private void selectPage(String page) {
        currentPage = page;
        drawerLayout.closeDrawer(GravityCompat.START);
        renderPage();
    }

    private void renderPage() {
        if (contentView == null) {
            return;
        }

        contentView.removeAllViews();
        switch (currentPage) {
            case PAGE_MANUAL:
                setHeader("使用手册", "文档与 Key 入口");
                renderManualPage();
                break;
            case PAGE_OPENCODE:
                setHeader("OpenCode 控制", "进入维护中心执行");
                renderOpenCodePage();
                break;
            case PAGE_TERMINAL_GUIDE:
                setHeader("终端教学", "回到 Termux 浮层");
                renderTerminalGuidePage();
                break;
            case PAGE_SHORTCUTS:
                setHeader("终端快捷键", "基础控制键和 AI 快捷键");
                renderShortcutsPage();
                break;
            case PAGE_REPAIR:
                setHeader("维护与修复", "维护中心承接");
                renderRepairPage();
                break;
            case PAGE_LOGS:
                setHeader("日志", "维护中心和阶段日志");
                renderLogsPage();
                break;
            case PAGE_ADVANCED:
                setHeader("兼容入口", "主线已迁移");
                renderAdvancedPage();
                break;
            case PAGE_HOME:
            default:
                setHeader("OpenHouseAI", "兼容入口");
                renderHomePage();
                break;
        }
    }

    private void setHeader(String title, String subtitle) {
        pageTitleView.setText(title);
        pageSubtitleView.setText(subtitle);
    }

    private void renderHomePage() {
        LinearLayout panel = panel();
        addTitle(panel, "主界面已迁移到终端浮层", 19);
        addBody(panel, "这里保留为旧入口兼容页，不再承载一键初始化、DeepSeek 配置或 OpenCode 控制主线。");
        addBody(panel, "查看详细进度、重新配置 DeepSeek、启动或重启 OpenCode，请进入维护中心；返回终端主界面会回到 TermuxActivity 的终端浮层。");
        addButtonRow(panel,
            compactButton("返回终端主界面", v -> openTerminal(false), true),
            compactButton("查看详细进度", v -> openMaintenanceCenter(), true));
        addButtonRow(panel,
            compactButton("重新配置 DeepSeek", v -> openMaintenanceCenter(), true),
            compactButton("OpenCode 控制", v -> openMaintenanceCenter(), true));
        panel.addView(button("打开在线手册", v -> openUrl(getString(R.string.openhouse_url_manual))));
        contentView.addView(panel);
    }

    private void renderManualPage() {
        LinearLayout panel = panel();
        addTitle(panel, "使用手册", 19);
        addBody(panel, "离线基础说明仍在安装后的文档目录中；联网时可以打开在线最新版。");
        panel.addView(button("在线最新版", v -> openUrl(getString(R.string.openhouse_url_manual))));
        panel.addView(button("申请 DeepSeek API Key", v -> openUrl(getString(R.string.openhouse_deepseek_url))));
        panel.addView(button("填写 Key 并配置", v -> openMaintenanceCenter()));
        contentView.addView(panel);

        addManualSection("OpenCode 新增项目", "OpenCode Web 默认地址是 " + getOpenCodeUrl() + "。新增项目或选择项目时先填写 /root，4096 是端口，不是项目路径。");
        addManualSection("终端使用", "主界面是 TermuxActivity 的终端浮层。维护中心底部终端固定为 Termux，不会因为默认入口设置而自动进入 Ubuntu。");
        addManualSection("AI 工具", "DeepSeek Key 和 OpenCode 控制都从维护中心进入，避免绕过向导确认和阶段状态。");
    }

    private void addManualSection(String title, String body) {
        LinearLayout section = panel();
        addTitle(section, title, 17);
        addBody(section, body);
        contentView.addView(section);
    }

    private void renderOpenCodePage() {
        LinearLayout panel = panel();
        addTitle(panel, "OpenCode 控制入口", 19);
        addStatusRow(panel, "访问地址", getOpenCodeUrl());
        addBody(panel, "启动、重启、自定义端口和打开浏览器都由维护中心执行。这样可以沿用向导确认、阶段阻塞和共享日志语义。");
        addButtonRow(panel,
            compactButton("进入维护中心", v -> openMaintenanceCenter(), true),
            compactButton("复制地址", v -> copyText(getString(R.string.openhouse_url_opencode_label), getOpenCodeUrl()), true));
        panel.addView(button("尝试在浏览器打开", v -> openUrl(getOpenCodeUrl())));
        contentView.addView(panel);
    }

    private void renderTerminalGuidePage() {
        LinearLayout panel = panel();
        addTitle(panel, "终端教学", 19);
        addBody(panel, "教学入口在 TermuxActivity 终端浮层中展示。");
        addButtonRow(panel,
            compactButton("打开终端教学", v -> openTerminal(true), true),
            compactButton("直接回到终端", v -> openTerminal(false), true));
        contentView.addView(panel);
    }

    private void renderShortcutsPage() {
        LinearLayout panel = panel();
        addTitle(panel, "终端快捷键", 19);
        addBody(panel, "基础键：ESC、TAB、CTRL、ALT、方向键、键盘、exit、clear。");
        addBody(panel, "AI 快捷键：claude、claude --continue、codex、reasonix、OpenCode Web。");
        panel.addView(button("回到终端", v -> openTerminal(false)));
        contentView.addView(panel);
    }

    private void renderRepairPage() {
        LinearLayout panel = panel();
        addTitle(panel, "维护与修复", 19);
        addBody(panel, "维护中心包含权限、分步执行、一键阶段、DeepSeek Key、日志和 OpenCode 控制入口。");
        panel.addView(button("打开维护中心", v -> openMaintenanceCenter()));
        contentView.addView(panel);
    }

    private void renderLogsPage() {
        LinearLayout panel = panel();
        addTitle(panel, "日志", 19);
        addBody(panel, "阶段日志保存在维护日志目录。完整进度与共享安装日志请从维护中心查看。");
        panel.addView(button("查看启动日志", v -> openMaintenanceLog("start", "启动 OpenCode")));
        panel.addView(button("查看重启日志", v -> openMaintenanceLog("restart", "重启 OpenCode")));
        panel.addView(button("打开维护中心", v -> openMaintenanceCenter()));
        contentView.addView(panel);
    }

    private void renderAdvancedPage() {
        LinearLayout panel = panel();
        addTitle(panel, "旧 Home 兼容入口", 19);
        addStatusRow(panel, "主线入口", "TermuxActivity 终端浮层");
        addStatusRow(panel, "详细进度", "维护中心");
        addStatusRow(panel, "OpenCode 端口", Integer.toString(OpenCodeSettings.DEFAULT_OPENCODE_PORT));
        addStatusRow(panel, "OpenCode 目录", OpenCodeSettings.DEFAULT_PROJECT_DIRECTORY);
        panel.addView(button("复制在线手册地址", v -> copyText("在线手册地址", getString(R.string.openhouse_url_manual))));
        contentView.addView(panel);
    }

    private String getOpenCodeUrl() {
        return OpenCodeSettings.getRootProjectUrl(OpenCodeSettings.DEFAULT_OPENCODE_PORT);
    }

    private void openMaintenanceCenter() {
        if (OpenHouseAgreement.hasAcceptedCurrentVersion(this)) {
            ActivityUtils.startActivity(this, new Intent(this, MaintenanceCenterActivity.class));
            return;
        }

        Intent intent = new Intent(this, OpenHouseAgreementActivity.class);
        intent.putExtra(OpenHouseAgreementActivity.EXTRA_OPEN_MAINTENANCE_AFTER_ACCEPT, true);
        ActivityUtils.startActivity(this, intent);
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

        LinearLayout.LayoutParams firstParams = new LinearLayout.LayoutParams(
            0,
            dp(44),
            1);
        row.addView(first, firstParams);

        LinearLayout.LayoutParams secondParams = new LinearLayout.LayoutParams(
            0,
            dp(44),
            1);
        secondParams.setMargins(dp(8), 0, 0, 0);
        row.addView(second, secondParams);
        parent.addView(row, rowParams);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
