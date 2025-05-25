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
import javafx.scene.chart.*;

public class ExpenseTrackerApp extends Application {
    private ExpenseManager manager = new ExpenseManager();
    private ExcelStorage storage = new ExcelStorage();
    private TableView<Expense> expenseTable = new TableView<>();
    private ObservableList<String> categories = FXCollections.observableArrayList();
    private Label errorLabel = new Label();
    private FilteredList<Expense> filteredData;
    private TextField searchField = new TextField();
    private ObservableList<Expense> expenseList = FXCollections.observableArrayList();
    private Label totalLabel = new Label();
    private ComboBox<Integer> yearCombo = new ComboBox<>();
    private ComboBox<Month> monthCombo = new ComboBox<>();
    private ObservableList<Integer> yearList = FXCollections.observableArrayList();
    private ObservableList<Month> monthList = FXCollections.observableArrayList(Month.values());
    private TableView<CategoryTotal> categoryTable = new TableView<>();
    private ObservableList<CategoryTotal> categoryTotals = FXCollections.observableArrayList();
    private TextField incomeField = new TextField();
    private Label moneySavedLabel = new Label();
    private Map<YearMonth, Double> incomes = new HashMap<>();
    private PieChart categoryChart = new PieChart();
    private BarChart<String, Number> monthlyTrendChart;
    private ComboBox<String> chartPeriodCombo = new ComboBox<>();

