package com.wyn.expensetracker;

import javafx.beans.property.*;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.stage.Stage;

import java.io.IOException;
import java.time.Month;
import java.time.YearMonth;
import java.util.*;
import java.util.stream.Collectors;

public class SharedState {

    // Core services
    private ExpenseManager manager;
    private FileStorage storage;
    private ProfileManager profileManager;
    private CategorizationRules categorizationRules;
    private ReceiptScanner receiptScanner;
    private final ProjectionEngine projectionEngine = new ProjectionEngine();
    private final CurrencyManager currencyManager = new CurrencyManager();
    private ImportRegistry importRegistry;

    // Observable collections
    private final ObservableList<String> categories;
    private final ObservableList<Expense> expenseList = FXCollections.observableArrayList();
    private final ObservableList<RecurringExpense> recurringList = FXCollections.observableArrayList();
    private final ObservableList<CategoryTotal> categoryTotals = FXCollections.observableArrayList();
    private final ObservableList<Expense> incomeList = FXCollections.observableArrayList();
    private final ObservableList<ImportLog> importLogs = FXCollections.observableArrayList();
    private final ObservableList<String> tags = FXCollections.observableArrayList();
    private final ObservableList<SavingsGoal> savingsGoals = FXCollections.observableArrayList();
    private final ObservableList<GoalContribution> goalContributions = FXCollections.observableArrayList();
    private final ObservableList<Debt> debts = FXCollections.observableArrayList();
    private final ObservableList<DebtPayment> debtPayments = FXCollections.observableArrayList();
    private final Set<String> dismissedAnomalyKeys = new HashSet<>();
    private final FilteredList<Expense> filteredData;

    // Year list for combo
    private final ObservableList<Integer> yearList = FXCollections.observableArrayList();

    // Settings
    private Map<YearMonth, Double> incomes;
    private Map<String, Double> budgets;
    private final StringProperty currencySymbol = new SimpleStringProperty("R");
    private final DoubleProperty recurringIncome = new SimpleDoubleProperty(0.0);

    // Period selection (bound to toolbar combos)
    private final ObjectProperty<Integer> selectedYear = new SimpleObjectProperty<>();
    private final ObjectProperty<Month> selectedMonth = new SimpleObjectProperty<>();
    private final StringProperty chartPeriod = new SimpleStringProperty("Last 12 Months");

    // State flags
    private boolean projectionsNeedUpdate = true;
    private String currentViewName = "dashboard";

    // UI
    private Stage stage;

    // Refresh callback
    private Runnable refreshCallback;

    public SharedState(ExpenseManager manager, FileStorage storage,
                       ObservableList<String> categories, Map<YearMonth, Double> incomes,
                       Stage stage, ProfileManager profileManager) {
        this.manager = manager;
        this.storage = storage;
        this.categories = categories;
        this.incomes = incomes;
        this.stage = stage;
        this.profileManager = profileManager;
        this.filteredData = new FilteredList<>(expenseList, p -> true);
        this.budgets = new HashMap<>();
        this.categorizationRules = new CategorizationRules();
        this.receiptScanner = new ReceiptScanner();
    }

    // --- Core services ---

    public ExpenseManager getManager() { return manager; }
    public void setManager(ExpenseManager manager) { this.manager = manager; }

    public FileStorage getStorage() { return storage; }
    public void setStorage(FileStorage storage) { this.storage = storage; }

    public ProfileManager getProfileManager() { return profileManager; }

    public CategorizationRules getCategorizationRules() { return categorizationRules; }
    public void setCategorizationRules(CategorizationRules rules) { this.categorizationRules = rules; }

    public ReceiptScanner getReceiptScanner() { return receiptScanner; }

    /** Imported statements (periods, balances) for the active profile; set by the Import screen. */
    public ImportRegistry getImportRegistry() { return importRegistry; }
    public void setImportRegistry(ImportRegistry registry) { this.importRegistry = registry; }
    public ProjectionEngine getProjectionEngine() { return projectionEngine; }

    // --- Collections ---

    public ObservableList<String> getCategories() { return categories; }
    public ObservableList<Expense> getExpenseList() { return expenseList; }
    public ObservableList<RecurringExpense> getRecurringList() { return recurringList; }
    public ObservableList<CategoryTotal> getCategoryTotals() { return categoryTotals; }
    public ObservableList<Expense> getIncomeList() { return incomeList; }
    public ObservableList<ImportLog> getImportLogs() { return importLogs; }
    public ObservableList<String> getTags() { return tags; }
    public ObservableList<SavingsGoal> getSavingsGoals() { return savingsGoals; }
    public ObservableList<GoalContribution> getGoalContributions() { return goalContributions; }
    public ObservableList<Debt> getDebts() { return debts; }
    public ObservableList<DebtPayment> getDebtPayments() { return debtPayments; }
    public CurrencyManager getCurrencyManager() { return currencyManager; }
    public Set<String> getDismissedAnomalyKeys() { return dismissedAnomalyKeys; }
    public FilteredList<Expense> getFilteredData() { return filteredData; }
    public ObservableList<Integer> getYearList() { return yearList; }

