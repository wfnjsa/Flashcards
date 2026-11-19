package com.pocketflashcards.app;

import android.app.Activity;
import android.content.Context;

abstract class LocalizedActivity extends Activity {
    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(AppLanguage.localizedContext(base));
    }
}
