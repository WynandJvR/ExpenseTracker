package com.wyn.expensetracker;

import javafx.collections.ObservableList;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.*;

public class FileStorage {
    private final String baseDir;
    private final String expensesFile;
    private final String categoriesFile;
    private final String incomeFile;
    private final List<String> parseWarnings = new ArrayList<>();
    private int lastLoadTotalLines = 0;
    private int lastLoadFailedLines = 0;

    public static final class LoadStats {
        public final int totalLines;
        public final int failedLines;
        public LoadStats(int totalLines, int failedLines) {
            this.totalLines = totalLines;
            this.failedLines = failedLines;
        }
        public boolean isSevere() {
            return failedLines > 0 && totalLines > 0 && failedLines * 20 >= totalLines; // >=5%
        }
    }

    @FunctionalInterface
    interface IOConsumer<T> {
        void accept(T t) throws IOException;
    }

    public FileStorage() {
        this(System.getProperty("user.home") + File.separator + ".expenseTracker");
    }

    FileStorage(String baseDir) {
        this.baseDir = baseDir;
        this.expensesFile = baseDir + File.separator + "expenses.txt";
        this.categoriesFile = baseDir + File.separator + "categories.txt";
        this.incomeFile = baseDir + File.separator + "incomes.txt";
        File dir = new File(baseDir);
        if (!dir.exists()) {
            dir.mkdirs();
        }
    }

    /** Returns and clears any warnings accumulated during the last load operations. */
    public List<String> drainParseWarnings() {
        List<String> warnings = new ArrayList<>(parseWarnings);
        parseWarnings.clear();
        return warnings;
    }

    /** Stats for the most recent loadExpenses() call. Used to detect severe corruption. */
    public LoadStats getLastExpenseLoadStats() {
        return new LoadStats(lastLoadTotalLines, lastLoadFailedLines);
    }

    private void addParseWarning(String message) {
        System.err.println(message);
        parseWarnings.add(message);
    }

    private void atomicWrite(Path target, IOConsumer<PrintWriter> writer) throws IOException {
        Path tmp = Files.createTempFile(target.getParent(), target.getFileName().toString(), ".tmp");
        try {
            try (PrintWriter out = new PrintWriter(Files.newBufferedWriter(tmp))) {
                writer.accept(out);
            }
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            try { Files.deleteIfExists(tmp); } catch (IOException ignored) {}
            throw e;
        }
    }

    public void saveExpenses(List<Expense> expenses) throws IOException {
        rotateBackups(expensesFile, 5);
        atomicWrite(Path.of(expensesFile), out -> {
            for (Expense expense : expenses) {
                if (expense instanceof RecurringExpense recurringExpense) {
                    String excludedFlag = expense.isExcluded() ? ",EXCLUDED" : "";
                    String incomeFlag = expense.isIncome() ? ",INCOME" : "";
                    String refundFlag = expense.isRefund() ? ",REFUND" : "";
                    String tagsFlag = expense.getTags().isEmpty() ? "" : ",TAGS:" + String.join("|", expense.getTags());
                    String currencyFlag = expense.getCurrency() != null ? ",CUR:" + expense.getCurrency() : "";
                    String receiptFlag = expense.getReceiptPath() != null ? ",RCPT:" + escapeCsv(expense.getReceiptPath()) : "";
                    out.println(expense.getAmount() + "," +
                            escapeCsv(expense.getCategory()) + "," +
                            expense.getDate() + "," +
                            escapeCsv(expense.getDescription()) + "," +
                            "RECURRING," +
                            recurringExpense.getFrequency() + "," +
                            (recurringExpense.getEndDate() != null ? recurringExpense.getEndDate() : "") +
                            excludedFlag + incomeFlag + refundFlag + tagsFlag + currencyFlag + receiptFlag);
                } else {
                    String importId = expense.getImportId() != null ? expense.getImportId() : "";
                    String excludedFlag = expense.isExcluded() ? ",EXCLUDED" : "";
                    String incomeFlag = expense.isIncome() ? ",INCOME" : "";
                    String refundFlag = expense.isRefund() ? ",REFUND" : "";
                    String tagsFlag = expense.getTags().isEmpty() ? "" : ",TAGS:" + String.join("|", expense.getTags());
                    String currencyFlag = expense.getCurrency() != null ? ",CUR:" + expense.getCurrency() : "";
                    String receiptFlag = expense.getReceiptPath() != null ? ",RCPT:" + escapeCsv(expense.getReceiptPath()) : "";
                    out.println(expense.getAmount() + "," +
                            escapeCsv(expense.getCategory()) + "," +
                            expense.getDate() + "," +
                            escapeCsv(expense.getDescription()) + "," +
                            "REGULAR," + importId + excludedFlag + incomeFlag + refundFlag + tagsFlag + currencyFlag + receiptFlag);
                }
            }
        });
    }

