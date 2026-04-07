package com.wyn.expensetracker;

import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

public class DrillDownDialog {

    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("dd MMM yyyy");

    public static void show(Stage owner, String title, List<Expense> expenses,
                               String currencySymbol) {
        show(owner, title, expenses, currencySymbol, null);
    }

    public static void show(Stage owner, String title, List<Expense> expenses,
                               String currencySymbol, CurrencyManager cm) {
        if (expenses == null || expenses.isEmpty()) return;

        Stage dialog = new Stage();
        dialog.initModality(Modality.WINDOW_MODAL);
        dialog.initOwner(owner);
        dialog.setTitle(title);

        Label header = new Label(title);
        header.getStyleClass().add("section-title");

        TableView<Expense> table = new TableView<>(FXCollections.observableArrayList(expenses));
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);

        TableColumn<Expense, Double> amountCol = new TableColumn<>("Amount");
        amountCol.setCellValueFactory(new PropertyValueFactory<>("amount"));
        amountCol.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(Double item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : UIUtils.fmt(item, currencySymbol));
                setAlignment(Pos.CENTER_RIGHT);
            }
        });

        TableColumn<Expense, String> categoryCol = new TableColumn<>("Category");
        categoryCol.setCellValueFactory(new PropertyValueFactory<>("category"));

        TableColumn<Expense, LocalDate> dateCol = new TableColumn<>("Date");
        dateCol.setCellValueFactory(new PropertyValueFactory<>("date"));
        dateCol.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(LocalDate item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : item.format(DATE_FORMAT));
                setAlignment(Pos.CENTER);
            }
        });

        TableColumn<Expense, String> descCol = new TableColumn<>("Description");
        descCol.setCellValueFactory(new PropertyValueFactory<>("description"));

        table.getColumns().addAll(amountCol, categoryCol, dateCol, descCol);
        table.setPrefHeight(400);

        double total = expenses.stream()
            .mapToDouble(e -> cm != null ? cm.toBase(e.getAmount(), e.getCurrency()) : e.getAmount())
            .sum();
        Label summary = new Label(String.format("%d transactions  |  Total: %s",
            expenses.size(), UIUtils.fmt(total, currencySymbol)));
        summary.getStyleClass().add("form-label");

        Button closeBtn = new Button("Close");
        closeBtn.getStyleClass().add("primary-button");
        closeBtn.setOnAction(e -> dialog.close());
        HBox buttons = new HBox(closeBtn);
        buttons.setAlignment(Pos.CENTER_RIGHT);

        VBox content = new VBox(12, header, table, summary, buttons);
        content.setPadding(new Insets(20));
        content.getStyleClass().add("root-pane");

        Scene scene = new Scene(content, 650, 520);
        scene.getStylesheets().add(DrillDownDialog.class.getResource("/styles.css").toExternalForm());
        dialog.setScene(scene);
        dialog.show();
    }
}
