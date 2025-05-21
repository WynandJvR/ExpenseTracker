package com.wyn.expensetracker;

import javafx.collections.ObservableList;
import java.io.*;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

public class FileStorage {
    private static final String BASE_DIR = System.getProperty("user.home") + File.separator + ".expenseTracker";
    private static final String EXPENSES_FILE = BASE_DIR + File.separator + "expenses.xlsx";
    private static final String CATEGORIES_FILE = BASE_DIR + File.separator + "categories.txt";
    private static final String INCOME_FILE = BASE_DIR + File.separator + "incomes.txt";

    public FileStorage() {
        File dir = new File(BASE_DIR);
        if (!dir.exists()) {
            dir.mkdirs();
        }
    }

    public void saveExpenses(List<Expense> expenses) throws IOException {
        try (Workbook workbook = new XSSFWorkbook();
             FileOutputStream fileOut = new FileOutputStream(EXPENSES_FILE)) {
            Sheet sheet = workbook.createSheet("Expenses");

            // Create header row
            Row headerRow = sheet.createRow(0);
            String[] headers = {"Amount", "Category", "Date", "Description"};
            for (int i = 0; i < headers.length; i++) {
                Cell cell = headerRow.createCell(i);
                cell.setCellValue(headers[i]);
            }

            // Fill data rows
            for (int i = 0; i < expenses.size(); i++) {
                Row row = sheet.createRow(i + 1);
                Expense expense = expenses.get(i);
                row.createCell(0).setCellValue(expense.getAmount());
                row.createCell(1).setCellValue(expense.getCategory());
                row.createCell(2).setCellValue(expense.getDate().toString());
                row.createCell(3).setCellValue(expense.getDescription());
            }

            // Auto-size columns
            for (int i = 0; i < headers.length; i++) {
                sheet.autoSizeColumn(i);
            }

            workbook.write(fileOut);
        }
    }

    public List<Expense> loadExpenses() throws IOException {
        List<Expense> expenses = new ArrayList<>();
        File file = new File(EXPENSES_FILE);
        if (!file.exists()) {
            return expenses;
        }
        try (Workbook workbook = new XSSFWorkbook(new FileInputStream(file))) {
            Sheet sheet = workbook.getSheet("Expenses");
            if (sheet == null) return expenses;

            for (int i = 1; i <= sheet.getLastRowNum(); i++) {
                Row row = sheet.getRow(i);
                if (row != null) {
                    try {
                        double amount = row.getCell(0).getNumericCellValue();
                        if (amount <= 0) continue;
                        String category = row.getCell(1).getStringCellValue();
                        LocalDate date = LocalDate.parse(row.getCell(2).getStringCellValue());
                        String description = row.getCell(3) != null ? row.getCell(3).getStringCellValue() : "";
                        expenses.add(new Expense(amount, category, date, description));
                    } catch (Exception e) {
                        System.err.println("Error parsing row " + i + ": " + e.getMessage());
                    }
                }
            }
        }
        return expenses;
    }

    public void saveCategories(ObservableList<String> categories) throws IOException {
        try (PrintWriter out = new PrintWriter(new FileWriter(CATEGORIES_FILE))) {
            for (String category : categories) {
                out.println(category);
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
                    String[] parts = line.split(",");
                    if (parts.length == 2) {
                        YearMonth yearMonth = YearMonth.parse(parts[0]);
                        double income = Double.parseDouble(parts[1]);
                        if (income < 0) continue;
                        incomes.put(yearMonth, income);
                    }
                } catch (Exception e) {
                    System.err.println("Error parsing line " + lineNumber + ": " + e.getMessage());
                }
            }
        }
        return incomes;
    }
}