package com.wyn.expensetracker;

import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.scene.layout.*;
import javafx.stage.FileChooser;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.event.EventHandler;
import javafx.stage.WindowEvent;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;

import java.io.File;
import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

public class ImportController {

    @FXML private TableView<CategorizationRules.RuleEntry> rulesTable;
    @FXML private Label importErrorLabel;

    private SharedState state;

    @FXML
    public void initialize() {
    }

    public void init(SharedState state) {
        this.state = state;
        rulesTable.setItems(state.getCategorizationRules().getRuleEntries());

        VBox rulesEmptyState = new VBox(6);
        rulesEmptyState.setAlignment(Pos.CENTER);
        Label rulesMsg = new Label("No auto-categorization rules yet.");
        rulesMsg.getStyleClass().add("empty-state-label");
        Label rulesHint = new Label("Use 'Add Rule' to map a keyword to a category.");
        rulesHint.getStyleClass().add("empty-state-hint");
        rulesEmptyState.getChildren().addAll(rulesMsg, rulesHint);
        rulesTable.setPlaceholder(rulesEmptyState);

        // Load import logs from storage
        try {
            List<ImportLog> logs = state.getStorage().loadImportLogs();
            state.getImportLogs().setAll(logs);
        } catch (IOException e) {
            // Silently use empty list
        }
    }

    public void refresh() {
        rulesTable.refresh();
    }

    @FXML
    private void handleScanReceipt() {
        ReceiptScanner receiptScanner = state.getReceiptScanner();
        if (!receiptScanner.isTessDataAvailable()) {
            showMsg("OCR not available. Place eng.traineddata in ~/.expenseTracker/tessdata/", true);
            return;
        }

        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("Select Receipt Image");
        fileChooser.getExtensionFilters().addAll(
            new FileChooser.ExtensionFilter("Image Files", "*.jpg", "*.jpeg", "*.png", "*.bmp", "*.tiff", "*.tif"),
            new FileChooser.ExtensionFilter("All Files", "*.*")
        );
        File file = fileChooser.showOpenDialog(state.getStage());
        if (file == null) return;

        // Use EXIF photo date as fallback, otherwise today (user can edit dates in the review dialog)
        LocalDate exifDate = receiptScanner.extractPhotoDate(file);
        LocalDate fallbackDate = exifDate != null ? exifDate : LocalDate.now();

        // Show overlay spinner
        ProgressIndicator spinner = new ProgressIndicator();
        spinner.setPrefSize(48, 48);
        spinner.setMaxSize(48, 48);
        Label ocrStatusLabel = new Label("Scanning receipt...");
        ocrStatusLabel.setStyle("-fx-text-fill: white; -fx-font-size: 14px;");
        VBox spinnerBox = new VBox(12, spinner, ocrStatusLabel);
        spinnerBox.setAlignment(Pos.CENTER);
        spinnerBox.setStyle("-fx-background-color: rgba(0,0,0,0.7); -fx-background-radius: 12; -fx-padding: 30;");
        StackPane overlay = new StackPane(spinnerBox);
        overlay.setStyle("-fx-background-color: rgba(0,0,0,0.3);");

        Scene scene = state.getStage().getScene();
        Parent originalRoot = scene.getRoot();
        StackPane wrapper = new StackPane(originalRoot, overlay);
        scene.setRoot(wrapper);

        // Save and restore original close handler
        EventHandler<WindowEvent> originalCloseHandler = state.getStage().getOnCloseRequest();

        CategorizationRules categorizationRules = state.getCategorizationRules();

        Thread ocrThread = new Thread(() -> {
            if (Thread.currentThread().isInterrupted()) return;
            try {
                String ocrText = receiptScanner.performOcr(file);

                if (Thread.currentThread().isInterrupted()) return;
                Platform.runLater(() -> ocrStatusLabel.setText("Parsing items..."));

                List<ImportItem> items = receiptScanner.parseReceipt(ocrText, fallbackDate);

                // Auto-categorize
                for (ImportItem item : items) {
                    String cat = categorizationRules.categorize(item.getDescription());
                    if (cat != null) {
                        item.setCategory(cat);
                        item.setStatus("Auto-categorized");
                    }
                }

                if (Thread.currentThread().isInterrupted()) return;
                Platform.runLater(() -> {
                    state.getStage().setOnCloseRequest(originalCloseHandler);
                    wrapper.getChildren().clear();
                    scene.setRoot(originalRoot);

                    if (items.isEmpty()) {
                        // Show OCR text so user can see what was scanned
                        Alert alert = new Alert(Alert.AlertType.WARNING);
                        alert.initOwner(state.getStage());
                        alert.setTitle("No Items Found");
                        alert.setHeaderText("Could not extract any line items from this receipt.");
                        alert.getDialogPane().getStylesheets().add(getClass().getResource("/styles.css").toExternalForm());
                        TextArea ocrArea = new TextArea(ocrText);
                        ocrArea.setEditable(false);
                        ocrArea.setWrapText(true);
                        ocrArea.setPrefHeight(300);
                        alert.getDialogPane().setExpandableContent(
                            new VBox(5, new Label("OCR text (for debugging):"), ocrArea));
                        alert.showAndWait();
                        return;
                    }
                    showMsg("Found " + items.size() + " items.", false);
                    ImportReviewDialog dialog = new ImportReviewDialog(
                        state.getStage(), items, state.getCategories(), state.getCurrencySymbol(),
                        ocrText, categorizationRules, state.getManager().getExpenses());
                    List<Expense> expenses = dialog.showAndWait();
                    if (expenses != null && !expenses.isEmpty()) {
                        saveLearnedRules(dialog);
                        importExpenses(expenses, file.getName(), "Receipt");
                    }
                });
            } catch (Exception e) {
                if (Thread.currentThread().isInterrupted()) return;
                Platform.runLater(() -> {
                    state.getStage().setOnCloseRequest(originalCloseHandler);
                    wrapper.getChildren().clear();
                    scene.setRoot(originalRoot);
                    showMsg("OCR failed: " + e.getMessage(), true);
                });
            }
        });
        ocrThread.setDaemon(true);
        ocrThread.start();
        state.getStage().setOnCloseRequest(e -> {
            ocrThread.interrupt();
            if (originalCloseHandler != null) originalCloseHandler.handle(e);
        });
    }

