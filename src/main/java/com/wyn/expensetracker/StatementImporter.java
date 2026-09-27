package com.wyn.expensetracker;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;

/**
 * The automatic import pipeline, free of any UI so it can run in the background:
 *
 *   read file → detect format → parse → check balances → classify (spend / income /
 *   refund / own-account transfer) → categorise → drop anything already imported
 *
 * {@link #prepare(File)} does all the work for one file and never touches the ledger;
 * the caller commits the resulting expenses (on the FX thread) and then calls
 * {@link ImportRegistry#record} so the same transactions are skipped next time.
 */
public class StatementImporter {

    /** Problem text used when a CSV's columns couldn't be recognised automatically. */
    public static final String NEEDS_CSV_MAPPING = "Couldn't recognise the columns in this CSV";

    public static final Set<String> SUPPORTED_EXTENSIONS = Set.of("pdf", "csv", "ofx", "qfx", "qif", "txt");

    /** Outcome of preparing one file. */
    public static class Prepared {
        public final File file;
        public String fileHash;
        public StatementParseResult statement;
        /** Items that are new and will be imported. */
        public final List<ImportItem> newItems = new ArrayList<>();
        public int alreadyImported;
        public int duplicatesOfManualEntries;
        /** Non-null when the file couldn't be imported; a short user-facing reason. */
        public String problem;
        /** True when the whole file had been imported before. */
        public boolean skippedWholeFile;
        /** True when the user removed this file's import earlier (the silent folder scan leaves it alone). */
        public boolean dismissed;

        Prepared(File file) { this.file = file; }

        public boolean hasWork() { return problem == null && !skippedWholeFile && !newItems.isEmpty(); }

        public long uncategorizedCount() {
            return newItems.stream()
                .filter(i -> !i.isTransfer() && TransactionClassifier.UNCATEGORIZED.equals(i.getCategory()))
                .count();
        }

        public String sourceType() {
            String n = file.getName().toLowerCase();
            return n.endsWith(".pdf") ? "PDF" : n.endsWith(".ofx") || n.endsWith(".qfx") ? "OFX"
                : n.endsWith(".qif") ? "QIF" : "CSV";
        }
    }

    private final CategorizationRules userRules;
    private final ImportRegistry.Snapshot seen;
    private final List<Expense> existingExpenses;
    // Claimed by earlier files in this same batch, so two copies (or overlapping
    // statements) dropped together are only counted once.
    private final Set<String> batchHashes = new HashSet<>();
    private final Set<String> batchFingerprints = new HashSet<>();

    /**
     * Construct on the FX thread: it copies the rules, the registry and the ledger so
     * {@link #prepare} can then run on a background thread without racing edits.
     */
    public StatementImporter(CategorizationRules userRules, ImportRegistry registry, List<Expense> existingExpenses) {
        CategorizationRules copy = new CategorizationRules();
        if (userRules != null) copy.loadFrom(new LinkedHashMap<>(userRules.getRules()));
        this.userRules = copy;
        this.seen = registry.snapshot();
        this.existingExpenses = existingExpenses != null ? new ArrayList<>(existingExpenses) : List.of();
    }

    public static boolean isSupported(File f) {
        String n = f.getName().toLowerCase();
        int dot = n.lastIndexOf('.');
        return f.isFile() && dot > 0 && SUPPORTED_EXTENSIONS.contains(n.substring(dot + 1));
    }

    /** Parses, classifies and de-duplicates one file. Safe to call off the FX thread. */
    public Prepared prepare(File file) {
        return prepare(file, null, false);
    }

    public Prepared prepare(File file, StatementParseResult preParsed) {
        return prepare(file, preParsed, false);
    }

