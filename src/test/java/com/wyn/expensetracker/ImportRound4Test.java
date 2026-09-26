package com.wyn.expensetracker;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Round-4 audit fixes on the import side. Synthetic data only. */
class ImportRound4Test {

    private static List<ImportItem> parse(String... lines) {
        return new GenericPdfParser().parseStatement(String.join("\n", lines)).getItems();
    }

    // I-NEW-3: yearless dates are anchored on the statement's end
    @Test
    void historicDecemberToJanuaryStatement() {
        List<ImportItem> items = parse(
            "Statement Date: 05 Jan 2025",
            "28 Dec Shop A 10.00 90.00",
            "02 Jan Shop B 10.00 80.00");
        assertEquals(LocalDate.of(2024, 12, 28), items.get(0).getDate());
        assertEquals(LocalDate.of(2025, 1, 2), items.get(1).getDate());
    }

    @Test
    void historicDecemberOnlyStatementDatedJanuary() {
        List<ImportItem> items = parse(
            "Statement Date: 03 Jan 2025",
            "10 Dec Shop A 10.00 90.00",
            "20 Dec Shop B 10.00 80.00");
        assertEquals(LocalDate.of(2024, 12, 10), items.get(0).getDate());
        assertEquals(LocalDate.of(2024, 12, 20), items.get(1).getDate());
    }

    @Test
    void newestFirstAcrossNewYearWithOnlyAYearInTheHeader() {
        List<ImportItem> items = parse(
            "Statement 2026",
            "04 Jan Shop A 10.00 70.00",
            "03 Jan Shop B 10.00 80.00",
            "15 Dec Shop C 10.00 90.00");
        assertEquals(LocalDate.of(2026, 1, 4), items.get(0).getDate());
        assertEquals(LocalDate.of(2026, 1, 3), items.get(1).getDate());
        assertEquals(LocalDate.of(2025, 12, 15), items.get(2).getDate());
    }

    // I-R3-2: punctuation-joined and run-on brand names
    @Test
    void brandSpellingsStillMatch() {
        assertEquals("Eating Out", TransactionClassifier.builtInCategory("POS Purchase McDonalds Jhb"));
        assertEquals("Eating Out", TransactionClassifier.builtInCategory("POS Purchase Uber *Eats"));
        assertEquals("Games", TransactionClassifier.builtInCategory("POS Purchase Steampowered"));
        assertEquals("Subscriptions", TransactionClassifier.builtInCategory("POS Purchase Spotifyza"));
        assertEquals("Subscriptions", TransactionClassifier.builtInCategory("POS Purchase Netflixcom"));
        assertEquals("Shopping", TransactionClassifier.builtInCategory("POS Purchase Takealo*T"));
        assertEquals("Software & AI", TransactionClassifier.builtInCategory("POS Purchase Claude.Ai Subscript"));
        assertEquals("Transport", TransactionClassifier.builtInCategory("POS Purchase Uber Trip"));
    }
}
