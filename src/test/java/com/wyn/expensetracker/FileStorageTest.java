package com.wyn.expensetracker;

import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class FileStorageTest {

    @TempDir
    Path tempDir;

    private FileStorage storage;

    @BeforeEach
    void setUp() {
        storage = new FileStorage(tempDir.toString());
    }

    // ======================== EXPENSES ROUND-TRIP ========================

    @Test
    void saveAndLoadRegularExpenses() throws IOException {
        List<Expense> expenses = List.of(
            new Expense(50.0, "Food", LocalDate.of(2025, 1, 15), "Groceries"),
            new Expense(30.0, "Transport", LocalDate.of(2025, 1, 16), "Bus fare")
        );

        storage.saveExpenses(expenses);
        List<Expense> loaded = storage.loadExpenses();

        assertEquals(2, loaded.size());
        assertEquals(50.0, loaded.get(0).getAmount());
        assertEquals("Food", loaded.get(0).getCategory());
        assertEquals(LocalDate.of(2025, 1, 15), loaded.get(0).getDate());
        assertEquals("Groceries", loaded.get(0).getDescription());
        assertEquals(30.0, loaded.get(1).getAmount());
        assertEquals("Transport", loaded.get(1).getCategory());
    }

    @Test
    void saveAndLoadRecurringExpenses() throws IOException {
        RecurringExpense recurring = new RecurringExpense(
            100.0, "Utilities", LocalDate.of(2025, 1, 1), "Electricity",
            RecurrenceType.MONTHLY, LocalDate.of(2025, 12, 31)
        );
        RecurringExpense noEndDate = new RecurringExpense(
            50.0, "Entertainment", LocalDate.of(2025, 2, 1), "Netflix",
            RecurrenceType.MONTHLY, null
        );

        storage.saveExpenses(List.of(recurring, noEndDate));
        List<Expense> loaded = storage.loadExpenses();

        assertEquals(2, loaded.size());

        assertInstanceOf(RecurringExpense.class, loaded.get(0));
        RecurringExpense r1 = (RecurringExpense) loaded.get(0);
        assertEquals(100.0, r1.getAmount());
        assertEquals(RecurrenceType.MONTHLY, r1.getFrequency());
        assertEquals(LocalDate.of(2025, 12, 31), r1.getEndDate());

        RecurringExpense r2 = (RecurringExpense) loaded.get(1);
        assertNull(r2.getEndDate());
    }

    @Test
    void saveAndLoadExpensesWithImportId() throws IOException {
        Expense exp = new Expense(75.0, "Food", LocalDate.of(2025, 3, 10), "Restaurant");
        exp.setImportId("IMP-2025-001");

        storage.saveExpenses(List.of(exp));
        List<Expense> loaded = storage.loadExpenses();

        assertEquals(1, loaded.size());
        assertEquals("IMP-2025-001", loaded.get(0).getImportId());
    }

    @Test
    void saveAndLoadMixedExpenses() throws IOException {
        Expense regular = new Expense(25.0, "Food", LocalDate.of(2025, 1, 1), "Lunch");
        RecurringExpense recurring = new RecurringExpense(
            200.0, "Rent", LocalDate.of(2025, 1, 1), "Monthly rent",
            RecurrenceType.MONTHLY, null
        );
        Expense imported = new Expense(99.99, "Electronics", LocalDate.of(2025, 1, 5), "Headphones");
        imported.setImportId("PDF-001");

        storage.saveExpenses(List.of(regular, recurring, imported));
        List<Expense> loaded = storage.loadExpenses();

        assertEquals(3, loaded.size());
        assertFalse(loaded.get(0) instanceof RecurringExpense);
        assertInstanceOf(RecurringExpense.class, loaded.get(1));
        assertEquals("PDF-001", loaded.get(2).getImportId());
    }

    @Test
    void saveAndLoadEmptyExpenses() throws IOException {
        storage.saveExpenses(List.of());
        List<Expense> loaded = storage.loadExpenses();
        assertTrue(loaded.isEmpty());
    }

    @Test
    void loadExpensesReturnsEmptyWhenNoFile() throws IOException {
        List<Expense> loaded = storage.loadExpenses();
        assertTrue(loaded.isEmpty());
    }

    // ======================== CSV ESCAPING ========================

    @Test
    void expenseWithCommaInDescription() throws IOException {
        Expense exp = new Expense(10.0, "Food", LocalDate.of(2025, 1, 1), "Rice, beans, and chicken");

        storage.saveExpenses(List.of(exp));
        List<Expense> loaded = storage.loadExpenses();

        assertEquals(1, loaded.size());
        assertEquals("Rice, beans, and chicken", loaded.get(0).getDescription());
    }

    @Test
    void expenseWithQuotesInDescription() throws IOException {
        Expense exp = new Expense(15.0, "Entertainment", LocalDate.of(2025, 1, 1), "Movie \"Avatar\"");

        storage.saveExpenses(List.of(exp));
        List<Expense> loaded = storage.loadExpenses();

        assertEquals(1, loaded.size());
        assertEquals("Movie \"Avatar\"", loaded.get(0).getDescription());
    }

    @Test
    void expenseWithCommaInCategory() throws IOException {
        Expense exp = new Expense(20.0, "Food, Dining", LocalDate.of(2025, 1, 1), "Lunch");

        storage.saveExpenses(List.of(exp));
        List<Expense> loaded = storage.loadExpenses();

        assertEquals(1, loaded.size());
        assertEquals("Food, Dining", loaded.get(0).getCategory());
    }

    @Test
    void expenseWithCommasAndQuotes() throws IOException {
        Expense exp = new Expense(5.0, "Other", LocalDate.of(2025, 1, 1), "He said, \"hello, world\"");

        storage.saveExpenses(List.of(exp));
        List<Expense> loaded = storage.loadExpenses();

        assertEquals(1, loaded.size());
        assertEquals("He said, \"hello, world\"", loaded.get(0).getDescription());
    }

    @Test
    void expenseWithQuotesWrappingEntireValue() throws IOException {
        Expense exp = new Expense(10.0, "\"Special\"", LocalDate.of(2025, 1, 1), "\"Quoted desc\"");

        storage.saveExpenses(List.of(exp));
        List<Expense> loaded = storage.loadExpenses();

        assertEquals(1, loaded.size());
        assertEquals("\"Special\"", loaded.get(0).getCategory());
        assertEquals("\"Quoted desc\"", loaded.get(0).getDescription());
    }

    // ======================== MALFORMED DATA HANDLING ========================

    @Test
    void loadSkipsLinesWithNegativeAmount() throws IOException {
        Path file = tempDir.resolve("expenses.txt");
        Files.writeString(file, "-5.0,Food,2025-01-01,Bad,REGULAR,\n50.0,Food,2025-01-01,Good,REGULAR,\n");

        List<Expense> loaded = storage.loadExpenses();
        assertEquals(1, loaded.size());
        assertEquals("Good", loaded.get(0).getDescription());
    }

    @Test
    void loadSkipsMalformedLines() throws IOException {
        Path file = tempDir.resolve("expenses.txt");
        Files.writeString(file, "not,enough,fields\n50.0,Food,2025-01-01,Valid,REGULAR,\n");

        List<Expense> loaded = storage.loadExpenses();
        assertEquals(1, loaded.size());
    }

    @Test
    void loadSkipsUnknownExpenseType() throws IOException {
        Path file = tempDir.resolve("expenses.txt");
        Files.writeString(file, "50.0,Food,2025-01-01,Test,UNKNOWN,\n25.0,Food,2025-01-01,Valid,REGULAR,\n");

        List<Expense> loaded = storage.loadExpenses();
        assertEquals(1, loaded.size());
        assertEquals("Valid", loaded.get(0).getDescription());
    }

    // ======================== EXPENSES FILE EXISTS ========================

    @Test
    void expensesFileExistsReturnsFalseWhenMissing() {
        assertFalse(storage.expensesFileExists());
    }

    @Test
    void expensesFileExistsReturnsTrueAfterSave() throws IOException {
        storage.saveExpenses(List.of(new Expense(10.0, "Food", LocalDate.now(), "Test")));
        assertTrue(storage.expensesFileExists());
    }

    // ======================== ATOMIC WRITES ========================

    @Test
    void atomicWriteLeavesNoTempFiles() throws IOException {
        storage.saveExpenses(List.of(new Expense(10.0, "Food", LocalDate.now(), "Test")));

        File[] tmpFiles = tempDir.toFile().listFiles((dir, name) -> name.endsWith(".tmp"));
        assertNotNull(tmpFiles);
        assertEquals(0, tmpFiles.length);
    }

    @Test
    void atomicWritePreservesOriginalOnOverwrite() throws IOException {
        List<Expense> first = List.of(new Expense(10.0, "Food", LocalDate.of(2025, 1, 1), "First"));
        storage.saveExpenses(first);

        List<Expense> second = List.of(
            new Expense(20.0, "Transport", LocalDate.of(2025, 2, 1), "Second"),
            new Expense(30.0, "Entertainment", LocalDate.of(2025, 3, 1), "Third")
        );
        storage.saveExpenses(second);

        List<Expense> loaded = storage.loadExpenses();
        assertEquals(2, loaded.size());
        assertEquals(20.0, loaded.get(0).getAmount());
        assertEquals(30.0, loaded.get(1).getAmount());
    }

    // ======================== BACKUP ROTATION ========================

    @Test
    void backupFilesAreCreatedOnSave() throws IOException {
        Expense exp = new Expense(10.0, "Food", LocalDate.now(), "Test");

        // First save — creates expenses.txt, no backup yet (file didn't exist before)
        storage.saveExpenses(List.of(exp));
        assertFalse(new File(tempDir.toString(), "expenses.txt.1").exists());

        // Second save — rotates, creates .1
        storage.saveExpenses(List.of(exp));
        assertTrue(new File(tempDir.toString(), "expenses.txt.1").exists());

        // Third save — .1 becomes .2, new .1
        storage.saveExpenses(List.of(exp));
        assertTrue(new File(tempDir.toString(), "expenses.txt.1").exists());
        assertTrue(new File(tempDir.toString(), "expenses.txt.2").exists());
    }

    @Test
    void backupRotationCapsAtFive() throws IOException {
        Expense exp = new Expense(10.0, "Food", LocalDate.now(), "Test");

        // Save 7 times (first creates file, next 6 rotate)
        for (int i = 0; i < 7; i++) {
            storage.saveExpenses(List.of(new Expense(i + 1, "Food", LocalDate.now(), "Save " + i)));
        }

        assertTrue(new File(tempDir.toString(), "expenses.txt.1").exists());
        assertTrue(new File(tempDir.toString(), "expenses.txt.5").exists());
        assertFalse(new File(tempDir.toString(), "expenses.txt.6").exists());
    }

    @Test
    void backupContainsPreviousVersion() throws IOException {
        // Save version 1
        storage.saveExpenses(List.of(new Expense(10.0, "Food", LocalDate.of(2025, 1, 1), "Version1")));
        // Save version 2 — .1 should contain version 1
        storage.saveExpenses(List.of(new Expense(20.0, "Food", LocalDate.of(2025, 1, 1), "Version2")));

        String backup = Files.readString(tempDir.resolve("expenses.txt.1"));
        assertTrue(backup.contains("Version1"));
        assertTrue(backup.contains("10.0"));
    }

    // ======================== CATEGORIES ========================

    @Test
    void saveAndLoadCategories() throws IOException {
        ObservableList<String> categories = FXCollections.observableArrayList(
            "Food", "Transport", "Entertainment", "Utilities"
        );

        storage.saveCategories(categories);
        List<String> loaded = storage.loadCategories();

        assertEquals(4, loaded.size());
        assertEquals("Food", loaded.get(0));
        assertEquals("Utilities", loaded.get(3));
    }

    @Test
    void categoriesWithSpecialCharacters() throws IOException {
        ObservableList<String> categories = FXCollections.observableArrayList(
            "Food, Dining", "\"Luxury\" Items"
        );

        storage.saveCategories(categories);
        List<String> loaded = storage.loadCategories();

        assertEquals(2, loaded.size());
        assertEquals("Food, Dining", loaded.get(0));
        assertEquals("\"Luxury\" Items", loaded.get(1));
    }

    @Test
    void loadCategoriesReturnsEmptyWhenNoFile() throws IOException {
        assertTrue(storage.loadCategories().isEmpty());
    }

    // ======================== INCOMES ========================

    @Test
    void saveAndLoadIncomes() throws IOException {
        Map<YearMonth, Double> incomes = new HashMap<>();
        incomes.put(YearMonth.of(2025, 1), 5000.0);
        incomes.put(YearMonth.of(2025, 2), 5500.0);

        storage.saveIncomes(incomes);
        Map<YearMonth, Double> loaded = storage.loadIncomes();

        assertEquals(2, loaded.size());
        assertEquals(5000.0, loaded.get(YearMonth.of(2025, 1)));
        assertEquals(5500.0, loaded.get(YearMonth.of(2025, 2)));
    }

    @Test
    void loadIncomesSkipsNegativeValues() throws IOException {
        Path file = tempDir.resolve("incomes.txt");
        Files.writeString(file, "2025-01,-500.0\n2025-02,3000.0\n");

        Map<YearMonth, Double> loaded = storage.loadIncomes();
        assertEquals(1, loaded.size());
        assertEquals(3000.0, loaded.get(YearMonth.of(2025, 2)));
    }

    @Test
    void loadIncomesReturnsEmptyWhenNoFile() throws IOException {
        assertTrue(storage.loadIncomes().isEmpty());
    }

    // ======================== BUDGETS ========================

    @Test
    void saveAndLoadBudgets() throws IOException {
        Map<String, Double> budgets = new LinkedHashMap<>();
        budgets.put("Food", 2000.0);
        budgets.put("Transport", 500.0);

        storage.saveBudgets(budgets);
        Map<String, Double> loaded = storage.loadBudgets();

        assertEquals(2, loaded.size());
        assertEquals(2000.0, loaded.get("Food"));
        assertEquals(500.0, loaded.get("Transport"));
    }

    @Test
    void budgetWithCommaInCategory() throws IOException {
        Map<String, Double> budgets = new LinkedHashMap<>();
        budgets.put("Food, Dining", 1500.0);

        storage.saveBudgets(budgets);
        Map<String, Double> loaded = storage.loadBudgets();

        assertEquals(1, loaded.size());
        assertEquals(1500.0, loaded.get("Food, Dining"));
    }

    @Test
    void loadBudgetsReturnsEmptyWhenNoFile() throws IOException {
        assertTrue(storage.loadBudgets().isEmpty());
    }

    // ======================== CATEGORIZATION RULES ========================

    @Test
    void saveAndLoadCategorizationRules() throws IOException {
        Map<String, String> rules = new LinkedHashMap<>();
        rules.put("groceries", "Food");
        rules.put("uber", "Transport");
        rules.put("netflix", "Entertainment");

        storage.saveCategorizationRules(rules);
        Map<String, String> loaded = storage.loadCategorizationRules();

        assertEquals(3, loaded.size());
        assertEquals("Food", loaded.get("groceries"));
        assertEquals("Transport", loaded.get("uber"));
        assertEquals("Entertainment", loaded.get("netflix"));
    }

    @Test
    void categorizationRulesWithSpecialChars() throws IOException {
        Map<String, String> rules = new LinkedHashMap<>();
        rules.put("burger, pizza", "Food, Dining");
        rules.put("a \"premium\" item", "Luxury");
        rules.put("\"quoted\"", "Other");

        storage.saveCategorizationRules(rules);
        Map<String, String> loaded = storage.loadCategorizationRules();

        assertEquals(3, loaded.size());
        assertEquals("Food, Dining", loaded.get("burger, pizza"));
        assertEquals("Luxury", loaded.get("a \"premium\" item"));
        assertEquals("Other", loaded.get("\"quoted\""));
    }

    @Test
    void loadCategorizationRulesReturnsEmptyWhenNoFile() throws IOException {
        assertTrue(storage.loadCategorizationRules().isEmpty());
    }

    // ======================== IMPORT LOGS ========================

    @Test
    void saveAndLoadImportLogs() throws IOException {
        LocalDateTime ts1 = LocalDateTime.of(2025, 1, 15, 10, 30, 0);
        LocalDateTime ts2 = LocalDateTime.of(2025, 2, 20, 14, 45, 0);
        List<ImportLog> logs = List.of(
            new ImportLog("IMP-001", ts1, "bank_jan.pdf", "FNB PDF", 15),
            new ImportLog("IMP-002", ts2, "bank_feb.pdf", "FNB PDF", 22)
        );

        storage.saveImportLogs(logs);
        List<ImportLog> loaded = storage.loadImportLogs();

        assertEquals(2, loaded.size());
        assertEquals("IMP-001", loaded.get(0).getImportId());
        assertEquals(ts1, loaded.get(0).getTimestamp());
        assertEquals("bank_jan.pdf", loaded.get(0).getSourceFile());
        assertEquals("FNB PDF", loaded.get(0).getSourceType());
        assertEquals(15, loaded.get(0).getItemCount());
        assertEquals("IMP-002", loaded.get(1).getImportId());
    }

    @Test
    void importLogsWithSpecialCharsInFilename() throws IOException {
        LocalDateTime ts = LocalDateTime.of(2025, 3, 1, 9, 0, 0);
        List<ImportLog> logs = List.of(
            new ImportLog("IMP-003", ts, "statement, march \"2025\".pdf", "Bank PDF", 5)
        );

        storage.saveImportLogs(logs);
        List<ImportLog> loaded = storage.loadImportLogs();

        assertEquals(1, loaded.size());
        assertEquals("statement, march \"2025\".pdf", loaded.get(0).getSourceFile());
    }

    @Test
    void loadImportLogsReturnsEmptyWhenNoFile() throws IOException {
        assertTrue(storage.loadImportLogs().isEmpty());
    }

    // ======================== SETTINGS (currency, recurring income) ========================

    @Test
    void saveAndLoadCurrencySymbol() throws IOException {
        storage.saveCurrencySymbol("$");
        assertEquals("$", storage.loadCurrencySymbol());
    }

    @Test
    void loadCurrencySymbolDefaultsToR() {
        assertEquals("R", storage.loadCurrencySymbol());
    }

    @Test
    void saveAndLoadRecurringIncome() throws IOException {
        storage.saveRecurringIncome(15000.50);
        assertEquals(15000.50, storage.loadRecurringIncome(), 0.001);
    }

    @Test
    void loadRecurringIncomeDefaultsToZero() {
        assertEquals(0.0, storage.loadRecurringIncome());
    }

    @Test
    void multipleSettingsCoexist() throws IOException {
        storage.saveCurrencySymbol("€");
        storage.saveRecurringIncome(8000.0);

        assertEquals("€", storage.loadCurrencySymbol());
        assertEquals(8000.0, storage.loadRecurringIncome(), 0.001);
    }

    // ======================== EXCEL MIGRATION ========================

    @Test
    void migrateFromExcelWhenTxtMissingAndXlsxExists() throws IOException {
        // Create an xlsx file using ExcelExporter
        List<Expense> original = List.of(
            new Expense(42.0, "Food", LocalDate.of(2025, 5, 1), "Migration test")
        );
        String xlsxPath = tempDir.resolve("expenses.xlsx").toString();
        ExcelExporter.exportExpenses(original, xlsxPath);

        // No expenses.txt exists — migration should occur
        assertFalse(storage.expensesFileExists());
        boolean migrated = storage.migrateFromExcelIfNeeded();
        assertTrue(migrated);
        assertTrue(storage.expensesFileExists());

        // Verify migrated data
        List<Expense> loaded = storage.loadExpenses();
        assertEquals(1, loaded.size());
        assertEquals(42.0, loaded.get(0).getAmount());
        assertEquals("Migration test", loaded.get(0).getDescription());
    }

    @Test
    void migrateSkipsWhenTxtAlreadyExists() throws IOException {
        // Create both files
        storage.saveExpenses(List.of(new Expense(10.0, "Food", LocalDate.now(), "From txt")));
        ExcelExporter.exportExpenses(
            List.of(new Expense(99.0, "Other", LocalDate.now(), "From xlsx")),
            tempDir.resolve("expenses.xlsx").toString()
        );

        boolean migrated = storage.migrateFromExcelIfNeeded();
        assertFalse(migrated);

        // txt data should be unchanged
        List<Expense> loaded = storage.loadExpenses();
        assertEquals(1, loaded.size());
        assertEquals("From txt", loaded.get(0).getDescription());
    }

    @Test
    void migrateSkipsWhenNoXlsxExists() {
        assertFalse(storage.migrateFromExcelIfNeeded());
    }

    @Test
    void migratePreservesRecurringExpenses() throws IOException {
        RecurringExpense recurring = new RecurringExpense(
            150.0, "Utilities", LocalDate.of(2025, 1, 1), "Power",
            RecurrenceType.MONTHLY, LocalDate.of(2025, 12, 31)
        );
        ExcelExporter.exportExpenses(List.of(recurring), tempDir.resolve("expenses.xlsx").toString());

        storage.migrateFromExcelIfNeeded();
        List<Expense> loaded = storage.loadExpenses();

        assertEquals(1, loaded.size());
        assertInstanceOf(RecurringExpense.class, loaded.get(0));
        RecurringExpense r = (RecurringExpense) loaded.get(0);
        assertEquals(RecurrenceType.MONTHLY, r.getFrequency());
        assertEquals(LocalDate.of(2025, 12, 31), r.getEndDate());
    }

    @Test
    void migratePreservesImportId() throws IOException {
        Expense exp = new Expense(88.0, "Electronics", LocalDate.of(2025, 6, 1), "Gadget");
        exp.setImportId("IMPORT-XYZ");
        ExcelExporter.exportExpenses(List.of(exp), tempDir.resolve("expenses.xlsx").toString());

        storage.migrateFromExcelIfNeeded();
        List<Expense> loaded = storage.loadExpenses();

        assertEquals(1, loaded.size());
        assertEquals("IMPORT-XYZ", loaded.get(0).getImportId());
    }

    // ======================== DATA ISOLATION ========================

    @Test
    void differentDataTypesDoNotInterfere() throws IOException {
        // Save all data types, then verify each loads independently
        storage.saveExpenses(List.of(new Expense(10.0, "Food", LocalDate.now(), "Test")));
        storage.saveCategories(FXCollections.observableArrayList("A", "B"));
        storage.saveIncomes(Map.of(YearMonth.of(2025, 1), 5000.0));
        storage.saveBudgets(Map.of("Food", 2000.0));
        storage.saveCategorizationRules(Map.of("pizza", "Food"));
        storage.saveImportLogs(List.of(
            new ImportLog("ID1", LocalDateTime.now(), "f.pdf", "PDF", 3)
        ));
        storage.saveCurrencySymbol("$");
        storage.saveRecurringIncome(10000.0);

        assertEquals(1, storage.loadExpenses().size());
        assertEquals(2, storage.loadCategories().size());
        assertEquals(1, storage.loadIncomes().size());
        assertEquals(1, storage.loadBudgets().size());
        assertEquals(1, storage.loadCategorizationRules().size());
        assertEquals(1, storage.loadImportLogs().size());
        assertEquals("$", storage.loadCurrencySymbol());
        assertEquals(10000.0, storage.loadRecurringIncome(), 0.001);
    }

    // ======================== OVERWRITE BEHAVIOR ========================

    @Test
    void savingExpensesTwiceOverwritesCompletely() throws IOException {
        storage.saveExpenses(List.of(
            new Expense(10.0, "Food", LocalDate.now(), "First"),
            new Expense(20.0, "Food", LocalDate.now(), "Second")
        ));
        storage.saveExpenses(List.of(
            new Expense(99.0, "Other", LocalDate.now(), "Only")
        ));

        List<Expense> loaded = storage.loadExpenses();
        assertEquals(1, loaded.size());
        assertEquals("Only", loaded.get(0).getDescription());
    }

    @Test
    void savingCategoriesTwiceOverwritesCompletely() throws IOException {
        storage.saveCategories(FXCollections.observableArrayList("A", "B", "C"));
        storage.saveCategories(FXCollections.observableArrayList("X"));

        List<String> loaded = storage.loadCategories();
        assertEquals(1, loaded.size());
        assertEquals("X", loaded.get(0));
    }

    // ======================== LARGE DATA ========================

    @Test
    void handleLargeNumberOfExpenses() throws IOException {
        List<Expense> expenses = new ArrayList<>();
        for (int i = 0; i < 1000; i++) {
            expenses.add(new Expense(i + 0.99, "Cat" + (i % 10), LocalDate.of(2025, 1, 1).plusDays(i % 365), "Desc " + i));
        }

        storage.saveExpenses(expenses);
        List<Expense> loaded = storage.loadExpenses();

        assertEquals(1000, loaded.size());
        assertEquals(0.99, loaded.get(0).getAmount(), 0.001);
        assertEquals(999.99, loaded.get(999).getAmount(), 0.001);
    }
}
