package com.wyn.expensetracker;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

public class QifStatementParser implements BankStatementParser {

    private static final DateTimeFormatter[] DATE_FORMATS = {
        // Day-first before month-first: an ambiguous "03/04/2024" is 3 April in South Africa.
        DateTimeFormatter.ofPattern("dd/MM/yyyy"),
        DateTimeFormatter.ofPattern("MM/dd/yyyy"),
        DateTimeFormatter.ofPattern("yyyy-MM-dd"),
        DateTimeFormatter.ofPattern("dd-MM-yyyy"),
        DateTimeFormatter.ofPattern("MM-dd-yyyy"),
        DateTimeFormatter.ofPattern("d/M/yyyy"),
        DateTimeFormatter.ofPattern("M/d/yyyy"),
    };

    @Override
    public boolean canParse(String text) {
        return text.contains("!Type:") || text.contains("!type:");
    }

    @Override
    public List<ImportItem> parse(String text) {
        chooseFormat(text);
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
            Double parsed = Amounts.parse(amountStr);
            if (parsed == null) return null;
            double amount = parsed;
            LocalDate date = parseDate(dateStr);
            if (date == null) return null;

            String description = buildDescription(payee, memo);

            double absAmount = Amounts.round2(Math.abs(amount));
            if (absAmount <= 0) return null;

            ImportItem item = new ImportItem(absAmount, description, date);
            item.setCredit(amount > 0);
            item.setStatus("Uncategorized");
            return item;
        } catch (Exception e) {
            return null;
        }
    }

    /** Chosen once per file so every record uses the same day/month order. */
    private DateTimeFormatter fileFormat;

    private static String cleanDate(String dateStr) {
        dateStr = dateStr.replace(" ", "");
        return dateStr.contains("'") ? dateStr.replace("'", "/20") : dateStr;
    }

    private void chooseFormat(String text) {
        List<String> dates = new ArrayList<>();
        for (String line : text.split("\\r?\\n")) {
            if (line.startsWith("D")) dates.add(cleanDate(line.substring(1).trim()));
        }
        fileFormat = null;
        int best = 0;
        for (DateTimeFormatter fmt : DATE_FORMATS) {
            int ok = 0;
            for (String d : dates) {
                try { LocalDate.parse(d, fmt); ok++; } catch (Exception ignored) {}
            }
            if (ok > best) { best = ok; fileFormat = fmt; }
        }
    }

    private LocalDate parseDate(String dateStr) {
        if (fileFormat != null) {
            try { return LocalDate.parse(cleanDate(dateStr), fileFormat); } catch (Exception ignored) {}
        }
        // Quicken pads single digits with spaces ("1/ 5'24")
        dateStr = dateStr.replace(" ", "");
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
