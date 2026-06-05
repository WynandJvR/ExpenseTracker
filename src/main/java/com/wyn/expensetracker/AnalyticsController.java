package com.wyn.expensetracker;

import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.chart.*;
import javafx.scene.control.*;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

import javafx.scene.Cursor;

import java.time.Month;
import java.time.YearMonth;
import java.time.format.TextStyle;
import java.util.*;
import java.util.stream.Collectors;

public class AnalyticsController {

    // --- FXML fields ---
    @FXML private ComboBox<String> chartPeriodCombo;
    @FXML private TabPane analyticsTabPane;
    @FXML private PieChart categoryChart;
    @FXML private StackedBarChart<String, Number> monthlyTrendChart;
    @FXML private FlowPane trendChartLegend;
    @FXML private BarChart<String, Number> incomeVsExpensesChart;
    @FXML private BarChart<String, Number> budgetVsActualChart;
    @FXML private Label budgetVsActualSubtitle;
    @FXML private VBox budgetEmptyOverlay;
    @FXML private LineChart<Number, Number> cumulativeSpendingChart;
    @FXML private Label cumulativeSubtitle;
    @FXML private StackedAreaChart<String, Number> categoryTrendChart;
    @FXML private LineChart<String, Number> yearOverYearChart;
    @FXML private PieChart recurringVsOneTimeChart;
    @FXML private Tab projectionsTab;
    @FXML private Tab cashFlowTab;
    @FXML private VBox cashFlowContent;
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
    private static final int MAX_PIE_SLICES = 8;

    // --- State ---
    private SharedState state;
    private boolean initialized = false;
    private List<Expense> lastChartExpenses = Collections.emptyList();

    private double toBase(Expense e) {
        return state.getCurrencyManager().toBase(e.getAmount(), e.getCurrency());
    }

    @FXML
    public void initialize() {
        // Empty — real setup in init(SharedState)
    }

    public void init(SharedState state) {
        this.state = state;
        if (initialized) return;
        initialized = true;

        chartPeriodCombo.setItems(FXCollections.observableArrayList(
            "All Time", "Last 12 Months", "Last 6 Months", "By Year", "By Month"));
        chartPeriodCombo.valueProperty().bindBidirectional(state.chartPeriodProperty());

        chartPeriodCombo.valueProperty().addListener((obs, oldVal, newVal) -> updateCharts());

        // Projections and Cash Flow tabs — lazy compute
        analyticsTabPane.getSelectionModel().selectedItemProperty().addListener((obs, oldTab, newTab) -> {
            if (newTab == projectionsTab && state.isProjectionsNeedUpdate()) {
                updateProjections();
            }
            if (newTab == cashFlowTab) {
                updateCashFlowCalendar();
            }
        });
    }

    public void refresh() {
        updateCharts();
    }

    // ======================== MASTER CHART UPDATE ========================

    private void updateCharts() {
        Integer selectedYear = state.getSelectedYear();
        Month selectedMonth = state.getSelectedMonth();
        String chartPeriod = state.getChartPeriod();

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

        List<Expense> chartExpenses = state.filterExpensesByPeriod(chartPeriod, selectedYear, selectedYearMonth, now);
        this.lastChartExpenses = chartExpenses;

        updateCategoryPieChart(chartExpenses);
        updateMonthlyTrendBarChart(chartExpenses, chartPeriod, selectedYear, selectedMonth, selectedYearMonth, now);
        updateIncomeVsExpensesChart(chartPeriod, selectedYear, selectedYearMonth, now);
        updateBudgetVsActualChart(selectedYearMonth);
        updateCumulativeSpendingChart(selectedYearMonth);
        updateCategoryTrendChart(chartPeriod, selectedYear, selectedYearMonth, now);
        updateYearOverYearChart(selectedYear);
        updateRecurringVsOneTimeChart(chartPeriod, selectedYear, selectedYearMonth, now);

        // Flag charts that ended up with no renderable data (categoryChart/budgetVsActual handle their own empty state)
        applyEmptyTitle(monthlyTrendChart, xyEmpty(monthlyTrendChart));
        applyEmptyTitle(incomeVsExpensesChart, xyEmpty(incomeVsExpensesChart));
        applyEmptyTitle(cumulativeSpendingChart, xyEmpty(cumulativeSpendingChart));
        applyEmptyTitle(categoryTrendChart, xyEmpty(categoryTrendChart));
        applyEmptyTitle(yearOverYearChart, xyEmpty(yearOverYearChart));
        applyEmptyTitle(recurringVsOneTimeChart, recurringVsOneTimeChart.getData().isEmpty());

        // Mark projections as needing update; compute immediately if tab is active
        state.setProjectionsNeedUpdate(true);
        if (analyticsTabPane.getSelectionModel().getSelectedItem() == projectionsTab) {
            updateProjections();
        }

        // Fade-in animation for all charts
        UIUtils.animateChartFadeIn(categoryChart);
        UIUtils.animateChartFadeIn(monthlyTrendChart);
        UIUtils.animateChartFadeIn(incomeVsExpensesChart);
        UIUtils.animateChartFadeIn(budgetVsActualChart);
        UIUtils.animateChartFadeIn(cumulativeSpendingChart);
        UIUtils.animateChartFadeIn(categoryTrendChart);
        UIUtils.animateChartFadeIn(yearOverYearChart);
        UIUtils.animateChartFadeIn(recurringVsOneTimeChart);
    }

