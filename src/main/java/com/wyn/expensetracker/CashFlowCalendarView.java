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
        monthLabel.setStyle("-fx-text-fill: #FFFFFF; -fx-font-size: 18px; -fx-font-weight: bold;");

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
            header.setStyle("-fx-text-fill: #A0A0A0; -fx-font-size: 11px; -fx-font-weight: bold;");
            header.setAlignment(Pos.CENTER);
            header.setMaxWidth(Double.MAX_VALUE);
            calendarGrid.add(header, i, 0);
        }

        // Compute recurring expenses/income per day
        Map<LocalDate, List<RecurringExpense>> recurringByDay = computeRecurringForMonth();
        double startingBalance = computeStartingBalance();

        LocalDate firstDay = displayedMonth.atDay(1);
        int startCol = firstDay.getDayOfWeek().getValue() - 1; // Mon=0
        int daysInMonth = displayedMonth.lengthOfMonth();

        double runningBalance = startingBalance;
        int row = 1;
        int col = startCol;

        for (int day = 1; day <= daysInMonth; day++) {
            LocalDate date = displayedMonth.atDay(day);
            List<RecurringExpense> dayRecurring = recurringByDay.getOrDefault(date, Collections.emptyList());

            double dayIncome = 0;
            double dayExpense = 0;
            for (RecurringExpense r : dayRecurring) {
                double baseAmt = state.getCurrencyManager().toBase(r.getAmount(), r.getCurrency());
                if (r.isIncome()) dayIncome += baseAmt;
                else dayExpense += baseAmt;
            }
            runningBalance += dayIncome - dayExpense;

            VBox cell = createDayCell(day, dayRecurring, runningBalance, date.equals(LocalDate.now()));

            calendarGrid.add(cell, col, row);
            col++;
            if (col > 6) { col = 0; row++; }
        }
    }

    private VBox createDayCell(int day, List<RecurringExpense> recurring, double balance, boolean isToday) {
        VBox cell = new VBox(2);
        cell.setPadding(new Insets(4));
        cell.setMinHeight(70);
        cell.setAlignment(Pos.TOP_CENTER);

        String bgColor = balance < 0 ? "rgba(229, 57, 53, 0.15)" : "rgba(76, 175, 80, 0.08)";
        String borderColor = isToday ? "#5C6BC0" : "#3A3A3A";
        int borderWidth = isToday ? 2 : 1;
        cell.setStyle(String.format(
            "-fx-background-color: %s; -fx-border-color: %s; -fx-border-width: %d; "
            + "-fx-border-radius: 4; -fx-background-radius: 4;", bgColor, borderColor, borderWidth));

        Label dayLabel = new Label(String.valueOf(day));
        dayLabel.setStyle("-fx-text-fill: " + (isToday ? "#5C6BC0" : "#E0E0E0") + "; -fx-font-size: 12px; -fx-font-weight: bold;");

        cell.getChildren().add(dayLabel);

        // Show dots for recurring items
        if (!recurring.isEmpty()) {
            HBox dots = new HBox(3);
            dots.setAlignment(Pos.CENTER);
            int shown = 0;
            StringBuilder tooltipText = new StringBuilder();
            for (RecurringExpense r : recurring) {
                if (shown < 3) {
                    Label dot = new Label("\u25CF");
                    dot.setStyle("-fx-text-fill: " + (r.isIncome() ? "#43A047" : "#EF5350") + "; -fx-font-size: 8px;");
                    dots.getChildren().add(dot);
                }
                shown++;
                tooltipText.append(r.isIncome() ? "+" : "-")
                    .append(UIUtils.fmt(r.getAmount(), state.getCurrencySymbol()))
                    .append(" ").append(r.getDescription() != null ? r.getDescription() : r.getCategory())
                    .append("\n");
            }
            if (shown > 3) {
                Label more = new Label("+" + (shown - 3));
                more.setStyle("-fx-text-fill: #A0A0A0; -fx-font-size: 8px;");
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
        balLabel.setStyle("-fx-text-fill: " + (balance < 0 ? "#EF5350" : "#A0A0A0") + "; -fx-font-size: 9px;");
        cell.getChildren().add(balLabel);

        return cell;
    }

    private Map<LocalDate, List<RecurringExpense>> computeRecurringForMonth() {
        Map<LocalDate, List<RecurringExpense>> result = new HashMap<>();
        List<RecurringExpense> recurring = state.getRecurringList();

        for (RecurringExpense r : recurring) {
            LocalDate start = r.getDate();
            LocalDate end = r.getEndDate();
            if (end != null && end.isBefore(displayedMonth.atDay(1))) continue;
            if (start.isAfter(displayedMonth.atEndOfMonth())) continue;

            // Fast-forward to at or near the displayed month to avoid iterating from years ago
            LocalDate current = start;
            LocalDate monthStart = displayedMonth.atDay(1);
            while (current.isBefore(monthStart)) {
                LocalDate next = advanceByFrequency(current, r.getFrequency());
                if (next == null || !next.isAfter(current)) break;
                if (!next.isBefore(monthStart)) { current = current; break; }
                current = next;
            }

            // Generate occurrences in this month
            while (!current.isAfter(displayedMonth.atEndOfMonth())) {
                if (!current.isBefore(monthStart) && YearMonth.from(current).equals(displayedMonth)) {
                    if (end == null || !current.isAfter(end)) {
                        result.computeIfAbsent(current, k -> new ArrayList<>()).add(r);
                    }
                }
                current = advanceByFrequency(current, r.getFrequency());
                if (current == null) break;
            }
        }
        return result;
    }

    private LocalDate advanceByFrequency(LocalDate date, RecurrenceType freq) {
        return switch (freq) {
            case DAILY -> date.plusDays(1);
            case WEEKLY -> date.plusWeeks(1);
            case BIWEEKLY -> date.plusWeeks(2);
            case MONTHLY -> date.plusMonths(1);
            case QUARTERLY -> date.plusMonths(3);
            case YEARLY -> date.plusYears(1);
        };
    }

    private double computeStartingBalance() {
        // Monthly income minus any non-recurring expenses already recorded this month
        double income = state.getRecurringIncome();
        Double monthIncome = state.getIncomes().get(displayedMonth);
        if (monthIncome != null && monthIncome > 0) income = monthIncome;

        // Subtract one-time expenses already recorded this month for a more realistic picture
        double oneTimeExpenses = state.getExpenseList().stream()
            .filter(e -> !e.isExcluded() && !e.isIncome() && e.getRecurringId() == null
                && YearMonth.from(e.getDate()).equals(displayedMonth))
            .mapToDouble(e -> state.getCurrencyManager().toBase(e.getAmount(), e.getCurrency())).sum();

        return income - oneTimeExpenses;
    }
}
