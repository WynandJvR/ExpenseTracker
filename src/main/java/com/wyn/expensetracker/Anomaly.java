package com.wyn.expensetracker;

import java.time.LocalDate;

public class Anomaly {
    public enum AnomalyType {
        AMOUNT_OUTLIER, NEW_CATEGORY, SPENDING_SPIKE, LARGE_TRANSACTION,
        /** A subscription/debit order charged at a different price than usual. */
        PRICE_CHANGE,
        /** A merchant that has started charging the same amount at a regular interval. */
        NEW_SUBSCRIPTION,
        /** The same amount at the same merchant twice within a few days. */
        DUPLICATE_CHARGE
    }

    private final AnomalyType type;
    private final String message;
    private final Expense expense;
    private final LocalDate date;
    private final double severity; // 0.0-1.0
    private final String subject;  // e.g. the category for NEW_CATEGORY; part of the dismiss key

    public Anomaly(AnomalyType type, String message, Expense expense, LocalDate date, double severity) {
        this(type, message, expense, date, severity, null);
    }

    public Anomaly(AnomalyType type, String message, Expense expense, LocalDate date, double severity,
                   String subject) {
        this.type = type;
        this.message = message;
        this.expense = expense;
        this.date = date;
        this.severity = severity;
        this.subject = subject;
    }

    public String getSubject() { return subject; }

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
        // Without a subject, two NEW_CATEGORY alerts in the same month would share a key
        // and dismissing one would hide the other.
        return type.name() + ":" + date + (subject != null ? ":" + subject : "");
    }
}
