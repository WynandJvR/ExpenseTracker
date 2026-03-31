package com.wyn.expensetracker;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class OfxStatementParser implements BankStatementParser {

    // Matches both SGML-style <TAG>value and XML-style <TAG>value</TAG>
    private static final Pattern TAG_PATTERN = Pattern.compile("<(\\w+)>([^<\\r\\n]*)");
    private static final Pattern STMTTRN_START = Pattern.compile("<STMTTRN>", Pattern.CASE_INSENSITIVE);
    private static final Pattern STMTTRN_END = Pattern.compile("</STMTTRN>", Pattern.CASE_INSENSITIVE);
    private static final DateTimeFormatter OFX_DATE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");

    @Override
    public boolean canParse(String text) {
        String upper = text.toUpperCase();
        return upper.contains("<OFX>") || upper.contains("<STMTTRN>");
    }

    @Override
    public List<ImportItem> parse(String text) {
        List<ImportItem> items = new ArrayList<>();
        String[] lines = text.split("\\r?\\n");

        boolean inTransaction = false;
        String trnType = null;
        String dateStr = null;
        String amountStr = null;
        String name = null;
        String memo = null;

        for (String line : lines) {
            String trimmed = line.trim();

            if (STMTTRN_START.matcher(trimmed).find()) {
                inTransaction = true;
                trnType = null;
                dateStr = null;
                amountStr = null;
                name = null;
                memo = null;
                continue;
            }

            if (inTransaction && STMTTRN_END.matcher(trimmed).find()) {
                ImportItem item = buildItem(trnType, dateStr, amountStr, name, memo);
                if (item != null) items.add(item);
                inTransaction = false;
                continue;
            }

            if (inTransaction) {
                Matcher m = TAG_PATTERN.matcher(trimmed);
                while (m.find()) {
                    String tag = m.group(1).toUpperCase();
                    String value = m.group(2).trim();
                    switch (tag) {
                        case "TRNTYPE" -> trnType = value;
                        case "DTPOSTED" -> dateStr = value;
                        case "TRNAMT" -> amountStr = value;
                        case "NAME" -> name = value;
                        case "MEMO" -> memo = value;
                    }
                }
            }
        }

        // Handle SGML without closing tags — last transaction may not have </STMTTRN>
        if (inTransaction) {
            ImportItem item = buildItem(trnType, dateStr, amountStr, name, memo);
            if (item != null) items.add(item);
        }

        return items;
    }

    private ImportItem buildItem(String trnType, String dateStr, String amountStr, String name, String memo) {
        if (amountStr == null || dateStr == null) return null;

        try {
            double amount = Double.parseDouble(amountStr.replace(",", "").trim());
            // OFX dates: YYYYMMDD or YYYYMMDDHHMMSS with optional timezone
            String dateOnly = dateStr.length() >= 8 ? dateStr.substring(0, 8) : dateStr;
            LocalDate date = LocalDate.parse(dateOnly, OFX_DATE_FMT);

            String description = buildDescription(name, memo);

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

    private String buildDescription(String name, String memo) {
        if (name != null && memo != null && !memo.isEmpty() && !memo.equalsIgnoreCase(name)) {
            return name + " - " + memo;
        }
        return name != null ? name : (memo != null ? memo : "");
    }

    @Override
    public String getBankName() {
        return "OFX Statement";
    }
}
