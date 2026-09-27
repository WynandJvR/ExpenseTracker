package com.wyn.expensetracker;

import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

public class RecurringPatternDetector {

    public static class DetectedPattern {
        private final BooleanProperty selected = new SimpleBooleanProperty(false);
        private final String description;
        private final String category;
        private final double averageAmount;
        private final RecurrenceType frequency;
        private final LocalDate earliestDate;
        private final List<Expense> matchingExpenses;
        private final double previousAmount; // the price before a change; 0 = unchanged

        public DetectedPattern(String description, String category, double averageAmount,
                               RecurrenceType frequency, LocalDate earliestDate, List<Expense> matchingExpenses) {
            this(description, category, averageAmount, frequency, earliestDate, matchingExpenses, 0);
        }

        public DetectedPattern(String description, String category, double averageAmount,
                               RecurrenceType frequency, LocalDate earliestDate, List<Expense> matchingExpenses,
                               double previousAmount) {
            this.previousAmount = previousAmount;
            this.description = description;
            this.category = category;
            this.averageAmount = averageAmount;
            this.frequency = frequency;
            this.earliestDate = earliestDate;
            this.matchingExpenses = matchingExpenses;
        }

        public boolean isSelected() { return selected.get(); }
        public void setSelected(boolean val) { selected.set(val); }
        public BooleanProperty selectedProperty() { return selected; }

        public String getDescription() { return description; }
        public String getCategory() { return category; }
        public double getAverageAmount() { return averageAmount; }
        public RecurrenceType getFrequency() { return frequency; }
        public LocalDate getEarliestDate() { return earliestDate; }
        public List<Expense> getMatchingExpenses() { return matchingExpenses; }
        public int getOccurrences() { return matchingExpenses.size(); }
        /** The price before it changed (e.g. a subscription that went from R2,000 to R4,000); 0 if it didn't. */
        public double getPreviousAmount() { return previousAmount; }
    }

    /**
     * Detects recurring patterns in one-time expenses.
     * Groups by normalized description + category, then checks for regular intervals.
     */
    public List<DetectedPattern> detectPatterns(List<Expense> allExpenses, List<RecurringExpense> existingRecurring) {
        // Filter to only one-time, non-excluded, non-income expenses
        List<Expense> oneTimeExpenses = allExpenses.stream()
            .filter(e -> e.getRecurringId() == null)
            .filter(e -> !(e instanceof RecurringExpense))
            .filter(e -> !e.isExcluded())
            .filter(e -> !e.isIncome())
            .collect(Collectors.toList());

        // Group by normalized description + category, with fuzzy matching
        Map<String, List<Expense>> groups = new LinkedHashMap<>();
        for (Expense expense : oneTimeExpenses) {
            String key = buildGroupKey(expense);
            // Try to find an existing group with a similar key
            String matchedKey = findSimilarGroupKey(key, groups.keySet());
            if (matchedKey != null) {
                groups.get(matchedKey).add(expense);
            } else {
                groups.computeIfAbsent(key, k -> new ArrayList<>()).add(expense);
            }
        }

        List<DetectedPattern> patterns = new ArrayList<>();

        for (Map.Entry<String, List<Expense>> entry : groups.entrySet()) {
            List<Expense> group = entry.getValue();
            if (group.size() < 2) continue;

            // A subscription whose price changed part-way (R2,000 for months, then R4,000) is
            // still one subscription: keep every charge for the timing, price it at the new amount.
            List<Expense> dated = new ArrayList<>(group);
            dated.sort(Comparator.comparing(Expense::getDate));
            int step = amountsAreSimilar(dated) ? -1 : priceStep(dated);
            RecurrenceType detectedFreq = step > 0 ? detectFrequency(dated) : null;

            List<Expense> consistent;
            if (detectedFreq != null) {
                consistent = dated;
            } else {
                step = -1;
                // Strip amount outliers if needed, then check similarity
                consistent = stripAmountOutliers(group);
                if (consistent.size() < 2) continue;
                if (!amountsAreSimilar(consistent)) continue;
                consistent.sort(Comparator.comparing(Expense::getDate));
                // Detect frequency from intervals
                detectedFreq = detectFrequency(consistent);
            }
            if (detectedFreq == null) continue;

            // Check if this pattern already exists as a recurring expense
            Expense representative = consistent.get(0);
            if (alreadyExists(representative, detectedFreq, existingRecurring)) continue;

            List<Expense> current = step > 0 ? consistent.subList(step, consistent.size()) : consistent;
            double avgAmount = current.stream().mapToDouble(Expense::getAmount).average().orElse(0);
            avgAmount = Math.round(avgAmount * 100.0) / 100.0;
            double previous = 0;
            if (step > 0) {
                previous = consistent.subList(0, step).stream().mapToDouble(Expense::getAmount).average().orElse(0);
                previous = Math.round(previous * 100.0) / 100.0;
            }

            patterns.add(new DetectedPattern(
                representative.getDescription(),
                representative.getCategory(),
                avgAmount,
                detectedFreq,
                consistent.get(0).getDate(),
                new ArrayList<>(consistent),
                previous
            ));
        }

        // Sort by occurrence count descending
        patterns.sort(Comparator.comparingInt(DetectedPattern::getOccurrences).reversed());

        return patterns;
    }

