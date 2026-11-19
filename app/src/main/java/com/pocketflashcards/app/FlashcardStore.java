package com.pocketflashcards.app;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.DatabaseUtils;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.IllformedLocaleException;
import java.util.List;
import java.util.Locale;
import java.util.Set;

final class FlashcardStore extends SQLiteOpenHelper {
    static final long ALL_DECKS = 0;
    static final int DECK_NAME_LIMIT = 60;
    static final int TERM_LIMIT = 200;
    static final int READING_LIMIT = 200;
    static final int DEFINITION_LIMIT = 1200;
    static final int EXAMPLE_LIMIT = 2000;
    static final String DEFAULT_DECK_LANGUAGE = "en-US";

    private static final String CARD_QUERY =
            "SELECT c._id, c.deck_id, c.term, c.definition, c.example, d.name, d.language, c.reading " +
            "FROM cards c JOIN decks d ON c.deck_id = d._id";

    FlashcardStore(Context context) {
        super(context.getApplicationContext(), "flashcards.db", null, 2);
    }

    @Override
    public void onConfigure(SQLiteDatabase db) {
        db.setForeignKeyConstraintsEnabled(true);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE decks (_id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "name TEXT NOT NULL COLLATE NOCASE UNIQUE, language TEXT NOT NULL)");
        db.execSQL("CREATE TABLE cards (_id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "deck_id INTEGER NOT NULL REFERENCES decks(_id) ON DELETE CASCADE, " +
                "term TEXT NOT NULL, definition TEXT NOT NULL, example TEXT NOT NULL, " +
                "reading TEXT NOT NULL DEFAULT '', " +
                "due_at INTEGER NOT NULL DEFAULT 0, streak INTEGER NOT NULL DEFAULT 0)");
        db.execSQL("CREATE INDEX cards_deck ON cards(deck_id, _id)");
        db.execSQL("CREATE INDEX cards_due ON cards(due_at, _id)");

        long deck = db.insertOrThrow("decks", null, deckValues("工程英文", DEFAULT_DECK_LANGUAGE));
        addSample(db, deck, "durability", "耐久性",
                "We tested the durability of the engine.");
        addSample(db, deck, "vibration", "振動",
                "I noticed a slight vibration during the test.");
        addSample(db, deck, "stable", "穩定的",
                "The temperature remained stable at 95 °C.");
        addSample(db, deck, "inspect", "檢查",
                "I'll stop the test and inspect the engine.");
        addSample(db, deck, "crankcase", "曲軸箱",
                "We measured the pressure in the crankcase.");
        addSample(db, deck, "blow-by", "吹漏氣",
                "We measured blow-by during the durability test.");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE cards ADD COLUMN reading TEXT NOT NULL DEFAULT ''");
        }
    }

    private static void addSample(SQLiteDatabase db, long deck, String term,
            String definition, String example) {
        db.insertOrThrow("cards", null, cardValues(deck, term, definition, example));
    }

    List<Deck> decks() {
        List<Deck> decks = new ArrayList<>();
        try (Cursor cursor = getReadableDatabase().query("decks",
                new String[]{"_id", "name", "language"}, null, null, null, null,
                "name COLLATE NOCASE, _id")) {
            while (cursor.moveToNext()) {
                decks.add(new Deck(cursor.getLong(0), cursor.getString(1), cursor.getString(2)));
            }
        }
        return decks;
    }

    Deck deck(long id) {
        try (Cursor cursor = getReadableDatabase().query("decks",
                new String[]{"_id", "name", "language"}, "_id = ?", idArgs(id),
                null, null, null)) {
            if (cursor.moveToFirst()) {
                return new Deck(cursor.getLong(0), cursor.getString(1), cursor.getString(2));
            }
        }
        return null;
    }

    long saveDeck(long id, String name, String language) {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues values = deckValues(name, language);
        if (id == 0) {
            return db.insertOrThrow("decks", null, values);
        }
        db.update("decks", values, "_id = ?", idArgs(id));
        return id;
    }

    long importDeck(String name, String language, List<CardFields> cards) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            String candidate = limitedName(name, DECK_NAME_LIMIT);
            for (int copy = 2; DatabaseUtils.longForQuery(db,
                    "SELECT COUNT(*) FROM decks WHERE name = ?", new String[]{candidate}) != 0;
                    copy++) {
                String suffix = " (" + copy + ")";
                candidate = limitedName(name, DECK_NAME_LIMIT - suffix.length()) + suffix;
            }
            long deckId = saveDeck(0, candidate, language);
            // The card list displays the newest IDs first. Preserve the workbook's row order.
            for (int i = cards.size() - 1; i >= 0; i--) {
                CardFields card = cards.get(i);
                saveCard(0, deckId, card.term, card.definition, card.example, card.reading);
            }
            db.setTransactionSuccessful();
            return deckId;
        } finally {
            db.endTransaction();
        }
    }

    private static String limitedName(String name, int limit) {
        if (name.length() <= limit) {
            return name;
        }
        int end = Character.isHighSurrogate(name.charAt(limit - 1)) ? limit - 1 : limit;
        return name.substring(0, end).trim();
    }

    void deleteDeck(long id) {
        getWritableDatabase().delete("decks", "_id = ?", idArgs(id));
    }

    void deleteDecks(List<Long> ids) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            for (long id : ids) {
                deleteDeck(id);
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    boolean mergeDecks(long targetId, List<Long> ids) {
        if (ids.size() < 2 || !ids.contains(targetId)) {
            return false;
        }
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            if (DatabaseUtils.longForQuery(db, "SELECT COUNT(*) FROM decks WHERE _id = ?",
                    idArgs(targetId)) != 1) {
                return false;
            }
            ContentValues move = new ContentValues();
            move.put("deck_id", targetId);
            for (long id : ids) {
                if (id == targetId) {
                    continue;
                }
                db.update("cards", move, "deck_id = ?", idArgs(id));
                if (db.delete("decks", "_id = ?", idArgs(id)) != 1) {
                    return false;
                }
            }
            db.setTransactionSuccessful();
            return true;
        } finally {
            db.endTransaction();
        }
    }

    List<Card> cards(long deckId) {
        return readCards(CARD_QUERY + deckWhere(deckId) + " ORDER BY c._id DESC",
                deckArgs(deckId));
    }

    Card card(long id) {
        try (Cursor cursor = getReadableDatabase().rawQuery(CARD_QUERY + " WHERE c._id = ?",
                idArgs(id))) {
            return cursor.moveToFirst() ? readCard(cursor) : null;
        }
    }

    long cardCount(long deckId) {
        String filter = deckId == ALL_DECKS ? "" : " WHERE deck_id = ?";
        return DatabaseUtils.longForQuery(getReadableDatabase(),
                "SELECT COUNT(*) FROM cards" + filter, deckArgs(deckId));
    }

    List<Card> widgetCards(long deckId, long offset, int limit) {
        String[] args = deckId == ALL_DECKS
                ? new String[]{Integer.toString(limit), Long.toString(offset)}
                : new String[]{Long.toString(deckId), Integer.toString(limit), Long.toString(offset)};
        return readCards(CARD_QUERY + deckWhere(deckId) + " ORDER BY c._id LIMIT ? OFFSET ?", args);
    }

    long saveCard(long id, long deckId, String term, String definition, String example) {
        // Older callers can edit other fields without clearing an existing reading.
        return saveCard(id, deckId, term, definition, example, null);
    }

    long saveCard(long id, long deckId, String term, String definition, String example,
            String reading) {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues values = cardValues(deckId, term, definition, example);
        if (reading != null) {
            values.put("reading", reading);
        }
        if (id == 0) {
            return db.insertOrThrow("cards", null, values);
        }
        db.update("cards", values, "_id = ?", idArgs(id));
        return id;
    }

    boolean transferCards(long sourceDeckId, long targetDeckId, Set<Long> ids, boolean copy) {
        if (ids.isEmpty() || (!copy && sourceDeckId == targetDeckId)) {
            return false;
        }
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            if (DatabaseUtils.longForQuery(db, "SELECT COUNT(*) FROM decks WHERE _id = ?",
                    idArgs(targetDeckId)) != 1) {
                return false;
            }
            ContentValues destination = new ContentValues();
            destination.put("deck_id", targetDeckId);
            List<Long> orderedIds = new ArrayList<>(ids);
            if (copy) {
                // Allocate new IDs in the same relative order as the original cards.
                orderedIds.sort(Long::compareTo);
            }
            for (long id : orderedIds) {
                if (copy) {
                    Card original = card(id);
                    if (original == null || original.deckId != sourceDeckId) {
                        return false;
                    }
                    saveCard(0, targetDeckId, original.term, original.definition, original.example,
                            original.reading);
                } else if (db.update("cards", destination, "_id = ? AND deck_id = ?",
                        new String[]{Long.toString(id), Long.toString(sourceDeckId)}) != 1) {
                    return false;
                }
            }
            db.setTransactionSuccessful();
            return true;
        } finally {
            db.endTransaction();
        }
    }

    boolean deleteCards(long deckId, Set<Long> ids) {
        if (ids.isEmpty()) {
            return false;
        }
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            for (long id : ids) {
                if (db.delete("cards", "_id = ? AND deck_id = ?",
                        new String[]{Long.toString(id), Long.toString(deckId)}) != 1) {
                    return false;
                }
            }
            db.setTransactionSuccessful();
            return true;
        } finally {
            db.endTransaction();
        }
    }

    List<Long> reviewIds(long deckId, boolean allCards, long now) {
        List<Long> ids = new ArrayList<>();
        StringBuilder query = new StringBuilder("SELECT _id FROM cards WHERE 1 = 1");
        List<String> args = new ArrayList<>();
        if (deckId != ALL_DECKS) {
            query.append(" AND deck_id = ?");
            args.add(Long.toString(deckId));
        }
        if (!allCards) {
            query.append(" AND due_at <= ?");
            args.add(Long.toString(now));
        }
        query.append(" ORDER BY due_at, _id");

        try (Cursor cursor = getReadableDatabase().rawQuery(query.toString(),
                args.toArray(new String[0]))) {
            while (cursor.moveToNext()) {
                ids.add(cursor.getLong(0));
            }
        }
        return ids;
    }

    void review(long cardId, boolean remembered, long now) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try (Cursor cursor = db.query("cards", new String[]{"streak"}, "_id = ?",
                idArgs(cardId), null, null, null)) {
            if (cursor.moveToFirst()) {
                ReviewSchedule next = ReviewSchedule.afterAnswer(cursor.getInt(0), remembered, now);
                ContentValues values = new ContentValues();
                values.put("streak", next.streak);
                values.put("due_at", next.dueAt);
                db.update("cards", values, "_id = ?", idArgs(cardId));
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    private List<Card> readCards(String sql, String[] args) {
        List<Card> cards = new ArrayList<>();
        try (Cursor cursor = getReadableDatabase().rawQuery(sql, args)) {
            while (cursor.moveToNext()) {
                cards.add(readCard(cursor));
            }
        }
        return cards;
    }

    private static Card readCard(Cursor cursor) {
        return new Card(cursor.getLong(0), cursor.getLong(1), cursor.getString(2),
                cursor.getString(3), cursor.getString(4), cursor.getString(5), cursor.getString(6),
                cursor.getString(7));
    }

    private static String deckWhere(long deckId) {
        return deckId == ALL_DECKS ? "" : " WHERE c.deck_id = ?";
    }

    private static String[] deckArgs(long deckId) {
        return deckId == ALL_DECKS ? new String[0] : idArgs(deckId);
    }

    private static String[] idArgs(long id) {
        return new String[]{Long.toString(id)};
    }

    private static ContentValues deckValues(String name, String language) {
        ContentValues values = new ContentValues();
        values.put("name", name);
        values.put("language", normalizedLanguage(language));
        return values;
    }

    private static String normalizedLanguage(String language) {
        if (language != null) {
            try {
                Locale locale = new Locale.Builder().setLanguageTag(language.trim()).build();
                if (!locale.getLanguage().isEmpty()) {
                    return locale.toLanguageTag();
                }
            } catch (IllformedLocaleException invalidTag) {
                // Imported decks without a valid language use the default.
            }
        }
        return DEFAULT_DECK_LANGUAGE;
    }

    private static ContentValues cardValues(long deckId, String term, String definition,
            String example) {
        ContentValues values = new ContentValues();
        values.put("deck_id", deckId);
        values.put("term", term);
        values.put("definition", definition);
        values.put("example", example);
        return values;
    }

    static final class Deck {
        final long id;
        final String name;
        final String language;

        Deck(long id, String name, String language) {
            this.id = id;
            this.name = name;
            this.language = language;
        }
    }

    static final class CardFields {
        final String term;
        final String definition;
        final String example;
        final String reading;

        CardFields(String term, String definition, String example) {
            this(term, definition, example, "");
        }

        CardFields(String term, String definition, String example, String reading) {
            this.term = term;
            this.definition = definition;
            this.example = example;
            this.reading = reading == null ? "" : reading;
        }
    }

    static final class Card {
        final long id;
        final long deckId;
        final String term;
        final String definition;
        final String example;
        final String deckName;
        final String language;
        final String reading;

        Card(long id, long deckId, String term, String definition, String example,
                String deckName, String language) {
            this(id, deckId, term, definition, example, deckName, language, "");
        }

        Card(long id, long deckId, String term, String definition, String example,
                String deckName, String language, String reading) {
            this.reading = reading == null ? "" : reading;
            this.id = id;
            this.deckId = deckId;
            this.term = term;
            this.definition = definition;
            this.example = example;
            this.deckName = deckName;
            this.language = language;
        }
    }
}
