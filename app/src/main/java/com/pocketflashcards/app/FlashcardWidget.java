package com.pocketflashcards.app;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.appwidget.AppWidgetProviderInfo;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.View;
import android.widget.RemoteViews;
import android.graphics.Typeface;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.StyleSpan;

import java.util.List;

public final class FlashcardWidget extends AppWidgetProvider {
    // Keep each collection small enough to be sent to the launcher in a Binder transaction.
    private static final int BATCH_SIZE = 100;
    // Older launcher views can still send this while waiting for the first refresh.
    private static final String LEGACY_FLIP = "com.pocketflashcards.app.action.FLIP";
    private static final String NEXT_BATCH = "com.pocketflashcards.app.action.NEXT_BATCH";
    private static final String DECK = "deck";

    @Override
    public void onUpdate(Context context, AppWidgetManager manager, int[] ids) {
        try (FlashcardStore store = new FlashcardStore(context)) {
            for (int id : ids) {
                update(context, manager, store, id, false, false);
            }
        }
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (!NEXT_BATCH.equals(action) && !LEGACY_FLIP.equals(action)) {
            super.onReceive(context, intent);
            return;
        }

        int id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID,
                AppWidgetManager.INVALID_APPWIDGET_ID);
        AppWidgetManager manager = AppWidgetManager.getInstance(context);
        AppWidgetProviderInfo info = manager.getAppWidgetInfo(id);
        if (info == null || !component(context).equals(info.provider)) {
            return;
        }

