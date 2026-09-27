package com.wyn.expensetracker;

import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.*;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.drew.imaging.ImageMetadataReader;
import com.drew.metadata.Metadata;
import com.drew.metadata.exif.ExifIFD0Directory;
import com.drew.metadata.exif.ExifSubIFDDirectory;

public class ReceiptScanner {

    private static final String TESSDATA_DIR = System.getProperty("user.home")
        + File.separator + ".expenseTracker" + File.separator + "tessdata";

    /** Amount at end of line. R-prefixed amounts may use space thousands ("R 1 299,95"); bare amounts may not,
     *  so a quantity column ("ROLLS 6 24.99") is never glued onto the price. Optional leading/trailing '-' and VAT flag ("34.99 A"). */
    private static final Pattern LINE_AMOUNT = Pattern.compile(
        "(?:(?<![A-Za-z])R\\s?(-\\s?)?((?:\\d{1,3}(?:[ ,.]\\d{3})+|\\d+)\\s?[.,]\\s?\\d{2})"
        + "|(-\\s?)?((?:\\d{1,3}(?:[,.]\\d{3})+|\\d+)[.,]\\s?\\d{2}))"
        + "(-)?(?:\\s+[A-Z*#]{1,2})?\\s*$");
    /** Trailing amount token that may contain OCR letter/digit confusions (O/o/@->0, S/s->5, I/l/|->1, B->8, ':'->'.'). */
    private static final Pattern AMOUNT_TAIL = Pattern.compile(
        "(?<![A-Za-z])([0-9OoSsIl|B@]{1,3}(?:[ ,.][0-9OoSsIl|B@]{3})*\\s?[.,:]\\s?[0-9OoSsIl|B@]{2}-?(?:\\s+[A-Z*#]{1,2})?)\\s*$");
    private static final Pattern FLAG = Pattern.compile("\\s+[A-Z*#]{1,2}$");
    /** Whole-word keywords, so CASHEW / CARDIGAN / TAXI / CARDBOARD / EXCHANGE are NOT skipped. */
    private static final Pattern NON_ITEM = Pattern.compile(
        "(?i)\\b(sub-?\\s?total|total|amount\\s*due|balance(\\s*due)?|change|vat|tax|cash|card\\s*(?:sale|tender|no)|visa|master\\s?card|maestro|"
        + "debit|credit|tendered|rounding|eft|payment|auth|approved|points|you\\s*saved|incl|excl|items|qty|tel|reg\\s*no|date|time|till)\\b");
    private static final Pattern DISCOUNT = Pattern.compile(
        "(?i)\\b(saving|savings|discount|promo|coupon|less|smart\\s*price|voucher)\\b");
    private static final Pattern TOTAL_LINE = Pattern.compile(
        "(?i)^(?!.*sub).*\\b(total(\\s*due)?|amount\\s*due|balance\\s*due|grand\\s*total|to\\s*pay)\\b");
    private static final Pattern NOT_THE_TOTAL = Pattern.compile(
        "(?i)\\b(discount|savings?|saved|vat|tax|items?|qty|excl|incl|points)\\b");
    private static final Pattern PLAIN_TOTAL = Pattern.compile(
        "(?i)(grand\\s*)?total(\\s*due)?|amount\\s*due|balance\\s*due|to\\s*pay|total\\s*r?");
    /** "CARD", "CARD ****1234", "CARD 4521": how the slip was paid (unlike "CARD GAME UNO"). */
    private static final Pattern CARD_PAYMENT = Pattern.compile("(?i)^card(\\s*[*#xX\\d].*)?$");
    private static final Pattern QTY_LINE = Pattern.compile("^\\d+(?:[.,]\\d+)?\\s*[@xX*]\\s*(?:R\\s?)?[\\d.,]+$");
    private static final Pattern NUM_DATE = Pattern.compile(
        "(?<!\\d)(\\d{1,4})\\s?[/\\-.]\\s?(\\d{1,2})\\s?[/\\-.]\\s?(\\d{2,4})(?!\\d)");
    /** Locale-independent month names (en_ZA CLDR formats September as "Sept", so DateTimeFormatter "MMM" can't parse "Sep"). */
    private static final Pattern TEXT_DATE = Pattern.compile(
        "(?i)(?<!\\d)(\\d{1,2})\\s*(jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)[a-z]*\\.?,?\\s*(\\d{2,4})(?!\\d)");
    private static final String[] CHAINS = {"PICK N PAY", "SHOPRITE", "CHECKERS", "WOOLWORTHS", "SPAR", "USAVE", "OK FOODS",
        "FOOD LOVER", "CLICKS", "DIS-CHEM", "DISCHEM", "PEP", "ACKERMANS", "MR PRICE", "BUILDERS", "GAME", "MAKRO", "ENGEN",
        "SHELL", "SASOL", "BP", "CALTEX", "KFC", "MCDONALD", "NANDO", "STEERS", "WIMPY", "SPUR", "CAPE UNION MART"};

