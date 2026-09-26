package com.wyn.expensetracker;

import javafx.collections.FXCollections;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression tests for the calculation audit: income coverage (H7), debit-order debts (H8),
 * coverage rules (M6/M7), the month x category clamping rule (M8), anomaly noise (M9/M10),
 * budget averaging (M11), projected future spend (M12), base-currency conversion (M18) and
 * historical income gaps (L10).
 */
class CalculationAuditFixesTest {

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

    // ---------------------------------------------------------------- helpers

    private static Expense occurrence(RecurringExpense src, LocalDate date) {
        Expense e = new Expense(src.getAmount(), src.getCategory(), date, src.getDescription(),
            src.getId() + "|" + date, src);
        if (src.isIncome()) e.setIncome(true);
        return e;
    }

    private static Expense imported(double amount, String cat, String desc, LocalDate date, String id) {
        Expense e = new Expense(amount, cat, date, desc);
        e.setImportId(id);
        return e;
    }

    private static Expense exp(double amount, String cat, LocalDate date) {
        return new Expense(amount, cat, date, "item");
    }

    private static Expense refund(double amount, String cat, LocalDate date) {
        Expense e = new Expense(amount, cat, date, "refund");
        e.setRefund(true);
        return e;
    }

    // ---------------------------------------------------------------- H7

    @Test
    void importedSalaryCoversGeneratedIncomeOccurrence_notCountedTwice() {
        YearMonth past = YearMonth.now().minusMonths(2);
        RecurringExpense salary = new RecurringExpense(20000, "Salary", past.atDay(25).minusMonths(3),
            "Salary", RecurrenceType.MONTHLY, null);
        salary.setIncome(true);
        Expense occ = occurrence(salary, past.atDay(25));
        Expense imp = imported(20000, "Salary", "SALARY ACME CORP", past.atDay(24), "i1");
        imp.setIncome(true);
        state.getExpenseList().setAll(occ, imp);

        assertTrue(state.coversRecurring(imp));
        assertEquals(20000, state.actualIncome(past), 0.001);
        assertEquals(20000, state.incomeForMonth(past), 0.001);

        ProjectionEngine.ProjectionInput input = new ProjectionEngine.ProjectionInput(
            List.of(occ, imp), List.of(salary), new HashMap<>(), 0, new HashMap<>(), new CurrencyManager());
        assertEquals(20000, new ProjectionEngine().computeHistoricalIncome(input, past, new CurrencyManager()), 0.001);
    }

    @Test
    void incomeImportDoesNotCoverSpendOccurrence() {
        RecurringExpense rent = new RecurringExpense(5000, "Housing", LocalDate.of(2025, 1, 1),
            "Rent", RecurrenceType.MONTHLY, null);
        Expense occ = occurrence(rent, LocalDate.of(2025, 3, 1));
        Expense credit = imported(5000, "Income", "Rent received", LocalDate.of(2025, 3, 1), "i1");
        credit.setIncome(true);
        state.getExpenseList().setAll(occ, credit);
        assertTrue(state.countsAsSpend(occ));
    }

    // ---------------------------------------------------------------- M6 / M7 / L8

    @Test
    void excludedImportNeverCoversRecurring() {
        RecurringExpense gym = new RecurringExpense(300, "Health", LocalDate.of(2025, 1, 3),
            "Gym", RecurrenceType.MONTHLY, null);
        Expense occ = occurrence(gym, LocalDate.of(2025, 3, 3));
        Expense transfer = imported(300, "Transfer", "GYM transfer", LocalDate.of(2025, 3, 3), "i1");
        transfer.setExcluded(true);
        state.getExpenseList().setAll(occ, transfer);

        assertTrue(state.countsAsSpend(occ), "Excluded transfer must not cover the bill");
        assertFalse(state.coversRecurring(transfer));
    }

