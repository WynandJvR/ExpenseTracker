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

        // Load per-occurrence overrides before expenses so regeneration applies them
        try {
            manager.setOverrides(storage.loadRecurringOverrides());
        } catch (Exception e) {
            System.err.println("Failed to load recurring overrides: " + e.getMessage());
        }

        // Load expenses
        try {
            manager.loadExpenses(storage.loadExpenses());
            System.out.println("Loaded " + manager.getExpenses().size() + " expenses");
            // A brand-new user starts with an empty ledger (no sample data).
            if (storage.hadLegacyRecurringOnLoad()) {
                // Upgrade path: persist the freshly-minted series ids so they stay stable
                // across launches and overrides keyed to them survive.
                storage.saveExpenses(manager.getExpensesForSave());
            }
        } catch (Exception e) {
            System.err.println("Error loading expenses: " + e.getMessage());
            if (storage.isExpenseSaveBlocked()) {
                handleUnreadableExpenses(storage, e);
            }
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
                String copy = storage.getLastCorruptCopyPath();
                alert.setContentText((copy != null
                        ? "An untouched copy of the original file was saved to:\n" + copy + "\n\n"
                        : "Rolling backups are kept under .expenseTracker. ")
                    + "Review the details before continuing; saving now will overwrite the bad rows "
                    + "with the data that did load.");
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

    /**
     * expenses.txt exists but could not be read at all. FileStorage has blocked saving so the
     * empty in-memory ledger can't overwrite it. Move the file aside (keeping it intact),
     * start fresh, and tell the user where their data went. If even the move fails, saving
     * stays blocked for this session.
     */
    private static void handleUnreadableExpenses(FileStorage storage, Exception cause) {
        String movedTo = null;
        String moveError = null;
        try {
            movedTo = storage.quarantineUnreadableExpenses();
        } catch (Exception ex) {
            moveError = ex.getMessage();
        }
        Alert alert = new Alert(Alert.AlertType.ERROR);
        UIUtils.applyStylesheet(alert.getDialogPane());
        alert.setTitle("Could Not Read Expenses");
        alert.setHeaderText("Your expense file could not be read: " + cause.getMessage());
        StringBuilder msg = new StringBuilder();
        if (movedTo != null) {
            msg.append("It has been moved aside, unchanged, to:\n").append(movedTo)
               .append("\n\nThe app will start with an empty ledger.");
        } else {
            msg.append("The file could not be moved aside");
            if (moveError != null) msg.append(" (").append(moveError).append(")");
            msg.append(", so saving expenses is disabled for this session to avoid overwriting it.");
        }
        if (storage.getLastCorruptCopyPath() != null) {
            msg.append("\n\nA copy was also saved to:\n").append(storage.getLastCorruptCopyPath());
        }
        alert.setContentText(msg.toString());
        alert.showAndWait();
    }

    public static void main(String[] args) {
        launch(args);
    }
}
