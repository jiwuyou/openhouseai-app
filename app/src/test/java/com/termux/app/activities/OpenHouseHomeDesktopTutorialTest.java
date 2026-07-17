package com.termux.app.activities;

import org.junit.Assert;
import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

public class OpenHouseHomeDesktopTutorialTest {

    private static final String HOME_PATH =
        "app/src/main/java/com/termux/app/activities/OpenHouseHomeActivity.java";
    private static final String DESKTOP_PATH =
        "app/src/main/java/com/termux/app/openhouse/desktop/ui/OpenHouseDesktopView.java";
    private static final String TILE_PATH =
        "app/src/main/java/com/termux/app/openhouse/desktop/ui/DesktopAppTileView.java";

    @Test
    public void tutorialResolvesARealRegisteredServiceOffTheUiThread() throws Exception {
        String source = source(HOME_PATH);
        String start = methodSource(
            source,
            "private void startDesktopUsageTutorialOverlay()",
            "private void createDesktopUsageTutorialOverlay");
        String resolve = methodSource(
            source,
            "private DesktopTutorialTarget findDesktopTutorialApp(",
            "private boolean isDesktopTutorialCandidate");
        String service = methodSource(
            source,
            "private String findTutorialServiceId(",
            "private boolean isTutorialControlPlaneService");

        Assert.assertTrue(start.contains("backgroundExecutor.execute"));
        Assert.assertTrue(start.contains("new ServiceManagerControlClient(this).listServices()"));
        Assert.assertTrue(resolve.contains("findTutorialServiceId(component, registeredServices)"));
        Assert.assertTrue(resolve.contains("\"pi-agent\".equals(normalizeId(component.id))"));
        Assert.assertTrue(service.contains("ServiceManagerServiceResolver.resolve("));
        Assert.assertTrue(service.contains("resolution.serviceIds"));
        Assert.assertTrue(service.contains("!isTutorialControlPlaneService(serviceId)"));
    }

    @Test
    public void candidateMustBeVisibleEmbeddedLaunchableAndControllable() throws Exception {
        String source = source(HOME_PATH);
        String candidate = methodSource(
            source,
            "private boolean isDesktopTutorialCandidate",
            "private String findTutorialServiceId(");

        Assert.assertTrue(candidate.contains("component.isDesktopVisible()"));
        Assert.assertTrue(candidate.contains("component.hasEntry()"));
        Assert.assertTrue(candidate.contains("component.hasControlEntry()"));
        Assert.assertTrue(candidate.contains("OpenHouseComponent.EntryType.WEBVIEW"));
        Assert.assertTrue(candidate.contains("OpenHouseComponent.EntryType.NATIVE_PAGE"));
        Assert.assertTrue(candidate.contains("launchIntent.launchable"));
    }

    @Test
    public void controlCompletionIsGatedByActivityResult() throws Exception {
        String source = source(HOME_PATH);
        String begin = methodSource(
            source,
            "private void beginDesktopAppControlTutorial",
            "private void scheduleResumePendingUsageTutorial");
        String result = methodSource(
            source,
            "protected void onActivityResult",
            "public void onBackPressed");

        Assert.assertTrue(begin.contains("USAGE_STAGE_CONTROL_IN_PROGRESS"));
        Assert.assertTrue(begin.contains("startActivityForResult"));
        Assert.assertTrue(result.contains("resultCode == RESULT_OK"));
        Assert.assertTrue(result.contains("EXTRA_SERVICE_CONTROL_TUTORIAL_COMPLETED"));
        Assert.assertTrue(result.contains("USAGE_STAGE_RETURN_FROM_CONTROL"));
    }

    @Test
    public void desktopTargetRevealsItsPageAndUsesStableViewTag() throws Exception {
        String desktop = source(DESKTOP_PATH);
        String tile = source(TILE_PATH);

        Assert.assertTrue(desktop.contains("public boolean revealEntry(String entryId"));
        Assert.assertTrue(desktop.contains("setCurrentPage(entry.slotIndex / getPageSize(), false)"));
        Assert.assertTrue(desktop.contains("dispatchEntryViewWhenReady"));
        Assert.assertTrue(desktop.contains("public View findEntryView(String entryId)"));
        Assert.assertTrue(tile.contains("ENTRY_TAG_PREFIX = \"openhouse_desktop_app:\""));
        Assert.assertTrue(tile.contains("setTag(entryTag("));
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
