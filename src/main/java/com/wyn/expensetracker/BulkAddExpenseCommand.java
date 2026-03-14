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
        for (Expense expense : expenses) {
            manager.addExpense(expense);
        }
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
