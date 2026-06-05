package com.wyn.expensetracker;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Stack;

public class ExpenseManager {
    private final List<Expense> expenses;
    private final List<RecurringExpense> baseRecurringExpenses;
    private final Set<String> generatedRecurringIds;
    // Per-occurrence overrides keyed by OccurrenceOverride.key(templateId, date).
    private final Map<String, OccurrenceOverride> occurrenceOverrides;
    private final Stack<Command> undoStack;
    private final Stack<Command> redoStack;

    public ExpenseManager() {
        expenses = new ArrayList<>();
        baseRecurringExpenses = new ArrayList<>();
        generatedRecurringIds = new HashSet<>();
        occurrenceOverrides = new HashMap<>();
        undoStack = new Stack<>();
        redoStack = new Stack<>();
    }

    public void executeCommand(Command command) {
        command.execute();
        undoStack.push(command);
        redoStack.clear();
    }

    public void undo() {
        if (!undoStack.isEmpty()) {
            Command command = undoStack.pop();
            command.undo();
            redoStack.push(command);
        }
    }

    /**
     * Undo the last command without enabling redo. Use after error recovery
     * (e.g. save-to-disk failed) where re-applying the command would re-introduce
     * the failure or, worse, succeed silently in memory after the user has been
     * told the operation failed.
     */
    public void rollbackLastCommand() {
        if (!undoStack.isEmpty()) {
            Command command = undoStack.pop();
            command.undo();
        }
    }

    public void redo() {
        if (!redoStack.isEmpty()) {
            Command command = redoStack.pop();
            command.execute();
            undoStack.push(command);
        }
    }

    public boolean canUndo() {
        return !undoStack.isEmpty();
    }

    public boolean canRedo() {
        return !redoStack.isEmpty();
    }

    // Sane bounds for user-entered data. Loading from disk bypasses these so existing
    // records are never rejected; they guard only fresh input from the UI.
    static final LocalDate MIN_DATE = LocalDate.of(1900, 1, 1);
    static final LocalDate MAX_DATE = LocalDate.of(2100, 12, 31);
    public static final int MAX_CATEGORY_LENGTH = 60;
    public static final int MAX_DESCRIPTION_LENGTH = 500;

    /**
     * Validates user-entered expense data. Throws IllegalArgumentException with a
     * user-facing message on the first problem found. Centralised so every entry
     * point (add form, inline table edit, recurring editor) enforces the same rules.
     */
    public static void validateExpense(Expense e) {
        if (e == null) {
            throw new IllegalArgumentException("Expense cannot be null");
        }
        if (e.getAmount() <= 0) {
            throw new IllegalArgumentException("Expense amount must be positive");
        }
        if (e.getCategory() == null || e.getCategory().trim().isEmpty()) {
            throw new IllegalArgumentException("Category cannot be empty");
        }
        if (e.getCategory().length() > MAX_CATEGORY_LENGTH) {
            throw new IllegalArgumentException("Category name is too long (max " + MAX_CATEGORY_LENGTH + " characters)");
        }
        if (e.getDate() == null) {
            throw new IllegalArgumentException("Date is required");
        }
        if (e.getDate().isBefore(MIN_DATE) || e.getDate().isAfter(MAX_DATE)) {
            throw new IllegalArgumentException("Date must be between " + MIN_DATE + " and " + MAX_DATE);
        }
        if (e.getDescription() != null && e.getDescription().length() > MAX_DESCRIPTION_LENGTH) {
            throw new IllegalArgumentException("Description is too long (max " + MAX_DESCRIPTION_LENGTH + " characters)");
        }
        if (e instanceof RecurringExpense rec) {
            if (rec.getFrequency() == null) {
                throw new IllegalArgumentException("Recurrence frequency is required");
            }
            if (rec.getEndDate() != null) {
                if (rec.getEndDate().isBefore(e.getDate())) {
                    throw new IllegalArgumentException("End date cannot be before the start date");
                }
                if (rec.getEndDate().isAfter(MAX_DATE)) {
                    throw new IllegalArgumentException("End date must be on or before " + MAX_DATE);
                }
            }
        }
    }

    public void addExpense(Expense expense) {
        validateExpense(expense);
        if (expense instanceof RecurringExpense) {
            baseRecurringExpenses.add((RecurringExpense) expense);
            regenerateExpenses();
        } else {
            expenses.add(expense);
        }
    }

