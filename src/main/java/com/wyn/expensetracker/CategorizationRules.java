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
        for (Map.Entry<String, String> entry : rules.entrySet()) {
            if (lower.contains(entry.getKey().toLowerCase())) {
                return entry.getValue();
            }
        }
        return null;
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
