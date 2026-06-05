package com.wyn.expensetracker;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers single-occurrence recurring overrides (skip / edit / reset), their
 * persistence, stable template ids, upcoming-bill projection, and the shared
 * expense validation rules.
 */
class RecurringOverrideTest {

    @TempDir
    Path tempDir;

    private RecurringExpense monthly(double amount, String desc, LocalDate start, LocalDate end) {
        return new RecurringExpense(amount, "Bills", start, desc, RecurrenceType.MONTHLY, end);
    }

    private Expense occurrenceOn(ExpenseManager m, LocalDate date) {
        return m.getExpenses().stream()
            .filter(e -> e.getRecurringId() != null && e.getDate().equals(date))
            .findFirst().orElse(null);
    }

    // ======================== SKIP ========================

    @Test
    void skipOccurrence_removesOnlyThatInstance() {
        ExpenseManager m = new ExpenseManager();
        m.loadExpenses(List.of(monthly(100.0, "Rent",
            LocalDate.of(2025, 1, 1), LocalDate.of(2025, 4, 30))));

        Expense feb = occurrenceOn(m, LocalDate.of(2025, 2, 1));
        assertNotNull(feb);
        m.skipOccurrence(feb);

        assertNull(occurrenceOn(m, LocalDate.of(2025, 2, 1)), "February occurrence should be skipped");
        assertNotNull(occurrenceOn(m, LocalDate.of(2025, 1, 1)), "January should remain");
        assertNotNull(occurrenceOn(m, LocalDate.of(2025, 3, 1)), "March should remain");
    }

    // ======================== EDIT ========================

    @Test
    void editOccurrence_overridesAmountForThatDateOnly() {
        ExpenseManager m = new ExpenseManager();
        m.loadExpenses(List.of(monthly(100.0, "Rent",
            LocalDate.of(2025, 1, 1), LocalDate.of(2025, 3, 31))));

        Expense feb = occurrenceOn(m, LocalDate.of(2025, 2, 1));
        m.editOccurrence(feb, 150.0, null, null);

        assertEquals(150.0, occurrenceOn(m, LocalDate.of(2025, 2, 1)).getAmount(), 0.001);
        assertEquals(100.0, occurrenceOn(m, LocalDate.of(2025, 1, 1)).getAmount(), 0.001);
        assertEquals(100.0, occurrenceOn(m, LocalDate.of(2025, 3, 1)).getAmount(), 0.001);
    }

    @Test
    void editOccurrence_rejectsNonPositiveAmount() {
        ExpenseManager m = new ExpenseManager();
        m.loadExpenses(List.of(monthly(100.0, "Rent",
            LocalDate.of(2025, 1, 1), LocalDate.of(2025, 3, 31))));
        Expense feb = occurrenceOn(m, LocalDate.of(2025, 2, 1));
        assertThrows(IllegalArgumentException.class, () -> m.editOccurrence(feb, -5.0, null, null));
    }

    @Test
    void resetOccurrence_restoresSeriesValue() {
        ExpenseManager m = new ExpenseManager();
        m.loadExpenses(List.of(monthly(100.0, "Rent",
            LocalDate.of(2025, 1, 1), LocalDate.of(2025, 3, 31))));

        Expense feb = occurrenceOn(m, LocalDate.of(2025, 2, 1));
        m.editOccurrence(feb, 150.0, null, null);
        assertTrue(m.hasOverride(occurrenceOn(m, LocalDate.of(2025, 2, 1))));

        m.resetOccurrence(occurrenceOn(m, LocalDate.of(2025, 2, 1)));
        assertFalse(m.hasOverride(occurrenceOn(m, LocalDate.of(2025, 2, 1))));
        assertEquals(100.0, occurrenceOn(m, LocalDate.of(2025, 2, 1)).getAmount(), 0.001);
    }

    // ======================== SURVIVES TEMPLATE EDIT ========================

