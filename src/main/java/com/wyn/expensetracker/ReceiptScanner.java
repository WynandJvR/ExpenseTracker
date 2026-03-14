package com.wyn.expensetracker;

import java.io.*;
import java.nio.file.*;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ReceiptScanner {

    private static final String TESSDATA_DIR = System.getProperty("user.home")
        + File.separator + ".expenseTracker" + File.separator + "tessdata";

    private static final Pattern AMOUNT_PATTERN = Pattern.compile("(\\d+[.,]\\d{2})\\s*$");
    private static final Pattern TOTAL_PATTERN = Pattern.compile(
        "(?i)(total|subtotal|sub-total|amount\\s*due|grand\\s*total|balance\\s*due|change|vat|tax)");
    private static final DateTimeFormatter[] DATE_FORMATS = {
        DateTimeFormatter.ofPattern("dd/MM/yyyy"),
        DateTimeFormatter.ofPattern("MM/dd/yyyy"),
        DateTimeFormatter.ofPattern("yyyy-MM-dd"),
        DateTimeFormatter.ofPattern("dd-MM-yyyy"),
        DateTimeFormatter.ofPattern("yyyy/MM/dd"),
    };

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

    public String performOcr(File imageFile) throws Exception {
        if (!tessDataAvailable) {
            throw new IllegalStateException("OCR is not available. Please place eng.traineddata in " + TESSDATA_DIR);
        }

        net.sourceforge.tess4j.Tesseract tesseract = new net.sourceforge.tess4j.Tesseract();
        tesseract.setDatapath(TESSDATA_DIR);
        tesseract.setLanguage("eng");
        tesseract.setPageSegMode(6); // Assume uniform block of text
        return tesseract.doOCR(imageFile);
    }

    public List<ImportItem> parseReceipt(String ocrText, LocalDate fallbackDate) {
        List<ImportItem> items = new ArrayList<>();
        LocalDate receiptDate = extractDate(ocrText);
        if (receiptDate == null) {
            receiptDate = fallbackDate != null ? fallbackDate : LocalDate.now();
        }

        String[] lines = ocrText.split("\\r?\\n");
        for (String line : lines) {
            line = line.trim();
            if (line.isEmpty()) continue;
            if (TOTAL_PATTERN.matcher(line).find()) continue;

            Matcher amountMatch = AMOUNT_PATTERN.matcher(line);
            if (amountMatch.find()) {
                String amountStr = amountMatch.group(1).replace(",", ".");
                try {
                    double amount = Double.parseDouble(amountStr);
                    if (amount <= 0 || amount > 100000) continue;

                    String description = line.substring(0, amountMatch.start()).trim();
                    description = description.replaceAll("[^a-zA-Z0-9\\s/-]", "").trim();
                    if (description.isEmpty()) continue;

                    ImportItem item = new ImportItem(amount, description, receiptDate);
                    item.setStatus("Uncategorized");
                    items.add(item);
                } catch (NumberFormatException e) {
                    // skip
                }
            }
        }
        return items;
    }

    private LocalDate extractDate(String text) {
        Pattern datePattern = Pattern.compile("(\\d{1,4}[/\\-.]\\d{1,2}[/\\-.]\\d{2,4})");
        Matcher m = datePattern.matcher(text);
        while (m.find()) {
            String dateStr = m.group(1);
            for (DateTimeFormatter fmt : DATE_FORMATS) {
                try {
                    return LocalDate.parse(dateStr, fmt);
                } catch (DateTimeParseException e) {
                    // try next format
                }
            }
        }
        return null;
    }
}