    // --- Settings ---

    public Map<YearMonth, Double> getIncomes() { return incomes; }
    public void setIncomes(Map<YearMonth, Double> incomes) { this.incomes = incomes; }

    public Map<String, Double> getBudgets() { return budgets; }
    public void setBudgets(Map<String, Double> budgets) { this.budgets = budgets; }

    public StringProperty currencySymbolProperty() { return currencySymbol; }
    public String getCurrencySymbol() { return currencySymbol.get(); }
    public void setCurrencySymbol(String symbol) { currencySymbol.set(symbol); }

    public DoubleProperty recurringIncomeProperty() { return recurringIncome; }
    public double getRecurringIncome() { return recurringIncome.get(); }
    public void setRecurringIncome(double value) { recurringIncome.set(value); }

    // --- Period selection ---

    public ObjectProperty<Integer> selectedYearProperty() { return selectedYear; }
    public Integer getSelectedYear() { return selectedYear.get(); }
    public void setSelectedYear(Integer year) { selectedYear.set(year); }

    public ObjectProperty<Month> selectedMonthProperty() { return selectedMonth; }
    public Month getSelectedMonth() { return selectedMonth.get(); }
    public void setSelectedMonth(Month month) { selectedMonth.set(month); }

    public StringProperty chartPeriodProperty() { return chartPeriod; }
    public String getChartPeriod() { return chartPeriod.get(); }
    public void setChartPeriod(String period) { chartPeriod.set(period); }

    public YearMonth getSelectedYearMonth() {
        Integer y = getSelectedYear();
        Month m = getSelectedMonth();
        if (y == null || m == null) return null;
        return YearMonth.of(y, m);
    }

    // --- State flags ---

    public boolean isProjectionsNeedUpdate() { return projectionsNeedUpdate; }
    public void setProjectionsNeedUpdate(boolean value) { projectionsNeedUpdate = value; }

    public String getCurrentViewName() { return currentViewName; }
    public void setCurrentViewName(String name) { currentViewName = name; }

    // --- UI ---

    public Stage getStage() { return stage; }

    // --- Refresh ---

    public void setRefreshCallback(Runnable callback) { this.refreshCallback = callback; }

    public void requestRefresh() {
        // Flags may have been toggled in place (income/refund/excluded), which the
        // list listener cannot see; recompute derived spend data on every refresh.
        invalidateSpendCache();
        if (refreshCallback != null) refreshCallback.run();
    }

    // --- Convenience methods ---

    public void saveExpenses() throws IOException {
        storage.saveExpenses(manager.getExpensesForSave());
    }

    public void saveRecurringOverrides() throws IOException {
        storage.saveRecurringOverrides(manager.getOverrides());
    }

    public void syncExpenseList() {
        expenseList.setAll(manager.getExpenses());
    }

    public void syncRecurringList() {
        recurringList.setAll(manager.getBaseRecurringExpenses());
    }

    /**
     * Current balance of a debt: manual payments plus imported transactions matching its
     * payment keyword; with no payments at all, the scheduled (estimated) balance.
     * The balance is in the debt's own currency.
     */
    public Debt.BalanceStatus debtStatus(Debt debt) {
        return debt.resolveBalance(debtPayments, expenseList, currencyManager, java.time.LocalDate.now());
    }

    /** A debt amount (in the debt's currency) converted to the base currency. */
    public double debtToBase(Debt debt, double amount) {
        return currencyManager.toBase(amount, debt.getCurrency());
    }

    /**
     * Re-expresses every amount that is implicitly in the base currency (planned monthly
     * incomes, the recurring-income default, budgets, savings-goal targets/monthly targets
     * and goal contributions) by multiplying by {@code factor}. Used when the base currency
     * changes (factor = old-base amount -> new-base amount).
     */
    public void scaleBaseCurrencyAmounts(double factor) {
        if (incomes != null) incomes.replaceAll((k, v) -> v == null ? null : v * factor);
        setRecurringIncome(getRecurringIncome() * factor);
        if (budgets != null) budgets.replaceAll((k, v) -> v == null ? null : v * factor);
        for (SavingsGoal g : savingsGoals) {
            g.setTargetAmount(g.getTargetAmount() * factor);
            g.setMonthlyTarget(g.getMonthlyTarget() * factor);
        }
        List<GoalContribution> converted = new ArrayList<>();
        for (GoalContribution c : goalContributions) {
            converted.add(new GoalContribution(c.getGoalId(), c.getAmount() * factor, c.getDate(), c.getNote()));
        }
        goalContributions.setAll(converted);
    }

