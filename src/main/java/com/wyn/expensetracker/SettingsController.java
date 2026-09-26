package com.wyn.expensetracker;

import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.VBox;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Settings view: app-wide configuration that's set rarely (base currency) plus a full
 * category manager (add / rename / delete-with-reassign). Workflow-contextual editing
 * (auto-categorization rules, inline category quick-add) stays where it's used.
 */
public class SettingsController {

    @FXML private ComboBox<String> currencyCombo;

    @FXML private TableView<CategoryStat> categoryTable;
    @FXML private TableColumn<CategoryStat, String> catNameColumn;
    @FXML private TableColumn<CategoryStat, String> catCountColumn;
    @FXML private Button renameCategoryButton;
    @FXML private Button deleteCategoryButton;
    @FXML private Label categoryErrorLabel;

    private SharedState state;
    private boolean initialized = false;
    private boolean suppressCurrencyListener = false;
    private final ObservableList<CategoryStat> categoryStats = FXCollections.observableArrayList();

    public void init(SharedState state) {
        this.state = state;
        if (initialized) return;
        initialized = true;
        setupCurrency();
        setupCategories();
    }

    /** Refreshes displayed state (currency value, category usage counts) after data/profile changes. */
    public void refresh() {
        if (state == null) return;
        refreshCurrencyValue();
        refreshCategoryStats();
    }

    // ======================== CURRENCY ========================

