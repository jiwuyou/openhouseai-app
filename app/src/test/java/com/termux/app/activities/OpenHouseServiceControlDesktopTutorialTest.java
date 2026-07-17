package com.termux.app.activities;

import org.junit.Assert;
import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

public class OpenHouseServiceControlDesktopTutorialTest {

    private static final String ACTIVITY_PATH =
        "app/src/main/java/com/termux/app/activities/OpenHouseServiceControlActivity.java";

    @Test
    public void desktopTutorialPublishesStableIntentContract() throws Exception {
        String source = source(ACTIVITY_PATH);

        Assert.assertTrue(source.contains(
            "EXTRA_SERVICE_CONTROL_TUTORIAL_SERVICE_ID ="));
        Assert.assertTrue(source.contains(
            "\"openhouse_service_control_tutorial_service_id\""));
        Assert.assertTrue(source.contains(
            "TUTORIAL_DESKTOP_APP_CONTROL = \"desktop_app_control\""));
        Assert.assertTrue(source.contains(
            "EXTRA_SERVICE_CONTROL_TUTORIAL_COMPLETED ="));
        Assert.assertTrue(source.contains(
            "intent.getStringExtra(EXTRA_SERVICE_CONTROL_TUTORIAL_SERVICE_ID)"));
    }

    @Test
    public void desktopTutorialUsesOnlyDeclaredNonControlPlaneService() throws Exception {
        String source = source(ACTIVITY_PATH);
        String ids = methodSource(
            source,
            "private List<String> readComponentServiceIds(Intent intent)",
            "private List<String> readIntentStringList");

        Assert.assertTrue(ids.contains("!componentId.isEmpty()"));
        Assert.assertTrue(ids.contains("!tutorialServiceId.isEmpty()"));
        Assert.assertTrue(ids.contains("!isControlPlaneService(tutorialServiceId)"));
        Assert.assertTrue(ids.contains("out.contains(tutorialServiceId)"));
        Assert.assertTrue(ids.contains("fixedTarget.add(tutorialServiceId)"));
        Assert.assertTrue(source.contains(
            "isDesktopAppControlTutorial() ? \"\" : componentId"));
    }

    @Test
    public void serviceCardRetainsNormalStartStopAndRefreshButtons() throws Exception {
        String source = source(ACTIVITY_PATH);
        String create = methodSource(
            source,
            "private ServiceCard createServiceCard(ServiceSnapshot snapshot)",
            "private void refreshAllStatuses()");

        Assert.assertTrue(create.contains(
            "Button startButton = actionButton(\"启动\""));
        Assert.assertTrue(create.contains(
            "Button stopButton = actionButton(\"关闭\""));
        Assert.assertTrue(create.contains(
            "openButton, tutorialActionButton, startButton, stopButton, refreshButton"));
        Assert.assertTrue(source.contains("final Button startButton;"));
        Assert.assertTrue(source.contains("final Button stopButton;"));
        Assert.assertTrue(source.contains("final Button refreshButton;"));
    }

    @Test
    public void tutorialRequiresRealStopAndStartStateConfirmation() throws Exception {
        String source = source(ACTIVITY_PATH);
        String tutorial = methodSource(
            source,
            "private void maybeStartDesktopAppControlTutorial()",
            "private void runDesktopTutorialAction");
        String action = methodSource(
            source,
            "private void runDesktopTutorialAction",
            "private void finishDesktopTutorialActionWithError");

        Assert.assertTrue(tutorial.contains("ACTION_STOP"));
        Assert.assertTrue(tutorial.contains("ACTION_START"));
        Assert.assertTrue(tutorial.contains("isStoppedState(card.state)"));
        Assert.assertTrue(tutorial.contains(
            "runDesktopTutorialAction(overlay, ACTION_START, false)"));
        Assert.assertTrue(tutorial.contains(
            "runDesktopTutorialAction(overlay, ACTION_STOP, false)"));
        Assert.assertTrue(tutorial.contains(
            "runDesktopTutorialAction(overlay, ACTION_START, true)"));
        int prepareStart = tutorial.indexOf("先启动应用服务");
        int stop = tutorial.indexOf("真实关闭应用服务");
        int finalStart = tutorial.indexOf("最终启动应用服务");
        Assert.assertTrue(prepareStart >= 0 && prepareStart < stop);
        Assert.assertTrue(stop >= 0 && stop < finalStart);
        Assert.assertTrue(action.contains("controlClient.runAction(serviceId, cleanAction)"));
        Assert.assertTrue(action.contains("controlClient.getStatus(serviceId)"));
        Assert.assertTrue(action.contains(
            "desktopTutorialStateMatches(cleanAction, lastSnapshot.state)"));
        Assert.assertTrue(action.contains("desktopTutorialActionInFlight"));
        Assert.assertTrue(action.contains("desktopTutorialStopConfirmed = true"));
        Assert.assertTrue(action.contains("desktopTutorialFinalStartConfirmed = true"));
        Assert.assertTrue(action.contains(
            "desktopTutorialStopConfirmed\n                    && desktopTutorialFinalStartConfirmed"));
        Assert.assertTrue(action.contains("overlay.next()"));
        Assert.assertFalse(action.contains("returnToOpenHouseMenu()"));
    }

    @Test
    public void desktopTutorialReturnsDirectlyToExistingAppActivity() throws Exception {
        String source = source(ACTIVITY_PATH);
        String build = methodSource(
            source,
            "private void buildContentView()",
            "private void addCcCodexTutorialPanel");

        Assert.assertTrue(build.contains(
            "actionButton(\"返回应用\", v -> finishDesktopTutorialAndReturn())"));
        Assert.assertTrue(source.contains("setResult(RESULT_CANCELED)"));
        Assert.assertTrue(source.contains("setResult(RESULT_OK, result)"));
        Assert.assertTrue(source.contains(
            "result.putExtra(EXTRA_SERVICE_CONTROL_TUTORIAL_COMPLETED, true)"));
        String finish = methodSource(
            source,
            "private void finishDesktopTutorialAndReturn()",
            "private boolean desktopTutorialStateMatches");
        Assert.assertTrue(finish.contains("!desktopTutorialStopConfirmed"));
        Assert.assertTrue(finish.contains("!desktopTutorialFinalStartConfirmed"));
        Assert.assertTrue(finish.indexOf("!desktopTutorialStopConfirmed")
            < finish.indexOf("setResult(RESULT_OK, result)"));
        Assert.assertTrue(source.contains("TUTORIAL_CC_CODEX_CONTROL"));
        Assert.assertTrue(source.contains("maybeStartCcCodexControlTutorial()"));
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
