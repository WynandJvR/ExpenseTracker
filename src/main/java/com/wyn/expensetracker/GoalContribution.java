package com.wyn.expensetracker;

import java.time.LocalDate;

public class GoalContribution {
    private final String goalId;
    private final double amount;
    private final LocalDate date;
    private final String note;

    public GoalContribution(String goalId, double amount, LocalDate date, String note) {
        this.goalId = goalId;
        this.amount = amount;
        this.date = date;
        this.note = note;
    }

    public String getGoalId() { return goalId; }
    public double getAmount() { return amount; }
    public LocalDate getDate() { return date; }
    public String getNote() { return note; }
}