    /**
     * As {@link #prepare(File)}, but with transactions the caller already parsed (e.g. a CSV
     * the user mapped by hand). Pass null to parse the file automatically.
     * @param silent true for the watched-folder scan, which leaves files the user removed alone
     */
    public Prepared prepare(File file, StatementParseResult preParsed, boolean silent) {
        Prepared p = new Prepared(file);
        try {
            p.fileHash = ImportRegistry.sha256(file);
            // A file imported before is only skipped outright by the folder scan. Picked by hand it
            // goes through per-transaction dedupe, so rows it shares with a removed overlapping
            // import come back while rows it still owns are counted as already imported.
            if (batchHashes.contains(p.fileHash) || (silent && seen.fileHashes.contains(p.fileHash))) {
                p.skippedWholeFile = true;
                return p;
            }
            if (silent && seen.dismissed.contains(p.fileHash)) {
                p.skippedWholeFile = true;
                p.dismissed = true;
                return p;
            }
            p.statement = preParsed != null ? preParsed : parse(file);
        } catch (Exception e) {
            p.problem = "Couldn't read the file (" + e.getMessage() + ")";
            return p;
        }
        boolean knownFile = seen.fileHashes.contains(p.fileHash);
        if (knownFile && (p.statement == null || p.statement.getItems().isEmpty())) {
            p.skippedWholeFile = true; // imported before (e.g. a hand-mapped CSV): don't ask again
            return p;
        }
        if (p.statement == null || p.statement.getItems().isEmpty()) {
            p.problem = file.getName().toLowerCase().endsWith(".csv")
                ? NEEDS_CSV_MAPPING
                : "No transactions found \u2014 is this a bank statement?";
            return p;
        }

        List<ImportItem> items = p.statement.getItems();
        for (ImportItem item : items) {
            normalizeLegacyMarkers(item);
            item.setSourceFile(file.getName());
            TransactionClassifier.classify(item, userRules);
        }
        // Key on the account number's last digits: banks rename products ("Easy Account") between statements.
        String label = Objects.toString(p.statement.getAccountLabel(), "");
        String digits = label.replaceAll("\\D", "");
        String accountKey = p.statement.getBankName() + ":" + (digits.isEmpty() ? label : digits);
        ImportRegistry.assignFingerprints(items, accountKey);

        // Existing manual (non-imported) entries that exactly match an imported line.
        List<Expense> manual = new ArrayList<>();
        for (Expense e : existingExpenses) {
            if (e.getImportId() == null && e.getRecurringId() == null) manual.add(e);
        }
        Set<Expense> claimed = Collections.newSetFromMap(new IdentityHashMap<>());
        int dismissedItems = 0;

        for (ImportItem item : items) {
            String fp = item.getFingerprint();
            if (seen.fingerprints.contains(fp) || batchFingerprints.contains(fp)) {
                p.alreadyImported++;
                continue;
            }
            // The folder scan doesn't bring back transactions of an import the user removed,
            // even from another copy of the statement (re-downloaded PDF, CSV of the same period).
            if (silent && seen.dismissedFingerprints.contains(fp)) {
                dismissedItems++;
                continue;
            }
            Expense match = findManualDuplicate(item, manual, claimed);
            if (match != null) {
                claimed.add(match);
                p.duplicatesOfManualEntries++;
                continue;
            }
            p.newItems.add(item);
        }
        if (p.newItems.isEmpty() && dismissedItems > 0) {
            p.skippedWholeFile = true;
            p.dismissed = true;
        } else if (p.newItems.isEmpty() && p.alreadyImported > 0) {
            p.skippedWholeFile = true;
        }
        batchHashes.add(p.fileHash);
        for (ImportItem i : p.newItems) batchFingerprints.add(i.getFingerprint());
        return p;
    }

    /** Detects the format from the content (not just the extension) and parses it. */
    public static StatementParseResult parse(File file) throws IOException {
        String name = file.getName().toLowerCase();
        if (name.endsWith(".pdf")) {
            String text;
            try (PDDocument doc = PDDocument.load(file)) {
                text = new PDFTextStripper().getText(doc);
            }
            return parsePdfText(text);
        }
        String text = readText(file);
        OfxStatementParser ofx = new OfxStatementParser();
        if (ofx.canParse(text)) {
            StatementParseResult r = new StatementParseResult("OFX", ofx.parse(text));
            java.util.regex.Matcher acct = java.util.regex.Pattern.compile("<ACCTID>\\s*([^<\\s]+)", java.util.regex.Pattern.CASE_INSENSITIVE).matcher(text);
            if (acct.find()) {
                String id = acct.group(1);
                r.setAccountLabel("Account \u2022\u2022" + id.substring(Math.max(0, id.length() - 4)));
            }
            java.util.regex.Matcher cur = java.util.regex.Pattern.compile("<CURDEF>\\s*([A-Za-z]{3})", java.util.regex.Pattern.CASE_INSENSITIVE).matcher(text);
            if (cur.find()) r.setCurrency(cur.group(1).toUpperCase(Locale.ROOT));
            return r;
        }
        QifStatementParser qif = new QifStatementParser();
        if (qif.canParse(text)) return new StatementParseResult("QIF", qif.parse(text));
        return CsvStatementParser.autoParse(text);
    }

    /** Tries each PDF parser; the first one that recognises the bank and finds transactions wins. */
    public static StatementParseResult parsePdfText(String text) {
        FnbPdfParser fnb = new FnbPdfParser();
        if (fnb.canParse(text)) {
            StatementParseResult r = fnb.parseStatement(text);
            if (!r.getItems().isEmpty()) return r;
        }
        GenericPdfParser generic = new GenericPdfParser();
        if (generic.canParse(text)) {
            StatementParseResult r = generic.parseStatement(text);
            if (!r.getItems().isEmpty()) return r;
        }
        return null;
    }

