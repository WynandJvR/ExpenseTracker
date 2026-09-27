package com.wyn.expensetracker;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/** Receipt text → line items, total, merchant and date (the OCR step itself isn't needed here). */
class ReceiptParsingTest {

    private static final String SLIP = String.join("\n",
        "SHOPRITE",
        "TAX INVOICE",
        "VAT NO 4123456789",
        "26 Sep 2026 15:47",
        "SUGAR 2.5KG           45.99",
        "BURGER 3              49.99",
        "CASHEW NUTS 100G      34.99 A",
        "BIRTHDAY CARD         29.90",
        "WATER 5L",
        "  2 @ 12.99           25.98",
        "COKE 2L               24.99",
        "XTRA SAVINGS           5.00-",
        "TAXI MUG             R 1 299,95",
        "SUBTOTAL            R 1 505,79",
        "VAT 15%                196,41",
        "TOTAL               R 1 505,79",
        "VISA                R 1 505,79",
        "CARD ****1234       R 1 505,79",
        "CHANGE                   0.00");

    private static Map<String, Double> items(String text) {
        List<ImportItem> items = new ReceiptScanner().parseReceipt(text, LocalDate.of(2026, 1, 1));
        return items.stream().collect(Collectors.toMap(ImportItem::getDescription, ImportItem::getAmount, (a, b) -> a));
    }

    @Test
    void readsItemsAndSkipsTotalsAndPayments() {
        Map<String, Double> got = items(SLIP);
        assertEquals(45.99, got.get("SUGAR 2.5KG"), 0.001, got.toString());   // not "SUGA 2.50"
        assertEquals(49.99, got.get("BURGER 3"), 0.001, got.toString());      // quantity isn't glued to the price
        assertEquals(34.99, got.get("CASHEW NUTS 100G"), 0.001);             // "cash" is a whole word only
        assertEquals(29.90, got.get("BIRTHDAY CARD"), 0.001);                // a card you buy is an item
        assertEquals(25.98, got.get("WATER 5L"), 0.001);                     // "2 @ 12.99" takes the line above
        assertEquals(19.99, got.get("COKE 2L"), 0.001);                      // promo taken off the item above
        assertEquals(1299.95, got.get("TAXI MUG"), 0.001);                   // R-prefixed space thousands
        assertEquals(7, got.size(), got.toString());                         // no SUBTOTAL/VAT/TOTAL/VISA/CHANGE
    }

    @Test
    void readsTotalMerchantAndDate() {
        ReceiptScanner scanner = new ReceiptScanner();
        assertEquals(1505.79, scanner.extractTotal(SLIP), 0.001);
        assertEquals("SHOPRITE", scanner.extractMerchant(SLIP));
        // "Sep" must parse whatever the JVM locale (en_ZA writes "Sept").
        assertEquals(LocalDate.of(2026, 9, 26), ReceiptScanner.extractDate(SLIP));
        assertEquals(LocalDate.of(2026, 9, 26),
            new ReceiptScanner().parseReceipt(SLIP, LocalDate.of(2026, 1, 1)).get(0).getDate());
    }

    @Test
    void totalIgnoresDiscountSavingsAndVatTotals() {
        ReceiptScanner scanner = new ReceiptScanner();
        assertEquals(125.97, scanner.extractTotal(String.join("\n",
            "MILK 2L 35.99", "BREAD 99.98", "SUBTOTAL 135.97", "TOTAL DISCOUNT 10.00", "TOTAL 125.97", "TOTAL VAT 16.43")), 0.001);
        assertEquals(50.00, scanner.extractTotal(String.join("\n",
            "TOTAL SAVINGS 12.00", "COFFEE 50.00", "TOTAL DUE 50.00")), 0.001);
    }

    @Test
    void itemsStartingWithCardAreKeptAndChainNamesOnlyCountInTheHeader() {
        Map<String, Double> got = items("CARD GAME UNO  89.99\nCARD ****1234  89.99");
        assertEquals(89.99, got.get("CARD GAME UNO"), 0.001, got.toString());
        assertEquals(1, got.size(), got.toString());
        String cafe = String.join("\n", "THE COFFEE ROOM", "12 Main Road", "TAX INVOICE", "", "", "", "", "",
            "SHELL PASTA 89.00", "TOTAL 89.00");
        assertEquals("THE COFFEE ROOM", new ReceiptScanner().extractMerchant(cafe));
    }

    @Test
    void fixesCommonOcrConfusionsInAmountsOnly() {
        Map<String, Double> got = items("BREAD 700G  4S.00\nMILK 2L  22:99\nEGGS 18  @9,99");
        assertEquals(45.00, got.get("BREAD 700G"), 0.001, got.toString());
        assertEquals(22.99, got.get("MILK 2L"), 0.001, got.toString());
        assertEquals(9.99, got.get("EGGS 18"), 0.001, got.toString());
    }

    @Test
    void numericDatesPreferDayFirstAndRejectFutureDates() {
        assertEquals(LocalDate.of(2026, 3, 5), ReceiptScanner.extractDate("DATE 05/03/2026"));
        assertEquals(LocalDate.of(2026, 3, 5), ReceiptScanner.extractDate("2026-03-05 10:11"));
        assertNull(ReceiptScanner.extractDate("REF 12/12/2099"));
    }
}
