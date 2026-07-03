package com.termux.app.openhouse;

import android.content.Context;

import com.termux.app.openhouse.servicecontrol.ServiceManagerActionResult;
import com.termux.app.openhouse.servicecontrol.ServiceManagerClient;
import com.termux.app.openhouse.servicecontrol.ServiceManagerControlClient;
import com.termux.app.openhouse.servicecontrol.ServiceManagerRedactor;
import com.termux.app.openhouse.servicecontrol.ServiceManagerService;
import com.termux.shared.logger.Logger;
import com.termux.shared.termux.TermuxConstants;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;

public final class OpenHouseExitAllController {

    private static final String LOG_TAG = "OpenHouseExitAll";
    private static final int PROCESS_STOP_TIMEOUT_SECONDS = 15;

    private final Context context;
    private final ServiceManagerControlClient controlClient;

    public OpenHouseExitAllController(Context context) {
        this.context = context.getApplicationContext();
        this.controlClient = new ServiceManagerControlClient(this.context);
    }

    public ExitReport exitAll() {
        OpenHouseRuntimeSupervisor.setExitAllRequested(context, true);
        StringBuilder report = new StringBuilder();
        int stopped = 0;
        int failed = 0;

        ServiceManagerActionResult groupStop = controlClient.runGroupAction("local-stack", "stop");
        if (groupStop.success()) {
            stopped++;
            appendLine(report, "local-stack：已提交停止。");
        } else {
            appendLine(report, "local-stack：停止失败或未注册，继续逐个停止。"
                + optionalMessage(groupStop.message()));
        }

        try {
            List<ServiceManagerService> services = controlClient.listServices();
            for (ServiceManagerService service : services) {
                String serviceId = ServiceManagerClient.sanitizeServiceId(service.id());
                if (serviceId.isEmpty()) {
                    continue;
                }
                ServiceManagerActionResult result = controlClient.runAction(serviceId, "stop");
                if (result.success()) {
                    stopped++;
                    appendLine(report, serviceId + "：已提交停止。");
                } else {
                    failed++;
                    appendLine(report, serviceId + "：停止失败。" + optionalMessage(result.message()));
                }
            }
        } catch (Exception e) {
            failed++;
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to stop services through service-manager", e);
            appendLine(report, "读取或停止 service-manager 服务失败：" + safeText(e.getMessage()));
        }

        ShellResult shellResult = stopManagedProcesses();
        if (shellResult.exitCode == 0) {
            appendLine(report, "OpenHouse 长期进程清理完成。");
        } else {
            failed++;
            appendLine(report, "OpenHouse 长期进程清理失败，退出码 " + shellResult.exitCode + ".");
        }
        if (!shellResult.output.isEmpty()) {
            appendLine(report, shellResult.output);
        }

        return new ExitReport(
            failed == 0,
            stopped,
            failed,
            "全部退出完成：已请求停止 " + stopped + " 项，失败 " + failed
                + " 项。\n用户数据、模型配置、日志和 payload 均保留。"
                + (report.length() == 0 ? "" : "\n" + report)
        );
    }

    private ShellResult stopManagedProcesses() {
        File bash = new File(TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH, "bash");
        if (!bash.isFile()) {
            return new ShellResult(127, "Termux bash is not installed yet.");
        }

        Process process = null;
        try {
            ProcessBuilder builder = new ProcessBuilder(bash.getAbsolutePath(), "-lc", buildStopScript());
            builder.directory(new File(TermuxConstants.TERMUX_HOME_DIR_PATH));
            builder.redirectErrorStream(true);
            builder.environment().put("HOME", TermuxConstants.TERMUX_HOME_DIR_PATH);
            builder.environment().put("PREFIX", TermuxConstants.TERMUX_PREFIX_DIR_PATH);
            builder.environment().put("PATH", TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + ":/system/bin");
            builder.environment().put("LD_LIBRARY_PATH", TermuxConstants.TERMUX_LIB_PREFIX_DIR_PATH);
            builder.environment().put("TMPDIR", TermuxConstants.TERMUX_TMP_PREFIX_DIR_PATH);
            process = builder.start();
            boolean finished = process.waitFor(PROCESS_STOP_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return new ShellResult(124, readProcessOutput(process));
            }
            return new ShellResult(process.exitValue(), readProcessOutput(process));
        } catch (Exception e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to stop OpenHouse managed processes", e);
            return new ShellResult(1, safeText(e.getMessage()));
        } finally {
            if (process != null) {
                process.destroy();
            }
        }
    }

