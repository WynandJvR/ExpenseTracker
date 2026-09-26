package com.wyn.expensetracker;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** Regression tests for the third calculation audit round (C-R3-1..4, I-R3-1). Synthetic data only. */
class CalculationAuditRound3Test {

    private static Expense imported(double amount, String cat, String desc, LocalDate date, String id) {
        Expense e = new Expense(amount, cat, date, desc);
        e.setImportId(id);
        return e;
    }

    // ---------------------------------------------------------------- C-R3-1 / C-R3-2

    private static Debt loan() {
        Debt loan = new Debt("L", "Loan", 100_000, 12, 60, LocalDate.of(2024, 9, 1), "MONTHLY", 0, "ZAR");
        loan.setPaymentKeyword("LOANPAY");
        return loan;
    }

    private static void assertLateDebitNotDoubleCounted(int debitDay) {
        Debt loan = loan();
        double inst = loan.getScheduledPayment();
        List<Expense> ledger = new ArrayList<>();
        for (int m = 7; m <= 9; m++) {
            ledger.add(imported(inst, "Debt", "DEBIT LOANPAY " + m, LocalDate.of(2026, m, debitDay), "i" + m));
        }
        LocalDate asOf = LocalDate.of(2026, 9, 26);
        Debt.BalanceStatus st = loan.resolveBalance(List.of(), ledger, new CurrencyManager(), asOf);
        double scheduled = loan.getScheduledBalance(asOf);
        // Paying a week or two late costs a little extra interest, never a whole instalment.
        assertEquals(scheduled, st.balance, inst * 0.1, "debit on day " + debitDay);
        assertTrue(st.balance > scheduled - 1.0, "must not be one instalment short (day " + debitDay + ")");

        // Instalments Oct 2024 .. Jun 2026 (21) are assumed paid; Jul's is the first debit.
        assertEquals(21, st.assumedPeriods);
        double assumed = 0;
        for (Debt.AmortizationEntry en : loan.getAmortizationSchedule()) {
            if (en.month <= 21) assumed += en.payment;
        }
        assertEquals(assumed, st.assumedPaid, 0.01);
        assertEquals(3 * inst, st.totalPaid, 0.01);
        assertEquals(assumed + 3 * inst, st.totalPaidInclAssumed(), 0.01);
    }

    @Test
    void debitOnTheSeventhPaysThatMonthsInstalment() {
        assertLateDebitNotDoubleCounted(7);
    }

    @Test
    void debitOnTheFifteenthPaysThatMonthsInstalment() {
        assertLateDebitNotDoubleCounted(15);
    }

    @Test
    void estimatedDebtReportsInstalmentsAssumedPaid() {
        Debt debt = new Debt("d1", "Car", 12000, 0, 12, LocalDate.of(2025, 1, 1), "MONTHLY", 1000, "ZAR");
        Debt.BalanceStatus st = debt.resolveBalance(List.of(), List.of(), null, LocalDate.of(2025, 4, 15));
        assertTrue(st.estimated);
        assertEquals(3, st.assumedPeriods);
        assertEquals(3000, st.assumedPaid, 0.001);
        assertEquals(0, st.totalPaid, 0.001);
    }

    // ---------------------------------------------------------------- C-R3-3

    @Test
    void recurringRefundIsNettedNotDropped() {
        YearMonth now = YearMonth.of(2025, 6);
        CurrencyManager cm = new CurrencyManager();
        List<Expense> all = new ArrayList<>();
        for (int m = 0; m < 6; m++) {
            all.add(new Expense(500, "Groceries", now.minusMonths(6 - m).atDay(10), "Shop"));
        }
        LocalDate start = LocalDate.of(2025, 1, 1);
        RecurringExpense box = new RecurringExpense(100, "Groceries", start, "Veg box", RecurrenceType.MONTHLY, null);
        RecurringExpense cashback = new RecurringExpense(50, "Groceries", start, "Cashback", RecurrenceType.MONTHLY, null);
        cashback.setRefund(true);

        ProjectionEngine.ProjectionResult r = new ProjectionEngine().project(
            new ProjectionEngine.ProjectionInput(all, List.of(box, cashback), Map.of(), 0, Map.of(), cm), now);
        ProjectionEngine.MonthProjection first = r.monthProjections.get(0);
        assertEquals(50, first.projectedRecurringExpenses, 0.001, "100 box - 50 cashback");
        assertEquals(500, first.projectedVariableExpenses, 0.001);
        assertEquals(550, first.projectedExpenses, 0.001, "same as the Overview's clamped cell");

        // A refund series larger than the recurring spend offsets the variable spend.
        RecurringExpense bigCashback = new RecurringExpense(150, "Groceries", start, "Cashback", RecurrenceType.MONTHLY, null);
        bigCashback.setRefund(true);
        r = new ProjectionEngine().project(
            new ProjectionEngine.ProjectionInput(all, List.of(box, bigCashback), Map.of(), 0, Map.of(), cm), now);
        first = r.monthProjections.get(0);
        assertEquals(0, first.projectedRecurringExpenses, 0.001);
        assertEquals(450, first.projectedVariableExpenses, 0.001);
        assertEquals(450, first.categoryBreakdown.get("Groceries"), 0.001);
    }