    @Test
    void debitOrderDueFirstPaidFridayThirtiethOfPriorMonth_isCovered() {
        // 30 May 2025 is a Friday; the debit order is due on 1 June.
        assertEquals(java.time.DayOfWeek.FRIDAY, LocalDate.of(2025, 5, 30).getDayOfWeek());
        RecurringExpense insurance = new RecurringExpense(800, "Insurance", LocalDate.of(2025, 1, 1),
            "Insurance", RecurrenceType.MONTHLY, null);
        Expense june = occurrence(insurance, LocalDate.of(2025, 6, 1));
        Expense paid = imported(800, "Insurance", "DEBIT INSURANCE CO", LocalDate.of(2025, 5, 30), "i1");
        state.getExpenseList().setAll(june, paid);

        assertFalse(state.countsAsSpend(june));
        assertTrue(state.coversRecurring(paid));
        assertEquals(800, state.netSpend(state.getExpenseList()), 0.001, "Counted once");
        assertEquals(800, state.netSpendByMonth(state.getExpenseList()).get(YearMonth.of(2025, 5)), 0.001);
        assertNull(state.netSpendByMonth(state.getExpenseList()).get(YearMonth.of(2025, 6)));
    }

    @Test
    void importOutsideFiveDayWindowDoesNotCover() {
        RecurringExpense insurance = new RecurringExpense(800, "Insurance", LocalDate.of(2025, 1, 1),
            "Insurance", RecurrenceType.MONTHLY, null);
        Expense occ = occurrence(insurance, LocalDate.of(2025, 6, 1));
        Expense late = imported(800, "Insurance", "INSURANCE", LocalDate.of(2025, 6, 7), "i1");
        state.getExpenseList().setAll(occ, late);
        assertTrue(state.countsAsSpend(occ));
    }

    @Test
    void closestDateWinsAcrossMonths() {
        RecurringExpense sub = new RecurringExpense(100, "Bills", LocalDate.of(2025, 1, 1),
            "Stream", RecurrenceType.MONTHLY, null);
        Expense may = occurrence(sub, LocalDate.of(2025, 5, 1));
        Expense june = occurrence(sub, LocalDate.of(2025, 6, 1));
        Expense i1 = imported(100, "Bills", "STREAM", LocalDate.of(2025, 5, 2), "i1");
        Expense i2 = imported(100, "Bills", "STREAM", LocalDate.of(2025, 5, 30), "i2");
        state.getExpenseList().setAll(may, june, i1, i2);

        SharedState.RecurringCoverage cov = state.getRecurringCoverage();
        assertTrue(cov.coveredRecurringIds.contains(may.getRecurringId()));
        assertTrue(cov.coveredRecurringIds.contains(june.getRecurringId()));
        assertEquals(200, state.netSpend(state.getExpenseList()), 0.001);
    }

    @Test
    void coverageComparesBaseCurrencyAmounts() {
        CurrencyManager cm = state.getCurrencyManager();
        cm.setExchangeRates(Map.of("USD", 18.5));
        RecurringExpense cloud = new RecurringExpense(185, "Bills", LocalDate.of(2025, 1, 10),
            "Cloud", RecurrenceType.MONTHLY, null);
        Expense occ = occurrence(cloud, LocalDate.of(2025, 3, 10));
        Expense usd = imported(10, "Bills", "CLOUD SERVICES", LocalDate.of(2025, 3, 10), "i1");
        usd.setCurrency("USD");
        state.getExpenseList().setAll(occ, usd);
        assertFalse(state.countsAsSpend(occ), "10 USD = 185 ZAR matches the 185 ZAR template");
    }

    // ---------------------------------------------------------------- M8

