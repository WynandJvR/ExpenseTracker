package com.wyn.expensetracker;

import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import java.io.IOException;
import java.time.Month;
import java.time.YearMonth;
import java.time.format.TextStyle;
import java.util.*;
import java.util.stream.Collectors;

public class DashboardController {

    // --- Dashboard cards ---
    @FXML private TextField dashTotalSpent;
    @FXML private TextField dashTopCategory;
    @FXML private TextField dashTopCategoryAmount;
    @FXML private TextField dashBudgetStatus;
    @FXML private TextField dashBudgetSubtitle;
    @FXML private TextField dashMonthChange;

    // --- Budget / totals ---
    @FXML private Label totalLabel;
    @FXML private Label moneySavedLabel;

    // --- Income fields ---
    @FXML private Button toggleIncomeButton;
    @FXML private VBox incomeFieldsBox;
    @FXML private TextField recurringIncomeField;
    @FXML private TextField incomeField;

    // --- Income table ---
    @FXML private TableView<Expense> incomeTable;
    @FXML private Label incomeTabSummary;
    @FXML private Label incomeErrorLabel;

    // --- Category table ---
    @FXML private TableView<CategoryTotal> categoryTable;
    @FXML private TableColumn<CategoryTotal, String> categoryNameColumn;
    @FXML private TableColumn<CategoryTotal, Double> categoryTotalColumn;
    @FXML private TableColumn<CategoryTotal, Double> budgetColumn;
    @FXML private TableColumn<CategoryTotal, Double> progressColumn;

    // --- Error label ---
    @FXML private Label errorLabel;

    private SharedState state;
    private boolean suppressIncomeListener = false;
    private boolean suppressRecurringIncomeListener = false;
    private boolean initialized = false;

    @FXML
    public void initialize() {
        // Minimal — real setup happens in init(SharedState) after data is loaded
    }

    public void init(SharedState state) {
        this.state = state;
        if (initialized) return;
        initialized = true;

        setupCategoryTable();
        setupCategoryTableContextMenu();
        setupIncomeTable();
        setupIncomeFieldListeners();

        UIUtils.makeLabelCopyable(totalLabel);
        UIUtils.makeLabelCopyable(moneySavedLabel);
    }

    // ======================== REFRESH ========================

    public void refresh() {
        updateTotalExpenses();
        refreshIncomeTable();
        updateIncomeField();
    }

    // ======================== CATEGORY TABLE SETUP ========================

