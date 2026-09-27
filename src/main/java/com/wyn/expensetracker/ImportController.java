package com.wyn.expensetracker;

import javafx.application.Platform;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.input.DragEvent;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.*;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.event.EventHandler;
import javafx.stage.WindowEvent;

import java.io.File;
import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * The Import screen. Statements go through {@link StatementImporter} on a background
 * thread and are committed without any review step; the watched folder is scanned on
 * startup and whenever the window regains focus.
 */
public class ImportController {

    private static final DateTimeFormatter PERIOD_FMT = DateTimeFormatter.ofPattern("d MMM yyyy");
    private static final long FOCUS_RESCAN_MILLIS = 60_000;

    @FXML private StackPane dropZone;
    @FXML private VBox dropIdle;
    @FXML private VBox dropBusy;
    @FXML private Label busyLabel;
    @FXML private VBox resultBox;
    @FXML private Label folderLabel;
    @FXML private Button scanNowButton;
    @FXML private Button clearFolderButton;
    @FXML private Button deleteImportButton;
    @FXML private TableView<ImportRow> statementsTable;
    @FXML private TableColumn<ImportRow, String> periodColumn;
    @FXML private TableColumn<ImportRow, String> accountColumn;
    @FXML private TableColumn<ImportRow, Number> countColumn;
    @FXML private TableColumn<ImportRow, ImportRow> checkColumn;
    @FXML private TableColumn<ImportRow, String> closingColumn;
    @FXML private TableColumn<ImportRow, String> fileColumn;
    @FXML private TitledPane rulesPane;
    @FXML private TableView<CategorizationRules.RuleEntry> rulesTable;
    @FXML private Label importErrorLabel;

    private SharedState state;
    private ImportRegistry registry;
    private boolean busy;
    /** Set when import history can't be saved or read; the silent folder scan stops until restart. */
    private boolean autoScanPaused;
    /** path|size|modified of folder files already looked at, so focus scans don't re-read them. */
    private final Set<String> scannedFolderFiles = new HashSet<>();
    private long lastFolderScan;
    private boolean focusListenerInstalled;
    private Runnable onReviewUncategorized = () -> {};
    private java.util.function.Consumer<java.time.YearMonth> onImported = ym -> {};
    private final ObservableList<ImportRow> rows = FXCollections.observableArrayList();

    /** One row of the imported-statements table: an import log plus its statement summary, if any. */
    public static class ImportRow {
        final ImportLog log;
        final ImportRegistry.StatementRecord record;

        ImportRow(ImportLog log, ImportRegistry.StatementRecord record) {
            this.log = log;
            this.record = record;
        }
    }

