package com.pocketflashcards.app;

import android.app.AlertDialog;
import android.os.Bundle;

public final class WidgetDeckPickerActivity extends LocalizedActivity {
    private AlertDialog picker;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        try (FlashcardStore store = new FlashcardStore(this)) {
            picker = DeckPickerDialog.create(this, getString(R.string.widget_deck_picker_title),
                    store.decks(), FlashcardWidget.selectedDeck(this),
                    deckId -> FlashcardWidget.selectDeck(this, deckId));
        }
        picker.setOnDismissListener(dialog -> finish());
        picker.show();
    }

    @Override
    protected void onDestroy() {
        if (picker != null) {
            picker.setOnDismissListener(null);
            picker.dismiss();
        }
        super.onDestroy();
    }
}
