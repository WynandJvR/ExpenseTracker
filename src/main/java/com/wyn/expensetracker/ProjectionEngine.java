package com.wyn.expensetracker;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.*;
import java.util.stream.Collectors;

public class ProjectionEngine {

    // ======================== DATA STRUCTURES ========================

    public static class ProjectionInput {
        public final List<Expense> allExpenses;
        public final List<RecurringExpense> recurringExpenses;
        public final Map<YearMonth, Double> incomes;
        public final double recurringIncome;
        public final Map<String, Double> budgets;
        public final CurrencyManager currencyManager;
        /** Skip/edit overrides for recurring occurrences (applied to projected months). */
        public final List<OccurrenceOverride> overrides;

        public ProjectionInput(List<Expense> allExpenses, List<RecurringExpense> recurringExpenses,
                               Map<YearMonth, Double> incomes, double recurringIncome,
                               Map<String, Double> budgets, CurrencyManager currencyManager) {
            this(allExpenses, recurringExpenses, incomes, recurringIncome, budgets, currencyManager,
                 Collections.emptyList());
        }

        public ProjectionInput(List<Expense> allExpenses, List<RecurringExpense> recurringExpenses,
                               Map<YearMonth, Double> incomes, double recurringIncome,
                               Map<String, Double> budgets, CurrencyManager currencyManager,
                               Collection<OccurrenceOverride> overrides) {
            this.overrides = overrides != null ? new ArrayList<>(overrides) : new ArrayList<>();
            this.allExpenses = new ArrayList<>(allExpenses);
            this.recurringExpenses = new ArrayList<>(recurringExpenses);
            this.incomes = new HashMap<>(incomes);
            this.recurringIncome = recurringIncome;
            this.budgets = new HashMap<>(budgets);
            this.currencyManager = currencyManager;
        }
    }

    public static class MonthProjection {
        public final YearMonth month;
        public double projectedExpenses;
        public double projectedRecurringExpenses;
        public double projectedVariableExpenses;
        public double projectedIncome;
        public double netSavings;
        public double optimisticExpenses;
        public double pessimisticExpenses;
        public Map<String, Double> categoryBreakdown = new LinkedHashMap<>();
        public Map<String, Double> categoryRecurring = new LinkedHashMap<>();
        public Map<String, Double> categoryVariable = new LinkedHashMap<>();
        /**
         * Variable spend per category before leftover recurring refunds are netted in.
         * The Overview adds the refund occurrences itself and clamps per month x category,
         * so it must start from this gross figure to avoid subtracting the refund twice.
         */
        public Map<String, Double> categoryVariableGross = new LinkedHashMap<>();

        public MonthProjection(YearMonth month) {
            this.month = month;
        }
    }

    public static class ProjectionResult {
        public final List<MonthProjection> monthProjections;
        public final double currentBalance;
        public final double trendSlope;
        public final boolean hasSeasonalData;
        public final int dataMonthsAvailable;

        public ProjectionResult(List<MonthProjection> monthProjections, double currentBalance,
                                double trendSlope, boolean hasSeasonalData, int dataMonthsAvailable) {
            this.monthProjections = monthProjections;
            this.currentBalance = currentBalance;
            this.trendSlope = trendSlope;
            this.hasSeasonalData = hasSeasonalData;
            this.dataMonthsAvailable = dataMonthsAvailable;
        }
    }

    // ======================== MAIN PROJECTION ========================

    private static double toBase(Expense e, CurrencyManager cm) {
        return cm.toBase(e.getAmount(), e.getCurrency());
    }

    public ProjectionResult project(ProjectionInput input) {
        return project(input, YearMonth.now());
    }

    /** Signed spend contribution: +amount for spend, -amount for a refund, 0 otherwise. */
    private static double signedSpend(Expense e, CurrencyManager cm) {
        if (e.isExcluded()) return 0;
        if (e.isRefund()) return -toBase(e, cm);
        if (e.isIncome()) return 0;
        return toBase(e, cm);
    }

    /**
     * Projection reusing an already-computed coverage of {@code input.allExpenses} (e.g.
     * {@link SharedState#getRecurringCoverage()}), so the matching is not redone.
     */
    public ProjectionResult project(ProjectionInput input, SharedState.RecurringCoverage coverage) {
        return project(input, YearMonth.now(), coverage);
    }

