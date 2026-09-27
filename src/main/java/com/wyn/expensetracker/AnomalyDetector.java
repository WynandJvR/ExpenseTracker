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

        // Subscriptions & debit orders first: a price change explains its charge, so the
        // general checks below leave that charge out ("unusually high", "large", day spikes).
        List<List<Expense>> merchants = groupByMerchant(activeExpenses);
        Set<Expense> repriced = detectPriceChanges(anomalies, merchants, monthExpenses, coverage, currencySymbol, cm);
        detectNewSubscriptions(anomalies, merchants, selectedMonth, expenses, currencySymbol, cm);
        detectDoubleCharges(anomalies, merchants, selectedMonth, expenses, currencySymbol, cm);

        Set<Expense> explained = Collections.newSetFromMap(new IdentityHashMap<>());
        explained.addAll(coveringImports);
        explained.addAll(repriced);
        detectAmountOutliers(anomalies, activeExpenses, monthExpenses, currencySymbol, cm, explained);
        detectLargeTransactions(anomalies, activeExpenses, monthExpenses, currencySymbol, cm, explained);
        detectSpendingSpikes(anomalies, activeExpenses, monthExpenses, selectedMonth, currencySymbol, cm, explained);
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

    // ------------------------------------------------------------ subscriptions

    /** A change smaller than this (fraction, and in base currency) isn't worth a note. */
    static final double PRICE_CHANGE_MIN_FRACTION = 0.10;
    static final double PRICE_CHANGE_MIN_AMOUNT = 10;
    /** Double charges below this are usually two coffees, not a billing error. */
    static final double DOUBLE_CHARGE_MIN_AMOUNT = 50;
    static final int DOUBLE_CHARGE_WINDOW_DAYS = 3;

    /**
     * Imported/entered charges (not generated occurrences) grouped by merchant, each group
     * sorted by date. "CLAUDE.AI SUBSCRIPTION 8842" and "ANTHROPIC* CLAUDE" land together.
     */
    static List<List<Expense>> groupByMerchant(List<Expense> active) {
        // Exact keys first (cheap), then merge distinct keys that name the same merchant. Only
        // keys sharing a word or their first letters are compared (or known aliases), so years
        // of history stay fast on the FX thread.
        Map<String, List<Expense>> byKey = new LinkedHashMap<>();
        for (Expense e : active) {
            if (e.getRecurringId() != null || e.getDate() == null) continue;
            String key = RecurringPatternDetector.normalizeDescription(e.getDescription());
            if (key.isEmpty()) continue;
            byKey.computeIfAbsent(key, k -> new ArrayList<>()).add(e);
        }
        List<String> keys = new ArrayList<>(byKey.keySet());
        int[] parent = new int[keys.size()];
        for (int i = 0; i < parent.length; i++) parent[i] = i;
        Map<String, List<Integer>> index = new HashMap<>();
        for (int i = 0; i < keys.size(); i++) {
            for (String token : indexTokens(keys.get(i))) {
                List<Integer> seen = index.computeIfAbsent(token, t -> new ArrayList<>());
                // A word on hundreds of different merchants ("store", a town) says nothing; skip it.
                if (seen.size() > MAX_BUCKET) continue;
                for (int j : seen) {
                    if (find(parent, i) != find(parent, j)
                            && RecurringPatternDetector.sameMerchantKey(keys.get(i), keys.get(j))) {
                        parent[find(parent, i)] = find(parent, j);
                    }
                }
                seen.add(i);
            }
        }
        Map<Integer, List<Expense>> merged = new LinkedHashMap<>();
        for (int i = 0; i < keys.size(); i++) {
            merged.computeIfAbsent(find(parent, i), r -> new ArrayList<>()).addAll(byKey.get(keys.get(i)));
        }
        List<List<Expense>> groups = new ArrayList<>(merged.values());
        groups.forEach(g -> g.sort(Comparator.comparing(Expense::getDate)));
        return groups;
    }

    private static final int MAX_BUCKET = 40;

    /** Buckets for {@link #groupByMerchant}: the first word, each word of 4+ letters, and brand aliases. */
    private static Set<String> indexTokens(String key) {
        Set<String> tokens = new HashSet<>();
        tokens.add("^" + key.split("[\\s.]+", 2)[0]);
        for (String w : key.split("[\\s.]+")) if (w.length() >= 4) tokens.add(w);
        for (String alias : new String[]{"claude", "anthropic", "chatgpt", "openai", "google", "microsoft", "ms ",
                "netflix", "spotify", "amazon", "amzn", "apple"}) {
            if (key.contains(alias)) tokens.add("alias");
        }
        return tokens;
    }

    private static int find(int[] parent, int i) {
        while (parent[i] != i) i = parent[i] = parent[parent[i]];
        return i;
    }

    /** True when the charges look like one bill: about the same amount at a steady interval. */
    static boolean looksLikeBill(List<Expense> dated, CurrencyManager cm) {
        if (dated.size() < 2) return false;
        double med = median(dated.stream().map(e -> toBase(e, cm)).collect(Collectors.toList()));
        if (med <= 0) return false;
        for (Expense e : dated) {
            if (Math.abs(toBase(e, cm) - med) > med * 0.15) return false;
        }
        long min = Long.MAX_VALUE, max = 0;
        for (int i = 1; i < dated.size(); i++) {
            long d = java.time.temporal.ChronoUnit.DAYS.between(dated.get(i - 1).getDate(), dated.get(i).getDate());
            min = Math.min(min, d);
            max = Math.max(max, d);
        }
        // Weekly to yearly, and steady (monthly bills land 28-31 days apart).
        return min >= 5 && max <= 400 && max <= min * 1.5 + 3;
    }

    private static double median(List<Double> values) {
        List<Double> v = new ArrayList<>(values);
        Collections.sort(v);
        int n = v.size();
        return n == 0 ? 0 : n % 2 == 1 ? v.get(n / 2) : (v.get(n / 2 - 1) + v.get(n / 2)) / 2;
    }

    private static String payee(Expense e) {
        String d = e.getDescription();
        return d != null && !d.isBlank() ? d.trim() : e.getCategory();
    }

    /**
     * A regular bill charged at a new price: either a recurring series paid at a different
     * amount (see {@link SharedState.RecurringCoverage#repricedImports}), or a merchant that
     * has charged about the same amount at a steady interval and now charges something else.
     *
     * @return the charges flagged
     */
    private static Set<Expense> detectPriceChanges(List<Anomaly> anomalies, List<List<Expense>> merchants,
                                                  List<Expense> month, SharedState.RecurringCoverage coverage,
                                                  String cs, CurrencyManager cm) {
        Set<Expense> flagged = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Expense charge : month) {
            Expense occurrence = coverage.repricedImports.get(charge);
            if (occurrence == null) continue;
            addPriceChange(anomalies, charge, toBase(occurrence, cm), toBase(charge, cm), true, cs);
            flagged.add(charge);
        }
        Set<Expense> inMonth = Collections.newSetFromMap(new IdentityHashMap<>());
        inMonth.addAll(month);
        for (List<Expense> group : merchants) {
            for (int i = 2; i < group.size(); i++) {
                Expense charge = group.get(i);
                if (!inMonth.contains(charge) || flagged.contains(charge)) continue;
                List<Expense> before = group.subList(Math.max(0, i - 3), i);
                if (!looksLikeBill(before, cm)) continue;
                // Same company isn't enough: "VODACOM AIRTIME" is not the "VODACOM DEBIT ORDER" contract.
                // Compare the descriptions as printed (reference numbers aside), not the grouping key.
                String paid = SharedState.paymentKey(charge.getDescription());
                if (before.stream().noneMatch(b -> RecurringPatternDetector.sameMerchantKey(
                        SharedState.paymentKey(b.getDescription()), paid))) continue;
                double usual = median(before.stream().map(e -> toBase(e, cm)).collect(Collectors.toList()));
                double now = toBase(charge, cm);
                if (Math.abs(now - usual) < Math.max(PRICE_CHANGE_MIN_AMOUNT, usual * PRICE_CHANGE_MIN_FRACTION)) continue;
                addPriceChange(anomalies, charge, usual, now, false, cs);
                flagged.add(charge);
            }
        }
        return flagged;
    }

    private static void addPriceChange(List<Anomaly> anomalies, Expense charge, double usual, double now,
                                       boolean recurring, String cs) {
        double change = usual > 0 ? (now - usual) / usual : 0;
        boolean up = now > usual;
        String pct = String.format("%s%.0f%%", up ? "+" : "−", Math.abs(change) * 100);
        String msg = String.format("%s went %s: %s on %s, %s %s (%s). Same %s, new price?%s",
            payee(charge), up ? "up" : "down", UIUtils.fmt(now, cs), charge.getDate(),
            recurring ? "your recurring amount is" : "usually", UIUtils.fmt(usual, cs), pct,
            recurring ? "bill" : "subscription",
            recurring ? " Update the recurring amount if it's permanent." : "");
        // Increases matter more; doubling or more is as loud as it gets.
        double severity = up ? Math.min(0.45 + Math.abs(change) / 2, 1.0) : 0.35;
        anomalies.add(new Anomaly(Anomaly.AnomalyType.PRICE_CHANGE, msg, charge, charge.getDate(), severity));
    }

    /**
     * A merchant that has recently started charging the same amount at a steady interval
     * (2-3 charges so far, the latest this month) and isn't set up as a recurring bill yet.
     */
    private static void detectNewSubscriptions(List<Anomaly> anomalies, List<List<Expense>> merchants,
                                               YearMonth selectedMonth, List<Expense> ledger,
                                               String cs, CurrencyManager cm) {
        List<String> recurringNames = new ArrayList<>();
        for (Expense e : ledger) {
            RecurringExpense src = e.getSourceRecurringExpense();
            if (src != null && src.getDescription() != null) recurringNames.add(src.getDescription());
        }
        for (List<Expense> group : merchants) {
            if (group.size() < 2 || group.size() > 3) continue;
            Expense latest = group.get(group.size() - 1);
            if (!YearMonth.from(latest.getDate()).equals(selectedMonth)) continue;
            if (!looksLikeBill(group, cm)) continue;
            // Subscriptions charge a fixed price; two similar grocery shops a month apart don't.
            DoubleSummaryStatistics amounts = group.stream().mapToDouble(e -> toBase(e, cm)).summaryStatistics();
            if (amounts.getMax() - amounts.getMin() > Math.max(1, amounts.getMax() * 0.02)) continue;
            if (recurringNames.stream().anyMatch(n -> RecurringPatternDetector.sameMerchant(n, latest.getDescription()))) continue;
            long gap = java.time.temporal.ChronoUnit.DAYS.between(group.get(0).getDate(), latest.getDate())
                / (group.size() - 1);
            // Weekly "subscriptions" are mostly habits (the Friday coffee), not sign-ups.
            if (gap < 25) continue;
            String every = gap <= 45 ? "monthly" : gap <= 120 ? "quarterly" : "yearly";
            anomalies.add(new Anomaly(Anomaly.AnomalyType.NEW_SUBSCRIPTION,
                String.format("New subscription? %s has charged %s %d times since %s, %s. "
                        + "Add it under Recurring to see it coming.",
                    payee(latest), UIUtils.fmt(toBase(latest, cm), cs), group.size(), group.get(0).getDate(), every),
                latest, latest.getDate(), 0.45));
        }
    }

    /** The same amount at the same merchant twice within a few days: possibly billed twice. */
    private static void detectDoubleCharges(List<Anomaly> anomalies, List<List<Expense>> merchants,
                                            YearMonth selectedMonth, List<Expense> ledger,
                                            String cs, CurrencyManager cm) {
        List<Expense> refunds = ledger.stream().filter(e -> e.isRefund() && e.getDate() != null).collect(Collectors.toList());
        for (List<Expense> group : merchants) {
            for (int i = 1; i < group.size(); i++) {
                Expense a = group.get(i - 1), b = group.get(i);
                if (!YearMonth.from(b.getDate()).equals(selectedMonth)) continue;
                double amt = toBase(b, cm);
                if (amt < DOUBLE_CHARGE_MIN_AMOUNT || Math.abs(toBase(a, cm) - amt) >= 0.005) continue;
                long days = java.time.temporal.ChronoUnit.DAYS.between(a.getDate(), b.getDate());
                if (days > DOUBLE_CHARGE_WINDOW_DAYS) continue;
                // The bank already reversed one of them.
                boolean reversed = refunds.stream().anyMatch(r -> Math.abs(toBase(r, cm) - amt) < 0.005
                    && !r.getDate().isBefore(a.getDate()) && !r.getDate().isAfter(b.getDate().plusDays(14))
                    && RecurringPatternDetector.sameMerchant(r.getDescription(), b.getDescription()));
                // Bought at that exact amount several times before (e.g. R500 prepaid electricity): a habit.
                long habitual = group.subList(0, i - 1).stream().filter(x -> Math.abs(toBase(x, cm) - amt) < 0.005).count();
                if (reversed || habitual >= 3) continue;
                anomalies.add(new Anomaly(Anomaly.AnomalyType.DUPLICATE_CHARGE,
                    String.format("Charged twice? %s at %s on %s%s. If you only paid once, query it with your bank.",
                        UIUtils.fmt(amt, cs), payee(b), a.getDate(),
                        days == 0 ? " (both the same day)" : " and " + b.getDate()),
                    b, b.getDate(), 0.6));
                i++; // a third identical charge would pair with b again
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
