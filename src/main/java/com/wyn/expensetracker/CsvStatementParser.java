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
        List<ImportItem> items = new ArrayList<>();
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern(dateFormat);
        String[] lines = text.split("\\r?\\n");

        for (int i = 1; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.isEmpty()) continue;

            String[] fields = splitLine(line, delimiter);
            if (fields.length <= Math.max(dateCol, Math.max(amountCol, descCol))) continue;

            try {
                String dateStr = fields[dateCol].trim().replaceAll("^\"|\"$", "");
                LocalDate date = LocalDate.parse(dateStr, formatter);

                String amountStr = fields[amountCol].trim().replaceAll("^\"|\"$", "")
                    .replace(",", "").replace(" ", "");
                double amount = Double.parseDouble(amountStr);

                boolean isExpense;
                if (negativeIsExpense) {
                    isExpense = amount < 0;
                    amount = Math.abs(amount);
                } else {
                    isExpense = amount > 0;
                }

                if (amount <= 0) continue;

                String description = descCol >= 0 && descCol < fields.length
                    ? fields[descCol].trim().replaceAll("^\"|\"$", "")
                    : "";

                ImportItem item = new ImportItem(amount, description, date);
                if (!isExpense) {
                    item.setDescription("[CREDIT] " + description);
                    item.setSelected(false);
                }
                item.setStatus("Uncategorized");
                items.add(item);
            } catch (DateTimeParseException | NumberFormatException e) {
                // Skip unparseable lines
            }
        }
        return items;
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