    private String buildGroupKey(Expense expense) {
        String desc = normalizeDescription(expense.getDescription());
        String cat = expense.getCategory() != null ? expense.getCategory().toLowerCase().trim() : "";
        return desc + "|" + cat;
    }

    /**
     * Find an existing group key that is similar enough to merge with.
     * Matches if: same category AND (descriptions share a long common prefix,
     * or one contains the other).
     */
    private String findSimilarGroupKey(String newKey, Set<String> existingKeys) {
        String[] newParts = newKey.split("\\|", 2);
        String newDesc = newParts[0];
        String newCat = newParts.length > 1 ? newParts[1] : "";

        if (newDesc.isEmpty()) return null;

        for (String existing : existingKeys) {
            String[] existParts = existing.split("\\|", 2);
            String existDesc = existParts[0];
            String existCat = existParts.length > 1 ? existParts[1] : "";

            // Category must match
            if (!newCat.equals(existCat)) continue;
            if (existDesc.isEmpty()) continue;

            // Check containment (e.g., "anthropic claude" matches "anthropic claude pro")
            if (newDesc.contains(existDesc) || existDesc.contains(newDesc)) return existing;

            // Check common prefix (at least 60% of the shorter string)
            int prefixLen = commonPrefixLength(newDesc, existDesc);
            int minLen = Math.min(newDesc.length(), existDesc.length());
            if (minLen >= 4 && prefixLen >= minLen * 0.6) return existing;

            // Check known brand aliases (parent company / product name)
            if (areBrandAliases(newDesc, existDesc)) return existing;
        }
        return null;
    }

    /** Check if two descriptions refer to the same brand/service via known aliases. */
    static boolean areBrandAliases(String a, String b) {
        String[][] aliases = {
            {"claude", "anthropic"},
            {"chatgpt", "openai"},
            {"google one", "google storage"},
            {"ms", "microsoft"},
            {"netflix", "netflix.com"},
            {"spotify", "spotify ab"},
            {"amazon prime", "amzn prime", "amazon.com"},
            {"apple.com", "apple icloud", "apple one"},
        };
        for (String[] group : aliases) {
            boolean aMatch = false, bMatch = false;
            for (String alias : group) {
                // Whole words only: "ms" must not match "MAMS MEGASTOP".
                if (containsWord(a, alias)) aMatch = true;
                if (containsWord(b, alias)) bMatch = true;
            }
            if (aMatch && bMatch) return true;
        }
        return false;
    }

    private static boolean containsWord(String text, String word) {
        for (int i = text.indexOf(word); i >= 0; i = text.indexOf(word, i + 1)) {
            boolean startOk = i == 0 || !Character.isLetterOrDigit(text.charAt(i - 1));
            int end = i + word.length();
            boolean endOk = end == text.length() || !Character.isLetterOrDigit(text.charAt(end));
            if (startOk && endOk) return true;
        }
        return false;
    }

    /** Check if two descriptions share a significant keyword (4+ chars, not noise). */
    static boolean shareSignificantWord(String a, String b) {
        if (a == null || b == null) return false;
        Set<String> noise = Set.of("pos", "purchase", "card", "payment", "app", "fnb",
            "the", "for", "and", "fee", "from", "with", "debit", "online", "transfer");
        String[] wordsA = a.split("\\s+");
        Set<String> wordsB = Set.of(b.split("\\s+"));
        for (String word : wordsA) {
            if (word.length() >= 4 && !noise.contains(word) && wordsB.contains(word)) {
                return true;
            }
        }
        return false;
    }

    private static int commonPrefixLength(String a, String b) {
        int len = Math.min(a.length(), b.length());
        for (int i = 0; i < len; i++) {
            if (a.charAt(i) != b.charAt(i)) return i;
        }
        return len;
    }

