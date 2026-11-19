package com.pocketflashcards.app;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;

import java.util.function.BiConsumer;

final class SpeechConnection implements AutoCloseable {
    private final TextToSpeech engine;
    private boolean closed;

    SpeechConnection(Context context, BiConsumer<SpeechConnection, TextToSpeech> onReady) {
        Handler main = new Handler(Looper.getMainLooper());
        engine = new TextToSpeech(context.getApplicationContext(),
                status -> main.post(() -> reportReady(status, onReady)));
    }

    private void reportReady(int status, BiConsumer<SpeechConnection, TextToSpeech> onReady) {
        if (!closed) {
            onReady.accept(this, status == TextToSpeech.SUCCESS ? engine : null);
        }
    }

    @Override
    public void close() {
        if (!closed) {
            closed = true;
            engine.shutdown();
        }
    }
}
