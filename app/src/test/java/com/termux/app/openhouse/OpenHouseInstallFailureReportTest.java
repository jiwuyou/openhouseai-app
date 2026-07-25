package com.termux.app.openhouse;

import org.junit.Assert;
import org.junit.Test;

import java.io.File;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

public class OpenHouseInstallFailureReportTest {

    @Test
    public void serviceManagerReadyTimeoutIsReportedBeforeBoundedLogTails() throws Exception {
        File directory = Files.createTempDirectory("openhouse-install-report").toFile();
        File manifest = new File(directory, "manifest_full.log");
        File serviceManager = new File(directory, "service-manager.log");
        File rescue = new File(directory, "pi-web-rescue-30142.log");
        File binary = executable(new File(directory, "service-manager"));
        String longPrefix = "PREFIX_MUST_NOT_APPEAR\n" + repeat("安装输出行\n", 50000);
        write(manifest, longPrefix + "service-manager 在优先时间内未就绪\nLOG_END_MARKER\n");
        write(serviceManager, "health check timed out\n");

        OpenHouseInstallState state = failedState("install_service_manager", "service-manager 在优先时间内未就绪");
        OpenHouseInstallFailureReport.Diagnosis diagnosis = OpenHouseInstallFailureReport.diagnose(
            state, read(manifest), read(serviceManager), new File[] {binary});
        Assert.assertEquals("SERVICE_MANAGER_READY_TIMEOUT", diagnosis.code);

        String report = OpenHouseInstallFailureReport.build(null, state, manifest, serviceManager, rescue);
        Assert.assertTrue(report.indexOf("诊断代码：SERVICE_MANAGER_READY_TIMEOUT")
            < report.indexOf("===== manifest_full.log 日志末尾摘录 ====="));
        Assert.assertTrue(report.contains("LOG_END_MARKER"));
        Assert.assertFalse(report.contains("PREFIX_MUST_NOT_APPEAR"));
        Assert.assertTrue(report.contains("截断：是（仅保留文件末尾"));
        Assert.assertTrue(report.contains("状态：日志文件不存在"));
        Assert.assertFalse(report.contains("（完整）"));
    }

    @Test
    public void sparseHugeLogsRetainTailMarkersAndReportExactBounds() throws Exception {
        File directory = Files.createTempDirectory("openhouse-install-huge-report").toFile();
        File manifest = new File(directory, "manifest_full.log");
        File serviceManager = new File(directory, "service-manager.log");
        File rescue = new File(directory, "pi-web-rescue-30142.log");
        long manifestBytes = 128L * 1024L * 1024L;
        long serviceManagerBytes = 1024L * 1024L * 1024L;
        writeSparseWithTail(manifest, manifestBytes,
            "MANIFEST_PREFIX_MUST_NOT_APPEAR\n", "MANIFEST_TAIL_MARKER\n");
        writeSparseWithTail(serviceManager, serviceManagerBytes,
            "SERVICE_PREFIX_MUST_NOT_APPEAR\n", "SERVICE_TAIL_MARKER\n");
        write(rescue, "RESCUE_TAIL_MARKER\n");

        String report = OpenHouseInstallFailureReport.build(
            null,
            failedState("install_service_manager", "service-manager Address already in use"),
            manifest,
            serviceManager,
            rescue);

        Assert.assertTrue(report.contains("诊断代码：SERVICE_MANAGER_PORT_OCCUPIED"));
        Assert.assertTrue(report.contains("MANIFEST_TAIL_MARKER"));
        Assert.assertTrue(report.contains("SERVICE_TAIL_MARKER"));
        Assert.assertTrue(report.contains("RESCUE_TAIL_MARKER"));
        Assert.assertFalse(report.contains("MANIFEST_PREFIX_MUST_NOT_APPEAR"));
        Assert.assertFalse(report.contains("SERVICE_PREFIX_MUST_NOT_APPEAR"));
        Assert.assertTrue(report.contains("原文件大小：" + manifestBytes + " 字节"));
        Assert.assertTrue(report.contains("原文件大小：" + serviceManagerBytes + " 字节"));
        Assert.assertTrue(report.contains(
            "包含字节数：" + OpenHouseInstallFailureReport.MAX_LOG_TAIL_BYTES + " 字节"));
        Assert.assertTrue(report.getBytes(StandardCharsets.UTF_8).length
            <= OpenHouseInstallFailureReport.MAX_REPORT_BYTES);
    }

