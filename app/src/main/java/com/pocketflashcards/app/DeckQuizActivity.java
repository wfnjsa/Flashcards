package com.pocketflashcards.app;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

public final class DeckQuizActivity extends LocalizedActivity {
    private static final String DECK_ID = "deckId";
    private static final int NO_ANSWER = -1;
    private static final int MIN_QUESTION_AREA_DP = 320;
    private static final int WRONG_BACKGROUND = 0xFFF5DBD6;
    private static final int WRONG_TEXT = 0xFF9C392D;

    private FlashcardViews ui;
    private FlashcardStore store;
    private FlashcardStore.Deck deck;
    private LinearLayout content;
    private List<DeckQuiz.Question> questions;
    private int position;
    private int selectedOption = NO_ANSWER;
    private int score;

    static Intent intent(Context context, long deckId) {
        return new Intent(context, DeckQuizActivity.class).putExtra(DECK_ID, deckId);
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        ui = new FlashcardViews(this);
        store = new FlashcardStore(this);
        deck = store.deck(getIntent().getLongExtra(DECK_ID, FlashcardStore.ALL_DECKS));
        if (deck == null) {
            finish();
            return;
        }
        createLayout();
        if (state == null || state.getBundle("round") == null) {
            startRound();
        } else {
            restoreRound(state);
            render();
        }
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
        content.setPadding(ui.dp(20), ui.dp(4), ui.dp(20), ui.dp(12));
        root.addView(scrollable(content), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        setContentView(root);
        root.requestApplyInsets();
    }

    private void startRound() {
        questions = DeckQuiz.generate(store.cards(deck.id), new Random());
        position = 0;
        selectedOption = NO_ANSWER;
        score = 0;
        render();
    }

    private void render() {
        content.removeAllViews();
        if (questions.isEmpty()) {
            content.addView(ui.text(getString(R.string.quiz_empty), 18, FlashcardViews.MUTED,
                    "sans-serif"), ui.fullWidth(20));
            return;
        }
        if (position == questions.size()) {
            renderResult();
            return;
        }
        renderQuestion(questions.get(position));
    }

    private void renderQuestion(DeckQuiz.Question question) {
        TextView progress = ui.text(getString(R.string.quiz_progress, position + 1, questions.size()),
                14, FlashcardViews.MUTED, "sans-serif-medium");
        content.addView(progress, ui.fullWidth(0));
        TextView instruction = ui.text(getString(question.meaningFirst
                ? R.string.quiz_choose_term : R.string.quiz_choose_meaning),
                16, FlashcardViews.INK, "sans-serif-medium");
        content.addView(instruction, ui.fullWidth(6));

        LinearLayout area = ui.column();
        LinearLayout.LayoutParams areaSize = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ui.dp(MIN_QUESTION_AREA_DP), 1);
        areaSize.topMargin = ui.dp(10);
        content.addView(area, areaSize);

        TextView prompt = ui.text(FlashcardViews.withReading(question.prompt, question.promptReading),
                25, FlashcardViews.INK, "serif");
        prompt.setGravity(Gravity.CENTER);
        prompt.setPadding(ui.dp(16), ui.dp(12), ui.dp(16), ui.dp(12));
        ScrollView promptCard = scrollable(prompt);
        promptCard.setBackground(ui.shape(FlashcardViews.CARD, 16, FlashcardViews.OUTLINE));
        promptCard.setClipToOutline(true);
        area.addView(promptCard, equalSlot(0));

        LinearLayout answerSlots = ui.column();
        for (int index = 0; index < DeckQuiz.OPTION_COUNT; index++) {
            View slot;
            if (index < question.options.size()) {
                slot = scrollable(answerButton(question, index));
            } else {
                slot = new View(this);
                slot.setBackground(ui.shape(FlashcardViews.CARD, 12, FlashcardViews.OUTLINE));
                slot.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            }
            answerSlots.addView(slot, equalSlot(index == 0 ? 0 : 6));
        }
        area.addView(answerSlots, equalSlot(8));

        boolean answered = selectedOption != NO_ANSWER;
        boolean correct = selectedOption == question.correctOption;
        TextView feedback = ui.text(getString(correct ? R.string.quiz_correct : R.string.quiz_incorrect),
                15, correct ? FlashcardViews.GREEN : WRONG_TEXT, "sans-serif-medium");
        feedback.setVisibility(answered ? View.VISIBLE : View.INVISIBLE);
        content.addView(feedback, ui.fullWidth(8));

        Button next = ui.button(getString(position + 1 == questions.size()
                ? R.string.quiz_show_result : R.string.quiz_next), true);
        next.setEnabled(answered);
        next.setAlpha(answered ? 1f : 0.45f);
        next.setOnClickListener(view -> {
            if (selectedOption != NO_ANSWER && isCurrentQuestion(question)) {
                position++;
                selectedOption = NO_ANSWER;
                render();
            }
        });
        content.addView(next, ui.fullWidth(6));
    }

