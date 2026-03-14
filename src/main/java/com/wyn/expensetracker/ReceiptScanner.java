package com.wyn.expensetracker;

import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.*;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
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

    // R-prefixed amounts (SA Rand) + fallback for amounts at end-of-line without prefix
    // R-prefixed amounts accept 1-2 decimal digits (OCR sometimes drops the last digit)
    private static final Pattern AMOUNT_PATTERN = Pattern.compile(
        "(?:R\\s*(\\d[\\d\\s,]*[.,]\\d{1,2}))|(\\d[\\d,]*[.,]\\d{2})\\s*$");

    private static final Pattern TOTAL_PATTERN = Pattern.compile(
        "(?i)(total|subtotal|sub-total|amount\\s*due|grand\\s*total|balance\\s*due|"
        + "change|vat|tax|card|cash|tendered|rounding|discount|loyalty|smartshopper|"
        + "auth|slip|eft|payment|qty|items|saving|you saved|excl|incl|"
        + "nett|gross|member|points|vitality)");

    private static final DateTimeFormatter[] DATE_FORMATS = {
        DateTimeFormatter.ofPattern("dd/MM/yyyy"),
        DateTimeFormatter.ofPattern("MM/dd/yyyy"),
        DateTimeFormatter.ofPattern("yyyy-MM-dd"),
        DateTimeFormatter.ofPattern("dd-MM-yyyy"),
        DateTimeFormatter.ofPattern("yyyy/MM/dd"),
        DateTimeFormatter.ofPattern("dd MMM yyyy"),
        DateTimeFormatter.ofPattern("d MMM yyyy"),
        DateTimeFormatter.ofPattern("dd MMMM yyyy"),
    };

    // Matches text-month dates (e.g. "09 Sep 2024") and numeric dates (e.g. "17/02/2025")
    private static final Pattern DATE_PATTERN = Pattern.compile(
        "(\\d{1,2}\\s+(?:Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)\\w*\\s+\\d{2,4})"
        + "|(\\d{1,4}[/\\-.]\\d{1,2}[/\\-.]\\d{2,4})", Pattern.CASE_INSENSITIVE);

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
        if (Files.exists(tessDataPath)) {
            tessDataAvailable = true;
            return;
        }

        try {
            Files.createDirectories(Paths.get(TESSDATA_DIR));
            try (InputStream is = getClass().getResourceAsStream("/tessdata/eng.traineddata")) {
                if (is != null) {
                    Files.copy(is, tessDataPath, StandardCopyOption.REPLACE_EXISTING);
                    tessDataAvailable = true;
                }
            }
        } catch (IOException e) {
            System.err.println("Failed to copy tessdata: " + e.getMessage());
        }
    }

    /**
     * Extracts the photo date from EXIF metadata (when the photo was taken).
     * Useful as a default date for the receipt date picker.
     */
    public LocalDate extractPhotoDate(File imageFile) {
        try {
            Metadata metadata = ImageMetadataReader.readMetadata(imageFile);
            ExifSubIFDDirectory subIfd = metadata.getFirstDirectoryOfType(ExifSubIFDDirectory.class);
            if (subIfd != null) {
                Date date = subIfd.getDateOriginal();
                if (date != null) {
                    return date.toInstant().atZone(ZoneId.systemDefault()).toLocalDate();
                }
            }
            ExifIFD0Directory exifDir = metadata.getFirstDirectoryOfType(ExifIFD0Directory.class);
            if (exifDir != null) {
                Date date = exifDir.getDate(ExifIFD0Directory.TAG_DATETIME);
                if (date != null) {
                    return date.toInstant().atZone(ZoneId.systemDefault()).toLocalDate();
                }
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

        // Preprocess: EXIF rotation, grayscale
        BufferedImage preprocessed = preprocessor.preprocess(imageFile);

        net.sourceforge.tess4j.Tesseract tesseract = new net.sourceforge.tess4j.Tesseract();
        tesseract.setDatapath(TESSDATA_DIR);
        tesseract.setLanguage("eng");
        tesseract.setOcrEngineMode(1); // LSTM only (required for tessdata_best)
        tesseract.setPageSegMode(3);   // Fully automatic page segmentation
        tesseract.setVariable("user_defined_dpi", "300");
        return tesseract.doOCR(preprocessed);
    }

    public List<ImportItem> parseReceipt(String ocrText, LocalDate fallbackDate) {
        List<ImportItem> items = new ArrayList<>();
        LocalDate receiptDate = extractDate(ocrText, fallbackDate);
        if (receiptDate == null) {
            receiptDate = fallbackDate != null ? fallbackDate : LocalDate.now();
        }

        String[] lines = ocrText.split("\\r?\\n");
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.isEmpty()) continue;
            if (TOTAL_PATTERN.matcher(line).find()) continue;

            Matcher amountMatch = AMOUNT_PATTERN.matcher(line);
            if (amountMatch.find()) {
                // Skip negative amounts (discounts/refunds like "-R49.99" or "DISCOUNT -84.98")
                String beforeMatch = line.substring(0, amountMatch.start());
                if (beforeMatch.matches("(?:^|.*\\s)-\\s*")) continue;

                // Group 1 = R-prefixed amount, Group 2 = end-of-line amount
                String amountStr = amountMatch.group(1) != null ? amountMatch.group(1) : amountMatch.group(2);
                double amount;
                try {
                    amount = Double.parseDouble(normalizeAmount(amountStr));
                } catch (NumberFormatException e) {
                    continue;
                }
                if (amount <= 0 || amount > 100000) continue;

                String description = line.substring(0, amountMatch.start()).trim();
                // Remove R prefix if present at the end of the description
                description = description.replaceAll("R\\s*$", "").trim();
                description = cleanDescription(description);

                // If description is too short or purely numeric (tabular receipt),
                // look at adjacent lines for a better description
                if (description.length() <= 2 || description.matches("\\d+")) {
                    String better = findNearbyDescription(lines, i);
                    if (better != null) {
                        description = better;
                    }
                }

                if (description.isEmpty()) continue;

                ImportItem item = new ImportItem(amount, description, receiptDate);
                item.setStatus("Uncategorized");
                items.add(item);
            }
        }
        return items;
    }

    /**
     * Normalizes an amount string to a parseable double format.
     * Handles SA formats like "1 299,95" and "1299,95" as well as standard "1299.95".
     */
    private String normalizeAmount(String amountStr) {
        // Strip spaces (handles "R1 299.95" or "1 299,95" with thousands separator spaces)
        amountStr = amountStr.replaceAll("\\s", "");

        boolean hasDot = amountStr.contains(".");
        boolean hasComma = amountStr.contains(",");

        if (hasDot && hasComma) {
            // Both present — whichever comes last is the decimal separator
            int lastDot = amountStr.lastIndexOf('.');
            int lastComma = amountStr.lastIndexOf(',');
            if (lastComma > lastDot) {
                // Comma is decimal: "1.299,95" → "1299.95"
                amountStr = amountStr.replace(".", "").replace(",", ".");
            } else {
                // Dot is decimal: "1,299.95" → "1299.95"
                amountStr = amountStr.replace(",", "");
            }
        } else if (hasComma) {
            // Only comma — if ends with ",\d{2}", comma is decimal separator
            if (amountStr.matches(".*,\\d{1,2}$")) {
                amountStr = amountStr.replace(",", ".");
            } else {
                // Comma is thousands separator
                amountStr = amountStr.replace(",", "");
            }
        }
        // Only dot or neither — standard format, nothing to do

        return amountStr;
    }

    /**
     * Cleans up an OCR description string, preserving useful characters.
     */
    private String cleanDescription(String description) {
        // Remove characters that are clearly OCR noise, but keep periods, parens, ampersands
        description = description.replaceAll("[^a-zA-Z0-9\\s/\\-.()&]", "");
        // Collapse multiple whitespace
        description = description.replaceAll("\\s{2,}", " ").trim();
        return description;
    }

    /**
     * Looks at adjacent lines for a usable description when the current line's
     * description is too short (common in tabular receipts where descriptions
     * and amounts are on separate lines).
     */
    private String findNearbyDescription(String[] lines, int currentIndex) {
        // Score all nearby candidates and pick the most descriptive one
        // (avoids grabbing SKU/code lines over actual item names in tabular receipts)
        String best = null;
        int bestLetters = 0;

        for (int offset : new int[]{1, -1, 2, -2}) {
            int idx = currentIndex + offset;
            if (idx < 0 || idx >= lines.length) continue;
            String candidate = lines[idx].trim();
            if (candidate.isEmpty()) continue;
            if (TOTAL_PATTERN.matcher(candidate).find()) continue;
            if (AMOUNT_PATTERN.matcher(candidate).find()) continue;
            String cleaned = cleanDescription(candidate);
            if (cleaned.length() < 3 || !cleaned.matches(".*[a-zA-Z]{2,}.*")) continue;

            int letters = (int) cleaned.chars().filter(Character::isLetter).count();
            if (letters > bestLetters) {
                bestLetters = letters;
                best = cleaned;
            }
        }
        return best;
    }

    private LocalDate extractDate(String text, LocalDate referenceDate) {
        Matcher m = DATE_PATTERN.matcher(text);
        while (m.find()) {
            // Group 1 = text-month date, Group 2 = numeric date
            String dateStr = m.group(1) != null ? m.group(1) : m.group(2);
            for (DateTimeFormatter fmt : DATE_FORMATS) {
                try {
                    return LocalDate.parse(dateStr, fmt);
                } catch (DateTimeParseException e) {
                    // try next format
                }
            }

            // If exact parsing failed, try to recover OCR-corrupted date components
            LocalDate corrected = tryOcrDateCorrection(dateStr, referenceDate);
            if (corrected != null) return corrected;
        }
        return null;
    }

    /**
     * Attempts to recover a date from an OCR-corrupted date string.
     * Handles cases where OCR drops a digit (e.g. "09" → "0", making day/month invalid).
     * Uses reference date (e.g. EXIF) for accurate correction when available,
     * otherwise defaults corrupted day to 1 to preserve the correct month/year.
     */
    private LocalDate tryOcrDateCorrection(String dateStr, LocalDate reference) {
        String[] parts = dateStr.split("[/\\-.]");
        if (parts.length != 3) return null;

        try {
            int p0 = Integer.parseInt(parts[0]);
            int p1 = Integer.parseInt(parts[1]);
            int p2 = Integer.parseInt(parts[2]);

            // Normalize 2-digit year
            if (p2 >= 0 && p2 <= 99) p2 += 2000;

            // Try dd/MM/yyyy (SA standard)
            if (p2 >= 2000 && p2 <= 2100) {
                LocalDate corrected = correctDate(p0, p1, p2, reference);
                if (corrected != null) return corrected;
            }

            // Try yyyy/MM/dd
            int y0 = p0;
            if (y0 >= 0 && y0 <= 99) y0 += 2000;
            if (y0 >= 2000 && y0 <= 2100) {
                LocalDate corrected = correctDate(p2, p1, y0, reference);
                if (corrected != null) return corrected;
            }
        } catch (NumberFormatException e) {
            return null;
        }
        return null;
    }

    private LocalDate correctDate(int day, int month, int year, LocalDate reference) {
        boolean dayInvalid = day < 1 || day > 31;
        boolean monthInvalid = month < 1 || month > 12;

        // Only correct if exactly one component is corrupted
        if (dayInvalid == monthInvalid) return null;

        // If reference date matches the valid components, use it for exact correction
        if (reference != null && reference.getYear() == year) {
            if (!monthInvalid && month == reference.getMonthValue() && dayInvalid) {
                return reference;
            }
        }

        // No reference — default corrupted day to 1 (preserves correct month/year)
        if (dayInvalid) day = 1;
        if (monthInvalid) return null; // can't safely guess month

        try {
            return LocalDate.of(year, month, day);
        } catch (DateTimeException e) {
            return null;
        }
    }
}
