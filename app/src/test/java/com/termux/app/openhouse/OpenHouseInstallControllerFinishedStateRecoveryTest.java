package com.termux.app.openhouse;

import com.termux.shared.termux.TermuxConstants;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.io.File;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

@RunWith(RobolectricTestRunner.class)
public class OpenHouseInstallControllerFinishedStateRecoveryTest {

    private File termuxFilesDir;
    private File backupTermuxFilesDir;
    private final List<ExecutorService> executors = new ArrayList<>();
    private final List<OpenHouseInstallController> controllers = new ArrayList<>();
    private final List<BlockingVerifier> blockingVerifiers = new ArrayList<>();

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
    public void restoreTermuxFiles() throws Exception {
        for (BlockingVerifier verifier : blockingVerifiers) {
            verifier.release.countDown();
        }
        for (OpenHouseInstallController controller : controllers) {
            awaitControllerIdle(controller);
        }
        for (OpenHouseInstallController controller : controllers) {
            Object installExecutor = getField(controller, "executor");
            if (installExecutor instanceof ExecutorService) {
                shutdownAndAwait((ExecutorService) installExecutor);
            }
        }
        for (ExecutorService executor : executors) {
            shutdownAndAwait(executor);
        }
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
    public void stateReadsAndMainPollOnlyScheduleBackgroundFileInspection() throws Exception {
        String source = controllerSource();
        String getState = methodSource(
            source, "public OpenHouseInstallState getState()", "private void scheduleInitialStateLoad");
        Assert.assertFalse(getState.contains("hasFreshRunningMarker"));
        Assert.assertFalse(getState.contains("readRunningStateFromLog"));
        Assert.assertFalse(getState.contains("readLogTail"));
        Assert.assertTrue(getState.contains("scheduleInitialStateLoad"));
        Assert.assertTrue(getState.contains("scheduleRunningStatePoll"));
        Assert.assertTrue(getState.contains("scheduleFailedStateReconciliation"));

        String poll = source.substring(
            source.indexOf("private final Runnable pollRunnable"),
            source.indexOf("private volatile OpenHouseInstallState state"));
        Assert.assertTrue(poll.contains("scheduleRunningStatePoll(state)"));
        Assert.assertFalse(poll.contains("readRunningStateFromLog"));
        Assert.assertFalse(poll.contains("hasFreshRunningMarker"));

        String constructor = methodSource(
            source,
            "OpenHouseInstallController(Context context,",
            "public void addListener");
        Assert.assertTrue(constructor.contains("this.state = OpenHouseInstallState.idle()"));
        Assert.assertTrue(constructor.contains("scheduleInitialStateLoad(this.state)"));
        Assert.assertFalse(constructor.contains("readInitialStateFromLog"));
        String scheduler = methodSource(
            source, "private void scheduleInitialStateLoad", "private void loadInitialStateInBackground");
        Assert.assertTrue(scheduler.contains("recoveryExecutor.execute"));
    }

    @Test
    public void constructorQueuesInitialLoadWithoutRunningItInline() throws Exception {
        QueueingExecutor queued = new QueueingExecutor();
        OpenHouseInstallController controller = new OpenHouseInstallController(
            RuntimeEnvironment.getApplication(), queued, taskScope -> false);
        controllers.add(controller);

        Assert.assertEquals(1, queued.size());
        Assert.assertEquals(OpenHouseInstallState.Status.PENDING, controller.getState().status);
        Assert.assertEquals(1, queued.size());
        queued.runNext();
        awaitControllerIdle(controller);
        Assert.assertTrue((Boolean) getField(controller, "initialStateLoaded"));
    }

    @Test
    public void constructorReturnsWhileInitialCompletionVerificationIsBlocked() throws Exception {
        File logDir = new File(TermuxConstants.TERMUX_HOME_DIR_PATH, ".maintainer-logs");
        Assert.assertTrue(logDir.mkdirs() || logDir.isDirectory());
        write(
            new File(logDir, "manifest_full.log"),
            "__OPENHOUSE_INSTALL_TASK__:runtime_environment",
            "__TERMUX_MAINT_DONE__:manifest_full:0"
        );
        BlockingVerifier verifier = blockingVerifier(true);
        ExecutorService recoveryExecutor = executor();
        Future<OpenHouseInstallController> created = executor().submit(() ->
            new OpenHouseInstallController(
                RuntimeEnvironment.getApplication(), recoveryExecutor, verifier));
        OpenHouseInstallController controller = created.get(1, TimeUnit.SECONDS);
        controllers.add(controller);

        try {
            Assert.assertEquals(OpenHouseInstallState.Status.PENDING, controller.getState().status);
            Assert.assertTrue(verifier.entered.await(1, TimeUnit.SECONDS));
            Object processLock = getField(controller, "processLock");
            Future<Boolean> acquired = executor().submit(() -> {
                synchronized (processLock) {
                    return true;
                }
            });
            Assert.assertTrue(acquired.get(500, TimeUnit.MILLISECONDS));
        } finally {
            verifier.release.countDown();
            awaitControllerIdle(controller);
        }
        Assert.assertEquals(OpenHouseInstallState.Status.SUCCEEDED, stateOf(controller).status);
    }

    @Test
    public void getStateReturnsOriginalFailureWhileVerifierIsBlocked() throws Exception {
        BlockingVerifier verifier = blockingVerifier(true);
        OpenHouseInstallController controller = controller(verifier);
        OpenHouseInstallState failed = prepareFailedRecovery(controller, 1);
        ExecutorService caller = executor();

        try {
            Future<OpenHouseInstallState> returned = caller.submit(controller::getState);
            Assert.assertSame(failed, returned.get(1, TimeUnit.SECONDS));
            Assert.assertTrue(verifier.entered.await(1, TimeUnit.SECONDS));
            Assert.assertEquals(1L, verifier.release.getCount());
            Assert.assertSame(failed, getField(controller, "state"));
        } finally {
            verifier.release.countDown();
            awaitControllerIdle(controller);
        }
    }

    @Test
    public void runningFreshMarkerAndSuccessfulExitArePolledOffThread() throws Exception {
        BlockingVerifier verifier = blockingVerifier(true);
        OpenHouseInstallController controller = controller(verifier);
        OpenHouseInstallState running = prepareRunningPoll(controller, 1);

        try {
            Future<OpenHouseInstallState> returned = executor().submit(controller::getState);
            Assert.assertSame(running, returned.get(1, TimeUnit.SECONDS));
            Assert.assertTrue(verifier.entered.await(1, TimeUnit.SECONDS));
            Assert.assertEquals(1, verifier.calls.get());

            Object processLock = getField(controller, "processLock");
            Future<Boolean> acquired = executor().submit(() -> {
                synchronized (processLock) {
                    return true;
                }
            });
            Assert.assertTrue(acquired.get(500, TimeUnit.MILLISECONDS));
        } finally {
            verifier.release.countDown();
            awaitControllerIdle(controller);
        }
        Assert.assertEquals(OpenHouseInstallState.Status.SUCCEEDED, stateOf(controller).status);
    }

    @Test
    public void repeatedStateReadsShareOneBlockedRecoveryProbe() throws Exception {
        BlockingVerifier verifier = blockingVerifier(true);
        OpenHouseInstallController controller = controller(verifier);
        OpenHouseInstallState failed = prepareFailedRecovery(controller, 1);

        try {
            Assert.assertSame(failed, controller.getState());
            Assert.assertTrue(verifier.entered.await(1, TimeUnit.SECONDS));
            for (int index = 0; index < 12; index++) {
                Assert.assertSame(failed, controller.getState());
            }
            Assert.assertEquals(1, verifier.calls.get());
        } finally {
            verifier.release.countDown();
            awaitControllerIdle(controller);
        }
    }

    @Test
    public void blockedVerifierDoesNotHoldProcessLock() throws Exception {
        BlockingVerifier verifier = blockingVerifier(true);
        OpenHouseInstallController controller = controller(verifier);
        prepareFailedRecovery(controller, 1);

        try {
            controller.getState();
            Assert.assertTrue(verifier.entered.await(1, TimeUnit.SECONDS));
            Object processLock = getField(controller, "processLock");
            Future<Boolean> acquired = executor().submit(() -> {
                synchronized (processLock) {
                    return true;
                }
            });
            Assert.assertTrue(acquired.get(500, TimeUnit.MILLISECONDS));
        } finally {
            verifier.release.countDown();
            awaitControllerIdle(controller);
        }
    }

    @Test
    public void successfulExitAndReadyVerifierPromoteStateAsynchronously() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        OpenHouseInstallController controller = controller(taskScope -> {
            calls.incrementAndGet();
            return true;
        });
        OpenHouseInstallState failed = prepareFailedRecovery(controller, 1);

        Assert.assertSame(failed, controller.getState());
        awaitCondition(() -> stateOf(controller).status == OpenHouseInstallState.Status.SUCCEEDED);

        OpenHouseInstallState succeeded = stateOf(controller);
        awaitControllerIdle(controller);
        Assert.assertEquals(OpenHouseInstallState.Status.SUCCEEDED, succeeded.status);
        Assert.assertEquals(100, succeeded.percent);
        Assert.assertEquals(1, calls.get());
    }

