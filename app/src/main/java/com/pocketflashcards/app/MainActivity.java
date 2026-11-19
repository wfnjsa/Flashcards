package com.pocketflashcards.app;

import android.app.AlertDialog;
import android.appwidget.AppWidgetManager;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.PopupMenu;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import android.window.OnBackInvokedCallback;
import android.window.OnBackInvokedDispatcher;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.LongConsumer;

public final class MainActivity extends LocalizedActivity {
    static final String ACTION_ADD_CARD = "com.pocketflashcards.app.action.ADD_CARD";

    private static final int IMPORT_EXCEL = 31;
    private static final int EXPORT_EXCEL = 32;

    private enum Page { DECKS, CARDS, WIDGET, SETTINGS }

    private FlashcardViews ui;
    private FlashcardStore store;
    private VocabularyEditor editor;
    private DeckExcelTransfer excelTransfer;
    private long pendingExcelDeck = FlashcardStore.ALL_DECKS;
    private LinearLayout root;
    private SlidingMenuLayout mainMenu;
    private Button importExcelItem;
    private FrameLayout body;
    private ImageButton navigation;
    private ImageButton selectionActions;
    private TextView headerTitle;
    private View addDeckButton;
    private DeckAdapter deckAdapter;
    private View addCardButton;
    private CardsAdapter cardsAdapter;
    private Page page = Page.DECKS;
    private long cardsDeckId = FlashcardStore.ALL_DECKS;
    private long widgetPreviewDeckId = FlashcardStore.ALL_DECKS;
    private String searchQuery = "";
    private boolean editingDecks;
    private final Set<Long> selectedDeckIds = new LinkedHashSet<>();
    private final Set<Long> selectedCardIds = new LinkedHashSet<>();
    private OnBackInvokedCallback backCallback;