    @FXML
    private void handleImportStatement() {
        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("Import Bank Statements");
        fileChooser.getExtensionFilters().addAll(
            new FileChooser.ExtensionFilter("Bank Statements", "*.pdf", "*.csv", "*.ofx", "*.qfx", "*.qif"),
            new FileChooser.ExtensionFilter("PDF Files", "*.pdf"),
            new FileChooser.ExtensionFilter("CSV Files", "*.csv"),
            new FileChooser.ExtensionFilter("OFX/QFX Files", "*.ofx", "*.qfx"),
            new FileChooser.ExtensionFilter("QIF Files", "*.qif"),
            new FileChooser.ExtensionFilter("All Files", "*.*")
        );
        List<File> files = fileChooser.showOpenMultipleDialog(state.getStage());
        if (files == null || files.isEmpty()) return;

        List<ImportItem> allItems = new ArrayList<>();
        List<String> fileNames = new ArrayList<>();

        for (File file : files) {
            try {
                String fileName = file.getName().toLowerCase();
                List<ImportItem> items;

                if (fileName.endsWith(".pdf")) {
                    items = parsePdfStatement(file);
                } else if (fileName.endsWith(".ofx") || fileName.endsWith(".qfx")) {
                    items = parseOfxStatement(file);
                } else if (fileName.endsWith(".qif")) {
                    items = parseQifStatement(file);
                } else {
                    items = parseCsvStatement(file);
                }

                if (items != null && !items.isEmpty()) {
                    for (ImportItem item : items) {
                        item.setSourceFile(file.getName());
                    }
                    allItems.addAll(items);
                    fileNames.add(file.getName());
                }
            } catch (Exception e) {
                showMsg("Failed to parse " + file.getName() + ": " + e.getMessage(), true);
            }
        }

        if (allItems.isEmpty()) {
            showMsg("No transactions found in the selected files.", true);
            return;
        }

        // Auto-categorize
        CategorizationRules categorizationRules = state.getCategorizationRules();
        for (ImportItem item : allItems) {
            String cat = categorizationRules.categorize(item.getDescription());
            if (cat != null) {
                item.setCategory(cat);
                item.setStatus("Auto-categorized");
            }
        }

        ImportReviewDialog dialog = new ImportReviewDialog(
            state.getStage(), allItems, state.getCategories(), state.getCurrencySymbol(),
            null, categorizationRules, state.getManager().getExpenses(),
            state.getManager().getBaseRecurringExpenses());
        List<Expense> expenses = dialog.showAndWait();
        if (expenses != null && !expenses.isEmpty()) {
            saveLearnedRules(dialog);

            // Build a map from each selected ImportItem to its resulting Expense.
            // The review dialog returns expenses in the same order as selected items.
            List<ImportItem> selectedItems = allItems.stream()
                .filter(i -> i.isSelected() && i.getAmount() > 0)
                .collect(Collectors.toList());

            // Group expenses by source file
            Map<String, List<Expense>> expensesByFile = new LinkedHashMap<>();
            for (int i = 0; i < expenses.size() && i < selectedItems.size(); i++) {
                String src = selectedItems.get(i).getSourceFile();
                if (src == null) src = "Unknown";
                expensesByFile.computeIfAbsent(src, k -> new ArrayList<>()).add(expenses.get(i));
            }

            // Import each file's expenses separately
            for (Map.Entry<String, List<Expense>> entry : expensesByFile.entrySet()) {
                String entryFileName = entry.getKey();
                String lowerName = entryFileName.toLowerCase();
                String type = lowerName.endsWith(".pdf") ? "PDF"
                    : lowerName.endsWith(".ofx") || lowerName.endsWith(".qfx") ? "OFX"
                    : lowerName.endsWith(".qif") ? "QIF" : "CSV";
                importExpenses(entry.getValue(), entryFileName, type);
            }
        }
    }

