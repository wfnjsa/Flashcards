package com.pocketflashcards.app;

import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.speech.tts.TextToSpeech;
import android.speech.tts.Voice;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class VoiceSettingsActivity extends LocalizedActivity {
    private static final String TTS_SETTINGS = "com.android.settings.TTS_SETTINGS";
    private static final long DOWNLOAD_POLL_MS = 2_000;
    private static final long DOWNLOAD_WAIT_MS = 60_000;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable refresh = this::refreshCatalog;
    private final Map<String, Long> requestedDownloads = new HashMap<>();

    private FlashcardViews ui;
    private VoiceRows rows;
    private TextView empty;
    private SpeechConnection connection;
    private TextToSpeech engine;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        ui = new FlashcardViews(this);
        LinearLayout root = ui.activityRoot();

        LinearLayout header = ui.row();
        header.setPadding(ui.dp(8), ui.dp(4), ui.dp(8), ui.dp(4));
        ImageButton back = ui.iconButton(R.drawable.ic_back, getString(R.string.voice_packs_back), false);
        back.setOnClickListener(view -> finish());
        header.addView(back, new LinearLayout.LayoutParams(ui.dp(48), ui.dp(48)));
        TextView title = ui.text(getString(R.string.voice_packs_title), 24,
                FlashcardViews.INK, "sans-serif-medium");
        title.setAccessibilityHeading(true);
        header.addView(title, ui.halfWidth(8));
        root.addView(header, ui.fullWidth(0));

        LinearLayout content = ui.column();
        content.setPadding(ui.dp(20), ui.dp(6), ui.dp(20), ui.dp(8));
        Button system = ui.button(getString(R.string.voice_packs_open_system), false);
        system.setOnClickListener(view -> openSystemVoiceSettings());
        content.addView(system, ui.fullWidth(0));

        FrameLayout listArea = new FrameLayout(this);
        ListView list = new ListView(this);
        list.setDivider(null);
        list.setClipToPadding(false);
        list.setPadding(0, ui.dp(10), 0, 0);
        rows = new VoiceRows();
        list.setAdapter(rows);
        listArea.addView(list);
        empty = ui.text(getString(R.string.voice_packs_loading), 16,
                FlashcardViews.MUTED, "sans-serif");
        empty.setGravity(Gravity.CENTER);
        empty.setPadding(ui.dp(18), ui.dp(18), ui.dp(18), ui.dp(18));
        listArea.addView(empty, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        list.setEmptyView(empty);
        content.addView(listArea, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        root.addView(content, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        setContentView(root);
        root.requestApplyInsets();
    }

    @Override
    protected void onResume() {
        super.onResume();
        empty.setText(R.string.voice_packs_loading);
        connection = new SpeechConnection(this, (session, connectedEngine) -> {
            engine = connectedEngine;
            if (engine == null) {
                session.close();
            }
            refreshCatalog();
        });
    }

    @Override
    protected void onPause() {
        main.removeCallbacks(refresh);
        if (connection != null) {
            connection.close();
            connection = null;
        }
        engine = null;
        super.onPause();
    }

    private void refreshCatalog() {
        main.removeCallbacks(refresh);
        Locale display = getResources().getConfiguration().getLocales().get(0);
        List<VoiceCatalog.Language> languages = VoiceCatalog.languages(
                engine == null ? null : engine.getVoices(), display);
        long now = SystemClock.uptimeMillis();
        requestedDownloads.values().removeIf(deadline -> deadline <= now);
        for (VoiceCatalog.Language language : languages) {
            if (language.status() == VoiceCatalog.Status.DOWNLOADED) {
                requestedDownloads.remove(language.tag);
            }
        }
        rows.update(languages);
        empty.setText(R.string.voice_packs_empty);
        if (engine != null && !requestedDownloads.isEmpty()) {
            main.postDelayed(refresh, DOWNLOAD_POLL_MS);
        }
    }

    private void download(VoiceCatalog.Language language) {
        Voice voice = language.downloadableVoice();
        if (engine != null && voice != null && engine.setVoice(voice) == TextToSpeech.SUCCESS) {
            // The engine completes the download asynchronously. Recheck its reported state.
            requestedDownloads.put(language.tag, SystemClock.uptimeMillis() + DOWNLOAD_WAIT_MS);
            refreshCatalog();
            return;
        }
        if (engine != null) {
            String enginePackage = engine.getDefaultEngine();
            if (enginePackage != null && tryOpen(new Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA)
                    .setPackage(enginePackage))) {
                return;
            }
        }
        openSystemVoiceSettings();
    }

    private void remove(VoiceCatalog.Language language) {
        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.voice_pack_remove_title, language.name))
                .setMessage(getString(R.string.voice_pack_remove_in_settings, language.name))
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.voice_packs_open_system,
                        (dialog, which) -> openSystemVoiceSettings())
                .show();
    }

    private void openSystemVoiceSettings() {
        if (!tryOpen(new Intent(TTS_SETTINGS).setPackage("com.android.settings"))) {
            tryOpen(new Intent(Settings.ACTION_SETTINGS));
            Toast.makeText(this, R.string.voice_packs_settings_unavailable, Toast.LENGTH_LONG).show();
        }
    }

    private boolean tryOpen(Intent intent) {
        try {
            startActivity(intent);
            return true;
        } catch (ActivityNotFoundException | SecurityException unavailable) {
            return false;
        }
    }

    private final class VoiceRows extends BaseAdapter {
        private List<VoiceCatalog.Language> languages = Collections.emptyList();

        void update(List<VoiceCatalog.Language> updated) {
            languages = updated;
            notifyDataSetChanged();
        }

        @Override
        public int getCount() {
            return languages.size();
        }

        @Override
        public VoiceCatalog.Language getItem(int position) {
            return languages.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View recycled, ViewGroup parent) {
            VoiceRow row = recycled instanceof VoiceRow ? (VoiceRow) recycled : new VoiceRow();
            row.bind(getItem(position));
            return row;
        }
    }

    private final class VoiceRow extends LinearLayout {
        private final TextView name;
        private final TextView statusView;
        private final Button action;

        VoiceRow() {
            super(VoiceSettingsActivity.this);
            setGravity(Gravity.CENTER_VERTICAL);
            setMinimumHeight(ui.dp(72));
            setPaddingRelative(ui.dp(12), ui.dp(6), ui.dp(4), ui.dp(6));

            LinearLayout labels = ui.column();
            name = ui.text("", 16, FlashcardViews.INK, "sans-serif-medium");
            labels.addView(name, ui.fullWidth(0));
            statusView = ui.text("", 13, FlashcardViews.MUTED, "sans-serif");
            statusView.setCompoundDrawablePadding(ui.dp(6));
            labels.addView(statusView, ui.fullWidth(2));
            addView(labels, ui.halfWidth(0));
            action = ui.button("", false);
            LinearLayout.LayoutParams actionSize = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            actionSize.setMarginStart(ui.dp(8));
            addView(action, actionSize);
        }

        void bind(VoiceCatalog.Language language) {
            name.setText(language.name);
            VoiceCatalog.Status status = language.status();
            if (status == VoiceCatalog.Status.CAN_DOWNLOAD && requestedDownloads.containsKey(language.tag)) {
                status = VoiceCatalog.Status.REQUESTED;
            }
            statusView.setText(status.labelRes);
            statusView.setCompoundDrawablesRelativeWithIntrinsicBounds(status.iconRes, 0, 0, 0);
            if (status == VoiceCatalog.Status.ONLINE_ONLY) {
                action.setVisibility(View.GONE);
            } else {
                boolean installed = status == VoiceCatalog.Status.DOWNLOADED;
                int label = installed ? R.string.voice_pack_remove : R.string.voice_pack_download;
                action.setVisibility(View.VISIBLE);
                action.setEnabled(status != VoiceCatalog.Status.REQUESTED);
                action.setText(label);
                action.setContentDescription(getString(R.string.voice_pack_choice_description,
                        getString(label), language.name));
                action.setOnClickListener(view -> {
                    if (installed) {
                        remove(language);
                    } else {
                        download(language);
                    }
                });
            }
        }
    }
}