    /** Projection relative to an explicit "current month" (package-visible for tests). */
    ProjectionResult project(ProjectionInput input, YearMonth now) {
        return project(input, now, null);
    }

    /**
     * @param precomputed coverage of {@code input.allExpenses}; null computes it here
     */
    ProjectionResult project(ProjectionInput input, YearMonth now, SharedState.RecurringCoverage precomputed) {
        CurrencyManager cm = input.currencyManager;

        // One-to-one coverage of generated recurring occurrences by imported transactions.
        SharedState.RecurringCoverage coverage = precomputed != null ? precomputed
                : SharedState.computeRecurringCoverage(input.allExpenses, cm);

        // Spend-relevant items: spend (minus covered recurring occurrences) and refunds.
        List<Expense> spendItems = input.allExpenses.stream()
                .filter(e -> !e.isExcluded())
                .filter(e -> e.isRefund() || !e.isIncome())
                .filter(e -> e.getRecurringId() == null
                        || !coverage.coveredRecurringIds.contains(e.getRecurringId()))
                .collect(Collectors.toList());

        // Variable expenses = spend not generated from recurring and not an import that
        // stands in for a recurring occurrence (those are projected via Algorithm 1).
        List<Expense> variableExpenses = spendItems.stream()
                .filter(e -> e.getRecurringId() == null)
                .filter(e -> !coverage.coveringImports.contains(e))
                .collect(Collectors.toList());

        // Variable spend net of refunds, clamped at month x category (the app-wide rule);
        // monthly totals are the sum of the clamped cells.
        Map<YearMonth, Map<String, Double>> variableCells =
                SharedState.clampedSpendCells(variableExpenses, e -> signedSpend(e, cm));
        Map<YearMonth, Double> monthlyVariableTotals = new HashMap<>();
        Map<String, Map<YearMonth, Double>> categoryMonthlyVariable = new HashMap<>();
        variableCells.forEach((ym, cats) -> cats.forEach((cat, v) -> {
            monthlyVariableTotals.merge(ym, v, Double::sum);
            categoryMonthlyVariable.computeIfAbsent(cat, k -> new HashMap<>()).put(ym, v);
        }));

        // Sorted COMPLETE months with data: the current month is still partial, so it
        // would drag the trend/average down; only months strictly before now are used.
        List<YearMonth> dataMonths = monthlyVariableTotals.keySet().stream()
                .filter(ym -> ym.isBefore(now))
                .sorted()
                .collect(Collectors.toList());

        int dataMonthsAvailable = dataMonths.size();

        // Compute current balance (historical income - historical net spend)
        double totalHistoricalIncome = computeHistoricalIncome(input, now, cm, coverage);
        double totalHistoricalExpenses = SharedState.clampedNetSpend(spendItems.stream()
                .filter(e -> !YearMonth.from(e.getDate()).isAfter(now))
                .collect(Collectors.toList()), e -> signedSpend(e, cm));
        double currentBalance = totalHistoricalIncome - totalHistoricalExpenses;

        // Algorithm 3: Linear trend
        double trendSlope = computeTrendSlope(monthlyVariableTotals, dataMonths);

        // Algorithm 4: Seasonal indices
        boolean hasSeasonalData = dataMonthsAvailable >= 12;
        Map<Integer, Double> seasonalIndices = computeSeasonalIndices(monthlyVariableTotals, dataMonths);

        // Algorithm 5: Confidence band stddev
        double stddev = computeStdDev(monthlyVariableTotals, dataMonths);
        boolean hasConfidenceBand = dataMonthsAvailable >= 2;

        // Actual recurring occurrences (with skip/edit overrides) in the projected window,
        // the same source the Overview uses for future months.
        Map<YearMonth, List<Expense>> occurrencesByMonth = upcomingOccurrencesByMonth(input, now);

        // All categories present in recurring + variable
        Set<String> allCategories = new LinkedHashSet<>();
        input.recurringExpenses.stream()
                .filter(ProjectionEngine::isRecurringSpend)
                .forEach(r -> allCategories.add(r.getCategory()));
        occurrencesByMonth.values().forEach(list -> list.stream()
                .filter(ProjectionEngine::isOccurrenceSpend)
                .forEach(e -> allCategories.add(e.getCategory())));
        categoryMonthlyVariable.keySet().forEach(allCategories::add);

        // Build 6 month projections
        List<MonthProjection> projections = new ArrayList<>();
        for (int n = 1; n <= 6; n++) {
            YearMonth targetMonth = now.plusMonths(n);
            MonthProjection mp = new MonthProjection(targetMonth);

            // Algorithm 1: Deterministic recurring per category, net of recurring refunds
            // (e.g. a monthly cashback). A negative net offsets that category's variable
            // spend below, so the month x category cell is clamped like everywhere else.
            Map<String, Double> recurringNet = computeRecurringForMonth(
                    occurrencesByMonth.getOrDefault(targetMonth, Collections.emptyList()), cm);
            Map<String, Double> recurringByCategory = new LinkedHashMap<>();
            Map<String, Double> recurringCredit = new HashMap<>();
            recurringNet.forEach((cat, v) -> {
                if (v > 1e-9) recurringByCategory.put(cat, v);
                else if (v < -1e-9) recurringCredit.put(cat, -v);
            });
            double totalRecurring = recurringByCategory.values().stream().mapToDouble(Double::doubleValue).sum();

            // Algorithm 2: WMA per category + seasonal + trend
            Map<String, Double> variableByCategory = new LinkedHashMap<>();
            double totalVariable = 0;
            for (String category : allCategories) {
                Map<YearMonth, Double> catMonthly = categoryMonthlyVariable.getOrDefault(category, Collections.emptyMap());
                double catWma = computeWMA(catMonthly, dataMonths);
                variableByCategory.put(category, catWma);
                totalVariable += catWma;
            }

            // Apply seasonal adjustment to variable totals
            double seasonalIndex = seasonalIndices.getOrDefault(targetMonth.getMonthValue(), 1.0);
            for (Map.Entry<String, Double> entry : variableByCategory.entrySet()) {
                entry.setValue(entry.getValue() * seasonalIndex);
            }
            totalVariable *= seasonalIndex;

            // Apply trend adjustment
            double trendAdjustment = trendSlope * n;
            totalVariable += trendAdjustment;
            // Distribute trend proportionally across categories
            if (!variableByCategory.isEmpty()) {
                double variableSum = variableByCategory.values().stream().mapToDouble(Double::doubleValue).sum();
                if (variableSum > 0 && trendAdjustment != 0) {
                    for (Map.Entry<String, Double> entry : variableByCategory.entrySet()) {
                        double proportion = entry.getValue() / variableSum;
                        entry.setValue(Math.max(0, entry.getValue() + trendAdjustment * proportion));
                    }
                }
            }

            variableByCategory.forEach((cat, v) -> mp.categoryVariableGross.put(cat, Math.max(0, v)));

            // Recurring refunds left over after netting against recurring spend reduce the
            // category's variable spend, clamped at 0 (month x category rule).
            recurringCredit.forEach((cat, credit) -> variableByCategory.computeIfPresent(cat,
                    (c, v) -> Math.max(0, v - credit)));

            // Recompute totalVariable from clamped category values to stay consistent
            totalVariable = variableByCategory.values().stream().mapToDouble(Double::doubleValue).sum();

            mp.projectedRecurringExpenses = totalRecurring;
            mp.projectedVariableExpenses = Math.max(0, totalVariable);
            mp.projectedExpenses = totalRecurring + mp.projectedVariableExpenses;

            // Confidence bands
            if (hasConfidenceBand) {
                mp.optimisticExpenses = totalRecurring + Math.max(0, mp.projectedVariableExpenses - stddev);
                mp.pessimisticExpenses = totalRecurring + mp.projectedVariableExpenses + stddev;
            } else {
                mp.optimisticExpenses = mp.projectedExpenses;
                mp.pessimisticExpenses = mp.projectedExpenses;
            }

            // Income
            mp.projectedIncome = SharedState.resolveMonthlyIncome(
                    computeRecurringIncomeForMonth(
                            occurrencesByMonth.getOrDefault(targetMonth, Collections.emptyList()), cm),
                    plannedIncome(input, targetMonth), targetMonth, now);

            // Net savings
            mp.netSavings = mp.projectedIncome - mp.projectedExpenses;

            // Category breakdowns
            for (String category : allCategories) {
                double catRecurring = recurringByCategory.getOrDefault(category, 0.0);
                double catVariable = variableByCategory.getOrDefault(category, 0.0);
                mp.categoryRecurring.put(category, catRecurring);
                mp.categoryVariable.put(category, Math.max(0, catVariable));
                mp.categoryBreakdown.put(category, catRecurring + Math.max(0, catVariable));
            }

            projections.add(mp);
        }

        return new ProjectionResult(projections, currentBalance, trendSlope, hasSeasonalData, dataMonthsAvailable);
    }