    static String normalizeDescription(String description) {
        if (description == null || description.trim().isEmpty()) return "";
        String normalized = description.toLowerCase().trim();
        // Remove trailing reference numbers (common in bank statements)
        normalized = normalized.replaceAll("\\s*#\\d+$", "");
        normalized = normalized.replaceAll("\\s*ref\\s*:?\\s*\\d+$", "");
        // Remove trailing dates in common formats
        normalized = normalized.replaceAll("\\s*\\d{2}/\\d{2}/\\d{2,4}$", "");
        // Remove embedded dates (e.g., "CLAUDE 03/26" or "NETFLIX 2026-03-01")
        normalized = normalized.replaceAll("\\s*\\d{2,4}[/-]\\d{2}[/-]\\d{2,4}", "");
        normalized = normalized.replaceAll("\\s*\\d{2}/\\d{2}", "");
        // Normalize separators: asterisks, dashes, underscores → spaces
        normalized = normalized.replaceAll("[*_\\-/]+", " ");
        // Remove common transaction noise
        normalized = normalized.replaceAll("\\s*(payment|debit order|recurring|subscription|sub|subscript)\\s*$", "");
        // Remove common bank statement prefixes
        normalized = normalized.replaceAll("^(pos purchase|debit card purchase|card payment|online purchase)\\s+", "");
        // Remove trailing digits (transaction IDs)
        normalized = normalized.replaceAll("\\s+\\d{4,}$", "");
        // Remove multiple spaces and trim
        normalized = normalized.replaceAll("\\s+", " ").trim();
        return normalized;
    }

    /**
     * Remove amount outliers by keeping only entries within 50% of the median.
     * Works well for small datasets (5-10 entries) where IQR fails.
     * If all amounts are already similar, returns the full list unchanged.
     */
    private List<Expense> stripAmountOutliers(List<Expense> group) {
        if (amountsAreSimilar(group)) return group;
        if (group.size() < 3) return group;

        double[] amounts = group.stream().mapToDouble(Expense::getAmount).sorted().toArray();
        int mid = amounts.length / 2;
        double median = (amounts.length % 2 == 0)
            ? (amounts[mid - 1] + amounts[mid]) / 2.0
            : amounts[mid];

        // Keep entries within 50% of the median
        List<Expense> filtered = group.stream()
            .filter(e -> Math.abs(e.getAmount() - median) <= median * 0.50)
            .collect(Collectors.toList());

        return filtered.size() >= 2 ? filtered : group;
    }

    /**
     * True when two bank descriptions name the same merchant/service: equal once reference
     * numbers and dates are stripped, one contains the other, a long shared prefix, or known
     * aliases ("CLAUDE.AI" / "ANTHROPIC").
     */
    static boolean sameMerchant(String a, String b) {
        return sameMerchantKey(normalizeDescription(a), normalizeDescription(b));
    }

    /** {@link #sameMerchant} for descriptions already passed through {@link #normalizeDescription}. */
    static boolean sameMerchantKey(String x, String y) {
        if (x.isEmpty() || y.isEmpty()) return false;
        if (x.equals(y)) return true;
        // "payment to john" / "payment to mom" differ only in the person: never fuzzy-match those.
        if (PERSON_PAYMENT.matcher(x).find() || PERSON_PAYMENT.matcher(y).find()) return false;
        String shorter = x.length() <= y.length() ? x : y, longer = shorter == x ? y : x;
        // Whole words only, so "fee" isn't found in "coffee bean".
        if (shorter.length() >= 4 && (" " + longer + " ").contains(" " + shorter + " ")) return true;
        if (shorter.length() >= 4 && commonPrefixLength(x, y) >= shorter.length() * 0.6) return true;
        return areBrandAliases(x, y);
    }

    private static final java.util.regex.Pattern PERSON_PAYMENT =
        java.util.regex.Pattern.compile("\\b(payment|transfer|paid|send|sent)\\s+(to|from)\\b");

    /**
     * For date-sorted charges that aren't all one price: the index where a single price change
     * happened (every charge before it is one price, every charge from it another), or -1.
     * Needs at least two charges at the new price, so one odd charge isn't mistaken for it.
     */
    static int priceStep(List<Expense> dated) {
        int n = dated.size();
        if (n < 3) return -1;
        for (int k = n - 2; k >= 1; k--) {
            List<Expense> before = dated.subList(0, k), after = dated.subList(k, n);
            if (amountsAreSimilar(before) && amountsAreSimilar(after)) return k;
        }
        return -1;
    }

