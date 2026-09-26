package com.wyn.expensetracker;

import javafx.collections.FXCollections;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** Regression tests for persistence / data-model fixes (encoding, undo, overrides, currency, backups). */
class PersistenceFixesTest {

    @TempDir
    Path tempDir;

    private FileStorage storage;
    private ExpenseManager manager;

    @BeforeEach
    void setUp() {
        storage = new FileStorage(tempDir.toString());
        manager = new ExpenseManager();
    }

    private static final LocalDate START = LocalDate.now().minusMonths(3).withDayOfMonth(1);

    private RecurringExpense monthly(String category) {
        return new RecurringExpense(100.0, category, START, "Rent", RecurrenceType.MONTHLY, null);
    }

    private Expense instanceOn(RecurringExpense template, LocalDate date) {
        return manager.getExpenses().stream()
            .filter(e -> e.getSourceRecurringExpense() == template && e.getDate().equals(date))
            .findFirst().orElse(null);
    }

    // ======================== 1. Encoding ========================

    @Test
    void nonAsciiRoundTripsThroughUtf8() throws IOException {
        Expense e = new Expense(12.5, "Café", LocalDate.of(2025, 3, 1), "Ωmega R 1 000 — naïve");
        e.addTag("Zürich");
        storage.saveExpenses(List.of(e));
        storage.saveCategories(FXCollections.observableArrayList("Café", "Ωmega"));
        storage.saveTags(List.of("Zürich"));

        // File is really UTF-8 on disk
        String raw = Files.readString(tempDir.resolve("expenses.txt"), StandardCharsets.UTF_8);
        assertTrue(raw.contains("Café"));

        Expense loaded = storage.loadExpenses().get(0);
        assertEquals("Café", loaded.getCategory());
        assertEquals("Ωmega R 1 000 — naïve", loaded.getDescription());
        assertTrue(loaded.hasTag("Zürich"));
        assertEquals(List.of("Café", "Ωmega"), storage.loadCategories());
        assertEquals(List.of("Zürich"), storage.loadTags());
    }

    @Test
    void legacyNonUtf8FileIsStillReadable() throws IOException {
        // Older versions could leave a file in the platform charset (e.g. Cp1252).
        Charset legacy = Charset.forName("windows-1252");
        Files.write(tempDir.resolve("categories.txt"), "Café\n".getBytes(legacy));
        List<String> cats = storage.loadCategories();
        assertEquals(1, cats.size());
        // Either the platform default (Cp1252) or ISO-8859-1 decodes 0xE9 as é
        assertEquals("Café", cats.get(0));
    }

    @Test
    void profileNameRoundTripsNonAscii() throws IOException {
        ProfileManager pm = new ProfileManager(tempDir.toString());
        assertTrue(pm.createProfile("Ménage"));
        pm.setActiveProfile("Ménage");
        assertEquals("Ménage", pm.getActiveProfile());
    }

    // ======================== 2. Line breaks ========================

    @Test
    void lineBreaksInDescriptionDoNotCorruptFile() throws IOException {
        Expense e = new Expense(10.0, "Food", LocalDate.of(2025, 1, 1), "line1\r\nline2\rline3\nline4");
        RecurringExpense r = new RecurringExpense(5.0, "Bills", LocalDate.of(2025, 1, 1),
            "a\nb", RecurrenceType.MONTHLY, null);
        storage.saveExpenses(List.of(e, r));

        List<String> lines = Files.readAllLines(tempDir.resolve("expenses.txt"), StandardCharsets.UTF_8);
        assertEquals(2, lines.size());
        List<Expense> loaded = storage.loadExpenses();
        assertEquals(2, loaded.size());
        assertEquals("line1 line2 line3 line4", loaded.get(0).getDescription());
        assertEquals("a b", loaded.get(1).getDescription());
        assertEquals(0, storage.getLastExpenseLoadStats().failedLines);
    }

    @Test
    void overrideWithLineBreakStaysOnOneLine() throws IOException {
        OccurrenceOverride o = new OccurrenceOverride("tid", LocalDate.of(2025, 1, 1));
        o.setDescription("x\r\ny");
        storage.saveRecurringOverrides(List.of(o));
        List<OccurrenceOverride> loaded = storage.loadRecurringOverrides();
        assertEquals(1, loaded.size());
        assertEquals("x y", loaded.get(0).getDescription());
    }

