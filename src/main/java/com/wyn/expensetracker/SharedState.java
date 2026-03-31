package com.wyn.expensetracker;

import javafx.beans.property.*;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.stage.Stage;

import java.io.IOException;
import java.time.Month;
import java.time.YearMonth;
import java.util.*;
import java.util.stream.Collectors;

public class SharedState {

    // Core services
    private ExpenseManager manager;
    private FileStorage storage;
    private ProfileManager profileManager;
    private CategorizationRules categorizationRules;
    private ReceiptScanner receiptScanner;
    private final ProjectionEngine projectionEngine = new ProjectionEngine();

    // Observable collections
    private final ObservableList<String> categories;
    private final ObservableList<Expense> expenseList = FXCollections.observableArrayList();
    private final ObservableList<RecurringExpense> recurringList = FXCollections.observableArrayList();
    private final ObservableList<CategoryTotal> categoryTotals = FXCollections.observableArrayList();
    private final ObservableList<Expense> incomeList = FXCollections.observableArrayList();
    private final ObservableList<ImportLog> importLogs = FXCollections.observableArrayList();
    private final FilteredList<Expense> filteredData;

    // Year list for combo
    private final ObservableList<Integer> yearList = FXCollections.observableArrayList();

    // Settings
    private Map<YearMonth, Double> incomes;
    private Map<String, Double> budgets;
    private final StringProperty currencySymbol = new SimpleStringProperty("R");
    private final DoubleProperty recurringIncome = new SimpleDoubleProperty(0.0);

    // Period selection (bound to toolbar combos)
    private final ObjectProperty<Integer> selectedYear = new SimpleObjectProperty<>();
    private final ObjectProperty<Month> selectedMonth = new SimpleObjectProperty<>();
    private final StringProperty chartPeriod = new SimpleStringProperty("Last 12 Months");

    // State flags
    private boolean projectionsNeedUpdate = true;
    private String currentViewName = "dashboard";

    // UI
    private Stage stage;

    // Refresh callback
    private Runnable refreshCallback;

    public SharedState(ExpenseManager manager, FileStorage storage,
                       ObservableList<String> categories, Map<YearMonth, Double> incomes,
                       Stage stage, ProfileManager profileManager) {
        this.manager = manager;
        this.storage = storage;
        this.categories = categories;
        this.incomes = incomes;
        this.stage = stage;
        this.profileManager = profileManager;
        this.filteredData = new FilteredList<>(expenseList, p -> true);
        this.budgets = new HashMap<>();
        this.categorizationRules = new CategorizationRules();
        this.receiptScanner = new ReceiptScanner();
    }

    // --- Core services ---

    public ExpenseManager getManager() { return manager; }
    public void setManager(ExpenseManager manager) { this.manager = manager; }

    public FileStorage getStorage() { return storage; }
    public void setStorage(FileStorage storage) { this.storage = storage; }

    public ProfileManager getProfileManager() { return profileManager; }

    public CategorizationRules getCategorizationRules() { return categorizationRules; }
    public void setCategorizationRules(CategorizationRules rules) { this.categorizationRules = rules; }

    public ReceiptScanner getReceiptScanner() { return receiptScanner; }
    public ProjectionEngine getProjectionEngine() { return projectionEngine; }

    // --- Collections ---

    public ObservableList<String> getCategories() { return categories; }
    public ObservableList<Expense> getExpenseList() { return expenseList; }
    public ObservableList<RecurringExpense> getRecurringList() { return recurringList; }
    public ObservableList<CategoryTotal> getCategoryTotals() { return categoryTotals; }
    public ObservableList<Expense> getIncomeList() { return incomeList; }
    public ObservableList<ImportLog> getImportLogs() { return importLogs; }
    public FilteredList<Expense> getFilteredData() { return filteredData; }
    public ObservableList<Integer> getYearList() { return yearList; }

    // --- Settings ---

    public Map<YearMonth, Double> getIncomes() { return incomes; }
    public void setIncomes(Map<YearMonth, Double> incomes) { this.incomes = incomes; }

