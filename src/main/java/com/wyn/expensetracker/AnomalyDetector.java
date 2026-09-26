package com.wyn.expensetracker;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.*;
import java.util.stream.Collectors;

public class AnomalyDetector {

    private static double toBase(Expense e, CurrencyManager cm) {
        return cm.toBase(e.getAmount(), e.getCurrency());
    }

    public static List<Anomaly> detect(List<Expense> expenses, YearMonth selectedMonth,
                                        String currencySymbol, CurrencyManager cm) {
        return detect(expenses, selectedMonth, currencySymbol, cm, null);
    }

    /**
     * @param precomputed coverage of {@code expenses} (e.g. {@link SharedState#getRecurringCoverage()});
     *                    null computes it here
     */
    public static List<Anomaly> detect(List<Expense> expenses, YearMonth selectedMonth,
                                        String currencySymbol, CurrencyManager cm,
                                        SharedState.RecurringCoverage precomputed) {
        List<Anomaly> anomalies = new ArrayList<>();

        // Imported transactions that stand in for a recurring occurrence (e.g. the rent
        // debit order) are expected bills, just like the generated occurrences.
        SharedState.RecurringCoverage coverage = precomputed != null ? precomputed
            : SharedState.computeRecurringCoverage(expenses, cm);
        Set<Expense> coveringImports = coverage.coveringImports;

        // A generated occurrence that an import already covers is the same payment: keep
        // only the import, or daily totals / averages / IQR lists count it twice.
        List<Expense> activeExpenses = expenses.stream()
            .filter(e -> !e.isExcluded() && !e.isIncome() && !e.isRefund())
            .filter(e -> e.getRecurringId() == null
                || !coverage.coveredRecurringIds.contains(e.getRecurringId()))
            .collect(Collectors.toList());

        List<Expense> monthExpenses = activeExpenses.stream()
            .filter(e -> YearMonth.from(e.getDate()).equals(selectedMonth))
            .collect(Collectors.toList());

        if (monthExpenses.isEmpty()) return anomalies;

        detectAmountOutliers(anomalies, activeExpenses, monthExpenses, currencySymbol, cm, coveringImports);
        detectLargeTransactions(anomalies, activeExpenses, monthExpenses, currencySymbol, cm, coveringImports);
        detectSpendingSpikes(anomalies, activeExpenses, monthExpenses, selectedMonth, currencySymbol, cm, coveringImports);
        detectNewCategories(anomalies, expenses, activeExpenses, monthExpenses, selectedMonth);

        anomalies.sort(Comparator.comparingDouble(Anomaly::getSeverity).reversed());
        return anomalies;
    }

    private static void detectAmountOutliers(List<Anomaly> anomalies, List<Expense> all,
                                              List<Expense> month, String cs, CurrencyManager cm,
                                              Set<Expense> coveringImports) {
        // IQR method per category
        Map<String, List<Double>> categoryAmounts = all.stream()
            .collect(Collectors.groupingBy(Expense::getCategory,
                Collectors.mapping(e -> toBase(e, cm), Collectors.toList())));
        // Sort each category once, not once per expense.
        categoryAmounts.values().forEach(Collections::sort);

        for (Expense e : month) {
            // Generated recurring occurrences are expected bills, not anomalies.
            if (e.getRecurringId() != null || coveringImports.contains(e)) continue;
            List<Double> amounts = categoryAmounts.get(e.getCategory());
            if (amounts == null || amounts.size() < 5) continue;

            double q1 = amounts.get(amounts.size() / 4);
            double q3 = amounts.get(3 * amounts.size() / 4);
            double iqr = q3 - q1;
            double upperBound = q3 + 1.5 * iqr;

            double baseAmount = toBase(e, cm);
            if (baseAmount > upperBound && iqr > 0) {
                double severity = Math.min((baseAmount - upperBound) / iqr, 1.0);
                anomalies.add(new Anomaly(
                    Anomaly.AnomalyType.AMOUNT_OUTLIER,
                    String.format("%s: %s is unusually high for %s (typical range: %s - %s)",
                        e.getCategory(), UIUtils.fmt(baseAmount, cs), e.getCategory(),
                        UIUtils.fmt(q1, cs), UIUtils.fmt(q3, cs)),
                    e, e.getDate(), severity));
            }
        }
    }

