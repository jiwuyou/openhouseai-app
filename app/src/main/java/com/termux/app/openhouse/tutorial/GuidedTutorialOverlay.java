package com.termux.app.openhouse.tutorial;

import android.app.Activity;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.termux.R;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A reusable, on-demand tutorial overlay for OpenHouse screens.
 *
 * <p>The overlay is intentionally programmatic so callers can attach it to any Activity without
 * layout resource coupling. It only performs target actions after an actual user tap inside the
 * highlighted target area; side-effect steps never auto-click.</p>
 */
public final class GuidedTutorialOverlay {

    public static final long DEFAULT_SKIP_DELAY_MS = 20_000L;

    private static final int COLOR_DIM = 0xB8000000;
    private static final int COLOR_CARD = Color.WHITE;
    private static final int COLOR_TEXT = Color.rgb(24, 34, 30);
    private static final int COLOR_MUTED = Color.rgb(91, 104, 98);
    private static final int COLOR_PRIMARY = Color.rgb(30, 111, 82);
    private static final int COLOR_PRIMARY_DARK = Color.rgb(21, 95, 67);
    private static final int COLOR_BORDER = Color.rgb(202, 213, 204);
    private static final int COLOR_WARNING = Color.rgb(143, 55, 42);
    private static final int COLOR_TARGET_FILL = 0x18FFFFFF;

    private static final int[] RELOCATE_DELAYS_MS = new int[] {0, 80, 180, 360, 700};

    private final Activity mActivity;
    private final ViewGroup mContainer;
    private final List<Step> mSteps;
    private final Listener mListener;
    private final Handler mHandler = new Handler(Looper.getMainLooper());

    private TutorialRootView mRootView;
    private MaskView mMaskView;
    private LinearLayout mCardView;
    private TextView mCountView;
    private TextView mTitleView;
    private TextView mBodyView;
    private TextView mInstructionView;
    private TextView mMissingTargetView;
    private ProgressBar mProgressBar;
    private Button mNextButton;
    private Button mSkipButton;

    private int mStepIndex;
    private RectF mTargetRect;
    private boolean mTargetMissing;
    private boolean mSkipUnlocked;
    private boolean mStarted;
    private boolean mDestroyed;

    private final Runnable mUnlockSkipRunnable = new Runnable() {
        @Override
        public void run() {
            mSkipUnlocked = true;
            updateSkipButton();
        }
    };

    private final ViewTreeObserver.OnGlobalLayoutListener mRelayoutListener =
        new ViewTreeObserver.OnGlobalLayoutListener() {
            @Override
            public void onGlobalLayout() {
                updateTargetAndCard();
            }
        };

    public GuidedTutorialOverlay(Activity activity, ViewGroup container, List<Step> steps,
                                 Listener listener) {
        if (activity == null) {
            throw new IllegalArgumentException("activity == null");
        }
        if (container == null) {
            throw new IllegalArgumentException("container == null");
        }
        if (steps == null || steps.isEmpty()) {
            throw new IllegalArgumentException("steps must not be empty");
        }

        mActivity = activity;
        mContainer = container;
        mSteps = Collections.unmodifiableList(new ArrayList<>(steps));
        mListener = listener;
    }

    public void start() {
        if (mStarted || mDestroyed || mActivity.isFinishing()) {
            return;
        }

        mStarted = true;
        createViews();
        mContainer.addView(mRootView);
        mRootView.getViewTreeObserver().addOnGlobalLayoutListener(mRelayoutListener);
        showStep(0);
    }

    public void destroy() {
        if (mDestroyed) {
            return;
        }

        mDestroyed = true;
        mHandler.removeCallbacksAndMessages(null);
        if (mRootView != null) {
            if (mRootView.getViewTreeObserver().isAlive()) {
                mRootView.getViewTreeObserver().removeOnGlobalLayoutListener(mRelayoutListener);
            }
            ViewGroup parent = (ViewGroup) mRootView.getParent();
            if (parent != null) {
                parent.removeView(mRootView);
            }
        }
    }

    public boolean isShowing() {
        return mStarted && !mDestroyed && mRootView != null && mRootView.getParent() != null;
    }

