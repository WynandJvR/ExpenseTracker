package com.wyn.expensetracker;

import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import java.io.*;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

public class ExcelStorage {
    private static final String BASE_DIR = System.getProperty("user.home") + File.separator + ".expenseTracker";
    private static final String DEFAULT_EXPENSES_FILE = BASE_DIR + File.separator + "expenses.xlsx";
    private String lastSavedFilePath; // Store the last saved file path

    public ExcelStorage() {
        File dir = new File(BASE_DIR);
        if (!dir.exists()) {
            dir.mkdirs();
        }
        lastSavedFilePath = DEFAULT_EXPENSES_FILE; // Default path
    }

    // Modified saveExpenses to accept a file path
    public void saveExpenses(List<Expense> expenses, String filePath) throws IOException {
        Workbook workbook = new XSSFWorkbook();
        Sheet sheet = workbook.createSheet("Expenses");

        // Create header row
        Row headerRow = sheet.createRow(0);
        String[] headers = {"Amount", "Category", "Date", "Description"};
        for (int i = 0; i < headers.length; i++) {
            Cell cell = headerRow.createCell(i);
            cell.setCellValue(headers[i]);
        }

        // Create data rows
        int rowNum = 1;
        for (Expense expense : expenses) {
            Row row = sheet.createRow(rowNum++);
            row.createCell(0).setCellValue(expense.getAmount());
            row.createCell(1).setCellValue(expense.getCategory());
            row.createCell(2).setCellValue(expense.getDate().toString());
            row.createCell(3).setCellValue(expense.getDescription());
        }

        // Auto-size columns
        for (int i = 0; i < headers.length; i++) {
            sheet.autoSizeColumn(i);
        }

        // Write to file
        try (FileOutputStream outputStream = new FileOutputStream(filePath)) {
            workbook.write(outputStream);
        }
        workbook.close();
        lastSavedFilePath = filePath; // Update the last saved file path
    }

    // Original saveExpenses method for backward compatibility
    public void saveExpenses(List<Expense> expenses) throws IOException {
        saveExpenses(expenses, lastSavedFilePath);
    }

    // Getter for last saved file path
    public String getLastSavedFilePath() {
        return lastSavedFilePath;
    }

    public List<Expense> loadExpenses() throws IOException {
        List<Expense> expenses = new ArrayList<>();
        File file = new File(lastSavedFilePath);
        if (!file.exists()) {
            return expenses;
        }

        try (Workbook workbook = WorkbookFactory.create(file)) {
            Sheet sheet = workbook.getSheetAt(0);
            for (Row row : sheet) {
                if (row.getRowNum() == 0) continue; // Skip header row

                try {
                    double amount = row.getCell(0).getNumericCellValue();
                    String category = row.getCell(1).getStringCellValue();
                    LocalDate date = LocalDate.parse(row.getCell(2).getStringCellValue());
                    String description = row.getCell(3) != null ? row.getCell(3).getStringCellValue() : "";

                    expenses.add(new Expense(amount, category, date, description));
                } catch (Exception e) {
                    System.err.println("Error parsing row " + row.getRowNum() + ": " + e.getMessage());
                }
            }
        }
        return expenses;
    }
}