        try (FlashcardStore store = new FlashcardStore(context)) {
            update(context, manager, store, id, NEXT_BATCH.equals(action), false);
        }
    }

    @Override
    public void onAppWidgetOptionsChanged(Context context, AppWidgetManager manager, int id,
            Bundle options) {
        onUpdate(context, manager, new int[]{id});
    }

    @Override
    public void onDeleted(Context context, int[] ids) {
        SharedPreferences.Editor editor = preferences(context).edit();
        for (int id : ids) {
            editor.remove(batchKey(id)).remove(revealKey(id));
        }
        editor.apply();
    }

    static ComponentName component(Context context) {
        return new ComponentName(context, FlashcardWidget.class);
    }

    static long selectedDeck(Context context) {
        return preferences(context).getLong(DECK, FlashcardStore.ALL_DECKS);
    }

    static void selectDeck(Context context, long deckId) {
        preferences(context).edit().putLong(DECK, deckId).apply();
        refresh(context, true);
    }

    static void refresh(Context context, boolean resetPosition) {
        AppWidgetManager manager = AppWidgetManager.getInstance(context);
        int[] ids = manager.getAppWidgetIds(component(context));
        if (ids.length == 0) {
            return;
        }
        try (FlashcardStore store = new FlashcardStore(context)) {
            for (int id : ids) {
                update(context, manager, store, id, false, resetPosition);
            }
        }
    }

    private static void update(Context base, AppWidgetManager manager, FlashcardStore store,
            int widgetId, boolean nextBatch, boolean resetPosition) {
        Context context = AppLanguage.localizedContext(base);
        SharedPreferences prefs = preferences(context);
        FlashcardStore.Deck deck = store.deck(selectedDeck(context));
        long deckId = deck == null ? FlashcardStore.ALL_DECKS : deck.id;
        long count = store.cardCount(deckId);
        long batches = Math.max(1, (count + BATCH_SIZE - 1) / BATCH_SIZE);
        long batch = resetPosition ? 0 : Math.min(prefs.getLong(batchKey(widgetId), 0), batches - 1);
        if (nextBatch) {
            batch = (batch + 1) % batches;
        }

        List<FlashcardStore.Card> cards = store.widgetCards(deckId, batch * BATCH_SIZE, BATCH_SIZE);

        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget_flashcards);
        views.setInt(android.R.id.background, "setLayoutDirection",
                context.getResources().getConfiguration().getLayoutDirection());
        String deckLabel = deck == null
                ? context.getString(R.string.widget_all_decks) : preview(deck.name, 60);
        views.setTextViewText(R.id.widget_deck, context.getString(R.string.widget_deck_menu, deckLabel));
        views.setContentDescription(R.id.widget_deck,
                context.getString(R.string.widget_deck_menu_description, deckLabel));
        views.setOnClickPendingIntent(R.id.widget_deck, openDeckPicker(context, widgetId));
        views.setViewVisibility(R.id.widget_empty, cards.isEmpty() ? View.VISIBLE : View.GONE);
        views.setViewVisibility(R.id.widget_stack_host, cards.isEmpty() ? View.GONE : View.VISIBLE);
        views.setViewVisibility(R.id.widget_swipe_hint, cards.size() > 1 ? View.VISIBLE : View.GONE);
        views.setViewVisibility(R.id.widget_batch, batches > 1 ? View.VISIBLE : View.GONE);
        views.setTextViewText(R.id.widget_open_app, context.getString(R.string.widget_open));
        views.setTextViewText(R.id.widget_empty, context.getString(R.string.widget_empty));
        views.setTextViewText(R.id.widget_swipe_hint, context.getString(R.string.widget_swipe));
        views.setOnClickPendingIntent(R.id.widget_open_app, openApp(context, widgetId, false));
        views.setOnClickPendingIntent(R.id.widget_empty, openApp(context, widgetId, true));

        if (batches > 1) {
            views.setTextViewText(R.id.widget_batch,
                    context.getString(R.string.widget_next_batch, batch + 1, batches));
            views.setOnClickPendingIntent(R.id.widget_batch,
                    broadcast(context, widgetId, NEXT_BATCH));
        }

        // Recreate the stack so Android does not empty the wrapped rows during an adapter refresh.
        views.removeAllViews(R.id.widget_stack_host);
        if (!cards.isEmpty()) {
            views.addView(R.id.widget_stack_host,
                    new RemoteViews(context.getPackageName(), R.layout.widget_stack));

            setStackCards(context, views, cards, (card, revealedId) -> {
                RemoteViews item = new RemoteViews(context.getPackageName(), R.layout.widget_card);
                boolean showAnswer = card.id == revealedId;
                boolean showExample = showAnswer && !card.example.isEmpty();

                item.setTextViewText(R.id.widget_term, showAnswer
                        ? preview(card.definition, 130)
                        : preview(card.term, 100));
                item.setViewVisibility(R.id.widget_example,
                        showExample ? View.VISIBLE : View.INVISIBLE);

                if (showExample) {
                    String label = AppLanguage.localizedContext(context).getString(R.string.example_label);
                    SpannableString example = new SpannableString(label + card.example);
                    example.setSpan(new StyleSpan(Typeface.ITALIC), label.length(),
                            example.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    item.setTextViewText(R.id.widget_example, example);
                }
                return item;
            });
        }
        manager.updateAppWidget(widgetId, views);
        prefs.edit().putLong(batchKey(widgetId), batch).remove(revealKey(widgetId)).apply();
    }

    private static void setStackCards(Context context, RemoteViews views,
            List<FlashcardStore.Card> cards, CardRenderer renderer) {
        RemoteViews.RemoteCollectionItems.Builder items =
                new RemoteViews.RemoteCollectionItems.Builder().setHasStableIds(true);
        for (FlashcardStore.Card card : cards) {
            RemoteViews row = new RemoteViews(context.getPackageName(), R.layout.widget_flip_card);
            RemoteViews front = renderer.render(card, 0);
            if (!card.reading.trim().isEmpty()) {
                front.setTextViewText(R.id.widget_term, FlashcardViews.withReading(
                        preview(card.term, 100), card.reading));
                front.setInt(R.id.widget_term, "setMaxLines", 6);
            }
            row.addView(R.id.widget_flip_front, front);
            row.addView(R.id.widget_flip_back, renderer.render(card, card.id));
            // The switch is transparent. These descriptions follow the visible face in TalkBack.
            row.setCharSequence(R.id.widget_flip_control, "setTextOff",
                    FlashcardViews.withReading(preview(card.term, 100), card.reading));
            String backDescription = preview(card.definition, 130);
            if (!card.example.isEmpty()) {
                backDescription = context.getString(R.string.widget_answer_with_example,
                        backDescription, preview(card.example, 130));
            }
            row.setCharSequence(R.id.widget_flip_control, "setTextOn", backDescription);
            items.addItem(card.id, row);
        }
        views.setRemoteAdapter(R.id.widget_stack, items.build());
    }

    private interface CardRenderer {
        RemoteViews render(FlashcardStore.Card card, long revealedId);
    }

    private static String preview(String text, int max) {
        if (text.codePointCount(0, text.length()) <= max) {
            return text;
        }
        return text.substring(0, text.offsetByCodePoints(0, max - 1)) + "…";
    }

    private static PendingIntent broadcast(Context context, int widgetId, String action) {
        Intent intent = new Intent(context, FlashcardWidget.class).setAction(action);
        intent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId);
        return PendingIntent.getBroadcast(context, widgetId, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private static PendingIntent openDeckPicker(Context context, int widgetId) {
        Intent intent = new Intent(context, WidgetDeckPickerActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return PendingIntent.getActivity(context, widgetId, intent, PendingIntent.FLAG_IMMUTABLE);
    }

    private static PendingIntent openApp(Context context, int widgetId, boolean addCard) {
        Intent intent = new Intent(context, MainActivity.class)
                .setAction(addCard ? MainActivity.ACTION_ADD_CARD : Intent.ACTION_MAIN)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP
                        | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return PendingIntent.getActivity(context, widgetId, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences("widget_settings", Context.MODE_PRIVATE);
    }

    private static String batchKey(int id) {
        return "batch_" + id;
    }

    private static String revealKey(int id) {
        return "reveal_" + id;
    }
}
