package com.wyn.expensetracker;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** The automatic import pipeline. All statement text here is synthetic. */
class StatementImportTest {

    /** A made-up statement in FNB's text layout, including the tricky cases seen in real ones. */
    private static final String FNB_TEXT = String.join("\n",
        "First National Bank",
        "Cheque Account : 12345678901",
        "Statement Period : 27 November 2025 to 27 February 2026",
        "Opening Balance 1,000.00 Cr",
        "Closing Balance 694.50 Cr",
        "Date Description Amount Balance",
        "28 Nov POS Purchase Checkers Hyper 123456*7890 26 Nov 250.00 750.00Cr",
        "29 Nov 8.00 742.00Cr",
        "29 Nov 8.00 734.00Cr",                                               // identical fee, same day: keep both
        "01 Dec Edo Collection Attempt Somecard Ref123 400.00Cr 734.00Cr",    // memo: balance unchanged
        "02 Dec Magtape Credit Employer Salary 2,000.00Cr 2,734.00Cr",
        "03 Dec FNB App Transfer To Savings 1,500.00 1,234.00Cr",
        "04 Dec Payment To Investment Holiday 500.00 734.00Cr",
        "05 Dec POS Purchase Takealot 123456*7890 04 Dec 900.00 166.00",     // no Cr: overdrawn
        "06 Dec POS Purchase Takealot 123456*7890 05 Dec 100.00Cr 66.00",    // refund, still overdrawn
        "15 Dec 3.50Cr 62.50",                                                // fee reversal
        "02 Jan FNB App Transfer From Savings 800.00Cr 737.50Cr",            // year rolls over
        "03 Jan POS Purchase Mysterious Shop 123456*7890 02 Jan 43.00 694.50Cr",
        "Closing Balance 694.50Cr");

    private static StatementParseResult parseFnb() {
        return new FnbPdfParser().parseStatement(FNB_TEXT);
    }

    @Test
    void fnbStatementReconcilesAndSkipsMemoLines() {
        StatementParseResult r = parseFnb();
        assertEquals(1000.00, r.getOpeningBalance(), 0.001);
        assertEquals(694.50, r.getClosingBalance(), 0.001);
        assertEquals(1, r.getSkippedMemoLines());
        assertEquals(11, r.getItems().size());
        assertTrue(r.isReconciled(), "diff=" + r.reconciliationDifference());
        assertEquals(LocalDate.of(2025, 11, 27), r.getPeriodStart());
        assertEquals("Cheque Account ••8901", r.getAccountLabel());
    }

    @Test
    void directionComesFromTheRunningBalance() {
        List<ImportItem> items = parseFnb().getItems();
        ImportItem takealot = items.get(6);
        assertEquals(900.00, takealot.getAmount(), 0.001);
        assertFalse(takealot.isCredit());
        assertEquals(-166.00, takealot.getBalance(), 0.001, "balance without Cr is overdrawn");
        assertTrue(items.get(7).isCredit());
        ImportItem jan = items.get(9);
        assertEquals(LocalDate.of(2026, 1, 2), jan.getDate(), "year rolls over inside the period");
        assertEquals("POS Purchase Checkers Hyper", items.get(0).getDescription(), "card number and card date stripped");
    }

    @Test
    void classifierSeparatesSpendIncomeRefundsAndTransfers() {
        List<ImportItem> items = parseFnb().getItems();
        for (ImportItem i : items) TransactionClassifier.classify(i, new CategorizationRules());

        assertEquals("Groceries", items.get(0).getCategory());
        assertEquals(TransactionClassifier.BANK_FEES, items.get(1).getCategory());
        assertTrue(items.get(3).isIncome());
        assertTrue(items.get(4).isTransfer(), "FNB App Transfer To is an own-account move");
        assertTrue(items.get(5).isTransfer(), "Payment To Investment is an own-account move");
        assertEquals("Shopping", items.get(6).getCategory());
        assertTrue(items.get(7).isRefund(), "a POS credit is a refund");
        assertEquals("Shopping", items.get(7).getCategory());
        assertTrue(items.get(8).isRefund(), "a fee reversal is a refund of bank fees");
        assertEquals(TransactionClassifier.BANK_FEES, items.get(8).getCategory());
        assertTrue(items.get(9).isTransfer());
        assertEquals(TransactionClassifier.UNCATEGORIZED, items.get(10).getCategory());
    }

    @Test
    void userRulesBeatBuiltInsAndLongestKeywordWins() {
        CategorizationRules rules = new CategorizationRules();
        rules.addRule("uber", "Transport");
        rules.addRule("uber eats", "Takeaways");
        rules.addRule("checkers", "Food shopping");
        assertEquals("Takeaways", rules.categorize("POS Purchase Uber Eats Jhb"));
        assertEquals("Transport", rules.categorize("POS Purchase Uber Trip"));

        ImportItem item = new ImportItem(10, "POS Purchase Checkers Hyper", LocalDate.of(2025, 1, 1));
        TransactionClassifier.classify(item, rules);
        assertEquals("Food shopping", item.getCategory());
    }

