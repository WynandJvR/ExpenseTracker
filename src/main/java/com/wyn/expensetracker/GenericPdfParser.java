package com.wyn.expensetracker;

import java.time.LocalDate;
import java.time.Month;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.regex.MatchResult;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class GenericPdfParser implements BankStatementParser {

    // Date patterns found in bank statements. '-' separators are read as '/'. Whether
    // "03/04/2024" is 3 April or 4 March is decided once per statement (detectDayFirst).
    private static final DateTimeFormatter[] YEAR_FIRST = { DateTimeFormatter.ofPattern("yyyy/M/d", Locale.ENGLISH) };
    private static final DateTimeFormatter[] DAY_FIRST = {
        DateTimeFormatter.ofPattern("d/M/yyyy", Locale.ENGLISH),
        DateTimeFormatter.ofPattern("d/M/yy", Locale.ENGLISH),
    };
    private static final DateTimeFormatter[] MONTH_FIRST = {
        DateTimeFormatter.ofPattern("M/d/yyyy", Locale.ENGLISH),
        DateTimeFormatter.ofPattern("M/d/yy", Locale.ENGLISH),
    };
    private static final DateTimeFormatter[] NAMED_MONTH = {
        new DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern("d MMM yyyy").toFormatter(Locale.ENGLISH),
        new DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern("d MMMM yyyy").toFormatter(Locale.ENGLISH),
    };
    private static final Pattern NUMERIC_DATE = Pattern.compile("(\\d{1,2})/(\\d{1,2})/(\\d{2}|\\d{4})");

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
    private static final Pattern SPACE_GROUPED = Pattern.compile("-?\\d{1,3}(?: \\d{3})+\\.\\d{2}");
    private static final Pattern COMMA_GROUPED = Pattern.compile("(?<![\\d.,])\\d{1,3}(?:,\\d{3})+\\.\\d{2}(?!\\d)");
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

        boolean dayFirst = detectDayFirst(text);
        // Without a balance to check, "1 500.00" is read whole unless the statement is known to
        // group thousands with commas (then the "1" ends the description).
        boolean preferWhole = usesSpaceGrouping(text) || !usesCommaGrouping(text);
        Double prevBalance = result.getOpeningBalance();
        int memo = 0;
        // Items whose date had no year of its own; their years are assigned at the end.
        Set<ImportItem> yearless = Collections.newSetFromMap(new IdentityHashMap<>());
        for (String rawLine : text.split("\\r?\\n")) {
            String line = rawLine.trim();
            if (line.isEmpty() || SKIP_LINE.matcher(line).find()) continue;

            LocalDate date = extractDate(line, inferredYear, dayFirst);
            if (date == null) continue;
            Matcher fm = FULL_DATE.matcher(line);
            boolean hasYear = fm.find() && parseFullDate(fm.group(1), dayFirst) != null;

            // Only look for amounts after the date so day/month digits aren't mistaken for money.
            String afterDate = line;
            Matcher dm = FULL_DATE.matcher(line);
            if (dm.find()) {
                afterDate = line.substring(dm.end());
            } else {
                Matcher sm = SHORT_DATE.matcher(line);
                if (sm.find()) afterDate = line.substring(sm.end());
            }

            List<MatchResult> amounts = new ArrayList<>();
            Matcher m = SIGNED_AMOUNT.matcher(afterDate);
            while (m.find()) {
                Double v = signedValue(m.group(1), m.group(2));
                if (v != null && Math.abs(v) < 10_000_000) amounts.add(m.toMatchResult());
            }
            if (amounts.isEmpty()) continue;

            MatchResult firstMatch = amounts.get(0);
            MatchResult lastMatch = amounts.get(amounts.size() - 1);
            Double balance = amounts.size() >= 2 ? Amounts.round2(signedValue(lastMatch.group(1), lastMatch.group(2))) : null;
            // "Uber trip 2 150.00" also matches as "2 150.00": try where the amount could start,
            // and take the reading that moves the balance correctly when there is one.
            List<Integer> starts = amountStarts(firstMatch.group(1), preferWhole);
            int start = starts.get(0);
            if (prevBalance != null && balance != null) {
                for (int st : starts) {
                    double a = Amounts.round2(Math.abs(signedValue(firstMatch.group(1).substring(st), firstMatch.group(2))));
                    if (Math.abs(Math.abs(prevBalance - balance) - a) < 0.005) {
                        start = st;
                        break;
                    }
                }
            }
            double first = signedValue(firstMatch.group(1).substring(start), firstMatch.group(2));
            boolean explicitMarker = firstMatch.group(2) != null || first < 0;
            double amount = Amounts.round2(Math.abs(first));
            if (amount <= 0) continue;

            boolean credit;
            if (explicitMarker) {
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

            // The description is what's left once the amounts actually read are cut out.
            StringBuilder rest = new StringBuilder(afterDate);
            for (int k = amounts.size() - 1; k >= 0; k--) {
                MatchResult r = amounts.get(k);
                rest.replace(k == 0 ? r.start(1) + start : r.start(), r.end(), " ");
            }
            String description = extractDescription(line.substring(0, line.length() - afterDate.length()) + rest);
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
        boolean dayFirst = detectDayFirst(text);
        Matcher m = FULL_DATE.matcher(text);
        while (m.find()) {
            LocalDate d = parseFullDate(m.group(1), dayFirst);
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

    static LocalDate parseFullDate(String s, boolean dayFirst) {
        String n = s.trim().replace('-', '/').replaceAll("\\s+", " ");
        DateTimeFormatter[] formats = n.contains(" ") ? NAMED_MONTH
            : n.matches("\\d{4}/.*") ? YEAR_FIRST : dayFirst ? DAY_FIRST : MONTH_FIRST;
        for (DateTimeFormatter fmt : formats) {
            try {
                return LocalDate.parse(n, fmt);
            } catch (DateTimeParseException ignored) {
                // try next
            }
        }
        return null;
    }

    /**
     * Day/month order for the whole statement: a first number above 12 ("15/03/2024") proves
     * day-first, a second one above 12 ("03/15/2024") month-first. Day-first unless the
     * month-first evidence wins, so every date on one statement is read the same way.
     */
    static boolean detectDayFirst(String text) {
        int dayFirst = 0, monthFirst = 0;
        Matcher m = FULL_DATE.matcher(text);
        while (m.find()) {
            Matcher d = NUMERIC_DATE.matcher(m.group(1).replace('-', '/'));
            if (!d.matches()) continue;
            int a = Integer.parseInt(d.group(1)), b = Integer.parseInt(d.group(2));
            if (a > 12 && b <= 12) dayFirst++;
            else if (b > 12 && a <= 12) monthFirst++;
        }
        return monthFirst <= dayFirst;
    }

    /**
     * Whether this statement groups thousands with spaces ("1 000.00"). Only trusted when it's
     * unambiguous: an opening/closing balance, or an amount printed right after another one
     * (a description's trailing digits can't be glued onto that).
     */
    static boolean usesSpaceGrouping(String text) {
        for (Pattern p : new Pattern[]{OPENING, CLOSING}) {
            Matcher m = p.matcher(text);
            while (m.find()) {
                if (SPACE_GROUPED.matcher(m.group(1).trim()).matches()) return true;
            }
        }
        for (String line : text.split("\\r?\\n")) {
            Matcher m = SIGNED_AMOUNT.matcher(line);
            boolean afterAmount = false;
            while (m.find()) {
                if (afterAmount && m.group(1).contains(" ")) return true;
                afterAmount = true;
            }
        }
        return false;
    }

    /** Whether this statement groups thousands with commas ("1,234.56") anywhere. */
    static boolean usesCommaGrouping(String text) {
        return COMMA_GROUPED.matcher(text).find();
    }

    /**
     * Where in a matched number like "2 150.00" the amount may start: 0 (the whole thing,
     * 2150.00) or after a space (150.00, the "2" ending the description). The likelier
     * reading comes first: the whole number unless {@code preferWhole} is false.
     */
    private static List<Integer> amountStarts(String number, boolean preferWhole) {
        List<Integer> starts = new ArrayList<>();
        starts.add(0);
        for (int i = 0; i < number.length(); i++) {
            if (number.charAt(i) == ' ') starts.add(i + 1);
        }
        if (!preferWhole) Collections.reverse(starts);
        return starts;
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

    private LocalDate extractDate(String line, int fallbackYear, boolean dayFirst) {
        // Try full date formats first
        Matcher fullMatch = FULL_DATE.matcher(line);
        if (fullMatch.find()) {
            LocalDate d = parseFullDate(fullMatch.group(1), dayFirst);
            if (d != null) return d;
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

        // Remove any amounts left (same pattern the amounts are read with, so no stray digits remain)
        String noAmounts = SIGNED_AMOUNT.matcher(noDate).replaceAll("").trim();

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