    @Test
    public void attemptChangeWhileVerifierIsBlockedPreventsOldProbeCommit() throws Exception {
        BlockingVerifier verifier = blockingVerifier(true);
        OpenHouseInstallController controller = controller(verifier);
        OpenHouseInstallState failed = prepareFailedRecovery(controller, 1);

        try {
            controller.getState();
            Assert.assertTrue(verifier.entered.await(1, TimeUnit.SECONDS));
            setField(controller, "currentAttempt", 2);
            verifier.release.countDown();
            awaitControllerIdle(controller);
            Assert.assertSame(failed, stateOf(controller));
            Assert.assertEquals(OpenHouseInstallState.Status.FAILED, stateOf(controller).status);
        } finally {
            verifier.release.countDown();
            awaitControllerIdle(controller);
        }
    }

    @Test
    public void stateSnapshotChangeWhileVerifierIsBlockedPreventsOldProbeCommit() throws Exception {
        BlockingVerifier verifier = blockingVerifier(true);
        OpenHouseInstallController controller = controller(verifier);
        prepareFailedRecovery(controller, 1);
        OpenHouseInstallState replacement = state(
            OpenHouseInstallState.Status.PENDING,
            OpenHouseInstallState.TaskScope.RUNTIME_ENVIRONMENT);

        try {
            controller.getState();
            Assert.assertTrue(verifier.entered.await(1, TimeUnit.SECONDS));
            setField(controller, "state", replacement);
            verifier.release.countDown();
            awaitControllerIdle(controller);
            Assert.assertSame(replacement, stateOf(controller));
            Assert.assertEquals(OpenHouseInstallState.Status.PENDING, stateOf(controller).status);
        } finally {
            verifier.release.countDown();
            awaitControllerIdle(controller);
        }
    }

