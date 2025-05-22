package com.wyn.expensetracker;

import java.io.File;
import java.time.LocalDate;
import java.util.Scanner;

public class ConsoleApp {
    public static void main(String[] args) {
        ExpenseManager manager = new ExpenseManager();
        ExcelStorage storage = new ExcelStorage();
        Scanner scanner = new Scanner(System.in);

        // Load existing expenses
        try {
            manager.getExpenses().addAll(storage.loadExpenses());
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
                    // Existing add expense logic...
                } else if (choice == 2) {
                    // Existing list expenses logic...
                } else if (choice == 3) {
                    // Existing view total by category logic...
                } else if (choice == 4) {
                    try {
                        storage.saveExpenses(manager.getExpenses());
                        System.out.println("Expenses exported to Excel successfully!");
                        System.out.println("Location: " + System.getProperty("user.home") + 
                            File.separator + ".expenseTracker" + File.separator + "expenses.xlsx");
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