    @Test
    void rulesMatchWholeWordsOnly() {
        CategorizationRules rules = new CategorizationRules();
        rules.addRule("milk", "Groceries");
        assertNull(rules.categorize("Milkwood Restaurant"));
        assertEquals("Groceries", rules.categorize("MILK 2L"));
        rules.addRule("mr d", "Eating Out");
        assertEquals("Eating Out", rules.categorize("POS Purchase MrD Food"), "multi-word keywords also match compacted");
    }

    @Test
    void addingARuleForAnExistingKeywordReplacesIt() {
        CategorizationRules rules = new CategorizationRules();
        rules.addRule("Woolworths", "Groceries");
        rules.addRule("WOOLWORTHS", "Clothing");
        assertEquals(1, rules.getRules().size());
        assertEquals(1, rules.getRuleEntries().size());
        assertEquals("Clothing", rules.categorize("Woolworths Sandton"));
    }

    @Test
    void keywordForStripsBankBoilerplate() {
        assertEquals("Checkers Fresh", CategorizationRules.keywordFor("POS Purchase Checkers Fresh 123456*7890 01 Jan"));
        assertEquals("Wifi Provider", CategorizationRules.keywordFor("FNB App Payment To Wifi Provider"));
        assertNull(CategorizationRules.keywordFor("POS Purchase"));
    }

    @Test
    void merchantBeatsGenericPaymentChannel() {
        assertEquals("Tax", TransactionClassifier.builtInCategory("Internet Pmt To Sars Efiling"));
        assertEquals(TransactionClassifier.PAYMENTS, TransactionClassifier.builtInCategory("FNB App Payment To Someone"));
        assertNull(TransactionClassifier.builtInCategory("Current account interest"), "'rent' must not match inside 'current'");
    }

    @Test
    void reimportingTheSameOrOverlappingStatementAddsNothing(@TempDir Path dir) throws Exception {
        File pdfLike = dir.resolve("statement.txt").toFile();
        Files.writeString(pdfLike.toPath(), "unused", StandardCharsets.UTF_8);
        ImportRegistry registry = new ImportRegistry(dir.toString());

        StatementImporter importer = new StatementImporter(new CategorizationRules(), registry, List.of());
        StatementImporter.Prepared first = importer.prepare(pdfLike, parseFnb());
        assertEquals(11, first.newItems.size(), "both identical R8 fees are kept");
        registry.record(StatementImporter.recordFor(first, "IMP-1"), StatementImporter.fingerprintsOf(first));
        registry.save();

        // Same transactions arriving in a different file (e.g. an overlapping statement)
        File other = dir.resolve("other.txt").toFile();
        Files.writeString(other.toPath(), "different bytes", StandardCharsets.UTF_8);
        ImportRegistry reloaded = new ImportRegistry(dir.toString());
        reloaded.load();
        StatementImporter.Prepared second = new StatementImporter(new CategorizationRules(), reloaded, List.of())
            .prepare(other, parseFnb());
        assertEquals(0, second.newItems.size());
        assertEquals(11, second.alreadyImported);
        assertTrue(second.skippedWholeFile);

        // The exact same file is recognised by its hash
        assertTrue(new StatementImporter(new CategorizationRules(), reloaded, List.of()).prepare(pdfLike).skippedWholeFile);

        // Forgetting the import allows it again
        reloaded.forget("IMP-1");
        assertEquals(11, new StatementImporter(new CategorizationRules(), reloaded, List.of())
            .prepare(other, parseFnb()).newItems.size());
    }

    @Test
    void importedEntriesCarryTheRightFlags(@TempDir Path dir) throws Exception {
        File f = dir.resolve("s.txt").toFile();
        Files.writeString(f.toPath(), "x", StandardCharsets.UTF_8);
        StatementImporter.Prepared p = new StatementImporter(new CategorizationRules(),
            new ImportRegistry(dir.toString()), List.of()).prepare(f, parseFnb());
        List<Expense> expenses = StatementImporter.toExpenses(p, "IMP-X");
        assertTrue(expenses.stream().allMatch(e -> "IMP-X".equals(e.getImportId())));
        assertTrue(expenses.get(4).isExcluded(), "transfers are excluded from totals");
        assertTrue(expenses.get(3).isIncome());
        assertTrue(expenses.get(7).isRefund());
        assertFalse(expenses.get(7).isIncome(), "refunds are not income");
    }

