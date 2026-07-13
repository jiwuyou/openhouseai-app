package com.termux.app.openhouse;

import org.junit.Assert;
import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

public class OpenHouseControlPlaneStartActionTest {

    @Test
    public void startControlPlaneActionDirectlyUsesTermuxNativeControlPlaneScript() {
        OpenHouseMaintainerRunner.Action action =
            OpenHouseMaintainerRunner.Action.valueOf("START_CONTROL_PLANE");

        Assert.assertEquals("start_control_plane", action.slug);
        Assert.assertEquals("启动运行中枢", action.label);
        Assert.assertEquals("start-control-plane-termux-native.sh", action.assetName);
        Assert.assertEquals(150, action.timeoutSeconds);
        Assert.assertNotEquals("repair-control-plane.sh", action.assetName);
        Assert.assertNotEquals("start-smallphone.sh", action.assetName);
    }

    @Test
    public void serviceControlStartEntryUsesStartActionInsteadOfRepairAction() throws Exception {
        String source = source(
            "app/src/main/java/com/termux/app/activities/OpenHouseServiceControlActivity.java");
        String header = methodSource(
            source,
            "private void buildContentView()",
            "private void addCcCodexTutorialPanel");

        Assert.assertTrue(header.contains("actionButton(\"启动运行中枢\""));
        Assert.assertTrue(source.contains(
            ".run(OpenHouseMaintainerRunner.Action.START_CONTROL_PLANE, 0)"));
        Assert.assertFalse(source.contains(
            ".run(OpenHouseMaintainerRunner.Action.REPAIR_CONTROL_PLANE, 0)"));
        Assert.assertFalse(source.contains("runControlPlaneRepair"));
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
