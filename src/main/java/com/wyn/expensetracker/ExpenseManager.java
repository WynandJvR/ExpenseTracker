package com.wyn.expensetracker;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.io.IOException;

public class ExpenseManager {
    private List<Expense> expenses;
    private final ExcelStorage storage;

    public ExpenseManager() {
        expenses = new ArrayList<>();
        storage = new ExcelStorage();
    }

    public void addExpense(Expense expense) {
        if (expense == null) {
            throw new IllegalArgumentException("Expense cannot be null");
        }
        expenses.add(expense);
        try {
            storage.saveExpenses(expenses);
        } catch (IOException e) {
            expenses.remove(expense);
            throw new RuntimeException("Failed to save expense: " + e.getMessage());
        }
    }

    public List<Expense> getExpenses() {
        return new ArrayList<>(expenses);
    }

    public double getTotalByCategory(String category) {
        if (category == null) return 0.0;
        return expenses.stream()
            .filter(e -> e.getCategory().equalsIgnoreCase(category))
            .mapToDouble(Expense::getAmount)
            .sum();
    }

    public void generateRecurringExpenses(LocalDate upToDate) {
        if (upToDate == null) return;
        
        List<Expense> generatedExpenses = new ArrayList<>();
        for (Expense expense : new ArrayList<>(expenses)) {
            if (expense instanceof RecurringExpense recurringExpense) {
                LocalDate currentDate = recurringExpense.getDate();
                LocalDate endDate = recurringExpense.getEndDate() != null ? 
                    recurringExpense.getEndDate() : upToDate;
                
                while (!currentDate.isAfter(endDate)) {
                    if (!currentDate.equals(recurringExpense.getDate())) {
                        generatedExpenses.add(new Expense(
                            recurringExpense.getAmount(),
                            recurringExpense.getCategory(),
                            currentDate,
                            recurringExpense.getDescription()
                        ));
                    }
                    currentDate = getNextRecurrenceDate(currentDate, recurringExpense.getFrequency());
                }
            }
        }
        expenses.addAll(generatedExpenses);
    }

    private LocalDate getNextRecurrenceDate(LocalDate currentDate, RecurrenceType frequency) {
        return switch (frequency) {
            case DAILY -> currentDate.plusDays(1);
            case WEEKLY -> currentDate.plusWeeks(1);
            case MONTHLY -> currentDate.plusMonths(1);
            case YEARLY -> currentDate.plusYears(1);
        };
    }
}