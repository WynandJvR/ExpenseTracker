package com.wyn.expensetracker;

import javafx.geometry.Bounds;
import javafx.geometry.Point2D;
import javafx.scene.Node;
import javafx.scene.chart.Axis;
import javafx.scene.chart.StackedAreaChart;
import javafx.scene.chart.StackedBarChart;
import javafx.scene.chart.XYChart;
import javafx.scene.control.Tooltip;
import javafx.scene.input.MouseEvent;
import javafx.stage.Window;

import java.util.Objects;
import java.util.function.Function;

/**
 * Hover read-out for XY charts: pointing anywhere over a month (or day) shows every
 * series' value at that point in one tooltip, and dims the other columns so it's clear
 * what is being described. Works for bar, stacked bar, line and area charts, and reads the
 * chart's data at hover time, so it only needs installing once.
 */
public final class ChartHover {

    private static final String KEY = "chart-hover-installed";

    private ChartHover() {}

    /**
     * @param format  turns a value into display text (e.g. currency)
     * @param xLabel  turns an x value into a heading (e.g. "Aug 26" or "Day 12"); null = toString
     * @param hint    optional last line, e.g. "Click for the transactions"
     */
    public static <X> void install(XYChart<X, Number> chart, Function<Double, String> format,
                                   Function<X, String> xLabel, String hint) {
        if (chart == null || chart.getProperties().containsKey(KEY)) return;
        chart.getProperties().put(KEY, Boolean.TRUE);
        Tooltip tip = new Tooltip();
        tip.getStyleClass().add("chart-hover-tip");
        Object[] shownX = {null};

        chart.addEventHandler(MouseEvent.MOUSE_MOVED, e -> {
            Node plot = chart.lookup(".chart-plot-background");
            if (plot == null || chart.getData().isEmpty()) return;
            Point2D p = plot.sceneToLocal(e.getSceneX(), e.getSceneY());
            Bounds b = plot.getLayoutBounds();
            if (p == null || !b.contains(p)) {
                hide(chart, tip, shownX);
                return;
            }
            X x = nearestX(chart, e);
            if (x == null) {
                hide(chart, tip, shownX);
                return;
            }
            if (!Objects.equals(x, shownX[0])) {
                shownX[0] = x;
                tip.setText(describe(chart, x, format, xLabel, hint));
                highlight(chart, x);
            }
            Window w = chart.getScene() != null ? chart.getScene().getWindow() : null;
            if (w != null) tip.show(w, e.getScreenX() + 16, e.getScreenY() + 14);
        });
        chart.addEventHandler(MouseEvent.MOUSE_EXITED, e -> hide(chart, tip, shownX));
        chart.addEventHandler(MouseEvent.MOUSE_ENTERED, e -> removeNodeTooltips(chart));
    }

    public static <X> void install(XYChart<X, Number> chart, Function<Double, String> format) {
        install(chart, format, null, null);
    }

    private static <X> void hide(XYChart<X, Number> chart, Tooltip tip, Object[] shownX) {
        tip.hide();
        if (shownX[0] != null) {
            shownX[0] = null;
            highlight(chart, null);
        }
    }

    /** The data x value closest to the mouse. */
    @SuppressWarnings("unchecked")
    private static <X> X nearestX(XYChart<X, Number> chart, MouseEvent e) {
        Axis<X> axis = chart.getXAxis();
        Point2D a = axis.sceneToLocal(e.getSceneX(), e.getSceneY());
        if (a == null) return null;
        double mouse = a.getX();
        X best = null;
        double bestDist = Double.MAX_VALUE;
        for (XYChart.Series<X, Number> s : chart.getData()) {
            for (XYChart.Data<X, Number> d : s.getData()) {
                double pos = axis.getDisplayPosition(d.getXValue());
                double dist = Math.abs(pos - mouse);
                if (dist < bestDist) {
                    bestDist = dist;
                    best = d.getXValue();
                }
            }
        }
        return best;
    }

    static <X> String describe(XYChart<X, Number> chart, X x, Function<Double, String> format,
                                       Function<X, String> xLabel, String hint) {
        StringBuilder sb = new StringBuilder(xLabel != null ? xLabel.apply(x) : String.valueOf(x));
        boolean stacked = chart instanceof StackedBarChart || chart instanceof StackedAreaChart;
        double total = 0;
        int shown = 0;
        for (XYChart.Series<X, Number> s : chart.getData()) {
            for (XYChart.Data<X, Number> d : s.getData()) {
                if (!Objects.equals(d.getXValue(), x) || d.getYValue() == null) continue;
                double v = d.getYValue().doubleValue();
                if (stacked && Math.abs(v) < 0.005) continue; // skip empty segments
                sb.append('\n').append(s.getName() == null ? "Value" : s.getName()).append(":  ").append(format.apply(v));
                total += v;
                shown++;
            }
        }
        if (shown == 0) sb.append("\nNothing recorded");
        if (stacked && shown > 1) sb.append("\nTotal:  ").append(format.apply(total));
        if (hint != null) sb.append("\n\n").append(hint);
        return sb.toString();
    }

    /** Dims every data node except the ones at {@code x} (bars and points); null restores all. */
    private static <X> void highlight(XYChart<X, Number> chart, X x) {
        for (XYChart.Series<X, Number> s : chart.getData()) {
            for (XYChart.Data<X, Number> d : s.getData()) {
                Node n = d.getNode();
                if (n != null) n.setOpacity(x == null || Objects.equals(d.getXValue(), x) ? 1.0 : 0.4);
            }
        }
    }

    /** Per-bar tooltips would pop up on top of the read-out; the read-out already says more. */
    private static <X> void removeNodeTooltips(XYChart<X, Number> chart) {
        for (XYChart.Series<X, Number> s : chart.getData()) {
            for (XYChart.Data<X, Number> d : s.getData()) {
                Node n = d.getNode();
                if (n == null) continue;
                Object t = n.getProperties().get("javafx.scene.control.Tooltip");
                if (t instanceof Tooltip tooltip) Tooltip.uninstall(n, tooltip);
            }
        }
    }
}