    @Override
    public void start(Stage stage) {
        // Add shutdown hook for proper cleanup
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                List<Expense> expensesToSave = manager.getExpenses();
                if (expensesToSave.isEmpty()) {
                    System.out.println("Skipping shutdown save: No expenses to save");
                    return;
                }
                System.out.println("Shutting down - saving " + expensesToSave.size() + " expenses...");
                storage.saveExpenses(expensesToSave);
            } catch (IOException e) {
                System.err.println("Failed to save during shutdown: " + e.getMessage());
            }
        }));

        try {
            stage.getIcons().add(new Image(getClass().getResourceAsStream("/expenseIcon.png")));
        } catch (Exception e) {
            showError("Failed to load icon: " + e.getMessage());
        }

        // Initialize UI
        BorderPane root = new BorderPane();
        root.getStyleClass().add("root-pane");

        // Create left and right panels
        VBox leftPanel = createLeftPanel();
        VBox rightPanel = createRightPanel();

        // Create scroll panes
        ScrollPane leftScrollPane = new ScrollPane(leftPanel);
        leftScrollPane.setFitToWidth(true);
        leftScrollPane.getStyleClass().add("scroll-pane");

        ScrollPane rightScrollPane = new ScrollPane(rightPanel);
        rightScrollPane.setFitToWidth(true);
        rightScrollPane.getStyleClass().add("scroll-pane");

        // Set up split pane
        SplitPane splitPane = new SplitPane(leftScrollPane, rightScrollPane);
        splitPane.setDividerPositions(0.4);
        root.setCenter(splitPane);

        // Set up scene
        Scene scene = new Scene(root, 1200, 800);
        try {
            scene.getStylesheets().add(getClass().getResource("/styles.css").toExternalForm());
        } catch (Exception e) {
            showError("Failed to load stylesheet: " + e.getMessage());
        }

        // Load data
        try {
            // Load categories first
            categories.setAll(storage.loadCategories());
            if (categories.isEmpty()) {
                categories.addAll("Food", "Transport", "Utilities", "Entertainment", "Other");
            }

            // Then load expenses
            List<Expense> loadedExpenses = storage.loadExpenses();
            System.out.println("Loaded " + loadedExpenses.size() + " expenses from storage");
            
            manager.getExpenses().clear();
            manager.getExpenses().addAll(loadedExpenses);
            manager.generateRecurringExpenses(LocalDate.now());
            
            // Initialize the observable list
            expenseList.setAll(manager.getExpenses());
            System.out.println("Manager now has " + manager.getExpenses().size() + " expenses");
            
        } catch (IOException e) {
            showError("Failed to load data: " + e.getMessage());
        }

        // Initialize filtered data
        filteredData = new FilteredList<>(expenseList, p -> true);
        SortedList<Expense> sortedData = new SortedList<>(filteredData);
        sortedData.comparatorProperty().bind(expenseTable.comparatorProperty());
        expenseTable.setItems(sortedData);

        // Set up event handlers
        setupEventHandlers();

        // Ensure table is refreshed with loaded data
        refreshTable();

        stage.setTitle("Expense Tracker");
        stage.setScene(scene);
        stage.setMinWidth(800);
        stage.setMinHeight(600);
        stage.show();
    }

    private VBox createLeftPanel() {
        VBox leftPanel = new VBox(15);
        leftPanel.setPadding(new Insets(20));
        leftPanel.getStyleClass().add("left-panel");

        // Header
        Label headerLabel = new Label("Expense Tracker");
        headerLabel.getStyleClass().add("header-label");

        // Form section
        VBox formBox = createFormSection();

        // Income section
        VBox incomeBox = createIncomeSection();

        // Search section
        VBox searchBox = createSearchSection();

        leftPanel.getChildren().addAll(
            headerLabel,
            formBox,
            new Separator(),
            incomeBox,
            new Separator(),
            searchBox,
            errorLabel
        );
        errorLabel.getStyleClass().add("error-label");

        return leftPanel;
    }

    private VBox createFormSection() {
        VBox formBox = new VBox(15);
        formBox.getStyleClass().add("panel-box");

        Label formTitle = new Label("Add New Expense");
        formTitle.getStyleClass().add("section-title");

        // Amount field
        TextField amountField = createStyledTextField("e.g., 10.99");
        Label amountLabel = createFormLabel("Amount:");

        // Category
        ComboBox<String> categoryCombo = createStyledComboBox("Select or enter category", categories);
        Label categoryLabel = createFormLabel("Category:");

        // Category buttons
        HBox categoryButtons = new HBox(10);
        Button addCategoryButton = createStyledButton("Add Category", "primary-button");
        Button removeCategoryButton = createStyledButton("Remove Category", "danger-button");
        categoryButtons.getChildren().addAll(addCategoryButton, removeCategoryButton);

        // Date picker
        DatePicker datePicker = createStyledDatePicker();
        Label dateLabel = createFormLabel("Date:");

        // Description
        TextField descriptionField = createStyledTextField("Enter description");
        Label descLabel = createFormLabel("Description (optional):");

        // Recurring expense fields
        CheckBox recurringCheckBox = new CheckBox("Is Recurring?");
        recurringCheckBox.getStyleClass().add("check-box");
        Label recurringLabel = createFormLabel("Recurring:");

        ComboBox<RecurrenceType> frequencyCombo = new ComboBox<>(FXCollections.observableArrayList(RecurrenceType.values()));
        frequencyCombo.setPromptText("Select frequency");
        frequencyCombo.setDisable(true);
        Label frequencyLabel = createFormLabel("Frequency:");

        DatePicker endDatePicker = new DatePicker();
        endDatePicker.setPromptText("Select end date");
        endDatePicker.setDisable(true);
        Label endDateLabel = createFormLabel("End Date (optional):");

        // Enable/disable recurring fields
        recurringCheckBox.selectedProperty().addListener((obs, oldVal, newVal) -> {
            frequencyCombo.setDisable(!newVal);
            endDatePicker.setDisable(!newVal);
        });

        // Action buttons
        HBox actionButtons = new HBox(10);
        Button addButton = createStyledButton("Add Expense", "success-button");
        Button deleteButton = createStyledButton("Delete Selected", "danger-button");
        actionButtons.getChildren().addAll(addButton, deleteButton);

        // Add all components to form box
        formBox.getChildren().addAll(
            formTitle,
            amountLabel, amountField,
            categoryLabel, categoryCombo, categoryButtons,
            dateLabel, datePicker,
            descLabel, descriptionField,
            recurringLabel, recurringCheckBox,
            frequencyLabel, frequencyCombo,
            endDateLabel, endDatePicker,
            actionButtons
        );

        // Add button action
        addButton.setOnAction(event -> {
            Expense expense = null;
            try {
                double amount = Double.parseDouble(amountField.getText());
                if (amount <= 0) {
                    showError("Amount must be positive");
                    return;
                }

                String category = categoryCombo.getValue() != null ? 
                    categoryCombo.getValue() : categoryCombo.getEditor().getText().trim();
                if (category.isEmpty()) {
                    showError("Category cannot be empty");
                    return;
                }

                LocalDate date = datePicker.getValue();
                if (date == null) {
                    showError("Please select a date");
                    return;
                }

                String description = descriptionField.getText().trim();

                if (recurringCheckBox.isSelected()) {
                    RecurrenceType frequency = frequencyCombo.getValue();
                    if (frequency == null) {
                        showError("Please select a recurrence frequency");
                        return;
                    }
                    LocalDate endDate = endDatePicker.getValue();
                    expense = new RecurringExpense(amount, category, date, description, frequency, endDate);
                } else {
                    expense = new Expense(amount, category, date, description);
                }

                // Add category if new
                if (!categories.contains(category)) {
                    categories.add(category);
                }

                manager.addExpense(expense); // Saving is handled in ExpenseManager
                refreshTable();
                showSuccess("Expense added successfully!");
            } catch (NumberFormatException ex) {
                showError("Invalid amount: Please enter a valid number");
            } catch (IllegalArgumentException ex) {
                showError(ex.getMessage());
            } catch (Exception e) { // Catch any unexpected exceptions
                showError("Failed to add expense: " + e.getMessage());
                if (expense != null) {
                    manager.getExpenses().remove(expense);
                }
            }
        });

        // Delete button action
        deleteButton.setOnAction(event -> {
            Expense selected = expenseTable.getSelectionModel().getSelectedItem();
            if (selected == null) {
                showError("Please select an expense to delete");
                return;
            }

            Alert confirmation = new Alert(Alert.AlertType.CONFIRMATION);
            confirmation.setTitle("Confirm Deletion");
            confirmation.setHeaderText(null);
            confirmation.setContentText("Are you sure you want to delete this expense?");
            
            if (confirmation.showAndWait().orElse(ButtonType.CANCEL) == ButtonType.OK) {
                manager.getExpenses().remove(selected);
                try {
                    storage.saveExpenses(manager.getExpenses());
                    refreshTable();
                    showSuccess("Expense deleted successfully!");
                } catch (IOException e) {
                    showError("Failed to save changes: " + e.getMessage());
                    manager.getExpenses().add(selected); // Revert if save fails
                }
            }
        });

        // Add category button action
        addCategoryButton.setOnAction(event -> {
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
                    } catch (IOException e) {
                        showError("Failed to save categories: " + e.getMessage());
                        categories.remove(category);
                    }
                } else if (category.isEmpty()) {
                    showError("Category cannot be empty");
                } else {
                    showError("Category already exists");
                }
            });
        });

        // Remove category button action
        removeCategoryButton.setOnAction(event -> {
            String selected = categoryCombo.getValue();
            if (selected == null) {
                showError("Please select a category to remove");
                return;
            }

            boolean isUsed = manager.getExpenses().stream()
                .anyMatch(exp -> exp.getCategory().equals(selected));
            
            if (isUsed) {
                showError("Cannot remove category used by existing expenses");
                return;
            }

            categories.remove(selected);
            try {
                storage.saveCategories(categories);
            } catch (IOException e) {
                showError("Failed to save categories: " + e.getMessage());
                categories.add(selected);
            }
        });

        return formBox;
    }

    private VBox createIncomeSection() {
        VBox incomeBox = new VBox(15);
        incomeBox.getStyleClass().add("panel-box");

        Label incomeTitle = new Label("Income & Savings");
        incomeTitle.getStyleClass().add("section-title");

        // Income input
        Label incomeLabel = createFormLabel("Monthly Income:");
        incomeField = createStyledTextField("e.g., 5000.00");

        // Totals display
        VBox totalsBox = new VBox(10);
        totalLabel.getStyleClass().add("total-label");
        moneySavedLabel.getStyleClass().add("saved-label");
        totalsBox.getChildren().addAll(totalLabel, moneySavedLabel);

        incomeBox.getChildren().addAll(
            incomeTitle,
            incomeLabel, incomeField,
            new Separator(),
            totalsBox
        );

        return incomeBox;
    }

    private VBox createSearchSection() {
        VBox searchBox = new VBox(15);
        searchBox.getStyleClass().add("panel-box");

        Label searchTitle = new Label("Search Expenses");
        searchTitle.getStyleClass().add("section-title");

        searchField = createStyledTextField("Search by amount, category, date, or description");

        searchBox.getChildren().addAll(
            searchTitle,
            searchField
        );

        return searchBox;
    }

    @SuppressWarnings("unchecked")
    private VBox createRightPanel() {
        VBox rightPanel = new VBox(15);
        rightPanel.setPadding(new Insets(20));
        rightPanel.getStyleClass().add("right-panel");

        // Period selector
        Label periodLabel = createFormLabel("Select Period:");
        HBox selectorBox = new HBox(10);
        yearCombo = createStyledComboBox("Year", yearList);
        monthCombo = createStyledComboBox("Month", monthList);
        selectorBox.getChildren().addAll(periodLabel, yearCombo, monthCombo);

        // Expense table
        expenseTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        expenseTable.getStyleClass().add("table-view");
        expenseTable.setPrefHeight(400);

        TableColumn<Expense, Double> amountColumn = new TableColumn<>("Amount");
        amountColumn.setCellValueFactory(new PropertyValueFactory<>("amount"));
        amountColumn.setCellFactory(tc -> new TableCell<>() {
            @Override
            protected void updateItem(Double item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty ? null : String.format("$%.2f", item));
                setAlignment(Pos.CENTER_RIGHT);
            }
        });

        TableColumn<Expense, String> categoryColumn = new TableColumn<>("Category");
        categoryColumn.setCellValueFactory(new PropertyValueFactory<>("category"));

        TableColumn<Expense, LocalDate> dateColumn = new TableColumn<>("Date");
        dateColumn.setCellValueFactory(new PropertyValueFactory<>("date"));

        TableColumn<Expense, String> descriptionColumn = new TableColumn<>("Description");
        descriptionColumn.setCellValueFactory(new PropertyValueFactory<>("description"));

        expenseTable.getColumns().setAll(amountColumn, categoryColumn, dateColumn, descriptionColumn);

        // Initialize filtered data
        expenseList = FXCollections.observableArrayList(manager.getExpenses());
        filteredData = new FilteredList<>(expenseList, p -> true);
        SortedList<Expense> sortedData = new SortedList<>(filteredData);
        sortedData.comparatorProperty().bind(expenseTable.comparatorProperty());
        expenseTable.setItems(sortedData);

        // Export button
        Button exportButton = createStyledButton("Export to Excel", "success-button");
        exportButton.setOnAction(event -> exportToExcel());

        // Analytics section
        VBox analyticsBox = new VBox(15);
        analyticsBox.getStyleClass().add("panel-box");

        Label analyticsTitle = new Label("Expense Analytics");
        analyticsTitle.getStyleClass().add("section-title");

        // Chart period selector
        HBox chartControls = new HBox(10);
        Label periodLabelChart = createFormLabel("View by:");
        chartPeriodCombo.setItems(FXCollections.observableArrayList("All Time", "By Year", "By Month"));
        chartPeriodCombo.setValue("All Time");
        chartControls.getChildren().addAll(periodLabelChart, chartPeriodCombo);

        // Create charts
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

        // Category totals table
        Label categoryTableTitle = new Label("Expenses by Category");
        categoryTableTitle.getStyleClass().add("table-title");

        categoryTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        categoryTable.setPrefHeight(180);
        categoryTable.getStyleClass().add("table-view");

        TableColumn<CategoryTotal, String> categoryTotalColumn = new TableColumn<>("Category");
        categoryTotalColumn.setCellValueFactory(new PropertyValueFactory<>("category"));

        TableColumn<CategoryTotal, Double> totalColumn = new TableColumn<>("Amount");
        totalColumn.setCellValueFactory(new PropertyValueFactory<>("total"));
        totalColumn.setCellFactory(tc -> new TableCell<>() {
            @Override
            protected void updateItem(Double item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty ? null : String.format("$%.2f", item));
            }
        });

        categoryTable.getColumns().setAll(categoryTotalColumn, totalColumn);
        categoryTable.setItems(categoryTotals);

        analyticsBox.getChildren().addAll(
            analyticsTitle,
            chartControls,
            categoryChart,
            monthlyTrendChart,
            new Separator(),
            categoryTableTitle,
            categoryTable
        );

        Label recordsLabel = new Label("Expense Records");
        recordsLabel.getStyleClass().add("section-title");

        rightPanel.getChildren().addAll(
            selectorBox,
            recordsLabel,
            expenseTable,
            new Separator(),
            exportButton,
            new Separator(),
            analyticsBox
        );

        return rightPanel;
    }

    private void loadInitialData() {
        // Already handled in start method
    }

    private void setupEventHandlers() {
        // Search debounce
        PauseTransition searchDebounce = new PauseTransition(Duration.millis(300));
        searchField.textProperty().addListener((observable, oldValue, newValue) -> {
            searchDebounce.setOnFinished(e -> updateTotalExpenses());
            searchDebounce.playFromStart();
        });

        // Year/month change listeners
        yearCombo.valueProperty().addListener((obs, oldVal, newVal) -> {
            updateTotalExpenses();
            updateIncomeField();
        });

        monthCombo.valueProperty().addListener((obs, oldVal, newVal) -> {
            updateTotalExpenses();
            updateIncomeField();
        });

        // Income field listener
        incomeField.textProperty().addListener((obs, oldVal, newVal) -> {
            Integer year = yearCombo.getValue();
            Month month = monthCombo.getValue();
            if (year == null || month == null) return;
            
            YearMonth ym = YearMonth.of(year, month);
            try {
                double income = newVal.isEmpty() ? 0.0 : Double.parseDouble(newVal);
                if (income < 0) {
                    showError("Income cannot be negative");
                    return;
                }
                incomes.put(ym, income);
                updateTotalExpenses();
            } catch (NumberFormatException e) {
                showError("Invalid income: Please enter a valid number");
            }
        });

        // Chart period change listener
        chartPeriodCombo.valueProperty().addListener((obs, oldVal, newVal) -> {
            updateTotalExpenses();
        });
    }

    private void refreshTable() {
        try {
            // Reload expenses from storage
            List<Expense> loadedExpenses = storage.loadExpenses();
            if (!loadedExpenses.isEmpty()) { // Only clear if we loaded valid data
                manager.getExpenses().clear();
                manager.getExpenses().addAll(loadedExpenses);
            } else {
                System.out.println("No expenses loaded from file, keeping existing data");
            }
            
            // Generate recurring expenses and save
            manager.generateRecurringExpenses(LocalDate.now());
            storage.saveExpenses(manager.getExpenses()); // Save after generating recurring expenses
            
            // Update the observable list
            expenseList.setAll(manager.getExpenses());
            
            // Force refresh of the filtered data
            filteredData.setPredicate(p -> true);
            
            updateYearList();
            updateTotalExpenses();
            updateIncomeField();
            
            System.out.println("Refreshed table with " + manager.getExpenses().size() + " expenses");
        } catch (IOException e) {
            showError("Failed to refresh data: " + e.getMessage());
        }
    }

    private void updateYearList() {
        Set<Integer> years = manager.getExpenses().stream()
            .map(e -> e.getDate().getYear())
            .collect(Collectors.toSet());
        
        yearList.setAll(years.stream().sorted().collect(Collectors.toList()));
        
        if (!yearList.isEmpty() && yearCombo.getValue() == null) {
            yearCombo.setValue(Collections.max(yearList));
            monthCombo.setValue(Month.of(LocalDate.now().getMonthValue()));
        }
    }

    private void updateIncomeField() {
        Integer year = yearCombo.getValue();
        Month month = monthCombo.getValue();
        if (year == null || month == null) {
            incomeField.setText("");
            return;
        }
        
        YearMonth ym = YearMonth.of(year, month);
        Double income = incomes.get(ym);
        incomeField.setText(income != null ? String.format("%.2f", income) : "");
    }

    private void updateTotalExpenses() {
        Integer year = yearCombo.getValue();
        Month month = monthCombo.getValue();
        String chartPeriod = chartPeriodCombo.getValue();
        String searchText = searchField.getText().toLowerCase();

        if (year == null || month == null) {
            totalLabel.setText("Total Expenses: $0.00");
            moneySavedLabel.setText("Money Saved: $0.00");
            categoryTotals.clear();
            categoryChart.getData().clear();
            monthlyTrendChart.getData().clear();
            return;
        }

        YearMonth selectedYm = YearMonth.of(year, month);

        // Filter expenses
        List<Expense> filteredExpenses = manager.getExpenses().stream()
            .filter(expense -> {
                // Filter by period
                switch (chartPeriod) {
                    case "By Year":
                        return expense.getDate().getYear() == year;
                    case "By Month":
                        return YearMonth.from(expense.getDate()).equals(selectedYm);
                    default: // "All Time"
                        return true;
                }
            })
            .filter(expense -> {
                // Filter by search text
                if (searchText.isEmpty()) return true;
                return String.valueOf(expense.getAmount()).contains(searchText) ||
                       expense.getCategory().toLowerCase().contains(searchText) ||
                       expense.getDate().toString().contains(searchText) ||
                       (expense.getDescription() != null && 
                        expense.getDescription().toLowerCase().contains(searchText));
            })
            .collect(Collectors.toList());

        // Update filtered data
        filteredData.setPredicate(expense -> filteredExpenses.contains(expense));

        // Calculate totals
        double total = filteredExpenses.stream()
            .mapToDouble(Expense::getAmount)
            .sum();

        double income = incomes.getOrDefault(selectedYm, 0.0);
        double saved = income - total;

        totalLabel.setText(String.format("Total Expenses for %s %d: $%.2f",
            month.getDisplayName(TextStyle.FULL, Locale.ENGLISH), year, total));
        moneySavedLabel.setText(String.format("Money Saved: $%.2f", Math.max(0, saved)));

        // Update category totals
        Map<String, Double> categoryMap = filteredExpenses.stream()
            .collect(Collectors.groupingBy(
                Expense::getCategory,
                Collectors.summingDouble(Expense::getAmount)
            ));

        categoryTotals.setAll(
            categoryMap.entrySet().stream()
                .map(entry -> new CategoryTotal(entry.getKey(), entry.getValue()))
                .sorted(Comparator.comparing(CategoryTotal::getCategory))
                .collect(Collectors.toList())
        );

        // Update pie chart
        ObservableList<PieChart.Data> pieData = FXCollections.observableArrayList();
        String[] colors = {"#FF6F61", "#6B5B95", "#88B04B", "#F7B731", "#4ECDC4"};
        
        List<Map.Entry<String, Double>> sortedCategories = categoryMap.entrySet().stream()
            .sorted(Map.Entry.comparingByValue(Comparator.reverseOrder()))
            .collect(Collectors.toList());

        for (int i = 0; i < sortedCategories.size(); i++) {
            Map.Entry<String, Double> entry = sortedCategories.get(i);
            PieChart.Data data = new PieChart.Data(
                entry.getKey() + " ($" + String.format("%.2f", entry.getValue()) + ")",
                entry.getValue()
            );
            
            final int colorIndex = i % colors.length;
            data.nodeProperty().addListener((obs, oldNode, newNode) -> {
                if (newNode != null) {
                    newNode.setStyle("-fx-pie-color: " + colors[colorIndex] + ";");
                }
            });
            pieData.add(data);
        }
        categoryChart.setData(pieData);

        // Update monthly trend chart
        monthlyTrendChart.getData().clear();
        XYChart.Series<String, Number> series = new XYChart.Series<>();
        
        Map<YearMonth, Double> monthlyTotals = manager.getExpenses().stream()
            .collect(Collectors.groupingBy(
                expense -> YearMonth.from(expense.getDate()),
                Collectors.summingDouble(Expense::getAmount)
            ));
        
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

    private void exportToExcel() {
        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("Save Expenses");
        fileChooser.getExtensionFilters().add(
            new FileChooser.ExtensionFilter("Excel Files", "*.xlsx")
        );
        fileChooser.setInitialFileName("expenses.xlsx");
        
        File file = fileChooser.showSaveDialog(null);
        if (file != null) {
            try {
                storage.saveExpenses(manager.getExpenses(), file.getAbsolutePath());
                showSuccess("Expenses exported successfully to: " + file.getAbsolutePath());
            } catch (IOException e) {
                showError("Failed to export expenses: " + e.getMessage());
            }
        }
    }

    // Helper methods
    private Label createFormLabel(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("form-label");
        return label;
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
                setText(empty ? null : item.toString());
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

    private void showError(String message) {
        errorLabel.setText(message);
        errorLabel.getStyleClass().setAll("error-label", "error-message");
    }

    private void showSuccess(String message) {
        errorLabel.setText(message);
        errorLabel.getStyleClass().setAll("error-label", "success-message");
    }

    public static void main(String[] args) {
        launch(args);
    }
}