package com.wyn.expensetracker;

import java.time.LocalDate;
import java.util.Scanner;

public class ConsoleApp {
    public static void main(String[] args) {
        ExpenseManager manager = new ExpenseManager();
        FileStorage storage = new FileStorage();
        Scanner scanner = new Scanner(System.in);

        // Load existing expenses
        try {
            manager.getExpenses().addAll(storage.loadExpenses());
            System.out.println("Loaded " + manager.getExpenses().size() + " expenses");
        } catch (Exception e) {
            System.out.println("Error loading expenses: " + e.getMessage());
        }

        while (true) {
            System.out.println("1. Add Expense\n2. List Expenses\n3. View Total by Category\n4. Exit");
            System.out.print("Choose an option: ");
            try {
                int choice = scanner.nextInt();
                scanner.nextLine(); // Consume newline

                if (choice == 1) {
                    System.out.print("Amount: ");
                    double amount;
                    try {
                        amount = scanner.nextDouble();
                        if (amount <= 0) {
                            System.out.println("Amount must be positive");
                            scanner.nextLine();
                            continue;
                        }
                    } catch (Exception e) {
                        System.out.println("Invalid amount. Please enter a valid number (e.g., 10.99)");
                        scanner.nextLine();
                        continue;
                    }
                    scanner.nextLine();
                    System.out.print("Category: ");
                    String category = scanner.nextLine().trim();
                    if (category.isEmpty()) {
                        System.out.println("Category cannot be empty");
                        continue;
                    }
                    System.out.print("Date (YYYY-MM-DD): ");
                    String dateStr = scanner.nextLine();
                    LocalDate date;
                    try {
                        date = LocalDate.parse(dateStr);
                    } catch (Exception e) {
                        System.out.println("Invalid date format. Please use YYYY-MM-DD");
                        continue;
                    }
                    System.out.print("Description: ");
                    String description = scanner.nextLine().trim();

                    try {
                        Expense expense = new Expense(amount, category, date, description);
                        manager.addExpense(expense);
                        storage.saveExpenses(manager.getExpenses());
                        System.out.println("Expense added!");
                    } catch (Exception e) {
                        System.out.println("Error saving expense: " + e.getMessage());
                    }
                } else if (choice == 2) {
                    if (manager.getExpenses().isEmpty()) {
                        System.out.println("No expenses found.");
                    } else {
                        for (Expense e : manager.getExpenses()) {
                            System.out.println(e);
                        }
                    }
                } else if (choice == 3) {
                    System.out.print("Enter category: ");
                    String category = scanner.nextLine().trim();
                    if (category.isEmpty()) {
                        System.out.println("Category cannot be empty");
                        continue;
                    }
                    double total = manager.getTotalByCategory(category);
                    System.out.printf("Total for %s: %.2f\n", category, total);
                } else if (choice == 4) {
                    break;
                } else {
                    System.out.println("Invalid option. Please choose 1-4.");
                }
            } catch (Exception e) {
                System.out.println("Invalid input. Please enter a number.");
                scanner.nextLine();
            }
        }
        scanner.close();
    }
}