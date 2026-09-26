package com.wyn.expensetracker;

import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

/**
 * Remembers what has already been imported so the same statement — or the same
 * transaction appearing in two overlapping statements — is never counted twice.
 *
 * Keeps three things per profile directory:
 *  - statements.txt:   one summary per imported file (hash, period, balances, reconciliation)
 *  - fingerprints.txt: one line per imported transaction fingerprint → import id
 *  - auto_import.txt:  the folder that is scanned for new statements on startup
 *  - dismissed.txt:    hashes of files the user removed, so the folder scan doesn't bring them back
 */
public class ImportRegistry {

    /** Summary of one imported statement file, shown on the Import and Overview screens. */
    public static class StatementRecord {
        public final String importId;
        public final String fileHash;
        public final String fileName;
        public final String bank;
        public final String account;
        public final LocalDate periodStart;
        public final LocalDate periodEnd;
        public final Double openingBalance;
        public final Double closingBalance;
        public final boolean reconciled;
        public final int transactionCount;
        public final LocalDateTime importedAt;

        public StatementRecord(String importId, String fileHash, String fileName, String bank, String account,
                               LocalDate periodStart, LocalDate periodEnd, Double openingBalance,
                               Double closingBalance, boolean reconciled, int transactionCount,
                               LocalDateTime importedAt) {
            this.importId = importId;
            this.fileHash = fileHash;
            this.fileName = fileName;
            this.bank = bank;
            this.account = account;
            this.periodStart = periodStart;
            this.periodEnd = periodEnd;
            this.openingBalance = openingBalance;
            this.closingBalance = closingBalance;
            this.reconciled = reconciled;
            this.transactionCount = transactionCount;
            this.importedAt = importedAt;
        }

        /** Latest date this statement covers (period end, else import date). */
        public LocalDate coverageEnd() {
            return periodEnd != null ? periodEnd : importedAt.toLocalDate();
        }
    }

    private final Path dir;
    private final List<StatementRecord> statements = new ArrayList<>();
    private final Map<String, String> fingerprints = new HashMap<>(); // fingerprint -> importId
    private final Set<String> dismissedHashes = new HashSet<>();
    private String autoImportFolder;
    /** Set when a file exists but couldn't be read: saving would overwrite real history with nothing. */
    private boolean loadFailed;

    /** Immutable copy of what's been imported, safe to read from a background thread. */
    public static final class Snapshot {
        final Set<String> fileHashes;
        final Set<String> fingerprints;
        final Set<String> dismissed;

        Snapshot(Set<String> fileHashes, Set<String> fingerprints, Set<String> dismissed) {
            this.fileHashes = fileHashes;
            this.fingerprints = fingerprints;
            this.dismissed = dismissed;
        }
    }

    public ImportRegistry(String profileDir) {
        this.dir = Path.of(profileDir);
    }

    // ------------------------------------------------------------ queries

    public List<StatementRecord> getStatements() {
        List<StatementRecord> sorted = new ArrayList<>(statements);
        sorted.sort(Comparator.comparing(StatementRecord::coverageEnd).reversed());
        return sorted;
    }

    public boolean isFileImported(String fileHash) {
        return fileHash != null && statements.stream().anyMatch(s -> fileHash.equals(s.fileHash));
    }

    public boolean isKnownFingerprint(String fp) {
        return fp != null && fingerprints.containsKey(fp);
    }

    /** The most recent statement with a known closing balance, or null. */
    public StatementRecord latestWithBalance() {
        return statements.stream()
            .filter(s -> s.closingBalance != null)
            .max(Comparator.comparing(StatementRecord::coverageEnd))
            .orElse(null);
    }

    public String getAutoImportFolder() { return autoImportFolder; }

    public boolean isLoadFailed() { return loadFailed; }

    public boolean isDismissed(String fileHash) { return fileHash != null && dismissedHashes.contains(fileHash); }

    public Snapshot snapshot() {
        Set<String> hashes = new HashSet<>();
        for (StatementRecord r : statements) hashes.add(r.fileHash);
        return new Snapshot(Set.copyOf(hashes), Set.copyOf(fingerprints.keySet()), Set.copyOf(dismissedHashes));
    }

