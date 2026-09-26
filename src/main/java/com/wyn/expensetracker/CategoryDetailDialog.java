package com.wyn.expensetracker;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.chart.BarChart;
import javafx.scene.chart.CategoryAxis;
import javafx.scene.chart.NumberAxis;
import javafx.scene.chart.XYChart;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.*;
import javafx.stage.Modality;
import javafx.stage.Stage;

import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Everything about one spending category: what it cost this month, over the last year and
 * all time, how it moves month to month, and which merchants make it up. Opened by clicking
 * a category on the Overview.
 */
public final class CategoryDetailDialog {

    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("MMM yy", Locale.ENGLISH);
    private static final DateTimeFormatter MONTH_LONG = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.ENGLISH);

    private CategoryDetailDialog() {}

    /** Totals for one category, computed with the same rules as the Overview. */
    static final class Summary {
        final Map<YearMonth, Double> byMonth = new TreeMap<>();
        final Map<String, double[]> merchants = new LinkedHashMap<>(); // name -> {amount, count}
        double allTime;

        double month(YearMonth ym) { return byMonth.getOrDefault(ym, 0.0); }

        double lastTwelve(YearMonth end) {
            double sum = 0;
            for (int i = 0; i < 12; i++) sum += month(end.minusMonths(i));
            return sum;
        }

        /** Average over the months in the last year that had any spending in this category. */
        double monthlyAverage(YearMonth end) {
            int months = 0;
            for (int i = 0; i < 12; i++) if (month(end.minusMonths(i)) > 0) months++;
            return months == 0 ? 0 : lastTwelve(end) / months;
        }
    }

    static Summary summarize(SharedState state, String category) {
        Summary s = new Summary();
        Set<YearMonth> months = new TreeSet<>();
        for (Expense e : state.getExpenseList()) months.add(YearMonth.from(e.getDate()));
        for (YearMonth ym : months) {
            double v = state.spendByCategory(state.expensesInMonth(ym)).getOrDefault(category, 0.0);
            if (v > 0) s.byMonth.put(ym, v);
            s.allTime += v;
        }
        // Merchants: the same keyword the app learns rules from ("Checkers Fresh", "Mr D Food").
        for (Expense e : state.getExpenseList()) {
            if (!category.equals(e.getCategory())) continue;
            double v = state.spendContribution(e);
            if (v == 0) continue;
            String name = CategorizationRules.keywordFor(e.getDescription());
            if (name == null) name = e.getDescription() == null || e.getDescription().isBlank() ? "(no description)" : e.getDescription();
            double[] agg = s.merchants.computeIfAbsent(name, k -> new double[2]);
            agg[0] += v;
            agg[1] += 1;
        }
        return s;
    }

    public static void show(SharedState state, String category, YearMonth selected, Runnable seeTransactions) {
        Stage stage = new Stage();
        stage.initOwner(state.getStage());
        stage.initModality(Modality.WINDOW_MODAL);
        stage.setTitle(category);
        Scene scene = new Scene(build(state, category, selected, () -> {
            stage.close();
            if (seeTransactions != null) seeTransactions.run();
        }, stage::close), 820, 760);
        try {
            scene.getStylesheets().add(CategoryDetailDialog.class.getResource("/styles.css").toExternalForm());
        } catch (Exception ignored) {
            // stylesheet missing: the dialog still works unstyled
        }
        scene.setOnKeyPressed(e -> {
            if (e.getCode() == javafx.scene.input.KeyCode.ESCAPE) stage.close();
        });
        stage.setScene(scene);
        stage.show();
    }

    /** The dialog's content, separate from the window so it can also be rendered offscreen. */
    static javafx.scene.Parent build(SharedState state, String category, YearMonth selected,
                                     Runnable seeTransactions, Runnable close) {
        Summary s = summarize(state, category);
        String sym = state.getCurrencySymbol();
        YearMonth end = selected != null ? selected : YearMonth.now();

        Label title = new Label(category);
        title.getStyleClass().add("view-title");
        Label subtitle = new Label("Spending after refunds. Transfers between your own accounts aren't included.");
        subtitle.getStyleClass().add("view-subtitle");

        GridPane kpis = new GridPane();
        kpis.setHgap(12);
        for (int i = 0; i < 4; i++) {
            ColumnConstraints c = new ColumnConstraints();
            c.setPercentWidth(25);
            kpis.getColumnConstraints().add(c);
        }
        kpis.add(kpi(end.format(MONTH_LONG), UIUtils.fmt(s.month(end), sym), "the month you're viewing"), 0, 0);
        kpis.add(kpi("Last 12 months", UIUtils.fmt(s.lastTwelve(end), sym), "up to " + end.format(MONTH)), 1, 0);
        kpis.add(kpi("Monthly average", UIUtils.fmt(s.monthlyAverage(end), sym), "per month it was used"), 2, 0);
        kpis.add(kpi("All time", UIUtils.fmt(s.allTime, sym), "everything imported"), 3, 0);

        // Month by month, last 12 months
        CategoryAxis x = new CategoryAxis();
        NumberAxis y = new NumberAxis();
        y.setMinorTickVisible(false);
        y.setTickLabelFormatter(new javafx.util.StringConverter<>() {
            @Override public String toString(Number n) {
                double v = n.doubleValue();
                return compactAmount(v);
            }
            @Override public Number fromString(String t) { return 0; }
        });
        BarChart<String, Number> chart = new BarChart<>(x, y);
        chart.setLegendVisible(false);
        chart.setAnimated(false);
        chart.setPrefHeight(240);
        chart.getStyleClass().add("trend-chart");
        XYChart.Series<String, Number> series = new XYChart.Series<>();
        series.setName(category);
        for (int i = 11; i >= 0; i--) {
            YearMonth ym = end.minusMonths(i);
            series.getData().add(new XYChart.Data<>(ym.format(MONTH), s.month(ym)));
        }
        chart.getData().add(series);
        ChartHover.install(chart, v -> UIUtils.fmt(v, sym));
        VBox chartCard = card("Month by month", chart);

        // Where it goes: top merchants
        VBox merchantRows = new VBox(8);
        List<Map.Entry<String, double[]>> top = new ArrayList<>(s.merchants.entrySet());
        top.sort((a, b) -> Double.compare(b.getValue()[0], a.getValue()[0]));
        double max = top.isEmpty() ? 1 : Math.max(top.get(0).getValue()[0], 0.01);
        double sumAll = top.stream().mapToDouble(e -> Math.max(0, e.getValue()[0])).sum();
        int shown = 0;
        for (Map.Entry<String, double[]> m : top) {
            if (shown++ >= 10) break;
            double amount = m.getValue()[0];
            int count = (int) m.getValue()[1];
            Label name = new Label(m.getKey());
            name.getStyleClass().add("bar-name");
            Label amt = new Label(UIUtils.fmt(amount, sym));
            amt.getStyleClass().add("bar-amount");
            Label meta = new Label(count + "× · " + (sumAll > 0 ? String.format("%.0f%%", Math.max(0, amount) / sumAll * 100) : ""));
            meta.getStyleClass().add("bar-share");
            meta.setMinWidth(70);
            Region spacer = new Region();
            HBox.setHgrow(spacer, Priority.ALWAYS);
            HBox head = new HBox(8, name, spacer, amt, meta);
            head.setAlignment(Pos.BASELINE_LEFT);
            Region fill = new Region();
            fill.getStyleClass().add("bar-fill");
            StackPane track = new StackPane(fill);
            track.getStyleClass().add("bar-track");
            StackPane.setAlignment(fill, Pos.CENTER_LEFT);
            fill.maxWidthProperty().bind(track.widthProperty().multiply(Math.max(0.01, Math.max(0, amount) / max)));
            fill.prefWidthProperty().bind(fill.maxWidthProperty());
            merchantRows.getChildren().add(new VBox(4, head, track));
        }
        if (top.isEmpty()) {
            Label none = new Label("No spending in this category yet.");
            none.getStyleClass().add("empty-state-hint");
            merchantRows.getChildren().add(none);
        } else if (top.size() > 10) {
            Label more = new Label("+" + (top.size() - 10) + " more places");
            more.getStyleClass().add("faint-text");
            merchantRows.getChildren().add(more);
        }
        VBox merchantsCard = card("Where it goes (all time)", merchantRows);

        Button see = new Button("See all " + category + " transactions");
        see.getStyleClass().add("accent-button");
        see.setOnAction(e -> seeTransactions.run());
        Button closeButton = new Button("Close");
        closeButton.getStyleClass().add("ghost-button");
        closeButton.setOnAction(e -> close.run());
        Region grow = new Region();
        HBox.setHgrow(grow, Priority.ALWAYS);
        HBox buttons = new HBox(10, grow, closeButton, see);
        buttons.setAlignment(Pos.CENTER_RIGHT);

        VBox content = new VBox(16, new VBox(4, title, subtitle), kpis, chartCard, merchantsCard, buttons);
        content.setPadding(new Insets(24, 26, 22, 26));
        content.getStyleClass().add("content-view");
        ScrollPane scroll = new ScrollPane(content);
        scroll.setFitToWidth(true);
        scroll.getStyleClass().add("scroll-pane");

        scroll.getStyleClass().add("root-pane");
        return scroll;
    }

    /** Axis labels: "3.5k" rather than a rounded "4k" that repeats. */
    static String compactAmount(double v) {
        double a = Math.abs(v);
        if (a >= 1_000_000) return trim(v / 1_000_000) + "m";
        if (a >= 1_000) return trim(v / 1_000) + "k";
        return String.format("%.0f", v);
    }

    private static String trim(double v) {
        String s = String.format(Locale.ROOT, "%.1f", v);
        return s.endsWith(".0") ? s.substring(0, s.length() - 2) : s;
    }

    private static VBox kpi(String label, String value, String sub) {
        Label l = new Label(label);
        l.getStyleClass().add("kpi-label");
        Label v = new Label(value);
        v.getStyleClass().add("kpi-value");
        Label s = new Label(sub);
        s.getStyleClass().add("kpi-sub");
        VBox box = new VBox(4, l, v, s);
        box.getStyleClass().add("kpi-card");
        box.setMaxWidth(Double.MAX_VALUE);
        return box;
    }

    private static VBox card(String title, javafx.scene.Node body) {
        Label t = new Label(title);
        t.getStyleClass().add("card-title");
        VBox card = new VBox(10, t, body);
        card.getStyleClass().add("card");
        return card;
    }
}
