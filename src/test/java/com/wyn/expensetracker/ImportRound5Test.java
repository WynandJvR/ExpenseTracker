package com.wyn.expensetracker;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Round-5 audit fixes on the import side. Synthetic data only. */
class ImportRound5Test {

    private static final String NL = String.valueOf((char) 10);

    private static String text(String... lines) {
        return String.join(NL, lines);
    }

    private static List<ImportItem> pdf(String... lines) {
        return new GenericPdfParser().parseStatement(text(lines)).getItems();
    }

    private static File file(Path dir, String name, String content) throws Exception {
        File f = dir.resolve(name).toFile();
        Files.writeString(f.toPath(), content, StandardCharsets.UTF_8);
        return f;
    }

    /** Transactions with running balances, one per day of January 2024 from {@code fromDay} to {@code toDay}. */
    private static StatementParseResult january(int fromDay, int toDay) {
        List<ImportItem> items = new ArrayList<>();
        for (int d = fromDay; d <= toDay; d++) {
            ImportItem i = new ImportItem(10, "Shop " + d, LocalDate.of(2024, 1, d));
            i.setBalance(1000.0 - 10 * d);
            items.add(i);
        }
        return new StatementParseResult("Test Bank", items);
    }

    private static StatementImporter importer(ImportRegistry reg) {
        return new StatementImporter(new CategorizationRules(), reg, List.of());
    }

    // 1: a description ending in digits isn't glued onto a space-grouped amount
    @Test
    void descriptionDigitsAreNotReadAsThousands() {
        List<ImportItem> items = pdf(
            "Opening balance 1 150.00",
            "01/03/2024 Uber trip 2 150.00 1 000.00",
            "02/03/2024 Rent 2 000.00 3 000.00 Cr");
        assertEquals(150.00, items.get(0).getAmount(), 0.001);
        assertFalse(items.get(0).isCredit());
        assertEquals("Uber trip 2", items.get(0).getDescription());
        assertEquals(2000.00, items.get(1).getAmount(), 0.001, "real space grouping still reads in full");
        assertTrue(items.get(1).isCredit());
    }

    @Test
    void withoutSpaceGroupingTrailingDigitsStayInTheDescription() {
        List<ImportItem> items = pdf(
            "01/03/2024 Uber trip 2 150.00",
            "02/03/2024 Flight 123 80.00",
            "03/03/2024 Shop 1,250.00");
        assertEquals(150.00, items.get(0).getAmount(), 0.001);
        assertEquals("Uber trip 2", items.get(0).getDescription());
        assertEquals(80.00, items.get(1).getAmount(), 0.001);
        assertEquals("Flight 123", items.get(1).getDescription());
        assertEquals(1250.00, items.get(2).getAmount(), 0.001);
    }

    @Test
    void spaceGroupedAmountWithoutBalanceOrEvidenceReadsWhole() {
        List<ImportItem> items = pdf("05/03/2024 WOOLWORTHS FOOD 1 500.00");
        assertEquals(1, items.size());
        assertEquals(1500.00, items.get(0).getAmount(), 0.001);
        assertEquals("WOOLWORTHS FOOD", items.get(0).getDescription());
    }

    @Test
    void commaGroupedStatementKeepsLeadingDigitsInDescription() {
        List<ImportItem> items = pdf(
            "01/03/2024 Uber trip 2 150.00",
            "02/03/2024 Salary 1,234.56 Cr");
        assertEquals(150.00, items.get(0).getAmount(), 0.001);
        assertEquals("Uber trip 2", items.get(0).getDescription());
        assertEquals(1234.56, items.get(1).getAmount(), 0.001);
    }

    // 2: removing an import doesn't strand the rows an overlapping, still-imported statement covers
    @Test
    void reimportingOverlappingStatementRestoresRowsOfRemovedImport(@TempDir Path dir) throws Exception {
        ImportRegistry reg = new ImportRegistry(dir.toString());
        File a = file(dir, "a.csv", "A");
        File b = file(dir, "b.csv", "B");
        StatementImporter.Prepared pa = importer(reg).prepare(a, january(1, 20));
        reg.record(StatementImporter.recordFor(pa, "A"), StatementImporter.fingerprintsOf(pa));
        StatementImporter.Prepared pb = importer(reg).prepare(b, january(10, 31));
        assertEquals(11, pb.alreadyImported);
        reg.record(StatementImporter.recordFor(pb, "B"), StatementImporter.fingerprintsOf(pb));

        reg.forget("A"); // "Remove import" deletes Jan 1-20, including Jan 10-20 that B also covers

        assertTrue(importer(reg).prepare(b, january(10, 31), true).skippedWholeFile,
            "the folder scan still leaves a known file alone");
        StatementImporter.Prepared again = importer(reg).prepare(b, january(10, 31), false);
        assertFalse(again.skippedWholeFile);
        assertEquals(11, again.newItems.size(), "Jan 10-20 come back");
        assertEquals(11, again.alreadyImported, "Jan 21-31 are still B's");

        StatementImporter.Prepared untouched = importer(reg).prepare(a, january(1, 20), false);
        assertEquals(20, untouched.newItems.size());
    }

