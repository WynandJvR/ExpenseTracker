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
    private String lastSavedFilePath;

    public ExcelStorage() {
        File dir = new File(BASE_DIR);
        if (!dir.exists()) {
            dir.mkdirs();
        }
        lastSavedFilePath = DEFAULT_EXPENSES_FILE;
    }

    public void saveExpenses(List<Expense> expenses, String filePath) throws IOException {
        try (Workbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("Expenses");

            // Create header row
            Row headerRow = sheet.createRow(0);
            String[] headers = {"Amount", "Category", "Date", "Description", "IsRecurring", "Frequency", "EndDate", "ImportId"};
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
                if (expense instanceof RecurringExpense recurringExpense) {
                    row.createCell(4).setCellValue(true);
                    row.createCell(5).setCellValue(recurringExpense.getFrequency().toString());
                    row.createCell(6).setCellValue(recurringExpense.getEndDate() != null ? recurringExpense.getEndDate().toString() : "");
                } else {
                    row.createCell(4).setCellValue(false);
                    row.createCell(5).setCellValue("");
                    row.createCell(6).setCellValue("");
                    row.createCell(7).setCellValue(expense.getImportId() != null ? expense.getImportId() : "");
                }
            }

            // Auto-size columns
            for (int i = 0; i < headers.length; i++) {
                sheet.autoSizeColumn(i);
            }

            // Write to file
            try (FileOutputStream outputStream = new FileOutputStream(filePath)) {
                workbook.write(outputStream);
            }
        }
        lastSavedFilePath = filePath;
    }

    public void saveExpenses(List<Expense> expenses) throws IOException {
        saveExpenses(expenses, lastSavedFilePath);
    }

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
                    boolean isRecurring = row.getCell(4) != null && row.getCell(4).getBooleanCellValue();
                    if (isRecurring) {
                        RecurrenceType frequency = RecurrenceType.valueOf(row.getCell(5).getStringCellValue());
                        String endDateStr = row.getCell(6) != null ? row.getCell(6).getStringCellValue() : "";
                        LocalDate endDate = endDateStr.isEmpty() ? null : LocalDate.parse(endDateStr);
                        expenses.add(new RecurringExpense(amount, category, date, description, frequency, endDate));
                    } else {
                        Expense exp = new Expense(amount, category, date, description);
                        Cell importIdCell = row.getCell(7);
                        if (importIdCell != null && !importIdCell.getStringCellValue().isEmpty()) {
                            exp.setImportId(importIdCell.getStringCellValue());
                        }
                        expenses.add(exp);
                    }
                } catch (Exception e) {
                    System.err.println("Error parsing row " + row.getRowNum() + ": " + e.getMessage());
                }
            }
        }
        return expenses;
    }
}