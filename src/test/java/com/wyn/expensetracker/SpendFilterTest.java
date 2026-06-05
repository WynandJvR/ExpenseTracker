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
 * Locks in the single-source-of-truth spend filter shared by every analytics chart:
 * refunds/income/excluded never count as spend, and a recurring projection is dropped
 * only when a matching imported transaction actually covers it (selective dedup), not
 * blanket-dropped for any month that happens to contain an import.
 */
class SpendFilterTest {

    @TempDir
    Path tempDir;

    private SharedState state;

    @BeforeEach
    void setUp() {
        ExpenseManager manager = new ExpenseManager();
        FileStorage storage = new FileStorage(tempDir.toString());
        state = new SharedState(manager, storage,
            FXCollections.observableArrayList(), new HashMap<>(), null, null);
    }

    private Expense expense(double amount, String cat, String desc, LocalDate date) {
        return new Expense(amount, cat, date, desc);
    }

    // ======================== EXCLUSIONS ========================

    @Test
    void refundIncomeExcluded_doNotCountAsSpend() {
        Expense normal = expense(100, "Food", "Groceries", LocalDate.of(2025, 3, 1));
        Expense refund = expense(40, "Food", "Returned item", LocalDate.of(2025, 3, 2));
        refund.setRefund(true);
        Expense income = expense(5000, "Salary", "Pay", LocalDate.of(2025, 3, 3));
        income.setIncome(true);
        Expense excluded = expense(80, "Food", "Reimbursed", LocalDate.of(2025, 3, 4));
        excluded.setExcluded(true);
        state.getExpenseList().setAll(normal, refund, income, excluded);

        Set<YearMonth> imported = state.importedMonths();
        assertTrue(state.countsAsSpend(normal, imported));
        assertFalse(state.countsAsSpend(refund, imported), "Refund must not count as spend");
        assertFalse(state.countsAsSpend(income, imported), "Income must not count as spend");
        assertFalse(state.countsAsSpend(excluded, imported), "Excluded must not count as spend");
    }

    @Test
    void filterExpensesByPeriod_omitsRefunds() {
        Expense normal = expense(100, "Food", "Groceries", LocalDate.of(2025, 3, 1));
        Expense refund = expense(40, "Food", "Returned item", LocalDate.of(2025, 3, 2));
        refund.setRefund(true);
        state.getExpenseList().setAll(normal, refund);

        List<Expense> spend = state.filterExpensesByPeriod(
            "By Month", 2025, YearMonth.of(2025, 3), YearMonth.of(2025, 3));

        assertEquals(1, spend.size());
        assertSame(normal, spend.get(0));
    }

    // ======================== SELECTIVE RECURRING DEDUP ========================

    private Expense recurringInstance(RecurringExpense src, LocalDate date) {
        return new Expense(src.getAmount(), src.getCategory(), date, src.getDescription(),
            "rid-" + date, src);
    }

    @Test
    void recurringDroppedOnlyWhenMatchingImportExists() {
        LocalDate d = LocalDate.of(2025, 3, 1);
        RecurringExpense netflix = new RecurringExpense(100, "Bills", d, "Netflix",
            RecurrenceType.MONTHLY, null);
        Expense recurring = recurringInstance(netflix, d);

        // A matching imported transaction in the same month (desc contains "netflix", amount within 20%).
        Expense matchingImport = expense(100, "Bills", "NETFLIX subscription", d);
        matchingImport.setImportId("imp-1");

        state.getExpenseList().setAll(recurring, matchingImport);
        Set<YearMonth> imported = state.importedMonths();

        assertFalse(state.countsAsSpend(recurring, imported),
            "Recurring projection should be dropped when a matching import covers it");
        assertTrue(state.countsAsSpend(matchingImport, imported),
            "The real imported transaction still counts");
    }

    @Test
    void recurringKept_whenMonthHasImportsButNoMatch() {
        LocalDate d = LocalDate.of(2025, 3, 1);
        RecurringExpense netflix = new RecurringExpense(100, "Bills", d, "Netflix",
            RecurrenceType.MONTHLY, null);
        Expense recurring = recurringInstance(netflix, d);

        // An unrelated import in the same month — month "has imports" but nothing matches Netflix.
        Expense unrelatedImport = expense(250, "Groceries", "Woolworths", LocalDate.of(2025, 3, 15));
        unrelatedImport.setImportId("imp-2");

        state.getExpenseList().setAll(recurring, unrelatedImport);
        Set<YearMonth> imported = state.importedMonths();

        assertTrue(imported.contains(YearMonth.of(2025, 3)), "Month should register as having imports");
        assertTrue(state.countsAsSpend(recurring, imported),
            "Recurring must still count when no matching import exists (selective, not blanket, dedup)");
        assertTrue(state.countsAsSpend(unrelatedImport, imported));
    }
}
