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

    private SharedState state;
    private RecurringExpense selectedRecurringExpense;
    private boolean initialized = false;

    @FXML
    public void initialize() {
        // Real setup happens in init(SharedState)
    }

    public void init(SharedState state) {
        this.state = state;
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

        // Empty state for recurring table
        VBox recurringEmptyState = new VBox(6);
        recurringEmptyState.setAlignment(Pos.CENTER);
        Label recurringMsg = new Label("No recurring expenses yet.");
        recurringMsg.getStyleClass().add("empty-state-label");
        Label recurringHint = new Label("Add one above, or use \"Detect Recurring Patterns\" to find them automatically.");
        recurringHint.getStyleClass().add("empty-state-hint");
        recurringEmptyState.getChildren().addAll(recurringMsg, recurringHint);
        recurringTable.setPlaceholder(recurringEmptyState);

        // Disable "Add Recurring Expense" until the amount is a valid positive number
        UIUtils.bindPositiveAmountValidation(addRecurringAmountField, addRecurringButton);

        // Enter in the amount or description field submits the form
        UIUtils.submitOnEnter(addRecurringButton, addRecurringAmountField, addRecurringDescField);

        // Selection listener: populate edit form when recurring expense selected
        recurringTable.getSelectionModel().selectedItemProperty().addListener((obs, oldSelection, newSelection) -> {
            if (newSelection != null) {
                selectedRecurringExpense = newSelection;
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

        // Date picker default to today
        addRecurringDatePicker.setValue(LocalDate.now());
    }

    public void refresh() {
        // Minimal — list is already synced via SharedState
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
            showMsg("Recurring expense added successfully!", false);
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
            state.getManager().executeCommand(new UpdateRecurringExpenseCommand(state.getManager(), selectedRecurringExpense, newExpense));
            try {
                state.saveExpenses();
                state.syncRecurringList();
                state.requestRefresh();
                clearEditRecurringForm();
                updateRecurringButton.setDisable(true);
                showMsgOn("Recurring expense updated successfully!", false, editRecurringErrorLabel);
            } catch (Exception ex) {
                showMsgOn("Error updating recurring expense: " + ex.getMessage(), true, editRecurringErrorLabel);
            }
        } catch (NumberFormatException ex) {
            showMsgOn("Invalid amount: Please enter a valid number (e.g., 10.99)", true, editRecurringErrorLabel);
        } catch (IllegalArgumentException ex) {
            showMsgOn(ex.getMessage(), true, editRecurringErrorLabel);
        }
    }

    @FXML
    private void handleDeleteRecurring() {
        RecurringExpense selected = recurringTable.getSelectionModel().getSelectedItem();
        if (selected == null) {
            showMsgOn("Please select a recurring expense to delete", true, editRecurringErrorLabel);
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
                clearEditRecurringForm();
                updateRecurringButton.setDisable(true);
                showMsgOn("Recurring expense deleted successfully!", false, editRecurringErrorLabel);
            } catch (Exception ex) {
                showMsgOn("Error deleting recurring expense: " + ex.getMessage(), true, editRecurringErrorLabel);
            }
        }
    }

    @FXML
    private void handleDetectRecurring() {
        RecurringPatternDetector detector = new RecurringPatternDetector();
        List<RecurringPatternDetector.DetectedPattern> patterns = detector.detectPatterns(
            state.getManager().getExpenses(), state.getManager().getBaseRecurringExpenses());

        if (patterns.isEmpty()) {
            showMsg("No recurring patterns detected in your expenses.", false);
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
        Button selectAllBtn = new Button("Select All");
        selectAllBtn.getStyleClass().add("primary-button");
        selectAllBtn.setOnAction(e -> patterns.forEach(p -> p.setSelected(true)));

        Button deselectAllBtn = new Button("Deselect All");
        deselectAllBtn.getStyleClass().add("primary-button");
        deselectAllBtn.setOnAction(e -> patterns.forEach(p -> p.setSelected(false)));

        HBox selectionButtons = new HBox(10, selectAllBtn, deselectAllBtn);

        CheckBox removeOriginals = new CheckBox("Remove original one-time expenses after conversion");
        removeOriginals.setSelected(true);
        removeOriginals.getStyleClass().add("form-label");

        Label statusLabel = new Label();
        statusLabel.getStyleClass().add("error-label");

        Button convertBtn = new Button("Convert Selected to Recurring");
        convertBtn.getStyleClass().add("success-button");
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
                    for (Expense original : pattern.getMatchingExpenses()) {
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
                showMsg("Failed to save: " + ex.getMessage(), true);
                return;
            }

            int converted = selected.size();
            showMsg(converted + " recurring " + (converted == 1 ? "expense" : "expenses")
                + " created successfully!", false);
            dialog.close();
        });

        Button cancelBtn = new Button("Cancel");
        cancelBtn.getStyleClass().add("danger-button");
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
        textArea.setStyle("-fx-font-family: monospace; -fx-font-size: 13px; -fx-control-inner-background: #2A2A2A; -fx-text-fill: #E0E0E0;");
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
        addRecurringFreqCombo.setValue(null);
        addRecurringEndDatePicker.setValue(null);
        Platform.runLater(() -> addRecurringAmountField.requestFocus());
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
