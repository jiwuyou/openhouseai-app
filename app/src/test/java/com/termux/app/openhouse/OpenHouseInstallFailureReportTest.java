package com.termux.app.openhouse;

import org.junit.Assert;
import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

public class OpenHouseInstallFailureReportTest {

    @Test
    public void serviceManagerReadyTimeoutIsReportedBeforeCompleteLogs() throws Exception {
        File directory = Files.createTempDirectory("openhouse-install-report").toFile();
        File manifest = new File(directory, "manifest_full.log");
        File serviceManager = new File(directory, "service-manager.log");
        File rescue = new File(directory, "pi-web-rescue-30142.log");
        File binary = executable(new File(directory, "service-manager"));
        String longPrefix = repeat("安装输出行\n", 30000);
        write(manifest, longPrefix + "service-manager 在优先时间内未就绪\nLOG_END_MARKER\n");
        write(serviceManager, "health check timed out\n");

        OpenHouseInstallState state = failedState("install_service_manager", "service-manager 在优先时间内未就绪");
        OpenHouseInstallFailureReport.Diagnosis diagnosis = OpenHouseInstallFailureReport.diagnose(
            state, read(manifest), read(serviceManager), new File[] {binary});
        Assert.assertEquals("SERVICE_MANAGER_READY_TIMEOUT", diagnosis.code);

        String report = OpenHouseInstallFailureReport.build(null, state, manifest, serviceManager, rescue);
        Assert.assertTrue(report.indexOf("诊断代码：SERVICE_MANAGER_READY_TIMEOUT")
            < report.indexOf("===== manifest_full.log（完整） ====="));
        Assert.assertTrue(report.contains("LOG_END_MARKER"));
        Assert.assertTrue("report must contain the full log, not the legacy 120k tail",
            report.length() > 120000);
        Assert.assertTrue(report.contains("[日志文件不存在] " + rescue.getAbsolutePath()));
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

        OpenHouseInstallFailureReport.writeLatestFailureReport(report, latest);
        Assert.assertTrue(latest.isFile());
        Assert.assertEquals(report, read(latest));
        Assert.assertTrue(read(latest).contains("PI_WEB_INSTALL_FAILED"));
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
