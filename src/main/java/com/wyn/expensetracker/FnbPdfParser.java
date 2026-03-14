package com.wyn.expensetracker;

import java.time.LocalDate;
import java.time.Month;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class FnbPdfParser implements BankStatementParser {

    private static final Pattern STATEMENT_PERIOD = Pattern.compile(
        "Statement\\s+Period\\s*:\\s*\\d+\\s+\\w+\\s+(\\d{4})\\s+to\\s+\\d+\\s+\\w+\\s+(\\d{4})",
        Pattern.CASE_INSENSITIVE);

    private static final Pattern TRANSACTION_LINE = Pattern.compile(
        "^(\\d{1,2})\\s+(Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)\\s+(.+?)\\s+(-?[\\d,]+\\.\\d{2})(Cr)?\\s+(-?[\\d,]+\\.\\d{2})(Cr)?.*$",
        Pattern.CASE_INSENSITIVE);

    private static final Pattern FEE_LINE = Pattern.compile(
        "^(\\d{1,2})\\s+(Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)\\s+(-?[\\d,]+\\.\\d{2})\\s+(-?[\\d,]+\\.\\d{2})(Cr)?.*$",
        Pattern.CASE_INSENSITIVE);

    private static final Pattern CARD_NUMBER = Pattern.compile("\\d{6}\\*\\d{4}\\s+\\d{1,2}\\s+\\w{3}");

    private static final Map<String, Month> MONTH_MAP = new LinkedHashMap<>();
    static {
        MONTH_MAP.put("jan", Month.JANUARY);
        MONTH_MAP.put("feb", Month.FEBRUARY);
        MONTH_MAP.put("mar", Month.MARCH);
        MONTH_MAP.put("apr", Month.APRIL);
        MONTH_MAP.put("may", Month.MAY);
        MONTH_MAP.put("jun", Month.JUNE);
        MONTH_MAP.put("jul", Month.JULY);
        MONTH_MAP.put("aug", Month.AUGUST);
        MONTH_MAP.put("sep", Month.SEPTEMBER);
        MONTH_MAP.put("oct", Month.OCTOBER);
        MONTH_MAP.put("nov", Month.NOVEMBER);
        MONTH_MAP.put("dec", Month.DECEMBER);
    }

    private static final Set<String> SKIP_PATTERNS = Set.of(
        "opening balance", "closing balance", "statement period",
        "date", "description", "amount", "balance", "bank charges",
        "account number", "account type", "branch", "vat reg"
    );

    // Debit-side patterns: money moving to own savings pockets
    private static final List<String> DEBIT_TRANSFER_PATTERNS = List.of(
        "payment to investment"
    );

    // Credit-side patterns: money coming back from own savings pockets
    private static final List<String> CREDIT_TRANSFER_PATTERNS = List.of(
        "fnb app transfer from"
    );

    @Override
    public boolean canParse(String text) {
        String lower = text.toLowerCase();
        return lower.contains("first national bank") || lower.contains("fnb");
    }

    @Override
    public String getBankName() {
        return "FNB (First National Bank)";
    }

    @Override
    public List<ImportItem> parse(String text) {
        List<ImportItem> items = new ArrayList<>();
        int startYear = LocalDate.now().getYear();
        int endYear = startYear;

        Matcher periodMatcher = STATEMENT_PERIOD.matcher(text);
        if (periodMatcher.find()) {
            startYear = Integer.parseInt(periodMatcher.group(1));
            endYear = Integer.parseInt(periodMatcher.group(2));
        }

        String[] lines = text.split("\\r?\\n");
        Month previousMonth = null;
        int currentYear = startYear;

        for (String line : lines) {
            line = line.trim();
            if (line.isEmpty()) continue;
            if (isSkipLine(line)) continue;

            Matcher txnMatch = TRANSACTION_LINE.matcher(line);
            if (txnMatch.matches()) {
                int day = Integer.parseInt(txnMatch.group(1));
                Month month = MONTH_MAP.get(txnMatch.group(2).toLowerCase());
                String description = txnMatch.group(3).trim();
                String amountStr = txnMatch.group(4).replace(",", "");
                boolean isCredit = txnMatch.group(5) != null;

                currentYear = resolveYear(month, previousMonth, currentYear, startYear, endYear);
                previousMonth = month;

                description = CARD_NUMBER.matcher(description).replaceAll("").trim();
                description = description.replaceAll("\\s{2,}", " ");

                if (description.isEmpty()) description = "Unknown Transaction";

                double amount = Double.parseDouble(amountStr);
                if (amount <= 0) continue;

                LocalDate date;
                try {
                    date = LocalDate.of(currentYear, month, day);
                } catch (Exception e) {
                    continue;
                }

                ImportItem item = new ImportItem(amount, description, date);
                if (isCredit) {
                    if (isCreditTransfer(description)) {
                        item.setDescription("[TRANSFER] " + description);
                        item.setStatus("Transfer");
                    } else {
                        item.setDescription("[CREDIT] " + description);
                        item.setStatus("Uncategorized");
                    }
                    item.setSelected(false);
                } else if (isDebitTransfer(description)) {
                    item.setDescription("[TRANSFER] " + description);
                    item.setSelected(false);
                    item.setStatus("Transfer");
                } else {
                    item.setStatus("Uncategorized");
                }
                items.add(item);
                continue;
            }

            Matcher feeMatch = FEE_LINE.matcher(line);
            if (feeMatch.matches()) {
                int day = Integer.parseInt(feeMatch.group(1));
                Month month = MONTH_MAP.get(feeMatch.group(2).toLowerCase());
                String amountStr = feeMatch.group(3).replace(",", "");

                currentYear = resolveYear(month, previousMonth, currentYear, startYear, endYear);
                previousMonth = month;

                double amount = Double.parseDouble(amountStr);
                if (amount <= 0) continue;

                LocalDate date;
                try {
                    date = LocalDate.of(currentYear, month, day);
                } catch (Exception e) {
                    continue;
                }

                ImportItem item = new ImportItem(amount, "Bank Fee", date);
                item.setStatus("Uncategorized");
                items.add(item);
            }
        }

        return items;
    }

    private int resolveYear(Month currentMonth, Month previousMonth, int currentYear, int startYear, int endYear) {
        if (previousMonth != null && currentMonth.getValue() < previousMonth.getValue()) {
            if (currentYear < endYear) {
                return currentYear + 1;
            }
        }
        return currentYear;
    }

    private boolean isDebitTransfer(String description) {
        String lower = description.toLowerCase();
        for (String pattern : DEBIT_TRANSFER_PATTERNS) {
            if (lower.startsWith(pattern)) return true;
        }
        return false;
    }

    private boolean isCreditTransfer(String description) {
        String lower = description.toLowerCase();
        for (String pattern : CREDIT_TRANSFER_PATTERNS) {
            if (lower.startsWith(pattern)) return true;
        }
        return false;
    }

    private boolean isSkipLine(String line) {
        String lower = line.toLowerCase();
        for (String pattern : SKIP_PATTERNS) {
            if (lower.startsWith(pattern)) return true;
        }
        if (lower.matches("^page\\s+\\d+.*")) return true;
        if (lower.matches("^\\d+$")) return true;
        return false;
    }
}