    @FXML
    public void initialize() {
        periodColumn.setCellValueFactory(c -> new ReadOnlyStringWrapper(periodText(c.getValue())));
        accountColumn.setCellValueFactory(c -> {
            ImportRow r = c.getValue();
            String text = r.record != null
                ? r.record.bank + (r.record.account != null ? " · " + r.record.account : "")
                : r.log.getSourceType();
            return new ReadOnlyStringWrapper(text);
        });
        countColumn.setCellValueFactory(c -> new ReadOnlyObjectWrapper<>(c.getValue().log.getItemCount()));
        closingColumn.setCellValueFactory(c -> {
            ImportRegistry.StatementRecord r = c.getValue().record;
            return new ReadOnlyStringWrapper(r != null && r.closingBalance != null ? fmt(r.closingBalance) : "—");
        });
        fileColumn.setCellValueFactory(c -> new ReadOnlyStringWrapper(c.getValue().log.getSourceFile()));
        checkColumn.setCellValueFactory(c -> new ReadOnlyObjectWrapper<>(c.getValue()));
        checkColumn.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(ImportRow row, boolean empty) {
                super.updateItem(row, empty);
                setText(null);
                if (empty || row == null) {
                    setGraphic(null);
                    return;
                }
                Label badge = new Label();
                badge.getStyleClass().add("badge");
                if (row.record == null || row.record.openingBalance == null) {
                    badge.setText("Not available");
                    badge.getStyleClass().add("badge-neutral");
                    HoverTip.install(badge, "This file doesn't include opening and closing balances.");
                } else if (row.record.reconciled) {
                    badge.setText("✓ Matches");
                    badge.getStyleClass().add("badge-good");
                    HoverTip.install(badge, "Opening balance + money in − money out equals the closing balance, "
                        + "so no transactions were missed.");
                } else {
                    badge.setText("⚠ Check");
                    badge.getStyleClass().add("badge-warn");
                    HoverTip.install(badge, "The imported transactions don't add up to the statement's closing "
                        + "balance. Some lines may not have been read correctly.");
                }
                setGraphic(badge);
            }
        });
        statementsTable.setItems(rows);
        Label empty = new Label("Nothing imported yet — drop a statement above.");
        empty.getStyleClass().add("empty-state-hint");
        statementsTable.setPlaceholder(empty);
        deleteImportButton.disableProperty().bind(statementsTable.getSelectionModel().selectedItemProperty().isNull());

        setupDropZone();
    }

    public void init(SharedState state) {
        this.state = state;
        rulesTable.setItems(state.getCategorizationRules().getRuleEntries());
        Label rulesEmpty = new Label("No rules yet. Change a transaction's category and a rule is added for you.");
        rulesEmpty.getStyleClass().add("empty-state-hint");
        rulesEmpty.setWrapText(true);
        rulesTable.setPlaceholder(rulesEmpty);

        try {
            state.getImportLogs().setAll(state.getStorage().loadImportLogs());
        } catch (IOException e) {
            state.getImportLogs().clear();
        }
        ProfileManager pm = state.getProfileManager();
        registry = new ImportRegistry(pm.getProfileDir(pm.getActiveProfile()));
        registry.load();
        state.setImportRegistry(registry);
        scannedFolderFiles.clear();
        autoScanPaused = registry.isLoadFailed() || state.getStorage().isExpenseSaveBlocked();
        if (registry.isLoadFailed()) {
            showMsg("Your import history couldn't be read (the file may be locked by OneDrive or antivirus). "
                + "Importing is paused until you restart the app, so nothing gets imported twice.", true);
        } else if (state.getStorage().isExpenseSaveBlocked()) {
            // The ledger in memory isn't your real data; leave import history alone.
            showMsg("Your transactions couldn't be loaded, so automatic importing is paused.", true);
        } else {
            reconcileHistoryWithLedger(state.getStorage().wasLastExpenseLoadLossy());
        }

        refreshRows();
        updateFolderUi();
        resultBox.getChildren().clear();
        resultBox.setVisible(false);
        resultBox.setManaged(false);

        if (!focusListenerInstalled && state.getStage() != null) {
            focusListenerInstalled = true;
            state.getStage().focusedProperty().addListener((obs, was, focused) -> {
                if (focused && System.currentTimeMillis() - lastFolderScan > FOCUS_RESCAN_MILLIS) {
                    scanFolder(false);
                }
            });
        }
        // Pick up statements saved into the folder since the app last ran.
        Platform.runLater(() -> scanFolder(false));
    }

    /**
     * Imports whose transactions are no longer in the ledger:
     *  - after a lossy load (an unreadable file was set aside), forget them without
     *    dismissing, so re-importing the statements rebuilds the data;
     *  - otherwise the user deleted those rows themselves, so treat it like "Remove import":
     *    dismiss the file (the folder scan won't bring it back) and drop its history row.
     */
    private void reconcileHistoryWithLedger(boolean ledgerWasLossy) {
        Set<String> present = new HashSet<>();
        for (Expense e : state.getManager().getExpenses()) {
            if (e.getImportId() != null) present.add(e.getImportId());
        }
        boolean changed = false;
        for (ImportRegistry.StatementRecord r : registry.getStatements()) {
            if (present.contains(r.importId)) continue;
            if (ledgerWasLossy) registry.purge(r.importId); else registry.forget(r.importId);
            changed = true;
        }
        // Either way the history rows for those imports are stale.
        changed |= state.getImportLogs().removeIf(log -> !present.contains(log.getImportId()));
        if (changed) saveRegistryQuietly();
    }

    /** Told the newest month in each successful import, so the main window can show it. */
    public void setOnImported(java.util.function.Consumer<java.time.YearMonth> r) {
        this.onImported = r != null ? r : ym -> {};
    }

    /** Called by the main window so the "Review" link can open the uncategorised transactions. */
    public void setOnReviewUncategorized(Runnable r) {
        this.onReviewUncategorized = r != null ? r : () -> {};
    }

    public void refresh() {
        rulesTable.refresh();
        rulesPane.setText("Categorisation rules (" + state.getCategorizationRules().getRules().size() + ")");
        refreshRows();
    }

    // ------------------------------------------------------------ statement import

    @FXML
    private void handleImportStatement() {
        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("Import Bank Statements");
        fileChooser.getExtensionFilters().addAll(
            new FileChooser.ExtensionFilter("Bank statements", "*.pdf", "*.csv", "*.ofx", "*.qfx", "*.qif"),
            new FileChooser.ExtensionFilter("All files", "*.*")
        );
        if (registry.getAutoImportFolder() != null) {
            File dir = new File(registry.getAutoImportFolder());
            if (dir.isDirectory()) fileChooser.setInitialDirectory(dir);
        }
        List<File> files = fileChooser.showOpenMultipleDialog(state.getStage());
        if (files != null && !files.isEmpty()) importFiles(files, true);
    }

    private void setupDropZone() {
        dropZone.setOnDragOver((DragEvent e) -> {
            if (e.getDragboard().hasFiles() && !busy) {
                e.acceptTransferModes(TransferMode.COPY);
                if (!dropZone.getStyleClass().contains("drop-zone-active")) {
                    dropZone.getStyleClass().add("drop-zone-active");
                }
            }
            e.consume();
        });
        dropZone.setOnDragExited(e -> dropZone.getStyleClass().remove("drop-zone-active"));
        dropZone.setOnDragDropped(e -> {
            boolean ok = false;
            if (e.getDragboard().hasFiles() && !busy) {
                List<File> files = new ArrayList<>();
                for (File f : e.getDragboard().getFiles()) {
                    if (f.isDirectory()) {
                        File[] inner = f.listFiles();
                        if (inner != null) files.addAll(Arrays.asList(inner));
                    } else {
                        files.add(f);
                    }
                }
                importFiles(files, true);
                ok = true;
            }
            e.setDropCompleted(ok);
            e.consume();
        });
    }

    /**
     * Runs the pipeline for {@code files} in the background and commits the result.
     * @param interactive true when the user started it (always report); false for the
     *                    silent folder scan (report only if something was imported)
     */
    private void importFiles(List<File> files, boolean interactive) {
        importFiles(files, interactive, !interactive);
    }

    /**
     * @param respectDismissed skip files whose import the user removed earlier (folder scans);
     *                         false when the user picked or dropped the files themselves
     */
    private void importFiles(List<File> files, boolean interactive, boolean respectDismissed) {
        if (busy) return;
        if (registry.isLoadFailed() || state.getStorage().isExpenseSaveBlocked()) {
            // Without the history we can't tell what's already imported; don't risk duplicates.
            if (interactive) showMsg("Importing is paused because your saved data couldn't be read. Restart the app to try again.", true);
            return;
        }
        List<File> supported = files.stream().filter(StatementImporter::isSupported)
            .sorted(Comparator.comparing(File::getName)).collect(Collectors.toList());
        if (supported.isEmpty()) {
            if (interactive) showMsg("Those files aren't bank statements (PDF, CSV, OFX or QIF).", true);
            return;
        }
        // Snapshot everything the background thread reads (rules, history, ledger) here on the FX thread.
        StatementImporter importer = new StatementImporter(state.getCategorizationRules(), registry,
            new ArrayList<>(state.getManager().getExpenses()));
        ExpenseManager managerAtStart = state.getManager();
        ImportRegistry registryAtStart = registry;

        Task<List<StatementImporter.Prepared>> task = new Task<>() {
            @Override
            protected List<StatementImporter.Prepared> call() {
                List<StatementImporter.Prepared> out = new ArrayList<>();
                for (int i = 0; i < supported.size(); i++) {
                    File f = supported.get(i);
                    updateMessage("Reading " + f.getName() + " (" + (i + 1) + " of " + supported.size() + ")…");
                    out.add(importer.prepare(f, null, respectDismissed));
                }
                return out;
            }
        };
        setBusy(true);
        busyLabel.textProperty().bind(task.messageProperty());
        task.setOnSucceeded(e -> {
            busyLabel.textProperty().unbind();
            List<StatementImporter.Prepared> prepared = new ArrayList<>(task.getValue());
            if (state.getManager() != managerAtStart || registry != registryAtStart) {
                // The profile changed while files were being read: don't import into the wrong one.
                setBusy(false);
                return;
            }
            if (!interactive) {
                for (StatementImporter.Prepared p : prepared) scannedFolderFiles.add(folderKey(p.file));
            }
            // CSVs whose columns couldn't be recognised get one chance at manual mapping.
            if (interactive) {
                for (int i = 0; i < prepared.size(); i++) {
                    StatementImporter.Prepared p = prepared.get(i);
                    if (StatementImporter.NEEDS_CSV_MAPPING.equals(p.problem)) {
                        StatementParseResult mapped = mapCsvManually(p.file);
                        if (mapped != null) prepared.set(i, importer.prepare(p.file, mapped));
                    }
                }
            }
            try {
                commit(prepared, interactive);
            } finally {
                setBusy(false);
            }
        });
        task.setOnFailed(e -> {
            busyLabel.textProperty().unbind();
            setBusy(false);
            showMsg("Import failed: " + task.getException().getMessage(), true);
        });
        Thread t = new Thread(task, "statement-import");
        t.setDaemon(true);
        t.start();
    }

    /** Adds every prepared file's transactions as one undoable step, then saves. */
    private void commit(List<StatementImporter.Prepared> prepared, boolean interactive) {
        List<StatementImporter.Prepared> work = prepared.stream()
            .filter(StatementImporter.Prepared::hasWork).collect(Collectors.toList());

        if (!work.isEmpty()) {
            List<Expense> all = new ArrayList<>();
            List<ImportLog> logs = new ArrayList<>();
            List<ImportRegistry.StatementRecord> records = new ArrayList<>();
            Map<String, List<String>> fingerprints = new HashMap<>();
            for (StatementImporter.Prepared p : work) {
                String importId = "IMP-" + UUID.randomUUID();
                List<Expense> expenses = StatementImporter.toExpenses(p, importId,
                    state.getCurrencyManager().getBaseCurrency());
                all.addAll(expenses);
                logs.add(new ImportLog(importId, LocalDateTime.now(), p.file.getName(), p.sourceType(), expenses.size()));
                records.add(StatementImporter.recordFor(p, importId));
                fingerprints.put(importId, StatementImporter.fingerprintsOf(p));
            }
            for (Expense e : all) {
                if (!state.getCategories().contains(e.getCategory())) state.getCategories().add(e.getCategory());
            }

            ExpenseManager manager = state.getManager();
            ImportRegistry reg = registry;
            boolean[] rollingBack = {false};
            Command cmd = new Command() {
                @Override public void execute() {
                    manager.addExpenses(all);
                    for (ImportRegistry.StatementRecord r : records) reg.record(r, fingerprints.get(r.importId));
                    state.getImportLogs().addAll(logs);
                    saveRegistryQuietly(); // also on redo, so a restart doesn't re-import it
                }
                @Override public void undo() {
                    for (Expense e : all) manager.removeExpense(e);
                    // Undo marks the files dismissed so the folder scan doesn't re-import them; a
                    // rollback after a failed save doesn't (the file should be retried later).
                    for (ImportRegistry.StatementRecord r : records) {
                        if (rollingBack[0]) reg.purge(r.importId); else reg.forget(r.importId);
                    }
                    state.getImportLogs().removeAll(logs);
                    saveRegistryQuietly();
                }
            };
            try {
                manager.executeCommand(cmd);
            } catch (Exception ex) {
                showMsg("Couldn't import: " + ex.getMessage(), true);
                return;
            }
            try {
                state.saveExpenses();
            } catch (Exception ex) {
                rollingBack[0] = true;
                manager.rollbackLastCommand();
                autoScanPaused = true;
                showMsg("Couldn't save the import (" + ex.getMessage() + "). Nothing was imported; "
                    + "automatic importing is paused until you restart the app.", true);
                return;
            }
            try {
                registry.save();
                state.getStorage().saveImportLogs(new ArrayList<>(state.getImportLogs()));
                state.getStorage().saveCategories(state.getCategories());
            } catch (IOException ex) {
                autoScanPaused = true;
                showMsg("Transactions saved, but import history couldn't be saved (" + ex.getMessage()
                    + "). Automatic importing is paused so nothing is imported twice.", true);
            }
            state.requestRefresh();
            all.stream().map(Expense::getDate).max(Comparator.naturalOrder())
                .ifPresent(d -> onImported.accept(java.time.YearMonth.from(d)));
        }
        refreshRows();
        if (interactive || !work.isEmpty()) showResult(prepared);
    }

    private void showResult(List<StatementImporter.Prepared> prepared) {
        resultBox.getChildren().clear();
        int imported = 0;
        long uncategorized = 0;
        double in = 0, out = 0;
        for (StatementImporter.Prepared p : prepared) {
            if (!p.hasWork()) continue;
            imported += p.newItems.size();
            uncategorized += p.uncategorizedCount();
            for (ImportItem i : p.newItems) {
                if (i.isTransfer()) continue;
                if (i.isCredit() && !i.isRefund()) in += i.getAmount();
                else if (!i.isCredit()) out += i.getAmount();
            }
        }

        Label title = new Label(imported > 0
            ? "Imported " + imported + " transaction" + (imported == 1 ? "" : "s")
            : "Nothing new to import");
        title.getStyleClass().add("card-title");
        resultBox.getChildren().add(title);
        if (imported > 0) {
            Label totals = new Label("Money in " + fmt(in) + "  ·  Money out " + fmt(out)
                + "  (transfers between your own accounts are left out)");
            totals.getStyleClass().add("muted-text");
            resultBox.getChildren().add(totals);
        }

        for (StatementImporter.Prepared p : prepared) {
            resultBox.getChildren().add(resultLine(p));
        }

        if (uncategorized > 0) {
            Button review = new Button("Review " + uncategorized + " uncategorised");
            review.getStyleClass().add("accent-button");
            review.setOnAction(e -> onReviewUncategorized.run());
            Label hint = new Label("Pick a category once and similar transactions are sorted automatically from then on.");
            hint.getStyleClass().add("muted-text");
            hint.setWrapText(true);
            HBox row = new HBox(12, review, hint);
            row.setAlignment(Pos.CENTER_LEFT);
            row.setPadding(new Insets(6, 0, 0, 0));
            resultBox.getChildren().add(row);
        }
        resultBox.setVisible(true);
        resultBox.setManaged(true);
        if (imported > 0) Toast.show("Imported " + imported + " transactions");
    }

    private HBox resultLine(StatementImporter.Prepared p) {
        Label icon = new Label();
        Label text = new Label();
        text.setWrapText(true);
        icon.getStyleClass().add("result-icon");
        if (p.problem != null) {
            icon.setText("✕");
            icon.getStyleClass().add("result-bad");
            text.setText(p.file.getName() + " — " + p.problem);
        } else if (p.skippedWholeFile) {
            icon.setText("–");
            icon.getStyleClass().add("result-neutral");
            text.setText(p.file.getName() + (p.dismissed
                ? " — you removed this import earlier (drop the file here to bring it back)"
                : " — already imported"));
        } else {
            StatementParseResult s = p.statement;
            StringBuilder sb = new StringBuilder(p.file.getName()).append(" — ")
                .append(p.newItems.size()).append(" transactions");
            if (s.getPeriodStart() != null && s.getPeriodEnd() != null) {
                sb.append(", ").append(s.getPeriodStart().format(PERIOD_FMT)).append(" to ")
                  .append(s.getPeriodEnd().format(PERIOD_FMT));
            }
            if (p.alreadyImported > 0) sb.append(" (").append(p.alreadyImported).append(" already imported)");
            if (p.duplicatesOfManualEntries > 0) {
                sb.append(" (").append(p.duplicatesOfManualEntries).append(" matched entries you'd added by hand)");
            }
            if (s.canReconcile()) {
                if (s.isReconciled()) {
                    sb.append(" · balances match");
                    icon.setText("✓");
                    icon.getStyleClass().add("result-good");
                } else {
                    sb.append(" · balances are off by ").append(fmt(Math.abs(s.reconciliationDifference())));
                    icon.setText("!");
                    icon.getStyleClass().add("result-warn");
                }
            } else {
                icon.setText("✓");
                icon.getStyleClass().add("result-good");
            }
            text.setText(sb.toString());
        }
        HBox row = new HBox(10, icon, text);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    private void setBusy(boolean b) {
        busy = b;
        dropIdle.setVisible(!b);
        dropIdle.setManaged(!b);
        dropBusy.setVisible(b);
        dropBusy.setManaged(b);
        scanNowButton.setDisable(b);
    }

    // ------------------------------------------------------------ watched folder

    @FXML
    private void handleChooseFolder() {
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle("Folder where you save bank statements");
        if (registry.getAutoImportFolder() != null) {
            File current = new File(registry.getAutoImportFolder());
            if (current.isDirectory()) chooser.setInitialDirectory(current);
        }
        File dir = chooser.showDialog(state.getStage());
        if (dir == null) return;
        registry.setAutoImportFolder(dir.getAbsolutePath());
        saveRegistryQuietly();
        updateFolderUi();
        scanFolder(true);
    }

    @FXML
    private void handleClearFolder() {
        registry.setAutoImportFolder(null);
        saveRegistryQuietly();
        updateFolderUi();
    }

    @FXML
    private void handleScanNow() {
        scanFolder(true);
    }

    private void scanFolder(boolean interactive) {
        if (registry == null || busy) return;
        if (autoScanPaused && !interactive) return;
        String folder = registry.getAutoImportFolder();
        if (folder == null) return;
        lastFolderScan = System.currentTimeMillis();
        File dir = new File(folder);
        File[] files = dir.listFiles();
        if (files == null) {
            if (interactive) showMsg("Can't open the statements folder: " + folder, true);
            return;
        }
        List<File> candidates = new ArrayList<>();
        for (File f : files) {
            // Plain .txt files in a documents folder are usually notes, not statements.
            if (f.getName().toLowerCase().endsWith(".txt")) continue;
            // The silent scan skips files it has already looked at (unchanged since).
            if (!interactive && scannedFolderFiles.contains(folderKey(f))) continue;
            candidates.add(f);
        }
        if (candidates.isEmpty()) {
            if (interactive) showResult(List.of());
            return;
        }
        // "Check now" is explicit, but it still shouldn't resurrect imports the user removed.
        importFiles(candidates, interactive, true);
    }

    private static String folderKey(File f) {
        return f.getAbsolutePath() + "|" + f.length() + "|" + f.lastModified();
    }

    private void updateFolderUi() {
        String folder = registry.getAutoImportFolder();
        boolean set = folder != null;
        folderLabel.setText(set
            ? folder + "\nNew statements saved here are imported automatically when the app opens."
            : "Not set — pick the folder where you save your statements and new ones will be imported every time the app opens.");
        scanNowButton.setVisible(set);
        scanNowButton.setManaged(set);
        clearFolderButton.setVisible(set);
        clearFolderButton.setManaged(set);
    }

    // ------------------------------------------------------------ imported statements

    private void refreshRows() {
        if (registry == null) return;
        Map<String, ImportRegistry.StatementRecord> byId = new HashMap<>();
        for (ImportRegistry.StatementRecord r : registry.getStatements()) byId.put(r.importId, r);
        List<ImportRow> list = new ArrayList<>();
        for (ImportLog log : state.getImportLogs()) list.add(new ImportRow(log, byId.get(log.getImportId())));
        list.sort(Comparator.comparing((ImportRow r) -> r.record != null && r.record.periodEnd != null
            ? r.record.periodEnd.atStartOfDay() : r.log.getTimestamp()).reversed());
        rows.setAll(list);
    }

    private String periodText(ImportRow r) {
        if (r.record != null && r.record.periodStart != null && r.record.periodEnd != null) {
            return r.record.periodStart.format(PERIOD_FMT) + " – " + r.record.periodEnd.format(PERIOD_FMT);
        }
        List<LocalDate> dates = state.getManager().getExpenses().stream()
            .filter(e -> r.log.getImportId().equals(e.getImportId()))
            .map(Expense::getDate).sorted().collect(Collectors.toList());
        if (dates.isEmpty()) return "Imported " + r.log.getTimestampDisplay();
        return dates.get(0).format(PERIOD_FMT) + " – " + dates.get(dates.size() - 1).format(PERIOD_FMT);
    }

    @FXML
    private void handleDeleteImport() {
        ImportRow selected = statementsTable.getSelectionModel().getSelectedItem();
        if (selected == null) return;
        ImportLog log = selected.log;
        ExpenseManager manager = state.getManager();
        List<Expense> toRemove = manager.getExpenses().stream()
            .filter(exp -> log.getImportId().equals(exp.getImportId()))
            .collect(Collectors.toList());

        Alert confirmation = new Alert(Alert.AlertType.CONFIRMATION);
        confirmation.initOwner(state.getStage());
        UIUtils.applyStylesheet(confirmation.getDialogPane());
        confirmation.setTitle("Remove import");
        confirmation.setHeaderText("Remove " + log.getSourceFile() + "?");
        confirmation.setContentText("Its " + toRemove.size() + " transaction(s) will be removed and the file "
            + "won't be re-imported from your statements folder. You can still import it by hand, or press Ctrl+Z to undo.");
        if (confirmation.showAndWait().orElse(null) != ButtonType.OK) return;

        ImportRegistry.StatementRecord record = selected.record;
        List<String> fps = new ArrayList<>();
        // Remember which fingerprints belonged to this import so undo can restore them.
        if (record != null) fps.addAll(registry.fingerprintsFor(record.importId));

        manager.executeCommand(new Command() {
            @Override public void execute() {
                for (Expense exp : toRemove) manager.removeExpense(exp);
                state.getImportLogs().remove(log);
                registry.forget(log.getImportId()); // also keeps the folder scan from re-importing it
                saveRegistryQuietly();
            }
            @Override public void undo() {
                // Restoring rows that were already in the ledger: no re-validation.
                for (Expense exp : toRemove) manager.addExpenseUnchecked(exp);
                state.getImportLogs().add(log);
                if (record != null) registry.record(record, fps);
                saveRegistryQuietly();
            }
        });
        try {
            state.saveExpenses();
        } catch (Exception ex) {
            // The registry and history were already updated: put the rows, the history row and
            // the registry entry back (undo re-saves the registry) so memory matches the disk.
            manager.rollbackLastCommand();
            refreshRows();
            showMsg("Couldn't save after removing the import (" + ex.getMessage() + "). Nothing was changed.", true);
            return;
        }
        try {
            state.getStorage().saveImportLogs(new ArrayList<>(state.getImportLogs()));
            registry.save();
        } catch (IOException ex) {
            showMsg("Failed to save after removing the import: " + ex.getMessage(), true);
            return;
        }
        state.requestRefresh();
        refreshRows();
        Toast.show("Removed " + toRemove.size() + " transactions");
    }

    private void saveRegistryQuietly() {
        try {
            registry.save();
            state.getStorage().saveImportLogs(new ArrayList<>(state.getImportLogs()));
        } catch (IOException e) {
            System.err.println("Failed to save import registry: " + e.getMessage());
        }
    }

    private StatementParseResult mapCsvManually(File file) {
        try {
            // Same decoding as automatic detection (BOM, Windows-1252), and skip any preamble above the table.
            String text = StatementImporter.readText(file);
            String[] allLines = text.split("\\r?\\n");
            int headerRow = CsvStatementParser.findHeaderRow(allLines);
            String[] lines = Arrays.copyOfRange(allLines, headerRow, allLines.length);
            if (lines.length < 2) return null;
            char delimiter = CsvStatementParser.detectDelimiter(String.join("\n", lines));
            String[] headers = CsvStatementParser.parseHeaders(lines[0], delimiter);
            List<ImportItem> items = showCsvMappingDialog(text, headers, delimiter, lines, headerRow + 1);
            return items == null || items.isEmpty() ? null : new StatementParseResult("CSV", items);
        } catch (IOException e) {
            return null;
        }
    }

    // ------------------------------------------------------------ receipts

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
                Double receiptTotal = receiptScanner.extractTotal(ocrText);
                String merchant = receiptScanner.extractMerchant(ocrText);
                if (items.isEmpty() && receiptTotal != null && receiptTotal > 0) {
                    // Couldn't read the lines, but the total is there: offer the receipt as one expense.
                    LocalDate onSlip = ReceiptScanner.extractDate(ocrText);
                    ImportItem whole = new ImportItem(receiptTotal, merchant != null ? merchant : "Receipt",
                        onSlip != null ? onSlip : fallbackDate != null ? fallbackDate : LocalDate.now());
                    whole.setStatus("Uncategorized");
                    items.add(whole);
                }

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
                    double itemSum = items.stream().mapToDouble(ImportItem::getAmount).sum();
                    if (receiptTotal != null && items.size() > 1 && Math.abs(itemSum - receiptTotal) > 0.01) {
                        showMsg(String.format("Found %d items adding up to %s, but the receipt total is %s. "
                                + "Check the amounts before importing.", items.size(),
                            UIUtils.fmt(itemSum, state.getCurrencySymbol()),
                            UIUtils.fmt(receiptTotal, state.getCurrencySymbol())), true);
                    } else {
                        showMsg("Found " + items.size() + (items.size() == 1 ? " item." : " items."), false);
                    }
                    ImportReviewDialog dialog = new ImportReviewDialog(
                        state.getStage(), items, state.getCategories(), state.getCurrencySymbol(),
                        ocrText, categorizationRules, state.getManager().getExpenses());
                    List<Expense> expenses = dialog.showAndWait();
                    if (expenses != null && !expenses.isEmpty()) {
                        saveLearnedRules(dialog);
                        importExpenses(expenses, file.getName(), "Receipt");
                    }
                });
            } catch (Throwable e) {
                if (Thread.currentThread().isInterrupted()) return;
                String why = e.getMessage() != null ? e.getMessage() : e.toString();
                Platform.runLater(() -> {
                    state.getStage().setOnCloseRequest(originalCloseHandler);
                    wrapper.getChildren().clear();
                    scene.setRoot(originalRoot);
                    showMsg("OCR failed: " + why, true);
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
        ComboBox<String> catCombo = new ComboBox<>(state.getSortedCategories());
        catCombo.setEditable(true);
        catCombo.getStyleClass().add("combo-box");
        catCombo.setMaxWidth(Double.MAX_VALUE);

        Button addBtn = new Button("Add Rule");
        addBtn.getStyleClass().add("accent-button");
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
            ruleStage.close();
            // Apply the new rule to anything still uncategorised right away.
            handleRecategorize();
            refresh();
        });

        Button cancelBtn = new Button("Cancel");
        cancelBtn.getStyleClass().add("ghost-button");
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

    /** {@code lines} start at the header row; {@code firstDataLine} indexes the rows of {@code text}. */
    private List<ImportItem> showCsvMappingDialog(String text, String[] headers, char delimiter, String[] lines,
                                                  int firstDataLine) {
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
            FXCollections.observableArrayList(CsvStatementParser.DETECTABLE_DATE_FORMATS));
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
        // Suggest the date format the rows actually use, as automatic detection would.
        java.util.function.Consumer<String> suggestFormat = col -> {
            int idx = Arrays.asList(headers).indexOf(col);
            String detected = idx < 0 ? null : CsvStatementParser.detectDateFormat(lines, 1, idx, delimiter);
            if (detected != null) dateFormatCombo.setValue(detected);
        };
        if (dateColCombo.getValue() != null) suggestFormat.accept(dateColCombo.getValue());
        dateColCombo.valueProperty().addListener((obs, old, col) -> suggestFormat.accept(col));

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
        okBtn.getStyleClass().add("accent-button");
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

            resultHolder[0] = CsvStatementParser.parse(text, delimiter, dateIdx, amountIdx, -1,
                descIdx, -1, dateFormatCombo.getValue(), negativeIsExpense.isSelected(), firstDataLine);
            mappingStage.close();
        });

        Button cancelBtn = new Button("Cancel");
        cancelBtn.getStyleClass().add("ghost-button");
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

    // ------------------------------------------------------------ rules

    @FXML
    private void handleRemoveRule() {
        CategorizationRules.RuleEntry selected = rulesTable.getSelectionModel().getSelectedItem();
        if (selected == null) {
            showMsg("Select a rule to remove.", true);
            return;
        }
        state.getCategorizationRules().removeRule(selected.getKeyword());
        try {
            state.getStorage().saveCategorizationRules(state.getCategorizationRules().getRules());
        } catch (IOException ex) {
            showMsg("Failed to save rules: " + ex.getMessage(), true);
        }
        refresh();
    }

    /** Runs the user's rules, then the built-in merchant list, over every uncategorised transaction. */
    @FXML
    private void handleRecategorize() {
        int changed = recategorizeUncategorized(state);
        if (changed > 0) {
            try {
                state.saveExpenses();
                state.getStorage().saveCategories(state.getCategories());
            } catch (IOException e) {
                showMsg("Failed to save: " + e.getMessage(), true);
                return;
            }
            state.requestRefresh();
            Toast.show("Categorised " + changed + " transaction" + (changed == 1 ? "" : "s"));
        } else {
            showMsg("No uncategorised transactions matched a rule.", false);
        }
    }

    /** Shared with the Transactions screen, which calls it after the user teaches a new rule. */
    static int recategorizeUncategorized(SharedState state) {
        CategorizationRules rules = state.getCategorizationRules();
        int changed = 0;
        for (Expense expense : state.getManager().getExpenses()) {
            if (expense.getRecurringId() != null || expense.isExcluded()) continue;
            if (!TransactionClassifier.UNCATEGORIZED.equals(expense.getCategory())) continue;
            String cat = rules.categorize(expense.getDescription());
            if (cat == null && !expense.isIncome()) cat = TransactionClassifier.builtInCategory(expense.getDescription());
            if (cat != null && !cat.equals(expense.getCategory())) {
                expense.setCategory(cat);
                if (!state.getCategories().contains(cat)) state.getCategories().add(cat);
                changed++;
            }
        }
        return changed;
    }

    private void saveLearnedRules(ImportReviewDialog dialog) {
        Map<String, String> learned = dialog.getLearnedRules();
        if (learned.isEmpty()) return;
        CategorizationRules categorizationRules = state.getCategorizationRules();
        // A correction always wins over an older rule for the same keyword.
        learned.forEach(categorizationRules::addRule);
        try {
            state.getStorage().saveCategorizationRules(categorizationRules.getRules());
        } catch (IOException ex) {
            System.err.println("Failed to save learned rules: " + ex.getMessage());
        }
        refresh();
    }

    /** Commits receipt line items reviewed in {@link ImportReviewDialog}. */
    private void importExpenses(List<Expense> expenses, String sourceFile, String sourceType) {
        String importId = "IMP-" + UUID.randomUUID();
        for (Expense exp : expenses) exp.setImportId(importId);

        ExpenseManager manager = state.getManager();
        ImportLog log = new ImportLog(importId, LocalDateTime.now(), sourceFile, sourceType, expenses.size());
        manager.executeCommand(new Command() {
            @Override public void execute() {
                manager.addExpenses(expenses);
                state.getImportLogs().add(log);
            }
            @Override public void undo() {
                for (Expense e : expenses) manager.removeExpense(e);
                state.getImportLogs().remove(log);
                saveRegistryQuietly();
            }
        });
        try {
            state.saveExpenses();
        } catch (IOException ex) {
            manager.rollbackLastCommand();
            showMsg("Failed to save imported items: " + ex.getMessage(), true);
            return;
        }
        try {
            state.getStorage().saveCategories(state.getCategories());
            state.getStorage().saveImportLogs(new ArrayList<>(state.getImportLogs()));
        } catch (IOException ex) {
            showMsg("Items saved, but categories or history couldn't be saved: " + ex.getMessage(), true);
        }
        state.requestRefresh();
        refreshRows();
        Toast.show("Added " + expenses.size() + " item" + (expenses.size() == 1 ? "" : "s") + " from " + sourceFile);
    }

    private String fmt(double amount) {
        return UIUtils.fmt(amount, state.getCurrencySymbol());
    }

    private void showMsg(String message, boolean isError) {
        UIUtils.showMessage(message, isError, importErrorLabel);
    }
}
