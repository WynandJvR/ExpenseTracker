package com.wyn.expensetracker;

import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.chart.BarChart;
import javafx.scene.chart.NumberAxis;
import javafx.scene.chart.XYChart;
import javafx.scene.control.*;
import javafx.util.StringConverter;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.*;

import java.io.IOException;
import java.time.Month;
import java.time.YearMonth;
import java.time.format.TextStyle;
import java.util.*;
import java.util.stream.Collectors;

public class DashboardController {

    // --- Header and headline numbers ---
    @FXML private Label overviewTitle;
    @FXML private Label overviewSubtitle;
    @FXML private VBox attentionBox;
    @FXML private Label kpiInValue;
    @FXML private Label kpiInSub;
    @FXML private Label kpiOutValue;
    @FXML private Label kpiOutSub;
    @FXML private Label kpiNetValue;
    @FXML private Label kpiNetSub;
    @FXML private Label kpiBalanceValue;
    @FXML private Label kpiBalanceSub;

    // --- Spending by category and trend ---
    @FXML private VBox categoryBars;
    @FXML private Label categorySummaryLabel;
    @FXML private BarChart<String, Number> trendChart;
    @FXML private TitledPane incomePane;

    // --- Income fields ---
    @FXML private VBox incomeFieldsBox;
    @FXML private TextField recurringIncomeField;
    @FXML private TextField incomeField;

    // --- Income table ---
    @FXML private TableView<Expense> incomeTable;
    @FXML private Label incomeTabSummary;
    @FXML private Label incomeErrorLabel;

    // --- Budget alerts ---
    @FXML private VBox budgetAlertBox;

    // --- Anomaly detection ---
    @FXML private VBox remindersBox;

    @FXML private VBox anomalyBox;

    // --- Savings goals ---
    @FXML private VBox goalsBox;
    @FXML private VBox goalsProgressBox;

    // --- Debt summary ---
    @FXML private VBox debtSummaryBox;
    @FXML private VBox debtSummaryContent;

    // --- Exchange rates ---
    @FXML private VBox exchangeRatesBox;
    @FXML private VBox exchangeRatesContent;

    // --- Error label ---
    @FXML private Label errorLabel;

    private SharedState state;
    private Runnable onReviewUncategorized = () -> {};
    private Runnable onGoToImport = () -> {};
    private java.util.function.Consumer<String> onShowCategory = c -> {};
    private String summaryText = "";
    private boolean suppressIncomeListener = false;
    private boolean suppressRecurringIncomeListener = false;
    private boolean initialized = false;

    @FXML
    public void initialize() {
        // Minimal — real setup happens in init(SharedState) after data is loaded
    }

    public void init(SharedState state) {
        this.state = state;
        if (initialized) return;
        initialized = true;

        setupIncomeTable();
        setupIncomeFieldListeners();
        ((NumberAxis) trendChart.getYAxis()).setTickLabelFormatter(new StringConverter<Number>() {
            @Override public String toString(Number n) { return compact(n.doubleValue()); }
            @Override public Number fromString(String s) { return 0; }
        });
    }

    /** Lets the Overview send the user to the right place from its "needs attention" notes. */
    public void setNavigation(Runnable reviewUncategorized, Runnable goToImport) {
        if (reviewUncategorized != null) this.onReviewUncategorized = reviewUncategorized;
        if (goToImport != null) this.onGoToImport = goToImport;
    }

    /** Opens the Transactions screen filtered to one category, across all months. */
    public void setOnShowCategory(java.util.function.Consumer<String> showCategory) {
        if (showCategory != null) this.onShowCategory = showCategory;
    }

    /** Plain-text summary of the headline numbers, for "copy summary". */
    public String getSummaryText() { return summaryText; }

    // ======================== REFRESH ========================

    public void refresh() {
        updateTotalExpenses();
        refreshIncomeTable();
        updateIncomeField();
        updateGoalsPanel();
        updateRemindersPanel();
        updateAnomalyAlerts();
        updateDebtSummary();
        updateExchangeRatesPanel();
    }

    // ======================== INCOME TABLE SETUP ========================

