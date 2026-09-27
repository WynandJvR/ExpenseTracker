package com.wyn.expensetracker;

import java.util.ArrayList;
import java.util.List;

public class DeleteExpenseCommand implements Command {
    private final ExpenseManager manager;
    private final Expense expense;
    // If a recurring template is deleted through this command, its overrides get pruned.
    private List<OccurrenceOverride> overridesSnapshot = new ArrayList<>();
    // Deleting a generated occurrence is recorded as a "skip" override (removing the
    // transient instance alone would be undone by the next regeneration). Remember any
    // override that was on that date before, so undo puts it back exactly.
    private OccurrenceOverride priorOccurrenceOverride;

    public DeleteExpenseCommand(ExpenseManager manager, Expense expense) {
        this.manager = manager;
        this.expense = expense;
    }

    /** True if this command deletes a generated recurring occurrence (persisted as an override). */
    public boolean isOccurrenceDelete() {
        return !(expense instanceof RecurringExpense) && expense.getRecurringId() != null
            && expense.getSourceRecurringExpense() != null;
    }

    @Override
    public void execute() {
        if (isOccurrenceDelete()) {
            OccurrenceOverride prior = manager.getOverrideFor(expense);
            priorOccurrenceOverride = prior != null ? copyOf(prior) : null;
            manager.skipOccurrence(expense);
            return;
        }
        if (expense instanceof RecurringExpense rec) {
            overridesSnapshot = manager.getOverridesFor(rec.getId());
        }
        manager.removeExpense(expense);
    }

    @Override
    public void undo() {
        if (isOccurrenceDelete()) {
            manager.resetOccurrence(expense);
            if (priorOccurrenceOverride != null) {
                manager.restoreOverrides(List.of(priorOccurrenceOverride));
            }
            return;
        }
        // Unchecked: the expense may have been loaded from disk and predate today's
        // validation rules (e.g. a >60-char category); undo must never reject it.
        manager.addExpenseUnchecked(expense);
        manager.restoreOverrides(overridesSnapshot);
    }

    private static OccurrenceOverride copyOf(OccurrenceOverride o) {
        OccurrenceOverride c = new OccurrenceOverride(o.getTemplateId(), o.getDate());
        c.setSkipped(o.isSkipped());
        c.setAmount(o.getAmount());
        c.setCategory(o.getCategory());
        c.setDescription(o.getDescription());
        return c;
    }
}