    @FXML
    private void handleShowImportHistory() {
        Stage dialog = new Stage();
        dialog.initOwner(state.getStage());
        dialog.initModality(Modality.WINDOW_MODAL);
        dialog.setTitle("Import History");

        // Table
        TableView<ImportLog> table = new TableView<>();
        table.setItems(state.getImportLogs());
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);

        TableColumn<ImportLog, String> dateCol = new TableColumn<>("Date");
        dateCol.setCellValueFactory(new PropertyValueFactory<>("timestampDisplay"));
        dateCol.setMinWidth(140);

        TableColumn<ImportLog, String> sourceCol = new TableColumn<>("Source");
        sourceCol.setCellValueFactory(new PropertyValueFactory<>("sourceFile"));
        sourceCol.setMinWidth(180);

        TableColumn<ImportLog, String> typeCol = new TableColumn<>("Type");
        typeCol.setCellValueFactory(new PropertyValueFactory<>("sourceType"));
        typeCol.setMinWidth(80);

        TableColumn<ImportLog, Number> itemsCol = new TableColumn<>("Items");
        itemsCol.setCellValueFactory(new PropertyValueFactory<>("itemCount"));
        itemsCol.setMinWidth(60);

        table.getColumns().addAll(dateCol, sourceCol, typeCol, itemsCol);
        table.setPlaceholder(new Label("No imports yet"));

