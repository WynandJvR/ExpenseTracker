package com.wyn.expensetracker;

import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** Regression tests for the round-5 data/persistence audit (tags escaping, undo history, occurrences, etc.). */
class DataRound5Test {

    @TempDir
    Path tempDir;

    private FileStorage storage;
    private ExpenseManager manager;

    private static final LocalDate START = LocalDate.now().minusMonths(3).withDayOfMonth(1);

    @BeforeEach
    void setUp() {
        storage = new FileStorage(tempDir.toString());
        manager = new ExpenseManager();
    }

    private RecurringExpense monthly() {
        return new RecurringExpense(100.0, "Housing", START, "Rent", RecurrenceType.MONTHLY, null);
    }

    private Expense occurrenceOn(LocalDate date) {
        return manager.getExpenses().stream()
            .filter(e -> e.getRecurringId() != null && e.getDate().equals(date))
            .findFirst().orElse(null);
    }

    // ---------- 1. TAGS token escaping ----------

    @Test
    void tagWithQuoteRoundTripsWithoutCorruptingTheRow() throws IOException {
        Expense e = new Expense(12.5, "Tech", LocalDate.of(2025, 3, 1), "Screen");
        e.getTags().add("24\" monitor"); // bypass sanitising: legacy in-memory tag
        e.getTags().add("work");
        e.setCurrency("USD");
        e.setReceiptPath("r1.png");
        RecurringExpense rec = monthly();
        rec.getTags().add("a\"b");
        rec.setCurrency("EUR");
        String id = rec.getId();

        storage.saveExpenses(List.of(e, rec));
        List<Expense> loaded = storage.loadExpenses();

        assertEquals(0, storage.getLastExpenseLoadStats().failedLines);
        Expense le = loaded.stream().filter(x -> !(x instanceof RecurringExpense)).findFirst().orElseThrow();
        assertEquals("USD", le.getCurrency());
        assertEquals("r1.png", le.getReceiptPath());
        assertTrue(le.getTags().contains("work"));
        assertEquals(2, le.getTags().size());
        RecurringExpense lr = (RecurringExpense) loaded.stream().filter(x -> x instanceof RecurringExpense).findFirst().orElseThrow();
        assertEquals("EUR", lr.getCurrency());
        assertEquals(id, lr.getId(), "series id must survive so overrides stay attached");
    }

    @Test
    void sanitizeStripsQuotesFromTags() {
        Expense e = new Expense(1, "A", LocalDate.of(2025, 1, 1), "x");
        e.addTag("24\" monitor");
        e.addTag("\"");
        assertEquals(Set.of("24 monitor"), e.getTags());
    }

    @Test
    void oldFormatUnquotedTagsStillLoad() throws IOException {
        Files.writeString(tempDir.resolve("expenses.txt"),
            "50.0,Food,2025-01-01,Lunch,REGULAR,,TAGS:a|b,CUR:USD\n", StandardCharsets.UTF_8);
        List<Expense> loaded = storage.loadExpenses();
        assertEquals(1, loaded.size());
        assertEquals(new LinkedHashSet<>(List.of("a", "b")), loaded.get(0).getTags());
        assertEquals("USD", loaded.get(0).getCurrency());
    }

    // ---------- 3. bulk changes clear undo history ----------

    @Test
    void stampMissingCurrencyClearsUndoHistory() {
        Expense e = new Expense(100, "Food", LocalDate.of(2025, 1, 1), "x");
        manager.executeCommand(new AddExpenseCommand(manager, e));
        Expense edited = new Expense(120, "Food", LocalDate.of(2025, 1, 1), "x");
        manager.executeCommand(new EditExpenseCommand(manager, e, edited));
        assertTrue(manager.canUndo());

        manager.stampMissingCurrency("ZAR");

        assertFalse(manager.canUndo(), "undo would restore a copy without the stamped currency");
        assertFalse(manager.canRedo());
        assertEquals("ZAR", edited.getCurrency());
    }

    @Test
    void stampMissingCurrencyKeepsHistoryWhenNothingChanged() {
        Expense e = new Expense(100, "Food", LocalDate.of(2025, 1, 1), "x");
        e.setCurrency("USD");
        manager.executeCommand(new AddExpenseCommand(manager, e));
        manager.stampMissingCurrency("ZAR");
        assertTrue(manager.canUndo());
    }

    @Test
    void renameCategoryClearsUndoHistoryOnlyWhenSomethingChanged() {
        Expense e = new Expense(100, "Food", LocalDate.of(2025, 1, 1), "x");
        manager.executeCommand(new AddExpenseCommand(manager, e));
        manager.renameCategory("Nope", "Other");
        assertTrue(manager.canUndo());
        manager.renameCategory("Food", "Groceries");
        assertFalse(manager.canUndo());
    }

