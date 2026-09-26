package com.wyn.expensetracker;

import java.time.LocalDate;
import java.time.Month;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses FNB (First National Bank, South Africa) PDF statements.
 *
 * FNB prints every line as "dd Mon  description  amount[Cr]  balance[Cr]  [accrued fee]".
 * A balance without "Cr" is overdrawn. Because every line carries the running balance,
 * each transaction is checked against the previous balance: that decides its direction
 * exactly and filters out memo lines that don't move money (e.g. "Edo Collection
 * Attempt ... Cr", a notice of a failed debit order).
 */
public class FnbPdfParser implements BankStatementParser {

    private static final String MONTHS = "(Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)";
    private static final String MONEY = "([\\d,]+\\.\\d{2})\\s?(Cr|Dr)?";

    private static final Pattern STATEMENT_PERIOD = Pattern.compile(
        "Statement\\s+Period\\s*:\\s*(\\d{1,2})\\s+(\\w+)\\s+(\\d{4})\\s+to\\s+(\\d{1,2})\\s+(\\w+)\\s+(\\d{4})",
        Pattern.CASE_INSENSITIVE);
    private static final Pattern OPENING = Pattern.compile("Opening\\s+Balance\\s+" + MONEY, Pattern.CASE_INSENSITIVE);
    private static final Pattern CLOSING = Pattern.compile("Closing\\s+Balance\\s+" + MONEY, Pattern.CASE_INSENSITIVE);
    private static final Pattern ACCOUNT = Pattern.compile(
        "^([A-Za-z][A-Za-z ]{2,40}?)\\s*:\\s*(\\d{8,})\\s*$", Pattern.MULTILINE);

    /** "dd Mon description amount balance ..." — description is lazy so the first amount+balance pair wins. */
    private static final Pattern TRANSACTION_LINE = Pattern.compile(
        "^(\\d{1,2})\\s+" + MONTHS + "\\s+(.+?)\\s+" + MONEY + "\\s+" + MONEY + "(?:\\s.*)?$",
        Pattern.CASE_INSENSITIVE);

    /** Accrued bank-charge lines have no description: "dd Mon amount balance". */
    private static final Pattern FEE_LINE = Pattern.compile(
        "^(\\d{1,2})\\s+" + MONTHS + "\\s+" + MONEY + "\\s+" + MONEY + "(?:\\s.*)?$",
        Pattern.CASE_INSENSITIVE);

    private static final Pattern CARD_REF = Pattern.compile("\\s*\\d{6}\\*\\d{4}(\\s+\\d{1,2}\\s+" + MONTHS + ")?", Pattern.CASE_INSENSITIVE);

    private static final Map<String, Month> MONTH_MAP = new HashMap<>();
    static {
        for (Month m : Month.values()) {
            String full = m.name().toLowerCase();
            MONTH_MAP.put(full.substring(0, 3), m);
            MONTH_MAP.put(full, m);
        }
    }

    @Override
    public boolean canParse(String text) {
        String lower = text.toLowerCase();
        return lower.contains("first national bank") || lower.contains("fnb.co.za")
            || (lower.contains("fnb") && lower.contains("statement period"));
    }

    @Override
    public String getBankName() {
        return "FNB";
    }

    @Override
    public List<ImportItem> parse(String text) {
        return parseStatement(text).getItems();
    }

