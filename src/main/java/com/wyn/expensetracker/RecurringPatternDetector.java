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

        // Group by normalized description + category
        Map<String, List<Expense>> groups = new LinkedHashMap<>();
        for (Expense expense : oneTimeExpenses) {
            String key = buildGroupKey(expense);
            groups.computeIfAbsent(key, k -> new ArrayList<>()).add(expense);
        }

        List<DetectedPattern> patterns = new ArrayList<>();

        for (Map.Entry<String, List<Expense>> entry : groups.entrySet()) {
            List<Expense> group = entry.getValue();
            if (group.size() < 2) continue;

            // Check if amounts are similar (within 10% of the median)
            if (!amountsAreSimilar(group)) continue;

            // Sort by date
            group.sort(Comparator.comparing(Expense::getDate));

            // Detect frequency from intervals
            RecurrenceType detectedFreq = detectFrequency(group);
            if (detectedFreq == null) continue;

            // Check if this pattern already exists as a recurring expense
            Expense representative = group.get(0);
            if (alreadyExists(representative, detectedFreq, existingRecurring)) continue;

            double avgAmount = group.stream().mapToDouble(Expense::getAmount).average().orElse(0);
            // Round to 2 decimal places
            avgAmount = Math.round(avgAmount * 100.0) / 100.0;

            patterns.add(new DetectedPattern(
                representative.getDescription(),
                representative.getCategory(),
                avgAmount,
                detectedFreq,
                group.get(0).getDate(),
                new ArrayList<>(group)
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

    private String normalizeDescription(String description) {
        if (description == null || description.trim().isEmpty()) return "";
        String normalized = description.toLowerCase().trim();
        // Remove trailing reference numbers (common in bank statements)
        normalized = normalized.replaceAll("\\s*#\\d+$", "");
        normalized = normalized.replaceAll("\\s*ref\\s*:?\\s*\\d+$", "");
        // Remove trailing dates in common formats
        normalized = normalized.replaceAll("\\s*\\d{2}/\\d{2}/\\d{2,4}$", "");
        // Remove multiple spaces
        normalized = normalized.replaceAll("\\s+", " ");
        return normalized;
    }

    private boolean amountsAreSimilar(List<Expense> group) {
        double[] amounts = group.stream().mapToDouble(Expense::getAmount).sorted().toArray();
        int mid = amounts.length / 2;
        double median = (amounts.length % 2 == 0)
            ? (amounts[mid - 1] + amounts[mid]) / 2.0
            : amounts[mid];
        double tolerance = median * 0.10; // 10% tolerance

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

        // Check each frequency with tolerance
        if (isWithinRange(avgInterval, 7, 2)) return RecurrenceType.WEEKLY;
        if (isWithinRange(avgInterval, 14, 3)) return RecurrenceType.BIWEEKLY;
        if (isWithinRange(avgInterval, 30, 5)) return RecurrenceType.MONTHLY;
        if (isWithinRange(avgInterval, 91, 15)) return RecurrenceType.QUARTERLY;
        if (isWithinRange(avgInterval, 365, 30)) return RecurrenceType.YEARLY;

        // Also check consistency — if intervals vary too much, skip
        double stdDev = calculateStdDev(intervals, avgInterval);
        if (stdDev > avgInterval * 0.3) return null; // Too inconsistent

        // Try to match on best-fit even with wider range
        if (avgInterval >= 25 && avgInterval <= 35) return RecurrenceType.MONTHLY;
        if (avgInterval >= 11 && avgInterval <= 17) return RecurrenceType.BIWEEKLY;
        if (avgInterval >= 5 && avgInterval <= 9) return RecurrenceType.WEEKLY;
        if (avgInterval >= 80 && avgInterval <= 100) return RecurrenceType.QUARTERLY;
        if (avgInterval >= 335 && avgInterval <= 395) return RecurrenceType.YEARLY;

        return null;
    }

    private boolean isWithinRange(double value, double target, double tolerance) {
        return Math.abs(value - target) <= tolerance;
    }

    private double calculateStdDev(List<Long> values, double mean) {
        double sumSquaredDiffs = values.stream()
            .mapToDouble(v -> Math.pow(v - mean, 2))
            .sum();
        return Math.sqrt(sumSquaredDiffs / values.size());
    }

    private boolean alreadyExists(Expense representative, RecurrenceType freq,
                                   List<RecurringExpense> existingRecurring) {
        String normalizedDesc = normalizeDescription(representative.getDescription());
        for (RecurringExpense existing : existingRecurring) {
            String existingDesc = normalizeDescription(existing.getDescription());
            boolean descMatch = normalizedDesc.equals(existingDesc)
                || (!normalizedDesc.isEmpty() && existingDesc.contains(normalizedDesc))
                || (!existingDesc.isEmpty() && normalizedDesc.contains(existingDesc));
            boolean catMatch = existing.getCategory().equalsIgnoreCase(representative.getCategory());
            boolean amountClose = Math.abs(existing.getAmount() - representative.getAmount())
                <= representative.getAmount() * 0.10;

            if (descMatch && catMatch && amountClose) {
                return true;
            }
        }
        return false;
    }
}
