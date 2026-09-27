package com.wyn.expensetracker;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;

public class CsvStatementParser {

    public static final String[] DATE_FORMATS = {
        "yyyy-MM-dd", "dd/MM/yyyy", "MM/dd/yyyy", "dd-MM-yyyy",
        "yyyy/MM/dd", "dd MMM yyyy", "MMM dd, yyyy", "yyyyMMdd"
    };

    /** Header of a column that only says which way the money went: "Debit/Credit", "Dr/Cr", "D/C"... */
    private static final java.util.regex.Pattern SIGN_HEADER = java.util.regex.Pattern.compile(
        "(debit|dr|d)\\s*(/|or|-)?\\s*(credit|cr|c)|(credit|cr|c)\\s*(/|or|-)?\\s*(debit|dr|d)");

    /**
     * The row the table starts on (its header): the first line that splits into the same
     * number of columns as the line after it. Skips the account/period preamble some banks
     * put above the table. Returns 0 when there's no such line.
     */
    static int findHeaderRow(String[] lines) {
        for (int h = 0; h < Math.min(lines.length - 1, 15); h++) {
            String rest = String.join("\n", Arrays.copyOfRange(lines, h, Math.min(lines.length, h + 10)));
            char d = detectDelimiter(rest);
            int n = countChar(lines[h], d);
            if (n == 0) continue;
            for (int i = h + 1; i < lines.length; i++) {
                if (lines[i].trim().isEmpty()) continue;
                if (countChar(lines[i], d) == n) return h;
                break;
            }
        }
        return 0;
    }

    public static char detectDelimiter(String text) {
        char[] candidates = {',', ';', '\t', '|'};
        String[] lines = text.split("\\r?\\n", 10);
        if (lines.length < 2) return ',';

        int bestCount = 0;
        char bestDelim = ',';

        for (char delim : candidates) {
            int firstCount = countChar(lines[0], delim);
            if (firstCount == 0) continue;

            boolean consistent = true;
            for (int i = 1; i < Math.min(lines.length, 5); i++) {
                if (lines[i].trim().isEmpty()) continue;
                if (countChar(lines[i], delim) != firstCount) {
                    consistent = false;
                    break;
                }
            }
            if (consistent && firstCount > bestCount) {
                bestCount = firstCount;
                bestDelim = delim;
            }
        }
        return bestDelim;
    }

    public static String[] parseHeaders(String headerLine, char delimiter) {
        return splitLine(headerLine, delimiter);
    }

    public static List<ImportItem> parse(String text, char delimiter, int dateCol, int amountCol,
                                          int descCol, String dateFormat, boolean negativeIsExpense) {
        return parse(text, delimiter, dateCol, amountCol, -1, descCol, -1, dateFormat, negativeIsExpense, 1);
    }

    /**
     * Parses rows using an explicit column mapping. {@code creditCol} >= 0 means the
     * file has separate debit ({@code amountCol}) and credit columns; {@code balanceCol}
     * >= 0 records the running balance for exact duplicate detection.
     */
    static List<ImportItem> parse(String text, char delimiter, int dateCol, int amountCol, int creditCol,
                                  int descCol, int balanceCol, String dateFormat,
                                  boolean negativeIsExpense, int firstDataLine) {
        return parse(text, delimiter, dateCol, amountCol, creditCol, descCol, balanceCol, -1,
            dateFormat, negativeIsExpense, firstDataLine);
    }