    public boolean expensesFileExists() {
        return new File(expensesFile).exists();
    }

    public List<Expense> loadExpenses() throws IOException {
        List<Expense> expenses = new ArrayList<>();
        lastLoadTotalLines = 0;
        lastLoadFailedLines = 0;
        File file = new File(expensesFile);
        if (!file.exists()) {
            return expenses;
        }
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line;
            int lineNumber = 0;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                if (line.trim().isEmpty()) continue;
                lastLoadTotalLines++;
                try {
                    String[] parts = splitCsv(line);
                    if (parts.length >= 5) {
                        double amount = Double.parseDouble(parts[0]);
                        if (amount <= 0) {
                            lastLoadFailedLines++;
                            addParseWarning("Invalid amount at line " + lineNumber + ": " + line);
                            continue;
                        }
                        String category = parts[1];
                        LocalDate date = LocalDate.parse(parts[2]);
                        String description = parts[3];
                        String type = parts[4];

                        if ("RECURRING".equals(type) && parts.length >= 7) {
                            RecurrenceType frequency = RecurrenceType.valueOf(parts[5]);
                            LocalDate endDate = parts[6].isEmpty() ? null : LocalDate.parse(parts[6]);
                            RecurringExpense rec = new RecurringExpense(amount, category, date, description, frequency, endDate);
                            for (int i = 7; i < parts.length; i++) {
                                if ("EXCLUDED".equals(parts[i])) rec.setExcluded(true);
                                else if ("INCOME".equals(parts[i])) rec.setIncome(true);
                                else if ("REFUND".equals(parts[i])) rec.setRefund(true);
                                else if (parts[i].startsWith("TAGS:")) parseTags(rec, parts[i]);
                                else if (parts[i].startsWith("CUR:")) rec.setCurrency(parts[i].substring(4));
                                else if (parts[i].startsWith("RCPT:")) rec.setReceiptPath(parts[i].substring(5));
                            }
                            expenses.add(rec);
                        } else if ("REGULAR".equals(type)) {
                            Expense exp = new Expense(amount, category, date, description);
                            if (parts.length >= 6 && !parts[5].isEmpty()) {
                                exp.setImportId(parts[5]);
                            }
                            for (int i = 6; i < parts.length; i++) {
                                if ("EXCLUDED".equals(parts[i])) exp.setExcluded(true);
                                else if ("INCOME".equals(parts[i])) exp.setIncome(true);
                                else if ("REFUND".equals(parts[i])) exp.setRefund(true);
                                else if (parts[i].startsWith("TAGS:")) parseTags(exp, parts[i]);
                                else if (parts[i].startsWith("CUR:")) exp.setCurrency(parts[i].substring(4));
                                else if (parts[i].startsWith("RCPT:")) exp.setReceiptPath(parts[i].substring(5));
                            }
                            expenses.add(exp);
                        } else {
                            lastLoadFailedLines++;
                            addParseWarning("Unknown expense type at line " + lineNumber + ": " + line);
                        }
                    } else {
                        lastLoadFailedLines++;
                        addParseWarning("Malformed line at " + lineNumber + ": " + line);
                    }
                } catch (Exception e) {
                    lastLoadFailedLines++;
                    addParseWarning("Error parsing expense line " + lineNumber + ": " + e.getMessage());
                }
            }
        }
        return expenses;
    }

    /**
     * One-time migration: if expenses.txt is missing but expenses.xlsx exists,
     * load from Excel and save to text. Returns true if migration occurred.
     */
    public boolean migrateFromExcelIfNeeded() {
        File txtFile = new File(expensesFile);
        File xlsxFile = new File(baseDir + File.separator + "expenses.xlsx");
        if (!txtFile.exists() && xlsxFile.exists()) {
            try {
                List<Expense> expenses = loadExpensesFromExcel(xlsxFile);
                saveExpenses(expenses);
                System.out.println("Migrated " + expenses.size() + " expenses from Excel to text format");
                return true;
            } catch (Exception e) {
                addParseWarning("Excel migration failed: " + e.getMessage());
            }
        }
        return false;
    }

    private List<Expense> loadExpensesFromExcel(File file) throws IOException {
        List<Expense> expenses = new ArrayList<>();
        try (org.apache.poi.ss.usermodel.Workbook workbook = org.apache.poi.ss.usermodel.WorkbookFactory.create(file)) {
            org.apache.poi.ss.usermodel.Sheet sheet = workbook.getSheetAt(0);
            for (org.apache.poi.ss.usermodel.Row row : sheet) {
                if (row.getRowNum() == 0) continue;
                try {
                    double amount = row.getCell(0).getNumericCellValue();
                    String category = row.getCell(1).getStringCellValue();
                    LocalDate date = LocalDate.parse(row.getCell(2).getStringCellValue());
                    String description = row.getCell(3) != null ? row.getCell(3).getStringCellValue() : "";
                    boolean isRecurring = row.getCell(4) != null && row.getCell(4).getBooleanCellValue();
                    if (isRecurring) {
                        RecurrenceType frequency = RecurrenceType.valueOf(row.getCell(5).getStringCellValue());
                        String endDateStr = row.getCell(6) != null ? row.getCell(6).getStringCellValue() : "";
                        LocalDate endDate = endDateStr.isEmpty() ? null : LocalDate.parse(endDateStr);
                        expenses.add(new RecurringExpense(amount, category, date, description, frequency, endDate));
                    } else {
                        Expense exp = new Expense(amount, category, date, description);
                        org.apache.poi.ss.usermodel.Cell importIdCell = row.getCell(7);
                        if (importIdCell != null && !importIdCell.getStringCellValue().isEmpty()) {
                            exp.setImportId(importIdCell.getStringCellValue());
                        }
                        expenses.add(exp);
                    }
                } catch (Exception e) {
                    addParseWarning("Error parsing Excel row " + row.getRowNum() + ": " + e.getMessage());
                }
            }
        }
        return expenses;
    }

    private void rotateBackups(String filePath, int maxBackups) throws IOException {
        File file = new File(filePath);
        if (!file.exists()) return;
        // Delete oldest backup
        File oldest = new File(filePath + "." + maxBackups);
        if (oldest.exists()) {
            Files.deleteIfExists(oldest.toPath());
        }
        // Rotate existing backups
        for (int i = maxBackups - 1; i >= 1; i--) {
            File from = new File(filePath + "." + i);
            Path toPath = new File(filePath + "." + (i + 1)).toPath();
            if (from.exists()) {
                Files.move(from.toPath(), toPath, StandardCopyOption.REPLACE_EXISTING);
            }
        }
        // Copy current to .1 — let IOException propagate
        Files.copy(file.toPath(), new File(filePath + ".1").toPath(),
            StandardCopyOption.REPLACE_EXISTING);
    }

    public void saveCategories(ObservableList<String> categories) throws IOException {
        atomicWrite(Path.of(categoriesFile), out -> {
            for (String category : categories) {
                out.println(escapeCsv(category));
            }
        });
    }

    public List<String> loadCategories() throws IOException {
        List<String> categories = new ArrayList<>();
        File file = new File(categoriesFile);
        if (!file.exists()) {
            return categories;
        }
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.trim().isEmpty()) {
                    categories.add(unescapeCsv(line.trim()));
                }
            }
        }
        return categories;
    }

    public void saveIncomes(Map<YearMonth, Double> incomes) throws IOException {
        atomicWrite(Path.of(incomeFile), out -> {
            for (Map.Entry<YearMonth, Double> entry : incomes.entrySet()) {
                out.println(entry.getKey() + "," + entry.getValue());
            }
        });
    }

    public Map<YearMonth, Double> loadIncomes() throws IOException {
        Map<YearMonth, Double> incomes = new HashMap<>();
        File file = new File(incomeFile);
        if (!file.exists()) {
            return incomes;
        }
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line;
            int lineNumber = 0;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                try {
                    String[] parts = splitCsv(line);
                    if (parts.length == 2) {
                        YearMonth yearMonth = YearMonth.parse(parts[0]);
                        double income = Double.parseDouble(parts[1]);
                        if (income < 0) {
                            addParseWarning("Invalid income at line " + lineNumber + ": " + line);
                            continue;
                        }
                        incomes.put(yearMonth, income);
                    } else {
                        addParseWarning("Malformed income line at " + lineNumber + ": " + line);
                    }
                } catch (Exception e) {
                    addParseWarning("Error parsing income line " + lineNumber + ": " + e.getMessage());
                }
            }
        }
        return incomes;
    }

    public void saveDismissedAnomalies(Set<String> keys) throws IOException {
        atomicWrite(Path.of(baseDir + File.separator + "dismissed_anomalies.txt"), out -> {
            for (String key : keys) {
                out.println(key);
            }
        });
    }

    public Set<String> loadDismissedAnomalies() throws IOException {
        Set<String> keys = new HashSet<>();
        File file = new File(baseDir + File.separator + "dismissed_anomalies.txt");
        if (!file.exists()) return keys;
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.trim().isEmpty()) keys.add(line.trim());
            }
        }
        return keys;
    }

    public void saveGoals(List<SavingsGoal> goals) throws IOException {
        atomicWrite(Path.of(baseDir + File.separator + "goals.txt"), out -> {
            for (SavingsGoal g : goals) {
                out.println(escapeCsv(g.getId()) + "," +
                    escapeCsv(g.getName()) + "," +
                    g.getTargetAmount() + "," +
                    (g.getDeadline() != null ? g.getDeadline() : "") + "," +
                    g.getMonthlyTarget() + "," +
                    g.getCreatedDate());
            }
        });
    }

    public List<SavingsGoal> loadGoals() throws IOException {
        List<SavingsGoal> goals = new ArrayList<>();
        File file = new File(baseDir + File.separator + "goals.txt");
        if (!file.exists()) return goals;
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line;
            int lineNumber = 0;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                try {
                    String[] parts = splitCsv(line);
                    if (parts.length >= 6) {
                        String id = parts[0];
                        String name = parts[1];
                        double targetAmount = Double.parseDouble(parts[2]);
                        LocalDate deadline = parts[3].isEmpty() ? null : LocalDate.parse(parts[3]);
                        double monthlyTarget = Double.parseDouble(parts[4]);
                        LocalDate createdDate = LocalDate.parse(parts[5]);
                        goals.add(new SavingsGoal(id, name, targetAmount, deadline, monthlyTarget, createdDate));
                    }
                } catch (Exception e) {
                    addParseWarning("Error parsing goal line " + lineNumber + ": " + e.getMessage());
                }
            }
        }
        return goals;
    }

    public void saveGoalContributions(List<GoalContribution> contributions) throws IOException {
        atomicWrite(Path.of(baseDir + File.separator + "goal_contributions.txt"), out -> {
            for (GoalContribution c : contributions) {
                out.println(escapeCsv(c.getGoalId()) + "," +
                    c.getAmount() + "," +
                    c.getDate() + "," +
                    escapeCsv(c.getNote() != null ? c.getNote() : ""));
            }
        });
    }

    public List<GoalContribution> loadGoalContributions() throws IOException {
        List<GoalContribution> contributions = new ArrayList<>();
        File file = new File(baseDir + File.separator + "goal_contributions.txt");
        if (!file.exists()) return contributions;
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.trim().isEmpty()) continue;
                try {
                    String[] parts = splitCsv(line);
                    if (parts.length >= 3) {
                        String goalId = parts[0];
                        double amount = Double.parseDouble(parts[1]);
                        LocalDate date = LocalDate.parse(parts[2]);
                        String note = parts.length >= 4 ? parts[3] : "";
                        contributions.add(new GoalContribution(goalId, amount, date, note));
                    }
                } catch (Exception e) {
                    System.err.println("Error parsing goal contribution: " + e.getMessage());
                }
            }
        }
        return contributions;
    }

    private void parseTags(Expense expense, String tagsField) {
        String tagsPart = tagsField.substring("TAGS:".length());
        if (!tagsPart.isEmpty()) {
            for (String tag : tagsPart.split("\\|")) {
                if (!tag.trim().isEmpty()) expense.addTag(tag.trim());
            }
        }
    }

    public void saveTags(List<String> tags) throws IOException {
        atomicWrite(Path.of(baseDir + File.separator + "tags.txt"), out -> {
            for (String tag : tags) {
                out.println(escapeCsv(tag));
            }
        });
    }

    public List<String> loadTags() throws IOException {
        List<String> tags = new ArrayList<>();
        File file = new File(baseDir + File.separator + "tags.txt");
        if (!file.exists()) return tags;
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.trim().isEmpty()) {
                    tags.add(unescapeCsv(line.trim()));
                }
            }
        }
        return tags;
    }

    private String escapeCsv(String value) {
        if (value == null) return "";
        if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }

    private String unescapeCsv(String value) {
        if (value == null) return "";
        if (value.startsWith("\"") && value.endsWith("\"")) {
            return value.substring(1, value.length() - 1).replace("\"\"", "\"");
        }
        return value;
    }

    private String[] splitCsv(String line) {
        List<String> parts = new ArrayList<>();
        boolean inQuotes = false;
        StringBuilder field = new StringBuilder();

        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                if (inQuotes && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    field.append('"');
                    i++;
                } else {
                    inQuotes = !inQuotes;
                }
            } else if (c == ',' && !inQuotes) {
                parts.add(field.toString());
                field = new StringBuilder();
            } else {
                field.append(c);
            }
        }
        parts.add(field.toString());
        return parts.toArray(new String[0]);
    }

    private Map<String, String> loadSettings() {
        Map<String, String> settings = new HashMap<>();
        File file = new File(baseDir + File.separator + "settings.txt");
        if (!file.exists()) return settings;
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = reader.readLine()) != null) {
                int eq = line.indexOf('=');
                if (eq > 0) {
                    settings.put(line.substring(0, eq).trim(), line.substring(eq + 1).trim());
                }
            }
        } catch (IOException e) {
            System.err.println("Error loading settings: " + e.getMessage());
        }
        return settings;
    }

    private void saveSetting(String key, String value) throws IOException {
        Map<String, String> settings = loadSettings();
        settings.put(key, value);
        atomicWrite(Path.of(baseDir + File.separator + "settings.txt"), out -> {
            for (Map.Entry<String, String> entry : settings.entrySet()) {
                out.println(entry.getKey() + "=" + entry.getValue());
            }
        });
    }

    public void saveCurrencySymbol(String symbol) throws IOException {
        saveSetting("currency", symbol);
    }

    public String loadCurrencySymbol() {
        return loadSettings().getOrDefault("currency", "R");
    }

    public void saveRecurringIncome(double amount) throws IOException {
        saveSetting("recurringIncome", String.valueOf(amount));
    }

    public double loadRecurringIncome() {
        try {
            return Double.parseDouble(loadSettings().getOrDefault("recurringIncome", "0"));
        } catch (NumberFormatException e) {
            return 0.0;
        }
    }

    public void saveUIState(Map<String, String> uiState) throws IOException {
        atomicWrite(Path.of(baseDir + File.separator + "ui_state.txt"), out -> {
            for (Map.Entry<String, String> entry : uiState.entrySet()) {
                out.println(entry.getKey() + "=" + entry.getValue());
            }
        });
    }

    public Map<String, String> loadUIState() {
        Map<String, String> state = new HashMap<>();
        File file = new File(baseDir + File.separator + "ui_state.txt");
        if (!file.exists()) return state;
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = reader.readLine()) != null) {
                int eq = line.indexOf('=');
                if (eq > 0) {
                    state.put(line.substring(0, eq).trim(), line.substring(eq + 1).trim());
                }
            }
        } catch (IOException e) {
            System.err.println("Error loading UI state: " + e.getMessage());
        }
        return state;
    }

    public void saveBudgets(Map<String, Double> budgets) throws IOException {
        atomicWrite(Path.of(baseDir + File.separator + "budgets.txt"), out -> {
            for (Map.Entry<String, Double> entry : budgets.entrySet()) {
                out.println(escapeCsv(entry.getKey()) + "," + entry.getValue());
            }
        });
    }

    public Map<String, Double> loadBudgets() throws IOException {
        Map<String, Double> budgets = new HashMap<>();
        File file = new File(baseDir + File.separator + "budgets.txt");
        if (!file.exists()) {
            return budgets;
        }
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line;
            int lineNumber = 0;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                try {
                    String[] parts = splitCsv(line);
                    if (parts.length == 2) {
                        String category = parts[0];
                        double budget = Double.parseDouble(parts[1]);
                        if (budget >= 0) {
                            budgets.put(category, budget);
                        }
                    } else {
                        addParseWarning("Malformed budget line at " + lineNumber + ": " + line);
                    }
                } catch (Exception e) {
                    addParseWarning("Error parsing budget line " + lineNumber + ": " + e.getMessage());
                }
            }
        }
        return budgets;
    }

    public void saveCategorizationRules(Map<String, String> rules) throws IOException {
        atomicWrite(Path.of(baseDir + File.separator + "categorization_rules.txt"), out -> {
            for (Map.Entry<String, String> entry : rules.entrySet()) {
                out.println(escapeCsv(entry.getKey()) + "," + escapeCsv(entry.getValue()));
            }
        });
    }

    public Map<String, String> loadCategorizationRules() throws IOException {
        Map<String, String> rules = new LinkedHashMap<>();
        File file = new File(baseDir + File.separator + "categorization_rules.txt");
        if (!file.exists()) {
            return rules;
        }
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.trim().isEmpty()) {
                    String[] parts = splitCsv(line);
                    if (parts.length == 2) {
                        rules.put(parts[0], parts[1]);
                    }
                }
            }
        }
        return rules;
    }

    public void saveImportLogs(List<ImportLog> logs) throws IOException {
        atomicWrite(Path.of(baseDir + File.separator + "import_log.txt"), out -> {
            for (ImportLog log : logs) {
                out.println(escapeCsv(log.getImportId()) + "," +
                    log.getTimestamp() + "," +
                    escapeCsv(log.getSourceFile()) + "," +
                    escapeCsv(log.getSourceType()) + "," +
                    log.getItemCount());
            }
        });
    }

    public List<ImportLog> loadImportLogs() throws IOException {
        List<ImportLog> logs = new ArrayList<>();
        File file = new File(baseDir + File.separator + "import_log.txt");
        if (!file.exists()) return logs;
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.trim().isEmpty()) continue;
                try {
                    String[] parts = splitCsv(line);
                    if (parts.length >= 5) {
                        String importId = parts[0];
                        LocalDateTime timestamp = LocalDateTime.parse(parts[1]);
                        String sourceFile = parts[2];
                        String sourceType = parts[3];
                        int itemCount = Integer.parseInt(parts[4]);
                        logs.add(new ImportLog(importId, timestamp, sourceFile, sourceType, itemCount));
                    }
                } catch (Exception e) {
                    System.err.println("Error parsing import log: " + e.getMessage());
                }
            }
        }
        return logs;
    }

    // ======================== Exchange Rates ========================

    public void saveExchangeRates(String baseCurrency, Map<String, Double> rates) throws IOException {
        atomicWrite(Path.of(baseDir + File.separator + "exchange_rates.txt"), out -> {
            out.println("BASE=" + baseCurrency);
            for (Map.Entry<String, Double> entry : rates.entrySet()) {
                out.println(entry.getKey() + "," + entry.getValue());
            }
        });
    }

    public String loadBaseCurrency() {
        return loadSettings().getOrDefault("baseCurrency", "ZAR");
    }

    public void saveBaseCurrency(String code) throws IOException {
        saveSetting("baseCurrency", code);
    }

    public Map<String, Double> loadExchangeRates() throws IOException {
        Map<String, Double> rates = new LinkedHashMap<>();
        File file = new File(baseDir + File.separator + "exchange_rates.txt");
        if (!file.exists()) return rates;
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("BASE=")) continue;
                String[] parts = splitCsv(line);
                if (parts.length == 2) {
                    try {
                        rates.put(parts[0], Double.parseDouble(parts[1]));
                    } catch (NumberFormatException e) {
                        addParseWarning("Invalid exchange rate: " + line);
                    }
                }
            }
        }
        return rates;
    }

    // ======================== Receipts ========================

    public String getReceiptsDir() {
        String dir = baseDir + File.separator + "receipts";
        new File(dir).mkdirs();
        return dir;
    }

    // ======================== Debts ========================

    public void saveDebts(List<Debt> debts) throws IOException {
        atomicWrite(Path.of(baseDir + File.separator + "debts.txt"), out -> {
            for (Debt d : debts) {
                out.println(escapeCsv(d.getId()) + "," +
                    escapeCsv(d.getName()) + "," +
                    d.getPrincipal() + "," +
                    d.getAnnualRate() + "," +
                    d.getTermMonths() + "," +
                    d.getStartDate() + "," +
                    d.getPaymentFrequency() + "," +
                    d.getMonthlyPayment() + "," +
                    (d.getCurrency() != null ? d.getCurrency() : ""));
            }
        });
    }

    public List<Debt> loadDebts() throws IOException {
        List<Debt> debts = new ArrayList<>();
        File file = new File(baseDir + File.separator + "debts.txt");
        if (!file.exists()) return debts;
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line;
            int lineNumber = 0;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                if (line.trim().isEmpty()) continue;
                try {
                    String[] parts = splitCsv(line);
                    if (parts.length >= 8) {
                        String id = parts[0];
                        String name = parts[1];
                        double principal = Double.parseDouble(parts[2]);
                        double annualRate = Double.parseDouble(parts[3]);
                        int termMonths = Integer.parseInt(parts[4]);
                        LocalDate startDate = LocalDate.parse(parts[5]);
                        String paymentFrequency = parts[6];
                        double monthlyPayment = Double.parseDouble(parts[7]);
                        String currency = parts.length >= 9 && !parts[8].isEmpty() ? parts[8] : null;
                        debts.add(new Debt(id, name, principal, annualRate, termMonths, startDate,
                            paymentFrequency, monthlyPayment, currency));
                    }
                } catch (Exception e) {
                    addParseWarning("Error parsing debt line " + lineNumber + ": " + e.getMessage());
                }
            }
        }
        return debts;
    }

    public void saveDebtPayments(List<DebtPayment> payments) throws IOException {
        atomicWrite(Path.of(baseDir + File.separator + "debt_payments.txt"), out -> {
            for (DebtPayment p : payments) {
                out.println(escapeCsv(p.getDebtId()) + "," +
                    p.getAmount() + "," +
                    p.getDate() + "," +
                    escapeCsv(p.getNote() != null ? p.getNote() : ""));
            }
        });
    }

    public List<DebtPayment> loadDebtPayments() throws IOException {
        List<DebtPayment> payments = new ArrayList<>();
        File file = new File(baseDir + File.separator + "debt_payments.txt");
        if (!file.exists()) return payments;
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.trim().isEmpty()) continue;
                try {
                    String[] parts = splitCsv(line);
                    if (parts.length >= 3) {
                        String debtId = parts[0];
                        double amount = Double.parseDouble(parts[1]);
                        LocalDate date = LocalDate.parse(parts[2]);
                        String note = parts.length >= 4 ? parts[3] : "";
                        payments.add(new DebtPayment(debtId, amount, date, note));
                    }
                } catch (Exception e) {
                    System.err.println("Error parsing debt payment: " + e.getMessage());
                }
            }
        }
        return payments;
    }
}
