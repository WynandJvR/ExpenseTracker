package com.wyn.expensetracker;

import java.time.LocalDate;
import java.util.UUID;

public class RecurringExpense extends Expense {
    private RecurrenceType frequency;
    private LocalDate endDate; // Null if no end date
    // Stable identity for the series, independent of its mutable fields. Used to
    // key per-occurrence overrides (see OccurrenceOverride) so they survive edits
    // to the template. Defaults to a fresh id; persisted templates restore theirs.
    private String id = UUID.randomUUID().toString();

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

    public String getId() {
        return id;
    }

    /** Restores a persisted id. Ignores null/blank so a template always keeps a usable id. */
    public void setId(String id) {
        if (id != null && !id.trim().isEmpty()) {
            this.id = id;
        }
    }

    @Override
    public String toString() {
        return String.format("%s, Frequency: %s, End Date: %s",
                super.toString(), frequency, endDate != null ? endDate : "None");
    }
}

enum RecurrenceType {
    DAILY, WEEKLY, BIWEEKLY, MONTHLY, QUARTERLY, YEARLY
}