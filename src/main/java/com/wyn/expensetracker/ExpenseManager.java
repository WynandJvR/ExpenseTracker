package com.wyn.expensetracker;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class ExpenseManager {
    private List<Expense> expenses;
    private Set<String> generatedRecurringIds; // Track generated recurring expenses

    public ExpenseManager() {
        expenses = new ArrayList<>();
        generatedRecurringIds = new HashSet<>();
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
        
        for (Expense expense : new ArrayList<>(expenses)) { // Create copy to avoid concurrent modification
            if (expense instanceof RecurringExpense recurringExpense) {
                LocalDate currentDate = getNextRecurringDate(recurringExpense);
                LocalDate endDate = recurringExpense.getEndDate() != null ? recurringExpense.getEndDate() : upToDate;
                
                while (!currentDate.isAfter(endDate) && !currentDate.isAfter(upToDate)) {
                    String recurringId = generateRecurringId(recurringExpense, currentDate);
                    
                    // Only add if we haven't generated this specific recurring expense before
                    if (!generatedRecurringIds.contains(recurringId)) {
                        generatedExpenses.add(new Expense(
                            recurringExpense.getAmount(),
                            recurringExpense.getCategory(),
                            currentDate,
                            recurringExpense.getDescription()
                        ));
                        generatedRecurringIds.add(recurringId);
                    }
                    
                    currentDate = getNextRecurringDate(recurringExpense, currentDate);
                }
            }
        }
        
        expenses.addAll(generatedExpenses);
    }

    private LocalDate getNextRecurringDate(RecurringExpense expense) {
        return getNextRecurringDate(expense, expense.getDate());
    }

    private LocalDate getNextRecurringDate(RecurringExpense expense, LocalDate fromDate) {
        return switch (expense.getFrequency()) {
            case DAILY -> fromDate.plusDays(1);
            case WEEKLY -> fromDate.plusWeeks(1);
            case MONTHLY -> fromDate.plusMonths(1);
            case YEARLY -> fromDate.plusYears(1);
        };
    }

    private String generateRecurringId(RecurringExpense expense, LocalDate date) {
        return expense.getAmount() + "|" + expense.getCategory() + "|" + 
               expense.getDate() + "|" + expense.getFrequency() + "|" + date;
    }

    // Clear generated recurring IDs when loading expenses (to allow fresh generation)
    public void clearGeneratedRecurringIds() {
        generatedRecurringIds.clear();
    }
}