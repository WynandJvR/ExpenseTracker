package com.wyn.expensetracker;

import javafx.animation.FadeTransition;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.css.PseudoClass;
import javafx.scene.Node;
import javafx.scene.chart.Chart;
import javafx.scene.control.*;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.KeyCode;
import javafx.util.Duration;

public final class UIUtils {

    private UIUtils() {}

    /**
     * Colour-blind-validated categorical palette for dark surfaces. Only eight colours, so
     * charts show at most seven categories plus a grey "Other" (see {@link #OTHER_COLOR}).
     */
    public static final String[] CATEGORY_COLORS = {
        "#3987e5", "#d95926", "#199e70", "#c98500", "#d55181", "#008300", "#9085e9", "#e66767"
    };

    /** Neutral grey used for the folded "Other" series/slice. */
    public static final String OTHER_COLOR = "#6b7480";

    public static String fmt(double amount, String currencySymbol) {
        String digits = String.format(java.util.Locale.US, "%,.2f", Math.abs(amount));
        return (amount < -0.004 ? "-" : "") + (currencySymbol == null ? "" : currencySymbol) + digits;
    }

    /** Applies the app stylesheet to a dialog pane, guarding against a missing/renamed resource. */
    public static void applyStylesheet(DialogPane pane) {
        try {
            pane.getStylesheets().add(UIUtils.class.getResource("/styles.css").toExternalForm());
        } catch (Exception ignored) { /* styling is best-effort */ }
    }

    /** Pseudo-class toggled on input fields that currently hold invalid values (styled in styles.css). */
    public static final PseudoClass INVALID = PseudoClass.getPseudoClass("invalid");

    /** Strict (Double.parseDouble) check; "Infinity", "NaN" and overflow such as "1e999" are rejected. */
    public static boolean isPositiveDouble(String s) {
        if (s == null) return false;
        try {
            double v = Double.parseDouble(s.trim());
            return Double.isFinite(v) && v > 0;
        } catch (NumberFormatException e) { return false; }
    }

    /**
     * Parses a user-typed money amount in either decimal style ("123.45", "123,45", "1 234,56",
     * "1,234.56"), so the app works regardless of the JVM locale. Returns null for anything that
     * isn't a plain non-negative number (letters, exponents, "Infinity", signs) or whose
     * separators aren't a decimal point/comma plus consistent groups of three ("..5", "1,2,3").
     * The result is rounded to cents.
     */
    public static Double parseAmount(String s) {
        if (s == null) return null;
        String t = s.trim().replace((char) 0xA0, ' ');
        if (t.startsWith("+")) t = t.substring(1).trim();
        if (!USER_AMOUNT.matcher(t).matches()) return null;
        // A lone comma before four or more digits is neither a decimal comma nor grouping.
        if (t.matches("\\d*,\\d{4,}")) return null;
        // "150,000" / "1,500": nobody types money to three decimals, so a comma before exactly
        // three digits is grouping. "1.234" could be one thousand or one point two: ask again.
        if (t.matches("\\d{1,3},\\d{3}")) return Double.valueOf(t.replace(",", ""));
        if (t.matches("\\d*[.,]\\d{3,}")) return null;
        Double v = Amounts.parse(t.replace("'", ""));
        return v != null && Double.isFinite(v) && v >= 0 ? Amounts.round2(v) : null;
    }

    /** Plain digits, one decimal separator, or groups of three split by one consistent separator. */
    private static final java.util.regex.Pattern USER_AMOUNT = java.util.regex.Pattern.compile(
        "\\d*[.,]\\d+|\\d+|\\d{1,3}([ ,.'])\\d{3}(?:\\1\\d{3})*(?:(?!\\1)[.,]\\d+)?");

    /** True if {@link #parseAmount} yields a positive amount. */
    public static boolean isPositiveAmount(String s) {
        Double v = parseAmount(s);
        return v != null && v > 0;
    }