    /** Whether any base-currency-denominated setting holds a non-zero amount. */
    public boolean hasBaseCurrencyAmounts() {
        if (incomes != null && incomes.values().stream().anyMatch(v -> v != null && v != 0)) return true;
        if (getRecurringIncome() != 0) return true;
        if (budgets != null && budgets.values().stream().anyMatch(v -> v != null && v != 0)) return true;
        if (savingsGoals.stream().anyMatch(g -> g.getTargetAmount() != 0 || g.getMonthlyTarget() != 0)) return true;
        return goalContributions.stream().anyMatch(c -> c.getAmount() != 0);
    }

    public boolean monthHasImportedData(YearMonth ym) {
        return expenseList.stream()
            .anyMatch(e -> !e.isIncome() && e.getImportId() != null
                && YearMonth.from(e.getDate()).equals(ym));
    }

    // ======================== RECURRING COVERAGE (one-to-one) ========================

    /**
     * Result of matching imported transactions against generated recurring occurrences.
     * Each import covers at most one occurrence and each occurrence is covered by at most
     * one import.
     */
    public static final class RecurringCoverage {
        /** recurringIds of generated occurrences that an imported transaction already covers. */
        public final Set<String> coveredRecurringIds;
        /** Imported transactions (by identity) that were matched to a recurring occurrence. */
        public final Set<Expense> coveringImports;

        RecurringCoverage(Set<String> coveredRecurringIds, Set<Expense> coveringImports) {
            this.coveredRecurringIds = coveredRecurringIds;
            this.coveringImports = coveringImports;
        }
    }

    private RecurringCoverage coverageCache;

    {
        // Any change to the ledger invalidates the cached coverage.
        expenseList.addListener((javafx.collections.ListChangeListener<Expense>) c -> invalidateSpendCache());
    }

    /** Drops cached spend data; call after mutating an Expense in place without touching the list. */
    public void invalidateSpendCache() {
        coverageCache = null;
        projectionCache = null;
    }

    /** Cached one-to-one coverage for the current expense list. */
    public RecurringCoverage getRecurringCoverage() {
        RecurringCoverage c = coverageCache;
        if (c == null) {
            c = computeRecurringCoverage(expenseList, currencyManager);
            coverageCache = c;
        }
        return c;
    }

    /** Maximum distance (days) between an import and the occurrence it covers. */
    public static final int COVERAGE_WINDOW_DAYS = 5;

    /** Raw-amount variant (no currency conversion); kept for source compatibility. */
    public static RecurringCoverage computeRecurringCoverage(Collection<? extends Expense> expenses) {
        return computeRecurringCoverage(expenses, null);
    }

