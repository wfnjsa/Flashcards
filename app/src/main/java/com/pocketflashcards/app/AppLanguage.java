package com.pocketflashcards.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;

import java.util.Locale;

enum AppLanguage {
    ENGLISH("en", R.string.language_english),
    TRADITIONAL_CHINESE("zh-Hant-TW", R.string.language_traditional_chinese),
    JAPANESE("ja", R.string.language_japanese),
    FRENCH("fr", R.string.language_french),
    SPANISH("es", R.string.language_spanish),
    PORTUGUESE("pt", R.string.language_portuguese),
    RUSSIAN("ru", R.string.language_russian),
    GERMAN("de", R.string.language_german),
    ARABIC("ar", R.string.language_arabic);

    private static final String KEY = "language";

    final int nameRes;
    private final String tag;

    AppLanguage(String tag, int nameRes) {
        this.tag = tag;
        this.nameRes = nameRes;
    }

    static AppLanguage current(Context context) {
        String saved = preferences(context).getString(KEY, ENGLISH.tag);
        for (AppLanguage language : values()) {
            if (language.tag.equals(saved)) {
                return language;
            }
        }
        return ENGLISH;
    }

    void save(Context context) {
        preferences(context).edit().putString(KEY, tag).apply();
    }

    static Context localizedContext(Context context) {
        Locale locale = Locale.forLanguageTag(current(context).tag);
        Configuration override = new Configuration();
        override.setLocale(locale);
        override.setLayoutDirection(locale);
        return context.createConfigurationContext(override);
    }

    private static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences("app_settings", Context.MODE_PRIVATE);
    }
}
