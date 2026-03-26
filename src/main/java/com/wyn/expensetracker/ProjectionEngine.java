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

        public ProjectionInput(List<Expense> allExpenses, List<RecurringExpense> recurringExpenses,
                               Map<YearMonth, Double> incomes, double recurringIncome,
                               Map<String, Double> budgets) {
            this.allExpenses = new ArrayList<>(allExpenses);
            this.recurringExpenses = new ArrayList<>(recurringExpenses);
            this.incomes = new HashMap<>(incomes);
            this.recurringIncome = recurringIncome;
            this.budgets = new HashMap<>(budgets);
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

    public ProjectionResult project(ProjectionInput input) {
        YearMonth now = YearMonth.now();

        // Separate actual (non-excluded, non-income, non-refund) expenses
        List<Expense> actualExpenses = input.allExpenses.stream()
                .filter(e -> !e.isExcluded() && !e.isIncome() && !e.isRefund())
                .collect(Collectors.toList());

        // Variable expenses = actual expenses that are NOT generated from recurring
        List<Expense> variableExpenses = actualExpenses.stream()
                .filter(e -> e.getRecurringId() == null)
                .collect(Collectors.toList());

        // Monthly totals for variable expenses (for trend & seasonal)
        Map<YearMonth, Double> monthlyVariableTotals = variableExpenses.stream()
                .collect(Collectors.groupingBy(
                        e -> YearMonth.from(e.getDate()),
                        Collectors.summingDouble(Expense::getAmount)));

        // Per-category monthly variable totals
        Map<String, Map<YearMonth, Double>> categoryMonthlyVariable = variableExpenses.stream()
                .collect(Collectors.groupingBy(
                        Expense::getCategory,
                        Collectors.groupingBy(
                                e -> YearMonth.from(e.getDate()),
                                Collectors.summingDouble(Expense::getAmount))));

        // Sorted months with data
        List<YearMonth> dataMonths = monthlyVariableTotals.keySet().stream()
                .filter(ym -> !ym.isAfter(now))
                .sorted()
                .collect(Collectors.toList());

        int dataMonthsAvailable = dataMonths.size();

        // Compute current balance (historical income - historical expenses)
        double totalHistoricalIncome = computeHistoricalIncome(input, now);
        double totalHistoricalExpenses = actualExpenses.stream()
                .filter(e -> !YearMonth.from(e.getDate()).isAfter(now))
                .mapToDouble(Expense::getAmount)
                .sum();
        double currentBalance = totalHistoricalIncome - totalHistoricalExpenses;

        // Algorithm 3: Linear trend
        double trendSlope = computeTrendSlope(monthlyVariableTotals, dataMonths);

        // Algorithm 4: Seasonal indices
        boolean hasSeasonalData = dataMonthsAvailable >= 12;
        Map<Integer, Double> seasonalIndices = computeSeasonalIndices(monthlyVariableTotals, dataMonths);

        // Algorithm 5: Confidence band stddev
        double stddev = computeStdDev(monthlyVariableTotals, dataMonths);
        boolean hasConfidenceBand = dataMonthsAvailable >= 2;

        // All categories present in recurring + variable
        Set<String> allCategories = new LinkedHashSet<>();
        input.recurringExpenses.forEach(r -> allCategories.add(r.getCategory()));
        categoryMonthlyVariable.keySet().forEach(allCategories::add);

        // Build 6 month projections
        List<MonthProjection> projections = new ArrayList<>();
        for (int n = 1; n <= 6; n++) {
            YearMonth targetMonth = now.plusMonths(n);
            MonthProjection mp = new MonthProjection(targetMonth);

            // Algorithm 1: Deterministic recurring per category
            Map<String, Double> recurringByCategory = computeRecurringForMonth(input.recurringExpenses, targetMonth);
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
            mp.projectedIncome = input.incomes.getOrDefault(targetMonth, input.recurringIncome);

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

    private Map<String, Double> computeRecurringForMonth(List<RecurringExpense> recurringExpenses, YearMonth targetMonth) {
        Map<String, Double> result = new LinkedHashMap<>();
        LocalDate monthStart = targetMonth.atDay(1);
        LocalDate monthEnd = targetMonth.atEndOfMonth();

        for (RecurringExpense re : recurringExpenses) {
            // Check if recurring is active during this month
            if (re.getDate().isAfter(monthEnd)) continue;
            if (re.getEndDate() != null && re.getEndDate().isBefore(monthStart)) continue;

            double monthlyEquivalent = switch (re.getFrequency()) {
                case DAILY -> re.getAmount() * targetMonth.lengthOfMonth();
                case WEEKLY -> re.getAmount() * targetMonth.lengthOfMonth() / 7.0;
                case BIWEEKLY -> re.getAmount() * targetMonth.lengthOfMonth() / 14.0;
                case MONTHLY -> re.getAmount();
                case QUARTERLY -> re.getAmount() / 3.0;
                case YEARLY -> re.getAmount() / 12.0;
            };

            result.merge(re.getCategory(), monthlyEquivalent, Double::sum);
        }
        return result;
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

    private double computeHistoricalIncome(ProjectionInput input, YearMonth upTo) {
        double total = 0;

        // Sum up explicit income entries
        for (Map.Entry<YearMonth, Double> entry : input.incomes.entrySet()) {
            if (!entry.getKey().isAfter(upTo)) {
                total += entry.getValue();
            }
        }

        // For months without explicit income, use recurring income if set
        if (input.recurringIncome > 0) {
            // Find range of expense data
            Optional<YearMonth> earliest = input.allExpenses.stream()
                    .filter(e -> !e.isExcluded())
                    .map(e -> YearMonth.from(e.getDate()))
                    .min(Comparator.naturalOrder());

            if (earliest.isPresent()) {
                YearMonth start = earliest.get();
                YearMonth end = upTo;
                YearMonth current = start;
                while (!current.isAfter(end)) {
                    if (!input.incomes.containsKey(current)) {
                        total += input.recurringIncome;
                    }
                    current = current.plusMonths(1);
                }
            }
        }

        // Also add income/refund flagged expenses
        total += input.allExpenses.stream()
                .filter(e -> (e.isIncome() || e.isRefund()) && !e.isExcluded())
                .filter(e -> !YearMonth.from(e.getDate()).isAfter(upTo))
                .mapToDouble(Expense::getAmount)
                .sum();

        return total;
    }
}
