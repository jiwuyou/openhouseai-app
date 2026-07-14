package com.termux.app.activities;

import org.junit.Assert;
import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

public class OpenHouseHomeActivityAiRescueCollapseTest {

    private static final String ACTIVITY_PATH =
        "app/src/main/java/com/termux/app/activities/OpenHouseHomeActivity.java";

    @Test
    public void aiRescueCollapseUsesDedicatedPreferencesAndFrameHost() throws Exception {
        String source = source(ACTIVITY_PATH);

        Assert.assertTrue(source.contains(
            "PREF_AI_RESCUE_CONTROLS_COLLAPSED = \"ai_rescue_controls_collapsed\""));
        Assert.assertTrue(source.contains(
            "PREF_AI_RESCUE_BUBBLE_EDGE = \"ai_rescue_bubble_edge\""));
        Assert.assertTrue(source.contains(
            "PREF_AI_RESCUE_BUBBLE_Y_RATIO = \"ai_rescue_bubble_y_ratio\""));
        Assert.assertTrue(source.contains(
            "PREF_TOP_ACTION_BAR_COLLAPSED = \"top_action_bar_collapsed\""));

        String create = methodSource(
            source,
            "private FrameLayout createAiRescuePageView()",
            "private void setAiRescueControlsCollapsed");
        Assert.assertTrue(create.contains("FrameLayout pageHost = new FrameLayout(this)"));
        Assert.assertTrue(create.contains("aiRescueControlsView = panel"));
        Assert.assertTrue(create.contains("pageHost.addView(page, new FrameLayout.LayoutParams("));
        Assert.assertTrue(create.contains("pageHost.addView(aiRescueBubbleView, bubbleParams)"));
    }

    @Test
    public void collapseButtonAndAccessibleBubbleToggleMutuallyExclusiveChrome() throws Exception {
        String source = source(ACTIVITY_PATH);
        String create = methodSource(
            source,
            "private FrameLayout createAiRescuePageView()",
            "private void setAiRescueControlsCollapsed");

        Assert.assertTrue(create.contains(
            "compactButton(\"收起\", v -> setAiRescueControlsCollapsed(true), true)"));
        Assert.assertTrue(create.contains(
            "aiRescueBubbleView.setContentDescription(\"展开 AI 救援控制\")"));
        Assert.assertTrue(create.contains("aiRescueBubbleView.setElevation(dp(8))"));
        Assert.assertTrue(create.contains(
            "new FrameLayout.LayoutParams(dp(52), dp(52))"));
        Assert.assertTrue(create.contains(
            "aiRescueBubbleView.setOnClickListener(v -> setAiRescueControlsCollapsed(false))"));

        String setter = methodSource(
            source,
            "private void setAiRescueControlsCollapsed",
            "private boolean isAiRescueControlsCollapsed");
        Assert.assertTrue(setter.contains(
            ".putBoolean(PREF_AI_RESCUE_CONTROLS_COLLAPSED, collapsed)"));
        Assert.assertTrue(setter.contains("updateAiRescueControlsChrome()"));

        String chrome = methodSource(
            source,
            "private void updateAiRescueControlsChrome",
            "private void attachAiRescueBubbleDrag");
        Assert.assertTrue(chrome.contains(
            "aiRescueControlsView.setVisibility(collapsed ? View.GONE : View.VISIBLE)"));
        Assert.assertTrue(chrome.contains(
            "aiRescueBubbleView.setVisibility(collapsed ? View.VISIBLE : View.GONE)"));

        String collapsePath = setter + chrome;
        Assert.assertFalse(collapsePath.contains("destroy()"));
        Assert.assertFalse(collapsePath.contains("releaseAiRescuePage"));
        Assert.assertFalse(collapsePath.contains("reloadAiRescueWebView"));
        Assert.assertFalse(collapsePath.contains("loadUrl("));
    }