    @Test
    void reimportingAnUntouchedFileByHandSaysAlreadyImported(@TempDir Path dir) throws Exception {
        ImportRegistry reg = new ImportRegistry(dir.toString());
        File a = file(dir, "a.csv", "A");
        StatementImporter.Prepared pa = importer(reg).prepare(a, january(1, 5));
        reg.record(StatementImporter.recordFor(pa, "A"), StatementImporter.fingerprintsOf(pa));
        StatementImporter.Prepared again = importer(reg).prepare(a, january(1, 5), false);
        assertTrue(again.skippedWholeFile);
        assertFalse(again.dismissed);
        assertTrue(again.newItems.isEmpty());
    }

    // 3: the folder scan doesn't resurrect a removed import through another copy of the statement
    @Test
    void secondCopyOfRemovedStatementStaysRemovedForFolderScan(@TempDir Path dir) throws Exception {
        ImportRegistry reg = new ImportRegistry(dir.toString());
        StatementImporter.Prepared pa = importer(reg).prepare(file(dir, "a.pdf", "A"), january(1, 5));
        reg.record(StatementImporter.recordFor(pa, "A"), StatementImporter.fingerprintsOf(pa));
        reg.forget("A");
        reg.save();

        ImportRegistry reloaded = new ImportRegistry(dir.toString());
        reloaded.load();
        File copy = file(dir, "a-redownloaded.pdf", "A, different bytes");
        StatementImporter.Prepared silent = importer(reloaded).prepare(copy, january(1, 5), true);
        assertTrue(silent.skippedWholeFile);
        assertTrue(silent.dismissed);

        // A CSV covering the removed period plus new days only brings in the new days.
        StatementImporter.Prepared wider = importer(reloaded).prepare(file(dir, "jan.csv", "csv"), january(1, 8), true);
        assertEquals(3, wider.newItems.size());

        StatementImporter.Prepared manual = importer(reloaded).prepare(copy, january(1, 5), false);
        assertEquals(5, manual.newItems.size());
        reloaded.record(StatementImporter.recordFor(manual, "A2"), StatementImporter.fingerprintsOf(manual));
        reloaded.forget("A2"); // removing it again dismisses its transactions again
        StatementImporter.Prepared afterManual = importer(reloaded).prepare(copy, january(1, 5), true);
        assertTrue(afterManual.dismissed);
    }

    @Test
    void manualImportClearsDismissedTransactions(@TempDir Path dir) throws Exception {
        ImportRegistry reg = new ImportRegistry(dir.toString());
        StatementImporter.Prepared pa = importer(reg).prepare(file(dir, "a.pdf", "A"), january(1, 5));
        reg.record(StatementImporter.recordFor(pa, "A"), StatementImporter.fingerprintsOf(pa));
        reg.forget("A");
        File copy = file(dir, "copy.pdf", "copy");
        StatementImporter.Prepared manual = importer(reg).prepare(copy, january(1, 5), false);
        reg.record(StatementImporter.recordFor(manual, "A2"), StatementImporter.fingerprintsOf(manual));
        reg.purge("A2"); // e.g. rebuilt after data loss: not dismissed any more
        StatementImporter.Prepared silent = importer(reg).prepare(file(dir, "copy2.pdf", "copy2"), january(1, 5), true);
        assertEquals(5, silent.newItems.size());
    }

    // 4: Chase-style export: "Description" beats "Details"; a "...Type" column is never the description
    @Test
    void csvPrefersDescriptionOverDetailsAndType() {
        StatementParseResult r = CsvStatementParser.autoParse(text(
            "Details,Posting Date,Description,Amount,Type,Balance",
            "DEBIT,01/15/2024,STARBUCKS STORE 123,-5.50,DEBIT_CARD,994.50",
            "CREDIT,01/16/2024,PAYROLL ACME,1000.00,ACH_CREDIT,1994.50"));
        assertNotNull(r);
        assertEquals("STARBUCKS STORE 123", r.getItems().get(0).getDescription());
        assertFalse(r.getItems().get(0).isCredit());
        assertTrue(r.getItems().get(1).isCredit());

        StatementParseResult t = CsvStatementParser.autoParse(text(
            "Date,Transaction Type,Details,Amount",
            "2024-01-02,POS,Corner Shop,-12.00",
            "2024-01-03,EFT,Landlord,-500.00"));
        assertEquals("Corner Shop", t.getItems().get(0).getDescription());
    }

