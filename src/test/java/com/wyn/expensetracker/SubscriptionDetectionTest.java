package com.wyn.expensetracker;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.*;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/** Subscriptions and debit orders: price changes, new subscriptions and double charges. */
class SubscriptionDetectionTest {

    private static Expense imported(double amount, String category, LocalDate date, String desc) {
        Expense e = new Expense(amount, category, date, desc);
        e.setImportId("imp-" + date);
        return e;
    }

    private static List<Anomaly> of(List<Anomaly> all, Anomaly.AnomalyType type) {
        return all.stream().filter(a -> a.getType() == type).collect(Collectors.toList());
    }

    /** Some everyday spending so the other detectors have a baseline. */
    private static void addGroceries(List<Expense> list, YearMonth from, int months) {
        for (int m = 0; m < months; m++) {
            YearMonth ym = from.plusMonths(m);
            for (int d = 3; d <= 27; d += 4) {
                list.add(new Expense(300 + d * 7, "Groceries", ym.atDay(d), "CHECKERS HYPER"));
            }
        }
    }

    @Test
    void subscriptionThatDoubledIsFlaggedAsPriceChange() {
        List<Expense> list = new ArrayList<>();
        addGroceries(list, YearMonth.of(2026, 5), 5);
        list.add(imported(2000, "Subscriptions", LocalDate.of(2026, 5, 12), "CLAUDE.AI SUBSCRIPTION 88213"));
        list.add(imported(2000, "Subscriptions", LocalDate.of(2026, 6, 12), "CLAUDE.AI SUBSCRIPTION 90114"));
        list.add(imported(2000, "Subscriptions", LocalDate.of(2026, 7, 12), "ANTHROPIC* CLAUDE"));
        list.add(imported(2000, "Subscriptions", LocalDate.of(2026, 8, 12), "CLAUDE.AI SUBSCRIPTION 91877"));
        Expense sep = imported(4000, "Subscriptions", LocalDate.of(2026, 9, 12), "CLAUDE.AI SUBSCRIPTION 93550");
        list.add(sep);

        List<Anomaly> anomalies = AnomalyDetector.detect(list, YearMonth.of(2026, 9), "R", new CurrencyManager());
        List<Anomaly> changes = of(anomalies, Anomaly.AnomalyType.PRICE_CHANGE);
        assertEquals(1, changes.size(), anomalies.toString());
        assertSame(sep, changes.get(0).getExpense());
        assertTrue(changes.get(0).getMessage().contains("+100%"), changes.get(0).getMessage());
        assertTrue(changes.get(0).getSeverity() > 0.6, "a doubled price should be prominent");
        // The price change explains the charge; no separate "large transaction" note for it.
        assertTrue(anomalies.stream().noneMatch(a -> a != changes.get(0) && a.getExpense() == sep), anomalies.toString());
        // The biggest issue comes first.
        assertEquals(Anomaly.AnomalyType.PRICE_CHANGE, anomalies.get(0).getType());
    }

    @Test
    void steadySubscriptionAndSmallChangesAreQuiet() {
        List<Expense> list = new ArrayList<>();
        for (int m = 5; m <= 9; m++) {
            list.add(imported(199, "Subscriptions", LocalDate.of(2026, m, 3), "NETFLIX.COM"));
        }
        list.add(imported(205, "Subscriptions", LocalDate.of(2026, 9, 20), "SPOTIFY AB")); // only once
        List<Anomaly> anomalies = AnomalyDetector.detect(list, YearMonth.of(2026, 9), "R", new CurrencyManager());
        assertTrue(of(anomalies, Anomaly.AnomalyType.PRICE_CHANGE).isEmpty(), anomalies.toString());

        // R199 -> R205 is 3%: not worth a note.
        list.add(imported(205, "Subscriptions", LocalDate.of(2026, 10, 3), "NETFLIX.COM"));
        anomalies = AnomalyDetector.detect(list, YearMonth.of(2026, 10), "R", new CurrencyManager());
        assertTrue(of(anomalies, Anomaly.AnomalyType.PRICE_CHANGE).isEmpty(), anomalies.toString());
    }

