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

/** Locks in fixes from the import-pipeline audit. All statement text is synthetic. */
class ImportAuditRegressionTest {

    private static final String STATEMENT = String.join("\n",
        "First National Bank",
        "Cheque Account : 12345678901",
        "Statement Period : 01 March 2025 to 31 March 2025",
        "Opening Balance 500.00 Cr",
        "Closing Balance 380.00 Cr",
        "02 Mar POS Purchase Some Shop 123456*7890 01 Mar 100.00 400.00Cr",
        "03 Mar POS Purchase Other Shop 123456*7890 02 Mar 20.00 380.00Cr");

    private static File file(Path dir, String name, String content) throws Exception {
        File f = dir.resolve(name).toFile();
        Files.writeString(f.toPath(), content, StandardCharsets.UTF_8);
        return f;
    }

    private static StatementParseResult parse() {
        return new FnbPdfParser().parseStatement(STATEMENT);
    }

    // H1: two copies / overlapping statements in the same batch are only counted once
    @Test
    void overlappingFilesInOneBatchAreCountedOnce(@TempDir Path dir) throws Exception {
        ImportRegistry reg = new ImportRegistry(dir.toString());
        StatementImporter importer = new StatementImporter(new CategorizationRules(), reg, List.of());
        StatementImporter.Prepared a = importer.prepare(file(dir, "a.txt", "one"), parse());
        StatementImporter.Prepared b = importer.prepare(file(dir, "b.txt", "two"), parse());
        assertEquals(2, a.newItems.size());
        assertEquals(0, b.newItems.size());
        assertEquals(2, b.alreadyImported);

        // the identical file twice in one batch
        File same = file(dir, "c.txt", "three");
        StatementImporter importer2 = new StatementImporter(new CategorizationRules(), reg, List.of());
        importer2.prepare(same, parse());
        assertTrue(importer2.prepare(same, parse()).skippedWholeFile);
    }

    // H2: a later overlapping import must not take over (or re-record) fingerprints it didn't add
    @Test
    void laterImportDoesNotOwnEarlierFingerprints(@TempDir Path dir) throws Exception {
        ImportRegistry reg = new ImportRegistry(dir.toString());
        StatementImporter.Prepared a = new StatementImporter(new CategorizationRules(), reg, List.of())
            .prepare(file(dir, "a.txt", "one"), parse());
        reg.record(StatementImporter.recordFor(a, "A"), StatementImporter.fingerprintsOf(a));

        StatementImporter.Prepared b = new StatementImporter(new CategorizationRules(), reg, List.of())
            .prepare(file(dir, "b.txt", "two"), parse());
        assertTrue(StatementImporter.fingerprintsOf(b).isEmpty(), "B added nothing, so it records nothing");
        reg.record(StatementImporter.recordFor(b, "B"), StatementImporter.fingerprintsOf(b));

        // Removing A lets its transactions be imported again (B never owned them)
        reg.forget("A");
        StatementImporter.Prepared again = new StatementImporter(new CategorizationRules(), reg, List.of())
            .prepare(file(dir, "a2.txt", "one again"), parse());
        assertEquals(2, again.newItems.size());
    }

    // H5: a removed import isn't brought back by the silent folder scan, but a manual import still works
    @Test
    void removedImportStaysRemovedForFolderScan(@TempDir Path dir) throws Exception {
        File f = file(dir, "a.txt", "one");
        ImportRegistry reg = new ImportRegistry(dir.toString());
        StatementImporter.Prepared a = new StatementImporter(new CategorizationRules(), reg, List.of()).prepare(f, parse());
        reg.record(StatementImporter.recordFor(a, "A"), StatementImporter.fingerprintsOf(a));
        reg.forget("A");
        reg.save();

        ImportRegistry reloaded = new ImportRegistry(dir.toString());
        reloaded.load();
        StatementImporter.Prepared silent = new StatementImporter(new CategorizationRules(), reloaded, List.of())
            .prepare(f, parse(), true);
        assertTrue(silent.skippedWholeFile);
        assertTrue(silent.dismissed);

        StatementImporter.Prepared manual = new StatementImporter(new CategorizationRules(), reloaded, List.of())
            .prepare(f, parse(), false);
        assertEquals(2, manual.newItems.size());
        reloaded.record(StatementImporter.recordFor(manual, "A2"), StatementImporter.fingerprintsOf(manual));
        assertFalse(reloaded.isDismissed(manual.fileHash), "importing it again clears the dismissal");
    }

