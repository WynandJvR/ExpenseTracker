package com.wyn.expensetracker;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.TextArea;
import javafx.scene.image.Image;
import javafx.stage.Stage;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ExpenseTrackerApp extends Application {

    @Override
    public void start(Stage stage) throws Exception {
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            System.err.println("Uncaught exception in thread " + t.getName() + ":");
            e.printStackTrace();
            Platform.runLater(() -> {
                Alert alert = new Alert(Alert.AlertType.ERROR);
                UIUtils.applyStylesheet(alert.getDialogPane());
                alert.setTitle("Unexpected Error");
                alert.setHeaderText("An unexpected error occurred.");
                alert.setContentText(e.getMessage());
                StringWriter sw = new StringWriter();
                e.printStackTrace(new PrintWriter(sw));
                TextArea details = new TextArea(sw.toString());
                details.setEditable(false);
                details.setWrapText(true);
                alert.getDialogPane().setExpandableContent(details);
                alert.showAndWait();
            });
        });

        // Initialize profile manager and migrate existing data if needed
        ProfileManager profileManager = new ProfileManager();
        profileManager.migrateToProfiles();
        String activeProfile = profileManager.getActiveProfile();

        ExpenseManager manager = new ExpenseManager();
        FileStorage storage = new FileStorage(profileManager.getProfileDir(activeProfile));

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

        // Migrate from Excel if needed (one-time)
        storage.migrateFromExcelIfNeeded();

        // Load expenses
        try {
            manager.loadExpenses(storage.loadExpenses());
            System.out.println("Loaded " + manager.getExpenses().size() + " expenses");
            if (manager.getExpenses().isEmpty() && !storage.expensesFileExists()) {
                manager.executeCommand(new AddExpenseCommand(manager, new Expense(50.0, "Food", LocalDate.now(), "Groceries")));
                manager.executeCommand(new AddExpenseCommand(manager, new Expense(30.0, "Transport", LocalDate.now(), "Bus fare")));
                storage.saveExpenses(manager.getExpensesForSave());
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

        // Show parse warnings if any data was malformed
        List<String> warnings = storage.drainParseWarnings();
        if (!warnings.isEmpty()) {
            FileStorage.LoadStats stats = storage.getLastExpenseLoadStats();
            boolean severe = stats.isSevere();
            Alert alert = new Alert(severe ? Alert.AlertType.ERROR : Alert.AlertType.WARNING);
            UIUtils.applyStylesheet(alert.getDialogPane());
            alert.setTitle(severe ? "Data Corruption Detected" : "Data Warnings");
            if (severe) {
                alert.setHeaderText(stats.failedLines + " of " + stats.totalLines
                    + " expense lines failed to parse — your expense file may be corrupted.");
                alert.setContentText("A backup is kept under .expenseTracker. Review the details before continuing; "
                    + "saving over the file now will overwrite the bad rows with the data that did load.");
            } else {
                alert.setHeaderText(warnings.size() + " issue(s) found while loading data.");
                alert.setContentText("Some entries were skipped. Expand for details.");
            }
            TextArea details = new TextArea(String.join("\n", warnings));
            details.setEditable(false);
            details.setWrapText(true);
            alert.getDialogPane().setExpandableContent(details);
            alert.showAndWait();
        }

        // Load FXML and get controller
        FXMLLoader loader = new FXMLLoader(getClass().getResource("MainView.fxml"));
        Parent root = loader.load();
        MainController controller = loader.getController();
        controller.initializeData(manager, storage, categories, incomes, stage, profileManager);

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
        stage.setTitle("Expense Tracker - " + activeProfile);
        stage.setScene(scene);
        stage.show();
    }

    public static void main(String[] args) {
        launch(args);
    }
}
