package com.termux.app.openhouse.terminal;

import android.content.Intent;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;

import androidx.viewpager.widget.ViewPager;

import com.termux.R;
import com.termux.app.TermuxActivity;
import com.termux.app.activities.OpenHouseHomeActivity;
import com.termux.app.browser.ControlledBrowserContract;
import com.termux.app.openhouse.tutorial.GuidedTutorialOverlay;

import java.util.ArrayList;
import java.util.List;

public final class OpenHouseTerminalTutorial {

    private static final int NO_TOOLBAR_PAGE = -1;
    private static final int SHORT_AI_KEYS_PAGE = 0;
    private static final int FULL_AI_COMMANDS_PAGE = 1;

    private final TermuxActivity mActivity;
    private final List<StepSpec> mStepSpecs = new ArrayList<>();
    private GuidedTutorialOverlay mOverlay;

    public OpenHouseTerminalTutorial(TermuxActivity activity) {
        mActivity = activity;
    }

    public void start() {
        buildSteps();
        if (mStepSpecs.isEmpty() || mActivity.isFinishing()) return;

        ViewGroup targetRoot = mActivity.getTermuxActivityRootView();
        ViewGroup overlayContainer = mActivity.findViewById(android.R.id.content);
        if (targetRoot == null || overlayContainer == null) return;

        List<GuidedTutorialOverlay.Step> steps = new ArrayList<>();
        for (StepSpec spec : mStepSpecs) {
            steps.add(spec.step);
        }

        dismiss();
        mOverlay = new GuidedTutorialOverlay(
            mActivity,
            overlayContainer,
            steps,
            new GuidedTutorialOverlay.SimpleListener() {
                @Override
                public void onStepChanged(GuidedTutorialOverlay overlay,
                                          GuidedTutorialOverlay.Step step,
                                          int stepIndex) {
                    if (stepIndex >= 0 && stepIndex < mStepSpecs.size()) {
                        prepareUiForStep(mStepSpecs.get(stepIndex), overlay);
                    }
                }

                @Override
                public void onSkipped(GuidedTutorialOverlay overlay,
                                      GuidedTutorialOverlay.Step step) {
                    finishTutorial(false);
                }

                @Override
                public void onFinished(GuidedTutorialOverlay overlay) {
                    finishTutorial(false);
                }
            }
        );
        mOverlay.start();
    }

    public void dismiss() {
        if (mOverlay != null) {
            mOverlay.destroy();
            mOverlay = null;
        }
    }

    private void buildSteps() {
        mStepSpecs.clear();
        View root = mActivity.getTermuxActivityRootView();

        mStepSpecs.add(new StepSpec(
            GuidedTutorialOverlay.Step.explanation(
                text(R.string.openhouse_terminal_tutorial_intro_title),
                text(R.string.openhouse_terminal_tutorial_intro_body)
            ).build(),
            NO_TOOLBAR_PAGE,
            false,
            false
        ));

        mStepSpecs.add(new StepSpec(
            GuidedTutorialOverlay.Step.requiredClick(
                text(R.string.openhouse_terminal_tutorial_terminal_title),
                text(R.string.openhouse_terminal_tutorial_terminal_body),
                GuidedTutorialOverlay.targetById(root, R.id.terminal_view)
            )
                .onTargetClick((overlay, step) -> true)
                .build(),
            NO_TOOLBAR_PAGE,
            false,
            true
        ));

        mStepSpecs.add(new StepSpec(
            GuidedTutorialOverlay.Step.requiredClick(
                text(R.string.openhouse_terminal_tutorial_sessions_button_title),
                text(R.string.openhouse_terminal_tutorial_sessions_button_body),
                GuidedTutorialOverlay.targetById(root, R.id.terminal_list_button)
            )
                .onTargetClick((overlay, step) -> {
                    mActivity.getDrawer().openDrawer(Gravity.LEFT);
                    return true;
                })
                .build(),
            NO_TOOLBAR_PAGE,
            false,
            true
        ));

        mStepSpecs.add(new StepSpec(
            GuidedTutorialOverlay.Step.requiredClick(
                text(R.string.openhouse_terminal_tutorial_sessions_title),
                text(R.string.openhouse_terminal_tutorial_sessions_body),
                GuidedTutorialOverlay.targetById(root, R.id.terminal_sessions_list)
            )
                .onTargetClick((overlay, step) -> true)
                .build(),
            NO_TOOLBAR_PAGE,
            true,
            true
        ));

        mStepSpecs.add(new StepSpec(
            GuidedTutorialOverlay.Step.requiredClick(
                text(R.string.openhouse_terminal_tutorial_base_keys_title),
                text(R.string.openhouse_terminal_tutorial_base_keys_body),
                GuidedTutorialOverlay.targetById(root, R.id.terminal_toolbar_view_pager)
            )
                .onTargetClick((overlay, step) -> true)
                .build(),
            SHORT_AI_KEYS_PAGE,
            false,
            true
        ));

        if (mActivity.getTermuxTerminalExtraKeys() != null
            && mActivity.getTermuxTerminalExtraKeys().isUsingOpenHouseDefaultExtraKeys()) {
            mStepSpecs.add(new StepSpec(
                GuidedTutorialOverlay.Step.requiredClick(
                    text(R.string.openhouse_terminal_tutorial_ai_keys_title),
                    text(R.string.openhouse_terminal_tutorial_ai_keys_body),
                    GuidedTutorialOverlay.targetById(root, R.id.terminal_toolbar_view_pager)
                )
                    .onTargetClick((overlay, step) -> true)
                    .build(),
                SHORT_AI_KEYS_PAGE,
                false,
                true
            ));

            mStepSpecs.add(new StepSpec(
                GuidedTutorialOverlay.Step.explanation(
                    text(R.string.openhouse_terminal_tutorial_continue_title),
                    text(R.string.openhouse_terminal_tutorial_continue_body)
                ).build(),
                SHORT_AI_KEYS_PAGE,
                false,
                true
            ));

            mStepSpecs.add(new StepSpec(
                GuidedTutorialOverlay.Step.requiredClick(
                    text(R.string.openhouse_terminal_tutorial_full_commands_title),
                    text(R.string.openhouse_terminal_tutorial_full_commands_body),
                    GuidedTutorialOverlay.targetById(root, R.id.terminal_toolbar_view_pager)
                )
                    .onTargetClick((overlay, step) -> true)
                    .build(),
                FULL_AI_COMMANDS_PAGE,
                false,
                true
            ));
        } else {
            mStepSpecs.add(new StepSpec(
                GuidedTutorialOverlay.Step.explanation(
                    text(R.string.openhouse_terminal_tutorial_custom_keys_title),
                    text(R.string.openhouse_terminal_tutorial_custom_keys_body)
                ).build(),
                SHORT_AI_KEYS_PAGE,
                false,
                true
            ));
        }

        mStepSpecs.add(new StepSpec(
            GuidedTutorialOverlay.Step.explanation(
                text(R.string.openhouse_terminal_tutorial_claude_title),
                text(R.string.openhouse_terminal_tutorial_claude_body)
            ).build(),
            SHORT_AI_KEYS_PAGE,
            false,
            true
        ));

        mStepSpecs.add(new StepSpec(
            GuidedTutorialOverlay.Step.explanation(
                text(R.string.openhouse_terminal_tutorial_runtime_title),
                text(R.string.openhouse_terminal_tutorial_runtime_body)
            ).build(),
            FULL_AI_COMMANDS_PAGE,
            false,
            true
        ));

        mStepSpecs.add(new StepSpec(
            GuidedTutorialOverlay.Step.requiredClick(
                text(R.string.openhouse_terminal_tutorial_menu_title),
                text(R.string.openhouse_terminal_tutorial_menu_body),
                GuidedTutorialOverlay.targetById(root, R.id.openhouse_menu_button)
            )
                .onTargetClick((overlay, step) -> {
                    finishTutorial(true);
                    return true;
                })
                .advanceAfterTargetClick(false)
                .build(),
            NO_TOOLBAR_PAGE,
            false,
            true
        ));
    }

