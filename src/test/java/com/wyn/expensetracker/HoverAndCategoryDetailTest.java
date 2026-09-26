package com.wyn.expensetracker;

import javafx.collections.FXCollections;
import javafx.scene.chart.CategoryAxis;
import javafx.scene.chart.NumberAxis;
import javafx.scene.chart.StackedBarChart;
import javafx.scene.chart.XYChart;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.HashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class HoverAndCategoryDetailTest {

    @org.junit.jupiter.api.BeforeAll
    static void startToolkit() {
        // Chart classes need the JavaFX toolkit; nothing is shown on screen.
        try {
            javafx.application.Platform.startup(() -> {});
        } catch (IllegalStateException alreadyRunning) {
            // started by another test
        }
    }

    @Test
    void hoverReadoutListsEverySeriesAndTheTotal() {
        StackedBarChart<String, Number> chart = new StackedBarChart<>(new CategoryAxis(), new NumberAxis());
        XYChart.Series<String, Number> food = new XYChart.Series<>();
        food.setName("Groceries");
        food.getData().add(new XYChart.Data<>("Aug 26", 1200));
        XYChart.Series<String, Number> fuel = new XYChart.Series<>();
        fuel.setName("Fuel");
        fuel.getData().add(new XYChart.Data<>("Aug 26", 300));
        XYChart.Series<String, Number> none = new XYChart.Series<>();
        none.setName("Travel");
        none.getData().add(new XYChart.Data<>("Aug 26", 0));
        chart.getData().addAll(List.of(food, fuel, none));

        String text = ChartHover.describe(chart, "Aug 26", v -> UIUtils.fmt(v, "R"), null, "Click for details");
        assertEquals("Aug 26\nGroceries:  R1,200.00\nFuel:  R300.00\nTotal:  R1,500.00\n\nClick for details", text);
    }

    @Test
    void categoryBreakdownTotals(@TempDir Path dir) {
        ExpenseManager manager = new ExpenseManager();
        SharedState state = new SharedState(manager, new FileStorage(dir.toString()),
            FXCollections.observableArrayList(), new HashMap<>(), null, null);
        manager.addExpenses(List.of(
            new Expense(300, "Software & AI", LocalDate.of(2026, 7, 3), "POS Purchase Claude.Ai Subscript"),
            new Expense(300, "Software & AI", LocalDate.of(2026, 8, 3), "POS Purchase Claude.Ai Subscript"),
            new Expense(100, "Software & AI", LocalDate.of(2026, 8, 9), "POS Purchase Neon.Tech"),
            new Expense(999, "Groceries", LocalDate.of(2026, 8, 9), "POS Purchase Checkers")));
        Expense refund = new Expense(50, "Software & AI", LocalDate.of(2026, 8, 20), "POS Purchase Neon.Tech");
        refund.setRefund(true);
        manager.addExpense(refund);
        state.syncExpenseList();

        CategoryDetailDialog.Summary s = CategoryDetailDialog.summarize(state, "Software & AI");
        assertEquals(650, s.allTime, 0.001, "refunds net off");
        assertEquals(350, s.month(YearMonth.of(2026, 8)), 0.001);
        assertEquals(650, s.lastTwelve(YearMonth.of(2026, 8)), 0.001);
        assertEquals(325, s.monthlyAverage(YearMonth.of(2026, 8)), 0.001, "two months with spending");
        assertEquals(600, s.merchants.get("Claude.Ai Subscript")[0], 0.001);
        assertEquals(2, (int) s.merchants.get("Claude.Ai Subscript")[1]);
        assertEquals(50, s.merchants.get("Neon.Tech")[0], 0.001);
    }

    @Test
    void axisLabelsDoNotRoundIntoDuplicates() {
        assertEquals("3.5k", CategoryDetailDialog.compactAmount(3500));
        assertEquals("4k", CategoryDetailDialog.compactAmount(4000));
        assertEquals("500", CategoryDetailDialog.compactAmount(500));
        assertEquals("1.2m", CategoryDetailDialog.compactAmount(1_200_000));
    }
}
