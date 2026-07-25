package com.termux.app.openhouse;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.os.Build;

import com.termux.shared.logger.Logger;
import com.termux.shared.termux.TermuxConstants;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Builds the user-shareable first-install report without invoking shell probes. */
final class OpenHouseInstallFailureReport {

    private static final String LOG_TAG = "OpenHouseInstallReport";
    static final int MAX_LOG_TAIL_BYTES = 512 * 1024;
    static final int MAX_REPORT_BYTES = 2 * 1024 * 1024;
    private static final String REPORT_TRUNCATED_NOTICE =
        "\n\n[报告已达到 2 MiB 上限，后续日志摘录已省略。]\n";
    private static final Pattern EXPLICIT_FAILURE_PATTERN = Pattern.compile(
        "__OPENHOUSE_INSTALL_FAILURE__:([A-Z0-9_]+):([^:]*):(.*)");
    private static final Pattern NAMED_SECRET_PATTERN = Pattern.compile(
        "(?i)([\\\"']?(?:api[_-]?key|authorization|access[_-]?token|refresh[_-]?token|token|password|passwd|secret)[\\\"']?\\s*[:=]\\s*[\\\"']?)([^\\s,\\\"'};]+)");
    private static final Pattern BEARER_SECRET_PATTERN = Pattern.compile(
        "(?i)((?:authorization\\s*[:=]\\s*)?bearer\\s+)([^\\s,;]+)");
    private static final Pattern OPENAI_STYLE_KEY_PATTERN = Pattern.compile(
        "\\bsk-[A-Za-z0-9_-]{8,}\\b");
    private static final Pattern PRIVATE_KEY_PATTERN = Pattern.compile(
        "(?s)-----BEGIN [A-Z ]*PRIVATE KEY-----.*?-----END [A-Z ]*PRIVATE KEY-----");

    private OpenHouseInstallFailureReport() {}

    static String createInstallLogHeader(Context context,
                                         OpenHouseInstallState.TaskScope taskScope,
                                         OpenHouseInstallState.RetryMode retryMode,
                                         int attempt,
                                         String taskLabel) {
        AppVersion version = readAppVersion(context);
        StringBuilder builder = new StringBuilder();
        builder.append("__OPENHOUSE_INSTALL_TASK__:")
            .append(taskScope == null ? OpenHouseInstallState.TaskScope.FULL.value : taskScope.value)
            .append('\n');
        builder.append("__OPENHOUSE_INSTALL_ATTEMPT__:").append(Math.max(1, attempt)).append('\n');
        builder.append("===== OpenHouse AI 首次安装详细日志 =====\n");
        builder.append("开始时间：").append(now()).append('\n');
        builder.append("安装任务：").append(emptyFallback(taskLabel, "一键初始化")).append('\n');
        builder.append("重试模式：")
            .append(retryMode == null ? OpenHouseInstallState.RetryMode.GENERAL.label : retryMode.label)
            .append("；尝试次数：").append(Math.max(1, attempt)).append('\n');
        builder.append("APK：").append(version.versionName).append(" (").append(version.versionCode).append(")\n");
        builder.append("设备：").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
            .append("；Android ").append(Build.VERSION.RELEASE)
            .append(" (SDK ").append(Build.VERSION.SDK_INT).append(")\n");
        builder.append("运行目录：HOME=").append(TermuxConstants.TERMUX_HOME_DIR_PATH)
            .append("；PREFIX=").append(TermuxConstants.TERMUX_PREFIX_DIR_PATH).append('\n');
        builder.append("日志从资源准备前开始记录；失败后不会删除。\n");
        return builder.toString();
    }