    private void prepareUiForStep(StepSpec step, GuidedTutorialOverlay overlay) {
        if (step.drawerOpen) {
            mActivity.getDrawer().openDrawer(Gravity.LEFT);
        } else {
            mActivity.getDrawer().closeDrawers();
        }

        if (step.quickButtonsVisible) {
            ensureQuickButtonsVisible();
        }

        if (step.toolbarPage != NO_TOOLBAR_PAGE) {
            ViewPager toolbar = mActivity.getTerminalToolbarViewPager();
            if (toolbar != null) {
                toolbar.setVisibility(View.VISIBLE);
                int page = step.toolbarPage;
                if (toolbar.getAdapter() != null) {
                    page = Math.min(page, toolbar.getAdapter().getCount() - 1);
                }
                toolbar.setCurrentItem(Math.max(0, page), true);
            }
        }

        if (overlay != null) {
            overlay.refreshTarget();
            View root = mActivity.getTermuxActivityRootView();
            if (root != null) {
                root.postDelayed(overlay::refreshTarget, 260);
            }
        }
    }

    private void finishTutorial(boolean openOpenHouseMenu) {
        dismiss();
        mActivity.getDrawer().closeDrawers();
        if (openOpenHouseMenu) {
            Intent intent = new Intent(mActivity, OpenHouseHomeActivity.class);
            intent.putExtra(ControlledBrowserContract.EXTRA_OPENHOUSE_PAGE, "home");
            mActivity.startActivity(intent);
            return;
        }
        if (mActivity.getTerminalView() != null) mActivity.getTerminalView().requestFocus();
    }

    private void ensureQuickButtonsVisible() {
        View terminalListButton = mActivity.findViewById(R.id.terminal_list_button);
        View menuButton = mActivity.findViewById(R.id.openhouse_menu_button);
        if (terminalListButton != null) terminalListButton.setVisibility(View.VISIBLE);
        if (menuButton != null) menuButton.setVisibility(View.VISIBLE);
    }

    private CharSequence text(int resId) {
        return mActivity.getString(resId);
    }

    private static final class StepSpec {
        final GuidedTutorialOverlay.Step step;
        final int toolbarPage;
        final boolean drawerOpen;
        final boolean quickButtonsVisible;

        StepSpec(GuidedTutorialOverlay.Step step, int toolbarPage, boolean drawerOpen,
                 boolean quickButtonsVisible) {
            this.step = step;
            this.toolbarPage = toolbarPage;
            this.drawerOpen = drawerOpen;
            this.quickButtonsVisible = quickButtonsVisible;
        }
    }
}