    private void setupCategoryTable() {
        categoryTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        categoryTable.setItems(state.getCategoryTotals());

        Label categoryPlaceholder = new Label("No category data for this period.");
        categoryPlaceholder.getStyleClass().add("empty-state-label");
        categoryTable.setPlaceholder(categoryPlaceholder);

        // Color-coded name column
        categoryNameColumn.setCellFactory(tc -> new TableCell<CategoryTotal, String>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setStyle("");
                } else {
                    setText(item);
                    String color = UIUtils.getCategoryColor(item);
                    setStyle("-fx-background-color: " + color + "33; -fx-border-color: " + color
                            + " transparent transparent transparent; -fx-border-width: 0 0 0 3;");
                }
            }
        });

        // Currency-formatted total column
        categoryTotalColumn.setCellFactory(tc -> new TableCell<CategoryTotal, Double>() {
            @Override
            protected void updateItem(Double item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : fmt(item));
            }
        });

        // Budget column
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

        // Progress bar column
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
    }

    private void setupCategoryTableContextMenu() {
        ContextMenu budgetMenu = new ContextMenu();
        MenuItem setBudgetItem = new MenuItem("Set Budget...");
        setBudgetItem.setOnAction(e -> handleSetBudget());
        MenuItem clearBudgetItem = new MenuItem("Clear Budget");
        clearBudgetItem.setOnAction(e -> handleClearBudget());
        budgetMenu.getItems().addAll(setBudgetItem, clearBudgetItem);
        categoryTable.setContextMenu(budgetMenu);
    }

    // ======================== INCOME TABLE SETUP ========================

    private void setupIncomeTable() {
        incomeTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        incomeTable.setItems(state.getIncomeList());

        // Empty state
        VBox incomeEmptyState = new VBox(6);
        incomeEmptyState.setAlignment(Pos.CENTER);
        Label incomeMsg = new Label("No income transactions for this period.");
        incomeMsg.getStyleClass().add("empty-state-label");
        Label incomeHint = new Label("Import a bank statement or mark expenses as income via right-click.");
        incomeHint.getStyleClass().add("empty-state-hint");
        incomeEmptyState.getChildren().addAll(incomeMsg, incomeHint);
        incomeTable.setPlaceholder(incomeEmptyState);

        // Row factory with excluded styling and context menu
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
                        state.saveExpenses();
                    } catch (IOException ex) {
                        item.setExcluded(prev);
                        UIUtils.showMessage("Failed to save: " + ex.getMessage(), true, incomeErrorLabel);
                    }
                    state.requestRefresh();
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

        // Delete key handler
        incomeTable.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.DELETE) handleDeleteIncome();
        });
    }

    // ======================== INCOME FIELD LISTENERS ========================

    private void setupIncomeFieldListeners() {
        // Recurring income field — saves to storage
        recurringIncomeField.textProperty().addListener((obs, oldVal, newVal) -> {
            if (suppressRecurringIncomeListener) return;
            try {
                double value = (newVal == null || newVal.isEmpty()) ? 0.0 : Double.parseDouble(newVal);
                if (value < 0) { return; }
                state.setRecurringIncome(value);
                state.getStorage().saveRecurringIncome(value);
                updateIncomeField();
            } catch (NumberFormatException e) {
                // ignore while typing
            } catch (IOException e) {
                UIUtils.showMessage("Error saving recurring income: " + e.getMessage(), true, errorLabel);
            }
        });

        // Income field — per-month override
        incomeField.textProperty().addListener((obs, oldVal, newVal) -> {
            if (suppressIncomeListener) return;
            Integer selectedYear = state.getSelectedYear();
            Month selectedMonth = state.getSelectedMonth();
            if (selectedYear == null || selectedMonth == null) return;
            YearMonth selectedYearMonth = YearMonth.of(selectedYear, selectedMonth);
            try {
                if (newVal == null || newVal.isEmpty()) {
                    // Clear manual override — fall back to recurring
                    state.getIncomes().remove(selectedYearMonth);
                    try {
                        state.getStorage().saveIncomes(state.getIncomes());
                    } catch (IOException ex) {
                        UIUtils.showMessage("Error saving incomes: " + ex.getMessage(), true, errorLabel);
                    }
                    updateTotalExpenses();
                    return;
                }
                double incomeValue = Double.parseDouble(newVal);
                if (incomeValue < 0) {
                    UIUtils.showMessage("Income cannot be negative", true, errorLabel);
                    return;
                }
                state.getIncomes().put(selectedYearMonth, incomeValue);
                try {
                    state.getStorage().saveIncomes(state.getIncomes());
                    updateTotalExpenses();
                } catch (IOException ex) {
                    state.getIncomes().remove(selectedYearMonth);
                    UIUtils.showMessage("Error saving incomes: " + ex.getMessage(), true, errorLabel);
                }
            } catch (NumberFormatException ex) {
                UIUtils.showMessage("Invalid income: Please enter a valid number (e.g., 5000.00)", true, errorLabel);
            }
        });
    }

    // ======================== CORE COMPUTATION ========================

    private void updateTotalExpenses() {
        Integer selectedYear = state.getSelectedYear();
        Month selectedMonth = state.getSelectedMonth();

        if (selectedYear == null || selectedMonth == null) {
            totalLabel.setText("Total Expenses: " + fmt(0));
            moneySavedLabel.setText("Money Saved: " + fmt(0));
            state.getCategoryTotals().clear();
            dashTotalSpent.setText(fmt(0));
            dashTopCategory.setText("-");
            dashTopCategoryAmount.setText("");
            dashBudgetStatus.setText("-");
            dashBudgetStatus.getStyleClass().setAll("dashboard-card-value-field");
            dashBudgetSubtitle.setText("");
            dashMonthChange.setText("-");
            dashMonthChange.getStyleClass().setAll("dashboard-card-value-field");
            return;
        }

        YearMonth selectedYearMonth = YearMonth.of(selectedYear, selectedMonth);
        ObservableList<Expense> expenseList = state.getExpenseList();
        Map<String, Double> budgets = state.getBudgets();

        // Check if this month has real imported data (not just recurring projections)
        boolean hasImportedData = state.monthHasImportedData(selectedYearMonth);

        // Filter expenses for the selected month (dashboard computes its own totals
        // by streaming over expenseList directly)
        List<Expense> monthExpenses = expenseList.stream()
                .filter(e -> YearMonth.from(e.getDate()).equals(selectedYearMonth))
                .collect(Collectors.toList());

        double total;
        if (hasImportedData) {
            // Actual month: only count real expenses (exclude recurring projections)
            total = monthExpenses.stream()
                    .filter(e -> !e.isExcluded() && !e.isIncome() && e.getRecurringId() == null)
                    .mapToDouble(Expense::getAmount)
                    .sum();
        } else {
            // Projected month: use recurring expenses as forecast
            total = monthExpenses.stream()
                    .filter(e -> !e.isExcluded() && !e.isIncome())
                    .mapToDouble(Expense::getAmount)
                    .sum();
        }

        double actualIncome = monthExpenses.stream()
                .filter(e -> !e.isExcluded() && e.isIncome())
                .mapToDouble(Expense::getAmount)
                .sum();
        double projectedIncome = state.getIncomes().getOrDefault(selectedYearMonth, state.getRecurringIncome());

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

        Map<String, Double> categoryMap = monthExpenses.stream()
                .filter(e -> !e.isExcluded() && !e.isIncome()
                        && (!hasImportedData || e.getRecurringId() == null))
                .collect(Collectors.groupingBy(
                        Expense::getCategory,
                        Collectors.summingDouble(Expense::getAmount))
                );

        state.getCategoryTotals().setAll(categoryMap.entrySet().stream()
                .map(entry -> new CategoryTotal(entry.getKey(), entry.getValue(),
                        budgets.getOrDefault(entry.getKey(), 0.0)))
                .sorted(Comparator.comparing(CategoryTotal::getCategory))
                .collect(Collectors.toList()));

        // Compute previous month total for month-over-month comparison
        YearMonth prevYearMonth = selectedYearMonth.minusMonths(1);
        boolean prevMonthHasImports = state.monthHasImportedData(prevYearMonth);
        double prevTotal = expenseList.stream()
                .filter(expense -> {
                    if (expense.isExcluded() || expense.isIncome()) return false;
                    if (prevMonthHasImports && expense.getRecurringId() != null) return false;
                    return YearMonth.from(expense.getDate()).equals(prevYearMonth);
                })
                .mapToDouble(Expense::getAmount)
                .sum();

        updateDashboardCards(total, categoryMap, prevTotal);
    }

    private void updateDashboardCards(double total, Map<String, Double> categoryMap, double prevTotal) {
        Map<String, Double> budgets = state.getBudgets();

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
                int topPct = total > 0 ? (int) Math.round((top.getValue() / total) * 100) : 0;
                dashTopCategoryAmount.setText(fmt(top.getValue()) + " (" + topPct + "%)");
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
            int pctUsed = (int) Math.round((totalBudgeted / totalBudget) * 100);
            if (remaining >= 0) {
                dashBudgetStatus.setText(fmt(remaining) + " left");
                dashBudgetStatus.getStyleClass().setAll("dashboard-card-value-field", "dashboard-positive");
            } else {
                dashBudgetStatus.setText(fmt(Math.abs(remaining)) + " over");
                dashBudgetStatus.getStyleClass().setAll("dashboard-card-value-field", "dashboard-negative");
            }
            dashBudgetSubtitle.setText(String.format("%d%% of %s budget used", pctUsed, fmt(totalBudget)));
        } else {
            dashBudgetStatus.setText("No budgets");
            dashBudgetStatus.getStyleClass().setAll("dashboard-card-value-field");
            dashBudgetSubtitle.setText("");
        }

        // Month-over-month change
        if (prevTotal > 0) {
            double change = total - prevTotal;
            double pct = (change / prevTotal) * 100;
            String arrow = change >= 0 ? "\u25B2" : "\u25BC";
            dashMonthChange.setText(String.format("%s %.0f%%", arrow, Math.abs(pct)));
            dashMonthChange.getStyleClass().setAll("dashboard-card-value-field",
                    change <= 0 ? "dashboard-positive" : "dashboard-negative");
        } else {
            dashMonthChange.setText("-");
            dashMonthChange.getStyleClass().setAll("dashboard-card-value-field");
        }
    }

    // ======================== BUDGET HANDLERS ========================

    private void handleSetBudget() {
        CategoryTotal selected = categoryTable.getSelectionModel().getSelectedItem();
        if (selected == null) {
            UIUtils.showMessage("Please select a category to set a budget for", true, errorLabel);
            return;
        }

        TextInputDialog dialog = new TextInputDialog(
                selected.getBudget() > 0 ? String.format("%.2f", selected.getBudget()) : "");
        dialog.initOwner(state.getStage());
        dialog.setTitle("Set Budget");
        dialog.setHeaderText("Set monthly budget for: " + selected.getCategory());
        dialog.setContentText("Budget amount:");
        dialog.getDialogPane().getStylesheets().add(
                getClass().getResource("/styles.css").toExternalForm());

        dialog.showAndWait().ifPresent(input -> {
            try {
                double budget = input.isEmpty() ? 0.0 : Double.parseDouble(input);
                if (budget < 0) {
                    UIUtils.showMessage("Budget cannot be negative", true, errorLabel);
                    return;
                }
                Map<String, Double> budgets = state.getBudgets();
                if (budget > 0) {
                    budgets.put(selected.getCategory(), budget);
                } else {
                    budgets.remove(selected.getCategory());
                }
                state.getStorage().saveBudgets(budgets);
                updateTotalExpenses();
                UIUtils.showMessage("Budget set for " + selected.getCategory(), false, errorLabel);
            } catch (NumberFormatException ex) {
                UIUtils.showMessage("Invalid budget amount", true, errorLabel);
            } catch (IOException ex) {
                UIUtils.showMessage("Error saving budget: " + ex.getMessage(), true, errorLabel);
            }
        });
    }

    private void handleClearBudget() {
        CategoryTotal selected = categoryTable.getSelectionModel().getSelectedItem();
        if (selected == null) {
            UIUtils.showMessage("Please select a category", true, errorLabel);
            return;
        }
        state.getBudgets().remove(selected.getCategory());
        try {
            state.getStorage().saveBudgets(state.getBudgets());
            updateTotalExpenses();
            UIUtils.showMessage("Budget cleared for " + selected.getCategory(), false, errorLabel);
        } catch (IOException ex) {
            UIUtils.showMessage("Error saving budget: " + ex.getMessage(), true, errorLabel);
        }
    }

    // ======================== INCOME HANDLERS ========================

    @FXML
    private void handleToggleIncome() {
        boolean show = !incomeFieldsBox.isVisible();
        incomeFieldsBox.setVisible(show);
        incomeFieldsBox.setManaged(show);
        toggleIncomeButton.setText(show ? "Hide" : "Edit");
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
        confirmation.initOwner(state.getStage());
        confirmation.setTitle("Delete Income");
        confirmation.setHeaderText(null);
        confirmation.setContentText("Delete this income entry (" + fmt(selected.getAmount()) + ")?");
        confirmation.getDialogPane().getStylesheets().add(
                getClass().getResource("/styles.css").toExternalForm());

        Optional<ButtonType> result = confirmation.showAndWait();
        if (result.isPresent() && result.get() == ButtonType.OK) {
            state.getManager().executeCommand(new DeleteExpenseCommand(state.getManager(), selected));
            try {
                state.saveExpenses();
                state.syncExpenseList();
                state.requestRefresh();
                incomeErrorLabel.setText("Income deleted.");
                incomeErrorLabel.getStyleClass().setAll("error-label", "success-message");
            } catch (Exception ex) {
                state.getManager().undo();
                incomeErrorLabel.setText("Error: " + ex.getMessage());
                incomeErrorLabel.getStyleClass().setAll("error-label", "error-message");
            }
        }
    }

    private void refreshIncomeTable() {
        Integer selectedYear = state.getSelectedYear();
        Month selectedMonth = state.getSelectedMonth();
        if (selectedYear == null || selectedMonth == null) {
            state.getIncomeList().clear();
            incomeTabSummary.setText("");
            return;
        }
        YearMonth selectedYearMonth = YearMonth.of(selectedYear, selectedMonth);
        List<Expense> monthIncome = state.getManager().getExpenses().stream()
                .filter(e -> e.isIncome() && YearMonth.from(e.getDate()).equals(selectedYearMonth))
                .sorted(Comparator.comparing(Expense::getDate).reversed())
                .collect(Collectors.toList());
        state.getIncomeList().setAll(monthIncome);
        if (monthIncome.isEmpty()) {
            incomeTabSummary.setText("No income this month.");
        } else {
            double total = monthIncome.stream().mapToDouble(Expense::getAmount).sum();
            incomeTabSummary.setText(String.format("%d transaction(s) \u2014 Total: %s", monthIncome.size(), fmt(total)));
        }
    }

    private void updateIncomeField() {
        suppressIncomeListener = true;
        suppressRecurringIncomeListener = true;
        try {
            // Populate recurring income field with stored value
            double recurringIncome = state.getRecurringIncome();
            String currentRecurring = recurringIncomeField.getText();
            String expectedRecurring = recurringIncome > 0 ? String.format("%.2f", recurringIncome) : "";
            if (!expectedRecurring.equals(currentRecurring)) {
                recurringIncomeField.setText(expectedRecurring);
            }

            Integer selectedYear = state.getSelectedYear();
            Month selectedMonth = state.getSelectedMonth();
            if (selectedYear == null || selectedMonth == null) {
                incomeField.setText("");
                incomeField.setPromptText("Leave empty to use default");
                return;
            }
            YearMonth selectedYearMonth = YearMonth.of(selectedYear, selectedMonth);
            Double monthProjected = state.getIncomes().get(selectedYearMonth);
            if (monthProjected != null) {
                incomeField.setText(String.format("%.2f", monthProjected));
                incomeField.setPromptText("Clear to use default");
            } else {
                incomeField.setText("");
                incomeField.setPromptText(recurringIncome > 0
                        ? String.format("Using default: %.2f", recurringIncome)
                        : "Set projected income");
            }
        } finally {
            suppressIncomeListener = false;
            suppressRecurringIncomeListener = false;
        }
    }

    // ======================== HELPERS ========================

    private String fmt(double amount) {
        return UIUtils.fmt(amount, state.getCurrencySymbol());
    }

    // --- Public accessors for MainController's copyable view content ---

    public String getTotalSpentText() { return dashTotalSpent.getText(); }
    public String getTopCategoryText() { return dashTopCategory.getText() + "  " + dashTopCategoryAmount.getText(); }
    public String getBudgetStatusText() { return dashBudgetStatus.getText() + "  " + dashBudgetSubtitle.getText(); }
    public String getMonthChangeText() { return dashMonthChange.getText(); }
}
