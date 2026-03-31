package com.wyn.expensetracker;

import java.time.LocalDate;

public class SavingsGoal {
    private String id;
    private String name;
    private double targetAmount;
    private LocalDate deadline;
    private double monthlyTarget;
    private LocalDate createdDate;

    public SavingsGoal(String id, String name, double targetAmount, LocalDate deadline,
                       double monthlyTarget, LocalDate createdDate) {
        this.id = id;
        this.name = name;
        this.targetAmount = targetAmount;
        this.deadline = deadline;
        this.monthlyTarget = monthlyTarget;
        this.createdDate = createdDate;
    }

    public String getId() { return id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public double getTargetAmount() { return targetAmount; }
    public void setTargetAmount(double targetAmount) { this.targetAmount = targetAmount; }
    public LocalDate getDeadline() { return deadline; }
    public void setDeadline(LocalDate deadline) { this.deadline = deadline; }
    public double getMonthlyTarget() { return monthlyTarget; }
    public void setMonthlyTarget(double monthlyTarget) { this.monthlyTarget = monthlyTarget; }
    public LocalDate getCreatedDate() { return createdDate; }
}