    // ======================== ALGORITHM 1: DETERMINISTIC RECURRING ========================

    /**
     * Recurring occurrences (skip/edit overrides applied) in months now+1 .. now+6, grouped
     * by month, generated by the same ExpenseManager logic the Overview uses.
     */
    static Map<YearMonth, List<Expense>> upcomingOccurrencesByMonth(ProjectionInput input, YearMonth now) {
        Map<YearMonth, List<Expense>> byMonth = new HashMap<>();
        if (input.recurringExpenses.isEmpty()) return byMonth;
        ExpenseManager scratch = new ExpenseManager();
        scratch.setOverrides(input.overrides);
        List<Expense> templates = new ArrayList<>(input.recurringExpenses);
        scratch.loadExpenses(templates);
        for (Expense e : scratch.getUpcomingRecurring(now.plusMonths(1).atDay(1), now.plusMonths(6).atEndOfMonth())) {
            byMonth.computeIfAbsent(YearMonth.from(e.getDate()), k -> new ArrayList<>()).add(e);
        }
        return byMonth;
    }

    /**
     * Signed recurring spend per category for a month: that month's spend occurrences minus
     * its refund occurrences (unclamped; the caller clamps the month x category cell).
     */
    private Map<String, Double> computeRecurringForMonth(List<Expense> occurrences, CurrencyManager cm) {
        Map<String, Double> result = new LinkedHashMap<>();
        for (Expense e : occurrences) {
            if (isOccurrenceSpend(e)) result.merge(e.getCategory(), toBase(e, cm), Double::sum);
            else if (SharedState.isRefundCredit(e)) result.merge(e.getCategory(), -toBase(e, cm), Double::sum);
        }
        return result;
    }

