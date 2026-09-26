package com.wyn.expensetracker;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DebtBalanceTest {

    private static final LocalDate START = LocalDate.of(2025, 1, 1);

    private Debt loan() {
        Debt d = new Debt("d1", "Loan", 12000, 12.0, 12, START, "MONTHLY", 0, "ZAR");
        d.setMonthlyPayment(d.calculateMonthlyPayment());
        return d;
    }

    @Test
    void finalScheduledPaymentIsTheTrueRemainingAmount() {
        Debt d = new Debt("d1", "Loan", 1000, 0.0, 3, START, "MONTHLY", 400, "ZAR");
        List<Debt.AmortizationEntry> s = d.getAmortizationSchedule();
        assertEquals(3, s.size());
        assertEquals(200.0, s.get(2).payment, 0.001, "Last payment only covers what's left");
        assertEquals(0.0, s.get(2).remainingBalance, 0.001);
        assertEquals(1000.0, d.getTotalCost(), 0.001);
    }

    @Test
    void paymentBelowInterest_totalCostNeverBelowPrincipal() {
        // 10000 @ 24% => 200/month interest, but only 50/month paid.
        Debt d = new Debt("d1", "Loan", 10000, 24.0, 12, START, "MONTHLY", 50, "ZAR");
        assertTrue(d.getTotalCost() >= d.getPrincipal(), "Unpaid interest must not vanish");
        assertTrue(d.getTotalInterest() > 0);
        List<Debt.AmortizationEntry> s = d.getAmortizationSchedule();
        assertTrue(s.get(s.size() - 1).remainingBalance > 10000, "Shortfall capitalises");
    }

    @Test
    void partialPaymentReducesBalance() {
        Debt d = loan();
        double full = d.getRemainingBalance(d.getScheduledPayment());
        double partial = d.getRemainingBalance(d.getScheduledPayment() * 1.5);
        assertTrue(partial < full, "Half an extra instalment must reduce the balance");
    }

    @Test
    void totalPaidEqualToTotalCostIsFullyPaid() {
        Debt d = loan();
        assertEquals(0.0, d.getRemainingBalance(d.getTotalCost()), 0.001);
    }

    @Test
    void datedPayments_lumpSumSettlesDebt() {
        Debt d = loan();
        List<DebtPayment> payments = new ArrayList<>();
        payments.add(new DebtPayment("d1", d.getScheduledPayment(), START.plusMonths(1), ""));
        // Balance after month 1 ~ 12000*1.01 - pmt; after month 2 interest again. Pay a big lump.
        payments.add(new DebtPayment("d1", 20000, START.plusMonths(2), "lump"));
        assertEquals(0.0, d.getRemainingBalance(payments, START.plusMonths(6)), 0.001);
        assertEquals(1.0, d.getPayoffProgress(payments, START.plusMonths(6)), 0.001);
    }

    @Test
    void datedPayments_matchScheduleWhenPaidOnTime() {
        Debt d = loan();
        List<Debt.AmortizationEntry> schedule = d.getAmortizationSchedule();
        List<DebtPayment> payments = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            payments.add(new DebtPayment("d1", schedule.get(i).payment, schedule.get(i).date, ""));
        }
        assertEquals(schedule.get(2).remainingBalance,
            d.getRemainingBalance(payments, schedule.get(2).date), 0.01);
    }

    @Test
    void datedPayments_ignoreOtherDebtsAndHandlePartial() {
        Debt d = new Debt("d1", "Loan", 1000, 0.0, 10, START, "MONTHLY", 0, "ZAR");
        List<DebtPayment> payments = List.of(
            new DebtPayment("d1", 30, START.plusDays(10), "partial"),
            new DebtPayment("other", 500, START.plusDays(10), ""));
        assertEquals(970.0, d.getRemainingBalance(payments, START.plusMonths(3)), 0.001);
    }
}
