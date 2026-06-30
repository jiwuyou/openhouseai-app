package com.termux.app.openhouse.terminal;

import android.app.AlertDialog;
import android.content.DialogInterface;
import android.view.Gravity;
import android.view.View;

import androidx.viewpager.widget.ViewPager;

import com.termux.R;
import com.termux.app.TermuxActivity;

import java.util.ArrayList;
import java.util.List;

public final class OpenHouseTerminalTutorial {

    private static final int NO_TOOLBAR_PAGE = -1;
    private static final int SHORT_AI_KEYS_PAGE = 0;
    private static final int FULL_AI_COMMANDS_PAGE = 1;
    private static final int ACTION_NONE = 0;

    private final TermuxActivity mActivity;
    private final List<Step> mSteps = new ArrayList<>();
    private AlertDialog mDialog;
    private int mStepIndex;

    public OpenHouseTerminalTutorial(TermuxActivity activity) {
        mActivity = activity;
    }

    public void start() {
        buildSteps();
        if (mSteps.isEmpty()) return;
        showStep(0);
    }

    public void dismiss() {
        if (mDialog != null) {
            mDialog.setOnCancelListener(null);
            mDialog.dismiss();
            mDialog = null;
        }
    }

    private void buildSteps() {
        mSteps.clear();
        mSteps.add(new Step(
            R.string.openhouse_terminal_tutorial_intro_title,
            R.string.openhouse_terminal_tutorial_intro_body,
            NO_TOOLBAR_PAGE,
            false
        ));
        mSteps.add(new Step(
            R.string.openhouse_terminal_tutorial_sessions_title,
            R.string.openhouse_terminal_tutorial_sessions_body,
            NO_TOOLBAR_PAGE,
            true
        ));
        mSteps.add(new Step(
            R.string.openhouse_terminal_tutorial_base_keys_title,
            R.string.openhouse_terminal_tutorial_base_keys_body,
            SHORT_AI_KEYS_PAGE,
            false
        ));

        if (mActivity.getTermuxTerminalExtraKeys() != null
            && mActivity.getTermuxTerminalExtraKeys().isUsingOpenHouseDefaultExtraKeys()) {
            mSteps.add(new Step(
                R.string.openhouse_terminal_tutorial_ai_keys_title,
                R.string.openhouse_terminal_tutorial_ai_keys_body,
                SHORT_AI_KEYS_PAGE,
                false
            ));
            mSteps.add(new Step(
                R.string.openhouse_terminal_tutorial_continue_title,
                R.string.openhouse_terminal_tutorial_continue_body,
                SHORT_AI_KEYS_PAGE,
                false
            ));
            mSteps.add(new Step(
                R.string.openhouse_terminal_tutorial_full_commands_title,
                R.string.openhouse_terminal_tutorial_full_commands_body,
                FULL_AI_COMMANDS_PAGE,
                false
            ));
        } else {
            mSteps.add(new Step(
                R.string.openhouse_terminal_tutorial_custom_keys_title,
                R.string.openhouse_terminal_tutorial_custom_keys_body,
                SHORT_AI_KEYS_PAGE,
                false,
                ACTION_NONE
            ));
        }
        mSteps.add(new Step(
            R.string.openhouse_terminal_tutorial_claude_title,
            R.string.openhouse_terminal_tutorial_claude_body,
            SHORT_AI_KEYS_PAGE,
            false,
            ACTION_NONE
        ));
        mSteps.add(new Step(
            R.string.openhouse_terminal_tutorial_menu_title,
            R.string.openhouse_terminal_tutorial_menu_body,
            NO_TOOLBAR_PAGE,
            false,
            ACTION_NONE
        ));
        mSteps.add(new Step(
            R.string.openhouse_terminal_tutorial_runtime_title,
            R.string.openhouse_terminal_tutorial_runtime_body,
            FULL_AI_COMMANDS_PAGE,
            false,
            ACTION_NONE
        ));
    }

    private void showStep(int stepIndex) {
        if (mActivity.isFinishing()) return;

        mStepIndex = stepIndex;
        Step step = mSteps.get(mStepIndex);
        prepareUiForStep(step);

        dismiss();
        mDialog = new AlertDialog.Builder(mActivity)
            .setTitle(step.titleRes)
            .setMessage(step.bodyRes)
            .setNegativeButton(R.string.openhouse_terminal_tutorial_skip, null)
            .setPositiveButton(isLastStep() ? R.string.openhouse_terminal_tutorial_start_using : R.string.openhouse_terminal_tutorial_next, null)
            .create();
        mDialog.setCanceledOnTouchOutside(false);
        mDialog.setOnCancelListener(dialog -> finishTutorial());
        mDialog.setOnShowListener(dialog -> {
            mDialog.getButton(DialogInterface.BUTTON_NEGATIVE).setOnClickListener(v -> finishTutorial());
            mDialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener(v -> {
                if (isLastStep()) {
                    finishTutorial();
                } else {
                    showStep(mStepIndex + 1);
                }
            });
        });
        mDialog.show();
    }

    private void prepareUiForStep(Step step) {
        if (step.drawerOpen) {
            mActivity.getDrawer().openDrawer(Gravity.LEFT);
        } else {
            mActivity.getDrawer().closeDrawers();
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
    }

    private void finishTutorial() {
        dismiss();
        mActivity.getDrawer().closeDrawers();
        if (mActivity.getTerminalView() != null) mActivity.getTerminalView().requestFocus();
    }

    private boolean isLastStep() {
        return mStepIndex == mSteps.size() - 1;
    }

    private static final class Step {
        final int titleRes;
        final int bodyRes;
        final int toolbarPage;
        final boolean drawerOpen;

        Step(int titleRes, int bodyRes, int toolbarPage, boolean drawerOpen) {
            this(titleRes, bodyRes, toolbarPage, drawerOpen, ACTION_NONE);
        }

        Step(int titleRes, int bodyRes, int toolbarPage, boolean drawerOpen, int action) {
            this.titleRes = titleRes;
            this.bodyRes = bodyRes;
            this.toolbarPage = toolbarPage;
            this.drawerOpen = drawerOpen;
            this.action = action;
        }

        final int action;
    }
}
