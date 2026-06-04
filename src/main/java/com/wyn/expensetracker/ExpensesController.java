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
        categoryCombo.setItems(state.getCategories());
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
        expenseTable.setTooltip(new Tooltip("Double-click a cell to edit  |  Right-click for more options  |  Press F1 for shortcuts"));
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
                    getStyleClass().removeAll("excluded-row", "income-row", "refund-row");
                    if (empty || item == null) {
                        setOpacity(1.0);
                        setStyle("");
                    } else if (item.isExcluded()) {
                        getStyleClass().add("excluded-row");
                        setOpacity(0.45);
                        setStyle("");
                    } else if (item.isRefund()) {
                        getStyleClass().add("refund-row");
                        setOpacity(1.0);
                        setStyle("-fx-background-color: rgba(171, 71, 188, 0.12);");
                    } else if (item.isIncome()) {
                        getStyleClass().add("income-row");
                        setOpacity(1.0);
                        setStyle("-fx-background-color: rgba(76, 175, 80, 0.12);");
                    } else {
                        setOpacity(1.0);
                        setStyle("");
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
                    item.setIncome(!prev);
                    try {
                        state.saveExpenses();
                    } catch (IOException ex) {
                        item.setIncome(prev);
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
                    if (item.isRefund() && !item.isIncome()) {
                        item.setIncome(true);
                    } else if (!item.isRefund() && prevRefund) {
                        item.setIncome(prevIncome && !prevRefund);
                    }
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

            menu.setOnShowing(e -> {
                Expense item = row.getItem();
                if (item != null && item.isExcluded()) {
                    toggleExclude.setText("Include in Analytics");
                } else {
                    toggleExclude.setText("Exclude from Analytics");
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
                boolean hasReceipt = item != null && item.getReceiptPath() != null && !item.getReceiptPath().isEmpty();
                viewReceiptItem.setVisible(hasReceipt);
                removeReceiptItem.setVisible(hasReceipt);
                attachReceiptItem.setText(hasReceipt ? "Replace Receipt..." : "Attach Receipt...");
            });

            menu.getItems().addAll(copyItem, new SeparatorMenuItem(),
                    toggleExclude, toggleIncome, toggleRefund,
                    new SeparatorMenuItem(), manageTags,
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
        try {
            String minText = filterMinAmount.getText();
            if (minText != null && !minText.isEmpty()) minAmount = Double.parseDouble(minText);
        } catch (NumberFormatException ignored) {}
        try {
            String maxText = filterMaxAmount.getText();
            if (maxText != null && !maxText.isEmpty()) maxAmount = Double.parseDouble(maxText);
        } catch (NumberFormatException ignored) {}
        final double fMin = minAmount;
        final double fMax = maxAmount;

        state.getFilteredData().setPredicate(expense -> {
            if (!YearMonth.from(expense.getDate()).equals(selectedYearMonth)) return false;
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

        // Update empty state message dynamically
        if (state.getFilteredData().isEmpty()) {
            String message;
            if (lowerCaseFilter != null) {
                message = "No expenses match your search.";
            } else {
                message = String.format("No expenses recorded for %s %d.",
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
            filterItems.addAll(state.getCategories());
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
            filterItems.addAll(state.getTags());
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
        Label expenseMsg = new Label("No expenses for this period.");
        expenseMsg.getStyleClass().add("empty-state-label");
        Label expenseHint = new Label("Press Ctrl+N to add one, or import from the Import tab.");
        expenseHint.getStyleClass().add("empty-state-hint");
        expenseEmptyState.getChildren().addAll(expenseMsg, expenseHint);
        expenseTable.setPlaceholder(expenseEmptyState);
    }

    // ======================== FXML HANDLERS ========================

    @FXML
    private void handleAddExpense() {
        try {
            double amount = Double.parseDouble(amountField.getText());
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
        confirmation.setContentText("Are you sure you want to delete this expense?");
        confirmation.getDialogPane().getStylesheets().add(getClass().getResource("/styles.css").toExternalForm());

        Optional<ButtonType> result = confirmation.showAndWait();
        if (result.isPresent() && result.get() == ButtonType.OK) {
            // Clean up receipt file if present
            String receiptPath = selectedExpense.getReceiptPath();
            state.getManager().executeCommand(new DeleteExpenseCommand(state.getManager(), selectedExpense));
            try {
                state.saveExpenses();
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
        filterToggleButton.setText(showing ? "Filters" : "Hide Filters");
    }

    @FXML
    private void handleClearFilters() {
        filterCategoryCombo.setValue("All Categories");
        filterTagCombo.setValue("All Tags");
        filterMinAmount.clear();
        filterMaxAmount.clear();
        searchField.clear();
        filterFieldsBox.setVisible(false);
        filterFieldsBox.setManaged(false);
        filterToggleButton.setText("Filters");
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
        state.getManager().executeCommand(new EditExpenseCommand(state.getManager(), oldExpense, newExpense));
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

    private void setupEditableAmountColumn() {
        amountColumn.setCellFactory(col -> new TableCell<Expense, Double>() {
            private TextField textField;
            private boolean editing = false;

            {
                setOnMouseClicked(event -> {
                    if (event.getClickCount() == 2 && !isEmpty() && getTableRow() != null
                            && canEditExpense(getTableRow().getItem())) {
                        startInlineEdit();
                    }
                });
            }

            private void startInlineEdit() {
                editing = true;
                textField = new TextField(getItem().toString());
                textField.getStyleClass().add("text-field");
                textField.setOnAction(e -> commitInlineEdit());
                textField.setOnKeyPressed(e -> { if (e.getCode() == KeyCode.ESCAPE) cancelInlineEdit(); });
                setGraphic(textField);
                setText(null);
                textField.selectAll();
                textField.requestFocus();
            }

            private void commitInlineEdit() {
                if (!editing) return;
                try {
                    double val = Double.parseDouble(textField.getText());
                    if (val <= 0) { showMsg("Amount must be positive", true); cancelInlineEdit(); return; }
                    if (getTableRow() == null || getTableRow().getItem() == null) { cancelInlineEdit(); return; }
                    Expense old = getTableRow().getItem();
                    editing = false;
                    Expense updated = new Expense(val, old.getCategory(), old.getDate(), old.getDescription());
                    updated.setImportId(old.getImportId());
                    updated.setExcluded(old.isExcluded());
                    updated.setIncome(old.isIncome());
                    updated.setRefund(old.isRefund());
                    updated.setTags(old.getTags());
                    updated.setCurrency(old.getCurrency());
                    updated.setReceiptPath(old.getReceiptPath());
                    handleInlineEdit(old, updated);
                } catch (NumberFormatException ex) {
                    showMsg("Invalid amount", true);
                    cancelInlineEdit();
                }
            }

            private void cancelInlineEdit() {
                editing = false;
                setText(getItem() == null ? null : fmt(getItem()));
                setGraphic(null);
            }

            @Override
            protected void updateItem(Double item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) { setText(null); setGraphic(null); editing = false; }
                else if (editing && textField != null) { setGraphic(textField); setText(null); }
                else {
                    Expense expense = getTableRow() != null ? getTableRow().getItem() : null;
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

            {
                setOnMouseClicked(event -> {
                    if (event.getClickCount() == 2 && !isEmpty() && getTableRow() != null
                            && canEditExpense(getTableRow().getItem())) {
                        startInlineEdit();
                    }
                });
            }

            private void startInlineEdit() {
                editing = true;
                committing = false;
                comboBox = new ComboBox<>(FXCollections.observableArrayList(state.getCategories()));
                comboBox.setValue(getItem());
                comboBox.setEditable(true);
                comboBox.getStyleClass().add("combo-box");
                comboBox.setOnAction(e -> commitInlineEdit());
                comboBox.setOnKeyPressed(e -> { if (e.getCode() == KeyCode.ESCAPE) cancelInlineEdit(); });
                setGraphic(comboBox);
                setText(null);
                comboBox.requestFocus();
            }

            private void commitInlineEdit() {
                if (committing || !editing) return;
                committing = true;
                try {
                    String newCategory = comboBox.getValue();
                    if (newCategory == null || newCategory.trim().isEmpty()) {
                        newCategory = comboBox.getEditor().getText().trim();
                    }
                    if (newCategory == null || newCategory.isEmpty()) {
                        showMsg("Category cannot be empty", true);
                        cancelInlineEdit();
                        return;
                    }
                    if (getTableRow() == null || getTableRow().getItem() == null) {
                        cancelInlineEdit();
                        return;
                    }
                    if (!state.getCategories().contains(newCategory)) {
                        state.getCategories().add(newCategory);
                        try { state.getStorage().saveCategories(state.getCategories()); }
                        catch (Exception ex) { state.getCategories().remove(newCategory); }
                    }
                    Expense old = getTableRow().getItem();
                    editing = false;
                    Expense updated = new Expense(old.getAmount(), newCategory, old.getDate(), old.getDescription());
                    updated.setImportId(old.getImportId());
                    updated.setExcluded(old.isExcluded());
                    updated.setIncome(old.isIncome());
                    updated.setRefund(old.isRefund());
                    updated.setTags(old.getTags());
                    updated.setCurrency(old.getCurrency());
                    updated.setReceiptPath(old.getReceiptPath());
                    handleInlineEdit(old, updated);
                } catch (Exception ex) {
                    System.err.println("Error in category commitInlineEdit: " + ex.getMessage());
                    cancelInlineEdit();
                }
            }

            private void cancelInlineEdit() {
                editing = false;
                committing = false;
                setText(getItem());
                setGraphic(null);
            }

            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
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

            {
                setOnMouseClicked(event -> {
                    if (event.getClickCount() == 2 && !isEmpty() && getTableRow() != null
                            && canEditExpense(getTableRow().getItem())) {
                        startInlineEdit();
                    }
                });
            }

            private void startInlineEdit() {
                editing = true;
                picker = new DatePicker(getItem());
                picker.getStyleClass().add("date-picker");
                picker.setOnAction(e -> commitInlineEdit());
                picker.setOnKeyPressed(e -> { if (e.getCode() == KeyCode.ESCAPE) cancelInlineEdit(); });
                setGraphic(picker);
                setText(null);
                picker.requestFocus();
            }

            private void commitInlineEdit() {
                if (!editing) return;
                LocalDate newDate = picker.getValue();
                if (newDate == null) { cancelInlineEdit(); return; }
                if (getTableRow() == null || getTableRow().getItem() == null) { cancelInlineEdit(); return; }
                Expense old = getTableRow().getItem();
                editing = false;
                Expense updated = new Expense(old.getAmount(), old.getCategory(), newDate, old.getDescription());
                updated.setImportId(old.getImportId());
                updated.setExcluded(old.isExcluded());
                updated.setIncome(old.isIncome());
                updated.setRefund(old.isRefund());
                updated.setTags(old.getTags());
                updated.setCurrency(old.getCurrency());
                updated.setReceiptPath(old.getReceiptPath());
                handleInlineEdit(old, updated);
            }

            private void cancelInlineEdit() {
                editing = false;
                setText(getItem() == null ? null : getItem().toString());
                setGraphic(null);
            }

            @Override
            protected void updateItem(LocalDate item, boolean empty) {
                super.updateItem(item, empty);
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

            {
                setOnMouseClicked(event -> {
                    if (event.getClickCount() == 2 && !isEmpty() && getTableRow() != null
                            && canEditExpense(getTableRow().getItem())) {
                        startInlineEdit();
                    }
                });
            }

            private void startInlineEdit() {
                editing = true;
                textField = new TextField(getItem() != null ? getItem() : "");
                textField.getStyleClass().add("text-field");
                textField.setOnAction(e -> commitInlineEdit());
                textField.setOnKeyPressed(e -> { if (e.getCode() == KeyCode.ESCAPE) cancelInlineEdit(); });
                setGraphic(textField);
                setText(null);
                textField.selectAll();
                textField.requestFocus();
            }

            private void commitInlineEdit() {
                if (!editing) return;
                if (getTableRow() == null || getTableRow().getItem() == null) { cancelInlineEdit(); return; }
                Expense old = getTableRow().getItem();
                editing = false;
                Expense updated = new Expense(old.getAmount(), old.getCategory(), old.getDate(), textField.getText().trim());
                updated.setImportId(old.getImportId());
                updated.setExcluded(old.isExcluded());
                updated.setIncome(old.isIncome());
                updated.setRefund(old.isRefund());
                updated.setTags(old.getTags());
                updated.setCurrency(old.getCurrency());
                updated.setReceiptPath(old.getReceiptPath());
                handleInlineEdit(old, updated);
            }

            private void cancelInlineEdit() {
                editing = false;
                setText(getItem() != null ? getItem() : "");
                setGraphic(null);
            }

            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty) { setText(null); setGraphic(null); editing = false; }
                else if (editing && textField != null) { setGraphic(textField); setText(null); }
                else {
                    String text = item != null ? item : "";
                    setText(text);
                    setGraphic(null);
                    setAlignment(Pos.CENTER_LEFT);
                    if (text.length() > 30) {
                        setTooltip(new Tooltip(text));
                    } else {
                        setTooltip(null);
                    }
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
                    if (isForeign) {
                        Expense expense = getTableRow() != null ? getTableRow().getItem() : null;
                        if (expense != null) {
                            if (state.getCurrencyManager().hasRate(item)) {
                                double converted = state.getCurrencyManager().toBase(expense.getAmount(), item);
                                setTooltip(new Tooltip(String.format("%s (= %s)",
                                    CurrencyManager.fmt(expense.getAmount(), item),
                                    UIUtils.fmt(converted, state.getCurrencySymbol()))));
                            } else {
                                setStyle("-fx-text-fill: #FC5C65; -fx-font-weight: bold;");
                                setTooltip(new Tooltip("No exchange rate set for " + item + " — using 1:1"));
                            }
                        }
                    } else {
                        setTooltip(null);
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
                        icon.setTooltip(new Tooltip("View receipt"));
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

        Label errorLabel = new Label();
        errorLabel.getStyleClass().add("error-label");

        Button confirmBtn = new Button("Make Recurring");
        confirmBtn.getStyleClass().add("success-button");
        confirmBtn.setOnAction(e -> {
            RecurrenceType freq = freqCombo.getValue();
            if (freq == null) {
                errorLabel.setText("Please select a frequency");
                return;
            }
            LocalDate endDate = endDatePicker.getValue();

            RecurringExpense recurring = new RecurringExpense(
                    expense.getAmount(), expense.getCategory(), expense.getDate(),
                    expense.getDescription() != null ? expense.getDescription() : "",
                    freq, endDate);
            if (expense.isIncome()) recurring.setIncome(true);

            state.getManager().executeCommand(new AddExpenseCommand(state.getManager(), recurring));
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

        VBox content = new VBox(12, header, freqLabel, freqCombo, endLabel, endDatePicker, errorLabel, buttons);
        content.setPadding(new Insets(20));
        content.getStyleClass().add("root-pane");

        Scene scene = new Scene(content, 400, 380);
        scene.getStylesheets().add(getClass().getResource("/styles.css").toExternalForm());
        dialog.setScene(scene);
        dialog.showAndWait();
    }

    // ======================== PUBLIC HELPERS ========================

    public void focusAddForm() {
        addExpensePane.setExpanded(true);
        Platform.runLater(() -> amountField.requestFocus());
    }

    public void focusSearch() {
        Platform.runLater(() -> searchField.requestFocus());
    }

    public ObservableList<Expense> getTableItems() {
        return expenseTable.getItems();
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
