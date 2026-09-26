package com.wyn.expensetracker;

import javafx.collections.FXCollections;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pure-logic tests for the shared spend/income helpers in SharedState: refund netting,
 * the single monthly income rule and one-to-one recurring coverage.
 */
class NetSpendAndIncomeTest {

    @TempDir
    Path tempDir;

    private SharedState state;
    private Map<YearMonth, Double> incomes;

    @BeforeEach
    void setUp() {
        incomes = new HashMap<>();
        state = new SharedState(new ExpenseManager(), new FileStorage(tempDir.toString()),
            FXCollections.observableArrayList(), incomes, null, null);
    }

    private static Expense exp(double amount, String cat, String desc, LocalDate date) {
        return new Expense(amount, cat, date, desc);
    }

    private static Expense refund(double amount, String cat, LocalDate date) {
        Expense e = exp(amount, cat, "Refund", date);
        e.setRefund(true);
        return e;
    }

    // ======================== REFUND NETTING ========================

    @Test
    void refundReducesSpendInItsCategory() {
        LocalDate d = LocalDate.of(2025, 3, 5);
        List<Expense> items = List.of(
            exp(500, "Clothing", "Shoes", d),
            refund(200, "Clothing", d),
            exp(100, "Food", "Lunch", d));
        state.getExpenseList().setAll(items);

        Map<String, Double> byCat = state.spendByCategory(items);
        assertEquals(300.0, byCat.get("Clothing"), 0.001);
        assertEquals(100.0, byCat.get("Food"), 0.001);
        assertEquals(400.0, state.netSpend(items), 0.001);
        assertEquals(400.0, state.netSpendForMonth(YearMonth.of(2025, 3)), 0.001);
    }

    @Test
    void refundExceedingSpendClampsCategoryAtZero() {
        LocalDate d = LocalDate.of(2025, 3, 5);
        List<Expense> items = List.of(
            exp(50, "Clothing", "Socks", d),
            refund(200, "Clothing", d),
            exp(100, "Food", "Lunch", d));
        state.getExpenseList().setAll(items);

        Map<String, Double> byCat = state.spendByCategory(items);
        assertFalse(byCat.containsKey("Clothing"));
        assertEquals(100.0, state.netSpend(items), 0.001, "Total reconciles with clamped categories");
    }

    @Test
    void legacyRefundAlsoFlaggedIncome_isTreatedAsRefundNotIncome() {
        LocalDate d = LocalDate.of(2025, 3, 5);
        Expense legacy = refund(200, "Clothing", d);
        legacy.setIncome(true);
        List<Expense> items = List.of(exp(500, "Clothing", "Coat", d), legacy);
        state.getExpenseList().setAll(items);

        assertFalse(SharedState.isIncomeItem(legacy));
        assertEquals(300.0, state.netSpend(items), 0.001);
        assertEquals(0.0, state.actualIncome(YearMonth.of(2025, 3)), 0.001);
    }

    @Test
    void excludedIsNeitherSpendNorIncome() {
        LocalDate d = LocalDate.of(2025, 3, 5);
        Expense transferOut = exp(1000, "Transfer", "To savings", d);
        transferOut.setExcluded(true);
        Expense transferIn = exp(1000, "Transfer", "From savings", d);
        transferIn.setIncome(true);
        transferIn.setExcluded(true);
        state.getExpenseList().setAll(transferOut, transferIn);

        assertEquals(0.0, state.netSpend(state.getExpenseList()), 0.001);
        assertEquals(0.0, state.actualIncome(YearMonth.of(2025, 3)), 0.001);
    }

    // ======================== INCOME RULE ========================

    @Test
    void resolveMonthlyIncome_pastMonthUsesActualElsePlanned() {
        YearMonth now = YearMonth.of(2025, 6);
        assertEquals(18000, SharedState.resolveMonthlyIncome(18000, 20000, YearMonth.of(2025, 4), now), 0.001);
        assertEquals(20000, SharedState.resolveMonthlyIncome(0, 20000, YearMonth.of(2025, 4), now), 0.001);
    }

    @Test
    void resolveMonthlyIncome_currentMonthSmallCreditDoesNotWipeSalary() {
        YearMonth now = YearMonth.of(2025, 6);
        assertEquals(20000, SharedState.resolveMonthlyIncome(35, 20000, now, now), 0.001);
        assertEquals(22000, SharedState.resolveMonthlyIncome(22000, 20000, now, now), 0.001);
        assertEquals(20000, SharedState.resolveMonthlyIncome(0, 20000, now.plusMonths(2), now), 0.001);
    }