    public Map<String, Double> getBudgets() { return budgets; }
    public void setBudgets(Map<String, Double> budgets) { this.budgets = budgets; }

    public StringProperty currencySymbolProperty() { return currencySymbol; }
    public String getCurrencySymbol() { return currencySymbol.get(); }
    public void setCurrencySymbol(String symbol) { currencySymbol.set(symbol); }

    public DoubleProperty recurringIncomeProperty() { return recurringIncome; }
    public double getRecurringIncome() { return recurringIncome.get(); }
    public void setRecurringIncome(double value) { recurringIncome.set(value); }

    // --- Period selection ---

    public ObjectProperty<Integer> selectedYearProperty() { return selectedYear; }
    public Integer getSelectedYear() { return selectedYear.get(); }
    public void setSelectedYear(Integer year) { selectedYear.set(year); }

    public ObjectProperty<Month> selectedMonthProperty() { return selectedMonth; }
    public Month getSelectedMonth() { return selectedMonth.get(); }
    public void setSelectedMonth(Month month) { selectedMonth.set(month); }

    public StringProperty chartPeriodProperty() { return chartPeriod; }
    public String getChartPeriod() { return chartPeriod.get(); }
    public void setChartPeriod(String period) { chartPeriod.set(period); }

    public YearMonth getSelectedYearMonth() {
        Integer y = getSelectedYear();
        Month m = getSelectedMonth();
        if (y == null || m == null) return null;
        return YearMonth.of(y, m);
    }

    // --- State flags ---

    public boolean isProjectionsNeedUpdate() { return projectionsNeedUpdate; }
    public void setProjectionsNeedUpdate(boolean value) { projectionsNeedUpdate = value; }

    public String getCurrentViewName() { return currentViewName; }
    public void setCurrentViewName(String name) { currentViewName = name; }

    // --- UI ---

    public Stage getStage() { return stage; }

    // --- Refresh ---

    public void setRefreshCallback(Runnable callback) { this.refreshCallback = callback; }

    public void requestRefresh() {
        if (refreshCallback != null) refreshCallback.run();
    }

    // --- Convenience methods ---

    public void saveExpenses() throws IOException {
        storage.saveExpenses(manager.getExpensesForSave());
    }

    public void syncExpenseList() {
        expenseList.setAll(manager.getExpenses());
    }

    public void syncRecurringList() {
        recurringList.setAll(manager.getBaseRecurringExpenses());
    }

    public boolean monthHasImportedData(YearMonth ym) {
        return expenseList.stream()
            .anyMatch(e -> !e.isIncome() && e.getImportId() != null
                && YearMonth.from(e.getDate()).equals(ym));
    }

    public boolean shouldExcludeRecurring(Expense recurring, YearMonth ym) {
        RecurringExpense source = recurring.getSourceRecurringExpense();
        if (source == null) return false;
        String srcDesc = source.getDescription() != null ? source.getDescription().toLowerCase().trim() : "";
        if (srcDesc.isEmpty()) return false;
        return expenseList.stream()
            .anyMatch(imp -> imp.getRecurringId() == null && imp.getImportId() != null
                && !imp.isIncome()
                && YearMonth.from(imp.getDate()).equals(ym)
                && imp.getDescription() != null
                && imp.getDescription().toLowerCase().contains(srcDesc)
                && Math.abs(imp.getAmount() - recurring.getAmount()) <= recurring.getAmount() * 0.20);
    }

    public List<Expense> filterExpensesByPeriod(String chartPeriod, int selectedYear,
                                                 YearMonth selectedYearMonth, YearMonth now) {
        Set<YearMonth> importedMonths = expenseList.stream()
            .filter(e -> !e.isIncome() && e.getImportId() != null)
            .map(e -> YearMonth.from(e.getDate()))
            .collect(Collectors.toSet());

        return expenseList.stream()
            .filter(expense -> !expense.isExcluded() && !expense.isIncome())
            .filter(expense -> {
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
                    default:
                        return true;
                }
            })
            .collect(Collectors.toList());
    }

    public void getMonthRange(String chartPeriod, int selectedYear, YearMonth selectedYearMonth,
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
            default:
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
}
