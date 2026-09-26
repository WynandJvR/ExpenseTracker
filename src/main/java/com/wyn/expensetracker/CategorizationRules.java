package com.wyn.expensetracker;

import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class CategorizationRules {
    private final Map<String, String> rules = new LinkedHashMap<>();
    private final ObservableList<RuleEntry> ruleEntries = FXCollections.observableArrayList();

    /**
     * Returns the category of the most specific matching rule (longest keyword wins,
     * so "uber eats" beats "uber"), or null. Keywords match whole words only, so a
     * rule for "milk" doesn't fire on "Milkwood". Multi-word keywords also match with
     * the spaces removed ("mr d" matches "MrD").
     */
    public String categorize(String description) {
        return categorize(description, c -> true);
    }

    /** As {@link #categorize(String)}, considering only rules whose category passes {@code accept}. */
    public String categorize(String description, java.util.function.Predicate<String> accept) {
        if (description == null || description.isEmpty()) return null;
        String lower = description.toLowerCase();
        String normalized = " " + normalize(lower) + " ";
        List<Map.Entry<String, String>> ordered = new ArrayList<>(rules.entrySet());
        ordered.sort((a, b) -> b.getKey().length() - a.getKey().length());
        for (Map.Entry<String, String> entry : ordered) {
            String keyNormalized = normalize(entry.getKey().toLowerCase());
            if (keyNormalized.isEmpty()) continue;
            boolean matched = TransactionClassifier.indexOfWord(normalized, keyNormalized) >= 0;
            if (!matched) {
                // "mr d" also matches "MrD" and "mrd" also matches "Mr D" — but not "Mr Dlamini".
                String keyCompact = keyNormalized.replace(" ", "");
                if (keyCompact.length() >= 3) {
                    StringBuilder rx = new StringBuilder("(?<![a-z])");
                    for (int i = 0; i < keyCompact.length(); i++) {
                        if (i > 0) rx.append(" ?");
                        rx.append(java.util.regex.Pattern.quote(String.valueOf(keyCompact.charAt(i))));
                    }
                    rx.append("(?![a-z])");
                    matched = java.util.regex.Pattern.compile(rx.toString()).matcher(normalized).find();
                }
            }
            if (matched && accept.test(entry.getValue())) {
                // Don't categorize outgoing payments as Income
                if ("Income".equalsIgnoreCase(entry.getValue()) && isOutgoingDescription(lower)) {
                    continue;
                }
                return entry.getValue();
            }
        }
        return null;
    }

    /**
     * Detect descriptions that indicate outgoing money (debits), so they
     * are not incorrectly categorized as Income by keyword matches.
     */
    private static boolean isOutgoingDescription(String lowerDescription) {
        return lowerDescription.contains("payment to ")
            || lowerDescription.contains("pmt to ")
            || lowerDescription.contains("transfer to ")
            || lowerDescription.contains("betaling aan ");
    }

    /**
     * Normalize a string for fuzzy matching: strip punctuation, special chars
     * like asterisks and truncation markers, collapse whitespace.
     */
    private static String normalize(String s) {
        // Apostrophes join ("Nando's" = "Nandos"); other punctuation separates words, so
        // a rule for "netflix" matches "Netflix.Com" and "takealot" matches "TAKEALOT*ORDER".
        String result = s.replace("'", "").replaceAll("[.*\\-_/\\\\,;:!?\"()\\[\\]{}#@&+=<>|~^`]", " ");
        // Collapse multiple whitespace into single space
        result = result.replaceAll("\\s+", " ").trim();
        return result;
    }

    /** Adds a rule, replacing any existing rule for the same keyword (case-insensitive). */
    public void addRule(String keyword, String category) {
        if (keyword == null || keyword.isBlank() || category == null || category.isBlank()) return;
        String k = keyword.trim();
        removeRule(k);
        rules.put(k, category.trim());
        ruleEntries.add(new RuleEntry(k, category.trim()));
    }

    public void removeRule(String keyword) {
        if (keyword == null) return;
        rules.keySet().removeIf(k -> k.equalsIgnoreCase(keyword.trim()));
        ruleEntries.removeIf(e -> e.getKeyword().equalsIgnoreCase(keyword.trim()));
    }

    /**
     * The keyword to learn from a transaction description when the user assigns it
     * a category: the merchant name with bank boilerplate, card numbers and
     * reference codes stripped (e.g. "POS Purchase Checkers Fresh X 123456*7890"
     * becomes "Checkers Fresh"). Returns null when nothing usable remains.
     */
    public static String keywordFor(String description) {
        if (description == null) return null;
        String clean = description
            .replaceFirst("(?i)^\\[(CREDIT|TRANSFER)\\]\\s*", "")
            .replaceFirst("(?i)^(POS Purchase|Fuel Purchase|FNB App Payment (To|From)|FNB App Transfer (To|From)"
                + "|Magtape (Credit|Debit)|Payshap (Credit|Account Off-Us)|Rtc Credit|FNB OB Pmt|Internet Pmt To"
                + "|Refund Chq Card Purchase|Credit Voucher Vouch|Debit Order|DebiCheck|Payment To)(\\s+|$)", "")
            .replaceAll("\\d{6}\\*\\d{4}.*$", "")
            .trim();
        String[] words = clean.split("\\s+");
        StringBuilder keyword = new StringBuilder();
        for (String w : words) {
            if (w.isEmpty()) continue;
            if (keyword.length() > 0 && (w.matches(".*\\d{3,}.*") || w.startsWith("*"))) break;
            if (keyword.length() > 0) keyword.append(' ');
            keyword.append(w);
            if (keyword.toString().split(" ").length >= 3) break;
        }
        String result = keyword.toString().trim();
        return normalize(result.toLowerCase()).length() >= 3 ? result : null;
    }

    public Map<String, String> getRules() {
        return rules;
    }

    public ObservableList<RuleEntry> getRuleEntries() {
        return ruleEntries;
    }

    public void loadFrom(Map<String, String> loaded) {
        rules.clear();
        ruleEntries.clear();
        for (Map.Entry<String, String> entry : loaded.entrySet()) {
            addRule(entry.getKey(), entry.getValue());
        }
    }

    public static class RuleEntry {
        private final StringProperty keyword = new SimpleStringProperty();
        private final StringProperty category = new SimpleStringProperty();

        public RuleEntry(String keyword, String category) {
            this.keyword.set(keyword);
            this.category.set(category);
        }

        public String getKeyword() { return keyword.get(); }
        public StringProperty keywordProperty() { return keyword; }

        public String getCategory() { return category.get(); }
        public StringProperty categoryProperty() { return category; }
    }
}
