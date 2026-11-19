package com.pocketflashcards.app;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Insets;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.animation.DecelerateInterpolator;
import android.widget.AdapterView;
import android.widget.FrameLayout;

final class SlidingMenuLayout extends FrameLayout {
    private static final float WIDTH_FRACTION = 0.65f;
    private static final float SWIPE_FRACTION = 0.25f;
    private static final int ANIMATION_MS = 220;

    private final ViewGroup content;
    private final View panel;
    private final View dismissArea;
    private final Runnable onOpenChanged;
    private final int touchSlop;
    private final int maxPanelWidth;
    private final int minSwipeDistance;
    private final int contentFocusability;

    private ValueAnimator animator;
    private boolean swipeEnabled;
    private boolean open;
    private boolean tracking;
    private boolean dragging;
    private float position;
    private float downX;
    private float downY;
    private float downPosition;

    SlidingMenuLayout(Context context, ViewGroup content, View panel, Runnable onOpenChanged) {
        super(context);
        this.content = content;
        this.panel = panel;
        this.onOpenChanged = onOpenChanged;
        contentFocusability = content.getDescendantFocusability();
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        float density = getResources().getDisplayMetrics().density;
        maxPanelWidth = Math.round(288 * density);
        minSwipeDistance = Math.round(40 * density);

        setBackgroundColor(FlashcardViews.PAPER);
        addView(content, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT,
                Gravity.START | Gravity.TOP));

        dismissArea = new View(context);
        dismissArea.setContentDescription(context.getString(R.string.menu_close));
        dismissArea.setFocusable(true);
        dismissArea.setOnClickListener(view -> setOpen(false, true));
        addView(dismissArea, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT,
                Gravity.START | Gravity.TOP));

        panel.setAccessibilityPaneTitle(context.getString(R.string.menu_main));
        panel.setElevation(6 * density);
        addView(panel, new LayoutParams(0, LayoutParams.MATCH_PARENT, Gravity.START | Gravity.TOP));
        panel.setOnApplyWindowInsetsListener((view, insets) -> {
            Insets safe = insets.getInsets(WindowInsets.Type.systemBars()
                    | WindowInsets.Type.displayCutout());
            LayoutParams params = (LayoutParams) view.getLayoutParams();
            if (params.topMargin != safe.top || params.bottomMargin != safe.bottom) {
                params.topMargin = safe.top;
                params.bottomMargin = safe.bottom;
                view.setLayoutParams(params);
            }
            view.setPadding(isRtl() ? 0 : safe.left, view.getPaddingTop(),
                    isRtl() ? safe.right : 0, view.getPaddingBottom());
            return insets;
        });
        updateAccessibility();
        positionChildren();
    }

    void setSwipeEnabled(boolean enabled) {
        swipeEnabled = enabled;
    }

    boolean isOpen() {
        return open;
    }

    void setOpen(boolean open, boolean animate) {
        boolean changed = this.open != open;
        this.open = open;
        tracking = false;
        dragging = false;
        stopAnimation();

        float destination = open ? 1f : 0f;
        if (animate && position != destination && getWidth() > 0) {
            animator = ValueAnimator.ofFloat(position, destination);
            animator.setDuration(ANIMATION_MS);
            animator.setInterpolator(new DecelerateInterpolator());
            animator.addUpdateListener(frame -> setPosition((float) frame.getAnimatedValue()));
            animator.start();
        } else {
            setPosition(destination);
        }
        if (changed) {
            updateAccessibility();
            onOpenChanged.run();
        }
    }

    private void updateAccessibility() {
        content.setImportantForAccessibility(open
                ? IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS : IMPORTANT_FOR_ACCESSIBILITY_AUTO);
        content.setDescendantFocusability(open ? FOCUS_BLOCK_DESCENDANTS : contentFocusability);
        panel.setImportantForAccessibility(open
                ? IMPORTANT_FOR_ACCESSIBILITY_AUTO : IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        dismissArea.setImportantForAccessibility(open
                ? IMPORTANT_FOR_ACCESSIBILITY_YES : IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        panel.getLayoutParams().width = Math.min(maxPanelWidth,
                Math.round(MeasureSpec.getSize(widthMeasureSpec) * WIDTH_FRACTION));
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);
        positionChildren();
    }

    private void setPosition(float fraction) {
        position = Math.max(0f, Math.min(1f, fraction));
        positionChildren();
    }

    private void positionChildren() {
        int width = panel.getMeasuredWidth();
        float direction = isRtl() ? -1f : 1f;
        float shift = position * width * direction;
        content.setTranslationX(shift);
        dismissArea.setTranslationX(shift);
        panel.setTranslationX(shift - width * direction);
        int visibility = position > 0f ? VISIBLE : INVISIBLE;
        panel.setVisibility(visibility);
        dismissArea.setVisibility(visibility);
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = event.getX();
                downY = event.getY();
                downPosition = position;
                dragging = false;
                tracking = open || (swipeEnabled && !hitsControl(content,
                        downX - content.getX(), downY - content.getY()));
                return false;
            case MotionEvent.ACTION_MOVE:
                return moveDrag(event);
            case MotionEvent.ACTION_POINTER_DOWN:
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                tracking = false;
                return false;
            default:
                return false;
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                return tracking;
            case MotionEvent.ACTION_MOVE:
                moveDrag(event);
                return true;
            case MotionEvent.ACTION_UP:
                if (dragging) {
                    moveDrag(event);
                    float threshold = Math.max(SWIPE_FRACTION,
                            (float) minSwipeDistance / panel.getMeasuredWidth());
                    setOpen(open ? position > 1f - threshold : position >= threshold, true);
                }
                tracking = false;
                return true;
            case MotionEvent.ACTION_CANCEL:
            case MotionEvent.ACTION_POINTER_DOWN:
                if (dragging) {
                    setOpen(open, true);
                }
                tracking = false;
                return true;
            default:
                return true;
        }
    }

    private boolean moveDrag(MotionEvent event) {
        int width = panel.getMeasuredWidth();
        if (!tracking || width == 0) {
            return false;
        }
        float dx = (event.getX() - downX) * (isRtl() ? -1f : 1f);
        float dy = event.getY() - downY;
        if (!dragging) {
            if (Math.max(Math.abs(dx), Math.abs(dy)) < touchSlop) {
                return false;
            }
            if ((open ? dx >= 0 : dx <= 0) || Math.abs(dx) < 1.25f * Math.abs(dy)) {
                tracking = false;
                return false;
            }
            dragging = true;
            stopAnimation();
        }
        setPosition(downPosition + dx / width);
        return true;
    }

    private boolean isRtl() {
        return getLayoutDirection() == LAYOUT_DIRECTION_RTL;
    }

    private static boolean hitsControl(View view, float x, float y) {
        if (view.getVisibility() != VISIBLE || x < 0 || y < 0
                || x >= view.getWidth() || y >= view.getHeight()) {
            return false;
        }
        // A list can handle scrolling even where there is no deck row to tap.
        if (!(view instanceof AdapterView) && (view.isClickable() || view.isLongClickable())) {
            return true;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = group.getChildCount() - 1; i >= 0; i--) {
                View child = group.getChildAt(i);
                if (hitsControl(child, x + group.getScrollX() - child.getX(),
                        y + group.getScrollY() - child.getY())) {
                    return true;
                }
            }
        }
        return false;
    }

    private void stopAnimation() {
        if (animator != null) {
            animator.cancel();
            animator = null;
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        stopAnimation();
        setPosition(open ? 1f : 0f);
        super.onDetachedFromWindow();
    }
}
