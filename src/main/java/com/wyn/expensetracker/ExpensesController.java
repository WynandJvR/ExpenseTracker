package com.wyn.expensetracker;

import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.collections.transformation.SortedList;
import javafx.fxml.FXML;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.util.Duration;

import java.io.File;
import java.io.IOException;
import java.time.LocalDate;
import java.time.Month;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.*;

public class ExpensesController {

    // --- FXML fields ---
    @FXML private TitledPane addExpensePane;
    @FXML private TextField amountField;
    @FXML private ComboBox<String> categoryCombo;
    @FXML private DatePicker datePicker;
    @FXML private TextField descriptionField;
    @FXML private Button addButton;
    @FXML private Button deleteButton;
    @FXML private TextField searchField;
    @FXML private Button filterToggleButton;
    @FXML private HBox filterFieldsBox;
    @FXML private ComboBox<String> filterCategoryCombo;
    @FXML private TextField filterMinAmount;
    @FXML private TextField filterMaxAmount;
    @FXML private ComboBox<String> filterTagCombo;
    @FXML private TableView<Expense> expenseTable;
    @FXML private TableColumn<Expense, Double> amountColumn;
    @FXML private TableColumn<Expense, String> expenseCategoryColumn;
    @FXML private TableColumn<Expense, LocalDate> dateColumn;
    @FXML private TableColumn<Expense, String> descriptionColumn;
    @FXML private TableColumn<Expense, String> tagsColumn;
    @FXML private TableColumn<Expense, String> currencyColumn;
    @FXML private TableColumn<Expense, String> receiptColumn;
    @FXML private ComboBox<String> currencyCodeCombo;
    @FXML private HBox detailBar;
    @FXML private TextField detailText;
    @FXML private Label expenseErrorLabel;
    @FXML private Label viewSummaryLabel;
    @FXML private Button toggleAddButton;
    @FXML private ToggleButton scopeMonthToggle;
    @FXML private ToggleButton scopeAllToggle;
    @FXML private ComboBox<String> filterKindCombo;

    private static final String KIND_ALL = "Everything";
    private static final String KIND_SPEND = "Spending";
    private static final String KIND_INCOME = "Income";
    private static final String KIND_REFUND = "Refunds";
    private static final String KIND_TRANSFER = "Transfers";
    /** Descriptions too generic to learn a rule from. */
    private static final Set<String> UNLEARNABLE = Set.of("card purchase", "bank charges", "bank transaction", "bank fee");

    // --- State ---
    private SharedState state;
    private boolean suppressFilterListener = false;
    private boolean initialized = false;

    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("dd MMM yyyy");

    @FXML
    public void initialize() {
        // Minimal — real setup in init(SharedState)
    }

