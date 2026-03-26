package com.wyn.expensetracker;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.Stack;

public class ExpenseManager {
    private List<Expense> expenses;
    private List<RecurringExpense> baseRecurringExpenses;
    private Set<String> generatedRecurringIds;
    private Stack<Command> undoStack;
    private Stack<Command> redoStack;

    public ExpenseManager() {
        expenses = new ArrayList<>();
        baseRecurringExpenses = new ArrayList<>();
        generatedRecurringIds = new HashSet<>();
        undoStack = new Stack<>();
        redoStack = new Stack<>();
    }

    public void executeCommand(Command command) {
        command.execute();
        undoStack.push(command);
        redoStack.clear();
    }

    public void undo() {
        if (!undoStack.isEmpty()) {
            Command command = undoStack.pop();
            command.undo();
            redoStack.push(command);
        }
    }

    public void redo() {
        if (!redoStack.isEmpty()) {
            Command command = redoStack.pop();
            command.execute();
            undoStack.push(command);
        }
    }

    public boolean canUndo() {
        return !undoStack.isEmpty();
    }

    public boolean canRedo() {
        return !redoStack.isEmpty();
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
        if (expense instanceof RecurringExpense) {
            baseRecurringExpenses.add((RecurringExpense) expense);
            regenerateExpenses();
        } else {
            expenses.add(expense);
        }
    }

    public void replaceExpense(Expense oldExpense, Expense newExpense) {
        int index = expenses.indexOf(oldExpense);
        if (index != -1) {
            expenses.set(index, newExpense);
        }
    }

    public void removeExpense(Expense expense) {
        if (expense instanceof RecurringExpense) {
            baseRecurringExpenses.remove(expense);
            regenerateExpenses();
        } else {
            expenses.remove(expense);
        }
    }

    public void updateRecurringExpense(RecurringExpense oldExpense, RecurringExpense newExpense) {
        int index = baseRecurringExpenses.indexOf(oldExpense);
        if (index != -1) {
            baseRecurringExpenses.set(index, newExpense);
            regenerateExpenses();
        }
    }

    public void deleteRecurringExpense(RecurringExpense expense) {
        baseRecurringExpenses.remove(expense);
        regenerateExpenses();
    }

    public List<Expense> getExpenses() {
        return expenses;
    }

    public List<RecurringExpense> getBaseRecurringExpenses() {
        return baseRecurringExpenses;
    }

    public List<Expense> getExpensesForSave() {
        List<Expense> result = new ArrayList<>();
        result.addAll(baseRecurringExpenses);
        for (Expense e : expenses) {
            if (e.getRecurringId() == null) {
                result.add(e);
            }
        }
        return result;
    }

    public void generateRecurringExpenses(LocalDate upToDate) {
        expenses.removeIf(e -> e.getRecurringId() != null);
        generatedRecurringIds.clear();

        List<Expense> generatedExpenses = new ArrayList<>();
        for (RecurringExpense recurringExpense : baseRecurringExpenses) {
            LocalDate currentDate = recurringExpense.getDate();
            LocalDate endDate = recurringExpense.getEndDate() != null ? recurringExpense.getEndDate() : upToDate;

            while (!currentDate.isAfter(endDate) && !currentDate.isAfter(upToDate)) {
                String recurringId = generateRecurringId(recurringExpense, currentDate);

                if (!generatedRecurringIds.contains(recurringId)) {
                    Expense generated = new Expense(
                        recurringExpense.getAmount(),
                        recurringExpense.getCategory(),
                        currentDate,
                        recurringExpense.getDescription(),
                        recurringId,
                        recurringExpense
                    );
                    if (recurringExpense.isIncome()) generated.setIncome(true);
                    if (recurringExpense.isRefund()) generated.setRefund(true);
                    if (recurringExpense.isExcluded()) generated.setExcluded(true);
                    generatedExpenses.add(generated);
                    generatedRecurringIds.add(recurringId);
                }

                currentDate = getNextRecurringDate(recurringExpense, currentDate);
            }
        }

        expenses.addAll(generatedExpenses);
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

    private void regenerateExpenses() {
        expenses.removeIf(e -> e.getRecurringId() != null);
        generatedRecurringIds.clear();
        generateRecurringExpenses(LocalDate.now());
    }

    private LocalDate getNextRecurringDate(RecurringExpense expense, LocalDate fromDate) {
        int originalDay = expense.getDate().getDayOfMonth();
        return switch (expense.getFrequency()) {
            case DAILY -> fromDate.plusDays(1);
            case WEEKLY -> fromDate.plusWeeks(1);
            case BIWEEKLY -> fromDate.plusWeeks(2);
            case MONTHLY -> adjustDay(fromDate.plusMonths(1), originalDay);
            case QUARTERLY -> adjustDay(fromDate.plusMonths(3), originalDay);
            case YEARLY -> adjustDay(fromDate.plusYears(1), originalDay);
        };
    }

    /** Preserve the original day-of-month, clamping to month length for short months. */
    private static LocalDate adjustDay(LocalDate date, int targetDay) {
        if (targetDay > date.lengthOfMonth()) {
            return date.withDayOfMonth(date.lengthOfMonth());
        }
        return date.withDayOfMonth(targetDay);
    }

    private String generateRecurringId(RecurringExpense expense, LocalDate date) {
        return expense.getAmount() + "|" + expense.getCategory() + "|" +
               expense.getDate() + "|" + expense.getFrequency() + "|" +
               expense.getEndDate() + "|" + expense.getDescription() + "|" + date;
    }

    public void loadExpenses(List<Expense> loadedExpenses) {
        expenses.clear();
        baseRecurringExpenses.clear();
        for (Expense expense : loadedExpenses) {
            if (expense instanceof RecurringExpense) {
                baseRecurringExpenses.add((RecurringExpense) expense);
            } else {
                expenses.add(expense);
            }
        }
        regenerateExpenses();
    }
}