package com.termux.app.activities;

import android.Manifest;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
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
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.view.GravityCompat;
import androidx.drawerlayout.widget.DrawerLayout;

import com.termux.R;
import com.termux.app.OpenCodeSettings;
import com.termux.app.OpenHouseAgreement;
import com.termux.app.TermuxActivity;
import com.termux.app.openhouse.OpenHouseDeepSeekController;
import com.termux.app.openhouse.OpenHouseMaintainerRunner;
import com.termux.app.openhouse.OpenHouseOpenCodeController;
import com.termux.shared.activity.ActivityUtils;
import com.termux.shared.logger.Logger;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class OpenHouseHomeActivity extends AppCompatActivity {

    private static final String LOG_TAG = "OpenHouseHome";
    private static final String PAGE_HOME = "home";
    private static final String PAGE_MANUAL = "manual";
    private static final String PAGE_OPENCODE = "opencode";
    private static final String PAGE_DEEPSEEK = "deepseek";
    private static final String PAGE_PERMISSIONS = "permissions";
    private static final String PAGE_TERMINAL_GUIDE = "terminal_guide";
    private static final String PAGE_SHORTCUTS = "shortcuts";
    private static final String PAGE_REPAIR = "repair";
    private static final String PAGE_LOGS = "logs";
    private static final String PAGE_ADVANCED = "advanced";

    private final ExecutorService backgroundExecutor = Executors.newSingleThreadExecutor();

    private DrawerLayout drawerLayout;
    private LinearLayout contentView;
    private TextView pageTitleView;
    private TextView pageSubtitleView;
    private String currentPage = PAGE_HOME;
    private int openCodePort = OpenCodeSettings.DEFAULT_OPENCODE_PORT;
    private String lastOpenCodeUrl = OpenCodeSettings.getRootProjectUrl(OpenCodeSettings.DEFAULT_OPENCODE_PORT);

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

    @Override
    protected void onDestroy() {
        backgroundExecutor.shutdownNow();
        super.onDestroy();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (PAGE_PERMISSIONS.equals(currentPage)) {
            renderPage();
        }
    }

    private void bindNavigation() {
        findViewById(R.id.buttonNavHome).setOnClickListener(v -> selectPage(PAGE_HOME));
        findViewById(R.id.buttonNavManual).setOnClickListener(v -> selectPage(PAGE_MANUAL));
        findViewById(R.id.buttonNavOpenCode).setOnClickListener(v -> selectPage(PAGE_OPENCODE));
        findViewById(R.id.buttonNavDeepSeek).setOnClickListener(v -> selectPage(PAGE_DEEPSEEK));
        findViewById(R.id.buttonNavPermissions).setOnClickListener(v -> selectPage(PAGE_PERMISSIONS));
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
                setHeader("使用手册", "离线基础说明和在线手册入口");
                renderManualPage();
                break;
            case PAGE_OPENCODE:
                setHeader("OpenCode 控制", "启动、停止、重启和自定义端口");
                renderOpenCodePage();
                break;
            case PAGE_DEEPSEEK:
                setHeader("DeepSeek Key", "一键替换 AI 软件配置");
                renderDeepSeekPage();
                break;
            case PAGE_PERMISSIONS:
                setHeader("权限获取", "后台运行、文件访问和悬浮窗");
                renderPermissionsPage();
                break;
            case PAGE_TERMINAL_GUIDE:
                setHeader("终端教学", "回到终端后的手指教学");
                renderTerminalGuidePage();
                break;
            case PAGE_SHORTCUTS:
                setHeader("终端快捷键", "底部按键说明");
                renderShortcutsPage();
                break;
            case PAGE_REPAIR:
                setHeader("维护与修复", "详细进度和修复入口");
                renderRepairPage();
                break;
            case PAGE_LOGS:
                setHeader("日志", "阶段日志和 OpenCode 日志");
                renderLogsPage();
                break;
            case PAGE_ADVANCED:
                setHeader("高级设置", "显示和兼容设置");
                renderAdvancedPage();
                break;
            case PAGE_HOME:
            default:
                setHeader("OpenHouseAI", "菜单总览");
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
        addTitle(panel, "菜单总览", 19);
        addBody(panel, "这里保留主入口，具体内容请从左侧侧边栏进入：使用手册、OpenCode 控制、DeepSeek Key、权限获取、终端快捷键和高级设置。");
        addButtonRow(panel,
            compactButton("进入 AI 软件安装引导", v -> openInstallGuide(), true),
            compactButton("回到终端", v -> openTerminal(false), true));
        addButtonRow(panel,
            compactButton("OpenCode 控制", v -> selectPage(PAGE_OPENCODE), true),
            compactButton("DeepSeek Key", v -> selectPage(PAGE_DEEPSEEK), true));
        contentView.addView(panel);

        LinearLayout quick = panel();
        addTitle(quick, "快速状态", 17);
        addStatusRow(quick, "OpenCode 默认地址", getOpenCodeUrl(openCodePort));
        addStatusRow(quick, "OpenCode 目录", OpenCodeSettings.DEFAULT_PROJECT_DIRECTORY);
        addStatusRow(quick, "运行环境", "AI 工具安装在 Ubuntu /root");
        contentView.addView(quick);
    }

    private void renderManualPage() {
        addManualSection("安装时建议阅读",
            "第一次安装通常需要 10 分钟到半小时，期间会下载约 500M 文件，建议在 Wi-Fi 下进行。OpenHouseAI 会准备 Ubuntu、OpenCode、Codex、Claude Code 和 Reasonix。AI 能做什么，取决于你想让它做什么。");
        addManualSection("为什么需要 DeepSeek Key",
            "AI 运行通常需要模型 API。这里推荐 DeepSeek，是因为它相对实惠，适合作为第一次统一安装和配置引导。OpenHouseAI 不限制长期使用哪一个 API，后续可以让 AI 帮你接入自己的模型。");
        addManualSection("终端里的 AI 怎么用",
            "以 Claude Code 为例，在 Ubuntu 终端输入 claude 再按回车即可使用；想继续上次对话，可以输入 claude --continue。记不住命令时，底部快捷键会准备 claude、reasonix、codex、oc 和 --continue。");
        addManualSection("Termux 和 Ubuntu",
            "启动后看到的是 Termux 终端。OpenHouseAI 会在 Termux 里安装 Ubuntu proot，OpenCode、Codex、Claude Code、Reasonix 等 AI 软件安装在 Ubuntu 的 /root 环境。普通入口终端可以默认进入 Ubuntu，维护中心底部终端固定为 Termux。");
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
        panel.addView(button("粘贴新 Key 并选择替换目标", v -> showDeepSeekReplaceDialog()));
        contentView.addView(panel);

        addManualSection("为什么需要 DeepSeek Key",
            "AI 运行需要模型 API。DeepSeek 比较实惠，适合作为首次安装的统一配置入口。本软件不限制你接入哪一个 API，长期使用的 API 可以后续让 AI 自行配置。");
        addManualSection("DeepSeek Key 怎么拿",
            "打开 DeepSeek 平台后，可以充值 1 元或 5 元；充值完成后点击 API Keys，再点击创建 API Key，名称可以任意填，比如 op。创建后复制 Key，回到这里粘贴并保存。");
    }

    private void renderPermissionsPage() {
        LinearLayout panel = panel();
        addTitle(panel, "权限获取", 19);
        addBody(panel, "忽略电池优化能降低安装过程被系统回收的概率。文件权限用于访问共享存储和文档；悬浮窗权限用于悬浮入口和部分后台拉起辅助。");
        addStatusRow(panel, "忽略电池优化", isBatteryOptimizationExempt() ? "已开启" : "未开启");
        addStatusRow(panel, "文件/存储权限", isStoragePermissionGranted() ? "已开启" : "未开启");
        addStatusRow(panel, "悬浮窗权限", isOverlayPermissionGranted() ? "已开启" : "未开启");
        addButtonRow(panel,
            compactButton("忽略电池优化", v -> requestBatteryOptimizationExemption(), true),
            compactButton("文件权限", v -> openStoragePermissionSettings(), true));
        panel.addView(button("悬浮窗权限", v -> openOverlayPermissionSettings()));
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

        new AlertDialog.Builder(this)
            .setTitle("自定义端口启动 OpenCode")
            .setMessage("端口仅影响本次控制页启动。启动成功后会打开浏览器，并显示可复制网址。")
            .setView(input)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton("启动", (dialog, which) -> {
                int port = parsePort(input.getText() == null ? "" : input.getText().toString());
                if (!OpenCodeSettings.isValidPort(port)) {
                    Toast.makeText(this, "端口无效，请输入 1-65535。", Toast.LENGTH_SHORT).show();
                    return;
                }
                openCodePort = port;
                lastOpenCodeUrl = getOpenCodeUrl(port);
                renderPage();
                runOpenCodeAction(OpenHouseMaintainerRunner.Action.START, port);
            })
            .show();
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

        new AlertDialog.Builder(this)
            .setTitle("替换 DeepSeek Key")
            .setMessage("默认全选。取消某项后，不会覆盖该软件当前配置。")
            .setView(form)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton("保存并替换", (dialog, which) -> {
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
            })
            .show();
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
        if (OpenHouseAgreement.hasAcceptedCurrentVersion(this)) {
            ActivityUtils.startActivity(this, new Intent(this, MaintenanceCenterActivity.class));
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