    @Test
    public void reportHardCapKeepsDiagnosisBeforePathologicalLogData() throws Exception {
        File directory = Files.createTempDirectory("openhouse-install-report-cap").toFile();
        File manifest = new File(directory, "manifest_full.log");
        File serviceManager = new File(directory, "service-manager.log");
        File rescue = new File(directory, "pi-web-rescue-30142.log");
        writeInvalidUtf8Tail(manifest);
        writeInvalidUtf8Tail(serviceManager);
        writeInvalidUtf8Tail(rescue);

        String report = OpenHouseInstallFailureReport.build(
            null,
            failedState("install_service_manager", "service-manager Address already in use"),
            manifest,
            serviceManager,
            rescue);

        Assert.assertTrue(report.startsWith("OpenHouse AI 首次安装错误报告"));
        Assert.assertTrue(report.contains("诊断代码：SERVICE_MANAGER_PORT_OCCUPIED"));
        Assert.assertTrue(report.contains("[报告已达到 2 MiB 上限"));
        Assert.assertTrue(report.getBytes(StandardCharsets.UTF_8).length
            <= OpenHouseInstallFailureReport.MAX_REPORT_BYTES);
    }

    @Test
    public void utf8TailBoundaryDoesNotEmitReplacementForCutContinuationByte() throws Exception {
        File directory = Files.createTempDirectory("openhouse-install-utf8-tail").toFile();
        File manifest = new File(directory, "manifest_full.log");
        byte[] marker = "UTF8_TAIL_MARKER\n".getBytes(StandardCharsets.UTF_8);
        try (RandomAccessFile file = new RandomAccessFile(manifest, "rw")) {
            file.write("中".getBytes(StandardCharsets.UTF_8));
            file.setLength(OpenHouseInstallFailureReport.MAX_LOG_TAIL_BYTES + 2L);
            file.seek(file.length() - marker.length);
            file.write(marker);
        }

        String report = OpenHouseInstallFailureReport.build(
            null,
            failedState("prepare", "初始化失败"),
            manifest,
            new File(directory, "missing-sm.log"),
            new File(directory, "missing-rescue.log"));

        Assert.assertTrue(report.contains("UTF8_TAIL_MARKER"));
        Assert.assertFalse(report.contains("\uFFFD"));
        Assert.assertTrue(report.contains("包含字节数："
            + (OpenHouseInstallFailureReport.MAX_LOG_TAIL_BYTES - 1) + " 字节"));
    }

    @Test
    public void missingAndNonRegularLogsHaveExplicitMetadata() throws Exception {
        File directory = Files.createTempDirectory("openhouse-install-unavailable-log").toFile();
        File missing = new File(directory, "missing.log");
        File nonRegular = new File(directory, "not-a-file");
        File unreadable = new File(directory, "unreadable.log") {
            @Override public boolean exists() { return true; }
            @Override public boolean isFile() { return true; }
            @Override public boolean canRead() { return false; }
            @Override public long length() { return 123L; }
        };
        Assert.assertTrue(nonRegular.mkdir());

        String report = OpenHouseInstallFailureReport.build(
            null,
            failedState("prepare", "初始化失败"),
            missing,
            nonRegular,
            unreadable);

        Assert.assertTrue(report.contains("路径：" + missing.getAbsolutePath()));
        Assert.assertTrue(report.contains("原文件大小：未知"));
        Assert.assertTrue(report.contains("状态：日志文件不存在"));
        Assert.assertTrue(report.contains("路径：" + nonRegular.getAbsolutePath()));
        Assert.assertTrue(report.contains("状态：读取失败：路径不是普通文件"));
        Assert.assertTrue(report.contains("路径：" + unreadable.getAbsolutePath()));
        Assert.assertTrue(report.contains("原文件大小：123 字节"));
        Assert.assertTrue(report.contains("状态：读取失败：日志文件不可读"));
    }

    @Test
    public void serviceManagerSpecificFailuresHaveStableCodes() throws Exception {
        File directory = Files.createTempDirectory("openhouse-install-diagnosis").toFile();
        File binary = executable(new File(directory, "service-manager"));
        OpenHouseInstallState state = failedState("install_service_manager", "failed");

        Assert.assertEquals("SERVICE_MANAGER_PORT_OCCUPIED",
            diagnose(state, "service-manager: Address already in use", binary).code);
        Assert.assertEquals("SERVICE_MANAGER_TOKEN_MISMATCH",
            diagnose(state, "service-manager HTTP 401 token mismatch", binary).code);
        Assert.assertEquals("SERVICE_MANAGER_PROCESS_EXITED",
            diagnose(state, "service-manager process exited unexpectedly", binary).code);
        Assert.assertEquals("SERVICE_MANAGER_START_FAILED",
            diagnose(state, "service-manager 未能启动", binary).code);
        Assert.assertEquals("SERVICE_MANAGER_PORT_NOT_LISTENING",
            diagnose(state, "service-manager connection refused", binary).code);
        Assert.assertEquals("SERVICE_MANAGER_BINARY_MISSING",
            OpenHouseInstallFailureReport.diagnose(
                state, "找不到 Termux native service-manager", "", new File[0]).code);
    }

