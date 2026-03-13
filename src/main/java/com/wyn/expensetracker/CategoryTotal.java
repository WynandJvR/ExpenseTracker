package com.wyn.expensetracker;

public class CategoryTotal {
    private String category;
    private double total;
    private double budget;

    public CategoryTotal(String category, double total) {
        this(category, total, 0.0);
    }

    public CategoryTotal(String category, double total, double budget) {
        this.category = category;
        this.total = total;
        this.budget = budget;
    }

    public String getCategory() {
        return category;
    }

    public double getTotal() {
        return total;
    }

    public double getBudget() {
        return budget;
    }

    public double getProgress() {
        return budget > 0 ? total / budget : 0.0;
    }
}