    private void setupCurrency() {
        currencyCombo.setItems(FXCollections.observableArrayList(CurrencyManager.getCurrencyCodes()));
        currencyCombo.setCellFactory(lv -> new ListCell<String>() {
            @Override protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : CurrencyManager.getDisplayName(item));
            }
        });
        currencyCombo.setButtonCell(new ListCell<String>() {
            @Override protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : CurrencyManager.getDisplayName(item));
            }
        });
        refreshCurrencyValue();
        currencyCombo.valueProperty().addListener((obs, oldVal, newVal) -> {
            if (suppressCurrencyListener || newVal == null) return;
            changeBaseCurrency(newVal);
        });
    }

    /**
     * Switches the base currency without changing what existing data means:
     * <ul>
     *   <li>records with no explicit currency (i.e. "base") are stamped with the OLD base code,
     *       so a R100 expense doesn't silently become $100;</li>
     *   <li>amounts that are implicitly in the base currency (planned incomes, the recurring
     *       income default, budgets, savings goals and their contributions) are converted
     *       with the old&rarr;new rate. Debts keep their own (stamped) currency.</li>
     *   <li>stored exchange rates (relative to the old base) are re-expressed relative to the
     *       new base, or cleared with a warning if there's no rate for the new base.</li>
     * </ul>
     * If there are base-currency amounts to convert but no rate for the new base is
     * configured, the change is refused. On save failure the in-memory changes are rolled back.
     */
    private void changeBaseCurrency(String newBase) {
        CurrencyManager cm = state.getCurrencyManager();
        String oldBase = cm.getBaseCurrency();
        if (newBase.equals(oldBase)) return;

        Map<String, Double> oldRates = new LinkedHashMap<>(cm.getExchangeRates());
        // Rates are "1 X = rate(X) old-base", so 1 old-base = 1/rate(newBase) new-base.
        Double newBaseRate = oldRates.get(newBase);
        boolean haveRate = newBaseRate != null && newBaseRate > 0
            && !newBaseRate.isNaN() && !newBaseRate.isInfinite();
        boolean needConversion = state.hasBaseCurrencyAmounts();
        java.util.Set<String> lacking = currenciesLackingRateAfterSwitch(state, newBase);
        if (!lacking.isEmpty()) {
            refreshCurrencyValue();
            showMsg("Can't switch the base currency to " + newBase + ": after the switch there would be no "
                + newBase + " exchange rate for " + String.join(", ", lacking)
                + ", so those amounts would be silently re-valued 1:1. "
                + "Add a " + newBase + " rate (1 " + newBase + " = ? " + oldBase + ")"
                + (lacking.size() > 1 || !lacking.contains(oldBase) ? " and rates for the other listed currencies" : "")
                + " first, then try again.", true);
            return;
        }
        double factor = haveRate ? 1.0 / newBaseRate : 1.0;
        if (needConversion) state.scaleBaseCurrencyAmounts(factor);

        List<Expense> stamped = state.getManager().stampMissingCurrency(oldBase);
        List<Debt> stampedDebts = new ArrayList<>();
        for (Debt d : state.getDebts()) {
            if (d.getCurrency() == null) { d.setCurrency(oldBase); stampedDebts.add(d); }
        }
        boolean ratesConverted = cm.changeBaseCurrency(newBase);

        try {
            if (!stamped.isEmpty()) state.saveExpenses();
            if (!stampedDebts.isEmpty()) state.getStorage().saveDebts(state.getDebts());
            if (needConversion) {
                state.getStorage().saveIncomes(state.getIncomes());
                state.getStorage().saveRecurringIncome(state.getRecurringIncome());
                state.getStorage().saveBudgets(state.getBudgets());
                state.getStorage().saveGoals(new ArrayList<>(state.getSavingsGoals()));
                state.getStorage().saveGoalContributions(new ArrayList<>(state.getGoalContributions()));
            }
            // Rates first, then base: if the base write fails, the BASE= line in the rates
            // file lets the loader convert them back to the still-configured old base.
            state.getStorage().saveExchangeRates(newBase, cm.getExchangeRates());
            state.getStorage().saveBaseCurrency(newBase);
        } catch (Exception ex) {
            // Stamped records already written to disk are harmless (explicit old-base code
            // means the same thing as "base" did), so only memory needs rolling back.
            for (Expense e : stamped) e.setCurrency(null);
            for (Debt d : stampedDebts) d.setCurrency(null);
            if (needConversion) {
                state.scaleBaseCurrencyAmounts(1.0 / factor);
                // Best effort: put the old-base amounts back on disk too.
                try {
                    state.getStorage().saveIncomes(state.getIncomes());
                    state.getStorage().saveRecurringIncome(state.getRecurringIncome());
                    state.getStorage().saveBudgets(state.getBudgets());
                    state.getStorage().saveGoals(new ArrayList<>(state.getSavingsGoals()));
                    state.getStorage().saveGoalContributions(new ArrayList<>(state.getGoalContributions()));
                } catch (Exception ignored) {
                    // the original error is reported below
                }
            }
            cm.setBaseCurrency(oldBase);
            cm.setExchangeRates(oldRates);
            refreshCurrencyValue();
            showMsg("Failed to change base currency: " + ex.getMessage(), true);
            return;
        }

        state.setCurrencySymbol(CurrencyManager.getSymbol(newBase));
        state.requestRefresh();
        if (!ratesConverted && !oldRates.isEmpty()) {
            showMsg("Base currency is now " + newBase + ". Your exchange rates were relative to " + oldBase
                + " and there was no " + newBase + " rate to convert them, so they were cleared — please re-enter them.", true);
        } else {
            showMsg("Base currency changed to " + newBase
                + (stamped.isEmpty() ? "" : "; existing entries keep their " + oldBase + " amounts")
                + (needConversion ? "; incomes, budgets and goals were converted at 1 " + oldBase + " = "
                    + String.format(java.util.Locale.ROOT, "%.4f", factor) + " " + newBase : ""), false);
        }
    }

    /**
     * Currencies in use that would have no rate to {@code newBase} after a base switch
     * (sorted; empty = the switch is safe). "In use" means every currency stamped on an
     * expense, recurring template or debt, plus the current base itself whenever any data
     * exists (unstamped records get stamped with it, and base-denominated settings are
     * converted from it). Without a rate, toBase would fall back to 1.0 and re-value them.
     */
    static java.util.Set<String> currenciesLackingRateAfterSwitch(SharedState state, String newBase) {
        CurrencyManager cm = state.getCurrencyManager();
        String oldBase = cm.getBaseCurrency();
        java.util.Set<String> used = new java.util.TreeSet<>();
        boolean anyData = state.hasBaseCurrencyAmounts();
        List<Expense> records = new ArrayList<>();
        if (state.getManager() != null) {
            records.addAll(state.getManager().getExpenses());
            records.addAll(state.getManager().getBaseRecurringExpenses());
        }
        for (Expense e : records) {
            if (e == null) continue;
            anyData = true;
            used.add(e.getCurrency() != null ? e.getCurrency() : oldBase);
        }
        for (Debt d : state.getDebts()) {
            anyData = true;
            used.add(d.getCurrency() != null ? d.getCurrency() : oldBase);
        }
        if (anyData) used.add(oldBase);
        return currenciesLackingRate(cm.getExchangeRates(), oldBase, newBase, used);
    }

    /** Pure core of {@link #currenciesLackingRateAfterSwitch}: which of {@code used} lack a rate to newBase. */
    static java.util.Set<String> currenciesLackingRate(Map<String, Double> oldRates, String oldBase,
                                                       String newBase, java.util.Collection<String> used) {
        java.util.Set<String> lacking = new java.util.TreeSet<>();
        if (newBase == null || newBase.equals(oldBase)) return lacking;
        Map<String, Double> converted = CurrencyManager.convertRates(oldRates, oldBase, newBase);
        for (String c : used) {
            if (c == null || c.equals(newBase)) continue;
            Double r = converted != null ? converted.get(c) : null;
            if (r == null || r <= 0 || r.isNaN() || r.isInfinite()) lacking.add(c);
        }
        return lacking;
    }

    private void refreshCurrencyValue() {
        suppressCurrencyListener = true;
        currencyCombo.setValue(state.getCurrencyManager().getBaseCurrency());
        suppressCurrencyListener = false;
    }

    // ======================== CATEGORIES ========================

    private void setupCategories() {
        categoryTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        catNameColumn.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().name()));
        catCountColumn.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().usageText()));
        categoryTable.setItems(categoryStats);

        VBox empty = new VBox(6);
        empty.setAlignment(Pos.CENTER);
        Label msg = new Label("No categories yet.");
        msg.getStyleClass().add("empty-state-label");
        Label hint = new Label("Add one below, or they'll be created as you enter expenses.");
        hint.getStyleClass().add("empty-state-hint");
        empty.getChildren().addAll(msg, hint);
        categoryTable.setPlaceholder(empty);

        categoryTable.getSelectionModel().selectedItemProperty().addListener((obs, oldSel, newSel) -> {
            boolean selected = newSel != null;
            renameCategoryButton.setDisable(!selected);
            deleteCategoryButton.setDisable(!selected);
        });

        refreshCategoryStats();
    }

    private void refreshCategoryStats() {
        Map<String, Long> counts = state.getManager().getExpenses().stream()
            .filter(e -> e.getCategory() != null)
            .collect(Collectors.groupingBy(Expense::getCategory, Collectors.counting()));
        List<CategoryStat> rows = new ArrayList<>();
        for (String c : state.getCategories()) {
            rows.add(new CategoryStat(c, counts.getOrDefault(c, 0L)));
        }
        categoryStats.setAll(rows);
    }

    @FXML
    private void handleAddCategory() {
        TextInputDialog dialog = new TextInputDialog();
        dialog.initOwner(state.getStage());
        dialog.setTitle("Add Category");
        dialog.setHeaderText("Create a new category");
        dialog.setContentText("Name:");
        UIUtils.applyStylesheet(dialog.getDialogPane());
        dialog.showAndWait().ifPresent(name -> {
            String trimmed = name.trim();
            if (trimmed.isEmpty()) { showMsg("Category name cannot be empty", true); return; }
            if (trimmed.length() > ExpenseManager.MAX_CATEGORY_LENGTH) { showMsg("Category name is too long (max " + ExpenseManager.MAX_CATEGORY_LENGTH + " characters)", true); return; }
            if (state.getCategories().contains(trimmed)) { showMsg("A category named \"" + trimmed + "\" already exists", true); return; }
            state.getCategories().add(trimmed);
            try {
                state.getStorage().saveCategories(state.getCategories());
            } catch (Exception ex) {
                state.getCategories().remove(trimmed);
                showMsg("Failed to save: " + ex.getMessage(), true);
                return;
            }
            refreshCategoryStats();
            showMsg("Added category \"" + trimmed + "\"", false);
        });
    }

    @FXML
    private void handleRenameCategory() {
        CategoryStat selected = categoryTable.getSelectionModel().getSelectedItem();
        if (selected == null) return;
        String oldCat = selected.name();

        TextInputDialog dialog = new TextInputDialog(oldCat);
        dialog.initOwner(state.getStage());
        dialog.setTitle("Rename Category");
        dialog.setHeaderText("Rename \"" + oldCat + "\"");
        dialog.setContentText("New name:");
        UIUtils.applyStylesheet(dialog.getDialogPane());
        dialog.showAndWait().ifPresent(name -> {
            String newCat = name.trim();
            if (newCat.isEmpty()) { showMsg("Category name cannot be empty", true); return; }
            if (newCat.equals(oldCat)) return;
            if (state.getCategories().contains(newCat)) {
                showMsg("\"" + newCat + "\" already exists — use Delete to merge into it instead", true);
                return;
            }
            int n = reassignPersist(oldCat, newCat, () -> {
                ObservableList<String> cats = state.getCategories();
                int idx = cats.indexOf(oldCat);
                if (idx >= 0) cats.set(idx, newCat); else cats.add(newCat);
            });
            if (n >= 0) {
                showMsg("Renamed \"" + oldCat + "\" to \"" + newCat + "\" (" + n + " expense" + (n == 1 ? "" : "s") + " updated)", false);
            }
        });
    }

    @FXML
    private void handleDeleteCategory() {
        CategoryStat selected = categoryTable.getSelectionModel().getSelectedItem();
        if (selected == null) return;
        String cat = selected.name();
        long count = selected.count();

        if (count == 0) {
            Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
            confirm.initOwner(state.getStage());
            confirm.setTitle("Delete Category");
            confirm.setHeaderText("Delete \"" + cat + "\"?");
            confirm.setContentText("This category isn't used by any expenses.");
            UIUtils.applyStylesheet(confirm.getDialogPane());
            confirm.showAndWait().ifPresent(result -> {
                if (result != ButtonType.OK) return;
                List<String> catsSnapshot = new ArrayList<>(state.getCategories());
                Map<String, Double> budgetSnapshot = new HashMap<>(state.getBudgets());
                state.getCategories().remove(cat);
                state.getBudgets().remove(cat);
                if (persistAll()) {
                    state.requestRefresh();
                    showMsg("Deleted category \"" + cat + "\"", false);
                } else {
                    state.getCategories().setAll(catsSnapshot);
                    state.getBudgets().clear();
                    state.getBudgets().putAll(budgetSnapshot);
                }
            });
            return;
        }

        // In use — reassign its expenses to another category (a merge)
        List<String> others = state.getCategories().stream()
            .filter(c -> !c.equals(cat)).collect(Collectors.toList());
        if (others.isEmpty()) {
            showMsg("Add another category first to move these expenses into", true);
            return;
        }
        ChoiceDialog<String> dialog = new ChoiceDialog<>(others.get(0), others);
        dialog.initOwner(state.getStage());
        dialog.setTitle("Delete Category");
        dialog.setHeaderText("\"" + cat + "\" is used by " + count + " expense" + (count == 1 ? "" : "s") + ".");
        dialog.setContentText("Move them to:");
        UIUtils.applyStylesheet(dialog.getDialogPane());
        dialog.showAndWait().ifPresent(target -> {
            if (target == null || target.equals(cat)) return;
            int n = reassignPersist(cat, target, () -> state.getCategories().remove(cat));
            if (n >= 0) {
                showMsg("Moved " + n + " expense" + (n == 1 ? "" : "s") + " to \"" + target + "\" and deleted \"" + cat + "\"", false);
            }
        });
    }

    /**
     * Reassigns {@code oldCat}→{@code newCat} (expenses, budgets, rules), applies the category-list
     * change, then persists. On save failure, fully restores the prior in-memory state so memory and
     * disk stay consistent (matching the add path).
     * @return number of expenses reassigned, or -1 if the save failed (error already shown).
     */
    private int reassignPersist(String oldCat, String newCat, Runnable categoryListUpdate) {
        Map<Expense, String> catSnapshot = state.getManager().snapshotCategories();
        List<String> catsSnapshot = new ArrayList<>(state.getCategories());
        Map<String, Double> budgetSnapshot = new HashMap<>(state.getBudgets());
        Map<String, String> rulesSnapshot = new LinkedHashMap<>(state.getCategorizationRules().getRules());

        int n = cascadeReassign(oldCat, newCat);
        categoryListUpdate.run();

        if (persistAll()) {
            state.requestRefresh();
            return n;
        }

        // Save failed — roll back every in-memory change to keep memory consistent with disk.
        state.getManager().restoreCategories(catSnapshot);
        state.getCategories().setAll(catsSnapshot);
        state.getBudgets().clear();
        state.getBudgets().putAll(budgetSnapshot);
        state.getCategorizationRules().loadFrom(rulesSnapshot);
        return -1;
    }

    /** Reassigns expenses, recurring templates, budgets, and rules from {@code oldCat} to {@code newCat}. */
    private int cascadeReassign(String oldCat, String newCat) {
        int n = state.getManager().renameCategory(oldCat, newCat);

        Map<String, Double> budgets = state.getBudgets();
        if (budgets.containsKey(oldCat)) {
            double v = budgets.remove(oldCat);
            budgets.merge(newCat, v, Double::sum);
        }

        Map<String, String> rules = state.getCategorizationRules().getRules();
        boolean ruleChanged = false;
        Map<String, String> updated = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : rules.entrySet()) {
            if (oldCat.equals(e.getValue())) { updated.put(e.getKey(), newCat); ruleChanged = true; }
            else updated.put(e.getKey(), e.getValue());
        }
        if (ruleChanged) state.getCategorizationRules().loadFrom(updated);

        return n;
    }

    private boolean persistAll() {
        try {
            state.saveExpenses();
            // renameCategory also rewrites per-occurrence override categories.
            state.saveRecurringOverrides();
            state.getStorage().saveBudgets(state.getBudgets());
            state.getStorage().saveCategorizationRules(state.getCategorizationRules().getRules());
            state.getStorage().saveCategories(state.getCategories());
            return true;
        } catch (Exception ex) {
            showMsg("Failed to save changes: " + ex.getMessage(), true);
            return false;
        }
    }

    private void showMsg(String message, boolean isError) {
        UIUtils.showMessage(message, isError, categoryErrorLabel);
    }

    /** A category and how many expenses currently use it. */
    public record CategoryStat(String name, long count) {
        public String usageText() {
            if (count == 0) return "—";
            return count + (count == 1 ? " expense" : " expenses");
        }
    }
}
