package com.termux.app.openhouse;

import android.content.Context;

import com.termux.shared.termux.TermuxConstants;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;

@RunWith(RobolectricTestRunner.class)
public class OpenHouseInstallControllerFinishedStateRecoveryTest {

    private File termuxFilesDir;
    private File backupTermuxFilesDir;

    @Before
    public void setUpIsolatedTermuxFiles() throws Exception {
        termuxFilesDir = new File(TermuxConstants.TERMUX_FILES_DIR_PATH);
        if (termuxFilesDir.exists()) {
            backupTermuxFilesDir = new File(
                termuxFilesDir.getParentFile(),
                termuxFilesDir.getName() + ".finished-state-recovery-test-" + System.nanoTime()
            );
            Assert.assertTrue("failed to isolate existing Termux files", termuxFilesDir.renameTo(backupTermuxFilesDir));
        }

        File home = new File(TermuxConstants.TERMUX_HOME_DIR_PATH);
        File bin = new File(TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH);
        File docs = new File(home, "openhouseai-docs");
        Assert.assertTrue(home.mkdirs() || home.isDirectory());
        Assert.assertTrue(bin.mkdirs() || bin.isDirectory());
        Assert.assertTrue(docs.mkdirs() || docs.isDirectory());
        Assert.assertTrue(new File(home, "workspace").mkdirs());

        write(new File(bin, "bash"), "#!/bin/sh", "exit 0");
        Assert.assertTrue(new File(bin, "bash").setExecutable(true));
        write(new File(docs, "README.md"), "ready");
        write(new File(docs, "ENVIRONMENT.md"), "ready");
        write(new File(docs, "MODEL_API_SETUP.md"), "ready");
    }

    @After
    public void restoreTermuxFiles() {
        deleteRecursively(termuxFilesDir);
        if (backupTermuxFilesDir != null) {
            Assert.assertTrue("failed to restore existing Termux files", backupTermuxFilesDir.renameTo(termuxFilesDir));
        }
    }

    @Test
    public void failedCachedStateWithUntrackedSuccessfulExitIsEligibleForReconciliation() {
        Assert.assertTrue(OpenHouseInstallController.shouldReconcileSuccessfulExit(
            state(OpenHouseInstallState.Status.FAILED),
            false,
            false,
            0
        ));
    }

    @Test
    public void unsuccessfulOrUnknownExitIsNotEligibleForReconciliation() {
        OpenHouseInstallState failed = state(OpenHouseInstallState.Status.FAILED);

        Assert.assertFalse(OpenHouseInstallController.shouldReconcileSuccessfulExit(
            failed, false, false, 1));
        Assert.assertFalse(OpenHouseInstallController.shouldReconcileSuccessfulExit(
            failed, false, false, -1));
        Assert.assertFalse(OpenHouseInstallController.shouldReconcileSuccessfulExit(
            failed, false, false, null));
    }

    @Test
    public void activeProcessOrFreshMarkerPreventsReconciliation() {
        OpenHouseInstallState failed = state(OpenHouseInstallState.Status.FAILED);

        Assert.assertFalse(OpenHouseInstallController.shouldReconcileSuccessfulExit(
            failed, true, false, 0));
        Assert.assertFalse(OpenHouseInstallController.shouldReconcileSuccessfulExit(
            failed, false, true, 0));
    }

    @Test
    public void onlyFailedCachedStateIsEligibleForReconciliation() {
        for (OpenHouseInstallState.Status status : OpenHouseInstallState.Status.values()) {
            if (status == OpenHouseInstallState.Status.FAILED) {
                continue;
            }
            Assert.assertFalse(status.name(), OpenHouseInstallController.shouldReconcileSuccessfulExit(
                state(status), false, false, 0));
        }
        Assert.assertFalse(OpenHouseInstallController.shouldReconcileSuccessfulExit(
            null, false, false, 0));
    }

