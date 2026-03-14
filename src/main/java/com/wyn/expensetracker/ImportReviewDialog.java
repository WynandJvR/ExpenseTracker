package com.wyn.expensetracker;

import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.control.cell.CheckBoxTableCell;
import javafx.scene.layout.*;
import javafx.stage.Modality;
import javafx.stage.Stage;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class ImportReviewDialog {

    private final Stage dialogStage;
    private final ObservableList<ImportItem> items;
    private final ObservableList<ImportItem> expenseItems;
    private final ObservableList<ImportItem> incomeItems;
    private final ObservableList<String> categories;
    private final String currencySymbol;
    private final CategorizationRules categorizationRules;
    private List<Expense> result = null;
    private final Label summaryLabel;
    private final Map<String, String> learnedRules = new LinkedHashMap<>();
    private int duplicateCount;
    private TabPane tabPane;
    private Tab expenseTab;
    private Tab incomeTab;
    private TableView<ImportItem> expenseTable;
    private TableView<ImportItem> incomeTable;

    public ImportReviewDialog(Stage owner, List<ImportItem> importItems,
                              ObservableList<String> categories, String currencySymbol,
                              String rawText, CategorizationRules categorizationRules,
                              List<Expense> existingExpenses) {
        this.categorizationRules = categorizationRules;
        this.items = FXCollections.observableArrayList(importItems);
        this.categories = categories;
        this.currencySymbol = currencySymbol;

        // Run duplicate detection
        this.duplicateCount = DuplicateDetector.flagDuplicates(
            new ArrayList<>(this.items), existingExpenses);

        // Mark duplicate status and auto-deselect
        for (ImportItem item : items) {
            if (item.isDuplicate()) {
                item.setStatus("Duplicate");
                item.setSelected(false);
            }
        }

        // Detect refunds among credit items
        for (ImportItem item : items) {
            if (item.isIncome() && !item.isDuplicate() && isRefundDescription(item.getDescription())) {
                item.setStatus("Refund");
            }
        }

        // Split items into expenses and income
        // All credits go to income tab (refunds are still money received)
        expenseItems = FXCollections.observableArrayList();
        incomeItems = FXCollections.observableArrayList();
        for (ImportItem item : items) {
            if (item.isIncome()) {
                item.setSelected(true); // Select income items by default
                incomeItems.add(item);
            } else {
                expenseItems.add(item);
            }
        }

        dialogStage = new Stage();
        dialogStage.initModality(Modality.WINDOW_MODAL);
        dialogStage.initOwner(owner);
        dialogStage.setTitle("Review Import - " + items.size() + " items found");

        // Summary label
        summaryLabel = new Label();
        summaryLabel.getStyleClass().add("section-title");

        // Create tabs early so updateSummary can set their titles
        tabPane = new TabPane();
        tabPane.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        expenseTab = new Tab();
        incomeTab = new Tab();

        updateSummary();

        // Auto-deselect duplicates checkbox
        CheckBox autoDeselectDuplicates = new CheckBox("Auto-deselect duplicates");
        autoDeselectDuplicates.setSelected(true);

        // Create tables for each tab
        expenseTable = createTable(expenseItems, false);
        incomeTable = createTable(incomeItems, true);

        // Wire checkbox listener
        autoDeselectDuplicates.selectedProperty().addListener((obs, wasSelected, isSelected) -> {
            for (ImportItem item : expenseItems) {
                if (item.isDuplicate()) item.setSelected(!isSelected);
            }
            for (ImportItem item : incomeItems) {
                if (item.isDuplicate()) item.setSelected(!isSelected);
            }
            expenseTable.refresh();
            incomeTable.refresh();
            updateSummary();
        });

        // Top bar with summary and duplicate controls
        HBox topBar = new HBox(10, summaryLabel);
        topBar.setAlignment(Pos.CENTER_LEFT);
        if (duplicateCount > 0) {
            Label dupLabel = new Label("(" + duplicateCount + " potential duplicate" + (duplicateCount == 1 ? "" : "s") + ")");
            dupLabel.getStyleClass().add("status-duplicate");
            topBar.getChildren().addAll(dupLabel, autoDeselectDuplicates);
        }

        VBox expenseContent = new VBox(expenseTable);
        VBox.setVgrow(expenseTable, Priority.ALWAYS);
        expenseTab.setContent(expenseContent);

        VBox incomeContent = new VBox(incomeTable);
        VBox.setVgrow(incomeTable, Priority.ALWAYS);
        incomeTab.setContent(incomeContent);

        tabPane.getTabs().addAll(expenseTab, incomeTab);

        // Style income tab header
        incomeTab.setStyle("-fx-text-base-color: #4CAF50;");

        // Buttons
        HBox buttonBar = createButtonBar();

        // Layout
        VBox mainBox = new VBox(10);
        mainBox.setPadding(new Insets(15));
        mainBox.getStyleClass().add("root-pane");

        if (rawText != null && !rawText.isEmpty()) {
            TextArea ocrArea = new TextArea(rawText);
            ocrArea.setEditable(false);
            ocrArea.getStyleClass().add("ocr-text-area");
            ocrArea.setPrefHeight(300);
            ocrArea.setWrapText(true);

            SplitPane splitPane = new SplitPane();
            VBox leftPane = new VBox(5, topBar, tabPane);
            VBox.setVgrow(tabPane, Priority.ALWAYS);
            leftPane.setPadding(new Insets(5));

            Label ocrTitle = new Label("OCR Text");
            ocrTitle.getStyleClass().add("form-label");
            VBox rightPane = new VBox(5, ocrTitle, ocrArea);
            VBox.setVgrow(ocrArea, Priority.ALWAYS);
            rightPane.setPadding(new Insets(5));

            splitPane.getItems().addAll(leftPane, rightPane);
            splitPane.setDividerPositions(0.6);
            VBox.setVgrow(splitPane, Priority.ALWAYS);
            mainBox.getChildren().addAll(splitPane, buttonBar);
        } else {
            VBox.setVgrow(tabPane, Priority.ALWAYS);
            mainBox.getChildren().addAll(topBar, tabPane, buttonBar);
        }

        Scene scene = new Scene(mainBox, 950, 650);
        scene.getStylesheets().add(getClass().getResource("/styles.css").toExternalForm());
        dialogStage.setScene(scene);
    }

    private void updateTabTitles() {
        long expSelected = expenseItems.stream().filter(ImportItem::isSelected).count();
        long incSelected = incomeItems.stream().filter(ImportItem::isSelected).count();
        expenseTab.setText("Expenses (" + expenseItems.size() + ")  -  " + expSelected + " selected");
        incomeTab.setText("Income (" + incomeItems.size() + ")  -  " + incSelected + " selected");
    }

    private TableView<ImportItem> createTable(ObservableList<ImportItem> tableItems, boolean isIncomeTab) {
        TableView<ImportItem> table = new TableView<>(tableItems);
        table.setEditable(true);
        table.getStyleClass().add("table-view");

        // Checkbox column
        TableColumn<ImportItem, Boolean> selectCol = new TableColumn<>("");
        selectCol.setCellValueFactory(cd -> cd.getValue().selectedProperty());
        selectCol.setCellFactory(CheckBoxTableCell.forTableColumn(selectCol));
        selectCol.setEditable(true);
        selectCol.setPrefWidth(40);

        // Amount column
        TableColumn<ImportItem, Number> amountCol = new TableColumn<>("Amount");
        amountCol.setCellValueFactory(cd -> cd.getValue().amountProperty());
        amountCol.setCellFactory(col -> new TableCell<>() {
            private final TextField textField = new TextField();
            {
                textField.getStyleClass().add("text-field");
                textField.setOnAction(e -> {
                    try {
                        commitEdit(Double.parseDouble(textField.getText()));
                    } catch (NumberFormatException ex) {
                        cancelEdit();
                    }
                });
            }
            @Override
            protected void updateItem(Number item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setGraphic(null);
                } else {
                    setText(String.format("%s %.2f", currencySymbol, item.doubleValue()));
                }
            }

            @Override
            public void startEdit() {
                super.startEdit();
                textField.setText(String.format("%.2f", getItem().doubleValue()));
                setGraphic(textField);
                setText(null);
                textField.requestFocus();
            }

            @Override
            public void cancelEdit() {
                super.cancelEdit();
                setText(String.format("%s %.2f", currencySymbol, getItem().doubleValue()));
                setGraphic(null);
            }

            @Override
            public void commitEdit(Number newValue) {
                super.commitEdit(newValue);
                ImportItem item2 = getTableView().getItems().get(getIndex());
                item2.setAmount(newValue.doubleValue());
                setText(String.format("%s %.2f", currencySymbol, newValue.doubleValue()));
                setGraphic(null);
                updateSummary();
            }
        });
        amountCol.setEditable(true);
        amountCol.setPrefWidth(100);

        // Category column (ComboBox)
        TableColumn<ImportItem, String> categoryCol = new TableColumn<>("Category");
        categoryCol.setCellValueFactory(cd -> cd.getValue().categoryProperty());
        categoryCol.setCellFactory(col -> new TableCell<>() {
            private final ComboBox<String> combo = new ComboBox<>(categories);
            {
                combo.setEditable(true);
                combo.getStyleClass().add("combo-box");
                combo.setMaxWidth(Double.MAX_VALUE);
                combo.setOnAction(e -> {
                    String val = combo.getValue();
                    if (val != null && !val.trim().isEmpty()) {
                        commitEdit(val.trim());
                    }
                });
            }
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty) {
                    setText(null);
                    setGraphic(null);
                } else {
                    setText(item != null && !item.isEmpty() ? item : "(none)");
                }
            }

            @Override
            public void startEdit() {
                super.startEdit();
                combo.setValue(getItem());
                setGraphic(combo);
                setText(null);
                combo.requestFocus();
            }

            @Override
            public void cancelEdit() {
                super.cancelEdit();
                setText(getItem() != null && !getItem().isEmpty() ? getItem() : "(none)");
                setGraphic(null);
            }

            @Override
            public void commitEdit(String newValue) {
                super.commitEdit(newValue);
                ImportItem item2 = getTableView().getItems().get(getIndex());
                item2.setCategory(newValue);
                if (!categories.contains(newValue)) {
                    categories.add(newValue);
                }
                item2.setStatus("Manual");
                // Auto-learn: extract keyword from description for future imports
                String desc = item2.getDescription();
                if (desc != null && !desc.isEmpty() && newValue != null && !newValue.isEmpty()) {
                    String keyword = extractKeyword(desc);
                    if (keyword != null && !keyword.isEmpty()) {
                        learnedRules.put(keyword, newValue);
                        // Live propagation: apply this keyword to other uncategorized items
                        propagateRule(keyword, newValue, getTableView());
                    }
                }
                setText(newValue);
                setGraphic(null);
                updateSummary();
            }
        });
        categoryCol.setEditable(true);
        categoryCol.setPrefWidth(130);

        // Date column
        TableColumn<ImportItem, LocalDate> dateCol = new TableColumn<>("Date");
        dateCol.setCellValueFactory(cd -> cd.getValue().dateProperty());
        dateCol.setCellFactory(col -> new TableCell<>() {
            private final DatePicker picker = new DatePicker();
            {
                picker.getStyleClass().add("date-picker");
                picker.setOnAction(e -> commitEdit(picker.getValue()));
            }
            @Override
            protected void updateItem(LocalDate item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setGraphic(null);
                } else {
                    setText(item.toString());
                }
            }

            @Override
            public void startEdit() {
                super.startEdit();
                picker.setValue(getItem());
                setGraphic(picker);
                setText(null);
            }

            @Override
            public void cancelEdit() {
                super.cancelEdit();
                setText(getItem() != null ? getItem().toString() : null);
                setGraphic(null);
            }

            @Override
            public void commitEdit(LocalDate newValue) {
                super.commitEdit(newValue);
                ImportItem item2 = getTableView().getItems().get(getIndex());
                item2.setDate(newValue);
                setText(newValue.toString());
                setGraphic(null);
            }
        });
        dateCol.setEditable(true);
        dateCol.setPrefWidth(110);

        // Description column
        TableColumn<ImportItem, String> descCol = new TableColumn<>("Description");
        descCol.setCellValueFactory(cd -> cd.getValue().descriptionProperty());
        descCol.setCellFactory(col -> new TableCell<>() {
            private final TextField textField = new TextField();
            {
                textField.getStyleClass().add("text-field");
                textField.setOnAction(e -> commitEdit(textField.getText()));
            }
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty) {
                    setText(null);
                    setGraphic(null);
                } else {
                    setText(item);
                }
            }

            @Override
            public void startEdit() {
                super.startEdit();
                textField.setText(getItem());
                setGraphic(textField);
                setText(null);
                textField.requestFocus();
            }

            @Override
            public void cancelEdit() {
                super.cancelEdit();
                setText(getItem());
                setGraphic(null);
            }

            @Override
            public void commitEdit(String newValue) {
                super.commitEdit(newValue);
                ImportItem item2 = getTableView().getItems().get(getIndex());
                item2.setDescription(newValue);
                setText(newValue);
                setGraphic(null);
            }
        });
        descCol.setEditable(true);
        descCol.setPrefWidth(250);

        // Status column
        TableColumn<ImportItem, String> statusCol = new TableColumn<>("Status");
        statusCol.setCellValueFactory(cd -> cd.getValue().statusProperty());
        statusCol.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    getStyleClass().removeAll("status-auto", "status-uncategorized", "status-transfer", "status-manual", "status-duplicate", "status-refund");
                } else {
                    setText(item);
                    getStyleClass().removeAll("status-auto", "status-uncategorized", "status-transfer", "status-manual", "status-duplicate", "status-refund");
                    if ("Auto-categorized".equals(item)) {
                        getStyleClass().add("status-auto");
                    } else if ("Uncategorized".equals(item)) {
                        getStyleClass().add("status-uncategorized");
                    } else if ("Transfer".equals(item)) {
                        getStyleClass().add("status-transfer");
                    } else if ("Manual".equals(item)) {
                        getStyleClass().add("status-manual");
                    } else if ("Duplicate".equals(item)) {
                        getStyleClass().add("status-duplicate");
                    } else if ("Refund".equals(item)) {
                        getStyleClass().add("status-refund");
                    }
                }
            }
        });
        statusCol.setPrefWidth(120);

        // Row factory: duplicate highlighting + right-click to move between tabs
        table.setRowFactory(tv -> new TableRow<>() {
            @Override
            protected void updateItem(ImportItem item, boolean empty) {
                super.updateItem(item, empty);
                getStyleClass().removeAll("duplicate-row");
                if (empty || item == null) {
                    setTooltip(null);
                    setContextMenu(null);
                } else {
                    if (item.isDuplicate()) {
                        getStyleClass().add("duplicate-row");
                        Expense match = item.getDuplicateMatch();
                        if (match != null) {
                            setTooltip(new Tooltip(String.format(
                                "Potential duplicate of: %s on %s (%s %.2f)",
                                match.getDescription(), match.getDate(),
                                currencySymbol, match.getAmount())));
                        }
                    } else {
                        setTooltip(null);
                    }

                    ContextMenu menu = new ContextMenu();
                    if (isIncomeTab) {
                        MenuItem moveToExpenses = new MenuItem("Move to Expenses");
                        moveToExpenses.setOnAction(e -> {
                            ImportItem rowItem = getItem();
                            if (rowItem != null) {
                                rowItem.setIncome(false);
                                incomeItems.remove(rowItem);
                                expenseItems.add(rowItem);
                                updateTabTitles();
                                updateSummary();
                            }
                        });
                        menu.getItems().add(moveToExpenses);
                    } else {
                        MenuItem moveToIncome = new MenuItem("Move to Income");
                        moveToIncome.setOnAction(e -> {
                            ImportItem rowItem = getItem();
                            if (rowItem != null) {
                                rowItem.setIncome(true);
                                expenseItems.remove(rowItem);
                                incomeItems.add(rowItem);
                                updateTabTitles();
                                updateSummary();
                            }
                        });
                        menu.getItems().add(moveToIncome);
                    }
                    setContextMenu(menu);
                }
            }
        });

        table.getColumns().addAll(selectCol, amountCol, categoryCol, dateCol, descCol, statusCol);
        return table;
    }

    private ObservableList<ImportItem> getActiveTabItems() {
        if (tabPane.getSelectionModel().getSelectedItem() == incomeTab) {
            return incomeItems;
        }
        return expenseItems;
    }

    private TableView<ImportItem> getActiveTable() {
        if (tabPane.getSelectionModel().getSelectedItem() == incomeTab) {
            return incomeTable;
        }
        return expenseTable;
    }

    private HBox createButtonBar() {
        Button selectAll = new Button("Select All");
        selectAll.getStyleClass().add("primary-button");
        selectAll.setOnAction(e -> {
            getActiveTabItems().forEach(i -> i.setSelected(true));
            getActiveTable().refresh();
            updateSummary();
        });

        Button deselectAll = new Button("Deselect All");
        deselectAll.getStyleClass().add("primary-button");
        deselectAll.setOnAction(e -> {
            getActiveTabItems().forEach(i -> i.setSelected(false));
            getActiveTable().refresh();
            updateSummary();
        });

        // Bulk categorize: assign a category to all currently selected uncategorized items in active tab
        Button bulkCategorize = new Button("Categorize Selected");
        bulkCategorize.getStyleClass().add("primary-button");
        bulkCategorize.setOnAction(e -> {
            ObservableList<ImportItem> activeItems = getActiveTabItems();
            List<ImportItem> uncategorizedSelected = new ArrayList<>();
            for (ImportItem item : activeItems) {
                if (item.isSelected() && "Uncategorized".equals(item.getStatus())) {
                    uncategorizedSelected.add(item);
                }
            }
            if (uncategorizedSelected.isEmpty()) {
                return;
            }
            ComboBox<String> catPicker = new ComboBox<>(categories);
            catPicker.setEditable(true);
            catPicker.setPromptText("Select category...");
            catPicker.setMaxWidth(Double.MAX_VALUE);

            Alert dialog = new Alert(Alert.AlertType.NONE);
            dialog.initOwner(dialogStage);
            dialog.setTitle("Bulk Categorize");
            dialog.setHeaderText("Assign category to " + uncategorizedSelected.size() + " uncategorized selected item(s)");
            dialog.getDialogPane().setContent(catPicker);
            dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
            dialog.getDialogPane().getStylesheets().add(getClass().getResource("/styles.css").toExternalForm());

            dialog.showAndWait().ifPresent(bt -> {
                if (bt == ButtonType.OK) {
                    String cat = catPicker.getValue();
                    if (cat == null || cat.trim().isEmpty()) {
                        cat = catPicker.getEditor().getText();
                    }
                    if (cat != null && !cat.trim().isEmpty()) {
                        cat = cat.trim();
                        if (!categories.contains(cat)) {
                            categories.add(cat);
                        }
                        for (ImportItem item : uncategorizedSelected) {
                            item.setCategory(cat);
                            item.setStatus("Manual");
                            // Learn keyword from each item
                            String keyword = extractKeyword(item.getDescription());
                            if (keyword != null && !keyword.isEmpty()) {
                                learnedRules.put(keyword, cat);
                            }
                        }
                        // Now propagate all learned rules to remaining uncategorized
                        for (Map.Entry<String, String> rule : learnedRules.entrySet()) {
                            propagateRule(rule.getKey(), rule.getValue(), null);
                        }
                        getActiveTable().refresh();
                        updateSummary();
                    }
                }
            });
        });

        Button importBtn = new Button("Import Selected");
        importBtn.getStyleClass().add("success-button");
        importBtn.setOnAction(e -> {
            result = new ArrayList<>();
            // Add selected expenses
            for (ImportItem item : expenseItems) {
                if (item.isSelected() && item.getAmount() > 0) {
                    String cat = item.getCategory();
                    if (cat == null || cat.trim().isEmpty()) {
                        cat = "Uncategorized";
                        if (!categories.contains(cat)) {
                            categories.add(cat);
                        }
                    }
                    result.add(new Expense(item.getAmount(), cat, item.getDate(),
                        item.getDescription()));
                }
            }
            // Add selected income items with income flag
            for (ImportItem item : incomeItems) {
                if (item.isSelected() && item.getAmount() > 0) {
                    String cat = item.getCategory();
                    if (cat == null || cat.trim().isEmpty()) {
                        cat = "Income";
                        if (!categories.contains(cat)) {
                            categories.add(cat);
                        }
                    }
                    Expense exp = new Expense(item.getAmount(), cat, item.getDate(),
                        item.getDescription());
                    exp.setIncome(true);
                    if ("Refund".equals(item.getStatus())) {
                        exp.setRefund(true);
                    }
                    result.add(exp);
                }
            }
            dialogStage.close();
        });

        Button cancelBtn = new Button("Cancel");
        cancelBtn.getStyleClass().add("danger-button");
        cancelBtn.setOnAction(e -> {
            result = null;
            dialogStage.close();
        });

        // Listen for selection changes to update summary
        for (ImportItem item : items) {
            item.selectedProperty().addListener((obs, ov, nv) -> updateSummary());
        }

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        HBox bar = new HBox(10, selectAll, deselectAll, bulkCategorize, spacer, importBtn, cancelBtn);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setPadding(new Insets(10, 0, 0, 0));
        return bar;
    }

    private void updateSummary() {
        long expCount = expenseItems.stream().filter(ImportItem::isSelected).count();
        double expTotal = expenseItems.stream().filter(ImportItem::isSelected)
            .mapToDouble(ImportItem::getAmount).sum();
        long incCount = incomeItems.stream().filter(ImportItem::isSelected).count();
        double incTotal = incomeItems.stream().filter(ImportItem::isSelected)
            .mapToDouble(ImportItem::getAmount).sum();

        StringBuilder sb = new StringBuilder();
        sb.append(String.format("%d expenses (%s %.2f)", expCount, currencySymbol, expTotal));
        if (!incomeItems.isEmpty()) {
            sb.append(String.format("  |  %d income (%s %.2f)", incCount, currencySymbol, incTotal));
        }
        summaryLabel.setText(sb.toString());
        updateTabTitles();
    }

    public Map<String, String> getLearnedRules() {
        return learnedRules;
    }

    /**
     * Apply a newly learned keyword rule to all other uncategorized items in the batch.
     */
    private void propagateRule(String keyword, String category, TableView<ImportItem> table) {
        String keywordLower = keyword.toLowerCase();
        int propagated = 0;
        for (ImportItem item : items) {
            if (!"Uncategorized".equals(item.getStatus())) continue;
            String desc = item.getDescription();
            if (desc == null || desc.isEmpty()) continue;
            if (desc.toLowerCase().contains(keywordLower)) {
                item.setCategory(category);
                item.setStatus("Auto-categorized");
                propagated++;
            }
        }
        // Also try matching via the categorization rules normalize approach
        if (categorizationRules != null) {
            for (ImportItem item : items) {
                if (!"Uncategorized".equals(item.getStatus())) continue;
                String cat = categorizationRules.categorize(item.getDescription());
                if (cat == null) {
                    // Try matching with the new keyword using normalized comparison
                    String desc = item.getDescription();
                    if (desc != null && normalizedContains(desc, keywordLower)) {
                        item.setCategory(category);
                        item.setStatus("Auto-categorized");
                        propagated++;
                    }
                }
            }
        }
        if (propagated > 0) {
            if (table != null) table.refresh();
            if (expenseTable != null) expenseTable.refresh();
            if (incomeTable != null) incomeTable.refresh();
        }
    }

    private static boolean normalizedContains(String text, String keyword) {
        String normText = normalize(text.toLowerCase());
        String normKeyword = normalize(keyword.toLowerCase());
        if (normText.contains(normKeyword)) return true;
        // Also try compact match (strip spaces) for "Mr.D" vs "Mr D" type cases
        String compactText = normText.replace(" ", "");
        String compactKeyword = normKeyword.replace(" ", "");
        return compactKeyword.length() >= 3 && compactText.contains(compactKeyword);
    }

    private static String normalize(String s) {
        String result = s.replaceAll("[.*\\-_/\\\\,;:!?'\"()\\[\\]{}#@&+=<>|~^`]", "");
        result = result.replaceAll("\\s+", " ").trim();
        return result;
    }

    private static String extractKeyword(String description) {
        // Strip common prefixes to get the merchant name
        String clean = description
            .replaceFirst("(?i)^\\[CREDIT\\]\\s*", "")
            .replaceFirst("(?i)^POS Purchase\\s+", "")
            .replaceFirst("(?i)^FNB App Payment To\\s+", "")
            .replaceFirst("(?i)^FNB App Payment From\\s+", "")
            .replaceFirst("(?i)^Magtape Credit\\s+", "")
            .replaceFirst("(?i)^Payshap Credit\\s+", "")
            .replaceFirst("(?i)^FNB OB Pmt\\s+", "")
            .replaceFirst("(?i)^Refund Chq Card Purchase\\s+", "")
            .replaceFirst("(?i)^Credit Voucher Vouch\\s+", "")
            .trim();
        // Take first 2-3 meaningful words as keyword
        String[] words = clean.split("\\s+");
        if (words.length == 0) return null;
        StringBuilder keyword = new StringBuilder(words[0]);
        for (int i = 1; i < Math.min(words.length, 3); i++) {
            // Stop at numbers/codes
            if (words[i].matches("\\d+.*|\\*.*")) break;
            keyword.append(" ").append(words[i]);
        }
        String result = keyword.toString().trim();
        return result.length() >= 3 ? result : null;
    }

    private static boolean isRefundDescription(String description) {
        if (description == null || description.isEmpty()) return false;
        String lower = description.toLowerCase();
        return lower.contains("credit voucher")
            || lower.contains("refund")
            || lower.contains("reversal")
            || lower.contains("chargeback")
            || lower.contains("cashback")
            || lower.contains("dispute");
    }

    public List<Expense> showAndWait() {
        dialogStage.showAndWait();
        return result;
    }
}
