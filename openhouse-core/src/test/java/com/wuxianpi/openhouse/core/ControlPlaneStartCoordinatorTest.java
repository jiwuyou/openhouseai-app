package com.wuxianpi.openhouse.core;

import org.junit.Assert;
import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public class ControlPlaneStartCoordinatorTest {
    @Test
    public void concurrentRequestsShareOneBridgeExecution() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ControlPlaneBridge bridge = listener -> {
            calls.incrementAndGet();
            listener.onOutput("stdout", "starting");
            entered.countDown();
            try {
                release.await(2, TimeUnit.SECONDS);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                return new ControlPlaneCommandResult(130, "", "interrupted");
            }
            return new ControlPlaneCommandResult(0, "started", "");
        };

        AtomicReference<ControlPlaneCommandResult> first = new AtomicReference<>();
        AtomicReference<ControlPlaneCommandResult> second = new AtomicReference<>();
        Thread firstThread = new Thread(() -> first.set(
            ControlPlaneStartCoordinator.start(bridge, "manual")));
        Thread secondThread = new Thread(() -> second.set(
            ControlPlaneStartCoordinator.start(bridge, "foreground")));
        firstThread.start();
        Assert.assertTrue(entered.await(1, TimeUnit.SECONDS));
        secondThread.start();
        Thread.sleep(100L);
        release.countDown();
        firstThread.join(2_000L);
        secondThread.join(2_000L);

        Assert.assertEquals(1, calls.get());
        Assert.assertEquals(0, first.get().exitCode);
        Assert.assertEquals(0, second.get().exitCode);
        Assert.assertTrue(ControlPlaneStartCoordinator.latestTranscript().contains("[stdout] starting"));
        Assert.assertTrue(ControlPlaneStartCoordinator.latestTranscript().contains("[exit] 0"));
    }

    @Test
    public void rawResultKeepsStdoutAndStderrSeparate() {
        ControlPlaneCommandResult result = new ControlPlaneCommandResult(7, "out", "err");

        Assert.assertFalse(result.isSuccess());
        Assert.assertEquals("out", result.stdout);
        Assert.assertEquals("err", result.stderr);
        Assert.assertEquals("out\nerr", result.combinedOutput());
    }
}
