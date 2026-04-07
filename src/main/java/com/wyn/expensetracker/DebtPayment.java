package com.wyn.expensetracker;

import java.time.LocalDate;

public class DebtPayment {
    private final String debtId;
    private final double amount;
    private final LocalDate date;
    private final String note;

    public DebtPayment(String debtId, double amount, LocalDate date, String note) {
        this.debtId = debtId;
        this.amount = amount;
        this.date = date;
        this.note = note;
    }

    public String getDebtId() { return debtId; }
    public double getAmount() { return amount; }
    public LocalDate getDate() { return date; }
    public String getNote() { return note; }
}
