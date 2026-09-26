package com.wyn.expensetracker;

import java.util.ArrayList;
import java.util.List;

public class DeleteRecurringExpenseCommand implements Command {
    private final ExpenseManager manager;
    private final RecurringExpense expense;
    // Deleting a series prunes its per-occurrence overrides; keep them so undo brings them back.
    private List<OccurrenceOverride> overridesSnapshot = new ArrayList<>();

    public DeleteRecurringExpenseCommand(ExpenseManager manager, RecurringExpense expense) {
        this.manager = manager;
        this.expense = expense;
    }

    @Override
    public void execute() {
        overridesSnapshot = manager.getOverridesFor(expense.getId());
        manager.deleteRecurringExpense(expense);
    }

    @Override
    public void undo() {
        // Unchecked: the template may be legacy data loaded from disk that predates
        // today's validation rules; undo must never reject it.
        manager.addExpenseUnchecked(expense);
        manager.restoreOverrides(overridesSnapshot);
    }
}