    // M16: an unreadable history file is never overwritten
    @Test
    void unreadableHistoryBlocksSaving(@TempDir Path dir) throws Exception {
        Files.createDirectory(dir.resolve("statements.txt")); // reading a directory throws IOException
        ImportRegistry reg = new ImportRegistry(dir.toString());
        reg.load();
        assertTrue(reg.isLoadFailed());
        assertThrows(java.io.IOException.class, reg::save);
    }

    // H6: built-in merchants match whole words; payment channels don't hide the merchant
    @Test
    void builtInMatchingIsWholeWord() {
        assertEquals("Tax", TransactionClassifier.builtInCategory("Internet Pmt To SARS Efiling"));
        assertEquals(TransactionClassifier.PAYMENTS, TransactionClassifier.builtInCategory("Payment To J Huber"));
        assertNull(TransactionClassifier.builtInCategory("Sparks Electrical"));
        assertEquals(TransactionClassifier.PAYMENTS, TransactionClassifier.builtInCategory("FNB App Payment To Mr Dlamini"));
        assertEquals("Eating Out", TransactionClassifier.builtInCategory("POS Purchase Mr D Food"));
        assertEquals("Groceries", TransactionClassifier.builtInCategory("POS Purchase Spar2Ussriversdal"),
            "digits glued to a name still match");
        assertEquals("Groceries", TransactionClassifier.builtInCategory(" CHECKERS HYPER"), "leading space is still lowercased");
    }

    // M1: an overdrawn opening balance (no "Cr") is negative, like the line balances
    @Test
    void overdrawnOpeningBalanceIsNegative() {
        String text = String.join("\n",
            "First National Bank",
            "Statement Period : 01 March 2025 to 31 March 2025",
            "Opening Balance 100.00",
            "Closing Balance 50.00 Cr",
            "02 Mar Magtape Credit Salary 150.00Cr 50.00Cr");
        StatementParseResult r = new FnbPdfParser().parseStatement(text);
        assertEquals(-100.00, r.getOpeningBalance(), 0.001);
        assertTrue(r.isReconciled());
        assertEquals("ZAR", r.getCurrency());
    }

    // M2: interest charged is a bank cost, not a hidden transfer
    @Test
    void overdraftInterestIsABankFee() {
        ImportItem item = new ImportItem(12.30, "Interest Adjustment", LocalDate.of(2025, 3, 1));
        TransactionClassifier.classify(item, new CategorizationRules());
        assertFalse(item.isTransfer());
        assertEquals(TransactionClassifier.BANK_FEES, item.getCategory());
    }

    // M3: punctuation separates words in user rules
    @Test
    void userRulesMatchAcrossPunctuation() {
        CategorizationRules rules = new CategorizationRules();
        rules.addRule("netflix", "Subscriptions");
        rules.addRule("takealot", "Shopping");
        assertEquals("Subscriptions", rules.categorize("NETFLIX.COM Los Gatos"));
        assertEquals("Shopping", rules.categorize("TAKEALOT*ORDER 1234"));
        rules.addRule("mr d", "Eating Out");
        assertNull(rules.categorize("Payment To Mr Dlamini"));
    }

    // M4: short decimal commas and a currency prefix before the sign
    @Test
    void amountEdgeCases() {
        assertEquals(12.5, Amounts.parse("12,5"), 0.001);
        assertEquals(-50.0, Amounts.parse("R -50.00"), 0.001);
        assertEquals(-50.0, Amounts.parse("-R50.00"), 0.001);
        assertEquals(1234.0, Amounts.parse("1,234"), 0.001, "three digits after a lone comma is thousands");
    }

    // M5: the date format that parses every row beats one that drops a couple
    @Test
    void csvPicksTheFormatThatParsesMostRows() {
        StringBuilder csv = new StringBuilder("Date,Description,Amount\n");
        for (int d = 1; d <= 12; d++) csv.append(String.format("03/%02d/2025,Shop,-1.00%n", d)); // ambiguous
        csv.append("03/20/2025,Shop,-1.00\n03/25/2025,Shop,-1.00\n");                              // month-first only
        StatementParseResult r = CsvStatementParser.autoParse(csv.toString());
        assertNotNull(r);
        assertEquals(14, r.getItems().size());
        assertEquals(LocalDate.of(2025, 3, 20), r.getItems().get(12).getDate());
    }