    public void replaceExpense(Expense oldExpense, Expense newExpense) {
        if (oldExpense == null || newExpense == null) {
            throw new IllegalArgumentException("Expenses cannot be null");
        }
        validateExpense(newExpense);
        // Editing a generated recurring instance would be wiped on the next
        // regenerateExpenses() pass — route the user to the Recurring tab instead.
        if (oldExpense.getRecurringId() != null) {
            throw new IllegalArgumentException(
                "Cannot edit a generated recurring instance — edit the base recurring expense instead");
        }
        if (oldExpense instanceof RecurringExpense oldRec) {
            if (!(newExpense instanceof RecurringExpense newRec)) {
                throw new IllegalArgumentException(
                    "Replacement for a RecurringExpense must also be a RecurringExpense");
            }
            updateRecurringExpense(oldRec, newRec);
            return;
        }
        int index = expenses.indexOf(oldExpense);
        if (index != -1) {
            expenses.set(index, newExpense);
        }
    }

    public void removeExpense(Expense expense) {
        if (expense instanceof RecurringExpense) {
            baseRecurringExpenses.remove(expense);
            regenerateExpenses();
        } else {
            expenses.remove(expense);
        }
    }

    public void updateRecurringExpense(RecurringExpense oldExpense, RecurringExpense newExpense) {
        validateExpense(newExpense);
        int index = baseRecurringExpenses.indexOf(oldExpense);
        if (index != -1) {
            // Preserve the series identity so per-occurrence overrides stay attached.
            newExpense.setId(oldExpense.getId());
            baseRecurringExpenses.set(index, newExpense);
            regenerateExpenses();
        }
    }

    public void deleteRecurringExpense(RecurringExpense expense) {
        baseRecurringExpenses.remove(expense);
        regenerateExpenses();
    }

    public List<Expense> getExpenses() {
        return expenses;
    }

    public List<RecurringExpense> getBaseRecurringExpenses() {
        return baseRecurringExpenses;
    }

    /**
     * Reassigns every expense and recurring template using {@code oldCategory} to {@code newCategory}.
     * Covers live expenses (including generated recurring instances) and the base recurring templates
     * that get persisted. Returns the number of expenses updated.
     */
    public int renameCategory(String oldCategory, String newCategory) {
        if (oldCategory == null || newCategory == null) return 0;
        int updated = 0;
        for (Expense e : expenses) {
            if (oldCategory.equals(e.getCategory())) {
                e.setCategory(newCategory);
                updated++;
            }
        }
        for (RecurringExpense r : baseRecurringExpenses) {
            if (oldCategory.equals(r.getCategory())) {
                r.setCategory(newCategory);
            }
        }
        return updated;
    }

    /** Snapshots every expense/template's current category (by object identity), for rollback. */
    public Map<Expense, String> snapshotCategories() {
        Map<Expense, String> snapshot = new IdentityHashMap<>();
        for (Expense e : expenses) snapshot.put(e, e.getCategory());
        for (RecurringExpense r : baseRecurringExpenses) snapshot.put(r, r.getCategory());
        return snapshot;
    }

    /** Restores category assignments captured by {@link #snapshotCategories()}. */
    public void restoreCategories(Map<Expense, String> snapshot) {
        for (Map.Entry<Expense, String> entry : snapshot.entrySet()) {
            entry.getKey().setCategory(entry.getValue());
        }
    }

    public List<Expense> getExpensesForSave() {
        List<Expense> result = new ArrayList<>();
        result.addAll(baseRecurringExpenses);
        for (Expense e : expenses) {
            if (e.getRecurringId() == null) {
                result.add(e);
            }
        }
        return result;
    }

    public void generateRecurringExpenses(LocalDate upToDate) {
        expenses.removeIf(e -> e.getRecurringId() != null);
        generatedRecurringIds.clear();

        List<Expense> generatedExpenses = new ArrayList<>();
        for (RecurringExpense recurringExpense : baseRecurringExpenses) {
            LocalDate currentDate = recurringExpense.getDate();
            LocalDate endDate = recurringExpense.getEndDate() != null ? recurringExpense.getEndDate() : upToDate;

            while (!currentDate.isAfter(endDate) && !currentDate.isAfter(upToDate)) {
                String recurringId = generateRecurringId(recurringExpense, currentDate);
                OccurrenceOverride override = occurrenceOverrides.get(recurringId);

                // A skipped occurrence produces no instance for that date.
                if ((override == null || !override.isSkipped())
                        && !generatedRecurringIds.contains(recurringId)) {
                    // Null override fields inherit the template's current value, so an
                    // occurrence that only changes (say) amount still tracks later edits
                    // to the series description.
                    double amount = override != null && override.getAmount() != null
                        ? override.getAmount() : recurringExpense.getAmount();
                    String category = override != null && override.getCategory() != null
                        ? override.getCategory() : recurringExpense.getCategory();
                    String description = override != null && override.getDescription() != null
                        ? override.getDescription() : recurringExpense.getDescription();

                    Expense generated = new Expense(
                        amount,
                        category,
                        currentDate,
                        description,
                        recurringId,
                        recurringExpense
                    );
                    if (recurringExpense.isIncome()) generated.setIncome(true);
                    if (recurringExpense.isRefund()) generated.setRefund(true);
                    if (recurringExpense.isExcluded()) generated.setExcluded(true);
                    if (recurringExpense.getCurrency() != null) generated.setCurrency(recurringExpense.getCurrency());
                    generatedExpenses.add(generated);
                    generatedRecurringIds.add(recurringId);
                }

                currentDate = getNextRecurringDate(recurringExpense, currentDate);
            }
        }

        expenses.addAll(generatedExpenses);
    }

