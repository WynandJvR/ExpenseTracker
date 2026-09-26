package com.wyn.expensetracker;

import javafx.geometry.HPos;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.*;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.TextStyle;
import java.util.*;

public class CashFlowCalendarView extends VBox {

    private final SharedState state;
    private YearMonth displayedMonth;
    private final Label monthLabel;
    private final GridPane calendarGrid;

    public CashFlowCalendarView(SharedState state) {
        this.state = state;
        this.displayedMonth = state.getSelectedYearMonth() != null ? state.getSelectedYearMonth() : YearMonth.now();

        setSpacing(12);
        setPadding(new Insets(8));

        // Navigation
        Button prevBtn = new Button("\u25C0");
        prevBtn.getStyleClass().add("nav-arrow");
        prevBtn.setOnAction(e -> { displayedMonth = displayedMonth.minusMonths(1); rebuild(); });

        Button nextBtn = new Button("\u25B6");
        nextBtn.getStyleClass().add("nav-arrow");
        nextBtn.setOnAction(e -> { displayedMonth = displayedMonth.plusMonths(1); rebuild(); });

        monthLabel = new Label();
        monthLabel.getStyleClass().add("cal-month-label");

        Button todayBtn = new Button("Today");
        todayBtn.getStyleClass().add("today-button");
        todayBtn.setOnAction(e -> { displayedMonth = YearMonth.now(); rebuild(); });

        Region spacerLeft = new Region();
        Region spacerRight = new Region();
        HBox.setHgrow(spacerLeft, Priority.ALWAYS);
        HBox.setHgrow(spacerRight, Priority.ALWAYS);

        HBox nav = new HBox(8, prevBtn, spacerLeft, monthLabel, todayBtn, spacerRight, nextBtn);
        nav.setAlignment(Pos.CENTER);

        // Calendar grid
        calendarGrid = new GridPane();
        calendarGrid.setHgap(2);
        calendarGrid.setVgap(2);
        for (int i = 0; i < 7; i++) {
            ColumnConstraints cc = new ColumnConstraints();
            cc.setPercentWidth(100.0 / 7);
            cc.setHalignment(HPos.CENTER);
            calendarGrid.getColumnConstraints().add(cc);
        }

        getChildren().addAll(nav, calendarGrid);
        rebuild();
    }

    public void rebuild() {
        monthLabel.setText(displayedMonth.getMonth().getDisplayName(TextStyle.FULL, Locale.ENGLISH)
            + " " + displayedMonth.getYear());
        calendarGrid.getChildren().clear();

        // Day-of-week headers
        String[] dayNames = {"Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"};
        for (int i = 0; i < 7; i++) {
            Label header = new Label(dayNames[i]);
            header.getStyleClass().add("cal-weekday");
            header.setAlignment(Pos.CENTER);
            header.setMaxWidth(Double.MAX_VALUE);
            calendarGrid.add(header, i, 0);
        }

        // Compute recurring expenses/income per day
        Map<LocalDate, List<Expense>> recurringByDay = computeRecurringForMonth();
        double startingBalance = computeStartingBalance(recurringByDay);

        LocalDate firstDay = displayedMonth.atDay(1);
        int startCol = firstDay.getDayOfWeek().getValue() - 1; // Mon=0
        int daysInMonth = displayedMonth.lengthOfMonth();

        double runningBalance = startingBalance;
        int row = 1;
        int col = startCol;

        for (int day = 1; day <= daysInMonth; day++) {
            LocalDate date = displayedMonth.atDay(day);
            List<Expense> dayRecurring = recurringByDay.getOrDefault(date, Collections.emptyList());

            for (Expense r : dayRecurring) {
                runningBalance += signedFlow(r);
            }

            VBox cell = createDayCell(day, dayRecurring, runningBalance, date.equals(LocalDate.now()));

            calendarGrid.add(cell, col, row);
            col++;
            if (col > 6) { col = 0; row++; }
        }
    }

    /**
     * Cash effect of one scheduled occurrence in base currency: income and refunds are
     * inflows, spend is an outflow, excluded items (own-account transfers) are neutral.
     */
    private double signedFlow(Expense r) {
        if (r.isExcluded()) return 0;
        double baseAmt = state.toBase(r);
        if (r.isRefund() || SharedState.isIncomeItem(r)) return baseAmt;
        return -baseAmt;
    }

