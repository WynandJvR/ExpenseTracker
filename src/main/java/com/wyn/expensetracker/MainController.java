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
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
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

    // --- Navigation ---
    @FXML private StackPane contentArea;
    @FXML private ToggleButton navDashboard;
    @FXML private ToggleButton navExpenses;
    @FXML private ToggleButton navRecurring;
    @FXML private ToggleButton navImport;
    @FXML private ToggleButton navAnalytics;

    // --- Views ---
    @FXML private ScrollPane dashboardView;
    @FXML private ScrollPane expensesView;
    @FXML private ScrollPane recurringView;
    @FXML private ScrollPane importView;
    @FXML private ScrollPane analyticsView;
    @FXML private TitledPane addExpensePane;

    // --- Expense form ---
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

    // --- Left panel: Income tab ---
    @FXML private TableView<Expense> incomeTable;
    @FXML private Label incomeTabSummary;
    @FXML private Label incomeErrorLabel;

    // --- Left panel: Import tab ---
    @FXML private Label importErrorLabel;
    @FXML private TableView<CategorizationRules.RuleEntry> rulesTable;
    // importHistoryList removed — shown in dialog via handleShowImportHistory

    // --- Filters ---
    @FXML private ComboBox<String> filterCategoryCombo;
    @FXML private TextField filterMinAmount;
    @FXML private TextField filterMaxAmount;
    @FXML private HBox filterFieldsBox;
    @FXML private Button filterToggleButton;

    // --- Right panel ---
    @FXML private ComboBox<Integer> yearCombo;
    @FXML private ComboBox<Month> monthCombo;
    @FXML private TableView<Expense> expenseTable;
    @FXML private TableColumn<Expense, Double> amountColumn;
    @FXML private TableColumn<Expense, String> expenseCategoryColumn;
    @FXML private TableColumn<Expense, LocalDate> dateColumn;
    @FXML private TableColumn<Expense, String> descriptionColumn;
    @FXML private ComboBox<String> chartPeriodCombo;
    @FXML private PieChart categoryChart;
    @FXML private StackedBarChart<String, Number> monthlyTrendChart;
    @FXML private FlowPane trendChartLegend;
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

    // --- Analytics charts ---
    @FXML private TabPane analyticsTabPane;
    @FXML private BarChart<String, Number> incomeVsExpensesChart;
    @FXML private BarChart<String, Number> budgetVsActualChart;
    @FXML private Label budgetVsActualSubtitle;
    @FXML private LineChart<Number, Number> cumulativeSpendingChart;
    @FXML private Label cumulativeSubtitle;
    @FXML private StackedAreaChart<String, Number> categoryTrendChart;
    @FXML private LineChart<String, Number> yearOverYearChart;
    @FXML private PieChart recurringVsOneTimeChart;

    // --- Projections tab ---
    @FXML private Tab projectionsTab;
    @FXML private VBox projectionsContent;
    @FXML private Label projExpenses;
    @FXML private Label projExpensesSubtitle;
    @FXML private Label projIncome;
    @FXML private Label projIncomeSubtitle;
    @FXML private Label projNetSavings;
    @FXML private Label projNetSavingsSubtitle;
    @FXML private Label projCumulativeSavings;
    @FXML private Label projPeriodLabel;
    @FXML private Label projMethodLabel;
    @FXML private Label projTrendIndicator;
    @FXML private Label projNoDataLabel;
    @FXML private LineChart<String, Number> projOutlookChart;
    @FXML private AreaChart<String, Number> projBalanceChart;
    @FXML private StackedBarChart<String, Number> projCategoryChart;

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
    private ObservableList<Expense> incomeList;
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
    private boolean projectionsNeedUpdate = true;
    private ProjectionEngine projectionEngine = new ProjectionEngine();
    private String currentViewName = "dashboard";

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

        // Setup navigation
        setupNavigation();

        // Initial date pickers
        datePicker.setValue(LocalDate.now());
        addRecurringDatePicker.setValue(LocalDate.now());

        // Select default view
        navDashboard.setSelected(true);

        // Initial refresh
        refreshTable();

        // Restore UI state from previous session
        restoreUIState();

        // Save UI state on window close
        stage.setOnCloseRequest(e -> saveUIState());
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

        // Projected recurring income
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

        // Row factory: style excluded/income rows + right-click context menu
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
            MenuItem toggleExclude = new MenuItem("Exclude from Analytics");
            toggleExclude.setOnAction(e -> {
                Expense item = row.getItem();
                if (item != null) {
                    boolean prev = item.isExcluded();
                    item.setExcluded(!prev);
                    try {
                        storage.saveExpenses(manager.getExpensesForSave());
                    } catch (IOException ex) {
                        item.setExcluded(prev);
                        showMessage("Failed to save: " + ex.getMessage(), true);
                    }
                    refreshTable();
                }
            });
            MenuItem toggleIncome = new MenuItem("Mark as Income");
            toggleIncome.setOnAction(e -> {
                Expense item = row.getItem();
                if (item != null) {
                    boolean prev = item.isIncome();
                    item.setIncome(!prev);
                    try {
                        storage.saveExpenses(manager.getExpensesForSave());
                    } catch (IOException ex) {
                        item.setIncome(prev);
                        showMessage("Failed to save: " + ex.getMessage(), true);
                    }
                    refreshTable();
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
                        item.setIncome(true); // Refunds are income (money received)
                    }
                    try {
                        storage.saveExpenses(manager.getExpensesForSave());
                    } catch (IOException ex) {
                        item.setRefund(prevRefund);
                        item.setIncome(prevIncome);
                        showMessage("Failed to save: " + ex.getMessage(), true);
                    }
                    refreshTable();
                }
            });
            MenuItem makeRecurring = new MenuItem("Make Recurring...");
            makeRecurring.setOnAction(e -> {
                Expense item = row.getItem();
                if (item != null && item.getRecurringId() == null && !(item instanceof RecurringExpense)) {
                    showMakeRecurringDialog(item);
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
                // Only show "Make Recurring" for one-time expenses (not already recurring)
                makeRecurring.setVisible(item != null && item.getRecurringId() == null
                    && !(item instanceof RecurringExpense));
            });
            menu.getItems().addAll(toggleExclude, toggleIncome, toggleRefund, new SeparatorMenuItem(), makeRecurring);
            row.setContextMenu(menu);
            return row;
        });

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

        // Income table
        incomeTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        incomeList = FXCollections.observableArrayList();
        incomeTable.setItems(incomeList);
        Label incomePlaceholder = new Label("No income transactions for this period.");
        incomePlaceholder.getStyleClass().add("empty-state-label");
        incomeTable.setPlaceholder(incomePlaceholder);
        incomeTable.setRowFactory(tv -> {
            TableRow<Expense> row = new TableRow<>() {
                @Override
                protected void updateItem(Expense item, boolean empty) {
                    super.updateItem(item, empty);
                    getStyleClass().removeAll("excluded-row");
                    if (empty || item == null) {
                        setStyle("");
                        setOpacity(1.0);
                    } else if (item.isExcluded()) {
                        getStyleClass().add("excluded-row");
                        setOpacity(0.45);
                        setStyle("");
                    } else {
                        setStyle("-fx-background-color: rgba(76, 175, 80, 0.12);");
                        setOpacity(1.0);
                    }
                }
            };
            ContextMenu menu = new ContextMenu();
            MenuItem toggleExclude = new MenuItem("Exclude from Analytics");
            toggleExclude.setOnAction(e -> {
                Expense item = row.getItem();
                if (item != null) {
                    boolean prev = item.isExcluded();
                    item.setExcluded(!prev);
                    try {
                        storage.saveExpenses(manager.getExpensesForSave());
                    } catch (IOException ex) {
                        item.setExcluded(prev);
                        showMessage("Failed to save: " + ex.getMessage(), true);
                    }
                    refreshTable();
                }
            });
            menu.setOnShowing(e -> {
                Expense item = row.getItem();
                toggleExclude.setText(item != null && item.isExcluded()
                    ? "Include in Analytics" : "Exclude from Analytics");
            });
            menu.getItems().add(toggleExclude);
            row.setContextMenu(menu);
            return row;
        });
        incomeTable.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.DELETE) handleDeleteIncome();
        });
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

        // Period selectors — skip during refreshTable() which calls these methods itself
        yearCombo.valueProperty().addListener((observable, oldValue, newValue) -> {
            if (!refreshingTable) {
                updateTotalExpenses();
                updateCharts();
                updateIncomeField();
                refreshIncomeTable();
            }
        });
        monthCombo.valueProperty().addListener((observable, oldValue, newValue) -> {
            if (!refreshingTable) {
                updateTotalExpenses();
                updateCharts();
                updateIncomeField();
                refreshIncomeTable();
            }
        });

        // Income field — projected per-month override
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

        // Projections tab — lazy compute
        analyticsTabPane.getSelectionModel().selectedItemProperty().addListener((obs, oldTab, newTab) -> {
            if (newTab == projectionsTab && projectionsNeedUpdate) {
                updateProjections();
            }
        });

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
                        if (!"expenses".equals(currentViewName)) {
                            navExpenses.setSelected(true);
                        }
                        addExpensePane.setExpanded(true);
                        amountField.requestFocus();
                        event.consume();
                        break;
                    case E:
                        handleExport();
                        event.consume();
                        break;
                    case F:
                        if (!"expenses".equals(currentViewName)) {
                            navExpenses.setSelected(true);
                        }
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

    // ======================== UI STATE PERSISTENCE ========================

    private void saveUIState() {
        try {
            Map<String, String> state = new HashMap<>();
            state.put("activeView", currentViewName);
            state.put("analyticsTab", String.valueOf(analyticsTabPane.getSelectionModel().getSelectedIndex()));
            state.put("chartPeriod", chartPeriodCombo.getValue());
            storage.saveUIState(state);
        } catch (IOException e) {
            System.err.println("Error saving UI state: " + e.getMessage());
        }
    }

    private void restoreUIState() {
        Map<String, String> state = storage.loadUIState();
        if (state.isEmpty()) return;

        // Restore active view
        if (state.containsKey("activeView")) {
            switch (state.get("activeView")) {
                case "expenses" -> navExpenses.setSelected(true);
                case "recurring" -> navRecurring.setSelected(true);
                case "import" -> navImport.setSelected(true);
                case "analytics" -> navAnalytics.setSelected(true);
                default -> navDashboard.setSelected(true);
            }
        }

        // Restore analytics tab selection
        if (state.containsKey("analyticsTab")) {
            try {
                int tabIndex = Integer.parseInt(state.get("analyticsTab"));
                if (tabIndex >= 0 && tabIndex < analyticsTabPane.getTabs().size()) {
                    analyticsTabPane.getSelectionModel().select(tabIndex);
                }
            } catch (NumberFormatException e) { /* ignore */ }
        }

        // Restore chart period
        if (state.containsKey("chartPeriod")) {
            String period = state.get("chartPeriod");
            if (chartPeriodCombo.getItems().contains(period)) {
                chartPeriodCombo.setValue(period);
            }
        }
    }

    // ======================== NAVIGATION ========================

    private void setupNavigation() {
        ToggleGroup navGroup = new ToggleGroup();
        navDashboard.setToggleGroup(navGroup);
        navExpenses.setToggleGroup(navGroup);
        navRecurring.setToggleGroup(navGroup);
        navImport.setToggleGroup(navGroup);
        navAnalytics.setToggleGroup(navGroup);

        navGroup.selectedToggleProperty().addListener((obs, oldToggle, newToggle) -> {
            if (newToggle == null) {
                if (oldToggle != null) oldToggle.setSelected(true);
                return;
            }
            if (newToggle == navDashboard) switchView("dashboard");
            else if (newToggle == navExpenses) switchView("expenses");
            else if (newToggle == navRecurring) switchView("recurring");
            else if (newToggle == navImport) switchView("import");
            else if (newToggle == navAnalytics) switchView("analytics");
        });
    }

    private void switchView(String viewName) {
        dashboardView.setVisible(false); dashboardView.setManaged(false);
        expensesView.setVisible(false); expensesView.setManaged(false);
        recurringView.setVisible(false); recurringView.setManaged(false);
        importView.setVisible(false); importView.setManaged(false);
        analyticsView.setVisible(false); analyticsView.setManaged(false);

        ScrollPane target = switch (viewName) {
            case "expenses" -> expensesView;
            case "recurring" -> recurringView;
            case "import" -> importView;
            case "analytics" -> analyticsView;
            default -> dashboardView;
        };
        target.setVisible(true);
        target.setManaged(true);
        currentViewName = viewName;

        FadeTransition fade = new FadeTransition(Duration.millis(150), target);
        fade.setFromValue(0.0);
        fade.setToValue(1.0);
        fade.play();
    }

    @FXML
    private void handleToggleFilters() {
        boolean showing = filterFieldsBox.isVisible();
        filterFieldsBox.setVisible(!showing);
        filterFieldsBox.setManaged(!showing);
        filterToggleButton.setText(showing ? "Filters" : "Hide Filters");
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
        confirmation.initOwner(stage);
        confirmation.setTitle("Confirm Deletion");
        confirmation.setHeaderText(null);
        confirmation.setContentText("Are you sure you want to delete this expense?");
        confirmation.getDialogPane().getStylesheets().add(getClass().getResource("/styles.css").toExternalForm());

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

    private void showMakeRecurringDialog(Expense expense) {
        Stage dialog = new Stage();
        dialog.initModality(javafx.stage.Modality.WINDOW_MODAL);
        dialog.initOwner(stage);
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

        Label errorLabel2 = new Label();
        errorLabel2.getStyleClass().add("error-label");

        Button confirmBtn = new Button("Make Recurring");
        confirmBtn.getStyleClass().add("success-button");
        confirmBtn.setOnAction(e -> {
            RecurrenceType freq = freqCombo.getValue();
            if (freq == null) {
                errorLabel2.setText("Please select a frequency");
                return;
            }
            LocalDate endDate = endDatePicker.getValue();

            // Create recurring expense with same details
            RecurringExpense recurring = new RecurringExpense(
                expense.getAmount(), expense.getCategory(), expense.getDate(),
                expense.getDescription() != null ? expense.getDescription() : "",
                freq, endDate);
            if (expense.isIncome()) recurring.setIncome(true);

            // Add the recurring rule — keep the original expense as historical record
            manager.executeCommand(new AddExpenseCommand(manager, recurring));
            try {
                manager.generateRecurringExpenses(LocalDate.now());
                storage.saveExpenses(manager.getExpensesForSave());
                recurringList.setAll(manager.getBaseRecurringExpenses());
            } catch (IOException ex) {
                manager.undo();
                showMessage("Failed to save: " + ex.getMessage(), true);
                dialog.close();
                return;
            }
            refreshTable();
            showMessage("Expense converted to recurring (" + freq.toString().toLowerCase() + ")", false);
            dialog.close();
        });

        Button cancelBtn = new Button("Cancel");
        cancelBtn.getStyleClass().add("danger-button");
        cancelBtn.setOnAction(e -> dialog.close());

        HBox buttons = new HBox(10, confirmBtn, cancelBtn);
        buttons.setAlignment(javafx.geometry.Pos.CENTER_RIGHT);

        VBox content = new VBox(12, header, freqLabel, freqCombo, endLabel, endDatePicker, errorLabel2, buttons);
        content.setPadding(new javafx.geometry.Insets(20));
        content.getStyleClass().add("root-pane");

        Scene scene = new Scene(content, 400, 380);
        scene.getStylesheets().add(getClass().getResource("/styles.css").toExternalForm());
        dialog.setScene(scene);
        dialog.showAndWait();
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
        confirmation.initOwner(stage);
        confirmation.setTitle("Confirm Deletion");
        confirmation.setHeaderText(null);
        confirmation.setContentText(String.format(
            "Are you sure you want to delete this recurring expense?\nThis will also remove %d generated expenses.", generatedCount));
        confirmation.getDialogPane().getStylesheets().add(getClass().getResource("/styles.css").toExternalForm());

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
    private void handleDetectRecurring() {
        RecurringPatternDetector detector = new RecurringPatternDetector();
        List<RecurringPatternDetector.DetectedPattern> patterns = detector.detectPatterns(
            manager.getExpenses(), manager.getBaseRecurringExpenses());

        if (patterns.isEmpty()) {
            showMessage("No recurring patterns detected in your expenses.", false);
            return;
        }

        showDetectedPatternsDialog(patterns);
    }

    private void showDetectedPatternsDialog(List<RecurringPatternDetector.DetectedPattern> patterns) {
        Stage dialog = new Stage();
        dialog.initModality(javafx.stage.Modality.WINDOW_MODAL);
        dialog.initOwner(stage);
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
        selectCol.setCellFactory(javafx.scene.control.cell.CheckBoxTableCell.forTableColumn(selectCol));
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
                    null // no end date
                );
                manager.executeCommand(new AddExpenseCommand(manager, recurring));
                commandCount++;

                if (removeOriginals.isSelected()) {
                    for (Expense original : pattern.getMatchingExpenses()) {
                        manager.executeCommand(new DeleteExpenseCommand(manager, original));
                        commandCount++;
                    }
                }
            }

            try {
                manager.generateRecurringExpenses(LocalDate.now());
                storage.saveExpenses(manager.getExpensesForSave());
                recurringList.setAll(manager.getBaseRecurringExpenses());
                refreshTable();
            } catch (IOException ex) {
                // Rollback all commands from the loop
                for (int i = 0; i < commandCount; i++) {
                    if (manager.canUndo()) manager.undo();
                }
                showMessage("Failed to save: " + ex.getMessage(), true);
                return;
            }

            int converted = selected.size();
            showMessage(converted + " recurring " + (converted == 1 ? "expense" : "expenses")
                + " created successfully!", false);
            dialog.close();
        });

        Button cancelBtn = new Button("Cancel");
        cancelBtn.getStyleClass().add("danger-button");
        cancelBtn.setOnAction(e -> dialog.close());

        HBox actionButtons = new HBox(10, convertBtn, cancelBtn);
        actionButtons.setAlignment(javafx.geometry.Pos.CENTER_RIGHT);

        VBox content = new VBox(12, header, subtitle, patternTable, selectionButtons,
            removeOriginals, statusLabel, actionButtons);
        content.setPadding(new javafx.geometry.Insets(20));
        content.getStyleClass().add("root-pane");

        Scene scene = new Scene(content, 700, 500);
        scene.getStylesheets().add(getClass().getResource("/styles.css").toExternalForm());
        dialog.setScene(scene);
        dialog.showAndWait();
    }

    @FXML
    private void handleAddCategory() {
        TextInputDialog dialog = new TextInputDialog();
        dialog.initOwner(stage);
        dialog.setTitle("Add Category");
        dialog.setHeaderText("Enter a new category:");
        dialog.setContentText("Category:");
        dialog.getDialogPane().getStylesheets().add(getClass().getResource("/styles.css").toExternalForm());

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

    // ======================== INCOME TABLE ========================

    private void refreshIncomeTable() {
        Integer selectedYear = yearCombo.getValue();
        Month selectedMonth = monthCombo.getValue();
        if (selectedYear == null || selectedMonth == null) {
            incomeList.clear();
            incomeTabSummary.setText("");
            return;
        }
        YearMonth selectedYearMonth = YearMonth.of(selectedYear, selectedMonth);
        List<Expense> monthIncome = manager.getExpenses().stream()
            .filter(e -> e.isIncome() && YearMonth.from(e.getDate()).equals(selectedYearMonth))
            .sorted(Comparator.comparing(Expense::getDate).reversed())
            .collect(Collectors.toList());
        incomeList.setAll(monthIncome);
        if (monthIncome.isEmpty()) {
            incomeTabSummary.setText("No income this month.");
        } else {
            double total = monthIncome.stream().mapToDouble(Expense::getAmount).sum();
            incomeTabSummary.setText(String.format("%d transaction(s) — Total: %s", monthIncome.size(), fmt(total)));
        }
    }

    @FXML
    private void handleDeleteIncome() {
        Expense selected = incomeTable.getSelectionModel().getSelectedItem();
        if (selected == null) {
            incomeErrorLabel.setText("Select an income entry to delete.");
            incomeErrorLabel.getStyleClass().setAll("error-label", "error-message");
            return;
        }

        Alert confirmation = new Alert(Alert.AlertType.CONFIRMATION);
        confirmation.initOwner(stage);
        confirmation.setTitle("Delete Income");
        confirmation.setHeaderText(null);
        confirmation.setContentText("Delete this income entry (" + fmt(selected.getAmount()) + ")?");
        confirmation.getDialogPane().getStylesheets().add(getClass().getResource("/styles.css").toExternalForm());

        Optional<ButtonType> result = confirmation.showAndWait();
        if (result.isPresent() && result.get() == ButtonType.OK) {
            manager.executeCommand(new DeleteExpenseCommand(manager, selected));
            try {
                storage.saveExpenses(manager.getExpensesForSave());
                refreshTable();
                incomeErrorLabel.setText("Income deleted.");
                incomeErrorLabel.getStyleClass().setAll("error-label", "success-message");
            } catch (Exception ex) {
                manager.undo();
                incomeErrorLabel.setText("Error: " + ex.getMessage());
                incomeErrorLabel.getStyleClass().setAll("error-label", "error-message");
            }
        }
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
        dialog.initOwner(stage);
        dialog.setTitle("Set Budget");
        dialog.setHeaderText("Set monthly budget for: " + selected.getCategory());
        dialog.setContentText("Budget amount:");
        dialog.getDialogPane().getStylesheets().add(getClass().getResource("/styles.css").toExternalForm());

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
            refreshIncomeTable();
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
            incomeField.setPromptText("Leave empty to use default");
            suppressIncomeListener = false;
            return;
        }
        YearMonth selectedYearMonth = YearMonth.of(selectedYear, selectedMonth);
        Double monthProjected = incomes.get(selectedYearMonth);
        if (monthProjected != null) {
            incomeField.setText(String.format("%.2f", monthProjected));
            incomeField.setPromptText("Clear to use default");
        } else {
            incomeField.setText("");
            incomeField.setPromptText(recurringIncome > 0
                ? String.format("Using default: %.2f", recurringIncome)
                : "Set projected income");
        }
        suppressIncomeListener = false;
    }

    private void updateYearList() {
        // Capture selections BEFORE setAll — setAll can clear ComboBox selection
        Integer selectedYear = yearCombo.getValue();
        Month selectedMonth = monthCombo.getValue();

        Set<Integer> years = new TreeSet<>();
        for (Expense expense : expenseList) {
            years.add(expense.getDate().getYear());
        }
        yearList.setAll(years);

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

        // Restore month — setAll on yearList may have indirectly cleared it
        if (selectedMonth != null) {
            monthCombo.setValue(selectedMonth);
        } else if (yearCombo.getValue() != null) {
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

        // Check if this month has real imported data (not just recurring projections)
        boolean hasImportedData = expenseList.stream()
            .anyMatch(e -> !e.isIncome() && e.getImportId() != null
                && YearMonth.from(e.getDate()).equals(selectedYearMonth));

        double total;
        if (hasImportedData) {
            // Actual month: only count real expenses (exclude recurring projections)
            total = filteredData.stream()
                .filter(e -> !e.isExcluded() && !e.isIncome() && e.getRecurringId() == null)
                .mapToDouble(Expense::getAmount)
                .sum();
        } else {
            // Projected month: use recurring expenses as forecast
            total = filteredData.stream()
                .filter(e -> !e.isExcluded() && !e.isIncome())
                .mapToDouble(Expense::getAmount)
                .sum();
        }

        double actualIncome = filteredData.stream()
            .filter(e -> !e.isExcluded() && e.isIncome())
            .mapToDouble(Expense::getAmount)
            .sum();
        double projectedIncome = incomes.getOrDefault(selectedYearMonth, recurringIncome);

        // Use actual income when available, otherwise fall back to projected
        boolean hasActualIncome = actualIncome > 0;
        double income = hasActualIncome ? actualIncome : projectedIncome;

        boolean isProjected = !hasImportedData && !hasActualIncome;
        String prefix = isProjected ? "Projected " : "";

        totalLabel.setText(String.format("%sExpenses for %s %d: %s", prefix,
                selectedMonth.getDisplayName(TextStyle.FULL, Locale.ENGLISH), selectedYear, fmt(total)));

        double moneySaved = income - total;
        if (moneySaved >= 0) {
            String label = isProjected ? "Projected Savings: " : "Money Saved: ";
            moneySavedLabel.setText(label + fmt(moneySaved));
            moneySavedLabel.getStyleClass().setAll("saved-label");
        } else {
            String label = isProjected ? "Projected Overspend: " : "Overspent: ";
            moneySavedLabel.setText(label + fmt(Math.abs(moneySaved)));
            moneySavedLabel.getStyleClass().setAll("saved-label", "overspent-label");
        }

        Map<String, Double> categoryMap = filteredData.stream()
            .filter(e -> !e.isExcluded() && !e.isIncome()
                && (!hasImportedData || e.getRecurringId() == null))
            .collect(Collectors.groupingBy(
                Expense::getCategory,
                Collectors.summingDouble(Expense::getAmount))
            );

        categoryTotals.setAll(categoryMap.entrySet().stream()
            .map(entry -> new CategoryTotal(entry.getKey(), entry.getValue(),
                budgets.getOrDefault(entry.getKey(), 0.0)))
            .sorted(Comparator.comparing(CategoryTotal::getCategory))
            .collect(Collectors.toList()));

        // Compute previous month total with same filters for consistent month-over-month comparison
        YearMonth prevYearMonth = selectedYearMonth.minusMonths(1);
        boolean prevMonthHasImports = monthHasImportedData(prevYearMonth);
        double prevTotal = expenseList.stream()
            .filter(expense -> {
                if (expense.isExcluded() || expense.isIncome()) return false;
                if (prevMonthHasImports && expense.getRecurringId() != null) return false;
                if (!YearMonth.from(expense.getDate()).equals(prevYearMonth)) return false;
                if (filterByCategory && !expense.getCategory().equals(selectedCategory)) return false;
                if (expense.getAmount() < fMin || expense.getAmount() > fMax) return false;
                if (lowerCaseFilter == null) return true;
                return String.valueOf(expense.getAmount()).contains(lowerCaseFilter) ||
                       expense.getCategory().toLowerCase().contains(lowerCaseFilter) ||
                       expense.getDate().toString().contains(lowerCaseFilter) ||
                       (expense.getDescription() != null && expense.getDescription().toLowerCase().contains(lowerCaseFilter));
            })
            .mapToDouble(Expense::getAmount)
            .sum();

        updateDashboard(total, categoryMap, selectedYear, selectedMonth, prevTotal);
    }

    private void updateDashboard(double total, Map<String, Double> categoryMap,
                                  int selectedYear, Month selectedMonth, double prevTotal) {
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

    /**
     * Check if a given month has real imported expense data (not just recurring projections).
     */
    private boolean monthHasImportedData(YearMonth ym) {
        return expenseList.stream()
            .anyMatch(e -> !e.isIncome() && e.getImportId() != null
                && YearMonth.from(e.getDate()).equals(ym));
    }

    /**
     * Check if a recurring expense should be excluded because a matching imported
     * transaction already exists for that month. If no matching import exists,
     * the recurring expense should still be counted.
     */
    private boolean shouldExcludeRecurring(Expense recurring, YearMonth ym) {
        RecurringExpense source = recurring.getSourceRecurringExpense();
        if (source == null) return false; // no template info, keep the projection
        String srcDesc = source.getDescription() != null ? source.getDescription().toLowerCase().trim() : "";
        if (srcDesc.isEmpty()) return false; // can't match without a description
        return expenseList.stream()
            .anyMatch(imp -> imp.getRecurringId() == null && imp.getImportId() != null
                && !imp.isIncome()
                && YearMonth.from(imp.getDate()).equals(ym)
                && imp.getDescription() != null
                && imp.getDescription().toLowerCase().contains(srcDesc)
                && Math.abs(imp.getAmount() - recurring.getAmount()) <= recurring.getAmount() * 0.20);
    }

    private List<Expense> filterExpensesByPeriod(String chartPeriod, int selectedYear,
                                                    YearMonth selectedYearMonth, YearMonth now) {
        // Precompute which months have imported data
        Set<YearMonth> importedMonths = expenseList.stream()
            .filter(e -> !e.isIncome() && e.getImportId() != null)
            .map(e -> YearMonth.from(e.getDate()))
            .collect(Collectors.toSet());

        return expenseList.stream()
            .filter(expense -> !expense.isExcluded() && !expense.isIncome())
            .filter(expense -> {
                // Skip recurring projections for months with real imported data,
                // but only if a matching imported expense actually exists
                YearMonth ym = YearMonth.from(expense.getDate());
                if (expense.getRecurringId() != null && importedMonths.contains(ym)
                    && shouldExcludeRecurring(expense, ym)) {
                    return false;
                }
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
    }

    private void getMonthRange(String chartPeriod, int selectedYear, YearMonth selectedYearMonth,
                                YearMonth now, Map<YearMonth, Double> monthlyTotals,
                                YearMonth[] out) {
        switch (chartPeriod) {
            case "By Year":
                out[0] = YearMonth.of(selectedYear, 1);
                out[1] = YearMonth.of(selectedYear, 12);
                break;
            case "Last 6 Months":
                out[0] = now.minusMonths(5);
                out[1] = now;
                break;
            case "Last 12 Months":
                out[0] = now.minusMonths(11);
                out[1] = now;
                break;
            default: // All Time
                if (monthlyTotals.isEmpty()) {
                    out[0] = now;
                    out[1] = now;
                } else {
                    out[0] = monthlyTotals.keySet().stream().min(Comparator.naturalOrder()).orElse(now);
                    out[1] = monthlyTotals.keySet().stream().max(Comparator.naturalOrder()).orElse(now);
                }
                break;
        }
    }

    private void updateCharts() {
        Integer selectedYear = yearCombo.getValue();
        Month selectedMonth = monthCombo.getValue();
        String chartPeriod = chartPeriodCombo.getValue();

        if (selectedYear == null || selectedMonth == null || chartPeriod == null) {
            categoryChart.setData(FXCollections.observableArrayList());
            monthlyTrendChart.getData().clear();
            incomeVsExpensesChart.getData().clear();
            budgetVsActualChart.getData().clear();
            cumulativeSpendingChart.getData().clear();
            categoryTrendChart.getData().clear();
            yearOverYearChart.getData().clear();
            recurringVsOneTimeChart.setData(FXCollections.observableArrayList());
            return;
        }

        YearMonth selectedYearMonth = YearMonth.of(selectedYear, selectedMonth);
        YearMonth now = YearMonth.now();

        List<Expense> chartExpenses = filterExpensesByPeriod(chartPeriod, selectedYear, selectedYearMonth, now);

        // --- Existing charts ---
        updateCategoryPieChart(chartExpenses);
        updateMonthlyTrendBarChart(chartExpenses, chartPeriod, selectedYear, selectedMonth, selectedYearMonth, now);

        // --- New charts ---
        updateIncomeVsExpensesChart(chartPeriod, selectedYear, selectedYearMonth, now);
        updateBudgetVsActualChart(selectedYearMonth);
        updateCumulativeSpendingChart(selectedYearMonth);
        updateCategoryTrendChart(chartPeriod, selectedYear, selectedYearMonth, now);
        updateYearOverYearChart(selectedYear);
        updateRecurringVsOneTimeChart(chartPeriod, selectedYear, selectedYearMonth, now);

        // Mark projections as needing update; compute immediately if tab is active
        projectionsNeedUpdate = true;
        if (analyticsTabPane.getSelectionModel().getSelectedItem() == projectionsTab) {
            updateProjections();
        }

        // Fade-in animation for all charts
        animateChartFadeIn(categoryChart);
        animateChartFadeIn(monthlyTrendChart);
        animateChartFadeIn(incomeVsExpensesChart);
        animateChartFadeIn(budgetVsActualChart);
        animateChartFadeIn(cumulativeSpendingChart);
        animateChartFadeIn(categoryTrendChart);
        animateChartFadeIn(yearOverYearChart);
        animateChartFadeIn(recurringVsOneTimeChart);
    }

    private static final int MAX_PIE_SLICES = 8;

    private void updateCategoryPieChart(List<Expense> chartExpenses) {
        Map<String, Double> categoryMap = chartExpenses.stream()
            .collect(Collectors.groupingBy(
                Expense::getCategory,
                Collectors.summingDouble(Expense::getAmount)));

        double pieTotal = categoryMap.values().stream().mapToDouble(Double::doubleValue).sum();

        List<Map.Entry<String, Double>> sortedEntries = categoryMap.entrySet().stream()
            .sorted(Map.Entry.comparingByValue(Comparator.reverseOrder()))
            .collect(Collectors.toList());

        // Group small categories into "Other" to prevent label overlap
        List<Map.Entry<String, Double>> displayEntries;
        double otherTotal = 0;
        if (sortedEntries.size() > MAX_PIE_SLICES) {
            displayEntries = new ArrayList<>(sortedEntries.subList(0, MAX_PIE_SLICES));
            for (int i = MAX_PIE_SLICES; i < sortedEntries.size(); i++) {
                otherTotal += sortedEntries.get(i).getValue();
            }
        } else {
            displayEntries = sortedEntries;
        }

        ObservableList<PieChart.Data> pieChartData = FXCollections.observableArrayList();
        for (Map.Entry<String, Double> entry : displayEntries) {
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

        if (otherTotal > 0) {
            final double otherAmt = otherTotal;
            double otherPct = pieTotal > 0 ? (otherAmt / pieTotal) * 100 : 0;
            PieChart.Data otherData = new PieChart.Data(
                "Other (" + String.format("%.0f%%", otherPct) + ")",
                otherAmt);
            otherData.nodeProperty().addListener((obs, oldNode, newNode) -> {
                if (newNode != null) {
                    newNode.setStyle("-fx-pie-color: #888888;");
                    Tooltip tooltip = new Tooltip("Other: " + fmt(otherAmt)
                        + " (" + String.format("%.1f%%", pieTotal > 0 ? (otherAmt / pieTotal) * 100 : 0) + ")");
                    tooltip.setStyle("-fx-font-size: 13px;");
                    Tooltip.install(newNode, tooltip);
                }
            });
            pieChartData.add(otherData);
        }

        categoryChart.setData(pieChartData);
        categoryChart.setLabelLineLength(10);
        categoryChart.setAnimated(true);
    }

    private void updateMonthlyTrendBarChart(List<Expense> chartExpenses, String chartPeriod,
                                             int selectedYear, Month selectedMonth,
                                             YearMonth selectedYearMonth, YearMonth now) {
        CategoryAxis xAxis = (CategoryAxis) monthlyTrendChart.getXAxis();
        xAxis.setAnimated(false);
        monthlyTrendChart.setAnimated(false);
        monthlyTrendChart.getData().clear();
        xAxis.getCategories().clear();
        xAxis.setAutoRanging(false);
        xAxis.setTickLabelRotation(0);
        xAxis.setTickLabelGap(3);

        boolean isDailyMode = "By Month".equals(chartPeriod);

        List<String> barLabels = new ArrayList<>();

        // Collect all categories present in the data
        Set<String> allCategories = new LinkedHashSet<>();

        // Map: barLabel -> (category -> amount)
        Map<String, Map<String, Double>> barCategoryTotals = new LinkedHashMap<>();

        if (isDailyMode) {
            int daysInMonth = selectedYearMonth.lengthOfMonth();
            for (int weekStart = 1; weekStart <= daysInMonth; weekStart += 7) {
                int weekEnd = Math.min(weekStart + 6, daysInMonth);
                String label = weekStart + "\u2013" + weekEnd;
                barLabels.add(label);

                final int ws = weekStart;
                final int we = weekEnd;
                Map<String, Double> catTotals = new LinkedHashMap<>();
                for (Expense e : chartExpenses) {
                    int day = e.getDate().getDayOfMonth();
                    if (day >= ws && day <= we) {
                        catTotals.merge(e.getCategory(), e.getAmount(), Double::sum);
                        allCategories.add(e.getCategory());
                    }
                }
                barCategoryTotals.put(label, catTotals);
            }
            monthlyTrendChart.setTitle("Weekly Spending \u2014 "
                + selectedMonth.getDisplayName(TextStyle.FULL, Locale.getDefault()) + " " + selectedYear);
        } else {
            Set<YearMonth> importedMonths = expenseList.stream()
                .filter(e -> !e.isIncome() && e.getImportId() != null)
                .map(e -> YearMonth.from(e.getDate()))
                .collect(Collectors.toSet());
            Map<YearMonth, Double> monthlyTotals = expenseList.stream()
                .filter(expense -> !expense.isExcluded() && !expense.isIncome()
                    && !(expense.getRecurringId() != null && importedMonths.contains(YearMonth.from(expense.getDate()))
                        && shouldExcludeRecurring(expense, YearMonth.from(expense.getDate()))))
                .collect(Collectors.groupingBy(
                    expense -> YearMonth.from(expense.getDate()),
                    Collectors.summingDouble(Expense::getAmount)));

            YearMonth[] range = new YearMonth[2];
            getMonthRange(chartPeriod, selectedYear, selectedYearMonth, now, monthlyTotals, range);
            YearMonth rangeStart = range[0];
            YearMonth rangeEnd = range[1];

            boolean sameYear = rangeStart.getYear() == rangeEnd.getYear();

            YearMonth cursor = rangeStart;
            while (!cursor.isAfter(rangeEnd)) {
                final YearMonth ym = cursor;
                boolean ymHasImports = importedMonths.contains(ym);
                String label = ym.getMonth()
                    .getDisplayName(TextStyle.SHORT, Locale.getDefault())
                    + (sameYear ? "" : " '" + String.format("%02d", ym.getYear() % 100));
                barLabels.add(label);

                Map<String, Double> catTotals = new LinkedHashMap<>();
                for (Expense e : expenseList) {
                    if (!e.isExcluded() && !e.isIncome() && YearMonth.from(e.getDate()).equals(ym)
                        && !(ymHasImports && e.getRecurringId() != null)) {
                        catTotals.merge(e.getCategory(), e.getAmount(), Double::sum);
                        allCategories.add(e.getCategory());
                    }
                }
                barCategoryTotals.put(label, catTotals);
                cursor = cursor.plusMonths(1);
            }
            monthlyTrendChart.setTitle("Monthly Trend");
        }

        // Create one series per category for stacked bars
        for (String category : allCategories) {
            XYChart.Series<String, Number> series = new XYChart.Series<>();
            series.setName(category);
            final String color = getCategoryColor(category);

            for (String label : barLabels) {
                double amount = barCategoryTotals.get(label).getOrDefault(category, 0.0);
                XYChart.Data<String, Number> data = new XYChart.Data<>(label, amount);
                final String barLabel = label;
                final double amt = amount;
                data.nodeProperty().addListener((obs, oldNode, newNode) -> {
                    if (newNode != null) {
                        newNode.setStyle("-fx-bar-fill: " + color + ";");
                        if (amt > 0) {
                            Tooltip tooltip = new Tooltip(category + " (" + barLabel + "): " + fmt(amt));
                            tooltip.setStyle("-fx-font-size: 13px;");
                            Tooltip.install(newNode, tooltip);
                        }
                    }
                });
                series.getData().add(data);
            }
            monthlyTrendChart.getData().add(series);
        }

        xAxis.setCategories(FXCollections.observableArrayList(barLabels));
        monthlyTrendChart.setLegendVisible(false);

        // Build custom legend below the chart
        trendChartLegend.getChildren().clear();
        for (String category : allCategories) {
            String color = getCategoryColor(category);
            javafx.scene.shape.Rectangle swatch = new javafx.scene.shape.Rectangle(10, 10);
            swatch.setFill(javafx.scene.paint.Color.web(color));
            swatch.setArcWidth(2);
            swatch.setArcHeight(2);
            Label lbl = new Label(category);
            lbl.setStyle("-fx-text-fill: #F5F5F5; -fx-font-size: 11px; -fx-font-weight: bold;");
            HBox item = new HBox(4, swatch, lbl);
            item.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
            trendChartLegend.getChildren().add(item);
        }

        monthlyTrendChart.setAnimated(true);
    }

    // ==================== NEW CHARTS ====================

    private void updateIncomeVsExpensesChart(String chartPeriod, int selectedYear,
                                              YearMonth selectedYearMonth, YearMonth now) {
        CategoryAxis xAxis = (CategoryAxis) incomeVsExpensesChart.getXAxis();
        xAxis.setAnimated(false);
        incomeVsExpensesChart.setAnimated(false);
        incomeVsExpensesChart.getData().clear();
        xAxis.getCategories().clear();
        xAxis.setAutoRanging(false);

        Set<YearMonth> impMonths = expenseList.stream()
            .filter(e -> !e.isIncome() && e.getImportId() != null)
            .map(e -> YearMonth.from(e.getDate()))
            .collect(Collectors.toSet());

        Map<YearMonth, Double> monthlyExpenses = expenseList.stream()
            .filter(expense -> !expense.isExcluded() && !expense.isIncome()
                && !(expense.getRecurringId() != null && impMonths.contains(YearMonth.from(expense.getDate()))
                    && shouldExcludeRecurring(expense, YearMonth.from(expense.getDate()))))
            .collect(Collectors.groupingBy(
                expense -> YearMonth.from(expense.getDate()),
                Collectors.summingDouble(Expense::getAmount)));

        Map<YearMonth, Double> monthlyItemIncome = expenseList.stream()
            .filter(expense -> !expense.isExcluded() && expense.isIncome())
            .collect(Collectors.groupingBy(
                expense -> YearMonth.from(expense.getDate()),
                Collectors.summingDouble(Expense::getAmount)));

        YearMonth[] range = new YearMonth[2];
        getMonthRange(chartPeriod, selectedYear, selectedYearMonth, now, monthlyExpenses, range);
        YearMonth rangeStart = range[0];
        YearMonth rangeEnd = range[1];

        boolean sameYear = rangeStart.getYear() == rangeEnd.getYear();

        List<String> labels = new ArrayList<>();
        XYChart.Series<String, Number> incomeSeries = new XYChart.Series<>();
        incomeSeries.setName("Income");
        XYChart.Series<String, Number> expenseSeries = new XYChart.Series<>();
        expenseSeries.setName("Expenses");

        YearMonth cursor = rangeStart;
        while (!cursor.isAfter(rangeEnd)) {
            final YearMonth ym = cursor;
            String label = ym.getMonth().getDisplayName(TextStyle.SHORT, Locale.getDefault())
                + (sameYear ? "" : " '" + String.format("%02d", ym.getYear() % 100));
            labels.add(label);

            double expenseAmt = monthlyExpenses.getOrDefault(ym, 0.0);
            double actualInc = monthlyItemIncome.getOrDefault(ym, 0.0);
            double projectedInc = incomes.getOrDefault(ym, recurringIncome);
            double incomeAmt = actualInc > 0 ? actualInc : projectedInc;

            final double fIncome = incomeAmt;
            final double fExpense = expenseAmt;

            XYChart.Data<String, Number> incomeData = new XYChart.Data<>(label, incomeAmt);
            incomeData.nodeProperty().addListener((obs, oldNode, newNode) -> {
                if (newNode != null) {
                    newNode.setStyle("-fx-bar-fill: #4CAF50;");
                    Tooltip t = new Tooltip(ym.getMonth().getDisplayName(TextStyle.FULL, Locale.getDefault())
                        + " " + ym.getYear() + "\nIncome: " + fmt(fIncome));
                    t.setStyle("-fx-font-size: 13px;");
                    Tooltip.install(newNode, t);
                }
            });
            incomeSeries.getData().add(incomeData);

            XYChart.Data<String, Number> expenseData = new XYChart.Data<>(label, expenseAmt);
            expenseData.nodeProperty().addListener((obs, oldNode, newNode) -> {
                if (newNode != null) {
                    newNode.setStyle("-fx-bar-fill: #FF6F61;");
                    Tooltip t = new Tooltip(ym.getMonth().getDisplayName(TextStyle.FULL, Locale.getDefault())
                        + " " + ym.getYear() + "\nExpenses: " + fmt(fExpense));
                    t.setStyle("-fx-font-size: 13px;");
                    Tooltip.install(newNode, t);
                }
            });
            expenseSeries.getData().add(expenseData);

            cursor = cursor.plusMonths(1);
        }

        xAxis.setCategories(FXCollections.observableArrayList(labels));
        incomeVsExpensesChart.getData().addAll(incomeSeries, expenseSeries);
        incomeVsExpensesChart.setAnimated(true);
        styleChartLegend(incomeVsExpensesChart, "#4CAF50", "#FF6F61");
    }

    private void updateBudgetVsActualChart(YearMonth selectedYearMonth) {
        CategoryAxis xAxis = (CategoryAxis) budgetVsActualChart.getXAxis();
        xAxis.setAnimated(false);
        budgetVsActualChart.setAnimated(false);
        budgetVsActualChart.getData().clear();
        xAxis.getCategories().clear();
        xAxis.setAutoRanging(false);

        budgetVsActualSubtitle.setText("Showing "
            + selectedYearMonth.getMonth().getDisplayName(TextStyle.FULL, Locale.getDefault())
            + " " + selectedYearMonth.getYear());

        // Only include categories that have a budget
        boolean budgetMonthHasImports = monthHasImportedData(selectedYearMonth);
        Map<String, Double> actualByCategory = expenseList.stream()
            .filter(e -> !e.isExcluded() && !e.isIncome())
            .filter(e -> YearMonth.from(e.getDate()).equals(selectedYearMonth))
            .filter(e -> !(budgetMonthHasImports && e.getRecurringId() != null))
            .collect(Collectors.groupingBy(Expense::getCategory, Collectors.summingDouble(Expense::getAmount)));

        List<String> budgetedCategories = budgets.entrySet().stream()
            .filter(e -> e.getValue() > 0)
            .map(Map.Entry::getKey)
            .sorted()
            .collect(Collectors.toList());

        if (budgetedCategories.isEmpty()) {
            budgetVsActualSubtitle.setText("Set budgets via right-click on category table");
            return;
        }

        List<String> labels = new ArrayList<>(budgetedCategories);
        XYChart.Series<String, Number> budgetSeries = new XYChart.Series<>();
        budgetSeries.setName("Budget");
        XYChart.Series<String, Number> actualSeries = new XYChart.Series<>();
        actualSeries.setName("Actual");

        for (String category : budgetedCategories) {
            double budgetAmt = budgets.get(category);
            double actualAmt = actualByCategory.getOrDefault(category, 0.0);
            final double fBudget = budgetAmt;
            final double fActual = actualAmt;

            XYChart.Data<String, Number> bData = new XYChart.Data<>(category, budgetAmt);
            bData.nodeProperty().addListener((obs, oldNode, newNode) -> {
                if (newNode != null) {
                    newNode.setStyle("-fx-bar-fill: #5C6BC0;");
                    Tooltip t = new Tooltip(category + "\nBudget: " + fmt(fBudget));
                    t.setStyle("-fx-font-size: 13px;");
                    Tooltip.install(newNode, t);
                }
            });
            budgetSeries.getData().add(bData);

            XYChart.Data<String, Number> aData = new XYChart.Data<>(category, actualAmt);
            aData.nodeProperty().addListener((obs, oldNode, newNode) -> {
                if (newNode != null) {
                    String barColor = fActual > fBudget ? "#E53935" : "#4CAF50";
                    newNode.setStyle("-fx-bar-fill: " + barColor + ";");
                    Tooltip t = new Tooltip(category + "\nActual: " + fmt(fActual)
                        + (fActual > fBudget ? " (OVER)" : ""));
                    t.setStyle("-fx-font-size: 13px;");
                    Tooltip.install(newNode, t);
                }
            });
            actualSeries.getData().add(aData);
        }

        xAxis.setCategories(FXCollections.observableArrayList(labels));
        budgetVsActualChart.getData().addAll(budgetSeries, actualSeries);
        budgetVsActualChart.setAnimated(true);
        styleChartLegend(budgetVsActualChart, "#5C6BC0", "#4CAF50");
    }

    private void updateCumulativeSpendingChart(YearMonth selectedYearMonth) {
        NumberAxis xAxis = (NumberAxis) cumulativeSpendingChart.getXAxis();
        NumberAxis yAxis = (NumberAxis) cumulativeSpendingChart.getYAxis();
        cumulativeSpendingChart.setAnimated(false);
        cumulativeSpendingChart.getData().clear();

        cumulativeSubtitle.setText(selectedYearMonth.getMonth()
            .getDisplayName(TextStyle.FULL, Locale.getDefault()) + " " + selectedYearMonth.getYear());

        int daysInMonth = selectedYearMonth.lengthOfMonth();
        xAxis.setAutoRanging(false);
        xAxis.setLowerBound(1);
        xAxis.setUpperBound(daysInMonth);
        xAxis.setTickUnit(daysInMonth <= 15 ? 1 : 5);
        xAxis.setLabel("Day of Month");
        yAxis.setAutoRanging(true);
        yAxis.setLabel("Amount");

        boolean cumMonthHasImports = monthHasImportedData(selectedYearMonth);
        Map<Integer, Double> dailyTotals = expenseList.stream()
            .filter(e -> !e.isExcluded() && !e.isIncome())
            .filter(e -> YearMonth.from(e.getDate()).equals(selectedYearMonth))
            .filter(e -> !(cumMonthHasImports && e.getRecurringId() != null))
            .collect(Collectors.groupingBy(
                e -> e.getDate().getDayOfMonth(),
                Collectors.summingDouble(Expense::getAmount)));

        // Actual cumulative line
        XYChart.Series<Number, Number> actualSeries = new XYChart.Series<>();
        actualSeries.setName("Actual");
        double runningTotal = 0;
        for (int day = 1; day <= daysInMonth; day++) {
            runningTotal += dailyTotals.getOrDefault(day, 0.0);
            final double total = runningTotal;
            final int d = day;
            XYChart.Data<Number, Number> data = new XYChart.Data<>(day, runningTotal);
            data.nodeProperty().addListener((obs, oldNode, newNode) -> {
                if (newNode != null) {
                    newNode.setStyle("-fx-background-color: transparent;");
                    Tooltip t = new Tooltip("Day " + d + ": " + fmt(total));
                    t.setStyle("-fx-font-size: 13px;");
                    Tooltip.install(newNode, t);
                }
            });
            actualSeries.getData().add(data);
        }
        cumulativeSpendingChart.getData().add(actualSeries);

        // Budget line (total of all budgets)
        double totalBudget = budgets.values().stream().mapToDouble(Double::doubleValue).sum();
        if (totalBudget > 0) {
            XYChart.Series<Number, Number> budgetLine = new XYChart.Series<>();
            budgetLine.setName("Budget (" + fmt(totalBudget) + ")");
            budgetLine.getData().add(new XYChart.Data<>(1, totalBudget));
            budgetLine.getData().add(new XYChart.Data<>(daysInMonth, totalBudget));
            for (XYChart.Data<Number, Number> d : budgetLine.getData()) {
                d.nodeProperty().addListener((obs, oldNode, newNode) -> {
                    if (newNode != null) newNode.setStyle("-fx-background-color: transparent;");
                });
            }
            cumulativeSpendingChart.getData().add(budgetLine);
        }

        // Style the lines after they are added
        Platform.runLater(() -> {
            if (actualSeries.getNode() != null) {
                Node line = actualSeries.getNode().lookup(".chart-series-line");
                if (line != null) line.setStyle("-fx-stroke: #5C6BC0; -fx-stroke-width: 2px;");
            }
            if (totalBudget > 0 && cumulativeSpendingChart.getData().size() > 1) {
                Node budgetNode = cumulativeSpendingChart.getData().get(1).getNode();
                if (budgetNode != null) {
                    Node line = budgetNode.lookup(".chart-series-line");
                    if (line != null) {
                        line.setStyle("-fx-stroke: #FF9800; -fx-stroke-width: 2px; -fx-stroke-dash-array: 8 4;");
                    }
                }
            }
        });

        cumulativeSpendingChart.setCreateSymbols(false);
        if (totalBudget > 0) {
            styleChartLegend(cumulativeSpendingChart, "#5C6BC0", "#FF9800");
        } else {
            styleChartLegend(cumulativeSpendingChart, "#5C6BC0");
        }
    }

    private void updateCategoryTrendChart(String chartPeriod, int selectedYear,
                                           YearMonth selectedYearMonth, YearMonth now) {
        CategoryAxis xAxis = (CategoryAxis) categoryTrendChart.getXAxis();
        xAxis.setAnimated(false);
        categoryTrendChart.setAnimated(false);
        categoryTrendChart.getData().clear();
        xAxis.getCategories().clear();
        xAxis.setAutoRanging(false);

        Set<YearMonth> catTrendImportedMonths = expenseList.stream()
            .filter(e -> !e.isIncome() && e.getImportId() != null)
            .map(e -> YearMonth.from(e.getDate()))
            .collect(Collectors.toSet());
        Map<YearMonth, Double> monthlyTotals = expenseList.stream()
            .filter(e -> !e.isExcluded() && !e.isIncome()
                && !(e.getRecurringId() != null && catTrendImportedMonths.contains(YearMonth.from(e.getDate()))))
            .collect(Collectors.groupingBy(
                e -> YearMonth.from(e.getDate()),
                Collectors.summingDouble(Expense::getAmount)));

        YearMonth[] range = new YearMonth[2];
        getMonthRange(chartPeriod, selectedYear, selectedYearMonth, now, monthlyTotals, range);
        YearMonth rangeStart = range[0];
        YearMonth rangeEnd = range[1];

        boolean sameYear = rangeStart.getYear() == rangeEnd.getYear();

        // Build month labels
        List<String> monthLabels = new ArrayList<>();
        YearMonth cursor = rangeStart;
        while (!cursor.isAfter(rangeEnd)) {
            String label = cursor.getMonth().getDisplayName(TextStyle.SHORT, Locale.getDefault())
                + (sameYear ? "" : " '" + String.format("%02d", cursor.getYear() % 100));
            monthLabels.add(label);
            cursor = cursor.plusMonths(1);
        }

        // Get top categories by total spend in the range
        List<Expense> rangeExpenses = filterExpensesByPeriod(chartPeriod, selectedYear, selectedYearMonth, now);
        Map<String, Double> categoryTotalMap = rangeExpenses.stream()
            .collect(Collectors.groupingBy(Expense::getCategory, Collectors.summingDouble(Expense::getAmount)));

        List<String> topCategories = categoryTotalMap.entrySet().stream()
            .sorted(Map.Entry.comparingByValue(Comparator.reverseOrder()))
            .limit(8)
            .map(Map.Entry::getKey)
            .collect(Collectors.toList());

        // Group remaining categories as "Other"
        boolean hasOther = categoryTotalMap.size() > 8;

        // Build per-month per-category map
        Map<YearMonth, Map<String, Double>> monthCategoryMap = rangeExpenses.stream()
            .collect(Collectors.groupingBy(
                e -> YearMonth.from(e.getDate()),
                Collectors.groupingBy(
                    e -> topCategories.contains(e.getCategory()) ? e.getCategory() : "Other",
                    Collectors.summingDouble(Expense::getAmount))));

        List<String> allCategories = new ArrayList<>(topCategories);
        if (hasOther) allCategories.add("Other");

        for (String category : allCategories) {
            XYChart.Series<String, Number> series = new XYChart.Series<>();
            series.setName(category);
            cursor = rangeStart;
            int labelIdx = 0;
            while (!cursor.isAfter(rangeEnd)) {
                Map<String, Double> catMap = monthCategoryMap.getOrDefault(cursor, Collections.emptyMap());
                double amt = catMap.getOrDefault(category, 0.0);
                series.getData().add(new XYChart.Data<>(monthLabels.get(labelIdx), amt));
                cursor = cursor.plusMonths(1);
                labelIdx++;
            }
            categoryTrendChart.getData().add(series);
        }

        xAxis.setCategories(FXCollections.observableArrayList(monthLabels));

        // Apply category colors to areas and legend
        String[] catColors = allCategories.stream()
            .map(this::getCategoryColor).toArray(String[]::new);

        Platform.runLater(() -> {
            for (XYChart.Series<String, Number> s : categoryTrendChart.getData()) {
                String color = getCategoryColor(s.getName());
                if (s.getNode() != null) {
                    Node fill = s.getNode().lookup(".chart-series-area-fill");
                    Node line = s.getNode().lookup(".chart-series-area-line");
                    if (fill != null) fill.setStyle("-fx-fill: " + color + "44;");
                    if (line != null) line.setStyle("-fx-stroke: " + color + ";");
                }
            }
        });
        styleChartLegend(categoryTrendChart, catColors);

        categoryTrendChart.setAnimated(true);
    }

    private void updateYearOverYearChart(int selectedYear) {
        CategoryAxis xAxis = (CategoryAxis) yearOverYearChart.getXAxis();
        xAxis.setAnimated(false);
        yearOverYearChart.setAnimated(false);
        yearOverYearChart.getData().clear();
        xAxis.getCategories().clear();
        xAxis.setAutoRanging(false);

        int prevYear = selectedYear - 1;
        yearOverYearChart.setTitle(selectedYear + " vs " + prevYear);

        List<String> monthLabels = new ArrayList<>();
        for (Month m : Month.values()) {
            monthLabels.add(m.getDisplayName(TextStyle.SHORT, Locale.getDefault()));
        }

        Set<YearMonth> yoyImportedMonths = expenseList.stream()
            .filter(e -> !e.isIncome() && e.getImportId() != null)
            .map(e -> YearMonth.from(e.getDate()))
            .collect(Collectors.toSet());
        Map<YearMonth, Double> monthlyTotals = expenseList.stream()
            .filter(e -> !e.isExcluded() && !e.isIncome()
                && !(e.getRecurringId() != null && yoyImportedMonths.contains(YearMonth.from(e.getDate()))))
            .filter(e -> e.getDate().getYear() == selectedYear || e.getDate().getYear() == prevYear)
            .collect(Collectors.groupingBy(
                e -> YearMonth.from(e.getDate()),
                Collectors.summingDouble(Expense::getAmount)));

        XYChart.Series<String, Number> currentSeries = new XYChart.Series<>();
        currentSeries.setName(String.valueOf(selectedYear));
        XYChart.Series<String, Number> prevSeries = new XYChart.Series<>();
        prevSeries.setName(String.valueOf(prevYear));

        for (Month m : Month.values()) {
            String label = m.getDisplayName(TextStyle.SHORT, Locale.getDefault());

            double currentAmt = monthlyTotals.getOrDefault(YearMonth.of(selectedYear, m), 0.0);
            double prevAmt = monthlyTotals.getOrDefault(YearMonth.of(prevYear, m), 0.0);
            final double fCurrent = currentAmt;
            final double fPrev = prevAmt;

            XYChart.Data<String, Number> cData = new XYChart.Data<>(label, currentAmt);
            cData.nodeProperty().addListener((obs, oldNode, newNode) -> {
                if (newNode != null) {
                    newNode.setStyle("-fx-background-color: #5C6BC0;");
                    Tooltip t = new Tooltip(label + " " + selectedYear + ": " + fmt(fCurrent));
                    t.setStyle("-fx-font-size: 13px;");
                    Tooltip.install(newNode, t);
                }
            });
            currentSeries.getData().add(cData);

            XYChart.Data<String, Number> pData = new XYChart.Data<>(label, prevAmt);
            pData.nodeProperty().addListener((obs, oldNode, newNode) -> {
                if (newNode != null) {
                    newNode.setStyle("-fx-background-color: #A0A0A0;");
                    Tooltip t = new Tooltip(label + " " + prevYear + ": " + fmt(fPrev));
                    t.setStyle("-fx-font-size: 13px;");
                    Tooltip.install(newNode, t);
                }
            });
            prevSeries.getData().add(pData);
        }

        xAxis.setCategories(FXCollections.observableArrayList(monthLabels));
        yearOverYearChart.getData().addAll(currentSeries, prevSeries);

        // Style the lines
        Platform.runLater(() -> {
            if (currentSeries.getNode() != null) {
                Node line = currentSeries.getNode().lookup(".chart-series-line");
                if (line != null) line.setStyle("-fx-stroke: #5C6BC0; -fx-stroke-width: 2px;");
            }
            if (prevSeries.getNode() != null) {
                Node line = prevSeries.getNode().lookup(".chart-series-line");
                if (line != null) line.setStyle("-fx-stroke: #A0A0A0; -fx-stroke-width: 2px;");
            }
        });
        styleChartLegend(yearOverYearChart, "#5C6BC0", "#A0A0A0");

        yearOverYearChart.setAnimated(true);
    }

    private void updateRecurringVsOneTimeChart(String chartPeriod, int selectedYear,
                                                   YearMonth selectedYearMonth, YearMonth now) {
        // This chart needs ALL expenses including recurring projections (not filtered out)
        // so it can show the actual recurring vs one-time breakdown.
        // For months with imported data, count imported expenses that were originally
        // recurring (matched by description/category to a recurring template) as "recurring".
        Set<String> recurringDescs = manager.getBaseRecurringExpenses().stream()
            .map(r -> (r.getDescription() != null ? r.getDescription().toLowerCase().trim() : "") + "|" + r.getCategory().toLowerCase())
            .collect(Collectors.toSet());

        List<Expense> allPeriodExpenses = expenseList.stream()
            .filter(e -> !e.isExcluded() && !e.isIncome())
            .filter(e -> {
                YearMonth ym = YearMonth.from(e.getDate());
                switch (chartPeriod) {
                    case "By Year": return e.getDate().getYear() == selectedYear;
                    case "By Month": return ym.equals(selectedYearMonth);
                    case "Last 6 Months": return !ym.isBefore(now.minusMonths(5)) && !ym.isAfter(now);
                    case "Last 12 Months": return !ym.isBefore(now.minusMonths(11)) && !ym.isAfter(now);
                    default: return true;
                }
            })
            .collect(Collectors.toList());

        // For months with imported data, prefer actual imports over projections
        // to avoid double-counting. For months without imports, use projections.
        Set<YearMonth> importedMonths = expenseList.stream()
            .filter(e -> !e.isIncome() && e.getImportId() != null)
            .map(e -> YearMonth.from(e.getDate()))
            .collect(Collectors.toSet());

        double recurringTotal = 0;
        double oneTimeTotal = 0;
        for (Expense e : allPeriodExpenses) {
            YearMonth ym = YearMonth.from(e.getDate());
            boolean monthHasImports = importedMonths.contains(ym);

            if (e.getRecurringId() != null) {
                if (monthHasImports) {
                    // Month has imports — skip projection, but only if there's an actual
                    // imported expense matching this recurring template (otherwise still count it)
                    String key = (e.getDescription() != null ? e.getDescription().toLowerCase().trim() : "") + "|" + e.getCategory().toLowerCase();
                    boolean hasImportedMatch = allPeriodExpenses.stream()
                        .anyMatch(imp -> imp.getRecurringId() == null && imp.getImportId() != null
                            && YearMonth.from(imp.getDate()).equals(ym)
                            && recurringDescs.contains(
                                (imp.getDescription() != null ? imp.getDescription().toLowerCase().trim() : "")
                                + "|" + imp.getCategory().toLowerCase())
                            && Math.abs(imp.getAmount() - e.getAmount()) <= e.getAmount() * 0.15);
                    if (!hasImportedMatch) {
                        recurringTotal += e.getAmount();
                    }
                } else {
                    recurringTotal += e.getAmount();
                }
            } else {
                // Real expense — check if it matches a recurring template
                String key = (e.getDescription() != null ? e.getDescription().toLowerCase().trim() : "") + "|" + e.getCategory().toLowerCase();
                if (recurringDescs.contains(key)) {
                    recurringTotal += e.getAmount();
                } else {
                    oneTimeTotal += e.getAmount();
                }
            }
        }
        // Capture as final for use in lambdas
        final double finalRecurringTotal = recurringTotal;
        final double finalOneTimeTotal = oneTimeTotal;
        double grandTotal = finalRecurringTotal + finalOneTimeTotal;

        ObservableList<PieChart.Data> data = FXCollections.observableArrayList();

        if (grandTotal > 0) {
            double recurPct = (finalRecurringTotal / grandTotal) * 100;
            double onePct = (finalOneTimeTotal / grandTotal) * 100;

            PieChart.Data recurData = new PieChart.Data(
                "Recurring (" + String.format("%.0f%%", recurPct) + ")", finalRecurringTotal);
            recurData.nodeProperty().addListener((obs, oldNode, newNode) -> {
                if (newNode != null) {
                    newNode.setStyle("-fx-pie-color: #FF9800;");
                    Tooltip t = new Tooltip("Recurring: " + fmt(finalRecurringTotal)
                        + " (" + String.format("%.1f%%", recurPct) + ")");
                    t.setStyle("-fx-font-size: 13px;");
                    Tooltip.install(newNode, t);
                }
            });

            PieChart.Data oneData = new PieChart.Data(
                "One-Time (" + String.format("%.0f%%", onePct) + ")", finalOneTimeTotal);
            oneData.nodeProperty().addListener((obs, oldNode, newNode) -> {
                if (newNode != null) {
                    newNode.setStyle("-fx-pie-color: #45AAF2;");
                    Tooltip t = new Tooltip("One-Time: " + fmt(finalOneTimeTotal)
                        + " (" + String.format("%.1f%%", onePct) + ")");
                    t.setStyle("-fx-font-size: 13px;");
                    Tooltip.install(newNode, t);
                }
            });

            data.addAll(recurData, oneData);
        }

        recurringVsOneTimeChart.setData(data);
        recurringVsOneTimeChart.setAnimated(true);
        if (grandTotal > 0) {
            styleChartLegend(recurringVsOneTimeChart, "#FF9800", "#45AAF2");
        }
    }

    private void styleChartLegend(Chart chart, String... colors) {
        Platform.runLater(() -> {
            int i = 0;
            for (Node legendItem : chart.lookupAll(".chart-legend-item-symbol")) {
                if (i < colors.length) {
                    legendItem.setStyle("-fx-background-color: " + colors[i] + ";");
                }
                i++;
            }
        });
    }

    // ======================== PROJECTIONS ========================

    private void updateProjections() {
        projectionsNeedUpdate = false;

        // Build input snapshot
        ProjectionEngine.ProjectionInput input = new ProjectionEngine.ProjectionInput(
                new ArrayList<>(manager.getExpenses()),
                new ArrayList<>(manager.getBaseRecurringExpenses()),
                new HashMap<>(incomes),
                recurringIncome,
                new HashMap<>(budgets)
        );

        ProjectionEngine.ProjectionResult result = projectionEngine.project(input);

        // Edge case: no data at all
        if (result.dataMonthsAvailable == 0 && input.recurringExpenses.isEmpty()) {
            projExpenses.setText("-");
            projExpensesSubtitle.setText("");
            projIncome.setText("-");
            projIncomeSubtitle.setText("");
            projNetSavings.setText("-");
            projNetSavings.setStyle("");
            projNetSavingsSubtitle.setText("");
            projCumulativeSavings.setText("-");
            projCumulativeSavings.setStyle("");
            projTrendIndicator.setText("");
            projPeriodLabel.setText("");
            projMethodLabel.setText("");
            projNoDataLabel.setText("Not enough data for projections");
            projNoDataLabel.setVisible(true);
            projNoDataLabel.setManaged(true);
            projOutlookChart.getData().clear();
            projBalanceChart.getData().clear();
            projCategoryChart.getData().clear();
            return;
        }

        projNoDataLabel.setVisible(false);
        projNoDataLabel.setManaged(false);

        // Period label
        ProjectionEngine.MonthProjection firstMonth = result.monthProjections.get(0);
        ProjectionEngine.MonthProjection lastMonth = result.monthProjections.get(result.monthProjections.size() - 1);
        String fromStr = firstMonth.month.getMonth().getDisplayName(java.time.format.TextStyle.FULL, Locale.getDefault()) + " " + firstMonth.month.getYear();
        String toStr = lastMonth.month.getMonth().getDisplayName(java.time.format.TextStyle.FULL, Locale.getDefault()) + " " + lastMonth.month.getYear();
        projPeriodLabel.setText("Projecting: " + fromStr + " \u2013 " + toStr);

        // Methodology label — describe what's active based on available data
        List<String> methods = new ArrayList<>();
        methods.add("Recurring expenses (fixed schedules)");
        if (result.dataMonthsAvailable >= 1) {
            methods.add("Weighted moving average on last " + Math.min(result.dataMonthsAvailable, 6) + " months of variable spending");
        }
        if (result.dataMonthsAvailable >= 3) {
            methods.add("Linear trend detection (last " + Math.min(result.dataMonthsAvailable, 12) + " months)");
        }
        if (result.hasSeasonalData) {
            methods.add("Seasonal adjustment (12+ months of history)");
        }
        if (result.dataMonthsAvailable >= 2) {
            methods.add("Confidence bands (\u00B11\u03C3 std deviation)");
        }
        projMethodLabel.setText("Based on: " + String.join(" \u2022 ", methods));

        // Summary cards — use first month projection
        ProjectionEngine.MonthProjection first = result.monthProjections.get(0);
        String monthName = first.month.getMonth().getDisplayName(java.time.format.TextStyle.SHORT, Locale.getDefault());

        projExpenses.setText(fmt(first.projectedExpenses));
        projExpensesSubtitle.setText(monthName + " " + first.month.getYear());

        projIncome.setText(fmt(first.projectedIncome));
        projIncomeSubtitle.setText(monthName + " " + first.month.getYear());

        projNetSavings.setText(fmt(Math.abs(first.netSavings)));
        projNetSavings.setStyle("-fx-text-fill: " + (first.netSavings >= 0 ? "#4CAF50" : "#FF6F61") + ";");
        projNetSavingsSubtitle.setText(first.netSavings >= 0 ? "Surplus" : "Deficit");

        double cumulativeSavings = result.monthProjections.stream()
                .mapToDouble(mp -> mp.netSavings).sum();
        projCumulativeSavings.setText(fmt(Math.abs(cumulativeSavings)));
        projCumulativeSavings.setStyle("-fx-text-fill: " + (cumulativeSavings >= 0 ? "#4CAF50" : "#FF6F61") + ";");

        // Trend indicator
        if (result.trendSlope > 10) {
            projTrendIndicator.setText("\u25B2 Spending trending up " + fmt(Math.abs(result.trendSlope)) + "/month");
            projTrendIndicator.setStyle("-fx-text-fill: #FF6F61; -fx-font-weight: bold; -fx-font-size: 13px;");
        } else if (result.trendSlope < -10) {
            projTrendIndicator.setText("\u25BC Spending trending down " + fmt(Math.abs(result.trendSlope)) + "/month");
            projTrendIndicator.setStyle("-fx-text-fill: #4CAF50; -fx-font-weight: bold; -fx-font-size: 13px;");
        } else {
            projTrendIndicator.setText("\u2192 Spending is stable");
            projTrendIndicator.setStyle("-fx-text-fill: #F7B731; -fx-font-weight: bold; -fx-font-size: 13px;");
        }

        // Charts
        updateProjectionOutlookChart(result);
        updateProjectionBalanceChart(result);
        updateProjectionCategoryChart(result);

        animateChartFadeIn(projOutlookChart);
        animateChartFadeIn(projBalanceChart);
        animateChartFadeIn(projCategoryChart);
    }

    private void updateProjectionOutlookChart(ProjectionEngine.ProjectionResult result) {
        CategoryAxis xAxis = (CategoryAxis) projOutlookChart.getXAxis();
        xAxis.setAnimated(false);
        projOutlookChart.setAnimated(false);
        projOutlookChart.getData().clear();

        XYChart.Series<String, Number> incomeSeries = new XYChart.Series<>();
        incomeSeries.setName("Income");
        XYChart.Series<String, Number> expenseSeries = new XYChart.Series<>();
        expenseSeries.setName("Expenses");
        XYChart.Series<String, Number> savingsSeries = new XYChart.Series<>();
        savingsSeries.setName("Net Savings");
        XYChart.Series<String, Number> optimisticSeries = new XYChart.Series<>();
        optimisticSeries.setName("Optimistic");
        XYChart.Series<String, Number> pessimisticSeries = new XYChart.Series<>();
        pessimisticSeries.setName("Pessimistic");

        boolean showBands = result.dataMonthsAvailable >= 2;

        for (ProjectionEngine.MonthProjection mp : result.monthProjections) {
            String label = mp.month.getMonth().getDisplayName(java.time.format.TextStyle.SHORT, Locale.getDefault());
            incomeSeries.getData().add(new XYChart.Data<>(label, mp.projectedIncome));
            expenseSeries.getData().add(new XYChart.Data<>(label, mp.projectedExpenses));
            savingsSeries.getData().add(new XYChart.Data<>(label, mp.netSavings));
            if (showBands) {
                optimisticSeries.getData().add(new XYChart.Data<>(label, mp.optimisticExpenses));
                pessimisticSeries.getData().add(new XYChart.Data<>(label, mp.pessimisticExpenses));
            }
        }

        projOutlookChart.getData().addAll(Arrays.asList(incomeSeries, expenseSeries, savingsSeries));
        if (showBands) {
            projOutlookChart.getData().addAll(Arrays.asList(optimisticSeries, pessimisticSeries));
        }

        // Style the series after adding to chart
        Platform.runLater(() -> {
            styleOutlookSeries(incomeSeries, "#4CAF50", false);
            styleOutlookSeries(expenseSeries, "#FF6F61", false);
            styleOutlookSeries(savingsSeries, "#5C6BC0", false);
            if (showBands) {
                styleOutlookSeries(optimisticSeries, "#66BB6A", true);   // green — lower expenses = good
                styleOutlookSeries(pessimisticSeries, "#EF5350", true);  // red — higher expenses = bad
            }

            // Match legend colors to series — dashed symbols for confidence bands
            Platform.runLater(() -> {
                int idx = 0;
                for (Node legendItem : projOutlookChart.lookupAll(".chart-legend-item-symbol")) {
                    switch (idx) {
                        case 0 -> legendItem.setStyle("-fx-background-color: #4CAF50;"); // Income
                        case 1 -> legendItem.setStyle("-fx-background-color: #FF6F61;"); // Expenses
                        case 2 -> legendItem.setStyle("-fx-background-color: #5C6BC0;"); // Net Savings
                        case 3 -> legendItem.setStyle( // Optimistic (dashed green)
                            "-fx-background-color: transparent; -fx-border-color: #66BB6A; -fx-border-style: dashed; -fx-border-width: 2; -fx-opacity: 0.7;");
                        case 4 -> legendItem.setStyle( // Pessimistic (dashed red)
                            "-fx-background-color: transparent; -fx-border-color: #EF5350; -fx-border-style: dashed; -fx-border-width: 2; -fx-opacity: 0.7;");
                    }
                    idx++;
                }
            });

            // Add tooltips to all data points
            for (XYChart.Series<String, Number> series : projOutlookChart.getData()) {
                for (XYChart.Data<String, Number> data : series.getData()) {
                    if (data.getNode() != null) {
                        Tooltip t = new Tooltip(series.getName() + " - " + data.getXValue() + ": " + fmt(data.getYValue().doubleValue()));
                        t.setStyle("-fx-font-size: 13px;");
                        Tooltip.install(data.getNode(), t);
                    }
                }
            }
        });
    }

    private void styleOutlookSeries(XYChart.Series<String, Number> series, String color, boolean dashed) {
        Node line = series.getNode();
        if (line != null) {
            if (dashed) {
                line.setStyle("-fx-stroke: " + color + "; -fx-stroke-dash-array: 8 4; -fx-opacity: 0.6;");
            } else {
                line.setStyle("-fx-stroke: " + color + "; -fx-stroke-width: 2.5;");
            }
        }
        for (XYChart.Data<String, Number> data : series.getData()) {
            if (data.getNode() != null) {
                if (dashed) {
                    data.getNode().setStyle("-fx-background-color: " + color + "; -fx-opacity: 0.5; -fx-background-radius: 3;");
                } else {
                    data.getNode().setStyle("-fx-background-color: " + color + "; -fx-background-radius: 4;");
                }
            }
        }
    }

    private void updateProjectionBalanceChart(ProjectionEngine.ProjectionResult result) {
        CategoryAxis xAxis = (CategoryAxis) projBalanceChart.getXAxis();
        xAxis.setAnimated(false);
        projBalanceChart.setAnimated(false);
        projBalanceChart.getData().clear();

        XYChart.Series<String, Number> pessimisticSeries = new XYChart.Series<>();
        pessimisticSeries.setName("Pessimistic");
        XYChart.Series<String, Number> expectedSeries = new XYChart.Series<>();
        expectedSeries.setName("Expected");
        XYChart.Series<String, Number> optimisticSeries = new XYChart.Series<>();
        optimisticSeries.setName("Optimistic");

        double runningExpected = result.currentBalance;
        double runningOptimistic = result.currentBalance;
        double runningPessimistic = result.currentBalance;

        // Add starting point
        String startLabel = "Now";
        pessimisticSeries.getData().add(new XYChart.Data<>(startLabel, result.currentBalance));
        expectedSeries.getData().add(new XYChart.Data<>(startLabel, result.currentBalance));
        optimisticSeries.getData().add(new XYChart.Data<>(startLabel, result.currentBalance));

        for (ProjectionEngine.MonthProjection mp : result.monthProjections) {
            String label = mp.month.getMonth().getDisplayName(java.time.format.TextStyle.SHORT, Locale.getDefault());
            runningExpected += mp.projectedIncome - mp.projectedExpenses;
            runningOptimistic += mp.projectedIncome - mp.optimisticExpenses;
            runningPessimistic += mp.projectedIncome - mp.pessimisticExpenses;

            expectedSeries.getData().add(new XYChart.Data<>(label, runningExpected));
            optimisticSeries.getData().add(new XYChart.Data<>(label, runningOptimistic));
            pessimisticSeries.getData().add(new XYChart.Data<>(label, runningPessimistic));
        }

        // Order matters for layering: pessimistic (back) -> expected -> optimistic (front)
        projBalanceChart.getData().addAll(Arrays.asList(pessimisticSeries, expectedSeries, optimisticSeries));

        Platform.runLater(() -> {
            styleAreaSeries(pessimisticSeries, "#FF6F61", 0.15);
            styleAreaSeries(expectedSeries, "#5C6BC0", 0.25);
            styleAreaSeries(optimisticSeries, "#4CAF50", 0.15);

            // Match legend colors to series colors (order: pessimistic, expected, optimistic)
            styleChartLegend(projBalanceChart, "#FF6F61", "#5C6BC0", "#4CAF50");
        });
    }

    private void styleAreaSeries(XYChart.Series<String, Number> series, String color, double fillOpacity) {
        Node line = series.getNode();
        if (line != null) {
            // The series node in AreaChart is a Group containing the fill path and the line path
            line.setStyle("-fx-stroke: " + color + "; -fx-stroke-width: 2;");
            // Apply fill via lookup
            line.lookupAll(".chart-series-area-fill").forEach(fill ->
                fill.setStyle("-fx-fill: " + color + "; -fx-opacity: " + fillOpacity + ";")
            );
            line.lookupAll(".chart-series-area-line").forEach(ln ->
                ln.setStyle("-fx-stroke: " + color + "; -fx-stroke-width: 2;")
            );
        }
    }

    private void updateProjectionCategoryChart(ProjectionEngine.ProjectionResult result) {
        CategoryAxis xAxis = (CategoryAxis) projCategoryChart.getXAxis();
        NumberAxis yAxis = (NumberAxis) projCategoryChart.getYAxis();
        xAxis.setAnimated(false);
        projCategoryChart.setAnimated(false);
        projCategoryChart.getData().clear();

        // Use first month projection for category breakdown
        ProjectionEngine.MonthProjection first = result.monthProjections.get(0);

        // Collect all categories sorted by total descending
        List<Map.Entry<String, Double>> sortedCategories = first.categoryBreakdown.entrySet().stream()
                .filter(e -> e.getValue() > 0)
                .sorted(Map.Entry.comparingByValue(Comparator.reverseOrder()))
                .collect(Collectors.toList());

        if (sortedCategories.isEmpty()) return;

        // Two series: Recurring and Variable
        XYChart.Series<String, Number> recurringSeries = new XYChart.Series<>();
        recurringSeries.setName("Recurring");
        XYChart.Series<String, Number> variableSeries = new XYChart.Series<>();
        variableSeries.setName("Variable");

        for (Map.Entry<String, Double> entry : sortedCategories) {
            String cat = entry.getKey();
            double recurring = first.categoryRecurring.getOrDefault(cat, 0.0);
            double variable = first.categoryVariable.getOrDefault(cat, 0.0);

            recurringSeries.getData().add(new XYChart.Data<>(cat, recurring));
            variableSeries.getData().add(new XYChart.Data<>(cat, variable));
        }

        projCategoryChart.getData().addAll(Arrays.asList(recurringSeries, variableSeries));

        // Style bars with category colors and add tooltips
        Platform.runLater(() -> {
            for (XYChart.Data<String, Number> data : recurringSeries.getData()) {
                if (data.getNode() != null) {
                    String color = getCategoryColor(data.getXValue());
                    data.getNode().setStyle("-fx-bar-fill: " + color + ";");
                    Tooltip t = new Tooltip(data.getXValue() + " (Recurring): " + fmt(data.getYValue().doubleValue()) + "/month");
                    t.setStyle("-fx-font-size: 13px;");
                    Tooltip.install(data.getNode(), t);
                }
            }
            for (XYChart.Data<String, Number> data : variableSeries.getData()) {
                if (data.getNode() != null) {
                    String color = getCategoryColor(data.getXValue());
                    data.getNode().setStyle("-fx-bar-fill: " + color + "; -fx-opacity: 0.5;");
                    Tooltip t = new Tooltip(data.getXValue() + " (Variable): " + fmt(data.getYValue().doubleValue()) + "/month");
                    t.setStyle("-fx-font-size: 13px;");
                    Tooltip.install(data.getNode(), t);
                }
            }
        });
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

        // Import logs
        try {
            importLogs = FXCollections.observableArrayList(storage.loadImportLogs());
        } catch (IOException e) {
            importLogs = FXCollections.observableArrayList();
        }
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

        // Use EXIF photo date as fallback, otherwise today (user can edit dates in the review dialog)
        LocalDate exifDate = receiptScanner.extractPhotoDate(file);
        LocalDate fallbackDate = exifDate != null ? exifDate : LocalDate.now();

        // Show centered overlay progress indicator
        ProgressIndicator spinner = new ProgressIndicator();
        spinner.setPrefSize(48, 48);
        spinner.setMaxSize(48, 48);
        Label ocrStatusLabel = new Label("Scanning receipt...");
        ocrStatusLabel.setStyle("-fx-text-fill: white; -fx-font-size: 14px;");
        VBox spinnerBox = new VBox(12, spinner, ocrStatusLabel);
        spinnerBox.setAlignment(Pos.CENTER);
        spinnerBox.setStyle("-fx-background-color: rgba(0,0,0,0.7); -fx-background-radius: 12; -fx-padding: 30;");
        StackPane overlay = new StackPane(spinnerBox);
        overlay.setStyle("-fx-background-color: rgba(0,0,0,0.3);");

        // Wrap scene root in StackPane to allow overlay layering
        Scene scene = stage.getScene();
        javafx.scene.Parent originalRoot = scene.getRoot();
        StackPane wrapper = new StackPane(originalRoot, overlay);
        scene.setRoot(wrapper);

        // Save original close handler so we can restore it after OCR completes
        javafx.event.EventHandler<javafx.stage.WindowEvent> originalCloseHandler = stage.getOnCloseRequest();

        Thread ocrThread = new Thread(() -> {
            if (Thread.currentThread().isInterrupted()) return;
            try {
                String ocrText = receiptScanner.performOcr(file);

                if (Thread.currentThread().isInterrupted()) return;
                Platform.runLater(() -> ocrStatusLabel.setText("Parsing items..."));

                List<ImportItem> items = receiptScanner.parseReceipt(ocrText, fallbackDate);

                // Auto-categorize
                for (ImportItem item : items) {
                    String cat = categorizationRules.categorize(item.getDescription());
                    if (cat != null) {
                        item.setCategory(cat);
                        item.setStatus("Auto-categorized");
                    }
                }

                if (Thread.currentThread().isInterrupted()) return;
                Platform.runLater(() -> {
                    stage.setOnCloseRequest(originalCloseHandler);
                    wrapper.getChildren().clear();
                    scene.setRoot(originalRoot);

                    if (items.isEmpty()) {
                        // Show OCR text so user can see what was scanned
                        Alert alert = new Alert(Alert.AlertType.WARNING);
                        alert.initOwner(stage);
                        alert.setTitle("No Items Found");
                        alert.setHeaderText("Could not extract any line items from this receipt.");
                        alert.getDialogPane().getStylesheets().add(getClass().getResource("/styles.css").toExternalForm());
                        TextArea ocrArea = new TextArea(ocrText);
                        ocrArea.setEditable(false);
                        ocrArea.setWrapText(true);
                        ocrArea.setPrefHeight(300);
                        alert.getDialogPane().setExpandableContent(
                            new VBox(5, new Label("OCR text (for debugging):"), ocrArea));
                        alert.showAndWait();
                        return;
                    }
                    showMessage("Found " + items.size() + " items.", false);
                    ImportReviewDialog dialog = new ImportReviewDialog(
                        stage, items, categories, currencySymbol, ocrText, categorizationRules,
                        manager.getExpenses());
                    List<Expense> expenses = dialog.showAndWait();
                    if (expenses != null && !expenses.isEmpty()) {
                        saveLearnedRules(dialog);
                        importExpenses(expenses, file.getName(), "Receipt");
                    }
                });
            } catch (Exception e) {
                if (Thread.currentThread().isInterrupted()) return;
                Platform.runLater(() -> {
                    stage.setOnCloseRequest(originalCloseHandler);
                    wrapper.getChildren().clear();
                    scene.setRoot(originalRoot);
                    showMessage("OCR failed: " + e.getMessage(), true);
                });
            }
        });
        ocrThread.setDaemon(true);
        ocrThread.start();

        // Interrupt OCR thread if window is closed mid-scan, then delegate to original handler
        stage.setOnCloseRequest(e -> {
            ocrThread.interrupt();
            if (originalCloseHandler != null) originalCloseHandler.handle(e);
        });
    }

    @FXML
    private void handleImportStatement() {
        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("Import Bank Statements");
        fileChooser.getExtensionFilters().addAll(
            new FileChooser.ExtensionFilter("Bank Statements", "*.pdf", "*.csv"),
            new FileChooser.ExtensionFilter("PDF Files", "*.pdf"),
            new FileChooser.ExtensionFilter("CSV Files", "*.csv"),
            new FileChooser.ExtensionFilter("All Files", "*.*")
        );
        List<File> files = fileChooser.showOpenMultipleDialog(stage);
        if (files == null || files.isEmpty()) return;

        List<ImportItem> allItems = new ArrayList<>();
        List<String> fileNames = new ArrayList<>();

        for (File file : files) {
            try {
                String fileName = file.getName().toLowerCase();
                List<ImportItem> items;

                if (fileName.endsWith(".pdf")) {
                    items = parsePdfStatement(file);
                } else {
                    items = parseCsvStatement(file);
                }

                if (items != null && !items.isEmpty()) {
                    for (ImportItem item : items) {
                        item.setSourceFile(file.getName());
                    }
                    allItems.addAll(items);
                    fileNames.add(file.getName());
                }
            } catch (Exception e) {
                showMessage("Failed to parse " + file.getName() + ": " + e.getMessage(), true);
            }
        }

        if (allItems.isEmpty()) {
            showMessage("No transactions found in the selected files.", true);
            return;
        }

        // Auto-categorize
        for (ImportItem item : allItems) {
            String cat = categorizationRules.categorize(item.getDescription());
            if (cat != null) {
                item.setCategory(cat);
                item.setStatus("Auto-categorized");
            }
        }

        ImportReviewDialog dialog = new ImportReviewDialog(
            stage, allItems, categories, currencySymbol, null, categorizationRules,
            manager.getExpenses(), manager.getBaseRecurringExpenses());
        List<Expense> expenses = dialog.showAndWait();
        if (expenses != null && !expenses.isEmpty()) {
            saveLearnedRules(dialog);

            // Build a map from each selected ImportItem to its resulting Expense.
            // The review dialog returns expenses in the same order as selected items.
            List<ImportItem> selectedItems = allItems.stream()
                .filter(i -> i.isSelected() && i.getAmount() > 0)
                .collect(java.util.stream.Collectors.toList());

            // Group expenses by source file
            Map<String, List<Expense>> expensesByFile = new LinkedHashMap<>();
            for (int i = 0; i < expenses.size() && i < selectedItems.size(); i++) {
                String src = selectedItems.get(i).getSourceFile();
                if (src == null) src = "Unknown";
                expensesByFile.computeIfAbsent(src, k -> new ArrayList<>()).add(expenses.get(i));
            }

            // Import each file's expenses separately
            for (Map.Entry<String, List<Expense>> entry : expensesByFile.entrySet()) {
                String fileName = entry.getKey();
                String type = fileName.toLowerCase().endsWith(".pdf") ? "PDF" : "CSV";
                importExpenses(entry.getValue(), fileName, type);
            }
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

        javafx.scene.control.ScrollPane scrollPane = new javafx.scene.control.ScrollPane(layout);
        scrollPane.setFitToWidth(true);
        scrollPane.getStyleClass().add("root-pane");

        Scene scene = new Scene(scrollPane, 450, 550);
        scene.getStylesheets().add(getClass().getResource("/styles.css").toExternalForm());
        mappingStage.setMinWidth(350);
        mappingStage.setMinHeight(400);
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
        // Count income items (income flag already set by ImportReviewDialog)
        int incomeCount = 0;
        double totalIncomeAdded = 0;
        for (Expense exp : expenses) {
            if (exp.isIncome()) {
                incomeCount++;
                totalIncomeAdded += exp.getAmount();
            }
        }

        // Tag all items with a unique import ID
        String importId = "IMP-" + System.currentTimeMillis();
        for (Expense exp : expenses) {
            exp.setImportId(importId);
        }

        // Add all items (expenses + income) to the manager
        if (!expenses.isEmpty()) {
            BulkAddExpenseCommand cmd = new BulkAddExpenseCommand(manager, expenses);
            manager.executeCommand(cmd);
            try {
                storage.saveExpenses(manager.getExpensesForSave());
            } catch (IOException ex) {
                manager.undo();
                showMessage("Failed to save imported expenses: " + ex.getMessage(), true);
                return;
            }
            try {
                storage.saveCategories(categories);
            } catch (IOException ex) {
                showMessage("Expenses saved but failed to save categories: " + ex.getMessage(), true);
            }
        }

        // Log the import
        ImportLog log = new ImportLog(importId, java.time.LocalDateTime.now(), sourceFile, sourceType, expenses.size());
        importLogs.add(log);
        try {
            storage.saveImportLogs(new ArrayList<>(importLogs));
        } catch (IOException ex) {
            System.err.println("Failed to save import log: " + ex.getMessage());
        }

        refreshTable();

        // Build summary message
        StringBuilder msg = new StringBuilder();
        int expenseCount = expenses.size() - incomeCount;
        msg.append(expenseCount).append(" expenses imported");
        if (totalIncomeAdded > 0) {
            msg.append(", ").append(fmt(totalIncomeAdded)).append(" income added across ")
               .append(incomeCount).append(" transaction(s)");
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
        btnBox.setAlignment(javafx.geometry.Pos.CENTER_RIGHT);

        VBox layout = new VBox(8, titleLabel, keywordLabel, keywordField, catLabel, catCombo, btnBox);
        layout.setPadding(new javafx.geometry.Insets(15));
        layout.getStyleClass().add("root-pane");

        Scene scene = new Scene(layout, 380, 300);
        scene.getStylesheets().add(getClass().getResource("/styles.css").toExternalForm());
        ruleStage.setMinWidth(300);
        ruleStage.setMinHeight(250);
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
    private void handleRecategorize() {
        List<Expense> allExpenses = manager.getExpenses();
        int recategorized = 0;
        for (Expense expense : allExpenses) {
            if ("Uncategorized".equals(expense.getCategory())) {
                String cat = categorizationRules.categorize(expense.getDescription());
                if (cat != null) {
                    expense.setCategory(cat);
                    recategorized++;
                }
            }
        }
        if (recategorized > 0) {
            try {
                storage.saveExpenses(manager.getExpensesForSave());
            } catch (IOException e) {
                showMessage("Failed to save: " + e.getMessage(), true);
                return;
            }
            refreshTable();
            showMessage("Re-categorized " + recategorized + " expense(s).", false);
        } else {
            showMessage("No uncategorized expenses could be matched to existing rules.", false);
        }
    }

    @FXML
    private void handleShowImportHistory() {
        javafx.stage.Stage dialog = new javafx.stage.Stage();
        dialog.initOwner(stage);
        dialog.initModality(javafx.stage.Modality.WINDOW_MODAL);
        dialog.setTitle("Import History");

        // Table
        TableView<ImportLog> table = new TableView<>();
        table.setItems(importLogs);
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);

        TableColumn<ImportLog, String> dateCol = new TableColumn<>("Date");
        dateCol.setCellValueFactory(new PropertyValueFactory<>("timestampDisplay"));
        dateCol.setMinWidth(140);

        TableColumn<ImportLog, String> sourceCol = new TableColumn<>("Source");
        sourceCol.setCellValueFactory(new PropertyValueFactory<>("sourceFile"));
        sourceCol.setMinWidth(180);

        TableColumn<ImportLog, String> typeCol = new TableColumn<>("Type");
        typeCol.setCellValueFactory(new PropertyValueFactory<>("sourceType"));
        typeCol.setMinWidth(80);

        TableColumn<ImportLog, Number> itemsCol = new TableColumn<>("Items");
        itemsCol.setCellValueFactory(new PropertyValueFactory<>("itemCount"));
        itemsCol.setMinWidth(60);

        table.getColumns().addAll(dateCol, sourceCol, typeCol, itemsCol);
        table.setPlaceholder(new Label("No imports yet"));

        // Delete button
        Button deleteBtn = new Button("Delete Selected Import");
        deleteBtn.getStyleClass().add("danger-button");
        deleteBtn.disableProperty().bind(table.getSelectionModel().selectedItemProperty().isNull());
        deleteBtn.setOnAction(e -> {
            ImportLog selected = table.getSelectionModel().getSelectedItem();
            if (selected == null) return;

            long count = manager.getExpenses().stream()
                .filter(exp -> selected.getImportId().equals(exp.getImportId()))
                .count();

            Alert confirmation = new Alert(Alert.AlertType.CONFIRMATION);
            confirmation.initOwner(dialog);
            confirmation.setTitle("Delete Import");
            confirmation.setHeaderText("Delete import from " + selected.getSourceFile() + "?");
            confirmation.setContentText("This will remove " + count + " expense(s) that were imported on " +
                selected.getTimestampDisplay() + ".");
            confirmation.getDialogPane().getStylesheets().add(getClass().getResource("/styles.css").toExternalForm());
            confirmation.getDialogPane().getStyleClass().add("dialog-pane");

            if (confirmation.showAndWait().orElse(null) != ButtonType.OK) return;

            List<Expense> toRemove = manager.getExpenses().stream()
                .filter(exp -> selected.getImportId().equals(exp.getImportId()))
                .collect(java.util.stream.Collectors.toList());

            if (!toRemove.isEmpty()) {
                manager.executeCommand(new Command() {
                    @Override public void execute() {
                        for (Expense exp : toRemove) manager.removeExpense(exp);
                    }
                    @Override public void undo() {
                        for (Expense exp : toRemove) manager.addExpense(exp);
                    }
                });
            }

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
        });

        // Layout
        HBox buttonBar = new HBox(deleteBtn);
        buttonBar.setAlignment(Pos.CENTER_RIGHT);
        buttonBar.setPadding(new javafx.geometry.Insets(10, 0, 0, 0));

        VBox root = new VBox(10, table, buttonBar);
        root.setPadding(new javafx.geometry.Insets(20));
        root.getStyleClass().add("dialog-pane");
        VBox.setVgrow(table, javafx.scene.layout.Priority.ALWAYS);

        Scene scene = new Scene(root, 560, 400);
        scene.getStylesheets().add(getClass().getResource("/styles.css").toExternalForm());
        dialog.setScene(scene);
        dialog.showAndWait();
    }

    @FXML
    private void handleDeleteImport() {
        // Legacy handler kept for FXML compatibility — opens the history dialog instead
        handleShowImportHistory();
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
        filterFieldsBox.setVisible(false);
        filterFieldsBox.setManaged(false);
        filterToggleButton.setText("Filters");
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
        // Route to the correct label based on the active view
        Label target;
        if ("import".equals(currentViewName)) {
            target = importErrorLabel;
        } else if ("recurring".equals(currentViewName)) {
            target = addRecurringErrorLabel;
        } else if ("dashboard".equals(currentViewName)) {
            target = errorLabel;
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