    // ---------- 4. deleting a generated occurrence ----------

    @Test
    void deletingAnOccurrenceSkipsItAndPersists() throws IOException {
        RecurringExpense rec = monthly();
        manager.addExpense(rec);
        LocalDate target = START.plusMonths(1);
        Expense occ = occurrenceOn(target);
        assertNotNull(occ);

        manager.executeCommand(new DeleteExpenseCommand(manager, occ));
        assertNull(occurrenceOn(target));
        manager.generateRecurringExpenses(LocalDate.now());
        assertNull(occurrenceOn(target), "must not reappear after regeneration");

        storage.saveExpenses(manager.getExpensesForSave());
        storage.saveRecurringOverrides(manager.getOverrides());
        ExpenseManager reloaded = new ExpenseManager();
        reloaded.setOverrides(storage.loadRecurringOverrides());
        reloaded.loadExpenses(storage.loadExpenses());
        assertTrue(reloaded.getExpenses().stream()
            .noneMatch(e -> e.getRecurringId() != null && e.getDate().equals(target)));

        manager.undo();
        assertNotNull(occurrenceOn(target));
        assertTrue(manager.getOverrides().isEmpty());
    }

    @Test
    void undoingOccurrenceDeleteRestoresPriorEdit() {
        RecurringExpense rec = monthly();
        manager.addExpense(rec);
        LocalDate target = START.plusMonths(1);
        manager.editOccurrence(occurrenceOn(target), 150.0, null, null);
        Expense edited = occurrenceOn(target);
        assertEquals(150.0, edited.getAmount());

        manager.executeCommand(new DeleteExpenseCommand(manager, edited));
        assertNull(occurrenceOn(target));
        manager.undo();
        assertEquals(150.0, occurrenceOn(target).getAmount());
    }

    // ---------- 6. Make Recurring / pattern -> recurring ----------

    @Test
    void recurringFromCopiesFlagsCurrencyAndTags() {
        Expense e = new Expense(30, "Subs", LocalDate.of(2025, 1, 5), "Stream");
        e.setCurrency("USD");
        e.setRefund(true);
        e.addTag("media");
        e.setImportId("IMP-1");
        RecurringExpense r = RecurringController.recurringFrom(e, RecurrenceType.MONTHLY, null);
        assertEquals("USD", r.getCurrency());
        assertTrue(r.isRefund());
        assertEquals(Set.of("media"), r.getTags());
        assertNull(r.getImportId(), "undoing the import must not delete the series");
    }

    @Test
    void monthEndChargesKeepTheirDay() {
        assertEquals(LocalDate.of(2026, 3, 31), ExpenseManager.nthDate(RecurrenceType.MONTHLY, LocalDate.of(2026, 1, 31), 2));
        List<Expense> ledger = new ArrayList<>();
        Expense imp = new Expense(1, "x", LocalDate.of(2026, 3, 5), "LINE");
        imp.setImportId("IMP-1");
        ledger.add(imp);
        // Last charge 31 Jan, statements to 5 Mar: next is 31 Mar, not the 28th.
        assertEquals(LocalDate.of(2026, 3, 31),
            RecurringController.firstDateAfterImports(RecurrenceType.MONTHLY, LocalDate.of(2026, 1, 31), ledger));
    }

    @Test
    void makeRecurringStartsAfterMonthsAlreadyLoggedByHand() {
        List<Expense> ledger = new ArrayList<>();
        Expense first = new Expense(500, "Home", LocalDate.of(2026, 1, 10), "Gardener");
        ledger.add(first);
        ledger.add(new Expense(500, "Home", LocalDate.of(2026, 2, 10), "Gardener"));
        ledger.add(new Expense(500, "Home", LocalDate.of(2026, 3, 10), "gardener "));
        assertEquals(LocalDate.of(2026, 4, 10), RecurringController.makeRecurringStart(first, RecurrenceType.MONTHLY, ledger));
    }

