package com.wyn.expensetracker;

import javafx.collections.FXCollections;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The Overview's "Money out" for a future month (SharedState.netSpendForMonth) and the
 * Projections tab (ProjectionEngine via currentProjection) are separate code paths that
 * must agree, including skip/edit overrides and history-based variable spend.
 */
class ProjectionAgreementTest {

    @Test
    void overviewAndProjectionAgreeForFutureMonths(@TempDir Path dir) {
        ExpenseManager manager = new ExpenseManager();
        SharedState state = new SharedState(manager, new FileStorage(dir.toString()),
            FXCollections.observableArrayList(), new HashMap<>(), null, null);

        YearMonth now = YearMonth.now();
        LocalDate start = now.minusMonths(6).atDay(3);
        RecurringExpense weekly = new RecurringExpense(100, "Groceries", start, "Veg box", RecurrenceType.WEEKLY, null);
        RecurringExpense quarterly = new RecurringExpense(900, "Insurance", start.withDayOfMonth(15), "Cover",
            RecurrenceType.QUARTERLY, null);
        manager.addExpense(weekly);
        manager.addExpense(quarterly);

        // Some history so the projection also has variable spend to carry forward.
        List<Expense> history = new ArrayList<>();
        for (int m = 1; m <= 6; m++) {
            YearMonth ym = now.minusMonths(m);
            history.add(new Expense(250 + 10 * m, "Eating Out", ym.atDay(10), "Cafe"));
            history.add(new Expense(80, "Transport", ym.atDay(20), "Taxi"));
        }
        manager.addExpenses(history);

        // Skip one future occurrence and change another.
        LocalDate skipDate = manager.getUpcomingRecurring(now.plusMonths(1).atDay(1), now.plusMonths(1).atEndOfMonth())
            .stream().filter(e -> "Veg box".equals(e.getDescription())).findFirst().orElseThrow().getDate();
        Expense toSkip = new Expense(100, "Groceries", skipDate, "Veg box", OccurrenceOverride.key(weekly.getId(), skipDate), weekly);
        manager.skipOccurrence(toSkip);

        state.syncExpenseList();
        state.invalidateSpendCache();

        ProjectionEngine.ProjectionResult projection = state.currentProjection();
        assertFalse(projection.monthProjections.isEmpty());
        for (ProjectionEngine.MonthProjection mp : projection.monthProjections) {
            if (!mp.month.isAfter(now)) continue;
            assertEquals(mp.projectedExpenses, state.netSpendForMonth(mp.month), 0.01,
                "Overview vs Projections for " + mp.month);
        }
    }

    /** Recurring refunds (cashback) net exactly once, even when they exceed the category's recurring spend. */
    @Test
    void recurringRefundsNetOnceOnBothPaths(@TempDir Path dir) {
        double[][] scenarios = { // {recurring box amount (0 = none), recurring cashback amount}
            {0, 50}, {100, 150}, {0, 150}, {100, 50}
        };
        for (double[] sc : scenarios) {
            ExpenseManager manager = new ExpenseManager();
            SharedState state = new SharedState(manager, new FileStorage(dir.resolve("s" + (int) sc[0] + "_" + (int) sc[1]).toString()),
                FXCollections.observableArrayList(), new HashMap<>(), null, null);
            YearMonth now = YearMonth.now();
            LocalDate start = now.minusMonths(6).atDay(5);
            if (sc[0] > 0) {
                manager.addExpense(new RecurringExpense(sc[0], "Groceries", start, "Box", RecurrenceType.MONTHLY, null));
            }
            RecurringExpense cashback = new RecurringExpense(sc[1], "Groceries", start.withDayOfMonth(6), "Cashback",
                RecurrenceType.MONTHLY, null);
            cashback.setRefund(true);
            manager.addExpense(cashback);
            List<Expense> history = new ArrayList<>();
            for (int m = 1; m <= 6; m++) history.add(new Expense(500, "Groceries", now.minusMonths(m).atDay(12), "Shop"));
            manager.addExpenses(history);
            state.syncExpenseList();
            state.invalidateSpendCache();

            for (ProjectionEngine.MonthProjection mp : state.currentProjection().monthProjections) {
                if (!mp.month.isAfter(now)) continue;
                assertEquals(mp.projectedExpenses, state.netSpendForMonth(mp.month), 0.01,
                    "box " + sc[0] + " cashback " + sc[1] + " in " + mp.month);
            }
        }
    }
}
