package com.wyn.expensetracker;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Everything a statement parser could learn from one file: the transactions plus,
 * when the statement prints them, the opening/closing balances and period. The
 * balances let the importer prove nothing was missed (see {@link #isReconciled()}).
 */
public class StatementParseResult {
    private final String bankName;
    private final List<ImportItem> items;
    private String accountLabel;
    private LocalDate periodStart;
    private LocalDate periodEnd;
    private Double openingBalance;
    private Double closingBalance;
    private int skippedMemoLines;
    private String currency; // ISO code the statement is in, when known (e.g. "ZAR" for FNB)

    public StatementParseResult(String bankName, List<ImportItem> items) {
        this.bankName = bankName;
        this.items = items != null ? items : new ArrayList<>();
    }

    public String getBankName() { return bankName; }
    public List<ImportItem> getItems() { return items; }

    public String getAccountLabel() { return accountLabel; }
    public void setAccountLabel(String accountLabel) { this.accountLabel = accountLabel; }

    public LocalDate getPeriodStart() { return periodStart; }
    public void setPeriodStart(LocalDate periodStart) { this.periodStart = periodStart; }

    public LocalDate getPeriodEnd() { return periodEnd; }
    public void setPeriodEnd(LocalDate periodEnd) { this.periodEnd = periodEnd; }

    public Double getOpeningBalance() { return openingBalance; }
    public void setOpeningBalance(Double openingBalance) { this.openingBalance = openingBalance; }

    public Double getClosingBalance() { return closingBalance; }
    public void setClosingBalance(Double closingBalance) { this.closingBalance = closingBalance; }

    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }

    /** Lines that looked like transactions but didn't move the balance (e.g. failed debit-order notices). */
    public int getSkippedMemoLines() { return skippedMemoLines; }
    public void setSkippedMemoLines(int skippedMemoLines) { this.skippedMemoLines = skippedMemoLines; }

    public double totalIn() {
        return items.stream().filter(ImportItem::isCredit).mapToDouble(ImportItem::getAmount).sum();
    }

    public double totalOut() {
        return items.stream().filter(i -> !i.isCredit()).mapToDouble(ImportItem::getAmount).sum();
    }

    /** True when both balances are known. */
    public boolean canReconcile() {
        return openingBalance != null && closingBalance != null;
    }

    /** Opening + money in − money out equals the closing balance (to the cent). */
    public boolean isReconciled() {
        return canReconcile() && Math.abs(reconciliationDifference()) < 0.005;
    }

    public double reconciliationDifference() {
        if (!canReconcile()) return 0;
        return openingBalance + totalIn() - totalOut() - closingBalance;
    }
}