    private static final String NO_DATA_SUFFIX = " — No data for this period";

    /** True when an XY chart has no series, or every series is empty. */
    private static boolean xyEmpty(XYChart<?, ?> chart) {
        return chart.getData().stream().allMatch(s -> s.getData().isEmpty());
    }

    /** Appends/removes the "No data" suffix on a chart title without stacking it across refreshes. */
    private static void applyEmptyTitle(Chart chart, boolean empty) {
        String title = chart.getTitle() == null ? "" : chart.getTitle();
        boolean hasSuffix = title.endsWith(NO_DATA_SUFFIX);
        if (empty && !hasSuffix) {
            chart.setTitle(title + NO_DATA_SUFFIX);
        } else if (!empty && hasSuffix) {
            chart.setTitle(title.substring(0, title.length() - NO_DATA_SUFFIX.length()));
        }
    }

    // ======================== CATEGORY PIE CHART ========================

    private void updateCategoryPieChart(List<Expense> chartExpenses) {
        if (chartExpenses.isEmpty()) {
            categoryChart.setData(FXCollections.observableArrayList());
            categoryChart.setTitle("Expenses by Category \u2014 No data for this period");
            return;
        }
        categoryChart.setTitle("Expenses by Category");

        Map<String, Double> categoryMap = chartExpenses.stream()
            .collect(Collectors.groupingBy(
                Expense::getCategory,
                Collectors.summingDouble(this::toBase)));

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
            final String color = UIUtils.getCategoryColor(entry.getKey());
            final String category = entry.getKey();
            final double amount = entry.getValue();
            double pct = pieTotal > 0 ? (amount / pieTotal) * 100 : 0;
            PieChart.Data data = new PieChart.Data(
                category + " (" + String.format("%.0f%%", pct) + ")",
                amount);
            data.nodeProperty().addListener((obs, oldNode, newNode) -> {
                if (newNode != null) {
                    newNode.setStyle("-fx-pie-color: " + color + ";");
                    newNode.setCursor(Cursor.HAND);
                    Tooltip tooltip = new Tooltip(category + ": " + fmt(amount)
                        + " (" + String.format("%.1f%%", pieTotal > 0 ? (amount / pieTotal) * 100 : 0) + ")");
                    tooltip.setStyle("-fx-font-size: 13px;");
                    Tooltip.install(newNode, tooltip);
                    newNode.setOnMouseClicked(event -> {
                        List<Expense> filtered = lastChartExpenses.stream()
                            .filter(e -> e.getCategory().equals(category))
                            .collect(Collectors.toList());
                        DrillDownDialog.show(state.getStage(), "Category: " + category, filtered, state.getCurrencySymbol(), state.getCurrencyManager());
                    });
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
            Set<String> topCategories = displayEntries.stream()
                .map(Map.Entry::getKey).collect(Collectors.toSet());
            otherData.nodeProperty().addListener((obs, oldNode, newNode) -> {
                if (newNode != null) {
                    newNode.setStyle("-fx-pie-color: #888888;");
                    newNode.setCursor(Cursor.HAND);
                    Tooltip tooltip = new Tooltip("Other: " + fmt(otherAmt)
                        + " (" + String.format("%.1f%%", pieTotal > 0 ? (otherAmt / pieTotal) * 100 : 0) + ")");
                    tooltip.setStyle("-fx-font-size: 13px;");
                    Tooltip.install(newNode, tooltip);
                    newNode.setOnMouseClicked(event -> {
                        List<Expense> filtered = lastChartExpenses.stream()
                            .filter(e -> !topCategories.contains(e.getCategory()))
                            .collect(Collectors.toList());
                        DrillDownDialog.show(state.getStage(), "Other Categories", filtered, state.getCurrencySymbol(), state.getCurrencyManager());
                    });
                }
            });
            pieChartData.add(otherData);
        }

        categoryChart.setData(pieChartData);
        categoryChart.setLabelLineLength(10);
        categoryChart.setAnimated(true);
    }

    // ======================== MONTHLY TREND BAR CHART ========================

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

        ObservableList<Expense> expenseList = state.getExpenseList();

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
                        catTotals.merge(e.getCategory(), toBase(e), Double::sum);
                        allCategories.add(e.getCategory());
                    }
                }
                barCategoryTotals.put(label, catTotals);
            }
            monthlyTrendChart.setTitle("Weekly Spending \u2014 "
                + selectedMonth.getDisplayName(TextStyle.FULL, Locale.getDefault()) + " " + selectedYear);
        } else {
            Set<YearMonth> importedMonths = state.importedMonths();
            Map<YearMonth, Double> monthlyTotals = expenseList.stream()
                .filter(e -> state.countsAsSpend(e, importedMonths))
                .collect(Collectors.groupingBy(
                    expense -> YearMonth.from(expense.getDate()),
                    Collectors.summingDouble(this::toBase)));

            YearMonth[] range = new YearMonth[2];
            state.getMonthRange(chartPeriod, selectedYear, selectedYearMonth, now, monthlyTotals, range);
            YearMonth rangeStart = range[0];
            YearMonth rangeEnd = range[1];

            boolean sameYear = rangeStart.getYear() == rangeEnd.getYear();

            YearMonth cursor = rangeStart;
            while (!cursor.isAfter(rangeEnd)) {
                final YearMonth ym = cursor;
                String label = ym.getMonth()
                    .getDisplayName(TextStyle.SHORT, Locale.getDefault())
                    + (sameYear ? "" : " '" + String.format("%02d", ym.getYear() % 100));
                barLabels.add(label);

                Map<String, Double> catTotals = new LinkedHashMap<>();
                for (Expense e : expenseList) {
                    if (state.countsAsSpend(e, importedMonths) && YearMonth.from(e.getDate()).equals(ym)) {
                        catTotals.merge(e.getCategory(), toBase(e), Double::sum);
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
            final String color = UIUtils.getCategoryColor(category);

            for (String label : barLabels) {
                double amount = barCategoryTotals.get(label).getOrDefault(category, 0.0);
                XYChart.Data<String, Number> data = new XYChart.Data<>(label, amount);
                final String barLabel = label;
                final double amt = amount;
                data.nodeProperty().addListener((obs, oldNode, newNode) -> {
                    if (newNode != null) {
                        newNode.setStyle("-fx-bar-fill: " + color + ";");
                        if (amt > 0) {
                            newNode.setCursor(Cursor.HAND);
                            Tooltip tooltip = new Tooltip(category + " (" + barLabel + "): " + fmt(amt));
                            tooltip.setStyle("-fx-font-size: 13px;");
                            Tooltip.install(newNode, tooltip);
                            newNode.setOnMouseClicked(event -> {
                                List<Expense> filtered = lastChartExpenses.stream()
                                    .filter(e -> e.getCategory().equals(category))
                                    .collect(Collectors.toList());
                                DrillDownDialog.show(state.getStage(),
                                    category + " (" + barLabel + ")", filtered, state.getCurrencySymbol(), state.getCurrencyManager());
                            });
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
            String color = UIUtils.getCategoryColor(category);
            javafx.scene.shape.Rectangle swatch = new javafx.scene.shape.Rectangle(10, 10);
            swatch.setFill(javafx.scene.paint.Color.web(color));
            swatch.setArcWidth(2);
            swatch.setArcHeight(2);
            Label lbl = new Label(category);
            lbl.setStyle("-fx-text-fill: #F5F5F5; -fx-font-size: 11px; -fx-font-weight: bold;");
            HBox item = new HBox(4, swatch, lbl);
            item.setAlignment(Pos.CENTER_LEFT);
            trendChartLegend.getChildren().add(item);
        }

        monthlyTrendChart.setAnimated(true);
    }

    // ======================== INCOME VS EXPENSES CHART ========================

    private void updateIncomeVsExpensesChart(String chartPeriod, int selectedYear,
                                              YearMonth selectedYearMonth, YearMonth now) {
        CategoryAxis xAxis = (CategoryAxis) incomeVsExpensesChart.getXAxis();
        xAxis.setAnimated(false);
        incomeVsExpensesChart.setAnimated(false);
        incomeVsExpensesChart.getData().clear();
        xAxis.getCategories().clear();
        xAxis.setAutoRanging(false);

        ObservableList<Expense> expenseList = state.getExpenseList();

        Set<YearMonth> impMonths = state.importedMonths();

        Map<YearMonth, Double> monthlyExpenses = expenseList.stream()
            .filter(e -> state.countsAsSpend(e, impMonths))
            .collect(Collectors.groupingBy(
                expense -> YearMonth.from(expense.getDate()),
                Collectors.summingDouble(this::toBase)));

        Map<YearMonth, Double> monthlyItemIncome = expenseList.stream()
            .filter(expense -> !expense.isExcluded() && expense.isIncome())
            .collect(Collectors.groupingBy(
                expense -> YearMonth.from(expense.getDate()),
                Collectors.summingDouble(this::toBase)));

        YearMonth[] range = new YearMonth[2];
        state.getMonthRange(chartPeriod, selectedYear, selectedYearMonth, now, monthlyExpenses, range);
        YearMonth rangeStart = range[0];
        YearMonth rangeEnd = range[1];

        boolean sameYear = rangeStart.getYear() == rangeEnd.getYear();

        Map<YearMonth, Double> incomes = state.getIncomes();
        double recurringIncome = state.getRecurringIncome();

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
        UIUtils.styleChartLegend(incomeVsExpensesChart, "#4CAF50", "#FF6F61");
    }

    // ======================== BUDGET VS ACTUAL CHART ========================

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

        ObservableList<Expense> expenseList = state.getExpenseList();
        Map<String, Double> budgets = state.getBudgets();

        // Only include categories that have a budget
        Set<YearMonth> budgetImportedMonths = state.importedMonths();
        Map<String, Double> actualByCategory = expenseList.stream()
            .filter(e -> state.countsAsSpend(e, budgetImportedMonths))
            .filter(e -> YearMonth.from(e.getDate()).equals(selectedYearMonth))
            .collect(Collectors.groupingBy(Expense::getCategory, Collectors.summingDouble(this::toBase)));

        List<String> budgetedCategories = budgets.entrySet().stream()
            .filter(e -> e.getValue() > 0)
            .map(Map.Entry::getKey)
            .sorted()
            .collect(Collectors.toList());

        if (budgetedCategories.isEmpty()) {
            budgetVsActualSubtitle.setText("");
            budgetVsActualChart.setVisible(false);
            budgetEmptyOverlay.setVisible(true);
            budgetEmptyOverlay.setManaged(true);
            return;
        }

        budgetVsActualChart.setVisible(true);
        budgetEmptyOverlay.setVisible(false);
        budgetEmptyOverlay.setManaged(false);

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
        UIUtils.styleChartLegend(budgetVsActualChart, "#5C6BC0", "#4CAF50");
    }

    // ======================== CUMULATIVE SPENDING CHART ========================

    private void updateCumulativeSpendingChart(YearMonth selectedYearMonth) {
        NumberAxis xAxis = (NumberAxis) cumulativeSpendingChart.getXAxis();
        NumberAxis yAxis = (NumberAxis) cumulativeSpendingChart.getYAxis();
        cumulativeSpendingChart.setAnimated(false);
        cumulativeSpendingChart.getData().clear();

        cumulativeSubtitle.setText(selectedYearMonth.getMonth()
            .getDisplayName(TextStyle.FULL, Locale.getDefault()) + " " + selectedYearMonth.getYear());

        ObservableList<Expense> expenseList = state.getExpenseList();
        Map<String, Double> budgets = state.getBudgets();

        int daysInMonth = selectedYearMonth.lengthOfMonth();
        xAxis.setAutoRanging(false);
        xAxis.setLowerBound(1);
        xAxis.setUpperBound(daysInMonth);
        xAxis.setTickUnit(daysInMonth <= 15 ? 1 : 5);
        xAxis.setLabel("Day of Month");
        yAxis.setAutoRanging(true);
        yAxis.setLabel("Amount");

        Set<YearMonth> cumImportedMonths = state.importedMonths();
        Map<Integer, Double> dailyTotals = expenseList.stream()
            .filter(e -> state.countsAsSpend(e, cumImportedMonths))
            .filter(e -> YearMonth.from(e.getDate()).equals(selectedYearMonth))
            .collect(Collectors.groupingBy(
                e -> e.getDate().getDayOfMonth(),
                Collectors.summingDouble(this::toBase)));

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
            UIUtils.styleChartLegend(cumulativeSpendingChart, "#5C6BC0", "#FF9800");
        } else {
            UIUtils.styleChartLegend(cumulativeSpendingChart, "#5C6BC0");
        }
    }

    // ======================== CATEGORY TREND CHART ========================

    private void updateCategoryTrendChart(String chartPeriod, int selectedYear,
                                           YearMonth selectedYearMonth, YearMonth now) {
        CategoryAxis xAxis = (CategoryAxis) categoryTrendChart.getXAxis();
        xAxis.setAnimated(false);
        categoryTrendChart.setAnimated(false);
        categoryTrendChart.getData().clear();
        xAxis.getCategories().clear();
        xAxis.setAutoRanging(false);

        ObservableList<Expense> expenseList = state.getExpenseList();

        Set<YearMonth> catTrendImportedMonths = state.importedMonths();
        Map<YearMonth, Double> monthlyTotals = expenseList.stream()
            .filter(e -> state.countsAsSpend(e, catTrendImportedMonths))
            .collect(Collectors.groupingBy(
                e -> YearMonth.from(e.getDate()),
                Collectors.summingDouble(this::toBase)));

        YearMonth[] range = new YearMonth[2];
        state.getMonthRange(chartPeriod, selectedYear, selectedYearMonth, now, monthlyTotals, range);
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
        List<Expense> rangeExpenses = state.filterExpensesByPeriod(chartPeriod, selectedYear, selectedYearMonth, now);
        Map<String, Double> categoryTotalMap = rangeExpenses.stream()
            .collect(Collectors.groupingBy(Expense::getCategory, Collectors.summingDouble(this::toBase)));

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
                    Collectors.summingDouble(this::toBase))));

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
            .map(UIUtils::getCategoryColor).toArray(String[]::new);

        Platform.runLater(() -> {
            for (XYChart.Series<String, Number> s : categoryTrendChart.getData()) {
                String color = UIUtils.getCategoryColor(s.getName());
                if (s.getNode() != null) {
                    Node fill = s.getNode().lookup(".chart-series-area-fill");
                    Node line = s.getNode().lookup(".chart-series-area-line");
                    if (fill != null) fill.setStyle("-fx-fill: " + color + "44;");
                    if (line != null) line.setStyle("-fx-stroke: " + color + ";");
                }
            }
        });
        UIUtils.styleChartLegend(categoryTrendChart, catColors);

        categoryTrendChart.setAnimated(true);
    }

    // ======================== YEAR OVER YEAR CHART ========================

    private void updateYearOverYearChart(int selectedYear) {
        CategoryAxis xAxis = (CategoryAxis) yearOverYearChart.getXAxis();
        xAxis.setAnimated(false);
        yearOverYearChart.setAnimated(false);
        yearOverYearChart.getData().clear();
        xAxis.getCategories().clear();
        xAxis.setAutoRanging(false);

        int prevYear = selectedYear - 1;
        yearOverYearChart.setTitle(selectedYear + " vs " + prevYear);

        ObservableList<Expense> expenseList = state.getExpenseList();

        List<String> monthLabels = new ArrayList<>();
        for (Month m : Month.values()) {
            monthLabels.add(m.getDisplayName(TextStyle.SHORT, Locale.getDefault()));
        }

        Set<YearMonth> yoyImportedMonths = state.importedMonths();
        Map<YearMonth, Double> monthlyTotals = expenseList.stream()
            .filter(e -> state.countsAsSpend(e, yoyImportedMonths))
            .filter(e -> e.getDate().getYear() == selectedYear || e.getDate().getYear() == prevYear)
            .collect(Collectors.groupingBy(
                e -> YearMonth.from(e.getDate()),
                Collectors.summingDouble(this::toBase)));

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
        UIUtils.styleChartLegend(yearOverYearChart, "#5C6BC0", "#A0A0A0");

        yearOverYearChart.setAnimated(true);
    }

    // ======================== RECURRING VS ONE-TIME CHART ========================

    private void updateRecurringVsOneTimeChart(String chartPeriod, int selectedYear,
                                                YearMonth selectedYearMonth, YearMonth now) {
        ObservableList<Expense> expenseList = state.getExpenseList();
        ExpenseManager manager = state.getManager();

        // Build set of recurring description|category keys from base recurring expenses
        Set<String> recurringDescs = manager.getBaseRecurringExpenses().stream()
            .map(r -> (r.getDescription() != null ? r.getDescription().toLowerCase().trim() : "") + "|" + r.getCategory().toLowerCase())
            .collect(Collectors.toSet());

        List<Expense> allPeriodExpenses = expenseList.stream()
            .filter(e -> !e.isExcluded() && !e.isIncome() && !e.isRefund())
            .filter(e -> state.matchesPeriod(e, chartPeriod, selectedYear, selectedYearMonth, now))
            .collect(Collectors.toList());

        // For months with imported data, prefer actual imports over projections
        // to avoid double-counting. For months without imports, use projections.
        Set<YearMonth> importedMonths = state.importedMonths();

        double recurringTotal = 0;
        double oneTimeTotal = 0;
        for (Expense e : allPeriodExpenses) {
            YearMonth ym = YearMonth.from(e.getDate());
            boolean monthHasImports = importedMonths.contains(ym);

            if (e.getRecurringId() != null) {
                if (monthHasImports) {
                    // Month has imports — skip projection, but only if there's an actual
                    // imported expense matching this recurring template (otherwise still count it)
                    boolean hasImportedMatch = allPeriodExpenses.stream()
                        .anyMatch(imp -> imp.getRecurringId() == null && imp.getImportId() != null
                            && YearMonth.from(imp.getDate()).equals(ym)
                            && recurringDescs.contains(
                                (imp.getDescription() != null ? imp.getDescription().toLowerCase().trim() : "")
                                + "|" + imp.getCategory().toLowerCase())
                            && Math.abs(toBase(imp) - toBase(e)) <= toBase(e) * 0.15);
                    if (!hasImportedMatch) {
                        recurringTotal += toBase(e);
                    }
                } else {
                    recurringTotal += toBase(e);
                }
            } else {
                // Real expense — check if it matches a recurring template
                String key = (e.getDescription() != null ? e.getDescription().toLowerCase().trim() : "") + "|" + e.getCategory().toLowerCase();
                if (recurringDescs.contains(key)) {
                    recurringTotal += toBase(e);
                } else {
                    oneTimeTotal += toBase(e);
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
                    newNode.setCursor(Cursor.HAND);
                    Tooltip t = new Tooltip("Recurring: " + fmt(finalRecurringTotal)
                        + " (" + String.format("%.1f%%", recurPct) + ")");
                    t.setStyle("-fx-font-size: 13px;");
                    Tooltip.install(newNode, t);
                    newNode.setOnMouseClicked(event -> {
                        List<Expense> filtered = allPeriodExpenses.stream()
                            .filter(e -> e.getRecurringId() != null)
                            .collect(Collectors.toList());
                        DrillDownDialog.show(state.getStage(), "Recurring Expenses", filtered, state.getCurrencySymbol(), state.getCurrencyManager());
                    });
                }
            });

            PieChart.Data oneData = new PieChart.Data(
                "One-Time (" + String.format("%.0f%%", onePct) + ")", finalOneTimeTotal);
            oneData.nodeProperty().addListener((obs, oldNode, newNode) -> {
                if (newNode != null) {
                    newNode.setStyle("-fx-pie-color: #45AAF2;");
                    newNode.setCursor(Cursor.HAND);
                    Tooltip t = new Tooltip("One-Time: " + fmt(finalOneTimeTotal)
                        + " (" + String.format("%.1f%%", onePct) + ")");
                    t.setStyle("-fx-font-size: 13px;");
                    Tooltip.install(newNode, t);
                    newNode.setOnMouseClicked(event -> {
                        List<Expense> filtered = allPeriodExpenses.stream()
                            .filter(e -> e.getRecurringId() == null)
                            .collect(Collectors.toList());
                        DrillDownDialog.show(state.getStage(), "One-Time Expenses", filtered, state.getCurrencySymbol(), state.getCurrencyManager());
                    });
                }
            });

            data.addAll(recurData, oneData);
        }

        recurringVsOneTimeChart.setData(data);
        recurringVsOneTimeChart.setAnimated(true);
        if (grandTotal > 0) {
            UIUtils.styleChartLegend(recurringVsOneTimeChart, "#FF9800", "#45AAF2");
        }
    }

    // ======================== CASH FLOW CALENDAR ========================

    private void updateCashFlowCalendar() {
        cashFlowContent.getChildren().clear();
        CashFlowCalendarView calendar = new CashFlowCalendarView(state);
        cashFlowContent.getChildren().add(calendar);
    }

    // ======================== PROJECTIONS ========================

    private void updateProjections() {
        state.setProjectionsNeedUpdate(false);

        ExpenseManager manager = state.getManager();
        Map<YearMonth, Double> incomes = state.getIncomes();
        double recurringIncome = state.getRecurringIncome();
        Map<String, Double> budgets = state.getBudgets();
        ProjectionEngine projectionEngine = state.getProjectionEngine();

        // Build input snapshot
        ProjectionEngine.ProjectionInput input = new ProjectionEngine.ProjectionInput(
                new ArrayList<>(manager.getExpenses()),
                new ArrayList<>(manager.getBaseRecurringExpenses()),
                new HashMap<>(incomes),
                recurringIncome,
                new HashMap<>(budgets),
                state.getCurrencyManager()
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
        String fromStr = firstMonth.month.getMonth().getDisplayName(TextStyle.FULL, Locale.getDefault()) + " " + firstMonth.month.getYear();
        String toStr = lastMonth.month.getMonth().getDisplayName(TextStyle.FULL, Locale.getDefault()) + " " + lastMonth.month.getYear();
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
        String monthName = first.month.getMonth().getDisplayName(TextStyle.SHORT, Locale.getDefault());

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

        UIUtils.animateChartFadeIn(projOutlookChart);
        UIUtils.animateChartFadeIn(projBalanceChart);
        UIUtils.animateChartFadeIn(projCategoryChart);
    }

    // ======================== PROJECTION OUTLOOK CHART ========================

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
            String label = mp.month.getMonth().getDisplayName(TextStyle.SHORT, Locale.getDefault());
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

    // ======================== PROJECTION BALANCE CHART ========================

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
            String label = mp.month.getMonth().getDisplayName(TextStyle.SHORT, Locale.getDefault());
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
            UIUtils.styleChartLegend(projBalanceChart, "#FF6F61", "#5C6BC0", "#4CAF50");
        });
    }

    // ======================== PROJECTION CATEGORY CHART ========================

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
                    String color = UIUtils.getCategoryColor(data.getXValue());
                    data.getNode().setStyle("-fx-bar-fill: " + color + ";");
                    Tooltip t = new Tooltip(data.getXValue() + " (Recurring): " + fmt(data.getYValue().doubleValue()) + "/month");
                    t.setStyle("-fx-font-size: 13px;");
                    Tooltip.install(data.getNode(), t);
                }
            }
            for (XYChart.Data<String, Number> data : variableSeries.getData()) {
                if (data.getNode() != null) {
                    String color = UIUtils.getCategoryColor(data.getXValue());
                    data.getNode().setStyle("-fx-bar-fill: " + color + "; -fx-opacity: 0.5;");
                    Tooltip t = new Tooltip(data.getXValue() + " (Variable): " + fmt(data.getYValue().doubleValue()) + "/month");
                    t.setStyle("-fx-font-size: 13px;");
                    Tooltip.install(data.getNode(), t);
                }
            }
        });
    }

    // ======================== HELPER METHODS ========================

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

    public int getSelectedTabIndex() {
        return analyticsTabPane.getSelectionModel().getSelectedIndex();
    }

    public void selectTab(int index) {
        if (index >= 0 && index < analyticsTabPane.getTabs().size()) {
            analyticsTabPane.getSelectionModel().select(index);
        }
    }

    private String fmt(double amount) {
        return UIUtils.fmt(amount, state.getCurrencySymbol());
    }
}
