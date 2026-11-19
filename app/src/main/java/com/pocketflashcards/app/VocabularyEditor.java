package com.pocketflashcards.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.database.sqlite.SQLiteConstraintException;
import android.speech.tts.Voice;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.LongConsumer;

final class VocabularyEditor {
    private final Activity activity;
    private final FlashcardViews ui;
    private final FlashcardStore store;
    private final Runnable onSaved;

    VocabularyEditor(Activity activity, FlashcardViews ui, FlashcardStore store, Runnable onSaved) {
        this.activity = activity;
        this.ui = ui;
        this.store = store;
        this.onSaved = onSaved;
    }

    void editDeck(FlashcardStore.Deck deck) {
        editDeck(deck, null);
    }

    void deleteDecks(List<FlashcardStore.Deck> decks) {
        List<Long> ids = deckIds(decks);
        long count = 0;
        for (long id : ids) {
            count += store.cardCount(id);
        }
        String label = decks.size() == 1
                ? activity.getString(R.string.deck_delete_name, decks.get(0).name)
                : activity.getString(R.string.deck_delete_selection, decks.size());
        confirmDelete(activity.getString(R.string.deck_delete_confirm, label, count),
                () -> store.deleteDecks(ids));
    }

    void mergeDecks(List<FlashcardStore.Deck> decks, LongConsumer onMerged) {
        List<Long> ids = deckIds(decks);
        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle(R.string.deck_merge_choose)
                .setSingleChoiceItems(deckNames(decks), -1, (chooser, index) ->
                        ((AlertDialog) chooser).getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true))
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.continue_label, (chooser, which) -> {
                    int index = ((AlertDialog) chooser).getListView().getCheckedItemPosition();
                    if (index >= 0) {
                        confirmMerge(decks.get(index), ids, onMerged);
                    }
                }).show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
    }

    private void confirmMerge(FlashcardStore.Deck target, List<Long> ids, LongConsumer onMerged) {
        new AlertDialog.Builder(activity)
                .setTitle(activity.getString(R.string.deck_merge_title, target.name))
                .setMessage(R.string.deck_merge_description)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.merge, (dialog, which) -> {
                    if (store.mergeDecks(target.id, ids)) {
                        onMerged.accept(target.id);
                    } else {
                        Toast.makeText(activity, R.string.deck_selection_changed, Toast.LENGTH_SHORT).show();
                        onSaved.run();
                    }
                }).show();
    }

    private static List<Long> deckIds(List<FlashcardStore.Deck> decks) {
        List<Long> ids = new ArrayList<>(decks.size());
        for (FlashcardStore.Deck deck : decks) {
            ids.add(deck.id);
        }
        return ids;
    }

    void editCard(FlashcardStore.Card card, long preferredDeckId) {
        List<FlashcardStore.Deck> decks = store.decks();
        if (decks.isEmpty()) {
            editDeck(null, () -> editCard(null, FlashcardStore.ALL_DECKS));
            return;
        }

        LinearLayout form = form();
        Spinner deckSelector = spinner(deckNames(decks));
        long deckId = card == null ? preferredDeckId : card.deckId;
        for (int index = 0; index < decks.size(); index++) {
            if (decks.get(index).id == deckId) {
                deckSelector.setSelection(index);
                break;
            }
        }
        field(form, activity.getString(R.string.deck_field), deckSelector);
        EditText term = ui.input(activity.getString(R.string.card_term_hint), 1, FlashcardStore.TERM_LIMIT);
        EditText reading = ui.input(activity.getString(R.string.card_reading_hint), 1, FlashcardStore.READING_LIMIT);
        EditText definition = ui.input(activity.getString(R.string.card_meaning_hint), 2, FlashcardStore.DEFINITION_LIMIT);
        EditText example = ui.input(activity.getString(R.string.card_example_hint), 2, FlashcardStore.EXAMPLE_LIMIT);
        field(form, activity.getString(R.string.card_term), term);
        field(form, activity.getString(R.string.card_reading_optional), reading);
        field(form, activity.getString(R.string.card_meaning), definition);
        field(form, activity.getString(R.string.card_example_optional), example);
        if (card != null) {
            term.setText(card.term);
            reading.setText(card.reading);
            definition.setText(card.definition);
            example.setText(card.example);
        }

        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle(card == null ? R.string.card_add : R.string.card_edit)
                .setView(scroll(form))
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.save, null)
                .create();
        showForm(dialog);
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            String word = value(term);
            String meaning = value(definition);
            if (word.isEmpty()) {
                requireText(term, activity.getString(R.string.card_term_required));
                return;
            }
            if (meaning.isEmpty()) {
                requireText(definition, activity.getString(R.string.card_meaning_required));
                return;
            }
            long selectedDeck = decks.get(deckSelector.getSelectedItemPosition()).id;
            store.saveCard(card == null ? 0 : card.id, selectedDeck, word, meaning,
                    value(example), value(reading));
            dialog.dismiss();
            onSaved.run();
        });
    }

    void deleteCards(long deckId, Set<Long> ids) {
        if (ids.isEmpty()) {
            return;
        }
        int count = ids.size();
        String message = activity.getResources().getQuantityString(R.plurals.cards_delete, count, count);
        confirmDelete(message, () -> {
            if (!store.deleteCards(deckId, ids)) {
                Toast.makeText(activity, R.string.card_selection_changed, Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void editDeck(FlashcardStore.Deck deck, Runnable afterCreate) {
        LinearLayout form = form();
        EditText name = ui.input(activity.getString(R.string.deck_name_hint), 1, FlashcardStore.DECK_NAME_LIMIT);
        Locale preferred = Locale.forLanguageTag(
                deck == null ? FlashcardStore.DEFAULT_DECK_LANGUAGE : deck.language);
        Locale display = activity.getResources().getConfiguration().getLocales().get(0);
        Spinner language = new Spinner(activity, Spinner.MODE_DROPDOWN);
        language.setMinimumHeight(ui.dp(48));
        setVoiceChoices(language, null, preferred, display, VoiceCatalog.Status.CHECKING);
        language.setEnabled(false);
        field(form, activity.getString(R.string.deck_name), name);
        field(form, activity.getString(R.string.deck_voice_language), language);
        if (deck != null) {
            name.setText(deck.name);
        }

        AlertDialog.Builder builder = new AlertDialog.Builder(activity)
                .setTitle(deck == null ? R.string.deck_add : R.string.deck_edit)
                .setView(scroll(form))
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.save, null);
        AlertDialog dialog = builder.create();
        showForm(dialog);
        SpeechConnection query = new SpeechConnection(activity, (connection, engine) -> {
            try {
                if (dialog.isShowing() && !activity.isDestroyed()) {
                    Set<Voice> voices = engine == null ? null : engine.getVoices();
                    setVoiceChoices(language, voices, preferred, display, VoiceCatalog.Status.UNAVAILABLE);
                    language.setEnabled(true);
                }
            } finally {
                connection.close();
            }
        });
        dialog.setOnDismissListener(ignored -> query.close());
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            String label = value(name);
            if (label.isEmpty()) {
                requireText(name, activity.getString(R.string.deck_name_required));
                return;
            }
            try {
                VoiceCatalog.Language selection = (VoiceCatalog.Language) language.getSelectedItem();
                store.saveDeck(deck == null ? 0 : deck.id, label,
                        selection == null ? preferred.toLanguageTag() : selection.tag);
            } catch (SQLiteConstraintException duplicateName) {
                requireText(name, activity.getString(R.string.deck_name_duplicate));
                return;
            }
            dialog.dismiss();
            onSaved.run();
            if (afterCreate != null) {
                afterCreate.run();
            }
        });
    }

    private void setVoiceChoices(Spinner spinner, Set<Voice> voices, Locale preferred, Locale display,
            VoiceCatalog.Status missingStatus) {
        List<VoiceCatalog.Language> languages = VoiceCatalog.languages(voices, display);
        String preferredTag = preferred.toLanguageTag();
        int selected = 0;
        while (selected < languages.size() && !languages.get(selected).tag.equals(preferredTag)) {
            selected++;
        }
        if (selected == languages.size()) {
            languages.add(0, new VoiceCatalog.Language(preferred, display, missingStatus));
            selected = 0;
        }
        spinner.setAdapter(new VoiceChoices(languages));
        spinner.setSelection(selected);
    }

    private final class VoiceChoices extends ArrayAdapter<VoiceCatalog.Language> {
        VoiceChoices(List<VoiceCatalog.Language> languages) {
            super(activity, android.R.layout.simple_spinner_item, languages);
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        }

        @Override
        public View getView(int position, View recycled, ViewGroup parent) {
            return bind((TextView) super.getView(position, recycled, parent), position);
        }

        @Override
        public View getDropDownView(int position, View recycled, ViewGroup parent) {
            return bind((TextView) super.getDropDownView(position, recycled, parent), position);
        }

        private TextView bind(TextView row, int position) {
            VoiceCatalog.Language language = getItem(position);
            VoiceCatalog.Status status = language.status();
            row.setText(language.name);
            row.setMinHeight(ui.dp(48));
            row.setCompoundDrawablePadding(ui.dp(8));
            row.setCompoundDrawablesRelativeWithIntrinsicBounds(status.iconRes, 0, 0, 0);
            row.setContentDescription(activity.getString(R.string.voice_pack_choice_description,
                    language.name, activity.getString(status.labelRes)));
            return row;
        }
    }

    private void confirmDelete(String message, Runnable delete) {
        AlertDialog dialog = new AlertDialog.Builder(activity).setMessage(message)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.delete, (confirmation, which) -> {
                    delete.run();
                    onSaved.run();
                }).show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(0xFF9C392D);
    }

    private void showForm(AlertDialog dialog) {
        dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN
                | WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        dialog.show();
    }

    private LinearLayout form() {
        LinearLayout form = ui.column();
        form.setPadding(ui.dp(24), ui.dp(4), ui.dp(24), ui.dp(12));
        form.setFocusableInTouchMode(true);
        return form;
    }

    private ScrollView scroll(LinearLayout form) {
        ScrollView scroll = new ScrollView(activity);
        scroll.addView(form);
        return scroll;
    }

    private void field(LinearLayout form, String label, View input) {
        input.setId(View.generateViewId());
        TextView title = ui.text(label, 14, FlashcardViews.INK, "sans-serif-medium");
        title.setLabelFor(input.getId());
        form.addView(title, ui.fullWidth(10));
        form.addView(input, ui.fullWidth(4));
    }

    private Spinner spinner(String[] labels) {
        Spinner spinner = new Spinner(activity, Spinner.MODE_DROPDOWN);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(activity,
                android.R.layout.simple_spinner_item, labels);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);
        spinner.setMinimumHeight(ui.dp(48));
        return spinner;
    }

    private static String[] deckNames(List<FlashcardStore.Deck> decks) {
        String[] labels = new String[decks.size()];
        for (int index = 0; index < labels.length; index++) {
            labels[index] = decks.get(index).name;
        }
        return labels;
    }

    private static void requireText(EditText field, String message) {
        field.setError(message);
        field.requestFocus();
    }

    private static String value(EditText input) {
        return input.getText().toString().trim();
    }
}