    public List<String> fingerprintsFor(String importId) {
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, String> e : fingerprints.entrySet()) {
            if (e.getValue().equals(importId)) out.add(e.getKey());
        }
        return out;
    }

    // ------------------------------------------------------------ updates

    /**
     * Records an import. Only the fingerprints of transactions this import actually added
     * should be passed; a fingerprint already owned by an earlier import stays with it.
     */
    public void record(StatementRecord statement, Collection<String> newFingerprints) {
        statements.add(statement);
        for (String fp : newFingerprints) {
            if (fp != null) fingerprints.putIfAbsent(fp, statement.importId);
        }
        dismissedHashes.remove(statement.fileHash);
    }

    /**
     * Forgets an import the user removed (or undid). Its file is remembered as dismissed
     * so the watched-folder scan doesn't silently bring it back; importing it by hand
     * clears that.
     */
    public void forget(String importId) {
        for (StatementRecord r : statements) {
            if (r.importId.equals(importId) && r.fileHash != null) dismissedHashes.add(r.fileHash);
        }
        purge(importId);
    }

    /** Drops an import's records without dismissing its file (used to rebuild after data loss). */
    public void purge(String importId) {
        statements.removeIf(s -> s.importId.equals(importId));
        fingerprints.values().removeIf(id -> id.equals(importId));
    }

    public void undismiss(String fileHash) {
        dismissedHashes.remove(fileHash);
    }

    public void setAutoImportFolder(String folder) {
        this.autoImportFolder = folder == null || folder.isBlank() ? null : folder;
    }

    // ------------------------------------------------------------ fingerprints

    /**
     * Stable identity for a transaction. When the statement prints a running balance,
     * date + direction + amount + balance is unique for the account, so two genuine
     * identical R8 fees on the same day stay distinct while a re-imported line matches.
     * Without a balance, an occurrence counter within the file does the same job.
     */
    public static void assignFingerprints(List<ImportItem> items, String accountKey) {
        Map<String, Integer> seen = new HashMap<>();
        for (ImportItem item : items) {
            String core = item.getDate() + "|" + (item.isCredit() ? "C" : "D") + "|"
                + String.format(Locale.ROOT, "%.2f", item.getAmount());
            String base = (accountKey == null ? "" : accountKey) + "|" + core;
            String fp;
            if (item.getBalance() != null) {
                // The running balance pins the line down on its own, so the same transaction
                // matches whether it came from the PDF or a CSV export of the same account.
                fp = "B|" + core + "|" + String.format(Locale.ROOT, "%.2f", item.getBalance());
            } else {
                String desc = item.getDescription() == null ? "" : item.getDescription().toLowerCase()
                    .replaceAll("[^a-z0-9]", "");
                String key = base + "|" + desc;
                int n = seen.merge(key, 1, Integer::sum);
                fp = key + "#" + n;
            }
            item.setFingerprint(fp);
        }
    }

    public static String sha256(File file) throws IOException {
        try (InputStream in = Files.newInputStream(file.toPath())) {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[65536];
            int r;
            while ((r = in.read(buf)) > 0) md.update(buf, 0, r);
            StringBuilder sb = new StringBuilder();
            for (byte b : md.digest()) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
    }

    // ------------------------------------------------------------ persistence

    public void load() {
        statements.clear();
        fingerprints.clear();
        dismissedHashes.clear();
        autoImportFolder = null;
        loadFailed = false;
        for (String line : readLines("statements.txt")) {
            String[] f = line.split("\\|", -1);
            if (f.length < 12) continue;
            try {
                statements.add(new StatementRecord(f[0], f[1], f[2], f[3], emptyToNull(f[4]),
                    parseDate(f[5]), parseDate(f[6]), parseDouble(f[7]), parseDouble(f[8]),
                    Boolean.parseBoolean(f[9]), Integer.parseInt(f[10]), LocalDateTime.parse(f[11])));
            } catch (RuntimeException e) {
                System.err.println("Skipping bad statement record: " + line);
            }
        }
        for (String line : readLines("fingerprints.txt")) {
            int tab = line.lastIndexOf('\t');
            if (tab > 0) fingerprints.put(line.substring(0, tab), line.substring(tab + 1));
        }
        List<String> auto = readLines("auto_import.txt");
        if (!auto.isEmpty()) setAutoImportFolder(auto.get(0).trim());
        for (String line : readLines("dismissed.txt")) dismissedHashes.add(line.trim());
    }

    public void save() throws IOException {
        if (loadFailed) {
            throw new IOException("Import history couldn't be read earlier, so it wasn't overwritten. Restart the app to retry.");
        }
        List<String> st = new ArrayList<>();
        for (StatementRecord s : statements) {
            st.add(String.join("|", s.importId, s.fileHash, clean(s.fileName), clean(s.bank), clean(nz(s.account)),
                nz(s.periodStart), nz(s.periodEnd), nz(s.openingBalance), nz(s.closingBalance),
                String.valueOf(s.reconciled), String.valueOf(s.transactionCount), s.importedAt.toString()));
        }
        writeLines("statements.txt", st);
        List<String> fp = new ArrayList<>();
        for (Map.Entry<String, String> e : fingerprints.entrySet()) fp.add(e.getKey() + "\t" + e.getValue());
        writeLines("fingerprints.txt", fp);
        writeLines("auto_import.txt", autoImportFolder == null ? List.of() : List.of(autoImportFolder));
        writeLines("dismissed.txt", new ArrayList<>(dismissedHashes));
    }

    private List<String> readLines(String name) {
        Path p = dir.resolve(name);
        if (!Files.exists(p)) return List.of();
        try {
            List<String> out = new ArrayList<>();
            for (String l : Files.readAllLines(p, StandardCharsets.UTF_8)) if (!l.isBlank()) out.add(l);
            return out;
        } catch (IOException e) {
            // Locked by antivirus/OneDrive, etc. Don't treat it as "nothing imported yet".
            System.err.println("Failed to read " + p + ": " + e.getMessage());
            loadFailed = true;
            return List.of();
        }
    }

    private void writeLines(String name, List<String> lines) throws IOException {
        Files.createDirectories(dir);
        Path target = dir.resolve(name);
        Path tmp = dir.resolve(name + ".tmp");
        try (BufferedWriter w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
            for (String l : lines) {
                w.write(l);
                w.newLine();
            }
        }
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    private static String clean(String s) {
        return s == null ? "" : s.replace("|", "/").replace("\n", " ").replace("\r", " ");
    }

    private static String nz(Object o) { return o == null ? "" : o.toString(); }
    private static String emptyToNull(String s) { return s == null || s.isEmpty() ? null : s; }
    private static LocalDate parseDate(String s) { return s.isEmpty() ? null : LocalDate.parse(s); }
    private static Double parseDouble(String s) { return s.isEmpty() ? null : Double.valueOf(s); }
}
