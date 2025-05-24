package com.wyn.expensetracker;

import javafx.collections.FXCollections;
import javafx.collections.ObservableList;

import java.io.*;
import java.time.LocalDate;
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
        excelStorage.saveExpenses(expenses, filePath);
        saveToTextFile(expenses);
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
                    out.println(escapeCsv(expense.getAmount() + "," +
                            escapeCsv(expense.getCategory()) + "," +
                            expense.getDate() + "," +
                            escapeCsv(expense.getDescription()) + "," +
                            recurringExpense.getFrequency() + "," +
                            (recurringExpense.getEndDate() != null ? recurringExpense.getEndDate() : "")));
                } else {
                    out.println(expense.getAmount() + "," +
                            escapeCsv(expense.getCategory()) + "," +
                            expense.getDate() + "," +
                            escapeCsv(expense.getDescription()));
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
                    if (parts.length >= 4) {
                        double amount = Double.parseDouble(parts[0]);
                        if (amount <= 0) {
                            System.err.println("Invalid amount at line " + lineNumber + ": " + line);
                            continue;
                        }
                        String category = parts[1];
                        LocalDate date = LocalDate.parse(parts[2]);
                        String description = parts[3];
                        if (parts.length >= 6) { // Recurring expense
                            RecurrenceType frequency = RecurrenceType.valueOf(parts[4]);
                            LocalDate endDate = parts[5].isEmpty() ? null : LocalDate.parse(parts[5]);
                            expenses.add(new RecurringExpense(amount, category, date, description, frequency, endDate));
                        } else {
                            expenses.add(new Expense(amount, category, date, description));
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
                    categories.add(line.trim());
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

    private String[] splitCsv(String line) {
        List<String> parts = new ArrayList<>();
        boolean inQuotes = false;
        StringBuilder field = new StringBuilder();
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"' && (i == 0 || line.charAt(i - 1) != '\\')) {
                inQuotes = !inQuotes;
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
}