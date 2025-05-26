package com.wyn.expensetracker;

import javafx.animation.PauseTransition;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.collections.transformation.SortedList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.scene.layout.*;
import javafx.stage.Stage;
import javafx.stage.FileChooser;
import javafx.scene.image.Image;
import javafx.util.Duration;
import java.io.File;
import java.io.IOException;
import java.time.LocalDate;
import java.time.Month;
import java.time.YearMonth;
import java.time.format.TextStyle;
import java.util.*;
import java.util.stream.Collectors;
import javafx.scene.chart.PieChart;
import javafx.scene.chart.BarChart;
import javafx.scene.chart.CategoryAxis;
import javafx.scene.chart.NumberAxis;
import javafx.scene.chart.XYChart;
import javafx.scene.input.KeyCode;
import javafx.scene.Node;
import javafx.scene.layout.VBox;

public class ExpenseTrackerApp extends Application {
    private ExpenseManager manager = new ExpenseManager();
    private FileStorage storage = new FileStorage();
    private TableView<Expense> expenseTable;
    private ObservableList<String> categories;
    private Label errorLabel;
    private FilteredList<Expense> filteredData;
    private TextField searchField;
    private ObservableList<Expense> expenseList;
    private Label totalLabel;
    private ComboBox<Integer> yearCombo;
    private ComboBox<Month> monthCombo;
    private ObservableList<Integer> yearList;
    private ObservableList<Month> monthList;
    private TableView<CategoryTotal> categoryTable;
    private ObservableList<CategoryTotal> categoryTotals;
    private TextField incomeField;
    private Label moneySavedLabel;
    private Map<YearMonth, Double> incomes;
    private PieChart categoryChart;
    private BarChart<String, Number> monthlyTrendChart;
    private ComboBox<String> chartPeriodCombo;
    private Button undoButton;
    private Button redoButton;
    private TableView<RecurringExpense> recurringTable;
    private ObservableList<RecurringExpense> recurringList;
    private TextField editRecurringAmountField;
    private ComboBox<String> editRecurringCategoryCombo;
    private DatePicker editRecurringDatePicker;
    private TextField editRecurringDescField;
    private ComboBox<RecurrenceType> editRecurringFreqCombo;
    private DatePicker editRecurringEndDatePicker;
    private Button updateRecurringButton;
    private RecurringExpense selectedRecurringExpense;