    private static boolean amountsAreSimilar(List<Expense> group) {
        double[] amounts = group.stream().mapToDouble(Expense::getAmount).sorted().toArray();
        int mid = amounts.length / 2;
        double median = (amounts.length % 2 == 0)
            ? (amounts[mid - 1] + amounts[mid]) / 2.0
            : amounts[mid];
        double tolerance = median * 0.15; // 15% tolerance (accommodates small price changes)

        for (double amount : amounts) {
            if (Math.abs(amount - median) > tolerance) {
                return false;
            }
        }
        return true;
    }

    private RecurrenceType detectFrequency(List<Expense> sortedGroup) {
        if (sortedGroup.size() < 2) return null;

        List<Long> intervals = new ArrayList<>();
        for (int i = 1; i < sortedGroup.size(); i++) {
            long days = ChronoUnit.DAYS.between(
                sortedGroup.get(i - 1).getDate(),
                sortedGroup.get(i).getDate()
            );
            if (days > 0) {
                intervals.add(days);
            }
        }

        if (intervals.isEmpty()) return null;

        double avgInterval = intervals.stream().mapToLong(Long::longValue).average().orElse(0);
        double stdDev = intervals.size() > 1 ? calculateStdDev(intervals, avgInterval) : 0;

        // Try each candidate frequency: pick the one whose expected interval
        // is closest to the average, provided the stddev is reasonable.
        // Max stddev is calibrated per frequency (monthly billing dates shift 28-31 = ~1.3 stddev)
        double[][] candidates = {
            // {expected days, avg tolerance, max stddev}
            {7, 2, 2.5},       // WEEKLY
            {14, 3, 4},        // BIWEEKLY
            {30, 6, 5},        // MONTHLY (billing dates shift 28-31)
            {91, 15, 12},      // QUARTERLY
            {365, 30, 20},     // YEARLY
        };
        RecurrenceType[] types = {
            RecurrenceType.WEEKLY, RecurrenceType.BIWEEKLY,
            RecurrenceType.MONTHLY, RecurrenceType.QUARTERLY, RecurrenceType.YEARLY
        };

        for (int i = 0; i < candidates.length; i++) {
            double expected = candidates[i][0];
            double avgTolerance = candidates[i][1];
            double maxStdDev = candidates[i][2];
            if (Math.abs(avgInterval - expected) <= avgTolerance && stdDev <= maxStdDev) {
                return types[i];
            }
        }

        // Fallback: wider average tolerance if consistency is still decent
        for (int i = 0; i < candidates.length; i++) {
            double expected = candidates[i][0];
            double maxStdDev = candidates[i][2] * 1.5;
            if (Math.abs(avgInterval - expected) <= expected * 0.2 && stdDev <= maxStdDev) {
                return types[i];
            }
        }

        return null;
    }

    private double calculateStdDev(List<Long> values, double mean) {
        if (values.size() < 2) return 0;
        double sumSquaredDiffs = values.stream()
            .mapToDouble(v -> Math.pow(v - mean, 2))
            .sum();
        // Sample stddev (Bessel's correction): denominator is N-1.
        // Recurring pattern samples are typically tiny (2-5 intervals) where
        // the bias from using N is large enough to loosen the consistency check.
        return Math.sqrt(sumSquaredDiffs / (values.size() - 1));
    }

    private boolean alreadyExists(Expense representative, RecurrenceType freq,
                                   List<RecurringExpense> existingRecurring) {
        String normalizedDesc = normalizeDescription(representative.getDescription());
        for (RecurringExpense existing : existingRecurring) {
            String existingDesc = normalizeDescription(existing.getDescription());
            boolean descMatch = normalizedDesc.equals(existingDesc)
                || (!normalizedDesc.isEmpty() && existingDesc.contains(normalizedDesc))
                || (!existingDesc.isEmpty() && normalizedDesc.contains(existingDesc))
                || areBrandAliases(normalizedDesc, existingDesc)
                || shareSignificantWord(normalizedDesc, existingDesc);
            boolean catMatch = existing.getCategory().equalsIgnoreCase(representative.getCategory());
            boolean amountClose = Math.abs(existing.getAmount() - representative.getAmount())
                <= representative.getAmount() * 0.15;
            // Same merchant by name (not just a shared word): a new price is still that series.
            boolean strongDesc = sameMerchant(representative.getDescription(), existing.getDescription());

            if (descMatch && catMatch && (amountClose || strongDesc)) {
                return true;
            }
        }
        return false;
    }
}
