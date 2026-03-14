package com.wyn.expensetracker;

import javafx.animation.FadeTransition;
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
import javafx.scene.layout.StackPane;
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
    @FXML private TextField recurringIncomeField;
    @FXML private TextField incomeField;
    @FXML private Label totalLabel;
    @FXML private Label moneySavedLabel;
    @FXML private TextField searchField;
    @FXML private Label errorLabel;
    @FXML private Label expenseErrorLabel;
    @FXML private Label addRecurringErrorLabel;
    @FXML private Label editRecurringErrorLabel;
    @FXML private ComboBox<String> currencyCombo;
    @FXML private VBox incomeFieldsBox;
    @FXML private Button toggleIncomeButton;

    // --- Left panel: Import tab ---
    @FXML private Label importErrorLabel;
    @FXML private TableView<CategorizationRules.RuleEntry> rulesTable;
    @FXML private TableView<ImportLog> importLogTable;

    // --- Right panel: Filters ---
    @FXML private ComboBox<String> filterCategoryCombo;
    @FXML private TextField filterMinAmount;
    @FXML private TextField filterMaxAmount;

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
    @FXML private Label statusSaveLabel;
    @FXML private Label statusCountLabel;
    @FXML private Label dashTotalSpent;
    @FXML private Label dashTopCategory;
    @FXML private Label dashTopCategoryAmount;
    @FXML private Label dashBudgetStatus;
    @FXML private Label dashMonthChange;
    @FXML private TableView<CategoryTotal> categoryTable;
    @FXML private TableColumn<CategoryTotal, String> categoryNameColumn;
    @FXML private TableColumn<CategoryTotal, Double> categoryTotalColumn;
    @FXML private TableColumn<CategoryTotal, Double> budgetColumn;
    @FXML private TableColumn<CategoryTotal, Double> progressColumn;

    // --- Constants ---
    private static final String[] CATEGORY_COLORS = {
        "#FF6F61", "#6B5B95", "#88B04B", "#F7B731", "#4ECDC4",
        "#FC5C65", "#45AAF2", "#26DE81", "#FD9644", "#A55EEA",
        "#778CA3", "#20BF6B", "#EB3B5A", "#3867D6", "#D1D8E0",
        "#0FB9B1", "#FA8231", "#8854D0", "#2D98DA", "#E77F67"
    };

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
    private Map<String, Double> budgets;
    private String currencySymbol = "R";
    private double recurringIncome = 0.0;
    private boolean suppressIncomeListener = false;
    private boolean suppressFilterListener = false;
    private boolean refreshingTable = false;
    private RecurringExpense selectedRecurringExpense;
    private Stage stage;
    private CategorizationRules categorizationRules;
    private ReceiptScanner receiptScanner;
    private ObservableList<ImportLog> importLogs;

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

        // Load budgets
        try {
            this.budgets = storage.loadBudgets();
        } catch (IOException e) {
            this.budgets = new HashMap<>();
        }

        // Load categorization rules
        categorizationRules = new CategorizationRules();
        try {
            categorizationRules.loadFrom(storage.loadCategorizationRules());
        } catch (IOException e) {
            System.err.println("Failed to load categorization rules: " + e.getMessage());
        }

        // Initialize receipt scanner
        receiptScanner = new ReceiptScanner();

        setupComboBoxes();
        setupTables();
        setupListeners();
        setupEmptyStates();
        setupRulesTable();

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
        chartPeriodCombo.setItems(FXCollections.observableArrayList(
            "All Time", "Last 12 Months", "Last 6 Months", "By Year", "By Month"));
        chartPeriodCombo.setValue("Last 12 Months");

        // Recurring income
        recurringIncome = storage.loadRecurringIncome();
        if (recurringIncome > 0) {
            recurringIncomeField.setText(String.format("%.2f", recurringIncome));
        }
        recurringIncomeField.textProperty().addListener((obs, oldVal, newVal) -> {
            try {
                recurringIncome = (newVal == null || newVal.isEmpty()) ? 0.0 : Double.parseDouble(newVal);
                if (recurringIncome < 0) { recurringIncome = 0; return; }
                storage.saveRecurringIncome(recurringIncome);
                updateIncomeField();
            } catch (NumberFormatException e) {
                // ignore while typing
            } catch (IOException e) {
                showMessage("Error saving recurring income: " + e.getMessage(), true);
            }
        });

        // Currency combo
        currencySymbol = storage.loadCurrencySymbol();
        currencyCombo.setItems(FXCollections.observableArrayList("R", "$", "\u20AC", "\u00A3", "\u00A5", "CHF", "kr", "Rs"));
        currencyCombo.setValue(currencySymbol);
        currencyCombo.valueProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal != null) {
                currencySymbol = newVal;
                try { storage.saveCurrencySymbol(newVal); } catch (IOException e) { /* ignore */ }
                refreshTable();
            }
        });

        // Filter category combo — "All Categories" + actual categories
        updateFilterCategoryCombo();
        filterCategoryCombo.valueProperty().addListener((obs, oldVal, newVal) -> {
            if (!suppressFilterListener) refreshTable();
        });
        filterMinAmount.textProperty().addListener((obs, oldVal, newVal) -> refreshTable());
        filterMaxAmount.textProperty().addListener((obs, oldVal, newVal) -> refreshTable());
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
        // Expense table — editable via double-click
        expenseTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        setupEditableAmountColumn();
        setupEditableCategoryColumn();
        setupEditableDateColumn();
        setupEditableDescriptionColumn();

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
        categoryNameColumn.setCellFactory(tc -> new TableCell<CategoryTotal, String>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setStyle("");
                } else {
                    setText(item);
                    String color = getCategoryColor(item);
                    setStyle("-fx-background-color: " + color + "33; -fx-border-color: " + color + " transparent transparent transparent; -fx-border-width: 0 0 0 3;");
                }
            }
        });
        categoryTotalColumn.setCellFactory(tc -> new TableCell<CategoryTotal, Double>() {
            @Override
            protected void updateItem(Double item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : fmt(item));
            }
        });
        budgetColumn.setCellFactory(tc -> new TableCell<CategoryTotal, Double>() {
            @Override
            protected void updateItem(Double item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null || item <= 0) {
                    setText(empty ? null : "-");
                } else {
                    setText(fmt(item));
                }
            }
        });
        progressColumn.setCellFactory(tc -> new TableCell<CategoryTotal, Double>() {
            private final ProgressBar bar = new ProgressBar(0);
            private final Label label = new Label();
            private final StackPane pane = new StackPane(bar, label);
            {
                bar.setMaxWidth(Double.MAX_VALUE);
                bar.setPrefHeight(18);
                label.setStyle("-fx-text-fill: white; -fx-font-size: 11px; -fx-font-weight: bold;");
            }

            @Override
            protected void updateItem(Double progress, boolean empty) {
                super.updateItem(progress, empty);
                if (empty || progress == null || progress == 0) {
                    setGraphic(null);
                    setText(empty ? null : "-");
                } else {
                    bar.setProgress(Math.min(progress, 1.0));
                    label.setText(String.format("%.0f%%", progress * 100));
                    if (progress < 0.8) {
                        bar.setStyle("-fx-accent: #4CAF50;");
                    } else if (progress <= 1.0) {
                        bar.setStyle("-fx-accent: #FF9800;");
                    } else {
                        bar.setStyle("-fx-accent: #E53935;");
                        bar.setProgress(1.0);
                    }
                    setGraphic(pane);
                    setText(null);
                }
            }
        });

        // Right-click context menu for setting budgets
        ContextMenu budgetMenu = new ContextMenu();
        MenuItem setBudgetItem = new MenuItem("Set Budget...");
        setBudgetItem.setOnAction(e -> handleSetBudget());
        MenuItem clearBudgetItem = new MenuItem("Clear Budget");
        clearBudgetItem.setOnAction(e -> handleClearBudget());
        budgetMenu.getItems().addAll(setBudgetItem, clearBudgetItem);
        categoryTable.setContextMenu(budgetMenu);

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
                VBox content;
                if (newTab == tabPane.getTabs().get(0)) content = expenseTabContent;
                else if (newTab == tabPane.getTabs().get(1)) content = recurringTabContent;
                else content = (VBox) newTab.getContent();
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

        // Income field — manual per-month override
        incomeField.textProperty().addListener((observable, oldValue, newValue) -> {
            if (suppressIncomeListener) return;
            Integer selectedYear = yearCombo.getValue();
            Month selectedMonth = monthCombo.getValue();
            if (selectedYear == null || selectedMonth == null) return;
            YearMonth selectedYearMonth = YearMonth.of(selectedYear, selectedMonth);
            try {
                if (newValue == null || newValue.isEmpty()) {
                    // Clear manual override — fall back to recurring
                    incomes.remove(selectedYearMonth);
                    try {
                        storage.saveIncomes(incomes);
                    } catch (IOException ex) {
                        showMessage("Error saving incomes: " + ex.getMessage(), true);
                    }
                    updateTotalExpenses();
                    return;
                }
                double incomeValue = Double.parseDouble(newValue);
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
            if (event.isAltDown()) {
                switch (event.getCode()) {
                    case LEFT:
                        handlePrevMonth();
                        event.consume();
                        break;
                    case RIGHT:
                        handleNextMonth();
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
            showMessageOn("Please select a recurring expense to update", true, editRecurringErrorLabel);
            return;
        }

        try {
            double amount = Double.parseDouble(editRecurringAmountField.getText());
            if (amount <= 0) {
                showMessageOn("Amount must be positive", true, editRecurringErrorLabel);
                return;
            }
            String category = editRecurringCategoryCombo.getValue();
            if (category == null || category.trim().isEmpty()) {
                showMessageOn("Category cannot be empty", true, editRecurringErrorLabel);
                return;
            }
            LocalDate date = editRecurringDatePicker.getValue();
            if (date == null) {
                showMessageOn("Please select a start date", true, editRecurringErrorLabel);
                return;
            }
            String description = editRecurringDescField.getText().trim();
            RecurrenceType frequency = editRecurringFreqCombo.getValue();
            if (frequency == null) {
                showMessageOn("Please select a recurrence frequency", true, editRecurringErrorLabel);
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
                showMessageOn("Recurring expense updated successfully!", false, editRecurringErrorLabel);
            } catch (Exception ex) {
                showMessageOn("Error updating recurring expense: " + ex.getMessage(), true, editRecurringErrorLabel);
            }
        } catch (NumberFormatException ex) {
            showMessageOn("Invalid amount: Please enter a valid number (e.g., 10.99)", true, editRecurringErrorLabel);
        }
    }

    @FXML
    private void handleDeleteRecurring() {
        RecurringExpense selected = recurringTable.getSelectionModel().getSelectedItem();
        if (selected == null) {
            showMessageOn("Please select a recurring expense to delete", true, editRecurringErrorLabel);
            return;
        }

        long generatedCount = manager.getExpenses().stream()
            .filter(e -> e.getSourceRecurringExpense() == selected)
            .count();
        Alert confirmation = new Alert(Alert.AlertType.CONFIRMATION);
        confirmation.setTitle("Confirm Deletion");
        confirmation.setHeaderText(null);
        confirmation.setContentText(String.format(
            "Are you sure you want to delete this recurring expense?\nThis will also remove %d generated expenses.", generatedCount));

        Optional<ButtonType> result = confirmation.showAndWait();
        if (result.isPresent() && result.get() == ButtonType.OK) {
            manager.executeCommand(new DeleteRecurringExpenseCommand(manager, selected));
            try {
                storage.saveExpenses(manager.getExpensesForSave());
                refreshTable();
                recurringList.setAll(manager.getBaseRecurringExpenses());
                clearEditRecurringForm();
                updateRecurringButton.setDisable(true);
                showMessageOn("Recurring expense deleted successfully!", false, editRecurringErrorLabel);
            } catch (Exception ex) {
                showMessageOn("Error deleting recurring expense: " + ex.getMessage(), true, editRecurringErrorLabel);
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

            ExcelExporter.exportExpenses(manager.getExpensesForSave(), filePath);
            showMessage("Expenses exported to Excel successfully at: " + filePath, false);
        } catch (IOException ex) {
            showMessage("Failed to export to Excel: " + ex.getMessage(), true);
        }
    }

    @FXML
    private void handleExportFiltered() {
        if (filteredData.isEmpty()) {
            showMessage("No expenses to export for the current view", true);
            return;
        }
        try {
            Integer year = yearCombo.getValue();
            Month month = monthCombo.getValue();
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
            File selectedFile = fileChooser.showSaveDialog(stage);
            if (selectedFile == null) {
                showMessage("Export cancelled", true);
                return;
            }

            List<Expense> toExport = new ArrayList<>(filteredData);
            ExcelExporter.exportExpenses(toExport, selectedFile.getAbsolutePath());
            showMessage(String.format("Exported %d expenses to %s", toExport.size(), selectedFile.getName()), false);
        } catch (IOException ex) {
            showMessage("Failed to export: " + ex.getMessage(), true);
        }
    }

    // ======================== INCOME TOGGLE ========================

    @FXML
    private void handleToggleIncome() {
        boolean show = !incomeFieldsBox.isVisible();
        incomeFieldsBox.setVisible(show);
        incomeFieldsBox.setManaged(show);
        toggleIncomeButton.setText(show ? "Hide" : "Edit");
    }

    // ======================== DATE NAVIGATION ========================

    @FXML
    private void handlePrevMonth() {
        navigateMonth(-1);
    }

    @FXML
    private void handleNextMonth() {
        navigateMonth(1);
    }

    @FXML
    private void handleThisMonth() {
        int currentYear = LocalDate.now().getYear();
        if (!yearList.contains(currentYear)) {
            yearList.add(currentYear);
            FXCollections.sort(yearList);
        }
        yearCombo.setValue(currentYear);
        monthCombo.setValue(Month.of(LocalDate.now().getMonthValue()));
    }

    private void navigateMonth(int offset) {
        Integer year = yearCombo.getValue();
        Month month = monthCombo.getValue();
        if (year == null || month == null) {
            handleThisMonth();
            return;
        }
        YearMonth current = YearMonth.of(year, month).plusMonths(offset);
        if (!yearList.contains(current.getYear())) {
            yearList.add(current.getYear());
            FXCollections.sort(yearList);
        }
        yearCombo.setValue(current.getYear());
        monthCombo.setValue(current.getMonth());
    }

    // ======================== INLINE EDITING ========================

    private boolean canEditExpense(Expense expense) {
        if (expense == null) return false;
        if (expense.getRecurringId() != null) {
            showMessage("Edit recurring expenses from the Recurring Expenses tab", true);
            return false;
        }
        return true;
    }

    private void handleInlineEdit(Expense oldExpense, Expense newExpense) {
        manager.executeCommand(new EditExpenseCommand(manager, oldExpense, newExpense));
        try {
            storage.saveExpenses(manager.getExpensesForSave());
        } catch (Exception ex) {
            manager.undo();
            showMessage("Error saving edit: " + ex.getMessage(), true);
            refreshTable();
            return;
        }
        refreshTable();
        showMessage("Expense updated", false);
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
                    if (val <= 0) { showMessage("Amount must be positive", true); cancelInlineEdit(); return; }
                    if (getTableRow() == null || getTableRow().getItem() == null) { cancelInlineEdit(); return; }
                    Expense old = getTableRow().getItem();
                    editing = false;
                    Expense updated = new Expense(val, old.getCategory(), old.getDate(), old.getDescription());
                    updated.setImportId(old.getImportId());
                    handleInlineEdit(old, updated);
                } catch (NumberFormatException ex) {
                    showMessage("Invalid amount", true);
                    cancelInlineEdit();
                }
            }

            private void cancelInlineEdit() {
                editing = false;
                setText(getItem() == null ? null : getItem().toString());
                setGraphic(null);
            }

            @Override
            protected void updateItem(Double item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) { setText(null); setGraphic(null); editing = false; }
                else if (editing && textField != null) { setGraphic(textField); setText(null); }
                else { setText(item.toString()); setGraphic(null); setAlignment(Pos.CENTER_RIGHT); }
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
                comboBox = new ComboBox<>(FXCollections.observableArrayList(categories));
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
                        showMessage("Category cannot be empty", true);
                        cancelInlineEdit();
                        return;
                    }
                    if (getTableRow() == null || getTableRow().getItem() == null) {
                        cancelInlineEdit();
                        return;
                    }
                    if (!categories.contains(newCategory)) {
                        categories.add(newCategory);
                        try { storage.saveCategories(categories); } catch (Exception ex) { categories.remove(newCategory); }
                    }
                    Expense old = getTableRow().getItem();
                    editing = false;
                    Expense updated = new Expense(old.getAmount(), newCategory, old.getDate(), old.getDescription());
                    updated.setImportId(old.getImportId());
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
                else { setText(item.toString()); setGraphic(null); setAlignment(Pos.CENTER); }
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
                else { setText(item != null ? item : ""); setGraphic(null); setAlignment(Pos.CENTER_LEFT); }
            }
        });
    }

    // ======================== BUDGET HANDLERS ========================

    private void handleSetBudget() {
        CategoryTotal selected = categoryTable.getSelectionModel().getSelectedItem();
        if (selected == null) {
            showMessage("Please select a category to set a budget for", true);
            return;
        }

        TextInputDialog dialog = new TextInputDialog(
            selected.getBudget() > 0 ? String.format("%.2f", selected.getBudget()) : "");
        dialog.setTitle("Set Budget");
        dialog.setHeaderText("Set monthly budget for: " + selected.getCategory());
        dialog.setContentText("Budget amount:");
        dialog.getDialogPane().getStyleClass().add("dialog-pane");

        dialog.showAndWait().ifPresent(input -> {
            try {
                double budget = input.isEmpty() ? 0.0 : Double.parseDouble(input);
                if (budget < 0) {
                    showMessage("Budget cannot be negative", true);
                    return;
                }
                if (budget > 0) {
                    budgets.put(selected.getCategory(), budget);
                } else {
                    budgets.remove(selected.getCategory());
                }
                storage.saveBudgets(budgets);
                updateTotalExpenses();
                showMessage("Budget set for " + selected.getCategory(), false);
            } catch (NumberFormatException ex) {
                showMessage("Invalid budget amount", true);
            } catch (IOException ex) {
                showMessage("Error saving budget: " + ex.getMessage(), true);
            }
        });
    }

    private void handleClearBudget() {
        CategoryTotal selected = categoryTable.getSelectionModel().getSelectedItem();
        if (selected == null) {
            showMessage("Please select a category", true);
            return;
        }
        budgets.remove(selected.getCategory());
        try {
            storage.saveBudgets(budgets);
            updateTotalExpenses();
            showMessage("Budget cleared for " + selected.getCategory(), false);
        } catch (IOException ex) {
            showMessage("Error saving budget: " + ex.getMessage(), true);
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
        if (refreshingTable) return;
        refreshingTable = true;
        try {
            expenseList.setAll(manager.getExpenses());
            recurringList.setAll(manager.getBaseRecurringExpenses());
            updateYearList();
            updateFilterCategoryCombo();
            updateTotalExpenses();
            updateCharts();
            updateIncomeField();
            updateUndoRedoButtons();
            updateStatusBar();
        } catch (Exception e) {
            showMessage("Error refreshing table: " + e.getMessage(), true);
        } finally {
            refreshingTable = false;
        }
    }

    private void updateUndoRedoButtons() {
        undoButton.setDisable(!manager.canUndo());
        redoButton.setDisable(!manager.canRedo());
    }

    private void updateIncomeField() {
        suppressIncomeListener = true;
        Integer selectedYear = yearCombo.getValue();
        Month selectedMonth = monthCombo.getValue();
        if (selectedYear == null || selectedMonth == null) {
            incomeField.setText("");
            incomeField.setPromptText("Leave empty to use recurring");
            suppressIncomeListener = false;
            return;
        }
        YearMonth selectedYearMonth = YearMonth.of(selectedYear, selectedMonth);
        Double manualIncome = incomes.get(selectedYearMonth);
        if (manualIncome != null) {
            incomeField.setText(String.format("%.2f", manualIncome));
            incomeField.setPromptText("Clear to use recurring");
        } else {
            incomeField.setText("");
            incomeField.setPromptText(recurringIncome > 0
                ? String.format("Using recurring: %.2f", recurringIncome)
                : "Leave empty to use recurring");
        }
        suppressIncomeListener = false;
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
            totalLabel.setText("Total Expenses: " + fmt(0));
            moneySavedLabel.setText("Money Saved: " + fmt(0));
            filteredData.setPredicate(e -> false);
            categoryTotals.clear();
            dashTotalSpent.setText(fmt(0));
            dashTopCategory.setText("-");
            dashTopCategoryAmount.setText("");
            dashBudgetStatus.setText("-");
            dashBudgetStatus.getStyleClass().setAll("dashboard-card-value");
            dashMonthChange.setText("-");
            dashMonthChange.getStyleClass().setAll("dashboard-card-value");
            return;
        }

        YearMonth selectedYearMonth = YearMonth.of(selectedYear, selectedMonth);
        String filter = searchField.getText();
        String lowerCaseFilter = (filter != null && !filter.isEmpty()) ? filter.toLowerCase() : null;

        // Category filter
        String selectedCategory = filterCategoryCombo.getValue();
        boolean filterByCategory = selectedCategory != null && !"All Categories".equals(selectedCategory);

        // Amount range filter
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

        filteredData.setPredicate(expense -> {
            if (!YearMonth.from(expense.getDate()).equals(selectedYearMonth)) return false;
            if (filterByCategory && !expense.getCategory().equals(selectedCategory)) return false;
            if (expense.getAmount() < fMin || expense.getAmount() > fMax) return false;
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
        totalLabel.setText(String.format("Total Expenses for %s %d: %s",
                selectedMonth.getDisplayName(TextStyle.FULL, Locale.ENGLISH), selectedYear, fmt(total)));

        double income = incomes.getOrDefault(selectedYearMonth, recurringIncome);
        double moneySaved = income - total;
        if (moneySaved >= 0) {
            moneySavedLabel.setText("Money Saved: " + fmt(moneySaved));
            moneySavedLabel.getStyleClass().setAll("saved-label");
        } else {
            moneySavedLabel.setText("Overspent: " + fmt(Math.abs(moneySaved)));
            moneySavedLabel.getStyleClass().setAll("saved-label", "overspent-label");
        }

        Map<String, Double> categoryMap = filteredData.stream()
            .collect(Collectors.groupingBy(
                Expense::getCategory,
                Collectors.summingDouble(Expense::getAmount))
            );

        categoryTotals.setAll(categoryMap.entrySet().stream()
            .map(entry -> new CategoryTotal(entry.getKey(), entry.getValue(),
                budgets.getOrDefault(entry.getKey(), 0.0)))
            .sorted(Comparator.comparing(CategoryTotal::getCategory))
            .collect(Collectors.toList()));

        updateDashboard(total, categoryMap, selectedYear, selectedMonth);
    }

    private void updateDashboard(double total, Map<String, Double> categoryMap,
                                  int selectedYear, Month selectedMonth) {
        // Total spent
        dashTotalSpent.setText(fmt(total));

        // Top category
        if (categoryMap.isEmpty()) {
            dashTopCategory.setText("-");
            dashTopCategoryAmount.setText("");
        } else {
            Map.Entry<String, Double> top = categoryMap.entrySet().stream()
                .max(Map.Entry.comparingByValue()).orElse(null);
            if (top != null) {
                dashTopCategory.setText(top.getKey());
                dashTopCategoryAmount.setText(fmt(top.getValue()));
            }
        }

        // Budget status
        double totalBudget = 0;
        double totalBudgeted = 0;
        for (Map.Entry<String, Double> entry : categoryMap.entrySet()) {
            double budget = budgets.getOrDefault(entry.getKey(), 0.0);
            if (budget > 0) {
                totalBudget += budget;
                totalBudgeted += entry.getValue();
            }
        }
        if (totalBudget > 0) {
            double remaining = totalBudget - totalBudgeted;
            if (remaining >= 0) {
                dashBudgetStatus.setText(fmt(remaining) + " left");
                dashBudgetStatus.getStyleClass().setAll("dashboard-card-value", "dashboard-positive");
            } else {
                dashBudgetStatus.setText(fmt(Math.abs(remaining)) + " over");
                dashBudgetStatus.getStyleClass().setAll("dashboard-card-value", "dashboard-negative");
            }
        } else {
            dashBudgetStatus.setText("No budgets");
            dashBudgetStatus.getStyleClass().setAll("dashboard-card-value");
        }

        // Month-over-month change
        YearMonth prevMonth = YearMonth.of(selectedYear, selectedMonth).minusMonths(1);
        double prevTotal = expenseList.stream()
            .filter(e -> YearMonth.from(e.getDate()).equals(prevMonth))
            .mapToDouble(Expense::getAmount)
            .sum();
        if (prevTotal > 0) {
            double change = total - prevTotal;
            double pct = (change / prevTotal) * 100;
            String arrow = change >= 0 ? "\u25B2" : "\u25BC";
            dashMonthChange.setText(String.format("%s %.0f%%", arrow, Math.abs(pct)));
            dashMonthChange.getStyleClass().setAll("dashboard-card-value",
                change <= 0 ? "dashboard-positive" : "dashboard-negative");
        } else {
            dashMonthChange.setText("-");
            dashMonthChange.getStyleClass().setAll("dashboard-card-value");
        }
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
        YearMonth now = YearMonth.now();

        List<Expense> chartExpenses = expenseList.stream()
            .filter(expense -> {
                YearMonth ym = YearMonth.from(expense.getDate());
                switch (chartPeriod) {
                    case "By Year":
                        return expense.getDate().getYear() == selectedYear;
                    case "By Month":
                        return ym.equals(selectedYearMonth);
                    case "Last 6 Months":
                        return !ym.isBefore(now.minusMonths(5)) && !ym.isAfter(now);
                    case "Last 12 Months":
                        return !ym.isBefore(now.minusMonths(11)) && !ym.isAfter(now);
                    default: // All Time
                        return true;
                }
            })
            .collect(Collectors.toList());

        // --- PieChart ---
        Map<String, Double> categoryMap = chartExpenses.stream()
            .collect(Collectors.groupingBy(
                Expense::getCategory,
                Collectors.summingDouble(Expense::getAmount)));

        double pieTotal = categoryMap.values().stream().mapToDouble(Double::doubleValue).sum();

        ObservableList<PieChart.Data> pieChartData = FXCollections.observableArrayList();
        List<Map.Entry<String, Double>> sortedEntries = categoryMap.entrySet().stream()
            .sorted(Map.Entry.comparingByValue(Comparator.reverseOrder()))
            .collect(Collectors.toList());

        for (Map.Entry<String, Double> entry : sortedEntries) {
            final String color = getCategoryColor(entry.getKey());
            final String category = entry.getKey();
            final double amount = entry.getValue();
            double pct = pieTotal > 0 ? (amount / pieTotal) * 100 : 0;
            PieChart.Data data = new PieChart.Data(
                category + " (" + String.format("%.0f%%", pct) + ")",
                amount);
            data.nodeProperty().addListener((obs, oldNode, newNode) -> {
                if (newNode != null) {
                    newNode.setStyle("-fx-pie-color: " + color + ";");
                    Tooltip tooltip = new Tooltip(category + ": " + fmt(amount)
                        + " (" + String.format("%.1f%%", pieTotal > 0 ? (amount / pieTotal) * 100 : 0) + ")");
                    tooltip.setStyle("-fx-font-size: 13px;");
                    Tooltip.install(newNode, tooltip);
                }
            });
            pieChartData.add(data);
        }
        categoryChart.setData(pieChartData);
        categoryChart.setAnimated(true);

        // --- BarChart ---
        monthlyTrendChart.setAnimated(false);
        monthlyTrendChart.getData().clear();
        monthlyTrendChart.setAnimated(true);

        boolean isDailyMode = "By Month".equals(chartPeriod);

        XYChart.Series<String, Number> series = new XYChart.Series<>();

        if (isDailyMode) {
            // Daily breakdown for the selected month
            Map<Integer, Double> dailyTotals = chartExpenses.stream()
                .collect(Collectors.groupingBy(
                    expense -> expense.getDate().getDayOfMonth(),
                    Collectors.summingDouble(Expense::getAmount)));

            int daysInMonth = selectedYearMonth.lengthOfMonth();
            for (int day = 1; day <= daysInMonth; day++) {
                double total = dailyTotals.getOrDefault(day, 0.0);
                final int d = day;
                XYChart.Data<String, Number> data = new XYChart.Data<>(String.valueOf(day), total);
                data.nodeProperty().addListener((obs, oldNode, newNode) -> {
                    if (newNode != null) {
                        boolean isToday = selectedYearMonth.equals(YearMonth.now())
                            && d == LocalDate.now().getDayOfMonth();
                        String barColor = isToday ? "#FF6F61" : "#4CAF50";
                        newNode.setStyle("-fx-bar-fill: " + barColor + ";");
                        Tooltip tooltip = new Tooltip("Day " + d + ": " + fmt(total));
                        tooltip.setStyle("-fx-font-size: 13px;");
                        Tooltip.install(newNode, tooltip);
                    }
                });
                series.getData().add(data);
            }
            monthlyTrendChart.setTitle("Daily Spending — "
                + selectedMonth.getDisplayName(TextStyle.FULL, Locale.getDefault()) + " " + selectedYear);
        } else {
            // Monthly trend
            Map<YearMonth, Double> monthlyTotals = expenseList.stream()
                .collect(Collectors.groupingBy(
                    expense -> YearMonth.from(expense.getDate()),
                    Collectors.summingDouble(Expense::getAmount)));

            monthlyTotals.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .filter(entry -> {
                    YearMonth ym = entry.getKey();
                    switch (chartPeriod) {
                        case "By Year":
                            return ym.getYear() == selectedYear;
                        case "Last 6 Months":
                            return !ym.isBefore(now.minusMonths(5)) && !ym.isAfter(now);
                        case "Last 12 Months":
                            return !ym.isBefore(now.minusMonths(11)) && !ym.isAfter(now);
                        default: // All Time
                            return true;
                    }
                })
                .forEach(entry -> {
                    String label = entry.getKey().getMonth()
                        .getDisplayName(TextStyle.SHORT, Locale.getDefault())
                        + " '" + String.format("%02d", entry.getKey().getYear() % 100);
                    final double amount = entry.getValue();
                    final YearMonth ym = entry.getKey();
                    XYChart.Data<String, Number> data = new XYChart.Data<>(label, amount);
                    data.nodeProperty().addListener((obs, oldNode, newNode) -> {
                        if (newNode != null) {
                            boolean isCurrent = ym.equals(selectedYearMonth);
                            String barColor = isCurrent ? "#FF6F61" : "#4CAF50";
                            newNode.setStyle("-fx-bar-fill: " + barColor + ";");
                            Tooltip tooltip = new Tooltip(
                                ym.getMonth().getDisplayName(TextStyle.FULL, Locale.getDefault())
                                + " " + ym.getYear() + ": " + fmt(amount));
                            tooltip.setStyle("-fx-font-size: 13px;");
                            Tooltip.install(newNode, tooltip);
                        }
                    });
                    series.getData().add(data);
                });
            monthlyTrendChart.setTitle("Monthly Trend");
        }

        monthlyTrendChart.getData().add(series);

        // Fade-in animation for both charts
        animateChartFadeIn(categoryChart);
        animateChartFadeIn(monthlyTrendChart);
    }

    private void animateChartFadeIn(Node chart) {
        chart.setOpacity(0);
        FadeTransition fade = new FadeTransition(Duration.millis(300), chart);
        fade.setFromValue(0);
        fade.setToValue(1);
        fade.play();
    }

    private void updateStatusBar() {
        statusSaveLabel.setText("Last saved: just now");
        int total = expenseList.size();
        long thisMonth = filteredData.size();
        statusCountLabel.setText(String.format("%d expenses total | %d this month", total, thisMonth));
    }

    private void markSaved() {
        statusSaveLabel.setText("Last saved: just now");
    }

    // ======================== IMPORT ========================

    private void setupRulesTable() {
        rulesTable.setItems(categorizationRules.getRuleEntries());

        // Import log table
        try {
            importLogs = FXCollections.observableArrayList(storage.loadImportLogs());
        } catch (IOException e) {
            importLogs = FXCollections.observableArrayList();
        }
        importLogTable.setItems(importLogs);
    }

    @FXML
    private void handleScanReceipt() {
        if (!receiptScanner.isTessDataAvailable()) {
            showMessage("OCR not available. Place eng.traineddata in ~/.expenseTracker/tessdata/", true);
            return;
        }

        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("Select Receipt Image");
        fileChooser.getExtensionFilters().addAll(
            new FileChooser.ExtensionFilter("Image Files", "*.jpg", "*.jpeg", "*.png", "*.bmp", "*.tiff", "*.tif"),
            new FileChooser.ExtensionFilter("All Files", "*.*")
        );
        File file = fileChooser.showOpenDialog(stage);
        if (file == null) return;

        showMessage("Processing receipt...", false);

        Thread ocrThread = new Thread(() -> {
            try {
                String ocrText = receiptScanner.performOcr(file);
                List<ImportItem> items = receiptScanner.parseReceipt(ocrText, LocalDate.now());

                // Auto-categorize
                for (ImportItem item : items) {
                    String cat = categorizationRules.categorize(item.getDescription());
                    if (cat != null) {
                        item.setCategory(cat);
                        item.setStatus("Auto-categorized");
                    }
                }

                javafx.application.Platform.runLater(() -> {
                    if (items.isEmpty()) {
                        showMessage("No line items found in the receipt.", true);
                        return;
                    }
                    ImportReviewDialog dialog = new ImportReviewDialog(
                        stage, items, categories, currencySymbol, ocrText, categorizationRules);
                    List<Expense> expenses = dialog.showAndWait();
                    if (expenses != null && !expenses.isEmpty()) {
                        saveLearnedRules(dialog);
                        importExpenses(expenses, file.getName(), "Receipt");
                    }
                });
            } catch (Exception e) {
                javafx.application.Platform.runLater(() ->
                    showMessage("OCR failed: " + e.getMessage(), true));
            }
        });
        ocrThread.setDaemon(true);
        ocrThread.start();
    }

    @FXML
    private void handleImportStatement() {
        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("Import Bank Statement");
        fileChooser.getExtensionFilters().addAll(
            new FileChooser.ExtensionFilter("Bank Statements", "*.pdf", "*.csv"),
            new FileChooser.ExtensionFilter("PDF Files", "*.pdf"),
            new FileChooser.ExtensionFilter("CSV Files", "*.csv"),
            new FileChooser.ExtensionFilter("All Files", "*.*")
        );
        File file = fileChooser.showOpenDialog(stage);
        if (file == null) return;

        try {
            String fileName = file.getName().toLowerCase();
            List<ImportItem> items;

            if (fileName.endsWith(".pdf")) {
                items = parsePdfStatement(file);
            } else {
                items = parseCsvStatement(file);
            }

            if (items == null) return; // User cancelled column mapping or error already shown

            // Auto-categorize
            for (ImportItem item : items) {
                String cat = categorizationRules.categorize(item.getDescription());
                if (cat != null) {
                    item.setCategory(cat);
                    item.setStatus("Auto-categorized");
                }
            }

            if (items.isEmpty()) {
                showMessage("No transactions found in the file.", true);
                return;
            }

            ImportReviewDialog dialog = new ImportReviewDialog(
                stage, items, categories, currencySymbol, null, categorizationRules);
            List<Expense> expenses = dialog.showAndWait();
            if (expenses != null && !expenses.isEmpty()) {
                saveLearnedRules(dialog);
                String type = fileName.endsWith(".pdf") ? "PDF" : "CSV";
                importExpenses(expenses, file.getName(), type);
            }
        } catch (Exception e) {
            showMessage("Import failed: " + e.getMessage(), true);
        }
    }

    private List<ImportItem> parsePdfStatement(File file) throws Exception {
        String text;
        try (org.apache.pdfbox.pdmodel.PDDocument doc = org.apache.pdfbox.pdmodel.PDDocument.load(file)) {
            org.apache.pdfbox.text.PDFTextStripper stripper = new org.apache.pdfbox.text.PDFTextStripper();
            text = stripper.getText(doc);
        }

        BankStatementParser[] parsers = { new FnbPdfParser(), new GenericPdfParser() };
        for (BankStatementParser parser : parsers) {
            if (parser.canParse(text)) {
                List<ImportItem> items = parser.parse(text);
                showMessage("Detected " + parser.getBankName() + ". " + items.size() + " transactions found.", false);
                return items;
            }
        }

        showMessage("Could not find transactions in this PDF. Try exporting as CSV instead.", true);
        return null;
    }

    private List<ImportItem> parseCsvStatement(File file) throws Exception {
        String text = new String(java.nio.file.Files.readAllBytes(file.toPath()));
        char delimiter = CsvStatementParser.detectDelimiter(text);
        String[] lines = text.split("\\r?\\n");
        if (lines.length < 2) {
            showMessage("CSV file is empty or has no data rows.", true);
            return null;
        }

        String[] headers = CsvStatementParser.parseHeaders(lines[0], delimiter);

        // Show column mapping dialog
        return showCsvMappingDialog(text, headers, delimiter, lines);
    }

    private List<ImportItem> showCsvMappingDialog(String text, String[] headers, char delimiter, String[] lines) {
        Stage mappingStage = new Stage();
        mappingStage.initModality(javafx.stage.Modality.WINDOW_MODAL);
        mappingStage.initOwner(stage);
        mappingStage.setTitle("CSV Column Mapping");

        javafx.collections.ObservableList<String> headerList = javafx.collections.FXCollections.observableArrayList(headers);

        Label titleLabel = new Label("Map CSV columns to expense fields");
        titleLabel.getStyleClass().add("section-title");

        Label dateLabel = new Label("Date column:");
        dateLabel.getStyleClass().add("form-label");
        ComboBox<String> dateColCombo = new ComboBox<>(headerList);
        dateColCombo.getStyleClass().add("combo-box");
        dateColCombo.setMaxWidth(Double.MAX_VALUE);

        Label amountLabel = new Label("Amount column:");
        amountLabel.getStyleClass().add("form-label");
        ComboBox<String> amountColCombo = new ComboBox<>(headerList);
        amountColCombo.getStyleClass().add("combo-box");
        amountColCombo.setMaxWidth(Double.MAX_VALUE);

        Label descLabel = new Label("Description column:");
        descLabel.getStyleClass().add("form-label");
        ComboBox<String> descColCombo = new ComboBox<>(headerList);
        descColCombo.getStyleClass().add("combo-box");
        descColCombo.setMaxWidth(Double.MAX_VALUE);

        Label dateFormatLabel = new Label("Date format:");
        dateFormatLabel.getStyleClass().add("form-label");
        ComboBox<String> dateFormatCombo = new ComboBox<>(
            javafx.collections.FXCollections.observableArrayList(CsvStatementParser.DATE_FORMATS));
        dateFormatCombo.getStyleClass().add("combo-box");
        dateFormatCombo.setValue("yyyy-MM-dd");
        dateFormatCombo.setMaxWidth(Double.MAX_VALUE);

        CheckBox negativeIsExpense = new CheckBox("Negative amounts are expenses");
        negativeIsExpense.getStyleClass().add("check-box");
        negativeIsExpense.setSelected(true);

        // Auto-select columns by common header names
        for (int i = 0; i < headers.length; i++) {
            String h = headers[i].toLowerCase().trim();
            if (h.contains("date")) dateColCombo.setValue(headers[i]);
            else if (h.contains("amount") || h.contains("debit") || h.contains("value")) amountColCombo.setValue(headers[i]);
            else if (h.contains("desc") || h.contains("narr") || h.contains("detail") || h.contains("reference")) descColCombo.setValue(headers[i]);
        }

        // Preview
        Label previewLabel = new Label("Preview (first 3 rows):");
        previewLabel.getStyleClass().add("form-label");
        TextArea previewArea = new TextArea();
        previewArea.setEditable(false);
        previewArea.getStyleClass().add("import-preview");
        previewArea.setPrefHeight(80);
        StringBuilder preview = new StringBuilder();
        for (int i = 0; i < Math.min(4, lines.length); i++) {
            preview.append(lines[i]).append("\n");
        }
        previewArea.setText(preview.toString());

        final List<ImportItem>[] resultHolder = new List[]{null};

        Button okBtn = new Button("Parse");
        okBtn.getStyleClass().add("success-button");
        okBtn.setOnAction(e -> {
            String dateCol = dateColCombo.getValue();
            String amountCol = amountColCombo.getValue();
            String descCol = descColCombo.getValue();
            if (dateCol == null || amountCol == null) {
                return;
            }
            int dateIdx = java.util.Arrays.asList(headers).indexOf(dateCol);
            int amountIdx = java.util.Arrays.asList(headers).indexOf(amountCol);
            int descIdx = descCol != null ? java.util.Arrays.asList(headers).indexOf(descCol) : -1;

            resultHolder[0] = CsvStatementParser.parse(text, delimiter, dateIdx, amountIdx,
                descIdx, dateFormatCombo.getValue(), negativeIsExpense.isSelected());
            mappingStage.close();
        });

        Button cancelBtn = new Button("Cancel");
        cancelBtn.getStyleClass().add("danger-button");
        cancelBtn.setOnAction(e -> mappingStage.close());

        javafx.scene.layout.HBox btnBox = new javafx.scene.layout.HBox(10, okBtn, cancelBtn);
        btnBox.setAlignment(javafx.geometry.Pos.CENTER_RIGHT);

        VBox layout = new VBox(8, titleLabel, dateLabel, dateColCombo, amountLabel, amountColCombo,
            descLabel, descColCombo, dateFormatLabel, dateFormatCombo, negativeIsExpense,
            previewLabel, previewArea, btnBox);
        layout.setPadding(new javafx.geometry.Insets(15));
        layout.getStyleClass().add("root-pane");

        Scene scene = new Scene(layout, 450, 550);
        scene.getStylesheets().add(getClass().getResource("/styles.css").toExternalForm());
        mappingStage.setScene(scene);
        mappingStage.showAndWait();

        return resultHolder[0];
    }

    private void saveLearnedRules(ImportReviewDialog dialog) {
        Map<String, String> learned = dialog.getLearnedRules();
        if (learned.isEmpty()) return;
        for (Map.Entry<String, String> entry : learned.entrySet()) {
            if (!categorizationRules.getRules().containsKey(entry.getKey())) {
                categorizationRules.addRule(entry.getKey(), entry.getValue());
            }
        }
        try {
            storage.saveCategorizationRules(categorizationRules.getRules());
        } catch (IOException ex) {
            System.err.println("Failed to save learned rules: " + ex.getMessage());
        }
        rulesTable.refresh();
    }

    private void importExpenses(List<Expense> expenses, String sourceFile, String sourceType) {
        // Separate income from expenses
        List<Expense> actualExpenses = new ArrayList<>();
        List<Expense> incomeItems = new ArrayList<>();
        for (Expense exp : expenses) {
            if ("Income".equalsIgnoreCase(exp.getCategory())) {
                incomeItems.add(exp);
            } else {
                actualExpenses.add(exp);
            }
        }

        // Tag expenses with a unique import ID
        String importId = "IMP-" + System.currentTimeMillis();
        for (Expense exp : actualExpenses) {
            exp.setImportId(importId);
        }

        // Add expenses
        if (!actualExpenses.isEmpty()) {
            BulkAddExpenseCommand cmd = new BulkAddExpenseCommand(manager, actualExpenses);
            manager.executeCommand(cmd);
            try {
                storage.saveExpenses(manager.getExpensesForSave());
                storage.saveCategories(categories);
            } catch (IOException ex) {
                manager.undo();
                showMessage("Failed to save imported expenses: " + ex.getMessage(), true);
                return;
            }
        }

        // Add income to monthly income tracker
        double totalIncomeAdded = 0;
        if (!incomeItems.isEmpty()) {
            Map<YearMonth, Double> incomeByMonth = new LinkedHashMap<>();
            for (Expense inc : incomeItems) {
                YearMonth ym = YearMonth.from(inc.getDate());
                incomeByMonth.merge(ym, inc.getAmount(), Double::sum);
            }
            for (Map.Entry<YearMonth, Double> entry : incomeByMonth.entrySet()) {
                double existing = incomes.getOrDefault(entry.getKey(), 0.0);
                incomes.put(entry.getKey(), existing + entry.getValue());
                totalIncomeAdded += entry.getValue();
            }
            try {
                storage.saveIncomes(incomes);
            } catch (IOException ex) {
                System.err.println("Failed to save incomes: " + ex.getMessage());
            }
        }

        // Log the import
        int totalItems = actualExpenses.size() + incomeItems.size();
        ImportLog log = new ImportLog(importId, java.time.LocalDateTime.now(), sourceFile, sourceType, totalItems);
        importLogs.add(log);
        try {
            storage.saveImportLogs(new ArrayList<>(importLogs));
        } catch (IOException ex) {
            System.err.println("Failed to save import log: " + ex.getMessage());
        }

        refreshTable();

        // Build summary message
        StringBuilder msg = new StringBuilder();
        msg.append(actualExpenses.size()).append(" expenses imported");
        if (totalIncomeAdded > 0) {
            msg.append(", ").append(fmt(totalIncomeAdded)).append(" income added across ")
               .append(incomeItems.size()).append(" transaction(s)");
        }
        msg.append("!");
        showMessage(msg.toString(), false);
    }

    @FXML
    private void handleAddRule() {
        Stage ruleStage = new Stage();
        ruleStage.initModality(javafx.stage.Modality.WINDOW_MODAL);
        ruleStage.initOwner(stage);
        ruleStage.setTitle("Add Categorization Rule");

        Label titleLabel = new Label("Add Auto-Categorization Rule");
        titleLabel.getStyleClass().add("section-title");

        Label keywordLabel = new Label("Keyword (matched in description):");
        keywordLabel.getStyleClass().add("form-label");
        TextField keywordField = new TextField();
        keywordField.getStyleClass().add("text-field");
        keywordField.setPromptText("e.g., SPAR, UBER, NETFLIX");

        Label catLabel = new Label("Category:");
        catLabel.getStyleClass().add("form-label");
        ComboBox<String> catCombo = new ComboBox<>(categories);
        catCombo.setEditable(true);
        catCombo.getStyleClass().add("combo-box");
        catCombo.setMaxWidth(Double.MAX_VALUE);

        Button addBtn = new Button("Add Rule");
        addBtn.getStyleClass().add("success-button");
        addBtn.setOnAction(e -> {
            String keyword = keywordField.getText().trim();
            String cat = catCombo.getValue();
            if (cat == null || cat.trim().isEmpty()) {
                cat = catCombo.getEditor().getText().trim();
            }
            if (keyword.isEmpty() || cat.isEmpty()) return;

            if (!categories.contains(cat)) {
                categories.add(cat);
                try { storage.saveCategories(categories); } catch (IOException ex) { /* ignore */ }
            }
            categorizationRules.addRule(keyword, cat);
            try { storage.saveCategorizationRules(categorizationRules.getRules()); } catch (IOException ex) { /* ignore */ }
            rulesTable.refresh();
            ruleStage.close();
        });

        Button cancelBtn = new Button("Cancel");
        cancelBtn.getStyleClass().add("danger-button");
        cancelBtn.setOnAction(e -> ruleStage.close());

        javafx.scene.layout.HBox btnBox = new javafx.scene.layout.HBox(10, addBtn, cancelBtn);

        VBox layout = new VBox(8, titleLabel, keywordLabel, keywordField, catLabel, catCombo, btnBox);
        layout.setPadding(new javafx.geometry.Insets(15));
        layout.getStyleClass().add("root-pane");

        Scene scene = new Scene(layout, 350, 280);
        scene.getStylesheets().add(getClass().getResource("/styles.css").toExternalForm());
        ruleStage.setScene(scene);
        ruleStage.showAndWait();
    }

    @FXML
    private void handleRemoveRule() {
        CategorizationRules.RuleEntry selected = rulesTable.getSelectionModel().getSelectedItem();
        if (selected == null) {
            showMessage("Please select a rule to remove", true);
            return;
        }
        categorizationRules.removeRule(selected.getKeyword());
        try {
            storage.saveCategorizationRules(categorizationRules.getRules());
        } catch (IOException ex) {
            showMessage("Failed to save rules: " + ex.getMessage(), true);
        }
        rulesTable.refresh();
        showMessage("Rule removed.", false);
    }

    @FXML
    private void handleDeleteImport() {
        ImportLog selected = importLogTable.getSelectionModel().getSelectedItem();
        if (selected == null) {
            showMessage("Please select an import to delete", true);
            return;
        }

        // Count how many expenses will be removed
        long count = manager.getExpenses().stream()
            .filter(e -> selected.getImportId().equals(e.getImportId()))
            .count();

        Alert confirmation = new Alert(Alert.AlertType.CONFIRMATION);
        confirmation.setTitle("Delete Import");
        confirmation.setHeaderText("Delete import from " + selected.getSourceFile() + "?");
        confirmation.setContentText("This will remove " + count + " expense(s) that were imported on " +
            selected.getTimestampDisplay() + ".");
        confirmation.getDialogPane().getStylesheets().add(getClass().getResource("/styles.css").toExternalForm());
        confirmation.getDialogPane().getStyleClass().add("dialog-pane");

        if (confirmation.showAndWait().orElse(null) != javafx.scene.control.ButtonType.OK) {
            return;
        }

        // Remove all expenses with this import ID
        List<Expense> toRemove = manager.getExpenses().stream()
            .filter(e -> selected.getImportId().equals(e.getImportId()))
            .collect(java.util.stream.Collectors.toList());

        if (!toRemove.isEmpty()) {
            // Undoable command: execute removes, undo re-adds
            manager.executeCommand(new Command() {
                @Override public void execute() {
                    for (Expense exp : toRemove) {
                        manager.removeExpense(exp);
                    }
                }
                @Override public void undo() {
                    for (Expense exp : toRemove) {
                        manager.addExpense(exp);
                    }
                }
            });
        }

        // Remove the log entry
        importLogs.remove(selected);
        try {
            storage.saveImportLogs(new ArrayList<>(importLogs));
            storage.saveExpenses(manager.getExpensesForSave());
        } catch (IOException ex) {
            showMessage("Failed to save after delete: " + ex.getMessage(), true);
            return;
        }

        refreshTable();
        showMessage(count + " expenses from import deleted.", false);
    }

    private void updateFilterCategoryCombo() {
        suppressFilterListener = true;
        try {
            String current = filterCategoryCombo.getValue();
            ObservableList<String> filterItems = FXCollections.observableArrayList("All Categories");
            filterItems.addAll(categories);
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

    @FXML
    private void handleClearFilters() {
        filterCategoryCombo.setValue("All Categories");
        filterMinAmount.clear();
        filterMaxAmount.clear();
        searchField.clear();
    }

    private String fmt(double amount) {
        return currencySymbol + String.format("%.2f", amount);
    }

    private String getCategoryColor(String category) {
        int hash = Math.abs(category.hashCode());
        return CATEGORY_COLORS[hash % CATEGORY_COLORS.length];
    }

    private PauseTransition messageFade;

    private void showMessage(String message, boolean isError) {
        // Route to the correct label based on the active tab
        Label target;
        int activeTab = tabPane.getSelectionModel().getSelectedIndex();
        if (activeTab == 2) {
            target = importErrorLabel;
        } else if (activeTab == 1) {
            target = addRecurringErrorLabel;
        } else {
            target = expenseErrorLabel;
        }
        showMessageOn(message, isError, target);
    }

    private void showMessageOn(String message, boolean isError, Label target) {
        // Clear all error labels first
        for (Label lbl : new Label[]{errorLabel, expenseErrorLabel, addRecurringErrorLabel, editRecurringErrorLabel, importErrorLabel}) {
            if (lbl != target) {
                lbl.setText("");
                lbl.setOpacity(0);
            }
        }

        target.setText(message);
        target.setOpacity(1.0);
        if (message.isEmpty()) {
            target.getStyleClass().setAll("error-label");
        } else if (isError) {
            target.getStyleClass().setAll("error-label", "error-message");
        } else {
            target.getStyleClass().setAll("error-label", "success-message");
        }
        // Auto-fade success messages after 3 seconds
        if (!isError && !message.isEmpty()) {
            if (messageFade != null) messageFade.stop();
            messageFade = new PauseTransition(Duration.seconds(3));
            messageFade.setOnFinished(e -> {
                FadeTransition fade = new FadeTransition(Duration.millis(500), target);
                fade.setFromValue(1.0);
                fade.setToValue(0.0);
                fade.play();
            });
            messageFade.playFromStart();
        }
    }
}