    private final ReceiptImagePreprocessor preprocessor = new ReceiptImagePreprocessor();
    private boolean tessDataAvailable = false;

    public ReceiptScanner() {
        ensureTessData();
    }

    public boolean isTessDataAvailable() {
        return tessDataAvailable;
    }

    private void ensureTessData() {
        Path tessDataPath = Paths.get(TESSDATA_DIR, "eng.traineddata");
        try {
            if (Files.exists(tessDataPath) && Files.size(tessDataPath) > 1_000_000) {
                tessDataAvailable = true;
                return;
            }
            Files.createDirectories(Paths.get(TESSDATA_DIR));
            // tess4j itself ships /tessdata/eng.traineddata (tessdata_fast), so this works on a fresh machine too.
            try (InputStream is = getClass().getResourceAsStream("/tessdata/eng.traineddata")) {
                if (is != null) {
                    // copy to a temp file then move, so an interrupted copy never leaves a truncated model behind
                    Path tmp = Files.createTempFile(Paths.get(TESSDATA_DIR), "eng", ".part");
                    Files.copy(is, tmp, StandardCopyOption.REPLACE_EXISTING);
                    Files.move(tmp, tessDataPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                    tessDataAvailable = true;
                }
            }
        } catch (IOException e) {
            System.err.println("Failed to copy tessdata: " + e.getMessage());
        }
    }

    /**
     * Extracts the photo date from EXIF metadata (when the photo was taken).
     * EXIF timestamps are local wall-clock time with no zone; pass the local zone, otherwise metadata-extractor
     * treats them as UTC and a photo taken after 22:00 in SA lands on the next day.
     */
    public LocalDate extractPhotoDate(File imageFile) {
        TimeZone tz = TimeZone.getDefault();
        try {
            Metadata metadata = ImageMetadataReader.readMetadata(imageFile);
            ExifSubIFDDirectory subIfd = metadata.getFirstDirectoryOfType(ExifSubIFDDirectory.class);
            if (subIfd != null) {
                Date date = subIfd.getDateOriginal(tz);
                if (date != null) return date.toInstant().atZone(ZoneId.systemDefault()).toLocalDate();
            }
            ExifIFD0Directory exifDir = metadata.getFirstDirectoryOfType(ExifIFD0Directory.class);
            if (exifDir != null) {
                Date date = exifDir.getDate(ExifIFD0Directory.TAG_DATETIME, tz);
                if (date != null) return date.toInstant().atZone(ZoneId.systemDefault()).toLocalDate();
            }
        } catch (Exception e) {
            // Fall back silently
        }
        return null;
    }

    public String performOcr(File imageFile) throws Exception {
        if (!tessDataAvailable) {
            throw new IllegalStateException("OCR is not available. Please place eng.traineddata in " + TESSDATA_DIR);
        }
        BufferedImage preprocessed = preprocessor.preprocess(imageFile);

        net.sourceforge.tess4j.Tesseract tesseract = new net.sourceforge.tess4j.Tesseract(); // not thread-safe: one per call
        tesseract.setDatapath(TESSDATA_DIR);
        tesseract.setLanguage("eng");
        tesseract.setOcrEngineMode(1);   // LSTM only
        tesseract.setPageSegMode(6);     // single uniform block: keeps "ITEM ..... 12.99" on one line (PSM 3 splits the price column off)
        tesseract.setVariable("user_defined_dpi", "300");
        return tesseract.doOCR(preprocessed);
    }

    public List<ImportItem> parseReceipt(String ocrText, LocalDate fallbackDate) {
        LocalDate receiptDate = extractDate(ocrText);
        if (receiptDate == null) receiptDate = fallbackDate != null ? fallbackDate : LocalDate.now();

        List<ImportItem> items = new ArrayList<>();
        String[] lines = ocrText.split("\\r?\\n");
        ImportItem last = null;
        int lastIdx = -10, prevNonBlank = -1;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.isEmpty()) continue;
            boolean followsItem = lastIdx == prevNonBlank;
            prevNonBlank = i;
            if (NUM_DATE.matcher(line).find() || TEXT_DATE.matcher(line).find()) continue; // date/time header lines

            Matcher m = LINE_AMOUNT.matcher(fixAmountTail(line));
            if (!m.find()) continue;
            Double amount = parseAmount(m.group(2) != null ? m.group(2) : m.group(4));
            if (amount == null || amount <= 0 || amount > 100000) continue;
            boolean negative = m.group(1) != null || m.group(3) != null || m.group(5) != null;
            String description = line.substring(0, Math.min(line.length(), m.start())).trim();

            if (TOTAL_LINE.matcher(line).find() || NON_ITEM.matcher(description).find()) continue;
            // "CARD  1 376,96" / "CARD ****1234" is how the slip was paid; "BIRTHDAY CARD" is an item.
            if (CARD_PAYMENT.matcher(description).matches()) continue;
            if (negative || DISCOUNT.matcher(description).find()) {
                // per-item promo ("XTRA SAVINGS 20.00-") directly under the item it applies to
                if (last != null && followsItem && last.getAmount() > amount) {
                    last.setAmount(Math.round((last.getAmount() - amount) * 100) / 100.0);
                }
                continue;
            }
            // "WATER 5L" / "  2 @ 12.99      25.98": take the description from the line above
            if (QTY_LINE.matcher(description).matches() || description.length() <= 2 || description.matches("[\\d\\s.,]+")) {
                String prev = "";
                for (int j = i - 1; j >= 0 && j >= i - 2 && prev.isEmpty(); j--) prev = lines[j].trim();
                if (!prev.isEmpty() && !LINE_AMOUNT.matcher(prev).find() && prev.matches(".*[A-Za-z]{2,}.*")) description = prev;
            }
            description = cleanDescription(description);
            if (!description.matches(".*[A-Za-z]{2,}.*")) continue;

            ImportItem item = new ImportItem(amount, description, receiptDate);
            item.setStatus("Uncategorized");
            items.add(item);
            last = item;
            lastIdx = i;
        }
        return items;
    }

    /** The receipt total (TOTAL / TOTAL DUE / AMOUNT DUE / BALANCE DUE, never SUBTOTAL), or null. */
    public Double extractTotal(String ocrText) {
        Double fallback = null;
        for (String raw : ocrText.split("\\r?\\n")) {
            String line = raw.trim();
            // "TOTAL DISCOUNT 10.00", "TOTAL SAVINGS", "TOTAL VAT" are totals of something else.
            if (!TOTAL_LINE.matcher(line).find() || NOT_THE_TOTAL.matcher(line).find()) continue;
            Matcher m = LINE_AMOUNT.matcher(fixAmountTail(line));
            if (!m.find()) continue;
            Double amount = parseAmount(m.group(2) != null ? m.group(2) : m.group(4));
            if (amount == null) continue;
            // "TOTAL  125.97" / "AMOUNT DUE R 125,97": exactly the total line.
            if (PLAIN_TOTAL.matcher(line.substring(0, m.start()).trim()).matches()) return amount;
            if (fallback == null) fallback = amount;
        }
        return fallback;
    }

    /** Known chain anywhere on the slip, else the first mostly-alphabetic line in the header. */
    public String extractMerchant(String ocrText) {
        String[] lines = ocrText.split("\\r?\\n");
        // Chain names only in the header: "SHELL PASTA" further down is a product, not the shop.
        String upper = String.join("\n", Arrays.asList(lines).subList(0, Math.min(8, lines.length))).toUpperCase();
        for (String c : CHAINS) if (Pattern.compile("\\b" + Pattern.quote(c) + "\\b").matcher(upper).find()) return c;
        for (int i = 0; i < Math.min(6, lines.length); i++) {
            String l = lines[i].replaceAll("[^A-Za-z0-9&'\\- ]", "").trim();
            long letters = l.chars().filter(Character::isLetter).count();
            if (letters >= 3 && letters >= l.replace(" ", "").length() * 0.7
                && !l.matches("(?i).*\\b(tax invoice|invoice|receipt|welcome|vat|tel|reg)\\b.*")) return l;
        }
        return null;
    }

    private static String fixAmountTail(String line) {
        Matcher m = AMOUNT_TAIL.matcher(line);
        if (!m.find() || !m.group(1).matches(".*\\d.*")) return line;
        String tok = m.group(1);
        String fixed = tok.replaceAll("[Oo@]", "0").replaceAll("[Ss]", "5").replaceAll("[Il|]", "1")
            .replace('B', '8').replace(':', '.');
        Matcher flag = FLAG.matcher(tok);
        if (flag.find()) fixed = fixed.substring(0, flag.start()) + tok.substring(flag.start()); // keep VAT flag letter
        return line.substring(0, m.start(1)) + fixed; // same length as the original, so offsets stay valid
    }

    /** Last '.' or ',' is the decimal separator; everything else (spaces, other separators) is grouping. */
    private static Double parseAmount(String s) {
        s = s.replaceAll("\\s", "");
        int dec = Math.max(s.lastIndexOf('.'), s.lastIndexOf(','));
        if (dec < 0) return null;
        try {
            return Double.parseDouble(s.substring(0, dec).replaceAll("[.,]", "") + "." + s.substring(dec + 1));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String cleanDescription(String description) {
        description = description.replaceAll("[^a-zA-Z0-9\\s/\\-.()&%]", "");
        return description.replaceAll("\\s{2,}", " ").trim();
    }

    static LocalDate extractDate(String text) {
        String t = text.replaceAll("(?<=\\d)[Oo](?=[\\d/\\-.])|(?<=[/\\-.])[Oo](?=\\d)", "0");
        Matcher tm = TEXT_DATE.matcher(t);
        while (tm.find()) {
            int month = "janfebmaraprmayjunjulaugsepoctnovdec".indexOf(tm.group(2).toLowerCase()) / 3 + 1;
            LocalDate d = safeDate(year(tm.group(3)), month, Integer.parseInt(tm.group(1)));
            if (plausible(d)) return d;
        }
        Matcher m = NUM_DATE.matcher(t);
        while (m.find()) {
            String a = m.group(1), b = m.group(2), c = m.group(3);
            LocalDate d;
            if (a.length() == 4) {
                d = safeDate(Integer.parseInt(a), Integer.parseInt(b), Integer.parseInt(c));      // yyyy/MM/dd
            } else {
                d = safeDate(year(c), Integer.parseInt(b), Integer.parseInt(a));                  // dd/MM/yyyy (SA)
                if (d == null) d = safeDate(year(c), Integer.parseInt(a), Integer.parseInt(b));   // MM/dd/yyyy
            }
            if (plausible(d)) return d;
        }
        return null;
    }

    private static boolean plausible(LocalDate d) {
        return d != null && d.getYear() >= 2000 && !d.isAfter(LocalDate.now().plusDays(1));
    }

    private static int year(String y) {
        int v = Integer.parseInt(y);
        return y.length() == 2 ? 2000 + v : v;
    }

    private static LocalDate safeDate(int y, int m, int d) {
        try {
            return LocalDate.of(y, m, d);
        } catch (DateTimeException e) {
            return null;
        }
    }
}
