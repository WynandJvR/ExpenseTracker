package com.wyn.expensetracker;

import java.util.List;

public class DuplicateDetector {

    /**
     * Flag import items that match existing expenses by date + amount,
     * with optional description similarity for stronger matching.
     * Returns the number of duplicates found.
     */
    public static int flagDuplicates(List<ImportItem> items, List<Expense> existingExpenses) {
        int count = 0;
        for (ImportItem item : items) {
            Expense bestMatch = null;
            boolean strongMatch = false;

            for (Expense existing : existingExpenses) {
                if (isDateMatch(item, existing) && isAmountMatch(item, existing)) {
                    if (isDescriptionSimilar(item.getDescription(), existing.getDescription())) {
                        // Strong match: date + amount + description
                        bestMatch = existing;
                        strongMatch = true;
                        break;
                    } else if (bestMatch == null) {
                        // Weak match: date + amount only (keep looking for strong)
                        bestMatch = existing;
                    }
                }
            }

            if (bestMatch != null) {
                item.setDuplicate(true);
                item.setDuplicateMatch(bestMatch);
                if (!strongMatch) {
                    item.setStatus("Possible duplicate (amount + date match)");
                }
                count++;
            }
        }
        return count;
    }

    private static boolean isDateMatch(ImportItem item, Expense expense) {
        if (item.getDate() == null || expense.getDate() == null) return false;
        return item.getDate().equals(expense.getDate());
    }

    private static boolean isAmountMatch(ImportItem item, Expense expense) {
        return Math.abs(item.getAmount() - expense.getAmount()) < 0.01;
    }

    private static boolean isDescriptionSimilar(String a, String b) {
        if (a == null || b == null) return false;
        String na = normalize(a);
        String nb = normalize(b);
        if (na.isEmpty() || nb.isEmpty()) return false;
        return na.equals(nb) || na.contains(nb) || nb.contains(na);
    }

    private static String normalize(String s) {
        return s.toLowerCase().trim()
            .replaceAll("\\s*#\\d+$", "")
            .replaceAll("\\s*ref\\s*:?\\s*\\d+$", "")
            .replaceAll("\\s+", " ");
    }
}
