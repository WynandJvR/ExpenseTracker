package com.wyn.expensetracker;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class BulkAddExpenseCommand implements Command {
    private final ExpenseManager manager;
    private final List<Expense> expenses;

    public BulkAddExpenseCommand(ExpenseManager manager, List<Expense> expenses) {
        this.manager = manager;
        this.expenses = new ArrayList<>(expenses);
    }

    @Override
    public void execute() {
        // Atomic: validates everything before adding anything, so a bad row
        // leaves no partial import behind.
        manager.addExpenses(expenses);
    }

    @Override
    public void undo() {
        List<Expense> reversed = new ArrayList<>(expenses);
        Collections.reverse(reversed);
        for (Expense expense : reversed) {
            manager.removeExpense(expense);
        }
    }
}
