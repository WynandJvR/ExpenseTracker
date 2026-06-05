package com.wyn.expensetracker;

import java.time.LocalDate;

/**
 * A per-occurrence change to a recurring expense series, keyed by the owning
 * template's stable id plus the occurrence date. Keying on the template id (not
 * the template's mutable fields) means an override survives edits to the rest of
 * the series — change the rent amount and your "skip this month" still applies.
 *
 * Either the occurrence is skipped entirely, or one or more fields are overridden
 * for that single date. A null override field inherits the template's current
 * value, so unchanged fields track future edits to the series.
 */
public class OccurrenceOverride {
    private final String templateId;
    private final LocalDate date;
    private boolean skipped;
    private Double amount;       // null = inherit from template
    private String category;     // null = inherit from template
    private String description;  // null = inherit from template

    public OccurrenceOverride(String templateId, LocalDate date) {
        if (templateId == null || date == null) {
            throw new IllegalArgumentException("templateId and date are required");
        }
        this.templateId = templateId;
        this.date = date;
    }

    /** Stable lookup key for an occurrence: template id + occurrence date. */
    public static String key(String templateId, LocalDate date) {
        return templateId + "|" + date;
    }

    public String key() {
        return key(templateId, date);
    }

    public String getTemplateId() { return templateId; }
    public LocalDate getDate() { return date; }

    public boolean isSkipped() { return skipped; }
    public void setSkipped(boolean skipped) { this.skipped = skipped; }

    public Double getAmount() { return amount; }
    public void setAmount(Double amount) { this.amount = amount; }

    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    /** True when this override carries neither a skip nor any field change — safe to drop. */
    public boolean isEmpty() {
        return !skipped && amount == null && category == null && description == null;
    }
}