    @Test
    void recurringFromARealChargeStartsAfterEverythingImported() {
        List<Expense> ledger = new ArrayList<>();
        for (int m = 0; m < 6; m++) {
            Expense e = new Expense(m < 3 ? 89 : 99, "Subs", LocalDate.of(2025, 3 + m, 1), "HYBRID SUB");
            e.setImportId("IMP-1");
            ledger.add(e);
        }
        Expense lastImport = new Expense(12, "Food", LocalDate.of(2025, 8, 27), "LATEST LINE");
        lastImport.setImportId("IMP-1");
        ledger.add(lastImport);
        // Last Hybrid charge 1 Aug, statements run to 27 Aug: the series starts 1 Sep.
        assertEquals(LocalDate.of(2025, 9, 1),
            RecurringController.firstDateAfterImports(RecurrenceType.MONTHLY, LocalDate.of(2025, 8, 1), ledger));

        RecurringPatternDetector.DetectedPattern p = new RecurringPatternDetector.DetectedPattern(
            "HYBRID SUB", "Subs", 99, RecurrenceType.MONTHLY, LocalDate.of(2025, 3, 1), new ArrayList<>(ledger.subList(0, 6)));
        RecurringExpense t = RecurringController.templateFromPattern(p, ledger);
        assertEquals(LocalDate.of(2025, 9, 1), t.getDate());
        assertEquals(99, t.getAmount(), 0.001);

        // The real charges stay, and no generated occurrence lands on an imported month.
        manager.addExpense(t);
        ledger.forEach(manager::addExpense);
        manager.generateRecurringExpenses(LocalDate.of(2025, 12, 31));
        assertEquals(6, manager.getExpenses().stream().filter(e -> e.getRecurringId() == null
            && "HYBRID SUB".equals(e.getDescription())).count());
        assertTrue(manager.getExpenses().stream().filter(e -> e.getRecurringId() != null)
            .allMatch(e -> e.getDate().isAfter(LocalDate.of(2025, 8, 27))));
    }