    @Test
    void clampIsPerMonthAndCategory_totalsReconcile() {
        List<Expense> items = List.of(
            exp(100, "Clothing", LocalDate.of(2025, 1, 10)),
            refund(300, "Clothing", LocalDate.of(2025, 2, 10)),
            exp(50, "Food", LocalDate.of(2025, 2, 11)));
        state.getExpenseList().setAll(items);

        Map<String, Double> byCat = state.spendByCategory(items);
        assertEquals(100, byCat.get("Clothing"), 0.001, "January's clothing spend is not wiped by February's refund");
        assertEquals(50, byCat.get("Food"), 0.001);
        double total = state.netSpend(items);
        assertEquals(150, total, 0.001);
        double byMonthSum = state.netSpendByMonth(items).values().stream().mapToDouble(Double::doubleValue).sum();
        assertEquals(total, byMonthSum, 0.001);
        assertEquals(total, SharedState.clampedNetSpend(items, state::spendContribution), 0.001);
    }

    @Test
    void splitNetSpend_partsAddUpToTotal() {
        LocalDate d = LocalDate.of(2025, 3, 5);
        Expense recurringLike = exp(80, "Clothing", d);
        List<Expense> items = List.of(
            recurringLike,
            exp(100, "Clothing", d),
            refund(150, "Clothing", d),
            exp(40, "Food", d));
        state.getExpenseList().setAll(items);

        double[] split = state.splitNetSpend(items, e -> e == recurringLike);
        assertEquals(state.netSpend(items), split[0] + split[1], 0.001);
        assertTrue(split[0] >= 0 && split[1] >= 0);
    }

    // ---------------------------------------------------------------- M9 / M10

    @Test
    void importCoveringRecurringBillIsNotFlaggedLarge() {
        RecurringExpense rent = new RecurringExpense(9000, "Housing", LocalDate.of(2025, 1, 1),
            "Rent", RecurrenceType.MONTHLY, null);
        List<Expense> expenses = new ArrayList<>();
        for (int i = 0; i < 20; i++) expenses.add(new Expense(100, "Food", LocalDate.of(2025, 2, 1 + i), "Snack"));
        expenses.add(occurrence(rent, LocalDate.of(2025, 3, 1)));
        expenses.add(imported(9000, "Housing", "RENT PAYMENT", LocalDate.of(2025, 3, 1), "i1"));
        expenses.add(new Expense(100, "Food", LocalDate.of(2025, 3, 2), "Snack"));

        List<Anomaly> anomalies = AnomalyDetector.detect(expenses, YearMonth.of(2025, 3), "R", new CurrencyManager());
        assertTrue(anomalies.stream().noneMatch(a -> a.getType() == Anomaly.AnomalyType.LARGE_TRANSACTION));
        assertTrue(anomalies.stream().noneMatch(a -> a.getType() == Anomaly.AnomalyType.AMOUNT_OUTLIER));
    }

    @Test
    void newCategoryNeedsThreePriorMonthsOfData() {
        List<Expense> newUser = List.of(
            new Expense(50, "Food", LocalDate.of(2025, 2, 5), "Lunch"),
            new Expense(100, "Pets", LocalDate.of(2025, 3, 2), "Vet"));
        assertTrue(AnomalyDetector.detect(newUser, YearMonth.of(2025, 3), "R", new CurrencyManager()).stream()
            .noneMatch(a -> a.getType() == Anomaly.AnomalyType.NEW_CATEGORY));

        List<Expense> established = new ArrayList<>(newUser);
        established.add(new Expense(50, "Food", LocalDate.of(2025, 1, 5), "Lunch"));
        established.add(new Expense(50, "Food", LocalDate.of(2024, 12, 5), "Lunch"));
        assertTrue(AnomalyDetector.detect(established, YearMonth.of(2025, 3), "R", new CurrencyManager()).stream()
            .anyMatch(a -> a.getType() == Anomaly.AnomalyType.NEW_CATEGORY));
    }

    // ---------------------------------------------------------------- M11

