package com.wyn.expensetracker;

import javafx.collections.FXCollections;
import javafx.collections.ObservableList;

import java.io.*;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.*;

public class FileStorage {
    private static final String BASE_DIR = System.getProperty("user.home") + File.separator + ".expenseTracker";
    private static final String EXPENSES_FILE = BASE_DIR + File.separator + "expenses.txt";
    private static final String CATEGORIES_FILE = BASE_DIR + File.separator + "categories.txt";
    private static final String INCOME_FILE = BASE_DIR + File.separator + "incomes.txt";
    private final ExcelStorage excelStorage = new ExcelStorage();

    public FileStorage() {
        File dir = new File(BASE_DIR);
        if (!dir.exists()) {
            dir.mkdirs();
        }
    }

    public void saveExpenses(List<Expense> expenses, String filePath) throws IOException {
        rotateBackups(filePath, 5);
        rotateBackups(EXPENSES_FILE, 5);
        excelStorage.saveExpenses(expenses, filePath);
        saveToTextFile(expenses);
    }

    private void rotateBackups(String filePath, int maxBackups) {
        File file = new File(filePath);
        if (!file.exists()) return;
        // Delete oldest backup
        File oldest = new File(filePath + "." + maxBackups);
        if (oldest.exists()) oldest.delete();
        // Rotate existing backups
        for (int i = maxBackups - 1; i >= 1; i--) {
            File from = new File(filePath + "." + i);
            File to = new File(filePath + "." + (i + 1));
            if (from.exists()) from.renameTo(to);
        }
        // Copy current to .1
        try {
            java.nio.file.Files.copy(file.toPath(), new File(filePath + ".1").toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            System.err.println("Backup rotation failed: " + e.getMessage());
        }
    }

    public void saveExpenses(List<Expense> expenses) throws IOException {
        saveExpenses(expenses, excelStorage.getLastSavedFilePath());
    }

    public List<Expense> loadExpenses() throws IOException {
        try {
            return excelStorage.loadExpenses();
        } catch (Exception e) {
            System.err.println("Failed to load from Excel, falling back to text file: " + e.getMessage());
            return loadFromTextFile();
        }
    }

    private void saveToTextFile(List<Expense> expenses) throws IOException {
        try (PrintWriter out = new PrintWriter(new FileWriter(EXPENSES_FILE))) {
            for (Expense expense : expenses) {
                if (expense instanceof RecurringExpense recurringExpense) {
                    // Save recurring expenses with all fields properly escaped
                    out.println(expense.getAmount() + "," +
                            escapeCsv(expense.getCategory()) + "," +
                            expense.getDate() + "," +
                            escapeCsv(expense.getDescription()) + "," +
                            "RECURRING," +
                            recurringExpense.getFrequency() + "," +
                            (recurringExpense.getEndDate() != null ? recurringExpense.getEndDate() : ""));
                } else {
                    // Save regular expenses
                    String importId = expense.getImportId() != null ? expense.getImportId() : "";
                    out.println(expense.getAmount() + "," +
                            escapeCsv(expense.getCategory()) + "," +
                            expense.getDate() + "," +
                            escapeCsv(expense.getDescription()) + "," +
                            "REGULAR," + importId);
                }
            }
        }
    }

    private List<Expense> loadFromTextFile() throws IOException {
        List<Expense> expenses = new ArrayList<>();
        File file = new File(EXPENSES_FILE);
        if (!file.exists()) {
            return expenses;
        }
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line;
            int lineNumber = 0;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                try {
                    String[] parts = splitCsv(line);
                    if (parts.length >= 5) {
                        double amount = Double.parseDouble(parts[0]);
                        if (amount <= 0) {
                            System.err.println("Invalid amount at line " + lineNumber + ": " + line);
                            continue;
                        }
                        String category = unescapeCsv(parts[1]);
                        LocalDate date = LocalDate.parse(parts[2]);
                        String description = unescapeCsv(parts[3]);
                        String type = parts[4];
                        
                        if ("RECURRING".equals(type) && parts.length >= 7) {
                            // Recurring expense
                            RecurrenceType frequency = RecurrenceType.valueOf(parts[5]);
                            LocalDate endDate = parts[6].isEmpty() ? null : LocalDate.parse(parts[6]);
                            expenses.add(new RecurringExpense(amount, category, date, description, frequency, endDate));
                        } else if ("REGULAR".equals(type)) {
                            // Regular expense
                            Expense exp = new Expense(amount, category, date, description);
                            if (parts.length >= 6 && !parts[5].isEmpty()) {
                                exp.setImportId(parts[5]);
                            }
                            expenses.add(exp);
                        } else {
                            System.err.println("Unknown expense type at line " + lineNumber + ": " + line);
                        }
                    } else {
                        System.err.println("Malformed line at " + lineNumber + ": " + line);
                    }
                } catch (Exception e) {
                    System.err.println("Error parsing line " + lineNumber + ": " + e.getMessage());
                }
            }
        }
        return expenses;
    }

    public void saveCategories(ObservableList<String> categories) throws IOException {
        try (PrintWriter out = new PrintWriter(new FileWriter(CATEGORIES_FILE))) {
            for (String category : categories) {
                out.println(escapeCsv(category));
            }
        }
    }

    public List<String> loadCategories() throws IOException {
        List<String> categories = new ArrayList<>();
        File file = new File(CATEGORIES_FILE);
        if (!file.exists()) {
            return categories;
        }
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.trim().isEmpty()) {
                    categories.add(unescapeCsv(line.trim()));
                }
            }
        }
        return categories;
    }

    public void saveIncomes(Map<YearMonth, Double> incomes) throws IOException {
        try (PrintWriter out = new PrintWriter(new FileWriter(INCOME_FILE))) {
            for (Map.Entry<YearMonth, Double> entry : incomes.entrySet()) {
                out.println(entry.getKey() + "," + entry.getValue());
            }
        }
    }

    public Map<YearMonth, Double> loadIncomes() throws IOException {
        Map<YearMonth, Double> incomes = new HashMap<>();
        File file = new File(INCOME_FILE);
        if (!file.exists()) {
            return incomes;
        }
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line;
            int lineNumber = 0;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                try {
                    String[] parts = splitCsv(line);
                    if (parts.length == 2) {
                        YearMonth yearMonth = YearMonth.parse(parts[0]);
                        double income = Double.parseDouble(parts[1]);
                        if (income < 0) {
                            System.err.println("Invalid income at line " + lineNumber + ": " + line);
                            continue;
                        }
                        incomes.put(yearMonth, income);
                    } else {
                        System.err.println("Malformed line at " + lineNumber + ": " + line);
                    }
                } catch (Exception e) {
                    System.err.println("Error parsing line " + lineNumber + ": " + e.getMessage());
                }
            }
        }
        return incomes;
    }

    private String escapeCsv(String value) {
        if (value == null) return "";
        if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }

    private String unescapeCsv(String value) {
        if (value == null) return "";
        if (value.startsWith("\"") && value.endsWith("\"")) {
            return value.substring(1, value.length() - 1).replace("\"\"", "\"");
        }
        return value;
    }

    private String[] splitCsv(String line) {
        List<String> parts = new ArrayList<>();
        boolean inQuotes = false;
        StringBuilder field = new StringBuilder();
        
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                if (inQuotes && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    // Double quote - add a single quote to the field
                    field.append('"');
                    i++; // Skip the next quote
                } else {
                    // Start or end of quoted field
                    inQuotes = !inQuotes;
                }
            } else if (c == ',' && !inQuotes) {
                parts.add(field.toString());
                field = new StringBuilder();
            } else {
                field.append(c);
            }
        }
        parts.add(field.toString());
        return parts.toArray(new String[0]);
    }
	
    private Map<String, String> loadSettings() {
        Map<String, String> settings = new HashMap<>();
        File file = new File(BASE_DIR + File.separator + "settings.txt");
        if (!file.exists()) return settings;
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = reader.readLine()) != null) {
                int eq = line.indexOf('=');
                if (eq > 0) {
                    settings.put(line.substring(0, eq).trim(), line.substring(eq + 1).trim());
                }
            }
        } catch (IOException e) {
            System.err.println("Error loading settings: " + e.getMessage());
        }
        return settings;
    }

    private void saveSetting(String key, String value) throws IOException {
        Map<String, String> settings = loadSettings();
        settings.put(key, value);
        try (PrintWriter out = new PrintWriter(new FileWriter(BASE_DIR + File.separator + "settings.txt"))) {
            for (Map.Entry<String, String> entry : settings.entrySet()) {
                out.println(entry.getKey() + "=" + entry.getValue());
            }
        }
    }

    public void saveCurrencySymbol(String symbol) throws IOException {
        saveSetting("currency", symbol);
    }

    public String loadCurrencySymbol() {
        return loadSettings().getOrDefault("currency", "R");
    }

    public void saveRecurringIncome(double amount) throws IOException {
        saveSetting("recurringIncome", String.valueOf(amount));
    }

    public double loadRecurringIncome() {
        try {
            return Double.parseDouble(loadSettings().getOrDefault("recurringIncome", "0"));
        } catch (NumberFormatException e) {
            return 0.0;
        }
    }

    public void saveBudgets(Map<String, Double> budgets) throws IOException {
        try (PrintWriter out = new PrintWriter(new FileWriter(BASE_DIR + File.separator + "budgets.txt"))) {
            for (Map.Entry<String, Double> entry : budgets.entrySet()) {
                out.println(escapeCsv(entry.getKey()) + "," + entry.getValue());
            }
        }
    }

    public Map<String, Double> loadBudgets() throws IOException {
        Map<String, Double> budgets = new HashMap<>();
        File file = new File(BASE_DIR + File.separator + "budgets.txt");
        if (!file.exists()) {
            return budgets;
        }
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line;
            int lineNumber = 0;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                try {
                    String[] parts = splitCsv(line);
                    if (parts.length == 2) {
                        String category = unescapeCsv(parts[0]);
                        double budget = Double.parseDouble(parts[1]);
                        if (budget >= 0) {
                            budgets.put(category, budget);
                        }
                    } else {
                        System.err.println("Malformed budget line at " + lineNumber + ": " + line);
                    }
                } catch (Exception e) {
                    System.err.println("Error parsing budget line " + lineNumber + ": " + e.getMessage());
                }
            }
        }
        return budgets;
    }

    public void saveCategorizationRules(Map<String, String> rules) throws IOException {
        try (PrintWriter out = new PrintWriter(new FileWriter(BASE_DIR + File.separator + "categorization_rules.txt"))) {
            for (Map.Entry<String, String> entry : rules.entrySet()) {
                out.println(escapeCsv(entry.getKey()) + "," + escapeCsv(entry.getValue()));
            }
        }
    }

    public Map<String, String> loadCategorizationRules() throws IOException {
        Map<String, String> rules = new LinkedHashMap<>();
        File file = new File(BASE_DIR + File.separator + "categorization_rules.txt");
        if (!file.exists()) {
            return rules;
        }
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.trim().isEmpty()) {
                    String[] parts = splitCsv(line);
                    if (parts.length == 2) {
                        rules.put(unescapeCsv(parts[0]), unescapeCsv(parts[1]));
                    }
                }
            }
        }
        return rules;
    }

    public void saveImportLogs(List<ImportLog> logs) throws IOException {
        try (PrintWriter out = new PrintWriter(new FileWriter(BASE_DIR + File.separator + "import_log.txt"))) {
            for (ImportLog log : logs) {
                out.println(escapeCsv(log.getImportId()) + "," +
                    log.getTimestamp() + "," +
                    escapeCsv(log.getSourceFile()) + "," +
                    escapeCsv(log.getSourceType()) + "," +
                    log.getItemCount());
            }
        }
    }

    public List<ImportLog> loadImportLogs() throws IOException {
        List<ImportLog> logs = new ArrayList<>();
        File file = new File(BASE_DIR + File.separator + "import_log.txt");
        if (!file.exists()) return logs;
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.trim().isEmpty()) continue;
                try {
                    String[] parts = splitCsv(line);
                    if (parts.length >= 5) {
                        String importId = unescapeCsv(parts[0]);
                        LocalDateTime timestamp = LocalDateTime.parse(parts[1]);
                        String sourceFile = unescapeCsv(parts[2]);
                        String sourceType = unescapeCsv(parts[3]);
                        int itemCount = Integer.parseInt(parts[4]);
                        logs.add(new ImportLog(importId, timestamp, sourceFile, sourceType, itemCount));
                    }
                } catch (Exception e) {
                    System.err.println("Error parsing import log: " + e.getMessage());
                }
            }
        }
        return logs;
    }

	public ExcelStorage getExcelStorage() {
    return excelStorage;
}
}