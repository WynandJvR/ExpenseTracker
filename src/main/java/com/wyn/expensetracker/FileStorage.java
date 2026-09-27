package com.wyn.expensetracker;

import javafx.collections.ObservableList;

import java.io.*;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.*;

public class FileStorage {
    private final String baseDir;
    private final String expensesFile;
    private final String categoriesFile;
    private final String incomeFile;
    private final List<String> parseWarnings = new ArrayList<>();
    private int lastLoadTotalLines = 0;
    private int lastLoadFailedLines = 0;
    private boolean lastLoadHadLegacyRecurring = false;

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

    /**
     * Opens a text file for reading. All files are written as UTF-8, so UTF-8 is tried
     * first (strictly). Files written by older versions with the platform charset
     * (e.g. Cp1252 on Windows) are not valid UTF-8; those fall back to the platform
     * charset (or ISO-8859-1 if the platform default is itself UTF-8) so legacy
     * non-ASCII text is still read correctly instead of being mangled or rejected.
     * A leading UTF-8 BOM is stripped.
     */
    static BufferedReader openReader(File file) throws IOException {
        byte[] bytes = Files.readAllBytes(file.toPath());
        String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString();
        } catch (CharacterCodingException e) {
            Charset fallback = Charset.defaultCharset();
            if (StandardCharsets.UTF_8.equals(fallback)) fallback = StandardCharsets.ISO_8859_1;
            text = new String(bytes, fallback);
        }
        if (!text.isEmpty() && text.charAt(0) == '﻿') text = text.substring(1);
        return new BufferedReader(new StringReader(text));
    }

    /** Collapses CR, LF and CRLF into a single space so a value can never split a line-based record. */
    static String stripLineBreaks(String value) {
        if (value == null) return null;
        if (value.indexOf('\n') < 0 && value.indexOf('\r') < 0) return value;
        return value.replaceAll("\r\n|\r|\n", " ");
    }

    // ======================== Save-blocking after an unreadable load ========================

    private boolean expenseSavesBlocked = false;
    private String expenseSavesBlockedReason = null;
    private String lastCorruptCopyPath = null;
    private boolean expensesQuarantined = false;

    /**
     * True when the ledger in memory may be missing rows that were on disk: the last load
     * threw, found badly corrupted data, or the file was set aside. Callers that reconcile
     * other records against the ledger (e.g. import history) must not treat missing rows
     * as "deleted by the user" in that case.
     */
    public boolean wasLastExpenseLoadLossy() {
        return expenseSavesBlocked || expensesQuarantined || lastCorruptCopyPath != null
            || getLastExpenseLoadStats().failedLines > 0;
    }

    /**
     * True when the last {@link #loadExpenses()} threw, meaning the in-memory ledger is
     * not a faithful copy of expenses.txt. Saving now would replace the user's real data
     * with an empty/partial list, so {@link #saveExpenses} refuses until the caller
     * resolves it via {@link #quarantineUnreadableExpenses()} or {@link #acknowledgeLoadFailure()}.
     */
    public boolean isExpenseSaveBlocked() {
        return expenseSavesBlocked;
    }

    /**
     * The user has been told about the load failure and accepts that the next save will
     * overwrite expenses.txt (a timestamped copy was already made, if possible).
     */
    public void acknowledgeLoadFailure() {
        expenseSavesBlocked = false;
        expenseSavesBlockedReason = null;
    }

    /**
     * Moves an unreadable expenses.txt aside to {@code expenses.unreadable-<timestamp>.txt}
     * so the app can start with a fresh ledger without destroying the original, and
     * unblocks saving. Returns the path of the moved file, or null if there was nothing to move.
     */
    public String quarantineUnreadableExpenses() throws IOException {
        File file = new File(expensesFile);
        if (!file.exists()) {
            acknowledgeLoadFailure();
            return null;
        }
        File target = uniqueTimestampedFile("expenses.unreadable-");
        Files.move(file.toPath(), target.toPath());
        expensesQuarantined = true;
        acknowledgeLoadFailure();
        return target.getAbsolutePath();
    }

    /**
     * Path of the timestamped, never-rotated copy of expenses.txt made during the last
     * load because it was badly corrupted (or could not be read), or null if none was made.
     */
    public String getLastCorruptCopyPath() {
        return lastCorruptCopyPath;
    }

    private File uniqueTimestampedFile(String prefix) {
        String ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        File candidate = new File(baseDir, prefix + ts + ".txt");
        int n = 2;
        while (candidate.exists()) {
            candidate = new File(baseDir, prefix + ts + "-" + n++ + ".txt");
        }
        return candidate;
    }

    /** Copies expenses.txt to a timestamped file that backup rotation never touches. */
    private void preserveCorruptCopy() {
        File file = new File(expensesFile);
        if (!file.exists()) return;
        try {
            File target = uniqueTimestampedFile("expenses.corrupt-");
            Files.copy(file.toPath(), target.toPath());
            lastCorruptCopyPath = target.getAbsolutePath();
            addParseWarning("A copy of the original expense file was saved to " + lastCorruptCopyPath);
        } catch (IOException e) {
            addParseWarning("Could not save a copy of the corrupted expense file: " + e.getMessage());
        }
    }

    private void atomicWrite(Path target, IOConsumer<PrintWriter> writer) throws IOException {
        Path tmp = Files.createTempFile(target.getParent(), target.getFileName().toString(), ".tmp");
        try {
            try (PrintWriter out = new PrintWriter(Files.newBufferedWriter(tmp, StandardCharsets.UTF_8))) {
                writer.accept(out);
            }
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            try { Files.deleteIfExists(tmp); } catch (IOException ignored) {}
            throw e;
        }
    }

    public void saveExpenses(List<Expense> expenses) throws IOException {
        if (expenseSavesBlocked) {
            throw new IOException("Saving is disabled because expenses.txt could not be read ("
                + expenseSavesBlockedReason + "). Your original file has not been touched; "
                + "restart the app to recover it.");
        }
        rotateBackups(expensesFile, 5);
        atomicWrite(Path.of(expensesFile), out -> {
            for (Expense expense : expenses) {
                if (expense instanceof RecurringExpense recurringExpense) {
                    String excludedFlag = expense.isExcluded() ? ",EXCLUDED" : "";
                    String incomeFlag = expense.isIncome() ? ",INCOME" : "";
                    String refundFlag = expense.isRefund() ? ",REFUND" : "";
                    String tagsFlag = expense.getTags().isEmpty() ? "" : "," + escapeCsv("TAGS:" + String.join("|", expense.getTags()));
                    String currencyFlag = expense.getCurrency() != null ? ",CUR:" + expense.getCurrency() : "";
                    String receiptFlag = expense.getReceiptPath() != null ? ",RCPT:" + escapeCsv(expense.getReceiptPath()) : "";
                    String idFlag = ",ID:" + recurringExpense.getId();
                    out.println(expense.getAmount() + "," +
                            escapeCsv(expense.getCategory()) + "," +
                            expense.getDate() + "," +
                            escapeCsv(expense.getDescription()) + "," +
                            "RECURRING," +
                            recurringExpense.getFrequency() + "," +
                            (recurringExpense.getEndDate() != null ? recurringExpense.getEndDate() : "") +
                            excludedFlag + incomeFlag + refundFlag + tagsFlag + currencyFlag + receiptFlag + idFlag);
                } else {
                    String importId = expense.getImportId() != null ? expense.getImportId() : "";
                    String excludedFlag = expense.isExcluded() ? ",EXCLUDED" : "";
                    String incomeFlag = expense.isIncome() ? ",INCOME" : "";
                    String refundFlag = expense.isRefund() ? ",REFUND" : "";
                    String tagsFlag = expense.getTags().isEmpty() ? "" : "," + escapeCsv("TAGS:" + String.join("|", expense.getTags()));
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

    /**
     * True if the most recent load parsed a recurring template with no persisted id
     * (i.e. data written before per-occurrence overrides existed). Callers should
     * re-save once so the minted ids stick — otherwise each launch re-mints a random
     * id and any overrides keyed to the old one are silently pruned.
     */
    public boolean hadLegacyRecurringOnLoad() {
        return lastLoadHadLegacyRecurring;
    }

    public List<Expense> loadExpenses() throws IOException {
        lastLoadTotalLines = 0;
        lastLoadFailedLines = 0;
        lastLoadHadLegacyRecurring = false;
        lastCorruptCopyPath = null;
        expenseSavesBlocked = false;
        expenseSavesBlockedReason = null;
        File file = new File(expensesFile);
        if (!file.exists()) {
            return new ArrayList<>();
        }
        List<Expense> expenses;
        try {
            expenses = readExpenses(file);
        } catch (IOException | RuntimeException e) {
            // The caller will end up with an empty (or stale) ledger. Keep a copy of the
            // original and refuse to save over it until someone deals with the failure.
            expenseSavesBlocked = true;
            expenseSavesBlockedReason = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            preserveCorruptCopy();
            throw e;
        }
        if (getLastExpenseLoadStats().isSevere()) {
            preserveCorruptCopy();
        }
        return expenses;
    }

    private List<Expense> readExpenses(File file) throws IOException {
        List<Expense> expenses = new ArrayList<>();
        try (BufferedReader reader = openReader(file)) {
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
                        if (!Double.isFinite(amount) || amount <= 0) {
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
                            boolean hasIdFlag = false;
                            for (int i = 7; i < parts.length; i++) {
                                if ("EXCLUDED".equals(parts[i])) rec.setExcluded(true);
                                else if ("INCOME".equals(parts[i])) rec.setIncome(true);
                                else if ("REFUND".equals(parts[i])) rec.setRefund(true);
                                else if (parts[i].startsWith("TAGS:")) parseTags(rec, parts[i]);
                                else if (parts[i].startsWith("CUR:")) rec.setCurrency(parts[i].substring(4));
                                else if (parts[i].startsWith("RCPT:")) rec.setReceiptPath(parts[i].substring(5));
                                else if (parts[i].startsWith("ID:")) { rec.setId(parts[i].substring(3)); hasIdFlag = true; }
                            }
                            if (!hasIdFlag) lastLoadHadLegacyRecurring = true;
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
        try (BufferedReader reader = openReader(file)) {
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
        try (BufferedReader reader = openReader(file)) {
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
        try (BufferedReader reader = openReader(file)) {
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
        try (BufferedReader reader = openReader(file)) {
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
        try (BufferedReader reader = openReader(file)) {
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
        try (BufferedReader reader = openReader(file)) {
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
        // The file format is one record per line; an embedded CR/LF would split the row.
        value = stripLineBreaks(value);
        if (value.contains(",") || value.contains("\"")) {
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
        try (BufferedReader reader = openReader(file)) {
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
        try (BufferedReader reader = openReader(file)) {
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
        try (BufferedReader reader = openReader(file)) {
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
        try (BufferedReader reader = openReader(file)) {
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
        try (BufferedReader reader = openReader(file)) {
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

    /**
     * The base currency recorded in exchange_rates.txt (the currency the stored rates are
     * relative to), or null if the file is missing or has no BASE= line.
     */
    public String loadExchangeRatesBase() throws IOException {
        File file = new File(baseDir + File.separator + "exchange_rates.txt");
        if (!file.exists()) return null;
        try (BufferedReader reader = openReader(file)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("BASE=")) {
                    String base = line.substring(5).trim();
                    return base.isEmpty() ? null : base;
                }
            }
        }
        return null;
    }

    /**
     * Loads stored exchange rates, expressed relative to the configured base currency
     * ({@link #loadBaseCurrency()}). If the file's BASE= line names a different currency
     * (e.g. the base was changed by an older version that didn't rewrite the rates),
     * the rates are converted to the configured base; if that's impossible (no rate for
     * the configured base) they are dropped with a warning rather than silently misapplied.
     */
    public Map<String, Double> loadExchangeRates() throws IOException {
        Map<String, Double> rates = loadRawExchangeRates();
        if (rates.isEmpty()) return rates;
        String fileBase = loadExchangeRatesBase();
        String base = loadBaseCurrency();
        if (fileBase == null || fileBase.equals(base)) return rates;
        Map<String, Double> converted = CurrencyManager.convertRates(rates, fileBase, base);
        if (converted == null) {
            addParseWarning("Stored exchange rates are relative to " + fileBase + " but the base currency is "
                + base + ", and there is no " + base + " rate to convert them. Please re-enter your exchange rates.");
            return new LinkedHashMap<>();
        }
        return converted;
    }

    private Map<String, Double> loadRawExchangeRates() throws IOException {
        Map<String, Double> rates = new LinkedHashMap<>();
        File file = new File(baseDir + File.separator + "exchange_rates.txt");
        if (!file.exists()) return rates;
        try (BufferedReader reader = openReader(file)) {
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

    // ======================== Recurring occurrence overrides ========================

    public void saveRecurringOverrides(List<OccurrenceOverride> overrides) throws IOException {
        atomicWrite(Path.of(baseDir + File.separator + "recurring_overrides.txt"), out -> {
            for (OccurrenceOverride o : overrides) {
                if (o == null || o.isEmpty()) continue;
                String kind = o.isSkipped() ? "SKIP" : "MODIFY";
                out.println(escapeCsv(o.getTemplateId()) + "," +
                    o.getDate() + "," +
                    kind + "," +
                    (o.getAmount() != null ? o.getAmount() : "") + "," +
                    escapeCsv(o.getCategory() != null ? o.getCategory() : "") + "," +
                    escapeCsv(o.getDescription() != null ? o.getDescription() : ""));
            }
        });
    }

    public List<OccurrenceOverride> loadRecurringOverrides() throws IOException {
        List<OccurrenceOverride> result = new ArrayList<>();
        File file = new File(baseDir + File.separator + "recurring_overrides.txt");
        if (!file.exists()) return result;
        try (BufferedReader reader = openReader(file)) {
            String line;
            int lineNumber = 0;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                if (line.trim().isEmpty()) continue;
                try {
                    String[] parts = splitCsv(line);
                    if (parts.length >= 3) {
                        OccurrenceOverride o = new OccurrenceOverride(parts[0], LocalDate.parse(parts[1]));
                        if ("SKIP".equals(parts[2])) {
                            o.setSkipped(true);
                        } else {
                            if (parts.length >= 4 && !parts[3].isEmpty()) o.setAmount(Double.parseDouble(parts[3]));
                            if (parts.length >= 5 && !parts[4].isEmpty()) o.setCategory(parts[4]);
                            if (parts.length >= 6 && !parts[5].isEmpty()) o.setDescription(parts[5]);
                        }
                        if (!o.isEmpty()) result.add(o);
                    } else {
                        addParseWarning("Malformed recurring override at line " + lineNumber + ": " + line);
                    }
                } catch (Exception e) {
                    addParseWarning("Error parsing recurring override line " + lineNumber + ": " + e.getMessage());
                }
            }
        }
        return result;
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
                    (d.getCurrency() != null ? d.getCurrency() : "") + "," +
                    escapeCsv(d.getPaymentKeyword()));
            }
        });
    }

    public List<Debt> loadDebts() throws IOException {
        List<Debt> debts = new ArrayList<>();
        File file = new File(baseDir + File.separator + "debts.txt");
        if (!file.exists()) return debts;
        try (BufferedReader reader = openReader(file)) {
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
                        Debt debt = new Debt(id, name, principal, annualRate, termMonths, startDate,
                            paymentFrequency, monthlyPayment, currency);
                        // Optional trailing field (added later): payment keyword.
                        if (parts.length >= 10) debt.setPaymentKeyword(parts[9]);
                        debts.add(debt);
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
        try (BufferedReader reader = openReader(file)) {
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