    public int getStepIndex() {
        return mStepIndex;
    }

    public Step getCurrentStep() {
        if (mStepIndex < 0 || mStepIndex >= mSteps.size()) {
            return null;
        }
        return mSteps.get(mStepIndex);
    }

    public boolean next() {
        if (mStepIndex >= mSteps.size() - 1) {
            finish();
            return false;
        }
        showStep(mStepIndex + 1);
        return true;
    }

    public boolean previous() {
        if (mStepIndex <= 0) {
            return false;
        }
        showStep(mStepIndex - 1);
        return true;
    }

    public void finish() {
        if (mListener != null) {
            mListener.onFinished(this);
        }
        destroy();
    }

    public void skip() {
        if (!mSkipUnlocked) {
            return;
        }
        Step step = getCurrentStep();
        if (mListener != null) {
            mListener.onSkipped(this, step);
        }
        destroy();
    }

    public void refreshTarget() {
        scheduleRelocationPasses();
    }

    public static TargetSupplier targetById(final View root, final int viewId) {
        return new TargetSupplier() {
            @Override
            public View getTargetView() {
                return root == null ? null : root.findViewById(viewId);
            }
        };
    }

    public static TargetSupplier targetByTag(final View root, final Object tag) {
        return new TargetSupplier() {
            @Override
            public View getTargetView() {
                return root == null ? null : root.findViewWithTag(tag);
            }
        };
    }

    private void createViews() {
        mRootView = new TutorialRootView(mActivity);
        mRootView.setLayoutParams(new ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ));

