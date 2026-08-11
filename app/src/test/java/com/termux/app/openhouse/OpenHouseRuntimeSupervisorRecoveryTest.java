package com.termux.app.openhouse;

import org.junit.Assert;
import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

public class OpenHouseRuntimeSupervisorRecoveryTest {

    @Test
    public void foregroundSupervisorUsesHealthOnlyAndRequiredTiming() throws Exception {
        String source = source(
            "openhouse-feature/src/main/java/com/wuxianpi/openhouse/feature/ControlPlaneForegroundSupervisor.java");

        Assert.assertTrue(source.contains("http://127.0.0.1:20087/api/v1/health"));
        Assert.assertTrue(source.contains("ONLINE_INTERVAL_MS = 15_000L"));
        Assert.assertTrue(source.contains("{1_000L, 2_000L, 4_000L, 8_000L}"));
        Assert.assertTrue(source.contains("{5_000L, 15_000L, 30_000L, 60_000L}"));
        Assert.assertTrue(source.contains("ControlPlaneStartCoordinator.start(bridge, \"foreground\")"));
        Assert.assertFalse(source.contains("token"));
        Assert.assertFalse(source.contains("/api/v1/services"));
        Assert.assertFalse(source.contains("OpenHouseBundledRuntimeSync"));
    }

    @Test
    public void bothProductHostsInstallTheSharedSupervisor() throws Exception {
        String termux = source(
            "termux-host-adapter/src/main/java/com/openhouse/host/termux/TermuxProductHost.kt");
        String nativeHost = source(
            "native-host-adapter/src/main/java/com/openhouse/host/nativeapp/NativeProductHost.kt");
        String application = source("app/src/main/java/com/termux/app/TermuxApplication.java");

        Assert.assertTrue(termux.contains("ControlPlaneForegroundSupervisor.register"));
        Assert.assertTrue(nativeHost.contains("ControlPlaneForegroundSupervisor.register"));
        Assert.assertFalse(application.contains("OpenHouseForegroundRuntimeKeeper.register"));
    }

    @Test
    public void legacyRecoveryAlsoDelegatesToTheFixedBridge() throws Exception {
        String source = source(
            "app/src/main/java/com/termux/app/openhouse/OpenHouseRuntimeSupervisor.java");

        Assert.assertTrue(source.contains("ControlPlaneStartCoordinator.start("));
        Assert.assertTrue(source.contains("controlPlaneBridge()"));
        Assert.assertFalse(source.contains("OpenHouseMaintainerRunner.Action.REPAIR_CONTROL_PLANE"));
        Assert.assertFalse(source.contains("SERVICE_MANAGER_URL"));
        Assert.assertFalse(source.contains("SMALLPHONEAI_SERVICE_MANAGER_BIND"));
    }

    private static String source(String path) throws Exception {
        File file = new File(path);
        if (!file.isFile()) file = new File("..", path);
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }
}
