package com.wyn.expensetracker;

import javafx.collections.FXCollections;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** Regression tests for the second calculation audit round (C-NEW-1..5, M12). */
class CalculationAuditRound2Test {

    @TempDir
    Path tempDir;

    private SharedState state;

    @BeforeEach
    void setUp() {
        state = new SharedState(new ExpenseManager(), new FileStorage(tempDir.toString()),
            FXCollections.observableArrayList(), new HashMap<>(), null, null);
    }

    private static Expense imported(double amount, String cat, String desc, LocalDate date, String id) {
        Expense e = new Expense(amount, cat, date, desc);
        e.setImportId(id);
        return e;
    }

    // ---------------------------------------------------------------- C-NEW-1

    @Test
    void onlyRecentStatementsAssumeEarlierInstalmentsPaid() {
        Debt loan = new Debt("L", "Loan", 100_000, 12, 60, LocalDate.of(2024, 9, 1), "MONTHLY", 0, "ZAR");
        loan.setPaymentKeyword("LOANPAY");
        double inst = loan.getScheduledPayment();
        List<Expense> ledger = new ArrayList<>();
        for (int m = 7; m <= 9; m++) {
            ledger.add(imported(inst, "Debt", "DEBIT LOANPAY " + m, LocalDate.of(2026, m, 1), "i" + m));
        }
        LocalDate asOf = LocalDate.of(2026, 9, 26);
        Debt.BalanceStatus st = loan.resolveBalance(List.of(), ledger, new CurrencyManager(), asOf);
        assertFalse(st.estimated);
        assertEquals(loan.getScheduledBalance(asOf), st.balance, 1.0);
        assertTrue(st.balance < 70_000, "was 120k when earlier months counted as unpaid");
        assertEquals(3 * inst, st.totalPaid, 0.01);

        // A missed instalment after the first known payment still shows as arrears.
        ledger.remove(1); // August missing
        Debt.BalanceStatus missed = loan.resolveBalance(List.of(), ledger, new CurrencyManager(), asOf);
        assertTrue(missed.balance > st.balance + inst);
    }

    @Test
    void earliestManualPaymentAlsoAnchorsTheSchedule() {
        Debt loan = new Debt("L", "Loan", 100_000, 12, 60, LocalDate.of(2024, 9, 1), "MONTHLY", 0, "ZAR");
        double inst = loan.getScheduledPayment();
        List<DebtPayment> manual = List.of(new DebtPayment("L", inst, LocalDate.of(2026, 9, 1), ""));
        LocalDate asOf = LocalDate.of(2026, 9, 26);
        Debt.BalanceStatus st = loan.resolveBalance(manual, List.of(), null, asOf);
        assertEquals(loan.getScheduledBalance(asOf), st.balance, 1.0);
    }

    // ---------------------------------------------------------------- C-NEW-2

    @Test
    void manualPaymentAlsoOnStatementCountsOnce() {
        Debt debt = new Debt("d1", "Car", 12000, 0, 12, LocalDate.of(2025, 1, 1), "MONTHLY", 1000, "ZAR");
        debt.setPaymentKeyword("carfin");
        List<DebtPayment> manual = List.of(new DebtPayment("d1", 1000, LocalDate.of(2025, 2, 1), ""));
        List<Expense> ledger = List.of(
            imported(1000, "Transport", "CARFIN", LocalDate.of(2025, 2, 3), "a"),   // duplicate of manual
            imported(1000, "Transport", "CARFIN", LocalDate.of(2025, 3, 1), "b"));  // separate payment
        Debt.BalanceStatus st = debt.resolveBalance(manual, ledger, null, LocalDate.of(2025, 3, 15));
        assertEquals(2000, st.totalPaid, 0.001);
        assertEquals(10000, st.balance, 0.001);

        // One-to-one: two identical imports near one manual payment -> only one dropped.
        List<Expense> twice = List.of(
            imported(1000, "Transport", "CARFIN", LocalDate.of(2025, 2, 2), "a"),
            imported(1000, "Transport", "CARFIN", LocalDate.of(2025, 2, 4), "b"));
        assertEquals(2000, debt.resolveBalance(manual, twice, null, LocalDate.of(2025, 3, 15)).totalPaid, 0.001);

        // Amount outside 1% -> not a duplicate.
        List<Expense> diff = List.of(imported(1100, "Transport", "CARFIN", LocalDate.of(2025, 2, 2), "a"));
        assertEquals(2100, debt.resolveBalance(manual, diff, null, LocalDate.of(2025, 3, 15)).totalPaid, 0.001);
    }

