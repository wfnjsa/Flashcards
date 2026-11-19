package com.pocketflashcards.app;

import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.StyleSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.concurrent.ThreadLocalRandom;

public final class DeckReviewActivity extends LocalizedActivity {
    enum Mode { FLASHCARDS, PAIRED }

    private static final String DECK_ID = "deckId";
    private static final String REVIEW_MODE = "reviewMode";

    private FlashcardViews ui;
    private FlashcardStore store;
    private StudyVoice voice;
    private FlashcardStore.Deck deck;
    private Mode mode;
    private LinearLayout content;
    private final Set<Long> reviewedIds = new HashSet<>();
    private List<Long> cards;
    private long shuffleSeed = ThreadLocalRandom.current().nextLong();
    private int position;
    private boolean studyAll;
    private boolean answerVisible;

    static Intent intent(Context context, long deckId, Mode mode) {
        return new Intent(context, DeckReviewActivity.class)
                .putExtra(DECK_ID, deckId)
                .putExtra(REVIEW_MODE, mode.name());
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        ui = new FlashcardViews(this);
        store = new FlashcardStore(this);
        voice = new StudyVoice(this);
        deck = store.deck(getIntent().getLongExtra(DECK_ID, FlashcardStore.ALL_DECKS));
        if (deck == null) {
            finish();
            return;
        }
        mode = Mode.PAIRED.name().equals(getIntent().getStringExtra(REVIEW_MODE))
                ? Mode.PAIRED : Mode.FLASHCARDS;
        restoreRound(state);
        createLayout();
        render();
    }

    private void restoreRound(Bundle state) {
        if (state != null) {
            studyAll = state.getBoolean("studyAll");
            shuffleSeed = state.getLong("shuffleSeed", shuffleSeed);
            long[] reviewed = state.getLongArray("reviewed");
            if (reviewed != null) {
                for (long id : reviewed) {
                    reviewedIds.add(id);
                }
            }
        }
        reloadCards();
        if (state != null) {
            int savedPosition = cards.indexOf(state.getLong("currentCard"));
            if (savedPosition >= 0) {
                position = savedPosition;
                answerVisible = state.getBoolean("answerVisible");
            }
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        state.putBoolean("studyAll", studyAll);
        state.putLong("shuffleSeed", shuffleSeed);
        state.putBoolean("answerVisible", answerVisible);
        if (cards != null && !cards.isEmpty()) {
            state.putLong("currentCard", cards.get(position));
        }
        long[] reviewed = new long[reviewedIds.size()];
        int index = 0;
        for (long id : reviewedIds) {
            reviewed[index++] = id;
        }
        state.putLongArray("reviewed", reviewed);
    }

    @Override
    protected void onDestroy() {
        voice.close();
        store.close();
        super.onDestroy();
    }

    private void createLayout() {
        LinearLayout root = ui.activityRoot();
        LinearLayout header = ui.row();
        header.setPaddingRelative(ui.dp(8), ui.dp(4), ui.dp(16), ui.dp(4));
        ImageButton back = ui.iconButton(R.drawable.ic_back, getString(R.string.back_to_decks), false);
        back.setOnClickListener(view -> finish());
        header.addView(back, new LinearLayout.LayoutParams(ui.dp(48), ui.dp(48)));

        TextView title = ui.text(deck.name, 24, FlashcardViews.INK, "sans-serif-medium");
        title.setMaxLines(1);
        title.setEllipsize(TextUtils.TruncateAt.END);
        title.setAccessibilityHeading(true);
        header.addView(title, ui.halfWidth(8));
        root.addView(header, ui.fullWidth(0));

        content = ui.column();
        content.setPadding(ui.dp(20), 0, ui.dp(20), ui.dp(8));
        root.addView(content, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        setContentView(root);
        root.requestApplyInsets();
    }

    private void reloadCards() {
        cards = store.reviewIds(deck.id, studyAll, System.currentTimeMillis());
        cards.removeAll(reviewedIds);
        // Per-card random ranks keep the remaining order stable when a card is removed.
        cards.sort(Comparator.comparingLong(id -> new SplittableRandom(shuffleSeed ^ id).nextLong()));
        position = 0;
        answerVisible = false;
    }

    private void startRound() {
        reviewedIds.clear();
        shuffleSeed = ThreadLocalRandom.current().nextLong();
        reloadCards();
        render();
    }

    private FlashcardStore.Card currentCard() {
        while (!cards.isEmpty()) {
            FlashcardStore.Card card = store.card(cards.get(position));
            if (card != null && card.deckId == deck.id) {
                return card;
            }
            cards.remove(position);
            if (position == cards.size()) {
                position = 0;
            }
            answerVisible = false;
        }
        return null;
    }

    private View render() {
        content.removeAllViews();
        LinearLayout scope = ui.row();
        scope.addView(scopeButton(getString(R.string.review_due), false), ui.halfWidth(0));
        scope.addView(scopeButton(getString(R.string.review_all), true), ui.halfWidth(8));
        content.addView(scope, ui.fullWidth(0));

        FlashcardStore.Card card = currentCard();
        if (card == null) {
            renderEmptyRound();
            return null;
        }
        String progress = getString(R.string.review_progress, position + 1, cards.size());
        if (!reviewedIds.isEmpty()) {
            progress = getString(R.string.review_progress_with_count, progress, reviewedIds.size());
        }
        content.addView(ui.text(progress, 13, FlashcardViews.MUTED, "sans-serif-medium"),
                ui.fullWidth(10));

        View face = mode == Mode.FLASHCARDS ? singleCard(card) : pairedCards(card);
        LinearLayout.LayoutParams body = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1);
        body.topMargin = ui.dp(8);
        content.addView(face, body);
        renderControls(card);
        return face;
    }

    private Button scopeButton(String label, boolean all) {
        Button button = ui.button(label, studyAll == all);
        button.setSelected(studyAll == all);
        button.setOnClickListener(view -> {
            if (studyAll != all) {
                studyAll = all;
                startRound();
            }
        });
        return button;
    }

    private void renderEmptyRound() {
        long totalCards = store.cardCount(deck.id);
        int title = totalCards == 0 ? R.string.cards_empty
                : reviewedIds.isEmpty() ? R.string.review_none_due : R.string.review_complete;
        content.addView(ui.text(getString(title), 21, FlashcardViews.INK, "sans-serif-medium"),
                ui.fullWidth(24));
        if (totalCards > 0) {
            Button restart = ui.button(getString(studyAll ? R.string.review_restart : R.string.review_refresh_due), false);
            restart.setOnClickListener(view -> startRound());
            content.addView(restart, ui.fullWidth(12));
        }
    }

    private View singleCard(FlashcardStore.Card card) {
        SwipeCard face = new SwipeCard(this);
        face.setOrientation(LinearLayout.VERTICAL);
        face.setGravity(Gravity.CENTER);
        face.setPadding(ui.dp(20), ui.dp(15), ui.dp(20), ui.dp(15));
        face.setBackground(ui.ripple(FlashcardViews.CARD, 18));
        face.setContentDescription(getString(answerVisible ? R.string.review_show_word : R.string.review_show_meaning));
        face.setOnClickListener(view -> {
            answerVisible = !answerVisible;
            render();
        });

        LinearLayout.LayoutParams blank = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 0.8f);
        face.addView(new View(this), blank);

        TextView term = centeredText(answerVisible ? card.definition
                : FlashcardViews.withReading(card.term, card.reading), 39);
        term.setIncludeFontPadding(false);
        term.setMaxLines(answerVisible || card.reading.isEmpty() ? 4 : 6);
        term.setEllipsize(TextUtils.TruncateAt.END);
        term.setAutoSizeTextTypeUniformWithConfiguration(12, 39, 1, TypedValue.COMPLEX_UNIT_SP);
        face.addView(term, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        TextView example = exampleView(card.example);
        example.setMaxLines(5);
        example.setEllipsize(TextUtils.TruncateAt.END);
        example.setAutoSizeTextTypeUniformWithConfiguration(10, 25, 1, TypedValue.COMPLEX_UNIT_SP);
        example.setVisibility(answerVisible && !card.example.isEmpty() ? View.VISIBLE : View.INVISIBLE);
        LinearLayout.LayoutParams exampleParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 0.8f);
        exampleParams.setMarginStart(ui.dp(16));
        exampleParams.setMarginEnd(ui.dp(16));
        face.addView(example, exampleParams);
        return face;
    }