    // 5: an "Amount" column with a Debit/Credit (Dr/Cr) indicator column
    @Test
    void csvDebitCreditIndicatorColumnSetsDirection() {
        StatementParseResult r = CsvStatementParser.autoParse(text(
            "Date,Description,Amount,Debit/Credit",
            "2024-01-02,Shop,50.00,Debit",
            "2024-01-03,Salary,1000.00,Credit"));
        assertFalse(r.getItems().get(0).isCredit());
        assertTrue(r.getItems().get(1).isCredit());

        StatementParseResult dr = CsvStatementParser.autoParse(text(
            "Date,Narrative,Amount,Dr/Cr",
            "02/01/2024,Shop,50.00,DR",
            "03/01/2024,Refund,20.00,CR",
            "04/01/2024,Fee,5.00,D"));
        assertFalse(dr.getItems().get(0).isCredit());
        assertTrue(dr.getItems().get(1).isCredit());
        assertFalse(dr.getItems().get(2).isCredit());
        assertEquals(50.00, dr.getItems().get(0).getAmount(), 0.001);
    }

    // 6 + 10: two-digit QIF years; a final record without '^'
    @Test
    void qifTwoDigitYearsAndMissingFinalCaret() {
        List<ImportItem> items = new QifStatementParser().parse(text(
            "!Type:Bank",
            "D6/ 1/94", "T-10.00", "PShop", "^",
            "D01/15/24", "T-20.00", "POther", "^",
            "D12/31/49", "T35.00", "PLast"));
        assertEquals(3, items.size());
        assertEquals(LocalDate.of(1994, 6, 1), items.get(0).getDate());
        assertEquals(LocalDate.of(2024, 1, 15), items.get(1).getDate());
        assertEquals(LocalDate.of(2049, 12, 31), items.get(2).getDate());
        assertTrue(items.get(2).isCredit());
    }

    // 7: one day/month order per statement; single-digit, two-digit-year and "d MMM yyyy" dates
    @Test
    void pdfDateOrderIsDecidedPerStatement() {
        List<ImportItem> us = pdf(
            "03/04/2024 Shop A 10.00 90.00",
            "03/15/2024 Shop B 10.00 80.00");
        assertEquals(LocalDate.of(2024, 3, 4), us.get(0).getDate());
        assertEquals(LocalDate.of(2024, 3, 15), us.get(1).getDate());

        List<ImportItem> za = pdf(
            "5/3/2024 Shop A 10.00 90.00",
            "15/03/24 Shop B 10.00 80.00",
            "7 Mar 2024 Shop C 10.00 70.00",
            "03/04/2024 Shop D 10.00 60.00");
        assertEquals(4, za.size());
        assertEquals(LocalDate.of(2024, 3, 5), za.get(0).getDate());
        assertEquals(LocalDate.of(2024, 3, 15), za.get(1).getDate());
        assertEquals(LocalDate.of(2024, 3, 7), za.get(2).getDate());
        assertEquals(LocalDate.of(2024, 4, 3), za.get(3).getDate());
        assertEquals("Shop B", za.get(1).getDescription());
    }

    // 8: manual CSV mapping skips a preamble and decodes like automatic detection
    @Test
    void csvHeaderRowFoundBelowPreamble() {
        String[] lines = {"Account: 1234567", "Period: January 2024", "", "Date;Description;Amount",
            "2024-01-02;Shop;-5.00", "2024-01-03;Cafe;-7.00"};
        int h = CsvStatementParser.findHeaderRow(lines);
        assertEquals(3, h);
        char delim = CsvStatementParser.detectDelimiter(String.join(NL, java.util.Arrays.copyOfRange(lines, h, lines.length)));
        assertEquals(';', delim);
        List<ImportItem> items = CsvStatementParser.parse(String.join(NL, lines), delim, 0, 2, -1, 1, -1,
            "yyyy-MM-dd", true, h + 1);
        assertEquals(2, items.size());
        assertEquals(0, CsvStatementParser.findHeaderRow(new String[]{"Date,Amount", "2024-01-02,5.00"}));
    }

    @Test
    void readTextStripsBomAndFallsBackToWindows1252(@TempDir Path dir) throws Exception {
        File bom = dir.resolve("bom.csv").toFile();
        Files.write(bom.toPath(), new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF, 'D', 'a', 't', 'e'});
        assertEquals("Date", StatementImporter.readText(bom));
        File ansi = dir.resolve("ansi.csv").toFile();
        Files.write(ansi.toPath(), new byte[]{'C', 'a', 'f', (byte) 0xE9});
        assertEquals("Caf" + (char) 0xE9, StatementImporter.readText(ansi));
    }

    // 10: OFX character entities; non-breaking-space thousands separators
    @Test
    void ofxEntitiesAreDecoded() {
        List<ImportItem> items = new OfxStatementParser().parse(
            "<OFX><STMTTRN><TRNTYPE>DEBIT<DTPOSTED>20240102<TRNAMT>-5.00"
            + "<NAME>AT&amp;T &lt;Mobile&gt;<MEMO>Bob&apos;s &quot;plan&quot;</STMTTRN></OFX>");
        assertEquals("AT&T <Mobile> - Bob's \"plan\"", items.get(0).getDescription());
        assertEquals("&lt;", OfxStatementParser.decodeEntities("&amp;lt;"));
    }

    @Test
    void nonBreakingSpaceThousandsSeparator() {
        assertEquals(1234.50, Amounts.parse("1" + (char) 0xA0 + "234.50"), 0.001);
    }
}
