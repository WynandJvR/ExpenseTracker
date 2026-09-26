package com.wyn.expensetracker;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class AnomalyAndRecurringEditTest {

    @Test
    void newCategoryDismissKeysAreDistinctPerCategory() {
        // Three earlier months of history, so new-category alerts are enabled.
        List<Expense> expenses = List.of(
            new Expense(50, "Food", LocalDate.of(2024, 12, 5), "Lunch"),
            new Expense(50, "Food", LocalDate.of(2025, 1, 5), "Lunch"),
            new Expense(50, "Food", LocalDate.of(2025, 2, 5), "Lunch"),
            new Expense(100, "Pets", LocalDate.of(2025, 3, 2), "Vet"),
            new Expense(100, "Hobbies", LocalDate.of(2025, 3, 3), "Paint"));
        List<Anomaly> anomalies = AnomalyDetector.detect(expenses, YearMonth.of(2025, 3), "R", new CurrencyManager());
        Set<String> keys = new HashSet<>();
        long newCat = anomalies.stream().filter(a -> a.getType() == Anomaly.AnomalyType.NEW_CATEGORY)
            .peek(a -> keys.add(a.getDismissKey())).count();
        assertEquals(2, newCat);
        assertEquals(2, keys.size(), "Dismissing one new-category alert must not hide the other");
    }

    @Test
    void generatedRecurringOccurrenceIsNotFlaggedLarge() {
        RecurringExpense rent = new RecurringExpense(9000, "Housing", LocalDate.of(2025, 1, 1),
            "Rent", RecurrenceType.MONTHLY, null);
        List<Expense> expenses = new ArrayList<>();
        for (int i = 0; i < 20; i++) expenses.add(new Expense(100, "Food", LocalDate.of(2025, 2, 1 + i), "Snack"));
        LocalDate d = LocalDate.of(2025, 3, 1);
        expenses.add(new Expense(9000, "Housing", d, "Rent", rent.getId() + "|" + d, rent));
        expenses.add(new Expense(100, "Food", LocalDate.of(2025, 3, 2), "Snack"));

        List<Anomaly> anomalies = AnomalyDetector.detect(expenses, YearMonth.of(2025, 3), "R", new CurrencyManager());
        assertTrue(anomalies.stream().noneMatch(a -> a.getType() == Anomaly.AnomalyType.LARGE_TRANSACTION));
    }

    @Test
    void editingRecurringKeepsFlagsCurrencyAndTags() {
        RecurringExpense original = new RecurringExpense(20000, "Salary", LocalDate.of(2025, 1, 25),
            "Salary", RecurrenceType.MONTHLY, null);
        original.setIncome(true);
        original.setCurrency("USD");
        original.addTag("work");
        RecurringExpense edited = new RecurringExpense(21000, "Salary", LocalDate.of(2025, 1, 25),
            "Salary", RecurrenceType.MONTHLY, null);

        RecurringController.copyNonFormFields(original, edited);

        assertTrue(edited.isIncome());
        assertFalse(edited.isRefund());
        assertEquals("USD", edited.getCurrency());
        assertTrue(edited.hasTag("work"));
    }

    @Test
    void drillDownAmountsAreBaseCurrencyAndRefundsNegative() {
        CurrencyManager cm = new CurrencyManager();
        cm.setExchangeRates(Map.of("USD", 18.0));
        Expense usd = new Expense(10, "Online", LocalDate.of(2025, 3, 1), "App");
        usd.setCurrency("USD");
        Expense ref = new Expense(50, "Online", LocalDate.of(2025, 3, 2), "Refund");
        ref.setRefund(true);
        assertEquals(180.0, DrillDownDialog.signedBaseAmount(usd, cm), 0.001);
        assertEquals(-50.0, DrillDownDialog.signedBaseAmount(ref, cm), 0.001);
    }
}