    @Test
    void recurringBillChargedAtNewPriceIsCoveredOnceAndFlagged() {
        // Claude set up as a recurring bill at R2,000; the statement now charges R4,000.
        RecurringExpense claude = new RecurringExpense(2000, "Subscriptions", LocalDate.of(2026, 1, 12),
            "Claude", RecurrenceType.MONTHLY, null);
        LocalDate d = LocalDate.of(2026, 9, 12);
        Expense occurrence = new Expense(2000, "Subscriptions", d, "Claude", claude.getId() + "|" + d, claude);
        Expense charge = imported(4000, "Subscriptions", LocalDate.of(2026, 9, 13), "CLAUDE.AI SUBSCRIPTION");
        List<Expense> list = new ArrayList<>(List.of(occurrence, charge));

        SharedState.RecurringCoverage cov = SharedState.computeRecurringCoverage(list, new CurrencyManager());
        // Counted once (the R4,000 charge), not R6,000.
        assertTrue(cov.coveredRecurringIds.contains(occurrence.getRecurringId()));
        assertTrue(cov.coveringImports.contains(charge));
        assertSame(occurrence, cov.repricedImports.get(charge));

        List<Anomaly> changes = of(AnomalyDetector.detect(list, YearMonth.of(2026, 9), "R", new CurrencyManager()),
            Anomaly.AnomalyType.PRICE_CHANGE);
        assertEquals(1, changes.size());
        assertTrue(changes.get(0).getMessage().contains("recurring amount"), changes.get(0).getMessage());
    }

    @Test
    void coverageStillPrefersTheCloseAmount() {
        // Two same-name imports near the occurrence: the one at the recurring price is the payment.
        RecurringExpense gym = new RecurringExpense(500, "Health", LocalDate.of(2026, 1, 1),
            "Virgin Active", RecurrenceType.MONTHLY, null);
        LocalDate d = LocalDate.of(2026, 9, 1);
        Expense occurrence = new Expense(500, "Health", d, "Virgin Active", gym.getId() + "|" + d, gym);
        Expense shake = imported(1200, "Health", LocalDate.of(2026, 9, 1), "VIRGIN ACTIVE SHOP");
        Expense fee = imported(500, "Health", LocalDate.of(2026, 9, 2), "VIRGIN ACTIVE DEBIT");
        SharedState.RecurringCoverage cov = SharedState.computeRecurringCoverage(
            List.of(occurrence, shake, fee), new CurrencyManager());
        assertTrue(cov.coveringImports.contains(fee));
        assertFalse(cov.coveringImports.contains(shake));
        assertTrue(cov.repricedImports.isEmpty());
    }

    @Test
    void newMonthlySubscriptionIsSuggestedButNotHabits() {
        List<Expense> list = new ArrayList<>();
        addGroceries(list, YearMonth.of(2026, 6), 4);
        list.add(imported(349, "Entertainment", LocalDate.of(2026, 8, 6), "SHOWMAX"));
        Expense latest = imported(349, "Entertainment", LocalDate.of(2026, 9, 6), "SHOWMAX");
        list.add(latest);
        // Weekly coffee at the same price is a habit, not a subscription.
        for (int d = 1; d <= 22; d += 7) list.add(imported(45, "Eating Out", LocalDate.of(2026, 9, d), "VIDA E CAFFE"));

        List<Anomaly> subs = of(AnomalyDetector.detect(list, YearMonth.of(2026, 9), "R", new CurrencyManager()),
            Anomaly.AnomalyType.NEW_SUBSCRIPTION);
        assertEquals(1, subs.size(), subs.toString());
        assertSame(latest, subs.get(0).getExpense());
        assertTrue(subs.get(0).getMessage().contains("monthly"));
    }

    @Test
    void newSubscriptionIsNotSuggestedWhenAlreadyRecurring() {
        RecurringExpense showmax = new RecurringExpense(349, "Entertainment", LocalDate.of(2026, 8, 6),
            "Showmax", RecurrenceType.MONTHLY, null);
        LocalDate d = LocalDate.of(2026, 10, 6); // an upcoming occurrence, not covered by an import
        List<Expense> list = new ArrayList<>(List.of(
            imported(349, "Entertainment", LocalDate.of(2026, 8, 6), "SHOWMAX"),
            imported(349, "Entertainment", LocalDate.of(2026, 9, 6), "SHOWMAX"),
            new Expense(349, "Entertainment", d, "Showmax", showmax.getId() + "|" + d, showmax)));
        List<Anomaly> subs = of(AnomalyDetector.detect(list, YearMonth.of(2026, 9), "R", new CurrencyManager()),
            Anomaly.AnomalyType.NEW_SUBSCRIPTION);
        assertTrue(subs.isEmpty(), subs.toString());
    }

    @Test
    void sameAmountTwiceInAFewDaysIsAPossibleDoubleCharge() {
        List<Expense> list = new ArrayList<>();
        list.add(imported(899, "Shopping", LocalDate.of(2026, 9, 14), "TAKEALOT 5521"));
        Expense second = imported(899, "Shopping", LocalDate.of(2026, 9, 14), "TAKEALOT 5522");
        list.add(second);
        list.add(imported(30, "Eating Out", LocalDate.of(2026, 9, 15), "SEATTLE COFFEE"));
        list.add(imported(30, "Eating Out", LocalDate.of(2026, 9, 15), "SEATTLE COFFEE")); // too small to matter
        list.add(imported(899, "Shopping", LocalDate.of(2026, 9, 25), "TAKEALOT 7310"));   // 11 days later: fine

        List<Anomaly> dup = of(AnomalyDetector.detect(list, YearMonth.of(2026, 9), "R", new CurrencyManager()),
            Anomaly.AnomalyType.DUPLICATE_CHARGE);
        assertEquals(1, dup.size(), dup.toString());
        assertSame(second, dup.get(0).getExpense());
    }