    // ======================== Per-occurrence overrides ========================

    /** Replaces all overrides (used when loading from disk). Empty overrides are dropped. */
    public void setOverrides(Collection<OccurrenceOverride> overrides) {
        occurrenceOverrides.clear();
        if (overrides != null) {
            for (OccurrenceOverride o : overrides) {
                if (o != null && !o.isEmpty()) occurrenceOverrides.put(o.key(), o);
            }
        }
    }

    /** Live overrides, for persistence. */
    public List<OccurrenceOverride> getOverrides() {
        return new ArrayList<>(occurrenceOverrides.values());
    }

    private RecurringExpense sourceTemplateOf(Expense instance) {
        if (instance == null || instance.getRecurringId() == null) return null;
        return instance.getSourceRecurringExpense();
    }

    /** The override currently applied to a generated instance, or null. */
    public OccurrenceOverride getOverrideFor(Expense instance) {
        RecurringExpense src = sourceTemplateOf(instance);
        if (src == null) return null;
        return occurrenceOverrides.get(OccurrenceOverride.key(src.getId(), instance.getDate()));
    }

    public boolean hasOverride(Expense instance) {
        return getOverrideFor(instance) != null;
    }

    /** Hides a single generated occurrence without touching the rest of the series. */
    public void skipOccurrence(Expense instance) {
        RecurringExpense src = sourceTemplateOf(instance);
        if (src == null) {
            throw new IllegalArgumentException("Not a generated recurring occurrence");
        }
        OccurrenceOverride o = new OccurrenceOverride(src.getId(), instance.getDate());
        o.setSkipped(true);
        occurrenceOverrides.put(o.key(), o);
        regenerateExpenses();
    }

    /**
     * Overrides amount/category/description for a single occurrence. A null argument
     * (or a value equal to the template's) inherits the series value. If the result
     * carries no change, any existing override for that date is cleared.
     */
    public void editOccurrence(Expense instance, Double amount, String category, String description) {
        RecurringExpense src = sourceTemplateOf(instance);
        if (src == null) {
            throw new IllegalArgumentException("Not a generated recurring occurrence");
        }
        if (amount != null && amount <= 0) {
            throw new IllegalArgumentException("Amount must be positive");
        }
        if (category != null && category.trim().isEmpty()) {
            throw new IllegalArgumentException("Category cannot be empty");
        }
        if (category != null && category.length() > MAX_CATEGORY_LENGTH) {
            throw new IllegalArgumentException("Category name is too long (max " + MAX_CATEGORY_LENGTH + " characters)");
        }
        if (description != null && description.length() > MAX_DESCRIPTION_LENGTH) {
            throw new IllegalArgumentException("Description is too long (max " + MAX_DESCRIPTION_LENGTH + " characters)");
        }
        OccurrenceOverride o = new OccurrenceOverride(src.getId(), instance.getDate());
        o.setAmount(amount != null && amount != src.getAmount() ? amount : null);
        o.setCategory(category != null && !category.equals(src.getCategory()) ? category : null);
        o.setDescription(description != null && !description.equals(src.getDescription()) ? description : null);
        if (o.isEmpty()) {
            occurrenceOverrides.remove(o.key());
        } else {
            occurrenceOverrides.put(o.key(), o);
        }
        regenerateExpenses();
    }

    /** Removes any override for a generated occurrence, restoring it to the series default. */
    public void resetOccurrence(Expense instance) {
        RecurringExpense src = sourceTemplateOf(instance);
        if (src == null) return;
        occurrenceOverrides.remove(OccurrenceOverride.key(src.getId(), instance.getDate()));
        regenerateExpenses();
    }