    // ======================== 3. Undo delete restores overrides ========================

    @Test
    void undoDeleteRecurringRestoresOverrides() {
        RecurringExpense rent = monthly("Housing");
        manager.executeCommand(new AddExpenseCommand(manager, rent));
        LocalDate second = START.plusMonths(1);
        LocalDate third = START.plusMonths(2);
        manager.skipOccurrence(instanceOn(rent, second));
        manager.editOccurrence(instanceOn(rent, third), 150.0, null, null);
        assertEquals(2, manager.getOverrides().size());

        manager.executeCommand(new DeleteRecurringExpenseCommand(manager, rent));
        assertTrue(manager.getOverrides().isEmpty());

        manager.undo();
        assertEquals(2, manager.getOverrides().size());
        assertNull(instanceOn(rent, second), "skipped occurrence stays skipped after undo");
        assertEquals(150.0, instanceOn(rent, third).getAmount(), 0.001);

        // Redo deletes again, and a second undo still restores them
        manager.redo();
        assertTrue(manager.getOverrides().isEmpty());
        manager.undo();
        assertEquals(2, manager.getOverrides().size());
    }

    @Test
    void undoDeleteExpenseCommandOnTemplateRestoresOverrides() {
        RecurringExpense rent = monthly("Housing");
        manager.addExpense(rent);
        manager.skipOccurrence(instanceOn(rent, START.plusMonths(1)));
        manager.executeCommand(new DeleteExpenseCommand(manager, rent));
        assertTrue(manager.getOverrides().isEmpty());
        manager.undo();
        assertEquals(1, manager.getOverrides().size());
    }

    // ======================== 4. Undo robustness ========================

    @Test
    void undoDeleteOfLegacyInvalidExpenseSucceeds() {
        String longCat = "C".repeat(ExpenseManager.MAX_CATEGORY_LENGTH + 10);
        Expense legacy = new Expense(10.0, longCat, LocalDate.of(2025, 1, 1), "legacy");
        manager.loadExpenses(List.of(legacy)); // loading bypasses validation
        manager.executeCommand(new DeleteExpenseCommand(manager, legacy));
        assertTrue(manager.getExpenses().isEmpty());
        manager.undo();
        assertTrue(manager.getExpenses().contains(legacy));
    }

    @Test
    void undoEditOfLegacyInvalidExpenseSucceeds() {
        String longCat = "C".repeat(ExpenseManager.MAX_CATEGORY_LENGTH + 10);
        Expense legacy = new Expense(10.0, longCat, LocalDate.of(2025, 1, 1), "legacy");
        manager.loadExpenses(List.of(legacy));
        Expense fixed = new Expense(10.0, "Food", LocalDate.of(2025, 1, 1), "legacy");
        manager.executeCommand(new EditExpenseCommand(manager, legacy, fixed));
        manager.undo();
        assertTrue(manager.getExpenses().contains(legacy));
        assertFalse(manager.getExpenses().contains(fixed));
    }

    @Test
    void failingUndoKeepsCommandOnStack() {
        int[] undoCalls = {0};
        Command flaky = new Command() {
            @Override public void execute() { }
            @Override public void undo() {
                if (undoCalls[0]++ == 0) throw new IllegalStateException("boom");
            }
        };
        manager.executeCommand(flaky);
        assertThrows(IllegalStateException.class, manager::undo);
        assertTrue(manager.canUndo(), "command must not be lost when undo throws");
        assertFalse(manager.canRedo());
        manager.undo();
        assertFalse(manager.canUndo());
        assertTrue(manager.canRedo());
    }

    @Test
    void failingRedoKeepsCommandOnRedoStack() {
        int[] execCalls = {0};
        Command flaky = new Command() {
            @Override public void execute() {
                if (execCalls[0]++ == 1) throw new IllegalStateException("boom");
            }
            @Override public void undo() { }
        };
        manager.executeCommand(flaky);
        manager.undo();
        assertThrows(IllegalStateException.class, manager::redo);
        assertTrue(manager.canRedo());
        manager.redo();
        assertTrue(manager.canUndo());
        assertFalse(manager.canRedo());
    }

    // ======================== 5. Tags on generated occurrences ========================