    private View pairedCards(FlashcardStore.Card card) {
        PairedCardColumn pair = new PairedCardColumn(this);
        LinearLayout english = paper();
        english.addView(centeredText(FlashcardViews.withReading(card.term, card.reading), 29), ui.fullWidth(0));
        addPairedCard(pair, english, 0);

        LinearLayout chinese = paper();
        chinese.addView(centeredText(card.definition, 29), ui.fullWidth(0));
        if (!card.example.isEmpty()) {
            chinese.addView(exampleView(card.example), ui.fullWidth(12));
        }
        addPairedCard(pair, chinese, 10);
        return pair;
    }

    private void addPairedCard(LinearLayout pair, LinearLayout text, int marginTop) {
        // Give the fixed-size viewport the paper background while its text scrolls inside.
        Drawable background = text.getBackground();
        text.setBackground(null);
        text.setMinimumHeight(0);

        ScrollView viewport = new ScrollView(this);
        viewport.setFillViewport(true);
        viewport.setBackground(background);
        viewport.setClipToOutline(true);
        viewport.addView(text, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout.LayoutParams slot = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1);
        slot.topMargin = ui.dp(marginTop);
        pair.addView(viewport, slot);
    }

    private LinearLayout paper() {
        LinearLayout paper = ui.column();
        paper.setMinimumHeight(ui.dp(124));
        paper.setGravity(Gravity.CENTER);
        paper.setPadding(ui.dp(20), ui.dp(18), ui.dp(20), ui.dp(18));
        paper.setBackground(ui.shape(FlashcardViews.CARD, 16, FlashcardViews.OUTLINE));
        return paper;
    }