    @Override
    public void start(Stage stage) {
        errorLabel = new Label("");
        errorLabel.getStyleClass().add("error-label");

        try {
            stage.getIcons().add(new Image(getClass().getResourceAsStream("/expenseIcon.png")));
        } catch (Exception e) {
            setErrorMessage("Failed to load icon: " + e.getMessage());
        }

        try {
            categories = FXCollections.observableArrayList(storage.loadCategories());
        } catch (Exception e) {
            categories = FXCollections.observableArrayList("Food", "Transport", "Entertainment", "Utilities", "Other");
            setErrorMessage("Failed to load categories: " + e.getMessage());
        }

        try {
            manager.loadExpenses(storage.loadExpenses());
            manager.clearGeneratedRecurringIds();
            manager.generateRecurringExpenses(LocalDate.now());
            if (manager.getExpenses().isEmpty()) {
                if (!new File(storage.getExcelStorage().getLastSavedFilePath()).exists()) {
                    manager.executeCommand(new AddExpenseCommand(manager, new Expense(50.0, "Food", LocalDate.now(), "Groceries")));
                    manager.executeCommand(new AddExpenseCommand(manager, new Expense(30.0, "Transport", LocalDate.now(), "Bus fare")));
                    setErrorMessage("No expenses found. Added sample expenses.");
                    storage.saveExpenses(manager.getExpenses());
                }
            }
        } catch (Exception e) {
            setErrorMessage("Failed to load expenses: " + e.getMessage());
        }

        try {
            incomes = storage.loadIncomes();
        } catch (Exception e) {
            incomes = new HashMap<>();
            setErrorMessage("Failed to load incomes: " + e.getMessage());
        }

        BorderPane root = new BorderPane();
        root.getStyleClass().add("root-pane");

        VBox leftPanel = new VBox(5);
        leftPanel.setPadding(new Insets(20));
        leftPanel.getStyleClass().add("left-panel");
        VBox.setVgrow(leftPanel, Priority.NEVER);

        Label headerLabel = new Label("Expense Tracker");
        headerLabel.getStyleClass().add("header-label");
        headerLabel.setWrapText(true);

        HBox headerBox = new HBox(headerLabel);
        headerBox.setPadding(new Insets(0, 0, 20, 0));
        headerBox.setAlignment(Pos.CENTER);

        TabPane tabPane = new TabPane();
        tabPane.getStyleClass().add("tab-pane");
        VBox.setVgrow(tabPane, Priority.NEVER);

        Tab expenseTab = new Tab("Expenses");
        expenseTab.setClosable(false);
        VBox expenseTabContent = new VBox(5);
        expenseTabContent.setPadding(new Insets(10));
        expenseTabContent.getStyleClass().add("panel-box");
        VBox.setVgrow(expenseTabContent, Priority.NEVER);

        Label formTitle = new Label("Add New Expense");
        formTitle.getStyleClass().add("section-title");

        Label amountLabel = new Label("Amount:");
        amountLabel.getStyleClass().add("form-label");
        TextField amountField = createStyledTextField("e.g., 10.99");

        Label categoryLabel = new Label("Category:");
        categoryLabel.getStyleClass().add("form-label");
        ComboBox<String> categoryCombo = createStyledComboBox("Select or enter category", categories);

        HBox categoryButtons = new HBox(10);
        Button addCategoryButton = createStyledButton("Add Category", "primary-button");
        Button removeCategoryButton = createStyledButton("Remove Category", "danger-button");
        categoryButtons.getChildren().addAll(addCategoryButton, removeCategoryButton);
        categoryButtons.setPadding(new Insets(5, 0, 5, 0));

        Label dateLabel = new Label("Date:");
        dateLabel.getStyleClass().add("form-label");
        DatePicker datePicker = createStyledDatePicker();

        Label descLabel = new Label("Description (optional):");
        descLabel.getStyleClass().add("form-label");
        TextField descriptionField = createStyledTextField("Enter description");

        HBox actionButtons = new HBox(10);
        Button addButton = createStyledButton("Add Expense", "success-button");
        Button deleteButton = createStyledButton("Delete Selected", "danger-button");
        undoButton = createStyledButton("Undo", "primary-button");
        redoButton = createStyledButton("Redo", "primary-button");
        undoButton.setDisable(true);
        redoButton.setDisable(true);
        actionButtons.getChildren().addAll(addButton, deleteButton, undoButton, redoButton);

        expenseTabContent.getChildren().addAll(
            formTitle, amountLabel, amountField, categoryLabel, categoryCombo, categoryButtons,
            dateLabel, datePicker, descLabel, descriptionField, actionButtons);
        expenseTab.setContent(expenseTabContent);

        Tab recurringTab = new Tab("Recurring Expenses");
        recurringTab.setClosable(false);
        VBox recurringTabContent = new VBox(5);
        recurringTabContent.setPadding(new Insets(10));
        recurringTabContent.getStyleClass().add("panel-box");
        VBox.setVgrow(recurringTabContent, Priority.NEVER);

        Label addRecurringTitle = new Label("Add New Recurring Expense");
        addRecurringTitle.getStyleClass().add("section-title");

        Label addRecurringAmountLabel = new Label("Amount:");
        addRecurringAmountLabel.getStyleClass().add("form-label");
        TextField addRecurringAmountField = createStyledTextField("e.g., 10.99");

        Label addRecurringCategoryLabel = new Label("Category:");
        addRecurringCategoryLabel.getStyleClass().add("form-label");
        ComboBox<String> addRecurringCategoryCombo = createStyledComboBox("Select or enter category", categories);

        Label addRecurringDateLabel = new Label("Start Date:");
        addRecurringDateLabel.getStyleClass().add("form-label");
        DatePicker addRecurringDatePicker = createStyledDatePicker();

        Label addRecurringDescLabel = new Label("Description (optional):");
        addRecurringDescLabel.getStyleClass().add("form-label");
        TextField addRecurringDescField = createStyledTextField("Enter description");

        Label addRecurringFreqLabel = new Label("Frequency:");
        addRecurringFreqLabel.getStyleClass().add("form-label");
        ComboBox<RecurrenceType> addRecurringFreqCombo = new ComboBox<>(FXCollections.observableArrayList(RecurrenceType.values()));
        addRecurringFreqCombo.setPromptText("Select frequency");
        addRecurringFreqCombo.getStyleClass().add("combo-box");

        Label addRecurringEndDateLabel = new Label("End Date (optional):");
        addRecurringEndDateLabel.getStyleClass().add("form-label");
        DatePicker addRecurringEndDatePicker = new DatePicker();
        addRecurringEndDatePicker.setPromptText("Select end date");
        addRecurringEndDatePicker.getStyleClass().add("date-picker");

        Button addRecurringButton = createStyledButton("Add Recurring Expense", "success-button");

        VBox addRecurringForm = new VBox(5, addRecurringTitle, addRecurringAmountLabel, addRecurringAmountField,
            addRecurringCategoryLabel, addRecurringCategoryCombo, addRecurringDateLabel, addRecurringDatePicker,
            addRecurringDescLabel, addRecurringDescField, addRecurringFreqLabel, addRecurringFreqCombo,
            addRecurringEndDateLabel, addRecurringEndDatePicker, addRecurringButton);
        addRecurringForm.getStyleClass().add("panel-box");

        Label recurringTableTitle = new Label("Manage Recurring Expenses");
        recurringTableTitle.getStyleClass().add("section-title");

        recurringTable = new TableView<>();
        recurringTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        recurringTable.getStyleClass().add("table-view");
        recurringTable.setPrefHeight(150);

        TableColumn<RecurringExpense, Double> recurringAmountColumn = createTableColumn("Amount", "amount", Pos.CENTER_RIGHT, true);
        TableColumn<RecurringExpense, String> recurringCategoryColumn = createTableColumn("Category", "category", Pos.CENTER_LEFT, false);
        TableColumn<RecurringExpense, LocalDate> recurringDateColumn = createTableColumn("Start Date", "date", Pos.CENTER, false);
        TableColumn<RecurringExpense, String> recurringDescColumn = createTableColumn("Description", "description", Pos.CENTER_LEFT, false);
        TableColumn<RecurringExpense, RecurrenceType> recurringFreqColumn = createTableColumn("Frequency", "frequency", Pos.CENTER, false);
        TableColumn<RecurringExpense, LocalDate> recurringEndDateColumn = createTableColumn("End Date", "endDate", Pos.CENTER, false);

        @SuppressWarnings("unchecked")
        TableColumn<RecurringExpense, ?>[] recurringColumns = new TableColumn[]{
            recurringAmountColumn, recurringCategoryColumn, recurringDateColumn,
            recurringDescColumn, recurringFreqColumn, recurringEndDateColumn};
        recurringTable.getColumns().addAll(recurringColumns);

        recurringList = FXCollections.observableArrayList(manager.getBaseRecurringExpenses());
        recurringTable.setItems(recurringList);

        Label editRecurringTitle = new Label("Edit Selected Recurring Expense");
        editRecurringTitle.getStyleClass().add("section-title");

        Label editRecurringAmountLabel = new Label("Amount:");
        editRecurringAmountLabel.getStyleClass().add("form-label");
        editRecurringAmountField = createStyledTextField("e.g., 10.99");

        Label editRecurringCategoryLabel = new Label("Category:");
        editRecurringCategoryLabel.getStyleClass().add("form-label");
        editRecurringCategoryCombo = createStyledComboBox("Select category", categories);

        Label editRecurringDateLabel = new Label("Start Date:");
        editRecurringDateLabel.getStyleClass().add("form-label");
        editRecurringDatePicker = createStyledDatePicker();

        Label editRecurringDescLabel = new Label("Description (optional):");
        editRecurringDescLabel.getStyleClass().add("form-label");
        editRecurringDescField = createStyledTextField("Enter description");

        Label editRecurringFreqLabel = new Label("Frequency:");
        editRecurringFreqLabel.getStyleClass().add("form-label");
        editRecurringFreqCombo = new ComboBox<>(FXCollections.observableArrayList(RecurrenceType.values()));
        editRecurringFreqCombo.setPromptText("Select frequency");
        editRecurringFreqCombo.getStyleClass().add("combo-box");

        Label editRecurringEndDateLabel = new Label("End Date (optional):");
        editRecurringEndDateLabel.getStyleClass().add("form-label");
        editRecurringEndDatePicker = new DatePicker();
        editRecurringEndDatePicker.setPromptText("Select end date");
        editRecurringEndDatePicker.getStyleClass().add("date-picker");

        HBox recurringActionButtons = new HBox(10);
        updateRecurringButton = createStyledButton("Update Expense", "success-button");
        updateRecurringButton.setDisable(true);
        Button deleteRecurringButton = createStyledButton("Delete Selected", "danger-button");
        recurringActionButtons.getChildren().addAll(updateRecurringButton, deleteRecurringButton);

        VBox editRecurringForm = new VBox(5, editRecurringTitle, editRecurringAmountLabel, editRecurringAmountField,
            editRecurringCategoryLabel, editRecurringCategoryCombo, editRecurringDateLabel, editRecurringDatePicker,
            editRecurringDescLabel, editRecurringDescField, editRecurringFreqLabel, editRecurringFreqCombo,
            editRecurringEndDateLabel, editRecurringEndDatePicker, recurringActionButtons);
        editRecurringForm.getStyleClass().add("panel-box");

        recurringTabContent.getChildren().addAll(addRecurringForm, new Separator(), recurringTableTitle, recurringTable,
            new Separator(), editRecurringForm);
        recurringTab.setContent(recurringTabContent);

        tabPane.getTabs().addAll(expenseTab, recurringTab);

        tabPane.getSelectionModel().selectedItemProperty().addListener((obs, oldTab, newTab) -> {
    if (newTab != null) {
        tabPane.applyCss();
        tabPane.layout();
        double contentHeight = (newTab == expenseTab ? expenseTabContent : recurringTabContent).prefHeight(-1) + 40;
        tabPane.setPrefHeight(contentHeight);
    }
});

        VBox incomeBox = new VBox(5);
        incomeBox.getStyleClass().add("panel-box");

        Label incomeTitle = new Label("Income & Savings");
        incomeTitle.getStyleClass().add("section-title");

        Label incomeLabel = new Label("Monthly Income:");
        incomeLabel.getStyleClass().add("form-label");
        incomeField = createStyledTextField("e.g., 5000.00");

        VBox totalsBox = new VBox(5);
        totalLabel = new Label("Total Expenses: 0.00");
        totalLabel.getStyleClass().add("total-label");
        moneySavedLabel = new Label("Money Saved: 0.00");
        moneySavedLabel.getStyleClass().add("saved-label");
        totalsBox.getChildren().addAll(totalLabel, moneySavedLabel);

        incomeBox.getChildren().addAll(incomeTitle, incomeLabel, incomeField, new Separator(), totalsBox);

        VBox searchBox = new VBox(5);
        searchBox.getStyleClass().add("panel-box");

        Label searchTitle = new Label("Search Expenses");
        searchTitle.getStyleClass().add("section-title");

        searchField = createStyledTextField("Search by amount, category, date, or description");

        searchBox.getChildren().addAll(searchTitle, searchField);

        leftPanel.getChildren().addAll(headerBox, tabPane, new Separator(), incomeBox, new Separator(), searchBox, errorLabel);

        VBox rightPanel = new VBox(5);
        rightPanel.setPadding(new Insets(20));
        rightPanel.getStyleClass().add("right-panel");
        VBox.setVgrow(rightPanel, Priority.ALWAYS);

        Label periodLabel = new Label("Select Period:");
        periodLabel.getStyleClass().add("section-title");
        HBox selectorBox = new HBox(10);
        yearList = FXCollections.observableArrayList();
        yearCombo = createStyledComboBox("Year", yearList);
        monthList = FXCollections.observableArrayList(Month.values());
        monthCombo = createStyledComboBox("Month", monthList);
        selectorBox.getChildren().addAll(yearCombo, monthCombo);
        HBox periodBox = new HBox(10, periodLabel, selectorBox);
        periodBox.setAlignment(Pos.CENTER_LEFT);

        Label tableTitle = new Label("Expense Records");
        tableTitle.getStyleClass().add("section-title");

        expenseTable = new TableView<>();
        expenseTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        expenseTable.getStyleClass().add("table-view");
        expenseTable.setPrefHeight(400);

        TableColumn<Expense, Double> amountColumn = createTableColumn("Amount", "amount", Pos.CENTER_RIGHT, true);
        TableColumn<Expense, String> expenseCategoryColumn = createTableColumn("Category", "category", Pos.CENTER_LEFT, false);
        TableColumn<Expense, LocalDate> dateColumn = createTableColumn("Date", "date", Pos.CENTER, false);
        TableColumn<Expense, String> descriptionColumn = createTableColumn("Description", "description", Pos.CENTER_LEFT, false);

        @SuppressWarnings("unchecked")
        TableColumn<Expense, ?>[] expenseColumns = new TableColumn[]{amountColumn, expenseCategoryColumn, dateColumn, descriptionColumn};
        expenseTable.getColumns().addAll(expenseColumns);

        amountColumn.setPrefWidth(100);
        expenseCategoryColumn.setPrefWidth(150);
        dateColumn.setPrefWidth(120);
        descriptionColumn.setPrefWidth(250);

        expenseList = FXCollections.observableArrayList(manager.getExpenses());
        filteredData = new FilteredList<>(expenseList, p -> true);
        SortedList<Expense> sortedData = new SortedList<>(filteredData);
        sortedData.comparatorProperty().bind(expenseTable.comparatorProperty());
        expenseTable.setItems(sortedData);

        Button exportButton = createStyledButton("Export to Excel", "success-button");

        VBox analyticsBox = new VBox(5);
        analyticsBox.getStyleClass().add("panel-box");

        Label analyticsTitle = new Label("Expense Analytics");
        analyticsTitle.getStyleClass().add("section-title");

        HBox chartControls = new HBox(10);
        Label periodLabelChart = new Label("View by:");
        periodLabelChart.getStyleClass().add("form-label");
        chartPeriodCombo = new ComboBox<>(FXCollections.observableArrayList("All Time", "By Year", "By Month"));
        chartPeriodCombo.setValue("All Time");
        chartPeriodCombo.getStyleClass().add("combo-box");
        chartControls.getChildren().addAll(periodLabelChart, chartPeriodCombo);

        categoryChart = new PieChart();
        categoryChart.setTitle("Expenses by Category");
        categoryChart.setLegendVisible(true);
        categoryChart.getStyleClass().add("chart");
        categoryChart.setPrefHeight(250);

        CategoryAxis xAxis = new CategoryAxis();
        NumberAxis yAxis = new NumberAxis();
        monthlyTrendChart = new BarChart<>(xAxis, yAxis);
        monthlyTrendChart.setTitle("Monthly Trend");
        monthlyTrendChart.setLegendVisible(false);
        monthlyTrendChart.getStyleClass().add("chart");
        monthlyTrendChart.setPrefHeight(250);

        analyticsBox.getChildren().addAll(analyticsTitle, chartControls, categoryChart, monthlyTrendChart);

        Label categoryTableTitle = new Label("Expenses by Category");
        categoryTableTitle.getStyleClass().add("table-title");

        categoryTable = new TableView<>();
        categoryTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        categoryTable.setPrefHeight(180);
        categoryTable.getStyleClass().add("table-view");

        TableColumn<CategoryTotal, String> categoryColumn = createTableColumn("Category", "category", Pos.CENTER_LEFT, false);
        categoryColumn.setPrefWidth(120);
        TableColumn<CategoryTotal, Double> totalColumn = createTableColumn("Amount", "total", Pos.CENTER_RIGHT, true);
        totalColumn.setPrefWidth(80);

        @SuppressWarnings("unchecked")
        TableColumn<CategoryTotal, ?>[] categoryColumns = new TableColumn[]{categoryColumn, totalColumn};
        categoryTable.getColumns().addAll(categoryColumns);

        categoryTotals = FXCollections.observableArrayList();
        categoryTable.setItems(categoryTotals);

        rightPanel.getChildren().addAll(periodBox, new Separator(), tableTitle, expenseTable,
            new Separator(), exportButton, new Separator(), analyticsBox, new Separator(),
            categoryTableTitle, categoryTable);

        ScrollPane leftScrollPane = new ScrollPane(leftPanel);
        leftScrollPane.setFitToWidth(true);
        leftScrollPane.setHbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        leftScrollPane.getStyleClass().add("scroll-pane");

        ScrollPane rightScrollPane = new ScrollPane(rightPanel);
        rightScrollPane.setFitToWidth(true);
        rightScrollPane.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        rightScrollPane.getStyleClass().add("scroll-pane");

        SplitPane splitPane = new SplitPane();
        splitPane.setOrientation(javafx.geometry.Orientation.HORIZONTAL);
        splitPane.getItems().addAll(leftScrollPane, rightScrollPane);
        splitPane.setDividerPositions(0.4);
        SplitPane.setResizableWithParent(leftScrollPane, Boolean.TRUE);
        SplitPane.setResizableWithParent(rightScrollPane, Boolean.TRUE);
        root.setCenter(splitPane);

        BorderPane.setAlignment(splitPane, Pos.CENTER);
        splitPane.prefWidthProperty().bind(root.widthProperty());
        splitPane.prefHeightProperty().bind(root.heightProperty());

        Scene scene = new Scene(root, 1200, 800);
        try {
            scene.getStylesheets().add(getClass().getResource("/styles.css").toExternalForm());
        } catch (Exception e) {
            setErrorMessage("Failed to load stylesheet: " + e.getMessage());
        }

        tabPane.getSelectionModel().select(expenseTab);
        Platform.runLater(() -> {
    expenseTabContent.applyCss();
    expenseTabContent.layout();
    tabPane.applyCss();
    tabPane.layout();
    tabPane.setPrefHeight(expenseTabContent.prefHeight(-1) + 40);
    leftPanel.requestLayout();
});

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

        exportButton.setOnAction(event -> {
    String filePath = getExportFilePath(stage); // Always prompt user for file path
    if (filePath == null) {
        setErrorMessage("Export cancelled by user");
        return;
    }
    try {
        storage.saveExpenses(manager.getExpenses(), filePath); // Save to user-specified path
        setSuccessMessage("Expenses exported to Excel successfully at: " + filePath);
    } catch (IOException ex) {
        setErrorMessage("Failed to export to Excel: " + ex.getMessage());
    }
});

        PauseTransition searchDebounce = new PauseTransition(Duration.millis(300));
        searchField.textProperty().addListener((observable, oldValue, newValue) -> {
            searchDebounce.setOnFinished(e -> updateTotalExpenses());
            searchDebounce.playFromStart();
        });

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

        incomeField.textProperty().addListener((observable, oldValue, newValue) -> {
            Integer selectedYear = yearCombo.getValue();
            Month selectedMonth = monthCombo.getValue();
            if (selectedYear == null || selectedMonth == null) return;
            YearMonth selectedYearMonth = YearMonth.of(selectedYear, selectedMonth);
            try {
                double incomeValue = newValue.isEmpty() ? 0.0 : Double.parseDouble(newValue);
                if (incomeValue < 0) {
                    setErrorMessage("Income cannot be negative");
                    return;
                }
                incomes.put(selectedYearMonth, incomeValue);
                try {
                    storage.saveIncomes(incomes);
                    updateTotalExpenses();
                } catch (IOException ex) {
                    incomes.remove(selectedYearMonth);
                    setErrorMessage("Error saving incomes: " + ex.getMessage());
                }
            } catch (NumberFormatException ex) {
                setErrorMessage("Invalid income: Please enter a valid number (e.g., 5000.00)");
            }
        });

        chartPeriodCombo.valueProperty().addListener((obs, oldVal, newVal) -> updateCharts());

        addButton.setDefaultButton(true);
        amountField.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ENTER) addButton.fire();
        });

        expenseTable.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.DELETE) deleteButton.fire();
        });

        addButton.setOnAction(e -> {
            Expense expense = validateAndCreateExpense(amountField, categoryCombo, datePicker, descriptionField, false, null, null);
            if (expense != null) {
                try {
                    manager.executeCommand(new AddExpenseCommand(manager, expense));
                    storage.saveExpenses(manager.getExpenses());
                    refreshTable();
                    amountField.clear();
                    categoryCombo.setValue(null);
                    datePicker.setValue(LocalDate.now());
                    descriptionField.clear();
                    setSuccessMessage("Expense added successfully!");
                } catch (Exception ex) {
                    manager.undo();
                    setErrorMessage("Failed to save expense: " + ex.getMessage());
                }
            }
        });

        addRecurringButton.setOnAction(e -> {
            Expense expense = validateAndCreateExpense(addRecurringAmountField, addRecurringCategoryCombo,
                addRecurringDatePicker, addRecurringDescField, true, addRecurringFreqCombo, addRecurringEndDatePicker);
            if (expense != null) {
                try {
                    manager.executeCommand(new AddExpenseCommand(manager, expense));
                    storage.saveExpenses(manager.getExpenses());
                    manager.generateRecurringExpenses(LocalDate.now());
                    storage.saveExpenses(manager.getExpenses());
                    recurringList.setAll(manager.getBaseRecurringExpenses());
                    refreshTable();
                    addRecurringAmountField.clear();
                    addRecurringCategoryCombo.setValue(null);
                    addRecurringDatePicker.setValue(LocalDate.now());
                    addRecurringDescField.clear();
                    addRecurringFreqCombo.setValue(null);
                    addRecurringEndDatePicker.setValue(null);
                    setSuccessMessage("Recurring expense added successfully!");
                } catch (Exception ex) {
                    manager.undo();
                    setErrorMessage("Failed to save recurring expense: " + ex.getMessage());
                }
            }
        });

        deleteButton.setOnAction(e -> {
            Expense selectedExpense = expenseTable.getSelectionModel().getSelectedItem();
            if (selectedExpense == null) {
                setErrorMessage("Please select an expense to delete");
                return;
            }
            Alert confirmation = new Alert(Alert.AlertType.CONFIRMATION);
            confirmation.setTitle("Confirm Deletion");
            confirmation.setHeaderText(null);
            confirmation.setContentText("Are you sure you want to delete this expense?");
            Optional<ButtonType> result = confirmation.showAndWait();
            if (result.isPresent() && result.get() == ButtonType.OK) {
                try {
                    manager.executeCommand(new DeleteExpenseCommand(manager, selectedExpense));
                    storage.saveExpenses(manager.getExpenses());
                    refreshTable();
                    setSuccessMessage("Expense deleted successfully!");
                } catch (Exception ex) {
                    manager.undo();
                    setErrorMessage("Error deleting expense: " + ex.getMessage());
                }
            }
        });

        undoButton.setOnAction(e -> {
            try {
                manager.undo();
                storage.saveExpenses(manager.getExpenses());
                refreshTable();
                recurringList.setAll(manager.getBaseRecurringExpenses());
                updateUndoRedoButtons();
                setSuccessMessage("Undo successful!");
            } catch (Exception ex) {
                setErrorMessage("Error during undo: " + ex.getMessage());
            }
        });

        redoButton.setOnAction(e -> {
            try {
                manager.redo();
                storage.saveExpenses(manager.getExpenses());
                refreshTable();
                recurringList.setAll(manager.getBaseRecurringExpenses());
                updateUndoRedoButtons();
                setSuccessMessage("Redo successful!");
            } catch (Exception ex) {
                setErrorMessage("Error during redo: " + ex.getMessage());
            }
        });

        updateRecurringButton.setOnAction(e -> {
            if (selectedRecurringExpense == null) {
                setErrorMessage("Please select a recurring expense to update");
                return;
            }
            Expense expense = validateAndCreateExpense(editRecurringAmountField, editRecurringCategoryCombo,
                editRecurringDatePicker, editRecurringDescField, true, editRecurringFreqCombo, editRecurringEndDatePicker);
            if (expense != null) {
                try {
                    manager.updateRecurringExpense(selectedRecurringExpense, (RecurringExpense) expense);
                    storage.saveExpenses(manager.getExpenses());
                    refreshTable();
                    recurringList.setAll(manager.getBaseRecurringExpenses());
                    clearEditRecurringForm();
                    updateRecurringButton.setDisable(true);
                    setSuccessMessage("Recurring expense updated successfully!");
                } catch (Exception ex) {
                    setErrorMessage("Error updating recurring expense: " + ex.getMessage());
                }
            }
        });

        deleteRecurringButton.setOnAction(e -> {
            RecurringExpense selected = recurringTable.getSelectionModel().getSelectedItem();
            if (selected == null) {
                setErrorMessage("Please select a recurring expense to delete");
                return;
            }
            Alert confirmation = new Alert(Alert.AlertType.CONFIRMATION);
            confirmation.setTitle("Confirm Deletion");
            confirmation.setHeaderText(null);
            confirmation.setContentText("Are you sure you want to delete this recurring expense?");
            Optional<ButtonType> result = confirmation.showAndWait();
            if (result.isPresent() && result.get() == ButtonType.OK) {
                try {
                    manager.deleteRecurringExpense(selected);
                    storage.saveExpenses(manager.getExpenses());
                    refreshTable();
                    recurringList.setAll(manager.getBaseRecurringExpenses());
                    clearEditRecurringForm();
                    updateRecurringButton.setDisable(true);
                    setSuccessMessage("Recurring expense deleted successfully!");
                } catch (Exception ex) {
                    setErrorMessage("Error deleting recurring expense: " + ex.getMessage());
                }
            }
        });

        addCategoryButton.setOnAction(e -> {
            TextInputDialog dialog = new TextInputDialog();
            dialog.setTitle("Add Category");
            dialog.setHeaderText("Enter a new category:");
            dialog.setContentText("Category:");
            dialog.getDialogPane().getStyleClass().add("dialog-pane");
            dialog.showAndWait().ifPresent(category -> {
                category = category.trim();
                if (!category.isEmpty()) {
                    if (addCategory(category)) {
                        categoryCombo.setValue(category);
                        addRecurringCategoryCombo.setValue(category);
                        editRecurringCategoryCombo.setValue(category);
                        errorLabel.setText("");
                        errorLabel.getStyleClass().setAll("error-label");
                    }
                } else {
                    setErrorMessage("Category cannot be empty");
                }
            });
        });

        removeCategoryButton.setOnAction(e -> {
            String selectedCategory = categoryCombo.getValue();
            if (selectedCategory == null) {
                setErrorMessage("Please select a category to remove");
                return;
            }
            boolean isUsed = manager.getExpenses().stream()
                .anyMatch(expense -> expense.getCategory().equals(selectedCategory));
            if (isUsed) {
                setErrorMessage("Cannot remove category as it is used in existing expenses");
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
                errorLabel.setText("");
                errorLabel.getStyleClass().setAll("error-label");
            } catch (Exception ex) {
                categories.add(selectedCategory);
                setErrorMessage("Error saving categories: " + ex.getMessage());
            }
        });

        refreshTable();

        stage.setMinWidth(800);
        stage.setMinHeight(600);
        stage.setTitle("Expense Tracker");
        stage.setScene(scene);
        stage.show();
    }

