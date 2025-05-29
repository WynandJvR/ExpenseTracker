package com.wyn.expensetracker;

import java.time.LocalDate;

public class RecurringExpense extends Expense {
    private RecurrenceType frequency;
    private LocalDate endDate; // Null if no end date

    public RecurringExpense(double amount, String category, LocalDate date, String description,
                           RecurrenceType frequency, LocalDate endDate) {
        super(amount, category, date, description);
        this.frequency = frequency;
        this.endDate = endDate;
    }

    // Getters
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