    public void init(SharedState state) {
        this.state = state;
        if (initialized) return;
        initialized = true;

        // Category combo: share categories, editable
        categoryCombo.setItems(state.getSortedCategories());
        categoryCombo.setEditable(true);
        UIUtils.setupComboCellFactory(categoryCombo);

        // Date picker default to today
        datePicker.setValue(LocalDate.now());

        // Currency combo for new expenses
        currencyCodeCombo.setItems(FXCollections.observableArrayList(CurrencyManager.getCurrencyCodes()));
        currencyCodeCombo.setValue(state.getCurrencyManager().getBaseCurrency());
        currencyCodeCombo.setCellFactory(lv -> new ListCell<String>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : item);
            }
        });

        // Expense table setup
        expenseTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        setupEditableAmountColumn();
        setupEditableCategoryColumn();
        setupEditableDateColumn();
        setupEditableDescriptionColumn();
        setupCurrencyColumn();
        setupTagsColumn();
        setupReceiptColumn();

        // Table items bound to SortedList wrapping filteredData
        SortedList<Expense> sortedData = new SortedList<>(state.getFilteredData());
        sortedData.comparatorProperty().bind(expenseTable.comparatorProperty());
        expenseTable.setItems(sortedData);

        // Row factory: style excluded/income/refund rows + context menu
        expenseTable.setRowFactory(tv -> {
            TableRow<Expense> row = new TableRow<>() {
                @Override
                protected void updateItem(Expense item, boolean empty) {
                    super.updateItem(item, empty);
                    getStyleClass().removeAll("excluded-row", "income-row", "refund-row", "uncategorized-row");
                    if (empty || item == null) return;
                    if (item.isExcluded()) {
                        getStyleClass().add("excluded-row");
                    } else if (item.isRefund()) {
                        getStyleClass().add("refund-row");
                    } else if (item.isIncome()) {
                        getStyleClass().add("income-row");
                    } else if (TransactionClassifier.UNCATEGORIZED.equals(item.getCategory())) {
                        getStyleClass().add("uncategorized-row");
                    }
                }
            };

            ContextMenu menu = new ContextMenu();

            MenuItem copyItem = new MenuItem("Copy");
            copyItem.setOnAction(e -> {
                Expense item = row.getItem();
                if (item != null) {
                    UIUtils.copyExpenseToClipboard(item, state.getCurrencySymbol());
                    showMsg("Copied to clipboard", false);
                }
            });

            MenuItem toggleExclude = new MenuItem("Exclude from Analytics");
            toggleExclude.setOnAction(e -> {
                Expense item = row.getItem();
                if (item != null) {
                    boolean prev = item.isExcluded();
                    item.setExcluded(!prev);
                    try {
                        state.saveExpenses();
                    } catch (IOException ex) {
                        item.setExcluded(prev);
                        showMsg("Failed to save: " + ex.getMessage(), true);
                    }
                    state.requestRefresh();
                }
            });

            MenuItem toggleIncome = new MenuItem("Mark as Income");
            toggleIncome.setOnAction(e -> {
                Expense item = row.getItem();
                if (item != null) {
                    boolean prev = item.isIncome();
                    boolean prevRefund = item.isRefund();
                    item.setIncome(!prev);
                    if (item.isIncome()) item.setRefund(false);
                    state.invalidateSpendCache();
                    try {
                        state.saveExpenses();
                    } catch (IOException ex) {
                        item.setIncome(prev);
                        item.setRefund(prevRefund);
                        showMsg("Failed to save: " + ex.getMessage(), true);
                    }
                    state.requestRefresh();
                }
            });

            MenuItem toggleRefund = new MenuItem("Mark as Refund");
            toggleRefund.setOnAction(e -> {
                Expense item = row.getItem();
                if (item != null) {
                    boolean prevRefund = item.isRefund();
                    boolean prevIncome = item.isIncome();
                    item.setRefund(!prevRefund);
                    // A refund is money back for a purchase: it nets against spend in its
                    // category and is never income. Clear any legacy income flag either way
                    // (older versions set income=true alongside refund).
                    if (item.isRefund() || prevRefund) {
                        item.setIncome(false);
                    }
                    state.invalidateSpendCache();
                    try {
                        state.saveExpenses();
                    } catch (IOException ex) {
                        item.setRefund(prevRefund);
                        item.setIncome(prevIncome);
                        showMsg("Failed to save: " + ex.getMessage(), true);
                    }
                    state.requestRefresh();
                }
            });

            MenuItem manageTags = new MenuItem("Manage Tags...");
            manageTags.setOnAction(e -> {
                Expense item = row.getItem();
                if (item != null) {
                    Set<String> result = new TagEditorPopup(state.getStage(), item.getTags(),
                        new ArrayList<>(state.getTags())).showAndWait();
                    if (result != null) {
                        item.setTags(result);
                        // Ensure new tags are added to global list
                        for (String tag : result) {
                            if (!state.getTags().contains(tag)) state.getTags().add(tag);
                        }
                        try {
                            state.saveExpenses();
                            state.getStorage().saveTags(new ArrayList<>(state.getTags()));
                        } catch (IOException ex) {
                            showMsg("Failed to save tags: " + ex.getMessage(), true);
                        }
                        state.requestRefresh();
                    }
                }
            });

            MenuItem makeRecurring = new MenuItem("Make Recurring...");
            makeRecurring.setOnAction(e -> {
                Expense item = row.getItem();
                if (item != null && item.getRecurringId() == null && !(item instanceof RecurringExpense)) {
                    showMakeRecurringDialog(item);
                }
            });

            SeparatorMenuItem occurrenceSeparator = new SeparatorMenuItem();

            // Generated occurrences are rebuilt from their series on every refresh, so flag,
            // tag and receipt changes made on one would silently vanish. Say where to go instead.
            MenuItem occurrenceHint = new MenuItem("Flags, tags & receipts: change the series on the Recurring tab");
            occurrenceHint.setDisable(true);

            MenuItem editOccurrenceItem = new MenuItem("Edit This Occurrence...");
            editOccurrenceItem.setOnAction(e -> {
                Expense item = row.getItem();
                if (item != null && item.getRecurringId() != null) {
                    showEditOccurrenceDialog(item);
                }
            });

            MenuItem skipOccurrenceItem = new MenuItem("Skip This Occurrence");
            skipOccurrenceItem.setOnAction(e -> {
                Expense item = row.getItem();
                if (item != null && item.getRecurringId() != null) {
                    skipOccurrence(item);
                }
            });

            MenuItem resetOccurrenceItem = new MenuItem("Reset Occurrence to Series");
            resetOccurrenceItem.setOnAction(e -> {
                Expense item = row.getItem();
                if (item != null && item.getRecurringId() != null) {
                    resetOccurrence(item);
                }
            });

            MenuItem attachReceiptItem = new MenuItem("Attach Receipt...");
            attachReceiptItem.setOnAction(e -> {
                Expense item = row.getItem();
                if (item != null) attachReceipt(item);
            });

            MenuItem viewReceiptItem = new MenuItem("View Receipt");
            viewReceiptItem.setOnAction(e -> {
                Expense item = row.getItem();
                if (item != null) viewReceipt(item);
            });

            MenuItem removeReceiptItem = new MenuItem("Remove Receipt");
            removeReceiptItem.setOnAction(e -> {
                Expense item = row.getItem();
                if (item != null && item.getReceiptPath() != null) {
                    item.setReceiptPath(null);
                    try {
                        state.saveExpenses();
                        state.requestRefresh();
                        showMsg("Receipt removed", false);
                    } catch (IOException ex) {
                        showMsg("Failed to save: " + ex.getMessage(), true);
                    }
                }
            });

            MenuItem ownAccount = new MenuItem("This is one of my own accounts");
            ownAccount.setOnAction(e -> {
                Expense item = row.getItem();
                if (item != null) markAsOwnAccount(item);
            });

            menu.setOnShowing(e -> {
                Expense item = row.getItem();
                ownAccount.setVisible(item != null && !item.isExcluded() && item.getRecurringId() == null
                        && learnableKeyword(item) != null);
                if (item != null && item.isExcluded()) {
                    toggleExclude.setText("Count in totals again");
                } else {
                    toggleExclude.setText("Leave out of totals");
                }
                if (item != null && item.isIncome()) {
                    toggleIncome.setText("Mark as Expense");
                } else {
                    toggleIncome.setText("Mark as Income");
                }
                if (item != null && item.isRefund()) {
                    toggleRefund.setText("Unmark as Refund");
                } else {
                    toggleRefund.setText("Mark as Refund");
                }
                makeRecurring.setVisible(item != null && item.getRecurringId() == null
                        && !(item instanceof RecurringExpense));
                boolean isOccurrence = item != null && item.getRecurringId() != null;
                occurrenceHint.setVisible(isOccurrence);
                toggleExclude.setDisable(isOccurrence);
                toggleIncome.setDisable(isOccurrence);
                toggleRefund.setDisable(isOccurrence);
                manageTags.setDisable(isOccurrence);
                attachReceiptItem.setDisable(isOccurrence);
                removeReceiptItem.setDisable(isOccurrence);
                editOccurrenceItem.setVisible(isOccurrence);
                skipOccurrenceItem.setVisible(isOccurrence);
                resetOccurrenceItem.setVisible(isOccurrence && state.getManager().hasOverride(item));
                occurrenceSeparator.setVisible(isOccurrence);
                boolean hasReceipt = item != null && item.getReceiptPath() != null && !item.getReceiptPath().isEmpty();
                viewReceiptItem.setVisible(hasReceipt);
                removeReceiptItem.setVisible(hasReceipt);
                attachReceiptItem.setText(hasReceipt ? "Replace Receipt..." : "Attach Receipt...");
            });

            menu.getItems().addAll(copyItem, new SeparatorMenuItem(),
                    ownAccount, toggleExclude, toggleIncome, toggleRefund,
                    new SeparatorMenuItem(), manageTags,
                    occurrenceSeparator, occurrenceHint, editOccurrenceItem, skipOccurrenceItem, resetOccurrenceItem,
                    new SeparatorMenuItem(), attachReceiptItem, viewReceiptItem, removeReceiptItem,
                    new SeparatorMenuItem(), makeRecurring);
            row.setContextMenu(menu);
            return row;
        });

        // Detail bar selection listener
        expenseTable.getSelectionModel().selectedItemProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal != null) {
                detailBar.setVisible(true);
                detailBar.setManaged(true);
                detailText.setText(String.format("%s  |  %s  |  %s  |  %s",
                        fmt(newVal.getAmount()), newVal.getCategory(),
                        newVal.getDate().format(DATE_FORMAT),
                        newVal.getDescription() != null ? newVal.getDescription() : ""));
            } else {
                detailBar.setVisible(false);
                detailBar.setManaged(false);
                detailText.setText("");
            }
        });

        // Search debounce (300ms)
        PauseTransition searchDebounce = new PauseTransition(Duration.millis(300));
        searchField.textProperty().addListener((observable, oldValue, newValue) -> {
            searchDebounce.setOnFinished(e -> updateFiltering());
            searchDebounce.playFromStart();
        });

        // "This month" / "All time" scope
        ToggleGroup scopeGroup = new ToggleGroup();
        scopeMonthToggle.setToggleGroup(scopeGroup);
        scopeAllToggle.setToggleGroup(scopeGroup);
        scopeGroup.selectedToggleProperty().addListener((obs, oldT, newT) -> {
            if (newT == null) {
                if (oldT != null) oldT.setSelected(true); // keep one selected
                return;
            }
            updateFiltering();
        });

        // Kind of transaction
        filterKindCombo.setItems(FXCollections.observableArrayList(KIND_ALL, KIND_SPEND, KIND_INCOME, KIND_REFUND, KIND_TRANSFER));
        filterKindCombo.setValue(KIND_ALL);
        filterKindCombo.valueProperty().addListener((obs, oldVal, newVal) -> updateFiltering());

        // Filter category combo
        updateFilterCategoryCombo();
        filterCategoryCombo.valueProperty().addListener((obs, oldVal, newVal) -> {
            if (!suppressFilterListener) updateFiltering();
        });
        filterMinAmount.textProperty().addListener((obs, oldVal, newVal) -> updateFiltering());
        filterMaxAmount.textProperty().addListener((obs, oldVal, newVal) -> updateFiltering());

        // Filter tag combo
        updateFilterTagCombo();
        filterTagCombo.valueProperty().addListener((obs, oldVal, newVal) -> {
            if (!suppressFilterListener) updateFiltering();
        });

        // Empty state
        setupEmptyState();

        // Enter key on amountField fires addButton
        amountField.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ENTER) addButton.fire();
        });

        // Disable Add until the amount is a valid positive number; flag bad input inline
        UIUtils.bindPositiveAmountValidation(amountField, addButton);

        // Delete and Copy key handlers on table
        expenseTable.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.DELETE) deleteButton.fire();
            if (e.isControlDown() && e.getCode() == KeyCode.C) {
                Expense selected = expenseTable.getSelectionModel().getSelectedItem();
                if (selected != null) {
                    UIUtils.copyExpenseToClipboard(selected, state.getCurrencySymbol());
                    showMsg("Copied to clipboard", false);
                }
                e.consume();
            }
        });
    }

    // ======================== REFRESH ========================

    public void refresh() {
        updateFilterCategoryCombo();
        updateFilterTagCombo();
        // Follow base-currency changes / profile switches, but never override a choice the
        // user is making in the open add form.
        if (!addExpensePane.isVisible()) {
            currencyCodeCombo.setValue(state.getCurrencyManager().getBaseCurrency());
        }
        // Only show the currency and tag columns once they carry information.
        String base = state.getCurrencyManager().getBaseCurrency();
        boolean foreign = false, tagged = false;
        for (Expense e : state.getExpenseList()) {
            if (e.getCurrency() != null && !e.getCurrency().equalsIgnoreCase(base)) foreign = true;
            if (!e.getTags().isEmpty()) tagged = true;
            if (foreign && tagged) break;
        }
        currencyColumn.setVisible(foreign);
        tagsColumn.setVisible(tagged);
        updateFiltering();
    }

    // ======================== FILTERING ========================

    public void updateFiltering() {
        Integer selectedYear = state.getSelectedYear();
        Month selectedMonth = state.getSelectedMonth();

        if (selectedYear == null || selectedMonth == null) {
            state.getFilteredData().setPredicate(e -> false);
            return;
        }

        YearMonth selectedYearMonth = YearMonth.of(selectedYear, selectedMonth);

        String filter = searchField.getText();
        String lowerCaseFilter = (filter != null && !filter.isEmpty()) ? filter.toLowerCase() : null;

        String selectedCategory = filterCategoryCombo.getValue();
        boolean filterByCategory = selectedCategory != null && !"All Categories".equals(selectedCategory);

        String selectedTag = filterTagCombo.getValue();
        boolean filterByTag = selectedTag != null && !"All Tags".equals(selectedTag);

        double minAmount = 0;
        double maxAmount = Double.MAX_VALUE;
        Double parsedMin = UIUtils.parseAmount(filterMinAmount.getText());
        if (parsedMin != null) minAmount = parsedMin;
        Double parsedMax = UIUtils.parseAmount(filterMaxAmount.getText());
        if (parsedMax != null) maxAmount = parsedMax;
        final double fMin = minAmount;
        final double fMax = maxAmount;

        boolean allTime = scopeAllToggle.isSelected();
        String kind = filterKindCombo.getValue() == null ? KIND_ALL : filterKindCombo.getValue();

        state.getFilteredData().setPredicate(expense -> {
            if (!allTime && !YearMonth.from(expense.getDate()).equals(selectedYearMonth)) return false;
            switch (kind) {
                case KIND_SPEND -> { if (!state.countsAsSpend(expense)) return false; }
                case KIND_INCOME -> { if (!SharedState.isIncomeItem(expense)) return false; }
                case KIND_REFUND -> { if (!expense.isRefund() || expense.isExcluded()) return false; }
                case KIND_TRANSFER -> { if (!expense.isExcluded()) return false; }
                default -> { }
            }
            if (filterByCategory && !expense.getCategory().equals(selectedCategory)) return false;
            if (filterByTag && !expense.hasTag(selectedTag)) return false;
            if (expense.getAmount() < fMin || expense.getAmount() > fMax) return false;
            if (lowerCaseFilter == null) return true;
            boolean matchesTags = expense.getTags().stream()
                .anyMatch(t -> t.toLowerCase().contains(lowerCaseFilter));
            return String.valueOf(expense.getAmount()).contains(lowerCaseFilter) ||
                    expense.getCategory().toLowerCase().contains(lowerCaseFilter) ||
                    expense.getDate().toString().contains(lowerCaseFilter) ||
                    (expense.getDescription() != null && expense.getDescription().toLowerCase().contains(lowerCaseFilter)) ||
                    matchesTags;
        });

        updateViewSummary(allTime ? "All time" : selectedMonth.getDisplayName(TextStyle.FULL, Locale.ENGLISH) + " " + selectedYear);

        // Update empty state message dynamically
        if (state.getFilteredData().isEmpty()) {
            String message;
            if (lowerCaseFilter != null || filterByCategory || !KIND_ALL.equals(kind)) {
                message = "Nothing matches these filters.";
            } else if (allTime) {
                message = "No transactions yet — import a bank statement to get started.";
            } else {
                message = String.format("No transactions in %s %d.",
                        selectedMonth.getDisplayName(TextStyle.FULL, Locale.ENGLISH), selectedYear);
            }
            Label placeholder = new Label(message);
            placeholder.getStyleClass().add("empty-state-label");
            expenseTable.setPlaceholder(placeholder);
        }
    }

    private void updateFilterCategoryCombo() {
        suppressFilterListener = true;
        try {
            String current = filterCategoryCombo.getValue();
            ObservableList<String> filterItems = FXCollections.observableArrayList("All Categories");
            filterItems.addAll(state.getSortedCategories());
            filterCategoryCombo.setItems(filterItems);
            if (current != null && filterItems.contains(current)) {
                filterCategoryCombo.setValue(current);
            } else {
                filterCategoryCombo.setValue("All Categories");
            }
        } finally {
            suppressFilterListener = false;
        }
    }

    private void updateFilterTagCombo() {
        suppressFilterListener = true;
        try {
            String current = filterTagCombo.getValue();
            ObservableList<String> filterItems = FXCollections.observableArrayList("All Tags");
            filterItems.addAll(state.getTags().stream().sorted(SharedState.CATEGORY_ORDER).toList());
            filterTagCombo.setItems(filterItems);
            if (current != null && filterItems.contains(current)) {
                filterTagCombo.setValue(current);
            } else {
                filterTagCombo.setValue("All Tags");
            }
        } finally {
            suppressFilterListener = false;
        }
    }

    private void setupEmptyState() {
        VBox expenseEmptyState = new VBox(6);
        expenseEmptyState.setAlignment(Pos.CENTER);
        Label expenseMsg = new Label("No transactions for this period.");
        expenseMsg.getStyleClass().add("empty-state-label");
        Label expenseHint = new Label("Import a bank statement, or press Ctrl+N to add one by hand.");
        expenseHint.getStyleClass().add("empty-state-hint");
        expenseEmptyState.getChildren().addAll(expenseMsg, expenseHint);
        expenseTable.setPlaceholder(expenseEmptyState);
    }

    /** One line describing what's on screen: count, money in and money out. */
    private void updateViewSummary(String scopeLabel) {
        if (viewSummaryLabel == null) return;
        int count = state.getFilteredData().size();
        double out = 0, in = 0;
        for (Expense e : state.getFilteredData()) {
            // Same rules as the Overview: transfers and bills already covered by an import don't count.
            if (e.isExcluded() || (e.getRecurringId() != null && state.getRecurringCoverage().coveredRecurringIds.contains(e.getRecurringId()))) continue;
            if (SharedState.isIncomeItem(e)) in += state.toBase(e);
            else out += state.spendContribution(e);
        }
        viewSummaryLabel.setText(String.format("%s · %d transaction%s · in %s · out %s",
            scopeLabel, count, count == 1 ? "" : "s", fmt(in), fmt(out)));
    }

    /** Opens every uncategorised transaction, across all months (used by "Sort them"). */
    public void showUncategorized() {
        showCategoryAllTime(TransactionClassifier.UNCATEGORIZED);
    }

    /** Opens every transaction in {@code category}, across all months. */
    public void showCategoryAllTime(String category) {
        suppressFilterListener = true;
        try {
            searchField.clear();
            filterKindCombo.setValue(KIND_ALL);
            if (!filterCategoryCombo.getItems().contains(category)) {
                filterCategoryCombo.getItems().add(category);
            }
            filterCategoryCombo.setValue(category);
            scopeAllToggle.setSelected(true);
        } finally {
            suppressFilterListener = false;
        }
        updateFiltering();
        // Group repeat merchants together so one change sorts the whole group.
        descriptionColumn.setSortType(TableColumn.SortType.ASCENDING);
        expenseTable.getSortOrder().setAll(List.of(descriptionColumn));
        showMsg("Double-click a category to change it — similar transactions follow automatically.", false);
    }

    @FXML
    private void handleToggleAddForm() {
        boolean show = !addExpensePane.isVisible();
        addExpensePane.setVisible(show);
        addExpensePane.setManaged(show);
        addExpensePane.setExpanded(show);
        toggleAddButton.setText(show ? "Close" : "+ Add transaction");
        if (show) amountField.requestFocus();
    }

    /**
     * After the user changes a transaction's category: remember it as a rule and move
     * similar transactions (same merchant/payee, still on the old or no category) too.
     * Returns how many other transactions were moved, or -1 when nothing could be learned
     * (e.g. a bare "Card purchase"). One Ctrl+Z undoes the rule and the moves.
     */
    private int learnFromCategoryChange(Expense edited, String oldCategory, String newCategory) {
        if (edited.getRecurringId() != null || newCategory.equals(oldCategory)) return -1;
        String keyword = learnableKeyword(edited);
        if (keyword == null) return -1;

        CategorizationRules rules = new CategorizationRules();
        rules.addRule(keyword, newCategory); // just this rule, to find what it would match
        // Moving to Transfers says "this is my own account": money both in and out, whatever
        // category it had. Other categories only move same-direction rows that were on the
        // old category or uncategorised.
        boolean ownAccount = TransactionClassifier.TRANSFERS.equals(newCategory);
        List<Expense> similar = new ArrayList<>();
        for (Expense e : state.getManager().getExpenses()) {
            if (e == edited || e.getRecurringId() != null || e.isExcluded()) continue;
            if (!ownAccount) {
                if (e.isIncome() != edited.isIncome()) continue;
                String cat = e.getCategory();
                if (!oldCategory.equals(cat) && !TransactionClassifier.UNCATEGORIZED.equals(cat)) continue;
            }
            if (newCategory.equals(rules.categorize(e.getDescription()))) similar.add(e);
        }
        return teach(keyword, newCategory, similar) ? similar.size() : -1;
    }

    /** The keyword a rule would be learned from, or null for generic descriptions. */
    private static String learnableKeyword(Expense e) {
        String keyword = CategorizationRules.keywordFor(e.getDescription());
        return keyword == null || UNLEARNABLE.contains(keyword.toLowerCase()) ? null : keyword;
    }

    /**
     * The user says this payee/description is one of their own accounts (e.g. a savings
     * account or their credit card): every matching transaction becomes a transfer, left
     * out of spending and income, and future imports are treated the same way.
     */
    private void markAsOwnAccount(Expense item) {
        String keyword = learnableKeyword(item);
        if (keyword == null) return;
        CategorizationRules rules = new CategorizationRules();
        rules.addRule(keyword, TransactionClassifier.TRANSFERS);
        List<Expense> matches = new ArrayList<>();
        for (Expense e : state.getManager().getExpenses()) {
            if (e.getRecurringId() != null || e.isExcluded()) continue;
            if (e == item || TransactionClassifier.TRANSFERS.equals(rules.categorize(e.getDescription()))) matches.add(e);
        }
        if (teach(keyword, TransactionClassifier.TRANSFERS, matches)) {
            state.requestRefresh();
            Toast.show(matches.size() + " transaction" + (matches.size() == 1 ? "" : "s") + " with \"" + keyword
                + "\" now count as transfers between your accounts");
        }
    }

    /**
     * One undoable step: add the rule keyword → category (replacing any older rule for that
     * keyword) and move {@code rows} to the category. Moving to Transfers also takes the rows
     * out of the totals. Undo restores the previous rule and every row. Returns false if saving failed.
     */
    private boolean teach(String keyword, String category, List<Expense> rows) {
        CategorizationRules rules = state.getCategorizationRules();
        String previousRuleKey = null, previousRuleCategory = null;
        for (Map.Entry<String, String> r : rules.getRules().entrySet()) {
            if (r.getKey().equalsIgnoreCase(keyword)) {
                previousRuleKey = r.getKey();
                previousRuleCategory = r.getValue();
            }
        }
        final String prevKey = previousRuleKey, prevCat = previousRuleCategory;
        boolean toTransfers = TransactionClassifier.TRANSFERS.equals(category);
        Map<Expense, String> prevCategory = new IdentityHashMap<>();
        Map<Expense, boolean[]> prevFlags = new IdentityHashMap<>();
        for (Expense e : rows) {
            prevCategory.put(e, e.getCategory());
            prevFlags.put(e, new boolean[]{e.isExcluded(), e.isIncome(), e.isRefund()});
        }
        if (!state.getCategories().contains(category)) state.getCategories().add(category);

        state.getManager().executeCommand(new Command() {
            @Override public void execute() {
                rules.addRule(keyword, category);
                for (Expense e : rows) {
                    e.setCategory(category);
                    if (toTransfers) {
                        e.setExcluded(true);
                        e.setIncome(false);
                        e.setRefund(false);
                    }
                }
                saveRulesQuietly();
                state.invalidateSpendCache();
            }
            @Override public void undo() {
                rules.removeRule(keyword);
                if (prevKey != null) rules.addRule(prevKey, prevCat);
                for (Expense e : rows) {
                    e.setCategory(prevCategory.get(e));
                    boolean[] f = prevFlags.get(e);
                    e.setExcluded(f[0]);
                    e.setIncome(f[1]);
                    e.setRefund(f[2]);
                }
                saveRulesQuietly();
                state.invalidateSpendCache();
            }
        });
        try {
            state.saveExpenses();
            state.getStorage().saveCategories(state.getCategories());
        } catch (IOException ex) {
            state.getManager().rollbackLastCommand();
            showMsg("Couldn't save: " + ex.getMessage(), true);
            return false;
        }
        return true;
    }

    private void saveRulesQuietly() {
        try {
            state.getStorage().saveCategorizationRules(state.getCategorizationRules().getRules());
        } catch (IOException ex) {
            System.err.println("Failed to save rules: " + ex.getMessage());
        }
    }

    // ======================== FXML HANDLERS ========================

    @FXML
    private void handleAddExpense() {
        try {
            Double parsedAmount = UIUtils.parseAmount(amountField.getText());
            if (parsedAmount == null) throw new NumberFormatException(amountField.getText());
            double amount = parsedAmount;
            if (amount <= 0) {
                showMsg("Amount must be positive", true);
                return;
            }
            String category = categoryCombo.getValue();
            if (category == null || category.trim().isEmpty()) {
                category = categoryCombo.getEditor().getText().trim();
                if (category.isEmpty()) {
                    showMsg("Category cannot be empty", true);
                    return;
                }
                if (!state.getCategories().contains(category)) {
                    state.getCategories().add(category);
                    try {
                        state.getStorage().saveCategories(state.getCategories());
                    } catch (Exception ex) {
                        state.getCategories().remove(category);
                        showMsg("Failed to save categories: " + ex.getMessage(), true);
                        return;
                    }
                }
            }
            LocalDate date = datePicker.getValue();
            if (date == null) {
                showMsg("Please select a date", true);
                return;
            }
            String description = descriptionField.getText().trim();

            Expense expense = new Expense(amount, category, date, description.isEmpty() ? "" : description);
            String selectedCurrency = currencyCodeCombo.getValue();
            if (selectedCurrency != null && !selectedCurrency.equals(state.getCurrencyManager().getBaseCurrency())) {
                expense.setCurrency(selectedCurrency);
            }
            state.getManager().executeCommand(new AddExpenseCommand(state.getManager(), expense));
            try {
                state.saveExpenses();
            } catch (Exception ex) {
                state.getManager().rollbackLastCommand();
                showMsg("Failed to save expense: " + ex.getMessage(), true);
                return;
            }
            state.requestRefresh();
            resetExpenseForm();
            showMsg(String.format("Added %s to %s on %s",
                    fmt(amount), category, date.format(DATE_FORMAT)), false);
        } catch (NumberFormatException ex) {
            showMsg("Invalid amount: Please enter a valid number (e.g., 10.99)", true);
        } catch (Exception ex) {
            showMsg("Error: " + ex.getMessage(), true);
        }
    }

    @FXML
    private void handleDeleteExpense() {
        Expense selectedExpense = expenseTable.getSelectionModel().getSelectedItem();
        if (selectedExpense == null) {
            showMsg("Please select an expense to delete", true);
            return;
        }

        Alert confirmation = new Alert(Alert.AlertType.CONFIRMATION);
        confirmation.initOwner(state.getStage());
        confirmation.setTitle("Confirm Deletion");
        confirmation.setHeaderText(null);
        boolean isOccurrence = selectedExpense.getRecurringId() != null;
        confirmation.setContentText(isOccurrence
            ? "Delete this occurrence? Only this date is removed; the rest of the recurring series is kept."
            : "Are you sure you want to delete this expense?");
        confirmation.getDialogPane().getStylesheets().add(getClass().getResource("/styles.css").toExternalForm());

        Optional<ButtonType> result = confirmation.showAndWait();
        if (result.isPresent() && result.get() == ButtonType.OK) {
            // Clean up receipt file if present. A generated occurrence is only skipped (its
            // series lives on), so never delete a file it may share with the series.
            String receiptPath = isOccurrence ? null : selectedExpense.getReceiptPath();
            state.getManager().executeCommand(new DeleteExpenseCommand(state.getManager(), selectedExpense));
            try {
                state.saveExpenses();
                // Deleting an occurrence is persisted as a "skipped" override.
                if (isOccurrence) state.saveRecurringOverrides();
                if (receiptPath != null && !receiptPath.isEmpty()) {
                    java.io.File receiptFile = resolveReceiptFile(receiptPath);
                    if (receiptFile.exists()) receiptFile.delete();
                }
                state.requestRefresh();
                showMsg("Expense deleted successfully!", false);
            } catch (Exception ex) {
                state.getManager().rollbackLastCommand();
                showMsg("Error deleting expense: " + ex.getMessage(), true);
            }
        }
    }

    @FXML
    private void handleToggleFilters() {
        boolean showing = filterFieldsBox.isVisible();
        filterFieldsBox.setVisible(!showing);
        filterFieldsBox.setManaged(!showing);
        filterToggleButton.setText(showing ? "More" : "Less");
    }

    @FXML
    private void handleClearFilters() {
        filterKindCombo.setValue(KIND_ALL);
        scopeMonthToggle.setSelected(true);
        filterCategoryCombo.setValue("All Categories");
        filterTagCombo.setValue("All Tags");
        filterMinAmount.clear();
        filterMaxAmount.clear();
        searchField.clear();
        filterFieldsBox.setVisible(false);
        filterFieldsBox.setManaged(false);
        filterToggleButton.setText("More");
    }

    @FXML
    public void handleExport() {
        try {
            File defaultFile = new File(System.getProperty("user.home") + File.separator
                    + ".expenseTracker" + File.separator + "expenses.xlsx");
            String filePath;

            if (!defaultFile.exists()) {
                FileChooser fileChooser = new FileChooser();
                fileChooser.setTitle("Save Expenses to Excel");
                fileChooser.setInitialDirectory(new File(System.getProperty("user.home")));
                fileChooser.setInitialFileName("expenses.xlsx");
                fileChooser.getExtensionFilters().add(
                        new FileChooser.ExtensionFilter("Excel Files", "*.xlsx")
                );
                File selectedFile = fileChooser.showSaveDialog(state.getStage());
                if (selectedFile == null) {
                    showMsg("Export cancelled by user", true);
                    return;
                }
                filePath = selectedFile.getAbsolutePath();
            } else {
                filePath = defaultFile.getAbsolutePath();
            }

            ExcelExporter.exportExpenses(state.getManager().getExpensesForSave(),
                new java.util.ArrayList<>(state.getDebts()),
                new java.util.ArrayList<>(state.getDebtPayments()), filePath);
            showMsg("Expenses exported to Excel successfully at: " + filePath, false);
        } catch (IOException ex) {
            showMsg("Failed to export to Excel: " + ex.getMessage(), true);
        }
    }

    @FXML
    private void handleExportFiltered() {
        FilteredList<Expense> filteredData = state.getFilteredData();
        if (filteredData.isEmpty()) {
            showMsg("No expenses to export for the current view", true);
            return;
        }
        try {
            Integer year = state.getSelectedYear();
            Month month = state.getSelectedMonth();
            String defaultName = (year != null && month != null)
                    ? String.format("expenses_%s_%d.xlsx", month.toString().toLowerCase(), year)
                    : "expenses_filtered.xlsx";

            FileChooser fileChooser = new FileChooser();
            fileChooser.setTitle("Export Current View to Excel");
            fileChooser.setInitialDirectory(new File(System.getProperty("user.home")));
            fileChooser.setInitialFileName(defaultName);
            fileChooser.getExtensionFilters().add(
                    new FileChooser.ExtensionFilter("Excel Files", "*.xlsx")
            );
            File selectedFile = fileChooser.showSaveDialog(state.getStage());
            if (selectedFile == null) {
                showMsg("Export cancelled", true);
                return;
            }

            List<Expense> toExport = new ArrayList<>(filteredData);
            ExcelExporter.exportExpenses(toExport,
                new java.util.ArrayList<>(state.getDebts()),
                new java.util.ArrayList<>(state.getDebtPayments()),
                selectedFile.getAbsolutePath());
            showMsg(String.format("Exported %d expenses to %s", toExport.size(), selectedFile.getName()), false);
        } catch (IOException ex) {
            showMsg("Failed to export: " + ex.getMessage(), true);
        }
    }

    @FXML
    private void handleAddCategory() {
        TextInputDialog dialog = new TextInputDialog();
        dialog.initOwner(state.getStage());
        dialog.setTitle("Add Category");
        dialog.setHeaderText("Enter a new category:");
        dialog.setContentText("Category:");
        dialog.getDialogPane().getStylesheets().add(getClass().getResource("/styles.css").toExternalForm());

        dialog.showAndWait().ifPresent(category -> {
            category = category.trim();
            if (category.length() > ExpenseManager.MAX_CATEGORY_LENGTH) {
                showMsg("Category name is too long (max " + ExpenseManager.MAX_CATEGORY_LENGTH + " characters)", true);
                return;
            }
            if (!category.isEmpty() && !state.getCategories().contains(category)) {
                state.getCategories().add(category);
                categoryCombo.setValue(category);
                try {
                    state.getStorage().saveCategories(state.getCategories());
                    showMsg("", false);
                } catch (Exception ex) {
                    state.getCategories().remove(category);
                    showMsg("Error saving categories: " + ex.getMessage(), true);
                }
            } else if (state.getCategories().contains(category)) {
                showMsg("Category already exists", true);
            } else {
                showMsg("Category cannot be empty", true);
            }
        });
    }

    @FXML
    private void handleRemoveCategory() {
        String selectedCategory = categoryCombo.getValue();
        if (selectedCategory == null) {
            showMsg("Please select a category to remove", true);
            return;
        }

        boolean isUsed = state.getManager().getExpenses().stream()
                .anyMatch(expense -> expense.getCategory().equals(selectedCategory));
        if (isUsed) {
            showMsg("Cannot remove category as it is used in existing expenses", true);
            return;
        }

        state.getCategories().remove(selectedCategory);
        if (categoryCombo.getItems().isEmpty()) {
            categoryCombo.setValue(null);
        } else if (categoryCombo.getSelectionModel().getSelectedIndex() >= categoryCombo.getItems().size()) {
            categoryCombo.getSelectionModel().selectLast();
        }

        try {
            state.getStorage().saveCategories(state.getCategories());
            showMsg("", false);
        } catch (Exception ex) {
            state.getCategories().add(selectedCategory);
            showMsg("Error saving categories: " + ex.getMessage(), true);
        }
    }

    // ======================== INLINE EDITING ========================

    private boolean canEditExpense(Expense expense) {
        if (expense == null) return false;
        if (expense.getRecurringId() != null) {
            showMsg("Edit recurring expenses from the Recurring Expenses tab", true);
            return false;
        }
        return true;
    }

    private void handleInlineEdit(Expense oldExpense, Expense newExpense) {
        try {
            state.getManager().executeCommand(new EditExpenseCommand(state.getManager(), oldExpense, newExpense));
        } catch (IllegalArgumentException ex) {
            // Rejected by validation — revert the edited cell to its previous value.
            showMsg(ex.getMessage(), true);
            refresh();
            return;
        }
        try {
            state.saveExpenses();
        } catch (Exception ex) {
            state.getManager().rollbackLastCommand();
            showMsg("Error saving edit: " + ex.getMessage(), true);
            refresh();
            return;
        }
        state.requestRefresh();
        showMsg("Expense updated", false);
    }

    /**
     * The expense shown in {@code cell}'s row, read from the table by index. Cells are recycled
     * as the table scrolls/sorts/refreshes, so an inline editor must remember the expense it was
     * opened on and compare against this rather than trusting whatever row the cell shows now.
     */
    private static Expense rowItemOf(TableCell<Expense, ?> cell) {
        TableView<Expense> tv = cell.getTableView();
        int i = cell.getIndex();
        if (tv == null || i < 0 || i >= tv.getItems().size()) return null;
        return tv.getItems().get(i);
    }

    /**
     * Cancels an inline editor when it loses focus (click elsewhere, tab away) while still editing.
     * Focus also drops when the whole window is deactivated (alt-tab); that keeps the edit alive.
     */
    private static void cancelOnFocusLoss(javafx.scene.Node editor, java.util.function.BooleanSupplier stillEditing,
                                          Runnable cancel) {
        editor.focusedProperty().addListener((obs, was, now) -> {
            if (now || !stillEditing.getAsBoolean()) return;
            javafx.scene.Scene scene = editor.getScene();
            if (scene == null || scene.getWindow() == null || !scene.getWindow().isFocused()) return;
            cancel.run();
        });
    }

    /** Copy of {@code old} with the four editable fields replaced and every flag carried over. */
    private static Expense copyForEdit(Expense old, double amount, String category, LocalDate date, String description) {
        Expense updated = new Expense(amount, category, date, description);
        updated.setImportId(old.getImportId());
        updated.setExcluded(old.isExcluded());
        updated.setIncome(old.isIncome());
        updated.setRefund(old.isRefund());
        updated.setTags(old.getTags());
        updated.setCurrency(old.getCurrency());
        updated.setReceiptPath(old.getReceiptPath());
        return updated;
    }

    /** True if {@code target} is still a live ledger entry (not replaced/deleted since the edit began). */
    private boolean stillInLedger(Expense target) {
        if (target == null) return false;
        for (Expense e : state.getManager().getExpenses()) {
            if (e == target) return true;
        }
        return false;
    }

    private void setupEditableAmountColumn() {
        amountColumn.setCellFactory(col -> new TableCell<Expense, Double>() {
            private TextField textField;
            private boolean editing = false;
            private Expense editTarget;

            {
                setOnMouseClicked(event -> {
                    if (event.getClickCount() == 2 && !isEmpty() && canEditExpense(rowItemOf(this))) {
                        startInlineEdit();
                    }
                });
            }

            private void startInlineEdit() {
                editTarget = rowItemOf(this);
                if (editTarget == null) return;
                editing = true;
                // Locale.ROOT so the prefill round-trips on comma-decimal locales (e.g. en_ZA).
                textField = new TextField(UIUtils.formatAmountForEdit(editTarget.getAmount()));
                textField.getStyleClass().add("text-field");
                textField.setOnAction(e -> commitInlineEdit());
                textField.setOnKeyPressed(e -> { if (e.getCode() == KeyCode.ESCAPE) cancelInlineEdit(); });
                cancelOnFocusLoss(textField, () -> editing, this::cancelInlineEdit);
                setGraphic(textField);
                setText(null);
                textField.selectAll();
                textField.requestFocus();
            }

            private void commitInlineEdit() {
                if (!editing) return;
                Expense old = editTarget;
                Double val = UIUtils.parseAmount(textField.getText());
                if (val == null) { showMsg("Invalid amount", true); cancelInlineEdit(); return; }
                if (val <= 0) { showMsg("Amount must be positive", true); cancelInlineEdit(); return; }
                if (!stillInLedger(old)) { cancelInlineEdit(); return; }
                cancelInlineEdit();
                if (val == old.getAmount()) return;
                handleInlineEdit(old, copyForEdit(old, val, old.getCategory(), old.getDate(), old.getDescription()));
            }

            private void cancelInlineEdit() {
                editing = false;
                editTarget = null;
                setGraphic(null);
                updateItem(getItem(), isEmpty());
            }

            @Override
            protected void updateItem(Double item, boolean empty) {
                super.updateItem(item, empty);
                // Recycled onto another row (scroll/sort/refresh): drop the editor, never carry it over.
                if (editing && (empty || rowItemOf(this) != editTarget)) { editing = false; editTarget = null; }
                if (empty || item == null) { setText(null); setGraphic(null); editing = false; }
                else if (editing && textField != null) { setGraphic(textField); setText(null); }
                else {
                    Expense expense = rowItemOf(this);
                    if (expense != null && expense.getCurrency() != null
                            && !expense.getCurrency().equals(state.getCurrencyManager().getBaseCurrency())) {
                        setText(CurrencyManager.fmt(item, expense.getCurrency()));
                    } else {
                        setText(fmt(item));
                    }
                    setGraphic(null);
                    setAlignment(Pos.CENTER_RIGHT);
                }
            }
        });
    }

    private void setupEditableCategoryColumn() {
        expenseCategoryColumn.setCellFactory(col -> new TableCell<Expense, String>() {
            private ComboBox<String> comboBox;
            private boolean editing = false;
            private boolean committing = false;
            private Expense editTarget;
            // Set when the popup closes because the user chose an item (mouse click or Enter in the list).
            private boolean picked = false;
            private boolean escapedInPopup = false;

            {
                setOnMouseClicked(event -> {
                    if (event.getClickCount() == 2 && !isEmpty() && canEditExpense(rowItemOf(this))) {
                        startInlineEdit();
                    }
                });
            }

            private void startInlineEdit() {
                editTarget = rowItemOf(this);
                if (editTarget == null) return;
                editing = true;
                committing = false;
                picked = false;
                escapedInPopup = false;
                comboBox = new ComboBox<>(FXCollections.observableArrayList(state.getSortedCategories()));
                comboBox.setValue(editTarget.getCategory());
                comboBox.setEditable(true);
                comboBox.getStyleClass().add("combo-box");
                // No setOnAction(commit): it fires on every arrow-key move through the popup, and a
                // commit also bulk-moves similar transactions. Commit on Enter, or when the popup
                // closes after a pick; Escape cancels.
                comboBox.setCellFactory(lv -> {
                    if (lv.getProperties().putIfAbsent("inlinePickHook", Boolean.TRUE) == null) {
                        lv.addEventFilter(javafx.scene.input.KeyEvent.KEY_PRESSED, e -> {
                            if (e.getCode() == KeyCode.ENTER) picked = true;
                            else if (e.getCode() == KeyCode.ESCAPE) escapedInPopup = true;
                        });
                    }
                    ListCell<String> cell = new ListCell<>() {
                        @Override
                        protected void updateItem(String item, boolean empty) {
                            super.updateItem(item, empty);
                            setText(empty || item == null ? null : item);
                        }
                    };
                    // PRESSED, not RELEASED: the skin's own RELEASED filter on the list hides the
                    // popup before a cell-level RELEASED filter would run, so onHidden would miss it.
                    cell.addEventFilter(javafx.scene.input.MouseEvent.MOUSE_PRESSED, e -> {
                        if (!cell.isEmpty()) picked = true;
                    });
                    return cell;
                });
                comboBox.setOnShowing(e -> { picked = false; escapedInPopup = false; });
                comboBox.setOnHidden(e -> {
                    if (!editing) return;
                    if (escapedInPopup) cancelInlineEdit();
                    else if (picked) commitInlineEdit();
                    picked = false;
                });
                comboBox.addEventFilter(javafx.scene.input.KeyEvent.KEY_PRESSED, e -> {
                    if (e.getCode() == KeyCode.ENTER) {
                        e.consume();
                        commitInlineEdit();
                    } else if (e.getCode() == KeyCode.ESCAPE) {
                        e.consume();
                        cancelInlineEdit();
                    }
                });
                cancelOnFocusLoss(comboBox, () -> editing && !comboBox.isShowing(), this::cancelInlineEdit);
                setGraphic(comboBox);
                setText(null);
                comboBox.requestFocus();
            }

            private void commitInlineEdit() {
                if (committing || !editing) return;
                committing = true;
                try {
                    // The editor holds what the user typed or picked; the value can lag behind it.
                    String typed = comboBox.getEditor().getText();
                    String newCategory = typed == null ? "" : typed.trim();
                    if (newCategory.isEmpty() && comboBox.getValue() != null) newCategory = comboBox.getValue().trim();
                    Expense old = editTarget;
                    if (comboBox.isShowing()) comboBox.hide();
                    if (newCategory.isEmpty()) {
                        showMsg("Category cannot be empty", true);
                        cancelInlineEdit();
                        return;
                    }
                    if (!stillInLedger(old)) {
                        cancelInlineEdit();
                        return;
                    }
                    cancelInlineEdit();
                    if (newCategory.equals(old.getCategory())) return;
                    if (!state.getCategories().contains(newCategory)) {
                        state.getCategories().add(newCategory);
                        try { state.getStorage().saveCategories(state.getCategories()); }
                        catch (Exception ex) { state.getCategories().remove(newCategory); }
                    }
                    Expense updated = copyForEdit(old, old.getAmount(), newCategory, old.getDate(), old.getDescription());
                    if (TransactionClassifier.TRANSFERS.equals(newCategory)) {
                        // A transfer between your own accounts is neither spending nor income.
                        updated.setExcluded(true);
                        updated.setIncome(false);
                        updated.setRefund(false);
                    }
                    String oldCategory = old.getCategory();
                    handleInlineEdit(old, updated);
                    if (state.getManager().getExpenses().contains(updated)) {
                        int moved = learnFromCategoryChange(updated, oldCategory, newCategory);
                        state.requestRefresh();
                        if (moved > 0) {
                            Toast.show("Moved " + moved + " similar transaction" + (moved == 1 ? "" : "s") + " to " + newCategory + " too");
                        } else if (moved == 0) {
                            Toast.show("Saved — future imports will use " + newCategory);
                        }
                    }
                } catch (Exception ex) {
                    System.err.println("Error in category commitInlineEdit: " + ex.getMessage());
                    cancelInlineEdit();
                } finally {
                    committing = false;
                }
            }

            private void cancelInlineEdit() {
                editing = false;
                editTarget = null;
                setGraphic(null);
                updateItem(getItem(), isEmpty());
            }

            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (editing && (empty || rowItemOf(this) != editTarget)) { editing = false; editTarget = null; }
                if (empty || item == null) { setText(null); setGraphic(null); editing = false; }
                else if (editing && comboBox != null) { setGraphic(comboBox); setText(null); }
                else { setText(item); setGraphic(null); setAlignment(Pos.CENTER_LEFT); }
            }
        });
    }

    private void setupEditableDateColumn() {
        dateColumn.setCellFactory(col -> new TableCell<Expense, LocalDate>() {
            private DatePicker picker;
            private boolean editing = false;
            private Expense editTarget;

            {
                setOnMouseClicked(event -> {
                    if (event.getClickCount() == 2 && !isEmpty() && canEditExpense(rowItemOf(this))) {
                        startInlineEdit();
                    }
                });
            }

            private void startInlineEdit() {
                editTarget = rowItemOf(this);
                if (editTarget == null) return;
                editing = true;
                picker = new DatePicker(editTarget.getDate());
                picker.getStyleClass().add("date-picker");
                picker.setOnAction(e -> commitInlineEdit());
                picker.setOnKeyPressed(e -> { if (e.getCode() == KeyCode.ESCAPE) cancelInlineEdit(); });
                cancelOnFocusLoss(picker, () -> editing && !picker.isShowing(), this::cancelInlineEdit);
                setGraphic(picker);
                setText(null);
                picker.requestFocus();
            }

            private void commitInlineEdit() {
                if (!editing) return;
                LocalDate newDate = picker.getValue();
                Expense old = editTarget;
                if (newDate == null || !stillInLedger(old)) { cancelInlineEdit(); return; }
                cancelInlineEdit();
                if (newDate.equals(old.getDate())) return;
                handleInlineEdit(old, copyForEdit(old, old.getAmount(), old.getCategory(), newDate, old.getDescription()));
            }

            private void cancelInlineEdit() {
                editing = false;
                editTarget = null;
                setGraphic(null);
                updateItem(getItem(), isEmpty());
            }

            @Override
            protected void updateItem(LocalDate item, boolean empty) {
                super.updateItem(item, empty);
                if (editing && (empty || rowItemOf(this) != editTarget)) { editing = false; editTarget = null; }
                if (empty || item == null) { setText(null); setGraphic(null); editing = false; }
                else if (editing && picker != null) { setGraphic(picker); setText(null); }
                else {
                    setText(item.format(DATE_FORMAT));
                    setGraphic(null);
                    setAlignment(Pos.CENTER);
                }
            }
        });
    }

    private void setupEditableDescriptionColumn() {
        descriptionColumn.setCellFactory(col -> new TableCell<Expense, String>() {
            private TextField textField;
            private boolean editing = false;
            private Expense editTarget;

            {
                setOnMouseClicked(event -> {
                    if (event.getClickCount() == 2 && !isEmpty() && canEditExpense(rowItemOf(this))) {
                        startInlineEdit();
                    }
                });
                // Full text of long descriptions (the column cuts them off).
                HoverTip.install(this, () -> !isEmpty() && !editing && getItem() != null
                    && getItem().length() > 30 ? getItem() : null);
            }

            private void startInlineEdit() {
                editTarget = rowItemOf(this);
                if (editTarget == null) return;
                editing = true;
                textField = new TextField(editTarget.getDescription() != null ? editTarget.getDescription() : "");
                textField.getStyleClass().add("text-field");
                textField.setOnAction(e -> commitInlineEdit());
                textField.setOnKeyPressed(e -> { if (e.getCode() == KeyCode.ESCAPE) cancelInlineEdit(); });
                cancelOnFocusLoss(textField, () -> editing, this::cancelInlineEdit);
                setGraphic(textField);
                setText(null);
                textField.selectAll();
                textField.requestFocus();
            }

            private void commitInlineEdit() {
                if (!editing) return;
                Expense old = editTarget;
                String newDescription = textField.getText().trim();
                if (!stillInLedger(old)) { cancelInlineEdit(); return; }
                cancelInlineEdit();
                if (newDescription.equals(old.getDescription() != null ? old.getDescription() : "")) return;
                handleInlineEdit(old, copyForEdit(old, old.getAmount(), old.getCategory(), old.getDate(), newDescription));
            }

            private void cancelInlineEdit() {
                editing = false;
                editTarget = null;
                setGraphic(null);
                updateItem(getItem(), isEmpty());
            }

            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (editing && (empty || rowItemOf(this) != editTarget)) { editing = false; editTarget = null; }
                if (empty) { setText(null); setGraphic(null); editing = false; }
                else if (editing && textField != null) { setGraphic(textField); setText(null); }
                else {
                    String text = item != null ? item : "";
                    setText(text);
                    setGraphic(null);
                    setAlignment(Pos.CENTER_LEFT);
                }
            }
        });
    }

    private void setupTagsColumn() {
        tagsColumn.setCellValueFactory(cellData -> {
            Expense expense = cellData.getValue();
            String joined = expense.getTags().isEmpty() ? "" : String.join(", ", expense.getTags());
            return new javafx.beans.property.SimpleStringProperty(joined);
        });
        tagsColumn.setCellFactory(col -> new TableCell<Expense, String>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || getTableRow() == null || getTableRow().getItem() == null) {
                    setGraphic(null);
                    setText(null);
                } else {
                    Expense expense = getTableRow().getItem();
                    Set<String> tags = expense.getTags();
                    if (tags.isEmpty()) {
                        setGraphic(null);
                        setText("-");
                        setStyle("-fx-text-fill: #666666; -fx-font-style: italic;");
                    } else {
                        HBox chipBox = new HBox(4);
                        chipBox.setAlignment(Pos.CENTER_LEFT);
                        for (String tag : tags) {
                            Label chip = new Label(tag);
                            chip.setStyle("-fx-background-color: rgba(92, 107, 192, 0.2); -fx-text-fill: #9FA8DA; "
                                + "-fx-padding: 2 8; -fx-background-radius: 12; -fx-font-size: 11px;");
                            chipBox.getChildren().add(chip);
                        }
                        setGraphic(chipBox);
                        setText(null);
                        setStyle("");
                    }
                }
            }
        });
    }

    // ======================== CURRENCY COLUMN ========================

    private void setupCurrencyColumn() {
        currencyColumn.setCellValueFactory(cellData -> {
            Expense expense = cellData.getValue();
            String cur = state.getCurrencyManager().resolveExpenseCurrency(expense);
            return new javafx.beans.property.SimpleStringProperty(cur);
        });
        currencyColumn.setCellFactory(col -> new TableCell<Expense, String>() {
            {
                HoverTip.install(this, () -> {
                    String cur = getItem();
                    Expense expense = getTableRow() != null ? getTableRow().getItem() : null;
                    if (isEmpty() || cur == null || expense == null
                            || cur.equals(state.getCurrencyManager().getBaseCurrency())) return null;
                    if (!state.getCurrencyManager().hasRate(cur)) return "No exchange rate set for " + cur + " — using 1:1";
                    return String.format("%s (= %s)", CurrencyManager.fmt(expense.getAmount(), cur),
                        UIUtils.fmt(state.getCurrencyManager().toBase(expense.getAmount(), cur), state.getCurrencySymbol()));
                });
            }

            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setStyle("");
                } else {
                    setText(item);
                    setAlignment(Pos.CENTER);
                    boolean isForeign = !item.equals(state.getCurrencyManager().getBaseCurrency());
                    setStyle(isForeign ? "-fx-text-fill: #F7B731; -fx-font-weight: bold;" : "");
                    if (isForeign && !state.getCurrencyManager().hasRate(item)) {
                        setStyle("-fx-text-fill: #FC5C65; -fx-font-weight: bold;");
                    }
                }
            }
        });
    }

    // ======================== RECEIPT COLUMN ========================

    private void setupReceiptColumn() {
        receiptColumn.setCellValueFactory(cellData -> {
            Expense expense = cellData.getValue();
            return new javafx.beans.property.SimpleStringProperty(expense.getReceiptPath());
        });
        receiptColumn.setCellFactory(col -> new TableCell<Expense, String>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || getTableRow() == null || getTableRow().getItem() == null) {
                    setGraphic(null);
                    setText(null);
                } else {
                    Expense expense = getTableRow().getItem();
                    if (expense.getReceiptPath() != null && !expense.getReceiptPath().isEmpty()) {
                        Label icon = new Label("\uD83D\uDCCE"); // paperclip
                        icon.setStyle("-fx-cursor: hand; -fx-font-size: 14px;");
                        icon.setOnMouseClicked(e -> viewReceipt(expense));
                        HoverTip.install(icon, "View receipt");
                        setGraphic(icon);
                    } else {
                        setGraphic(null);
                    }
                    setText(null);
                    setAlignment(Pos.CENTER);
                }
            }
        });
    }

    private java.io.File resolveReceiptFile(String receiptPath) {
        java.io.File file = new java.io.File(receiptPath);
        if (file.isAbsolute()) return file;
        return new java.io.File(state.getStorage().getReceiptsDir(), receiptPath);
    }

    private static final Set<String> IMAGE_EXTENSIONS = Set.of(
        ".jpg", ".jpeg", ".png", ".bmp", ".gif", ".tiff", ".tif");

    private void viewReceipt(Expense expense) {
        if (expense.getReceiptPath() == null) return;
        java.io.File file = resolveReceiptFile(expense.getReceiptPath());
        if (!file.exists()) {
            showMsg("Receipt file not found: " + expense.getReceiptPath(), true);
            return;
        }
        String name = file.getName().toLowerCase();
        String ext = name.contains(".") ? name.substring(name.lastIndexOf('.')) : "";
        if (!IMAGE_EXTENSIONS.contains(ext)) {
            // Non-image file — open with system default application
            try {
                java.awt.Desktop.getDesktop().open(file);
            } catch (Exception ex) {
                showMsg("Cannot open file: " + ex.getMessage(), true);
            }
            return;
        }
        try {
            javafx.scene.image.Image image = new javafx.scene.image.Image(file.toURI().toString(), 600, 800, true, true);
            javafx.scene.image.ImageView imageView = new javafx.scene.image.ImageView(image);
            imageView.setPreserveRatio(true);
            imageView.setFitWidth(600);

            ScrollPane scrollPane = new ScrollPane(imageView);
            scrollPane.setFitToWidth(true);
            scrollPane.setPrefSize(640, 700);
            scrollPane.setStyle("-fx-background-color: #2A2A2A;");

            Alert dialog = new Alert(Alert.AlertType.NONE);
            dialog.initOwner(state.getStage());
            dialog.setTitle("Receipt - " + (expense.getDescription() != null ? expense.getDescription() : expense.getCategory()));
            dialog.getDialogPane().setContent(scrollPane);
            dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
            dialog.getDialogPane().getStylesheets().add(getClass().getResource("/styles.css").toExternalForm());
            dialog.getDialogPane().setPrefSize(660, 740);
            dialog.showAndWait();
        } catch (Exception ex) {
            showMsg("Error viewing receipt: " + ex.getMessage(), true);
        }
    }

    private void attachReceipt(Expense expense) {
        if (expense.getRecurringId() != null) {
            // Generated occurrences are rebuilt on refresh; the receipt link would be lost.
            showMsg("Receipts can't be attached to a single recurring occurrence", true);
            return;
        }
        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("Attach Receipt Image");
        fileChooser.getExtensionFilters().addAll(
            new FileChooser.ExtensionFilter("Image Files", "*.jpg", "*.jpeg", "*.png", "*.bmp", "*.tiff", "*.gif"),
            new FileChooser.ExtensionFilter("All Files", "*.*")
        );
        java.io.File selected = fileChooser.showOpenDialog(state.getStage());
        if (selected == null) return;

        try {
            String receiptsDir = state.getStorage().getReceiptsDir();
            String ext = "";
            int dot = selected.getName().lastIndexOf('.');
            if (dot >= 0) ext = selected.getName().substring(dot);
            String destName = System.currentTimeMillis() + "_" + expense.getCategory().replaceAll("[^a-zA-Z0-9]", "") + ext;
            java.io.File dest = new java.io.File(receiptsDir, destName);
            java.nio.file.Files.copy(selected.toPath(), dest.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);

            expense.setReceiptPath(dest.getName());
            state.saveExpenses();
            state.requestRefresh();
            showMsg("Receipt attached", false);
        } catch (Exception ex) {
            showMsg("Error attaching receipt: " + ex.getMessage(), true);
        }
    }

    // ======================== MAKE RECURRING DIALOG ========================

    // ======================== RECURRING OCCURRENCE OVERRIDES ========================

    private void skipOccurrence(Expense occurrence) {
        LocalDate when = occurrence.getDate();
        try {
            state.getManager().skipOccurrence(occurrence);
            state.saveRecurringOverrides();
        } catch (Exception ex) {
            showMsg("Failed to skip occurrence: " + ex.getMessage(), true);
            return;
        }
        state.syncExpenseList();
        state.requestRefresh();
        showMsg("Skipped occurrence on " + when.format(DATE_FORMAT), false);
    }

    private void resetOccurrence(Expense occurrence) {
        try {
            state.getManager().resetOccurrence(occurrence);
            state.saveRecurringOverrides();
        } catch (Exception ex) {
            showMsg("Failed to reset occurrence: " + ex.getMessage(), true);
            return;
        }
        state.syncExpenseList();
        state.requestRefresh();
        showMsg("Occurrence restored to series default", false);
    }

    private void showEditOccurrenceDialog(Expense occurrence) {
        Stage dialog = new Stage();
        dialog.initModality(Modality.WINDOW_MODAL);
        dialog.initOwner(state.getStage());
        dialog.setTitle("Edit This Occurrence");

        Label header = new Label(String.format("Edit only the %s occurrence of this recurring expense",
                occurrence.getDate().format(DATE_FORMAT)));
        header.getStyleClass().add("section-title");
        header.setWrapText(true);

        Label note = new Label("Other occurrences in the series are unaffected.");
        note.getStyleClass().add("form-label");
        note.setWrapText(true);

        Label amountLabel = new Label("Amount:");
        amountLabel.getStyleClass().add("form-label");
        TextField amountField = new TextField(UIUtils.formatAmountForEdit(occurrence.getAmount()));
        amountField.getStyleClass().add("text-field");

        Label categoryLabel = new Label("Category:");
        categoryLabel.getStyleClass().add("form-label");
        ComboBox<String> categoryBox = new ComboBox<>(state.getSortedCategories());
        categoryBox.setMaxWidth(Double.MAX_VALUE);
        categoryBox.getStyleClass().add("combo-box");
        categoryBox.setValue(occurrence.getCategory());

        Label descLabel = new Label("Description:");
        descLabel.getStyleClass().add("form-label");
        TextField descField = new TextField(occurrence.getDescription() != null ? occurrence.getDescription() : "");
        descField.getStyleClass().add("text-field");

        Label errorLabel = new Label();
        errorLabel.getStyleClass().add("error-label");

        Button confirmBtn = new Button("Save Occurrence");
        confirmBtn.getStyleClass().add("success-button");
        confirmBtn.setOnAction(e -> {
            Double parsed = UIUtils.parseAmount(amountField.getText());
            if (parsed == null) {
                errorLabel.setText("Enter a valid amount");
                return;
            }
            double amount = parsed;
            if (amount <= 0) {
                errorLabel.setText("Amount must be positive");
                return;
            }
            String category = categoryBox.getValue();
            if (category == null || category.trim().isEmpty()) {
                errorLabel.setText("Please select a category");
                return;
            }
            try {
                state.getManager().editOccurrence(occurrence, amount, category, descField.getText().trim());
                state.saveRecurringOverrides();
            } catch (Exception ex) {
                errorLabel.setText("Failed to save: " + ex.getMessage());
                return;
            }
            state.syncExpenseList();
            state.requestRefresh();
            showMsg("Occurrence updated", false);
            dialog.close();
        });

        Button cancelBtn = new Button("Cancel");
        cancelBtn.getStyleClass().add("danger-button");
        cancelBtn.setOnAction(e -> dialog.close());

        HBox buttons = new HBox(10, confirmBtn, cancelBtn);
        buttons.setAlignment(Pos.CENTER_RIGHT);

        VBox content = new VBox(12, header, note, amountLabel, amountField,
                categoryLabel, categoryBox, descLabel, descField, errorLabel, buttons);
        content.setPadding(new Insets(20));
        content.getStyleClass().add("root-pane");

        Scene scene = new Scene(content, 420, 440);
        scene.getStylesheets().add(getClass().getResource("/styles.css").toExternalForm());
        dialog.setScene(scene);
        dialog.showAndWait();
    }

    private void showMakeRecurringDialog(Expense expense) {
        Stage dialog = new Stage();
        dialog.initModality(Modality.WINDOW_MODAL);
        dialog.initOwner(state.getStage());
        dialog.setTitle("Make Recurring");

        Label header = new Label(String.format("Convert \"%s\" (%s) to recurring",
                expense.getDescription() != null && !expense.getDescription().isEmpty()
                        ? expense.getDescription() : expense.getCategory(),
                fmt(expense.getAmount())));
        header.getStyleClass().add("section-title");
        header.setWrapText(true);

        Label freqLabel = new Label("Frequency:");
        freqLabel.getStyleClass().add("form-label");
        ComboBox<RecurrenceType> freqCombo = new ComboBox<>(
                FXCollections.observableArrayList(RecurrenceType.values()));
        freqCombo.setPromptText("Select frequency...");
        freqCombo.setMaxWidth(Double.MAX_VALUE);
        freqCombo.getStyleClass().add("combo-box");

        Label endLabel = new Label("End Date (optional):");
        endLabel.getStyleClass().add("form-label");
        DatePicker endDatePicker = new DatePicker();
        endDatePicker.setMaxWidth(Double.MAX_VALUE);
        endDatePicker.getStyleClass().add("date-picker");
        endDatePicker.setPromptText("No end date");

        Label note = new Label("This transaction stays as it is. Pick how often it repeats to see "
            + "when the series starts.");
        note.getStyleClass().add("form-label");
        note.setWrapText(true);
        freqCombo.valueProperty().addListener((o, was, freq) -> {
            if (freq == null) return;
            LocalDate start = RecurringController.makeRecurringStart(expense, freq, state.getManager().getExpenses());
            note.setText("This transaction stays as it is. The series starts on "
                + start.format(DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH)) + ".");
        });

        Label errorLabel = new Label();
        errorLabel.getStyleClass().add("error-label");
        errorLabel.setWrapText(true);

        Button confirmBtn = new Button("Make Recurring");
        confirmBtn.getStyleClass().add("success-button");
        confirmBtn.setOnAction(e -> {
            RecurrenceType freq = freqCombo.getValue();
            if (freq == null) {
                errorLabel.setText("Please select a frequency");
                return;
            }
            LocalDate endDate = endDatePicker.getValue();

            // Keep this transaction as it is and start the series at the next one, so nothing is
            // counted twice. For an imported one, also past everything imported so far: those
            // months come from the statements, not from the series.
            LocalDate start = RecurringController.makeRecurringStart(expense, freq, state.getManager().getExpenses());
            if (endDate != null && endDate.isBefore(start)) {
                errorLabel.setText("The end date must be on or after the start, "
                    + start.format(DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH)) + ".");
                return;
            }
            RecurringExpense recurring = RecurringController.recurringFrom(expense, freq, endDate, start);
            try {
                state.getManager().executeCommand(new AddExpenseCommand(state.getManager(), recurring));
            } catch (IllegalArgumentException ex) {
                errorLabel.setText(ex.getMessage());
                return;
            }
            try {
                state.getManager().generateRecurringExpenses(LocalDate.now());
                state.saveExpenses();
                state.syncRecurringList();
            } catch (IOException ex) {
                state.getManager().rollbackLastCommand();
                showMsg("Failed to save: " + ex.getMessage(), true);
                dialog.close();
                return;
            }
            state.requestRefresh();
            showMsg("Expense converted to recurring (" + freq.toString().toLowerCase() + ")", false);
            dialog.close();
        });

        Button cancelBtn = new Button("Cancel");
        cancelBtn.getStyleClass().add("danger-button");
        cancelBtn.setOnAction(e -> dialog.close());

        HBox buttons = new HBox(10, confirmBtn, cancelBtn);
        buttons.setAlignment(Pos.CENTER_RIGHT);

        VBox content = new VBox(12, header, freqLabel, freqCombo, endLabel, endDatePicker, note, errorLabel, buttons);
        content.setPadding(new Insets(20));
        content.getStyleClass().add("root-pane");

        Scene scene = new Scene(content, 400, 420);
        scene.getStylesheets().add(getClass().getResource("/styles.css").toExternalForm());
        dialog.setScene(scene);
        dialog.showAndWait();
    }

    // ======================== PUBLIC HELPERS ========================

    public void focusAddForm() {
        if (!addExpensePane.isVisible()) handleToggleAddForm();
        Platform.runLater(() -> amountField.requestFocus());
    }

    public void focusSearch() {
        Platform.runLater(() -> searchField.requestFocus());
    }

    public ObservableList<Expense> getTableItems() {
        return expenseTable.getItems();
    }

    /**
     * Captures the current sort as "columnKey\tSortType" (empty string if unsorted), for persistence.
     * Keys on a stable column identifier (fx:id, falling back to header text) so it survives column reordering.
     */
    public String getSortState() {
        if (expenseTable.getSortOrder().isEmpty()) return "";
        TableColumn<Expense, ?> col = expenseTable.getSortOrder().get(0);
        String key = columnKey(col);
        return key.isEmpty() ? "" : key + "\t" + col.getSortType();
    }

    /** Restores a sort previously captured by {@link #getSortState()}; ignores malformed/stale input. */
    public void applySortState(String state) {
        if (state == null || state.isBlank()) return;
        try {
            int sep = state.lastIndexOf('\t');
            if (sep < 0) return; // unrecognised (e.g. older index-based) format — keep default order
            String key = state.substring(0, sep);
            TableColumn.SortType type = TableColumn.SortType.valueOf(state.substring(sep + 1));
            for (TableColumn<Expense, ?> col : expenseTable.getColumns()) {
                if (key.equals(columnKey(col))) {
                    col.setSortType(type);
                    expenseTable.getSortOrder().setAll(col);
                    return;
                }
            }
        } catch (Exception ignored) { /* stale/invalid state — keep default order */ }
    }

    /** Stable identifier for a column: its fx:id if set, otherwise its header text. */
    private static String columnKey(TableColumn<Expense, ?> col) {
        if (col.getId() != null && !col.getId().isEmpty()) return col.getId();
        return col.getText() == null ? "" : col.getText();
    }

    // ======================== PRIVATE HELPERS ========================

    private void resetExpenseForm() {
        amountField.clear();
        categoryCombo.setValue(null);
        categoryCombo.getEditor().clear();
        datePicker.setValue(LocalDate.now());
        descriptionField.clear();
        amountField.requestFocus();
    }

    private String fmt(double amount) {
        return UIUtils.fmt(amount, state.getCurrencySymbol());
    }

    private void showMsg(String message, boolean isError) {
        UIUtils.showMessage(message, isError, expenseErrorLabel);
    }
}
