package com.wyn.expensetracker;

import javafx.animation.PauseTransition;
import javafx.application.Application;
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
import javafx.scene.image.Image;
import javafx.util.Duration;
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

    @Override
    public void start(Stage stage) {
        // Initialize errorLabel first
        errorLabel = new Label("");
        errorLabel.setStyle("-fx-text-fill: #FF5252; -fx-font-weight: bold; -fx-font-size: 14px;");
        errorLabel.setWrapText(true);

        try {
            stage.getIcons().add(new Image(getClass().getResourceAsStream("/expenseIcon.png")));
        } catch (Exception e) {
            System.err.println("Failed to load icon: " + e.getMessage());
            errorLabel.setText("Failed to load icon: " + e.getMessage());
        }

        // Initialize data
        try {
            categories = FXCollections.observableArrayList(storage.loadCategories());
        } catch (Exception e) {
            categories = FXCollections.observableArrayList("Food", "Transport", "Entertainment", "Utilities", "Other");
            errorLabel.setText("Failed to load categories: " + e.getMessage());
        }

        try {
            manager.getExpenses().addAll(storage.loadExpenses());
            System.out.println("Loaded " + manager.getExpenses().size() + " expenses");
            if (manager.getExpenses().isEmpty()) {
                manager.addExpense(new Expense(50.0, "Food", LocalDate.now(), "Groceries"));
                manager.addExpense(new Expense(30.0, "Transport", LocalDate.now(), "Bus fare"));
                errorLabel.setText("No expenses found. Added sample expenses.");
            }
        } catch (Exception e) {
            System.err.println("Error loading expenses: " + e.getMessage());
            errorLabel.setText("Failed to load expenses: " + e.getMessage());
        }

        try {
            incomes = storage.loadIncomes();
        } catch (Exception e) {
            incomes = new HashMap<>();
            errorLabel.setText("Failed to load incomes: " + e.getMessage());
        }

        // Main container
        BorderPane root = new BorderPane();
        root.setStyle("-fx-background-color: #2D2D2D;");

        // Left panel - Input Form
        VBox leftPanel = new VBox(15);
        leftPanel.setPadding(new Insets(20));
        leftPanel.setStyle("-fx-background-color: #3A3A3A; -fx-border-color: #4A4A4A; -fx-border-width: 0 1 0 0;");
        leftPanel.setMinWidth(350);
        leftPanel.setMaxWidth(350);
        leftPanel.setFillWidth(true);

        // Header
        Label headerLabel = new Label("Expense Tracker");
        headerLabel.setStyle("-fx-text-fill: #FFFFFF; -fx-font-size: 20px; -fx-font-weight: bold;");
        headerLabel.setWrapText(true);
        headerLabel.setMaxWidth(310);

        HBox headerBox = new HBox(headerLabel);
        headerBox.setPadding(new Insets(0, 0, 20, 0));
        headerBox.setAlignment(Pos.CENTER);

        // Form section
        VBox formBox = new VBox(15);
        formBox.setStyle("-fx-background-color: #424242; -fx-padding: 20; -fx-border-radius: 5; -fx-background-radius: 5;");

        Label formTitle = new Label("Add New Expense");
        formTitle.setStyle("-fx-text-fill: #FFFFFF; -fx-font-size: 18px; -fx-font-weight: bold;");

        // Amount field
        Label amountLabel = new Label("Amount:");
        amountLabel.setStyle("-fx-text-fill: #FFFFFF; -fx-font-size: 14px;");
        TextField amountField = createStyledTextField("e.g., 10.99");

        // Category section
        Label categoryLabel = new Label("Category:");
        categoryLabel.setStyle("-fx-text-fill: #FFFFFF; -fx-font-size: 14px;");
        ComboBox<String> categoryCombo = createStyledComboBox("Select or enter category", categories);

        // Category buttons
        HBox categoryButtons = new HBox(10);
        Button addCategoryButton = createStyledButton("Add Category", "#5C6BC0");
        Button removeCategoryButton = createStyledButton("Remove Category", "#E53935");
        categoryButtons.getChildren().addAll(addCategoryButton, removeCategoryButton);
        categoryButtons.setPadding(new Insets(5, 0, 15, 0));

        // Date picker
        Label dateLabel = new Label("Date:");
        dateLabel.setStyle("-fx-text-fill: #FFFFFF; -fx-font-size: 14px;");
        DatePicker datePicker = createStyledDatePicker();

        // Description
        Label descLabel = new Label("Description (optional):");
        descLabel.setStyle("-fx-text-fill: #FFFFFF; -fx-font-size: 14px;");
        TextField descriptionField = createStyledTextField("Enter description");

        // Action buttons
        HBox actionButtons = new HBox(10);
        Button addButton = createStyledButton("Add Expense", "#43A047");
        Button deleteButton = createStyledButton("Delete Selected", "#E53935");
        actionButtons.getChildren().addAll(addButton, deleteButton);

        formBox.getChildren().addAll(
            formTitle,
            amountLabel, amountField,
            categoryLabel, categoryCombo, categoryButtons,
            dateLabel, datePicker,
            descLabel, descriptionField,
            actionButtons
        );

        // Income section
        VBox incomeBox = new VBox(15);
        incomeBox.setStyle("-fx-background-color: #424242; -fx-padding: 20; -fx-border-radius: 5; -fx-background-radius: 5;");

        Label incomeTitle = new Label("Income & Savings");
        incomeTitle.setStyle("-fx-text-fill: #FFFFFF; -fx-font-size: 18px; -fx-font-weight: bold;");

        // Income input
        Label incomeLabel = new Label("Monthly Income:");
        incomeLabel.setStyle("-fx-text-fill: #FFFFFF; -fx-font-size: 14px;");
        incomeField = createStyledTextField("e.g., 5000.00");

        // Year/month selector
        Label periodLabel = new Label("Select Period:");
        periodLabel.setStyle("-fx-text-fill: #FFFFFF; -fx-font-size: 14px;");
        HBox selectorBox = new HBox(10);
        yearList = FXCollections.observableArrayList();
        yearCombo = createStyledComboBox("Year", yearList);
        monthList = FXCollections.observableArrayList(Month.values());
        monthCombo = createStyledComboBox("Month", monthList);
        selectorBox.getChildren().addAll(yearCombo, monthCombo);

        // Totals display
        VBox totalsBox = new VBox(10);
        totalLabel = new Label("Total Expenses: 0.00");
        totalLabel.setStyle("-fx-text-fill: #FFFFFF; -fx-font-size: 14px;");

        moneySavedLabel = new Label("Money Saved: 0.00");
        moneySavedLabel.setStyle("-fx-text-fill: #4CAF50; -fx-font-size: 14px; -fx-font-weight: bold;");

        totalsBox.getChildren().addAll(totalLabel, moneySavedLabel);

        incomeBox.getChildren().addAll(
            incomeTitle,
            incomeLabel, incomeField,
            periodLabel, selectorBox,
            new Separator(),
            totalsBox
        );

        // Search section
        VBox searchBox = new VBox(15);
        searchBox.setStyle("-fx-background-color: #424242; -fx-padding: 20; -fx-border-radius: 5; -fx-background-radius: 5;");

        Label searchTitle = new Label("Search Expenses");
        searchTitle.setStyle("-fx-text-fill: #FFFFFF; -fx-font-size: 18px; -fx-font-weight: bold;");

        searchField = createStyledTextField("Search by amount, category, date, or description");

        // Category totals table
        Label categoryTableTitle = new Label("Expenses by Category");
        categoryTableTitle.setStyle("-fx-text-fill: #FFFFFF; -fx-font-size: 16px;");

        categoryTable = new TableView<>();
        categoryTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        categoryTable.setPrefHeight(180);
        categoryTable.setStyle("-fx-background-color: transparent; -fx-padding: 5;");

        TableColumn<CategoryTotal, String> categoryColumn = new TableColumn<>("Category");
        categoryColumn.setCellValueFactory(new PropertyValueFactory<>("category"));
        categoryColumn.setStyle("-fx-text-fill: white; -fx-alignment: CENTER_LEFT; -fx-font-size: 14px;");
        categoryColumn.setPrefWidth(120);
        categoryColumn.setCellFactory(tc -> new TableCell<CategoryTotal, String>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setStyle("-fx-background-color: #3A3A3A;");
                } else {
                    setText(item);
                    setStyle("-fx-text-fill: black; -fx-font-size: 14px; -fx-background-color: #e0e0e0; -fx-alignment: CENTER_LEFT;");
                }
            }
        });

        TableColumn<CategoryTotal, Double> totalColumn = new TableColumn<>("Amount");
        totalColumn.setCellValueFactory(new PropertyValueFactory<>("total"));
        totalColumn.setStyle("-fx-text-fill: white; -fx-alignment: CENTER_RIGHT; -fx-font-size: 14px;");
        totalColumn.setPrefWidth(80);
        totalColumn.setCellFactory(tc -> new TableCell<CategoryTotal, Double>() {
            @Override
            protected void updateItem(Double item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setStyle("-fx-background-color: #3A3A3A;");
                } else {
                    setText(String.format("%.2f", item));
                    setStyle("-fx-text-fill: black; -fx-font-size: 14px; -fx-background-color: #e0e0e0; -fx-alignment: CENTER_RIGHT;");
                }
            }
        });

        @SuppressWarnings("unchecked")
        TableColumn<CategoryTotal, ?>[] categoryColumns = new TableColumn[]{categoryColumn, totalColumn};
        categoryTable.getColumns().addAll(categoryColumns);

        categoryTotals = FXCollections.observableArrayList();
        categoryTable.setItems(categoryTotals);

        searchBox.getChildren().addAll(
            searchTitle,
            searchField,
            new Separator(),
            categoryTableTitle,
            categoryTable
        );

        // Assemble left panel
        leftPanel.getChildren().addAll(
            headerBox,
            formBox,
            new Separator(),
            incomeBox,
            new Separator(),
            searchBox,
            errorLabel
        );

        // Right panel - Expense records and analytics
        VBox rightPanel = new VBox(15);
        rightPanel.setPadding(new Insets(20));
        rightPanel.setStyle("-fx-background-color: #2D2D2D;");
        rightPanel.setMinWidth(850);
        rightPanel.setFillWidth(true);

        // Expense records table
        Label tableTitle = new Label("Expense Records");
        tableTitle.setStyle("-fx-text-fill: #FFFFFF; -fx-font-size: 20px; -fx-font-weight: bold;");

        expenseTable = new TableView<>();
        expenseTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        expenseTable.setStyle("-fx-background-color: transparent; -fx-padding: 5;");
        expenseTable.setPrefHeight(400);

        // Create columns
        TableColumn<Expense, Double> amountColumn = createStyledTableColumn("Amount", "amount", Pos.CENTER_RIGHT);
        TableColumn<Expense, String> expenseCategoryColumn = createStyledTableColumn("Category", "category", Pos.CENTER_LEFT);
        TableColumn<Expense, LocalDate> dateColumn = createStyledTableColumn("Date", "date", Pos.CENTER);
        TableColumn<Expense, String> descriptionColumn = createStyledTableColumn("Description", "description", Pos.CENTER_LEFT);

        @SuppressWarnings("unchecked")
        TableColumn<Expense, ?>[] expenseColumns = new TableColumn[]{amountColumn, expenseCategoryColumn, dateColumn, descriptionColumn};
        expenseTable.getColumns().addAll(expenseColumns);

        // Set column widths
        amountColumn.setPrefWidth(100);
        expenseCategoryColumn.setPrefWidth(150);
        dateColumn.setPrefWidth(120);
        descriptionColumn.setPrefWidth(250);

        // Initialize expense list and filtered data
        expenseList = FXCollections.observableArrayList(manager.getExpenses());
        filteredData = new FilteredList<>(expenseList, p -> true);
        SortedList<Expense> sortedData = new SortedList<>(filteredData);
        sortedData.comparatorProperty().bind(expenseTable.comparatorProperty());
        expenseTable.setItems(sortedData);

        // Add row highlighting
        expenseTable.setRowFactory(tv -> new TableRow<Expense>() {
            @Override
            protected void updateItem(Expense item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setStyle("-fx-background-color: #2D2D2D;");
                } else {
                    if (isSelected()) {
                        setStyle("-fx-background-color: #5C6BC0;");
                    } else {
                        setStyle("-fx-background-color: #3A3A3A;");
                    }
                }
            }
        });

        // Export button
        Button exportButton = createStyledButton("Export to Excel", "#4CAF50");
        exportButton.setOnAction(e -> {
            try {
                new ExcelStorage().saveExpenses(manager.getExpenses());
                errorLabel.setText("Expenses exported to Excel successfully!");
                errorLabel.setStyle("-fx-text-fill: #4CAF50;");
            } catch (IOException ex) {
                errorLabel.setText("Failed to export to Excel: " + ex.getMessage());
                errorLabel.setStyle("-fx-text-fill: #FF5252;");
            }
        });

        // Analytics section
        VBox analyticsBox = new VBox(15);
        analyticsBox.setStyle("-fx-background-color: #424242; -fx-padding: 20; -fx-border-radius: 5; -fx-background-radius: 5;");

        Label analyticsTitle = new Label("Expense Analytics");
        analyticsTitle.setStyle("-fx-text-fill: #FFFFFF; -fx-font-size: 18px; -fx-font-weight: bold;");

        // Chart period selector
        HBox chartControls = new HBox(10);
        Label periodLabelChart = new Label("View by:");
        periodLabelChart.setStyle("-fx-text-fill: #FFFFFF; -fx-font-size: 14px;");
        chartPeriodCombo = new ComboBox<>(FXCollections.observableArrayList("All Time", "By Year", "By Month"));
        chartPeriodCombo.setValue("All Time");
        chartPeriodCombo.setStyle("-fx-background-color: #535353; -fx-text-fill: white;");
        chartControls.getChildren().addAll(periodLabelChart, chartPeriodCombo);

        // Create charts
        categoryChart = new PieChart();
        categoryChart.setTitle("Expenses by Category");
        categoryChart.setLegendVisible(true);
        categoryChart.setStyle("-fx-text-fill: white; -fx-background-color: #2D2D2D;");
        categoryChart.setPrefHeight(250);

        CategoryAxis xAxis = new CategoryAxis();
        NumberAxis yAxis = new NumberAxis();
        monthlyTrendChart = new BarChart<>(xAxis, yAxis);
        monthlyTrendChart.setTitle("Monthly Trend");
        monthlyTrendChart.setLegendVisible(false);
        monthlyTrendChart.setStyle("-fx-text-fill: white; -fx-background-color: #2D2D2D;");
        monthlyTrendChart.setPrefHeight(250);

        // Add charts to analytics box
        analyticsBox.getChildren().addAll(
            analyticsTitle,
            chartControls,
            categoryChart,
            monthlyTrendChart
        );

        // Add all components to right panel
        rightPanel.getChildren().addAll(
            tableTitle,
            expenseTable,
            new Separator(),
            exportButton,
            new Separator(),
            analyticsBox
        );

        // Create scroll panes
        ScrollPane leftScrollPane = new ScrollPane(leftPanel);
        leftScrollPane.setFitToWidth(true);
        leftScrollPane.setHbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        leftScrollPane.setStyle("-fx-background-color: transparent; -fx-background: transparent;");
        leftScrollPane.setPadding(new Insets(0));

        ScrollPane rightScrollPane = new ScrollPane(rightPanel);
        rightScrollPane.setFitToWidth(true);
        rightScrollPane.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        rightScrollPane.setStyle("-fx-background-color: transparent; -fx-background: transparent;");
        rightScrollPane.setPadding(new Insets(0));

        // Style the scroll bars
        leftScrollPane.lookupAll(".scroll-bar").forEach(node ->
            node.setStyle("-fx-base: #3A3A3A; -fx-background-color: #3A3A3A;")
        );
        rightScrollPane.lookupAll(".scroll-bar").forEach(node ->
            node.setStyle("-fx-base: #2D2D2D; -fx-background-color: #2D2D2D;")
        );

        // Set up the main layout
        root.setLeft(leftScrollPane);
        root.setCenter(rightScrollPane);
        root.setPrefSize(1200, 800);

        // Set up the scene
        Scene scene = new Scene(root, 1200, 800);
        try {
            scene.getStylesheets().add(getClass().getResource("/styles.css").toExternalForm());
        } catch (Exception e) {
            System.err.println("Failed to load styles.css: " + e.getMessage());
            errorLabel.setText("Failed to load stylesheet: " + e.getMessage());
        }

        // Event handlers
        PauseTransition searchDebounce = new PauseTransition(Duration.millis(300));
        searchField.textProperty().addListener((observable, oldValue, newValue) -> {
            searchDebounce.setOnFinished(e -> updateTotalExpenses());
            searchDebounce.playFromStart();
        });

        yearCombo.valueProperty().addListener((observable, oldValue, newValue) -> {
            updateTotalExpenses();
            updateIncomeField();
        });

        monthCombo.valueProperty().addListener((observable, oldValue, newValue) -> {
            updateTotalExpenses();
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
                    return;
                }
                incomes.put(selectedYearMonth, incomeValue);
                try {
                    storage.saveIncomes(incomes);
                    updateTotalExpenses();
                } catch (IOException ex) {
                    incomes.remove(selectedYearMonth);
                    errorLabel.setText("Error saving incomes: " + ex.getMessage());
                }
            } catch (NumberFormatException ex) {
                errorLabel.setText("Invalid income: Please enter a valid number (e.g., 5000.00)");
            }
        });

        chartPeriodCombo.valueProperty().addListener((obs, oldVal, newVal) -> {
            updateTotalExpenses();
        });

        // Keyboard shortcuts
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

        // Button actions
        addButton.setOnAction(e -> {
            try {
                double amount = Double.parseDouble(amountField.getText());
                if (amount <= 0) {
                    errorLabel.setText("Amount must be positive");
                    errorLabel.setStyle("-fx-text-fill: #FF5252;");
                    return;
                }
                String category = categoryCombo.getValue();
                if (category == null || category.trim().isEmpty()) {
                    category = categoryCombo.getEditor().getText().trim();
                    if (category.isEmpty()) {
                        errorLabel.setText("Category cannot be empty");
                        errorLabel.setStyle("-fx-text-fill: #FF5252;");
                        return;
                    }
                    if (!categories.contains(category)) {
                        categories.add(category);
                        try {
                            storage.saveCategories(categories);
                        } catch (Exception ex) {
                            categories.remove(category);
                            errorLabel.setText("Failed to save categories: " + ex.getMessage());
                            errorLabel.setStyle("-fx-text-fill: #FF5252;");
                            return;
                        }
                    }
                }
                LocalDate date = datePicker.getValue();
                String description = descriptionField.getText().trim();

                if (date == null) {
                    errorLabel.setText("Please select a date");
                    errorLabel.setStyle("-fx-text-fill: #FF5252;");
                    return;
                }

                Expense expense = new Expense(amount, category, date, description.isEmpty() ? "" : description);
                manager.addExpense(expense);
                try {
                    storage.saveExpenses(manager.getExpenses());
                } catch (Exception ex) {
                    manager.getExpenses().remove(expense);
                    errorLabel.setText("Failed to save expense: " + ex.getMessage());
                    errorLabel.setStyle("-fx-text-fill: #FF5252;");
                    return;
                }
                refreshTable();

                amountField.clear();
                categoryCombo.setValue(null);
                datePicker.setValue(LocalDate.now());
                descriptionField.clear();
                errorLabel.setText("Expense added successfully!");
                errorLabel.setStyle("-fx-text-fill: #4CAF50;");
            } catch (NumberFormatException ex) {
                errorLabel.setText("Invalid amount: Please enter a valid number (e.g., 10.99)");
                errorLabel.setStyle("-fx-text-fill: #FF5252;");
            } catch (Exception ex) {
                errorLabel.setText("Error: " + ex.getMessage());
                errorLabel.setStyle("-fx-text-fill: #FF5252;");
            }
        });

        deleteButton.setOnAction(e -> {
            Expense selectedExpense = expenseTable.getSelectionModel().getSelectedItem();
            if (selectedExpense == null) {
                errorLabel.setText("Please select an expense to delete");
                errorLabel.setStyle("-fx-text-fill: #FF5252;");
                return;
            }

            Alert confirmation = new Alert(Alert.AlertType.CONFIRMATION);
            confirmation.setTitle("Confirm Deletion");
            confirmation.setHeaderText(null);
            confirmation.setContentText("Are you sure you want to delete this expense?");

            Optional<ButtonType> result = confirmation.showAndWait();
            if (result.isPresent() && result.get() == ButtonType.OK) {
                manager.getExpenses().remove(selectedExpense);
                try {
                    storage.saveExpenses(manager.getExpenses());
                    refreshTable();
                    errorLabel.setText("Expense deleted successfully!");
                    errorLabel.setStyle("-fx-text-fill: #4CAF50;");
                } catch (Exception ex) {
                    manager.addExpense(selectedExpense);
                    errorLabel.setText("Error deleting expense: " + ex.getMessage());
                    errorLabel.setStyle("-fx-text-fill: #FF5252;");
                }
            }
        });

        addCategoryButton.setOnAction(e -> {
            TextInputDialog dialog = new TextInputDialog();
            dialog.setTitle("Add Category");
            dialog.setHeaderText("Enter a new category:");
            dialog.setContentText("Category:");

            dialog.showAndWait().ifPresent(category -> {
                category = category.trim();
                if (!category.isEmpty() && !categories.contains(category)) {
                    categories.add(category);
                    categoryCombo.setValue(category);
                    try {
                        storage.saveCategories(categories);
                        errorLabel.setText("");
                    } catch (Exception ex) {
                        categories.remove(category);
                        errorLabel.setText("Error saving categories: " + ex.getMessage());
                        errorLabel.setStyle("-fx-text-fill: #FF5252;");
                    }
                } else if (categories.contains(category)) {
                    errorLabel.setText("Category already exists");
                    errorLabel.setStyle("-fx-text-fill: #FF5252;");
                } else {
                    errorLabel.setText("Category cannot be empty");
                    errorLabel.setStyle("-fx-text-fill: #FF5252;");
                }
            });
        });

        removeCategoryButton.setOnAction(e -> {
            String selectedCategory = categoryCombo.getValue();
            if (selectedCategory == null) {
                errorLabel.setText("Please select a category to remove");
                errorLabel.setStyle("-fx-text-fill: #FF5252;");
                return;
            }

            boolean isUsed = manager.getExpenses().stream()
                .anyMatch(expense -> expense.getCategory().equals(selectedCategory));
            if (isUsed) {
                errorLabel.setText("Cannot remove category as it is used in existing expenses");
                errorLabel.setStyle("-fx-text-fill: #FF5252;");
                return;
            }

            categories.remove(selectedCategory);
            if (categoryCombo.getItems().isEmpty()) {
                categoryCombo.setValue(null);
            } else if (categoryCombo.getSelectionModel().getSelectedIndex() >= categoryCombo.getItems().size()) {
                categoryCombo.getSelectionModel().selectLast();
            }

            try {
                storage.saveCategories(categories);
                errorLabel.setText("");
            } catch (Exception ex) {
                categories.add(selectedCategory);
                errorLabel.setText("Error saving categories: " + ex.getMessage());
                errorLabel.setStyle("-fx-text-fill: #FF5252;");
            }
        });

        // Initial refresh
        refreshTable();

        stage.setTitle("Expense Tracker");
        stage.setScene(scene);
        stage.show();
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
            updateYearList();
            updateTotalExpenses();
            updateIncomeField();
        } catch (Exception e) {
            errorLabel.setText("Error refreshing table: " + e.getMessage());
            errorLabel.setStyle("-fx-text-fill: #FF5252;");
        }
    }

    private void updateYearList() {
        Set<Integer> years = new TreeSet<>();
        for (Expense expense : expenseList) {
            years.add(expense.getDate().getYear());
        }

        Integer selectedYear = yearCombo.getValue();
        Month selectedMonth = monthCombo.getValue();
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

        if (yearCombo.getValue() != null && selectedMonth == null) {
            monthCombo.setValue(Month.of(LocalDate.now().getMonthValue()));
        }
    }

    private void updateTotalExpenses() {
        Integer selectedYear = yearCombo.getValue();
        Month selectedMonth = monthCombo.getValue();
        String chartPeriod = chartPeriodCombo.getValue();

        if (selectedYear == null || selectedMonth == null) {
            totalLabel.setText("Total Expenses: 0.00");
            moneySavedLabel.setText("Money Saved: 0.00");
            categoryTotals.clear();
            categoryChart.setData(FXCollections.emptyObservableList());
            monthlyTrendChart.getData().clear();
            return;
        }

        YearMonth selectedYearMonth = YearMonth.of(selectedYear, selectedMonth);

        // Compute filtered expenses
        List<Expense> filteredExpenses = expenseList.stream()
            .filter(expense -> {
                switch (chartPeriod) {
                    case "By Year":
                        return expense.getDate().getYear() == selectedYear;
                    case "By Month":
                        return YearMonth.from(expense.getDate()).equals(selectedYearMonth);
                    default:
                        return true;
                }
            })
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

        // Update expense table
        filteredData.setPredicate(expense -> filteredExpenses.contains(expense));

        // Calculate total
        double total = filteredExpenses.stream().mapToDouble(Expense::getAmount).sum();
        totalLabel.setText(String.format("Total Expenses for %s %d: %.2f",
            selectedMonth.getDisplayName(TextStyle.FULL, Locale.ENGLISH), selectedYear, total));

        double income = incomes.getOrDefault(selectedYearMonth, 0.0);
        double moneySaved = income - total;
        moneySavedLabel.setText(String.format("Money Saved: %.2f", Math.max(0, moneySaved)));

        // Update category totals table
        Map<String, Double> categoryMap = filteredExpenses.stream()
            .collect(Collectors.groupingBy(
                Expense::getCategory,
                Collectors.summingDouble(Expense::getAmount)
            ));

        categoryTotals.setAll(categoryMap.entrySet().stream()
            .map(entry -> new CategoryTotal(entry.getKey(), entry.getValue()))
            .sorted(Comparator.comparing(CategoryTotal::getCategory))
            .collect(Collectors.toList()));

        // Update pie chart data with custom colors
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
                entry.getValue()
            );
            data.nodeProperty().addListener((obs, oldNode, newNode) -> {
                if (newNode != null) {
                    newNode.setStyle("-fx-pie-color: " + colors[colorIndex] + ";");
                }
            });
            pieChartData.add(data);
        }
        categoryChart.setData(pieChartData);

        // Update monthly trend chart
        monthlyTrendChart.getData().clear();
        XYChart.Series<String, Number> series = new XYChart.Series<>();
        Map<YearMonth, Double> monthlyTotals = expenseList.stream()
            .collect(Collectors.groupingBy(
                expense -> YearMonth.from(expense.getDate()),
                Collectors.summingDouble(Expense::getAmount))
            );

        monthlyTotals.entrySet().stream()
            .sorted(Map.Entry.comparingByKey())
            .forEach(entry -> {
                XYChart.Data<String, Number> data = new XYChart.Data<>(
                    entry.getKey().getMonth().toString() + " " + entry.getKey().getYear(),
                    entry.getValue()
                );
                data.nodeProperty().addListener((obs, oldNode, newNode) -> {
                    if (newNode != null) {
                        newNode.setStyle("-fx-bar-fill: #4CAF50;");
                    }
                });
                series.getData().add(data);
            });

        monthlyTrendChart.getData().add(series);
    }

    // Helper methods for creating styled controls
    private TextField createStyledTextField(String prompt) {
        TextField field = new TextField();
        field.setPromptText(prompt);
        field.setStyle("-fx-background-color: #535353; -fx-text-fill: white; -fx-prompt-text-fill: #B0B0B0; -fx-font-size: 14px; -fx-padding: 5;");
        return field;
    }

    private <T> ComboBox<T> createStyledComboBox(String prompt, ObservableList<T> items) {
        ComboBox<T> combo = new ComboBox<>(items);
        combo.setPromptText(prompt);
        combo.setStyle("-fx-background-color: #535353; -fx-text-fill: white; -fx-prompt-text-fill: #B0B0B0; -fx-font-size: 14px;");
        combo.setCellFactory(lv -> new ListCell<T>() {
            @Override
            protected void updateItem(T item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty ? null : item.toString());
                setStyle("-fx-text-fill: white; -fx-background-color: #535353; -fx-font-size: 14px;");
            }
        });
        return combo;
    }

    private DatePicker createStyledDatePicker() {
        DatePicker picker = new DatePicker();
        picker.setPromptText("Select Date");
        picker.setValue(LocalDate.now());
        picker.setStyle("-fx-background-color: #535353; -fx-text-fill: white; -fx-font-size: 14px;");
        picker.getEditor().setStyle("-fx-text-fill: white; -fx-font-size: 14px;");
        return picker;
    }

    private Button createStyledButton(String text, String color) {
        Button button = new Button(text);
        button.setStyle("-fx-background-color: " + color + "; -fx-text-fill: white; -fx-font-weight: bold; -fx-font-size: 14px; -fx-padding: 5 10;");
        return button;
    }

    private <S, T> TableColumn<S, T> createStyledTableColumn(String title, String property, Pos alignment) {
        TableColumn<S, T> column = new TableColumn<>(title);
        column.setCellValueFactory(new PropertyValueFactory<>(property));
        column.setStyle("-fx-text-fill: white; -fx-font-size: 14px;");

        column.setCellFactory(tc -> new TableCell<S, T>() {
            @Override
            protected void updateItem(T item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setStyle("-fx-background-color: #3A3A3A;");
                } else {
                    setText(item.toString());
                    int row = getIndex();
                    String color = row % 2 == 0 ? "#e0e0e0" : "#d0d0d0";
                    setStyle("-fx-text-fill: black; -fx-font-size: 14px; -fx-background-color: " + color + ";");
                    setAlignment(alignment);
                }
            }
        });

        return column;
    }

    public static void main(String[] args) {
        launch(args);
    }
}