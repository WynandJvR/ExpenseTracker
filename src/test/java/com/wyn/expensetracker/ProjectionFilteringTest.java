package com.wyn.expensetracker;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class ProjectionFilteringTest {

    private final CurrencyManager cm = new CurrencyManager();
    private final ProjectionEngine engine = new ProjectionEngine();
    private static final YearMonth NOW = YearMonth.of(2025, 6);

    private ProjectionEngine.ProjectionInput input(List<Expense> all, List<RecurringExpense> rec,
                                                   Map<YearMonth, Double> incomes, double recurringIncome) {
        return new ProjectionEngine.ProjectionInput(all, rec, incomes, recurringIncome, Map.of(), cm);
    }

    @Test
    void incomeRefundExcludedTemplatesAreNotRecurringSpend() {
        LocalDate start = LocalDate.of(2025, 1, 1);
        RecurringExpense rent = new RecurringExpense(5000, "Housing", start, "Rent", RecurrenceType.MONTHLY, null);
        RecurringExpense salary = new RecurringExpense(20000, "Salary", start, "Salary", RecurrenceType.MONTHLY, null);
        salary.setIncome(true);
        RecurringExpense cashback = new RecurringExpense(50, "Bills", start, "Cashback", RecurrenceType.MONTHLY, null);
        cashback.setRefund(true);
        RecurringExpense transfer = new RecurringExpense(3000, "Transfer", start, "Savings", RecurrenceType.MONTHLY, null);
        transfer.setExcluded(true);

        ProjectionEngine.ProjectionResult r = engine.project(
            input(List.of(), List.of(rent, salary, cashback, transfer), Map.of(), 0), NOW);
        ProjectionEngine.MonthProjection first = r.monthProjections.get(0);
        assertEquals(5000.0, first.projectedRecurringExpenses, 0.001);
        assertEquals(20000.0, first.projectedIncome, 0.001, "Recurring income template feeds projected income");
    }

    @Test
    void importCoveringRecurringIsNotAlsoVariableSpend() {
        RecurringExpense netflix = new RecurringExpense(200, "Bills", LocalDate.of(2025, 1, 5),
            "Netflix", RecurrenceType.MONTHLY, null);
        List<Expense> all = new ArrayList<>();
        for (int m = 1; m <= 5; m++) {
            LocalDate d = LocalDate.of(2025, m, 5);
            all.add(new Expense(200, "Bills", d, "Netflix", netflix.getId() + "|" + d, netflix));
            Expense imp = new Expense(200, "Bills", d, "NETFLIX.COM");
            imp.setImportId("i" + m);
            all.add(imp);
            all.add(new Expense(1000, "Food", d, "Groceries"));
        }
        ProjectionEngine.ProjectionResult r = engine.project(input(all, List.of(netflix), Map.of(), 0), NOW);
        ProjectionEngine.MonthProjection first = r.monthProjections.get(0);
        assertEquals(200.0, first.projectedRecurringExpenses, 0.001);
        assertEquals(1000.0, first.projectedVariableExpenses, 0.001,
            "Only groceries are variable; the Netflix import is represented by the recurring projection");
    }

    @Test
    void currentPartialMonthIsExcludedFromTrend() {
        List<Expense> all = new ArrayList<>();
        for (int m = 1; m <= 5; m++) all.add(new Expense(1000, "Food", LocalDate.of(2025, m, 10), "Groceries"));
        all.add(new Expense(50, "Food", LocalDate.of(2025, 6, 1), "Coffee")); // partial current month
        ProjectionEngine.ProjectionResult r = engine.project(input(all, List.of(), Map.of(), 0), NOW);
        assertEquals(5, r.dataMonthsAvailable);
        assertEquals(1000.0, r.monthProjections.get(0).projectedVariableExpenses, 0.001);
    }

    @Test
    void refundsNetAgainstVariableSpend() {
        List<Expense> all = new ArrayList<>();
        for (int m = 1; m <= 5; m++) {
            all.add(new Expense(1000, "Clothing", LocalDate.of(2025, m, 10), "Shop"));
            Expense ref = new Expense(400, "Clothing", LocalDate.of(2025, m, 12), "Return");
            ref.setRefund(true);
            all.add(ref);
        }
        ProjectionEngine.ProjectionResult r = engine.project(input(all, List.of(), Map.of(), 0), NOW);
        assertEquals(600.0, r.monthProjections.get(0).projectedVariableExpenses, 0.001);
    }

    @Test
    void historicalIncomeIsNotDoubleCounted() {
        List<Expense> all = new ArrayList<>();
        for (int m = 4; m <= 6; m++) {
            Expense salary = new Expense(20000, "Salary", LocalDate.of(2025, m, 25), "Pay");
            salary.setIncome(true);
            all.add(salary);
            Expense ref = new Expense(100, "Food", LocalDate.of(2025, m, 3), "Refund");
            ref.setRefund(true);
            ref.setIncome(true); // legacy flagging
            all.add(ref);
        }
        Map<YearMonth, Double> incomes = Map.of(YearMonth.of(2025, 5), 20000.0);
        ProjectionEngine.ProjectionInput in = input(all, List.of(), incomes, 20000);
        // Apr, May, Jun: actual salary each month = 60000 (planned/recurring not added on top,
        // refunds not income).
        assertEquals(60000.0, engine.computeHistoricalIncome(in, NOW, cm), 0.001);
    }
}