        // Delete button
        Button deleteBtn = new Button("Delete Selected Import");
        deleteBtn.getStyleClass().add("danger-button");
        deleteBtn.disableProperty().bind(table.getSelectionModel().selectedItemProperty().isNull());
        deleteBtn.setOnAction(e -> {
            ImportLog selected = table.getSelectionModel().getSelectedItem();
            if (selected == null) return;

            ExpenseManager manager = state.getManager();
            long count = manager.getExpenses().stream()
                .filter(exp -> selected.getImportId().equals(exp.getImportId()))
                .count();

            Alert confirmation = new Alert(Alert.AlertType.CONFIRMATION);
            confirmation.initOwner(dialog);
            confirmation.setTitle("Delete Import");
            confirmation.setHeaderText("Delete import from " + selected.getSourceFile() + "?");
            confirmation.setContentText("This will remove " + count + " expense(s) that were imported on " +
                selected.getTimestampDisplay() + ".");
            confirmation.getDialogPane().getStylesheets().add(getClass().getResource("/styles.css").toExternalForm());
            confirmation.getDialogPane().getStyleClass().add("dialog-pane");

            if (confirmation.showAndWait().orElse(null) != ButtonType.OK) return;

            List<Expense> toRemove = manager.getExpenses().stream()
                .filter(exp -> selected.getImportId().equals(exp.getImportId()))
                .collect(Collectors.toList());

            if (!toRemove.isEmpty()) {
                manager.executeCommand(new Command() {
                    @Override public void execute() {
                        for (Expense exp : toRemove) manager.removeExpense(exp);
                    }
                    @Override public void undo() {
                        for (Expense exp : toRemove) manager.addExpense(exp);
                    }
                });
            }

            state.getImportLogs().remove(selected);
            try {
                state.getStorage().saveImportLogs(new ArrayList<>(state.getImportLogs()));
                state.saveExpenses();
            } catch (IOException ex) {
                showMsg("Failed to save after delete: " + ex.getMessage(), true);
                return;
            }

            state.requestRefresh();
            showMsg(count + " expenses from import deleted.", false);
        });

        // Layout
        HBox buttonBar = new HBox(deleteBtn);
        buttonBar.setAlignment(Pos.CENTER_RIGHT);
        buttonBar.setPadding(new Insets(10, 0, 0, 0));

        VBox root = new VBox(10, table, buttonBar);
        root.setPadding(new Insets(20));
        root.getStyleClass().add("dialog-pane");
        VBox.setVgrow(table, Priority.ALWAYS);

