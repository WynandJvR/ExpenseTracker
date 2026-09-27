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
    @FXML private FlowPane categoryTrendLegend;
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
    /** Most series/slices a category chart shows; beyond this it shows the top 7 plus "Other". */
    static final int MAX_SERIES = UIUtils.CATEGORY_COLORS.length;
    static final String OTHER = "Other";

    // --- State ---
    private SharedState state;
    private boolean initialized = false;
    private List<Expense> lastChartExpenses = Collections.emptyList();

    /**
     * Colour slots for the session. The seven biggest categories of all time own slots 0..6
     * permanently, so a category keeps its colour when the period changes. Other categories
     * borrow a free slot when they make a chart's top seven and keep it while it stays free.
     */
    private final Map<String, Integer> permanentSlots = new HashMap<>();
    private final Map<String, Integer> borrowedSlots = new HashMap<>();

    private double toBase(Expense e) {
        return state.getCurrencyManager().toBase(e.getAmount(), e.getCurrency());
    }

    @FXML
    public void initialize() {
        // Empty — real setup in init(SharedState)
    }

    public void init(SharedState state) {
        this.state = state;
        // A profile switch re-inits: colour slots belong to the old profile's categories.
        permanentSlots.clear();
        borrowedSlots.clear();
        if (initialized) return;
        initialized = true;

        chartPeriodCombo.setItems(FXCollections.observableArrayList(
            "All Time", "Last 12 Months", "Last 6 Months", "By Year", "By Month"));
        chartPeriodCombo.valueProperty().bindBidirectional(state.chartPeriodProperty());

        chartPeriodCombo.valueProperty().addListener((obs, oldVal, newVal) -> updateCharts());

        // Hover read-outs: point anywhere over a column/day to see every value there.
        java.util.function.Function<Double, String> money = this::fmt;
        String drill = "Click a coloured block to see its transactions";
        ChartHover.install(monthlyTrendChart, money, null, drill);
        ChartHover.install(incomeVsExpensesChart, money);
        ChartHover.install(budgetVsActualChart, money);
        ChartHover.install(cumulativeSpendingChart, money, d -> "Day " + d.intValue() + " of the month", null);
        ChartHover.install(categoryTrendChart, money);
        ChartHover.install(yearOverYearChart, money);
        ChartHover.install(projOutlookChart, money);
        ChartHover.install(projBalanceChart, money);
        ChartHover.install(projCategoryChart, money);

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
        Tab open = analyticsTabPane.getSelectionModel().getSelectedItem();
        if (open == cashFlowTab) updateCashFlowCalendar();
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

        // Spend items plus refunds: every chart nets refunds against spend per category.
        List<Expense> chartExpenses = state.filterSpendAndRefundsByPeriod(chartPeriod, selectedYear, selectedYearMonth, now);
        this.lastChartExpenses = chartExpenses;

        updateCategoryPieChart(chartExpenses);
        updateMonthlyTrendBarChart(chartExpenses, chartPeriod, selectedYear, selectedMonth, selectedYearMonth, now);
        updateIncomeVsExpensesChart(chartPeriod, selectedYear, selectedYearMonth, now);
        updateBudgetVsActualChart(chartPeriod, selectedYear, selectedYearMonth, now);
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

        Map<String, Double> categoryMap = state.spendByCategory(chartExpenses);
        if (categoryMap.isEmpty()) {
            categoryChart.setData(FXCollections.observableArrayList());
            categoryChart.setTitle("Expenses by Category — No data for this period");
            return;
        }

        double pieTotal = categoryMap.values().stream().mapToDouble(Double::doubleValue).sum();

        // At most 8 slices: the top 7 categories plus "Other", coloured per category.
        List<String> shown = shownCategories(categoryMap);
        Map<String, Double> folded = fold(categoryMap, shown);
        List<String> slices = new ArrayList<>(shown);
        if (needsOther(categoryMap, shown)) slices.add(OTHER);
        Map<String, String> colors = colorsFor(slices);
        Set<String> topCategories = new HashSet<>(shown);

        ObservableList<PieChart.Data> pieChartData = FXCollections.observableArrayList();
        for (String category : slices) {
            final boolean isOther = OTHER.equals(category) && !topCategories.contains(OTHER);
            final String color = colors.get(category);
            final double amount = folded.getOrDefault(category, 0.0);
            if (amount <= 0) continue;
            double pct = pieTotal > 0 ? (amount / pieTotal) * 100 : 0;
            PieChart.Data data = new PieChart.Data(
                category + " (" + String.format("%.0f%%", pct) + ")",
                amount);
            data.nodeProperty().addListener((obs, oldNode, newNode) -> {
                if (newNode != null) {
                    newNode.setStyle("-fx-pie-color: " + color + ";");
                    newNode.setCursor(Cursor.HAND);
                    tip(newNode, category + ": " + fmt(amount)
                        + " (" + String.format("%.1f%%", pct) + ")");
                    newNode.setOnMouseClicked(event -> {
                        List<Expense> filtered = lastChartExpenses.stream()
                            .filter(e -> isOther ? !topCategories.contains(e.getCategory())
                                                 : e.getCategory().equals(category))
                            .collect(Collectors.toList());
                        DrillDownDialog.show(state.getStage(), isOther ? "Other Categories" : "Category: " + category,
                            filtered, state.getCurrencySymbol(), state.getCurrencyManager());
                    });
                }
            });
            pieChartData.add(data);
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

        // Map: barLabel -> (category -> amount)
        Map<String, Map<String, Double>> barCategoryTotals = new LinkedHashMap<>();

        ObservableList<Expense> expenseList = state.getExpenseList();

        // Items behind each bar, so a drill-down lists exactly what the bar sums.
        Map<String, List<Expense>> barItems = new LinkedHashMap<>();

        if (isDailyMode) {
            // The clamping rule works on month x category cells, so a refund later in the
            // month must offset spend in an earlier week. Each clamped cell is apportioned
            // across the weeks by that week's gross spend, so the weeks sum to the month.
            List<Expense> monthItems = chartExpenses.stream()
                .filter(e -> e.getDate() != null && YearMonth.from(e.getDate()).equals(selectedYearMonth))
                .collect(Collectors.toList());
            Map<String, Double> monthCells = state.netSpendByMonthAndCategory(monthItems)
                .getOrDefault(selectedYearMonth, Collections.emptyMap());
            int daysInMonth = selectedYearMonth.lengthOfMonth();
            List<List<Expense>> weeks = new ArrayList<>();
            for (int weekStart = 1; weekStart <= daysInMonth; weekStart += 7) {
                int weekEnd = Math.min(weekStart + 6, daysInMonth);
                String label = weekStart + "\u2013" + weekEnd;
                barLabels.add(label);

                final int ws = weekStart;
                final int we = weekEnd;
                List<Expense> items = monthItems.stream()
                    .filter(e -> e.getDate().getDayOfMonth() >= ws && e.getDate().getDayOfMonth() <= we)
                    .collect(Collectors.toList());
                weeks.add(items);
                barItems.put(label, items);
            }
            List<Map<String, Double>> weekTotals = apportionToWeeks(monthCells, weeks, state::spendContribution);
            for (int i = 0; i < barLabels.size(); i++) {
                barCategoryTotals.put(barLabels.get(i), weekTotals.get(i));
            }
            monthlyTrendChart.setTitle("Weekly Spending \u2014 "
                + selectedMonth.getDisplayName(TextStyle.FULL, Locale.getDefault()) + " " + selectedYear);
        } else {
            List<Expense> spendItems = expenseList.stream()
                .filter(e -> state.countsAsSpend(e) || SharedState.isRefundCredit(e))
                .collect(Collectors.toList());
            Map<YearMonth, Double> monthlyTotals = state.netSpendByMonth(spendItems);
            Map<YearMonth, List<Expense>> itemsByMonth = spendItems.stream()
                .collect(Collectors.groupingBy(e -> YearMonth.from(e.getDate())));

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

                List<Expense> items = itemsByMonth.getOrDefault(ym, Collections.emptyList());
                Map<String, Double> catTotals = state.spendByCategory(items);
                barItems.put(label, items);
                barCategoryTotals.put(label, catTotals);
                cursor = cursor.plusMonths(1);
            }
            monthlyTrendChart.setTitle("Monthly Trend");
        }

        // Rank categories by their total across the bars; show the top 7 + "Other" at most
        // (8 series), so the stack stays readable. Only the grouping changes, not the sums.
        Map<String, Double> rangeTotals = new HashMap<>();
        barCategoryTotals.values().forEach(m -> m.forEach((c, v) -> rangeTotals.merge(c, v, Double::sum)));
        List<String> shown = shownCategories(rangeTotals);
        Set<String> shownSet = new HashSet<>(shown);
        List<String> seriesCategories = new ArrayList<>(shown);
        if (needsOther(rangeTotals, shown)) seriesCategories.add(OTHER);
        Map<String, String> colors = colorsFor(seriesCategories);
        Map<String, Map<String, Double>> foldedTotals = new HashMap<>();
        barCategoryTotals.forEach((label, m) -> foldedTotals.put(label, fold(m, shown)));

        // One series per category, biggest at the bottom of the stack, "Other" on top
        for (String category : seriesCategories) {
            XYChart.Series<String, Number> series = new XYChart.Series<>();
            series.setName(category);
            final String color = colors.get(category);
            final boolean isOther = OTHER.equals(category) && !shownSet.contains(OTHER);

            for (String label : barLabels) {
                double amount = foldedTotals.get(label).getOrDefault(category, 0.0);
                XYChart.Data<String, Number> data = new XYChart.Data<>(label, amount);
                final String barLabel = label;
                final double amt = amount;
                data.nodeProperty().addListener((obs, oldNode, newNode) -> {
                    if (newNode != null) {
                        newNode.setStyle("-fx-bar-fill: " + color + ";");
                        if (amt > 0) {
                            newNode.setCursor(Cursor.HAND);
                            newNode.setOnMouseClicked(event -> {
                                List<Expense> filtered = barItems.getOrDefault(barLabel, Collections.emptyList()).stream()
                                    .filter(e -> isOther ? !shownSet.contains(e.getCategory())
                                                         : e.getCategory().equals(category))
                                    .collect(Collectors.toList());
                                DrillDownDialog.show(state.getStage(),
                                    (isOther ? "Other categories" : category) + " (" + barLabel + ")",
                                    filtered, state.getCurrencySymbol(), state.getCurrencyManager());
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

        // Custom legend below the chart, in stack order
        trendChartLegend.getChildren().clear();
        for (String category : seriesCategories) {
            trendChartLegend.getChildren().add(legendItem(category, colors.get(category)));
        }

        monthlyTrendChart.setAnimated(true);
    }

    /**
     * Splits each clamped month x category cell across the weeks in proportion to each week's
     * gross spend (positive contributions) in that category, so the week bars add up to the
     * month's clamped net spend. A cell with no gross spend (refunds only) is already 0.
     */
    static List<Map<String, Double>> apportionToWeeks(Map<String, Double> monthCells,
                                                      List<List<Expense>> weeks,
                                                      java.util.function.ToDoubleFunction<Expense> contribution) {
        List<Map<String, Double>> grossByWeek = new ArrayList<>();
        Map<String, Double> grossTotal = new HashMap<>();
        for (List<Expense> week : weeks) {
            Map<String, Double> gross = new HashMap<>();
            for (Expense e : week) {
                double c = contribution.applyAsDouble(e);
                if (c > 0) gross.merge(e.getCategory(), c, Double::sum);
            }
            gross.forEach((cat, v) -> grossTotal.merge(cat, v, Double::sum));
            grossByWeek.add(gross);
        }
        List<Map<String, Double>> result = new ArrayList<>();
        for (Map<String, Double> gross : grossByWeek) {
            Map<String, Double> week = new HashMap<>();
            gross.forEach((cat, g) -> {
                double cell = monthCells.getOrDefault(cat, 0.0);
                double total = grossTotal.getOrDefault(cat, 0.0);
                if (cell > 0 && total > 0) week.put(cat, cell * g / total);
            });
            result.add(week);
        }
        return result;
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

        Map<YearMonth, Double> monthlyExpenses = state.netSpendByMonth(expenseList);

        YearMonth[] range = new YearMonth[2];
        state.getMonthRange(chartPeriod, selectedYear, selectedYearMonth, now, monthlyExpenses, range);
        YearMonth rangeStart = range[0];
        // Future months have no actual expenses yet; showing them as 0 next to planned
        // income is misleading, so the chart stops at the current month.
        YearMonth rangeEnd = range[1].isAfter(now) ? now : range[1];
        if (rangeStart.isAfter(rangeEnd)) rangeStart = rangeEnd;

        boolean sameYear = rangeStart.getYear() == rangeEnd.getYear();

        List<String> labels = new ArrayList<>();
        XYChart.Series<String, Number> incomeSeries = new XYChart.Series<>();
        incomeSeries.setName("Income");
        XYChart.Series<String, Number> expenseSeries = new XYChart.Series<>();
        expenseSeries.setName("Expenses");

        YearMonth cursor = rangeStart;
        while (!cursor.isAfter(rangeEnd)) {
            final YearMonth ym = cursor;
            // Months with no actual data (no spend, no income transactions) are left out
            // rather than drawn as 0 spend next to planned income.
            double expenseAmt = monthlyExpenses.getOrDefault(ym, 0.0);
            if (expenseAmt <= 0 && state.actualIncome(ym) <= 0) {
                cursor = cursor.plusMonths(1);
                continue;
            }
            String label = ym.getMonth().getDisplayName(TextStyle.SHORT, Locale.getDefault())
                + (sameYear ? "" : " '" + String.format("%02d", ym.getYear() % 100));
            labels.add(label);

            double incomeAmt = state.incomeForMonth(ym);

            final double fIncome = incomeAmt;
            final double fExpense = expenseAmt;

            XYChart.Data<String, Number> incomeData = new XYChart.Data<>(label, incomeAmt);
            incomeSeries.getData().add(incomeData);

            XYChart.Data<String, Number> expenseData = new XYChart.Data<>(label, expenseAmt);
            expenseSeries.getData().add(expenseData);

            cursor = cursor.plusMonths(1);
        }

        xAxis.setCategories(FXCollections.observableArrayList(labels));
        incomeVsExpensesChart.getData().addAll(incomeSeries, expenseSeries);
        incomeVsExpensesChart.setAnimated(true);
    }

    // ======================== BUDGET VS ACTUAL CHART ========================

    private void updateBudgetVsActualChart(String chartPeriod, int selectedYear,
                                           YearMonth selectedYearMonth, YearMonth now) {
        CategoryAxis xAxis = (CategoryAxis) budgetVsActualChart.getXAxis();
        xAxis.setAnimated(false);
        budgetVsActualChart.setAnimated(false);
        budgetVsActualChart.getData().clear();
        xAxis.getCategories().clear();
        xAxis.setAutoRanging(false);

        Map<String, Double> budgets = state.getBudgets();

        // Budgets are monthly. For multi-month periods compare the average monthly
        // actual spend over the period's months against the monthly budget.
        List<Expense> periodItems = state.filterSpendAndRefundsByPeriod(chartPeriod, selectedYear, selectedYearMonth, now);
        Map<String, Double> actualByCategory;
        String subtitle;
        if ("By Month".equals(chartPeriod)) {
            actualByCategory = state.spendByCategory(periodItems);
            subtitle = "Showing "
                + selectedYearMonth.getMonth().getDisplayName(TextStyle.FULL, Locale.getDefault())
                + " " + selectedYearMonth.getYear();
        } else {
            List<Expense> averaged = completeMonthItems(periodItems, now);
            long months = monthsWithData(averaged, now);
            actualByCategory = new HashMap<>();
            final long m = months;
            state.spendByCategory(averaged).forEach((k, v) -> actualByCategory.put(k, v / m));
            subtitle = "Average per month — " + chartPeriod + " (" + months + " months)";
        }
        budgetVsActualSubtitle.setText(subtitle);

        List<String> budgetedCategories = budgets.entrySet().stream()
            .filter(e -> e.getValue() > 0)
            .map(Map.Entry::getKey)
            .sorted()
            .collect(Collectors.toList());

        if (budgetedCategories.isEmpty()) {
            budgetVsActualSubtitle.setText("");
            budgetVsActualChart.setVisible(false);
            budgetVsActualChart.setManaged(false);
            budgetEmptyOverlay.setVisible(true);
            budgetEmptyOverlay.setManaged(true);
            return;
        }

        budgetVsActualChart.setVisible(true);
        budgetVsActualChart.setManaged(true);
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
            budgetSeries.getData().add(bData);

            XYChart.Data<String, Number> aData = new XYChart.Data<>(category, actualAmt);
            aData.nodeProperty().addListener((obs, oldNode, newNode) -> {
                if (newNode != null) {
                    setExclusiveClass(newNode, fActual > fBudget ? "actual-over" : "actual-under",
                        "actual-over", "actual-under");
                }
            });
            actualSeries.getData().add(aData);
        }

        xAxis.setCategories(FXCollections.observableArrayList(labels));
        budgetVsActualChart.getData().addAll(budgetSeries, actualSeries);
        budgetVsActualChart.setAnimated(true);
    }

    /**
     * Number of months to average over: the months in {@code items} that actually have
     * data, excluding the current (partial) month unless it is the only one. At least 1.
     */
    static long monthsWithData(Collection<? extends Expense> items, YearMonth now) {
        Set<YearMonth> months = new HashSet<>();
        for (Expense e : items) {
            if (e != null && e.getDate() != null) months.add(YearMonth.from(e.getDate()));
        }
        if (months.size() > 1) months.remove(now);
        return Math.max(1, months.size());
    }

    /** Drops the current (partial) month's items unless that month is the only one with data. */
    static List<Expense> completeMonthItems(List<Expense> items, YearMonth now) {
        boolean hasOtherMonths = items.stream()
            .anyMatch(e -> e.getDate() != null && !YearMonth.from(e.getDate()).equals(now));
        if (!hasOtherMonths) return items;
        return items.stream()
            .filter(e -> e.getDate() != null && !YearMonth.from(e.getDate()).equals(now))
            .collect(Collectors.toList());
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

        // Daily net spend (refunds reduce the running total on the day they land).
        // Cumulative spending is inherently a single-month view, so it follows the
        // selected month regardless of the period dropdown.
        // Same clamping rule as every other view: each category's running net is floored
        // at 0, so the month-end value equals the month's net spend.
        Map<Integer, Map<String, Double>> dailyByCategory = new HashMap<>();
        for (Expense e : expenseList) {
            if (!YearMonth.from(e.getDate()).equals(selectedYearMonth)) continue;
            double c = state.spendContribution(e);
            if (c == 0) continue;
            dailyByCategory.computeIfAbsent(e.getDate().getDayOfMonth(), k -> new HashMap<>())
                .merge(e.getCategory(), c, Double::sum);
        }

        // Actual cumulative line
        XYChart.Series<Number, Number> actualSeries = new XYChart.Series<>();
        actualSeries.setName("Actual");
        Map<String, Double> categoryRunning = new HashMap<>();
        double runningTotal = 0;
        for (int day = 1; day <= daysInMonth; day++) {
            Map<String, Double> today = dailyByCategory.get(day);
            if (today != null) {
                today.forEach((cat, v) -> categoryRunning.merge(cat, v, Double::sum));
                runningTotal = categoryRunning.values().stream().mapToDouble(v -> Math.max(0, v)).sum();
            }
            final double total = runningTotal;
            final int d = day;
            XYChart.Data<Number, Number> data = new XYChart.Data<>(day, runningTotal);
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
            cumulativeSpendingChart.getData().add(budgetLine);
        }

        // Line colours come from .cumulative-chart in styles.css (actual = series 0, budget = series 1)
        cumulativeSpendingChart.setCreateSymbols(false);
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

        Map<YearMonth, Double> monthlyTotals = state.netSpendByMonth(expenseList);

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
        List<Expense> rangeExpenses = state.filterSpendAndRefundsByPeriod(chartPeriod, selectedYear, selectedYearMonth, now);
        Map<String, Double> categoryTotalMap = state.spendByCategory(rangeExpenses);

        // Top 7 categories + "Other" at most (8 series), so the stacked areas stay readable
        List<String> topCategories = shownCategories(categoryTotalMap);
        boolean hasOther = needsOther(categoryTotalMap, topCategories);

        // Build per-month per-category map
        Map<YearMonth, Map<String, Double>> monthCategoryMap = new HashMap<>();
        state.netSpendByMonthAndCategory(rangeExpenses).forEach((ym, cats) -> {
            monthCategoryMap.put(ym, fold(cats, topCategories));
        });

        List<String> allCategories = new ArrayList<>(topCategories);
        if (hasOther) allCategories.add(OTHER);

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

        // Apply category colours (stable per category) to areas and legend
        Map<String, String> colors = colorsFor(allCategories);

        Platform.runLater(() -> {
            for (XYChart.Series<String, Number> s : categoryTrendChart.getData()) {
                String color = colors.getOrDefault(s.getName(), UIUtils.OTHER_COLOR);
                if (s.getNode() != null) {
                    Node fill = s.getNode().lookup(".chart-series-area-fill");
                    Node line = s.getNode().lookup(".chart-series-area-line");
                    if (fill != null) fill.setStyle("-fx-fill: " + color + "44;");
                    if (line != null) line.setStyle("-fx-stroke: " + color + ";");
                }
            }
        });
        categoryTrendLegend.getChildren().clear();
        for (String category : allCategories) {
            categoryTrendLegend.getChildren().add(legendItem(category, colors.get(category)));
        }

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

        Map<YearMonth, Double> monthlyTotals = state.netSpendByMonth(expenseList.stream()
            .filter(e -> e.getDate().getYear() == selectedYear || e.getDate().getYear() == prevYear)
            .collect(Collectors.toList()));

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
            // Months still to come have no spend yet; end the line at the current month.
            if (!YearMonth.of(selectedYear, m).isAfter(YearMonth.now())) currentSeries.getData().add(cData);

            XYChart.Data<String, Number> pData = new XYChart.Data<>(label, prevAmt);
            prevSeries.getData().add(pData);
        }

        xAxis.setCategories(FXCollections.observableArrayList(monthLabels));
        yearOverYearChart.getData().addAll(currentSeries, prevSeries);

        // Line colours come from .yoy-chart in styles.css (selected year = series 0)

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

        // Same spend source as every other chart (countsAsSpend + refund netting), so the
        // two slices sum to the category pie total for the period.
        List<Expense> allPeriodExpenses = state.filterSpendAndRefundsByPeriod(
            chartPeriod, selectedYear, selectedYearMonth, now);

        List<Expense> recurringItems = new ArrayList<>();
        List<Expense> oneTimeItems = new ArrayList<>();
        for (Expense e : allPeriodExpenses) {
            boolean recurring;
            if (e.getRecurringId() != null) {
                recurring = true;
            } else if (state.coversRecurring(e)) {
                // Imported transaction that stands in for a recurring occurrence
                recurring = true;
            } else {
                String key = (e.getDescription() != null ? e.getDescription().toLowerCase().trim() : "")
                    + "|" + e.getCategory().toLowerCase();
                recurring = recurringDescs.contains(key);
            }
            (recurring ? recurringItems : oneTimeItems).add(e);
        }

        // Capture as final for use in lambdas
        // Split the period's clamped net spend so the two slices add up to the pie total.
        Set<Expense> recurringSet = Collections.newSetFromMap(new IdentityHashMap<>());
        recurringSet.addAll(recurringItems);
        double[] split = state.splitNetSpend(allPeriodExpenses, recurringSet::contains);
        final double finalRecurringTotal = split[0];
        final double finalOneTimeTotal = split[1];
        double grandTotal = finalRecurringTotal + finalOneTimeTotal;

        ObservableList<PieChart.Data> data = FXCollections.observableArrayList();

        if (grandTotal > 0) {
            double recurPct = (finalRecurringTotal / grandTotal) * 100;
            double onePct = (finalOneTimeTotal / grandTotal) * 100;

            PieChart.Data recurData = new PieChart.Data(
                "Recurring (" + String.format("%.0f%%", recurPct) + ")", finalRecurringTotal);
            recurData.nodeProperty().addListener((obs, oldNode, newNode) -> {
                if (newNode != null) {
                    newNode.setStyle("-fx-pie-color: -c-cat-6;");
                    newNode.setCursor(Cursor.HAND);
                    tip(newNode, "Recurring: " + fmt(finalRecurringTotal)
                        + " (" + String.format("%.1f%%", recurPct) + ")");
                    newNode.setOnMouseClicked(event -> {
                        List<Expense> filtered = new ArrayList<>(recurringItems);
                        DrillDownDialog.show(state.getStage(), "Recurring Expenses", filtered, state.getCurrencySymbol(), state.getCurrencyManager());
                    });
                }
            });

            PieChart.Data oneData = new PieChart.Data(
                "One-Time (" + String.format("%.0f%%", onePct) + ")", finalOneTimeTotal);
            oneData.nodeProperty().addListener((obs, oldNode, newNode) -> {
                if (newNode != null) {
                    newNode.setStyle("-fx-pie-color: -c-series-in;");
                    newNode.setCursor(Cursor.HAND);
                    tip(newNode, "One-Time: " + fmt(finalOneTimeTotal)
                        + " (" + String.format("%.1f%%", onePct) + ")");
                    newNode.setOnMouseClicked(event -> {
                        List<Expense> filtered = new ArrayList<>(oneTimeItems);
                        DrillDownDialog.show(state.getStage(), "One-Time Expenses", filtered, state.getCurrencySymbol(), state.getCurrencyManager());
                    });
                }
            });

            // Zero slices are left out. Wedge colours are set per slice (a lone slice would
            // otherwise take slot 0's colour); the legend is only shown for two slices.
            if (finalRecurringTotal > 0) data.add(recurData);
            if (finalOneTimeTotal > 0) data.add(oneData);
        }

        recurringVsOneTimeChart.setLegendVisible(data.size() > 1);
        recurringVsOneTimeChart.setData(data);
        recurringVsOneTimeChart.setAnimated(true);
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
                state.getCurrencyManager(),
                manager.getOverrides()   // skipped / edited occurrences
        );

        ProjectionEngine.ProjectionResult result = projectionEngine.project(input, state.getRecurringCoverage());

        // Edge case: no data at all
        if (result.dataMonthsAvailable == 0 && input.recurringExpenses.isEmpty()) {
            projExpenses.setText("-");
            projExpensesSubtitle.setText("");
            projIncome.setText("-");
            projIncomeSubtitle.setText("");
            projNetSavings.setText("-");
            setExclusiveClass(projNetSavings, null, "kpi-good", "kpi-bad");
            projNetSavingsSubtitle.setText("");
            projCumulativeSavings.setText("-");
            setExclusiveClass(projCumulativeSavings, null, "kpi-good", "kpi-bad");
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
        setExclusiveClass(projNetSavings, first.netSavings >= 0 ? "kpi-good" : "kpi-bad", "kpi-good", "kpi-bad");
        projNetSavingsSubtitle.setText(first.netSavings >= 0 ? "Surplus" : "Deficit");

        double cumulativeSavings = result.monthProjections.stream()
                .mapToDouble(mp -> mp.netSavings).sum();
        projCumulativeSavings.setText(fmt(Math.abs(cumulativeSavings)));
        setExclusiveClass(projCumulativeSavings, cumulativeSavings >= 0 ? "kpi-good" : "kpi-bad", "kpi-good", "kpi-bad");

        // Trend indicator
        if (result.trendSlope > 10) {
            projTrendIndicator.setText("\u25B2 Spending trending up " + fmt(Math.abs(result.trendSlope)) + "/month");
            setExclusiveClass(projTrendIndicator, "trend-up", "trend-up", "trend-down", "trend-flat");
        } else if (result.trendSlope < -10) {
            projTrendIndicator.setText("\u25BC Spending trending down " + fmt(Math.abs(result.trendSlope)) + "/month");
            setExclusiveClass(projTrendIndicator, "trend-down", "trend-up", "trend-down", "trend-flat");
        } else {
            projTrendIndicator.setText("\u2192 Spending is stable");
            setExclusiveClass(projTrendIndicator, "trend-flat", "trend-up", "trend-down", "trend-flat");
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

        // Series colours (and dashed confidence bands) come from .outlook-chart in styles.css;
        // the hover read-out (ChartHover) describes each month.
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

        // Colours come from .projection-area-chart in styles.css (pessimistic, expected, optimistic)
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

        // Categories sorted by total descending; top 7 + "Other" at most
        Map<String, Double> totals = new HashMap<>();
        first.categoryBreakdown.forEach((cat, v) -> { if (v > 0) totals.put(cat, v); });
        if (totals.isEmpty()) return;
        List<String> shown = shownCategories(totals);
        Map<String, Double> recurringByCat = fold(first.categoryRecurring, shown);
        Map<String, Double> variableByCat = fold(first.categoryVariable, shown);
        List<String> labels = new ArrayList<>(shown);
        if (needsOther(totals, shown)) labels.add(OTHER);

        // Two series: Recurring and Variable (colours from .proj-category-chart in styles.css)
        XYChart.Series<String, Number> recurringSeries = new XYChart.Series<>();
        recurringSeries.setName("Recurring");
        XYChart.Series<String, Number> variableSeries = new XYChart.Series<>();
        variableSeries.setName("Variable");

        for (String cat : labels) {
            recurringSeries.getData().add(new XYChart.Data<>(cat, recurringByCat.getOrDefault(cat, 0.0)));
            variableSeries.getData().add(new XYChart.Data<>(cat, variableByCat.getOrDefault(cat, 0.0)));
        }

        xAxis.setAutoRanging(false);
        xAxis.setCategories(FXCollections.observableArrayList(labels));
        projCategoryChart.getData().addAll(Arrays.asList(recurringSeries, variableSeries));
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

    // ======================== CATEGORY FOLDING + COLOURS ========================

    /**
     * Categories a chart shows individually, largest first. Up to {@link #MAX_SERIES} are shown
     * as-is; with more, only the top seven are kept and the rest fold into "Other".
     */
    static List<String> shownCategories(Map<String, Double> totals) {
        List<String> ranked = totals.entrySet().stream()
            .filter(e -> e.getValue() != null && e.getValue() > 0)
            .sorted(Map.Entry.<String, Double>comparingByValue().reversed()
                .thenComparing(Map.Entry.comparingByKey()))
            .map(Map.Entry::getKey)
            .collect(Collectors.toList());
        if (ranked.size() <= MAX_SERIES) return ranked;
        return ranked.stream().filter(c -> !OTHER.equals(c))
            .limit(MAX_SERIES - 1).collect(Collectors.toList());
    }

    /** True when {@code shown} does not cover every category with spend in {@code totals}. */
    static boolean needsOther(Map<String, Double> totals, List<String> shown) {
        return totals.entrySet().stream()
            .anyMatch(e -> e.getValue() != null && e.getValue() > 0 && !shown.contains(e.getKey()));
    }

    /** Folds a category→amount map onto the shown categories plus "Other". */
    static Map<String, Double> fold(Map<String, Double> amounts, Collection<String> shown) {
        Map<String, Double> out = new LinkedHashMap<>();
        amounts.forEach((cat, amt) -> out.merge(shown.contains(cat) ? cat : OTHER, amt, Double::sum));
        return out;
    }

    private void ensurePermanentSlots() {
        if (!permanentSlots.isEmpty() || state == null) return;
        List<Expense> spend = state.getExpenseList().stream()
            .filter(e -> state.countsAsSpend(e) || SharedState.isRefundCredit(e))
            .collect(Collectors.toList());
        List<String> ranked = state.spendByCategory(spend).entrySet().stream()
            .filter(e -> e.getValue() > 0 && !OTHER.equals(e.getKey()))
            .sorted(Map.Entry.<String, Double>comparingByValue().reversed()
                .thenComparing(Map.Entry.comparingByKey()))
            .limit(MAX_SERIES - 1)
            .map(Map.Entry::getKey)
            .collect(Collectors.toList());
        for (int i = 0; i < ranked.size(); i++) permanentSlots.put(ranked.get(i), i);
    }

    /**
     * Distinct colours for categories drawn together in one chart (at most eight). The colour
     * follows the category, not its rank in the chart; "Other" is always neutral grey.
     */
    private Map<String, String> colorsFor(List<String> shown) {
        ensurePermanentSlots();
        String[] palette = UIUtils.CATEGORY_COLORS;
        Map<String, String> out = new HashMap<>();
        Set<Integer> used = new HashSet<>();
        for (String c : shown) {
            Integer slot = permanentSlots.get(c);
            if (slot != null) { out.put(c, palette[slot]); used.add(slot); }
        }
        for (String c : shown) {
            if (out.containsKey(c) || OTHER.equals(c)) continue;
            Integer slot = borrowedSlots.get(c);
            if (slot != null && used.add(slot)) out.put(c, palette[slot]);
        }
        for (String c : shown) {
            if (out.containsKey(c) || OTHER.equals(c)) continue;
            // Prefer the slot no permanent category owns, then any slot free in this chart.
            int slot = -1;
            for (int i = palette.length - 1; i >= 0 && slot < 0; i--) {
                if (!used.contains(i) && !permanentSlots.containsValue(i)) slot = i;
            }
            for (int i = 0; i < palette.length && slot < 0; i++) {
                if (!used.contains(i)) slot = i;
            }
            if (slot < 0) { out.put(c, UIUtils.getCategoryColor(c)); continue; }
            used.add(slot);
            borrowedSlots.put(c, slot);
            out.put(c, palette[slot]);
        }
        out.put(OTHER, UIUtils.OTHER_COLOR);
        return out;
    }

    /** Small legend entry: colour swatch plus category name. */
    private static HBox legendItem(String name, String color) {
        javafx.scene.shape.Rectangle swatch = new javafx.scene.shape.Rectangle(10, 10);
        swatch.setFill(javafx.scene.paint.Color.web(color));
        swatch.setArcWidth(4);
        swatch.setArcHeight(4);
        Label lbl = new Label(name);
        lbl.getStyleClass().add("chart-legend-label");
        HBox item = new HBox(6, swatch, lbl);
        item.setAlignment(Pos.CENTER_LEFT);
        return item;
    }

    private static void tip(Node node, String text) {
        HoverTip.install(node, text);
    }

    /** Swaps one of a set of mutually exclusive style classes on a node. */
    private static void setExclusiveClass(Node node, String cls, String... all) {
        node.getStyleClass().removeAll(all);
        if (cls != null) node.getStyleClass().add(cls);
    }
}
