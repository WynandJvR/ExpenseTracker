package com.wyn.expensetracker;

public class DeleteRecurringExpenseCommand implements Command {
    private ExpenseManager manager;
    private RecurringExpense expense;

    public DeleteRecurringExpenseCommand(ExpenseManager manager, RecurringExpense expense) {
        this.manager = manager;
        this.expense = expense;
    }

    @Override
    public void execute() {
        manager.deleteRecurringExpense(expense);
    }

    @Override
    public void undo() {
        manager.addExpense(expense);
    }
}