    @Test
    void patternTemplateKeepsCurrencyRefundAndTags() {
        List<Expense> ledger = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            Expense e = new Expense(20, "Subs", START.plusMonths(i), "CLOUD");
            e.setCurrency("USD");
            e.setRefund(true);
            e.addTag("cloud");
            ledger.add(e);
        }
        RecurringPatternDetector.DetectedPattern p = new RecurringPatternDetector.DetectedPattern(
            "CLOUD", "Subs", 20, RecurrenceType.MONTHLY, START, new ArrayList<>(ledger));
        RecurringExpense t = RecurringController.templateFromPattern(p, ledger);
        assertEquals("USD", t.getCurrency());
        assertTrue(t.isRefund());
        assertEquals(Set.of("cloud"), t.getTags());
    }

    // ---------- 8. recursive profile delete ----------

    @Test
    void deleteProfileRemovesReceiptsSubfolder() throws IOException {
        ProfileManager pm = new ProfileManager(tempDir.resolve("root").toString());
        assertTrue(pm.createProfile("A"));
        assertTrue(pm.createProfile("B"));
        Path receipts = Path.of(pm.getProfileDir("B"), "receipts");
        Files.createDirectories(receipts);
        Files.writeString(receipts.resolve("r.png"), "x");
        Files.writeString(Path.of(pm.getProfileDir("B"), "expenses.txt"), "x");

        assertTrue(pm.deleteProfile("B"));
        assertFalse(new File(pm.getProfileDir("B")).exists());
        assertEquals(List.of("A"), pm.listProfiles());
    }

    // ---------- 9. non-finite amounts ----------

    @Test
    void nonFiniteAmountsAreRejected() {
        assertFalse(UIUtils.isPositiveDouble("Infinity"));
        assertFalse(UIUtils.isPositiveDouble("1e999"));
        assertFalse(UIUtils.isPositiveDouble("NaN"));
        assertTrue(UIUtils.isPositiveDouble("12.5"));
        assertThrows(IllegalArgumentException.class, () -> ExpenseManager.validateExpense(
            new Expense(Double.POSITIVE_INFINITY, "A", LocalDate.of(2025, 1, 1), "x")));
        assertThrows(IllegalArgumentException.class, () -> ExpenseManager.validateExpense(
            new Expense(Double.NaN, "A", LocalDate.of(2025, 1, 1), "x")));
        assertThrows(IllegalArgumentException.class, () ->
            new Debt("d", "Loan", Double.NaN, 5, 12, LocalDate.of(2025, 1, 1), "MONTHLY", 0, null));
        assertThrows(IllegalArgumentException.class, () ->
            new Debt("d", "Loan", 1000, Double.NaN, 12, LocalDate.of(2025, 1, 1), "MONTHLY", 0, null));
        Debt ok = new Debt("d", "Loan", 1000, 5, 12, LocalDate.of(2025, 1, 1), "MONTHLY", 0, null);
        assertThrows(NumberFormatException.class, () -> ok.setPrincipal(Double.POSITIVE_INFINITY));
    }

    @Test
    void nonFiniteAmountOnDiskIsSkipped() throws IOException {
        Files.writeString(tempDir.resolve("expenses.txt"),
            "Infinity,Food,2025-01-01,Bad,REGULAR,\nNaN,Food,2025-01-01,Bad,REGULAR,\n10.0,Food,2025-01-01,Ok,REGULAR,\n",
            StandardCharsets.UTF_8);
        List<Expense> loaded = storage.loadExpenses();
        assertEquals(1, loaded.size());
        assertEquals(10.0, loaded.get(0).getAmount());
    }

    // ---------- C. locale-independent amount entry ----------

    @Test
    void userAmountsParseInEitherDecimalStyle() {
        assertEquals(123.45, UIUtils.parseAmount("123,45"));
        assertEquals(123.45, UIUtils.parseAmount("123.45"));
        assertEquals(1234.56, UIUtils.parseAmount("1 234,56"));
        assertEquals(1234.56, UIUtils.parseAmount("1,234.56"));
        assertNull(UIUtils.parseAmount("1e999"));
        assertNull(UIUtils.parseAmount("Infinity"));
        assertNull(UIUtils.parseAmount("-5"));
        assertNull(UIUtils.parseAmount("abc"));
        assertNull(UIUtils.parseAmount(""));
        assertTrue(UIUtils.isPositiveAmount("0,50"));
        assertFalse(UIUtils.isPositiveAmount("0"));
    }

    @Test
    void malformedUserAmountsAreRejected() {
        assertNull(UIUtils.parseAmount("..5"));
        assertNull(UIUtils.parseAmount("1,2,3"));
        assertNull(UIUtils.parseAmount("1.2.3"));
        assertNull(UIUtils.parseAmount("1,234,5"));
        assertNull(UIUtils.parseAmount("1,2345"));
        // A comma before exactly three digits is thousands; "1.234" is ambiguous, so rejected.
        assertEquals(1234.0, UIUtils.parseAmount("1,234"));
        assertEquals(150000.0, UIUtils.parseAmount("150,000"));
        assertNull(UIUtils.parseAmount("1.234"));
        assertNull(UIUtils.parseAmount(",123"));
        assertEquals(1234.0, UIUtils.parseAmount("1 234"));
        assertEquals(1234.0, UIUtils.parseAmount("1234"));
        assertEquals(1234567.89, UIUtils.parseAmount("1,234,567.89"));
        assertEquals(1234.56, UIUtils.parseAmount("1.234,56"));
        assertEquals(1234.5, UIUtils.parseAmount("1'234.50"));
        assertEquals(0.5, UIUtils.parseAmount(".5"));
    }

    @Test
    void editPrefillRoundTripsUnderCommaLocale() {
        Locale saved = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("en-ZA"));
            String text = UIUtils.formatAmountForEdit(123.45);
            assertEquals("123.45", text);
            assertEquals(123.45, UIUtils.parseAmount(text));
        } finally {
            Locale.setDefault(saved);
        }
    }

    // ---------- 10. Excel export flags ----------

    @Test
    void excelExportIncludesRefundExcludedAndTags() throws IOException {
        Expense e = new Expense(10, "Food", LocalDate.of(2025, 1, 1), "x");
        e.setRefund(true);
        e.setExcluded(true);
        e.addTag("a");
        e.addTag("b");
        String path = tempDir.resolve("out.xlsx").toString();
        ExcelExporter.exportExpenses(List.of(e), path);
        try (Workbook wb = WorkbookFactory.create(new File(path))) {
            Row h = wb.getSheetAt(0).getRow(0);
            assertEquals("IsRefund", h.getCell(11).getStringCellValue());
            assertEquals("IsExcluded", h.getCell(12).getStringCellValue());
            assertEquals("Tags", h.getCell(13).getStringCellValue());
            Row r = wb.getSheetAt(0).getRow(1);
            assertTrue(r.getCell(11).getBooleanCellValue());
            assertTrue(r.getCell(12).getBooleanCellValue());
            assertEquals("a|b", r.getCell(13).getStringCellValue());
        }
    }

    // ---------- 11. clipboard currency ----------

    @Test
    void clipboardUsesExpenseCurrencySymbol() {
        Expense usd = new Expense(10, "Food", LocalDate.of(2025, 1, 1), "x");
        usd.setCurrency("USD");
        assertTrue(UIUtils.clipboardText(usd, "R").startsWith("$10.00\t"));
        Expense base = new Expense(10, "Food", LocalDate.of(2025, 1, 1), "x");
        assertTrue(UIUtils.clipboardText(base, "R").startsWith("R10.00\t"));
    }

    @Test
    void categoryDropdownsStayAlphabetical() {
        javafx.collections.ObservableList<String> cats =
            javafx.collections.FXCollections.observableArrayList("Transport", "food", "Airtime");
        SharedState s = new SharedState(new ExpenseManager(), null, cats, new HashMap<>(), null, null);
        assertEquals(List.of("Airtime", "food", "Transport"), s.getSortedCategories());
        cats.add("Bank fees");
        cats.set(cats.indexOf("food"), "Zoo");
        assertEquals(List.of("Airtime", "Bank fees", "Transport", "Zoo"), s.getSortedCategories());
        assertEquals(List.of("Transport", "Zoo", "Airtime", "Bank fees"), cats, "the list itself isn't reordered");
    }
}
