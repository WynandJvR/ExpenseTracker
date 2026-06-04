package com.wyn.expensetracker;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

public class Debt {
    private final String id;
    private String name;
    private double principal;
    private double annualRate; // as percentage, e.g., 8.5 = 8.5%
    private int termMonths;
    private LocalDate startDate;
    private String paymentFrequency; // MONTHLY, BIWEEKLY, WEEKLY
    private double monthlyPayment;
    private String currency;

    public Debt(String id, String name, double principal, double annualRate, int termMonths,
                LocalDate startDate, String paymentFrequency, double monthlyPayment, String currency) {
        if (termMonths <= 0) {
            throw new IllegalArgumentException("Term months must be positive");
        }
        if (principal <= 0) {
            throw new IllegalArgumentException("Principal must be positive");
        }
        if (annualRate < 0) {
            throw new IllegalArgumentException("Annual rate cannot be negative");
        }
        this.id = id;
        this.name = name;
        this.principal = principal;
        this.annualRate = annualRate;
        this.termMonths = termMonths;
        this.startDate = startDate;
        this.paymentFrequency = paymentFrequency;
        this.monthlyPayment = monthlyPayment;
        this.currency = currency;
    }

    public String getId() { return id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public double getPrincipal() { return principal; }
    public void setPrincipal(double principal) { this.principal = principal; }
    public double getAnnualRate() { return annualRate; }
    public void setAnnualRate(double annualRate) { this.annualRate = annualRate; }
    public int getTermMonths() { return termMonths; }
    public void setTermMonths(int termMonths) { this.termMonths = termMonths; }
    public LocalDate getStartDate() { return startDate; }
    public void setStartDate(LocalDate startDate) { this.startDate = startDate; }
    public String getPaymentFrequency() { return paymentFrequency; }
    public void setPaymentFrequency(String paymentFrequency) { this.paymentFrequency = paymentFrequency; }
    public double getMonthlyPayment() { return monthlyPayment; }
    public void setMonthlyPayment(double monthlyPayment) { this.monthlyPayment = monthlyPayment; }
    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }

    /** Calculate the standard monthly payment using amortization formula. */
    public double calculateMonthlyPayment() {
        double monthlyRate = annualRate / 100.0 / 12.0;
        if (monthlyRate == 0) return principal / termMonths;
        return principal * (monthlyRate * Math.pow(1 + monthlyRate, termMonths))
            / (Math.pow(1 + monthlyRate, termMonths) - 1);
    }

    /** Generate a full amortization schedule. */
    public List<AmortizationEntry> getAmortizationSchedule() {
        List<AmortizationEntry> schedule = new ArrayList<>();
        double balance = principal;
        double monthlyRate = annualRate / 100.0 / 12.0;
        double payment = monthlyPayment > 0 ? monthlyPayment : calculateMonthlyPayment();
        LocalDate paymentDate = startDate;

        for (int i = 1; i <= termMonths && balance > 0.005; i++) {
            paymentDate = startDate.plusMonths(i);
            double interest = balance * monthlyRate;
            double principalPart = Math.min(payment - interest, balance);
            if (principalPart < 0) principalPart = 0;
            balance -= principalPart;
            if (balance < 0.005) balance = 0;
            schedule.add(new AmortizationEntry(i, paymentDate, payment, principalPart, interest, balance));
        }
        return schedule;
    }

    /**
     * Total paid over the life of the loan, summed from the actual amortization
     * schedule. This matches what the user sees in the schedule (which terminates
     * once the balance is paid off, possibly early or late vs. termMonths).
     */
    public double getTotalCost() {
        double total = 0;
        for (AmortizationEntry entry : getAmortizationSchedule()) {
            total += entry.principal + entry.interest;
        }
        return total;
    }

    /** Calculate total interest over the life of the loan. */
    public double getTotalInterest() {
        return getTotalCost() - principal;
    }

    /** Calculate remaining balance given total payments made. */
    public double getRemainingBalance(double totalPaid) {
        List<AmortizationEntry> schedule = getAmortizationSchedule();
        double balance = principal;
        double paid = 0;
        for (AmortizationEntry entry : schedule) {
            if (paid + entry.payment > totalPaid + 0.005) break;
            balance = entry.remainingBalance;
            paid += entry.payment;
        }
        return balance;
    }

    public static class AmortizationEntry {
        public final int month;
        public final LocalDate date;
        public final double payment;
        public final double principal;
        public final double interest;
        public final double remainingBalance;

        public AmortizationEntry(int month, LocalDate date, double payment, double principal,
                                 double interest, double remainingBalance) {
            this.month = month;
            this.date = date;
            this.payment = payment;
            this.principal = principal;
            this.interest = interest;
            this.remainingBalance = remainingBalance;
        }
    }
}
