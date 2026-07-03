package com.termux.app.openhouse;

import android.content.Context;
import android.content.Intent;

import com.termux.app.TermuxService;
import com.termux.app.openhouse.servicecontrol.ServiceManagerActionResult;
import com.termux.app.openhouse.servicecontrol.ServiceManagerClient;
import com.termux.app.openhouse.servicecontrol.ServiceManagerControlClient;
import com.termux.app.openhouse.servicecontrol.ServiceManagerRedactor;
import com.termux.app.openhouse.servicecontrol.ServiceManagerService;
import com.termux.shared.logger.Logger;
import com.termux.shared.termux.TermuxConstants;
import com.termux.shared.termux.TermuxConstants.TERMUX_APP.TERMUX_SERVICE;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
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

    public ExitReport stopRuntimeStack() {
        OpenHouseRuntimeSupervisor.setExitAllRequested(context, true);
        StopRuntimeStackResult stackResult = stopRuntimeStackInternal();
        return new ExitReport(
            stackResult.failedCount == 0,
            stackResult.stoppedCount,
            stackResult.failedCount,
            "运行栈已停止：App 会留在当前界面；本次会话已暂停自动保活；点击“恢复默认核心服务”可恢复默认核心服务。"
                + "\n用户数据、模型配置、日志和 payload 均保留。"
                + formatStopDetails(stackResult.details)
        );
    }

    public ExitReport exitAll() {
        OpenHouseRuntimeSupervisor.setExitAllRequested(context, true);
        StopRuntimeStackResult stackResult = stopRuntimeStackInternal();
        ShellResult termuxStopResult = requestTermuxServiceStop();
        int stopped = stackResult.stoppedCount + (termuxStopResult.exitCode == 0 ? 1 : 0);
        int failed = stackResult.failedCount + (termuxStopResult.exitCode == 0 ? 0 : 1);
        StringBuilder report = new StringBuilder();
        appendLine(report, stackResult.details);
        appendLine(report, termuxStopResult.output);

        return new ExitReport(
            failed == 0,
            stopped,
            failed,
            "全部退出 OpenHouse 已提交：已先停止运行栈，并请求关闭 Termux 前台服务和终端会话；OpenHouse 界面将关闭。"
                + "\n用户数据、模型配置、日志和 payload 均保留。"
                + "\n已请求停止 " + stopped + " 项，失败 " + failed + " 项。"
                + (report.length() == 0 ? "" : "\n" + report)
        );
    }

    private StopRuntimeStackResult stopRuntimeStackInternal() {
        StringBuilder report = new StringBuilder();
        int stopped = 0;
        int failed = 0;
        List<String> controlPlaneServiceIds = new ArrayList<>();

        try {
            for (ServiceManagerService service : controlClient.listServices()) {
                String serviceId = ServiceManagerClient.sanitizeServiceId(service.id());
                if (serviceId.isEmpty()) {
                    continue;
                }
                if (isControlPlaneService(serviceId)) {
                    controlPlaneServiceIds.add(serviceId);
                    continue;
                }
                ServiceStopCount count = stopRegisteredService(serviceId, report);
                stopped += count.stoppedCount;
                failed += count.failedCount;
            }
        } catch (Exception e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to stop services through service-manager", e);
            appendLine(report, "读取 service-manager 服务列表失败，继续停止运行栈进程：" + safeText(e.getMessage()));
        }

        boolean groupStopSuccess = false;
        try {
            ServiceManagerActionResult groupStop = controlClient.runGroupAction("local-stack", "stop");
            if (groupStop.success()) {
                groupStopSuccess = true;
                stopped++;
                appendLine(report, "local-stack：已提交停止。");
            } else {
                appendLine(report, "local-stack：停止失败或未注册，继续清理长期进程。"
                    + optionalMessage(groupStop.message()));
            }
        } catch (Exception e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to stop local-stack group", e);
            appendLine(report, "local-stack：停止请求异常，继续清理长期进程。" + safeText(e.getMessage()));
        }
        if (!groupStopSuccess) {
            for (String serviceId : controlPlaneServiceIds) {
                ServiceStopCount count = stopRegisteredService(serviceId, report);
                stopped += count.stoppedCount;
                failed += count.failedCount;
            }
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

        return new StopRuntimeStackResult(stopped, failed, report.toString());
    }

    private ServiceStopCount stopRegisteredService(String serviceId, StringBuilder report) {
        try {
            ServiceManagerActionResult result = controlClient.runAction(serviceId, "stop");
            if (result.success()) {
                appendLine(report, serviceId + "：已提交停止。");
                return new ServiceStopCount(1, 0);
            }
            appendLine(report, serviceId + "：停止失败。" + optionalMessage(result.message()));
        } catch (Exception e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to stop registered service: " + serviceId, e);
            appendLine(report, serviceId + "：停止请求异常。" + safeText(e.getMessage()));
        }
        return new ServiceStopCount(0, 1);
    }

    private ShellResult requestTermuxServiceStop() {
        try {
            Intent stopIntent = new Intent(context, TermuxService.class)
                .setAction(TERMUX_SERVICE.ACTION_STOP_SERVICE);
            context.startService(stopIntent);
            return new ShellResult(0, "Termux 前台服务和终端会话：已发送停止请求。");
        } catch (Exception e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to request TermuxService stop", e);
            return new ShellResult(1, "Termux 前台服务和终端会话：停止请求发送失败。" + safeText(e.getMessage()));
        }
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
        script.append("  self_pid=\"$$\"; parent_pid=\"${PPID:-}\"\n");
        script.append("  ps -eo pid=,args= 2>/dev/null | while read -r pid args; do\n");
        script.append("    [ -n \"$pid\" ] || continue; case \"$pid\" in *[!0-9]*) continue ;; esac\n");
        script.append("    [ \"$pid\" = \"$self_pid\" ] && continue\n");
        script.append("    [ -n \"$parent_pid\" ] && [ \"$pid\" = \"$parent_pid\" ] && continue\n");
        script.append("    case \" $args \" in\n");
        script.append("      *service-manager-proot-launcher*|*service-manager*' serve '*|*service-manager*' serve --bind '*|*openhouse-pi-agent-sentinel*|*openhouse-pi-web-start*|*cloudcli*' start '*|*dist-server/server/index.js*|*/smallphone-active/*|*smallphone-frontend*|*smallphone-core*|*/openhouse-connect/*|*openhouse-connect*' serve '*|*cc-connect*' serve '*)\n");
        script.append("        kill \"$pid\" >/dev/null 2>&1 && log \"stopped pid=$pid\" ;;\n");
        script.append("    esac\n");
        script.append("  done\n");
        script.append("}\n");
        script.append("kill_openhouse_matches\n");
        script.append("sleep 1\n");
        script.append("kill_openhouse_matches\n");
        script.append("if command -v proot-distro >/dev/null 2>&1; then\n");
        script.append("  proot-distro login ubuntu -- bash -lc '\n");
        script.append("set +e\n");
        script.append("self_pid=\"$$\"; parent_pid=\"${PPID:-}\"\n");
        script.append("ps -eo pid=,args= 2>/dev/null | while read -r pid args; do\n");
        script.append("  [ -n \"$pid\" ] || continue; case \"$pid\" in *[!0-9]*) continue ;; esac\n");
        script.append("  [ \"$pid\" = \"$self_pid\" ] && continue\n");
        script.append("  [ -n \"$parent_pid\" ] && [ \"$pid\" = \"$parent_pid\" ] && continue\n");
        script.append("  case \" $args \" in\n");
        script.append("    *service-manager*' serve '*|*service-manager*' serve --bind '*|*openhouse-pi-agent-sentinel*|*openhouse-pi-web-start*|*cloudcli*' start '*|*/smallphone-active/*|*smallphone-frontend*|*smallphone-core*|*/openhouse-connect/*|*openhouse-connect*' serve '*|*cc-connect*' serve '*) kill \"$pid\" >/dev/null 2>&1 || true ;;\n");
        script.append("  esac\n");
        script.append("done\n");
        script.append("' >/dev/null 2>&1 || true\n");
        script.append("fi\n");
        script.append("log 'runtime-stack-stop: OpenHouse managed processes requested to stop.'\n");
        script.append("exit 0\n");
        return script.toString();
    }

    private boolean isControlPlaneService(String serviceId) {
        String normalized = safeText(serviceId).toLowerCase(Locale.US);
        return normalized.equals("service-manager")
            || normalized.endsWith("-service-manager")
            || normalized.contains("service-manager");
    }

    private static String formatStopDetails(String details) {
        String clean = safeText(details);
        return clean.isEmpty() ? "" : "\n" + clean;
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

    private static final class StopRuntimeStackResult {
        final int stoppedCount;
        final int failedCount;
        final String details;

        StopRuntimeStackResult(int stoppedCount, int failedCount, String details) {
            this.stoppedCount = stoppedCount;
            this.failedCount = failedCount;
            this.details = safeText(details);
        }
    }

    private static final class ServiceStopCount {
        final int stoppedCount;
        final int failedCount;

        ServiceStopCount(int stoppedCount, int failedCount) {
            this.stoppedCount = stoppedCount;
            this.failedCount = failedCount;
        }
    }
}
