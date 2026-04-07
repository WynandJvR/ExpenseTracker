package com.wyn.expensetracker;

import org.apache.poi.ss.usermodel.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.*;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests simulating real user workflows across
 * multi-currency, debt tracking, receipt attachments, and export.
 */
class IntegrationTest {

    @TempDir
    Path tempDir;

    private FileStorage storage;
    private CurrencyManager cm;

    @BeforeEach
    void setUp() {
        storage = new FileStorage(tempDir.toString());
        cm = new CurrencyManager();
        cm.setBaseCurrency("ZAR");
        Map<String, Double> rates = new LinkedHashMap<>();
        rates.put("USD", 18.50);  // 1 USD = 18.50 ZAR
        rates.put("EUR", 20.00);  // 1 EUR = 20.00 ZAR
        rates.put("GBP", 23.50);  // 1 GBP = 23.50 ZAR
        cm.setExchangeRates(rates);
    }

    // ======================== CURRENCY MANAGER ========================

    @Test
    void currencyManager_baseConversion_returnsOriginalAmount() {
        assertEquals(100.0, cm.toBase(100.0, "ZAR"), 0.001);
        assertEquals(100.0, cm.toBase(100.0, null), 0.001);
    }

    @Test
    void currencyManager_foreignConversion_appliesRate() {
        assertEquals(1850.0, cm.toBase(100.0, "USD"), 0.001);
        assertEquals(2000.0, cm.toBase(100.0, "EUR"), 0.001);
        assertEquals(2350.0, cm.toBase(100.0, "GBP"), 0.001);
    }

    @Test
    void currencyManager_unknownCurrency_fallsBackTo1x() {
        // No rate configured for JPY
        assertEquals(500.0, cm.toBase(500.0, "JPY"), 0.001);
        assertFalse(cm.hasRate("JPY"));
    }

    @Test
    void currencyManager_hasRate_correctForConfiguredAndBase() {
        assertTrue(cm.hasRate("USD"));
        assertTrue(cm.hasRate("ZAR"));
        assertTrue(cm.hasRate(null));
        assertFalse(cm.hasRate("JPY"));
    }

    @Test
    void currencyManager_displayNames_areCorrect() {
        assertEquals("ZAR (R)", CurrencyManager.getDisplayName("ZAR"));
        assertEquals("USD ($)", CurrencyManager.getDisplayName("USD"));
        assertEquals("CHF", CurrencyManager.getDisplayName("CHF")); // symbol equals code
    }

    // ======================== MULTI-CURRENCY AGGREGATION ========================

    @Test
    void mixedCurrencyExpenses_aggregateCorrectlyInBaseCurrency() {
        List<Expense> expenses = List.of(
            makeExpense(500.0, "Groceries", "2025-03-01", null),         // ZAR 500
            makeExpense(50.0, "Online", "2025-03-05", "USD"),            // 50 USD = ZAR 925
            makeExpense(30.0, "Subscription", "2025-03-10", "EUR")       // 30 EUR = ZAR 600
        );

        double total = expenses.stream()
            .mapToDouble(e -> cm.toBase(e.getAmount(), e.getCurrency()))
            .sum();

        assertEquals(2025.0, total, 0.01, "500 + 925 + 600 = 2025 ZAR");
    }

    @Test
    void categoryBreakdown_convertsCorrectly() {
        List<Expense> expenses = List.of(
            makeExpense(100.0, "Food", "2025-03-01", null),      // ZAR 100
            makeExpense(10.0, "Food", "2025-03-02", "USD"),      // ZAR 185
            makeExpense(200.0, "Transport", "2025-03-03", null),  // ZAR 200
            makeExpense(5.0, "Transport", "2025-03-04", "EUR")   // ZAR 100
        );

        Map<String, Double> categoryMap = expenses.stream()
            .collect(Collectors.groupingBy(
                Expense::getCategory,
                Collectors.summingDouble(e -> cm.toBase(e.getAmount(), e.getCurrency()))));

        assertEquals(285.0, categoryMap.get("Food"), 0.01);
        assertEquals(300.0, categoryMap.get("Transport"), 0.01);
    }

