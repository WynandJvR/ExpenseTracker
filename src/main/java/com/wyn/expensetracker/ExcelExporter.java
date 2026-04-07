package com.wyn.expensetracker;

import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import java.io.*;
import java.util.List;

public class ExcelExporter {

    public static void exportExpenses(List<Expense> expenses, String filePath) throws IOException {
        exportExpenses(expenses, null, null, filePath);
    }

    public static void exportExpenses(List<Expense> expenses, List<Debt> debts,
                                       List<DebtPayment> debtPayments, String filePath) throws IOException {
        try (Workbook workbook = new XSSFWorkbook()) {
            // --- Expenses sheet ---
            Sheet sheet = workbook.createSheet("Expenses");

            Row headerRow = sheet.createRow(0);
            String[] headers = {"Amount", "Category", "Date", "Description", "IsRecurring",
                "Frequency", "EndDate", "ImportId", "IsIncome", "Currency", "ReceiptPath"};
            for (int i = 0; i < headers.length; i++) {
                Cell cell = headerRow.createCell(i);
                cell.setCellValue(headers[i]);
            }

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
                    row.createCell(8).setCellValue(expense.isIncome());
                }
                row.createCell(9).setCellValue(expense.getCurrency() != null ? expense.getCurrency() : "");
                row.createCell(10).setCellValue(expense.getReceiptPath() != null ? expense.getReceiptPath() : "");
            }

            for (int i = 0; i < headers.length; i++) {
                sheet.autoSizeColumn(i);
            }

            // --- Debts sheet ---
            if (debts != null && !debts.isEmpty()) {
                Sheet debtSheet = workbook.createSheet("Debts");
                Row dHeader = debtSheet.createRow(0);
                String[] dHeaders = {"Name", "Principal", "AnnualRate", "TermMonths",
                    "StartDate", "MonthlyPayment", "TotalCost", "TotalInterest", "Currency"};
                for (int i = 0; i < dHeaders.length; i++) {
                    dHeader.createCell(i).setCellValue(dHeaders[i]);
                }
                int dRow = 1;
                for (Debt d : debts) {
                    Row row = debtSheet.createRow(dRow++);
                    row.createCell(0).setCellValue(d.getName());
                    row.createCell(1).setCellValue(d.getPrincipal());
                    row.createCell(2).setCellValue(d.getAnnualRate());
                    row.createCell(3).setCellValue(d.getTermMonths());
                    row.createCell(4).setCellValue(d.getStartDate().toString());
                    row.createCell(5).setCellValue(d.getMonthlyPayment());
                    row.createCell(6).setCellValue(d.getTotalCost());
                    row.createCell(7).setCellValue(d.getTotalInterest());
                    row.createCell(8).setCellValue(d.getCurrency() != null ? d.getCurrency() : "");
                }
                for (int i = 0; i < dHeaders.length; i++) {
                    debtSheet.autoSizeColumn(i);
                }
            }

            // --- Debt Payments sheet ---
            if (debtPayments != null && !debtPayments.isEmpty()) {
                Sheet paySheet = workbook.createSheet("Debt Payments");
                Row pHeader = paySheet.createRow(0);
                String[] pHeaders = {"DebtId", "Amount", "Date", "Note"};
                for (int i = 0; i < pHeaders.length; i++) {
                    pHeader.createCell(i).setCellValue(pHeaders[i]);
                }
                int pRow = 1;
                for (DebtPayment p : debtPayments) {
                    Row row = paySheet.createRow(pRow++);
                    row.createCell(0).setCellValue(p.getDebtId());
                    row.createCell(1).setCellValue(p.getAmount());
                    row.createCell(2).setCellValue(p.getDate().toString());
                    row.createCell(3).setCellValue(p.getNote() != null ? p.getNote() : "");
                }
                for (int i = 0; i < pHeaders.length; i++) {
                    paySheet.autoSizeColumn(i);
                }
            }

            try (FileOutputStream outputStream = new FileOutputStream(filePath)) {
                workbook.write(outputStream);
            }
        }
    }
}
