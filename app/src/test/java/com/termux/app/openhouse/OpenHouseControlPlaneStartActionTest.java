package com.termux.app.openhouse;

import org.junit.Assert;
import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

public class OpenHouseControlPlaneStartActionTest {

    @Test
    public void allInOneUsesOnlyTheFixedTermuxCommand() throws Exception {
        String host = source(
            "termux-host-adapter/src/main/java/com/openhouse/host/termux/TermuxOpenHouseHost.java");
        String bridge = methodSource(host,
            "private final class TermuxControlPlaneBridge",
            "private static Thread streamReader");

        Assert.assertTrue(bridge.contains("/bin/openhouse-control-plane-start"));
        Assert.assertTrue(bridge.contains("new ProcessBuilder(command.getAbsolutePath())"));
        Assert.assertFalse(bridge.contains("OpenHouseMaintainerRunner"));
        Assert.assertFalse(bridge.contains("OpenHouseBundledRuntimeSync"));
        Assert.assertFalse(bridge.contains("token"));
        Assert.assertFalse(bridge.contains("service-manager.sh"));
    }

    @Test
    public void maintainerRunnerNoLongerOwnsAStartAction() throws Exception {
        String source = source(
            "app/src/main/java/com/termux/app/openhouse/OpenHouseMaintainerRunner.java");

        Assert.assertFalse(source.contains("START_CONTROL_PLANE"));
        Assert.assertFalse(source.contains("start_control_plane"));
    }

    @Test
    public void bothManualScreensUseTheProcessWideCoordinator() throws Exception {
        String legacy = source(
            "app/src/main/java/com/termux/app/activities/OpenHouseServiceControlActivity.java");
        String shared = source(
            "service-control-feature/src/main/java/com/wuxianpi/openhouse/servicecontrol/ServiceControlController.kt");

        Assert.assertTrue(legacy.contains("ControlPlaneStartCoordinator.start("));
        Assert.assertTrue(shared.contains("ControlPlaneStartCoordinator.start("));
        Assert.assertFalse(legacy.contains("OpenHouseMaintainerRunner.Action.START_CONTROL_PLANE"));
        Assert.assertFalse(shared.contains("controlPlaneStarter().startControlPlane()"));
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
