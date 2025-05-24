package com.wyn.expensetracker;

import java.io.File;
import java.time.LocalDate;
import java.util.Map;
import java.util.Scanner;
import java.util.stream.Collectors;

public class ConsoleApp {
    public static void main(String[] args) {
        ExpenseManager manager = new ExpenseManager();
        ExcelStorage storage = new ExcelStorage();
        Scanner scanner = new Scanner(System.in);

        // Load existing expenses
        try {
            manager.getExpenses().addAll(storage.loadExpenses());
            manager.generateRecurringExpenses(LocalDate.now()); // Generate recurring expenses up to today
            System.out.println("Loaded " + manager.getExpenses().size() + " expenses from Excel");
        } catch (Exception e) {
            System.out.println("Error loading expenses: " + e.getMessage());
        }

        while (true) {
            System.out.println("1. Add Expense\n2. List Expenses\n3. View Total by Category\n4. Export to Excel\n5. Exit");
            System.out.print("Choose an option: ");
            try {
                int choice = scanner.nextInt();
                scanner.nextLine(); // Consume newline

                if (choice == 1) {
                    System.out.print("Enter amount: ");
                    double amount = scanner.nextDouble();
                    if (amount <= 0) {
                        System.out.println("Amount must be positive");
                        continue;
                    }
                    scanner.nextLine(); // Consume newline

                    System.out.print("Enter category: ");
                    String category = scanner.nextLine().trim();
                    if (category.isEmpty()) {
                        System.out.println("Category cannot be empty");
                        continue;
                    }

                    System.out.print("Enter date (yyyy-MM-dd): ");
                    String dateStr = scanner.nextLine();
                    LocalDate date;
                    try {
                        date = LocalDate.parse(dateStr);
                    } catch (Exception e) {
                        System.out.println("Invalid date format. Use yyyy-MM-dd");
                        continue;
                    }

                    System.out.print("Enter description (optional): ");
                    String description = scanner.nextLine().trim();

                    System.out.print("Is this a recurring expense? (y/n): ");
                    String recurringInput = scanner.nextLine().trim().toLowerCase();
                    Expense expense;
                    if (recurringInput.equals("y")) {
                        System.out.println("Enter frequency (DAILY, WEEKLY, MONTHLY, YEARLY): ");
                        String frequencyStr = scanner.nextLine().trim().toUpperCase();
                        RecurrenceType frequency;
                        try {
                            frequency = RecurrenceType.valueOf(frequencyStr);
                        } catch (IllegalArgumentException e) {
                            System.out.println("Invalid frequency. Use DAILY, WEEKLY, MONTHLY, or YEARLY");
                            continue;
                        }

                        System.out.print("Enter end date (yyyy-MM-dd, or press Enter for none): ");
                        String endDateStr = scanner.nextLine().trim();
                        LocalDate endDate = endDateStr.isEmpty() ? null : LocalDate.parse(endDateStr);

                        expense = new RecurringExpense(amount, category, date, description, frequency, endDate);
                    } else {
                        expense = new Expense(amount, category, date, description);
                    }

                    manager.addExpense(expense);
                    try {
                        storage.saveExpenses(manager.getExpenses());
                        manager.generateRecurringExpenses(LocalDate.now());
                        storage.saveExpenses(manager.getExpenses());
                        System.out.println("Expense added successfully!");
                    } catch (Exception ex) {
                        manager.getExpenses().remove(expense);
                        System.out.println("Error saving expense: " + ex.getMessage());
                    }
                } else if (choice == 2) {
                    if (manager.getExpenses().isEmpty()) {
                        System.out.println("No expenses to display.");
                    } else {
                        for (Expense exp : manager.getExpenses()) {
                            System.out.println(exp);
                        }
                    }
                } else if (choice == 3) {
                    Map<String, Double> categoryTotals = manager.getExpenses().stream()
                        .collect(Collectors.groupingBy(
                            Expense::getCategory,
                            Collectors.summingDouble(Expense::getAmount)
                        ));
                    for (Map.Entry<String, Double> entry : categoryTotals.entrySet()) {
                        System.out.printf("Category: %s, Total: %.2f%n", entry.getKey(), entry.getValue());
                    }
                } else if (choice == 4) {
                    try {
                        File defaultFile = new File(System.getProperty("user.home") + File.separator + ".expenseTracker" + File.separator + "expenses.xlsx");
                        String filePath;

                        if (!defaultFile.exists()) {
                            System.out.print("Enter the path to save the Excel file (or press Enter for default: " +
                                             defaultFile.getAbsolutePath() + "): ");
                            String inputPath = scanner.nextLine().trim();
                            filePath = inputPath.isEmpty() ? defaultFile.getAbsolutePath() : inputPath;
                            if (!filePath.toLowerCase().endsWith(".xlsx")) {
                                filePath += ".xlsx";
                            }
                            File parentDir = new File(filePath).getParentFile();
                            if (parentDir != null && !parentDir.exists()) {
                                parentDir.mkdirs();
                            }
                        } else {
                            filePath = defaultFile.getAbsolutePath();
                        }

                        storage.saveExpenses(manager.getExpenses(), filePath);
                        System.out.println("Expenses exported to Excel successfully!");
                        System.out.println("Location: " + filePath);
                    } catch (Exception ex) {
                        System.out.println("Error exporting to Excel: " + ex.getMessage());
                    }
                } else if (choice == 5) {
                    break;
                } else {
                    System.out.println("Invalid option. Please choose 1-5.");
                }
            } catch (Exception e) {
                System.out.println("Invalid input. Please enter a number.");
                scanner.nextLine();
            }
        }
        scanner.close();
    }
}