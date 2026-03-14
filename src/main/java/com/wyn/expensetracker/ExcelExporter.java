package com.wyn.expensetracker;

import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import java.io.*;
import java.util.List;

public class ExcelExporter {

    public static void exportExpenses(List<Expense> expenses, String filePath) throws IOException {
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
    }
}
