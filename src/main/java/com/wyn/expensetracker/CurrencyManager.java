package com.wyn.expensetracker;

import java.util.*;

public class CurrencyManager {

    public static final LinkedHashMap<String, String> CURRENCIES = new LinkedHashMap<>();

    static {
        CURRENCIES.put("ZAR", "R");
        CURRENCIES.put("USD", "$");
        CURRENCIES.put("EUR", "\u20AC");
        CURRENCIES.put("GBP", "\u00A3");
        CURRENCIES.put("JPY", "\u00A5");
        CURRENCIES.put("CHF", "CHF");
        CURRENCIES.put("AUD", "A$");
        CURRENCIES.put("CAD", "C$");
        CURRENCIES.put("SEK", "kr");
        CURRENCIES.put("NOK", "kr");
        CURRENCIES.put("DKK", "kr");
        CURRENCIES.put("INR", "\u20B9");
        CURRENCIES.put("BRL", "R$");
        CURRENCIES.put("CNY", "\u00A5");
        CURRENCIES.put("KRW", "\u20A9");
        CURRENCIES.put("MXN", "Mex$");
        CURRENCIES.put("NZD", "NZ$");
        CURRENCIES.put("SGD", "S$");
        CURRENCIES.put("HKD", "HK$");
        CURRENCIES.put("PLN", "z\u0142");
    }

    private String baseCurrency = "ZAR";
    private final Map<String, Double> exchangeRates = new LinkedHashMap<>();

    public String getBaseCurrency() {
        return baseCurrency;
    }

    public void setBaseCurrency(String baseCurrency) {
        this.baseCurrency = baseCurrency;
    }

    public Map<String, Double> getExchangeRates() {
        return exchangeRates;
    }

    public void setExchangeRates(Map<String, Double> rates) {
        exchangeRates.clear();
        exchangeRates.putAll(rates);
    }

    public double getRate(String fromCurrency) {
        if (fromCurrency == null || fromCurrency.equals(baseCurrency)) return 1.0;
        return exchangeRates.getOrDefault(fromCurrency, 1.0);
    }

    /**
     * Returns true if a conversion rate is configured for the given currency. When this is
     * false, {@link #getRate}/{@link #toBase} fall back to 1.0 — callers that display
     * converted totals should use this (or {@link #missingRates}) to warn the user.
     */
    public boolean hasRate(String currency) {
        return currency == null || currency.equals(baseCurrency) || exchangeRates.containsKey(currency);
    }

    /** Currency codes used by the given expenses that have no configured rate (sorted). */
    public Set<String> missingRates(Collection<? extends Expense> expenses) {
        Set<String> missing = new TreeSet<>();
        if (expenses == null) return missing;
        for (Expense e : expenses) {
            if (e != null && !hasRate(e.getCurrency())) missing.add(e.getCurrency());
        }
        return missing;
    }

    /**
     * Re-expresses rates stored relative to {@code oldBase} (1 X = rate(X) oldBase) relative
     * to {@code newBase}: rate_new(X) = rate_old(X) / rate_old(newBase), and
     * rate_new(oldBase) = 1 / rate_old(newBase). Returns null if the conversion is impossible
     * because there is no valid rate for {@code newBase}. An empty input converts to empty.
     */
    public static Map<String, Double> convertRates(Map<String, Double> rates, String oldBase, String newBase) {
        Map<String, Double> result = new LinkedHashMap<>();
        if (rates == null || rates.isEmpty()) return result;
        if (oldBase == null || newBase == null || oldBase.equals(newBase)) {
            result.putAll(rates);
            return result;
        }
        Double pivot = rates.get(newBase);
        if (pivot == null || pivot <= 0 || pivot.isNaN() || pivot.isInfinite()) return null;
        for (Map.Entry<String, Double> e : rates.entrySet()) {
            if (e.getKey().equals(newBase) || e.getKey().equals(oldBase)) continue;
            result.put(e.getKey(), e.getValue() / pivot);
        }
        result.put(oldBase, 1.0 / pivot);
        return result;
    }

    /**
     * Switches the base currency and converts the stored rates to it. Returns true if the
     * rates were converted (or there were none); false if they could not be converted and
     * were cleared — the caller should tell the user to re-enter them.
     */
    public boolean changeBaseCurrency(String newBase) {
        if (newBase == null || newBase.equals(baseCurrency)) return true;
        Map<String, Double> converted = convertRates(exchangeRates, baseCurrency, newBase);
        baseCurrency = newBase;
        exchangeRates.clear();
        if (converted == null) return false;
        exchangeRates.putAll(converted);
        return true;
    }

    /** Convert an amount from its currency to the base currency. */
    public double toBase(double amount, String fromCurrency) {
        return amount * getRate(fromCurrency);
    }

    /** Get the symbol for a currency code. */
    public static String getSymbol(String code) {
        if (code == null) return "";
        return CURRENCIES.getOrDefault(code, code);
    }

    /** Format amount with the given currency code's symbol. */
    public static String fmt(double amount, String currencyCode) {
        String symbol = getSymbol(currencyCode);
        return symbol + String.format("%.2f", amount);
    }

    /** Get display string for combo boxes: "ZAR (R)" */
    public static String getDisplayName(String code) {
        String symbol = CURRENCIES.get(code);
        if (symbol == null) return code;
        if (symbol.equals(code)) return code;
        return code + " (" + symbol + ")";
    }

    public static List<String> getCurrencyCodes() {
        return new ArrayList<>(CURRENCIES.keySet());
    }

    /** Resolve the effective currency: expense currency if set, otherwise base. */
    public String resolveExpenseCurrency(Expense expense) {
        return expense.getCurrency() != null ? expense.getCurrency() : baseCurrency;
    }
}