    private Button answerButton(DeckQuiz.Question question, int index) {
        String answer = question.options.get(index);
        String reading = question.optionReadings.get(index);
        Button button = ui.button(FlashcardViews.withReading(answer, reading), false);
        if (!reading.isEmpty()) {
            button.setTextSize(20);
        }
        button.setSingleLine(false);
        button.setGravity(Gravity.CENTER);
        button.setEnabled(selectedOption == NO_ANSWER);
        if (selectedOption != NO_ANSWER) {
            if (index == question.correctOption) {
                button.setText(FlashcardViews.withReading("✓ " + answer, reading));
                button.setBackground(ui.shape(FlashcardViews.LIGHT_GREEN, 12, FlashcardViews.GREEN));
            } else if (index == selectedOption) {
                button.setText(FlashcardViews.withReading("✕ " + answer, reading));
                button.setTextColor(WRONG_TEXT);
                button.setBackground(ui.shape(WRONG_BACKGROUND, 12, WRONG_TEXT));
            }
        }
        button.setOnClickListener(view -> {
            if (selectedOption == NO_ANSWER && isCurrentQuestion(question)) {
                selectedOption = index;
                if (index == question.correctOption) {
                    score++;
                }
                render();
            }
        });
        return button;
    }

    private boolean isCurrentQuestion(DeckQuiz.Question question) {
        return position < questions.size() && questions.get(position) == question;
    }

    private void renderResult() {
        TextView result = ui.text(getString(R.string.quiz_score, score, questions.size()),
                24, FlashcardViews.INK, "sans-serif-medium");
        result.setGravity(Gravity.CENTER);
        content.addView(result, ui.fullWidth(24));
        Button retry = ui.button(getString(R.string.quiz_retry), true);
        retry.setOnClickListener(view -> startRound());
        content.addView(retry, ui.fullWidth(16));
    }

    private LinearLayout.LayoutParams equalSlot(int marginTop) {
        LinearLayout.LayoutParams slot = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1);
        slot.topMargin = ui.dp(marginTop);
        return slot;
    }

    private ScrollView scrollable(View child) {
        ScrollView view = new ScrollView(this);
        view.setFillViewport(true);
        view.addView(child, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return view;
    }

    private void restoreRound(Bundle state) {
        Bundle round = state.getBundle("round");
        int count = round.getInt("count");
        questions = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            Bundle saved = round.getBundle(Integer.toString(index));
            String[] readings = saved.getStringArray("optionReadings");
            questions.add(new DeckQuiz.Question(saved.getString("prompt"),
                    Arrays.asList(saved.getStringArray("options")), saved.getInt("correct"),
                    saved.getBoolean("meaningFirst"), saved.getString("promptReading"),
                    readings == null ? null : Arrays.asList(readings)));
        }
        position = state.getInt("position");
        selectedOption = state.getInt("selectedOption", NO_ANSWER);
        score = state.getInt("score");
    }

    @Override
    protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        if (questions == null) {
            return;
        }
        Bundle round = new Bundle();
        round.putInt("count", questions.size());
        for (int index = 0; index < questions.size(); index++) {
            DeckQuiz.Question question = questions.get(index);
            Bundle saved = new Bundle();
            saved.putString("prompt", question.prompt);
            saved.putString("promptReading", question.promptReading);
            saved.putStringArray("optionReadings", question.optionReadings.toArray(new String[0]));
            saved.putStringArray("options", question.options.toArray(new String[0]));
            saved.putInt("correct", question.correctOption);
            saved.putBoolean("meaningFirst", question.meaningFirst);
            round.putBundle(Integer.toString(index), saved);
        }
        state.putBundle("round", round);
        state.putInt("position", position);
        state.putInt("selectedOption", selectedOption);
        state.putInt("score", score);
    }

    @Override
    protected void onDestroy() {
        store.close();
        super.onDestroy();
    }
}