    static String build(Context context,
                        OpenHouseInstallState state,
                        File manifestLog,
                        File serviceManagerLog,
                        File piWebRescueLog) {
        OpenHouseInstallState snapshot = state == null ? OpenHouseInstallState.idle() : state;
        BoundedLogRead manifest = readLogTail(manifestLog);
        BoundedLogRead serviceManager = readLogTail(serviceManagerLog);
        BoundedLogRead piWebRescue = readLogTail(piWebRescueLog);
        File[] serviceManagerBinaries = serviceManagerBinaryCandidates();
        Diagnosis diagnosis = diagnose(
            snapshot, manifest.content, serviceManager.content, serviceManagerBinaries);
        AppVersion version = readAppVersion(context);

        StringBuilder builder = new StringBuilder();
        builder.append("OpenHouse AI 首次安装错误报告\n");
        builder.append("生成时间：").append(now()).append("\n\n");

        builder.append("一、错误结论\n");
        builder.append("诊断代码：").append(diagnosis.code).append('\n');
        builder.append("失败阶段：").append(friendlyStage(snapshot.currentStageSlug)).append('\n');
        builder.append("错误原因：").append(diagnosis.reason).append('\n');
        if (!snapshot.safeError.isEmpty()) {
            builder.append("界面错误：").append(abbreviate(snapshot.safeError, 16 * 1024)).append('\n');
        }

        builder.append("\n二、关键证据\n");
        List<String> evidence = collectEvidence(
            snapshot, manifest.content, serviceManager.content, serviceManagerBinaries);
        if (evidence.isEmpty()) {
            builder.append("- 没有提取到明确错误行，请查看下方日志末尾摘录。\n");
        } else {
            for (String line : evidence) {
                builder.append("- ").append(line).append('\n');
            }
        }

        builder.append("\n三、环境和安装状态\n");
        builder.append("APK版本：").append(version.versionName).append(" (versionCode=")
            .append(version.versionCode).append(")\n");
        builder.append("设备：").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append('\n');
        builder.append("Android：").append(Build.VERSION.RELEASE).append(" (SDK ")
            .append(Build.VERSION.SDK_INT).append(")\n");
        builder.append("状态：").append(snapshot.status.value)
            .append("；进度：").append(snapshot.percent).append('%')
            .append("；attempt：").append(snapshot.attempt).append('\n');
        builder.append("任务：").append(snapshot.taskScope.value)
            .append("；阶段：").append(snapshot.currentStageSlug).append('\n');
        builder.append("HOME：").append(TermuxConstants.TERMUX_HOME_DIR_PATH).append('\n');
        builder.append("PREFIX：").append(TermuxConstants.TERMUX_PREFIX_DIR_PATH).append('\n');
        builder.append("service-manager 配置：")
            .append(fileStatus(new File(TermuxConstants.TERMUX_HOME_DIR_PATH,
                ".config/openhouseai/service-manager/config.json"))).append('\n');
        for (File binary : serviceManagerBinaries) {
            builder.append("service-manager 候选：").append(fileStatus(binary)).append('\n');
        }
        builder.append("pi-web 30142 marker：")
            .append(fileStatus(new File(TermuxConstants.TERMUX_HOME_DIR_PATH,
                ".smallphoneai/rescue/pi-web-30142.marker"))).append('\n');

        builder.append("\n四、日志文件状态\n");
        builder.append("manifest：").append(fileStatus(manifestLog)).append('\n');
        builder.append("service-manager：").append(fileStatus(serviceManagerLog)).append('\n');
        builder.append("pi-web rescue：").append(fileStatus(piWebRescueLog)).append('\n');

        appendLogExcerpt(builder, "manifest_full.log", manifestLog, manifest);
        appendLogExcerpt(builder, "service-manager.log", serviceManagerLog, serviceManager);
        appendLogExcerpt(builder, "pi-web-rescue-30142.log", piWebRescueLog, piWebRescue);
        return sanitizeAndLimitReport(builder.toString());
    }

    static boolean writeLatestFailureReport(String report, File target) {
        return writePreparedFailureReport(sanitizeAndLimitReport(report), target);
    }

    static String sanitizeAndLimitReport(String report) {
        return enforceReportLimit(redactSecrets(report));
    }

