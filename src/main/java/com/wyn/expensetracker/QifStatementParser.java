package com.wyn.expensetracker;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

public class QifStatementParser implements BankStatementParser {

    private static final DateTimeFormatter[] DATE_FORMATS = {
        DateTimeFormatter.ofPattern("MM/dd/yyyy"),
        DateTimeFormatter.ofPattern("dd/MM/yyyy"),
        DateTimeFormatter.ofPattern("yyyy-MM-dd"),
        DateTimeFormatter.ofPattern("MM-dd-yyyy"),
        DateTimeFormatter.ofPattern("dd-MM-yyyy"),
        DateTimeFormatter.ofPattern("M/d/yyyy"),
    };

    @Override
    public boolean canParse(String text) {
        return text.contains("!Type:") || text.contains("!type:");
    }

    @Override
    public List<ImportItem> parse(String text) {
        List<ImportItem> items = new ArrayList<>();
        String[] lines = text.split("\\r?\\n");

        String dateStr = null;
        String amountStr = null;
        String payee = null;
        String memo = null;

        for (String line : lines) {
            if (line.trim().isEmpty()) continue;

            // Header lines start with !
            if (line.startsWith("!")) continue;

            char code = line.charAt(0);
            String value = line.substring(1).trim();

            switch (code) {
                case 'D' -> dateStr = value;
                case 'T', 'U' -> amountStr = value;
                case 'P' -> payee = value;
                case 'M' -> memo = value;
                case '^' -> {
                    // End of record
                    ImportItem item = buildItem(dateStr, amountStr, payee, memo);
                    if (item != null) items.add(item);
                    dateStr = null;
                    amountStr = null;
                    payee = null;
                    memo = null;
                }
            }
        }

        return items;
    }

    private ImportItem buildItem(String dateStr, String amountStr, String payee, String memo) {
        if (amountStr == null || dateStr == null) return null;

        try {
            double amount = Double.parseDouble(amountStr.replace(",", "").trim());
            LocalDate date = parseDate(dateStr);
            if (date == null) return null;

            String description = buildDescription(payee, memo);

            boolean isIncome = amount > 0;
            double absAmount = Math.abs(amount);
            if (absAmount <= 0) return null;

            String desc = isIncome ? "[CREDIT] " + description : description;
            ImportItem item = new ImportItem(absAmount, desc, date);
            item.setCategory("Uncategorized");
            item.setIncome(isIncome);
            item.setStatus("Uncategorized");
            return item;
        } catch (Exception e) {
            return null;
        }
    }

    private LocalDate parseDate(String dateStr) {
        // Handle QIF short date format: M/D'YY -> expand to M/D/20YY
        if (dateStr.contains("'")) {
            dateStr = dateStr.replace("'", "/20");
        }
        // Handle single-quote two-digit year: 3/15'24 -> 3/15/2024
        for (DateTimeFormatter fmt : DATE_FORMATS) {
            try {
                return LocalDate.parse(dateStr, fmt);
            } catch (Exception ignored) {}
        }
        return null;
    }

    private String buildDescription(String payee, String memo) {
        if (payee != null && memo != null && !memo.isEmpty() && !memo.equalsIgnoreCase(payee)) {
            return payee + " - " + memo;
        }
        return payee != null ? payee : (memo != null ? memo : "");
    }

    @Override
    public String getBankName() {
        return "QIF Statement";
    }
}