    // ======================== ANOMALY DETECTOR WITH CURRENCY ========================

    @Test
    void anomalyDetector_detectsLargeTransactionInForeignCurrency() {
        // Build history of small ZAR expenses so average is low
        List<Expense> expenses = new ArrayList<>();
        for (int i = 1; i <= 20; i++) {
            expenses.add(makeExpense(50.0, "Food", "2025-02-" + String.format("%02d", Math.min(i, 28)), null));
        }
        // Add a small USD amount that converts to a large ZAR amount
        Expense foreignExpense = makeExpense(100.0, "Food", "2025-03-15", "USD"); // = 1850 ZAR
        expenses.add(foreignExpense);

        List<Anomaly> anomalies = AnomalyDetector.detect(
            expenses, YearMonth.of(2025, 3), "R", cm);

        boolean foundLargeTransaction = anomalies.stream()
            .anyMatch(a -> a.getType() == Anomaly.AnomalyType.LARGE_TRANSACTION);
        assertTrue(foundLargeTransaction,
            "100 USD (= 1850 ZAR) should be flagged as large vs avg of 50 ZAR");
    }

    @Test
    void anomalyDetector_noFalsePositiveForSmallForeignAmount() {
        List<Expense> expenses = new ArrayList<>();
        for (int i = 1; i <= 20; i++) {
            expenses.add(makeExpense(500.0, "Food", "2025-02-" + String.format("%02d", Math.min(i, 28)), null));
        }
        // 5 USD = 92.50 ZAR, well below average of 500 ZAR
        expenses.add(makeExpense(5.0, "Food", "2025-03-10", "USD"));

        List<Anomaly> anomalies = AnomalyDetector.detect(
            expenses, YearMonth.of(2025, 3), "R", cm);

        boolean foundLargeTransaction = anomalies.stream()
            .anyMatch(a -> a.getType() == Anomaly.AnomalyType.LARGE_TRANSACTION);
        assertFalse(foundLargeTransaction,
            "5 USD (= 92.50 ZAR) should NOT be flagged as large vs avg of 500 ZAR");
    }

    // ======================== PROJECTION ENGINE WITH CURRENCY ========================

    @Test
    void projectionEngine_convertsHistoricalExpenses() {
        List<Expense> expenses = new ArrayList<>();
        // 3 months of mixed-currency expenses
        for (int month = 1; month <= 3; month++) {
            String dateStr = String.format("2025-%02d-15", month);
            expenses.add(makeExpense(1000.0, "Rent", dateStr, null));    // ZAR 1000
            expenses.add(makeExpense(50.0, "Online", dateStr, "USD"));   // ZAR 925
        }

        List<RecurringExpense> recurring = List.of(
            new RecurringExpense(100.0, "Insurance", LocalDate.of(2025, 1, 1),
                "Monthly insurance", RecurrenceType.MONTHLY, null)
        );

        ProjectionEngine engine = new ProjectionEngine();
        ProjectionEngine.ProjectionInput input = new ProjectionEngine.ProjectionInput(
            expenses, recurring,
            Map.of(), 5000.0, Map.of(), cm
        );

        ProjectionEngine.ProjectionResult result = engine.project(input);
        assertNotNull(result);
        assertFalse(result.monthProjections.isEmpty());

        // Each historical month = ZAR 1000 + ZAR 925 = 1925 variable
        // Projections should reflect converted amounts
        ProjectionEngine.MonthProjection first = result.monthProjections.get(0);
        assertTrue(first.projectedExpenses > 0, "Projected expenses should be positive");
        assertTrue(first.projectedVariableExpenses > 500,
            "Variable should reflect converted USD amounts, not raw 50");
    }

