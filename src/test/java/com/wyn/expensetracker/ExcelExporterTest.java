package com.wyn.expensetracker;

import org.apache.poi.ss.usermodel.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ExcelExporterTest {

    @TempDir
    Path tempDir;

    @Test
    void exportCreatesValidXlsxFile() throws IOException {
        String filePath = tempDir.resolve("test.xlsx").toString();
        List<Expense> expenses = List.of(
            new Expense(50.0, "Food", LocalDate.of(2025, 1, 15), "Groceries"),
            new Expense(30.0, "Transport", LocalDate.of(2025, 1, 16), "Bus")
        );

        ExcelExporter.exportExpenses(expenses, filePath);

        assertTrue(new File(filePath).exists());
        try (Workbook wb = WorkbookFactory.create(new File(filePath))) {
            Sheet sheet = wb.getSheetAt(0);
            assertEquals("Expenses", sheet.getSheetName());

            // Header row
            Row header = sheet.getRow(0);
            assertEquals("Amount", header.getCell(0).getStringCellValue());
            assertEquals("Category", header.getCell(1).getStringCellValue());
            assertEquals("Date", header.getCell(2).getStringCellValue());
            assertEquals("Description", header.getCell(3).getStringCellValue());

            // Data rows
            Row row1 = sheet.getRow(1);
            assertEquals(50.0, row1.getCell(0).getNumericCellValue());
            assertEquals("Food", row1.getCell(1).getStringCellValue());
            assertEquals("2025-01-15", row1.getCell(2).getStringCellValue());
            assertEquals("Groceries", row1.getCell(3).getStringCellValue());
            assertFalse(row1.getCell(4).getBooleanCellValue());

            Row row2 = sheet.getRow(2);
            assertEquals(30.0, row2.getCell(0).getNumericCellValue());
        }
    }

    @Test
    void exportRecurringExpense() throws IOException {
        String filePath = tempDir.resolve("recurring.xlsx").toString();
        RecurringExpense recurring = new RecurringExpense(
            100.0, "Utilities", LocalDate.of(2025, 1, 1), "Electricity",
            RecurrenceType.MONTHLY, LocalDate.of(2025, 12, 31)
        );

        ExcelExporter.exportExpenses(List.of(recurring), filePath);

        try (Workbook wb = WorkbookFactory.create(new File(filePath))) {
            Row row = wb.getSheetAt(0).getRow(1);
            assertTrue(row.getCell(4).getBooleanCellValue());
            assertEquals("MONTHLY", row.getCell(5).getStringCellValue());
            assertEquals("2025-12-31", row.getCell(6).getStringCellValue());
        }
    }

    @Test
    void exportRecurringExpenseWithNoEndDate() throws IOException {
        String filePath = tempDir.resolve("no_end.xlsx").toString();
        RecurringExpense recurring = new RecurringExpense(
            50.0, "Entertainment", LocalDate.of(2025, 2, 1), "Netflix",
            RecurrenceType.MONTHLY, null
        );

        ExcelExporter.exportExpenses(List.of(recurring), filePath);

        try (Workbook wb = WorkbookFactory.create(new File(filePath))) {
            Row row = wb.getSheetAt(0).getRow(1);
            assertTrue(row.getCell(4).getBooleanCellValue());
            assertEquals("", row.getCell(6).getStringCellValue());
        }
    }

    @Test
    void exportWithImportId() throws IOException {
        String filePath = tempDir.resolve("import_id.xlsx").toString();
        Expense exp = new Expense(75.0, "Food", LocalDate.of(2025, 3, 1), "Imported");
        exp.setImportId("PDF-001");

        ExcelExporter.exportExpenses(List.of(exp), filePath);

        try (Workbook wb = WorkbookFactory.create(new File(filePath))) {
            Row row = wb.getSheetAt(0).getRow(1);
            assertFalse(row.getCell(4).getBooleanCellValue());
            assertEquals("PDF-001", row.getCell(7).getStringCellValue());
        }
    }

    @Test
    void exportEmptyList() throws IOException {
        String filePath = tempDir.resolve("empty.xlsx").toString();

        ExcelExporter.exportExpenses(List.of(), filePath);

        assertTrue(new File(filePath).exists());
        try (Workbook wb = WorkbookFactory.create(new File(filePath))) {
            Sheet sheet = wb.getSheetAt(0);
            // Only header row
            assertEquals(0, sheet.getLastRowNum());
            assertNotNull(sheet.getRow(0));
        }
    }

    @Test
    void exportMixedExpenses() throws IOException {
        String filePath = tempDir.resolve("mixed.xlsx").toString();
        Expense regular = new Expense(25.0, "Food", LocalDate.of(2025, 1, 1), "Lunch");
        RecurringExpense recurring = new RecurringExpense(
            200.0, "Rent", LocalDate.of(2025, 1, 1), "Monthly rent",
            RecurrenceType.MONTHLY, null
        );
        Expense imported = new Expense(99.99, "Electronics", LocalDate.of(2025, 1, 5), "Headphones");
        imported.setImportId("IMP-007");

        ExcelExporter.exportExpenses(List.of(regular, recurring, imported), filePath);

        try (Workbook wb = WorkbookFactory.create(new File(filePath))) {
            Sheet sheet = wb.getSheetAt(0);
            assertEquals(3, sheet.getLastRowNum()); // 3 data rows

            // Regular
            assertFalse(sheet.getRow(1).getCell(4).getBooleanCellValue());
            // Recurring
            assertTrue(sheet.getRow(2).getCell(4).getBooleanCellValue());
            assertEquals("MONTHLY", sheet.getRow(2).getCell(5).getStringCellValue());
            // Imported
            assertEquals("IMP-007", sheet.getRow(3).getCell(7).getStringCellValue());
        }
    }

    @Test
    void exportOverwritesExistingFile() throws IOException {
        String filePath = tempDir.resolve("overwrite.xlsx").toString();

        ExcelExporter.exportExpenses(
            List.of(new Expense(10.0, "A", LocalDate.now(), "First")), filePath
        );
        ExcelExporter.exportExpenses(
            List.of(new Expense(20.0, "B", LocalDate.now(), "Second")), filePath
        );

        try (Workbook wb = WorkbookFactory.create(new File(filePath))) {
            Sheet sheet = wb.getSheetAt(0);
            assertEquals(1, sheet.getLastRowNum());
            assertEquals(20.0, sheet.getRow(1).getCell(0).getNumericCellValue());
        }
    }
}