    private VBox createDayCell(int day, List<Expense> recurring, double balance, boolean isToday) {
        VBox cell = new VBox(2);
        cell.setPadding(new Insets(4));
        cell.setMinHeight(70);
        cell.setAlignment(Pos.TOP_CENTER);

        cell.getStyleClass().add("cal-day");
        if (balance < 0) cell.getStyleClass().add("cal-negative");
        if (isToday) cell.getStyleClass().add("cal-today");

        Label dayLabel = new Label(String.valueOf(day));
        dayLabel.getStyleClass().add("cal-day-number");

        cell.getChildren().add(dayLabel);

        // Show dots for recurring items
        if (!recurring.isEmpty()) {
            HBox dots = new HBox(3);
            dots.setAlignment(Pos.CENTER);
            int shown = 0;
            StringBuilder tooltipText = new StringBuilder();
            for (Expense r : recurring) {
                boolean inflow = signedFlow(r) > 0;
                if (shown < 3) {
                    Label dot = new Label("\u25CF");
                    dot.getStyleClass().addAll("cal-dot", inflow ? "cal-dot-in" : "cal-dot-out");
                    dots.getChildren().add(dot);
                }
                shown++;
                tooltipText.append(inflow ? "+" : "-")
                    .append(UIUtils.fmt(state.toBase(r), state.getCurrencySymbol()))
                    .append(" ").append(r.getDescription() != null ? r.getDescription() : r.getCategory())
                    .append("\n");
            }
            if (shown > 3) {
                Label more = new Label("+" + (shown - 3));
                more.getStyleClass().add("cal-more");
                dots.getChildren().add(more);
            }
            cell.getChildren().add(dots);
            Tooltip tooltip = new Tooltip(tooltipText.toString().trim());
            tooltip.setStyle("-fx-font-size: 12px;");
            Tooltip.install(cell, tooltip);
        }

        // Balance label
        String balText = (balance < 0 ? "-" : "") + UIUtils.fmt(Math.abs(balance), state.getCurrencySymbol());
        Label balLabel = new Label(balText);
        balLabel.getStyleClass().add("cal-balance");
        if (balance < 0) balLabel.getStyleClass().add("cal-balance-negative");
        cell.getChildren().add(balLabel);

        return cell;
    }

    /**
     * Scheduled recurring occurrences in the displayed month, grouped by day. Uses the
     * manager's projection so skip/edit overrides and day-of-month clamping (e.g. the
     * 31st in February) match the rest of the app. Excluded series are dropped.
     */
    private Map<LocalDate, List<Expense>> computeRecurringForMonth() {
        Map<LocalDate, List<Expense>> result = new HashMap<>();
        if (state.getManager() == null) return result;
        for (Expense e : state.getManager().getUpcomingRecurring(
                displayedMonth.atDay(1), displayedMonth.atEndOfMonth())) {
            if (e.isExcluded()) continue;
            result.computeIfAbsent(e.getDate(), k -> new ArrayList<>()).add(e);
        }
        return result;
    }

    /**
     * Opening balance for the month: the month's income (single app-wide figure) less the
     * part that arrives as scheduled recurring income on specific days (added in the grid,
     * so it is not counted twice), less net one-time spend already recorded this month.
     * Imports that stand in for a recurring occurrence are skipped, as that occurrence is
     * already shown on its day.
     */
    private double computeStartingBalance(Map<LocalDate, List<Expense>> recurringByDay) {
        double income = state.incomeForMonth(displayedMonth);
        double scheduledIncome = recurringByDay.values().stream()
            .flatMap(List::stream)
            .filter(SharedState::isIncomeItem)
            .mapToDouble(state::toBase)
            .sum();
        double openingIncome = Math.max(0, income - scheduledIncome);

        List<Expense> oneTime = new ArrayList<>();
        for (Expense e : state.getExpenseList()) {
            if (e.getRecurringId() == null && YearMonth.from(e.getDate()).equals(displayedMonth)
                    && !state.coversRecurring(e)) {
                oneTime.add(e);
            }
        }
        double oneTimeNet = state.netSpend(oneTime);

        return openingIncome - oneTimeNet;
    }
}

