package com.termux.app.openhouse;

import android.content.Context;

import com.termux.shared.logger.Logger;
import com.termux.shared.termux.TermuxConstants;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Collections;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

public final class OpenHouseMaintainerRunner {

    private static final String LOG_TAG = "OpenHouseMaintainer";
    private static final Pattern SECRET_PATTERN = Pattern.compile("(?i)\\b(api[_-]?key|authorization|bearer|token|password)([=:\"' ]+)([^\\s\"']{8,})");
    private static final Pattern OPENAI_STYLE_KEY_PATTERN = Pattern.compile("\\bsk-[A-Za-z0-9_-]{12,}\\b");

    private final Context context;

    public OpenHouseMaintainerRunner(Context context) {
        this.context = context.getApplicationContext();
    }

    public Result run(Action action, int port) {
        return run(action, port, Collections.emptyMap());
    }

    public Result run(Action action, int port, Map<String, String> extraEnvironment) {
        File tempScript = null;
        File outputFile = null;
        Process process = null;
        try {
            File bash = new File(TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH, "bash");
            if (!bash.isFile()) {
                return new Result(action, 127, "Termux bash is not installed yet.");
            }

            File logDir = new File(TermuxConstants.TERMUX_HOME_DIR_PATH, ".maintainer-logs");
            if (!logDir.exists()) {
                logDir.mkdirs();
            }
            OpenHouseBundledRuntimeSync.Result runtimeSync = OpenHouseBundledRuntimeSync.sync(context);

            String assetBody = loadAsset("maintainer/" + action.assetName)
                .replace("__PORT__", Integer.toString(port))
                .replace("__DEEPSEEK_KEY_FILE__", OpenHouseStatusRepository.getDeepSeekKeyTempFile().getAbsolutePath());
            String wrapper = buildWrapperScript(action.label, action.slug, assetBody);
            tempScript = new File(logDir, "run-" + action.slug + ".sh");
            try (FileOutputStream outputStream = new FileOutputStream(tempScript, false)) {
                outputStream.write(wrapper.getBytes(StandardCharsets.UTF_8));
            }

            outputFile = File.createTempFile("openhouse-maintainer-", ".log", context.getCacheDir());
            ProcessBuilder processBuilder = new ProcessBuilder(bash.getAbsolutePath(), tempScript.getAbsolutePath());
            processBuilder.directory(new File(TermuxConstants.TERMUX_HOME_DIR_PATH));
            processBuilder.redirectErrorStream(true);
            processBuilder.redirectOutput(ProcessBuilder.Redirect.to(outputFile));
            Map<String, String> environment = processBuilder.environment();
            environment.put("HOME", TermuxConstants.TERMUX_HOME_DIR_PATH);
            environment.put("PREFIX", TermuxConstants.TERMUX_PREFIX_DIR_PATH);
            environment.put("PATH", TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + ":/system/bin");
            environment.put("LD_LIBRARY_PATH", TermuxConstants.TERMUX_LIB_PREFIX_DIR_PATH);
            environment.put("TMPDIR", TermuxConstants.TERMUX_TMP_PREFIX_DIR_PATH);
            environment.put("LANG", "C.UTF-8");
            environment.put("OPENHOUSEAI_NO_AUTO_UBUNTU", "1");
            environment.put("SMALLPHONEAI_NO_AUTO_UBUNTU", "1");
            environment.put("TERMUX_NO_AUTO_UBUNTU", "1");
            environment.put("OPENHOUSEAI_DEEPSEEK_KEY_FILE", OpenHouseStatusRepository.getDeepSeekKeyTempFile().getAbsolutePath());
            environment.put("SMALLPHONEAI_BOOTSTRAP", runtimeSync.bootstrapFile.getAbsolutePath());
            environment.put("SMALLPHONEAI_OFFLINE_PAYLOAD_DIR", runtimeSync.payloadDir.getAbsolutePath());
            File manifest = new File(runtimeSync.payloadDir, "manifest.json");
            if (manifest.isFile()) {
                environment.put("SMALLPHONEAI_OFFLINE_PAYLOAD_MANIFEST", manifest.getAbsolutePath());
            }
            if (extraEnvironment != null) {
                for (Map.Entry<String, String> entry : extraEnvironment.entrySet()) {
                    if (entry.getKey() != null && entry.getValue() != null) {
                        environment.put(entry.getKey(), entry.getValue());
                    }
                }
            }

            process = processBuilder.start();
            if (!process.waitFor(action.timeoutSeconds, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return new Result(action, 124, readOutput(outputFile));
            }

            return new Result(action, process.exitValue(), readOutput(outputFile));
        } catch (Exception e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to run OpenHouseAI maintainer action", e);
            return new Result(action, 1, e.getMessage());
        } finally {
            if (process != null) {
                process.destroy();
            }
            if (tempScript != null) {
                tempScript.delete();
            }
            if (outputFile != null) {
                outputFile.delete();
            }
        }
    }

    private String loadAsset(String assetPath) throws Exception {
        StringBuilder builder = new StringBuilder();
        try (InputStream inputStream = context.getAssets().open(assetPath);
             BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                builder.append(line).append('\n');
            }
        }
        return builder.toString();
    }

