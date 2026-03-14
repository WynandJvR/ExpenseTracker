package com.wyn.expensetracker;

import java.time.LocalDate;
import java.time.Month;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class GenericPdfParser implements BankStatementParser {

    // Common date patterns found in bank statements
    private static final DateTimeFormatter[] DATE_FORMATS = {
        DateTimeFormatter.ofPattern("dd/MM/yyyy"),
        DateTimeFormatter.ofPattern("yyyy-MM-dd"),
        DateTimeFormatter.ofPattern("dd-MM-yyyy"),
        DateTimeFormatter.ofPattern("MM/dd/yyyy"),
        DateTimeFormatter.ofPattern("yyyy/MM/dd"),
        DateTimeFormatter.ofPattern("dd MMM yyyy"),
        DateTimeFormatter.ofPattern("dd MMMM yyyy"),
    };

    // "DD Mon" without year (e.g. FNB-style: "27 Nov")
    private static final Pattern SHORT_DATE = Pattern.compile(
        "\\b(\\d{1,2})\\s+(Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)\\b",
        Pattern.CASE_INSENSITIVE);

    // Full date patterns
    private static final Pattern FULL_DATE = Pattern.compile(
        "(\\d{1,2}[/\\-]\\d{1,2}[/\\-]\\d{2,4}|\\d{4}[/\\-]\\d{1,2}[/\\-]\\d{1,2}|\\d{1,2}\\s+\\w{3,9}\\s+\\d{4})");

    // Amount pattern: optional minus, digits with optional commas, dot, 2 decimals
    private static final Pattern AMOUNT_PATTERN = Pattern.compile(
        "(-?\\d[\\d,]*\\.\\d{2})");

    // Credit indicators
    private static final Pattern CREDIT_INDICATOR = Pattern.compile(
        "(?i)(\\bCr\\b|\\bcredit\\b|\\brefund\\b|\\breversal\\b)");

    // Year detection from statement text
    private static final Pattern YEAR_PATTERN = Pattern.compile(
        "(?i)(?:statement|period|date).*?(20\\d{2})");

    // Lines to skip
    private static final Pattern SKIP_LINE = Pattern.compile(
        "(?i)(opening balance|closing balance|balance brought|balance carried|page\\s+\\d|" +
        "statement\\s+period|account\\s+number|account\\s+type|branch\\s+code|vat\\s+reg|" +
        "^\\s*date\\s+description|^\\s*$)");

    private static final Map<String, Month> MONTH_MAP = new LinkedHashMap<>();
    static {
        MONTH_MAP.put("jan", Month.JANUARY);  MONTH_MAP.put("feb", Month.FEBRUARY);
        MONTH_MAP.put("mar", Month.MARCH);    MONTH_MAP.put("apr", Month.APRIL);
        MONTH_MAP.put("may", Month.MAY);      MONTH_MAP.put("jun", Month.JUNE);
        MONTH_MAP.put("jul", Month.JULY);     MONTH_MAP.put("aug", Month.AUGUST);
        MONTH_MAP.put("sep", Month.SEPTEMBER); MONTH_MAP.put("oct", Month.OCTOBER);
        MONTH_MAP.put("nov", Month.NOVEMBER); MONTH_MAP.put("dec", Month.DECEMBER);
    }

    @Override
    public boolean canParse(String text) {
        // Generic parser is always the fallback — accept anything that looks like it has
        // dates and amounts on the same lines
        int transactionLines = 0;
        for (String line : text.split("\\r?\\n")) {
            if (hasDate(line) && AMOUNT_PATTERN.matcher(line).find()) {
                transactionLines++;
            }
        }
        return transactionLines >= 3;
    }

    @Override
    public String getBankName() {
        return "Generic Bank Statement";
    }

    @Override
    public List<ImportItem> parse(String text) {
        List<ImportItem> items = new ArrayList<>();
        int inferredYear = inferYear(text);

        String[] lines = text.split("\\r?\\n");
        for (String line : lines) {
            line = line.trim();
            if (line.isEmpty()) continue;
            if (SKIP_LINE.matcher(line).find()) continue;

            LocalDate date = extractDate(line, inferredYear);
            if (date == null) continue;

            List<Double> amounts = extractAmounts(line);
            if (amounts.isEmpty()) continue;

            boolean isCredit = CREDIT_INDICATOR.matcher(line).find();

            // The first amount is typically the transaction amount
            // If there are multiple, the last is usually the balance
            double amount = amounts.get(0);
            if (amount <= 0) continue;

            // Extract description: text between the date and the first amount
            String description = extractDescription(line);
            if (description.isEmpty()) {
                description = "Bank transaction";
            }

            ImportItem item = new ImportItem(amount, description, date);
            if (isCredit) {
                item.setDescription("[CREDIT] " + description);
                item.setSelected(false);
            }
            item.setStatus("Uncategorized");
            items.add(item);
        }

        return items;
    }

    private boolean hasDate(String line) {
        return FULL_DATE.matcher(line).find() || SHORT_DATE.matcher(line).find();
    }

    private LocalDate extractDate(String line, int fallbackYear) {
        // Try full date formats first
        Matcher fullMatch = FULL_DATE.matcher(line);
        if (fullMatch.find()) {
            String dateStr = fullMatch.group(1);
            for (DateTimeFormatter fmt : DATE_FORMATS) {
                try {
                    return LocalDate.parse(dateStr, fmt);
                } catch (DateTimeParseException e) {
                    // try next
                }
            }
        }

        // Try short "DD Mon" format
        Matcher shortMatch = SHORT_DATE.matcher(line);
        if (shortMatch.find()) {
            try {
                int day = Integer.parseInt(shortMatch.group(1));
                Month month = MONTH_MAP.get(shortMatch.group(2).toLowerCase());
                if (month != null) {
                    return LocalDate.of(fallbackYear, month, day);
                }
            } catch (Exception e) {
                // skip
            }
        }

        return null;
    }

    private List<Double> extractAmounts(String line) {
        List<Double> amounts = new ArrayList<>();
        Matcher m = AMOUNT_PATTERN.matcher(line);
        while (m.find()) {
            try {
                double val = Double.parseDouble(m.group(1).replace(",", ""));
                if (val > 0 && val < 10_000_000) {
                    amounts.add(val);
                }
            } catch (NumberFormatException e) {
                // skip
            }
        }
        return amounts;
    }

    private String extractDescription(String line) {
        // Remove date portion
        String noDate = FULL_DATE.matcher(line).replaceFirst("").trim();
        noDate = SHORT_DATE.matcher(noDate).replaceFirst("").trim();

        // Remove all amounts
        String noAmounts = AMOUNT_PATTERN.matcher(noDate).replaceAll("").trim();

        // Remove credit indicators
        String clean = CREDIT_INDICATOR.matcher(noAmounts).replaceAll("").trim();

        // Remove card number patterns
        clean = clean.replaceAll("\\d{6}\\*+\\d{4}", "").trim();

        // Collapse whitespace
        clean = clean.replaceAll("\\s{2,}", " ").trim();

        // Remove trailing/leading punctuation
        clean = clean.replaceAll("^[\\s,;\\-]+|[\\s,;\\-]+$", "");

        return clean;
    }

    private int inferYear(String text) {
        Matcher m = YEAR_PATTERN.matcher(text);
        if (m.find()) {
            return Integer.parseInt(m.group(1));
        }
        // Try to find any 20xx year in the first few lines
        String[] lines = text.split("\\r?\\n", 20);
        Pattern anyYear = Pattern.compile("(20\\d{2})");
        for (String line : lines) {
            Matcher ym = anyYear.matcher(line);
            if (ym.find()) {
                return Integer.parseInt(ym.group(1));
            }
        }
        return LocalDate.now().getYear();
    }
}
