package com.termux.app.openhouse;

import android.content.Context;

import com.termux.app.openhouse.resources.OpenHouseBundledResourceDelivery;
import com.termux.app.openhouse.servicecontrol.ServiceManagerRedactor;
import com.termux.shared.logger.Logger;
import com.termux.shared.termux.TermuxConstants;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public final class OpenHouseMaintainerRunner {

    private static final String LOG_TAG = "OpenHouseMaintainer";
    public static final String UPDATE_RESOURCE_ROOT =
        "$HOME/" + OpenHouseBundledResourceDelivery.ROOT_RELATIVE_PATH;
    public static final String COPYABLE_AI_GUIDE =
        OpenHouseBundledResourceDelivery.AI_REQUEST_SENTENCE;
    private final Context context;

    public OpenHouseMaintainerRunner(Context context) {
        this.context = context.getApplicationContext();
    }

    public Result run(Action action, int port) {
        return run(action, port, Collections.emptyMap());
    }

    public Result run(Action action, int port, Map<String, String> extraEnvironment) {
        if (action == null) return new Result(null, 126, "Unknown maintenance action; nothing executed.");
        if (action == Action.POST_APK_UPDATE) {
            return new Result(action, 0,
                "APK update resources are handled by OpenHousePostUpdateSync. No installer was started."
                    + "\n更新资源目录：" + UPDATE_RESOURCE_ROOT
                    + "\n可复制给 AI：" + COPYABLE_AI_GUIDE);
        }

        File tempScript = null;
        File outputFile = null;
        Process process = null;
        try {
            File bash = new File(TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH, "bash");
            if (!bash.isFile()) return new Result(action, 127, "Termux bash is not installed yet.");
            File logDir = new File(TermuxConstants.TERMUX_HOME_DIR_PATH, ".maintainer-logs");
            if (!logDir.exists() && !logDir.mkdirs()) {
                return new Result(action, 1, "Unable to create maintainer log directory.");
            }
            OpenHouseBundledRuntimeSync.Result runtimeSync = OpenHouseBundledRuntimeSync.sync(context);
            String assetBody = loadAsset("maintainer/" + action.assetName)
                .replace("__PORT__", Integer.toString(port));
            tempScript = new File(logDir, "run-" + action.slug + ".sh");
            try (FileOutputStream output = new FileOutputStream(tempScript, false)) {
                output.write(buildWrapperScript(action, runtimeSync.runtimeReport, assetBody)
                    .getBytes(StandardCharsets.UTF_8));
            }
            outputFile = File.createTempFile("openhouse-maintainer-", ".log", context.getCacheDir());
            ProcessBuilder builder = new ProcessBuilder(bash.getAbsolutePath(), tempScript.getAbsolutePath());
            builder.directory(new File(TermuxConstants.TERMUX_HOME_DIR_PATH));
            builder.redirectErrorStream(true);
            builder.redirectOutput(ProcessBuilder.Redirect.to(outputFile));
            Map<String, String> environment = builder.environment();
            environment.put("HOME", TermuxConstants.TERMUX_HOME_DIR_PATH);
            environment.put("PREFIX", TermuxConstants.TERMUX_PREFIX_DIR_PATH);
            environment.put("PATH", TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + ":/system/bin");
            environment.put("LD_LIBRARY_PATH", TermuxConstants.TERMUX_LIB_PREFIX_DIR_PATH);
            environment.put("TMPDIR", TermuxConstants.TERMUX_TMP_PREFIX_DIR_PATH);
            environment.put("LANG", "C.UTF-8");
            environment.put("OPENHOUSEAI_NO_AUTO_UBUNTU", "1");
            environment.put("TERMUX_NO_AUTO_UBUNTU", "1");
            environment.put("SMALLPHONEAI_BOOTSTRAP", runtimeSync.bootstrapFile.getAbsolutePath());
            environment.put("SMALLPHONEAI_OFFLINE_PAYLOAD_DIR", runtimeSync.payloadDir.getAbsolutePath());
            if (extraEnvironment != null) {
                for (Map.Entry<String, String> entry : extraEnvironment.entrySet()) {
                    if (entry.getKey() != null && entry.getValue() != null) {
                        environment.put(entry.getKey(), entry.getValue());
                    }
                }
            }
            process = builder.start();
            if (!process.waitFor(action.timeoutSeconds, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return new Result(action, 124, readOutput(outputFile));
            }
            return new Result(action, process.exitValue(), readOutput(outputFile));
        } catch (Exception e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to run explicit maintainer action", e);
            return new Result(action, 1, e.getMessage());
        } finally {
            if (process != null) process.destroy();
            if (tempScript != null) tempScript.delete();
            if (outputFile != null) outputFile.delete();
        }
    }

    private String loadAsset(String path) throws Exception {
        StringBuilder body = new StringBuilder();
        try (InputStream input = context.getAssets().open(path);
             BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) body.append(line).append('\n');
        }
        return body.toString();
    }

    private String buildWrapperScript(Action action, String runtimeReport, String body) {
        StringBuilder script = new StringBuilder();
        script.append("#!/data/data/com.termux/files/usr/bin/bash\nset -euo pipefail\n")
            .append("export HOME=\"${HOME:-/data/data/com.termux/files/home}\"\n")
            .append("export PREFIX=\"${PREFIX:-/data/data/com.termux/files/usr}\"\n")
            .append("export PATH=\"$PREFIX/bin:/system/bin:${PATH:-}\"\n")
            .append("export LD_LIBRARY_PATH=\"$PREFIX/lib:${LD_LIBRARY_PATH:-}\"\n")
            .append("export TMPDIR=\"${TMPDIR:-$PREFIX/tmp}\"\n")
            .append("export SMALLPHONEAI_BOOTSTRAP=\"${SMALLPHONEAI_BOOTSTRAP:-$HOME/.smallphoneai-bootstrap/bootstrap.sh}\"\n")
            .append("export SMALLPHONEAI_OFFLINE_PAYLOAD_DIR=\"${SMALLPHONEAI_OFFLINE_PAYLOAD_DIR:-$HOME/.smallphoneai-bootstrap/apk-assets/openhouse/product-payloads}\"\n")
            .append("LOG_DIR=\"$HOME/.maintainer-logs\"; LOG_FILE=\"$LOG_DIR/")
            .append(action.slug).append(".log\"; mkdir -p \"$LOG_DIR\"; : > \"$LOG_FILE\"\n")
            .append("log(){ printf '%s\\n' \"$1\" | tee -a \"$LOG_FILE\"; }\n")
            .append("run_logged(){ local status=0; set +e; \"$@\" 2>&1 | tee -a \"$LOG_FILE\"; status=${PIPESTATUS[0]}; set -e; return \"$status\"; }\n")
            .append("is_current_ubuntu(){ [ -r /etc/os-release ] && grep -qi ubuntu /etc/os-release; }\n")
            .append("run_ubuntu_logged(){ if is_current_ubuntu; then run_logged \"$@\"; else run_logged proot-distro login ubuntu -- \"$@\"; fi; }\n")
            .append("require_ubuntu(){ if is_current_ubuntu; then return 0; fi; command -v proot-distro >/dev/null 2>&1 && proot-distro login ubuntu -- true >/dev/null 2>&1 || exit 3; }\n")
            .append("log ").append(shellQuote("==> " + action.label)).append('\n');
        if (runtimeReport != null && !runtimeReport.trim().isEmpty()) {
            for (String line : runtimeReport.split("\\r?\\n")) {
                if (!line.isEmpty()) script.append("log ").append(shellQuote(line)).append('\n');
            }
        }
        script.append(body);
        return script.toString();
    }

    private String readOutput(File file) {
        if (file == null || !file.isFile()) return "";
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
            new FileInputStream(file), StandardCharsets.UTF_8))) {
            StringBuilder output = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null && output.length() < 2000) {
                if (output.length() > 0) output.append('\n');
                output.append(line);
            }
            return ServiceManagerRedactor.redact(output.toString());
        } catch (Exception e) {
            return e.getMessage();
        }
    }

    private static String shellQuote(String value) {
        return "'" + (value == null ? "" : value.replace("'", "'\"'\"'")) + "'";
    }

    public enum Action {
        INSTALL_CLAUDE_CODE_UI("install_claude_code_ui", "安装 ClaudeCodeUI / CloudCLI", "install-claude-code-ui.sh", 600),
        START_CLAUDE_CODE_UI("start_claude_code_ui", "启动 ClaudeCodeUI / CloudCLI", "start-claude-code-ui.sh", 75),
        STOP_CLAUDE_CODE_UI("stop_claude_code_ui", "停止 ClaudeCodeUI / CloudCLI", "stop-claude-code-ui.sh", 30),
        RESTART_CLAUDE_CODE_UI("restart_claude_code_ui", "重启 ClaudeCodeUI / CloudCLI", "restart-claude-code-ui.sh", 75),
        START_SMALLPHONE("start_smallphone", "启动 SmallPhoneAI 运行栈", "start-smallphone.sh", 150),
        REPAIR_CONTROL_PLANE("repair_control_plane", "修复控制中枢", "repair-control-plane.sh", 150),
        REPAIR_SMALLPHONE("repair_smallphone", "修复 SmallPhoneAI 运行栈", "repair-smallphone.sh", 600),
        PI_WEB_RESCUE("pi_web_rescue", "AI 救援 pi-web", "pi-web-rescue.sh", 600),
        POST_APK_UPDATE("post_apk_update", "APK 更新资源投递", null, 0);

        public final String slug;
        public final String label;
        final String assetName;
        final int timeoutSeconds;

        Action(String slug, String label, String assetName, int timeoutSeconds) {
            this.slug = slug;
            this.label = label;
            this.assetName = assetName;
            this.timeoutSeconds = timeoutSeconds;
        }
    }

    public static final class Result {
        public final Action action;
        public final int exitCode;
        public final String output;

        Result(Action action, int exitCode, String output) {
            this.action = action;
            this.exitCode = exitCode;
            this.output = output == null ? "" : ServiceManagerRedactor.redact(output);
        }

        public boolean isSuccess() {
            return exitCode == 0;
        }
    }
}