    // ---------------------------------------------------------------- C-R3-4

    @Test
    void coverageOfLongDailySeriesIsFastAndOneToOne() {
        LocalDate start = LocalDate.of(2023, 1, 1);
        RecurringExpense daily = new RecurringExpense(30, "Transport", start, "Bus", RecurrenceType.DAILY, null);
        List<Expense> all = new ArrayList<>();
        int days = 3 * 365;
        for (int d = 0; d < days; d++) {
            LocalDate date = start.plusDays(d);
            all.add(new Expense(30, "Transport", date, "Bus", "bus|" + d, daily));
            if (d % 7 != 3) all.add(imported(30, "Transport", "BUS FARE", date.plusDays(d % 3 == 0 ? 1 : 0), "b" + d));
        }
        long t0 = System.nanoTime();
        SharedState.RecurringCoverage cov = SharedState.computeRecurringCoverage(all, new CurrencyManager());
        long ms = (System.nanoTime() - t0) / 1_000_000;
        long imports = all.stream().filter(e -> e.getImportId() != null).count();
        assertEquals(imports, cov.coveringImports.size(), "every import covers one occurrence");
        assertEquals(imports, cov.coveredRecurringIds.size());
        assertTrue(ms < 1000, "coverage took " + ms + " ms");

        // The projection and the anomaly detector accept the precomputed coverage.
        ProjectionEngine.ProjectionInput input = new ProjectionEngine.ProjectionInput(
            all, List.of(), Map.of(), 0, Map.of(), new CurrencyManager());
        ProjectionEngine engine = new ProjectionEngine();
        YearMonth now = YearMonth.of(2025, 12);
        assertEquals(engine.project(input, now).currentBalance,
            engine.project(input, now, cov).currentBalance, 0.001);
        assertEquals(AnomalyDetector.detect(all, now, "R", new CurrencyManager()).size(),
            AnomalyDetector.detect(all, now, "R", new CurrencyManager(), cov).size());
    }

    // ---------------------------------------------------------------- I-R3-1

    @Test
    void patternDetectionIgnoresTransfersAndOnlyRemovesLiveRows() {
        List<Expense> ledger = new ArrayList<>();
        for (int m = 1; m <= 4; m++) {
            ledger.add(imported(200, "Subscriptions", "STREAMCO", LocalDate.of(2025, m, 5), "s" + m));
        }
        Expense transfer = imported(1000, "Transfer", "TRF SAVINGS", LocalDate.of(2025, 1, 2), "t");
        transfer.setExcluded(true);
        Expense refund = imported(50, "Shopping", "REFUND", LocalDate.of(2025, 1, 3), "r");
        refund.setRefund(true);
        ledger.add(transfer);
        ledger.add(refund);

        List<Expense> input = RecurringController.detectionInput(ledger);
        assertFalse(input.contains(transfer));
        assertFalse(input.contains(refund));
        assertEquals(4, input.size());

        // Signature changes when a row is recategorised / marked as a transfer in place.
        int before = RecurringController.ledgerSignature(ledger);
        ledger.get(0).setCategory("Entertainment");
        assertNotEquals(before, RecurringController.ledgerSignature(ledger));
        int mid = RecurringController.ledgerSignature(ledger);
        ledger.get(1).setExcluded(true);
        assertNotEquals(mid, RecurringController.ledgerSignature(ledger));
        ledger.get(1).setExcluded(false);

        // A pattern built earlier: row 1 has since been replaced by an edit, row 2 marked a transfer.
        RecurringPatternDetector.DetectedPattern pattern = new RecurringPatternDetector.DetectedPattern(
            "STREAMCO", "Subscriptions", 200, RecurrenceType.MONTHLY, LocalDate.of(2025, 1, 5),
            new ArrayList<>(ledger.subList(0, 4)));
        Expense stale = ledger.get(0);
        Expense replacement = imported(200, "Subscriptions", "STREAMCO", stale.getDate(), "s1");
        ledger.set(0, replacement);
        ledger.get(2).setExcluded(true);

        List<Expense> removable = RecurringController.removableOriginals(pattern, ledger);
        assertEquals(2, removable.size());
        assertTrue(removable.stream().noneMatch(e -> e == stale));
        assertTrue(removable.stream().noneMatch(Expense::isExcluded));
    }
}