    static boolean writePreparedFailureReport(String preparedReport, File target) {
        if (target == null) {
            return false;
        }
        File absoluteTarget = target.getAbsoluteFile();
        File parent = absoluteTarget.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs() && !parent.isDirectory()) {
            Logger.logError(LOG_TAG, "Failed to create rescue report directory: " + parent);
            return false;
        }
        File temporary = null;
        try {
            String boundedReport = enforceReportLimit(preparedReport);
            temporary = File.createTempFile("." + absoluteTarget.getName() + "-", ".tmp", parent);
            try (FileOutputStream outputStream = new FileOutputStream(temporary, false)) {
                outputStream.write(boundedReport.getBytes(StandardCharsets.UTF_8));
                outputStream.flush();
                outputStream.getFD().sync();
            }
            replaceAtomicallyWithFallback(temporary, absoluteTarget);
            return true;
        } catch (IOException e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to write latest first-install failure report", e);
            return false;
        } finally {
            if (temporary != null && temporary.exists() && !temporary.delete()) {
                Logger.logError(LOG_TAG, "Failed to delete temporary rescue report: " + temporary);
            }
        }
    }

    static Diagnosis diagnose(OpenHouseInstallState state,
                              String manifest,
                              String serviceManager,
                              File[] serviceManagerBinaries) {
        String stage = state == null ? "" : state.currentStageSlug;
        String currentStageFragment = extractCurrentStageFragment(manifest, stage);
        String currentEvidence = (state == null ? "" : state.safeError) + "\n" + currentStageFragment;
        Matcher explicit = EXPLICIT_FAILURE_PATTERN.matcher(currentEvidence);
        Diagnosis explicitDiagnosis = null;
        while (explicit.find()) {
            explicitDiagnosis = new Diagnosis(
                explicit.group(1),
                emptyFallback(explicit.group(3).trim(), "安装准备或启动失败。"));
        }
        if (explicitDiagnosis != null) {
            return explicitDiagnosis;
        }

        boolean binaryExists = anyExecutable(serviceManagerBinaries);
        if (isServiceManagerStage(stage)) {
            Diagnosis diagnosis = diagnoseServiceManagerEvidence(currentEvidence, binaryExists);
            if (diagnosis != null) {
                return diagnosis;
            }
            diagnosis = diagnoseServiceManagerEvidence(serviceManager, binaryExists);
            if (diagnosis != null) {
                return diagnosis;
            }
            if (!binaryExists && "install_service_manager".equals(stage)) {
                return new Diagnosis("SERVICE_MANAGER_BINARY_MISSING",
                    "service-manager 安装阶段结束前没有生成可执行二进制。");
            }
        }

        if ("prepare".equals(stage)) {
            return new Diagnosis("RESOURCE_PREPARATION_FAILED", "APK 内置资源准备、校验或安装任务启动失败。");
        }
        if ("install_wuyou".equals(stage)) {
            return new Diagnosis("WUYOU_INSTALL_FAILED", "wuyou 安装或检查失败。");
        }
        if ("install_termux_node".equals(stage)) {
            return new Diagnosis("NODE_INSTALL_FAILED", "Termux Node.js 安装或版本检查失败。");
        }
        if ("install_pi_agent".equals(stage)) {
            return new Diagnosis("PI_AGENT_INSTALL_FAILED", "pi-agent 安装或完整性检查失败。");
        }
        if ("install_pi_web".equals(stage)) {
            return new Diagnosis("PI_WEB_INSTALL_FAILED", "pi-web 安装或完整性检查失败。");
        }
        if ("start_pi_web_rescue".equals(stage)) {
            return new Diagnosis("PI_WEB_RESCUE_START_FAILED", "pi-web 30142 紧急救援入口启动或健康检查失败。");
        }
        if ("register_pi_services".equals(stage)) {
            return new Diagnosis("PI_SERVICE_REGISTRATION_FAILED", "pi-agent/pi-web 注册到 service-manager 失败。");
        }
        if ("start_smallphone".equals(stage)) {
            return new Diagnosis("MANAGED_PI_WEB_START_FAILED", "正式 pi-web 30141 启动或就绪检查失败。");
        }
        if ("install_openhouse_web".equals(stage)) {
            return new Diagnosis("OPENHOUSE_WEB_INSTALL_FAILED", "OpenHouse Web 安装或注册失败。");
        }
        return new Diagnosis("UNKNOWN_WITH_DIAGNOSTICS",
            "暂时无法仅凭错误摘要准确分类，请根据关键证据和下方日志摘录继续定位。");
    }

    private static Diagnosis diagnoseServiceManagerEvidence(String evidence, boolean binaryExists) {
        String normalized = evidence == null ? "" : evidence.toLowerCase(Locale.ROOT);
        if (containsAny(normalized, "address already in use", "eaddrinuse", "端口被占用", "端口已被", "port occupied")) {
            return new Diagnosis("SERVICE_MANAGER_PORT_OCCUPIED",
                "service-manager 需要的监听端口已被其他进程占用。");
        }
        if (containsAny(normalized, "token mismatch", "认证不匹配", "authentication mismatch",
            "unauthorized", "http 401", "http=401", "status=401", "http 403", "status=403")) {
            return new Diagnosis("SERVICE_MANAGER_TOKEN_MISMATCH",
                "service-manager API 可达，但配置中的认证 token 不匹配或已失效。");
        }
        if (!binaryExists && containsAny(normalized, "找不到 termux native service-manager",
            "找不到可用 service-manager", "service-manager binary missing", "缺少 service-manager")) {
            return new Diagnosis("SERVICE_MANAGER_BINARY_MISSING",
                "没有找到可执行的 Termux native service-manager 二进制。");
        }
        if (containsAny(normalized, "进程提前退出", "process exited", "exited unexpectedly",
            "panic", "fatal", "permission denied", "权限不足", "segmentation fault")) {
            return new Diagnosis("SERVICE_MANAGER_PROCESS_EXITED",
                "service-manager 进程启动后退出；具体退出信息见关键证据和 service-manager.log 摘录。");
        }
        if (containsAny(normalized, "无法启动 service-manager", "service-manager 启动失败",
            "service-manager start failed", "service-manager 未能启动")) {
            return new Diagnosis("SERVICE_MANAGER_START_FAILED",
                "service-manager 启动命令未能成功拉起控制中枢。");
        }
        if (containsAny(normalized, "connection refused", "econnrefused", "端口未监听",
            "port_listening=false", "port not listening")) {
            return new Diagnosis("SERVICE_MANAGER_PORT_NOT_LISTENING",
                "service-manager 的目标端口没有进入监听状态。");
        }
        if (containsAny(normalized, "优先时间内未就绪", "有限等待", "ready timeout",
            "readiness timeout", "timed out", "启动超时", "就绪超时", "暂未就绪")) {
            return new Diagnosis("SERVICE_MANAGER_READY_TIMEOUT",
                "service-manager 在当前等待时间内没有通过就绪检查。");
        }
        return null;
    }

    static String redactSecrets(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        String redacted = PRIVATE_KEY_PATTERN.matcher(value).replaceAll("-----BEGIN PRIVATE KEY-----\n***\n-----END PRIVATE KEY-----");
        redacted = BEARER_SECRET_PATTERN.matcher(redacted).replaceAll("$1***");
        redacted = NAMED_SECRET_PATTERN.matcher(redacted).replaceAll("$1***");
        return OPENAI_STYLE_KEY_PATTERN.matcher(redacted).replaceAll("sk-***");
    }

    private static List<String> collectEvidence(OpenHouseInstallState state,
                                                String manifest,
                                                String serviceManager,
                                                File[] binaries) {
        Set<String> evidence = new LinkedHashSet<>();
        if (state != null && !state.safeError.isEmpty()) {
            evidence.add("界面状态：" + state.safeError);
        }
        for (File binary : binaries) {
            if (binary.isFile()) {
                evidence.add("二进制：" + fileStatus(binary));
            }
        }
        String stage = state == null ? "" : state.currentStageSlug;
        collectRelevantLogLines(extractCurrentStageFragment(manifest, stage), evidence, 14);
        if (isServiceManagerStage(stage)) {
            collectRelevantLogLines(serviceManager, evidence, 14);
        }
        return new ArrayList<>(evidence);
    }

    private static boolean isServiceManagerStage(String stage) {
        return "install_service_manager".equals(stage)
            || "register_pi_services".equals(stage)
            || "start_smallphone".equals(stage);
    }

    private static String extractCurrentStageFragment(String manifest, String stage) {
        if (manifest == null || manifest.isEmpty()) {
            return "";
        }
        String anyStageMarker = "__OPENHOUSE_INSTALL_STAGE__:";
        if (stage == null || stage.isEmpty()) {
            return manifest.indexOf(anyStageMarker) < 0 ? manifest : "";
        }
        String currentMarker = anyStageMarker + stage + ":";
        int start = manifest.lastIndexOf(currentMarker);
        if (start < 0) {
            return manifest.indexOf(anyStageMarker) < 0 ? manifest : "";
        }
        int next = manifest.indexOf(anyStageMarker, start + currentMarker.length());
        return next < 0 ? manifest.substring(start) : manifest.substring(start, next);
    }

    private static void collectRelevantLogLines(String content, Set<String> collector, int maxTotal) {
        if (content == null || content.isEmpty() || collector.size() >= maxTotal) {
            return;
        }
        String[] lines = content.split("\\r?\\n");
        List<String> selected = new ArrayList<>();
        for (int i = lines.length - 1; i >= 0 && selected.size() < 8; i--) {
            String line = lines[i].trim();
            String lower = line.toLowerCase(Locale.ROOT);
            if (line.isEmpty() || line.startsWith("__OPENHOUSE_INSTALL_TASK__")) {
                continue;
            }
            if (containsAny(lower, "error", "failed", "failure", "warn", "fatal", "panic",
                "timeout", "timed out", "refused", "denied", "not ready", "未就绪", "失败",
                "错误", "异常", "退出", "缺少", "找不到", "占用", "不匹配")) {
                selected.add(0, abbreviate(line, 600));
            }
        }
        for (String line : selected) {
            if (collector.size() >= maxTotal) {
                break;
            }
            collector.add(line);
        }
    }

    private static void appendLogExcerpt(StringBuilder builder,
                                         String title,
                                         File file,
                                         BoundedLogRead logRead) {
        builder.append("\n===== ").append(title).append(" 日志末尾摘录 =====\n");
        builder.append("路径：")
            .append(file == null ? "unknown" : file.getAbsolutePath()).append('\n');
        builder.append("原文件大小：");
        if (logRead.originalBytes < 0L) {
            builder.append("未知");
        } else {
            builder.append(logRead.originalBytes).append(" 字节");
        }
        builder.append('\n');
        builder.append("包含字节数：").append(logRead.includedBytes).append(" 字节\n");
        builder.append("截断：").append(logRead.truncated
            ? "是（仅保留文件末尾，单日志上限 " + MAX_LOG_TAIL_BYTES + " 字节）"
            : "否").append('\n');
        if (!logRead.status.isEmpty()) {
            builder.append("状态：").append(logRead.status).append('\n');
        }
        if (!logRead.content.isEmpty()) {
            builder.append("--- 摘录开始 ---\n");
            builder.append(logRead.content);
            if (!logRead.content.endsWith("\n")) {
                builder.append('\n');
            }
            builder.append("--- 摘录结束 ---\n");
        }
        builder.append("===== ").append(title).append(" 日志末尾摘录结束 =====\n");
    }

    private static File[] serviceManagerBinaryCandidates() {
        File home = new File(TermuxConstants.TERMUX_HOME_DIR_PATH);
        return new File[] {
            new File(TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH, "service-manager"),
            new File(home, ".local/bin/service-manager"),
            new File(home, "smallphoneai-repos/service-manager/service-manager"),
            new File(home, "smallphoneai-repos/service-manager/target/release/service-manager")
        };
    }

    private static boolean anyExecutable(File[] files) {
        if (files == null) {
            return false;
        }
        for (File file : files) {
            if (file != null && file.isFile() && file.canExecute()) {
                return true;
            }
        }
        return false;
    }

    private static BoundedLogRead readLogTail(File file) {
        if (file == null || !file.exists()) {
            return BoundedLogRead.unavailable(-1L, "日志文件不存在");
        }
        if (!file.isFile()) {
            return BoundedLogRead.unavailable(file.length(), "读取失败：路径不是普通文件");
        }
        if (!file.canRead()) {
            return BoundedLogRead.unavailable(file.length(), "读取失败：日志文件不可读");
        }
        try (RandomAccessFile randomAccessFile = new RandomAccessFile(file, "r")) {
            long originalBytes = randomAccessFile.length();
            int requestedBytes = (int) Math.min(originalBytes, MAX_LOG_TAIL_BYTES);
            long start = Math.max(0L, originalBytes - requestedBytes);
            byte[] buffer = new byte[requestedBytes];
            randomAccessFile.seek(start);
            int totalRead = 0;
            while (totalRead < requestedBytes) {
                int count = randomAccessFile.read(buffer, totalRead, requestedBytes - totalRead);
                if (count < 0) {
                    break;
                }
                totalRead += count;
            }
            int contentOffset = start > 0L ? skipLeadingUtf8ContinuationBytes(buffer, totalRead) : 0;
            int includedBytes = Math.max(0, totalRead - contentOffset);
            String content = new String(buffer, contentOffset, includedBytes, StandardCharsets.UTF_8);
            return new BoundedLogRead(
                content,
                originalBytes,
                includedBytes,
                start > 0L || contentOffset > 0,
                originalBytes == 0L ? "日志文件为空" : "");
        } catch (IOException e) {
            return BoundedLogRead.unavailable(
                file.length(),
                "读取失败：" + e.getClass().getSimpleName() + ": " + emptyFallback(e.getMessage(), "unknown"));
        }
    }

    private static int skipLeadingUtf8ContinuationBytes(byte[] bytes, int length) {
        int offset = 0;
        while (offset < length && (bytes[offset] & 0xC0) == 0x80) {
            offset++;
        }
        return offset;
    }

    private static void replaceAtomicallyWithFallback(File source, File target) throws IOException {
        try {
            Files.move(source.toPath(), target.toPath(),
                StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException atomicMoveFailure) {
            try {
                Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException fallbackFailure) {
                fallbackFailure.addSuppressed(atomicMoveFailure);
                throw fallbackFailure;
            }
        }
    }

    private static String enforceReportLimit(String report) {
        String value = report == null ? "" : report;
        if (utf8Length(value) <= MAX_REPORT_BYTES) {
            return value;
        }
        int noticeBytes = utf8Length(REPORT_TRUNCATED_NOTICE);
        return utf8Prefix(value, Math.max(0, MAX_REPORT_BYTES - noticeBytes))
            + REPORT_TRUNCATED_NOTICE;
    }

    private static String utf8Prefix(String value, int maxBytes) {
        if (value == null || value.isEmpty() || maxBytes <= 0) {
            return "";
        }
        int bytes = 0;
        int offset = 0;
        while (offset < value.length()) {
            int codePoint = value.codePointAt(offset);
            int codePointBytes = utf8Length(codePoint);
            if (bytes + codePointBytes > maxBytes) {
                break;
            }
            bytes += codePointBytes;
            offset += Character.charCount(codePoint);
        }
        return value.substring(0, offset);
    }

    private static int utf8Length(String value) {
        if (value == null || value.isEmpty()) {
            return 0;
        }
        int bytes = 0;
        for (int offset = 0; offset < value.length();) {
            int codePoint = value.codePointAt(offset);
            bytes += utf8Length(codePoint);
            offset += Character.charCount(codePoint);
        }
        return bytes;
    }

    private static int utf8Length(int codePoint) {
        if (codePoint <= 0x7F) {
            return 1;
        }
        if (codePoint <= 0x7FF) {
            return 2;
        }
        return codePoint <= 0xFFFF ? 3 : 4;
    }

    private static String fileStatus(File file) {
        if (file == null) {
            return "unknown";
        }
        if (!file.exists()) {
            return file.getAbsolutePath() + " [missing]";
        }
        return file.getAbsolutePath() + " [size=" + file.length()
            + ", readable=" + file.canRead() + ", executable=" + file.canExecute() + ']';
    }

    private static String friendlyStage(String slug) {
        if (slug == null || slug.isEmpty()) {
            return "未知阶段";
        }
        switch (slug) {
            case "prepare": return "准备本机目录和 APK 资源 (prepare)";
            case "termux_packages": return "安装 Termux 基础包 (termux_packages)";
            case "install_wuyou": return "安装 wuyou (install_wuyou)";
            case "install_termux_node": return "安装 Termux Node.js (install_termux_node)";
            case "install_pi_agent": return "安装 pi-agent (install_pi_agent)";
            case "install_pi_web": return "安装 pi-web (install_pi_web)";
            case "start_pi_web_rescue": return "启动紧急救援 30142 (start_pi_web_rescue)";
            case "install_service_manager": return "安装 service-manager (install_service_manager)";
            case "register_pi_services": return "注册 pi 服务 (register_pi_services)";
            case "start_smallphone": return "启动正式 pi-web 30141 (start_smallphone)";
            case "install_openhouse_web": return "安装 OpenHouse Web (install_openhouse_web)";
            default: return slug;
        }
    }

    private static AppVersion readAppVersion(Context context) {
        if (context == null) {
            return new AppVersion("unknown", -1L);
        }
        try {
            PackageInfo packageInfo = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            long versionCode = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                ? packageInfo.getLongVersionCode()
                : packageInfo.versionCode;
            return new AppVersion(emptyFallback(packageInfo.versionName, "unknown"), versionCode);
        } catch (Exception e) {
            return new AppVersion("unknown", -1L);
        }
    }

    private static String now() {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(new Date());
    }

    private static boolean containsAny(String text, String... needles) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        for (String needle : needles) {
            if (needle != null && !needle.isEmpty() && text.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    private static String emptyFallback(String value, String fallback) {
        return value == null || value.trim().isEmpty() ? fallback : value.trim();
    }

    private static String abbreviate(String value, int maxChars) {
        if (value == null || value.length() <= maxChars) {
            return value == null ? "" : value;
        }
        return value.substring(0, maxChars) + "...";
    }

    static final class Diagnosis {
        final String code;
        final String reason;

        Diagnosis(String code, String reason) {
            this.code = code;
            this.reason = reason;
        }
    }

    private static final class BoundedLogRead {
        final String content;
        final long originalBytes;
        final int includedBytes;
        final boolean truncated;
        final String status;

        BoundedLogRead(String content,
                       long originalBytes,
                       int includedBytes,
                       boolean truncated,
                       String status) {
            this.content = content == null ? "" : content;
            this.originalBytes = originalBytes;
            this.includedBytes = Math.max(0, includedBytes);
            this.truncated = truncated;
            this.status = status == null ? "" : status;
        }

        static BoundedLogRead unavailable(long originalBytes, String status) {
            return new BoundedLogRead("", originalBytes, 0, false, status);
        }
    }

    private static final class AppVersion {
        final String versionName;
        final long versionCode;

        AppVersion(String versionName, long versionCode) {
            this.versionName = versionName;
            this.versionCode = versionCode;
        }
    }
}