    @Test
    void incomeForMonth_usesIncomeTransactionsAndPlannedFallback() {
        YearMonth past = YearMonth.now().minusMonths(2);
        state.setRecurringIncome(20000);
        Expense salary = exp(21000, "Salary", "Pay", past.atDay(25));
        salary.setIncome(true);
        state.getExpenseList().setAll(salary);

        assertEquals(21000, state.incomeForMonth(past), 0.001);
        assertEquals(20000, state.incomeForMonth(past.minusMonths(1)), 0.001, "No actual -> recurring default");
        incomes.put(past.minusMonths(1), 15000.0);
        assertEquals(15000, state.incomeForMonth(past.minusMonths(1)), 0.001, "Month entry beats default");
    }

    // ======================== ONE-TO-ONE RECURRING COVERAGE ========================

    private static Expense occurrence(RecurringExpense src, LocalDate date) {
        return new Expense(src.getAmount(), src.getCategory(), date, src.getDescription(),
            src.getId() + "|" + date, src);
    }

    private static Expense imported(double amount, String desc, LocalDate date, String id) {
        Expense e = new Expense(amount, "Bills", date, desc);
        e.setImportId(id);
        return e;
    }

    @Test
    void oneImportCoversOnlyOneOccurrence() {
        RecurringExpense weekly = new RecurringExpense(100, "Bills", LocalDate.of(2025, 3, 1),
            "Gym", RecurrenceType.WEEKLY, null);
        Expense o1 = occurrence(weekly, LocalDate.of(2025, 3, 1));
        Expense o2 = occurrence(weekly, LocalDate.of(2025, 3, 8));
        Expense imp = imported(100, "GYM DEBIT", LocalDate.of(2025, 3, 2), "i1");
        state.getExpenseList().setAll(o1, o2, imp);

        assertFalse(state.countsAsSpend(o1), "First occurrence is covered by the import");
        assertTrue(state.countsAsSpend(o2), "Second occurrence stays: the import can only cover one");
        assertTrue(state.coversRecurring(imp));
        assertEquals(200.0, state.netSpend(state.getExpenseList()), 0.001);
    }

    @Test
    void twoImportsCoverTwoOccurrences_closestAmountWins() {
        RecurringExpense a = new RecurringExpense(100, "Bills", LocalDate.of(2025, 3, 1),
            "Netflix", RecurrenceType.MONTHLY, null);
        RecurringExpense b = new RecurringExpense(115, "Bills", LocalDate.of(2025, 3, 3),
            "Netflix", RecurrenceType.MONTHLY, null);
        Expense oa = occurrence(a, LocalDate.of(2025, 3, 1));
        Expense ob = occurrence(b, LocalDate.of(2025, 3, 3));
        Expense i1 = imported(115, "NETFLIX.COM", LocalDate.of(2025, 3, 3), "i1");
        Expense i2 = imported(100, "NETFLIX.COM", LocalDate.of(2025, 3, 1), "i2");
        state.getExpenseList().setAll(oa, ob, i1, i2);

        SharedState.RecurringCoverage cov = state.getRecurringCoverage();
        assertTrue(cov.coveredRecurringIds.contains(oa.getRecurringId()));
        assertTrue(cov.coveredRecurringIds.contains(ob.getRecurringId()));
        assertEquals(215.0, state.netSpend(state.getExpenseList()), 0.001);
    }

    @Test
    void coverageCacheInvalidatesWhenListChanges() {
        RecurringExpense netflix = new RecurringExpense(100, "Bills", LocalDate.of(2025, 3, 1),
            "Netflix", RecurrenceType.MONTHLY, null);
        Expense occ = occurrence(netflix, LocalDate.of(2025, 3, 1));
        state.getExpenseList().setAll(occ);
        assertTrue(state.countsAsSpend(occ));

        state.getExpenseList().add(imported(100, "Netflix", LocalDate.of(2025, 3, 1), "i1"));
        assertFalse(state.countsAsSpend(occ), "Adding a matching import must invalidate the cache");
    }

    @Test
    void refundImportNeverCoversRecurring() {
        RecurringExpense netflix = new RecurringExpense(100, "Bills", LocalDate.of(2025, 3, 1),
            "Netflix", RecurrenceType.MONTHLY, null);
        Expense occ = occurrence(netflix, LocalDate.of(2025, 3, 1));
        Expense ref = imported(100, "Netflix refund", LocalDate.of(2025, 3, 4), "i1");
        ref.setRefund(true);
        state.getExpenseList().setAll(occ, ref);

        assertTrue(state.countsAsSpend(occ));
        assertEquals(0.0, state.netSpend(state.getExpenseList()), 0.001, "Refund nets the bill");
    }

    @Test
    void futureMonthIncludesScheduledRecurringBills() {
        ExpenseManager manager = state.getManager();
        RecurringExpense rent = new RecurringExpense(8000, "Housing", LocalDate.now().withDayOfMonth(1),
            "Rent", RecurrenceType.MONTHLY, null);
        manager.loadExpenses(List.of(rent));
        state.syncExpenseList();

        YearMonth future = YearMonth.now().plusMonths(2);
        assertEquals(8000.0, state.netSpendForMonth(future), 0.001);
    }
}
