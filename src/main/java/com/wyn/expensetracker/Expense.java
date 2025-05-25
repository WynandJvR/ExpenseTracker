package com.wyn.expensetracker;

import java.time.LocalDate;

public class Expense {
    private double amount;
    private String category;
    private LocalDate date;
    private String description;

    public Expense(double amount, String category, LocalDate date, String description) {
        if (amount <= 0) throw new IllegalArgumentException("Amount must be positive");
        if (category == null || category.trim().isEmpty()) throw new IllegalArgumentException("Category cannot be empty");
        if (date == null) throw new IllegalArgumentException("Date cannot be null");
        
        this.amount = amount;
        this.category = category.trim();
        this.date = date;
        this.description = description != null ? description.trim() : "";
    }

    public double getAmount() { return amount; }
    public String getCategory() { return category; }
    public LocalDate getDate() { return date; }
    public String getDescription() { return description; }

    @Override
    public String toString() {
        return String.format("%.2f, %s, %s, %s", amount, category, date, description);
    }
}