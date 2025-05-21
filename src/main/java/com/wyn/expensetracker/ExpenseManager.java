package com.wyn.expensetracker;

import java.util.ArrayList;
import java.util.List;

public class ExpenseManager {
    private List<Expense> expenses;

    public ExpenseManager() {
        expenses = new ArrayList<>();
    }

    public void addExpense(Expense expense) {
        if (expense == null) {
            throw new IllegalArgumentException("Expense cannot be null");
        }
        if (expense.getAmount() <= 0) {
            throw new IllegalArgumentException("Expense amount must be positive");
        }
        if (expense.getCategory() == null || expense.getCategory().trim().isEmpty()) {
            throw new IllegalArgumentException("Category cannot be empty");
        }
        expenses.add(expense);
    }

    public List<Expense> getExpenses() {
        return expenses;
    }

    public double getTotalByCategory(String category) {
        if (category == null) {
            return 0.0;
        }
        return expenses.stream()
            .filter(e -> e.getCategory().equalsIgnoreCase(category))
            .mapToDouble(Expense::getAmount)
            .sum();
    }
}