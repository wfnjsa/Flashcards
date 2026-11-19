package com.pocketflashcards.app;

import android.speech.tts.TextToSpeech;
import android.speech.tts.Voice;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

final class VoiceCatalog {
    private static final Comparator<Voice> QUALITY = Comparator.comparingInt(Voice::getQuality)
            .thenComparing(Voice::getName);

    enum Status {
        DOWNLOADED(R.string.voice_pack_installed, R.drawable.ic_voice_downloaded),
        CAN_DOWNLOAD(R.string.voice_pack_missing, R.drawable.ic_voice_download),
        ONLINE_ONLY(R.string.voice_pack_online_only, R.drawable.ic_voice_unavailable),
        UNAVAILABLE(R.string.voice_pack_not_available, R.drawable.ic_voice_unavailable),
        CHECKING(R.string.voice_pack_checking, R.drawable.ic_voice_wait),
        REQUESTED(R.string.voice_pack_requested, R.drawable.ic_voice_wait);

        final int labelRes;
        final int iconRes;

        Status(int labelRes, int iconRes) {
            this.labelRes = labelRes;
            this.iconRes = iconRes;
        }
    }

    static final class Language {
        final String tag;
        final String name;
        private final Status emptyStatus;
        private Voice installed;
        private Voice download;

        Language(Locale locale, Locale displayLocale, Status emptyStatus) {
            tag = locale.toLanguageTag();
            name = locale.getDisplayName(displayLocale);
            this.emptyStatus = emptyStatus;
        }

        Status status() {
            if (installed != null) {
                return Status.DOWNLOADED;
            }
            return download == null ? emptyStatus : Status.CAN_DOWNLOAD;
        }

        Voice downloadableVoice() {
            return download;
        }

        private void add(Voice voice) {
            if (voice.isNetworkConnectionRequired()) {
                return;
            }
            if (needsDownload(voice)) {
                if (download == null || QUALITY.compare(voice, download) > 0) {
                    download = voice;
                }
            } else if (installed == null || QUALITY.compare(voice, installed) > 0) {
                installed = voice;
            }
        }
    }

    private VoiceCatalog() {}

    static List<Language> languages(Set<Voice> voices, Locale displayLocale) {
        Map<String, Language> byTag = new HashMap<>();
        if (voices != null) {
            for (Voice voice : voices) {
                Locale locale = voice.getLocale();
                if (locale == null || locale.getLanguage().isEmpty()) {
                    continue;
                }
                Language language = byTag.computeIfAbsent(locale.toLanguageTag(),
                        tag -> new Language(locale, displayLocale, Status.ONLINE_ONLY));
                language.add(voice);
            }
        }
        List<Language> languages = new ArrayList<>(byTag.values());
        Collator collator = Collator.getInstance(displayLocale);
        languages.sort(Comparator.comparing((Language language) -> language.name, collator)
                .thenComparing(language -> language.tag));
        return languages;
    }

    static Voice bestInstalledVoice(Set<Voice> voices, Locale preferred) {
        if (voices == null || preferred.getLanguage().isEmpty()) {
            return null;
        }
        Comparator<Voice> preference = Comparator.comparing((Voice voice) -> preferred.equals(voice.getLocale()))
                .thenComparing(QUALITY);
        return voices.stream()
                .filter(voice -> voice.getLocale() != null
                        && preferred.getLanguage().equals(voice.getLocale().getLanguage()))
                .filter(voice -> !voice.isNetworkConnectionRequired() && !needsDownload(voice))
                .max(preference)
                .orElse(null);
    }

    private static boolean needsDownload(Voice voice) {
        Set<String> features = voice.getFeatures();
        return features != null && features.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED);
    }
}
