package com.pocketflashcards.app;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

final class DeckExcelTransfer {
    private static final String TAG = "DeckExcelTransfer";
    private static final Executor WORKER = Executors.newSingleThreadExecutor();

    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private Consumer<Result> listener;
    private Result completed;
    private boolean busy;

    DeckExcelTransfer(Context context) {
        this.context = context.getApplicationContext();
    }

    void attach(Consumer<Result> listener) {
        this.listener = listener;
        deliverResult();
    }

    void detach() {
        listener = null;
    }

    boolean isBusy() {
        return busy;
    }

    void importDeck(Uri uri) {
        run(R.string.excel_import_failed, () -> {
            DeckExcelWorkbook.Contents contents = readDocument(uri);
            String name = contents.name.isEmpty() ? documentName(uri) : contents.name;
            long id;
            try (FlashcardStore store = new FlashcardStore(context)) {
                id = store.importDeck(name, contents.language, contents.cards);
            }
            try {
                FlashcardWidget.refresh(context, false);
            } catch (RuntimeException widgetError) {
                Log.w(TAG, "Could not refresh the widget after import", widgetError);
            }
            return new Result(id, R.string.excel_imported, contents.cards.size());
        });
    }

    void exportDeck(Uri uri, long deckId) {
        run(R.string.excel_export_failed, () -> {
            FlashcardStore.Deck deck;
            List<FlashcardStore.Card> cards;
            try (FlashcardStore store = new FlashcardStore(context)) {
                SQLiteDatabase db = store.getReadableDatabase();
                db.beginTransactionNonExclusive();
                try {
                    deck = store.deck(deckId);
                    if (deck == null) {
                        return new Result(FlashcardStore.ALL_DECKS, R.string.excel_export_deck_missing);
                    }
                    cards = store.cards(deckId);
                    db.setTransactionSuccessful();
                } finally {
                    db.endTransaction();
                }
            }

            try (OutputStream output = context.getContentResolver().openOutputStream(uri, "wt")) {
                if (output == null) {
                    throw new IOException("No output stream");
                }
                DeckExcelWorkbook.write(output, deck, cards);
            }
            return new Result(FlashcardStore.ALL_DECKS, R.string.excel_exported, cards.size());
        });
    }

    private DeckExcelWorkbook.Contents readDocument(Uri uri) throws IOException {
        File file = File.createTempFile("excel-", ".xlsx", context.getCacheDir());
        try {
            try (InputStream input = context.getContentResolver().openInputStream(uri);
                    OutputStream output = new FileOutputStream(file)) {
                if (input == null) {
                    throw new IOException("No input stream");
                }
                byte[] buffer = new byte[8192];
                long total = 0;
                for (int bytes = input.read(buffer); bytes != -1; bytes = input.read(buffer)) {
                    total += bytes;
                    if (total > DeckExcelWorkbook.MAX_FILE_BYTES) {
                        throw DeckExcelWorkbook.fileTooLarge();
                    }
                    output.write(buffer, 0, bytes);
                }
            }
            return DeckExcelWorkbook.read(file);
        } finally {
            if (!file.delete()) {
                Log.w(TAG, "Could not delete the temporary workbook");
            }
        }
    }

    private String documentName(Uri uri) {
        ContentResolver resolver = context.getContentResolver();
        try (Cursor cursor = resolver.query(uri, new String[]{OpenableColumns.DISPLAY_NAME},
                null, null, null)) {
            if (cursor != null && cursor.moveToFirst() && cursor.getColumnCount() > 0) {
                String name = cursor.getString(0);
                if (name != null) {
                    name = name.trim();
                    if (name.toLowerCase(Locale.ROOT).endsWith(".xlsx")) {
                        name = name.substring(0, name.length() - 5).trim();
                    }
                    if (!name.isEmpty()) {
                        return name;
                    }
                }
            }
        } catch (RuntimeException unavailableName) {
            Log.d(TAG, "Document provider did not return a filename");
        }
        return AppLanguage.localizedContext(context).getString(R.string.excel_import_deck_name);
    }

    private void run(int failureRes, Callable<Result> task) {
        if (busy) {
            return;
        }
        busy = true;
        WORKER.execute(() -> {
            Result result;
            try {
                result = task.call();
            } catch (DeckExcelWorkbook.InvalidWorkbook e) {
                result = new Result(FlashcardStore.ALL_DECKS, e.messageRes, e.messageArgs);
            } catch (Exception e) {
                Log.w(TAG, "Excel transfer failed", e);
                result = new Result(FlashcardStore.ALL_DECKS, failureRes);
            }
            Result outcome = result;
            main.post(() -> {
                busy = false;
                completed = outcome;
                deliverResult();
            });
        });
    }

    private void deliverResult() {
        if (listener != null && completed != null) {
            Result result = completed;
            completed = null;
            listener.accept(result);
        }
    }

    static final class Result {
        final long importedDeckId;
        private final int messageRes;
        private final Object[] messageArgs;

        Result(long importedDeckId, int messageRes, Object... messageArgs) {
            this.importedDeckId = importedDeckId;
            this.messageRes = messageRes;
            this.messageArgs = messageArgs;
        }

        String message(Context context) {
            return context.getString(messageRes, messageArgs);
        }
    }
}
