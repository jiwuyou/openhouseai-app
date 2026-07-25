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
import java.io.RandomAccessFile;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

@RunWith(RobolectricTestRunner.class)
public class OpenHouseInstallControllerFailureReportRecoveryTest {

    private File termuxFilesDir;
    private File backupTermuxFilesDir;
    private ExecutorService recoveryExecutor;
    private OpenHouseInstallController controller;
    private CountDownLatch delayedGeneratorRelease;

    @Before
    public void setUpIsolatedTermuxFiles() throws Exception {
        termuxFilesDir = new File(TermuxConstants.TERMUX_FILES_DIR_PATH);
        if (termuxFilesDir.exists()) {
            backupTermuxFilesDir = new File(
                termuxFilesDir.getParentFile(),
                termuxFilesDir.getName() + ".failure-report-recovery-test-" + System.nanoTime());
            Assert.assertTrue(termuxFilesDir.renameTo(backupTermuxFilesDir));
        }
        Assert.assertTrue(new File(TermuxConstants.TERMUX_HOME_DIR_PATH).mkdirs());
        recoveryExecutor = Executors.newSingleThreadExecutor();
    }

    @After
    public void restoreTermuxFiles() throws Exception {
        if (delayedGeneratorRelease != null) {
            delayedGeneratorRelease.countDown();
        }
        if (controller != null) {
            ExecutorService installExecutor = (ExecutorService) getField(controller, "executor");
            awaitExecutor(installExecutor);
            shutdownAndAwait(installExecutor);
        }
        if (recoveryExecutor != null) {
            shutdownAndAwait(recoveryExecutor);
        }
        deleteRecursively(termuxFilesDir);
        if (backupTermuxFilesDir != null) {
            Assert.assertTrue(backupTermuxFilesDir.renameTo(termuxFilesDir));
        }
    }

    @Test
    public void restoredFailureWithOneGiBHistoricalLogsBuildsBoundedReport() throws Exception {
        File manifest = new File(
            TermuxConstants.TERMUX_HOME_DIR_PATH, ".maintainer-logs/manifest_full.log");
        File serviceManager = new File(
            TermuxConstants.TERMUX_HOME_DIR_PATH, ".smallphoneai/logs/service-manager.log");
        File latestReport = new File(
            TermuxConstants.TERMUX_HOME_DIR_PATH, ".smallphoneai/rescue/latest-install-failure.txt");
        Assert.assertTrue(manifest.getParentFile().mkdirs());
        Assert.assertTrue(serviceManager.getParentFile().mkdirs());
        writeSparseWithTail(
            manifest,
            1024L * 1024L * 1024L,
            "RESTORED_PREFIX_MUST_NOT_APPEAR\n",
            "__OPENHOUSE_INSTALL_TASK__:full\n"
                + "__OPENHOUSE_INSTALL_STAGE__:install_service_manager:安装 service-manager\n"
                + "service-manager Address already in use\n"
                + "RESTORED_MANIFEST_TAIL_MARKER\n"
                + "__TERMUX_MAINT_DONE__:manifest_full:1\n");
        writeSparseWithTail(
            serviceManager,
            1024L * 1024L * 1024L,
            "RESTORED_SERVICE_PREFIX_MUST_NOT_APPEAR\n",
            "service-manager Address already in use\nRESTORED_SERVICE_TAIL_MARKER\n");

        controller = new OpenHouseInstallController(
            RuntimeEnvironment.getApplication(), recoveryExecutor, taskScope -> false);

        awaitCondition(() -> (boolean) getField(controller, "initialStateLoaded"));
        OpenHouseInstallState restored = controller.getState();
        Assert.assertTrue(restored.failed);
        Assert.assertEquals("install_service_manager", restored.currentStageSlug);

        ExecutorService installExecutor = (ExecutorService) getField(controller, "executor");
        awaitExecutor(installExecutor);
        String report = controller.getFailureReportText();

        Assert.assertTrue(report.contains("诊断代码：SERVICE_MANAGER_PORT_OCCUPIED"));
        Assert.assertTrue(report.contains("RESTORED_MANIFEST_TAIL_MARKER"));
        Assert.assertTrue(report.contains("RESTORED_SERVICE_TAIL_MARKER"));
        Assert.assertFalse(report.contains("RESTORED_PREFIX_MUST_NOT_APPEAR"));
        Assert.assertFalse(report.contains("RESTORED_SERVICE_PREFIX_MUST_NOT_APPEAR"));
        Assert.assertTrue(report.getBytes(StandardCharsets.UTF_8).length
            <= OpenHouseInstallFailureReport.MAX_REPORT_BYTES);
        Assert.assertTrue(latestReport.isFile());
        Assert.assertTrue(latestReport.length() <= OpenHouseInstallFailureReport.MAX_REPORT_BYTES);
        Assert.assertEquals(report,
            new String(Files.readAllBytes(latestReport.toPath()), StandardCharsets.UTF_8));
    }