        mMaskView = new MaskView(mActivity);
        mRootView.addView(mMaskView, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ));

        mCardView = new LinearLayout(mActivity);
        mCardView.setOrientation(LinearLayout.VERTICAL);
        mCardView.setPadding(dp(18), dp(16), dp(18), dp(14));
        mCardView.setBackground(makeRoundedBackground(COLOR_CARD, COLOR_BORDER, dp(8)));
        mCardView.setElevation(dp(6));

        mCountView = new TextView(mActivity);
        mCountView.setTextColor(COLOR_PRIMARY_DARK);
        mCountView.setTextSize(12);
        mCountView.setTypeface(Typeface.DEFAULT_BOLD);
        mCardView.addView(mCountView, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        mTitleView = new TextView(mActivity);
        mTitleView.setTextColor(COLOR_TEXT);
        mTitleView.setTextSize(18);
        mTitleView.setTypeface(Typeface.DEFAULT_BOLD);
        mTitleView.setPadding(0, dp(6), 0, 0);
        mCardView.addView(mTitleView, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        mBodyView = new TextView(mActivity);
        mBodyView.setTextColor(COLOR_MUTED);
        mBodyView.setTextSize(14);
        mBodyView.setLineSpacing(dp(2), 1.0f);
        mBodyView.setPadding(0, dp(8), 0, 0);
        mCardView.addView(mBodyView, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        mInstructionView = new TextView(mActivity);
        mInstructionView.setTextColor(COLOR_PRIMARY_DARK);
        mInstructionView.setTextSize(13);
        mInstructionView.setTypeface(Typeface.DEFAULT_BOLD);
        mInstructionView.setPadding(0, dp(12), 0, 0);
        mCardView.addView(mInstructionView, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        mMissingTargetView = new TextView(mActivity);
        mMissingTargetView.setTextColor(COLOR_WARNING);
        mMissingTargetView.setTextSize(13);
        mMissingTargetView.setPadding(0, dp(10), 0, 0);
        mCardView.addView(mMissingTargetView, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        mProgressBar = new ProgressBar(mActivity);
        mProgressBar.setIndeterminate(true);
        LinearLayout.LayoutParams progressParams = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        );
        progressParams.gravity = Gravity.CENTER_HORIZONTAL;
        progressParams.topMargin = dp(12);
        mCardView.addView(mProgressBar, progressParams);

        LinearLayout actionRow = new LinearLayout(mActivity);
        actionRow.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        actionRow.setPadding(0, dp(16), 0, 0);

        mSkipButton = new Button(mActivity);
        mSkipButton.setText(R.string.openhouse_usage_tutorial_skip);
        mSkipButton.setAllCaps(false);
        mSkipButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                skip();
            }
        });
        actionRow.addView(mSkipButton, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        mNextButton = new Button(mActivity);
        mNextButton.setText(R.string.openhouse_usage_tutorial_next);
        mNextButton.setAllCaps(false);
        mNextButton.setTextColor(Color.WHITE);
        mNextButton.setBackground(makeRoundedBackground(COLOR_PRIMARY, COLOR_PRIMARY, dp(6)));
        mNextButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                next();
            }
        });
        LinearLayout.LayoutParams nextParams = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        );
        nextParams.leftMargin = dp(10);
        actionRow.addView(mNextButton, nextParams);

        mCardView.addView(actionRow, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        FrameLayout.LayoutParams cardParams = new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        );
        cardParams.leftMargin = dp(20);
        cardParams.rightMargin = dp(20);
        cardParams.gravity = Gravity.BOTTOM;
        cardParams.bottomMargin = dp(24);
        mRootView.addView(mCardView, cardParams);
    }

    private void showStep(int stepIndex) {
        if (mDestroyed) {
            return;
        }

        mStepIndex = Math.max(0, Math.min(stepIndex, mSteps.size() - 1));
        Step step = mSteps.get(mStepIndex);

        mTargetRect = null;
        mTargetMissing = false;
        mSkipUnlocked = step.skipDelayMs <= 0;
        mHandler.removeCallbacks(mUnlockSkipRunnable);
        if (!mSkipUnlocked) {
            mHandler.postDelayed(mUnlockSkipRunnable, step.skipDelayMs);
        }

        mCountView.setText(mActivity.getString(
            R.string.openhouse_usage_tutorial_step_count,
            mStepIndex + 1,
            mSteps.size()
        ));
        mTitleView.setText(step.title);
        mBodyView.setText(step.body);
        mProgressBar.setVisibility(step.type == StepType.PROGRESS ? View.VISIBLE : View.GONE);

        updateStepControls(step);
        scheduleRelocationPasses();

        if (mListener != null) {
            mListener.onStepChanged(this, step, mStepIndex);
        }
    }

    private void updateStepControls(Step step) {
        if (step == null) {
            return;
        }

        boolean clickStep = step.type == StepType.REQUIRED_CLICK
            || step.type == StepType.SIDE_EFFECT_CLICK;

        mInstructionView.setVisibility(clickStep && !mTargetMissing ? View.VISIBLE : View.GONE);
        if (step.type == StepType.SIDE_EFFECT_CLICK) {
            mInstructionView.setText(R.string.openhouse_usage_tutorial_side_effect_instruction);
        } else if (step.type == StepType.REQUIRED_CLICK) {
            mInstructionView.setText(R.string.openhouse_usage_tutorial_click_instruction);
        }

        boolean shouldShowNext = step.type == StepType.EXPLANATION
            || step.type == StepType.PROGRESS
            || mTargetMissing;
        mNextButton.setVisibility(shouldShowNext ? View.VISIBLE : View.GONE);
        mNextButton.setText(mStepIndex == mSteps.size() - 1
            ? mActivity.getString(R.string.openhouse_usage_tutorial_finish)
            : mActivity.getString(R.string.openhouse_usage_tutorial_next));

        mSkipButton.setVisibility(clickStep && !mTargetMissing ? View.VISIBLE : View.GONE);
        updateSkipButton();
    }

    private void updateSkipButton() {
        if (mSkipButton == null) {
            return;
        }

        mSkipButton.setEnabled(mSkipUnlocked);
        mSkipButton.setText(mSkipUnlocked
            ? mActivity.getString(R.string.openhouse_usage_tutorial_skip)
            : mActivity.getString(R.string.openhouse_usage_tutorial_skip_wait));
    }

    private void scheduleRelocationPasses() {
        for (int delay : RELOCATE_DELAYS_MS) {
            mHandler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    updateTargetAndCard();
                }
            }, delay);
        }
    }

    private void updateTargetAndCard() {
        if (mDestroyed || mRootView == null || mRootView.getWidth() == 0
            || mRootView.getHeight() == 0) {
            return;
        }

        Step step = getCurrentStep();
        RectF targetRect = findTargetRect(step);
        mTargetRect = targetRect;
        mTargetMissing = step != null && step.needsTarget() && targetRect == null;

        mMaskView.setTargetRect(targetRect);
        mMissingTargetView.setVisibility(mTargetMissing ? View.VISIBLE : View.GONE);
        mMissingTargetView.setText(R.string.openhouse_usage_tutorial_target_missing);
        updateStepControls(step);
        layoutCard(targetRect);
        mRootView.invalidate();
    }

    private RectF findTargetRect(Step step) {
        if (step == null || !step.needsTarget() || step.targetSupplier == null) {
            return null;
        }

        View target = step.targetSupplier.getTargetView();
        if (target == null || target.getVisibility() != View.VISIBLE || target.getWidth() <= 0
            || target.getHeight() <= 0) {
            return null;
        }

        Rect targetGlobalRect = new Rect();
        if (!target.getGlobalVisibleRect(targetGlobalRect) || targetGlobalRect.isEmpty()) {
            return null;
        }

        int[] rootLocation = new int[2];
        mRootView.getLocationOnScreen(rootLocation);
        RectF rect = new RectF(
            targetGlobalRect.left - rootLocation[0],
            targetGlobalRect.top - rootLocation[1],
            targetGlobalRect.right - rootLocation[0],
            targetGlobalRect.bottom - rootLocation[1]
        );
        rect.inset(-dp(8), -dp(8));
        rect.left = clamp(rect.left, dp(8), mRootView.getWidth() - dp(8));
        rect.top = clamp(rect.top, dp(8), mRootView.getHeight() - dp(8));
        rect.right = clamp(rect.right, dp(8), mRootView.getWidth() - dp(8));
        rect.bottom = clamp(rect.bottom, dp(8), mRootView.getHeight() - dp(8));
        return rect.width() > 0 && rect.height() > 0 ? rect : null;
    }

    private void layoutCard(RectF targetRect) {
        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) mCardView.getLayoutParams();
        params.leftMargin = dp(20);
        params.rightMargin = dp(20);

        if (targetRect == null) {
            params.gravity = Gravity.BOTTOM;
            params.topMargin = 0;
            params.bottomMargin = dp(24);
        } else if (targetRect.centerY() > mRootView.getHeight() / 2f) {
            params.gravity = Gravity.TOP;
            params.topMargin = dp(24);
            params.bottomMargin = 0;
        } else {
            params.gravity = Gravity.BOTTOM;
            params.topMargin = 0;
            params.bottomMargin = dp(24);
        }

        mCardView.setLayoutParams(params);
        mMaskView.setCardView(mCardView);
    }

    private boolean handleTargetTap() {
        Step step = getCurrentStep();
        if (step == null || !step.needsTarget() || mTargetMissing) {
            return true;
        }

        boolean handled = false;
        if (step.targetClickAction != null) {
            handled = step.targetClickAction.onTargetClick(this, step);
        } else {
            View target = step.targetSupplier == null ? null : step.targetSupplier.getTargetView();
            if (target != null) {
                handled = target.performClick();
            }
        }

        if (mListener != null) {
            mListener.onTargetClicked(this, step, handled);
        }

        if (step.type == StepType.REQUIRED_CLICK && step.advanceAfterTargetClick) {
            next();
        }

        return true;
    }

    private android.graphics.drawable.GradientDrawable makeRoundedBackground(int fillColor,
                                                                             int strokeColor,
                                                                             int radius) {
        android.graphics.drawable.GradientDrawable drawable =
            new android.graphics.drawable.GradientDrawable();
        drawable.setColor(fillColor);
        drawable.setCornerRadius(radius);
        drawable.setStroke(dp(1), strokeColor);
        return drawable;
    }

    private int dp(float value) {
        return Math.round(value * mActivity.getResources().getDisplayMetrics().density);
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    public enum StepType {
        EXPLANATION,
        REQUIRED_CLICK,
        SIDE_EFFECT_CLICK,
        PROGRESS
    }

    public interface TargetSupplier {
        View getTargetView();
    }

    public interface TargetClickAction {
        /**
         * Runs only after the user taps the highlighted target area.
         *
         * @return true if the action was handled by the caller.
         */
        boolean onTargetClick(GuidedTutorialOverlay overlay, Step step);
    }

    public interface Listener {
        void onStepChanged(GuidedTutorialOverlay overlay, Step step, int stepIndex);
        void onTargetClicked(GuidedTutorialOverlay overlay, Step step, boolean handled);
        void onSkipped(GuidedTutorialOverlay overlay, Step step);
        void onFinished(GuidedTutorialOverlay overlay);
    }

    public static class SimpleListener implements Listener {
        @Override
        public void onStepChanged(GuidedTutorialOverlay overlay, Step step, int stepIndex) {
        }

        @Override
        public void onTargetClicked(GuidedTutorialOverlay overlay, Step step, boolean handled) {
        }

        @Override
        public void onSkipped(GuidedTutorialOverlay overlay, Step step) {
        }

        @Override
        public void onFinished(GuidedTutorialOverlay overlay) {
        }
    }

    public static final class Step {
        public final StepType type;
        public final CharSequence title;
        public final CharSequence body;
        public final TargetSupplier targetSupplier;
        public final TargetClickAction targetClickAction;
        public final long skipDelayMs;
        public final boolean advanceAfterTargetClick;

        private Step(Builder builder) {
            type = builder.type;
            title = builder.title;
            body = builder.body;
            targetSupplier = builder.targetSupplier;
            targetClickAction = builder.targetClickAction;
            skipDelayMs = builder.skipDelayMs;
            advanceAfterTargetClick = builder.advanceAfterTargetClick;
        }

        public boolean needsTarget() {
            return type == StepType.REQUIRED_CLICK || type == StepType.SIDE_EFFECT_CLICK;
        }

        public static Builder explanation(CharSequence title, CharSequence body) {
            return new Builder(StepType.EXPLANATION).title(title).body(body).skipDelayMs(0);
        }

        public static Builder requiredClick(CharSequence title, CharSequence body,
                                            TargetSupplier targetSupplier) {
            return new Builder(StepType.REQUIRED_CLICK)
                .title(title)
                .body(body)
                .target(targetSupplier)
                .skipDelayMs(DEFAULT_SKIP_DELAY_MS);
        }

        public static Builder sideEffectClick(CharSequence title, CharSequence body,
                                              TargetSupplier targetSupplier) {
            return new Builder(StepType.SIDE_EFFECT_CLICK)
                .title(title)
                .body(body)
                .target(targetSupplier)
                .skipDelayMs(DEFAULT_SKIP_DELAY_MS)
                .advanceAfterTargetClick(false);
        }

        public static Builder progress(CharSequence title, CharSequence body) {
            return new Builder(StepType.PROGRESS).title(title).body(body).skipDelayMs(0);
        }
    }

    public static final class Builder {
        private final StepType type;
        private CharSequence title = "";
        private CharSequence body = "";
        private TargetSupplier targetSupplier;
        private TargetClickAction targetClickAction;
        private long skipDelayMs = DEFAULT_SKIP_DELAY_MS;
        private boolean advanceAfterTargetClick = true;

        public Builder(StepType type) {
            if (type == null) {
                throw new IllegalArgumentException("type == null");
            }
            this.type = type;
        }

        public Builder title(CharSequence title) {
            this.title = title == null ? "" : title;
            return this;
        }

        public Builder body(CharSequence body) {
            this.body = body == null ? "" : body;
            return this;
        }

        public Builder target(TargetSupplier targetSupplier) {
            this.targetSupplier = targetSupplier;
            return this;
        }

        public Builder onTargetClick(TargetClickAction targetClickAction) {
            this.targetClickAction = targetClickAction;
            return this;
        }

        public Builder skipDelayMs(long skipDelayMs) {
            this.skipDelayMs = Math.max(0L, skipDelayMs);
            return this;
        }

        public Builder advanceAfterTargetClick(boolean advanceAfterTargetClick) {
            this.advanceAfterTargetClick = advanceAfterTargetClick;
            return this;
        }

        public Step build() {
            return new Step(this);
        }
    }

    private final class TutorialRootView extends FrameLayout {
        TutorialRootView(Activity activity) {
            super(activity);
            setWillNotDraw(false);
            setClickable(true);
            setFocusable(true);
        }

        @Override
        public boolean dispatchTouchEvent(MotionEvent event) {
            if (event == null || mDestroyed) {
                return super.dispatchTouchEvent(event);
            }

            if (isInsideCard(event)) {
                return super.dispatchTouchEvent(event);
            }

            Step step = getCurrentStep();
            if (step != null && step.needsTarget() && mTargetRect != null
                && mTargetRect.contains(event.getX(), event.getY())) {
                if (event.getActionMasked() == MotionEvent.ACTION_UP) {
                    return handleTargetTap();
                }
                return true;
            }

            return true;
        }

        private boolean isInsideCard(MotionEvent event) {
            return mCardView != null
                && event.getX() >= mCardView.getLeft()
                && event.getX() <= mCardView.getRight()
                && event.getY() >= mCardView.getTop()
                && event.getY() <= mCardView.getBottom();
        }
    }

    private final class MaskView extends View {
        private final Paint mDimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mClearPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mTargetFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mTargetStrokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mArrowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF mCardRect = new RectF();
        private RectF mHighlightRect;
        private View mCard;

        MaskView(Activity activity) {
            super(activity);
            setLayerType(View.LAYER_TYPE_SOFTWARE, null);
            mDimPaint.setColor(COLOR_DIM);
            mClearPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.CLEAR));
            mTargetFillPaint.setColor(COLOR_TARGET_FILL);
            mTargetStrokePaint.setColor(Color.WHITE);
            mTargetStrokePaint.setStyle(Paint.Style.STROKE);
            mTargetStrokePaint.setStrokeWidth(dp(2));
            mArrowPaint.setColor(Color.WHITE);
            mArrowPaint.setStyle(Paint.Style.FILL_AND_STROKE);
            mArrowPaint.setStrokeWidth(dp(3));
            mArrowPaint.setStrokeCap(Paint.Cap.ROUND);
        }

        void setTargetRect(RectF targetRect) {
            mHighlightRect = targetRect == null ? null : new RectF(targetRect);
            invalidate();
        }

        void setCardView(View cardView) {
            mCard = cardView;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);

            canvas.drawRect(0, 0, getWidth(), getHeight(), mDimPaint);
            if (mHighlightRect == null) {
                return;
            }

            float radius = dp(10);
            canvas.drawRoundRect(mHighlightRect, radius, radius, mClearPaint);
            canvas.drawRoundRect(mHighlightRect, radius, radius, mTargetFillPaint);
            canvas.drawRoundRect(mHighlightRect, radius, radius, mTargetStrokePaint);
            drawArrow(canvas);
        }

        private void drawArrow(Canvas canvas) {
            if (mCard == null || mCard.getWidth() <= 0 || mCard.getHeight() <= 0) {
                return;
            }

            mCardRect.set(mCard.getLeft(), mCard.getTop(), mCard.getRight(), mCard.getBottom());
            float startX = mCardRect.centerX();
            float startY = mCardRect.top > mHighlightRect.bottom
                ? mCardRect.top
                : mCardRect.bottom;
            float endX = mHighlightRect.centerX();
            float endY = mCardRect.top > mHighlightRect.bottom
                ? mHighlightRect.bottom
                : mHighlightRect.top;

            canvas.drawLine(startX, startY, endX, endY, mArrowPaint);

            double angle = Math.atan2(endY - startY, endX - startX);
            float headSize = dp(9);
            Path arrowHead = new Path();
            arrowHead.moveTo(endX, endY);
            arrowHead.lineTo(
                (float) (endX - headSize * Math.cos(angle - Math.PI / 6)),
                (float) (endY - headSize * Math.sin(angle - Math.PI / 6))
            );
            arrowHead.lineTo(
                (float) (endX - headSize * Math.cos(angle + Math.PI / 6)),
                (float) (endY - headSize * Math.sin(angle + Math.PI / 6))
            );
            arrowHead.close();
            canvas.drawPath(arrowHead, mArrowPaint);
        }
    }
}