    @Test
    void projectionEngine_convertsForeignRecurringExpenses() {
        RecurringExpense usdRecurring = new RecurringExpense(
            20.0, "Streaming", LocalDate.of(2025, 1, 1),
            "Netflix USD", RecurrenceType.MONTHLY, null);
        usdRecurring.setCurrency("USD");

        ProjectionEngine engine = new ProjectionEngine();
        ProjectionEngine.ProjectionInput input = new ProjectionEngine.ProjectionInput(
            List.of(), List.of(usdRecurring),
            Map.of(), 5000.0, Map.of(), cm
        );

        ProjectionEngine.ProjectionResult result = engine.project(input);
        ProjectionEngine.MonthProjection first = result.monthProjections.get(0);

        // 20 USD * 18.50 = 370 ZAR
        assertEquals(370.0, first.projectedRecurringExpenses, 0.01,
            "20 USD recurring should project as 370 ZAR");
    }

    // ======================== DEBT CALCULATIONS ========================

    @Test
    void debt_amortizationSchedule_isCorrect() {
        Debt debt = new Debt("d1", "Car Loan", 100000, 10.0, 60,
            LocalDate.of(2025, 1, 1), "MONTHLY", 0, "ZAR");

        double monthlyPayment = debt.calculateMonthlyPayment();
        assertTrue(monthlyPayment > 2000 && monthlyPayment < 2200,
            "Monthly payment for 100k @ 10% over 60m should be ~2124");

        List<Debt.AmortizationEntry> schedule = debt.getAmortizationSchedule();
        assertEquals(60, schedule.size());

        // First payment should have significant interest portion
        Debt.AmortizationEntry first = schedule.get(0);
        assertTrue(first.interest > 800 && first.interest < 850,
            "First month interest on 100k @ 10% should be ~833");
        assertTrue(first.principal > 0, "Principal portion should be positive");

        // Last payment should reduce balance to ~0
        Debt.AmortizationEntry last = schedule.get(schedule.size() - 1);
        assertTrue(last.remainingBalance < 1.0,
            "Balance after final payment should be near zero");
    }

    @Test
    void debt_remainingBalance_tracksPayments() {
        Debt debt = new Debt("d1", "Loan", 10000, 5.0, 12,
            LocalDate.of(2025, 1, 1), "MONTHLY", 0, "ZAR");
        debt.setMonthlyPayment(debt.calculateMonthlyPayment());

        // After 3 payments
        double threePayments = debt.getMonthlyPayment() * 3;
        double balance = debt.getRemainingBalance(threePayments);
        assertTrue(balance > 0, "Should still owe money after 3 of 12 payments");
        assertTrue(balance < 10000, "Balance should be less than principal");

        // After all payments
        double allPayments = debt.getTotalCost();
        double finalBalance = debt.getRemainingBalance(allPayments);
        assertTrue(finalBalance < 1.0, "Balance after all payments should be near zero");
    }

    @Test
    void debt_zeroInterest_calculatesCorrectly() {
        Debt debt = new Debt("d2", "Interest-Free", 12000, 0.0, 12,
            LocalDate.of(2025, 1, 1), "MONTHLY", 0, "ZAR");

        assertEquals(1000.0, debt.calculateMonthlyPayment(), 0.01);
        assertEquals(12000.0, debt.getTotalCost(), 0.01);
        assertEquals(0.0, debt.getTotalInterest(), 0.01);
    }

    // ======================== FILE STORAGE: CURRENCY & RECEIPT ROUNDTRIP ========================

    @Test
    void fileStorage_savesAndLoads_currencyAndReceipt() throws IOException {
        Expense expense = new Expense(99.99, "Shopping", LocalDate.of(2025, 3, 15), "Amazon order");
        expense.setCurrency("USD");
        expense.setReceiptPath("receipt_123.jpg");

        storage.saveExpenses(List.of(expense));
        List<Expense> loaded = storage.loadExpenses();

        assertEquals(1, loaded.size());
        Expense e = loaded.get(0);
        assertEquals("USD", e.getCurrency());
        assertEquals("receipt_123.jpg", e.getReceiptPath());
        assertEquals(99.99, e.getAmount(), 0.01);
    }

    @Test
    void fileStorage_currencyAndReceipt_nullSafe() throws IOException {
        Expense expense = new Expense(50.0, "Food", LocalDate.of(2025, 3, 10), "Lunch");
        // currency and receipt both null

        storage.saveExpenses(List.of(expense));
        List<Expense> loaded = storage.loadExpenses();

        assertEquals(1, loaded.size());
        assertNull(loaded.get(0).getCurrency());
        assertNull(loaded.get(0).getReceiptPath());
    }

