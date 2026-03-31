package com.wyn.expensetracker;

public class BudgetAlert {
    public enum Severity { WARNING, DANGER }

    private final String category;
    private final double spentAmount;
    private final double budgetAmount;
    private final double percentUsed;
    private final Severity severity;

    public BudgetAlert(String category, double spentAmount, double budgetAmount) {
        this.category = category;
        this.spentAmount = spentAmount;
        this.budgetAmount = budgetAmount;
        this.percentUsed = budgetAmount > 0 ? (spentAmount / budgetAmount) * 100 : 0;
        this.severity = percentUsed >= 100 ? Severity.DANGER : Severity.WARNING;
    }

    public String getCategory() { return category; }
    public double getSpentAmount() { return spentAmount; }
    public double getBudgetAmount() { return budgetAmount; }
    public double getPercentUsed() { return percentUsed; }
    public Severity getSeverity() { return severity; }
}