    @Test
    void override_survivesSeriesEdit_viaStableId() {
        ExpenseManager m = new ExpenseManager();
        RecurringExpense template = monthly(100.0, "Rent",
            LocalDate.of(2025, 1, 1), LocalDate.of(2025, 4, 30));
        m.loadExpenses(List.of(template));

        m.skipOccurrence(occurrenceOn(m, LocalDate.of(2025, 2, 1)));
        assertNull(occurrenceOn(m, LocalDate.of(2025, 2, 1)));

        // Editing the series (new description) keeps the same id, so the skip persists.
        RecurringExpense edited = monthly(120.0, "Rent (increased)",
            LocalDate.of(2025, 1, 1), LocalDate.of(2025, 4, 30));
        m.updateRecurringExpense(template, edited);

        assertNull(occurrenceOn(m, LocalDate.of(2025, 2, 1)),
            "Skip should still apply after the series was edited");
        assertEquals(120.0, occurrenceOn(m, LocalDate.of(2025, 1, 1)).getAmount(), 0.001,
            "Non-skipped occurrences should pick up the new series amount");
    }

    @Test
    void deletingSeries_prunesItsOverrides() {
        ExpenseManager m = new ExpenseManager();
        RecurringExpense template = monthly(100.0, "Rent",
            LocalDate.of(2025, 1, 1), LocalDate.of(2025, 4, 30));
        m.loadExpenses(List.of(template));
        m.skipOccurrence(occurrenceOn(m, LocalDate.of(2025, 2, 1)));
        assertEquals(1, m.getOverrides().size());

        m.deleteRecurringExpense(template);
        assertTrue(m.getOverrides().isEmpty(), "Orphaned overrides should be pruned");
    }

    // ======================== PERSISTENCE ========================

    @Test
    void templateId_roundTripsThroughStorage() throws Exception {
        FileStorage storage = new FileStorage(tempDir.toString());
        RecurringExpense template = monthly(100.0, "Rent",
            LocalDate.of(2025, 1, 1), null);
        String originalId = template.getId();

        storage.saveExpenses(List.of(template));
        List<Expense> loaded = storage.loadExpenses();

        RecurringExpense reloaded = (RecurringExpense) loaded.get(0);
        assertEquals(originalId, reloaded.getId(), "Template id should survive a save/load cycle");
    }

    @Test
    void overrides_roundTripThroughStorage() throws Exception {
        FileStorage storage = new FileStorage(tempDir.toString());

        OccurrenceOverride skip = new OccurrenceOverride("tmpl-1", LocalDate.of(2025, 2, 1));
        skip.setSkipped(true);
        OccurrenceOverride edit = new OccurrenceOverride("tmpl-2", LocalDate.of(2025, 3, 15));
        edit.setAmount(250.0);
        edit.setDescription("Adjusted, with comma");

        storage.saveRecurringOverrides(List.of(skip, edit));
        Map<String, OccurrenceOverride> loaded = storage.loadRecurringOverrides().stream()
            .collect(Collectors.toMap(OccurrenceOverride::key, o -> o));

        OccurrenceOverride loadedSkip = loaded.get(skip.key());
        assertNotNull(loadedSkip);
        assertTrue(loadedSkip.isSkipped());

        OccurrenceOverride loadedEdit = loaded.get(edit.key());
        assertNotNull(loadedEdit);
        assertFalse(loadedEdit.isSkipped());
        assertEquals(250.0, loadedEdit.getAmount(), 0.001);
        assertEquals("Adjusted, with comma", loadedEdit.getDescription());
    }

    @Test
    void legacyRecurringWithoutId_detectedAndPersistedOnResave() throws Exception {
        FileStorage storage = new FileStorage(tempDir.toString());
        // A recurring line written before per-occurrence overrides existed: no ID: flag.
        Files.writeString(tempDir.resolve("expenses.txt"),
            "100.0,Bills,2025-01-01,Rent,RECURRING,MONTHLY,\n");

        List<Expense> loaded = storage.loadExpenses();
        assertTrue(storage.hadLegacyRecurringOnLoad(), "Missing ID flag should be flagged for re-save");
        String mintedId = ((RecurringExpense) loaded.get(0)).getId();
        assertNotNull(mintedId);

        // The app re-saves once on the upgrade path; the id must then be stable.
        storage.saveExpenses(loaded);
        List<Expense> reloaded = storage.loadExpenses();
        assertFalse(storage.hadLegacyRecurringOnLoad(), "Id should now be persisted");
        assertEquals(mintedId, ((RecurringExpense) reloaded.get(0)).getId(),
            "Series id must stay stable across launches after the upgrade re-save");
    }

