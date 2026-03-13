package com.wyn.expensetracker;

import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.collections.transformation.SortedList;
import javafx.fxml.FXML;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.scene.chart.*;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import javafx.util.Duration;

import java.io.File;
import java.io.IOException;
import java.time.LocalDate;
import java.time.Month;
import java.time.YearMonth;
import java.time.format.TextStyle;
import java.util.*;
import java.util.stream.Collectors;

public class MainController {

    // --- Left panel: Expense tab ---
    @FXML private TabPane tabPane;
    @FXML private VBox expenseTabContent;
    @FXML private VBox recurringTabContent;
    @FXML private TextField amountField;
    @FXML private ComboBox<String> categoryCombo;
    @FXML private DatePicker datePicker;
    @FXML private TextField descriptionField;
    @FXML private Button addButton;
    @FXML private Button deleteButton;
    @FXML private Button undoButton;
    @FXML private Button redoButton;

    // --- Left panel: Recurring tab ---
    @FXML private TextField addRecurringAmountField;
    @FXML private ComboBox<String> addRecurringCategoryCombo;
    @FXML private DatePicker addRecurringDatePicker;
    @FXML private TextField addRecurringDescField;
    @FXML private ComboBox<RecurrenceType> addRecurringFreqCombo;
    @FXML private DatePicker addRecurringEndDatePicker;
    @FXML private TableView<RecurringExpense> recurringTable;
    @FXML private TextField editRecurringAmountField;
    @FXML private ComboBox<String> editRecurringCategoryCombo;
    @FXML private DatePicker editRecurringDatePicker;
    @FXML private TextField editRecurringDescField;
    @FXML private ComboBox<RecurrenceType> editRecurringFreqCombo;
    @FXML private DatePicker editRecurringEndDatePicker;
    @FXML private Button updateRecurringButton;

    // --- Left panel: Income & Search ---
    @FXML private TextField incomeField;
    @FXML private Label totalLabel;
    @FXML private Label moneySavedLabel;
    @FXML private TextField searchField;
    @FXML private Label errorLabel;

    // --- Right panel ---
    @FXML private ComboBox<Integer> yearCombo;
    @FXML private ComboBox<Month> monthCombo;
    @FXML private TableView<Expense> expenseTable;
    @FXML private TableColumn<Expense, Double> amountColumn;
    @FXML private TableColumn<Expense, String> expenseCategoryColumn;
    @FXML private TableColumn<Expense, LocalDate> dateColumn;
    @FXML private TableColumn<Expense, String> descriptionColumn;
    @FXML private Button exportButton;
    @FXML private ComboBox<String> chartPeriodCombo;
    @FXML private PieChart categoryChart;
    @FXML private BarChart<String, Number> monthlyTrendChart;
    @FXML private TableView<CategoryTotal> categoryTable;
    @FXML private TableColumn<CategoryTotal, Double> categoryTotalColumn;

    // --- Data ---
    private ExpenseManager manager;
    private FileStorage storage;
    private ObservableList<String> categories;
    private ObservableList<Expense> expenseList;
    private FilteredList<Expense> filteredData;
    private ObservableList<Integer> yearList;
    private ObservableList<CategoryTotal> categoryTotals;
    private ObservableList<RecurringExpense> recurringList;
    private Map<YearMonth, Double> incomes;
    private RecurringExpense selectedRecurringExpense;
    private Stage stage;

    @FXML
    public void initialize() {
        // Minimal — most setup happens in initializeData() after data is loaded
    }