    @Test
    public void rescueBubbleDragUsesSlopMoveSnapAndDedicatedPositionPersistence() throws Exception {
        String source = source(ACTIVITY_PATH);
        String drag = methodSource(
            source,
            "private void attachAiRescueBubbleDrag",
            "private void applyAiRescueBubblePosition");
        Assert.assertTrue(drag.contains(
            "ViewConfiguration.get(this).getScaledTouchSlop()"));
        Assert.assertTrue(drag.contains("MotionEvent.ACTION_DOWN"));
        Assert.assertTrue(drag.contains("MotionEvent.ACTION_MOVE"));
        Assert.assertTrue(drag.contains("Math.hypot(dx, dy) > touchSlop"));
        Assert.assertTrue(drag.contains("moveAiRescueBubbleTo("));
        Assert.assertTrue(drag.contains("MotionEvent.ACTION_UP"));
        Assert.assertTrue(drag.contains("snapAndSaveAiRescueBubble()"));
        Assert.assertTrue(drag.contains("setAiRescueControlsCollapsed(false)"));

        String apply = methodSource(
            source,
            "private void applyAiRescueBubblePosition",
            "private void moveAiRescueBubbleTo");
        Assert.assertTrue(apply.contains("PREF_AI_RESCUE_BUBBLE_EDGE"));
        Assert.assertTrue(apply.contains("PREF_AI_RESCUE_BUBBLE_Y_RATIO"));
        Assert.assertTrue(apply.contains("moveAiRescueBubbleTo(left, top, false)"));

        String move = methodSource(
            source,
            "private void moveAiRescueBubbleTo",
            "private void snapAndSaveAiRescueBubble");
        Assert.assertTrue(move.contains("params.leftMargin = clampInt("));
        Assert.assertTrue(move.contains("params.topMargin = clampInt("));
        Assert.assertTrue(move.contains("saveAiRescueBubblePosition("));

        String snap = methodSource(
            source,
            "private void snapAndSaveAiRescueBubble",
            "private void saveAiRescueBubblePosition");
        Assert.assertTrue(snap.contains("aiRescuePageView.getWidth() / 2"));
        Assert.assertTrue(snap.contains(
            "moveAiRescueBubbleTo(snappedLeft, params.topMargin, true)"));

        String save = methodSource(
            source,
            "private void saveAiRescueBubblePosition",
            "private LinearLayout createAiRescueFallbackView");
        Assert.assertTrue(save.contains(".putInt(PREF_AI_RESCUE_BUBBLE_EDGE, edge)"));
        Assert.assertTrue(save.contains(
            ".putFloat(PREF_AI_RESCUE_BUBBLE_Y_RATIO, clampFloat(yRatio, 0f, 1f))"));
    }

    @Test
    public void releaseClearsRescuePanelBubbleAndWebViewReferences() throws Exception {
        String source = source(ACTIVITY_PATH);
        String release = methodSource(
            source,
            "private void releaseAiRescuePage()",
            "private void releaseCloudCliPage()");

        Assert.assertTrue(release.contains("aiRescueWebView.onPause()"));
        Assert.assertTrue(release.contains("aiRescueWebView.destroy()"));
        Assert.assertTrue(release.contains("aiRescueWebView = null"));
        Assert.assertTrue(release.contains("aiRescuePageView = null"));
        Assert.assertTrue(release.contains("aiRescueControlsView = null"));
        Assert.assertTrue(release.contains("aiRescueBubbleView = null"));
    }

    @Test
    public void existingRescueActionsPortAndWebViewRemainAvailable() throws Exception {
        String source = source(ACTIVITY_PATH);
        String create = methodSource(
            source,
            "private FrameLayout createAiRescuePageView()",
            "private void setAiRescueControlsCollapsed");

        Assert.assertTrue(create.contains("runPiWebRescueAction(\"start\")"));
        Assert.assertTrue(create.contains("runPiWebRescueAction(\"restart\")"));
        Assert.assertTrue(create.contains("runPiWebRescueAction(\"stop\")"));
        Assert.assertTrue(create.contains("runPiWebRescueAction(\"status\")"));
        Assert.assertTrue(create.contains("copyAiRescueUrlFromInput()"));
        Assert.assertTrue(create.contains("reloadAiRescueWebView()"));
        Assert.assertTrue(create.contains("saveAiRescuePortFromInput(true)"));
        Assert.assertTrue(create.contains("aiRescuePortInput = new EditText(this)"));
        Assert.assertTrue(create.contains("aiRescueWebView = new WebView(this)"));
        Assert.assertTrue(create.contains("configureAiRescueWebView(aiRescueWebView)"));
        Assert.assertTrue(create.contains("browserHost.addView(aiRescueWebView"));
    }

    @Test
    public void topActionBubbleLogicRemainsSeparateAndIntact() throws Exception {
        String source = source(ACTIVITY_PATH);
        String topChrome = methodSource(
            source,
            "private void createTopActionBarBubble()",
            "private void addAiFriendHelpDrawerEntry()");

        Assert.assertTrue(topChrome.contains("attachTopActionBarBubbleDrag("));
        Assert.assertTrue(topChrome.contains("setTopActionBarCollapsed(false)"));
        Assert.assertTrue(topChrome.contains("snapAndSaveTopActionBarBubble()"));
        Assert.assertTrue(topChrome.contains("PREF_TOP_ACTION_BAR_BUBBLE_EDGE"));
        Assert.assertTrue(topChrome.contains("PREF_TOP_ACTION_BAR_BUBBLE_Y_RATIO"));
        Assert.assertFalse(topChrome.contains("PREF_AI_RESCUE_CONTROLS_COLLAPSED"));

        String rescueChrome = methodSource(
            source,
            "private void setAiRescueControlsCollapsed",
            "private LinearLayout createAiRescueFallbackView");
        Assert.assertFalse(rescueChrome.contains("setTopActionBarCollapsed"));
        Assert.assertFalse(rescueChrome.contains("PREF_TOP_ACTION_BAR_BUBBLE_EDGE"));
        Assert.assertFalse(rescueChrome.contains("PREF_TOP_ACTION_BAR_BUBBLE_Y_RATIO"));
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
