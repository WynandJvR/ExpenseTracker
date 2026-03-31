package com.wyn.expensetracker;

import javafx.animation.FadeTransition;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.chart.Chart;
import javafx.scene.control.*;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.util.Duration;

public final class UIUtils {

    private UIUtils() {}

    public static final String[] CATEGORY_COLORS = {
        "#FF6F61", "#6B5B95", "#88B04B", "#F7B731", "#4ECDC4",
        "#FC5C65", "#45AAF2", "#26DE81", "#FD9644", "#A55EEA",
        "#778CA3", "#20BF6B", "#EB3B5A", "#3867D6", "#D1D8E0",
        "#0FB9B1", "#FA8231", "#8854D0", "#2D98DA", "#E77F67"
    };

    public static String fmt(double amount, String currencySymbol) {
        return currencySymbol + String.format("%.2f", amount);
    }

    public static String getCategoryColor(String category) {
        int hash = Math.abs(category.hashCode());
        return CATEGORY_COLORS[hash % CATEGORY_COLORS.length];
    }

    public static void animateChartFadeIn(Node chart) {
        chart.setOpacity(0);
        FadeTransition fade = new FadeTransition(Duration.millis(300), chart);
        fade.setFromValue(0);
        fade.setToValue(1);
        fade.play();
    }

    public static void styleChartLegend(Chart chart, String... colors) {
        Platform.runLater(() -> {
            int i = 0;
            for (Node legendItem : chart.lookupAll(".chart-legend-item-symbol")) {
                if (i < colors.length) {
                    legendItem.setStyle("-fx-background-color: " + colors[i] + ";");
                }
                i++;
            }
        });
    }

    public static void makeLabelCopyable(Label label) {
        ContextMenu menu = new ContextMenu();
        MenuItem copy = new MenuItem("Copy");
        copy.setOnAction(e -> {
            ClipboardContent content = new ClipboardContent();
            content.putString(label.getText());
            Clipboard.getSystemClipboard().setContent(content);
        });
        menu.getItems().add(copy);
        label.setContextMenu(menu);
    }

    public static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max - 3) + "...";
    }

    public static <T> void setupComboCellFactory(ComboBox<T> combo) {
        combo.setCellFactory(lv -> new ListCell<T>() {
            @Override
            protected void updateItem(T item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : item.toString());
            }
        });
    }

    private static PauseTransition messageFade;

    public static void showMessage(String message, boolean isError, Label target) {
        target.setText(message);
        target.setOpacity(1.0);
        if (message.isEmpty()) {
            target.getStyleClass().setAll("error-label");
        } else if (isError) {
            target.getStyleClass().setAll("error-label", "error-message");
        } else {
            target.getStyleClass().setAll("error-label", "success-message");
        }
        if (!isError && !message.isEmpty()) {
            if (messageFade != null) messageFade.stop();
            messageFade = new PauseTransition(Duration.seconds(3));
            messageFade.setOnFinished(e -> {
                FadeTransition fade = new FadeTransition(Duration.millis(500), target);
                fade.setFromValue(1.0);
                fade.setToValue(0.0);
                fade.play();
            });
            messageFade.playFromStart();
        }
    }

    public static void copyExpenseToClipboard(Expense expense, String currencySymbol) {
        if (expense == null) return;
        String text = String.format("%s\t%s\t%s\t%s",
            fmt(expense.getAmount(), currencySymbol), expense.getCategory(),
            expense.getDate().format(java.time.format.DateTimeFormatter.ofPattern("dd MMM yyyy")),
            expense.getDescription() != null ? expense.getDescription() : "");
        ClipboardContent content = new ClipboardContent();
        content.putString(text);
        Clipboard.getSystemClipboard().setContent(content);
    }

    public static void animateViewFadeIn(Node target) {
        FadeTransition fade = new FadeTransition(Duration.millis(150), target);
        fade.setFromValue(0.0);
        fade.setToValue(1.0);
        fade.play();
    }
}
