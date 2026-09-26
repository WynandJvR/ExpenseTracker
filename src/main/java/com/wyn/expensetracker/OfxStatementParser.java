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

    private static final Pattern TRANSACTION_BLOCK = Pattern.compile(
        "<STMTTRN>(.*?)(?=</STMTTRN>|<STMTTRN>|</BANKTRANLIST>|$)", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    @Override
    public List<ImportItem> parse(String text) {
        // Match blocks across the whole text so minified single-line OFX works too.
        List<ImportItem> items = new ArrayList<>();
        Matcher block = TRANSACTION_BLOCK.matcher(text);
        while (block.find()) {
            String trnType = null, dateStr = null, amountStr = null, name = null, memo = null;
            Matcher m = TAG_PATTERN.matcher(block.group(1));
            while (m.find()) {
                String value = m.group(2).trim();
                switch (m.group(1).toUpperCase()) {
                    case "TRNTYPE" -> trnType = value;
                    case "DTPOSTED" -> dateStr = value;
                    case "TRNAMT" -> amountStr = value;
                    case "NAME" -> name = value;
                    case "MEMO" -> memo = value;
                }
            }
            ImportItem item = buildItem(trnType, dateStr, amountStr, name, memo);
            if (item != null) items.add(item);
        }
        return items;
    }

    private ImportItem buildItem(String trnType, String dateStr, String amountStr, String name, String memo) {
        if (amountStr == null || dateStr == null) return null;

        try {
            Double parsed = Amounts.parse(amountStr);
            if (parsed == null) return null;
            double amount = parsed;
            // OFX dates: YYYYMMDD or YYYYMMDDHHMMSS with optional timezone
            String dateOnly = dateStr.length() >= 8 ? dateStr.substring(0, 8) : dateStr;
            LocalDate date = LocalDate.parse(dateOnly, OFX_DATE_FMT);

            String description = buildDescription(name, memo);

            double absAmount = Amounts.round2(Math.abs(amount));
            if (absAmount <= 0) return null;

            ImportItem item = new ImportItem(absAmount, description, date);
            item.setCredit(amount > 0);
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
