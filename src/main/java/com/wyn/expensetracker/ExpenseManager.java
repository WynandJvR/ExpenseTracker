package com.wyn.expensetracker;

import java.time.LocalDate;
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

    // Generate expenses from recurring expenses up to a specified date
    public void generateRecurringExpenses(LocalDate upToDate) {
        List<Expense> generatedExpenses = new ArrayList<>();
        for (Expense expense : expenses) {
            if (expense instanceof RecurringExpense recurringExpense) {
                LocalDate currentDate = recurringExpense.getDate();
                LocalDate endDate = recurringExpense.getEndDate() != null ? recurringExpense.getEndDate() : upToDate;
                while (!currentDate.isAfter(endDate)) {
                    if (!currentDate.equals(recurringExpense.getDate())) { // Skip the original expense date
                        generatedExpenses.add(new Expense(
                            recurringExpense.getAmount(),
                            recurringExpense.getCategory(),
                            currentDate,
                            recurringExpense.getDescription()
                        ));
                    }
                    currentDate = switch (recurringExpense.getFrequency()) {
                        case DAILY -> currentDate.plusDays(1);
                        case WEEKLY -> currentDate.plusWeeks(1);
                        case MONTHLY -> currentDate.plusMonths(1);
                        case YEARLY -> currentDate.plusYears(1);
                    };
                }
            }
        }
        expenses.addAll(generatedExpenses);
    }
}