    @Test
    public void taskScopeChangeWhileVerifierIsBlockedPreventsOldProbeCommit() throws Exception {
        BlockingVerifier verifier = blockingVerifier(true);
        OpenHouseInstallController controller = controller(verifier);
        OpenHouseInstallState failed = prepareFailedRecovery(controller, 1);

        try {
            controller.getState();
            Assert.assertTrue(verifier.entered.await(1, TimeUnit.SECONDS));
            setField(controller, "currentTaskScope", OpenHouseInstallState.TaskScope.FULL);
            verifier.release.countDown();
            awaitControllerIdle(controller);
            Assert.assertSame(failed, stateOf(controller));
        } finally {
            verifier.release.countDown();
            awaitControllerIdle(controller);
        }
    }

    @Test
    public void processAppearingWhileVerifierIsBlockedPreventsOldProbeCommit() throws Exception {
        BlockingVerifier verifier = blockingVerifier(true);
        OpenHouseInstallController controller = controller(verifier);
        OpenHouseInstallState failed = prepareFailedRecovery(controller, 1);

        try {
            controller.getState();
            Assert.assertTrue(verifier.entered.await(1, TimeUnit.SECONDS));
            setField(controller, "currentProcess", new FakeProcess());
            verifier.release.countDown();
            awaitControllerIdle(controller);
            Assert.assertSame(failed, stateOf(controller));
        } finally {
            setField(controller, "currentProcess", null);
            verifier.release.countDown();
            awaitControllerIdle(controller);
        }
    }

    @Test
    public void freshMarkerAppearingWhileVerifierIsBlockedPreventsOldProbeCommit() throws Exception {
        BlockingVerifier verifier = blockingVerifier(true);
        OpenHouseInstallController controller = controller(verifier);
        OpenHouseInstallState failed = prepareFailedRecovery(controller, 1);

        try {
            controller.getState();
            Assert.assertTrue(verifier.entered.await(1, TimeUnit.SECONDS));
            writeFreshRunningMarker(1, OpenHouseInstallState.TaskScope.RUNTIME_ENVIRONMENT);
            verifier.release.countDown();
            awaitControllerIdle(controller);
            Assert.assertSame(failed, stateOf(controller));
        } finally {
            verifier.release.countDown();
            awaitControllerIdle(controller);
        }
    }