    @Test
    void fileStorage_recurringWithCurrency_roundTrips() throws IOException {
        RecurringExpense recurring = new RecurringExpense(
            20.0, "Streaming", LocalDate.of(2025, 1, 1),
            "Netflix", RecurrenceType.MONTHLY, null);
        recurring.setCurrency("EUR");

        storage.saveExpenses(List.of(recurring));
        List<Expense> loaded = storage.loadExpenses();

        assertEquals(1, loaded.size());
        assertTrue(loaded.get(0) instanceof RecurringExpense);
        assertEquals("EUR", loaded.get(0).getCurrency());
    }

    // ======================== FILE STORAGE: DEBTS ROUNDTRIP ========================

    @Test
    void fileStorage_savesAndLoads_debts() throws IOException {
        Debt debt = new Debt("abc123", "Car Loan", 150000, 8.5, 60,
            LocalDate.of(2025, 1, 15), "MONTHLY", 3050.0, "ZAR");

        storage.saveDebts(List.of(debt));
        List<Debt> loaded = storage.loadDebts();

        assertEquals(1, loaded.size());
        Debt d = loaded.get(0);
        assertEquals("abc123", d.getId());
        assertEquals("Car Loan", d.getName());
        assertEquals(150000, d.getPrincipal(), 0.01);
        assertEquals(8.5, d.getAnnualRate(), 0.01);
        assertEquals(60, d.getTermMonths());
        assertEquals(LocalDate.of(2025, 1, 15), d.getStartDate());
        assertEquals(3050.0, d.getMonthlyPayment(), 0.01);
        assertEquals("ZAR", d.getCurrency());
    }

    @Test
    void fileStorage_savesAndLoads_debtPayments() throws IOException {
        DebtPayment payment = new DebtPayment("abc123", 3050.0,
            LocalDate.of(2025, 2, 15), "February payment");

        storage.saveDebtPayments(List.of(payment));
        List<DebtPayment> loaded = storage.loadDebtPayments();

        assertEquals(1, loaded.size());
        DebtPayment p = loaded.get(0);
        assertEquals("abc123", p.getDebtId());
        assertEquals(3050.0, p.getAmount(), 0.01);
        assertEquals(LocalDate.of(2025, 2, 15), p.getDate());
        assertEquals("February payment", p.getNote());
    }

    @Test
    void fileStorage_multipleDebts_roundTrip() throws IOException {
        List<Debt> debts = List.of(
            new Debt("d1", "Car", 100000, 10, 60, LocalDate.of(2024, 6, 1), "MONTHLY", 2124, "ZAR"),
            new Debt("d2", "Student", 50000, 5, 36, LocalDate.of(2023, 1, 1), "MONTHLY", 1499, "USD")
        );
        storage.saveDebts(debts);
        List<Debt> loaded = storage.loadDebts();

        assertEquals(2, loaded.size());
        assertEquals("Car", loaded.get(0).getName());
        assertEquals("Student", loaded.get(1).getName());
        assertEquals("USD", loaded.get(1).getCurrency());
    }

    // ======================== FILE STORAGE: EXCHANGE RATES ROUNDTRIP ========================

    @Test
    void fileStorage_savesAndLoads_exchangeRates() throws IOException {
        Map<String, Double> rates = new LinkedHashMap<>();
        rates.put("USD", 18.50);
        rates.put("EUR", 20.00);

        storage.saveExchangeRates("ZAR", rates);
        Map<String, Double> loaded = storage.loadExchangeRates();

        assertEquals(2, loaded.size());
        assertEquals(18.50, loaded.get("USD"), 0.001);
        assertEquals(20.00, loaded.get("EUR"), 0.001);
    }

    @Test
    void fileStorage_baseCurrency_roundTrips() throws IOException {
        storage.saveBaseCurrency("EUR");
        assertEquals("EUR", storage.loadBaseCurrency());
    }