    @Test
    void generatedAndUpcomingOccurrencesCopyTemplateTags() {
        RecurringExpense rent = monthly("Housing");
        rent.addTag("fixed");
        rent.addTag("home");
        manager.addExpense(rent);
        Expense inst = instanceOn(rent, START);
        assertNotNull(inst);
        assertEquals(Set.of("fixed", "home"), inst.getTags());

        List<Expense> upcoming = manager.getUpcomingRecurring(LocalDate.now(), LocalDate.now().plusMonths(2));
        assertFalse(upcoming.isEmpty());
        for (Expense u : upcoming) assertTrue(u.hasTag("fixed") && u.hasTag("home"));
    }

    // ======================== 6. Rename covers overrides ========================

    @Test
    void renameCategoryUpdatesOverridesAndSnapshotRestoresThem() {
        RecurringExpense rent = monthly("Housing");
        manager.addExpense(rent);
        LocalDate d = START.plusMonths(1);
        manager.editOccurrence(instanceOn(rent, d), null, "Old", null);

        Map<Expense, String> snap = manager.snapshotCategories();
        manager.renameCategory("Old", "New");
        assertEquals("New", manager.getOverrides().get(0).getCategory());
        assertEquals("New", instanceOn(rent, d).getCategory());

        manager.restoreCategories(snap);
        assertEquals("Old", manager.getOverrides().get(0).getCategory());
        assertEquals("Old", instanceOn(rent, d).getCategory());
    }

    // ======================== 7. Base currency ========================

    @Test
    void convertRatesToNewBase() {
        Map<String, Double> rates = new LinkedHashMap<>();
        rates.put("USD", 18.0);
        rates.put("EUR", 20.0);
        Map<String, Double> converted = CurrencyManager.convertRates(rates, "ZAR", "USD");
        assertNotNull(converted);
        assertFalse(converted.containsKey("USD"));
        assertEquals(20.0 / 18.0, converted.get("EUR"), 1e-9);
        assertEquals(1.0 / 18.0, converted.get("ZAR"), 1e-9);
        assertNull(CurrencyManager.convertRates(rates, "ZAR", "GBP"));
    }

    @Test
    void changeBaseCurrencyConvertsOrClears() {
        CurrencyManager cm = new CurrencyManager();
        cm.setBaseCurrency("ZAR");
        cm.setExchangeRates(Map.of("USD", 18.0, "EUR", 20.0));
        double eurBefore = cm.toBase(100, "EUR") / cm.toBase(1, "USD"); // EUR 100 in USD

        assertTrue(cm.changeBaseCurrency("USD"));
        assertEquals("USD", cm.getBaseCurrency());
        assertEquals(eurBefore, cm.toBase(100, "EUR"), 1e-9);
        assertEquals(100.0 / 18.0, cm.toBase(100, "ZAR"), 1e-9);

        assertFalse(cm.changeBaseCurrency("JPY"));
        assertEquals("JPY", cm.getBaseCurrency());
        assertTrue(cm.getExchangeRates().isEmpty());
        assertFalse(cm.hasRate("USD"));
        assertEquals(Set.of("USD"), cm.missingRates(List.of(withCurrency("USD"), withCurrency(null))));
    }

    private static Expense withCurrency(String code) {
        Expense e = new Expense(1.0, "X", LocalDate.of(2025, 1, 1), "");
        e.setCurrency(code);
        return e;
    }

    @Test
    void stampMissingCurrencyKeepsMeaningOfBaseRecords() {
        RecurringExpense rent = monthly("Housing");
        Expense plain = new Expense(10.0, "Food", LocalDate.of(2025, 1, 1), "");
        Expense usd = withCurrency("USD");
        manager.addExpense(rent);
        manager.addExpense(plain);
        manager.addExpense(usd);

        List<Expense> stamped = manager.stampMissingCurrency("ZAR");
        assertEquals("ZAR", plain.getCurrency());
        assertEquals("ZAR", rent.getCurrency());
        assertEquals("USD", usd.getCurrency());
        assertFalse(stamped.contains(usd));
        // generated occurrences are covered too (and regenerate with the template's currency)
        assertEquals("ZAR", instanceOn(rent, START).getCurrency());
        manager.generateRecurringExpenses(LocalDate.now());
        assertEquals("ZAR", instanceOn(rent, START).getCurrency());
    }