    @Test
    void patternDetectorKeepsARepricedSubscriptionAtItsNewPrice() {
        List<Expense> list = new ArrayList<>();
        for (int m = 3; m <= 7; m++) list.add(new Expense(2000, "Subscriptions", LocalDate.of(2026, m, 12), "CLAUDE.AI SUBSCRIPTION"));
        for (int m = 8; m <= 9; m++) list.add(new Expense(4000, "Subscriptions", LocalDate.of(2026, m, 12), "CLAUDE.AI SUBSCRIPTION"));

        List<RecurringPatternDetector.DetectedPattern> patterns =
            new RecurringPatternDetector().detectPatterns(list, List.of());
        assertEquals(1, patterns.size());
        RecurringPatternDetector.DetectedPattern p = patterns.get(0);
        assertEquals(RecurrenceType.MONTHLY, p.getFrequency());
        assertEquals(4000, p.getAverageAmount(), 0.001);
        assertEquals(2000, p.getPreviousAmount(), 0.001);
        assertEquals(7, p.getOccurrences());
    }

    @Test
    void patternDetectorDoesNotResuggestARecurringBillAfterAPriceChange() {
        RecurringExpense existing = new RecurringExpense(2000, "Subscriptions", LocalDate.of(2026, 1, 12),
            "Claude.ai subscription", RecurrenceType.MONTHLY, null);
        List<Expense> list = new ArrayList<>();
        for (int m = 7; m <= 9; m++) list.add(new Expense(4000, "Subscriptions", LocalDate.of(2026, m, 12), "CLAUDE.AI SUBSCRIPTION"));
        assertTrue(new RecurringPatternDetector().detectPatterns(list, List.of(existing)).isEmpty());
    }

    @Test
    void otherPurchaseAtSameCompanyIsNotTreatedAsTheBillAtANewPrice() {
        // Reviewer case: Vodacom contract R500 on the 20th, paid by debit order in earlier months;
        // in September only an airtime purchase shows up near the date.
        RecurringExpense contract = new RecurringExpense(500, "Phone", LocalDate.of(2026, 6, 20),
            "Vodacom", RecurrenceType.MONTHLY, null);
        List<Expense> list = new ArrayList<>();
        for (int m = 6; m <= 9; m++) {
            LocalDate d = LocalDate.of(2026, m, 20);
            list.add(new Expense(500, "Phone", d, "Vodacom", contract.getId() + "|" + d, contract));
            if (m < 9) list.add(imported(500, "Phone", d, "VODACOM DEBIT ORDER"));
        }
        Expense airtime = imported(300, "Phone", LocalDate.of(2026, 9, 21), "VODACOM AIRTIME PREPAID");
        list.add(airtime);

        SharedState.RecurringCoverage cov = SharedState.computeRecurringCoverage(list, new CurrencyManager());
        assertFalse(cov.coveringImports.contains(airtime), "airtime must not stand in for the contract");
        assertTrue(cov.repricedImports.isEmpty());
        assertFalse(cov.coveredRecurringIds.contains(contract.getId() + "|" + LocalDate.of(2026, 9, 20)));
    }

    @Test
    void otherPurchaseAtSameCompanyRaisesNoPriceChangeAlert() {
        // Same data as above, through the whole detector (the merchant-history path too).
        RecurringExpense contract = new RecurringExpense(500, "Phone", LocalDate.of(2026, 6, 20),
            "Vodacom", RecurrenceType.MONTHLY, null);
        List<Expense> list = new ArrayList<>();
        for (int m = 6; m <= 9; m++) {
            LocalDate d = LocalDate.of(2026, m, 20);
            list.add(new Expense(500, "Phone", d, "Vodacom", contract.getId() + "|" + d, contract));
            if (m < 9) list.add(imported(500, "Phone", d, "VODACOM DEBIT ORDER"));
        }
        list.add(imported(200, "Phone", LocalDate.of(2026, 9, 21), "VODACOM AIRTIME PREPAID"));
        List<Anomaly> changes = of(AnomalyDetector.detect(list, YearMonth.of(2026, 9), "R", new CurrencyManager()),
            Anomaly.AnomalyType.PRICE_CHANGE);
        assertTrue(changes.isEmpty(), changes.toString());
    }

