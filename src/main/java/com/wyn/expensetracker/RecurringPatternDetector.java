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

        public DetectedPattern(String description, String category, double averageAmount,
                               RecurrenceType frequency, LocalDate earliestDate, List<Expense> matchingExpenses) {
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

            // Strip amount outliers if needed, then check similarity
            List<Expense> consistent = stripAmountOutliers(group);
            if (consistent.size() < 2) continue;
            if (!amountsAreSimilar(consistent)) continue;

            // Sort by date
            consistent.sort(Comparator.comparing(Expense::getDate));

            // Detect frequency from intervals
            RecurrenceType detectedFreq = detectFrequency(consistent);
            if (detectedFreq == null) continue;

            // Check if this pattern already exists as a recurring expense
            Expense representative = consistent.get(0);
            if (alreadyExists(representative, detectedFreq, existingRecurring)) continue;

            double avgAmount = consistent.stream().mapToDouble(Expense::getAmount).average().orElse(0);
            avgAmount = Math.round(avgAmount * 100.0) / 100.0;

            patterns.add(new DetectedPattern(
                representative.getDescription(),
                representative.getCategory(),
                avgAmount,
                detectedFreq,
                consistent.get(0).getDate(),
                new ArrayList<>(consistent)
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
    private boolean areBrandAliases(String a, String b) {
        String[][] aliases = {
            {"claude", "anthropic"},
            {"chatgpt", "openai"},
            {"google one", "google storage"},
            {"ms ", "microsoft"},
            {"netflix", "netflix.com"},
            {"spotify", "spotify ab"},
            {"amazon prime", "amzn prime", "amazon.com"},
            {"apple.com", "apple icloud", "apple one"},
        };
        for (String[] group : aliases) {
            boolean aMatch = false, bMatch = false;
            for (String alias : group) {
                if (a.contains(alias)) aMatch = true;
                if (b.contains(alias)) bMatch = true;
            }
            if (aMatch && bMatch) return true;
        }
        return false;
    }

    /** Check if two descriptions share a significant keyword (4+ chars, not noise). */
    private boolean shareSignificantWord(String a, String b) {
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

    private int commonPrefixLength(String a, String b) {
        int len = Math.min(a.length(), b.length());
        for (int i = 0; i < len; i++) {
            if (a.charAt(i) != b.charAt(i)) return i;
        }
        return len;
    }

    private String normalizeDescription(String description) {
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

    private boolean amountsAreSimilar(List<Expense> group) {
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

            if (descMatch && catMatch && amountClose) {
                return true;
            }
        }
        return false;
    }
}
