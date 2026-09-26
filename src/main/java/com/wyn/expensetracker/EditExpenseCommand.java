package com.wyn.expensetracker;

public class EditExpenseCommand implements Command {
    private final ExpenseManager manager;
    private final Expense oldExpense;
    private final Expense newExpense;

    public EditExpenseCommand(ExpenseManager manager, Expense oldExpense, Expense newExpense) {
        this.manager = manager;
        this.oldExpense = oldExpense;
        this.newExpense = newExpense;
    }

    @Override
    public void execute() {
        manager.replaceExpense(oldExpense, newExpense);
    }

    @Override
    public void undo() {
        // Restoring the original record: don't re-validate it (it may be legacy data).
        manager.replaceExpenseUnchecked(newExpense, oldExpense);
    }
}
