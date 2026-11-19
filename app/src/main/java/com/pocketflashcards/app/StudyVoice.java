package com.pocketflashcards.app;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.speech.tts.Voice;
import android.widget.Toast;

import java.util.Locale;

final class StudyVoice {
    private final Activity activity;
    private TextToSpeech speaker;
    private FlashcardStore.Card pending;
    private boolean ready;
    private boolean closed;

    StudyVoice(Activity activity) {
        this.activity = activity;
    }

    void speak(FlashcardStore.Card card) {
        if (ready) {
            speakCard(card);
            return;
        }
        pending = card;
        if (speaker == null) {
            Handler main = new Handler(Looper.getMainLooper());
            speaker = new TextToSpeech(activity.getApplicationContext(),
                    status -> main.post(() -> onInitialized(status)));
        }
    }

    void close() {
        closed = true;
        pending = null;
        if (speaker != null) {
            speaker.shutdown();
            speaker = null;
        }
    }

    private void onInitialized(int status) {
        if (closed) {
            return;
        }
        if (status != TextToSpeech.SUCCESS) {
            if (speaker != null) {
                speaker.shutdown();
                speaker = null;
            }
            pending = null;
            unavailable();
            return;
        }
        ready = true;
        if (pending != null) {
            speakCard(pending);
            pending = null;
        }
    }

    private void speakCard(FlashcardStore.Card card) {
        Locale locale = Locale.forLanguageTag(card.language);
        Voice voice = VoiceCatalog.bestInstalledVoice(speaker.getVoices(), locale);
        if (voice == null || speaker.setVoice(voice) != TextToSpeech.SUCCESS
                || speaker.speak(card.term, TextToSpeech.QUEUE_FLUSH, null,
                        Long.toString(card.id)) != TextToSpeech.SUCCESS) {
            unavailable();
        }
    }

    private void unavailable() {
        Toast.makeText(activity, R.string.voice_unavailable,
                Toast.LENGTH_LONG).show();
    }
}