    // ======================== EXCEL EXPORT WITH NEW FIELDS ========================

    @Test
    void excelExport_includesCurrencyAndReceipt() throws IOException {
        Expense expense = new Expense(100.0, "Shopping", LocalDate.of(2025, 3, 1), "Online purchase");
        expense.setCurrency("USD");
        expense.setReceiptPath("receipt_001.png");

        String filePath = tempDir.resolve("export.xlsx").toString();
        ExcelExporter.exportExpenses(List.of(expense), null, null, filePath);

        try (Workbook wb = WorkbookFactory.create(new File(filePath))) {
            Sheet sheet = wb.getSheet("Expenses");
            assertNotNull(sheet);
            Row header = sheet.getRow(0);
            assertEquals("Currency", header.getCell(9).getStringCellValue());
            assertEquals("ReceiptPath", header.getCell(10).getStringCellValue());

            Row data = sheet.getRow(1);
            assertEquals("USD", data.getCell(9).getStringCellValue());
            assertEquals("receipt_001.png", data.getCell(10).getStringCellValue());
        }
    }

    @Test
    void excelExport_includesDebtSheets() throws IOException {
        Expense expense = new Expense(50.0, "Food", LocalDate.of(2025, 3, 1), "Lunch");

        List<Debt> debts = List.of(
            new Debt("d1", "Car Loan", 100000, 10, 60,
                LocalDate.of(2025, 1, 1), "MONTHLY", 2124.70, "ZAR")
        );

        List<DebtPayment> payments = List.of(
            new DebtPayment("d1", 2124.70, LocalDate.of(2025, 2, 1), "Feb payment")
        );

        String filePath = tempDir.resolve("export_debts.xlsx").toString();
        ExcelExporter.exportExpenses(List.of(expense), debts, payments, filePath);

        try (Workbook wb = WorkbookFactory.create(new File(filePath))) {
            // Expenses sheet
            assertNotNull(wb.getSheet("Expenses"));

            // Debts sheet
            Sheet debtSheet = wb.getSheet("Debts");
            assertNotNull(debtSheet, "Should have a Debts sheet");
            Row dHeader = debtSheet.getRow(0);
            assertEquals("Name", dHeader.getCell(0).getStringCellValue());
            Row dRow = debtSheet.getRow(1);
            assertEquals("Car Loan", dRow.getCell(0).getStringCellValue());
            assertEquals(100000.0, dRow.getCell(1).getNumericCellValue(), 0.01);

            // Debt Payments sheet
            Sheet paySheet = wb.getSheet("Debt Payments");
            assertNotNull(paySheet, "Should have a Debt Payments sheet");
            Row pRow = paySheet.getRow(1);
            assertEquals("d1", pRow.getCell(0).getStringCellValue());
            assertEquals(2124.70, pRow.getCell(1).getNumericCellValue(), 0.01);
            assertEquals("Feb payment", pRow.getCell(3).getStringCellValue());
        }
    }

    @Test
    void excelExport_noDebtSheets_whenNullOrEmpty() throws IOException {
        Expense expense = new Expense(50.0, "Food", LocalDate.of(2025, 3, 1), "Lunch");
        String filePath = tempDir.resolve("export_no_debts.xlsx").toString();
        ExcelExporter.exportExpenses(List.of(expense), null, null, filePath);

        try (Workbook wb = WorkbookFactory.create(new File(filePath))) {
            assertNotNull(wb.getSheet("Expenses"));
            assertNull(wb.getSheet("Debts"), "No Debts sheet when debts is null");
            assertNull(wb.getSheet("Debt Payments"), "No Payments sheet when payments is null");
        }
    }

    // ======================== RECEIPT FILE MANAGEMENT ========================

    @Test
    void receiptsDir_isCreated() {
        String receiptsDir = storage.getReceiptsDir();
        assertTrue(new File(receiptsDir).exists());
        assertTrue(new File(receiptsDir).isDirectory());
    }