    @Test
    public void snapshotAttemptMismatchIsNeverCommitted() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        OpenHouseInstallController controller = controller(taskScope -> {
            calls.incrementAndGet();
            return true;
        });
        OpenHouseInstallState failed = prepareFailedRecovery(controller, 2);
        setField(controller, "currentAttempt", 1);

        Assert.assertSame(failed, controller.getState());
        awaitControllerIdle(controller);
        Assert.assertSame(failed, stateOf(controller));
        Assert.assertEquals(0, calls.get());
    }

    @Test
    public void rejectedRecoverySchedulingCanBeRetried() throws Exception {
        RejectingExecutor rejecting = new RejectingExecutor();
        OpenHouseInstallController controller = new OpenHouseInstallController(
            RuntimeEnvironment.getApplication(), rejecting, taskScope -> true);
        controllers.add(controller);
        prepareFailedRecovery(controller, 1);
        int constructorRejection = rejecting.calls.get();

        controller.getState();
        controller.getState();

        Assert.assertEquals(constructorRejection + 2, rejecting.calls.get());
        Assert.assertFalse(((AtomicBoolean) getField(
            controller, "finishedStateRecoveryInFlight")).get());
        Assert.assertEquals(OpenHouseInstallState.Status.FAILED, stateOf(controller).status);
    }

    @Test
    public void falseVerifierResultCanBeRetried() throws Exception {
        SequencedVerifier verifier = new SequencedVerifier(false, true);
        OpenHouseInstallController controller = controller(verifier);
        OpenHouseInstallState failed = prepareFailedRecovery(controller, 1);

        Assert.assertSame(failed, controller.getState());
        awaitControllerIdle(controller);
        Assert.assertSame(failed, stateOf(controller));

        controller.getState();
        awaitCondition(() -> stateOf(controller).status == OpenHouseInstallState.Status.SUCCEEDED);
        awaitControllerIdle(controller);
        Assert.assertEquals(2, verifier.calls.get());
    }

    @Test
    public void verifierExceptionCanBeRetried() throws Exception {
        SequencedVerifier verifier = new SequencedVerifier(
            new IllegalStateException("fixture verifier failure"), true);
        OpenHouseInstallController controller = controller(verifier);
        OpenHouseInstallState failed = prepareFailedRecovery(controller, 1);

        Assert.assertSame(failed, controller.getState());
        awaitControllerIdle(controller);
        Assert.assertSame(failed, stateOf(controller));

        controller.getState();
        awaitCondition(() -> stateOf(controller).status == OpenHouseInstallState.Status.SUCCEEDED);
        awaitControllerIdle(controller);
        Assert.assertEquals(2, verifier.calls.get());
    }

    @Test
    public void nonRuntimeCompletionHooksRemainAfterSuccessfulStateCommitAndOutsideLock() throws Exception {
        String reconciliation = methodSource(
            controllerSource(),
            "private void reconcileFailedStateAfterSuccessfulExit",
            "private boolean isRecoverySnapshotCurrent");
        int stateCommit = reconciliation.indexOf("updateState(buildState(");
        int hookGuard = reconciliation.indexOf("if (runCompletionHooks)");
        int statusHook = reconciliation.indexOf("statusRepository.markOneClickInstallCompleted();");
        int firstInstallHook = reconciliation.indexOf(
            "OpenHousePostUpdateSync.onFirstInstallCompleted(context);");

        Assert.assertTrue(stateCommit >= 0);
        Assert.assertTrue(stateCommit < hookGuard);
        Assert.assertTrue(hookGuard < statusHook);
        Assert.assertTrue(statusHook < firstInstallHook);
        Assert.assertEquals(1, occurrences(reconciliation,
            "statusRepository.markOneClickInstallCompleted();"));
        Assert.assertEquals(1, occurrences(reconciliation,
            "OpenHousePostUpdateSync.onFirstInstallCompleted(context);"));
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

    private OpenHouseInstallController controller(OpenHouseInstallController.RecoveryVerifier verifier)
        throws Exception {
        ExecutorService recoveryExecutor = executor();
        OpenHouseInstallController controller = new OpenHouseInstallController(
            RuntimeEnvironment.getApplication(), recoveryExecutor, verifier);
        controllers.add(controller);
        awaitControllerIdle(controller);
        return controller;
    }

    private OpenHouseInstallState prepareFailedRecovery(OpenHouseInstallController controller,
                                                        int attempt) throws Exception {
        File logDir = new File(TermuxConstants.TERMUX_HOME_DIR_PATH, ".maintainer-logs");
        Assert.assertTrue(logDir.mkdirs() || logDir.isDirectory());
        write(
            new File(logDir, "manifest_full.log"),
            "__OPENHOUSE_INSTALL_TASK__:runtime_environment",
            "__TERMUX_MAINT_DONE__:manifest_full:0"
        );
        Assert.assertFalse(new File(logDir, "manifest_full.running").exists());
        OpenHouseInstallState failed = new OpenHouseInstallState(
            OpenHouseInstallState.Status.FAILED,
            96,
            "安装未完全就绪",
            "等待异步恢复探测",
            "configure_entry_ubuntu",
            OpenHouseInstallState.RetryMode.GENERAL,
            attempt,
            new File(logDir, "manifest_full.log").getAbsolutePath(),
            "等待异步恢复探测",
            OpenHouseInstallState.TaskScope.RUNTIME_ENVIRONMENT
        );
        setField(controller, "state", failed);
        setField(controller, "currentAttempt", attempt);
        setField(controller, "currentTaskScope", OpenHouseInstallState.TaskScope.RUNTIME_ENVIRONMENT);
        setField(controller, "currentProcess", null);
        setField(controller, "preparingAttempt", 0);
        setField(controller, "initialStateLoaded", true);
        return failed;
    }

    private OpenHouseInstallState prepareRunningPoll(OpenHouseInstallController controller,
                                                     int attempt) throws Exception {
        File logDir = new File(TermuxConstants.TERMUX_HOME_DIR_PATH, ".maintainer-logs");
        Assert.assertTrue(logDir.mkdirs() || logDir.isDirectory());
        write(
            new File(logDir, "manifest_full.log"),
            "__OPENHOUSE_INSTALL_TASK__:runtime_environment",
            "__TERMUX_MAINT_DONE__:manifest_full:0"
        );
        writeFreshRunningMarker(attempt, OpenHouseInstallState.TaskScope.RUNTIME_ENVIRONMENT);
        OpenHouseInstallState running = new OpenHouseInstallState(
            OpenHouseInstallState.Status.RUNNING,
            96,
            "正在配置启动入口",
            "等待后台读取完成状态",
            "configure_entry_ubuntu",
            OpenHouseInstallState.RetryMode.GENERAL,
            attempt,
            new File(logDir, "manifest_full.log").getAbsolutePath(),
            "",
            OpenHouseInstallState.TaskScope.RUNTIME_ENVIRONMENT
        );
        setField(controller, "state", running);
        setField(controller, "currentAttempt", attempt);
        setField(controller, "currentTaskScope", OpenHouseInstallState.TaskScope.RUNTIME_ENVIRONMENT);
        setField(controller, "currentRetryMode", OpenHouseInstallState.RetryMode.GENERAL);
        setField(controller, "currentProcess", null);
        setField(controller, "preparingAttempt", 0);
        setField(controller, "initialStateLoaded", true);
        return running;
    }

    private static void writeFreshRunningMarker(int attempt,
                                                OpenHouseInstallState.TaskScope taskScope) throws Exception {
        File logDir = new File(TermuxConstants.TERMUX_HOME_DIR_PATH, ".maintainer-logs");
        Assert.assertTrue(logDir.mkdirs() || logDir.isDirectory());
        long now = System.currentTimeMillis();
        write(
            new File(logDir, "manifest_full.running"),
            "started_at_ms=" + now,
            "stage_slug=configure_entry_ubuntu",
            "stage_started_at_ms=" + now,
            "remote_schedule=0",
            "retry_mode=general",
            "task_scope=" + taskScope.value,
            "attempt=" + attempt,
            "log_path=" + new File(logDir, "manifest_full.log").getAbsolutePath()
        );
    }

    private ExecutorService executor() {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        executors.add(executor);
        return executor;
    }

    private BlockingVerifier blockingVerifier(boolean result) {
        BlockingVerifier verifier = new BlockingVerifier(result);
        blockingVerifiers.add(verifier);
        return verifier;
    }

    private static OpenHouseInstallState stateOf(OpenHouseInstallController controller) throws Exception {
        return (OpenHouseInstallState) getField(controller, "state");
    }

    private static void awaitControllerIdle(OpenHouseInstallController controller) throws Exception {
        AtomicBoolean initial = (AtomicBoolean) getField(controller, "initialStateLoadInFlight");
        AtomicBoolean running = (AtomicBoolean) getField(controller, "runningStatePollInFlight");
        AtomicBoolean recovery = (AtomicBoolean) getField(controller, "finishedStateRecoveryInFlight");
        awaitCondition(() -> !initial.get() && !running.get() && !recovery.get());
    }

    private static void awaitCondition(CheckedCondition condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (!condition.value() && System.nanoTime() < deadline) {
            Thread.sleep(10L);
        }
        Assert.assertTrue("condition was not satisfied before timeout", condition.value());
    }

    private static void shutdownAndAwait(ExecutorService executor) throws Exception {
        executor.shutdown();
        if (!executor.awaitTermination(3, TimeUnit.SECONDS)) {
            executor.shutdownNow();
            Assert.assertTrue("executor did not terminate", executor.awaitTermination(3, TimeUnit.SECONDS));
        }
    }

    private static String controllerSource() throws Exception {
        File file = new File(
            "app/src/main/java/com/termux/app/openhouse/OpenHouseInstallController.java");
        if (!file.isFile()) {
            file = new File("..", file.getPath());
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

    private static int occurrences(String source, String value) {
        int count = 0;
        int offset = 0;
        while ((offset = source.indexOf(value, offset)) >= 0) {
            count++;
            offset += value.length();
        }
        return count;
    }

    private static Object getField(OpenHouseInstallController controller, String name) throws Exception {
        Field field = OpenHouseInstallController.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(controller);
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

    private interface CheckedCondition {
        boolean value() throws Exception;
    }

    private static final class BlockingVerifier implements OpenHouseInstallController.RecoveryVerifier {
        final AtomicInteger calls = new AtomicInteger();
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final boolean result;

        BlockingVerifier(boolean result) {
            this.result = result;
        }

        @Override
        public boolean verifyOnce(OpenHouseInstallState.TaskScope taskScope) {
            calls.incrementAndGet();
            entered.countDown();
            try {
                Assert.assertTrue("verifier was not released", release.await(3, TimeUnit.SECONDS));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("verifier interrupted", e);
            }
            return result;
        }
    }

    private static final class QueueingExecutor implements Executor {
        private final List<Runnable> tasks = new ArrayList<>();

        @Override
        public synchronized void execute(Runnable command) {
            tasks.add(command);
        }

        synchronized int size() {
            return tasks.size();
        }

        void runNext() {
            Runnable task;
            synchronized (this) {
                Assert.assertFalse("no queued task", tasks.isEmpty());
                task = tasks.remove(0);
            }
            task.run();
        }
    }

    private static final class RejectingExecutor implements Executor {
        final AtomicInteger calls = new AtomicInteger();

        @Override
        public void execute(Runnable command) {
            calls.incrementAndGet();
            throw new RejectedExecutionException("fixture rejection");
        }
    }

    private static final class SequencedVerifier implements OpenHouseInstallController.RecoveryVerifier {
        final AtomicInteger calls = new AtomicInteger();
        private final Object[] outcomes;

        SequencedVerifier(Object... outcomes) {
            this.outcomes = outcomes;
        }

        @Override
        public boolean verifyOnce(OpenHouseInstallState.TaskScope taskScope) {
            int index = calls.getAndIncrement();
            Object outcome = outcomes[Math.min(index, outcomes.length - 1)];
            if (outcome instanceof RuntimeException) {
                throw (RuntimeException) outcome;
            }
            return Boolean.TRUE.equals(outcome);
        }
    }

    private static final class FakeProcess extends Process {
        private final ByteArrayOutputStream output = new ByteArrayOutputStream();

        @Override
        public OutputStream getOutputStream() {
            return output;
        }

        @Override
        public InputStream getInputStream() {
            return new ByteArrayInputStream(new byte[0]);
        }

        @Override
        public InputStream getErrorStream() {
            return new ByteArrayInputStream(new byte[0]);
        }

        @Override
        public int waitFor() {
            return 0;
        }

        @Override
        public int exitValue() {
            return 0;
        }

        @Override
        public void destroy() {
        }
    }
}