    protected void onCreate(Bundle savedState) {
        super.onCreate(savedState);
        ui = new FlashcardViews(this);
        store = new FlashcardStore(this);
        editor = new VocabularyEditor(this, ui, store, this::onVocabularyChanged);
        excelTransfer = (DeckExcelTransfer) getLastNonConfigurationInstance();
        if (excelTransfer == null) {
            excelTransfer = new DeckExcelTransfer(this);
        }
        if (savedState != null) {
            pendingExcelDeck = savedState.getLong("pendingExcelDeck", FlashcardStore.ALL_DECKS);
            cardsDeckId = savedState.getLong("cardsDeck", FlashcardStore.ALL_DECKS);
            Page initialPage = cardsDeckId == FlashcardStore.ALL_DECKS ? Page.DECKS : Page.CARDS;
            page = Page.valueOf(savedState.getString("page", initialPage.name()));
            searchQuery = savedState.getString("search", "");
            editingDecks = savedState.getBoolean("editingDecks");
            long[] selection = savedState.getLongArray("selectedDeckIds");
            if (selection != null) {
                for (long id : selection) {
                    selectedDeckIds.add(id);
                }
            }
            long[] cardSelection = savedState.getLongArray("selectedCardIds");
            if (page == Page.CARDS && cardSelection != null) {
                for (long id : cardSelection) {
                    selectedCardIds.add(id);
                }
            }
        }

        createLayout();
        render();
        if (savedState != null && savedState.getBoolean("mainMenuOpen")
                && page == Page.DECKS && !editingDecks) {
            mainMenu.setOpen(true, false);
        }
        excelTransfer.attach(this::onExcelTransferComplete);
        if (savedState == null) {
            handleShortcut(getIntent());
        }
    }

    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleShortcut(intent);
    }

    protected void onResume() {
        super.onResume();
        if (page == Page.WIDGET && widgetPreviewDeckId != FlashcardWidget.selectedDeck(this)) {
            renderWidget();
        }
    }

    protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        state.putString("page", page.name());
        state.putBoolean("mainMenuOpen", mainMenu.isOpen());
        state.putLong("cardsDeck", cardsDeckId);
        state.putString("search", searchQuery);
        state.putBoolean("editingDecks", editingDecks);
        long[] selection = new long[selectedDeckIds.size()];
        int index = 0;
        for (long id : selectedDeckIds) {
            selection[index++] = id;
        }
        state.putLongArray("selectedDeckIds", selection);
        state.putLongArray("selectedCardIds", selectedCardIds.stream().mapToLong(Long::longValue).toArray());
        state.putLong("pendingExcelDeck", pendingExcelDeck);
    }

    public Object onRetainNonConfigurationInstance() {
        return excelTransfer;
    }

    protected void onDestroy() {
        excelTransfer.detach();
        setBackEnabled(false);
        store.close();
        super.onDestroy();
    }

    private void handleShortcut(Intent intent) {
        if (ACTION_ADD_CARD.equals(intent.getAction())) {
            openCardsAt(FlashcardWidget.selectedDeck(this));
            editor.editCard(null, cardsDeckId);
        }
    }

    private void openCardsAt(long deckId) {
        cardsDeckId = deckId;
        searchQuery = "";
        navigate(deckId == FlashcardStore.ALL_DECKS ? Page.DECKS : Page.CARDS);
    }

    private void navigate(Page next) {
        mainMenu.setOpen(false, true);
        InputMethodManager keyboard = getSystemService(InputMethodManager.class);
        keyboard.hideSoftInputFromWindow(root.getWindowToken(), 0);
        editingDecks = false;
        selectedDeckIds.clear();
        selectedCardIds.clear();
        page = next;
        render();
    }

    private boolean handlesBack() {
        return mainMenu.isOpen() || page != Page.DECKS || editingDecks;
    }

    private void navigateBack() {
        if (mainMenu.isOpen()) {
            mainMenu.setOpen(false, true);
        } else if (editingDecks) {
            editingDecks = false;
            selectedDeckIds.clear();
            updateDeckSelection();
        } else if (page == Page.CARDS && !selectedCardIds.isEmpty()) {
            selectedCardIds.clear();
            updateCardSelection();
        } else {
            openCardsAt(FlashcardStore.ALL_DECKS);
        }
    }

    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        if (handlesBack()) {
            navigateBack();
        } else {
            super.onBackPressed();
        }
    }

    private void setBackEnabled(boolean enabled) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return;
        }
        if (enabled && backCallback == null) {
            backCallback = this::navigateBack;
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    OnBackInvokedDispatcher.PRIORITY_DEFAULT, backCallback);
        } else if (!enabled && backCallback != null) {
            getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(backCallback);
            backCallback = null;
        }
    }

    private void createLayout() {
        root = ui.activityRoot();
        LinearLayout header = ui.row();
        header.setPadding(ui.dp(8), ui.dp(4), ui.dp(8), ui.dp(4));
        navigation = ui.iconButton(R.drawable.ic_menu, getString(R.string.menu_main), false);
        header.addView(navigation, new LinearLayout.LayoutParams(ui.dp(48), ui.dp(48)));

        headerTitle = ui.text("", 24, FlashcardViews.INK, "sans-serif-medium");
        headerTitle.setAccessibilityHeading(true);
        headerTitle.setMaxLines(1);
        headerTitle.setEllipsize(TextUtils.TruncateAt.END);
        header.addView(headerTitle, ui.halfWidth(8));

        selectionActions = ui.iconButton(R.drawable.ic_overflow, "", false);
        header.addView(selectionActions, new LinearLayout.LayoutParams(ui.dp(48), ui.dp(48)));
        root.addView(header, ui.fullWidth(0));

        body = new FrameLayout(this);
        root.addView(body, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        mainMenu = new SlidingMenuLayout(this, root, createMainMenu(), () -> {
            updateImportMenuItem();
            setBackEnabled(handlesBack());
        });
        setContentView(mainMenu);
        mainMenu.requestApplyInsets();
    }

    private void renderHeader() {
        boolean selectingDecks = page == Page.DECKS && editingDecks;
        boolean selectingCards = page == Page.CARDS && !selectedCardIds.isEmpty();
        mainMenu.setSwipeEnabled(page == Page.DECKS && !editingDecks);
        selectionActions.setVisibility(selectingDecks || selectingCards ? View.VISIBLE : View.GONE);
        if (selectingDecks || selectingCards) {
            int count = selectingCards ? selectedCardIds.size() : selectedDeckIds.size();
            int title = selectingCards ? R.plurals.cards_selected : R.plurals.decks_selected;
            headerTitle.setText(getResources().getQuantityString(title, count, count));
            navigation.setImageResource(R.drawable.ic_close);
            navigation.setContentDescription(getString(selectingCards
                    ? R.string.card_selection_done : R.string.deck_selection_done));
            navigation.setOnClickListener(view -> navigateBack());
            selectionActions.setContentDescription(getString(selectingCards
                    ? R.string.card_actions : R.string.deck_actions));
            selectionActions.setOnClickListener(selectingCards ? this::showCardActions : this::showDeckActions);
        } else if (page == Page.DECKS) {
            headerTitle.setText(R.string.app_name);
            navigation.setImageResource(R.drawable.ic_menu);
            navigation.setContentDescription(getString(R.string.menu_main));
            navigation.setOnClickListener(view -> mainMenu.setOpen(!mainMenu.isOpen(), true));
        } else {
            headerTitle.setText(page == Page.WIDGET ? R.string.widget_title
                    : page == Page.SETTINGS ? R.string.settings_title : R.string.app_name);
            navigation.setImageResource(R.drawable.ic_back);
            navigation.setContentDescription(getString(R.string.back_to_decks));
            navigation.setOnClickListener(view -> navigateBack());
        }
    }

    private LinearLayout createMainMenu() {
        LinearLayout menu = ui.column();
        menu.setBackgroundColor(FlashcardViews.INK);
        menu.setPadding(0, ui.dp(16), 0, 0);
        addMainMenuItem(menu, getString(R.string.widget_title), () -> navigate(Page.WIDGET));
        importExcelItem = addMainMenuItem(menu, getString(R.string.excel_import), () -> {
            mainMenu.setOpen(false, true);
            pickExcelDocument(null);
        });
        addMainMenuItem(menu, getString(R.string.settings_title), () -> navigate(Page.SETTINGS));
        updateImportMenuItem();
        return menu;
    }

    private Button addMainMenuItem(LinearLayout menu, String label, Runnable action) {
        Button item = ui.button(label, true);
        item.setTextSize(17);
        item.setMinHeight(ui.dp(56));
        item.setPadding(ui.dp(20), ui.dp(12), ui.dp(20), ui.dp(12));
        item.setBackground(ui.ripple(FlashcardViews.INK, 0));
        item.setOnClickListener(view -> action.run());
        menu.addView(item, ui.fullWidth(0));
        View divider = new View(this);
        divider.setBackgroundColor(0x33FFFFFF);
        menu.addView(divider, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ui.dp(1)));
        return item;
    }

    private void updateImportMenuItem() {
        boolean available = !excelTransfer.isBusy();
        importExcelItem.setEnabled(available);
        importExcelItem.setAlpha(available ? 1f : 0.45f);
    }

    private void render() {
        FlashcardStore.Deck currentDeck = page == Page.CARDS ? store.deck(cardsDeckId) : null;
        if (page == Page.CARDS && currentDeck == null) {
            cardsDeckId = FlashcardStore.ALL_DECKS;
            searchQuery = "";
            selectedCardIds.clear();
            page = Page.DECKS;
        }

        deckAdapter = null;
        addDeckButton = null;
        cardsAdapter = null;
        addCardButton = null;
        switch (page) {
            case DECKS:
                renderDeckList();
                break;
            case CARDS:
                renderDeckCards(currentDeck);
                break;
            case WIDGET:
                renderWidget();
                break;
            case SETTINGS:
                renderSettings();
                break;
        }
        renderHeader();
        setBackEnabled(handlesBack());
    }

    private void renderDeckList() {
        LinearLayout content = listPage();
        TextView title = ui.text(getString(R.string.widget_all_decks), 24,
                FlashcardViews.INK, "sans-serif-medium");
        title.setAccessibilityHeading(true);
        content.addView(title, ui.fullWidth(2));
        Button add = ui.button(getString(R.string.deck_add_button), true);
        add.setOnClickListener(view -> editor.editDeck(null));
        addDeckButton = add;
        add.setVisibility(editingDecks ? View.GONE : View.VISIBLE);
        content.addView(add, ui.fullWidth(12));

        List<FlashcardStore.Deck> decks = store.decks();
        Set<Long> available = new HashSet<>();
        for (FlashcardStore.Deck deck : decks) {
            available.add(deck.id);
        }
        selectedDeckIds.retainAll(available);
        if (decks.isEmpty()) {
            addEmptyState(content, getString(R.string.decks_empty_title),
                    getString(R.string.decks_empty_description));
            return;
        }
        ListView list = newListView();
        deckAdapter = new DeckAdapter(decks);
        list.setAdapter(deckAdapter);
        LinearLayout.LayoutParams listParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1);
        listParams.topMargin = ui.dp(10);
        content.addView(list, listParams);
    }

    private void updateDeckSelection() {
        renderHeader();
        addDeckButton.setVisibility(editingDecks ? View.GONE : View.VISIBLE);
        if (deckAdapter != null) {
            deckAdapter.notifyDataSetChanged();
        }
        setBackEnabled(handlesBack());
    }

    private void selectDeck(FlashcardStore.Deck deck) {
        if (!editingDecks) {
            openCardsAt(deck.id);
            return;
        }
        if (!selectedDeckIds.remove(deck.id)) {
            selectedDeckIds.add(deck.id);
        }
        updateDeckSelection();
    }

    private void showDeckActions(View anchor) {
        List<FlashcardStore.Deck> selection = new ArrayList<>();
        for (FlashcardStore.Deck deck : store.decks()) {
            if (selectedDeckIds.contains(deck.id)) {
                selection.add(deck);
            }
        }
        PopupMenu menu = new PopupMenu(this, anchor);
        menu.getMenu().add(R.string.select_all).setOnMenuItemClickListener(item -> {
            for (int index = 0; index < deckAdapter.getCount(); index++) {
                selectedDeckIds.add(deckAdapter.getItemId(index));
            }
            updateDeckSelection();
            return true;
        });
        menu.getMenu().add(R.string.edit).setEnabled(selection.size() == 1).setOnMenuItemClickListener(item -> {
            editor.editDeck(selection.get(0));
            return true;
        });
        menu.getMenu().add(R.string.excel_export)
                .setEnabled(selection.size() == 1 && !excelTransfer.isBusy())
                .setOnMenuItemClickListener(item -> {
                    pickExcelDocument(selection.get(0));
                    return true;
                });
        menu.getMenu().add(R.string.merge).setEnabled(selection.size() > 1).setOnMenuItemClickListener(item -> {
            editor.mergeDecks(selection, this::onVocabularyChanged);
            return true;
        });
        menu.getMenu().add(R.string.delete).setEnabled(!selection.isEmpty()).setOnMenuItemClickListener(item -> {
            editor.deleteDecks(selection);
            return true;
        });
        menu.show();
    }

    @SuppressWarnings("deprecation")
    private void pickExcelDocument(FlashcardStore.Deck deck) {
        boolean exporting = deck != null;
        Intent intent = new Intent(exporting ? Intent.ACTION_CREATE_DOCUMENT : Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType(DeckExcelWorkbook.MIME_TYPE);
        if (exporting) {
            pendingExcelDeck = deck.id;
            String filename = deck.name.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_");
            intent.putExtra(Intent.EXTRA_TITLE, filename + ".xlsx");
        }
        try {
            startActivityForResult(intent, exporting ? EXPORT_EXCEL : IMPORT_EXCEL);
        } catch (ActivityNotFoundException unavailablePicker) {
            pendingExcelDeck = FlashcardStore.ALL_DECKS;
            Toast.makeText(this, R.string.excel_picker_unavailable, Toast.LENGTH_LONG).show();
        }
    }

    @SuppressWarnings("deprecation")
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != IMPORT_EXCEL && requestCode != EXPORT_EXCEL) {
            return;
        }
        long exportDeck = pendingExcelDeck;
        pendingExcelDeck = FlashcardStore.ALL_DECKS;
        Uri file = data == null ? null : data.getData();
        if (resultCode != RESULT_OK || file == null) {
            return;
        }
        if (requestCode == IMPORT_EXCEL) {
            Toast.makeText(this, R.string.excel_importing, Toast.LENGTH_SHORT).show();
            excelTransfer.importDeck(file);
            updateImportMenuItem();
        } else if (exportDeck != FlashcardStore.ALL_DECKS) {
            Toast.makeText(this, R.string.excel_exporting, Toast.LENGTH_SHORT).show();
            excelTransfer.exportDeck(file, exportDeck);
            updateImportMenuItem();
        }
    }

    private void onExcelTransferComplete(DeckExcelTransfer.Result result) {
        updateImportMenuItem();
        if (result.importedDeckId != FlashcardStore.ALL_DECKS) {
            openCardsAt(result.importedDeckId);
        }
        Toast.makeText(this, result.message(this), Toast.LENGTH_LONG).show();
    }

    private void showReviewModes(FlashcardStore.Deck deck) {
        String[] choices = {
                getString(R.string.review_flashcards),
                getString(R.string.review_paired),
                getString(R.string.review_quiz)
        };
        Intent[] targets = {
                DeckReviewActivity.intent(this, deck.id, DeckReviewActivity.Mode.FLASHCARDS),
                DeckReviewActivity.intent(this, deck.id, DeckReviewActivity.Mode.PAIRED),
                DeckQuizActivity.intent(this, deck.id)
        };
        AlertDialog menu = new AlertDialog.Builder(this)
                .setTitle(R.string.review_mode_title)
                .setItems(choices, (dialog, which) -> startActivity(targets[which]))
                .create();
        menu.getWindow().setGravity(Gravity.CENTER);
        menu.show();
    }

    private LinearLayout listPage() {
        body.removeAllViews();
        LinearLayout content = ui.column();
        content.setPadding(ui.dp(20), ui.dp(5), ui.dp(20), ui.dp(4));
        content.setFocusableInTouchMode(true);
        body.addView(content, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        return content;
    }

    private ListView newListView() {
        ListView list = new ListView(this);
        list.setDivider(new ColorDrawable(FlashcardViews.PAPER));
        list.setDividerHeight(ui.dp(8));
        list.setPadding(0, 0, 0, ui.dp(10));
        list.setClipToPadding(false);
        return list;
    }

    private void renderDeckCards(FlashcardStore.Deck deck) {
        LinearLayout page = listPage();
        TextView title = ui.text(deck.name, 24, FlashcardViews.INK, "sans-serif-medium");
        title.setAccessibilityHeading(true);
        title.setMaxLines(2);
        title.setEllipsize(TextUtils.TruncateAt.END);
        page.addView(title, ui.fullWidth(10));

        List<FlashcardStore.Card> cards = store.cards(deck.id);
        int count = cards.size();
        TextView cardCount = ui.text(getResources().getQuantityString(R.plurals.deck_card_count, count, count),
                14, FlashcardViews.MUTED, "sans-serif");
        page.addView(cardCount, ui.fullWidth(4));

        Button add = ui.button(getString(R.string.card_add_button), true);
        add.setOnClickListener(view -> editor.editCard(null, deck.id));
        addCardButton = add;
        page.addView(add, ui.fullWidth(10));

        EditText search = ui.input(getString(R.string.card_search), 1, 100);
        search.setText(searchQuery);
        search.setContentDescription(getString(R.string.card_search));
        page.addView(search, ui.fullWidth(12));

        FrameLayout results = new FrameLayout(this);
        LinearLayout.LayoutParams resultParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1);
        resultParams.topMargin = ui.dp(12);
        page.addView(results, resultParams);
        ListView list = newListView();
        results.addView(list);
        if (!selectedCardIds.isEmpty()) {
            Set<Long> available = new HashSet<>();
            for (FlashcardStore.Card card : cards) {
                available.add(card.id);
            }
            selectedCardIds.retainAll(available);
        }
        add.setVisibility(selectedCardIds.isEmpty() ? View.VISIBLE : View.GONE);
        String emptyText = getString(cards.isEmpty() ? R.string.cards_empty : R.string.cards_no_matches);
        TextView empty = ui.text(emptyText, 16, FlashcardViews.MUTED, "sans-serif");
        empty.setGravity(Gravity.CENTER);
        results.addView(empty);
        list.setEmptyView(empty);

        CardsAdapter adapter = new CardsAdapter(cards);
        cardsAdapter = adapter;
        list.setAdapter(adapter);
        list.setOnItemClickListener((parent, item, position, id) -> {
            if (selectedCardIds.isEmpty()) {
                editor.editCard(adapter.getItem(position), deck.id);
            } else {
                toggleCardSelection(id);
            }
        });
        list.setOnItemLongClickListener((parent, item, position, id) -> {
            toggleCardSelection(id);
            return true;
        });

        search.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence text, int start, int count, int after) {}
            public void onTextChanged(CharSequence text, int start, int before, int count) {
                searchQuery = text.toString();
                adapter.filter(searchQuery);
            }
            public void afterTextChanged(Editable text) {}
        });
        page.requestFocus();
    }

    private void toggleCardSelection(long id) {
        if (!selectedCardIds.remove(id)) {
            selectedCardIds.add(id);
        }
        updateCardSelection();
    }

    private void updateCardSelection() {
        renderHeader();
        addCardButton.setVisibility(selectedCardIds.isEmpty() ? View.VISIBLE : View.GONE);
        cardsAdapter.notifyDataSetChanged();
    }

    private void showCardActions(View anchor) {
        PopupMenu menu = new PopupMenu(this, anchor);
        menu.getMenu().add(R.string.select_all).setOnMenuItemClickListener(item -> {
            for (int index = 0; index < cardsAdapter.getCount(); index++) {
                selectedCardIds.add(cardsAdapter.getItemId(index));
            }
            updateCardSelection();
            return true;
        });
        menu.getMenu().add(R.string.card_move).setOnMenuItemClickListener(item -> {
            chooseCardDestination(false);
            return true;
        });
        menu.getMenu().add(R.string.card_copy).setOnMenuItemClickListener(item -> {
            chooseCardDestination(true);
            return true;
        });
        menu.getMenu().add(R.string.delete).setOnMenuItemClickListener(item -> {
            editor.deleteCards(cardsDeckId, new LinkedHashSet<>(selectedCardIds));
            return true;
        });
        menu.show();
    }

    private void chooseCardDestination(boolean copy) {
        long source = cardsDeckId;
        Set<Long> selection = new LinkedHashSet<>(selectedCardIds);
        List<FlashcardStore.Deck> destinations = store.decks();
        if (!copy) {
            destinations.removeIf(deck -> deck.id == source);
        }
        if (destinations.isEmpty()) {
            Toast.makeText(this, R.string.card_move_no_destination, Toast.LENGTH_SHORT).show();
            return;
        }
        String[] labels = destinations.stream().map(deck -> deck.name).toArray(String[]::new);
        AlertDialog picker = new AlertDialog.Builder(this)
                .setTitle(copy ? R.string.card_copy_destination : R.string.card_move_destination)
                .setItems(labels, (dialog, which) ->
                        transferSelectedCards(source, destinations.get(which), selection, copy))
                .setNegativeButton(R.string.cancel, null)
                .create();
        picker.getWindow().setGravity(Gravity.CENTER);
        picker.show();
    }

    private void transferSelectedCards(long source, FlashcardStore.Deck destination,
            Set<Long> selection, boolean copy) {
        if (!store.transferCards(source, destination.id, selection, copy)) {
            Toast.makeText(this, R.string.card_selection_changed, Toast.LENGTH_SHORT).show();
            render();
            return;
        }
        int count = selection.size();
        int notice = copy ? R.plurals.cards_copied : R.plurals.cards_moved;
        Toast.makeText(this, getResources().getQuantityString(notice, count, count, destination.name),
                Toast.LENGTH_SHORT).show();
        onVocabularyChanged();
    }

    private void renderSettings() {
        LinearLayout page = scrollPage();
        AppLanguage language = AppLanguage.current(this);
        Button choose = ui.button(getString(R.string.settings_language_option,
                getString(language.nameRes)), false);
        choose.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        choose.setOnClickListener(view -> showLanguageChoices(language));
        page.addView(choose, ui.fullWidth(12));

        Button voices = ui.button(getString(R.string.voice_packs_title), false);
        voices.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        voices.setOnClickListener(view -> startActivity(new Intent(this, VoiceSettingsActivity.class)));
        page.addView(voices, ui.fullWidth(8));
    }

    private void showLanguageChoices(AppLanguage current) {
        AppLanguage[] languages = AppLanguage.values();
        String[] labels = new String[languages.length];
        for (int index = 0; index < languages.length; index++) {
            labels[index] = getString(languages[index].nameRes);
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.settings_language)
                .setSingleChoiceItems(labels, current.ordinal(), (dialog, index) -> {
                    dialog.dismiss();
                    AppLanguage chosen = languages[index];
                    if (chosen != current) {
                        chosen.save(this);
                        recreate();
                        try {
                            FlashcardWidget.refresh(this, false);
                        } catch (RuntimeException error) {
                            Log.w("MainActivity", "Could not refresh widgets after changing language", error);
                        }
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void renderWidget() {
        LinearLayout page = scrollPage();
        TextView description = ui.text(getString(R.string.widget_description),
                16, FlashcardViews.MUTED, "sans-serif");
        page.addView(description, ui.fullWidth(6));

        long deckId = FlashcardWidget.selectedDeck(this);
        widgetPreviewDeckId = deckId;
        String label = deckLabel(deckId);
        List<FlashcardStore.Card> preview = store.widgetCards(deckId, 0, 1);
        LinearLayout widget = ui.column();
        widget.setBackground(ui.shape(FlashcardViews.DARK_GREEN, 22, 0));
        widget.setPadding(ui.dp(14), ui.dp(10), ui.dp(14), ui.dp(10));
        TextView widgetDeck = ui.text(label, 13, 0xFFDAE7DD, "sans-serif-medium");
        widgetDeck.setMaxLines(1);
        widgetDeck.setEllipsize(TextUtils.TruncateAt.END);
        widget.addView(widgetDeck, ui.fullWidth(0));
        LinearLayout widgetCard = ui.column();
        widgetCard.setGravity(Gravity.CENTER);
        widgetCard.setMinimumHeight(ui.dp(103));
        widgetCard.setPadding(ui.dp(12), ui.dp(10), ui.dp(12), ui.dp(10));
        widgetCard.setBackground(ui.shape(FlashcardViews.CARD, 14, 0));
        TextView widgetWord = ui.text(preview.isEmpty() ? getString(R.string.widget_preview_empty)
                : FlashcardViews.withReading(preview.get(0).term, preview.get(0).reading),
                29, FlashcardViews.INK, "serif");
        widgetWord.setGravity(Gravity.CENTER);
        widgetWord.setMaxLines(6);
        widgetWord.setEllipsize(TextUtils.TruncateAt.END);
        widgetCard.addView(widgetWord, ui.fullWidth(0));
        widget.addView(widgetCard, ui.fullWidth(7));
        page.addView(widget, ui.fullWidth(18));

        TextView sourceLabel = ui.text(getString(R.string.widget_source), 15, FlashcardViews.INK, "sans-serif-medium");
        page.addView(sourceLabel, ui.fullWidth(20));
        Button source = ui.button(getString(R.string.widget_deck_menu, label), false);
        source.setOnClickListener(view -> chooseDeck(deckId, getString(R.string.widget_deck_picker_title), selected -> {
            FlashcardWidget.selectDeck(this, selected);
            renderWidget();
        }));
        page.addView(source, ui.fullWidth(5));

        Button pin = ui.button(getString(R.string.widget_pin), true);
        pin.setOnClickListener(view -> {
            AppWidgetManager manager = AppWidgetManager.getInstance(this);
            if (!manager.isRequestPinAppWidgetSupported()
                    || !manager.requestPinAppWidget(FlashcardWidget.component(this), null, null)) {
                Toast.makeText(this, R.string.widget_pin_unavailable, Toast.LENGTH_LONG).show();
            }
        });
        page.addView(pin, ui.fullWidth(18));
        TextView manual = ui.text(getString(R.string.widget_add_manually),
                14, FlashcardViews.MUTED, "sans-serif");
        page.addView(manual, ui.fullWidth(10));
    }

    private void chooseDeck(long selected, String title, LongConsumer onSelected) {
        DeckPickerDialog.create(this, title, store.decks(), selected, onSelected).show();
    }

    private String deckLabel(long deckId) {
        FlashcardStore.Deck deck = store.deck(deckId);
        return deck == null ? getString(R.string.widget_all_decks) : deck.name;
    }

    private void onVocabularyChanged() {
        onVocabularyChanged(FlashcardStore.ALL_DECKS);
    }

    private void onVocabularyChanged(long widgetFallbackDeck) {
        long widgetDeck = FlashcardWidget.selectedDeck(this);
        if (widgetDeck != FlashcardStore.ALL_DECKS && store.deck(widgetDeck) == null) {
            FlashcardWidget.selectDeck(this, widgetFallbackDeck);
        } else {
            FlashcardWidget.refresh(this, false);
        }
        editingDecks = false;
        selectedDeckIds.clear();
        selectedCardIds.clear();
        render();
    }

    private LinearLayout scrollPage() {
        body.removeAllViews();
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        LinearLayout page = ui.column();
        page.setPadding(ui.dp(20), ui.dp(5), ui.dp(20), ui.dp(20));
        scroll.addView(page);
        body.addView(scroll);
        return page;
    }

    private void addEmptyState(LinearLayout page, String title, String description) {
        LinearLayout card = ui.column();
        card.setPadding(ui.dp(22), ui.dp(23), ui.dp(22), ui.dp(23));
        card.setBackground(ui.shape(FlashcardViews.CARD, 18, FlashcardViews.OUTLINE));
        card.addView(ui.text(title, 21, FlashcardViews.INK, "sans-serif-medium"), ui.fullWidth(0));
        card.addView(ui.text(description, 15, FlashcardViews.MUTED, "sans-serif"), ui.fullWidth(6));
        page.addView(card, ui.fullWidth(20));
    }

    private final class DeckAdapter extends BaseAdapter {
        private final List<FlashcardStore.Deck> decks;

        DeckAdapter(List<FlashcardStore.Deck> decks) {
            this.decks = decks;
        }

        public int getCount() { return decks.size(); }
        public FlashcardStore.Deck getItem(int position) { return decks.get(position); }
        public long getItemId(int position) { return getItem(position).id; }
        public boolean hasStableIds() { return true; }

        public View getView(int position, View recycled, ViewGroup parent) {
            DeckRow row = recycled instanceof DeckRow ? (DeckRow) recycled : new DeckRow();
            row.bind(getItem(position));
            return row;
        }
    }

    private final class DeckRow extends LinearLayout {
        private final TextView name;
        private final CheckBox check;
        private final ImageButton play;

        DeckRow() {
            super(MainActivity.this);
            setGravity(Gravity.CENTER_VERTICAL);
            setMinimumHeight(ui.dp(64));
            setPaddingRelative(ui.dp(14), ui.dp(6), ui.dp(8), ui.dp(6));
            setFocusable(true);
            name = ui.text("", 18, FlashcardViews.INK, "sans-serif-medium");
            name.setMaxLines(2);
            name.setEllipsize(TextUtils.TruncateAt.END);
            addView(name, ui.halfWidth(0));

            check = new CheckBox(MainActivity.this);
            check.setClickable(false);
            check.setFocusable(false);
            check.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            addView(check, new LinearLayout.LayoutParams(ui.dp(48), ui.dp(48)));

            play = ui.iconButton(R.drawable.ic_play, "", true);
            addView(play, new LinearLayout.LayoutParams(ui.dp(48), ui.dp(48)));
        }

        void bind(FlashcardStore.Deck deck) {
            boolean selected = selectedDeckIds.contains(deck.id);
            setBackground(ui.ripple(selected ? FlashcardViews.LIGHT_GREEN : FlashcardViews.CARD, 12));
            setActivated(selected);
            name.setText(deck.name);
            check.setChecked(selected);
            check.setVisibility(editingDecks ? View.VISIBLE : View.GONE);
            play.setVisibility(editingDecks ? View.GONE : View.VISIBLE);
            play.setContentDescription(getString(R.string.deck_review_description, deck.name));
            play.setOnClickListener(view -> showReviewModes(deck));
            int description = editingDecks
                    ? (selected ? R.string.deck_selected_description : R.string.deck_unselected_description)
                    : R.string.deck_open_description;
            setContentDescription(getString(description, deck.name));
            setOnClickListener(view -> selectDeck(deck));
            setOnLongClickListener(view -> {
                if (!editingDecks) {
                    editingDecks = true;
                    selectedDeckIds.add(deck.id);
                    updateDeckSelection();
                }
                return true;
            });
        }
    }

    private final class CardsAdapter extends BaseAdapter {
        private final List<FlashcardStore.Card> allCards;
        private final List<FlashcardStore.Card> filtered = new ArrayList<>();

        CardsAdapter(List<FlashcardStore.Card> cards) {
            allCards = cards;
            filter(searchQuery);
        }

        void filter(String query) {
            String needle = query.trim().toLowerCase(Locale.ROOT);
            filtered.clear();
            for (FlashcardStore.Card card : allCards) {
                if (needle.isEmpty() || card.term.toLowerCase(Locale.ROOT).contains(needle)
                        || card.definition.toLowerCase(Locale.ROOT).contains(needle)) {
                    filtered.add(card);
                }
            }
            notifyDataSetChanged();
        }

        public int getCount() { return filtered.size(); }
        public FlashcardStore.Card getItem(int position) { return filtered.get(position); }
        public long getItemId(int position) { return getItem(position).id; }
        public boolean hasStableIds() { return true; }

        public View getView(int position, View recycled, ViewGroup parent) {
            CardRow row = recycled instanceof CardRow ? (CardRow) recycled : new CardRow();
            row.bind(getItem(position));
            return row;
        }
    }

    private final class CardRow extends LinearLayout {
        private final TextView term;
        private final TextView reading;
        private final TextView meaning;
        private final CheckBox check;

        CardRow() {
            super(MainActivity.this);
            setGravity(Gravity.CENTER_VERTICAL);
            setPaddingRelative(ui.dp(16), ui.dp(10), ui.dp(12), ui.dp(10));
            LinearLayout labels = ui.column();
            term = ui.text("", 23, FlashcardViews.INK, "serif");
            reading = ui.text("", 14, FlashcardViews.MUTED, "sans-serif");
            meaning = ui.text("", 15, FlashcardViews.INK, "sans-serif");
            for (TextView label : new TextView[]{term, reading, meaning}) {
                label.setMaxLines(2);
                label.setEllipsize(TextUtils.TruncateAt.END);
                labels.addView(label, ui.fullWidth(0));
            }
            addView(labels, ui.halfWidth(0));

            check = new CheckBox(MainActivity.this);
            check.setClickable(false);
            check.setFocusable(false);
            check.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            addView(check, new LinearLayout.LayoutParams(ui.dp(48), ui.dp(48)));
        }

        void bind(FlashcardStore.Card card) {
            boolean selected = selectedCardIds.contains(card.id);
            int color = selected ? FlashcardViews.LIGHT_GREEN : FlashcardViews.CARD;
            setBackground(ui.shape(color, 13, FlashcardViews.OUTLINE));
            setActivated(selected);
            setSelected(selected);
            check.setChecked(selected);
            check.setVisibility(selectedCardIds.isEmpty() ? View.GONE : View.VISIBLE);
            term.setText(card.term);
            reading.setText(card.reading);
            reading.setVisibility(card.reading.trim().isEmpty() ? View.GONE : View.VISIBLE);
            meaning.setText(card.definition);
        }
    }
}