    private String readOutput(File outputFile) {
        if (outputFile == null || !outputFile.isFile()) {
            return "";
        }

        try {
            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new FileInputStream(outputFile), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (output.length() < 2000) {
                        if (output.length() > 0) output.append('\n');
                        output.append(line);
                    }
                }
            }
            return redactSecrets(output.toString());
        } catch (Exception e) {
            return e.getMessage();
        }
    }

    private String redactSecrets(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }

        String redacted = SECRET_PATTERN.matcher(value).replaceAll("$1$2***");
        return OPENAI_STYLE_KEY_PATTERN.matcher(redacted).replaceAll("sk-***");
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
        builder.append("detect_openhouseai_runtime(){ if is_current_ubuntu; then printf 'ubuntu'; return 0; fi; local probe prefix; prefix=\"${PREFIX:-/data/data/com.termux/files/usr}\"; for probe in \"$prefix/bin/smallphoneai-env-probe\" \"$prefix/bin/openhouseai-env-probe\"; do if [ -x \"$probe\" ]; then \"$probe\" 2>/dev/null | awk -F= '$1==\"SMALLPHONEAI_RUNTIME\" || $1==\"OPENHOUSEAI_RUNTIME\"{print $2; found=1} END{if(!found) exit 1}' && return 0; fi; done; if is_termux; then printf 'termux'; return 0; fi; printf 'unknown'; }\n");
        builder.append("run_environment_probe(){ local probe prefix; prefix=\"${PREFIX:-/data/data/com.termux/files/usr}\"; for probe in \"$prefix/bin/smallphoneai-env-probe\" \"$prefix/bin/openhouseai-env-probe\"; do if [ -x \"$probe\" ]; then log \"正在执行环境探测命令：$probe\"; run_logged \"$probe\" || true; CURRENT_RUNTIME=\"$(detect_openhouseai_runtime)\"; log \"当前运行环境：$CURRENT_RUNTIME\"; return 0; fi; done; log \"环境探测命令不存在，使用内置探测逻辑。\"; CURRENT_RUNTIME=\"$(detect_openhouseai_runtime)\"; log \"当前运行环境：$CURRENT_RUNTIME\"; }\n");
        builder.append("run_ubuntu_logged(){ if is_current_ubuntu; then run_logged \"$@\"; else run_logged proot-distro login ubuntu -- \"$@\"; fi; }\n");
        builder.append("require_ubuntu(){ if is_current_ubuntu; then return 0; fi; if ! command -v proot-distro >/dev/null 2>&1; then log '缺少 proot-distro，请先执行“更新 Termux 软件包”。'; exit 2; fi; if ! proot-distro login ubuntu -- true >/dev/null 2>&1; then log 'Ubuntu 尚未安装，请先执行“下载 Ubuntu”。'; exit 3; fi; }\n");
        builder.append("__maint_finish(){ local exit_code=$?; printf '__TERMUX_MAINT_DONE__:%s:%s\\n' \"$STAGE_SLUG\" \"$exit_code\" | tee -a \"$LOG_FILE\"; }\n");
        builder.append("trap __maint_finish EXIT\n");
        builder.append("log \"==> $STAGE_NAME\"\n");
        builder.append("run_environment_probe\n");
        builder.append(scriptBody);
        if (!scriptBody.endsWith("\n")) {
            builder.append('\n');
        }
        return builder.toString();
    }

    private String shellQuote(String value) {
        if (value == null || value.isEmpty()) {
            return "''";
        }
        return "'" + value.replace("'", "'\"'\"'") + "'";
    }

    public enum Action {
        START("start", "启动 OpenCode", "start-opencode.sh", 75),
        STOP("stop", "停止 OpenCode", "stop-opencode.sh", 30),
        RESTART("restart", "重启 OpenCode", "restart-opencode.sh", 75),
        INSTALL_CLAUDE_CODE_UI("install_claude_code_ui", "安装 ClaudeCodeUI / CloudCLI", "install-claude-code-ui.sh", 600),
        START_CLAUDE_CODE_UI("start_claude_code_ui", "启动 ClaudeCodeUI / CloudCLI", "start-claude-code-ui.sh", 75),
        STOP_CLAUDE_CODE_UI("stop_claude_code_ui", "停止 ClaudeCodeUI / CloudCLI", "stop-claude-code-ui.sh", 30),
        RESTART_CLAUDE_CODE_UI("restart_claude_code_ui", "重启 ClaudeCodeUI / CloudCLI", "restart-claude-code-ui.sh", 75),
        START_SMALLPHONE("start_smallphone", "启动 SmallPhoneAI 运行栈", "start-smallphone.sh", 150),
        REPAIR_CONTROL_PLANE("repair_control_plane", "修复控制中枢", "repair-control-plane.sh", 150),
        REPAIR_SMALLPHONE("repair_smallphone", "修复 SmallPhoneAI 运行栈", "repair-smallphone.sh", 600),
        POST_APK_UPDATE("post_apk_update", "APK 更新后同步核心运行栈", "post-apk-update.sh", 900),
        CONFIGURE_DEEPSEEK("configure_deepseek", "配置 DeepSeek Key", "configure-deepseek-key.sh", 75);

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
            this.output = output == null ? "" : output;
        }

        public boolean isSuccess() {
            return exitCode == 0;
        }
    }
}