    static boolean isRecurringSpend(RecurringExpense re) {
        return !re.isIncome() && !re.isRefund() && !re.isExcluded();
    }

    private static boolean isOccurrenceSpend(Expense e) {
        return !e.isIncome() && !e.isRefund() && !e.isExcluded();
    }

    /** Scheduled recurring income (income occurrences, not refunds/excluded) for a month. */
    private double computeRecurringIncomeForMonth(List<Expense> occurrences, CurrencyManager cm) {
        double total = 0;
        for (Expense e : occurrences) {
            if (SharedState.isIncomeItem(e)) total += toBase(e, cm);
        }
        return total;
    }

    private static double plannedIncome(ProjectionInput input, YearMonth ym) {
        Double v = input.incomes.get(ym);
        return v != null ? v : input.recurringIncome;
    }

    // ======================== ALGORITHM 2: WEIGHTED MOVING AVERAGE ========================

    private double computeWMA(Map<YearMonth, Double> categoryMonthly, List<YearMonth> dataMonths) {
        if (dataMonths.isEmpty()) return 0;

        // Take last 6 months with actual data
        List<YearMonth> recentMonths = dataMonths.subList(Math.max(0, dataMonths.size() - 6), dataMonths.size());

        double weightedSum = 0;
        double weightTotal = 0;
        for (int i = 0; i < recentMonths.size(); i++) {
            double weight = i + 1; // oldest=1, most recent=highest
            double value = categoryMonthly.getOrDefault(recentMonths.get(i), 0.0);
            weightedSum += weight * value;
            weightTotal += weight;
        }

        return weightTotal > 0 ? weightedSum / weightTotal : 0;
    }

    // ======================== ALGORITHM 3: LINEAR TREND DETECTION ========================

    private double computeTrendSlope(Map<YearMonth, Double> monthlyTotals, List<YearMonth> dataMonths) {
        // Requires 3+ months
        List<YearMonth> last12 = dataMonths.subList(Math.max(0, dataMonths.size() - 12), dataMonths.size());
        if (last12.size() < 3) return 0;

        int n = last12.size();
        double[] x = new double[n];
        double[] y = new double[n];

        for (int i = 0; i < n; i++) {
            x[i] = i;
            y[i] = monthlyTotals.getOrDefault(last12.get(i), 0.0);
        }

        double xMean = Arrays.stream(x).average().orElse(0);
        double yMean = Arrays.stream(y).average().orElse(0);

        double numerator = 0;
        double denominator = 0;
        for (int i = 0; i < n; i++) {
            numerator += (x[i] - xMean) * (y[i] - yMean);
            denominator += (x[i] - xMean) * (x[i] - xMean);
        }

        double slope = denominator != 0 ? numerator / denominator : 0;

        // Cap at ±20% of mean
        double cap = yMean * 0.20;
        slope = Math.max(-cap, Math.min(cap, slope));

        return slope;
    }

