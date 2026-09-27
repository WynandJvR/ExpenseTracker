package com.wyn.expensetracker;

/**
 * Parses money amounts as they appear on bank statements and exports:
 * "1,234.56", "1 234,56", "-12,50", "(45.00)", "R 99.90", "300.00Cr", "12.00 Dr".
 */
public final class Amounts {

    private Amounts() {}

    /**
     * Returns the signed value, or null if the text is not an amount. A trailing
     * "Cr" is positive, "Dr" negative; parentheses mean negative. When both '.' and
     * ',' appear, the last one is the decimal separator. A lone ',' followed by one or
     * two digits is treated as a decimal comma, otherwise as a thousands separator.
     */
    public static Double parse(String raw) {
        if (raw == null) return null;
        String s = raw.trim().replace("\"", "").replace('\u00A0', ' ');
        if (s.isEmpty()) return null;

        boolean negative = false;
        String lower = s.toLowerCase();
        if (lower.endsWith("cr")) {
            s = s.substring(0, s.length() - 2).trim();
        } else if (lower.endsWith("dr")) {
            s = s.substring(0, s.length() - 2).trim();
            negative = true;
        }
        // Currency symbols/codes before the number ("R -50.00", "ZAR 12") go first so the sign is seen.
        s = s.replaceFirst("^[^0-9+\\-(.,]+", "").trim();
        if (s.startsWith("(") && s.endsWith(")")) {
            negative = !negative;
            s = s.substring(1, s.length() - 1).trim();
        }
        if (s.endsWith("-")) { // "12.00-" style
            negative = !negative;
            s = s.substring(0, s.length() - 1).trim();
        }
        if (s.startsWith("-")) {
            negative = !negative;
            s = s.substring(1).trim();
        } else if (s.startsWith("+")) {
            s = s.substring(1).trim();
        }
        s = s.replaceFirst("^[^0-9.,]+", "").trim(); // "-R50.00"
        // Drop currency symbols/codes and spaces used as thousands separators.
        s = s.replaceAll("[^0-9.,]", "");
        if (s.isEmpty() || !s.matches(".*\\d.*")) return null;

        int lastDot = s.lastIndexOf('.');
        int lastComma = s.lastIndexOf(',');
        if (lastDot >= 0 && lastComma >= 0) {
            if (lastComma > lastDot) {
                s = s.replace(".", "").replace(',', '.');
            } else {
                s = s.replace(",", "");
            }
        } else if (lastComma >= 0) {
            // One or two digits after a single comma is a decimal ("12,5" as Excel shows 12,50).
            int decimals = s.length() - lastComma - 1;
            boolean decimalComma = s.indexOf(',') == lastComma && decimals >= 1 && decimals <= 2;
            s = decimalComma ? s.replace(',', '.') : s.replace(",", "");
        } else if (lastDot >= 0 && s.indexOf('.') != lastDot) {
            // "1.234.567" — dots as thousands separators
            s = s.replace(".", "");
        }
        try {
            double v = Double.parseDouble(s);
            return negative ? -v : v;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Rounds to cents, avoiding binary floating-point noise in totals. */
    public static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