    /**
     * Pure one-to-one matching of generated recurring occurrences (spend AND income) to
     * imported transactions of the same kind. A candidate pair needs: the import is not
     * excluded/refund and has the same income flag as the occurrence; the import's
     * description contains the template description; the dates are within
     * {@link #COVERAGE_WINDOW_DAYS} days (across month boundaries); and the base-currency
     * amounts are within 20%. Pairs are chosen by an optimal one-to-one matching: the
     * maximum number of pairs, and among those the smallest total date distance (then
     * amount difference), so e.g. a weekly series is not mis-paired by a greedy choice.
     *
     * @param cm converts amounts to base currency before comparing; null compares raw amounts
     */
    public static RecurringCoverage computeRecurringCoverage(Collection<? extends Expense> expenses,
                                                             CurrencyManager cm) {
        List<Expense> imports = new ArrayList<>();
        List<Expense> occurrences = new ArrayList<>();
        for (Expense e : expenses) {
            if (e == null || e.getDate() == null) continue;
            if (e.isExcluded() || e.isRefund()) continue;
            if (e.getRecurringId() == null) {
                if (e.getImportId() != null && e.getDescription() != null) imports.add(e);
            } else {
                RecurringExpense src = e.getSourceRecurringExpense();
                if (src != null && src.getDescription() != null
                        && !src.getDescription().trim().isEmpty()) {
                    occurrences.add(e);
                }
            }
        }
        Set<String> covered = new HashSet<>();
        Set<Expense> covering = Collections.newSetFromMap(new IdentityHashMap<>());
        if (imports.isEmpty() || occurrences.isEmpty()) return new RecurringCoverage(covered, covering);

        occurrences.sort(Comparator.comparing(Expense::getDate)
            .thenComparing(Expense::getRecurringId));
        imports.sort(Comparator.comparing(Expense::getDate));
        int nOcc = occurrences.size();
        int nImp = imports.size();
        // Imports sorted by date: each occurrence only scans the imports within the window.
        long[] impDay = new long[nImp];
        String[] impDesc = new String[nImp];
        double[] impAmt = new double[nImp];
        for (int i = 0; i < nImp; i++) {
            Expense imp = imports.get(i);
            impDay[i] = imp.getDate().toEpochDay();
            impDesc[i] = imp.getDescription().toLowerCase();
            impAmt[i] = baseAmount(imp, cm);
        }
        // Candidate edges occurrence -> import with cost = days + small amount tie-break.
        int[][] edgeImp = new int[nOcc][];
        double[][] edgeCost = new double[nOcc][];
        int[] parent = new int[nOcc + nImp];
        for (int i = 0; i < parent.length; i++) parent[i] = i;
        for (int o = 0; o < nOcc; o++) {
            Expense occ = occurrences.get(o);
            String srcDesc = occ.getSourceRecurringExpense().getDescription().toLowerCase().trim();
            double occAmt = baseAmount(occ, cm);
            long day = occ.getDate().toEpochDay();
            int lo = lowerBound(impDay, day - COVERAGE_WINDOW_DAYS);
            int cnt = 0;
            int[] es = new int[4];
            double[] cs = new double[4];
            for (int i = lo; i < nImp && impDay[i] <= day + COVERAGE_WINDOW_DAYS; i++) {
                Expense imp = imports.get(i);
                if (imp.isIncome() != occ.isIncome()) continue;
                if (!impDesc[i].contains(srcDesc)) continue;
                double diff = Math.abs(impAmt[i] - occAmt);
                double tol = Math.abs(occAmt) * 0.20;
                if (diff > tol) continue;
                double rel = tol > 0 ? diff / tol : 0; // 0..1
                if (cnt == es.length) { es = Arrays.copyOf(es, cnt * 2); cs = Arrays.copyOf(cs, cnt * 2); }
                es[cnt] = i;
                cs[cnt] = Math.abs(impDay[i] - day) + rel * 1e-3;
                cnt++;
                union(parent, o, nOcc + i);
            }
            edgeImp[o] = Arrays.copyOf(es, cnt);
            edgeCost[o] = Arrays.copyOf(cs, cnt);
        }
        // Solve each connected component independently.
        Map<Integer, List<Integer>> compOcc = new LinkedHashMap<>();
        for (int o = 0; o < nOcc; o++) {
            if (edgeImp[o].length == 0) continue;
            compOcc.computeIfAbsent(find(parent, o), k -> new ArrayList<>()).add(o);
        }
        for (List<Integer> comp : compOcc.values()) {
            for (int[] pair : minCostMaxMatching(comp, edgeImp, edgeCost)) {
                covered.add(occurrences.get(pair[0]).getRecurringId());
                covering.add(imports.get(pair[1]));
            }
        }
        return new RecurringCoverage(covered, covering);
    }

