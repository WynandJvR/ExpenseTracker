package com.wyn.expensetracker;

import javafx.animation.FadeTransition;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.util.Duration;

import java.io.IOException;
import java.time.LocalDate;
import java.time.Month;
import java.time.YearMonth;
import java.time.format.TextStyle;
import java.util.*;

public class MainController {

    // --- Navigation ---
    @FXML private StackPane contentArea;
    @FXML private ToggleButton navDashboard;
    @FXML private ToggleButton navExpenses;
    @FXML private ToggleButton navRecurring;
    @FXML private ToggleButton navImport;
    @FXML private ToggleButton navAnalytics;
    @FXML private ToggleButton navDebts;

    // --- Sub-views (fx:include root nodes) ---
    @FXML private Node dashboard;
    @FXML private Node expenses;
    @FXML private Node recurring;
    @FXML private Node importTab;
    @FXML private Node analytics;
    @FXML private Node debtsTab;

    // --- Sub-controllers (fx:include convention: <fx:id>Controller) ---
    @FXML private DashboardController dashboardController;
    @FXML private ExpensesController expensesController;
    @FXML private RecurringController recurringController;
    @FXML private ImportController importTabController;
    @FXML private AnalyticsController analyticsController;
    @FXML private DebtController debtsTabController;

    // --- Toolbar ---
    @FXML private Button undoButton;
    @FXML private Button redoButton;
    @FXML private ComboBox<Integer> yearCombo;
    @FXML private ComboBox<Month> monthCombo;
    @FXML private ComboBox<String> currencyCombo;
    @FXML private ComboBox<String> profileCombo;

    // --- Status bar ---
    @FXML private Label statusSaveLabel;
    @FXML private Label statusCountLabel;

    // --- Shared state ---
    private SharedState state;
    private boolean refreshingTable = false;

    @FXML
    public void initialize() {
        // Minimal — most setup happens in initializeData()
    }

    public void initializeData(ExpenseManager manager, FileStorage storage,
                               ObservableList<String> categories, Map<YearMonth, Double> incomes,
                               Stage stage, ProfileManager profileManager) {

        // Create shared state
        state = new SharedState(manager, storage, categories, incomes, stage, profileManager);

        // Load budgets
        try {
            state.setBudgets(storage.loadBudgets());
        } catch (IOException e) {
            state.setBudgets(new HashMap<>());
        }

        // Load categorization rules
        try {
            state.getCategorizationRules().loadFrom(storage.loadCategorizationRules());
        } catch (IOException e) {
            System.err.println("Failed to load categorization rules: " + e.getMessage());
        }

        // Load tags
        try {
            state.getTags().setAll(storage.loadTags());
        } catch (IOException e) {
            System.err.println("Failed to load tags: " + e.getMessage());
        }

        // Load savings goals
        try {
            state.getSavingsGoals().setAll(storage.loadGoals());
            state.getGoalContributions().setAll(storage.loadGoalContributions());
        } catch (IOException e) {
            System.err.println("Failed to load savings goals: " + e.getMessage());
        }

        // Load dismissed anomalies
        try {
            state.getDismissedAnomalyKeys().addAll(storage.loadDismissedAnomalies());
        } catch (IOException e) {
            System.err.println("Failed to load dismissed anomalies: " + e.getMessage());
        }

        // Load exchange rates and base currency
        try {
            String baseCurrency = storage.loadBaseCurrency();
            state.getCurrencyManager().setBaseCurrency(baseCurrency);
            state.getCurrencyManager().setExchangeRates(storage.loadExchangeRates());
        } catch (IOException e) {
            System.err.println("Failed to load exchange rates: " + e.getMessage());
        }

        // Load debts
        try {
            state.getDebts().setAll(storage.loadDebts());
            state.getDebtPayments().setAll(storage.loadDebtPayments());
        } catch (IOException e) {
            System.err.println("Failed to load debts: " + e.getMessage());
        }

        // Set refresh callback
        state.setRefreshCallback(this::refreshTable);

        // Setup toolbar combos
        setupToolbarCombos();

        // Setup navigation
        setupNavigation();

        // Initialize sub-controllers
        dashboardController.init(state);
        expensesController.init(state);
        recurringController.init(state);
        importTabController.init(state);
        analyticsController.init(state);
        debtsTabController.init(state);

        // Select default view
        navDashboard.setSelected(true);

        // Initial refresh
        refreshTable();

        // Restore UI state from previous session
        restoreUIState();

        // Save UI state on window close
        stage.setOnCloseRequest(e -> saveUIState());
    }

