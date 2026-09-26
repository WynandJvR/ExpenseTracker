package com.wyn.expensetracker;

import java.util.ArrayList;
import java.util.List;

public class DeleteExpenseCommand implements Command {
    private final ExpenseManager manager;
    private final Expense expense;
    // If a recurring template is deleted through this command, its overrides get pruned.
    private List<OccurrenceOverride> overridesSnapshot = new ArrayList<>();

    public DeleteExpenseCommand(ExpenseManager manager, Expense expense) {
        this.manager = manager;
        this.expense = expense;
    }

    @Override
    public void execute() {
        if (expense instanceof RecurringExpense rec) {
            overridesSnapshot = manager.getOverridesFor(rec.getId());
        }
        manager.removeExpense(expense);
    }

    @Override
    public void undo() {
        // Unchecked: the expense may have been loaded from disk and predate today's
        // validation rules (e.g. a >60-char category); undo must never reject it.
        manager.addExpenseUnchecked(expense);
        manager.restoreOverrides(overridesSnapshot);
    }
}
