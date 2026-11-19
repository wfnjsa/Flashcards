package com.pocketflashcards.app;

import android.app.AlertDialog;
import android.content.Context;

import java.util.List;
import java.util.function.LongConsumer;

final class DeckPickerDialog {
    private DeckPickerDialog() {}

    static AlertDialog create(Context context, String title, List<FlashcardStore.Deck> decks,
            long selectedDeckId, LongConsumer onSelected) {
        String[] labels = new String[decks.size() + 1];
        labels[0] = context.getString(R.string.widget_all_decks);
        int checked = 0;
        for (int index = 0; index < decks.size(); index++) {
            FlashcardStore.Deck deck = decks.get(index);
            labels[index + 1] = deck.name;
            if (deck.id == selectedDeckId) {
                checked = index + 1;
            }
        }
        return new AlertDialog.Builder(context)
                .setTitle(title)
                .setSingleChoiceItems(labels, checked, (dialog, index) -> {
                    long deckId = index == 0 ? FlashcardStore.ALL_DECKS : decks.get(index - 1).id;
                    onSelected.accept(deckId);
                    dialog.dismiss();
                })
                .setNegativeButton(R.string.cancel, null)
                .create();
    }
}