    @Test
    public void olderDelayedGenerationCannotOverwriteNewerFailureReportOrCache() throws Exception {
        BlockingFailureReportGenerator generator = new BlockingFailureReportGenerator();
        delayedGeneratorRelease = generator.releaseFirst;
        controller = new OpenHouseInstallController(
            RuntimeEnvironment.getApplication(), recoveryExecutor, taskScope -> false, generator);
        awaitCondition(() -> (boolean) getField(controller, "initialStateLoaded"));

        OpenHouseInstallState olderFailure = failedState(1, "older failure");
        OpenHouseInstallState newerFailure = failedState(2, "newer failure");
        invokeUpdateState(controller, olderFailure);
        Assert.assertTrue("older generation did not start",
            generator.firstEntered.await(10, TimeUnit.SECONDS));

        invokeUpdateState(controller, newerFailure);
        Assert.assertSame("newer state publication must not wait for old log reads",
            newerFailure, getField(controller, "state"));
        generator.releaseFirst.countDown();

        ExecutorService installExecutor = (ExecutorService) getField(controller, "executor");
        awaitExecutor(installExecutor);
        File latestReport = new File(
            TermuxConstants.TERMUX_HOME_DIR_PATH, ".smallphoneai/rescue/latest-install-failure.txt");
        String diskReport = new String(
            Files.readAllBytes(latestReport.toPath()), StandardCharsets.UTF_8);

        Assert.assertEquals(2, generator.calls.get());
        Assert.assertEquals("NEW_REPORT_REVISION_2", diskReport);
        Assert.assertEquals("NEW_REPORT_REVISION_2", getField(controller, "cachedFailureReportText"));
        Assert.assertEquals("NEW_REPORT_REVISION_2", controller.getFailureReportText());
        Assert.assertFalse(diskReport.contains("OLD_REPORT_REVISION_1"));
    }

    @Test
    public void synchronousGetterJoinsBackgroundSingleFlightGeneration() throws Exception {
        BlockingFailureReportGenerator generator = new BlockingFailureReportGenerator();
        delayedGeneratorRelease = generator.releaseFirst;
        controller = new OpenHouseInstallController(
            RuntimeEnvironment.getApplication(), recoveryExecutor, taskScope -> false, generator);
        awaitCondition(() -> (boolean) getField(controller, "initialStateLoaded"));

        invokeUpdateState(controller, failedState(1, "single-flight failure"));
        Assert.assertTrue(generator.firstEntered.await(10, TimeUnit.SECONDS));

        ExecutorService readerExecutor = Executors.newSingleThreadExecutor();
        AtomicReference<Thread> readerThread = new AtomicReference<>();
        try {
            Future<String> reader = readerExecutor.submit(() -> {
                readerThread.set(Thread.currentThread());
                return controller.getFailureReportText();
            });
            awaitCondition(() -> {
                Thread thread = readerThread.get();
                return thread != null && (thread.getState() == Thread.State.WAITING
                    || thread.getState() == Thread.State.TIMED_WAITING);
            });
            Assert.assertEquals("getter must reuse the background FutureTask", 1, generator.calls.get());

            generator.releaseFirst.countDown();
            Assert.assertEquals("OLD_REPORT_REVISION_1", reader.get(10, TimeUnit.SECONDS));
            Assert.assertEquals(1, generator.calls.get());
        } finally {
            generator.releaseFirst.countDown();
            shutdownAndAwait(readerExecutor);
        }
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

    private static void awaitExecutor(ExecutorService executor) throws Exception {
        Future<?> barrier = executor.submit(() -> { });
        barrier.get(10, TimeUnit.SECONDS);
    }

    private static OpenHouseInstallState failedState(int attempt, String error) {
        return new OpenHouseInstallState(
            OpenHouseInstallState.Status.FAILED,
            50,
            "初始化失败",
            error,
            "install_service_manager",
            OpenHouseInstallState.RetryMode.GENERAL,
            attempt,
            "manifest_full.log",
            error,
            OpenHouseInstallState.TaskScope.FULL);
    }

    private static void invokeUpdateState(OpenHouseInstallController target,
                                          OpenHouseInstallState state) throws Exception {
        Method method = OpenHouseInstallController.class.getDeclaredMethod(
            "updateState", OpenHouseInstallState.class);
        method.setAccessible(true);
        method.invoke(target, state);
    }

    private static void awaitCondition(CheckedCondition condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!condition.value() && System.nanoTime() < deadline) {
            Thread.sleep(10L);
        }
        Assert.assertTrue("condition was not satisfied before timeout", condition.value());
    }

    private static Object getField(OpenHouseInstallController target, String name) throws Exception {
        Field field = OpenHouseInstallController.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private static void shutdownAndAwait(ExecutorService executor) throws Exception {
        executor.shutdown();
        if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
            executor.shutdownNow();
            Assert.assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }
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

    private static final class BlockingFailureReportGenerator
        implements OpenHouseInstallController.FailureReportGenerator {
        final AtomicInteger calls = new AtomicInteger();
        final CountDownLatch firstEntered = new CountDownLatch(1);
        final CountDownLatch releaseFirst = new CountDownLatch(1);

        @Override
        public String build(android.content.Context context,
                            OpenHouseInstallState state,
                            File manifestLog,
                            File serviceManagerLog,
                            File piWebRescueLog) {
            calls.incrementAndGet();
            if (state.attempt == 1) {
                firstEntered.countDown();
                try {
                    if (!releaseFirst.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("older report generation was not released");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("older report generation interrupted", e);
                }
                return "OLD_REPORT_REVISION_1";
            }
            return "NEW_REPORT_REVISION_2";
        }
    }
}
