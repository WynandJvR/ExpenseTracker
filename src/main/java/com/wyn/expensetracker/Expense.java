package com.wyn.expensetracker;

import java.time.LocalDate;
import java.util.LinkedHashSet;
import java.util.Set;

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
    private String currency; // ISO currency code (e.g., "ZAR", "USD"); null = base currency
    private String receiptPath; // path to attached receipt image
    private final Set<String> tags = new LinkedHashSet<>();

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

    public String getCurrency() {
        return currency;
    }

    public void setCurrency(String currency) {
        this.currency = currency;
    }

    public String getReceiptPath() {
        return receiptPath;
    }

    public void setReceiptPath(String receiptPath) {
        this.receiptPath = receiptPath;
    }

    public Set<String> getTags() {
        return tags;
    }

    public void setTags(Set<String> tags) {
        this.tags.clear();
        if (tags != null) {
            for (String tag : tags) {
                addTag(tag);
            }
        }
    }

    public void addTag(String tag) {
        if (tag != null && !tag.trim().isEmpty()) {
            tags.add(sanitizeTag(tag.trim()));
        }
    }

    private static String sanitizeTag(String tag) {
        return tag.replace("|", "").replace(",", "").replace("\n", "").replace("\r", "");
    }

    public void removeTag(String tag) {
        tags.remove(tag);
    }

    public boolean hasTag(String tag) {
        return tags.contains(tag);
    }

    @Override
    public String toString() {
        return String.format("Expense{amount=%.2f, category='%s', date=%s, description='%s'}", 
            amount, category, date, description);
    }
}