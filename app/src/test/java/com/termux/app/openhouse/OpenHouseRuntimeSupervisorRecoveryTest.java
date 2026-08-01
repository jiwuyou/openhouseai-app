package com.termux.app.openhouse;

import org.junit.Assert;
import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

public class OpenHouseRuntimeSupervisorRecoveryTest {

    @Test
    public void foregroundMaintenanceRepairsOnlyTheControlPlaneAndRechecksHealth() throws Exception {
        String source = source();
        String method = methodSource(
            source,
            "private MaintenanceReport maintainDefaultServices(boolean userInitiated)",
            "private OpenHouseMaintainerRunner.Result runControlPlaneRepair()");

        Assert.assertTrue(source.contains("private static final long MIN_FOREGROUND_TICK_MS = 15_000L;"));
        Assert.assertTrue(source.contains("private static final long MIN_REPAIR_INTERVAL_MS = 60_000L;"));
        Assert.assertTrue(source.contains("private static boolean controlPlaneRepairInFlight;"));
        Assert.assertTrue(source.contains("private static boolean shouldAttemptRepair(long now)"));
        Assert.assertTrue(method.contains("int failureCount = recordControlPlaneFailure();"));
        Assert.assertTrue(method.contains("if (shouldAttemptRepair(now))"));
        Assert.assertTrue(method.contains("OpenHouseMaintainerRunner.Result repair = runControlPlaneRepair();"));

        int repair = method.indexOf("OpenHouseMaintainerRunner.Result repair = runControlPlaneRepair();");
        int healthAfterRepair = method.indexOf("health = controlClient.healthCheck();", repair);
        Assert.assertTrue("health must be checked after repair", healthAfterRepair > repair);
        Assert.assertTrue(method.contains("Termux native service-manager 已恢复并通过健康检查。"));
    }

    @Test
    public void manualRecoveryRepairsImmediatelyThenVerifiesHealth() throws Exception {
        String source = source();
        String method = methodSource(
            source,
            "public MaintenanceReport recoverControlPlaneNow()",
            "public static boolean isExitAllRequested(Context context)");

        int repair = method.indexOf("OpenHouseMaintainerRunner.Result repair = runControlPlaneRepair();");
        int health = method.indexOf("ServiceManagerResult health = controlClient.healthCheck();");
        Assert.assertTrue("manual recovery must invoke repair", repair >= 0);
        Assert.assertTrue("manual recovery must verify health after repair", health > repair);
        Assert.assertTrue(method.contains("MaintenanceReport.success(message.toString(), true, true, repairSuccess)"));
        Assert.assertFalse(method.contains("shouldAttemptRepair"));
    }

    @Test
    public void repairIsProcessWideAndDoesNotStartBusinessServices() throws Exception {
        String source = source();

        Assert.assertTrue(source.contains("if (controlPlaneRepairInFlight)"));
        Assert.assertTrue(source.contains("controlPlaneRepairInFlight = true;"));
        Assert.assertTrue(source.contains("controlPlaneRepairInFlight = false;"));
        Assert.assertTrue(source.contains("OpenHouseMaintainerRunner.Action.REPAIR_CONTROL_PLANE"));
        Assert.assertTrue(source.contains("return Collections.emptyList();"));

        Assert.assertFalse(source.contains("DEFAULT_LONG_RUNNING_SERVICES"));
        Assert.assertFalse(source.contains("local-stack"));
        Assert.assertFalse(source.contains("yuanshengwuxianpi"));
        Assert.assertFalse(source.contains("startDefaultServices"));
        Assert.assertFalse(source.contains("MIN_FOREGROUND_TICK_MS = 5_000L"));
        Assert.assertFalse(source.toLowerCase().contains("heartbeat"));
    }

    private static String source() throws Exception {
        String path = "app/src/main/java/com/termux/app/openhouse/OpenHouseRuntimeSupervisor.java";
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