    // ======================== TOOLBAR SETUP ========================

    private void setupToolbarCombos() {
        // Profile combo
        profileCombo.setItems(FXCollections.observableArrayList(state.getProfileManager().listProfiles()));
        profileCombo.setValue(state.getProfileManager().getActiveProfile());
        profileCombo.setOnAction(e -> {
            String selected = profileCombo.getValue();
            if (selected != null && !selected.equals(state.getProfileManager().getActiveProfile())) {
                switchToProfile(selected);
            }
        });

        // Year/month combos
        yearCombo.setItems(state.getYearList());
        UIUtils.setupComboCellFactory(yearCombo);

        monthCombo.setItems(FXCollections.observableArrayList(Month.values()));
        UIUtils.setupComboCellFactory(monthCombo);

        // Period selectors — skip during refreshTable()
        yearCombo.valueProperty().addListener((obs, oldVal, newVal) -> {
            state.setSelectedYear(newVal);
            if (!refreshingTable) {
                refreshAllViews();
            }
        });
        monthCombo.valueProperty().addListener((obs, oldVal, newVal) -> {
            state.setSelectedMonth(newVal);
            if (!refreshingTable) {
                refreshAllViews();
            }
        });

        // Currency combo — now shows ISO codes, sets base currency
        String baseCurrency = state.getCurrencyManager().getBaseCurrency();
        state.setCurrencySymbol(CurrencyManager.getSymbol(baseCurrency));
        currencyCombo.setItems(FXCollections.observableArrayList(CurrencyManager.getCurrencyCodes()));
        currencyCombo.setValue(baseCurrency);
        currencyCombo.setCellFactory(lv -> new ListCell<String>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : CurrencyManager.getDisplayName(item));
            }
        });
        currencyCombo.setButtonCell(new ListCell<String>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : CurrencyManager.getDisplayName(item));
            }
        });
        currencyCombo.valueProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal != null) {
                state.getCurrencyManager().setBaseCurrency(newVal);
                state.setCurrencySymbol(CurrencyManager.getSymbol(newVal));
                try {
                    state.getStorage().saveBaseCurrency(newVal);
                    state.getStorage().saveCurrencySymbol(CurrencyManager.getSymbol(newVal));
                } catch (IOException ex) { /* ignore */ }
                refreshTable();
            }
        });

        // Recurring income from storage
        state.setRecurringIncome(state.getStorage().loadRecurringIncome());
    }

    // ======================== NAVIGATION ========================

    private void setupNavigation() {
        ToggleGroup navGroup = new ToggleGroup();
        navDashboard.setToggleGroup(navGroup);
        navExpenses.setToggleGroup(navGroup);
        navRecurring.setToggleGroup(navGroup);
        navImport.setToggleGroup(navGroup);
        navAnalytics.setToggleGroup(navGroup);
        navDebts.setToggleGroup(navGroup);

        navGroup.selectedToggleProperty().addListener((obs, oldToggle, newToggle) -> {
            if (newToggle == null) {
                if (oldToggle != null) oldToggle.setSelected(true);
                return;
            }
            if (newToggle == navDashboard) switchView("dashboard");
            else if (newToggle == navExpenses) switchView("expenses");
            else if (newToggle == navRecurring) switchView("recurring");
            else if (newToggle == navImport) switchView("import");
            else if (newToggle == navAnalytics) switchView("analytics");
            else if (newToggle == navDebts) switchView("debts");
        });
    }

    private void switchView(String viewName) {
        dashboard.setVisible(false); dashboard.setManaged(false);
        expenses.setVisible(false); expenses.setManaged(false);
        recurring.setVisible(false); recurring.setManaged(false);
        importTab.setVisible(false); importTab.setManaged(false);
        analytics.setVisible(false); analytics.setManaged(false);
        debtsTab.setVisible(false); debtsTab.setManaged(false);

        Node target = switch (viewName) {
            case "expenses" -> expenses;
            case "recurring" -> recurring;
            case "import" -> importTab;
            case "analytics" -> analytics;
            case "debts" -> debtsTab;
            default -> dashboard;
        };
        target.setVisible(true);
        target.setManaged(true);
        state.setCurrentViewName(viewName);

        UIUtils.animateViewFadeIn(target);
    }

    // ======================== REFRESH ========================

    private void refreshTable() {
        if (refreshingTable) return;
        refreshingTable = true;
        try {
            // Sync data from manager to observable lists
            state.syncExpenseList();
            state.syncRecurringList();

            // Update year list
            updateYearList();

            // Refresh all sub-controllers
            refreshAllViews();

            // Update toolbar state
            updateUndoRedoButtons();
            updateStatusBar();
        } catch (Exception e) {
            System.err.println("Error refreshing: " + e.getMessage());
        } finally {
            refreshingTable = false;
        }
    }

    private void refreshAllViews() {
        expensesController.refresh();
        dashboardController.refresh();
        analyticsController.refresh();
        recurringController.refresh();
        importTabController.refresh();
        debtsTabController.refresh();
    }

    private void updateYearList() {
        Integer selectedYear = yearCombo.getValue();
        Month selectedMonth = monthCombo.getValue();

        Set<Integer> years = new TreeSet<>();
        for (Expense expense : state.getExpenseList()) {
            years.add(expense.getDate().getYear());
        }
        state.getYearList().setAll(years);

        if (selectedYear != null && state.getYearList().contains(selectedYear)) {
            yearCombo.setValue(selectedYear);
        } else {
            int currentYear = LocalDate.now().getYear();
            if (state.getYearList().contains(currentYear)) {
                yearCombo.setValue(currentYear);
            } else if (!state.getYearList().isEmpty()) {
                yearCombo.setValue(state.getYearList().get(state.getYearList().size() - 1));
            }
        }

        if (selectedMonth != null) {
            monthCombo.setValue(selectedMonth);
        } else if (yearCombo.getValue() != null) {
            monthCombo.setValue(Month.of(LocalDate.now().getMonthValue()));
        }
    }

    private void updateUndoRedoButtons() {
        undoButton.setDisable(!state.getManager().canUndo());
        redoButton.setDisable(!state.getManager().canRedo());
    }

    private void updateStatusBar() {
        statusSaveLabel.setText("Last saved: just now");

        int total = state.getExpenseList().size();
        long thisMonth = state.getFilteredData().size();
        double monthTotal = state.getFilteredData().stream()
            .filter(e -> !e.isExcluded() && !e.isIncome() && !e.isRefund())
            .mapToDouble(e -> state.getCurrencyManager().toBase(e.getAmount(), e.getCurrency())).sum();

        String monthLabel = "this month";
        if (yearCombo.getValue() != null && monthCombo.getValue() != null) {
            monthLabel = monthCombo.getValue().getDisplayName(TextStyle.SHORT, Locale.getDefault())
                + " " + yearCombo.getValue();
        }

        StringBuilder sb = new StringBuilder();
        sb.append(String.format("%d expenses (%d in %s)", total, thisMonth, monthLabel));
        sb.append(String.format("  |  Month total: %s", UIUtils.fmt(monthTotal, state.getCurrencySymbol())));

        YearMonth ym = state.getSelectedYearMonth();
        if (ym != null) {
            Double monthIncome = state.getIncomes().get(ym);
            double effectiveIncome = (monthIncome != null && monthIncome > 0) ? monthIncome : state.getRecurringIncome();
            if (effectiveIncome > 0) {
                double remaining = effectiveIncome - monthTotal;
                sb.append(String.format("  |  Remaining: %s", UIUtils.fmt(remaining, state.getCurrencySymbol())));
            }
        }

        statusCountLabel.setText(sb.toString());
    }

    // ======================== EVENT HANDLERS ========================

    @FXML
    private void handleUndo() {
        if (!state.getManager().canUndo()) return;
        state.getManager().undo();
        try {
            state.saveExpenses();
            refreshTable();
        } catch (Exception ex) {
            System.err.println("Error during undo: " + ex.getMessage());
        }
    }

    @FXML
    private void handleRedo() {
        if (!state.getManager().canRedo()) return;
        state.getManager().redo();
        try {
            state.saveExpenses();
            refreshTable();
        } catch (Exception ex) {
            System.err.println("Error during redo: " + ex.getMessage());
        }
    }

    @FXML
    private void handlePrevMonth() {
        navigateMonth(-1);
    }

    @FXML
    private void handleNextMonth() {
        navigateMonth(1);
    }

    @FXML
    private void handleThisMonth() {
        int currentYear = LocalDate.now().getYear();
        if (!state.getYearList().contains(currentYear)) {
            state.getYearList().add(currentYear);
            FXCollections.sort(state.getYearList());
        }
        yearCombo.setValue(currentYear);
        monthCombo.setValue(Month.of(LocalDate.now().getMonthValue()));
    }

    private void navigateMonth(int offset) {
        Integer year = yearCombo.getValue();
        Month month = monthCombo.getValue();
        if (year == null || month == null) {
            handleThisMonth();
            return;
        }
        YearMonth current = YearMonth.of(year, month).plusMonths(offset);
        if (!state.getYearList().contains(current.getYear())) {
            state.getYearList().add(current.getYear());
            FXCollections.sort(state.getYearList());
        }
        yearCombo.setValue(current.getYear());
        monthCombo.setValue(current.getMonth());
    }

    // ======================== KEYBOARD SHORTCUTS ========================

    public void setupKeyboardShortcuts(Scene scene) {
        scene.addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            if (event.isControlDown() && event.isShiftDown() && event.getCode() == KeyCode.C) {
                showCopyableViewContent();
                event.consume();
                return;
            }
            if (event.isControlDown()) {
                Node focused = scene.getFocusOwner();
                boolean inTextField = focused instanceof TextField || focused instanceof TextArea;

                switch (event.getCode()) {
                    case Z:
                        if (!inTextField) { handleUndo(); event.consume(); }
                        break;
                    case Y:
                        if (!inTextField) { handleRedo(); event.consume(); }
                        break;
                    case N:
                        if (!"expenses".equals(state.getCurrentViewName())) {
                            navExpenses.setSelected(true);
                        }
                        expensesController.focusAddForm();
                        event.consume();
                        break;
                    case E:
                        expensesController.handleExport();
                        event.consume();
                        break;
                    case F:
                        if (!"expenses".equals(state.getCurrentViewName())) {
                            navExpenses.setSelected(true);
                        }
                        Platform.runLater(() -> expensesController.focusSearch());
                        event.consume();
                        break;
                    case SLASH:
                        showKeyboardShortcuts();
                        event.consume();
                        break;
                    default:
                        break;
                }
            }
            if (event.getCode() == KeyCode.F1) {
                showKeyboardShortcuts();
                event.consume();
            }
            if (event.isAltDown()) {
                switch (event.getCode()) {
                    case LEFT: handlePrevMonth(); event.consume(); break;
                    case RIGHT: handleNextMonth(); event.consume(); break;
                    default: break;
                }
            }
        });
    }

    // ======================== UI STATE PERSISTENCE ========================

    private void saveUIState() {
        try {
            Map<String, String> uiState = new HashMap<>();
            uiState.put("activeView", state.getCurrentViewName());
            uiState.put("analyticsTab", String.valueOf(analyticsController.getSelectedTabIndex()));
            uiState.put("chartPeriod", state.getChartPeriod());
            state.getStorage().saveUIState(uiState);
        } catch (IOException e) {
            System.err.println("Error saving UI state: " + e.getMessage());
        }
    }

    private void restoreUIState() {
        Map<String, String> uiState = state.getStorage().loadUIState();
        if (uiState.isEmpty()) return;

        if (uiState.containsKey("activeView")) {
            switch (uiState.get("activeView")) {
                case "expenses" -> navExpenses.setSelected(true);
                case "recurring" -> navRecurring.setSelected(true);
                case "import" -> navImport.setSelected(true);
                case "analytics" -> navAnalytics.setSelected(true);
                case "debts" -> navDebts.setSelected(true);
                default -> navDashboard.setSelected(true);
            }
        }

        if (uiState.containsKey("analyticsTab")) {
            try {
                analyticsController.selectTab(Integer.parseInt(uiState.get("analyticsTab")));
            } catch (NumberFormatException e) { /* ignore */ }
        }

        if (uiState.containsKey("chartPeriod")) {
            state.setChartPeriod(uiState.get("chartPeriod"));
        }
    }

    // ======================== PROFILES ========================

    private void switchToProfile(String profileName) {
        saveUIState();

        state.setStorage(new FileStorage(state.getProfileManager().getProfileDir(profileName)));
        state.setManager(new ExpenseManager());

        try {
            List<String> newCats = state.getStorage().loadCategories();
            if (newCats.isEmpty()) {
                newCats = List.of("Food", "Transport", "Entertainment", "Utilities", "Other");
            }
            state.getCategories().setAll(newCats);
        } catch (Exception e) {
            state.getCategories().setAll("Food", "Transport", "Entertainment", "Utilities", "Other");
        }

        state.getStorage().migrateFromExcelIfNeeded();

        try {
            state.getManager().loadExpenses(state.getStorage().loadExpenses());
        } catch (Exception e) {
            System.err.println("Error loading expenses for profile " + profileName + ": " + e.getMessage());
        }

        state.getIncomes().clear();
        try {
            state.getIncomes().putAll(state.getStorage().loadIncomes());
        } catch (Exception e) {
            System.err.println("Error loading incomes for profile " + profileName + ": " + e.getMessage());
        }

        try {
            state.setBudgets(state.getStorage().loadBudgets());
        } catch (Exception e) {
            state.setBudgets(new HashMap<>());
        }

        CategorizationRules rules = new CategorizationRules();
        try {
            rules.loadFrom(state.getStorage().loadCategorizationRules());
        } catch (Exception e) {
            System.err.println("Error loading rules for profile " + profileName + ": " + e.getMessage());
        }
        state.setCategorizationRules(rules);

        try {
            state.getImportLogs().setAll(state.getStorage().loadImportLogs());
        } catch (Exception e) {
            state.getImportLogs().clear();
        }

        try {
            state.getTags().setAll(state.getStorage().loadTags());
        } catch (Exception e) {
            state.getTags().clear();
        }

        try {
            state.getSavingsGoals().setAll(state.getStorage().loadGoals());
            state.getGoalContributions().setAll(state.getStorage().loadGoalContributions());
        } catch (Exception e) {
            state.getSavingsGoals().clear();
            state.getGoalContributions().clear();
        }

        try {
            state.getDismissedAnomalyKeys().clear();
            state.getDismissedAnomalyKeys().addAll(state.getStorage().loadDismissedAnomalies());
        } catch (Exception e) {
            state.getDismissedAnomalyKeys().clear();
        }

        // Load exchange rates
        try {
            String baseCurr = state.getStorage().loadBaseCurrency();
            state.getCurrencyManager().setBaseCurrency(baseCurr);
            state.getCurrencyManager().setExchangeRates(state.getStorage().loadExchangeRates());
        } catch (Exception e) {
            System.err.println("Error loading exchange rates: " + e.getMessage());
        }

        // Load debts
        try {
            state.getDebts().setAll(state.getStorage().loadDebts());
            state.getDebtPayments().setAll(state.getStorage().loadDebtPayments());
        } catch (Exception e) {
            state.getDebts().clear();
            state.getDebtPayments().clear();
        }

        String baseCurr = state.getCurrencyManager().getBaseCurrency();
        state.setCurrencySymbol(CurrencyManager.getSymbol(baseCurr));
        currencyCombo.setValue(baseCurr);
        state.setRecurringIncome(state.getStorage().loadRecurringIncome());

        // Re-init sub-controllers with updated state
        dashboardController.init(state);
        expensesController.init(state);
        recurringController.init(state);
        importTabController.init(state);
        analyticsController.init(state);
        debtsTabController.init(state);

        try {
            state.getProfileManager().setActiveProfile(profileName);
        } catch (Exception e) {
            System.err.println("Error saving active profile: " + e.getMessage());
        }

        state.getStage().setTitle("Expense Tracker - " + profileName);
        state.setProjectionsNeedUpdate(true);

        refreshTable();
        restoreUIState();

        showLoadWarningsIfAny();
    }

    private void showLoadWarningsIfAny() {
        List<String> warnings = state.getStorage().drainParseWarnings();
        if (warnings.isEmpty()) return;
        FileStorage.LoadStats stats = state.getStorage().getLastExpenseLoadStats();
        boolean severe = stats.isSevere();
        Alert alert = new Alert(severe ? Alert.AlertType.ERROR : Alert.AlertType.WARNING);
        alert.initOwner(state.getStage());
        alert.setTitle(severe ? "Data Corruption Detected" : "Data Warnings");
        if (severe) {
            alert.setHeaderText(stats.failedLines + " of " + stats.totalLines
                + " expense lines failed to parse — this profile's data may be corrupted.");
            alert.setContentText("Review the details before saving over the file.");
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

    @FXML
    private void handleCreateProfile() {
        TextInputDialog dialog = new TextInputDialog();
        dialog.setTitle("New Profile");
        dialog.setHeaderText("Create a new profile");
        dialog.setContentText("Profile name:");
        dialog.initOwner(state.getStage());
        dialog.showAndWait().ifPresent(name -> {
            String trimmed = name.trim();
            if (trimmed.isEmpty()) return;
            if (state.getProfileManager().createProfile(trimmed)) {
                profileCombo.getItems().setAll(state.getProfileManager().listProfiles());
                profileCombo.setValue(trimmed);
                switchToProfile(trimmed);
            }
        });
    }

    @FXML
    private void handleRenameProfile() {
        String current = profileCombo.getValue();
        if (current == null) return;
        TextInputDialog dialog = new TextInputDialog(current);
        dialog.setTitle("Rename Profile");
        dialog.setHeaderText("Rename profile: " + current);
        dialog.setContentText("New name:");
        dialog.initOwner(state.getStage());
        dialog.showAndWait().ifPresent(name -> {
            String trimmed = name.trim();
            if (trimmed.isEmpty() || trimmed.equals(current)) return;
            if (state.getProfileManager().renameProfile(current, trimmed)) {
                try { state.getProfileManager().setActiveProfile(trimmed); } catch (Exception e) { /* ignore */ }
                profileCombo.getItems().setAll(state.getProfileManager().listProfiles());
                profileCombo.setValue(trimmed);
                state.getStage().setTitle("Expense Tracker - " + trimmed);
            }
        });
    }

    @FXML
    private void handleDeleteProfile() {
        String current = profileCombo.getValue();
        if (current == null) return;
        if (state.getProfileManager().listProfiles().size() <= 1) return;
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.setTitle("Delete Profile");
        confirm.setHeaderText("Delete profile: " + current + "?");
        confirm.setContentText("All data in this profile will be permanently deleted.");
        confirm.initOwner(state.getStage());
        confirm.showAndWait().ifPresent(result -> {
            if (result == ButtonType.OK) {
                String switchTo = state.getProfileManager().listProfiles().stream()
                    .filter(p -> !p.equals(current)).findFirst().orElse(ProfileManager.DEFAULT_PROFILE);
                if (state.getProfileManager().deleteProfile(current)) {
                    profileCombo.getItems().setAll(state.getProfileManager().listProfiles());
                    profileCombo.setValue(switchTo);
                    switchToProfile(switchTo);
                }
            }
        });
    }

    // ======================== SHORTCUTS DIALOG ========================

    @FXML
    private void handleShowShortcuts() {
        showKeyboardShortcuts();
    }

    private void showKeyboardShortcuts() {
        Alert help = new Alert(Alert.AlertType.INFORMATION);
        help.initOwner(state.getStage());
        help.setTitle("Keyboard Shortcuts");
        help.setHeaderText("Keyboard Shortcuts");
        help.getDialogPane().getStylesheets().add(getClass().getResource("/styles.css").toExternalForm());

        VBox content = new VBox(12);
        content.setPadding(new javafx.geometry.Insets(8));

        String[][] shortcuts = {
            {"Ctrl+N", "Add new expense"},
            {"Ctrl+F", "Search expenses"},
            {"Ctrl+E", "Export to Excel"},
            {"Ctrl+Z", "Undo"},
            {"Ctrl+Y", "Redo"},
            {"Ctrl+Shift+C", "Copy all view content (selectable)"},
            {"Ctrl+/  or  F1", "Show this help"},
            {"Alt+Left", "Previous month"},
            {"Alt+Right", "Next month"},
            {"Delete", "Delete selected item"},
            {"Enter", "Submit form / Confirm edit"},
            {"Escape", "Cancel inline edit"},
            {"Double-click", "Edit a cell in the table"},
            {"Right-click", "Context menu (mark as income, exclude, etc.)"},
        };

        for (String[] shortcut : shortcuts) {
            HBox row = new HBox(12);
            row.setAlignment(Pos.CENTER_LEFT);
            Label key = new Label(shortcut[0]);
            key.setMinWidth(160);
            key.setStyle("-fx-font-weight: bold; -fx-text-fill: #5C6BC0; -fx-font-family: monospace; -fx-font-size: 13px;");
            Label desc = new Label(shortcut[1]);
            desc.setStyle("-fx-text-fill: #E0E0E0; -fx-font-size: 13px;");
            row.getChildren().addAll(key, desc);
            content.getChildren().add(row);
        }

        help.getDialogPane().setContent(content);
        help.getDialogPane().setPrefWidth(450);
        help.showAndWait();
    }

    private void showCopyableViewContent() {
        StringBuilder sb = new StringBuilder();

        switch (state.getCurrentViewName()) {
            case "dashboard" -> {
                sb.append("=== Dashboard ===\n\n");
                sb.append("Total Spent: ").append(dashboardController.getTotalSpentText()).append("\n");
                sb.append("Top Category: ").append(dashboardController.getTopCategoryText()).append("\n");
                sb.append("Budget Status: ").append(dashboardController.getBudgetStatusText()).append("\n");
                sb.append("vs Last Month: ").append(dashboardController.getMonthChangeText()).append("\n");
            }
            case "expenses" -> {
                sb.append("=== Expenses ===\n\n");
                sb.append("Amount\tCategory\tDate\tDescription\n");
                sb.append("------\t--------\t----\t-----------\n");
                for (Expense e : expensesController.getTableItems()) {
                    sb.append(String.format("%s\t%s\t%s\t%s\n",
                        UIUtils.fmt(e.getAmount(), state.getCurrencySymbol()), e.getCategory(),
                        e.getDate().format(java.time.format.DateTimeFormatter.ofPattern("dd MMM yyyy")),
                        e.getDescription() != null ? e.getDescription() : ""));
                }
                sb.append(String.format("\n%d expenses shown\n", expensesController.getTableItems().size()));
            }
            case "recurring" -> {
                sb.append("=== Recurring Expenses ===\n\n");
                sb.append("Amount\tCategory\tStart Date\tDescription\tFrequency\tEnd Date\n");
                for (RecurringExpense r : state.getRecurringList()) {
                    sb.append(String.format("%s\t%s\t%s\t%s\t%s\t%s\n",
                        UIUtils.fmt(r.getAmount(), state.getCurrencySymbol()), r.getCategory(), r.getDate(),
                        r.getDescription(), r.getFrequency(),
                        r.getEndDate() != null ? r.getEndDate() : "None"));
                }
            }
            default -> {
                sb.append("=== ").append(state.getCurrentViewName()).append(" ===\n\n");
                sb.append("Use Ctrl+C on a selected row to copy it.\n");
            }
        }

        TextArea textArea = new TextArea(sb.toString());
        textArea.setEditable(false);
        textArea.setWrapText(false);
        textArea.setStyle("-fx-font-family: monospace; -fx-font-size: 13px; -fx-control-inner-background: #2A2A2A; -fx-text-fill: #E0E0E0;");
        textArea.setPrefHeight(450);
        textArea.setPrefWidth(700);
        textArea.selectAll();

        Alert dialog = new Alert(Alert.AlertType.NONE);
        dialog.initOwner(state.getStage());
        dialog.setTitle("View Content — Select & Copy");
        dialog.setHeaderText("All text is selectable. Press Ctrl+A then Ctrl+C to copy everything.");
        dialog.getDialogPane().setContent(textArea);
        dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        dialog.getDialogPane().getStylesheets().add(getClass().getResource("/styles.css").toExternalForm());
        dialog.showAndWait();
    }
}