    @Test
    public void stateReadReconcilesOnceAndPromotesBeforeCompletionHooks() throws Exception {
        String source = source("app/src/main/java/com/termux/app/openhouse/OpenHouseInstallController.java");
        String getter = methodSource(
            source,
            "public OpenHouseInstallState getState()",
            "private void reconcileFailedStateAfterSuccessfulExit()");
        Assert.assertTrue(getter.contains("reconcileFailedStateAfterSuccessfulExit();"));

        String reconciliation = methodSource(
            source,
            "private void reconcileFailedStateAfterSuccessfulExit()",
            "static boolean shouldReconcileSuccessfulExit");
        Assert.assertTrue(reconciliation.contains(
            "verifyTaskComplete(taskScope, VerificationMode.CHECK_ONCE)"));

        int promoteState = reconciliation.indexOf("updateState(buildState(");
        int completionGuard = reconciliation.indexOf(
            "if (taskScope != OpenHouseInstallState.TaskScope.RUNTIME_ENVIRONMENT)");
        int markCompleted = reconciliation.indexOf("statusRepository.markOneClickInstallCompleted();");
        int finishFirstInstall = reconciliation.indexOf(
            "OpenHousePostUpdateSync.onFirstInstallCompleted(context);");

        Assert.assertTrue(promoteState >= 0);
        Assert.assertTrue(promoteState < completionGuard);
        Assert.assertTrue(completionGuard < markCompleted);
        Assert.assertTrue(markCompleted < finishFirstInstall);
    }

    @Test
    public void runningOrRetryingEntryDoesNotPromoteNewFailureDuringSameStateRead() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        OpenHouseInstallController controller = newController(context);
        OpenHouseStatusRepository repository = statusRepository(controller);
        Assert.assertTrue("test fixture must satisfy runtime readiness",
            repository.isRuntimeEnvironmentPrepared());

        File logDir = new File(TermuxConstants.TERMUX_HOME_DIR_PATH, ".maintainer-logs");
        Assert.assertTrue(logDir.mkdirs() || logDir.isDirectory());
        write(
            new File(logDir, "manifest_full.log"),
            "__OPENHOUSE_INSTALL_TASK__:runtime_environment",
            "__TERMUX_MAINT_DONE__:manifest_full:0"
        );
        Assert.assertFalse(new File(logDir, "manifest_full.running").exists());

        for (OpenHouseInstallState.Status entryStatus : Arrays.asList(
            OpenHouseInstallState.Status.RUNNING,
            OpenHouseInstallState.Status.RETRYING)) {
            setField(controller, "state", state(entryStatus, OpenHouseInstallState.TaskScope.RUNTIME_ENVIRONMENT));
            setField(controller, "currentProcess", null);
            setField(controller, "preparingAttempt", 0);

            OpenHouseInstallState result = controller.getState();

            Assert.assertEquals(entryStatus.name(), OpenHouseInstallState.Status.FAILED, result.status);
            Assert.assertFalse(entryStatus.name(), result.completed);
        }
    }

    private static OpenHouseInstallState state(OpenHouseInstallState.Status status) {
        return state(status, OpenHouseInstallState.TaskScope.FULL);
    }

    private static OpenHouseInstallState state(OpenHouseInstallState.Status status,
                                               OpenHouseInstallState.TaskScope taskScope) {
        return new OpenHouseInstallState(
            status,
            50,
            "phase",
            "detail",
            "stage",
            OpenHouseInstallState.RetryMode.GENERAL,
            1,
            "",
            "",
            taskScope
        );
    }

    private static OpenHouseInstallController newController(Context context) throws Exception {
        Constructor<OpenHouseInstallController> constructor =
            OpenHouseInstallController.class.getDeclaredConstructor(Context.class);
        constructor.setAccessible(true);
        return constructor.newInstance(context);
    }

    private static OpenHouseStatusRepository statusRepository(OpenHouseInstallController controller) throws Exception {
        Field field = OpenHouseInstallController.class.getDeclaredField("statusRepository");
        field.setAccessible(true);
        return (OpenHouseStatusRepository) field.get(controller);
    }

    private static void setField(OpenHouseInstallController controller, String name, Object value) throws Exception {
        Field field = OpenHouseInstallController.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(controller, value);
    }

    private static void write(File file, String... lines) throws Exception {
        Files.write(file.toPath(), Arrays.asList(lines), StandardCharsets.UTF_8);
    }

    private static void deleteRecursively(File file) {
        if (file == null || !file.exists()) {
            return;
        }
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) {
                deleteRecursively(child);
            }
        }
        Assert.assertTrue("failed to delete test path: " + file, file.delete());
    }

    private static String source(String path) throws Exception {
        File file = new File(path);
        if (!file.isFile()) {
            file = new File("..", path);
        }
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