    public StatementParseResult parseStatement(String text) {
        List<ImportItem> items = new ArrayList<>();
        StatementParseResult result = new StatementParseResult(getBankName(), items);
        result.setCurrency("ZAR");

        int startYear = LocalDate.now().getYear();
        int endYear = startYear;
        boolean periodKnown = false;
        Matcher pm = STATEMENT_PERIOD.matcher(text);
        if (pm.find()) {
            periodKnown = true;
            startYear = Integer.parseInt(pm.group(3));
            endYear = Integer.parseInt(pm.group(6));
            result.setPeriodStart(date(pm.group(1), pm.group(2), pm.group(3)));
            result.setPeriodEnd(date(pm.group(4), pm.group(5), pm.group(6)));
        }
        Matcher om = OPENING.matcher(text);
        if (om.find()) result.setOpeningBalance(signed(om.group(1), om.group(2)));
        Matcher cm = CLOSING.matcher(text);
        if (cm.find()) result.setClosingBalance(signed(cm.group(1), cm.group(2)));
        Matcher am = ACCOUNT.matcher(text);
        while (am.find()) {
            String name = am.group(1).trim();
            if (name.toLowerCase().contains("account")) {
                String number = am.group(2);
                result.setAccountLabel(name + " ••" + number.substring(number.length() - 4));
                break;
            }
        }

        Double prevBalance = result.getOpeningBalance();
        Month previousMonth = null;
        int currentYear = startYear;
        int memo = 0;

        for (String rawLine : text.split("\\r?\\n")) {
            String line = rawLine.trim();
            if (line.isEmpty()) continue;

            String dayStr, monStr, description, amountStr, amountSuffix, balStr, balSuffix;
            boolean isFeeLine = false;
            Matcher m = TRANSACTION_LINE.matcher(line);
            if (m.matches() && !m.group(3).matches("[\\d,.\\s]+(Cr|Dr)?")) {
                dayStr = m.group(1); monStr = m.group(2); description = m.group(3).trim();
                amountStr = m.group(4); amountSuffix = m.group(5);
                balStr = m.group(6); balSuffix = m.group(7);
            } else {
                Matcher f = FEE_LINE.matcher(line);
                if (!f.matches()) continue;
                dayStr = f.group(1); monStr = f.group(2); description = "Bank charges";
                amountStr = f.group(3); amountSuffix = f.group(4);
                balStr = f.group(5); balSuffix = f.group(6);
                isFeeLine = true;
            }

            Month month = MONTH_MAP.get(monStr.toLowerCase());
            double amount = Amounts.round2(Double.parseDouble(amountStr.replace(",", "")));
            // FNB shows a balance without "Cr" when the account is overdrawn.
            double balance = Double.parseDouble(balStr.replace(",", ""));
            if (!"Cr".equalsIgnoreCase(balSuffix)) balance = -balance;
            balance = Amounts.round2(balance);

            currentYear = resolveYear(month, previousMonth, currentYear, endYear);
            previousMonth = month;
            if (amount <= 0) continue; // informational lines such as "Cr.int.rate ... 0.00"

            boolean credit = "Cr".equalsIgnoreCase(amountSuffix);
            if (prevBalance != null) {
                if (Math.abs(prevBalance + amount - balance) < 0.005) {
                    credit = true;
                } else if (Math.abs(prevBalance - amount - balance) < 0.005) {
                    credit = false;
                } else if (Math.abs(prevBalance - balance) < 0.005) {
                    memo++; // balance unchanged: the line didn't move any money
                    continue;
                }
                // otherwise trust the printed Cr marker (e.g. text extraction reordered lines)
            }
            prevBalance = balance;

            LocalDate date;
            try {
                date = LocalDate.of(currentYear, month, Integer.parseInt(dayStr));
            } catch (Exception e) {
                continue;
            }

            ImportItem item = new ImportItem(amount, isFeeLine ? description : cleanDescription(description), date);
            item.setCredit(credit);
            item.setBalance(balance);
            if (isFeeLine) item.setCategory(TransactionClassifier.BANK_FEES);
            items.add(item);
        }
        result.setSkippedMemoLines(memo);
        if (!periodKnown) {
            // No period printed: dates were guessed in the current year. Anything that would
            // land in the future really belongs to last year (e.g. December lines imported in January).
            LocalDate today = LocalDate.now();
            for (ImportItem item : items) {
                if (item.getDate().isAfter(today)) item.setDate(item.getDate().minusYears(1));
            }
        }
        return result;
    }

    /** Removes card numbers and the card-transaction date FNB appends to POS lines. */
    static String cleanDescription(String description) {
        String s = CARD_REF.matcher(description).replaceAll("");
        s = s.replaceAll("\\s{2,}", " ").trim();
        return s.isEmpty() ? "Card purchase" : s;
    }

    /** Same convention as the transaction lines: without "Cr" the balance is overdrawn. */
    private static Double signed(String amount, String suffix) {
        double v = Double.parseDouble(amount.replace(",", ""));
        return "Cr".equalsIgnoreCase(suffix) ? v : -v;
    }

    private static LocalDate date(String day, String month, String year) {
        Month m = MONTH_MAP.get(month.toLowerCase());
        if (m == null) return null;
        try {
            return LocalDate.of(Integer.parseInt(year), m, Integer.parseInt(day));
        } catch (Exception e) {
            return null;
        }
    }

    private static int resolveYear(Month currentMonth, Month previousMonth, int currentYear, int endYear) {
        if (previousMonth != null && currentMonth.getValue() < previousMonth.getValue() && currentYear < endYear) {
            return currentYear + 1;
        }
        return currentYear;
    }
}
