package com.wyn.expensetracker;

import java.time.LocalDate;

public class RecurringExpense extends Expense {
    private RecurrenceType frequency;
    private LocalDate endDate;

    public RecurringExpense(double amount, String category, LocalDate date, String description,
                           RecurrenceType frequency, LocalDate endDate) {
        super(amount, category, date, description);
        if (frequency == null) throw new IllegalArgumentException("Frequency cannot be null");
        this.frequency = frequency;
        this.endDate = endDate;
    }

    public RecurrenceType getFrequency() {
        return frequency;
    }

    public LocalDate getEndDate() {
        return endDate;
    }

    @Override
    public String toString() {
        return String.format("%s, Frequency: %s, End Date: %s",
                super.toString(), frequency, endDate != null ? endDate : "None");
    }
}

enum RecurrenceType {
    DAILY, WEEKLY, MONTHLY, YEARLY
}