    @Test
    void twoCandidatesNearTheBillAreAmbiguousSoNeitherIsRepriced() {
        RecurringExpense gym = new RecurringExpense(500, "Health", LocalDate.of(2026, 1, 1),
            "Virgin Active", RecurrenceType.MONTHLY, null);
        LocalDate d = LocalDate.of(2026, 9, 1);
        Expense occurrence = new Expense(500, "Health", d, "Virgin Active", gym.getId() + "|" + d, gym);
        Expense a = imported(800, "Health", LocalDate.of(2026, 9, 1), "VIRGIN ACTIVE");
        Expense b = imported(900, "Health", LocalDate.of(2026, 9, 2), "VIRGIN ACTIVE");
        SharedState.RecurringCoverage cov = SharedState.computeRecurringCoverage(
            List.of(occurrence, a, b), new CurrencyManager());
        assertTrue(cov.repricedImports.isEmpty());
    }

    @Test
    void oneOddChargeDoesNotHideOrRepriceASubscription() {
        List<Expense> list = new ArrayList<>();
        for (int m = 3; m <= 7; m++) list.add(new Expense(199, "Subscriptions", LocalDate.of(2026, m, 3), "NETFLIX.COM"));
        list.add(new Expense(50, "Subscriptions", LocalDate.of(2026, 7, 13), "NETFLIX.COM"));
        List<RecurringPatternDetector.DetectedPattern> patterns = new RecurringPatternDetector().detectPatterns(list, List.of());
        assertEquals(1, patterns.size());
        assertEquals(199, patterns.get(0).getAverageAmount(), 0.001);
        assertEquals(0, patterns.get(0).getPreviousAmount(), 0.001);
    }

    @Test
    void merchantMatchingIsWholeWordAndNotAcrossPeople() {
        assertFalse(RecurringPatternDetector.sameMerchant("FEE", "COFFEE BEAN"));
        // Seen in real data: "ms" (Microsoft) matched inside "MAMS".
        assertFalse(RecurringPatternDetector.sameMerchant("POS Purchase Dlocal *Microsoft M", "POS Purchase Mams Megastop Conve"));
        assertTrue(RecurringPatternDetector.sameMerchant("MS AZURE", "Dlocal *Microsoft"));
        assertFalse(RecurringPatternDetector.sameMerchant("FNB APP PAYMENT TO JOHN", "FNB APP PAYMENT TO MOM"));
        assertTrue(RecurringPatternDetector.sameMerchant("NETFLIX.COM", "NETFLIX"));
        assertTrue(RecurringPatternDetector.sameMerchant("CLAUDE.AI SUBSCRIPTION", "ANTHROPIC* CLAUDE"));
    }

    @Test
    void reversedOrHabitualDoubleChargesAreQuiet() {
        List<Expense> list = new ArrayList<>();
        list.add(imported(899, "Shopping", LocalDate.of(2026, 9, 14), "TAKEALOT"));
        list.add(imported(899, "Shopping", LocalDate.of(2026, 9, 14), "TAKEALOT"));
        Expense reversal = imported(899, "Shopping", LocalDate.of(2026, 9, 16), "TAKEALOT REVERSAL");
        reversal.setRefund(true);
        list.add(reversal);
        // Prepaid electricity: R500 is simply what gets bought, often.
        for (int d = 1; d <= 25; d += 8) list.add(imported(500, "Utilities", LocalDate.of(2026, 8, d), "PREPAID ELECTRICITY"));
        list.add(imported(500, "Utilities", LocalDate.of(2026, 9, 10), "PREPAID ELECTRICITY"));
        list.add(imported(500, "Utilities", LocalDate.of(2026, 9, 11), "PREPAID ELECTRICITY"));

        List<Anomaly> dup = of(AnomalyDetector.detect(list, YearMonth.of(2026, 9), "R", new CurrencyManager()),
            Anomaly.AnomalyType.DUPLICATE_CHARGE);
        assertTrue(dup.isEmpty(), dup.toString());
    }

    @Test
    void groupingStaysFastOnYearsOfHistory() {
        List<Expense> list = new ArrayList<>();
        Random rnd = new Random(7);
        LocalDate start = LocalDate.of(2023, 9, 1);
        for (int i = 0; i < 6000; i++) {
            list.add(imported(20 + rnd.nextInt(2000), "Shopping", start.plusDays(rnd.nextInt(1095)),
                "MERCHANT " + (char) ('A' + rnd.nextInt(26)) + rnd.nextInt(2000) + " STORE"));
        }
        long t = System.nanoTime();
        AnomalyDetector.detect(list, YearMonth.of(2026, 8), "R", new CurrencyManager());
        long ms = (System.nanoTime() - t) / 1_000_000;
        assertTrue(ms < 3000, "detect took " + ms + " ms"); // was ~34 s before bucketing
    }
}
