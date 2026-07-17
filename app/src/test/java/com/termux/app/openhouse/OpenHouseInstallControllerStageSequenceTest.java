package com.termux.app.openhouse;

import org.junit.Assert;
import org.junit.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class OpenHouseInstallControllerStageSequenceTest {

    @Test
    public void runtimeEnvironmentSequenceInstallsAndStartsRuntimeComponentsOnce() throws Exception {
        Assert.assertEquals(Arrays.asList(
            "PREPARE",
            "TERMUX_PACKAGES",
            "INSTALL_WUYOU",
            "INSTALL_TERMUX_NODE",
            "INSTALL_PI_AGENT",
            "INSTALL_PI_WEB",
            "START_PI_WEB_RESCUE",
            "INSTALL_SERVICE_MANAGER",
            "REGISTER_PI_SERVICES",
            "START_SMALLPHONE",
            "INSTALL_OPENHOUSE_WEB",
            "INSTALL_UBUNTU",
            "UBUNTU_PACKAGES"
        ), stageNames("RUNTIME_ENVIRONMENT_STAGE_SEQUENCE"));
    }

    @Test
    public void aiFeaturesSequenceOnlyRestartsRegistrationBeforeAiStages() throws Exception {
        List<String> stages = stageNames("AI_FEATURES_STAGE_SEQUENCE");

        Assert.assertEquals(Arrays.asList(
            "START_SMALLPHONE",
            "INSTALL_OPENHOUSE_WEB",
            "INSTALL_NODE",
            "SYNC_OFFICIAL_DOCS",
            "INSTALL_AIONUI",
            "SYNC_OPENHOUSE_REGISTRY"
        ), stages);
        Assert.assertFalse(stages.contains("INSTALL_SERVICE_MANAGER"));
    }

    @Test
    public void fullSequenceDoesNotConfigureUbuntuEntryMode() throws Exception {
        Assert.assertEquals(Arrays.asList(
            "PREPARE",
            "TERMUX_PACKAGES",
            "INSTALL_WUYOU",
            "INSTALL_TERMUX_NODE",
            "INSTALL_PI_AGENT",
            "INSTALL_PI_WEB",
            "START_PI_WEB_RESCUE",
            "INSTALL_SERVICE_MANAGER",
            "REGISTER_PI_SERVICES",
            "START_SMALLPHONE",
            "INSTALL_OPENHOUSE_WEB",
            "INSTALL_UBUNTU",
            "UBUNTU_PACKAGES",
            "INSTALL_NODE",
            "SYNC_OFFICIAL_DOCS",
            "INSTALL_AIONUI",
            "SYNC_OPENHOUSE_REGISTRY"
        ), stageNames("FULL_STAGE_SEQUENCE"));
    }

    @Test
    public void runtimeReadinessDoesNotRequireUbuntuEntryMode() {
        OpenHouseStatus status = new OpenHouseStatus(
            true, false, true, true, false, false,
            true, false, false, false, false, false,
            true, true, true, false, false, false,
            false, false, false, false, false, false,
            "", false, ""
        );

        Assert.assertTrue(status.isRuntimeEnvironmentPrepared());
        Assert.assertEquals("准备 Ubuntu Node.js 24 LTS 工作台运行时", status.getNextStepLabel());
    }

    @Test
    public void configureUbuntuEntryRemainsManualButIsExcludedFromOneClickFlow() throws Exception {
        String controller = source("app/src/main/java/com/termux/app/openhouse/OpenHouseInstallController.java");
        Assert.assertTrue(controller.contains("CONFIGURE_ENTRY_UBUNTU(\"entry_ubuntu\""));

        String maintenance = source("app/src/main/java/com/termux/app/activities/MaintenanceCenterActivity.java");
        Assert.assertTrue(maintenance.contains(
            "bindStageButton(StageAction.CONFIGURE_ENTRY_UBUNTU, R.id.buttonConfigureEntryUbuntu)"));
        Assert.assertFalse(methodSource(
            maintenance,
            "private List<StageAction> getOneClickStageSequence()",
            "private boolean isCoreOneClickStage")
            .contains("CONFIGURE_ENTRY_UBUNTU"));
        Assert.assertFalse(methodSource(
            maintenance,
            "private boolean isCoreOneClickStage",
            "private void ensureStageAfter")
            .contains("CONFIGURE_ENTRY_UBUNTU"));
    }

    @Test
    public void standaloneAiFeaturesStillRequiresPreparedRuntime() throws Exception {
        Method method = OpenHouseInstallController.class.getDeclaredMethod(
            "requiresPreparedRuntime",
            OpenHouseInstallState.TaskScope.class
        );
        method.setAccessible(true);

        Assert.assertEquals(Boolean.TRUE, method.invoke(null, OpenHouseInstallState.TaskScope.AI_FEATURES));
        Assert.assertEquals(Boolean.FALSE, method.invoke(null, OpenHouseInstallState.TaskScope.RUNTIME_ENVIRONMENT));
        Assert.assertEquals(Boolean.FALSE, method.invoke(null, OpenHouseInstallState.TaskScope.FULL));
    }

    @Test
    public void firstInstallStagesOnWorkerBeforeGeneratingOrStartingInstaller() throws Exception {
        String install = source("app/src/main/java/com/termux/app/openhouse/OpenHouseInstallController.java");
        Assert.assertTrue(install.contains(
            "executor.execute(() -> prepareAndStartInstall(resolvedTaskScope, launchAttempt))"));
        Assert.assertTrue(install.contains(
            "OpenHouseBundledRuntimeSync.sync(\n                context, OpenHouseBundledResourceDelivery.Reason.FIRST_INSTALL)"));

        String worker = methodSource(install, "private void prepareAndStartInstall", "public boolean forceRestartOneClickInstall");
        int stage = worker.indexOf("prepareBundledRuntimeAssets()");
        int generateScript = worker.indexOf("writeScript(tempScript");
        int processBuilder = worker.indexOf("ProcessBuilder processBuilder");
        int processStart = worker.indexOf("processBuilder.start()");
        Assert.assertTrue(stage >= 0);
        Assert.assertTrue(stage < generateScript);
        Assert.assertTrue(generateScript < processBuilder);
        Assert.assertTrue(processBuilder < processStart);
        Assert.assertFalse(methodSource(
            install, "private boolean startInstallInternal", "private void prepareAndStartInstall")
            .contains("prepareBundledRuntimeAssets()"));
    }

    @Test
    public void firstInstallLogExistsBeforeResourcePreparationAndIsNotTruncatedByWrapper() throws Exception {
        String install = source("app/src/main/java/com/termux/app/openhouse/OpenHouseInstallController.java");
        String starter = methodSource(
            install, "private boolean startInstallInternal", "private void prepareAndStartInstall");
        String worker = methodSource(
            install, "private void prepareAndStartInstall", "public boolean forceRestartOneClickInstall");
        String wrapper = methodSource(
            install, "private String buildWrapperScript", "private void appendRuntimeReport");

        Assert.assertTrue(starter.indexOf("resetManifestLogForNewRun(resolvedTaskScope)")
            < starter.indexOf("executor.execute(() -> prepareAndStartInstall"));
        Assert.assertFalse(worker.contains("resetManifestLogForNewRun"));
        Assert.assertTrue(worker.contains("appendManifestFailure("));
        Assert.assertTrue(wrapper.contains("touch \\\"$LOG_FILE\\\""));
        Assert.assertFalse(wrapper.contains(": > \\\"$LOG_FILE\\\""));
    }

    @Test
    public void controllerExposesFailureReportAndFastRescueAvailabilityApis() throws Exception {
        String install = source("app/src/main/java/com/termux/app/openhouse/OpenHouseInstallController.java");
        Assert.assertTrue(install.contains("public String getFailureReportText()"));
        Assert.assertTrue(install.contains("public boolean isPiWebRescueAvailable()"));
        String rescue = methodSource(
            install, "public boolean isPiWebRescueAvailable()", "private void waitForInstallProcess");
        Assert.assertFalse(rescue.contains("ProcessBuilder"));
        Assert.assertFalse(rescue.contains("HttpURLConnection"));
        Assert.assertFalse(rescue.contains("Thread.sleep"));
    }

    @Test
    public void backgroundStagingRemainsRunningUntilItsProcessStarts() throws Exception {
        Assert.assertTrue(OpenHouseInstallController.isLaunchPreparing(3, 3));
        Assert.assertFalse(OpenHouseInstallController.isLaunchPreparing(3, 2));

        String install = source("app/src/main/java/com/termux/app/openhouse/OpenHouseInstallController.java");
        String getter = methodSource(
            install, "public OpenHouseInstallState getState()", "private void scheduleInitialStateLoad");
        Assert.assertTrue(getter.contains("scheduleRunningStatePoll(snapshot)"));
        Assert.assertTrue(getter.contains("return snapshot;"));
        Assert.assertFalse(getter.contains("readRunningStateFromLog"));

        String pollWorker = methodSource(
            install, "private void pollRunningStateInBackground", "private void scheduleFollowUpForState");
        Assert.assertTrue(pollWorker.contains(
            "isLaunchPreparing(scheduledAttempt, preparingAttempt)"));

        String starter = methodSource(install, "private boolean startInstallInternal", "private void prepareAndStartInstall");
        Assert.assertTrue(starter.indexOf("preparingAttempt = currentAttempt")
            < starter.indexOf("executor.execute(() -> prepareAndStartInstall"));

        String worker = methodSource(install, "private void prepareAndStartInstall", "public boolean forceRestartOneClickInstall");
        Assert.assertTrue(worker.indexOf("prepareBundledRuntimeAssets()")
            < worker.indexOf("processBuilder.start()"));
        Assert.assertTrue(worker.contains("clearPreparingAttemptIfMatchesLocked(launchAttempt)"));
        Assert.assertTrue(install.contains(
            "if (preparingAttempt == launchAttempt) {\n            preparingAttempt = 0;\n        }"));
    }

    @Test
    public void firstInstallAndAionUiReceiveOnlyVersionedResourcePaths() throws Exception {
        String install = source("app/src/main/java/com/termux/app/openhouse/OpenHouseInstallController.java");
        Assert.assertTrue(install.contains(
            "environment.put(\"SMALLPHONEAI_BOOTSTRAP\", runtimeSync.bootstrapFile.getAbsolutePath())"));
        Assert.assertTrue(install.contains(
            "environment.put(\"SMALLPHONEAI_OFFLINE_PAYLOAD_DIR\", runtimeSync.payloadDir.getAbsolutePath())"));
        Assert.assertTrue(install.contains(
            "environment.put(\"OPENHOUSEAI_MAINTAINER_DIR\", runtimeSync.maintainerDir.getAbsolutePath())"));
        Assert.assertFalse(install.contains(".smallphoneai-bootstrap/apk-assets"));
    }

    @Test
    public void maintenanceUsesPreparedResultWithoutRepublishingOrOverwritingMarker() throws Exception {
        String maintenance = source("app/src/main/java/com/termux/app/activities/MaintenanceCenterActivity.java");
        Assert.assertTrue(maintenance.contains("OpenHouseBundledRuntimeSync.prepareExisting(this)"));
        Assert.assertFalse(maintenance.contains("OpenHouseBundledRuntimeSync.sync(this)"));
        Assert.assertTrue(maintenance.contains("runtimeSync.bootstrapFile.getAbsolutePath()"));
        Assert.assertTrue(maintenance.contains("runtimeSync.payloadDir.getAbsolutePath()"));
        Assert.assertTrue(maintenance.contains("runtimeSync.maintainerDir.getAbsolutePath()"));
        Assert.assertTrue(maintenance.contains("runtimeSync.scriptsPublicDir.getAbsolutePath()"));
        Assert.assertFalse(maintenance.contains(".smallphoneai-bootstrap/apk-assets"));

        String maintainer = source("app/src/main/java/com/termux/app/openhouse/OpenHouseMaintainerRunner.java");
        Assert.assertTrue(maintainer.contains("if (action == Action.POST_APK_UPDATE)"));
        Assert.assertTrue(maintainer.contains("No installer was started"));
    }

    private static List<String> stageNames(String fieldName) throws Exception {
        Field field = OpenHouseInstallController.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        Object[] stages = (Object[]) field.get(null);
        List<String> names = new ArrayList<>(stages.length);
        for (Object stage : stages) {
            names.add(((Enum<?>) stage).name());
        }
        return names;
    }

    private static String source(String path) throws Exception {
        File file = new File(path);
        if (!file.isFile()) file = new File("..", path);
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }

    private static String methodSource(String source, String startMarker, String endMarker) {
        int start = source.indexOf(startMarker);
        int end = source.indexOf(endMarker, start + startMarker.length());
        Assert.assertTrue("missing start marker: " + startMarker, start >= 0);
        Assert.assertTrue("missing end marker: " + endMarker, end > start);
        return source.substring(start, end);
    }
}