    @Test
    void receipt_specialCharsInPath_roundTrips() throws IOException {
        Expense expense = new Expense(25.0, "Office", LocalDate.of(2025, 3, 1), "Staples");
        expense.setReceiptPath("receipt with spaces & symbols (1).jpg");

        storage.saveExpenses(List.of(expense));
        List<Expense> loaded = storage.loadExpenses();

        assertEquals("receipt with spaces & symbols (1).jpg", loaded.get(0).getReceiptPath());
    }

    // ======================== EXPENSE MANAGER WITH CURRENCY ========================

    @Test
    void expenseManager_generatedRecurring_inheritesCurrency() {
        ExpenseManager manager = new ExpenseManager();
        RecurringExpense recurring = new RecurringExpense(
            20.0, "Streaming", LocalDate.of(2025, 1, 1),
            "Netflix", RecurrenceType.MONTHLY, LocalDate.of(2025, 3, 31));
        recurring.setCurrency("USD");

        manager.loadExpenses(List.of(recurring));

        List<Expense> allExpenses = manager.getExpenses();
        // Should have generated instances for Jan, Feb, Mar
        List<Expense> generated = allExpenses.stream()
            .filter(e -> e.getRecurringId() != null)
            .collect(Collectors.toList());

        assertFalse(generated.isEmpty(), "Should have generated recurring expenses");
        for (Expense e : generated) {
            assertEquals("USD", e.getCurrency(),
                "Generated recurring expense should inherit USD currency");
        }
    }

    // ======================== END-TO-END WORKFLOW: USER WITH MIXED CURRENCIES ========================

    @Test
    void endToEnd_mixedCurrencyMonthlyTotal() {
        // Simulate a user in ZA who shops online in USD and has a EUR subscription
        List<Expense> march = List.of(
            makeExpense(2500.0, "Rent", "2025-03-01", null),       // ZAR 2500
            makeExpense(800.0, "Groceries", "2025-03-05", null),   // ZAR 800
            makeExpense(45.0, "AWS", "2025-03-10", "USD"),          // ZAR 832.50
            makeExpense(12.99, "Spotify", "2025-03-10", "EUR"),     // ZAR 259.80
            makeExpense(150.0, "Fuel", "2025-03-15", null)          // ZAR 150
        );

        double rawTotal = march.stream().mapToDouble(Expense::getAmount).sum();
        double convertedTotal = march.stream()
            .mapToDouble(e -> cm.toBase(e.getAmount(), e.getCurrency()))
            .sum();

        // Raw total would be 3507.99 (meaninglessly mixing currencies)
        assertEquals(3507.99, rawTotal, 0.01);
        // Converted total should be 4542.30
        assertEquals(4542.30, convertedTotal, 0.01);

        // Verify the difference is significant
        assertTrue(convertedTotal - rawTotal > 1000,
            "Converted total should be substantially more than raw when foreign amounts are involved");
    }

    @Test
    void endToEnd_debtPlusBudget_savingsCalculation() {
        // User earns 25000 ZAR, spends 15000 on expenses, has 3000/month in debt payments
        double income = 25000;
        double expenses = 15000;

        Debt carLoan = new Debt("d1", "Car", 100000, 10, 60,
            LocalDate.of(2024, 1, 1), "MONTHLY", 2124, "ZAR");
        Debt studentLoan = new Debt("d2", "Study", 30000, 7, 36,
            LocalDate.of(2024, 6, 1), "MONTHLY", 926, "ZAR");

        List<DebtPayment> payments = new ArrayList<>(); // fresh, no payments yet

        double monthlyDebt = 0;
        for (Debt d : List.of(carLoan, studentLoan)) {
            double paid = payments.stream()
                .filter(p -> p.getDebtId().equals(d.getId()))
                .mapToDouble(DebtPayment::getAmount).sum();
            double balance = d.getRemainingBalance(paid);
            if (balance > 0.01) {
                monthlyDebt += d.getMonthlyPayment();
            }
        }

        double savingsWithoutDebt = income - expenses;
        double savingsWithDebt = income - expenses - monthlyDebt;

        assertEquals(10000.0, savingsWithoutDebt, 0.01);
        assertEquals(6950.0, savingsWithDebt, 0.01, "25000 - 15000 - 2124 - 926 = 6950");
    }