    /**
     * Future recurring occurrences falling within [from, to] (inclusive), with skip/edit
     * overrides applied. Unlike {@link #generateRecurringExpenses}, this projects past
     * today without mutating the ledger — used to surface upcoming bills. Results are
     * sorted by date.
     */
    public List<Expense> getUpcomingRecurring(LocalDate from, LocalDate to) {
        List<Expense> result = new ArrayList<>();
        if (from == null || to == null || from.isAfter(to)) return result;
        for (RecurringExpense rec : baseRecurringExpenses) {
            LocalDate end = rec.getEndDate() != null && rec.getEndDate().isBefore(to)
                ? rec.getEndDate() : to;
            LocalDate cur = rec.getDate();
            // Skip forward to the first occurrence on/after the window start.
            while (cur.isBefore(from) && !cur.isAfter(end)) {
                cur = getNextRecurringDate(rec, cur);
            }
            while (!cur.isAfter(end)) {
                String id = generateRecurringId(rec, cur);
                OccurrenceOverride ov = occurrenceOverrides.get(id);
                if (ov == null || !ov.isSkipped()) {
                    double amount = ov != null && ov.getAmount() != null ? ov.getAmount() : rec.getAmount();
                    String category = ov != null && ov.getCategory() != null ? ov.getCategory() : rec.getCategory();
                    String description = ov != null && ov.getDescription() != null ? ov.getDescription() : rec.getDescription();
                    Expense e = new Expense(amount, category, cur, description, id, rec);
                    if (rec.isIncome()) e.setIncome(true);
                    if (rec.isRefund()) e.setRefund(true);
                    if (rec.isExcluded()) e.setExcluded(true);
                    if (rec.getCurrency() != null) e.setCurrency(rec.getCurrency());
                    result.add(e);
                }
                cur = getNextRecurringDate(rec, cur);
            }
        }
        result.sort(java.util.Comparator.comparing(Expense::getDate));
        return result;
    }

    public double getTotalByCategory(String category) {
        if (category == null) {
            return 0.0;
        }
        return expenses.stream()
            .filter(e -> e.getCategory().equalsIgnoreCase(category))
            .mapToDouble(Expense::getAmount)
            .sum();
    }

    private void regenerateExpenses() {
        pruneOrphanOverrides();
        expenses.removeIf(e -> e.getRecurringId() != null);
        generatedRecurringIds.clear();
        generateRecurringExpenses(LocalDate.now());
    }

    /** Drops overrides whose owning template no longer exists (e.g. series deleted). */
    private void pruneOrphanOverrides() {
        if (occurrenceOverrides.isEmpty()) return;
        Set<String> liveIds = new HashSet<>();
        for (RecurringExpense r : baseRecurringExpenses) liveIds.add(r.getId());
        occurrenceOverrides.values().removeIf(o -> !liveIds.contains(o.getTemplateId()));
    }

    private LocalDate getNextRecurringDate(RecurringExpense expense, LocalDate fromDate) {
        int originalDay = expense.getDate().getDayOfMonth();
        return switch (expense.getFrequency()) {
            case DAILY -> fromDate.plusDays(1);
            case WEEKLY -> fromDate.plusWeeks(1);
            case BIWEEKLY -> fromDate.plusWeeks(2);
            case MONTHLY -> adjustDay(fromDate.plusMonths(1), originalDay);
            case QUARTERLY -> adjustDay(fromDate.plusMonths(3), originalDay);
            case YEARLY -> adjustDay(fromDate.plusYears(1), originalDay);
        };
    }

    /** Preserve the original day-of-month, clamping to month length for short months. */
    private static LocalDate adjustDay(LocalDate date, int targetDay) {
        if (targetDay > date.lengthOfMonth()) {
            return date.withDayOfMonth(date.lengthOfMonth());
        }
        return date.withDayOfMonth(targetDay);
    }

    private String generateRecurringId(RecurringExpense expense, LocalDate date) {
        // Stable per-occurrence id: template identity + occurrence date. Stable across
        // edits to the template's fields, which is what lets overrides survive them.
        return OccurrenceOverride.key(expense.getId(), date);
    }

    public void loadExpenses(List<Expense> loadedExpenses) {
        if (loadedExpenses == null) {
            return;
        }
        // Partition into temp lists first so a parsing failure mid-iteration
        // doesn't leave the manager with cleared but unrepopulated state.
        List<Expense> newExpenses = new ArrayList<>();
        List<RecurringExpense> newRecurring = new ArrayList<>();
        for (Expense expense : loadedExpenses) {
            if (expense instanceof RecurringExpense recurring) {
                newRecurring.add(recurring);
            } else {
                newExpenses.add(expense);
            }
        }
        expenses.clear();
        baseRecurringExpenses.clear();
        expenses.addAll(newExpenses);
        baseRecurringExpenses.addAll(newRecurring);
        regenerateExpenses();
    }
}