    // M19: "dd Mon" statements that cross New Year roll the year forward
    @Test
    void genericPdfRollsYearOver() {
        String text = String.join("\n",
            "Statement 2025",
            "30 Dec Shop A 10.00 90.00",
            "02 Jan Shop B 10.00 80.00",
            "05 Jan Shop C 10.00 70.00");
        StatementParseResult r = new GenericPdfParser().parseStatement(text);
        // The header year is the year the statement ends in.
        assertEquals(LocalDate.of(2024, 12, 30), r.getItems().get(0).getDate());
        assertEquals(LocalDate.of(2025, 1, 2), r.getItems().get(1).getDate());
    }

    // M20: a refund isn't swallowed by a matching hand-entered purchase
    @Test
    void refundIsNotADuplicateOfAPurchase(@TempDir Path dir) throws Exception {
        String text = String.join("\n",
            "First National Bank",
            "Statement Period : 01 March 2025 to 31 March 2025",
            "Opening Balance 0.00 Cr",
            "Closing Balance 500.00 Cr",
            "02 Mar POS Purchase Mr Price 123456*7890 01 Mar 500.00Cr 500.00Cr");
        Expense typed = new Expense(500, "Shopping", LocalDate.of(2025, 3, 1), "Mr Price");
        StatementImporter.Prepared p = new StatementImporter(new CategorizationRules(),
            new ImportRegistry(dir.toString()), List.of(typed))
            .prepare(file(dir, "s.txt", "x"), new FnbPdfParser().parseStatement(text));
        assertEquals(1, p.newItems.size());
        assertTrue(p.newItems.get(0).isRefund());
    }

    // M21: a salary from a shop that also has a spending rule stays income
    @Test
    void incomeKeepsAnIncomeCategory() {
        CategorizationRules rules = new CategorizationRules();
        rules.addRule("checkers", "Groceries");
        ImportItem salary = new ImportItem(20000, "Magtape Credit Checkers Salaries", LocalDate.of(2025, 3, 25));
        salary.setCredit(true);
        TransactionClassifier.classify(salary, rules);
        assertTrue(salary.isIncome());
        assertEquals(TransactionClassifier.INCOME, salary.getCategory());

        rules.addRule("salaries", "Salary");
        TransactionClassifier.classify(salary, rules);
        assertEquals("Salary", salary.getCategory(), "an income-type user category is used");
    }

    // M22: rows are stamped with the statement's currency when it differs from the base
    @Test
    void importedRowsCarryTheStatementCurrency(@TempDir Path dir) throws Exception {
        StatementImporter.Prepared p = new StatementImporter(new CategorizationRules(),
            new ImportRegistry(dir.toString()), List.of()).prepare(file(dir, "s.txt", "x"), parse());
        assertNull(StatementImporter.toExpenses(p, "I", "ZAR").get(0).getCurrency(), "same as base: left as base");
        assertEquals("ZAR", StatementImporter.toExpenses(p, "I", "USD").get(0).getCurrency());
    }

    // "One of my own accounts": a user rule to Transfers makes it a transfer
    @Test
    void userTransferRuleMakesATransfer() {
        CategorizationRules rules = new CategorizationRules();
        rules.addRule("My Credit Card", TransactionClassifier.TRANSFERS);
        ImportItem item = new ImportItem(1000, "FNB App Payment To My Credit Card", LocalDate.of(2025, 3, 5));
        TransactionClassifier.classify(item, rules);
        assertTrue(item.isTransfer());
    }

    // L5: QIF uses one date order for the whole file
    @Test
    void qifChoosesOneDateOrderPerFile() {
        String qif = "!Type:Bank\nD03/04/2025\nT-1.00\nPA\n^\nD03/25/2025\nT-1.00\nPB\n^\nD03/26/2025\nT-1.00\nPC\n^\n";
        List<ImportItem> items = new QifStatementParser().parse(qif);
        assertEquals(LocalDate.of(2025, 3, 4), items.get(0).getDate(), "the file is month-first, so 03/04 is 4 March");
    }
}