    @Test
    void manualEntryMatchingAnImportedLineIsNotDoubled(@TempDir Path dir) throws Exception {
        File f = dir.resolve("s.txt").toFile();
        Files.writeString(f.toPath(), "x", StandardCharsets.UTF_8);
        Expense typedIn = new Expense(250.00, "Groceries", LocalDate.of(2025, 11, 27), "Checkers");
        StatementImporter.Prepared p = new StatementImporter(new CategorizationRules(),
            new ImportRegistry(dir.toString()), List.of(typedIn)).prepare(f, parseFnb());
        assertEquals(1, p.duplicatesOfManualEntries);
        assertEquals(10, p.newItems.size());
    }

    @Test
    void amountsParseLocaleVariants() {
        assertEquals(1234.56, Amounts.parse("1,234.56"), 0.001);
        assertEquals(1234.56, Amounts.parse("1 234,56"), 0.001);
        assertEquals(-12.50, Amounts.parse("-12,50"), 0.001);
        assertEquals(-45.00, Amounts.parse("(45.00)"), 0.001);
        assertEquals(300.00, Amounts.parse("300.00Cr"), 0.001);
        assertEquals(-12.00, Amounts.parse("12.00 Dr"), 0.001);
        assertEquals(99.90, Amounts.parse("R 99.90"), 0.001);
        assertEquals(1234567.00, Amounts.parse("1.234.567"), 0.001);
        assertNull(Amounts.parse("abc"));
    }

    @Test
    void csvIsMappedAutomaticallyWithDecimalCommas() {
        String csv = "Datum;Beskrywing;Bedrag;Saldo\n"
            + "03/04/2025;Checkers;-12,50;987,50\n"
            + "04/04/2025;Salary;1000,00;1987,50\n";
        StatementParseResult r = CsvStatementParser.autoParse(csv);
        assertNull(r, "Afrikaans headers aren't recognised, so the user is asked to map columns");

        String english = "Date;Description;Amount;Balance\n"
            + "03/04/2025;Checkers;-12,50;987,50\n"
            + "04/04/2025;Salary;1000,00;1987,50\n";
        r = CsvStatementParser.autoParse(english);
        assertNotNull(r);
        assertEquals(2, r.getItems().size());
        ImportItem first = r.getItems().get(0);
        assertEquals(12.50, first.getAmount(), 0.001);
        assertFalse(first.isCredit());
        assertEquals(LocalDate.of(2025, 4, 3), first.getDate(), "day-first for ambiguous dates");
        assertEquals(987.50, first.getBalance(), 0.001);
        assertTrue(r.getItems().get(1).isCredit());
    }

    @Test
    void csvWithSeparateDebitAndCreditColumns() {
        String csv = "Date,Description,Debit Amount,Credit Amount\n"
            + "2025-04-03,Coffee,35.00,\n"
            + "2025-04-04,Refund,,20.00\n";
        StatementParseResult r = CsvStatementParser.autoParse(csv);
        assertNotNull(r);
        assertEquals(2, r.getItems().size());
        assertFalse(r.getItems().get(0).isCredit());
        assertEquals(35.00, r.getItems().get(0).getAmount(), 0.001);
        assertTrue(r.getItems().get(1).isCredit());
    }

    @Test
    void singleLineOfxIsParsed() {
        String ofx = "<OFX><BANKTRANLIST><STMTTRN><TRNTYPE>DEBIT<DTPOSTED>20250301<TRNAMT>-50.00<NAME>Shop A"
            + "<STMTTRN><TRNTYPE>CREDIT<DTPOSTED>20250302<TRNAMT>100.00<NAME>Pay</BANKTRANLIST></OFX>";
        List<ImportItem> items = new OfxStatementParser().parse(ofx);
        assertEquals(2, items.size());
        assertFalse(items.get(0).isCredit());
        assertTrue(items.get(1).isCredit());
    }

    @Test
    void qifPrefersDayFirstAndHandlesPaddedDates() {
        String qif = "!Type:Bank\nD03/04/2025\nT-10.00\nPShop\n^\nD1/ 5'24\nT20.00\nPPay\n^\n";
        List<ImportItem> items = new QifStatementParser().parse(qif);
        assertEquals(2, items.size());
        assertEquals(LocalDate.of(2025, 4, 3), items.get(0).getDate());
        assertEquals(LocalDate.of(2024, 5, 1), items.get(1).getDate());
    }

    @Test
    void genericPdfUsesRunningBalanceForDirection() {
        String text = String.join("\n",
            "Some Bank Statement 2025",
            "Opening balance 100.00",
            "01/03/2025 Grocer 20.00 80.00",
            "02/03/2025 Employer 50.00 130.00",
            "03/03/2025 Notice only 10.00 130.00",
            "04/03/2025 Cafe 5.00 125.00",
            "Closing balance 125.00");
        StatementParseResult r = new GenericPdfParser().parseStatement(text);
        assertEquals(3, r.getItems().size());
        assertFalse(r.getItems().get(0).isCredit());
        assertTrue(r.getItems().get(1).isCredit());
        assertTrue(r.isReconciled());
    }
}