    // ---------------------------------------------------------------- C-NEW-3

    @Test
    void excludedTransferMatchingKeywordCountsButRefundAndIncomeDoNot() {
        Debt debt = new Debt("d1", "Car", 12000, 0, 12, LocalDate.of(2025, 1, 1), "MONTHLY", 1000, "ZAR");
        debt.setPaymentKeyword("LOAN ACC");
        Expense trf = imported(1000, "Transfer", "TRF TO LOAN ACC", LocalDate.of(2025, 2, 1), "t");
        trf.setExcluded(true);
        Expense refund = imported(1000, "Transfer", "LOAN ACC REVERSAL", LocalDate.of(2025, 2, 5), "r");
        refund.setRefund(true);
        Expense income = imported(1000, "Transfer", "LOAN ACC PAYOUT", LocalDate.of(2025, 2, 6), "n");
        income.setIncome(true);
        List<DebtPayment> matched = debt.matchImportedPayments(List.of(trf, refund, income), null);
        assertEquals(1, matched.size());
        assertEquals(1000, matched.get(0).getAmount(), 0.001);
    }

    // ---------------------------------------------------------------- M12

    @Test
    void projectionSumsActualOccurrencesWithOverrides() {
        YearMonth now = YearMonth.of(2026, 9);
        RecurringExpense weekly = new RecurringExpense(100, "Groceries", LocalDate.of(2026, 9, 2), "Veg box",
            RecurrenceType.WEEKLY, null);
        RecurringExpense quarterly = new RecurringExpense(900, "Insurance", LocalDate.of(2026, 8, 15), "Cover",
            RecurrenceType.QUARTERLY, null);
        OccurrenceOverride skip = new OccurrenceOverride(weekly.getId(), LocalDate.of(2026, 10, 7));
        skip.setSkipped(true);
        OccurrenceOverride edit = new OccurrenceOverride(quarterly.getId(), LocalDate.of(2026, 11, 15));
        edit.setAmount(1200.0);

        ExpenseManager mgr = new ExpenseManager();
        mgr.setOverrides(List.of(skip, edit));
        mgr.loadExpenses(new ArrayList<>(List.of(weekly, quarterly)));

        ProjectionEngine.ProjectionInput input = new ProjectionEngine.ProjectionInput(
            List.of(), mgr.getBaseRecurringExpenses(), Map.of(), 0, Map.of(), new CurrencyManager(),
            mgr.getOverrides());
        ProjectionEngine.ProjectionResult result = new ProjectionEngine().project(input, now);
        for (ProjectionEngine.MonthProjection mp : result.monthProjections) {
            double expected = mgr.getUpcomingRecurring(mp.month.atDay(1), mp.month.atEndOfMonth()).stream()
                .mapToDouble(Expense::getAmount).sum();
            assertEquals(expected, mp.projectedRecurringExpenses, 0.001, mp.month.toString());
        }
        // Oct: Wednesdays 7(skipped),14,21,28 -> 300; Nov: 4 Wednesdays + edited quarterly 1200.
        assertEquals(300, result.monthProjections.get(0).projectedRecurringExpenses, 0.001);
        assertEquals(400 + 1200, result.monthProjections.get(1).projectedRecurringExpenses, 0.001);
        assertEquals(1200, result.monthProjections.get(1).categoryRecurring.get("Insurance"), 0.001);
    }