    // ======================== EDGE CASES ========================

    @Test
    void emptyDebtList_savesAndLoadsCleanly() throws IOException {
        storage.saveDebts(List.of());
        List<Debt> loaded = storage.loadDebts();
        assertTrue(loaded.isEmpty());
    }

    @Test
    void emptyDebtPayments_savesAndLoadsCleanly() throws IOException {
        storage.saveDebtPayments(List.of());
        List<DebtPayment> loaded = storage.loadDebtPayments();
        assertTrue(loaded.isEmpty());
    }

    @Test
    void debt_withNullCurrency_roundTrips() throws IOException {
        Debt debt = new Debt("d1", "Loan", 5000, 5, 12,
            LocalDate.of(2025, 1, 1), "MONTHLY", 428, null);

        storage.saveDebts(List.of(debt));
        List<Debt> loaded = storage.loadDebts();

        assertEquals(1, loaded.size());
        assertNull(loaded.get(0).getCurrency());
    }

    @Test
    void exchangeRates_emptyFile_returnsEmpty() throws IOException {
        // Don't save anything — loadExchangeRates should return empty map
        Map<String, Double> rates = storage.loadExchangeRates();
        assertTrue(rates.isEmpty());
    }

    @Test
    void debtPayment_withEmptyNote_roundTrips() throws IOException {
        DebtPayment payment = new DebtPayment("d1", 500.0, LocalDate.of(2025, 3, 1), "");
        storage.saveDebtPayments(List.of(payment));
        List<DebtPayment> loaded = storage.loadDebtPayments();

        assertEquals(1, loaded.size());
        assertEquals("", loaded.get(0).getNote());
    }

    @Test
    void multipleExpenses_withMixedCurrencyAndReceipts_roundTrip() throws IOException {
        Expense e1 = new Expense(100.0, "Food", LocalDate.of(2025, 3, 1), "Groceries");
        // no currency, no receipt

        Expense e2 = new Expense(50.0, "Online", LocalDate.of(2025, 3, 5), "Amazon");
        e2.setCurrency("USD");
        e2.setReceiptPath("amazon_receipt.jpg");

        Expense e3 = new Expense(30.0, "Subscription", LocalDate.of(2025, 3, 10), "Netflix");
        e3.setCurrency("EUR");
        // no receipt

        RecurringExpense r1 = new RecurringExpense(
            20.0, "Streaming", LocalDate.of(2025, 1, 1),
            "Spotify", RecurrenceType.MONTHLY, null);
        r1.setCurrency("EUR");
        r1.setReceiptPath("spotify_proof.png");

        storage.saveExpenses(List.of(e1, e2, e3, r1));
        List<Expense> loaded = storage.loadExpenses();

        assertEquals(4, loaded.size());

        // e1: no currency, no receipt
        assertNull(loaded.get(0).getCurrency());
        assertNull(loaded.get(0).getReceiptPath());

        // e2: USD + receipt
        // Note: order might not be preserved if recurring goes first in loadExpenses
        // Find by description
        Expense loadedAmazon = loaded.stream()
            .filter(e -> "Amazon".equals(e.getDescription())).findFirst().orElseThrow();
        assertEquals("USD", loadedAmazon.getCurrency());
        assertEquals("amazon_receipt.jpg", loadedAmazon.getReceiptPath());

        // r1: EUR recurring + receipt
        Expense loadedSpotify = loaded.stream()
            .filter(e -> "Spotify".equals(e.getDescription())).findFirst().orElseThrow();
        assertTrue(loadedSpotify instanceof RecurringExpense);
        assertEquals("EUR", loadedSpotify.getCurrency());
        assertEquals("spotify_proof.png", loadedSpotify.getReceiptPath());
    }

    // ======================== HELPERS ========================

    private Expense makeExpense(double amount, String category, String date, String currency) {
        Expense e = new Expense(amount, category, LocalDate.parse(date), category + " expense");
        if (currency != null) e.setCurrency(currency);
        return e;
    }
}