    private void setupIncomeTable() {
        incomeTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        incomeTable.setItems(state.getIncomeList());

        // Empty state
        VBox incomeEmptyState = new VBox(6);
        incomeEmptyState.setAlignment(Pos.CENTER);
        Label incomeMsg = new Label("No income transactions for this period.");
        incomeMsg.getStyleClass().add("empty-state-label");
        Label incomeHint = new Label("Import a bank statement or mark expenses as income via right-click.");
        incomeHint.getStyleClass().add("empty-state-hint");
        incomeEmptyState.getChildren().addAll(incomeMsg, incomeHint);
        incomeTable.setPlaceholder(incomeEmptyState);

        // Row factory with excluded styling and context menu
        incomeTable.setRowFactory(tv -> {
            TableRow<Expense> row = new TableRow<>() {
                @Override
                protected void updateItem(Expense item, boolean empty) {
                    super.updateItem(item, empty);
                    getStyleClass().removeAll("excluded-row");
                    if (empty || item == null) {
                        setStyle("");
                        setOpacity(1.0);
                    } else if (item.isExcluded()) {
                        getStyleClass().add("excluded-row");
                        setOpacity(0.45);
                        setStyle("");
                    } else {
                        setStyle("-fx-background-color: rgba(76, 175, 80, 0.12);");
                        setOpacity(1.0);
                    }
                }
            };
            ContextMenu menu = new ContextMenu();
            MenuItem toggleExclude = new MenuItem("Exclude from Analytics");
            toggleExclude.setOnAction(e -> {
                Expense item = row.getItem();
                if (item != null) {
                    boolean prev = item.isExcluded();
                    item.setExcluded(!prev);
                    try {
                        state.saveExpenses();
                    } catch (IOException ex) {
                        item.setExcluded(prev);
                        UIUtils.showMessage("Failed to save: " + ex.getMessage(), true, incomeErrorLabel);
                    }
                    state.requestRefresh();
                }
            });
            menu.setOnShowing(e -> {
                Expense item = row.getItem();
                toggleExclude.setText(item != null && item.isExcluded()
                        ? "Include in Analytics" : "Exclude from Analytics");
            });
            menu.getItems().add(toggleExclude);
            row.setContextMenu(menu);
            return row;
        });

        // Delete key handler
        incomeTable.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.DELETE) handleDeleteIncome();
        });
    }

    // ======================== INCOME FIELD LISTENERS ========================

    private void setupIncomeFieldListeners() {
        // Recurring income field — saves to storage
        recurringIncomeField.textProperty().addListener((obs, oldVal, newVal) -> {
            if (suppressRecurringIncomeListener) return;
            try {
                double value = (newVal == null || newVal.isEmpty()) ? 0.0 : Double.parseDouble(newVal);
                if (value < 0) { return; }
                state.setRecurringIncome(value);
                state.getStorage().saveRecurringIncome(value);
                updateIncomeField();
            } catch (NumberFormatException e) {
                // ignore while typing
            } catch (IOException e) {
                UIUtils.showMessage("Error saving recurring income: " + e.getMessage(), true, errorLabel);
            }
        });

        // Income field — per-month override
        incomeField.textProperty().addListener((obs, oldVal, newVal) -> {
            if (suppressIncomeListener) return;
            Integer selectedYear = state.getSelectedYear();
            Month selectedMonth = state.getSelectedMonth();
            if (selectedYear == null || selectedMonth == null) return;
            YearMonth selectedYearMonth = YearMonth.of(selectedYear, selectedMonth);
            try {
                if (newVal == null || newVal.isEmpty()) {
                    // Clear manual override — fall back to recurring
                    state.getIncomes().remove(selectedYearMonth);
                    try {
                        state.getStorage().saveIncomes(state.getIncomes());
                    } catch (IOException ex) {
                        UIUtils.showMessage("Error saving incomes: " + ex.getMessage(), true, errorLabel);
                    }
                    updateTotalExpenses();
                    return;
                }
                double incomeValue = Double.parseDouble(newVal);
                if (incomeValue < 0) {
                    UIUtils.showMessage("Income cannot be negative", true, errorLabel);
                    return;
                }
                state.getIncomes().put(selectedYearMonth, incomeValue);
                try {
                    state.getStorage().saveIncomes(state.getIncomes());
                    updateTotalExpenses();
                } catch (IOException ex) {
                    state.getIncomes().remove(selectedYearMonth);
                    UIUtils.showMessage("Error saving incomes: " + ex.getMessage(), true, errorLabel);
                }
            } catch (NumberFormatException ex) {
                UIUtils.showMessage("Invalid income: Please enter a valid number (e.g., 5000.00)", true, errorLabel);
            }
        });
    }

    // ======================== CORE COMPUTATION ========================

    private void updateTotalExpenses() {
        Integer selectedYear = state.getSelectedYear();
        Month selectedMonth = state.getSelectedMonth();

        if (selectedYear == null || selectedMonth == null) {
            categoryBars.getChildren().clear();
            state.getCategoryTotals().clear();
            return;
        }

        YearMonth selectedYearMonth = YearMonth.of(selectedYear, selectedMonth);
        YearMonth nowYm = YearMonth.now();
        Map<String, Double> budgets = state.getBudgets();

        // Check if this month has real imported data (drives the "Projected" labeling below)
        boolean hasImportedData = state.monthHasImportedData(selectedYearMonth);

        // Ledger items for the month; future months also include scheduled recurring
        // bills (the ledger only generates occurrences up to today).
        List<Expense> monthExpenses = state.monthItemsWithProjection(selectedYearMonth);

        // Shared net-spend helpers — same source of truth as analytics and the status bar.
        Map<String, Double> categoryMap = state.spendByCategory(monthExpenses);
        double total = categoryMap.values().stream().mapToDouble(Double::doubleValue).sum();

        double income = state.incomeForMonth(selectedYearMonth);
        boolean hasActualIncome = state.actualIncome(selectedYearMonth) > 0;

        boolean isProjected = selectedYearMonth.isAfter(nowYm) || (!hasImportedData && !hasActualIncome);
        String prefix = isProjected ? "Projected " : "";

        double moneySaved = income - total;

        state.getCategoryTotals().setAll(categoryMap.entrySet().stream()
                .map(entry -> new CategoryTotal(entry.getKey(), entry.getValue(),
                        budgets.getOrDefault(entry.getKey(), 0.0)))
                .sorted(Comparator.comparing(CategoryTotal::getCategory))
                .collect(Collectors.toList()));

        // Month-over-month comparison. For the current (partial) month compare
        // month-to-date against the same day range of last month.
        YearMonth prevYearMonth = selectedYearMonth.minusMonths(1);
        double compareTotal = total;
        double prevTotal;
        if (selectedYearMonth.equals(nowYm)) {
            int today = java.time.LocalDate.now().getDayOfMonth();
            compareTotal = state.netSpend(monthExpenses.stream()
                    .filter(e -> e.getDate().getDayOfMonth() <= today)
                    .collect(Collectors.toList()));
            int prevCutoff = Math.min(today, prevYearMonth.lengthOfMonth());
            prevTotal = state.netSpend(state.expensesInMonth(prevYearMonth).stream()
                    .filter(e -> e.getDate().getDayOfMonth() <= prevCutoff)
                    .collect(Collectors.toList()));
        } else {
            prevTotal = state.netSpendForMonth(prevYearMonth);
        }

        updateHeadline(selectedYearMonth, isProjected, income, total, moneySaved, compareTotal, prevTotal);
        renderCategoryBars(categoryMap, total);
        updateTrendChart(selectedYearMonth);
        updateAttention();
        double payments = categoryMap.getOrDefault(TransactionClassifier.PAYMENTS, 0.0);
        if (total > 0 && payments / total > 0.2) {
            attentionBox.getChildren().add(notice("info", String.format("%.0f%% of this month's spending is \"Payments\"", payments / total * 100),
                "These are transfers to other people or accounts. Give the regular ones a category (rent, internet…) "
                    + "and the rest follow automatically.",
                "Review payments", () -> onShowCategory.accept(TransactionClassifier.PAYMENTS)));
        }
        updateBudgetAlerts(categoryMap);
    }

    private void updateHeadline(YearMonth ym, boolean isProjected, double income, double spend,
                                double leftOver, double compareSpend, double prevSpend) {
        String monthName = ym.getMonth().getDisplayName(TextStyle.FULL, Locale.ENGLISH) + " " + ym.getYear();
        overviewTitle.setText(monthName);

        double actualIn = state.actualIncome(ym);
        kpiInValue.setText(fmt(income));
        if (actualIn > 0) {
            long count = state.expensesInMonth(ym).stream().filter(SharedState::isIncomeItem).count();
            kpiInSub.setText(count + " payment" + (count == 1 ? "" : "s") + " received");
        } else if (income > 0) {
            kpiInSub.setText("Planned — no income in your statements yet");
        } else {
            kpiInSub.setText("Nothing received");
        }

        kpiOutValue.setText(fmt(spend));
        if (prevSpend > 0) {
            double pct = (compareSpend - prevSpend) / prevSpend * 100;
            boolean partial = ym.equals(YearMonth.now());
            kpiOutSub.setText(String.format("%s %.0f%% vs %s%s", pct >= 0 ? "▲" : "▼", Math.abs(pct),
                ym.minusMonths(1).getMonth().getDisplayName(TextStyle.SHORT, Locale.ENGLISH),
                partial ? " (same days)" : ""));
        } else {
            kpiOutSub.setText(isProjected ? "Scheduled bills plus your typical spending" : "Spending after refunds");
        }

        kpiNetValue.setText((leftOver < 0 ? "−" : "") + fmt(Math.abs(leftOver)));
        kpiNetValue.getStyleClass().removeAll("kpi-good", "kpi-bad");
        kpiNetValue.getStyleClass().add(leftOver >= 0 ? "kpi-good" : "kpi-bad");
        if (income > 0) {
            kpiNetSub.setText(leftOver >= 0
                ? String.format("You kept %.0f%% of what came in", leftOver / income * 100)
                : "You spent more than came in");
        } else {
            kpiNetSub.setText(spend > 0 ? "No income this month" : "");
        }

        ImportRegistry registry = state.getImportRegistry();
        ImportRegistry.StatementRecord latest = registry != null ? registry.latestWithBalance() : null;
        if (latest != null) {
            kpiBalanceValue.setText((latest.closingBalance < 0 ? "−" : "") + fmt(Math.abs(latest.closingBalance)));
            kpiBalanceValue.getStyleClass().removeAll("kpi-bad");
            if (latest.closingBalance < 0) kpiBalanceValue.getStyleClass().add("kpi-bad");
            kpiBalanceSub.setText("On " + latest.coverageEnd().format(DAY_FMT)
                + (latest.account != null ? " · " + latest.account : ""));
        } else {
            kpiBalanceValue.setText("—");
            kpiBalanceSub.setText("Shown once you import a statement");
        }

        int statements = registry != null ? registry.getStatements().size() : 0;
        String basis = statements == 0 ? "Import a bank statement to see where your money goes."
            : "Based on " + statements + " imported statement" + (statements == 1 ? "" : "s")
              + ". Transfers between your own accounts are left out.";
        if (isProjected) basis = "Projected from planned income and scheduled bills. " + basis;
        overviewSubtitle.setText(basis);

        summaryText = monthName + "\nMoney in: " + fmt(income) + "\nMoney out: " + fmt(spend)
            + "\nLeft over: " + (leftOver < 0 ? "-" : "") + fmt(Math.abs(leftOver))
            + (latest != null ? "\nBalance: " + fmt(latest.closingBalance) : "") + "\n";
    }

    private static final java.time.format.DateTimeFormatter DAY_FMT =
        java.time.format.DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH);

    /** Horizontal bars, largest first, each with its share and (when set) its budget. */
    private void renderCategoryBars(Map<String, Double> categoryMap, double total) {
        categoryBars.getChildren().clear();
        Map<String, Double> budgets = state.getBudgets();
        double totalBudget = budgets.values().stream().filter(Objects::nonNull).mapToDouble(Double::doubleValue).sum();
        if (totalBudget > 0) {
            double budgeted = budgets.keySet().stream().mapToDouble(c -> categoryMap.getOrDefault(c, 0.0)).sum();
            double left = totalBudget - budgeted;
            categorySummaryLabel.setText(left >= 0 ? fmt(left) + " of " + fmt(totalBudget) + " budget left"
                : fmt(-left) + " over your " + fmt(totalBudget) + " budget");
        } else {
            categorySummaryLabel.setText(total > 0 ? fmt(total) + " total" : "");
        }

        List<Map.Entry<String, Double>> entries = categoryMap.entrySet().stream()
            .filter(e -> e.getValue() > 0.005)
            .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
            .collect(Collectors.toList());
        if (entries.isEmpty()) {
            Label empty = new Label("No spending recorded for this month.");
            empty.getStyleClass().add("empty-state-hint");
            categoryBars.getChildren().add(empty);
            return;
        }
        double max = entries.get(0).getValue();
        for (Map.Entry<String, Double> e : entries) {
            String category = e.getKey();
            double spent = e.getValue();
            double budget = budgets.getOrDefault(category, 0.0);

            Label name = new Label(category);
            name.getStyleClass().add("bar-name");
            Label amount = new Label(fmt(spent));
            amount.getStyleClass().add("bar-amount");
            Label share = new Label(total > 0 ? String.format("%.0f%%", spent / total * 100) : "");
            share.getStyleClass().add("bar-share");
            Region spacer = new Region();
            HBox.setHgrow(spacer, Priority.ALWAYS);
            HBox top = new HBox(8, name, spacer, amount, share);
            top.setAlignment(Pos.BASELINE_LEFT);

            // Track + fill. Width is relative to the largest category, or to the budget when one is set.
            double ratio = budget > 0 ? Math.min(spent / budget, 1.0) : spent / max;
            Region fill = new Region();
            fill.getStyleClass().add("bar-fill");
            if (budget > 0) {
                double used = spent / budget;
                fill.getStyleClass().add(used > 1 ? "bar-fill-over" : used >= 0.8 ? "bar-fill-near" : "bar-fill-ok");
            }
            StackPane track = new StackPane(fill);
            track.getStyleClass().add("bar-track");
            StackPane.setAlignment(fill, Pos.CENTER_LEFT);
            fill.maxWidthProperty().bind(track.widthProperty().multiply(Math.max(ratio, 0.01)));
            fill.prefWidthProperty().bind(fill.maxWidthProperty());

            VBox row = new VBox(5, top, track);
            row.getStyleClass().add("bar-row");
            if (budget > 0) {
                double left = budget - spent;
                Label note = new Label(left >= 0 ? fmt(left) + " left of " + fmt(budget)
                    : "⚠ " + fmt(-left) + " over the " + fmt(budget) + " budget");
                note.getStyleClass().addAll("bar-note", left >= 0 ? "bar-note-ok" : "bar-note-over");
                row.getChildren().add(note);
            }

            ContextMenu menu = new ContextMenu();
            MenuItem setBudget = new MenuItem(budget > 0 ? "Change budget…" : "Set a monthly budget…");
            setBudget.setOnAction(ev -> handleSetBudget(category));
            menu.getItems().add(setBudget);
            if (budget > 0) {
                MenuItem clear = new MenuItem("Remove budget");
                clear.setOnAction(ev -> handleClearBudget(category));
                menu.getItems().add(clear);
            }
            row.setOnContextMenuRequested(ev -> menu.show(row, ev.getScreenX(), ev.getScreenY()));
            Tooltip.install(row, new Tooltip(category + ": " + fmt(spent)
                + (budget > 0 ? " of " + fmt(budget) + " budget" : "") + "\nRight-click to set a budget"));
            categoryBars.getChildren().add(row);
        }
    }

    /** Six months of money in vs money out, ending at the selected month. */
    private void updateTrendChart(YearMonth end) {
        XYChart.Series<String, Number> in = new XYChart.Series<>();
        in.setName("Money in");
        XYChart.Series<String, Number> out = new XYChart.Series<>();
        out.setName("Money out");
        java.time.format.DateTimeFormatter label = java.time.format.DateTimeFormatter.ofPattern("MMM yy", Locale.ENGLISH);
        YearMonth now = YearMonth.now();
        Set<YearMonth> withData = new HashSet<>();
        for (Expense e : state.getExpenseList()) {
            if (e.getRecurringId() == null) withData.add(YearMonth.from(e.getDate()));
        }
        for (int i = 5; i >= 0; i--) {
            YearMonth ym = end.minusMonths(i);
            // Future months and months with no transactions would show misleading zeros.
            if (ym.isAfter(now) || !withData.contains(ym)) continue;
            String key = ym.format(label);
            in.getData().add(new XYChart.Data<>(key, state.actualIncome(ym)));
            out.getData().add(new XYChart.Data<>(key, Math.max(0, state.netSpendForMonth(ym))));
        }
        trendChart.getData().setAll(List.of(in, out));
        for (XYChart.Series<String, Number> s : trendChart.getData()) {
            for (XYChart.Data<String, Number> d : s.getData()) {
                if (d.getNode() != null) {
                    Tooltip.install(d.getNode(), new Tooltip(s.getName() + " · " + d.getXValue() + "\n"
                        + fmt(d.getYValue().doubleValue())));
                }
            }
        }
    }

    /** Short notes about things the user should act on: uncategorised items, stale or unbalanced statements. */
    private void updateAttention() {
        attentionBox.getChildren().clear();
        ImportRegistry registry = state.getImportRegistry();
        int statements = registry != null ? registry.getStatements().size() : 0;

        if (statements == 0 && state.getExpenseList().isEmpty()) {
            attentionBox.getChildren().add(notice("info", "Start by importing a bank statement",
                "Drop your PDF, CSV, OFX or QIF statements on the Import screen. Everything is categorised for you.",
                "Import statements", onGoToImport));
            return;
        }

        long uncategorized = state.getManager().getExpenses().stream()
            .filter(e -> e.getRecurringId() == null && !e.isExcluded())
            .filter(e -> TransactionClassifier.UNCATEGORIZED.equals(e.getCategory()))
            .count();
        if (uncategorized > 0) {
            attentionBox.getChildren().add(notice("info", uncategorized + " transaction" + (uncategorized == 1 ? "" : "s")
                    + " need a category",
                "Pick a category once and similar transactions are sorted automatically from then on.",
                "Sort them", onReviewUncategorized));
        }
        if (registry != null) {
            long unbalanced = registry.getStatements().stream()
                .filter(s -> s.openingBalance != null && !s.reconciled).count();
            if (unbalanced > 0) {
                attentionBox.getChildren().add(notice("warn", unbalanced + " statement"
                        + (unbalanced == 1 ? " doesn't" : "s don't") + " add up",
                    "Some lines may not have been read correctly, so totals could be off.", "See imports", onGoToImport));
            }
            ImportRegistry.StatementRecord latest = registry.getStatements().stream()
                .max(Comparator.comparing(ImportRegistry.StatementRecord::coverageEnd)).orElse(null);
            if (latest != null && latest.periodEnd != null
                    && latest.periodEnd.isBefore(java.time.LocalDate.now().minusDays(35))) {
                attentionBox.getChildren().add(notice("info", "Your latest statement ends "
                        + latest.periodEnd.format(DAY_FMT),
                    "Import a newer one to keep this overview up to date.", "Import", onGoToImport));
            }
        }
    }

    private HBox notice(String kind, String title, String body, String actionText, Runnable action) {
        Label icon = new Label("warn".equals(kind) ? "⚠" : "ℹ");
        icon.getStyleClass().addAll("notice-icon", "notice-icon-" + kind);
        Label t = new Label(title);
        t.getStyleClass().add("notice-title");
        Label b = new Label(body);
        b.getStyleClass().add("notice-body");
        b.setWrapText(true);
        VBox text = new VBox(2, t, b);
        HBox.setHgrow(text, Priority.ALWAYS);
        HBox box = new HBox(12, icon, text);
        box.setAlignment(Pos.CENTER_LEFT);
        box.getStyleClass().addAll("notice", "notice-" + kind);
        if (actionText != null) {
            Button btn = new Button(actionText);
            btn.getStyleClass().add("secondary-button");
            btn.setOnAction(e -> action.run());
            box.getChildren().add(btn);
        }
        return box;
    }

    private static String compact(double v) {
        double a = Math.abs(v);
        if (a >= 1_000_000) return String.format("%.1fm", v / 1_000_000);
        if (a >= 1_000) return String.format("%.0fk", v / 1_000);
        return String.format("%.0f", v);
    }

    // ======================== ANOMALY DETECTION ========================

    private void updateAnomalyAlerts() {
        anomalyBox.getChildren().clear();
        YearMonth ym = state.getSelectedYearMonth();
        if (ym == null) return;

        List<Anomaly> anomalies = anomaliesToShow(state, ym);
        if (anomalies.isEmpty()) return;

        VBox card = new VBox(8);
        card.getStyleClass().add("card");
        Label title = new Label("Worth a look");
        title.getStyleClass().add("card-title");
        card.getChildren().add(title);

        int shown = 0;
        for (Anomaly anomaly : anomalies) {
            if (shown >= 3) break;
            Label icon = new Label(anomaly.getType() == Anomaly.AnomalyType.NEW_CATEGORY ? "\u2605" : "\u26A0");
            icon.getStyleClass().addAll("list-row-icon", anomaly.getSeverity() > 0.6 ? "list-row-icon-bad" : "list-row-icon-warn");
            Label msg = new Label(anomaly.getMessage());
            msg.getStyleClass().add("list-row-text");
            msg.setWrapText(true);
            HBox.setHgrow(msg, Priority.ALWAYS);
            msg.setMaxWidth(Double.MAX_VALUE);

            Button dismiss = new Button("Dismiss");
            dismiss.getStyleClass().add("ghost-button");
            HBox row = new HBox(10, icon, msg, dismiss);
            row.setAlignment(Pos.CENTER_LEFT);
            row.getStyleClass().add("list-row");
            final Anomaly a = anomaly;
            dismiss.setOnAction(e -> {
                state.getDismissedAnomalyKeys().add(a.getDismissKey());
                try {
                    state.getStorage().saveDismissedAnomalies(state.getDismissedAnomalyKeys());
                } catch (java.io.IOException ex) {
                    System.err.println("Failed to save dismissed anomalies: " + ex.getMessage());
                }
                card.getChildren().remove(row);
                if (card.getChildren().size() == 1) anomalyBox.getChildren().clear();
            });
            card.getChildren().add(row);
            shown++;
        }
        anomalyBox.getChildren().add(card);
    }

    /** The unusual-spending notes the Overview shows for {@code ym} (not yet dismissed). */
    static List<Anomaly> anomaliesToShow(SharedState state, YearMonth ym) {
        // The full ledger: the detector needs the recurring occurrences to recognise the
        // imports that pay them (and not flag your rent as "large" every month).
        List<Anomaly> anomalies = AnomalyDetector.detect(
            new ArrayList<>(state.getExpenseList()),
            ym, state.getCurrencySymbol(),
            state.getCurrencyManager(), state.getRecurringCoverage());
        anomalies.removeIf(a -> state.getDismissedAnomalyKeys().contains(a.getDismissKey()));
        // "Uncategorized is unusually high" repeats the needs-a-category note.
        anomalies.removeIf(a -> a.getExpense() != null
            && TransactionClassifier.UNCATEGORIZED.equals(a.getExpense().getCategory()));
        return anomalies;
    }

    // ======================== UPCOMING BILL REMINDERS ========================

    private static final int REMINDER_WINDOW_DAYS = 14;

    private void updateRemindersPanel() {
        remindersBox.getChildren().clear();
        java.time.LocalDate today = java.time.LocalDate.now();
        List<Expense> upcoming = state.getManager().getUpcomingRecurring(
            today.plusDays(1), today.plusDays(REMINDER_WINDOW_DAYS));
        // Bills are money going out — drop income, refunds, and excluded series.
        upcoming.removeIf(e -> e.isIncome() || e.isRefund() || e.isExcluded());
        if (upcoming.isEmpty()) return;

        double totalBase = upcoming.stream()
            .mapToDouble(e -> state.getCurrencyManager().toBase(e.getAmount(), e.getCurrency()))
            .sum();

        VBox card = new VBox(8);
        card.getStyleClass().add("card");
        Label title = new Label("Coming up in the next " + REMINDER_WINDOW_DAYS + " days");
        title.getStyleClass().add("card-title");
        Label total = new Label(upcoming.size() + " bill" + (upcoming.size() == 1 ? "" : "s") + " · " + fmt(totalBase));
        total.getStyleClass().add("muted-text");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox header = new HBox(8, title, spacer, total);
        header.setAlignment(Pos.BASELINE_LEFT);
        card.getChildren().add(header);

        int shown = 0;
        for (Expense bill : upcoming) {
            if (shown >= 6) {
                Label more = new Label("+" + (upcoming.size() - shown) + " more");
                more.getStyleClass().add("faint-text");
                card.getChildren().add(more);
                break;
            }
            long days = java.time.temporal.ChronoUnit.DAYS.between(today, bill.getDate());
            String due = days == 1 ? "tomorrow" : "in " + days + " days";
            String name = bill.getDescription() != null && !bill.getDescription().isEmpty()
                ? bill.getDescription() : bill.getCategory();

            Label when = new Label(bill.getDate().format(java.time.format.DateTimeFormatter.ofPattern("d MMM")));
            when.getStyleClass().add("list-row-date");
            Label text = new Label(name);
            text.getStyleClass().add("list-row-text");
            Label dueLabel = new Label(due);
            dueLabel.getStyleClass().add("faint-text");
            VBox textBox = new VBox(1, text, dueLabel);
            HBox.setHgrow(textBox, Priority.ALWAYS);
            String code = bill.getCurrency() != null ? bill.getCurrency()
                : state.getCurrencyManager().getBaseCurrency();
            Label amt = new Label(UIUtils.fmt(bill.getAmount(), CurrencyManager.getSymbol(code)));
            amt.getStyleClass().add("list-row-amount");
            HBox row = new HBox(12, when, textBox, amt);
            row.setAlignment(Pos.CENTER_LEFT);
            row.getStyleClass().add("list-row");
            card.getChildren().add(row);
            shown++;
        }
        remindersBox.getChildren().add(card);
    }

    // ======================== SAVINGS GOALS ========================

    private void updateGoalsPanel() {
        goalsProgressBox.getChildren().clear();
        List<SavingsGoal> goals = state.getSavingsGoals();

        if (goals.isEmpty()) {
            Label hint = new Label("Saving for something? Add a goal with Manage and track it here.");
            hint.getStyleClass().add("empty-state-hint");
            hint.setWrapText(true);
            goalsProgressBox.getChildren().add(hint);
            return;
        }

        for (SavingsGoal goal : goals) {
            double saved = state.getGoalContributions().stream()
                .filter(c -> c.getGoalId().equals(goal.getId()))
                .mapToDouble(GoalContribution::getAmount).sum();
            double pct = goal.getTargetAmount() > 0 ? saved / goal.getTargetAmount() : 0;

            HBox row = new HBox(10);
            row.setAlignment(Pos.CENTER_LEFT);

            Label nameLabel = new Label(goal.getName());
            nameLabel.getStyleClass().add("bar-name");
            nameLabel.setMinWidth(120);

            ProgressBar bar = new ProgressBar(Math.min(pct, 1.0));
            bar.setPrefWidth(200);
            bar.setPrefHeight(16);
            if (pct >= 1.0) bar.getStyleClass().add("progress-done");
            HBox.setHgrow(bar, Priority.ALWAYS);

            Label pctLabel = new Label(String.format("%.0f%%", pct * 100));
            pctLabel.getStyleClass().addAll("bar-amount", pct >= 1.0 ? "kpi-good" : "bar-amount");
            pctLabel.setMinWidth(45);

            Label amountLabel = new Label(fmt(saved) + " / " + fmt(goal.getTargetAmount()));
            amountLabel.getStyleClass().add("muted-text");

            row.getChildren().addAll(nameLabel, bar, pctLabel, amountLabel);

            if (goal.getDeadline() != null) {
                long daysLeft = java.time.temporal.ChronoUnit.DAYS.between(java.time.LocalDate.now(), goal.getDeadline());
                String deadlineText = daysLeft > 0 ? daysLeft + " days left" : (daysLeft == 0 ? "Due today" : Math.abs(daysLeft) + " days overdue");
                Label deadlineLabel = new Label(deadlineText);
                deadlineLabel.getStyleClass().add(daysLeft < 0 ? "bar-note-over" : "faint-text");
                row.getChildren().add(deadlineLabel);
            }

            goalsProgressBox.getChildren().add(row);
        }
    }

    @FXML
    private void handleManageGoals() {
        new GoalManagementDialog(state).show();
        updateGoalsPanel();
    }

    // ======================== BUDGET ALERTS ========================

    /** One note listing categories that went over budget (the bars show the rest). */
    private void updateBudgetAlerts(Map<String, Double> categoryMap) {
        budgetAlertBox.getChildren().clear();
        Map<String, Double> budgets = state.getBudgets();
        if (budgets.isEmpty()) return;

        List<BudgetAlert> over = new ArrayList<>();
        for (Map.Entry<String, Double> entry : categoryMap.entrySet()) {
            double budget = budgets.getOrDefault(entry.getKey(), 0.0);
            if (budget > 0 && entry.getValue() > budget) {
                over.add(new BudgetAlert(entry.getKey(), entry.getValue(), budget));
            }
        }
        if (over.isEmpty()) return;
        over.sort(Comparator.comparingDouble(BudgetAlert::getPercentUsed).reversed());

        List<String> parts = new ArrayList<>();
        for (BudgetAlert a : over) {
            parts.add(a.getCategory() + " by " + fmt(a.getSpentAmount() - a.getBudgetAmount()));
        }
        String title = over.size() == 1 ? over.get(0).getCategory() + " is over budget"
            : over.size() + " categories are over budget";
        budgetAlertBox.getChildren().add(notice("warn", title, "Over: " + String.join(" · ", parts) + ".", null, null));
    }

    // ======================== BUDGET HANDLERS ========================

    private void handleSetBudget(String category) {
        Double current = state.getBudgets().get(category);
        TextInputDialog dialog = new TextInputDialog(current != null && current > 0 ? String.format("%.2f", current) : "");
        dialog.initOwner(state.getStage());
        dialog.setTitle("Monthly budget");
        dialog.setHeaderText("Monthly budget for " + category);
        dialog.setContentText("Amount:");
        UIUtils.applyStylesheet(dialog.getDialogPane());

        dialog.showAndWait().ifPresent(input -> {
            Double parsed = input.isBlank() ? Double.valueOf(0.0) : Amounts.parse(input);
            if (parsed == null || parsed < 0) {
                UIUtils.showMessage("Enter a positive amount", true, errorLabel);
                return;
            }
            Map<String, Double> budgets = state.getBudgets();
            if (parsed > 0) budgets.put(category, parsed); else budgets.remove(category);
            try {
                state.getStorage().saveBudgets(budgets);
            } catch (IOException ex) {
                UIUtils.showMessage("Error saving budget: " + ex.getMessage(), true, errorLabel);
                return;
            }
            updateTotalExpenses();
            Toast.show(parsed > 0 ? "Budget set for " + category : "Budget removed for " + category);
        });
    }

    private void handleClearBudget(String category) {
        state.getBudgets().remove(category);
        try {
            state.getStorage().saveBudgets(state.getBudgets());
        } catch (IOException ex) {
            UIUtils.showMessage("Error saving budget: " + ex.getMessage(), true, errorLabel);
            return;
        }
        updateTotalExpenses();
        Toast.show("Budget removed for " + category);
    }

    // ======================== INCOME HANDLERS ========================

    @FXML
    private void handleDeleteIncome() {
        Expense selected = incomeTable.getSelectionModel().getSelectedItem();
        if (selected == null) {
            incomeErrorLabel.setText("Select an income entry to delete.");
            incomeErrorLabel.getStyleClass().setAll("error-label", "error-message");
            return;
        }

        Alert confirmation = new Alert(Alert.AlertType.CONFIRMATION);
        confirmation.initOwner(state.getStage());
        confirmation.setTitle("Delete Income");
        confirmation.setHeaderText(null);
        confirmation.setContentText("Delete this income entry (" + fmt(selected.getAmount()) + ")?");
        confirmation.getDialogPane().getStylesheets().add(
                getClass().getResource("/styles.css").toExternalForm());

        Optional<ButtonType> result = confirmation.showAndWait();
        if (result.isPresent() && result.get() == ButtonType.OK) {
            state.getManager().executeCommand(new DeleteExpenseCommand(state.getManager(), selected));
            try {
                state.saveExpenses();
                state.syncExpenseList();
                state.requestRefresh();
                incomeErrorLabel.setText("Income deleted.");
                incomeErrorLabel.getStyleClass().setAll("error-label", "success-message");
            } catch (Exception ex) {
                state.getManager().rollbackLastCommand();
                incomeErrorLabel.setText("Error: " + ex.getMessage());
                incomeErrorLabel.getStyleClass().setAll("error-label", "error-message");
            }
        }
    }

    private void refreshIncomeTable() {
        Integer selectedYear = state.getSelectedYear();
        Month selectedMonth = state.getSelectedMonth();
        if (selectedYear == null || selectedMonth == null) {
            state.getIncomeList().clear();
            incomeTabSummary.setText("");
            return;
        }
        YearMonth selectedYearMonth = YearMonth.of(selectedYear, selectedMonth);
        List<Expense> monthIncome = state.getManager().getExpenses().stream()
                .filter(e -> SharedState.isIncomeItem(e) && YearMonth.from(e.getDate()).equals(selectedYearMonth))
                .sorted(Comparator.comparing(Expense::getDate).reversed())
                .collect(Collectors.toList());
        state.getIncomeList().setAll(monthIncome);
        if (monthIncome.isEmpty()) {
            incomeTabSummary.setText("No income this month.");
        } else {
            double total = monthIncome.stream().mapToDouble(this::toBase).sum();
            incomeTabSummary.setText(String.format("%d transaction(s) \u2014 Total: %s", monthIncome.size(), fmt(total)));
        }
    }

    private void updateIncomeField() {
        suppressIncomeListener = true;
        suppressRecurringIncomeListener = true;
        try {
            // Populate recurring income field with stored value
            double recurringIncome = state.getRecurringIncome();
            String currentRecurring = recurringIncomeField.getText();
            String expectedRecurring = recurringIncome > 0 ? String.format("%.2f", recurringIncome) : "";
            if (!expectedRecurring.equals(currentRecurring)) {
                recurringIncomeField.setText(expectedRecurring);
            }

            Integer selectedYear = state.getSelectedYear();
            Month selectedMonth = state.getSelectedMonth();
            if (selectedYear == null || selectedMonth == null) {
                incomeField.setText("");
                incomeField.setPromptText("Leave empty to use default");
                return;
            }
            YearMonth selectedYearMonth = YearMonth.of(selectedYear, selectedMonth);
            Double monthProjected = state.getIncomes().get(selectedYearMonth);
            if (monthProjected != null) {
                incomeField.setText(String.format("%.2f", monthProjected));
                incomeField.setPromptText("Clear to use default");
            } else {
                incomeField.setText("");
                incomeField.setPromptText(recurringIncome > 0
                        ? String.format("Using default: %.2f", recurringIncome)
                        : "Set projected income");
            }
        } finally {
            suppressIncomeListener = false;
            suppressRecurringIncomeListener = false;
        }
    }

    // ======================== HELPERS ========================

    private String fmt(double amount) {
        return UIUtils.fmt(amount, state.getCurrencySymbol());
    }

    private double toBase(Expense e) {
        return state.getCurrencyManager().toBase(e.getAmount(), e.getCurrency());
    }

    // ======================== DEBT SUMMARY ========================

    private void updateDebtSummary() {
        debtSummaryContent.getChildren().clear();
        if (state.getDebts().isEmpty()) {
            debtSummaryBox.setVisible(false);
            debtSummaryBox.setManaged(false);
            return;
        }
        debtSummaryBox.setVisible(true);
        debtSummaryBox.setManaged(true);

        double totalBalance = 0;
        double totalMonthly = 0;

        for (Debt debt : state.getDebts()) {
            // Manual payments + imported payments matching the debt's keyword; with none,
            // the scheduled balance (estimated). Amounts are in the debt's own currency.
            Debt.BalanceStatus status = state.debtStatus(debt);
            double balance = status.balance;
            double payment = debt.getMonthlyPayment() > 0 ? debt.getMonthlyPayment() : debt.calculateMonthlyPayment();
            double progress = debt.getPrincipal() > 0
                ? Math.max(0, Math.min(1, (debt.getPrincipal() - balance) / debt.getPrincipal()))
                : (balance <= 0.01 ? 1 : 0);

            // Totals mix debts in different currencies, so sum in the base currency.
            totalBalance += state.debtToBase(debt, balance);
            if (balance > 0.01) totalMonthly += state.debtToBase(debt, payment);

            javafx.scene.layout.HBox row = new javafx.scene.layout.HBox(10);
            row.setAlignment(javafx.geometry.Pos.CENTER_LEFT);

            Label nameLabel = new Label(debt.getName());
            nameLabel.getStyleClass().add("bar-name");
            nameLabel.setMinWidth(120);

            javafx.scene.control.ProgressBar bar = new javafx.scene.control.ProgressBar(progress);
            bar.setPrefWidth(120);
            bar.setPrefHeight(14);
            if (balance <= 0.01) bar.getStyleClass().add("progress-done");

            String balText = debt.getCurrency() != null && CurrencyManager.CURRENCIES.containsKey(debt.getCurrency())
                ? CurrencyManager.fmt(balance, debt.getCurrency())
                : UIUtils.fmt(balance, state.getCurrencySymbol());
            Label balLabel = new Label(balText + " remaining" + (status.estimated ? " (est.)" : ""));
            balLabel.getStyleClass().add("muted-text");

            row.getChildren().addAll(nameLabel, bar, balLabel);
            debtSummaryContent.getChildren().add(row);
        }

        Label totalLine = new Label(String.format("%s owed in total · %s a month in repayments",
            UIUtils.fmt(totalBalance, state.getCurrencySymbol()),
            UIUtils.fmt(totalMonthly, state.getCurrencySymbol())));
        totalLine.getStyleClass().add("card-footer");
        debtSummaryContent.getChildren().add(totalLine);
    }

    // ======================== EXCHANGE RATES ========================

    private void updateExchangeRatesPanel() {
        exchangeRatesContent.getChildren().clear();
        Map<String, Double> rates = state.getCurrencyManager().getExchangeRates();
        String baseCurrency = state.getCurrencyManager().getBaseCurrency();

        // Check if any expenses use foreign currencies
        boolean hasForeignExpenses = state.getExpenseList().stream()
            .anyMatch(e -> e.getCurrency() != null && !e.getCurrency().equals(baseCurrency));

        if (rates.isEmpty() && !hasForeignExpenses) {
            exchangeRatesBox.setVisible(false);
            exchangeRatesBox.setManaged(false);
            return;
        }
        exchangeRatesBox.setVisible(true);
        exchangeRatesBox.setManaged(true);

        Label baseLabel = new Label("Base: " + CurrencyManager.getDisplayName(baseCurrency));
        baseLabel.getStyleClass().add("muted-text");
        exchangeRatesContent.getChildren().add(baseLabel);

        for (Map.Entry<String, Double> entry : rates.entrySet()) {
            Label rateLabel = new Label(String.format("1 %s = %.4f %s", entry.getKey(), entry.getValue(), baseCurrency));
            rateLabel.getStyleClass().add("list-row-text");
            exchangeRatesContent.getChildren().add(rateLabel);
        }

        if (rates.isEmpty()) {
            Label hint = new Label("No rates yet. Use Edit to add them.");
            hint.getStyleClass().add("empty-state-hint");
            exchangeRatesContent.getChildren().add(hint);
        }
    }

    @FXML
    private void handleEditExchangeRates() {
        String baseCurrency = state.getCurrencyManager().getBaseCurrency();
        Map<String, Double> currentRates = new LinkedHashMap<>(state.getCurrencyManager().getExchangeRates());

        javafx.scene.layout.VBox content = new javafx.scene.layout.VBox(10);
        content.setPadding(new javafx.geometry.Insets(15));
        content.getStyleClass().add("root-pane");

        Label header = new Label("Exchange rates relative to " + baseCurrency);
        header.getStyleClass().add("section-title");
        header.setWrapText(true);

        Label helpText = new Label("Enter how many " + baseCurrency + " one unit of each foreign currency is worth.");
        helpText.setStyle("-fx-text-fill: #B0B0B0; -fx-font-size: 12px;");
        helpText.setWrapText(true);

        javafx.scene.layout.VBox ratesBox = new javafx.scene.layout.VBox(8);
        Map<String, javafx.scene.control.TextField> rateFields = new LinkedHashMap<>();

        // Collect currencies used in expenses
        Set<String> usedCurrencies = new LinkedHashSet<>();
        for (Expense e : state.getExpenseList()) {
            if (e.getCurrency() != null && !e.getCurrency().equals(baseCurrency)) {
                usedCurrencies.add(e.getCurrency());
            }
        }
        // Also include existing rate keys
        usedCurrencies.addAll(currentRates.keySet());

        // Common currencies to offer
        for (String code : CurrencyManager.getCurrencyCodes()) {
            if (!code.equals(baseCurrency) && (usedCurrencies.contains(code) || currentRates.containsKey(code))) {
                addRateField(ratesBox, rateFields, code, baseCurrency, currentRates);
            }
        }

        // Add button for additional currencies
        javafx.scene.control.ComboBox<String> addCurrencyCombo = new javafx.scene.control.ComboBox<>();
        addCurrencyCombo.setPromptText("Add currency...");
        addCurrencyCombo.getStyleClass().add("combo-box");
        java.util.List<String> available = new java.util.ArrayList<>();
        for (String code : CurrencyManager.getCurrencyCodes()) {
            if (!code.equals(baseCurrency) && !rateFields.containsKey(code)) {
                available.add(code);
            }
        }
        addCurrencyCombo.setItems(javafx.collections.FXCollections.observableArrayList(available));
        addCurrencyCombo.setOnAction(e -> {
            String selected = addCurrencyCombo.getValue();
            if (selected != null && !rateFields.containsKey(selected)) {
                addRateField(ratesBox, rateFields, selected, baseCurrency, currentRates);
                addCurrencyCombo.getItems().remove(selected);
                addCurrencyCombo.setValue(null);
            }
        });

        javafx.scene.layout.HBox addRow = new javafx.scene.layout.HBox(8, new Label("Add:"), addCurrencyCombo);
        addRow.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        ((Label) addRow.getChildren().get(0)).setStyle("-fx-text-fill: #E0E0E0;");

        content.getChildren().addAll(header, helpText, ratesBox, addRow);

        javafx.scene.control.ScrollPane scrollPane = new javafx.scene.control.ScrollPane(content);
        scrollPane.setFitToWidth(true);
        scrollPane.setPrefSize(450, 400);
        scrollPane.setStyle("-fx-background-color: transparent; -fx-background: #191c20;");

        javafx.scene.control.Alert dialog = new javafx.scene.control.Alert(javafx.scene.control.Alert.AlertType.NONE);
        dialog.initOwner(state.getStage());
        dialog.setTitle("Exchange Rates");
        dialog.getDialogPane().setContent(scrollPane);
        dialog.getDialogPane().getButtonTypes().addAll(
            javafx.scene.control.ButtonType.OK, javafx.scene.control.ButtonType.CANCEL);
        dialog.getDialogPane().getStylesheets().add(getClass().getResource("/styles.css").toExternalForm());

        dialog.showAndWait().ifPresent(result -> {
            if (result == javafx.scene.control.ButtonType.OK) {
                Map<String, Double> newRates = new LinkedHashMap<>();
                for (Map.Entry<String, javafx.scene.control.TextField> entry : rateFields.entrySet()) {
                    String text = entry.getValue().getText().trim();
                    if (!text.isEmpty()) {
                        try {
                            double rate = Double.parseDouble(text);
                            if (rate > 0) newRates.put(entry.getKey(), rate);
                        } catch (NumberFormatException ignored) {}
                    }
                }
                state.getCurrencyManager().setExchangeRates(newRates);
                try {
                    state.getStorage().saveExchangeRates(baseCurrency, newRates);
                } catch (java.io.IOException ex) {
                    System.err.println("Error saving exchange rates: " + ex.getMessage());
                }
                state.requestRefresh();
            }
        });
    }

    private void addRateField(javafx.scene.layout.VBox ratesBox,
                              Map<String, javafx.scene.control.TextField> rateFields,
                              String code, String baseCurrency,
                              Map<String, Double> currentRates) {
        javafx.scene.layout.HBox row = new javafx.scene.layout.HBox(8);
        row.setAlignment(javafx.geometry.Pos.CENTER_LEFT);

        Label label = new Label(String.format("1 %s =", CurrencyManager.getDisplayName(code)));
        label.setStyle("-fx-text-fill: #E0E0E0; -fx-font-size: 13px; -fx-min-width: 140;");

        javafx.scene.control.TextField field = new javafx.scene.control.TextField();
        field.setPromptText("Rate in " + baseCurrency);
        field.getStyleClass().add("text-field");
        field.setPrefWidth(120);
        if (currentRates.containsKey(code)) {
            field.setText(String.valueOf(currentRates.get(code)));
        }

        Label unitLabel = new Label(baseCurrency);
        unitLabel.setStyle("-fx-text-fill: #B0B0B0; -fx-font-size: 13px;");

        row.getChildren().addAll(label, field, unitLabel);
        ratesBox.getChildren().add(row);
        rateFields.put(code, field);
    }

    // --- Public accessors for MainController's copyable view content ---

}