private String getExportFilePath(Stage stage) {
    FileChooser fileChooser = new FileChooser();
    fileChooser.setTitle("Save Expenses to Excel");
    fileChooser.setInitialDirectory(new File(System.getProperty("user.home"))); // Start in home directory
    fileChooser.setInitialFileName("expenses.xlsx"); // Default file name
    fileChooser.getExtensionFilters().add(
        new FileChooser.ExtensionFilter("Excel Files", "*.xlsx") // Filter for .xlsx files
    );
    File selectedFile = fileChooser.showSaveDialog(stage);
    if (selectedFile != null) {
        String path = selectedFile.getAbsolutePath();
        // Ensure the file has a .xlsx extension
        if (!path.toLowerCase().endsWith(".xlsx")) {
            path += ".xlsx";
        }
        return path;
    }
    return null; // User cancelled the dialog
}

    private Expense validateAndCreateExpense(TextField amountField, ComboBox<String> categoryCombo,
            DatePicker datePicker, TextField descField, boolean isRecurring,
            ComboBox<RecurrenceType> freqCombo, DatePicker endDatePicker) {
        try {
            double amount = Double.parseDouble(amountField.getText());
            if (amount <= 0) {
                setErrorMessage("Amount must be positive");
                return null;
            }
            String category = categoryCombo.getValue();
            if (category == null || category.trim().isEmpty()) {
                category = categoryCombo.getEditor().getText().trim();
                if (category.isEmpty()) {
                    setErrorMessage("Category cannot be empty");
                    return null;
                }
                if (!categories.contains(category) && !addCategory(category)) {
                    return null;
                }
            }
            LocalDate date = datePicker.getValue();
            if (date == null) {
                setErrorMessage("Please select a " + (isRecurring ? "start date" : "date"));
                return null;
            }
            String description = descField.getText().trim();
            if (isRecurring) {
                RecurrenceType frequency = freqCombo.getValue();
                if (frequency == null) {
                    setErrorMessage("Please select a recurrence frequency");
                    return null;
                }
                LocalDate endDate = endDatePicker.getValue();
                return new RecurringExpense(amount, category, date, description, frequency, endDate);
            }
            return new Expense(amount, category, date, description);
        } catch (NumberFormatException ex) {
            setErrorMessage("Invalid amount: Please enter a valid number (e.g., 10.99)");
            return null;
        }
    }

    private boolean addCategory(String category) {
        if (!categories.contains(category)) {
            categories.add(category);
            try {
                storage.saveCategories(categories);
                return true;
            } catch (Exception ex) {
                categories.remove(category);
                setErrorMessage("Failed to save categories: " + ex.getMessage());
                return false;
            }
        }
        setErrorMessage("Category already exists");
        return false;
    }

    private void setErrorMessage(String message) {
        errorLabel.setText(message);
        errorLabel.getStyleClass().setAll("error-label", "error-message");
    }

    private void setSuccessMessage(String message) {
        errorLabel.setText(message);
        errorLabel.getStyleClass().setAll("error-label", "success-message");
    }

    private void applyChartNodeStyle(Node node, String color) {
        if (node != null) {
            node.setStyle("-fx-pie-color: " + color + "; -fx-bar-fill: " + color + ";");
        }
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
            setErrorMessage("Error refreshing table: " + e.getMessage());
        }
    }

    private void updateUndoRedoButtons() {
        undoButton.setDisable(!manager.canUndo());
        redoButton.setDisable(!manager.canRedo());
    }

    private void clearEditRecurringForm() {
        editRecurringAmountField.clear();
        editRecurringCategoryCombo.setValue(null);
        editRecurringDatePicker.setValue(null);
        editRecurringDescField.clear();
        editRecurringFreqCombo.setValue(null);
        editRecurringEndDatePicker.setValue(null);
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
        List<Expense> filteredExpenses = expenseList.stream()
            .filter(expense -> YearMonth.from(expense.getDate()).equals(selectedYearMonth))
            .filter(expense -> {
                String filter = searchField.getText();
                if (filter == null || filter.isEmpty()) return true;
                String lowerCaseFilter = filter.toLowerCase();
                return String.valueOf(expense.getAmount()).contains(lowerCaseFilter) ||
                       expense.getCategory().toLowerCase().contains(lowerCaseFilter) ||
                       expense.getDate().toString().contains(lowerCaseFilter) ||
                       (expense.getDescription() != null && expense.getDescription().toLowerCase().contains(lowerCaseFilter));
            })
            .collect(Collectors.toList());
        filteredData.setPredicate(expense -> filteredExpenses.contains(expense));
        double total = filteredExpenses.stream().mapToDouble(Expense::getAmount).sum();
        totalLabel.setText(String.format("Total Expenses for %s %d: %.2f",
                selectedMonth.getDisplayName(TextStyle.FULL, Locale.ENGLISH), selectedYear, total));
        double income = incomes.getOrDefault(selectedYearMonth, 0.0);
        double moneySaved = income - total;
        moneySavedLabel.setText(String.format("Money Saved: %.2f", Math.max(0, moneySaved)));
        Map<String, Double> categoryMap = filteredExpenses.stream()
            .collect(Collectors.groupingBy(Expense::getCategory, Collectors.summingDouble(Expense::getAmount)));
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
                    case "By Year": return expense.getDate().getYear() == selectedYear;
                    case "By Month": return YearMonth.from(expense.getDate()).equals(selectedYearMonth);
                    default: return true;
                }
            })
            .collect(Collectors.toList());
        Map<String, Double> categoryMap = chartExpenses.stream()
            .collect(Collectors.groupingBy(Expense::getCategory, Collectors.summingDouble(Expense::getAmount)));
        ObservableList<PieChart.Data> pieChartData = FXCollections.observableArrayList();
        String[] colors = {"#FF6F61", "#6B5B95", "#88B04B", "#F7B731", "#4ECDC4"};
        List<Map.Entry<String, Double>> sortedEntries = categoryMap.entrySet().stream()
            .sorted(Map.Entry.comparingByValue(Comparator.reverseOrder()))
            .collect(Collectors.toList());
        for (int i = 0; i < sortedEntries.size(); i++) {
            Map.Entry<String, Double> entry = sortedEntries.get(i);
            final String color = colors[i % colors.length];
            PieChart.Data data = new PieChart.Data(
                entry.getKey() + " (" + String.format("%.2f", entry.getValue()) + ")", entry.getValue());
            data.nodeProperty().addListener((obs, oldNode, newNode) -> applyChartNodeStyle(newNode, color));
            pieChartData.add(data);
        }
        categoryChart.setData(pieChartData);
        monthlyTrendChart.getData().clear();
        XYChart.Series<String, Number> series = new XYChart.Series<>();
        Map<YearMonth, Double> monthlyTotals = expenseList.stream()
            .collect(Collectors.groupingBy(expense -> YearMonth.from(expense.getDate()),
                Collectors.summingDouble(Expense::getAmount)));
        monthlyTotals.entrySet().stream()
            .sorted(Map.Entry.comparingByKey())
            .filter(entry -> {
                switch (chartPeriod) {
                    case "By Year": return entry.getKey().getYear() == selectedYear;
                    case "By Month": return entry.getKey().equals(selectedYearMonth);
                    default: return true;
                }
            })
            .forEach(entry -> {
                XYChart.Data<String, Number> data = new XYChart.Data<>(
                    entry.getKey().getMonth().toString() + " " + entry.getKey().getYear(), entry.getValue());
                data.nodeProperty().addListener((obs, oldNode, newNode) -> applyChartNodeStyle(newNode, "#4CAF50"));
                series.getData().add(data);
            });
        monthlyTrendChart.getData().add(series);
    }

    private TextField createStyledTextField(String prompt) {
        TextField field = new TextField();
        field.setPromptText(prompt);
        field.getStyleClass().add("text-field");
        return field;
    }

    private <T> ComboBox<T> createStyledComboBox(String prompt, ObservableList<T> items) {
        ComboBox<T> combo = new ComboBox<>(items);
        combo.setPromptText(prompt);
        combo.getStyleClass().add("combo-box");
        combo.setCellFactory(lv -> new ListCell<T>() {
            @Override
            protected void updateItem(T item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : item.toString());
            }
        });
        return combo;
    }

    private DatePicker createStyledDatePicker() {
        DatePicker picker = new DatePicker();
        picker.setPromptText("Select Date");
        picker.setValue(LocalDate.now());
        picker.getStyleClass().add("date-picker");
        return picker;
    }

    private Button createStyledButton(String text, String styleClass) {
        Button button = new Button(text);
        button.getStyleClass().add(styleClass);
        return button;
    }

    private <S, T> TableColumn<S, T> createTableColumn(String title, String property, Pos alignment, boolean isNumeric) {
        TableColumn<S, T> column = new TableColumn<>(title);
        column.setCellValueFactory(new PropertyValueFactory<>(property));
        column.setCellFactory(tc -> new TableCell<S, T>() {
            @Override
            protected void updateItem(T item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                } else {
                    setText(isNumeric && item instanceof Number ? String.format("%.2f", ((Number) item).doubleValue()) : item.toString());
                }
                setAlignment(alignment);
            }
        });
        return column;
    }

    public static void main(String[] args) {
        launch(args);
    }
}