    private String buildStopScript() {
        StringBuilder script = new StringBuilder();
        script.append("set +e\n");
        script.append("export HOME=\"${HOME:-/data/data/com.termux/files/home}\"\n");
        script.append("export PREFIX=\"${PREFIX:-/data/data/com.termux/files/usr}\"\n");
        script.append("export PATH=\"$PREFIX/bin:/system/bin:${PATH:-}\"\n");
        script.append("log(){ printf '%s\\n' \"$1\"; }\n");
        script.append("kill_openhouse_matches(){\n");
        script.append("  ps -eo pid=,comm=,args= 2>/dev/null | while IFS= read -r line; do\n");
        script.append("    pid=\"${line%% *}\"; args=\"${line#* }\"; [ -n \"$pid\" ] || continue; [ \"$pid\" = \"$$\" ] && continue;\n");
        script.append("    case \" $args \" in\n");
        script.append("      *service-manager-proot-launcher*|*service-manager*' serve '*|*openhouse-pi-agent-sentinel*|*openhouse-pi-web-start*|*cloudcli*' start '*|*dist-server/server/index.js*|*smallphone-active*|*smallphone-frontend*|*smallphone-core*|*openhouse-connect*|*cc-connect*)\n");
        script.append("        kill \"$pid\" >/dev/null 2>&1 && log \"stopped pid=$pid\" ;;\n");
        script.append("    esac\n");
        script.append("  done\n");
        script.append("}\n");
        script.append("kill_openhouse_matches\n");
        script.append("sleep 1\n");
        script.append("kill_openhouse_matches\n");
        script.append("if command -v proot-distro >/dev/null 2>&1; then\n");
        script.append("  proot-distro login ubuntu -- bash -lc 'set +e; pkill -f \"openhouse-pi-agent-sentinel|openhouse-pi-web-start|cloudcli start|smallphone-active|smallphone-frontend|smallphone-core|openhouse-connect|cc-connect|service-manager serve\" 2>/dev/null || true' >/dev/null 2>&1 || true\n");
        script.append("fi\n");
        script.append("log 'exit-all: OpenHouse managed processes requested to stop.'\n");
        script.append("exit 0\n");
        return script.toString();
    }

    private String readProcessOutput(Process process) {
        if (process == null) {
            return "";
        }
        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
            process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (output.length() < 3000) {
                    if (output.length() > 0) {
                        output.append('\n');
                    }
                    output.append(line);
                }
            }
        } catch (Exception e) {
            return safeText(e.getMessage());
        }
        return safeText(output.toString());
    }

    private static String optionalMessage(String message) {
        String clean = safeText(message);
        return clean.isEmpty() ? "" : " " + clean;
    }

    private static void appendLine(StringBuilder builder, String line) {
        if (builder == null || line == null || line.isEmpty()) {
            return;
        }
        if (builder.length() > 0) {
            builder.append('\n');
        }
        builder.append(line);
    }

    private static String safeText(String value) {
        return ServiceManagerRedactor.redact(value == null ? "" : value.trim());
    }

    public static final class ExitReport {
        public final boolean success;
        public final int stoppedCount;
        public final int failedCount;
        public final String message;

        ExitReport(boolean success, int stoppedCount, int failedCount, String message) {
            this.success = success;
            this.stoppedCount = stoppedCount;
            this.failedCount = failedCount;
            this.message = safeText(message);
        }
    }

    private static final class ShellResult {
        final int exitCode;
        final String output;

        ShellResult(int exitCode, String output) {
            this.exitCode = exitCode;
            this.output = safeText(output);
        }
    }
}
