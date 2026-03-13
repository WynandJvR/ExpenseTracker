package com.wyn.expensetracker;

public class UpdateRecurringExpenseCommand implements Command {
    private ExpenseManager manager;
    private RecurringExpense oldExpense;
    private RecurringExpense newExpense;

    public UpdateRecurringExpenseCommand(ExpenseManager manager, RecurringExpense oldExpense, RecurringExpense newExpense) {
        this.manager = manager;
        this.oldExpense = oldExpense;
        this.newExpense = newExpense;
    }

    @Override
    public void execute() {
        manager.updateRecurringExpense(oldExpense, newExpense);
    }

    @Override
    public void undo() {
        manager.updateRecurringExpense(newExpense, oldExpense);
    }
}