    /** First index whose value is >= key (values sorted ascending). */
    private static int lowerBound(long[] values, long key) {
        int lo = 0, hi = values.length;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (values[mid] < key) lo = mid + 1; else hi = mid;
        }
        return lo;
    }

    private static int find(int[] parent, int x) {
        while (parent[x] != x) { parent[x] = parent[parent[x]]; x = parent[x]; }
        return x;
    }

    private static void union(int[] parent, int a, int b) {
        int ra = find(parent, a), rb = find(parent, b);
        if (ra != rb) parent[ra] = rb;
    }

    /**
     * Minimum-cost maximum bipartite matching over the given occurrences (successive
     * shortest augmenting paths, Dijkstra with Johnson potentials: each path found is the
     * globally cheapest one, so the result has the maximum number of pairs and, among
     * those, the minimum total cost). Returns {occurrenceIndex, importIndex} pairs.
     */
    private static List<int[]> minCostMaxMatching(List<Integer> occs, int[][] edgeImp, double[][] edgeCost) {
        // Node layout: 0 = source, 1..k = occurrences, then imports, last = sink.
        Map<Integer, Integer> impNode = new HashMap<>();
        List<Integer> impList = new ArrayList<>();
        int k = occs.size();
        for (int o : occs) {
            for (int i : edgeImp[o]) {
                if (!impNode.containsKey(i)) { impNode.put(i, k + 1 + impList.size()); impList.add(i); }
            }
        }
        int n = k + impList.size() + 2;
        int src = 0, sink = n - 1;
        int m = k + impList.size();
        for (int o : occs) m += edgeImp[o].length;
        FlowGraph g = new FlowGraph(n, m);
        for (int a = 0; a < k; a++) {
            int o = occs.get(a);
            g.add(src, a + 1, 0.0);
            for (int t = 0; t < edgeImp[o].length; t++) g.add(a + 1, impNode.get(edgeImp[o][t]), edgeCost[o][t]);
        }
        for (int j = 0; j < impList.size(); j++) g.add(k + 1 + j, sink, 0.0);

        double[] pot = new double[n];      // all initial costs are >= 0, so 0 is feasible
        double[] dist = new double[n];
        int[] via = new int[n];
        boolean[] done = new boolean[n];
        while (true) {
            Arrays.fill(dist, Double.POSITIVE_INFINITY);
            Arrays.fill(via, -1);
            Arrays.fill(done, false);
            dist[src] = 0;
            PriorityQueue<double[]> pq = new PriorityQueue<>((x, y) -> Double.compare(x[0], y[0]));
            pq.add(new double[] {0, src});
            while (!pq.isEmpty()) {
                double[] top = pq.poll();
                int u = (int) top[1];
                if (done[u]) continue;
                done[u] = true;
                if (u == sink) break;
                for (int e = g.head[u]; e >= 0; e = g.next[e]) {
                    if (g.cap[e] <= 0) continue;
                    int v = g.to[e];
                    if (done[v]) continue;
                    double rc = g.cost[e] + pot[u] - pot[v];
                    if (rc < 0) rc = 0; // rounding noise: reduced costs are non-negative
                    double nd = dist[u] + rc;
                    if (nd < dist[v] - 1e-12) { dist[v] = nd; via[v] = e; pq.add(new double[] {nd, v}); }
                }
            }
            if (via[sink] < 0) break;
            double dSink = dist[sink];
            // Early-stopped Dijkstra: cap unsettled distances at the sink's to keep potentials valid.
            for (int v = 0; v < n; v++) pot[v] += Math.min(dist[v], dSink);
            for (int v = sink; v != src; v = g.to[via[v] ^ 1]) {
                g.cap[via[v]] -= 1;
                g.cap[via[v] ^ 1] += 1;
            }
        }
        List<int[]> result = new ArrayList<>();
        for (int a = 0; a < k; a++) {
            for (int e = g.head[a + 1]; e >= 0; e = g.next[e]) {
                if ((e & 1) == 0 && g.to[e] != src && g.cap[e] == 0) {
                    result.add(new int[]{occs.get(a), impList.get(g.to[e] - k - 1)});
                }
            }
        }
        return result;
    }

    /** Unit-capacity residual graph in flat arrays (edge e and e^1 are a forward/back pair). */
    private static final class FlowGraph {
        final int[] head, to, cap, next;
        final double[] cost;
        int count;

        FlowGraph(int nodes, int edges) {
            head = new int[nodes];
            Arrays.fill(head, -1);
            to = new int[2 * edges]; cap = new int[2 * edges]; next = new int[2 * edges];
            cost = new double[2 * edges];
        }

        void add(int a, int b, double c) {
            int e = count;
            to[e] = b; cap[e] = 1; cost[e] = c; next[e] = head[a]; head[a] = e;
            to[e + 1] = a; cap[e + 1] = 0; cost[e + 1] = -c; next[e + 1] = head[b]; head[b] = e + 1;
            count = e + 2;
        }
    }

    private static double baseAmount(Expense e, CurrencyManager cm) {
        return cm != null ? cm.toBase(e.getAmount(), e.getCurrency()) : e.getAmount();
    }

    /**
     * Whether a generated recurring occurrence is already covered by an imported
     * transaction (one-to-one). {@code ym} is retained for source compatibility.
     */
    public boolean shouldExcludeRecurring(Expense recurring, YearMonth ym) {
        if (recurring == null || recurring.getRecurringId() == null) return false;
        return getRecurringCoverage().coveredRecurringIds.contains(recurring.getRecurringId());
    }

    /** Whether an imported transaction was matched to (covers) a recurring occurrence. */
    public boolean coversRecurring(Expense imported) {
        return imported != null && getRecurringCoverage().coveringImports.contains(imported);
    }

    /** Months that contain at least one imported (non-income) transaction. */
    public Set<YearMonth> importedMonths() {
        return expenseList.stream()
            .filter(e -> !e.isIncome() && e.getImportId() != null)
            .map(e -> YearMonth.from(e.getDate()))
            .collect(Collectors.toSet());
    }

    /**
     * Single source of truth for whether an expense counts as (gross) "spend".
     * Excludes income, refunds and excluded items, and drops a recurring occurrence
     * when an imported transaction already covers it (one-to-one). Refunds are netted
     * separately via {@link #spendContribution(Expense)} / {@link #netSpend(Collection)}.
     *
     * @param importedMonths ignored (kept for source compatibility); coverage is cached.
     */
    public boolean countsAsSpend(Expense e, Set<YearMonth> importedMonths) {
        return countsAsSpend(e);
    }

    public boolean countsAsSpend(Expense e) {
        if (e == null || e.isExcluded() || e.isIncome() || e.isRefund()) return false;
        return e.getRecurringId() == null || !shouldExcludeRecurring(e, null);
    }

    /** A refund (money back for a purchase) that reduces spend. Refund wins over income. */
    public static boolean isRefundCredit(Expense e) {
        return e.isRefund() && !e.isExcluded();
    }

    /** Income = income flag, not a refund, not excluded. */
    public static boolean isIncomeItem(Expense e) {
        return e.isIncome() && !e.isRefund() && !e.isExcluded();
    }

    /** Amount converted to the base currency. */
    public double toBase(Expense e) {
        return currencyManager.toBase(e.getAmount(), e.getCurrency());
    }

    /** Signed contribution to spend in base currency: +amount for spend, -amount for a refund, else 0. */
    public double spendContribution(Expense e) {
        if (countsAsSpend(e)) return toBase(e);
        if (isRefundCredit(e)) return -toBase(e);
        return 0;
    }

    // ---------------- Clamping rule (single source of truth) ----------------
    // Net spend is clamped at the MONTH x CATEGORY cell: a category's net spend in a
    // month is max(0, spend - refunds). Every total (per category over a period, per
    // month, grand total) is the sum of those clamped cells.

    /**
     * Clamped net spend cells: month -> category -> max(0, sum of contributions).
     * Cells that clamp to zero are omitted.
     */
    public static Map<YearMonth, Map<String, Double>> clampedSpendCells(
            Collection<? extends Expense> expenses, java.util.function.ToDoubleFunction<Expense> contribution) {
        Map<YearMonth, Map<String, Double>> raw = new HashMap<>();
        for (Expense e : expenses) {
            if (e == null || e.getDate() == null) continue;
            double c = contribution.applyAsDouble(e);
            if (c == 0) continue;
            raw.computeIfAbsent(YearMonth.from(e.getDate()), k -> new HashMap<>())
                .merge(e.getCategory(), c, Double::sum);
        }
        Map<YearMonth, Map<String, Double>> result = new HashMap<>();
        raw.forEach((ym, cats) -> {
            Map<String, Double> clamped = clampCategories(cats);
            if (!clamped.isEmpty()) result.put(ym, clamped);
        });
        return result;
    }

    /** Sum of the clamped month x category cells. */
    public static double clampedNetSpend(Collection<? extends Expense> expenses,
                                         java.util.function.ToDoubleFunction<Expense> contribution) {
        double total = 0;
        for (Map<String, Double> cats : clampedSpendCells(expenses, contribution).values()) {
            for (double v : cats.values()) total += v;
        }
        return total;
    }

    /**
     * Splits the clamped net spend of {@code expenses} into two parts (items matching
     * {@code isA} and the rest) that always add up to {@link #netSpend(Collection)}.
     * Within each month x category cell, each part's own net is clamped at 0 and, if the
     * two together exceed the cell's clamped net (refunds in one part offsetting spend in
     * the other), both are scaled down proportionally.
     *
     * @return {partA, partB}
     */
    public double[] splitNetSpend(Collection<? extends Expense> expenses,
                                  java.util.function.Predicate<Expense> isA) {
        Map<YearMonth, Map<String, double[]>> cells = new HashMap<>();
        for (Expense e : expenses) {
            if (e == null || e.getDate() == null) continue;
            double c = spendContribution(e);
            if (c == 0) continue;
            double[] cell = cells.computeIfAbsent(YearMonth.from(e.getDate()), k -> new HashMap<>())
                .computeIfAbsent(e.getCategory(), k -> new double[2]);
            cell[isA.test(e) ? 0 : 1] += c;
        }
        double a = 0, b = 0;
        for (Map<String, double[]> cats : cells.values()) {
            for (double[] cell : cats.values()) {
                double net = Math.max(0, cell[0] + cell[1]);
                double pa = Math.max(0, cell[0]);
                double pb = Math.max(0, cell[1]);
                double sum = pa + pb;
                if (net <= 1e-9 || sum <= 1e-9) continue;
                double scale = Math.min(1.0, net / sum);
                a += pa * scale;
                b += pb * scale;
            }
        }
        return new double[] {a, b};
    }

    /**
     * Net spend per category (spend minus refunds), in base currency, clamped at the
     * month x category cell and summed over months. Categories that net to 0 are omitted.
     */
    public Map<String, Double> spendByCategory(Collection<? extends Expense> expenses) {
        Map<String, Double> result = new HashMap<>();
        for (Map<String, Double> cats : clampedSpendCells(expenses, this::spendContribution).values()) {
            cats.forEach((cat, v) -> result.merge(cat, v, Double::sum));
        }
        return result;
    }

    /** Unclamped signed net spend per category. */
    public Map<String, Double> rawSpendByCategory(Collection<? extends Expense> expenses) {
        Map<String, Double> raw = new HashMap<>();
        for (Expense e : expenses) {
            double c = spendContribution(e);
            if (c != 0) raw.merge(e.getCategory(), c, Double::sum);
        }
        return raw;
    }

    static Map<String, Double> clampCategories(Map<String, Double> raw) {
        Map<String, Double> result = new HashMap<>();
        for (Map.Entry<String, Double> en : raw.entrySet()) {
            if (en.getValue() > 1e-9) result.put(en.getKey(), en.getValue());
        }
        return result;
    }

    /** Net spend = sum of the clamped per-category nets, so totals reconcile with category views. */
    public double netSpend(Collection<? extends Expense> expenses) {
        return spendByCategory(expenses).values().stream().mapToDouble(Double::doubleValue).sum();
    }

    /** Net spend per month per category (each month/category clamped at 0). */
    public Map<YearMonth, Map<String, Double>> netSpendByMonthAndCategory(Collection<? extends Expense> expenses) {
        return clampedSpendCells(expenses, this::spendContribution);
    }

    /** Net spend per month (sum of that month's clamped category nets). */
    public Map<YearMonth, Double> netSpendByMonth(Collection<? extends Expense> expenses) {
        Map<YearMonth, Double> result = new HashMap<>();
        netSpendByMonthAndCategory(expenses).forEach((ym, cats) ->
            result.put(ym, cats.values().stream().mapToDouble(Double::doubleValue).sum()));
        return result;
    }

    /** All ledger entries dated in the given month. */
    public List<Expense> expensesInMonth(YearMonth ym) {
        return expenseList.stream()
            .filter(e -> YearMonth.from(e.getDate()).equals(ym))
            .collect(Collectors.toList());
    }

    /**
     * Ledger entries for a month, plus — for months after the current one, which the
     * ledger has not generated yet — the scheduled recurring occurrences (with overrides).
     */
    public List<Expense> monthItemsWithProjection(YearMonth ym) {
        List<Expense> items = expensesInMonth(ym);
        if (ym.isAfter(YearMonth.now()) && manager != null) {
            Set<String> present = new HashSet<>();
            for (Expense e : items) if (e.getRecurringId() != null) present.add(e.getRecurringId());
            for (Expense e : manager.getUpcomingRecurring(ym.atDay(1), ym.atEndOfMonth())) {
                if (!e.isIncome() && !e.isExcluded() && !present.contains(e.getRecurringId())) {
                    items.add(e);
                }
            }
        }
        if (ym.isAfter(YearMonth.now())) {
            // Typical variable (non-recurring) spend from the projection engine, one
            // synthetic item per category, so the Overview matches the Projections tab.
            // Gross variable spend: this list also carries the month's recurring refund
            // occurrences, and the month x category clamp nets them exactly once.
            projectedGrossVariableByCategory(ym).forEach((cat, amt) -> {
                if (amt > 1e-9) items.add(new Expense(amt, cat, ym.atDay(1), PROJECTED_VARIABLE_DESCRIPTION));
            });
        }
        return items;
    }

    /** Description of the synthetic items that carry projected variable spend. */
    public static final String PROJECTED_VARIABLE_DESCRIPTION = "Projected variable spending";

    private ProjectionEngine.ProjectionResult projectionCache;
    private YearMonth projectionCacheMonth;

    /** Projection of the current state (cached until the ledger changes or a refresh). */
    public ProjectionEngine.ProjectionResult currentProjection() {
        YearMonth now = YearMonth.now();
        if (projectionCache == null || !now.equals(projectionCacheMonth)) {
            List<RecurringExpense> recurring = manager != null
                ? new ArrayList<>(manager.getBaseRecurringExpenses()) : new ArrayList<>();
            ProjectionEngine.ProjectionInput input = new ProjectionEngine.ProjectionInput(
                new ArrayList<>(expenseList), recurring,
                incomes != null ? incomes : new HashMap<>(), getRecurringIncome(),
                budgets != null ? budgets : new HashMap<>(), currencyManager,
                manager != null ? manager.getOverrides() : null);
            projectionCache = projectionEngine.project(input, now, getRecurringCoverage());
            projectionCacheMonth = now;
        }
        return projectionCache;
    }

    /** Projected variable spend per category for a future month (empty if out of range). */
    public Map<String, Double> projectedVariableByCategory(YearMonth ym) {
        if (!ym.isAfter(YearMonth.now())) return Collections.emptyMap();
        for (ProjectionEngine.MonthProjection mp : currentProjection().monthProjections) {
            if (mp.month.equals(ym)) return mp.categoryVariable;
        }
        return Collections.emptyMap();
    }

    /** As {@link #projectedVariableByCategory} but before recurring refunds are netted in. */
    public Map<String, Double> projectedGrossVariableByCategory(YearMonth ym) {
        if (!ym.isAfter(YearMonth.now())) return Collections.emptyMap();
        for (ProjectionEngine.MonthProjection mp : currentProjection().monthProjections) {
            if (mp.month.equals(ym)) return mp.categoryVariableGross;
        }
        return Collections.emptyMap();
    }

    /** Projected variable (non-recurring) spend for a future month; 0 for past/current months. */
    public double projectedVariableSpend(YearMonth ym) {
        return projectedVariableByCategory(ym).values().stream().mapToDouble(Double::doubleValue).sum();
    }

    /** Net spend for one calendar month (future months include scheduled bills and projected variable spend). */
    public double netSpendForMonth(YearMonth ym) {
        return netSpend(monthItemsWithProjection(ym));
    }

    // ======================== INCOME ========================

    /** Planned income: explicit per-month entry, else the recurring default. */
    public double plannedIncome(YearMonth ym) {
        Double v = incomes != null ? incomes.get(ym) : null;
        return v != null ? v : getRecurringIncome();
    }

    /**
     * Actual income transactions for the month (base currency). For future months, which
     * have no generated ledger occurrences yet, scheduled recurring income is used.
     */
    public double actualIncome(YearMonth ym) {
        double total = 0;
        for (Expense e : expenseList) {
            if (!isIncomeItem(e) || !YearMonth.from(e.getDate()).equals(ym)) continue;
            // A generated income occurrence already covered by an imported credit (e.g. the
            // salary on the statement) must not be counted a second time.
            if (e.getRecurringId() != null && shouldExcludeRecurring(e, ym)) continue;
            total += toBase(e);
        }
        if (ym.isAfter(YearMonth.now()) && manager != null) {
            for (Expense e : manager.getUpcomingRecurring(ym.atDay(1), ym.atEndOfMonth())) {
                if (isIncomeItem(e)) total += toBase(e);
            }
        }
        return total;
    }

    /** The single income figure for a month, used everywhere in the app. */
    public double incomeForMonth(YearMonth ym) {
        return resolveMonthlyIncome(actualIncome(ym), plannedIncome(ym), ym, YearMonth.now());
    }

    /**
     * Pure income rule: past months use actual income when there is any, else planned;
     * the current and future months use max(actual, planned) so a small early credit
     * cannot wipe out the planned salary.
     */
    public static double resolveMonthlyIncome(double actual, double planned, YearMonth ym, YearMonth now) {
        if (ym.isBefore(now)) {
            return actual > 0 ? actual : planned;
        }
        return Math.max(actual, planned);
    }

    /** Whether an expense's date falls within the selected chart period. */
    public boolean matchesPeriod(Expense e, String chartPeriod, int selectedYear,
                                 YearMonth selectedYearMonth, YearMonth now) {
        YearMonth ym = YearMonth.from(e.getDate());
        switch (chartPeriod) {
            case "By Year":
                return e.getDate().getYear() == selectedYear;
            case "By Month":
                return ym.equals(selectedYearMonth);
            case "Last 6 Months":
                return !ym.isBefore(now.minusMonths(5)) && !ym.isAfter(now);
            case "Last 12 Months":
                return !ym.isBefore(now.minusMonths(11)) && !ym.isAfter(now);
            default:
                return true;
        }
    }

    public List<Expense> filterExpensesByPeriod(String chartPeriod, int selectedYear,
                                                 YearMonth selectedYearMonth, YearMonth now) {
        return expenseList.stream()
            .filter(this::countsAsSpend)
            .filter(e -> matchesPeriod(e, chartPeriod, selectedYear, selectedYearMonth, now))
            .collect(Collectors.toList());
    }

    /** Spend items plus refunds (the inputs to net spend) within a chart period. */
    public List<Expense> filterSpendAndRefundsByPeriod(String chartPeriod, int selectedYear,
                                                       YearMonth selectedYearMonth, YearMonth now) {
        return expenseList.stream()
            .filter(e -> countsAsSpend(e) || isRefundCredit(e))
            .filter(e -> matchesPeriod(e, chartPeriod, selectedYear, selectedYearMonth, now))
            .collect(Collectors.toList());
    }

    public void getMonthRange(String chartPeriod, int selectedYear, YearMonth selectedYearMonth,
                               YearMonth now, Map<YearMonth, Double> monthlyTotals,
                               YearMonth[] out) {
        switch (chartPeriod) {
            case "By Year":
                out[0] = YearMonth.of(selectedYear, 1);
                out[1] = YearMonth.of(selectedYear, 12);
                break;
            case "Last 6 Months":
                out[0] = now.minusMonths(5);
                out[1] = now;
                break;
            case "Last 12 Months":
                out[0] = now.minusMonths(11);
                out[1] = now;
                break;
            default:
                if (monthlyTotals.isEmpty()) {
                    out[0] = now;
                    out[1] = now;
                } else {
                    out[0] = monthlyTotals.keySet().stream().min(Comparator.naturalOrder()).orElse(now);
                    out[1] = monthlyTotals.keySet().stream().max(Comparator.naturalOrder()).orElse(now);
                }
                break;
        }
    }
}