    @Test
    void loadExchangeRatesReconcilesStaleBase() throws IOException {
        // Rates were saved relative to ZAR, but the configured base is now USD.
        storage.saveExchangeRates("ZAR", new LinkedHashMap<>(Map.of("USD", 18.0, "EUR", 20.0)));
        storage.saveBaseCurrency("USD");
        assertEquals("ZAR", storage.loadExchangeRatesBase());
        Map<String, Double> rates = storage.loadExchangeRates();
        assertEquals(20.0 / 18.0, rates.get("EUR"), 1e-9);
        assertEquals(1.0 / 18.0, rates.get("ZAR"), 1e-9);
        assertFalse(rates.containsKey("USD"));
    }

    @Test
    void loadExchangeRatesClearsWhenUnconvertible() throws IOException {
        storage.saveExchangeRates("ZAR", new LinkedHashMap<>(Map.of("USD", 18.0)));
        storage.saveBaseCurrency("GBP");
        assertTrue(storage.loadExchangeRates().isEmpty());
        assertFalse(storage.drainParseWarnings().isEmpty());
    }

    // ======================== 8. Corruption backups & save blocking ========================

    @Test
    void severeCorruptionMakesTimestampedCopy() throws IOException {
        Files.writeString(tempDir.resolve("expenses.txt"),
            "10.0,Food,2025-01-01,ok,REGULAR,\ngarbage\nmore garbage\n", StandardCharsets.UTF_8);
        storage.loadExpenses();
        assertTrue(storage.getLastExpenseLoadStats().isSevere());
        String copy = storage.getLastCorruptCopyPath();
        assertNotNull(copy);
        assertTrue(new File(copy).getName().startsWith("expenses.corrupt-"));
        assertEquals(Files.readString(tempDir.resolve("expenses.txt")), Files.readString(Path.of(copy)));

        // Many saves (rotation keeps 5) never remove the corrupt copy
        for (int i = 0; i < 8; i++) {
            storage.saveExpenses(List.of(new Expense(i + 1, "Food", LocalDate.now(), "s" + i)));
        }
        assertTrue(new File(copy).exists());
    }

    @Test
    void cleanLoadMakesNoCorruptCopy() throws IOException {
        storage.saveExpenses(List.of(new Expense(1.0, "Food", LocalDate.now(), "")));
        storage.loadExpenses();
        assertNull(storage.getLastCorruptCopyPath());
    }

    @Test
    void unreadableFileBlocksSavesUntilQuarantined() throws IOException {
        // A directory where the file should be: exists() is true but reading throws.
        Files.createDirectory(tempDir.resolve("expenses.txt"));
        assertThrows(IOException.class, storage::loadExpenses);
        assertTrue(storage.isExpenseSaveBlocked());
        IOException ex = assertThrows(IOException.class,
            () -> storage.saveExpenses(List.of(new Expense(1.0, "Food", LocalDate.now(), ""))));
        assertTrue(ex.getMessage().contains("could not be read"));

        String moved = storage.quarantineUnreadableExpenses();
        assertNotNull(moved);
        assertTrue(new File(moved).getName().startsWith("expenses.unreadable-"));
        assertTrue(new File(moved).exists());
        assertFalse(storage.isExpenseSaveBlocked());
        storage.saveExpenses(List.of(new Expense(1.0, "Food", LocalDate.now(), "")));
        assertEquals(1, storage.loadExpenses().size());
    }

    // ======================== Atomic bulk add ========================

    @Test
    void addExpensesIsAtomic() {
        Expense good = new Expense(10.0, "Food", LocalDate.of(2025, 1, 1), "ok");
        Expense bad = new Expense(-5.0, "Food", LocalDate.of(2025, 1, 2), "bad");
        assertThrows(IllegalArgumentException.class, () -> manager.addExpenses(List.of(good, bad)));
        assertTrue(manager.getExpenses().isEmpty());

        assertThrows(IllegalArgumentException.class,
            () -> manager.executeCommand(new BulkAddExpenseCommand(manager, List.of(good, bad))));
        assertTrue(manager.getExpenses().isEmpty());
        assertFalse(manager.canUndo());

        RecurringExpense rent = monthly("Housing");
        manager.executeCommand(new BulkAddExpenseCommand(manager, List.of(good, rent)));
        assertTrue(manager.getExpenses().contains(good));
        assertTrue(manager.getBaseRecurringExpenses().contains(rent));
        assertNotNull(instanceOn(rent, START));
        manager.undo();
        assertTrue(manager.getExpenses().isEmpty());
        assertTrue(manager.getBaseRecurringExpenses().isEmpty());
    }
}