    /** Amount formatted for an editable text field: plain digits and a '.' decimal point, in any locale. */
    public static String formatAmountForEdit(double amount) {
        return String.format(java.util.Locale.ROOT, "%.2f", amount);
    }

    public static boolean isPositiveInt(String s) {
        if (s == null) return false;
        try { return Integer.parseInt(s.trim()) > 0; } catch (NumberFormatException e) { return false; }
    }

    /** Shows the :invalid (red) border only when the field is non-empty and invalid — never on an empty field. */
    public static void markValidity(TextField field, boolean valid) {
        String t = field.getText() == null ? "" : field.getText().trim();
        field.pseudoClassStateChanged(INVALID, !t.isEmpty() && !valid);
    }

    /**
     * Disables {@code submitButton} until {@code amountField} holds a positive number, and flags the
     * field invalid (red border) when it contains a non-empty, unparseable/non-positive value.
     */
    public static void bindPositiveAmountValidation(TextField amountField, Button submitButton) {
        Runnable validate = () -> {
            boolean valid = isPositiveAmount(amountField.getText());
            submitButton.setDisable(!valid);
            markValidity(amountField, valid);
        };
        amountField.textProperty().addListener((obs, o, n) -> validate.run());
        validate.run();
    }

    /** Fires {@code submitButton} when Enter is pressed in any of the given fields (a disabled button ignores it). */
    public static void submitOnEnter(Button submitButton, TextField... fields) {
        for (TextField f : fields) {
            f.setOnKeyPressed(e -> {
                if (e.getCode() == KeyCode.ENTER) submitButton.fire();
            });
        }
    }

    /**
     * Stable colour for a category: the same name always maps to the same palette slot
     * (String.hashCode is specified, so this holds across runs). "Other" is always grey.
     * Charts that need distinct colours for their visible categories assign slots
     * themselves and use this only as a fallback.
     */
    public static String getCategoryColor(String category) {
        if (category == null || "Other".equals(category)) return OTHER_COLOR;
        return CATEGORY_COLORS[Math.floorMod(category.hashCode(), CATEGORY_COLORS.length)];
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
        // Transient confirmations go to the global toast; the inline label is reserved for errors.
        if (!isError && !message.isEmpty() && Toast.show(message)) {
            target.setText("");
            target.setOpacity(1.0);
            target.getStyleClass().setAll("error-label");
            return;
        }
        // Errors, clears, and successes when no toast overlay is installed fall back to inline.
        target.setText(message);
        target.setOpacity(1.0);
        if (message.isEmpty()) {
            target.getStyleClass().setAll("error-label");
        } else if (isError) {
            target.getStyleClass().setAll("error-label", "error-message");
        } else {
            target.getStyleClass().setAll("error-label", "success-message");
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

    /**
     * Tab-separated text for one expense. A foreign-currency expense is shown with its own
     * currency's symbol, not the base one ({@code baseCurrencySymbol}).
     */
    static String clipboardText(Expense expense, String baseCurrencySymbol) {
        String symbol = expense.getCurrency() != null && !expense.getCurrency().isBlank()
            ? CurrencyManager.getSymbol(expense.getCurrency()) : baseCurrencySymbol;
        return String.format("%s\t%s\t%s\t%s",
            fmt(expense.getAmount(), symbol), expense.getCategory(),
            expense.getDate().format(java.time.format.DateTimeFormatter.ofPattern("dd MMM yyyy")),
            expense.getDescription() != null ? expense.getDescription() : "");
    }

    public static void copyExpenseToClipboard(Expense expense, String currencySymbol) {
        if (expense == null) return;
        ClipboardContent content = new ClipboardContent();
        content.putString(clipboardText(expense, currencySymbol));
        Clipboard.getSystemClipboard().setContent(content);
    }

    public static void animateViewFadeIn(Node target) {
        FadeTransition fade = new FadeTransition(Duration.millis(150), target);
        fade.setFromValue(0.0);
        fade.setToValue(1.0);
        fade.play();
    }
}