    // ---------------------------------------------------------------- C-NEW-4

    @Test
    void baseSwitchRefusedWhenStampedCurrencyWouldLackRate() {
        CurrencyManager cm = state.getCurrencyManager();
        cm.setBaseCurrency("ZAR");
        cm.setExchangeRates(new LinkedHashMap<>());
        state.getManager().addExpense(new Expense(185, "Food", LocalDate.of(2025, 1, 5), "Lunch"));

        // No USD rate: the old base (ZAR) would have no rate in USD -> refused.
        assertEquals(Set.of("ZAR"), SettingsController.currenciesLackingRateAfterSwitch(state, "USD"));

        // With a USD rate the ZAR rows convert properly.
        cm.setExchangeRates(new LinkedHashMap<>(Map.of("USD", 18.5)));
        assertTrue(SettingsController.currenciesLackingRateAfterSwitch(state, "USD").isEmpty());

        // A stamped EUR expense with no EUR rate would also be re-valued 1:1 -> refused.
        Expense eur = new Expense(10, "Food", LocalDate.of(2025, 1, 6), "Cafe");
        eur.setCurrency("EUR");
        state.getManager().addExpense(eur);
        assertEquals(Set.of("EUR"), SettingsController.currenciesLackingRateAfterSwitch(state, "USD"));
    }

    @Test
    void baseSwitchConsidersDebtsAndRecurringAndAllowsEmptyProfile() {
        CurrencyManager cm = state.getCurrencyManager();
        cm.setBaseCurrency("ZAR");
        cm.setExchangeRates(new LinkedHashMap<>());
        assertTrue(SettingsController.currenciesLackingRateAfterSwitch(state, "USD").isEmpty(),
            "nothing to re-value in an empty profile");

        state.getDebts().add(new Debt("d", "Loan", 1000, 0, 10, LocalDate.of(2025, 1, 1), "MONTHLY", 100, null));
        assertEquals(Set.of("ZAR"), SettingsController.currenciesLackingRateAfterSwitch(state, "USD"));
        state.getDebts().clear();

        RecurringExpense gbp = new RecurringExpense(5, "Subs", LocalDate.of(2025, 1, 1), "App",
            RecurrenceType.MONTHLY, LocalDate.of(2025, 2, 1));
        gbp.setCurrency("GBP");
        state.getManager().loadExpenses(new ArrayList<>(List.of(gbp)));
        cm.setExchangeRates(new LinkedHashMap<>(Map.of("USD", 18.5)));
        assertEquals(Set.of("GBP"), SettingsController.currenciesLackingRateAfterSwitch(state, "USD"));
    }

    // ---------------------------------------------------------------- C-NEW-5

    @Test
    void weeklyCoverageUsesOptimalPairing() {
        RecurringExpense weekly = new RecurringExpense(100, "Groceries", LocalDate.of(2026, 3, 3), "Veg box",
            RecurrenceType.WEEKLY, LocalDate.of(2026, 3, 10));
        Expense o1 = new Expense(100, "Groceries", LocalDate.of(2026, 3, 3), "Veg box", "w|1", weekly);
        Expense o2 = new Expense(100, "Groceries", LocalDate.of(2026, 3, 10), "Veg box", "w|2", weekly);
        Expense i1 = imported(100, "Groceries", "VEG BOX CO", LocalDate.of(2026, 2, 27), "a");
        Expense i2 = imported(100, "Groceries", "VEG BOX CO", LocalDate.of(2026, 3, 6), "b");
        List<Expense> all = List.of(o1, o2, i1, i2);

        SharedState.RecurringCoverage cov = SharedState.computeRecurringCoverage(all, null);
        assertEquals(Set.of("w|1", "w|2"), cov.coveredRecurringIds);
        assertEquals(2, cov.coveringImports.size());

        state.getExpenseList().setAll(all);
        assertEquals(200, state.netSpend(all), 0.001);
    }
}
