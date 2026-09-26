package com.wyn.expensetracker;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
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
    /** Optional keyword identifying this debt's instalments on imported statements (e.g. a debit order reference). */
    private String paymentKeyword;

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
    public String getPaymentKeyword() { return paymentKeyword; }
    public void setPaymentKeyword(String paymentKeyword) {
        this.paymentKeyword = paymentKeyword == null || paymentKeyword.trim().isEmpty()
            ? null : paymentKeyword.trim();
    }

    /** Calculate the standard monthly payment using amortization formula. */
    public double calculateMonthlyPayment() {
        double monthlyRate = annualRate / 100.0 / 12.0;
        if (monthlyRate == 0) return principal / termMonths;
        return principal * (monthlyRate * Math.pow(1 + monthlyRate, termMonths))
            / (Math.pow(1 + monthlyRate, termMonths) - 1);
    }

    private static final double EPS = 0.005;

    private double monthlyRate() {
        return annualRate / 100.0 / 12.0;
    }

    /** The scheduled monthly instalment (explicit payment, else the amortizing payment). */
    public double getScheduledPayment() {
        return monthlyPayment > 0 ? monthlyPayment : calculateMonthlyPayment();
    }

    /**
     * Generate the amortization schedule for the configured term. Each month interest
     * is applied, then the instalment. The final entry pays exactly the outstanding
     * amount (so it may be smaller than the regular instalment). If the instalment does
     * not cover the interest, the shortfall is capitalised (balance grows, principal part
     * negative) rather than silently dropped.
     */
    public List<AmortizationEntry> getAmortizationSchedule() {
        List<AmortizationEntry> schedule = new ArrayList<>();
        double balance = principal;
        double rate = monthlyRate();
        double payment = getScheduledPayment();

        for (int i = 1; i <= termMonths && balance > EPS; i++) {
            LocalDate paymentDate = startDate.plusMonths(i);
            double interest = balance * rate;
            double due = balance + interest;
            double paid = Math.min(payment, due);
            double principalPart = paid - interest;
            balance = due - paid;
            if (balance < EPS) balance = 0;
            schedule.add(new AmortizationEntry(i, paymentDate, paid, principalPart, interest, balance));
        }
        return schedule;
    }

    /**
     * Total cost of the loan: every scheduled payment plus any balance still owing at
     * the end of the term (a balloon), so it is never less than the principal.
     */
    public double getTotalCost() {
        double total = 0;
        double endBalance = principal;
        for (AmortizationEntry entry : getAmortizationSchedule()) {
            total += entry.payment;
            endBalance = entry.remainingBalance;
        }
        return total + endBalance;
    }

    /** Calculate total interest over the life of the loan. */
    public double getTotalInterest() {
        return getTotalCost() - principal;
    }

    /**
     * Remaining balance given only a total paid, assuming the money was paid as the
     * scheduled instalments from the start date (the last one possibly partial).
     * Partial payments reduce the balance and paying the full cost yields 0.
     * Prefer {@link #getRemainingBalance(Collection, LocalDate)} when payment dates are known.
     */
    public double getRemainingBalance(double totalPaid) {
        double balance = principal;
        double rate = monthlyRate();
        double payment = getScheduledPayment();
        double money = Math.max(0, totalPaid);
        if (payment <= 0) return Math.max(0, balance - money);
        int guard = 0;
        while (money > EPS && balance > EPS && guard++ < 12000) {
            balance += balance * rate;
            double pay = Math.min(Math.min(payment, money), balance);
            balance -= pay;
            money -= pay;
            // After the term, any remaining money is a lump settlement.
            if (guard >= termMonths && money > EPS) {
                double settle = Math.min(money, balance);
                balance -= settle;
                money -= settle;
            }
        }
        return balance < EPS ? 0 : balance;
    }

    /**
     * Remaining balance as of {@code asOf}, simulating month by month from the start
     * date: interest accrues each month on the outstanding balance and every recorded
     * payment for this debt (matched by id) is subtracted in the month it was made.
     * Partial and lump-sum payments are therefore handled exactly; a fully paid debt is 0.
     */
    public double getRemainingBalance(Collection<DebtPayment> payments, LocalDate asOf) {
        return simulateFrom(principal, 0, ownPayments(payments), asOf);
    }

    private List<DebtPayment> ownPayments(Collection<DebtPayment> payments) {
        List<DebtPayment> mine = new ArrayList<>();
        if (payments != null) {
            for (DebtPayment p : payments) {
                if (p != null && id != null && id.equals(p.getDebtId())) mine.add(p);
            }
        }
        return mine;
    }

    /**
     * Month-by-month simulation starting after scheduled period {@code startPeriod} with
     * {@code startBalance}: interest accrues each period and every payment dated on or
     * before the period end is subtracted. Payments dated before the first simulated
     * period are applied in it; payments after the last accrued period still count.
     */
    private double simulateFrom(double startBalance, int startPeriod, List<DebtPayment> mine, LocalDate asOf) {
        mine = new ArrayList<>(mine);
        mine.sort(Comparator.comparing(p -> p.getDate() != null ? p.getDate() : LocalDate.MIN));
        double balance = startBalance;
        double rate = monthlyRate();
        if (asOf == null) asOf = LocalDate.now();
        int idx = 0;

        for (int i = startPeriod + 1; balance > EPS && startDate != null; i++) {
            LocalDate periodEnd = startDate.plusMonths(i);
            if (periodEnd.isAfter(asOf)) break;
            balance += balance * rate;
            while (idx < mine.size()) {
                LocalDate d = mine.get(idx).getDate();
                if (d != null && d.isAfter(periodEnd)) break;
                balance -= mine.get(idx).getAmount();
                idx++;
            }
            if (balance < EPS) balance = 0;
        }
        // Payments made in the current, not-yet-accrued period (or later) still count.
        for (; idx < mine.size(); idx++) balance -= mine.get(idx).getAmount();
        return balance < EPS ? 0 : balance;
    }

    /**
     * Balance per the amortisation schedule as of {@code asOf}, i.e. assuming every
     * scheduled instalment due on or before that date was paid.
     */
    public double getScheduledBalance(LocalDate asOf) {
        if (asOf == null) asOf = LocalDate.now();
        double balance = principal;
        if (startDate == null) return balance;
        for (AmortizationEntry en : getAmortizationSchedule()) {
            if (en.date.isAfter(asOf)) break;
            balance = en.remainingBalance;
        }
        return balance;
    }

    /** Whether {@code description} contains the payment keyword as a whole word/phrase (case-insensitive). */
    public boolean matchesPaymentKeyword(String description) {
        if (paymentKeyword == null || description == null) return false;
        java.util.regex.Pattern p = java.util.regex.Pattern.compile(
            "(?<![\\p{L}\\p{N}])" + java.util.regex.Pattern.quote(paymentKeyword) + "(?![\\p{L}\\p{N}])",
            java.util.regex.Pattern.CASE_INSENSITIVE | java.util.regex.Pattern.UNICODE_CASE);
        return p.matcher(description).find();
    }

    /**
     * Imported transactions that pay this debt: imported (has an import id), not
     * income/refund (items excluded as transfers, e.g. "TRF TO LOAN ACC", DO count), dated on/after the start date, whose description matches the
     * payment keyword. Amounts are converted to the debt's currency via {@code cm}
     * (null = taken as-is).
     */
    public List<DebtPayment> matchImportedPayments(Collection<? extends Expense> expenses, CurrencyManager cm) {
        List<DebtPayment> result = new ArrayList<>();
        if (paymentKeyword == null || expenses == null) return result;
        for (Expense e : expenses) {
            if (e == null || e.getImportId() == null || e.getRecurringId() != null) continue;
            if (e.isIncome() || e.isRefund() || e.getDate() == null) continue;
            if (startDate != null && e.getDate().isBefore(startDate)) continue;
            if (!matchesPaymentKeyword(e.getDescription())) continue;
            double amount = e.getAmount();
            if (cm != null) {
                double base = cm.toBase(e.getAmount(), e.getCurrency());
                double debtRate = cm.getRate(currency);
                amount = debtRate > 0 ? base / debtRate : base;
            }
            result.add(new DebtPayment(id, amount, e.getDate(), "Imported: " + e.getDescription()));
        }
        return result;
    }

    /** Outcome of {@link #resolveBalance}. */
    public static final class BalanceStatus {
        /** Outstanding balance in the debt's currency. */
        public final double balance;
        /** True when no payments were found and the scheduled balance is assumed. */
        public final boolean estimated;
        /** Total of the payments used (manual + matched imports); 0 when estimated. */
        public final double totalPaid;
        /**
         * Scheduled instalments assumed paid (not recorded): those before the first known
         * payment, or every instalment due so far when the balance is estimated.
         */
        public final double assumedPaid;
        /** Number of leading schedule entries assumed paid (see {@link #assumedPaid}). */
        public final int assumedPeriods;

        BalanceStatus(double balance, boolean estimated, double totalPaid) {
            this(balance, estimated, totalPaid, 0, 0);
        }

        BalanceStatus(double balance, boolean estimated, double totalPaid,
                      double assumedPaid, int assumedPeriods) {
            this.balance = balance;
            this.estimated = estimated;
            this.totalPaid = totalPaid;
            this.assumedPaid = assumedPaid;
            this.assumedPeriods = assumedPeriods;
        }

        /** Recorded/matched payments plus the instalments assumed paid. */
        public double totalPaidInclAssumed() {
            return totalPaid + assumedPaid;
        }
    }

    /** Days either side within which a manual payment and a matched import are the same payment. */
    public static final int DUPLICATE_WINDOW_DAYS = 5;
    /** Relative amount tolerance for treating a manual payment and an import as the same payment. */
    public static final double DUPLICATE_AMOUNT_TOLERANCE = 0.01;

    /**
     * Drops imported payments that duplicate a manual payment (dated within
     * {@link #DUPLICATE_WINDOW_DAYS} days, amount within 1%). Matching is one-to-one:
     * each manual payment absorbs at most one import (closest date first).
     */
    public static List<DebtPayment> dropDuplicateImports(List<DebtPayment> manual, List<DebtPayment> imported) {
        List<DebtPayment> kept = new ArrayList<>();
        if (imported == null) return kept;
        List<DebtPayment> free = new ArrayList<>();
        if (manual != null) for (DebtPayment m : manual) if (m != null && m.getDate() != null) free.add(m);
        List<DebtPayment> sorted = new ArrayList<>(imported);
        sorted.sort(Comparator.comparing(p -> p.getDate() != null ? p.getDate() : LocalDate.MIN));
        for (DebtPayment imp : sorted) {
            DebtPayment best = null;
            long bestDays = Long.MAX_VALUE;
            if (imp.getDate() != null) {
                for (DebtPayment m : free) {
                    long days = Math.abs(java.time.temporal.ChronoUnit.DAYS.between(m.getDate(), imp.getDate()));
                    if (days > DUPLICATE_WINDOW_DAYS) continue;
                    double scale = Math.max(Math.abs(m.getAmount()), Math.abs(imp.getAmount()));
                    if (Math.abs(m.getAmount() - imp.getAmount()) > scale * DUPLICATE_AMOUNT_TOLERANCE) continue;
                    if (days < bestDays) { best = m; bestDays = days; }
                }
            }
            if (best != null) free.remove(best);
            else kept.add(imp);
        }
        return kept;
    }

    /**
     * Balance from manual payments plus imported transactions matching the payment keyword
     * (imports duplicating a manual payment are dropped). If there are no payments at all,
     * falls back to the scheduled amortisation balance (assuming the instalments were paid,
     * e.g. by debit order) and marks it estimated. Otherwise the scheduled instalments before
     * the one the earliest known payment settles are assumed paid — statements rarely go back to the loan's start — and the known
     * payments are simulated forward from that scheduled balance. The earliest payment is
     * taken to pay the schedule entry nearest to it by date (ties: the earlier entry), so a
     * debit that lands a week or two after the schedule day is not double counted.
     */
    public BalanceStatus resolveBalance(Collection<DebtPayment> manualPayments,
                                        Collection<? extends Expense> expenses,
                                        CurrencyManager cm, LocalDate asOf) {
        List<DebtPayment> manual = ownPayments(manualPayments);
        List<DebtPayment> all = new ArrayList<>(manual);
        all.addAll(dropDuplicateImports(manual, matchImportedPayments(expenses, cm)));
        if (all.isEmpty()) {
            if (asOf == null) asOf = LocalDate.now();
            double assumed = 0;
            int periods = 0;
            if (startDate != null) {
                for (AmortizationEntry en : getAmortizationSchedule()) {
                    if (en.date.isAfter(asOf)) break;
                    assumed += en.payment;
                    periods = en.month;
                }
            }
            return new BalanceStatus(getScheduledBalance(asOf), true, 0, assumed, periods);
        }
        LocalDate earliest = null;
        for (DebtPayment p : all) {
            if (p.getDate() != null && (earliest == null || p.getDate().isBefore(earliest))) earliest = p.getDate();
        }
        double startBalance = principal;
        int startPeriod = 0;
        double assumed = 0;
        if (earliest != null && startDate != null) {
            // The earliest known payment pays the schedule entry nearest to it (ties go to
            // the earlier entry, i.e. a late payment); every entry before that is assumed paid.
            List<AmortizationEntry> schedule = getAmortizationSchedule();
            int anchor = -1;
            long bestDays = Long.MAX_VALUE;
            for (int i = 0; i < schedule.size(); i++) {
                long days = Math.abs(java.time.temporal.ChronoUnit.DAYS.between(schedule.get(i).date, earliest));
                if (days < bestDays) { bestDays = days; anchor = i; }
                else if (schedule.get(i).date.isAfter(earliest)) break;
            }
            for (int i = 0; i < anchor; i++) {
                AmortizationEntry en = schedule.get(i);
                startBalance = en.remainingBalance;
                startPeriod = en.month;
                assumed += en.payment;
            }
        }
        return new BalanceStatus(simulateFrom(startBalance, startPeriod, all, asOf), false, getTotalPaid(all),
            assumed, startPeriod);
    }

    /** Sum of recorded payments for this debt. */
    public double getTotalPaid(Collection<DebtPayment> payments) {
        double total = 0;
        if (payments == null) return 0;
        for (DebtPayment p : payments) {
            if (p != null && id != null && id.equals(p.getDebtId())) total += p.getAmount();
        }
        return total;
    }

    /** Progress 0..1 as the share of principal already repaid. */
    public double getPayoffProgress(Collection<DebtPayment> payments, LocalDate asOf) {
        double remaining = getRemainingBalance(payments, asOf);
        return Math.max(0, Math.min(1, (principal - remaining) / principal));
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