    // ======================== ALGORITHM 4: SEASONAL ADJUSTMENT ========================

    private Map<Integer, Double> computeSeasonalIndices(Map<YearMonth, Double> monthlyTotals, List<YearMonth> dataMonths) {
        Map<Integer, Double> indices = new HashMap<>();
        // Default all months to 1.0
        for (int m = 1; m <= 12; m++) indices.put(m, 1.0);

        if (dataMonths.size() < 12) return indices;

        // Compute average for each calendar month
        Map<Integer, List<Double>> monthValues = new HashMap<>();
        for (YearMonth ym : dataMonths) {
            monthValues.computeIfAbsent(ym.getMonthValue(), k -> new ArrayList<>())
                    .add(monthlyTotals.getOrDefault(ym, 0.0));
        }

        double overallAvg = dataMonths.stream()
                .mapToDouble(ym -> monthlyTotals.getOrDefault(ym, 0.0))
                .average().orElse(0);
        if (overallAvg <= 0) return indices;

        for (Map.Entry<Integer, List<Double>> entry : monthValues.entrySet()) {
            double monthAvg = entry.getValue().stream().mapToDouble(Double::doubleValue).average().orElse(0);
            indices.put(entry.getKey(), monthAvg / overallAvg);
        }

        return indices;
    }

    // ======================== ALGORITHM 5: CONFIDENCE BANDS ========================

    private double computeStdDev(Map<YearMonth, Double> monthlyTotals, List<YearMonth> dataMonths) {
        List<YearMonth> last12 = dataMonths.subList(Math.max(0, dataMonths.size() - 12), dataMonths.size());
        if (last12.size() < 2) return 0;

        double[] values = last12.stream()
                .mapToDouble(ym -> monthlyTotals.getOrDefault(ym, 0.0))
                .toArray();

        double mean = Arrays.stream(values).average().orElse(0);
        double variance = Arrays.stream(values)
                .map(v -> (v - mean) * (v - mean))
                .sum() / (values.length - 1);

        return Math.sqrt(variance);
    }

    // ======================== HELPER ========================

    /**
     * Historical income through {@code upTo}, one figure per month via
     * {@link SharedState#resolveMonthlyIncome} (actual income transactions when present,
     * otherwise planned) — never both. Only months that have ledger data (any non-excluded
     * expense, refund or income) contribute: a gap month with no data at all adds nothing,
     * since its spend is unknown too. Generated recurring income occurrences that an
     * imported credit already covers are not counted twice. Refunds are not income.
     */
    double computeHistoricalIncome(ProjectionInput input, YearMonth upTo, CurrencyManager cm) {
        return computeHistoricalIncome(input, upTo, cm, null);
    }

    /** @param precomputed coverage of {@code input.allExpenses}; null computes it here */
    double computeHistoricalIncome(ProjectionInput input, YearMonth upTo, CurrencyManager cm,
                                   SharedState.RecurringCoverage precomputed) {
        SharedState.RecurringCoverage coverage = precomputed != null ? precomputed
                : SharedState.computeRecurringCoverage(input.allExpenses, cm);
        Map<YearMonth, Double> actualByMonth = new HashMap<>();
        Set<YearMonth> dataMonths = new HashSet<>();
        for (Expense e : input.allExpenses) {
            if (e.isExcluded() || e.getDate() == null) continue;
            YearMonth ym = YearMonth.from(e.getDate());
            if (ym.isAfter(upTo)) continue;
            dataMonths.add(ym);
            if (SharedState.isIncomeItem(e)) {
                if (e.getRecurringId() != null && coverage.coveredRecurringIds.contains(e.getRecurringId())) continue;
                actualByMonth.merge(ym, toBase(e, cm), Double::sum);
            }
        }
        double total = 0;
        for (YearMonth cur : dataMonths) {
            total += SharedState.resolveMonthlyIncome(
                    actualByMonth.getOrDefault(cur, 0.0), plannedIncome(input, cur), cur, upTo);
        }
        return total;
    }
}
