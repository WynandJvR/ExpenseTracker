package com.wyn.expensetracker;

public class CategoryTotal {
    private String category;
    private double total;

    public CategoryTotal(String category, double total) {
        this.category = category;
        this.total = total;
    }

    public String getCategory() {
        return category;
    }

    public double getTotal() {
        return total;
    }
}