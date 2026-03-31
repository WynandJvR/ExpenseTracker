package com.wyn.expensetracker;

import java.time.LocalDate;

public class Anomaly {
    public enum AnomalyType { AMOUNT_OUTLIER, NEW_CATEGORY, SPENDING_SPIKE, LARGE_TRANSACTION }

    private final AnomalyType type;
    private final String message;
    private final Expense expense;
    private final LocalDate date;
    private final double severity; // 0.0-1.0

    public Anomaly(AnomalyType type, String message, Expense expense, LocalDate date, double severity) {
        this.type = type;
        this.message = message;
        this.expense = expense;
        this.date = date;
        this.severity = severity;
    }

    public AnomalyType getType() { return type; }
    public String getMessage() { return message; }
    public Expense getExpense() { return expense; }
    public LocalDate getDate() { return date; }
    public double getSeverity() { return severity; }

    public String getDismissKey() {
        if (expense != null) {
            String desc = expense.getDescription() != null ? expense.getDescription() : "";
            return type.name() + ":" + expense.getAmount() + ":" + expense.getDate() + ":" + desc.hashCode();
        }
        return type.name() + ":" + date;
    }
}