    public void initializeData(ExpenseManager manager, FileStorage storage,
                               ObservableList<String> categories, Map<YearMonth, Double> incomes, Stage stage) {
        this.manager = manager;
        this.storage = storage;
        this.categories = categories;
        this.incomes = incomes;
        this.stage = stage;

        setupComboBoxes();
        setupTables();
        setupListeners();
        setupEmptyStates();

        // Initial date pickers
        datePicker.setValue(LocalDate.now());
        addRecurringDatePicker.setValue(LocalDate.now());

        // Select first tab
        tabPane.getSelectionModel().select(0);

        // Initial refresh
        refreshTable();

        // Defer tab height adjustment until CSS is applied
        Platform.runLater(() -> {
            expenseTabContent.applyCss();
            expenseTabContent.layout();
            tabPane.applyCss();
            tabPane.layout();
            double initialHeight = expenseTabContent.prefHeight(-1) + 40;
            tabPane.setPrefHeight(initialHeight);
        });
    }

    // ======================== SETUP ========================

    private void setupComboBoxes() {
        // Category combos share the same observable list
        categoryCombo.setItems(categories);
        addRecurringCategoryCombo.setItems(categories);
        editRecurringCategoryCombo.setItems(categories);

        setupComboCellFactory(categoryCombo);
        setupComboCellFactory(addRecurringCategoryCombo);
        setupComboCellFactory(editRecurringCategoryCombo);

        // Frequency combos
        addRecurringFreqCombo.setItems(FXCollections.observableArrayList(RecurrenceType.values()));
        editRecurringFreqCombo.setItems(FXCollections.observableArrayList(RecurrenceType.values()));

        // Year/month combos
        yearList = FXCollections.observableArrayList();
        yearCombo.setItems(yearList);
        setupComboCellFactory(yearCombo);

        monthCombo.setItems(FXCollections.observableArrayList(Month.values()));
        setupComboCellFactory(monthCombo);

        // Chart period combo
        chartPeriodCombo.setItems(FXCollections.observableArrayList("All Time", "By Year", "By Month"));
        chartPeriodCombo.setValue("All Time");
    }

