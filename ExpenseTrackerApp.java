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
        // Initialize error label for displaying messages
        errorLabel = new Label("");
        errorLabel.getStyleClass().add("error-label");

        // Load application icon
        try {
            stage.getIcons().add(new Image(getClass().getResourceAsStream("/expenseIcon.png")));
        } catch (Exception e) {
            System.err.println("Failed to load icon: " + e.getMessage());
            errorLabel.setText("Failed to load icon: " + e.getMessage());
        }

        // Load categories from storage
        try {
            categories = FXCollections.observableArrayList(storage.loadCategories());
        } catch (Exception e) {
            categories = FXCollections.observableArrayList("Food", "Transport", "Entertainment", "Utilities", "Other");
            errorLabel.setText("Failed to load categories: " + e.getMessage());
        }

        // Load expenses and generate recurring expenses
        try {
            manager.loadExpenses(storage.loadExpenses());
            manager.clearGeneratedRecurringIds();
            manager.generateRecurringExpenses(LocalDate.now());
            System.out.println("Loaded " + manager.getExpenses().size() + " expenses");
            if (manager.getExpenses().isEmpty()) {
                if (!new File(storage.getExcelStorage().getLastSavedFilePath()).exists()) {
                    manager.executeCommand(new AddExpenseCommand(manager, new Expense(50.0, "Food", LocalDate.now(), "Groceries")));
                    manager.executeCommand(new AddExpenseCommand(manager, new Expense(30.0, "Transport", LocalDate.now(), "Bus fare")));
                    errorLabel.setText("No expenses found. Added sample expenses.");
                    storage.saveExpenses(manager.getExpenses());
                }
            }
        } catch (Exception e) {
            System.err.println("Error loading expenses: " + e.getMessage());
            errorLabel.setText("Failed to load expenses: " + e.getMessage());
        }

        // Load incomes from storage
        try {
            incomes = storage.loadIncomes();
        } catch (Exception e) {
            incomes = new HashMap<>();
            errorLabel.setText("Failed to load incomes: " + e.getMessage());
        }

        // Set up the root layout
        BorderPane root = new BorderPane();
        root.getStyleClass().add("root-pane");

        // Configure left panel
        VBox leftPanel = new VBox(5);
        leftPanel.setPadding(new Insets(20));
        leftPanel.getStyleClass().add("left-panel");
        VBox.setVgrow(leftPanel, Priority.NEVER); // Prevent stretching beyond content

        // Create header
        //Label headerLabel = new Label("Expense Tracker");
        //headerLabel.getStyleClass().add("header-label");
        //headerLabel.setWrapText(true);

        //HBox headerBox = new HBox(headerLabel);
        //headerBox.setPadding(new Insets(0, 0, 20, 0));
        //headerBox.setAlignment(Pos.CENTER);

        // Set up TabPane for Expenses and Recurring Expenses
        TabPane tabPane = new TabPane();
        tabPane.getStyleClass().add("tab-pane");
        VBox.setVgrow(tabPane, Priority.NEVER); // TabPane should not stretch

        // Expenses Tab
        Tab expenseTab = new Tab("Expenses");
        expenseTab.setClosable(false);
        VBox expenseTabContent = new VBox(5);
        expenseTabContent.setPadding(new Insets(10));
        expenseTabContent.getStyleClass().add("panel-box");
        VBox.setVgrow(expenseTabContent, Priority.NEVER); // Content should not stretch

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
            formTitle,
            amountLabel, amountField,
            categoryLabel, categoryCombo, categoryButtons,
            dateLabel, datePicker,
            descLabel, descriptionField,
            actionButtons
        );
        expenseTab.setContent(expenseTabContent);

        // Recurring Expenses Tab
        Tab recurringTab = new Tab("Recurring Expenses");
        recurringTab.setClosable(false);
        VBox recurringTabContent = new VBox(5);
        recurringTabContent.setPadding(new Insets(10));
        recurringTabContent.getStyleClass().add("panel-box");
        VBox.setVgrow(recurringTabContent, Priority.NEVER);

        // Add New Recurring Expense Form
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

        VBox addRecurringForm = new VBox(5,
            addRecurringTitle,
            addRecurringAmountLabel, addRecurringAmountField,
            addRecurringCategoryLabel, addRecurringCategoryCombo,
            addRecurringDateLabel, addRecurringDatePicker,
            addRecurringDescLabel, addRecurringDescField,
            addRecurringFreqLabel, addRecurringFreqCombo,
            addRecurringEndDateLabel, addRecurringEndDatePicker,
            addRecurringButton
        );
        addRecurringForm.getStyleClass().add("panel-box");

        // Recurring Expenses Table
        Label recurringTableTitle = new Label("Manage Recurring Expenses");
        recurringTableTitle.getStyleClass().add("section-title");

        recurringTable = new TableView<>();
        recurringTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        recurringTable.getStyleClass().add("table-view");
        recurringTable.setPrefHeight(150);

        TableColumn<RecurringExpense, Double> recurringAmountColumn = createTableColumn("Amount", "amount", Pos.CENTER_RIGHT);
        TableColumn<RecurringExpense, String> recurringCategoryColumn = createTableColumn("Category", "category", Pos.CENTER_LEFT);
        TableColumn<RecurringExpense, LocalDate> recurringDateColumn = createTableColumn("Start Date", "date", Pos.CENTER);
        TableColumn<RecurringExpense, String> recurringDescColumn = createTableColumn("Description", "description", Pos.CENTER_LEFT);
        TableColumn<RecurringExpense, RecurrenceType> recurringFreqColumn = createTableColumn("Frequency", "frequency", Pos.CENTER);
        TableColumn<RecurringExpense, LocalDate> recurringEndDateColumn = createTableColumn("End Date", "endDate", Pos.CENTER);

        @SuppressWarnings("unchecked")
        TableColumn<RecurringExpense, ?>[] recurringColumns = new TableColumn[]{
            recurringAmountColumn, recurringCategoryColumn, recurringDateColumn,
            recurringDescColumn, recurringFreqColumn, recurringEndDateColumn
        };
        recurringTable.getColumns().addAll(recurringColumns);

        recurringList = FXCollections.observableArrayList(manager.getBaseRecurringExpenses());
        recurringTable.setItems(recurringList);

        // Edit Recurring Expense Form
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

        VBox editRecurringForm = new VBox(5,
            editRecurringTitle,
            editRecurringAmountLabel, editRecurringAmountField,
            editRecurringCategoryLabel, editRecurringCategoryCombo,
            editRecurringDateLabel, editRecurringDatePicker,
            editRecurringDescLabel, editRecurringDescField,
            editRecurringFreqLabel, editRecurringFreqCombo,
            editRecurringEndDateLabel, editRecurringEndDatePicker,
            recurringActionButtons
        );
        editRecurringForm.getStyleClass().add("panel-box");

        recurringTabContent.getChildren().addAll(
            addRecurringForm,
            new Separator(),
            recurringTableTitle,
            recurringTable,
            new Separator(),
            editRecurringForm
        );
        recurringTab.setContent(recurringTabContent);

        tabPane.getTabs().addAll(expenseTab, recurringTab);

        // Dynamically adjust TabPane height based on selected tab to prevent gaps
        tabPane.getSelectionModel().selectedItemProperty().addListener((obs, oldTab, newTab) -> {
            if (newTab != null) {
                tabPane.applyCss();
                tabPane.layout();
                double contentHeight;
                if (newTab == expenseTab) {
                    expenseTabContent.applyCss();
                    expenseTabContent.layout();
                    contentHeight = expenseTabContent.prefHeight(-1) + 40; // Include tab header height
                } else {
                    recurringTabContent.applyCss();
                    recurringTabContent.layout();
                    contentHeight = recurringTabContent.prefHeight(-1) + 40;
                }
                tabPane.setPrefHeight(contentHeight);
            }
        });

        // Set up Income & Savings section
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

        incomeBox.getChildren().addAll(
            incomeTitle,
            incomeLabel, incomeField,
            new Separator(),
            totalsBox
        );

        // Set up Search section
        VBox searchBox = new VBox(5);
        searchBox.getStyleClass().add("panel-box");

        Label searchTitle = new Label("Search Expenses");
        searchTitle.getStyleClass().add("section-title");

        searchField = createStyledTextField("Search by amount, category, date, or description");

        searchBox.getChildren().addAll(
            searchTitle,
            searchField
        );

        // Assemble left panel
        leftPanel.getChildren().addAll(
            //headerBox,
            tabPane,
            new Separator(),
            incomeBox,
            new Separator(),
            searchBox,
            errorLabel
        );

        // Set up right panel
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

        TableColumn<Expense, Double> amountColumn = createTableColumn("Amount", "amount", Pos.CENTER_RIGHT);
        TableColumn<Expense, String> expenseCategoryColumn = createTableColumn("Category", "category", Pos.CENTER_LEFT);
        TableColumn<Expense, LocalDate> dateColumn = createTableColumn("Date", "date", Pos.CENTER);
        TableColumn<Expense, String> descriptionColumn = createTableColumn("Description", "description", Pos.CENTER_LEFT);

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

        analyticsBox.getChildren().addAll(
            analyticsTitle,
            chartControls,
            categoryChart,
            monthlyTrendChart
        );

        Label categoryTableTitle = new Label("Expenses by Category");
        categoryTableTitle.getStyleClass().add("table-title");

        categoryTable = new TableView<>();
        categoryTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        categoryTable.setPrefHeight(180);
        categoryTable.getStyleClass().add("table-view");

        TableColumn<CategoryTotal, String> categoryColumn = new TableColumn<>("Category");
        categoryColumn.setCellValueFactory(new PropertyValueFactory<>("category"));
        categoryColumn.setPrefWidth(120);
        categoryColumn.setCellFactory(tc -> new TableCell<CategoryTotal, String>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty ? null : item);
            }
        });

        TableColumn<CategoryTotal, Double> totalColumn = new TableColumn<>("Amount");
        totalColumn.setCellValueFactory(new PropertyValueFactory<>("total"));
        totalColumn.setPrefWidth(80);
        totalColumn.setCellFactory(tc -> new TableCell<CategoryTotal, Double>() {
            @Override
            protected void updateItem(Double item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : String.format("%.2f", item));
            }
        });

        @SuppressWarnings("unchecked")
        TableColumn<CategoryTotal, ?>[] categoryColumns = new TableColumn[]{categoryColumn, totalColumn};
        categoryTable.getColumns().addAll(categoryColumns);

        categoryTotals = FXCollections.observableArrayList();
        categoryTable.setItems(categoryTotals);

        rightPanel.getChildren().addAll(
            periodBox,
            new Separator(),
            tableTitle,
            expenseTable,
            new Separator(),
            exportButton,
            new Separator(),
            analyticsBox,
            new Separator(),
            categoryTableTitle,
            categoryTable
        );

        // Set up ScrollPanes for left and right panels
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

        // Create and configure the scene
        Scene scene = new Scene(root, 1200, 800);
        try {
            scene.getStylesheets().add(getClass().getResource("/styles.css").toExternalForm());
        } catch (Exception e) {
            System.err.println("Failed to load stylesheet: " + e.getMessage());
            errorLabel.setText("Failed to load stylesheet: " + e.getMessage());
        }

        // Set initial tab and defer height adjustment until after the scene is shown
        tabPane.getSelectionModel().select(expenseTab);
        Platform.runLater(() -> {
            expenseTabContent.applyCss();
            expenseTabContent.layout();
            tabPane.applyCss();
            tabPane.layout();
            double initialHeight = expenseTabContent.prefHeight(-1) + 40; // Include tab header
            tabPane.setPrefHeight(initialHeight);
            leftPanel.requestLayout(); // Force layout update
        });

        // Event Handlers
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

        exportButton.setOnAction(e -> {
            try {
                File defaultFile = new File(System.getProperty("user.home") + File.separator + ".expenseTracker" + File.separator + "expenses.xlsx");
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
                        errorLabel.setText("Export cancelled by user");
                        errorLabel.getStyleClass().setAll("error-label", "error-message");
                        return;
                    }
                    filePath = selectedFile.getAbsolutePath();
                } else {
                    filePath = defaultFile.getAbsolutePath();
                }

                storage.saveExpenses(manager.getExpenses());
                errorLabel.setText("Expenses exported to Excel successfully at: " + filePath);
                errorLabel.getStyleClass().setAll("error-label", "success-message");
            } catch (IOException ex) {
                errorLabel.setText("Failed to export to Excel: " + ex.getMessage());
                errorLabel.getStyleClass().setAll("error-label", "error-message");
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
                    errorLabel.setText("Income cannot be negative");
                    errorLabel.getStyleClass().setAll("error-label", "error-message");
                    return;
                }
                incomes.put(selectedYearMonth, incomeValue);
                try {
                    storage.saveIncomes(incomes);
                    updateTotalExpenses();
                } catch (IOException ex) {
                    incomes.remove(selectedYearMonth);
                    errorLabel.setText("Error saving incomes: " + ex.getMessage());
                    errorLabel.getStyleClass().setAll("error-label", "error-message");
                }
            } catch (NumberFormatException ex) {
                errorLabel.setText("Invalid income: Please enter a valid number (e.g., 5000.00)");
                errorLabel.getStyleClass().setAll("error-label", "error-message");
            }
        });

        chartPeriodCombo.valueProperty().addListener((obs, oldVal, newVal) -> {
            updateCharts();
        });

        addButton.setDefaultButton(true);
        amountField.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ENTER) {
                addButton.fire();
            }
        });

        expenseTable.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.DELETE) {
                deleteButton.fire();
            }
        });

        // Add Expense (Expenses Tab)
        addButton.setOnAction(e -> {
            try {
                double amount = Double.parseDouble(amountField.getText());
                if (amount <= 0) {
                    errorLabel.setText("Amount must be positive");
                    errorLabel.getStyleClass().setAll("error-label", "error-message");
                    return;
                }
                String category = categoryCombo.getValue();
                if (category == null || category.trim().isEmpty()) {
                    category = categoryCombo.getEditor().getText().trim();
                    if (category.isEmpty()) {
                        errorLabel.setText("Category cannot be empty");
                        errorLabel.getStyleClass().setAll("error-label", "error-message");
                        return;
                    }
                    if (!categories.contains(category)) {
                        categories.add(category);
                        try {
                            storage.saveCategories(categories);
                        } catch (Exception ex) {
                            categories.remove(category);
                            errorLabel.setText("Failed to save categories: " + ex.getMessage());
                            errorLabel.getStyleClass().setAll("error-label", "error-message");
                            return;
                        }
                    }
                }
                LocalDate date = datePicker.getValue();
                String description = descriptionField.getText().trim();

                if (date == null) {
                    errorLabel.setText("Please select a date");
                    errorLabel.getStyleClass().setAll("error-label", "error-message");
                    return;
                }

                Expense expense = new Expense(amount, category, date, description.isEmpty() ? "" : description);
                manager.executeCommand(new AddExpenseCommand(manager, expense));
                try {
                    storage.saveExpenses(manager.getExpenses());
                } catch (Exception ex) {
                    manager.undo();
                    errorLabel.setText("Failed to save expense: " + ex.getMessage());
                    errorLabel.getStyleClass().setAll("error-label", "error-message");
                    return;
                }
                refreshTable();

                amountField.clear();
                categoryCombo.setValue(null);
                datePicker.setValue(LocalDate.now());
                descriptionField.clear();
                errorLabel.setText("Expense added successfully!");
                errorLabel.getStyleClass().setAll("error-label", "success-message");
            } catch (NumberFormatException ex) {
                errorLabel.setText("Invalid amount: Please enter a valid number (e.g., 10.99)");
                errorLabel.getStyleClass().setAll("error-label", "error-message");
            } catch (Exception ex) {
                errorLabel.setText("Error: " + ex.getMessage());
                errorLabel.getStyleClass().setAll("error-label", "error-message");
            }
        });

        // Add Recurring Expense (Recurring Expenses Tab)
        addRecurringButton.setOnAction(e -> {
            try {
                double amount = Double.parseDouble(addRecurringAmountField.getText());
                if (amount <= 0) {
                    errorLabel.setText("Amount must be positive");
                    errorLabel.getStyleClass().setAll("error-label", "error-message");
                    return;
                }
                String category = addRecurringCategoryCombo.getValue();
                if (category == null || category.trim().isEmpty()) {
                    category = addRecurringCategoryCombo.getEditor().getText().trim();
                    if (category.isEmpty()) {
                        errorLabel.setText("Category cannot be empty");
                        errorLabel.getStyleClass().setAll("error-label", "error-message");
                        return;
                    }
                    if (!categories.contains(category)) {
                        categories.add(category);
                        try {
                            storage.saveCategories(categories);
                        } catch (Exception ex) {
                            categories.remove(category);
                            errorLabel.setText("Failed to save categories: " + ex.getMessage());
                            errorLabel.getStyleClass().setAll("error-label", "error-message");
                            return;
                        }
                    }
                }
                LocalDate date = addRecurringDatePicker.getValue();
                if (date == null) {
                    errorLabel.setText("Please select a start date");
                    errorLabel.getStyleClass().setAll("error-label", "error-message");
                    return;
                }
                String description = addRecurringDescField.getText().trim();
                RecurrenceType frequency = addRecurringFreqCombo.getValue();
                if (frequency == null) {
                    errorLabel.setText("Please select a recurrence frequency");
                    errorLabel.getStyleClass().setAll("error-label", "error-message");
                    return;
                }
                LocalDate endDate = addRecurringEndDatePicker.getValue();

                RecurringExpense expense = new RecurringExpense(amount, category, date, description.isEmpty() ? "" : description, frequency, endDate);
                manager.executeCommand(new AddExpenseCommand(manager, expense));
                try {
                    storage.saveExpenses(manager.getExpenses());
                    manager.generateRecurringExpenses(LocalDate.now());
                    storage.saveExpenses(manager.getExpenses());
                    recurringList.setAll(manager.getBaseRecurringExpenses());
                } catch (Exception ex) {
                    manager.undo();
                    errorLabel.setText("Failed to save recurring expense: " + ex.getMessage());
                    errorLabel.getStyleClass().setAll("error-label", "error-message");
                    return;
                }
                refreshTable();

                addRecurringAmountField.clear();
                addRecurringCategoryCombo.setValue(null);
                addRecurringDatePicker.setValue(LocalDate.now());
                addRecurringDescField.clear();
                addRecurringFreqCombo.setValue(null);
                addRecurringEndDatePicker.setValue(null);
                errorLabel.setText("Recurring expense added successfully!");
                errorLabel.getStyleClass().setAll("error-label", "success-message");
            } catch (NumberFormatException ex) {
                errorLabel.setText("Invalid amount: Please enter a valid number (e.g., 10.99)");
                errorLabel.getStyleClass().setAll("error-label", "error-message");
            } catch (Exception ex) {
                errorLabel.setText("Error: " + ex.getMessage());
                errorLabel.getStyleClass().setAll("error-label", "error-message");
            }
        });

        // Delete Expense (Expenses Tab)
        deleteButton.setOnAction(e -> {
            Expense selectedExpense = expenseTable.getSelectionModel().getSelectedItem();
            if (selectedExpense == null) {
                errorLabel.setText("Please select an expense to delete");
                errorLabel.getStyleClass().setAll("error-label", "error-message");
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
                    storage.saveExpenses(manager.getExpenses());
                    refreshTable();
                    errorLabel.setText("Expense deleted successfully!");
                    errorLabel.getStyleClass().setAll("error-label", "success-message");
                } catch (Exception ex) {
                    manager.undo();
                    errorLabel.setText("Error deleting expense: " + ex.getMessage());
                    errorLabel.getStyleClass().setAll("error-label", "error-message");
                }
            }
        });

        // Undo
        undoButton.setOnAction(e -> {
            manager.undo();
            try {
                storage.saveExpenses(manager.getExpenses());
                refreshTable();
                recurringList.setAll(manager.getBaseRecurringExpenses());
                updateUndoRedoButtons();
                errorLabel.setText("Undo successful!");
                errorLabel.getStyleClass().setAll("error-label", "success-message");
            } catch (Exception ex) {
                errorLabel.setText("Error during undo: " + ex.getMessage());
                errorLabel.getStyleClass().setAll("error-label", "error-message");
            }
        });

        // Redo
        redoButton.setOnAction(e -> {
            manager.redo();
            try {
                storage.saveExpenses(manager.getExpenses());
                refreshTable();
                recurringList.setAll(manager.getBaseRecurringExpenses());
                updateUndoRedoButtons();
                errorLabel.setText("Redo successful!");
                errorLabel.getStyleClass().setAll("error-label", "success-message");
            } catch (Exception ex) {
                errorLabel.setText("Error during redo: " + ex.getMessage());
                errorLabel.getStyleClass().setAll("error-label", "error-message");
            }
        });

        // Update Recurring Expense
        updateRecurringButton.setOnAction(e -> {
            if (selectedRecurringExpense == null) {
                errorLabel.setText("Please select a recurring expense to update");
                errorLabel.getStyleClass().setAll("error-label", "error-message");
                return;
            }

            try {
                double amount = Double.parseDouble(editRecurringAmountField.getText());
                if (amount <= 0) {
                    errorLabel.setText("Amount must be positive");
                    errorLabel.getStyleClass().setAll("error-label", "error-message");
                    return;
                }
                String category = editRecurringCategoryCombo.getValue();
                if (category == null || category.trim().isEmpty()) {
                    errorLabel.setText("Category cannot be empty");
                    errorLabel.getStyleClass().setAll("error-label", "error-message");
                    return;
                }
                LocalDate date = editRecurringDatePicker.getValue();
                if (date == null) {
                    errorLabel.setText("Please select a start date");
                    errorLabel.getStyleClass().setAll("error-label", "error-message");
                    return;
                }
                String description = editRecurringDescField.getText().trim();
                RecurrenceType frequency = editRecurringFreqCombo.getValue();
                if (frequency == null) {
                    errorLabel.setText("Please select a recurrence frequency");
                    errorLabel.getStyleClass().setAll("error-label", "error-message");
                    return;
                }
                LocalDate endDate = editRecurringEndDatePicker.getValue();

                RecurringExpense newExpense = new RecurringExpense(amount, category, date, description, frequency, endDate);
                manager.updateRecurringExpense(selectedRecurringExpense, newExpense);
                try {
                    storage.saveExpenses(manager.getExpenses());
                    refreshTable();
                    recurringList.setAll(manager.getBaseRecurringExpenses());
                    clearEditRecurringForm();
                    updateRecurringButton.setDisable(true);
                    errorLabel.setText("Recurring expense updated successfully!");
                    errorLabel.getStyleClass().setAll("error-label", "success-message");
                } catch (Exception ex) {
                    errorLabel.setText("Error updating recurring expense: " + ex.getMessage());
                    errorLabel.getStyleClass().setAll("error-label", "error-message");
                }
            } catch (NumberFormatException ex) {
                errorLabel.setText("Invalid amount: Please enter a valid number (e.g., 10.99)");
                errorLabel.getStyleClass().setAll("error-label", "error-message");
            }
        });

        // Delete Recurring Expense
        deleteRecurringButton.setOnAction(e -> {
            RecurringExpense selected = recurringTable.getSelectionModel().getSelectedItem();
            if (selected == null) {
                errorLabel.setText("Please select a recurring expense to delete");
                errorLabel.getStyleClass().setAll("error-label", "error-message");
                return;
            }

            Alert confirmation = new Alert(Alert.AlertType.CONFIRMATION);
            confirmation.setTitle("Confirm Deletion");
            confirmation.setHeaderText(null);
            confirmation.setContentText("Are you sure you want to delete this recurring expense?");

            Optional<ButtonType> result = confirmation.showAndWait();
            if (result.isPresent() && result.get() == ButtonType.OK) {
                manager.deleteRecurringExpense(selected);
                try {
                    storage.saveExpenses(manager.getExpenses());
                    refreshTable();
                    recurringList.setAll(manager.getBaseRecurringExpenses());
                    clearEditRecurringForm();
                    updateRecurringButton.setDisable(true);
                    errorLabel.setText("Recurring expense deleted successfully!");
                    errorLabel.getStyleClass().setAll("error-label", "success-message");
                } catch (Exception ex) {
                    errorLabel.setText("Error deleting recurring expense: " + ex.getMessage());
                    errorLabel.getStyleClass().setAll("error-label", "error-message");
                }
            }
        });

        // Add Category
        addCategoryButton.setOnAction(e -> {
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
                        errorLabel.setText("");
                        errorLabel.getStyleClass().setAll("error-label");
                    } catch (Exception ex) {
                        categories.remove(category);
                        errorLabel.setText("Error saving categories: " + ex.getMessage());
                        errorLabel.getStyleClass().setAll("error-label", "error-message");
                    }
                } else if (categories.contains(category)) {
                    errorLabel.setText("Category already exists");
                    errorLabel.getStyleClass().setAll("error-label", "error-message");
                } else {
                    errorLabel.setText("Category cannot be empty");
                    errorLabel.getStyleClass().setAll("error-label", "error-message");
                }
            });
        });

        // Remove Category
        removeCategoryButton.setOnAction(e -> {
            String selectedCategory = categoryCombo.getValue();
            if (selectedCategory == null) {
                errorLabel.setText("Please select a category to remove");
                errorLabel.getStyleClass().setAll("error-label", "error-message");
                return;
            }

            boolean isUsed = manager.getExpenses().stream()
                .anyMatch(expense -> expense.getCategory().equals(selectedCategory));
            if (isUsed) {
                errorLabel.setText("Cannot remove category as it is used in existing expenses");
                errorLabel.getStyleClass().setAll("error-label", "error-message");
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
                errorLabel.setText("Error saving categories: " + ex.getMessage());
                errorLabel.getStyleClass().setAll("error-label", "error-message");
            }
        });

        // Refresh UI components
        refreshTable();

        // Configure stage
        stage.setMinWidth(800);
        stage.setMinHeight(600);
        stage.setTitle("Expense Tracker");
        stage.setScene(scene);
        stage.show();
    }

    // Update income field based on selected period
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

    // Refresh all UI components
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
            errorLabel.setText("Error refreshing table: " + e.getMessage());
            errorLabel.getStyleClass().setAll("error-label", "error-message");
        }
    }

    // Update undo/redo button states
    private void updateUndoRedoButtons() {
        undoButton.setDisable(!manager.canUndo());
        redoButton.setDisable(!manager.canRedo());
    }

    // Clear the edit recurring expense form
    private void clearEditRecurringForm() {
        editRecurringAmountField.clear();
        editRecurringCategoryCombo.setValue(null);
        editRecurringDatePicker.setValue(null);
        editRecurringDescField.clear();
        editRecurringFreqCombo.setValue(null);
        editRecurringEndDatePicker.setValue(null);
    }

    // Update the list of years in the year selector
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

    // Update total expenses and money saved based on selected period and search filter
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

        double total = filteredExpenses.stream()
            .mapToDouble(Expense::getAmount)
            .sum();
        totalLabel.setText(String.format("Total Expenses for %s %d: %.2f",
                selectedMonth.getDisplayName(TextStyle.FULL, Locale.ENGLISH), selectedYear, total));

        double income = incomes.getOrDefault(selectedYearMonth, 0.0);
        double moneySaved = income - total;
        moneySavedLabel.setText(String.format("Money Saved: %.2f", Math.max(0, moneySaved)));

        Map<String, Double> categoryMap = filteredExpenses.stream()
            .collect(Collectors.groupingBy(
                Expense::getCategory,
                Collectors.summingDouble(Expense::getAmount))
            );

        categoryTotals.setAll(categoryMap.entrySet().stream()
            .map(entry -> new CategoryTotal(entry.getKey(), entry.getValue()))
            .sorted(Comparator.comparing(CategoryTotal::getCategory))
            .collect(Collectors.toList()));
    }

    // Update charts based on selected period
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

        // Update PieChart
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

        // Update BarChart
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

    // Utility method to create a styled TextField
    private TextField createStyledTextField(String prompt) {
        TextField field = new TextField();
        field.setPromptText(prompt);
        field.getStyleClass().add("text-field");
        return field;
    }

    // Utility method to create a styled ComboBox
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

    // Utility method to create a styled DatePicker
    private DatePicker createStyledDatePicker() {
        DatePicker picker = new DatePicker();
        picker.setPromptText("Select Date");
        picker.setValue(LocalDate.now());
        picker.getStyleClass().add("date-picker");
        return picker;
    }

    // Utility method to create a styled Button
    private Button createStyledButton(String text, String styleClass) {
        Button button = new Button(text);
        button.getStyleClass().add(styleClass);
        return button;
    }

    // Utility method to create a TableColumn with specified alignment
    private <S, T> TableColumn<S, T> createTableColumn(String title, String property, Pos alignment) {
        TableColumn<S, T> column = new TableColumn<>(title);
        column.setCellValueFactory(new PropertyValueFactory<>(property));
        column.setCellFactory(tc -> new TableCell<S, T>() {
            @Override
            protected void updateItem(T item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : item.toString());
                setAlignment(alignment);
            }
        });
        return column;
    }

    // Main method to launch the application
    public static void main(String[] args) {
        launch(args);
    }
}
