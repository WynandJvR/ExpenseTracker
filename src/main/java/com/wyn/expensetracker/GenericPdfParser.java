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
        DateTimeFormatter.ofPattern("dd/MM/yyyy", Locale.ENGLISH),
        DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.ENGLISH),
        DateTimeFormatter.ofPattern("dd-MM-yyyy", Locale.ENGLISH),
        DateTimeFormatter.ofPattern("MM/dd/yyyy", Locale.ENGLISH),
        DateTimeFormatter.ofPattern("yyyy/MM/dd", Locale.ENGLISH),
        DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.ENGLISH),
        DateTimeFormatter.ofPattern("dd MMMM yyyy", Locale.ENGLISH),
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
        return parseStatement(text).getItems();
    }

    private static final Pattern SIGNED_AMOUNT = Pattern.compile(
        "(?<![\\d.])(-?\\d{1,3}(?:[, ]\\d{3})*\\.\\d{2}|-?\\d+\\.\\d{2})\\s?(Cr|Dr|CR|DR|-)?(?![\\d])");
    private static final Pattern OPENING = Pattern.compile(
        "(?i)(?:opening|previous|brought forward)\\s+balance[^\\d-]*(-?[\\d, ]+\\.\\d{2})\\s?(Cr|Dr)?");
    private static final Pattern CLOSING = Pattern.compile(
        "(?i)(?:closing|current|carried forward)\\s+balance[^\\d-]*(-?[\\d, ]+\\.\\d{2})\\s?(Cr|Dr)?");

    /**
     * Reads "date … description … amount … balance" lines. When the statement prints a
     * running balance, each line is checked against the previous one to decide whether
     * money came in or went out (and to skip lines that don't move the balance);
     * otherwise a minus sign, a "Cr"/"Dr" marker next to the amount, or wording decides.
     */
    public StatementParseResult parseStatement(String text) {
        List<ImportItem> items = new ArrayList<>();
        StatementParseResult result = new StatementParseResult(getBankName(), items);
        int inferredYear = inferYear(text);

        Matcher om = OPENING.matcher(text);
        if (om.find()) result.setOpeningBalance(signedValue(om.group(1), om.group(2)));
        Matcher cm = CLOSING.matcher(text);
        Double closing = null;
        while (cm.find()) closing = signedValue(cm.group(1), cm.group(2)); // last one wins
        result.setClosingBalance(closing);

        Double prevBalance = result.getOpeningBalance();
        int memo = 0;
        // Items whose date had no year of its own; their years are assigned at the end.
        Set<ImportItem> yearless = Collections.newSetFromMap(new IdentityHashMap<>());
        for (String rawLine : text.split("\\r?\\n")) {
            String line = rawLine.trim();
            if (line.isEmpty() || SKIP_LINE.matcher(line).find()) continue;

            LocalDate date = extractDate(line, inferredYear);
            if (date == null) continue;
            boolean hasYear = FULL_DATE.matcher(line).find();

            // Only look for amounts after the date so day/month digits aren't mistaken for money.
            String afterDate = line;
            Matcher dm = FULL_DATE.matcher(line);
            if (dm.find()) {
                afterDate = line.substring(dm.end());
            } else {
                Matcher sm = SHORT_DATE.matcher(line);
                if (sm.find()) afterDate = line.substring(sm.end());
            }

            List<double[]> amounts = new ArrayList<>(); // {signedValue, hasExplicitMarker}
            Matcher m = SIGNED_AMOUNT.matcher(afterDate);
            while (m.find()) {
                Double v = signedValue(m.group(1), m.group(2));
                if (v != null && Math.abs(v) < 10_000_000) amounts.add(new double[]{v, m.group(2) != null || v < 0 ? 1 : 0});
            }
            if (amounts.isEmpty()) continue;

            double first = amounts.get(0)[0];
            double amount = Amounts.round2(Math.abs(first));
            if (amount <= 0) continue;
            Double balance = amounts.size() >= 2 ? Amounts.round2(amounts.get(amounts.size() - 1)[0]) : null;

            boolean credit;
            if (amounts.get(0)[1] == 1) {
                credit = first > 0;
            } else {
                credit = CREDIT_INDICATOR.matcher(line).find();
            }
            if (prevBalance != null && balance != null) {
                if (Math.abs(prevBalance + amount - balance) < 0.005) credit = true;
                else if (Math.abs(prevBalance - amount - balance) < 0.005) credit = false;
                else if (Math.abs(prevBalance - balance) < 0.005) {
                    memo++;
                    continue;
                }
            }
            if (balance != null) prevBalance = balance;

            String description = extractDescription(line);
            if (description.isEmpty()) description = "Bank transaction";
            ImportItem item = new ImportItem(amount, description, date);
            item.setCredit(credit);
            item.setBalance(balance);
            items.add(item);
            if (!hasYear) yearless.add(item);
        }
        result.setSkippedMemoLines(memo);
        assignYears(yearless, statementEnd(text, yearless, inferredYear));
        return result;
    }

    /**
     * The date the statement runs up to. A full date printed anywhere (statement date,
     * period end, dated lines) wins — the latest one that isn't in the future. Otherwise:
     * the yearless "dd Mon" dates cover less than a year, so the biggest gap between them
     * (going round the calendar) is where the period starts; the date just before that gap
     * is the end, placed in the year from the header (and never after today).
     */
    static LocalDate statementEnd(String text, Collection<ImportItem> yearless, int inferredYear) {
        LocalDate today = LocalDate.now();
        LocalDate latest = null;
        Matcher m = FULL_DATE.matcher(text);
        while (m.find()) {
            LocalDate d = parseFullDate(m.group(1));
            if (d != null && !d.isAfter(today.plusDays(7)) && (latest == null || d.isAfter(latest))) latest = d;
        }
        if (latest != null) return latest;
        if (yearless.isEmpty()) return today;

        List<Integer> days = new ArrayList<>();
        for (ImportItem i : yearless) days.add(i.getDate().getDayOfYear());
        Collections.sort(days);
        int endDay = days.get(days.size() - 1);
        int biggestGap = 366 - days.get(days.size() - 1) + days.get(0); // wrap-around gap
        for (int k = 1; k < days.size(); k++) {
            int gap = days.get(k) - days.get(k - 1);
            if (gap > biggestGap) {
                biggestGap = gap;
                endDay = days.get(k - 1);
            }
        }
        LocalDate end = LocalDate.ofYearDay(inferredYear, Math.min(endDay, LocalDate.of(inferredYear, 12, 31).getDayOfYear()));
        while (end.isAfter(today.plusDays(7))) end = end.minusYears(1);
        return end;
    }

    /** Gives each yearless date the latest year that doesn't put it after the statement end. */
    static void assignYears(Collection<ImportItem> yearless, LocalDate end) {
        LocalDate limit = end.plusDays(7);
        for (ImportItem i : yearless) {
            LocalDate d = i.getDate();
            LocalDate candidate = d.withYear(limit.getYear());
            if (candidate.isAfter(limit)) candidate = candidate.minusYears(1);
            i.setDate(candidate);
        }
    }

    private static LocalDate parseFullDate(String s) {
        for (DateTimeFormatter fmt : DATE_FORMATS) {
            try {
                return LocalDate.parse(s, fmt);
            } catch (DateTimeParseException ignored) {
                // try next
            }
        }
        return null;
    }

    private static Double signedValue(String number, String marker) {
        Double v = Amounts.parse(number.replace(" ", ""));
        if (v == null) return null;
        if (marker != null && (marker.equalsIgnoreCase("Dr") || marker.equals("-"))) v = -Math.abs(v);
        return v;
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
