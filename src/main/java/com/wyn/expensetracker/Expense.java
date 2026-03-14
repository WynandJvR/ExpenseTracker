package com.wyn.expensetracker;

import java.time.LocalDate;

public class Expense {
    private double amount;
    private String category;
    private LocalDate date;
    private String description;
    private String recurringId;
    private RecurringExpense sourceRecurringExpense;
    private String importId;
    private boolean excluded;
    private boolean income;
    private boolean refund;

    public Expense(double amount, String category, LocalDate date, String description) {
        this.amount = amount;
        this.category = category;
        this.date = date;
        this.description = description;
    }

    public Expense(double amount, String category, LocalDate date, String description, String recurringId, RecurringExpense sourceRecurringExpense) {
        this(amount, category, date, description);
        this.recurringId = recurringId;
        this.sourceRecurringExpense = sourceRecurringExpense;
    }

    public double getAmount() {
        return amount;
    }

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public LocalDate getDate() {
        return date;
    }

    public String getDescription() {
        return description;
    }

    public String getRecurringId() {
        return recurringId;
    }

    public RecurringExpense getSourceRecurringExpense() {
        return sourceRecurringExpense;
    }

    public String getImportId() {
        return importId;
    }

    public void setImportId(String importId) {
        this.importId = importId;
    }

    public boolean isExcluded() {
        return excluded;
    }

    public void setExcluded(boolean excluded) {
        this.excluded = excluded;
    }

    public boolean isIncome() {
        return income;
    }

    public void setIncome(boolean income) {
        this.income = income;
    }

    public boolean isRefund() {
        return refund;
    }

    public void setRefund(boolean refund) {
        this.refund = refund;
    }

    @Override
    public String toString() {
        return String.format("Expense{amount=%.2f, category='%s', date=%s, description='%s'}", 
            amount, category, date, description);
    }
}