    /** Converts the prepared items into ledger entries tagged with {@code importId}. */
    public static List<Expense> toExpenses(Prepared p, String importId) {
        return toExpenses(p, importId, null);
    }

    /**
     * As above; when the statement is in a currency other than {@code baseCurrency}
     * (e.g. a rand FNB statement while the app's base is USD) the rows are stamped with it.
     */
    public static List<Expense> toExpenses(Prepared p, String importId, String baseCurrency) {
        String statementCurrency = p.statement != null ? p.statement.getCurrency() : null;
        boolean stamp = statementCurrency != null && baseCurrency != null && !statementCurrency.equalsIgnoreCase(baseCurrency);
        List<Expense> out = new ArrayList<>();
        for (ImportItem item : p.newItems) {
            String category = item.getCategory() == null || item.getCategory().isBlank()
                ? TransactionClassifier.UNCATEGORIZED : item.getCategory();
            Expense e = new Expense(Amounts.round2(item.getAmount()), category, item.getDate(),
                item.getDescription() == null ? "" : item.getDescription());
            e.setImportId(importId);
            if (stamp) e.setCurrency(statementCurrency.toUpperCase(Locale.ROOT));
            if (item.isTransfer()) {
                e.setExcluded(true); // own-account movement: neither spend nor income
            } else if (item.isRefund()) {
                e.setRefund(true);
            } else if (item.isIncome()) {
                e.setIncome(true);
            }
            out.add(e);
        }
        return out;
    }

    public static ImportRegistry.StatementRecord recordFor(Prepared p, String importId) {
        StatementParseResult s = p.statement;
        return new ImportRegistry.StatementRecord(importId, p.fileHash, p.file.getName(), s.getBankName(),
            s.getAccountLabel(), s.getPeriodStart(), s.getPeriodEnd(), s.getOpeningBalance(),
            s.getClosingBalance(), s.isReconciled(), p.newItems.size(), java.time.LocalDateTime.now());
    }

    /** Fingerprints of the transactions this import adds (not ones owned by earlier imports). */
    public static List<String> fingerprintsOf(Prepared p) {
        List<String> fps = new ArrayList<>();
        for (ImportItem i : p.newItems) fps.add(i.getFingerprint());
        return fps;
    }

    // ------------------------------------------------------------ helpers

    /** Reads a text export: UTF-8 (minus any byte-order mark), falling back to Windows-1252. */
    static String readText(File file) throws IOException {
        byte[] bytes = Files.readAllBytes(file.toPath());
        String utf8 = new String(bytes, StandardCharsets.UTF_8);
        // Bank CSV exports are often Windows-1252; fall back if UTF-8 decoding produced junk.
        if (utf8.indexOf('\uFFFD') >= 0) return new String(bytes, java.nio.charset.Charset.forName("windows-1252"));
        return utf8.startsWith("\uFEFF") ? utf8.substring(1) : utf8;
    }

    /** Older parsers flag direction with "[CREDIT]"/"[TRANSFER]" prefixes and the income flag. */
    private static void normalizeLegacyMarkers(ImportItem item) {
        String d = item.getDescription() == null ? "" : item.getDescription();
        if (d.startsWith("[CREDIT] ")) {
            item.setCredit(true);
            d = d.substring(9);
        } else if (d.startsWith("[TRANSFER] ")) {
            d = d.substring(11);
        }
        if (item.isIncome()) item.setCredit(true);
        item.setDescription(d.trim());
        if ("Uncategorized".equals(item.getCategory())) item.setCategory("");
    }

    /**
     * An existing hand-entered expense for the same money on (nearly) the same day with
     * a similar description. Each manual entry can absorb only one imported line.
     */
    private static Expense findManualDuplicate(ImportItem item, List<Expense> manual, Set<Expense> claimed) {
        if (item.isTransfer()) return null;
        for (Expense e : manual) {
            if (claimed.contains(e) || e.getDate() == null) continue;
            if (e.isIncome() != item.isIncome() || e.isRefund() != item.isRefund()) continue;
            if (Math.abs(e.getAmount() - item.getAmount()) >= 0.01) continue;
            if (Math.abs(e.getDate().toEpochDay() - item.getDate().toEpochDay()) > 2) continue;
            if (similar(e.getDescription(), item.getDescription())) return e;
        }
        return null;
    }

    private static boolean similar(String a, String b) {
        if (a == null || b == null) return false;
        String na = a.toLowerCase().replaceAll("[^a-z0-9]", "");
        String nb = b.toLowerCase().replaceAll("[^a-z0-9]", "");
        if (na.length() < 3 || nb.length() < 3) return false;
        return na.contains(nb) || nb.contains(na);
    }
}
