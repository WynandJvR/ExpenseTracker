package com.wyn.expensetracker;

import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.fxml.FXML;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.control.cell.CheckBoxTableCell;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;

import java.io.IOException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

public class RecurringController {

    // --- Add Recurring Form ---
    @FXML private TextField addRecurringAmountField;
    @FXML private ComboBox<String> addRecurringCategoryCombo;
    @FXML private DatePicker addRecurringDatePicker;
    @FXML private TextField addRecurringDescField;
    @FXML private ComboBox<RecurrenceType> addRecurringFreqCombo;
    @FXML private DatePicker addRecurringEndDatePicker;

    // --- Recurring Table ---
    @FXML private TableView<RecurringExpense> recurringTable;

    // --- Edit Recurring Form ---
    @FXML private TextField editRecurringAmountField;
    @FXML private ComboBox<String> editRecurringCategoryCombo;
    @FXML private DatePicker editRecurringDatePicker;
    @FXML private TextField editRecurringDescField;
    @FXML private ComboBox<RecurrenceType> editRecurringFreqCombo;
    @FXML private DatePicker editRecurringEndDatePicker;
    @FXML private Button updateRecurringButton;
    @FXML private Button addRecurringButton;

    // --- Labels ---
    @FXML private Label addRecurringErrorLabel;
    @FXML private Label editRecurringErrorLabel;
    @FXML private Label recurringListErrorLabel;
    @FXML private Label recurringCountLabel;
    @FXML private Label editRecurringTitle;

    // --- Layout ---
    @FXML private TitledPane addRecurringPane;
    @FXML private VBox editRecurringBox;
    @FXML private VBox suggestionBox;
    @FXML private Button deleteRecurringButton;
    @FXML private TableColumn<RecurringExpense, Double> recurringAmountColumn;
    @FXML private TableColumn<RecurringExpense, LocalDate> recurringStartColumn;
    @FXML private TableColumn<RecurringExpense, LocalDate> recurringEndColumn;

    private SharedState state;
    private RecurringExpense selectedRecurringExpense;
    private boolean initialized = false;

    /** Patterns found silently in imported statements; cached until the data changes. */
    private List<RecurringPatternDetector.DetectedPattern> suggestedPatterns = List.of();
    private int suggestionKey = -1;
    private boolean suggestionDismissed = false;

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("dd MMM yyyy");

    @FXML
    public void initialize() {
        // Real setup happens in init(SharedState)
    }

