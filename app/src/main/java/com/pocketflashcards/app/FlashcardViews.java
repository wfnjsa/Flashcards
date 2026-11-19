package com.pocketflashcards.app;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Insets;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.text.InputFilter;
import android.text.InputType;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.RelativeSizeSpan;
import android.view.Gravity;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;

final class FlashcardViews {
    static final int PAPER = 0xFFF5F3EB;
    static final int CARD = 0xFFFFFDF5;
    static final int INK = 0xFF1D3029;
    static final int MUTED = 0xFF50665B;
    static final int GREEN = 0xFF23614E;
    static final int DARK_GREEN = 0xFF173C32;
    static final int LIGHT_GREEN = 0xFFE2EDE4;
    static final int OUTLINE = 0xFFD9DED1;

    private final Context context;

    FlashcardViews(Context context) {
        this.context = context;
    }

    int dp(float value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    static CharSequence withReading(String word, String reading) {
        if (reading == null || reading.trim().isEmpty()) {
            return word;
        }
        SpannableString text = new SpannableString(word + "\n" + reading);
        text.setSpan(new RelativeSizeSpan(0.6f), word.length() + 1, text.length(),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return text;
    }

    TextView text(CharSequence text, int size, int color, String typeface) {
        TextView view = new TextView(context);
        view.setText(text);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setTypeface(Typeface.create(typeface, Typeface.NORMAL));
        return view;
    }

    Button button(CharSequence label, boolean primary) {
        Button button = new Button(context);
        button.setText(label);
        button.setTextSize(14);
        button.setTextColor(primary ? 0xFFFFFFFF : GREEN);
        button.setAllCaps(false);
        button.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setMinHeight(dp(48));
        button.setMinimumHeight(dp(48));
        button.setPadding(dp(12), dp(6), dp(12), dp(6));
        button.setElevation(0);
        button.setStateListAnimator(null);
        button.setBackground(ripple(primary ? GREEN : LIGHT_GREEN, 12));
        return button;
    }

    ImageButton iconButton(int drawable, String label, boolean filled) {
        ImageButton button = new ImageButton(context);
        button.setImageResource(drawable);
        button.setImageTintList(ColorStateList.valueOf(filled ? GREEN : INK));
        button.setContentDescription(label);
        button.setBackground(ripple(filled ? LIGHT_GREEN : PAPER, 12));
        return button;
    }

    LinearLayout activityRoot() {
        LinearLayout root = column();
        root.setBackgroundColor(PAPER);
        root.setOnApplyWindowInsetsListener((view, windowInsets) -> {
            Insets insets = windowInsets.getInsets(WindowInsets.Type.systemBars()
                    | WindowInsets.Type.displayCutout() | WindowInsets.Type.ime());
            view.setPadding(insets.left, insets.top, insets.right, insets.bottom);
            WindowInsetsController controller = view.getWindowInsetsController();
            if (controller != null) {
                int lightBars = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                        | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
                controller.setSystemBarsAppearance(lightBars, lightBars);
            }
            return windowInsets;
        });
        return root;
    }

    EditText input(String hint, int lines, int maxLength) {
        EditText input = new EditText(context);
        input.setHint(hint);
        input.setTextSize(16);
        input.setTextColor(INK);
        input.setHintTextColor(MUTED);
        input.setSingleLine(lines == 1);
        input.setInputType(InputType.TYPE_CLASS_TEXT | (lines > 1
                ? InputType.TYPE_TEXT_FLAG_MULTI_LINE : 0));
        input.setMinLines(lines);
        input.setMaxLines(lines + (lines == 1 ? 0 : 2));
        input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(maxLength)});
        input.setGravity(Gravity.TOP | Gravity.START);
        input.setPadding(dp(12), dp(11), dp(12), dp(11));
        input.setBackground(shape(CARD, 10, OUTLINE));
        return input;
    }

    LinearLayout column() {
        LinearLayout column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        return column;
    }

    LinearLayout row() {
        LinearLayout row = new LinearLayout(context);
        row.setGravity(Gravity.CENTER_VERTICAL);
        return row;
    }

    LinearLayout.LayoutParams fullWidth(int marginTop) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(marginTop);
        return params;
    }

    LinearLayout.LayoutParams halfWidth(int marginStart) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        params.setMarginStart(dp(marginStart));
        return params;
    }

    GradientDrawable shape(int fill, int radius, int stroke) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(fill);
        shape.setCornerRadius(dp(radius));
        if (stroke != 0) {
            shape.setStroke(dp(1), stroke);
        }
        return shape;
    }

    RippleDrawable ripple(int fill, int radius) {
        return new RippleDrawable(ColorStateList.valueOf(0x3323614E),
                shape(fill, radius, 0), shape(0xFFFFFFFF, radius, 0));
    }
}