        Scene scene = new Scene(root, 560, 400);
        scene.getStylesheets().add(getClass().getResource("/styles.css").toExternalForm());
        dialog.setScene(scene);
        dialog.showAndWait();
    }

    @FXML
    private void handleAddRule() {
        Stage ruleStage = new Stage();
        ruleStage.initModality(Modality.WINDOW_MODAL);
        ruleStage.initOwner(state.getStage());
        ruleStage.setTitle("Add Categorization Rule");

        Label titleLabel = new Label("Add Auto-Categorization Rule");
        titleLabel.getStyleClass().add("section-title");

        Label keywordLabel = new Label("Keyword (matched in description):");
        keywordLabel.getStyleClass().add("form-label");
        TextField keywordField = new TextField();
        keywordField.getStyleClass().add("text-field");
        keywordField.setPromptText("e.g., SPAR, UBER, NETFLIX");

        Label catLabel = new Label("Category:");
        catLabel.getStyleClass().add("form-label");
        ComboBox<String> catCombo = new ComboBox<>(state.getCategories());
        catCombo.setEditable(true);
        catCombo.getStyleClass().add("combo-box");
        catCombo.setMaxWidth(Double.MAX_VALUE);

        Button addBtn = new Button("Add Rule");
        addBtn.getStyleClass().add("success-button");
        addBtn.setOnAction(e -> {
            String keyword = keywordField.getText().trim();
            String cat = catCombo.getValue();
            if (cat == null || cat.trim().isEmpty()) {
                cat = catCombo.getEditor().getText().trim();
            }
            if (keyword.isEmpty() || cat.isEmpty()) return;

            if (!state.getCategories().contains(cat)) {
                state.getCategories().add(cat);
                try { state.getStorage().saveCategories(state.getCategories()); } catch (IOException ex) { /* ignore */ }
            }
            state.getCategorizationRules().addRule(keyword, cat);
            try { state.getStorage().saveCategorizationRules(state.getCategorizationRules().getRules()); } catch (IOException ex) { /* ignore */ }
            rulesTable.refresh();
            ruleStage.close();
        });

        Button cancelBtn = new Button("Cancel");
        cancelBtn.getStyleClass().add("danger-button");
        cancelBtn.setOnAction(e -> ruleStage.close());

        HBox btnBox = new HBox(10, addBtn, cancelBtn);
        btnBox.setAlignment(Pos.CENTER_RIGHT);

        VBox layout = new VBox(8, titleLabel, keywordLabel, keywordField, catLabel, catCombo, btnBox);
        layout.setPadding(new Insets(15));
        layout.getStyleClass().add("root-pane");

        Scene scene = new Scene(layout, 380, 300);
        scene.getStylesheets().add(getClass().getResource("/styles.css").toExternalForm());
        ruleStage.setMinWidth(300);
        ruleStage.setMinHeight(250);
        ruleStage.setScene(scene);
        ruleStage.showAndWait();
    }

    @FXML
    private void handleRemoveRule() {
        CategorizationRules.RuleEntry selected = rulesTable.getSelectionModel().getSelectedItem();
        if (selected == null) {
            showMsg("Please select a rule to remove", true);
            return;
        }
        state.getCategorizationRules().removeRule(selected.getKeyword());
        try {
            state.getStorage().saveCategorizationRules(state.getCategorizationRules().getRules());
        } catch (IOException ex) {
            showMsg("Failed to save rules: " + ex.getMessage(), true);
        }
        rulesTable.refresh();
        showMsg("Rule removed.", false);
    }

    @FXML
    private void handleRecategorize() {
        ExpenseManager manager = state.getManager();
        List<Expense> allExpenses = manager.getExpenses();
        CategorizationRules categorizationRules = state.getCategorizationRules();
        int recategorized = 0;
        for (Expense expense : allExpenses) {
            if ("Uncategorized".equals(expense.getCategory())) {
                String cat = categorizationRules.categorize(expense.getDescription());
                if (cat != null) {
                    expense.setCategory(cat);
                    recategorized++;
                }
            }
        }
        if (recategorized > 0) {
            try {
                state.saveExpenses();
            } catch (IOException e) {
                showMsg("Failed to save: " + e.getMessage(), true);
                return;
            }
            state.requestRefresh();
            showMsg("Re-categorized " + recategorized + " expense(s).", false);
        } else {
            showMsg("No uncategorized expenses could be matched to existing rules.", false);
        }
    }

    // --- Private helpers ---

    private List<ImportItem> parsePdfStatement(File file) throws Exception {
        String text;
        try (PDDocument doc = PDDocument.load(file)) {
            PDFTextStripper stripper = new PDFTextStripper();
            text = stripper.getText(doc);
        }

        BankStatementParser[] parsers = { new FnbPdfParser(), new GenericPdfParser() };
        for (BankStatementParser parser : parsers) {
            if (parser.canParse(text)) {
                List<ImportItem> items = parser.parse(text);
                showMsg("Detected " + parser.getBankName() + ". " + items.size() + " transactions found.", false);
                return items;
            }
        }

        showMsg("Could not find transactions in this PDF. Try exporting as CSV instead.", true);
        return null;
    }

    private List<ImportItem> parseCsvStatement(File file) throws Exception {
        String text = new String(java.nio.file.Files.readAllBytes(file.toPath()));
        char delimiter = CsvStatementParser.detectDelimiter(text);
        String[] lines = text.split("\\r?\\n");
        if (lines.length < 2) {
            showMsg("CSV file is empty or has no data rows.", true);
            return null;
        }

        String[] headers = CsvStatementParser.parseHeaders(lines[0], delimiter);

        // Show column mapping dialog
        return showCsvMappingDialog(text, headers, delimiter, lines);
    }

    private List<ImportItem> parseOfxStatement(File file) throws Exception {
        String text = new String(java.nio.file.Files.readAllBytes(file.toPath()));
        OfxStatementParser parser = new OfxStatementParser();
        if (!parser.canParse(text)) {
            showMsg("File does not appear to be a valid OFX/QFX file.", true);
            return null;
        }
        List<ImportItem> items = parser.parse(text);
        showMsg("OFX: " + items.size() + " transactions found.", false);
        return items;
    }

    private List<ImportItem> parseQifStatement(File file) throws Exception {
        String text = new String(java.nio.file.Files.readAllBytes(file.toPath()));
        QifStatementParser parser = new QifStatementParser();
        if (!parser.canParse(text)) {
            showMsg("File does not appear to be a valid QIF file.", true);
            return null;
        }
        List<ImportItem> items = parser.parse(text);
        showMsg("QIF: " + items.size() + " transactions found.", false);
        return items;
    }

    private List<ImportItem> showCsvMappingDialog(String text, String[] headers, char delimiter, String[] lines) {
        Stage mappingStage = new Stage();
        mappingStage.initModality(Modality.WINDOW_MODAL);
        mappingStage.initOwner(state.getStage());
        mappingStage.setTitle("CSV Column Mapping");

        ObservableList<String> headerList = FXCollections.observableArrayList(headers);

        Label titleLabel = new Label("Map CSV columns to expense fields");
        titleLabel.getStyleClass().add("section-title");

        Label dateLabel = new Label("Date column:");
        dateLabel.getStyleClass().add("form-label");
        ComboBox<String> dateColCombo = new ComboBox<>(headerList);
        dateColCombo.getStyleClass().add("combo-box");
        dateColCombo.setMaxWidth(Double.MAX_VALUE);

        Label amountLabel = new Label("Amount column:");
        amountLabel.getStyleClass().add("form-label");
        ComboBox<String> amountColCombo = new ComboBox<>(headerList);
        amountColCombo.getStyleClass().add("combo-box");
        amountColCombo.setMaxWidth(Double.MAX_VALUE);

        Label descLabel = new Label("Description column:");
        descLabel.getStyleClass().add("form-label");
        ComboBox<String> descColCombo = new ComboBox<>(headerList);
        descColCombo.getStyleClass().add("combo-box");
        descColCombo.setMaxWidth(Double.MAX_VALUE);

        Label dateFormatLabel = new Label("Date format:");
        dateFormatLabel.getStyleClass().add("form-label");
        ComboBox<String> dateFormatCombo = new ComboBox<>(
            FXCollections.observableArrayList(CsvStatementParser.DATE_FORMATS));
        dateFormatCombo.getStyleClass().add("combo-box");
        dateFormatCombo.setValue("yyyy-MM-dd");
        dateFormatCombo.setMaxWidth(Double.MAX_VALUE);

        CheckBox negativeIsExpense = new CheckBox("Negative amounts are expenses");
        negativeIsExpense.getStyleClass().add("check-box");
        negativeIsExpense.setSelected(true);

        // Auto-select columns by common header names
        for (int i = 0; i < headers.length; i++) {
            String h = headers[i].toLowerCase().trim();
            if (h.contains("date")) dateColCombo.setValue(headers[i]);
            else if (h.contains("amount") || h.contains("debit") || h.contains("value")) amountColCombo.setValue(headers[i]);
            else if (h.contains("desc") || h.contains("narr") || h.contains("detail") || h.contains("reference")) descColCombo.setValue(headers[i]);
        }

        // Preview
        Label previewLabel = new Label("Preview (first 3 rows):");
        previewLabel.getStyleClass().add("form-label");
        TextArea previewArea = new TextArea();
        previewArea.setEditable(false);
        previewArea.getStyleClass().add("import-preview");
        previewArea.setPrefHeight(80);
        StringBuilder preview = new StringBuilder();
        for (int i = 0; i < Math.min(4, lines.length); i++) {
            preview.append(lines[i]).append("\n");
        }
        previewArea.setText(preview.toString());

        final List<ImportItem>[] resultHolder = new List[]{null};

        Button okBtn = new Button("Parse");
        okBtn.getStyleClass().add("success-button");
        okBtn.setOnAction(e -> {
            String dateCol = dateColCombo.getValue();
            String amountCol = amountColCombo.getValue();
            String descCol = descColCombo.getValue();
            if (dateCol == null || amountCol == null) {
                return;
            }
            int dateIdx = Arrays.asList(headers).indexOf(dateCol);
            int amountIdx = Arrays.asList(headers).indexOf(amountCol);
            int descIdx = descCol != null ? Arrays.asList(headers).indexOf(descCol) : -1;

            resultHolder[0] = CsvStatementParser.parse(text, delimiter, dateIdx, amountIdx,
                descIdx, dateFormatCombo.getValue(), negativeIsExpense.isSelected());
            mappingStage.close();
        });

        Button cancelBtn = new Button("Cancel");
        cancelBtn.getStyleClass().add("danger-button");
        cancelBtn.setOnAction(e -> mappingStage.close());

        HBox btnBox = new HBox(10, okBtn, cancelBtn);
        btnBox.setAlignment(Pos.CENTER_RIGHT);

        VBox layout = new VBox(8, titleLabel, dateLabel, dateColCombo, amountLabel, amountColCombo,
            descLabel, descColCombo, dateFormatLabel, dateFormatCombo, negativeIsExpense,
            previewLabel, previewArea, btnBox);
        layout.setPadding(new Insets(15));
        layout.getStyleClass().add("root-pane");

        ScrollPane scrollPane = new ScrollPane(layout);
        scrollPane.setFitToWidth(true);
        scrollPane.getStyleClass().add("root-pane");

        Scene scene = new Scene(scrollPane, 450, 550);
        scene.getStylesheets().add(getClass().getResource("/styles.css").toExternalForm());
        mappingStage.setMinWidth(350);
        mappingStage.setMinHeight(400);
        mappingStage.setScene(scene);
        mappingStage.showAndWait();

        return resultHolder[0];
    }

    private void saveLearnedRules(ImportReviewDialog dialog) {
        Map<String, String> learned = dialog.getLearnedRules();
        if (learned.isEmpty()) return;
        CategorizationRules categorizationRules = state.getCategorizationRules();
        for (Map.Entry<String, String> entry : learned.entrySet()) {
            if (!categorizationRules.getRules().containsKey(entry.getKey())) {
                categorizationRules.addRule(entry.getKey(), entry.getValue());
            }
        }
        try {
            state.getStorage().saveCategorizationRules(categorizationRules.getRules());
        } catch (IOException ex) {
            System.err.println("Failed to save learned rules: " + ex.getMessage());
        }
        rulesTable.refresh();
    }

    private void importExpenses(List<Expense> expenses, String sourceFile, String sourceType) {
        // Count income items (income flag already set by ImportReviewDialog)
        int incomeCount = 0;
        double totalIncomeAdded = 0;
        for (Expense exp : expenses) {
            if (exp.isIncome()) {
                incomeCount++;
                totalIncomeAdded += exp.getAmount();
            }
        }

        // Tag all items with a unique import ID
        String importId = "IMP-" + System.currentTimeMillis();
        for (Expense exp : expenses) {
            exp.setImportId(importId);
        }

        // Add all items (expenses + income) to the manager
        ExpenseManager manager = state.getManager();
        if (!expenses.isEmpty()) {
            BulkAddExpenseCommand cmd = new BulkAddExpenseCommand(manager, expenses);
            manager.executeCommand(cmd);
            try {
                state.saveExpenses();
            } catch (IOException ex) {
                manager.rollbackLastCommand();
                showMsg("Failed to save imported expenses: " + ex.getMessage(), true);
                return;
            }
            try {
                state.getStorage().saveCategories(state.getCategories());
            } catch (IOException ex) {
                showMsg("Expenses saved but failed to save categories: " + ex.getMessage(), true);
            }
        }

        // Log the import
        ImportLog log = new ImportLog(importId, LocalDateTime.now(), sourceFile, sourceType, expenses.size());
        state.getImportLogs().add(log);
        try {
            state.getStorage().saveImportLogs(new ArrayList<>(state.getImportLogs()));
        } catch (IOException ex) {
            System.err.println("Failed to save import log: " + ex.getMessage());
        }

        state.requestRefresh();

        // Build summary message
        StringBuilder msg = new StringBuilder();
        int expenseCount = expenses.size() - incomeCount;
        msg.append(expenseCount).append(" expenses imported");
        if (totalIncomeAdded > 0) {
            msg.append(", ").append(fmt(totalIncomeAdded)).append(" income added across ")
               .append(incomeCount).append(" transaction(s)");
        }
        msg.append("!");
        showMsg(msg.toString(), false);
    }

    private String fmt(double amount) {
        return UIUtils.fmt(amount, state.getCurrencySymbol());
    }

    private void showMsg(String message, boolean isError) {
        UIUtils.showMessage(message, isError, importErrorLabel);
    }
}