    public void init(SharedState state) {
        this.state = state;
        // Profile switch: forget suggestions computed for the previous profile's data
        suggestionKey = -1;
        suggestionDismissed = false;
        if (initialized) return;
        initialized = true;

        // Category combos
        addRecurringCategoryCombo.setItems(state.getCategories());
        addRecurringCategoryCombo.setEditable(true);
        editRecurringCategoryCombo.setItems(state.getCategories());
        editRecurringCategoryCombo.setEditable(true);
        UIUtils.setupComboCellFactory(addRecurringCategoryCombo);
        UIUtils.setupComboCellFactory(editRecurringCategoryCombo);

        // Frequency combos
        addRecurringFreqCombo.setItems(FXCollections.observableArrayList(RecurrenceType.values()));
        editRecurringFreqCombo.setItems(FXCollections.observableArrayList(RecurrenceType.values()));
        UIUtils.setupComboCellFactory(addRecurringFreqCombo);
        UIUtils.setupComboCellFactory(editRecurringFreqCombo);

        // Recurring table
        recurringTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        recurringTable.setItems(state.getRecurringList());

        recurringAmountColumn.setCellFactory(col -> new TableCell<RecurringExpense, Double>() {
            @Override
            protected void updateItem(Double item, boolean empty) {
                super.updateItem(item, empty);
                RecurringExpense r = empty || getTableRow() == null ? null : getTableRow().getItem();
                setText(r == null || item == null ? null : (r.isIncome() ? "+" : "") + fmt(item));
            }
        });
        recurringStartColumn.setCellFactory(col -> dateCell());
        recurringEndColumn.setCellFactory(col -> dateCell());

        // Empty state for recurring table
        VBox recurringEmptyState = new VBox(6);
        recurringEmptyState.setAlignment(Pos.CENTER);
        Label recurringMsg = new Label("Nothing recurring yet.");
        recurringMsg.getStyleClass().add("empty-state-label");
        Label recurringHint = new Label("Use \"Find them in my statements\" to pick up debit orders and subscriptions, or \"+ Add\" to enter one.");
        recurringHint.getStyleClass().add("empty-state-hint");
        recurringEmptyState.getChildren().addAll(recurringMsg, recurringHint);
        recurringTable.setPlaceholder(recurringEmptyState);

        // Disable "Add Recurring Expense" until the amount is a valid positive number
        UIUtils.bindPositiveAmountValidation(addRecurringAmountField, addRecurringButton);

        // Enter in the amount or description field submits the form
        UIUtils.submitOnEnter(addRecurringButton, addRecurringAmountField, addRecurringDescField);

        // Selection listener: the edit form is shown, filled in, only while a row is selected
        recurringTable.getSelectionModel().selectedItemProperty().addListener((obs, oldSelection, newSelection) -> {
            setShown(editRecurringBox, newSelection != null);
            deleteRecurringButton.setDisable(newSelection == null);
            showMsgOn("", false, editRecurringErrorLabel);
            if (newSelection != null) {
                selectedRecurringExpense = newSelection;
                String desc = newSelection.getDescription();
                editRecurringTitle.setText(desc == null || desc.isBlank()
                    ? "Edit recurring item" : "Edit \u201c" + desc.trim() + "\u201d");
                editRecurringAmountField.setText(String.valueOf(newSelection.getAmount()));
                editRecurringCategoryCombo.setValue(newSelection.getCategory());
                editRecurringDatePicker.setValue(newSelection.getDate());
                editRecurringDescField.setText(newSelection.getDescription());
                editRecurringFreqCombo.setValue(newSelection.getFrequency());
                editRecurringEndDatePicker.setValue(newSelection.getEndDate());
                updateRecurringButton.setDisable(false);
            } else {
                selectedRecurringExpense = null;
                clearEditRecurringForm();
                updateRecurringButton.setDisable(true);
            }
        });

        // Date picker default to today; most bills are monthly
        addRecurringDatePicker.setValue(LocalDate.now());
        addRecurringFreqCombo.setValue(RecurrenceType.MONTHLY);

        state.getRecurringList().addListener((javafx.collections.ListChangeListener<RecurringExpense>) c -> updateCount());
        updateCount();
    }

    public void refresh() {
        if (state == null) return;
        updateCount();
        updateSuggestion();
    }

