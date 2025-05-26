package com.wyn.expensetracker;

public class AddExpenseCommand implements Command {
    private ExpenseManager manager;
    private Expense expense;

    public AddExpenseCommand(ExpenseManager manager, Expense expense) {
        this.manager = manager;
        this.expense = expense;
    }

    @Override
    public void execute() {
        manager.addExpense(expense);
    }

    @Override
    public void undo() {
        manager.getExpenses().remove(expense);
    }
}