    private TextView centeredText(CharSequence text, int size) {
        TextView view = ui.text(text, size, FlashcardViews.INK, "serif");
        view.setGravity(Gravity.CENTER);
        return view;
    }

    private TextView exampleView(String sentence) {
        String label = getString(R.string.example_label) + "\n";
        SpannableString text = new SpannableString(label + sentence);
        if (!sentence.isEmpty()) {
            text.setSpan(new StyleSpan(Typeface.ITALIC), label.length(), text.length(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        TextView example = ui.text(text, 20, FlashcardViews.MUTED, "sans-serif");
        example.setGravity(Gravity.CENTER);
        return example;
    }

    private void renderControls(FlashcardStore.Card card) {
        LinearLayout tools = ui.row();
        Button previous = ui.button(getString(R.string.review_previous), false);
        previous.setEnabled(cards.size() > 1);
        previous.setOnClickListener(view -> moveCard(-1));
        tools.addView(previous, ui.halfWidth(0));
        Button speak = ui.button(getString(R.string.review_speak), false);
        speak.setOnClickListener(view -> voice.speak(card));
        tools.addView(speak, ui.halfWidth(8));
        Button next = ui.button(getString(R.string.review_next), false);
        next.setEnabled(cards.size() > 1);
        next.setOnClickListener(view -> moveCard(1));
        tools.addView(next, ui.halfWidth(8));
        content.addView(tools, ui.fullWidth(8));

        LinearLayout rating = ui.row();
        Button again = ui.button(getString(R.string.review_again), false);
        again.setOnClickListener(view -> gradeCard(card, false));
        rating.addView(again, ui.halfWidth(0));
        Button good = ui.button(getString(R.string.review_remembered), true);
        good.setOnClickListener(view -> gradeCard(card, true));
        rating.addView(good, ui.halfWidth(8));
        rating.setVisibility(mode == Mode.PAIRED || answerVisible ? View.VISIBLE : View.INVISIBLE);
        content.addView(rating, ui.fullWidth(8));
    }

    private void moveCard(int step) {
        if (cards.size() < 2) {
            return;
        }
        position = Math.floorMod(position + step, cards.size());
        answerVisible = false;
        View face = render();
        if (mode == Mode.FLASHCARDS && face != null) {
            face.setTranslationY(ui.dp(-18 * step));
            face.setAlpha(0.78f);
            face.animate().translationY(0).alpha(1).setDuration(160).start();
        }
    }

    private void gradeCard(FlashcardStore.Card card, boolean remembered) {
        if (cards.isEmpty() || cards.get(position) != card.id) {
            return;
        }
        store.review(card.id, remembered, System.currentTimeMillis());
        reviewedIds.add(card.id);
        cards.remove(position);
        if (position == cards.size()) {
            position = 0;
        }
        answerVisible = false;
        render();
    }

    private final class PairedCardColumn extends LinearLayout {
        private final int touchSlop;
        private float downX;
        private float downY;
        private boolean trackingSwipe;

        PairedCardColumn(Context context) {
            super(context);
            setOrientation(VERTICAL);
            touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        }

        @Override
        public boolean onInterceptTouchEvent(MotionEvent event) {
            updateSwipeTracking(event);
            return event.getActionMasked() == MotionEvent.ACTION_MOVE
                    && isHorizontalSwipe(event, touchSlop);
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            updateSwipeTracking(event);
            if (event.getActionMasked() == MotionEvent.ACTION_UP) {
                boolean swipe = isHorizontalSwipe(event, ui.dp(44));
                trackingSwipe = false;
                if (swipe) {
                    int step = event.getX() < downX ? 1 : -1;
                    moveCard(getLayoutDirection() == LAYOUT_DIRECTION_RTL ? -step : step);
                }
            }
            return true;
        }

        private void updateSwipeTracking(MotionEvent event) {
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) {
                downX = event.getX();
                downY = event.getY();
                trackingSwipe = true;
            } else if (action == MotionEvent.ACTION_CANCEL || event.getPointerCount() > 1) {
                trackingSwipe = false;
            }
        }

        private boolean isHorizontalSwipe(MotionEvent event, int minimumDistance) {
            float distanceX = Math.abs(event.getX() - downX);
            float distanceY = Math.abs(event.getY() - downY);
            return trackingSwipe && distanceX >= minimumDistance && distanceX > distanceY * 1.2f;
        }
    }

    private final class SwipeCard extends LinearLayout {
        private float downX;
        private float downY;

        SwipeCard(Context context) {
            super(context);
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                downX = event.getX();
                downY = event.getY();
            } else if (event.getActionMasked() == MotionEvent.ACTION_UP) {
                float distanceY = event.getY() - downY;
                float distanceX = event.getX() - downX;
                if (Math.abs(distanceY) >= ui.dp(44) && Math.abs(distanceY) > Math.abs(distanceX)) {
                    MotionEvent cancel = MotionEvent.obtain(event);
                    cancel.setAction(MotionEvent.ACTION_CANCEL);
                    super.onTouchEvent(cancel);
                    cancel.recycle();
                    moveCard(distanceY > 0 ? 1 : -1);
                    return true;
                }
            }
            return super.onTouchEvent(event);
        }
    }
}