    private TableCell<RecurringExpense, LocalDate> dateCell() {
        return new TableCell<>() {
            @Override
            protected void updateItem(LocalDate item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty ? null : item == null ? "\u2014" : item.format(DATE_FMT));
            }
        };
    }

    private void updateCount() {
        int n = state.getRecurringList().size();
        recurringCountLabel.setText(n == 0 ? "" : n + (n == 1 ? " item" : " items"));
    }

    private static void setShown(javafx.scene.Node node, boolean shown) {
        node.setVisible(shown);
        node.setManaged(shown);
    }

    /**
     * When nothing recurring is set up yet but statements have been imported, quietly look for
     * repeating payments and offer them for review. Nothing is added until the user confirms.
     */
    private void updateSuggestion() {
        List<Expense> all = state.getManager().getExpenses();
        boolean hasImports = all.stream().anyMatch(e -> e.getImportId() != null);
        if (!state.getRecurringList().isEmpty() || !hasImports || suggestionDismissed) {
            suggestionBox.getChildren().clear();
            setShown(suggestionBox, false);
            return;
        }
        // Keyed on the rows' content, so recategorising, marking a transfer or an inline
        // edit (which replaces the row) all invalidate the cached patterns.
        int key = ledgerSignature(all);
        if (key != suggestionKey) {
            suggestionKey = key;
            try {
                suggestedPatterns = detectNow();
            } catch (RuntimeException ex) {
                suggestedPatterns = List.of();
            }
        }
        suggestionBox.getChildren().clear();
        if (suggestedPatterns.isEmpty()) {
            suggestionBox.getChildren().add(notice(
                "We can look for debit orders and subscriptions in your statements",
                "Nothing obvious turned up yet. Try again after importing a few months of statements.",
                "Find them", this::handleDetectRecurring));
        } else {
            int n = suggestedPatterns.size();
            List<String> names = suggestedPatterns.stream()
                .map(RecurringPatternDetector.DetectedPattern::getDescription)
                .filter(d -> d != null && !d.isBlank())
                .map(d -> UIUtils.truncate(d.trim(), 28))
                .distinct().limit(2)
                .collect(Collectors.toList());
            String title = "Found " + n + " likely repeating payment" + (n == 1 ? "" : "s")
                + (names.isEmpty() ? "" : " (e.g. " + String.join(", ", names) + ")");
            suggestionBox.getChildren().add(notice(title,
                "Review them and tick the ones to track. Nothing is added until you confirm.",
                "Review", this::handleDetectRecurring));
        }
        setShown(suggestionBox, true);
    }

    /** Only spend rows can become a recurring bill: excluded, income and refund rows are left out. */
    static List<Expense> detectionInput(List<Expense> all) {
        return all.stream()
            .filter(e -> e != null && !e.isExcluded() && !e.isIncome() && !e.isRefund())
            .collect(Collectors.toList());
    }

    /** Runs pattern detection on the current ledger. */
    private List<RecurringPatternDetector.DetectedPattern> detectNow() {
        return new RecurringPatternDetector().detectPatterns(
            detectionInput(state.getManager().getExpenses()), state.getManager().getBaseRecurringExpenses());
    }

    /** Cheap content signature of the ledger rows that detection depends on. */
    static int ledgerSignature(List<Expense> all) {
        int h = all.size();
        for (Expense e : all) {
            if (e == null) continue;
            h = 31 * h + System.identityHashCode(e);
            h = 31 * h + java.util.Objects.hash(e.getDescription(), e.getCategory(), e.isExcluded(),
                e.isIncome(), e.isRefund(), e.getAmount(), e.getDate());
        }
        return h;
    }

    /**
     * The originals of a pattern that may be removed after converting it: rows still in
     * the ledger (by identity, so rows replaced by an edit are not "deleted" and later
     * re-added by undo) and still plain spend (not since marked as a transfer/income/refund).
     */
    static List<Expense> removableOriginals(RecurringPatternDetector.DetectedPattern pattern, List<Expense> ledger) {
        java.util.Set<Expense> present = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        present.addAll(ledger);
        return pattern.getMatchingExpenses().stream()
            .filter(present::contains)
            .filter(e -> !e.isExcluded() && !e.isIncome() && !e.isRefund())
            .collect(Collectors.toList());
    }

    private HBox notice(String title, String body, String actionText, Runnable action) {
        Label icon = new Label("\u2139");
        icon.getStyleClass().addAll("notice-icon", "notice-icon-info");
        Label t = new Label(title);
        t.getStyleClass().add("notice-title");
        t.setWrapText(true);
        Label b = new Label(body);
        b.getStyleClass().add("notice-body");
        b.setWrapText(true);
        VBox text = new VBox(2, t, b);
        HBox.setHgrow(text, Priority.ALWAYS);
        Button btn = new Button(actionText);
        btn.getStyleClass().add("secondary-button");
        btn.setOnAction(e -> action.run());
        Button dismiss = new Button("Dismiss");
        dismiss.getStyleClass().add("ghost-button");
        dismiss.setOnAction(e -> {
            suggestionDismissed = true;
            updateSuggestion();
        });
        HBox box = new HBox(12, icon, text, btn, dismiss);
        box.setAlignment(Pos.CENTER_LEFT);
        box.getStyleClass().addAll("notice", "notice-info");
        return box;
    }

    @FXML
    private void handleShowAddForm() {
        setShown(addRecurringPane, true);
        addRecurringPane.setExpanded(true);
        Platform.runLater(() -> addRecurringAmountField.requestFocus());
    }

    @FXML
    private void handleHideAddForm() {
        addRecurringPane.setExpanded(false);
        setShown(addRecurringPane, false);
        showMsg("", false);
    }

    @FXML
    private void handleCancelEdit() {
        recurringTable.getSelectionModel().clearSelection();
    }

    @FXML
    private void handleAddRecurring() {
        try {
            double amount = Double.parseDouble(addRecurringAmountField.getText());
            if (amount <= 0) {
                showMsg("Amount must be positive", true);
                return;
            }
            String category = addRecurringCategoryCombo.getValue();
            if (category == null || category.trim().isEmpty()) {
                category = addRecurringCategoryCombo.getEditor() != null
                    ? addRecurringCategoryCombo.getEditor().getText().trim() : "";
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
            LocalDate date = addRecurringDatePicker.getValue();
            if (date == null) {
                showMsg("Please select a start date", true);
                return;
            }
            String description = addRecurringDescField.getText().trim();
            RecurrenceType frequency = addRecurringFreqCombo.getValue();
            if (frequency == null) {
                showMsg("Please select a recurrence frequency", true);
                return;
            }
            LocalDate endDate = addRecurringEndDatePicker.getValue();

            RecurringExpense expense = new RecurringExpense(amount, category, date,
                description.isEmpty() ? "" : description, frequency, endDate);
            state.getManager().executeCommand(new AddExpenseCommand(state.getManager(), expense));
            try {
                state.saveExpenses();
                state.getManager().generateRecurringExpenses(LocalDate.now());
                state.saveExpenses();
                state.syncRecurringList();
            } catch (Exception ex) {
                state.getManager().rollbackLastCommand();
                showMsg("Failed to save recurring expense: " + ex.getMessage(), true);
                return;
            }
            state.requestRefresh();
            resetRecurringForm();
            handleHideAddForm();
            showMsg("Recurring item added", false);
        } catch (NumberFormatException ex) {
            showMsg("Invalid amount: Please enter a valid number (e.g., 10.99)", true);
        } catch (Exception ex) {
            showMsg("Error: " + ex.getMessage(), true);
        }
    }

    @FXML
    private void handleUpdateRecurring() {
        if (selectedRecurringExpense == null) {
            showMsgOn("Please select a recurring expense to update", true, editRecurringErrorLabel);
            return;
        }

        try {
            double amount = Double.parseDouble(editRecurringAmountField.getText());
            if (amount <= 0) {
                showMsgOn("Amount must be positive", true, editRecurringErrorLabel);
                return;
            }
            String category = editRecurringCategoryCombo.getValue();
            if (category == null || category.trim().isEmpty()) {
                showMsgOn("Category cannot be empty", true, editRecurringErrorLabel);
                return;
            }
            LocalDate date = editRecurringDatePicker.getValue();
            if (date == null) {
                showMsgOn("Please select a start date", true, editRecurringErrorLabel);
                return;
            }
            String description = editRecurringDescField.getText().trim();
            RecurrenceType frequency = editRecurringFreqCombo.getValue();
            if (frequency == null) {
                showMsgOn("Please select a recurrence frequency", true, editRecurringErrorLabel);
                return;
            }
            LocalDate endDate = editRecurringEndDatePicker.getValue();

            RecurringExpense newExpense = new RecurringExpense(amount, category, date, description, frequency, endDate);
            copyNonFormFields(selectedRecurringExpense, newExpense);
            state.getManager().executeCommand(new UpdateRecurringExpenseCommand(state.getManager(), selectedRecurringExpense, newExpense));
            try {
                state.saveExpenses();
                state.syncRecurringList();
                state.requestRefresh();
                recurringTable.getSelectionModel().clearSelection();
                clearEditRecurringForm();
                updateRecurringButton.setDisable(true);
                showMsgOn("Recurring item updated", false, recurringListErrorLabel);
            } catch (Exception ex) {
                showMsgOn("Error updating recurring expense: " + ex.getMessage(), true, editRecurringErrorLabel);
            }
        } catch (NumberFormatException ex) {
            showMsgOn("Invalid amount: Please enter a valid number (e.g., 10.99)", true, editRecurringErrorLabel);
        } catch (IllegalArgumentException ex) {
            showMsgOn(ex.getMessage(), true, editRecurringErrorLabel);
        }
    }

    /**
     * Carries over the fields the edit form does not expose (income/refund/excluded
     * flags, currency, tags, import id, receipt) so editing amount/date/etc. doesn't
     * silently turn a salary into an expense or drop its currency.
     */
    static void copyNonFormFields(RecurringExpense from, RecurringExpense to) {
        if (from == null || to == null) return;
        to.setIncome(from.isIncome());
        to.setRefund(from.isRefund());
        to.setExcluded(from.isExcluded());
        to.setCurrency(from.getCurrency());
        to.setTags(from.getTags());
        to.setImportId(from.getImportId());
        to.setReceiptPath(from.getReceiptPath());
    }

    @FXML
    private void handleDeleteRecurring() {
        RecurringExpense selected = recurringTable.getSelectionModel().getSelectedItem();
        if (selected == null) {
            showMsgOn("Select a recurring item to delete", true, recurringListErrorLabel);
            return;
        }

        long generatedCount = state.getManager().getExpenses().stream()
            .filter(e -> e.getSourceRecurringExpense() == selected)
            .count();
        Alert confirmation = new Alert(Alert.AlertType.CONFIRMATION);
        confirmation.initOwner(state.getStage());
        confirmation.setTitle("Confirm Deletion");
        confirmation.setHeaderText(null);
        confirmation.setContentText(String.format(
            "Are you sure you want to delete this recurring expense?\nThis will also remove %d generated expenses.", generatedCount));
        confirmation.getDialogPane().getStylesheets().add(getClass().getResource("/styles.css").toExternalForm());

        Optional<ButtonType> result = confirmation.showAndWait();
        if (result.isPresent() && result.get() == ButtonType.OK) {
            state.getManager().executeCommand(new DeleteRecurringExpenseCommand(state.getManager(), selected));
            try {
                state.saveExpenses();
                state.syncRecurringList();
                state.requestRefresh();
                recurringTable.getSelectionModel().clearSelection();
                clearEditRecurringForm();
                updateRecurringButton.setDisable(true);
                showMsgOn("Recurring item deleted", false, recurringListErrorLabel);
            } catch (Exception ex) {
                showMsgOn("Error deleting recurring item: " + ex.getMessage(), true, recurringListErrorLabel);
            }
        }
    }

    @FXML
    private void handleDetectRecurring() {
        // Always re-detected from the current ledger (never a stale cached list).
        List<RecurringPatternDetector.DetectedPattern> patterns = detectNow();

        if (patterns.isEmpty()) {
            showMsgOn("No repeating payments found in your transactions yet.", false, recurringListErrorLabel);
            return;
        }

        showDetectedPatternsDialog(patterns);
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
                addRecurringCategoryCombo.setValue(category);
                editRecurringCategoryCombo.setValue(category);
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
        String selectedCategory = addRecurringCategoryCombo.getValue();
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
        addRecurringCategoryCombo.setValue(null);
        editRecurringCategoryCombo.setValue(null);

        try {
            state.getStorage().saveCategories(state.getCategories());
            showMsg("", false);
        } catch (Exception ex) {
            state.getCategories().add(selectedCategory);
            showMsg("Error saving categories: " + ex.getMessage(), true);
        }
    }

    private void showDetectedPatternsDialog(List<RecurringPatternDetector.DetectedPattern> patterns) {
        Stage dialog = new Stage();
        dialog.initModality(Modality.WINDOW_MODAL);
        dialog.initOwner(state.getStage());
        dialog.setTitle("Detected Recurring Patterns");

        Label header = new Label("We found " + patterns.size() + " potential recurring "
            + (patterns.size() == 1 ? "expense" : "expenses") + " in your history.");
        header.getStyleClass().add("section-title");
        header.setWrapText(true);

        Label subtitle = new Label("Select the ones you'd like to convert to recurring expenses.");
        subtitle.getStyleClass().add("form-label");
        subtitle.setWrapText(true);

        // Build a table of detected patterns with checkboxes
        TableView<RecurringPatternDetector.DetectedPattern> patternTable = new TableView<>();
        patternTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        patternTable.setEditable(true);

        TableColumn<RecurringPatternDetector.DetectedPattern, Boolean> selectCol = new TableColumn<>("");
        selectCol.setCellValueFactory(cd -> cd.getValue().selectedProperty());
        selectCol.setCellFactory(CheckBoxTableCell.forTableColumn(selectCol));
        selectCol.setEditable(true);
        selectCol.setPrefWidth(40);

        TableColumn<RecurringPatternDetector.DetectedPattern, String> descCol = new TableColumn<>("Description");
        descCol.setCellValueFactory(new PropertyValueFactory<>("description"));
        descCol.setPrefWidth(150);

        TableColumn<RecurringPatternDetector.DetectedPattern, String> catCol = new TableColumn<>("Category");
        catCol.setCellValueFactory(new PropertyValueFactory<>("category"));
        catCol.setPrefWidth(100);

        TableColumn<RecurringPatternDetector.DetectedPattern, Double> amtCol = new TableColumn<>("Avg Amount");
        amtCol.setCellValueFactory(new PropertyValueFactory<>("averageAmount"));
        amtCol.setPrefWidth(90);
        amtCol.setCellFactory(tc -> new TableCell<RecurringPatternDetector.DetectedPattern, Double>() {
            @Override
            protected void updateItem(Double item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : fmt(item));
            }
        });

        TableColumn<RecurringPatternDetector.DetectedPattern, RecurrenceType> freqCol = new TableColumn<>("Frequency");
        freqCol.setCellValueFactory(new PropertyValueFactory<>("frequency"));
        freqCol.setPrefWidth(80);

        TableColumn<RecurringPatternDetector.DetectedPattern, Integer> countCol = new TableColumn<>("Occurrences");
        countCol.setCellValueFactory(new PropertyValueFactory<>("occurrences"));
        countCol.setPrefWidth(80);

        TableColumn<RecurringPatternDetector.DetectedPattern, LocalDate> dateCol = new TableColumn<>("First Seen");
        dateCol.setCellValueFactory(new PropertyValueFactory<>("earliestDate"));
        dateCol.setPrefWidth(100);

        patternTable.getColumns().addAll(selectCol, descCol, catCol, amtCol, freqCol, countCol, dateCol);
        patternTable.getItems().addAll(patterns);
        patternTable.setPrefHeight(Math.min(300, 50 + patterns.size() * 30));

        // Select all / deselect all
        Button selectAllBtn = new Button("Select all");
        selectAllBtn.getStyleClass().add("ghost-button");
        selectAllBtn.setOnAction(e -> patterns.forEach(p -> p.setSelected(true)));

        Button deselectAllBtn = new Button("Select none");
        deselectAllBtn.getStyleClass().add("ghost-button");
        deselectAllBtn.setOnAction(e -> patterns.forEach(p -> p.setSelected(false)));

        HBox selectionButtons = new HBox(10, selectAllBtn, deselectAllBtn);

        CheckBox removeOriginals = new CheckBox("Remove original one-time expenses after conversion");
        removeOriginals.setSelected(true);
        removeOriginals.getStyleClass().add("form-label");

        Label statusLabel = new Label();
        statusLabel.getStyleClass().add("error-label");

        Button convertBtn = new Button("Track selected as recurring");
        convertBtn.getStyleClass().add("accent-button");
        convertBtn.setOnAction(e -> {
            List<RecurringPatternDetector.DetectedPattern> selected = patterns.stream()
                .filter(RecurringPatternDetector.DetectedPattern::isSelected)
                .collect(Collectors.toList());
            if (selected.isEmpty()) {
                statusLabel.setText("Please select at least one pattern.");
                statusLabel.getStyleClass().setAll("error-label", "error-message");
                return;
            }

            int commandCount = 0;
            for (RecurringPatternDetector.DetectedPattern pattern : selected) {
                RecurringExpense recurring = new RecurringExpense(
                    pattern.getAverageAmount(),
                    pattern.getCategory(),
                    pattern.getEarliestDate(),
                    pattern.getDescription() != null ? pattern.getDescription() : "",
                    pattern.getFrequency(),
                    null
                );
                state.getManager().executeCommand(new AddExpenseCommand(state.getManager(), recurring));
                commandCount++;

                if (removeOriginals.isSelected()) {
                    for (Expense original : removableOriginals(pattern, state.getManager().getExpenses())) {
                        state.getManager().executeCommand(new DeleteExpenseCommand(state.getManager(), original));
                        commandCount++;
                    }
                }
            }

            try {
                state.getManager().generateRecurringExpenses(LocalDate.now());
                state.saveExpenses();
                state.syncRecurringList();
                state.requestRefresh();
            } catch (IOException ex) {
                for (int i = 0; i < commandCount; i++) {
                    state.getManager().rollbackLastCommand();
                }
                statusLabel.setText("Failed to save: " + ex.getMessage());
                statusLabel.getStyleClass().setAll("error-label", "error-message");
                return;
            }

            int converted = selected.size();
            showMsgOn(converted + " recurring " + (converted == 1 ? "item" : "items") + " added",
                false, recurringListErrorLabel);
            dialog.close();
        });

        Button cancelBtn = new Button("Cancel");
        cancelBtn.getStyleClass().add("ghost-button");
        cancelBtn.setOnAction(e -> dialog.close());

        // Copy All button
        Button copyAllBtn = new Button("Copy All");
        copyAllBtn.getStyleClass().add("secondary-button");
        copyAllBtn.setTooltip(new Tooltip("Copy all detected patterns as text (Ctrl+Shift+C)"));
        copyAllBtn.setOnAction(e -> showCopyablePatterns(patterns));

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        HBox actionButtons = new HBox(10, convertBtn, cancelBtn, spacer, copyAllBtn);
        actionButtons.setAlignment(Pos.CENTER_RIGHT);

        VBox content = new VBox(12, header, subtitle, patternTable, selectionButtons,
            removeOriginals, statusLabel, actionButtons);
        content.setPadding(new Insets(20));
        content.getStyleClass().add("root-pane");

        Scene scene = new Scene(content, 700, 500);
        scene.getStylesheets().add(getClass().getResource("/styles.css").toExternalForm());
        scene.addEventFilter(KeyEvent.KEY_PRESSED, ev -> {
            if (ev.isControlDown() && ev.isShiftDown() && ev.getCode() == KeyCode.C) {
                showCopyablePatterns(patterns);
                ev.consume();
            }
        });
        dialog.setScene(scene);
        dialog.showAndWait();
    }

    private void showCopyablePatterns(List<RecurringPatternDetector.DetectedPattern> patterns) {
        StringBuilder sb = new StringBuilder();
        sb.append("Detected Recurring Patterns\n");
        sb.append("===========================\n\n");
        sb.append(String.format("%-30s %-15s %-12s %-12s %-8s %-12s\n",
            "Description", "Category", "Avg Amount", "Frequency", "Count", "First Seen"));
        sb.append("-".repeat(89)).append("\n");
        for (RecurringPatternDetector.DetectedPattern p : patterns) {
            sb.append(String.format("%-30s %-15s %-12s %-12s %-8d %-12s\n",
                UIUtils.truncate(p.getDescription(), 30),
                UIUtils.truncate(p.getCategory(), 15),
                fmt(p.getAverageAmount()),
                p.getFrequency(),
                p.getOccurrences(),
                p.getEarliestDate()));
        }
        sb.append("\n").append(patterns.size()).append(" pattern(s) detected.\n");

        TextArea textArea = new TextArea(sb.toString());
        textArea.setEditable(false);
        textArea.setWrapText(false);
        textArea.getStyleClass().add("import-preview");
        textArea.setPrefHeight(400);
        textArea.setPrefWidth(700);
        textArea.selectAll();

        Alert copyDialog = new Alert(Alert.AlertType.NONE);
        copyDialog.initOwner(state.getStage());
        copyDialog.setTitle("Detected Patterns — Select & Copy");
        copyDialog.setHeaderText("All text is selectable. Use Ctrl+A then Ctrl+C to copy.");
        copyDialog.getDialogPane().setContent(textArea);
        copyDialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        copyDialog.getDialogPane().getStylesheets().add(getClass().getResource("/styles.css").toExternalForm());
        copyDialog.showAndWait();
    }

    private void resetRecurringForm() {
        addRecurringAmountField.clear();
        addRecurringCategoryCombo.setValue(null);
        addRecurringDatePicker.setValue(LocalDate.now());
        addRecurringDescField.clear();
        addRecurringFreqCombo.setValue(RecurrenceType.MONTHLY);
        addRecurringEndDatePicker.setValue(null);
    }

    private void clearEditRecurringForm() {
        editRecurringAmountField.clear();
        editRecurringCategoryCombo.setValue(null);
        editRecurringDatePicker.setValue(null);
        editRecurringDescField.clear();
        editRecurringFreqCombo.setValue(null);
        editRecurringEndDatePicker.setValue(null);
    }

    private void showMsg(String message, boolean isError) {
        UIUtils.showMessage(message, isError, addRecurringErrorLabel);
    }

    private void showMsgOn(String message, boolean isError, Label target) {
        UIUtils.showMessage(message, isError, target);
    }

    private String fmt(double amount) {
        return UIUtils.fmt(amount, state.getCurrencySymbol());
    }
}