    /**
     * As above; {@code signCol} >= 0 is a "Debit/Credit" or "Dr/Cr" column whose "D"/"DR"/
     * "Debit" (or "C"/"CR"/"Credit") decides the direction of an unsigned amount.
     */
    static List<ImportItem> parse(String text, char delimiter, int dateCol, int amountCol, int creditCol,
                                  int descCol, int balanceCol, int signCol, String dateFormat,
                                  boolean negativeIsExpense, int firstDataLine) {
        List<ImportItem> items = new ArrayList<>();
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern(dateFormat, Locale.ENGLISH);
        String[] lines = text.split("\\r?\\n");

        for (int i = firstDataLine; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.isEmpty()) continue;

            String[] fields = splitLine(line, delimiter);
            if (fields.length <= Math.max(dateCol, amountCol)) continue;

            try {
                LocalDate date = LocalDate.parse(unquote(fields[dateCol]), formatter);

                double signed;
                if (creditCol >= 0) {
                    Double debit = Amounts.parse(field(fields, amountCol));
                    Double credit = Amounts.parse(field(fields, creditCol));
                    double d = debit != null ? Math.abs(debit) : 0;
                    double c = credit != null ? Math.abs(credit) : 0;
                    signed = c - d;
                } else {
                    Double amount = Amounts.parse(field(fields, amountCol));
                    if (amount == null) continue;
                    // negativeIsExpense: "-100" is money out. Otherwise positive values are money out.
                    signed = negativeIsExpense ? amount : -amount;
                    String marker = signCol >= 0 ? field(fields, signCol).toLowerCase(Locale.ROOT) : "";
                    if (marker.equals("d") || marker.equals("dr") || marker.equals("debit")) signed = -Math.abs(amount);
                    else if (marker.equals("c") || marker.equals("cr") || marker.equals("credit")) signed = Math.abs(amount);
                }
                double abs = Amounts.round2(Math.abs(signed));
                if (abs <= 0) continue;

                String description = descCol >= 0 ? field(fields, descCol) : "";
                ImportItem item = new ImportItem(abs, description, date);
                item.setCredit(signed > 0);
                if (balanceCol >= 0) {
                    item.setBalance(Amounts.parse(field(fields, balanceCol)));
                }
                items.add(item);
            } catch (DateTimeParseException | NumberFormatException e) {
                // Skip unparseable lines (totals, footers)
            }
        }
        return items;
    }

    /**
     * Works out the column layout by itself: finds the header row, the date,
     * description, amount (or debit + credit) and balance columns, and the date format.
     * Returns null when it can't confidently identify at least a date and an amount.
     */
    public static StatementParseResult autoParse(String text) {
        char delimiter = detectDelimiter(text);
        String[] lines = text.split("\\r?\\n");
        for (int h = 0; h < Math.min(lines.length, 15); h++) {
            String[] headers = splitLine(lines[h], delimiter);
            int date = -1, desc = -1, weakDesc = -1, amount = -1, debit = -1, credit = -1, balance = -1, sign = -1;
            for (int i = 0; i < headers.length; i++) {
                String x = unquote(headers[i]).toLowerCase();
                if (x.isEmpty()) continue;
                if (date < 0 && x.contains("date") && !x.contains("value date")) date = i;
                else if (balance < 0 && x.contains("balance")) balance = i;
                // "Debit/Credit", "Dr/Cr": says which way the "Amount" column's money went
                else if (sign < 0 && SIGN_HEADER.matcher(x).matches()) sign = i;
                else if (debit < 0 && (x.contains("debit") || x.equals("money out") || x.contains("withdrawal") || x.equals("paid out"))) debit = i;
                else if (credit < 0 && (x.contains("credit") || x.equals("money in") || x.contains("deposit") || x.equals("paid in"))) credit = i;
                else if (amount < 0 && (x.contains("amount") || x.equals("value"))) amount = i;
                else if (x.contains("type")) continue; // "Type", "Transaction Type": a code, not a description
                // A real description column beats a "Details"/"Reference" one wherever it sits.
                else if (desc < 0 && (x.contains("desc") || x.contains("narr") || x.contains("payee") || x.contains("memo"))) desc = i;
                else if (weakDesc < 0 && (x.contains("detail") || x.contains("reference") || x.contains("transaction"))) weakDesc = i;
            }
            if (desc < 0) desc = weakDesc;
            if (date < 0) {
                for (int i = 0; i < headers.length; i++) {
                    if (unquote(headers[i]).equalsIgnoreCase("date")) date = i;
                }
            }
            boolean split = debit >= 0 && credit >= 0;
            int amountCol = split ? debit : amount >= 0 ? amount : debit >= 0 ? debit : -1;
            if (date < 0 || amountCol < 0) continue;

            String format = detectDateFormat(lines, h + 1, date, delimiter);
            if (format == null) continue;

            // A lone "Debit" column holds positive money-out values.
            boolean negativeIsExpense = !(amount < 0 && debit >= 0 && !split);
            List<ImportItem> items = parse(text, delimiter, date, amountCol, split ? credit : -1,
                desc, balance, split ? -1 : sign, format, negativeIsExpense, h + 1);
            if (items.isEmpty()) continue;
            return new StatementParseResult("CSV", items);
        }
        return null;
    }

    /** The patterns {@link #detectDateFormat} chooses from (day-first first), also offered when mapping by hand. */
    static final String[] DETECTABLE_DATE_FORMATS = {"yyyy-MM-dd", "yyyy/MM/dd", "dd/MM/yyyy", "d/M/yyyy",
        "dd-MM-yyyy", "dd.MM.yyyy", "MM/dd/yyyy", "M/d/yyyy", "dd MMM yyyy", "d MMM yyyy", "dd-MMM-yyyy",
        "MMM dd, yyyy", "yyyyMMdd", "dd/MM/yy", "yyyy-MM-dd HH:mm:ss"};

    /** Picks the date pattern that parses every sample row; prefers day-first when ambiguous. */
    static String detectDateFormat(String[] lines, int from, int dateCol, char delimiter) {
        String[] candidates = DETECTABLE_DATE_FORMATS;
        List<String> samples = new ArrayList<>();
        for (int i = from; i < lines.length && samples.size() < 200; i++) {
            String[] f = splitLine(lines[i], delimiter);
            if (f.length > dateCol && !unquote(f[dateCol]).isEmpty()) samples.add(unquote(f[dateCol]));
        }
        if (samples.isEmpty()) return null;
        // The format that parses the most rows wins; candidate order (day-first) breaks ties.
        String best = null;
        int bestOk = 0;
        for (String c : candidates) {
            DateTimeFormatter fmt = DateTimeFormatter.ofPattern(c, Locale.ENGLISH);
            int ok = 0;
            for (String s : samples) {
                try {
                    LocalDate.parse(s, fmt);
                    ok++;
                } catch (DateTimeParseException ignored) {}
            }
            if (ok > bestOk) {
                best = c;
                bestOk = ok;
            }
        }
        // Allow a footer row or two to fail
        return best != null && bestOk >= samples.size() - 2 ? best : null;
    }

    private static String unquote(String s) {
        return s == null ? "" : s.trim().replaceAll("^\"|\"$", "").trim();
    }

    private static String field(String[] fields, int idx) {
        return idx >= 0 && idx < fields.length ? unquote(fields[idx]) : "";
    }

    private static String[] splitLine(String line, char delimiter) {
        List<String> parts = new ArrayList<>();
        boolean inQuotes = false;
        StringBuilder field = new StringBuilder();

        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                if (inQuotes && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    field.append('"');
                    i++;
                } else {
                    inQuotes = !inQuotes;
                }
            } else if (c == delimiter && !inQuotes) {
                parts.add(field.toString());
                field = new StringBuilder();
            } else {
                field.append(c);
            }
        }
        parts.add(field.toString());
        return parts.toArray(new String[0]);
    }

    private static int countChar(String s, char c) {
        int count = 0;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == c) count++;
        }
        return count;
    }
}