    @Test
    void editOccurrence_rejectsOverlongDescription() {
        ExpenseManager m = new ExpenseManager();
        m.loadExpenses(List.of(monthly(100.0, "Rent",
            LocalDate.of(2025, 1, 1), LocalDate.of(2025, 3, 31))));
        Expense feb = occurrenceOn(m, LocalDate.of(2025, 2, 1));
        String longDesc = "x".repeat(ExpenseManager.MAX_DESCRIPTION_LENGTH + 1);
        assertThrows(IllegalArgumentException.class, () -> m.editOccurrence(feb, null, null, longDesc));
    }

    @Test
    void overrides_appliedFromStorageAtLoad() throws Exception {
        FileStorage storage = new FileStorage(tempDir.toString());
        RecurringExpense template = monthly(100.0, "Rent",
            LocalDate.of(2025, 1, 1), LocalDate.of(2025, 4, 30));
        storage.saveExpenses(List.of(template));

        OccurrenceOverride skip = new OccurrenceOverride(template.getId(), LocalDate.of(2025, 2, 1));
        skip.setSkipped(true);
        storage.saveRecurringOverrides(List.of(skip));

        // Simulate app startup order: overrides set before expenses load.
        ExpenseManager m = new ExpenseManager();
        m.setOverrides(storage.loadRecurringOverrides());
        m.loadExpenses(storage.loadExpenses());

        assertNull(occurrenceOn(m, LocalDate.of(2025, 2, 1)),
            "Persisted skip should apply on load");
    }

    // ======================== UPCOMING PROJECTION ========================

    @Test
    void getUpcomingRecurring_projectsForwardWithinWindow() {
        ExpenseManager m = new ExpenseManager();
        m.loadExpenses(List.of(monthly(100.0, "Rent",
            LocalDate.of(2025, 1, 15), null)));

        List<Expense> upcoming = m.getUpcomingRecurring(
            LocalDate.of(2030, 6, 1), LocalDate.of(2030, 8, 31));

        List<LocalDate> dates = upcoming.stream().map(Expense::getDate).collect(Collectors.toList());
        assertEquals(List.of(
            LocalDate.of(2030, 6, 15),
            LocalDate.of(2030, 7, 15),
            LocalDate.of(2030, 8, 15)), dates);
    }

    @Test
    void getUpcomingRecurring_respectsSkipAndEndDate() {
        ExpenseManager m = new ExpenseManager();
        RecurringExpense template = monthly(100.0, "Rent",
            LocalDate.of(2025, 1, 15), LocalDate.of(2030, 7, 31));
        m.loadExpenses(List.of(template));

        OccurrenceOverride skip = new OccurrenceOverride(template.getId(), LocalDate.of(2030, 6, 15));
        skip.setSkipped(true);
        m.setOverrides(List.of(skip));

        List<LocalDate> dates = m.getUpcomingRecurring(
                LocalDate.of(2030, 6, 1), LocalDate.of(2030, 8, 31))
            .stream().map(Expense::getDate).collect(Collectors.toList());

        // June skipped; August beyond the series end date.
        assertEquals(List.of(LocalDate.of(2030, 7, 15)), dates);
    }

    // ======================== VALIDATION ========================

    @Test
    void validateExpense_rejectsOutOfRangeDates() {
        assertThrows(IllegalArgumentException.class, () ->
            ExpenseManager.validateExpense(new Expense(10, "Food", LocalDate.of(1, 1, 1), "x")));
        assertThrows(IllegalArgumentException.class, () ->
            ExpenseManager.validateExpense(new Expense(10, "Food", LocalDate.of(9999, 1, 1), "x")));
    }

    @Test
    void validateExpense_rejectsOverlongCategory() {
        String longName = "x".repeat(ExpenseManager.MAX_CATEGORY_LENGTH + 1);
        assertThrows(IllegalArgumentException.class, () ->
            ExpenseManager.validateExpense(new Expense(10, longName, LocalDate.of(2025, 1, 1), "x")));
    }

    @Test
    void validateExpense_rejectsEndDateBeforeStart() {
        RecurringExpense bad = new RecurringExpense(10, "Food",
            LocalDate.of(2025, 6, 1), "x", RecurrenceType.MONTHLY, LocalDate.of(2025, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> ExpenseManager.validateExpense(bad));
    }

    @Test
    void validateExpense_acceptsValidExpense() {
        assertDoesNotThrow(() ->
            ExpenseManager.validateExpense(new Expense(10, "Food", LocalDate.of(2025, 1, 1), "Lunch")));
    }
}
