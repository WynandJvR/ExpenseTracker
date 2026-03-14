package com.wyn.expensetracker;

import java.util.List;

public class DuplicateDetector {

    /**
     * Flag import items that match existing expenses by date + amount.
     * Returns the number of duplicates found.
     */
    public static int flagDuplicates(List<ImportItem> items, List<Expense> existingExpenses) {
        int count = 0;
        for (ImportItem item : items) {
            for (Expense existing : existingExpenses) {
                if (isDateMatch(item, existing) && isAmountMatch(item, existing)) {
                    item.setDuplicate(true);
                    item.setDuplicateMatch(existing);
                    count++;
                    break;
                }
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
}
