package com.wyn.expensetracker;

import javafx.beans.property.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public class ImportLog {
    private static final DateTimeFormatter DISPLAY_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final String importId;
    private final LocalDateTime timestamp;
    private final String sourceFile;
    private final String sourceType;
    private final int itemCount;

    public ImportLog(String importId, LocalDateTime timestamp, String sourceFile, String sourceType, int itemCount) {
        this.importId = importId;
        this.timestamp = timestamp;
        this.sourceFile = sourceFile;
        this.sourceType = sourceType;
        this.itemCount = itemCount;
    }

    public String getImportId() { return importId; }
    public LocalDateTime getTimestamp() { return timestamp; }
    public String getSourceFile() { return sourceFile; }
    public String getSourceType() { return sourceType; }
    public int getItemCount() { return itemCount; }

    public String getTimestampDisplay() { return timestamp.format(DISPLAY_FMT); }

    // JavaFX property accessors for TableView binding
    public StringProperty timestampDisplayProperty() { return new SimpleStringProperty(getTimestampDisplay()); }
    public StringProperty sourceFileProperty() { return new SimpleStringProperty(sourceFile); }
    public StringProperty sourceTypeProperty() { return new SimpleStringProperty(sourceType); }
    public IntegerProperty itemCountProperty() { return new SimpleIntegerProperty(itemCount); }
}