    @Test
    public void reportRedactsSecretsAndLatestFileContainsActualReportText() throws Exception {
        File directory = Files.createTempDirectory("openhouse-install-redaction").toFile();
        File manifest = new File(directory, "manifest_full.log");
        File latest = new File(directory, "rescue/latest-install-failure.txt");
        write(manifest,
            "password=openhouse123\n"
                + "Authorization: Bearer secret-token-value\n"
                + "api_key=sk-example-secret-key-123456\n");
        OpenHouseInstallState state = failedState("install_pi_web", "password=openhouse123");

        String report = OpenHouseInstallFailureReport.build(null, state, manifest,
            new File(directory, "missing-sm.log"), new File(directory, "missing-rescue.log"));
        Assert.assertFalse(report.contains("openhouse123"));
        Assert.assertFalse(report.contains("secret-token-value"));
        Assert.assertFalse(report.contains("sk-example-secret-key-123456"));
        Assert.assertTrue(report.contains("password=***"));

        write(latest, "stale report");
        OpenHouseInstallFailureReport.writeLatestFailureReport(report, latest);
        Assert.assertTrue(latest.isFile());
        Assert.assertEquals(report, read(latest));
        Assert.assertTrue(read(latest).contains("PI_WEB_INSTALL_FAILED"));
        File[] temporaryFiles = latest.getParentFile().listFiles(
            file -> file.getName().startsWith("." + latest.getName() + "-")
                && file.getName().endsWith(".tmp"));
        Assert.assertNotNull(temporaryFiles);
        Assert.assertEquals(0, temporaryFiles.length);
    }

    @Test
    public void secretsCrossingFinalSizeBoundaryAreRedactedBeforeTruncation() throws Exception {
        File directory = Files.createTempDirectory("openhouse-install-boundary-redaction").toFile();
        File tokenReport = new File(directory, "token-report.txt");
        String tokenPrefix = repeat("x", OpenHouseInstallFailureReport.MAX_REPORT_BYTES - 11) + "\n";
        String token = "sk-" + repeat("A", 64);

        Assert.assertTrue(OpenHouseInstallFailureReport.writeLatestFailureReport(
            tokenPrefix + token, tokenReport));
        String writtenTokenReport = read(tokenReport);
        Assert.assertFalse(writtenTokenReport.contains("sk-AAAAAAA"));
        Assert.assertTrue(writtenTokenReport.contains("sk-***"));
        Assert.assertTrue(tokenReport.length() <= OpenHouseInstallFailureReport.MAX_REPORT_BYTES);

        File privateKeyReport = new File(directory, "private-key-report.txt");
        String privateKeyPrefix = repeat("y", OpenHouseInstallFailureReport.MAX_REPORT_BYTES - 80);
        String privateKey = "-----BEGIN TEST PRIVATE KEY-----\n"
            + repeat("PRIVATE_SECRET_BODY", 20)
            + "\n-----END TEST PRIVATE KEY-----\n";

        Assert.assertTrue(OpenHouseInstallFailureReport.writeLatestFailureReport(
            privateKeyPrefix + privateKey, privateKeyReport));
        String writtenPrivateKeyReport = read(privateKeyReport);
        Assert.assertFalse(writtenPrivateKeyReport.contains("PRIVATE_SECRET_BODY"));
        Assert.assertTrue(writtenPrivateKeyReport.contains("***"));
        Assert.assertTrue(privateKeyReport.length() <= OpenHouseInstallFailureReport.MAX_REPORT_BYTES);
    }

    @Test
    public void explicitPreflightFailureWinsOverGenericStageClassification() {
        OpenHouseInstallState state = failedState("prepare", "初始化失败");
        OpenHouseInstallFailureReport.Diagnosis diagnosis = OpenHouseInstallFailureReport.diagnose(
            state,
            "__OPENHOUSE_INSTALL_FAILURE__:RESOURCE_PREPARATION_FAILED:prepare:payload manifest 校验失败",
            "",
            new File[0]);

        Assert.assertEquals("RESOURCE_PREPARATION_FAILED", diagnosis.code);
        Assert.assertEquals("payload manifest 校验失败", diagnosis.reason);
    }