    @Test
    void budgetAverageUsesMonthsWithData_excludingPartialCurrentMonth() {
        YearMonth now = YearMonth.of(2025, 6);
        List<Expense> items = List.of(
            exp(300, "Food", LocalDate.of(2025, 3, 1)),
            exp(300, "Food", LocalDate.of(2025, 5, 1)),
            exp(999, "Food", LocalDate.of(2025, 6, 2)));
        List<Expense> complete = AnalyticsController.completeMonthItems(items, now);
        assertEquals(2, complete.size(), "Current partial month dropped");
        assertEquals(2, AnalyticsController.monthsWithData(complete, now), "Only months with data count");

        List<Expense> onlyCurrent = List.of(exp(100, "Food", LocalDate.of(2025, 6, 2)));
        assertEquals(1, AnalyticsController.completeMonthItems(onlyCurrent, now).size());
        assertEquals(1, AnalyticsController.monthsWithData(onlyCurrent, now));
    }

    // ---------------------------------------------------------------- M12

    @Test
    void futureMonthIncludesProjectedVariableSpend() {
        YearMonth now = YearMonth.now();
        List<Expense> hist = new ArrayList<>();
        for (int m = 1; m <= 3; m++) hist.add(exp(600, "Food", now.minusMonths(m).atDay(10)));
        state.getExpenseList().setAll(hist);

        YearMonth next = now.plusMonths(1);
        double variable = state.projectedVariableSpend(next);
        assertTrue(variable > 0);
        assertEquals(variable, state.netSpendForMonth(next), 0.001);
        assertEquals(0, state.projectedVariableSpend(now), 0.001, "Only future months are projected");
    }

    // ---------------------------------------------------------------- L10

    @Test
    void historicalIncomeSkipsMonthsWithNoData() {
        CurrencyManager cm = new CurrencyManager();
        List<Expense> items = List.of(
            exp(100, "Food", LocalDate.of(2025, 1, 10)),
            exp(100, "Food", LocalDate.of(2025, 3, 10)));
        ProjectionEngine.ProjectionInput input = new ProjectionEngine.ProjectionInput(
            items, List.of(), new HashMap<>(), 1000, new HashMap<>(), cm);
        assertEquals(2000, new ProjectionEngine().computeHistoricalIncome(input, YearMonth.of(2025, 3), cm), 0.001,
            "February has no data at all, so it contributes no planned income");
    }

    // ---------------------------------------------------------------- M18

    @Test
    void scaleBaseCurrencyAmountsConvertsIncomesBudgetsAndGoals() {
        incomes.put(YearMonth.of(2025, 1), 20000.0);
        state.setRecurringIncome(18000);
        state.getBudgets().put("Food", 3000.0);
        state.getSavingsGoals().add(new SavingsGoal("g1", "Car", 50000, null, 1000, LocalDate.of(2025, 1, 1)));
        state.getGoalContributions().add(new GoalContribution("g1", 4000, LocalDate.of(2025, 2, 1), ""));
        assertTrue(state.hasBaseCurrencyAmounts());

        state.scaleBaseCurrencyAmounts(0.05);

        assertEquals(1000, incomes.get(YearMonth.of(2025, 1)), 0.001);
        assertEquals(900, state.getRecurringIncome(), 0.001);
        assertEquals(150, state.getBudgets().get("Food"), 0.001);
        assertEquals(2500, state.getSavingsGoals().get(0).getTargetAmount(), 0.001);
        assertEquals(50, state.getSavingsGoals().get(0).getMonthlyTarget(), 0.001);
        assertEquals(200, state.getGoalContributions().get(0).getAmount(), 0.001);
    }

    // ---------------------------------------------------------------- H8

    private static Debt carLoan() {
        // 0% so the schedule is simple: 12 x 1000.
        return new Debt("d1", "Car", 12000, 0, 12, LocalDate.of(2025, 1, 1), "MONTHLY", 1000, "ZAR");
    }