    private static void detectLargeTransactions(List<Anomaly> anomalies, List<Expense> all,
                                                 List<Expense> month, String cs, CurrencyManager cm,
                                                 Set<Expense> coveringImports) {
        double avgAmount = all.stream().mapToDouble(e -> toBase(e, cm)).average().orElse(0);
        if (avgAmount <= 0) return;

        for (Expense e : month) {
            // Rent/bond etc. generated from a recurring series are known in advance.
            if (e.getRecurringId() != null || coveringImports.contains(e)) continue;
            double baseAmount = toBase(e, cm);
            if (baseAmount > avgAmount * 3) {
                double severity = Math.min(baseAmount / (avgAmount * 5), 1.0);
                anomalies.add(new Anomaly(
                    Anomaly.AnomalyType.LARGE_TRANSACTION,
                    String.format("Large transaction: %s at \"%s\" (avg transaction: %s)",
                        UIUtils.fmt(baseAmount, cs),
                        e.getDescription() != null ? e.getDescription() : e.getCategory(),
                        UIUtils.fmt(avgAmount, cs)),
                    e, e.getDate(), severity));
            }
        }
    }

    private static void detectSpendingSpikes(List<Anomaly> anomalies, List<Expense> all,
                                              List<Expense> month, YearMonth selectedMonth,
                                              String cs, CurrencyManager cm, Set<Expense> coveringImports) {
        // Known bills (recurring occurrences and the imports that pay them) are expected,
        // so they are left out of the day totals on both sides of the comparison.
        java.util.function.Predicate<Expense> discretionary =
            e -> e.getRecurringId() == null && !coveringImports.contains(e);
        // Compare daily spending to historical average
        Map<LocalDate, Double> dailyTotals = month.stream()
            .filter(discretionary)
            .collect(Collectors.groupingBy(Expense::getDate,
                Collectors.summingDouble(e -> toBase(e, cm))));

        // Historical daily averages (last 3 months)
        List<Double> historicalDailyTotals = new ArrayList<>();
        for (int m = 1; m <= 3; m++) {
            YearMonth histMonth = selectedMonth.minusMonths(m);
            Map<LocalDate, Double> histDaily = all.stream()
                .filter(discretionary)
                .filter(e -> YearMonth.from(e.getDate()).equals(histMonth))
                .collect(Collectors.groupingBy(Expense::getDate,
                    Collectors.summingDouble(e -> toBase(e, cm))));
            historicalDailyTotals.addAll(histDaily.values());
        }

        if (historicalDailyTotals.size() < 10) return;

        double mean = historicalDailyTotals.stream().mapToDouble(d -> d).average().orElse(0);
        double variance = historicalDailyTotals.stream().mapToDouble(d -> (d - mean) * (d - mean)).average().orElse(0);
        // A perfectly steady history has no spread; use a floor so a real jump still stands out.
        double stdDev = Math.max(Math.sqrt(variance), 0.1 * mean);

        if (stdDev <= 0) return;

        for (Map.Entry<LocalDate, Double> entry : dailyTotals.entrySet()) {
            double zScore = (entry.getValue() - mean) / stdDev;
            if (zScore > 2.0) {
                double severity = Math.min(zScore / 4.0, 1.0);
                anomalies.add(new Anomaly(
                    Anomaly.AnomalyType.SPENDING_SPIKE,
                    String.format("Spending spike on %s: %s (daily avg: %s)",
                        entry.getKey(), UIUtils.fmt(entry.getValue(), cs), UIUtils.fmt(mean, cs)),
                    null, entry.getKey(), severity));
            }
        }
    }

    /** Minimum number of earlier months with any data before "new category" alerts are raised. */
    static final int NEW_CATEGORY_MIN_HISTORY_MONTHS = 3;

    private static void detectNewCategories(List<Anomaly> anomalies, List<Expense> ledger, List<Expense> all,
                                             List<Expense> month, YearMonth selectedMonth) {
        // A new user has no history, so every category would look "new": only alert once
        // there are at least three earlier months with any (non-excluded) data.
        long priorMonths = ledger.stream()
            .filter(e -> !e.isExcluded() && e.getDate() != null)
            .map(e -> YearMonth.from(e.getDate()))
            .filter(ym -> ym.isBefore(selectedMonth))
            .distinct().count();
        if (priorMonths < NEW_CATEGORY_MIN_HISTORY_MONTHS) return;

        Map<String, Long> categoryHistory = all.stream()
            .filter(e -> YearMonth.from(e.getDate()).isBefore(selectedMonth))
            .collect(Collectors.groupingBy(Expense::getCategory, Collectors.counting()));

        Set<String> monthCategories = month.stream()
            .map(Expense::getCategory).collect(Collectors.toSet());

        for (String cat : monthCategories) {
            long count = categoryHistory.getOrDefault(cat, 0L);
            if (count < 3) {
                anomalies.add(new Anomaly(
                    Anomaly.AnomalyType.NEW_CATEGORY,
                    String.format("New category \"%s\" — only used %d time%s before",
                        cat, count, count == 1 ? "" : "s"),
                    null, selectedMonth.atDay(1), 0.3, cat));
            }
        }
    }
}
