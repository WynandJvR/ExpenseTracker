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

/** Round-3 audit fixes on the import side. Synthetic data only. */
class ImportRound3Test {

    // I-NEW-4: user rules and multi-word keys need a clean word ending; only listed brands run on
    @Test
    void userRulesDoNotRunIntoLongerNames() {
        CategorizationRules rules = new CategorizationRules();
        rules.addRule("Anna Smit", TransactionClassifier.TRANSFERS);
        assertNull(rules.categorize("FNB App Payment To Anna Smith"));
        assertEquals(TransactionClassifier.TRANSFERS, rules.categorize("FNB App Payment To Anna Smit"));
    }

    @Test
    void builtInsOnlyRunOnForListedBrands() {
        assertNull(TransactionClassifier.builtInCategory("Hospitality Group Lunch"), "'hospital' must not match 'Hospitality'");
        assertEquals(TransactionClassifier.PAYMENTS, TransactionClassifier.builtInCategory("FNB App Payment To Mr D"));
        assertEquals("Eating Out", TransactionClassifier.builtInCategory("POS Purchase Mr D Food 123"));
        assertEquals("Alcohol", TransactionClassifier.builtInCategory("POS Purchase Liquorshop Mooi"));
        assertEquals("Shopping", TransactionClassifier.builtInCategory("POS Purchase Bobshopcoza"));
    }

    // M2: more interest spellings; fee reversals net against bank fees
    @Test
    void interestSpellingsAndFeeReversals() {
        assertEquals(TransactionClassifier.BANK_FEES, TransactionClassifier.builtInCategory("Interest On Overdraft"));
        assertEquals(TransactionClassifier.BANK_FEES, TransactionClassifier.builtInCategory("#Int On Debit Balance"));

        ImportItem reversal = new ImportItem(15, "Fee Reversal", LocalDate.of(2025, 3, 3));
        reversal.setCredit(true);
        TransactionClassifier.classify(reversal, new CategorizationRules());
        assertTrue(reversal.isRefund());
        assertEquals(TransactionClassifier.BANK_FEES, reversal.getCategory());
    }

    // I-NEW-3: generic PDFs: newest-first order doesn't advance the year; no future dates
    @Test
    void genericPdfYearHandling() {
        String newestFirst = String.join("\n",
            "Statement 2025",
            "20 Mar Shop A 10.00 70.00",
            "15 Feb Shop B 10.00 80.00",
            "10 Jan Shop C 10.00 90.00");
        StatementParseResult r = new GenericPdfParser().parseStatement(newestFirst);
        assertEquals(2025, r.getItems().get(2).getDate().getYear());

        int next = LocalDate.now().getYear() + 1;
        String headerInNextYear = String.join("\n",
            "Statement Date: 05 Jan " + next,
            "28 Dec Shop A 10.00 90.00",
            "02 Jan Shop B 10.00 80.00");
        r = new GenericPdfParser().parseStatement(headerInNextYear);
        for (ImportItem i : r.getItems()) assertFalse(i.getDate().isAfter(LocalDate.now()), i.getDate().toString());
    }

    // M9 through the Overview's own call path: an imported bill covering a recurring series isn't "large"
    @Test
    void overviewDoesNotFlagImportedRecurringBill(@TempDir Path dir) {
        ExpenseManager manager = new ExpenseManager();
        SharedState state = new SharedState(manager, new FileStorage(dir.toString()),
            FXCollections.observableArrayList(), new HashMap<>(), null, null);
        LocalDate start = LocalDate.now().withDayOfMonth(1).minusMonths(3);
        manager.addExpense(new RecurringExpense(9000, "Housing", start, "Rent", RecurrenceType.MONTHLY, null));
        List<Expense> extra = new ArrayList<>();
        for (int m = 0; m <= 3; m++) {
            LocalDate month = start.plusMonths(m);
            for (int d = 2; d < 20; d++) extra.add(new Expense(100, "Food", month.withDayOfMonth(d), "Snack"));
            Expense rent = new Expense(9000, "Housing", month, "RENT PAYMENT");
            rent.setImportId("IMP-" + m);
            extra.add(rent);
        }
        manager.addExpenses(extra);
        state.syncExpenseList();

        YearMonth last = YearMonth.from(start.plusMonths(3));
        List<Anomaly> shown = DashboardController.anomaliesToShow(state, last);
        assertTrue(shown.stream().noneMatch(a -> a.getType() == Anomaly.AnomalyType.LARGE_TRANSACTION), shown.toString());
        // M9: the covered occurrence and its import are one payment, not a doubled spike.
        assertTrue(shown.stream().noneMatch(a -> a.getType() == Anomaly.AnomalyType.SPENDING_SPIKE
            && a.getDate().equals(last.atDay(1))), shown.toString());
        String doubled = UIUtils.fmt(18000, state.getCurrencySymbol());
        assertTrue(shown.stream().noneMatch(a -> a.getMessage().contains(doubled)), shown.toString());

        // Same result when the cached coverage is passed in.
        List<Anomaly> viaCache = AnomalyDetector.detect(new ArrayList<>(state.getExpenseList()), last,
            state.getCurrencySymbol(), state.getCurrencyManager(), state.getRecurringCoverage());
        assertTrue(viaCache.stream().noneMatch(a -> a.getType() == Anomaly.AnomalyType.SPENDING_SPIKE
            || a.getType() == Anomaly.AnomalyType.LARGE_TRANSACTION), viaCache.toString());
    }
}