    @Test
    void importedDebitOrdersMatchingKeywordCountAsPayments() {
        Debt debt = carLoan();
        debt.setPaymentKeyword("carfin");
        List<Expense> ledger = new ArrayList<>();
        for (int m = 2; m <= 4; m++) {
            ledger.add(imported(1000, "Transport", "DEBIT ORDER CARFIN 12345", LocalDate.of(2025, m, 1), "i" + m));
        }
        ledger.add(imported(1000, "Transport", "CARFINANCE OTHER", LocalDate.of(2025, 4, 2), "x1")); // not a word match
        Expense before = imported(1000, "Transport", "CARFIN", LocalDate.of(2024, 12, 1), "x2");     // before start
        ledger.add(before);
        // Excluded as a transfer (e.g. "TRF TO LOAN ACC") still pays the debt (C-NEW-3).
        Expense excluded = imported(500, "Transfer", "CARFIN", LocalDate.of(2025, 4, 3), "x3");
        excluded.setExcluded(true);
        ledger.add(excluded);

        Debt.BalanceStatus status = debt.resolveBalance(List.of(), ledger, new CurrencyManager(), LocalDate.of(2025, 4, 15));
        assertFalse(status.estimated);
        assertEquals(3500, status.totalPaid, 0.001);
        assertEquals(8500, status.balance, 0.001);
    }

    @Test
    void debtWithNoPaymentsFallsBackToScheduledBalance_estimated() {
        Debt debt = carLoan();
        Debt.BalanceStatus status = debt.resolveBalance(List.of(), List.of(), new CurrencyManager(), LocalDate.of(2025, 4, 15));
        assertTrue(status.estimated);
        assertEquals(9000, status.balance, 0.001, "Three instalments (Feb, Mar, Apr) assumed paid");
        assertTrue(status.balance < debt.getPrincipal(), "Must not grow with interest when unpaid on record");

        Debt interest = new Debt("d2", "Loan", 10000, 12, 24, LocalDate.of(2025, 1, 1), "MONTHLY", 0, "ZAR");
        Debt.BalanceStatus s2 = interest.resolveBalance(List.of(), List.of(), null, LocalDate.of(2025, 7, 1));
        assertTrue(s2.estimated);
        assertTrue(s2.balance < 10000);
    }

    @Test
    void manualPaymentsDisableEstimate() {
        Debt debt = carLoan();
        List<DebtPayment> manual = List.of(new DebtPayment("d1", 500, LocalDate.of(2025, 2, 1), ""));
        Debt.BalanceStatus status = debt.resolveBalance(manual, List.of(), null, LocalDate.of(2025, 4, 15));
        assertFalse(status.estimated);
        assertEquals(11500, status.balance, 0.001);
    }

    @Test
    void sharedStateDebtStatusUsesLedgerImports() {
        Debt debt = carLoan();
        debt.setPaymentKeyword("CARFIN");
        state.getDebts().add(debt);
        state.getExpenseList().setAll(imported(1000, "Transport", "carfin debit", LocalDate.of(2025, 2, 1), "i1"));
        Debt.BalanceStatus status = state.debtStatus(debt);
        assertFalse(status.estimated);
        assertEquals(1000, status.totalPaid, 0.001);
    }

    @Test
    void paymentKeywordPersistsAndLegacyLinesStillLoad() throws Exception {
        FileStorage storage = new FileStorage(tempDir.toString());
        Debt debt = carLoan();
        debt.setPaymentKeyword("CAR FIN, 12");
        Debt plain = new Debt("d9", "Plain", 5000, 5, 12, LocalDate.of(2025, 1, 1), "MONTHLY", 0, null);
        storage.saveDebts(List.of(debt, plain));

        List<Debt> loaded = storage.loadDebts();
        assertEquals(2, loaded.size());
        assertEquals("CAR FIN, 12", loaded.get(0).getPaymentKeyword());
        assertNull(loaded.get(1).getPaymentKeyword());
        assertNull(loaded.get(1).getCurrency());

        // Legacy 9-field line (no keyword column).
        Files.writeString(tempDir.resolve("debts.txt"),
            "d1,Car,12000.0,0.0,12,2025-01-01,MONTHLY,1000.0,ZAR\n");
        List<Debt> legacy = storage.loadDebts();
        assertEquals(1, legacy.size());
        assertNull(legacy.get(0).getPaymentKeyword());
        assertEquals("ZAR", legacy.get(0).getCurrency());
    }
}
