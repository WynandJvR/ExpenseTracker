package com.wyn.expensetracker;

import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;

import java.util.LinkedHashMap;
import java.util.Map;

public class CategorizationRules {
    private final Map<String, String> rules = new LinkedHashMap<>();
    private final ObservableList<RuleEntry> ruleEntries = FXCollections.observableArrayList();

    public String categorize(String description) {
        if (description == null || description.isEmpty()) return null;
        String lower = description.toLowerCase();
        String normalized = normalize(lower);
        String compact = normalized.replace(" ", "");
        for (Map.Entry<String, String> entry : rules.entrySet()) {
            String keyLower = entry.getKey().toLowerCase();
            // Try exact substring match first
            if (lower.contains(keyLower)) {
                return entry.getValue();
            }
            // Try normalized match (strips punctuation, special chars, collapses whitespace)
            String keyNormalized = normalize(keyLower);
            if (normalized.contains(keyNormalized)) {
                return entry.getValue();
            }
            // Try compact match (also strip spaces) for cases like "Mr.D" vs "Mr D"
            String keyCompact = keyNormalized.replace(" ", "");
            if (keyCompact.length() >= 3 && compact.contains(keyCompact)) {
                return entry.getValue();
            }
        }
        return null;
    }

    /**
     * Normalize a string for fuzzy matching: strip punctuation, special chars
     * like asterisks and truncation markers, collapse whitespace.
     */
    private static String normalize(String s) {
        // Remove common special chars: dots, asterisks, hyphens, underscores, slashes
        String result = s.replaceAll("[.*\\-_/\\\\,;:!?'\"()\\[\\]{}#@&+=<>|~^`]", "");
        // Collapse multiple whitespace into single space
        result = result.replaceAll("\\s+", " ").trim();
        return result;
    }

    public void addRule(String keyword, String category) {
        rules.put(keyword, category);
        ruleEntries.add(new RuleEntry(keyword, category));
    }

    public void removeRule(String keyword) {
        rules.remove(keyword);
        ruleEntries.removeIf(e -> e.getKeyword().equals(keyword));
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
            rules.put(entry.getKey(), entry.getValue());
            ruleEntries.add(new RuleEntry(entry.getKey(), entry.getValue()));
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
