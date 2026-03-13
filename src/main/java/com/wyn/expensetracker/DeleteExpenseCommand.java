package com.wyn.expensetracker;

public class DeleteExpenseCommand implements Command {
    private ExpenseManager manager;
    private Expense expense;

    public DeleteExpenseCommand(ExpenseManager manager, Expense expense) {
        this.manager = manager;
        this.expense = expense;
    }

    @Override
    public void execute() {
        manager.removeExpense(expense);
    }

    @Override
    public void undo() {
        manager.addExpense(expense);
    }
}