    private <T> void setupComboCellFactory(ComboBox<T> combo) {
        combo.setCellFactory(lv -> new ListCell<T>() {
            @Override
            protected void updateItem(T item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : item.toString());
            }
        });
    }

    private void setupTables() {
        // Expense table
        expenseTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        setupColumnCellFactory(amountColumn, Pos.CENTER_RIGHT);
        setupColumnCellFactory(expenseCategoryColumn, Pos.CENTER_LEFT);
        setupColumnCellFactory(dateColumn, Pos.CENTER);
        setupColumnCellFactory(descriptionColumn, Pos.CENTER_LEFT);

        expenseList = FXCollections.observableArrayList(manager.getExpenses());
        filteredData = new FilteredList<>(expenseList, p -> true);
        SortedList<Expense> sortedData = new SortedList<>(filteredData);
        sortedData.comparatorProperty().bind(expenseTable.comparatorProperty());
        expenseTable.setItems(sortedData);

        // Recurring table
        recurringTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        recurringList = FXCollections.observableArrayList(manager.getBaseRecurringExpenses());
        recurringTable.setItems(recurringList);

        // Category table
        categoryTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        categoryTotalColumn.setCellFactory(tc -> new TableCell<CategoryTotal, Double>() {
            @Override
            protected void updateItem(Double item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : String.format("%.2f", item));
            }
        });
        categoryTotals = FXCollections.observableArrayList();
        categoryTable.setItems(categoryTotals);
    }

    private <S, T> void setupColumnCellFactory(TableColumn<S, T> column, Pos alignment) {
        column.setCellFactory(tc -> new TableCell<S, T>() {
            @Override
            protected void updateItem(T item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : item.toString());
                setAlignment(alignment);
            }
        });
    }

    private void setupEmptyStates() {
        Label expensePlaceholder = new Label("No expenses for this period.");
        expensePlaceholder.getStyleClass().add("empty-state-label");
        expenseTable.setPlaceholder(expensePlaceholder);

        Label recurringPlaceholder = new Label("No recurring expenses yet.");
        recurringPlaceholder.getStyleClass().add("empty-state-label");
        recurringTable.setPlaceholder(recurringPlaceholder);

        Label categoryPlaceholder = new Label("No category data for this period.");
        categoryPlaceholder.getStyleClass().add("empty-state-label");
        categoryTable.setPlaceholder(categoryPlaceholder);
    }

    private void setupListeners() {
        // Tab height adjustment
        tabPane.getSelectionModel().selectedItemProperty().addListener((obs, oldTab, newTab) -> {
            if (newTab != null) {
                tabPane.applyCss();
                tabPane.layout();
                VBox content = newTab == tabPane.getTabs().get(0) ? expenseTabContent : recurringTabContent;
                content.applyCss();
                content.layout();
                tabPane.setPrefHeight(content.prefHeight(-1) + 40);
            }
        });

        // Recurring table selection
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

        // Search debounce
        PauseTransition searchDebounce = new PauseTransition(Duration.millis(300));
        searchField.textProperty().addListener((observable, oldValue, newValue) -> {
            searchDebounce.setOnFinished(e -> updateTotalExpenses());
            searchDebounce.playFromStart();
        });

        // Period selectors
        yearCombo.valueProperty().addListener((observable, oldValue, newValue) -> {
            updateTotalExpenses();
            updateCharts();
            updateIncomeField();
        });
        monthCombo.valueProperty().addListener((observable, oldValue, newValue) -> {
            updateTotalExpenses();
            updateCharts();
            updateIncomeField();
        });

        // Income field
        incomeField.textProperty().addListener((observable, oldValue, newValue) -> {
            Integer selectedYear = yearCombo.getValue();
            Month selectedMonth = monthCombo.getValue();
            if (selectedYear == null || selectedMonth == null) return;
            YearMonth selectedYearMonth = YearMonth.of(selectedYear, selectedMonth);
            try {
                double incomeValue = newValue.isEmpty() ? 0.0 : Double.parseDouble(newValue);
                if (incomeValue < 0) {
                    showMessage("Income cannot be negative", true);
                    return;
                }
                incomes.put(selectedYearMonth, incomeValue);
                try {
                    storage.saveIncomes(incomes);
                    updateTotalExpenses();
                } catch (IOException ex) {
                    incomes.remove(selectedYearMonth);
                    showMessage("Error saving incomes: " + ex.getMessage(), true);
                }
            } catch (NumberFormatException ex) {
                showMessage("Invalid income: Please enter a valid number (e.g., 5000.00)", true);
            }
        });

        // Chart period
        chartPeriodCombo.valueProperty().addListener((obs, oldVal, newVal) -> updateCharts());

        // Enter key on amount field
        amountField.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ENTER) addButton.fire();
        });

        // Delete key on expense table
        expenseTable.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.DELETE) deleteButton.fire();
        });
    }

    public void setupKeyboardShortcuts(Scene scene) {
        scene.addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            if (event.isControlDown()) {
                Node focused = scene.getFocusOwner();
                boolean inTextField = focused instanceof TextField || focused instanceof TextArea;

                switch (event.getCode()) {
                    case Z:
                        if (!inTextField) {
                            handleUndo();
                            event.consume();
                        }
                        break;
                    case Y:
                        if (!inTextField) {
                            handleRedo();
                            event.consume();
                        }
                        break;
                    case N:
                        amountField.requestFocus();
                        event.consume();
                        break;
                    case E:
                        handleExport();
                        event.consume();
                        break;
                    case F:
                        searchField.requestFocus();
                        event.consume();
                        break;
                    default:
                        break;
                }
            }
        });
    }

    // ======================== EVENT HANDLERS ========================

    @FXML
    private void handleAddExpense() {
        try {
            double amount = Double.parseDouble(amountField.getText());
            if (amount <= 0) {
                showMessage("Amount must be positive", true);
                return;
            }
            String category = categoryCombo.getValue();
            if (category == null || category.trim().isEmpty()) {
                category = categoryCombo.getEditor().getText().trim();
                if (category.isEmpty()) {
                    showMessage("Category cannot be empty", true);
                    return;
                }
                if (!categories.contains(category)) {
                    categories.add(category);
                    try {
                        storage.saveCategories(categories);
                    } catch (Exception ex) {
                        categories.remove(category);
                        showMessage("Failed to save categories: " + ex.getMessage(), true);
                        return;
                    }
                }
            }
            LocalDate date = datePicker.getValue();
            if (date == null) {
                showMessage("Please select a date", true);
                return;
            }
            String description = descriptionField.getText().trim();

            Expense expense = new Expense(amount, category, date, description.isEmpty() ? "" : description);
            manager.executeCommand(new AddExpenseCommand(manager, expense));
            try {
                storage.saveExpenses(manager.getExpensesForSave());
            } catch (Exception ex) {
                manager.undo();
                showMessage("Failed to save expense: " + ex.getMessage(), true);
                return;
            }
            refreshTable();
            resetExpenseForm();
            showMessage("Expense added successfully!", false);
        } catch (NumberFormatException ex) {
            showMessage("Invalid amount: Please enter a valid number (e.g., 10.99)", true);
        } catch (Exception ex) {
            showMessage("Error: " + ex.getMessage(), true);
        }
    }

    @FXML
    private void handleDeleteExpense() {
        Expense selectedExpense = expenseTable.getSelectionModel().getSelectedItem();
        if (selectedExpense == null) {
            showMessage("Please select an expense to delete", true);
            return;
        }

        Alert confirmation = new Alert(Alert.AlertType.CONFIRMATION);
        confirmation.setTitle("Confirm Deletion");
        confirmation.setHeaderText(null);
        confirmation.setContentText("Are you sure you want to delete this expense?");

        Optional<ButtonType> result = confirmation.showAndWait();
        if (result.isPresent() && result.get() == ButtonType.OK) {
            manager.executeCommand(new DeleteExpenseCommand(manager, selectedExpense));
            try {
                storage.saveExpenses(manager.getExpensesForSave());
                refreshTable();
                showMessage("Expense deleted successfully!", false);
            } catch (Exception ex) {
                manager.undo();
                showMessage("Error deleting expense: " + ex.getMessage(), true);
            }
        }
    }

    @FXML
    private void handleUndo() {
        if (!manager.canUndo()) return;
        manager.undo();
        try {
            storage.saveExpenses(manager.getExpensesForSave());
            refreshTable();
            recurringList.setAll(manager.getBaseRecurringExpenses());
            updateUndoRedoButtons();
            showMessage("Undo successful!", false);
        } catch (Exception ex) {
            showMessage("Error during undo: " + ex.getMessage(), true);
        }
    }

    @FXML
    private void handleRedo() {
        if (!manager.canRedo()) return;
        manager.redo();
        try {
            storage.saveExpenses(manager.getExpensesForSave());
            refreshTable();
            recurringList.setAll(manager.getBaseRecurringExpenses());
            updateUndoRedoButtons();
            showMessage("Redo successful!", false);
        } catch (Exception ex) {
            showMessage("Error during redo: " + ex.getMessage(), true);
        }
    }

    @FXML
    private void handleAddRecurring() {
        try {
            double amount = Double.parseDouble(addRecurringAmountField.getText());
            if (amount <= 0) {
                showMessage("Amount must be positive", true);
                return;
            }
            String category = addRecurringCategoryCombo.getValue();
            if (category == null || category.trim().isEmpty()) {
                category = addRecurringCategoryCombo.getEditor().getText().trim();
                if (category.isEmpty()) {
                    showMessage("Category cannot be empty", true);
                    return;
                }
                if (!categories.contains(category)) {
                    categories.add(category);
                    try {
                        storage.saveCategories(categories);
                    } catch (Exception ex) {
                        categories.remove(category);
                        showMessage("Failed to save categories: " + ex.getMessage(), true);
                        return;
                    }
                }
            }
            LocalDate date = addRecurringDatePicker.getValue();
            if (date == null) {
                showMessage("Please select a start date", true);
                return;
            }
            String description = addRecurringDescField.getText().trim();
            RecurrenceType frequency = addRecurringFreqCombo.getValue();
            if (frequency == null) {
                showMessage("Please select a recurrence frequency", true);
                return;
            }
            LocalDate endDate = addRecurringEndDatePicker.getValue();

            RecurringExpense expense = new RecurringExpense(amount, category, date,
                description.isEmpty() ? "" : description, frequency, endDate);
            manager.executeCommand(new AddExpenseCommand(manager, expense));
            try {
                storage.saveExpenses(manager.getExpensesForSave());
                manager.generateRecurringExpenses(LocalDate.now());
                storage.saveExpenses(manager.getExpensesForSave());
                recurringList.setAll(manager.getBaseRecurringExpenses());
            } catch (Exception ex) {
                manager.undo();
                showMessage("Failed to save recurring expense: " + ex.getMessage(), true);
                return;
            }
            refreshTable();
            resetRecurringForm();
            showMessage("Recurring expense added successfully!", false);
        } catch (NumberFormatException ex) {
            showMessage("Invalid amount: Please enter a valid number (e.g., 10.99)", true);
        } catch (Exception ex) {
            showMessage("Error: " + ex.getMessage(), true);
        }
    }

    @FXML
    private void handleUpdateRecurring() {
        if (selectedRecurringExpense == null) {
            showMessage("Please select a recurring expense to update", true);
            return;
        }

        try {
            double amount = Double.parseDouble(editRecurringAmountField.getText());
            if (amount <= 0) {
                showMessage("Amount must be positive", true);
                return;
            }
            String category = editRecurringCategoryCombo.getValue();
            if (category == null || category.trim().isEmpty()) {
                showMessage("Category cannot be empty", true);
                return;
            }
            LocalDate date = editRecurringDatePicker.getValue();
            if (date == null) {
                showMessage("Please select a start date", true);
                return;
            }
            String description = editRecurringDescField.getText().trim();
            RecurrenceType frequency = editRecurringFreqCombo.getValue();
            if (frequency == null) {
                showMessage("Please select a recurrence frequency", true);
                return;
            }
            LocalDate endDate = editRecurringEndDatePicker.getValue();

            RecurringExpense newExpense = new RecurringExpense(amount, category, date, description, frequency, endDate);
            manager.executeCommand(new UpdateRecurringExpenseCommand(manager, selectedRecurringExpense, newExpense));
            try {
                storage.saveExpenses(manager.getExpensesForSave());
                refreshTable();
                recurringList.setAll(manager.getBaseRecurringExpenses());
                clearEditRecurringForm();
                updateRecurringButton.setDisable(true);
                showMessage("Recurring expense updated successfully!", false);
            } catch (Exception ex) {
                showMessage("Error updating recurring expense: " + ex.getMessage(), true);
            }
        } catch (NumberFormatException ex) {
            showMessage("Invalid amount: Please enter a valid number (e.g., 10.99)", true);
        }
    }

    @FXML
    private void handleDeleteRecurring() {
        RecurringExpense selected = recurringTable.getSelectionModel().getSelectedItem();
        if (selected == null) {
            showMessage("Please select a recurring expense to delete", true);
            return;
        }

        Alert confirmation = new Alert(Alert.AlertType.CONFIRMATION);
        confirmation.setTitle("Confirm Deletion");
        confirmation.setHeaderText(null);
        confirmation.setContentText("Are you sure you want to delete this recurring expense?");

        Optional<ButtonType> result = confirmation.showAndWait();
        if (result.isPresent() && result.get() == ButtonType.OK) {
            manager.executeCommand(new DeleteRecurringExpenseCommand(manager, selected));
            try {
                storage.saveExpenses(manager.getExpensesForSave());
                refreshTable();
                recurringList.setAll(manager.getBaseRecurringExpenses());
                clearEditRecurringForm();
                updateRecurringButton.setDisable(true);
                showMessage("Recurring expense deleted successfully!", false);
            } catch (Exception ex) {
                showMessage("Error deleting recurring expense: " + ex.getMessage(), true);
            }
        }
    }

    @FXML
    private void handleAddCategory() {
        TextInputDialog dialog = new TextInputDialog();
        dialog.setTitle("Add Category");
        dialog.setHeaderText("Enter a new category:");
        dialog.setContentText("Category:");
        dialog.getDialogPane().getStyleClass().add("dialog-pane");

        dialog.showAndWait().ifPresent(category -> {
            category = category.trim();
            if (!category.isEmpty() && !categories.contains(category)) {
                categories.add(category);
                categoryCombo.setValue(category);
                addRecurringCategoryCombo.setValue(category);
                editRecurringCategoryCombo.setValue(category);
                try {
                    storage.saveCategories(categories);
                    showMessage("", false);
                } catch (Exception ex) {
                    categories.remove(category);
                    showMessage("Error saving categories: " + ex.getMessage(), true);
                }
            } else if (categories.contains(category)) {
                showMessage("Category already exists", true);
            } else {
                showMessage("Category cannot be empty", true);
            }
        });
    }

    @FXML
    private void handleRemoveCategory() {
        String selectedCategory = categoryCombo.getValue();
        if (selectedCategory == null) {
            showMessage("Please select a category to remove", true);
            return;
        }

        boolean isUsed = manager.getExpenses().stream()
            .anyMatch(expense -> expense.getCategory().equals(selectedCategory));
        if (isUsed) {
            showMessage("Cannot remove category as it is used in existing expenses", true);
            return;
        }

        categories.remove(selectedCategory);
        if (categoryCombo.getItems().isEmpty()) {
            categoryCombo.setValue(null);
        } else if (categoryCombo.getSelectionModel().getSelectedIndex() >= categoryCombo.getItems().size()) {
            categoryCombo.getSelectionModel().selectLast();
        }
        addRecurringCategoryCombo.setValue(null);
        editRecurringCategoryCombo.setValue(null);

        try {
            storage.saveCategories(categories);
            showMessage("", false);
        } catch (Exception ex) {
            categories.add(selectedCategory);
            showMessage("Error saving categories: " + ex.getMessage(), true);
        }
    }

    @FXML
    private void handleExport() {
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
                File selectedFile = fileChooser.showSaveDialog(stage);
                if (selectedFile == null) {
                    showMessage("Export cancelled by user", true);
                    return;
                }
                filePath = selectedFile.getAbsolutePath();
            } else {
                filePath = defaultFile.getAbsolutePath();
            }

            storage.saveExpenses(manager.getExpensesForSave(), filePath);
            showMessage("Expenses exported to Excel successfully at: " + filePath, false);
        } catch (IOException ex) {
            showMessage("Failed to export to Excel: " + ex.getMessage(), true);
        }
    }

    // ======================== HELPERS ========================

    private void resetExpenseForm() {
        amountField.clear();
        categoryCombo.setValue(null);
        datePicker.setValue(LocalDate.now());
        descriptionField.clear();
        Platform.runLater(() -> amountField.requestFocus());
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

    private void refreshTable() {
        try {
            expenseList.setAll(manager.getExpenses());
            recurringList.setAll(manager.getBaseRecurringExpenses());
            updateYearList();
            updateTotalExpenses();
            updateCharts();
            updateIncomeField();
            updateUndoRedoButtons();
        } catch (Exception e) {
            showMessage("Error refreshing table: " + e.getMessage(), true);
        }
    }

    private void updateUndoRedoButtons() {
        undoButton.setDisable(!manager.canUndo());
        redoButton.setDisable(!manager.canRedo());
    }

    private void updateIncomeField() {
        Integer selectedYear = yearCombo.getValue();
        Month selectedMonth = monthCombo.getValue();
        if (selectedYear == null || selectedMonth == null) {
            incomeField.setText("");
            return;
        }
        YearMonth selectedYearMonth = YearMonth.of(selectedYear, selectedMonth);
        Double income = incomes.get(selectedYearMonth);
        incomeField.setText(income != null ? String.format("%.2f", income) : "");
    }

    private void updateYearList() {
        Set<Integer> years = new TreeSet<>();
        for (Expense expense : expenseList) {
            years.add(expense.getDate().getYear());
        }
        yearList.setAll(years);

        Integer selectedYear = yearCombo.getValue();
        Month selectedMonth = monthCombo.getValue();
        if (selectedYear != null && yearList.contains(selectedYear)) {
            yearCombo.setValue(selectedYear);
        } else {
            int currentYear = LocalDate.now().getYear();
            if (yearList.contains(currentYear)) {
                yearCombo.setValue(currentYear);
            } else if (!yearList.isEmpty()) {
                yearCombo.setValue(yearList.get(yearList.size() - 1));
            }
        }

        if (yearCombo.getValue() != null && selectedMonth == null) {
            monthCombo.setValue(Month.of(LocalDate.now().getMonthValue()));
        }
    }

    private void updateTotalExpenses() {
        Integer selectedYear = yearCombo.getValue();
        Month selectedMonth = monthCombo.getValue();

        if (selectedYear == null || selectedMonth == null) {
            totalLabel.setText("Total Expenses: 0.00");
            moneySavedLabel.setText("Money Saved: 0.00");
            filteredData.setPredicate(e -> false);
            categoryTotals.clear();
            return;
        }

        YearMonth selectedYearMonth = YearMonth.of(selectedYear, selectedMonth);
        String filter = searchField.getText();
        String lowerCaseFilter = (filter != null && !filter.isEmpty()) ? filter.toLowerCase() : null;

        filteredData.setPredicate(expense -> {
            if (!YearMonth.from(expense.getDate()).equals(selectedYearMonth)) return false;
            if (lowerCaseFilter == null) return true;
            return String.valueOf(expense.getAmount()).contains(lowerCaseFilter) ||
                   expense.getCategory().toLowerCase().contains(lowerCaseFilter) ||
                   expense.getDate().toString().contains(lowerCaseFilter) ||
                   (expense.getDescription() != null && expense.getDescription().toLowerCase().contains(lowerCaseFilter));
        });

        // Update empty state message dynamically
        if (filteredData.isEmpty()) {
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

        double total = filteredData.stream()
            .mapToDouble(Expense::getAmount)
            .sum();
        totalLabel.setText(String.format("Total Expenses for %s %d: %.2f",
                selectedMonth.getDisplayName(TextStyle.FULL, Locale.ENGLISH), selectedYear, total));

        double income = incomes.getOrDefault(selectedYearMonth, 0.0);
        double moneySaved = income - total;
        if (moneySaved >= 0) {
            moneySavedLabel.setText(String.format("Money Saved: %.2f", moneySaved));
            moneySavedLabel.getStyleClass().setAll("saved-label");
        } else {
            moneySavedLabel.setText(String.format("Overspent: %.2f", Math.abs(moneySaved)));
            moneySavedLabel.getStyleClass().setAll("saved-label", "overspent-label");
        }

        Map<String, Double> categoryMap = filteredData.stream()
            .collect(Collectors.groupingBy(
                Expense::getCategory,
                Collectors.summingDouble(Expense::getAmount))
            );

        categoryTotals.setAll(categoryMap.entrySet().stream()
            .map(entry -> new CategoryTotal(entry.getKey(), entry.getValue()))
            .sorted(Comparator.comparing(CategoryTotal::getCategory))
            .collect(Collectors.toList()));
    }

    private void updateCharts() {
        Integer selectedYear = yearCombo.getValue();
        Month selectedMonth = monthCombo.getValue();
        String chartPeriod = chartPeriodCombo.getValue();

        if (selectedYear == null || selectedMonth == null) {
            categoryChart.setData(FXCollections.observableArrayList());
            monthlyTrendChart.getData().clear();
            return;
        }

        YearMonth selectedYearMonth = YearMonth.of(selectedYear, selectedMonth);

        List<Expense> chartExpenses = expenseList.stream()
            .filter(expense -> {
                switch (chartPeriod) {
                    case "By Year":
                        return expense.getDate().getYear() == selectedYear;
                    case "By Month":
                        return YearMonth.from(expense.getDate()).equals(selectedYearMonth);
                    default: // All Time
                        return true;
                }
            })
            .collect(Collectors.toList());

        // PieChart
        Map<String, Double> categoryMap = chartExpenses.stream()
            .collect(Collectors.groupingBy(
                Expense::getCategory,
                Collectors.summingDouble(Expense::getAmount)));

        ObservableList<PieChart.Data> pieChartData = FXCollections.observableArrayList();
        String[] colors = {"#FF6F61", "#6B5B95", "#88B04B", "#F7B731", "#4ECDC4"};
        List<Map.Entry<String, Double>> sortedEntries = categoryMap.entrySet().stream()
            .sorted(Map.Entry.comparingByValue(Comparator.reverseOrder()))
            .collect(Collectors.toList());

        for (int i = 0; i < sortedEntries.size(); i++) {
            Map.Entry<String, Double> entry = sortedEntries.get(i);
            final int colorIndex = i % colors.length;
            PieChart.Data data = new PieChart.Data(
                entry.getKey() + " (" + String.format("%.2f", entry.getValue()) + ")",
                entry.getValue());
            data.nodeProperty().addListener((obs, oldNode, newNode) -> {
                if (newNode != null) {
                    newNode.setStyle("-fx-pie-color: " + colors[colorIndex] + ";");
                }
            });
            pieChartData.add(data);
        }
        categoryChart.setData(pieChartData);

        // BarChart
        monthlyTrendChart.getData().clear();
        XYChart.Series<String, Number> series = new XYChart.Series<>();
        Map<YearMonth, Double> monthlyTotals = expenseList.stream()
            .collect(Collectors.groupingBy(
                expense -> YearMonth.from(expense.getDate()),
                Collectors.summingDouble(Expense::getAmount)));

        monthlyTotals.entrySet().stream()
            .sorted(Map.Entry.comparingByKey())
            .filter(entry -> {
                switch (chartPeriod) {
                    case "By Year":
                        return entry.getKey().getYear() == selectedYear;
                    case "By Month":
                        return entry.getKey().equals(selectedYearMonth);
                    default: // All Time
                        return true;
                }
            })
            .forEach(entry -> {
                XYChart.Data<String, Number> data = new XYChart.Data<>(
                    entry.getKey().getMonth().toString() + " " + entry.getKey().getYear(),
                    entry.getValue());
                data.nodeProperty().addListener((obs, oldNode, newNode) -> {
                    if (newNode != null) {
                        newNode.setStyle("-fx-bar-fill: #4CAF50;");
                    }
                });
                series.getData().add(data);
            });

        monthlyTrendChart.getData().add(series);
    }

    private void showMessage(String message, boolean isError) {
        errorLabel.setText(message);
        if (message.isEmpty()) {
            errorLabel.getStyleClass().setAll("error-label");
        } else if (isError) {
            errorLabel.getStyleClass().setAll("error-label", "error-message");
        } else {
            errorLabel.getStyleClass().setAll("error-label", "success-message");
        }
    }
}
