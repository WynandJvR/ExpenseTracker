package com.wyn.expensetracker;

import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;

public class ExcelStorage {
    private static final String BASE_DIR = System.getProperty("user.home") + File.separator + "ExpenseTracker";
    private static final String EXPENSES_FILE = BASE_DIR + File.separator + "expenses.xlsx";
    private static final String BACKUP_FILE = BASE_DIR + File.separator + "expenses_backup.xlsx";
    private static final String CATEGORIES_FILE = BASE_DIR + File.separator + "categories.txt";
    private final ReentrantLock fileLock = new ReentrantLock();

    public ExcelStorage() {
        File dir = new File(BASE_DIR);
        System.out.println("Data directory: " + dir.getAbsolutePath());
        if (!dir.exists() && !dir.mkdirs()) {
            throw new RuntimeException("Failed to create directory: " + BASE_DIR);
        }
    }

    public void saveExpenses(List<Expense> expenses) throws IOException {
        saveExpenses(expenses, EXPENSES_FILE);
    }

    public void saveExpenses(List<Expense> expenses, String filePath) throws IOException {
        // Prevent saving empty list to avoid overwriting valid data
        if (expenses.isEmpty()) {
            System.out.println("Skipping save: Expense list is empty");
            return;
        }

        fileLock.lock();
        try {
            System.out.println("Saving " + expenses.size() + " expenses to " + filePath);
            
            // 1. First save to temporary file
            File tempFile = File.createTempFile("expenses_temp", ".xlsx", new File(BASE_DIR));
            
            try (Workbook workbook = new XSSFWorkbook();
                 FileOutputStream outputStream = new FileOutputStream(tempFile)) {
                
                Sheet sheet = workbook.createSheet("Expenses");

                // Create header row
                Row headerRow = sheet.createRow(0);
                String[] headers = {"Amount", "Category", "Date", "Description", "IsRecurring", "Frequency", "EndDate"};
                for (int i = 0; i < headers.length; i++) {
                    headerRow.createCell(i).setCellValue(headers[i]);
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
                        row.createCell(6).setCellValue(recurringExpense.getEndDate() != null ? 
                            recurringExpense.getEndDate().toString() : "");
                    } else {
                        row.createCell(4).setCellValue(false);
                        row.createCell(5).setCellValue("");
                        row.createCell(6).setCellValue("");
                    }
                }

                // Auto-size columns
                for (int i = 0; i < headers.length; i++) {
                    sheet.autoSizeColumn(i);
                }

                workbook.write(outputStream);
                outputStream.flush();
            }

            // 2. Verify the temporary file
            if (!tempFile.exists() || tempFile.length() == 0) {
                throw new IOException("Temporary file was not created properly");
            }

            // 3. Create backup of current file if exists
            File targetFile = new File(filePath);
            if (targetFile.exists()) {
                Files.copy(targetFile.toPath(), new File(BACKUP_FILE).toPath(), 
                          StandardCopyOption.REPLACE_EXISTING);
            }

            // 4. Atomic move of temp file to target
            Files.move(tempFile.toPath(), targetFile.toPath(), 
                      StandardCopyOption.REPLACE_EXISTING,
                      StandardCopyOption.ATOMIC_MOVE);
            
            System.out.println("Successfully saved expenses to " + targetFile.getAbsolutePath());
        } finally {
            fileLock.unlock();
        }
    }

    public List<Expense> loadExpenses() throws IOException {
        fileLock.lock();
        try {
            File primaryFile = new File(EXPENSES_FILE);
            File backupFile = new File(BACKUP_FILE);

            // Enhanced logging for debugging
            System.out.println("Attempting to load from primary file: " + primaryFile.getAbsolutePath());
            if (primaryFile.exists() && isValidExcelFile(primaryFile)) {
                List<Expense> expenses = parseExcelFile(primaryFile);
                System.out.println("Loaded " + expenses.size() + " expenses from primary file");
                return expenses;
            }
            
            System.out.println("Primary file invalid or missing, checking backup: " + backupFile.getAbsolutePath());
            if (backupFile.exists() && isValidExcelFile(backupFile)) {
                System.err.println("Using backup file - primary was corrupted");
                Files.copy(backupFile.toPath(), primaryFile.toPath(), 
                          StandardCopyOption.REPLACE_EXISTING);
                List<Expense> expenses = parseExcelFile(primaryFile);
                System.out.println("Loaded " + expenses.size() + " expenses from backup file");
                return expenses;
            }

            System.out.println("No valid data found in primary or backup, returning empty list");
            return new ArrayList<>();
        } finally {
            fileLock.unlock();
        }
    }

    private boolean isValidExcelFile(File file) {
        try (InputStream is = new FileInputStream(file);
             Workbook workbook = WorkbookFactory.create(is)) {
            boolean isValid = workbook.getNumberOfSheets() > 0;
            System.out.println("Validating " + file.getName() + ": " + 
                              (isValid ? "Valid, sheets: " + workbook.getNumberOfSheets() : "Invalid"));
            return isValid;
        } catch (Exception e) {
            System.err.println("Invalid Excel file: " + file.getName() + " - " + e.getMessage());
            return false;
        }
    }

    private List<Expense> parseExcelFile(File file) throws IOException {
        List<Expense> expenses = new ArrayList<>();
        try (InputStream is = new FileInputStream(file);
             Workbook workbook = WorkbookFactory.create(is)) {
             
            Sheet sheet = workbook.getSheetAt(0);
            if (sheet == null) {
                System.err.println("No sheet found in workbook");
                return expenses;
            }

            for (Row row : sheet) {
                if (row.getRowNum() == 0) continue; // Skip header

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
                        expenses.add(new Expense(amount, category, date, description));
                    }
                } catch (Exception e) {
                    System.err.println("Error parsing row " + row.getRowNum() + ": " + e.getMessage());
                }
            }
        }
        return expenses;
    }

    public void saveCategories(List<String> categories) throws IOException {
        try (PrintWriter writer = new PrintWriter(new FileWriter(CATEGORIES_FILE))) {
            for (String category : categories) {
                writer.println(category);
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
}