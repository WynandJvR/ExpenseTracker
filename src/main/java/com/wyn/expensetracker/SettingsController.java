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
            state.getCurrencyManager().setBaseCurrency(newVal);
            state.setCurrencySymbol(CurrencyManager.getSymbol(newVal));
            try {
                state.getStorage().saveBaseCurrency(newVal);
                state.getStorage().saveCurrencySymbol(CurrencyManager.getSymbol(newVal));
            } catch (Exception ex) { /* persistence best-effort */ }
            state.requestRefresh();
        });
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
