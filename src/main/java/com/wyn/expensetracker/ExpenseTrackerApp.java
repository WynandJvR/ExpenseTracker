package com.wyn.expensetracker;

import javafx.application.Application;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.image.Image;
import javafx.stage.Stage;

import java.io.File;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.HashMap;
import java.util.Map;

public class ExpenseTrackerApp extends Application {

    @Override
    public void start(Stage stage) throws Exception {
        ExpenseManager manager = new ExpenseManager();
        FileStorage storage = new FileStorage();

        // Load application icon
        try {
            stage.getIcons().add(new Image(getClass().getResourceAsStream("/expenseIcon.png")));
        } catch (Exception e) {
            System.err.println("Failed to load icon: " + e.getMessage());
        }

        // Load categories
        ObservableList<String> categories;
        try {
            categories = FXCollections.observableArrayList(storage.loadCategories());
        } catch (Exception e) {
            categories = FXCollections.observableArrayList("Food", "Transport", "Entertainment", "Utilities", "Other");
            System.err.println("Failed to load categories: " + e.getMessage());
        }

        // Load expenses
        try {
            manager.loadExpenses(storage.loadExpenses());
            System.out.println("Loaded " + manager.getExpenses().size() + " expenses");
            if (manager.getExpenses().isEmpty()) {
                if (!new File(storage.getExcelStorage().getLastSavedFilePath()).exists()) {
                    manager.executeCommand(new AddExpenseCommand(manager, new Expense(50.0, "Food", LocalDate.now(), "Groceries")));
                    manager.executeCommand(new AddExpenseCommand(manager, new Expense(30.0, "Transport", LocalDate.now(), "Bus fare")));
                    storage.saveExpenses(manager.getExpensesForSave());
                }
            }
        } catch (Exception e) {
            System.err.println("Error loading expenses: " + e.getMessage());
        }

        // Load incomes
        Map<YearMonth, Double> incomes;
        try {
            incomes = storage.loadIncomes();
        } catch (Exception e) {
            incomes = new HashMap<>();
            System.err.println("Failed to load incomes: " + e.getMessage());
        }

        // Load FXML and get controller
        FXMLLoader loader = new FXMLLoader(getClass().getResource("MainView.fxml"));
        Parent root = loader.load();
        MainController controller = loader.getController();
        controller.initializeData(manager, storage, categories, incomes, stage);

        // Create scene and apply stylesheet
        Scene scene = new Scene(root, 1200, 800);
        try {
            scene.getStylesheets().add(getClass().getResource("/styles.css").toExternalForm());
        } catch (Exception e) {
            System.err.println("Failed to load stylesheet: " + e.getMessage());
        }

        // Set up keyboard shortcuts
        controller.setupKeyboardShortcuts(scene);

        // Configure and show stage
        stage.setMinWidth(800);
        stage.setMinHeight(600);
        stage.setTitle("Expense Tracker");
        stage.setScene(scene);
        stage.show();
    }

    public static void main(String[] args) {
        launch(args);
    }
}