    @Test
    public void laterStagesIgnoreHistoricalServiceManagerFailures() throws Exception {
        File directory = Files.createTempDirectory("openhouse-install-stage-scope").toFile();
        File binary = executable(new File(directory, "service-manager"));
        String manifest = "__OPENHOUSE_INSTALL_STAGE__:install_service_manager:安装 service-manager\n"
            + "service-manager 在优先时间内未就绪\n"
            + "__OPENHOUSE_INSTALL_STAGE__:install_openhouse_web:安装 OpenHouse Web\n"
            + "openhouse-web payload 解压失败\n";
        String staleServiceManagerLog = "service-manager fatal: permission denied\n";

        OpenHouseInstallFailureReport.Diagnosis openhouseDiagnosis = OpenHouseInstallFailureReport.diagnose(
            failedState("install_openhouse_web", "OpenHouse Web 安装失败"),
            manifest,
            staleServiceManagerLog,
            new File[] {binary});
        Assert.assertEquals("OPENHOUSE_WEB_INSTALL_FAILED", openhouseDiagnosis.code);

        OpenHouseInstallFailureReport.Diagnosis ubuntuDiagnosis = OpenHouseInstallFailureReport.diagnose(
            failedState("install_ubuntu", "Ubuntu 安装失败"),
            manifest + "__OPENHOUSE_INSTALL_STAGE__:install_ubuntu:下载 Linux 系统\n下载失败\n",
            staleServiceManagerLog,
            new File[] {binary});
        Assert.assertEquals("UNKNOWN_WITH_DIAGNOSTICS", ubuntuDiagnosis.code);
    }

    @Test
    public void currentServiceManagerStageBeatsHistoricalAndStaleErrors() throws Exception {
        File directory = Files.createTempDirectory("openhouse-current-sm-stage").toFile();
        File binary = executable(new File(directory, "service-manager"));
        String manifest = "旧日志 permission denied\n"
            + "__OPENHOUSE_INSTALL_STAGE__:install_service_manager:安装 service-manager\n"
            + "service-manager 在优先时间内未就绪\n";

        OpenHouseInstallFailureReport.Diagnosis diagnosis = OpenHouseInstallFailureReport.diagnose(
            failedState("install_service_manager", "service-manager 在优先时间内未就绪"),
            manifest,
            "旧 service-manager process exited unexpectedly\n",
            new File[] {binary});
        Assert.assertEquals("SERVICE_MANAGER_READY_TIMEOUT", diagnosis.code);
    }

    private static OpenHouseInstallFailureReport.Diagnosis diagnose(OpenHouseInstallState state,
                                                                    String log,
                                                                    File binary) {
        return OpenHouseInstallFailureReport.diagnose(state, log, log, new File[] {binary});
    }

    private static OpenHouseInstallState failedState(String stage, String error) {
        return new OpenHouseInstallState(
            OpenHouseInstallState.Status.FAILED,
            50,
            "初始化失败",
            error,
            stage,
            OpenHouseInstallState.RetryMode.GENERAL,
            1,
            "manifest_full.log",
            error,
            OpenHouseInstallState.TaskScope.FULL);
    }

    private static File executable(File file) throws Exception {
        write(file, "binary");
        Assert.assertTrue(file.setExecutable(true));
        return file;
    }

    private static void write(File file, String content) throws Exception {
        File parent = file.getParentFile();
        if (parent != null) {
            Assert.assertTrue(parent.mkdirs() || parent.isDirectory());
        }
        Files.write(file.toPath(), content.getBytes(StandardCharsets.UTF_8));
    }

    private static void writeSparseWithTail(File file,
                                            long length,
                                            String prefix,
                                            String tail) throws Exception {
        byte[] tailBytes = tail.getBytes(StandardCharsets.UTF_8);
        try (RandomAccessFile randomAccessFile = new RandomAccessFile(file, "rw")) {
            randomAccessFile.write(prefix.getBytes(StandardCharsets.UTF_8));
            randomAccessFile.setLength(length);
            randomAccessFile.seek(length - tailBytes.length);
            randomAccessFile.write(tailBytes);
        }
    }

    private static void writeInvalidUtf8Tail(File file) throws Exception {
        byte[] invalidTail = new byte[OpenHouseInstallFailureReport.MAX_LOG_TAIL_BYTES];
        java.util.Arrays.fill(invalidTail, (byte) 0xFF);
        try (RandomAccessFile randomAccessFile = new RandomAccessFile(file, "rw")) {
            randomAccessFile.setLength(128L * 1024L * 1024L);
            randomAccessFile.seek(randomAccessFile.length() - invalidTail.length);
            randomAccessFile.write(invalidTail);
        }
    }

    private static String read(File file) throws Exception {
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }

    private static String repeat(String value, int count) {
        StringBuilder builder = new StringBuilder(value.length() * count);
        for (int i = 0; i < count; i++) {
            builder.append(value);
        }
        return builder.toString();
    }
}
