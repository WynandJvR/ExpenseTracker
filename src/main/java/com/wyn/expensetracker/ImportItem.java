package com.wyn.expensetracker;

import javafx.beans.property.*;
import java.time.LocalDate;

public class ImportItem {
    private final BooleanProperty selected = new SimpleBooleanProperty(true);
    private final DoubleProperty amount = new SimpleDoubleProperty();
    private final StringProperty category = new SimpleStringProperty("");
    private final ObjectProperty<LocalDate> date = new SimpleObjectProperty<>();
    private final StringProperty description = new SimpleStringProperty("");
    private final StringProperty status = new SimpleStringProperty("Uncategorized");
    private final BooleanProperty duplicate = new SimpleBooleanProperty(false);
    private final BooleanProperty income = new SimpleBooleanProperty(false);
    private final BooleanProperty transfer = new SimpleBooleanProperty(false);
    private final BooleanProperty refund = new SimpleBooleanProperty(false);
    private Expense duplicateMatch;
    private String sourceFile;
    private boolean credit;
    private Double balance;      // running balance after this line, when the statement shows one
    private String fingerprint;  // stable identity used to skip re-imported transactions

    public ImportItem(double amount, String description, LocalDate date) {
        this.amount.set(amount);
        this.description.set(description != null ? description : "");
        this.date.set(date);
    }

    // Selected
    public boolean isSelected() { return selected.get(); }
    public void setSelected(boolean val) { selected.set(val); }
    public BooleanProperty selectedProperty() { return selected; }

    // Amount
    public double getAmount() { return amount.get(); }
    public void setAmount(double val) { amount.set(val); }
    public DoubleProperty amountProperty() { return amount; }

    // Category
    public String getCategory() { return category.get(); }
    public void setCategory(String val) { category.set(val); }
    public StringProperty categoryProperty() { return category; }

    // Date
    public LocalDate getDate() { return date.get(); }
    public void setDate(LocalDate val) { date.set(val); }
    public ObjectProperty<LocalDate> dateProperty() { return date; }

    // Description
    public String getDescription() { return description.get(); }
    public void setDescription(String val) { description.set(val); }
    public StringProperty descriptionProperty() { return description; }

    // Status
    public String getStatus() { return status.get(); }
    public void setStatus(String val) { status.set(val); }
    public StringProperty statusProperty() { return status; }

    // Duplicate
    public boolean isDuplicate() { return duplicate.get(); }
    public void setDuplicate(boolean val) { duplicate.set(val); }
    public BooleanProperty duplicateProperty() { return duplicate; }

    public Expense getDuplicateMatch() { return duplicateMatch; }
    public void setDuplicateMatch(Expense val) { this.duplicateMatch = val; }

    // Income
    public boolean isIncome() { return income.get(); }
    public void setIncome(boolean val) { income.set(val); }
    public BooleanProperty incomeProperty() { return income; }

    // Transfer between the user's own accounts: imported for completeness but
    // excluded from spend and income totals.
    public boolean isTransfer() { return transfer.get(); }
    public void setTransfer(boolean val) { transfer.set(val); }
    public BooleanProperty transferProperty() { return transfer; }

    // Money back for an earlier purchase: reduces spend rather than counting as income.
    public boolean isRefund() { return refund.get(); }
    public void setRefund(boolean val) { refund.set(val); }
    public BooleanProperty refundProperty() { return refund; }

    /** Direction of the money as printed on the statement: true = into the account. */
    public boolean isCredit() { return credit; }
    public void setCredit(boolean val) { this.credit = val; }

    public Double getBalance() { return balance; }
    public void setBalance(Double val) { this.balance = val; }

    public String getFingerprint() { return fingerprint; }
    public void setFingerprint(String val) { this.fingerprint = val; }

    // Source file tracking (for per-file import logs)
    public String getSourceFile() { return sourceFile; }
    public void setSourceFile(String val) { this.sourceFile = val; }
}
