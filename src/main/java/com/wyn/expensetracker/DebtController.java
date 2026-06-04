package com.wyn.expensetracker;

import javafx.collections.FXCollections;
import javafx.fxml.FXML;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;

import java.io.IOException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;

public class DebtController {

    // Summary cards
    @FXML private TextField totalDebtField;
    @FXML private TextField monthlyPaymentsField;
    @FXML private TextField totalInterestField;
    @FXML private TextField totalPaidField;

    // Add form
    @FXML private TitledPane addDebtPane;
    @FXML private TextField debtNameField;
    @FXML private TextField debtPrincipalField;
    @FXML private TextField debtRateField;
    @FXML private TextField debtTermField;
    @FXML private DatePicker debtStartDate;
    @FXML private TextField debtPaymentField;
    @FXML private Button addDebtButton;
    @FXML private Label calculatedPaymentLabel;
    @FXML private Label debtErrorLabel;

    // Debt table
    @FXML private TableView<Debt> debtTable;
    @FXML private TableColumn<Debt, String> debtNameColumn;
    @FXML private TableColumn<Debt, Double> debtPrincipalColumn;
    @FXML private TableColumn<Debt, Double> debtRateColumn;
    @FXML private TableColumn<Debt, Integer> debtTermColumn;
    @FXML private TableColumn<Debt, Double> debtPaymentColumn;
    @FXML private TableColumn<Debt, Double> debtBalanceColumn;
    @FXML private TableColumn<Debt, Double> debtProgressColumn;

    // Payment table
    @FXML private TableView<DebtPayment> paymentTable;
    @FXML private TableColumn<DebtPayment, String> paymentDebtColumn;
    @FXML private TableColumn<DebtPayment, Double> paymentAmountColumn;
    @FXML private TableColumn<DebtPayment, LocalDate> paymentDateColumn;
    @FXML private TableColumn<DebtPayment, String> paymentNoteColumn;

    @FXML private Label debtViewErrorLabel;

    private SharedState state;
    private boolean initialized = false;
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("dd MMM yyyy");

    @FXML
    public void initialize() {}

    public void init(SharedState state) {
        this.state = state;
        if (initialized) return;
        initialized = true;

        debtStartDate.setValue(LocalDate.now());
        setupDebtTable();
        setupPaymentTable();
        setupAutoCalculate();

        // Enter in any add-form field submits (disabled button is ignored until valid)
        UIUtils.submitOnEnter(addDebtButton, debtNameField, debtPrincipalField,
            debtRateField, debtTermField, debtPaymentField);
    }

    public void refresh() {
        if (state == null) return;
        debtTable.setItems(FXCollections.observableArrayList(state.getDebts()));
        refreshPaymentTable();
        updateSummaryCards();
    }

    // ======================== TABLE SETUP ========================

    private void setupDebtTable() {
        debtTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);

        VBox debtEmptyState = new VBox(6);
        debtEmptyState.setAlignment(Pos.CENTER);
        Label debtMsg = new Label("No debts or loans yet.");
        debtMsg.getStyleClass().add("empty-state-label");
        Label debtHint = new Label("Use 'Add New Debt / Loan' above to track one.");
        debtHint.getStyleClass().add("empty-state-hint");
        debtEmptyState.getChildren().addAll(debtMsg, debtHint);
        debtTable.setPlaceholder(debtEmptyState);

        debtPrincipalColumn.setCellFactory(col -> new TableCell<Debt, Double>() {
            @Override
            protected void updateItem(Double item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || getTableRow() == null || getTableRow().getItem() == null) {
                    setText(null);
                } else {
                    Debt debt = getTableRow().getItem();
                    setText(fmtDebt(debt.getPrincipal(), debt));
                    setAlignment(Pos.CENTER_RIGHT);
                }
            }
        });

        debtRateColumn.setCellFactory(col -> new TableCell<Debt, Double>() {
            @Override
            protected void updateItem(Double item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : String.format("%.1f%%", item));
                setAlignment(Pos.CENTER);
            }
        });

        debtTermColumn.setCellFactory(col -> new TableCell<Debt, Integer>() {
            @Override
            protected void updateItem(Integer item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                } else {
                    int years = item / 12;
                    int months = item % 12;
                    setText(years > 0 ? String.format("%dy %dm", years, months) : months + "m");
                    setAlignment(Pos.CENTER);
                }
            }
        });

        debtPaymentColumn.setCellFactory(col -> new TableCell<Debt, Double>() {
            @Override
            protected void updateItem(Double item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || getTableRow() == null || getTableRow().getItem() == null) {
                    setText(null);
                } else {
                    Debt debt = getTableRow().getItem();
                    double payment = debt.getMonthlyPayment() > 0 ? debt.getMonthlyPayment() : debt.calculateMonthlyPayment();
                    setText(fmtDebt(payment, debt));
                    setAlignment(Pos.CENTER_RIGHT);
                }
            }
        });

        debtBalanceColumn.setCellFactory(col -> new TableCell<Debt, Double>() {
            @Override
            protected void updateItem(Double item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || getTableRow() == null || getTableRow().getItem() == null) {
                    setText(null);
                } else {
                    Debt debt = getTableRow().getItem();
                    double totalPaid = getTotalPaidForDebt(debt.getId());
                    double balance = debt.getRemainingBalance(totalPaid);
                    setText(fmtDebt(balance, debt));
                    setAlignment(Pos.CENTER_RIGHT);
                    if (balance <= 0.01) {
                        setStyle("-fx-text-fill: #26DE81; -fx-font-weight: bold;");
                    } else {
                        setStyle("");
                    }
                }
            }
        });

        debtProgressColumn.setCellFactory(col -> new TableCell<Debt, Double>() {
            @Override
            protected void updateItem(Double item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || getTableRow() == null || getTableRow().getItem() == null) {
                    setGraphic(null);
                    setText(null);
                } else {
                    Debt debt = getTableRow().getItem();
                    double totalPaid = getTotalPaidForDebt(debt.getId());
                    double totalCost = debt.getTotalCost();
                    double progress = totalCost > 0 ? Math.min(totalPaid / totalCost, 1.0) : 0;

                    ProgressBar bar = new ProgressBar(progress);
                    bar.setPrefWidth(100);
                    bar.setPrefHeight(16);
                    bar.setStyle(progress >= 1.0
                        ? "-fx-accent: #26DE81;"
                        : "-fx-accent: #5C6BC0;");

                    Label pctLabel = new Label(String.format("%.0f%%", progress * 100));
                    pctLabel.setStyle("-fx-text-fill: #E0E0E0; -fx-font-size: 11px;");
                    pctLabel.setMinWidth(40);

                    HBox box = new HBox(6, bar, pctLabel);
                    box.setAlignment(Pos.CENTER_LEFT);
                    setGraphic(box);
                    setText(null);
                }
            }
        });
    }

    private void setupPaymentTable() {
        paymentTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);

        VBox paymentEmptyState = new VBox(6);
        paymentEmptyState.setAlignment(Pos.CENTER);
        Label paymentMsg = new Label("No payments recorded yet.");
        paymentMsg.getStyleClass().add("empty-state-label");
        Label paymentHint = new Label("Select a debt above and use 'Record Payment'.");
        paymentHint.getStyleClass().add("empty-state-hint");
        paymentEmptyState.getChildren().addAll(paymentMsg, paymentHint);
        paymentTable.setPlaceholder(paymentEmptyState);

        paymentDebtColumn.setCellFactory(col -> new TableCell<DebtPayment, String>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                } else {
                    String debtName = state.getDebts().stream()
                        .filter(d -> d.getId().equals(item))
                        .map(Debt::getName)
                        .findFirst().orElse(item);
                    setText(debtName);
                }
            }
        });

        paymentAmountColumn.setCellFactory(col -> new TableCell<DebtPayment, Double>() {
            @Override
            protected void updateItem(Double item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : UIUtils.fmt(item, state.getCurrencySymbol()));
                setAlignment(Pos.CENTER_RIGHT);
            }
        });

        paymentDateColumn.setCellFactory(col -> new TableCell<DebtPayment, LocalDate>() {
            @Override
            protected void updateItem(LocalDate item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : item.format(DATE_FORMAT));
                setAlignment(Pos.CENTER);
            }
        });
    }

    private void refreshPaymentTable() {
        List<DebtPayment> sorted = new ArrayList<>(state.getDebtPayments());
        sorted.sort(Comparator.comparing(DebtPayment::getDate).reversed());
        paymentTable.setItems(FXCollections.observableArrayList(sorted));
    }

    // ======================== AUTO-CALCULATE PREVIEW ========================

    private void setupAutoCalculate() {
        javafx.beans.value.ChangeListener<String> calcListener = (obs, o, n) -> {
            updateCalculatedPayment();
            validateAddForm();
        };
        debtPrincipalField.textProperty().addListener(calcListener);
        debtRateField.textProperty().addListener(calcListener);
        debtTermField.textProperty().addListener(calcListener);
        validateAddForm();
    }

    /** Disables "Add Debt" until principal and term are valid positive numbers; flags bad input inline. */
    private void validateAddForm() {
        boolean principalOk = UIUtils.isPositiveDouble(debtPrincipalField.getText());
        boolean termOk = UIUtils.isPositiveInt(debtTermField.getText());
        addDebtButton.setDisable(!(principalOk && termOk));
        UIUtils.markValidity(debtPrincipalField, principalOk);
        UIUtils.markValidity(debtTermField, termOk);
    }

    private void updateCalculatedPayment() {
        try {
            double principal = Double.parseDouble(debtPrincipalField.getText());
            double rate = Double.parseDouble(debtRateField.getText());
            int term = Integer.parseInt(debtTermField.getText());
            if (principal > 0 && rate >= 0 && term > 0) {
                double monthlyRate = rate / 100.0 / 12.0;
                double payment;
                if (monthlyRate == 0) {
                    payment = principal / term;
                } else {
                    payment = principal * (monthlyRate * Math.pow(1 + monthlyRate, term))
                        / (Math.pow(1 + monthlyRate, term) - 1);
                }
                calculatedPaymentLabel.setText(String.format("Calculated payment: %s/month  |  Total: %s  |  Interest: %s",
                    UIUtils.fmt(payment, state.getCurrencySymbol()),
                    UIUtils.fmt(payment * term, state.getCurrencySymbol()),
                    UIUtils.fmt(payment * term - principal, state.getCurrencySymbol())));
            } else {
                calculatedPaymentLabel.setText("");
            }
        } catch (NumberFormatException e) {
            calculatedPaymentLabel.setText("");
        }
    }

    // ======================== SUMMARY CARDS ========================

    private void updateSummaryCards() {
        double totalDebt = 0;
        double totalMonthly = 0;
        double totalInterest = 0;
        double totalPaid = 0;

        for (Debt debt : state.getDebts()) {
            double paid = getTotalPaidForDebt(debt.getId());
            double balance = debt.getRemainingBalance(paid);
            double payment = debt.getMonthlyPayment() > 0 ? debt.getMonthlyPayment() : debt.calculateMonthlyPayment();

            totalDebt += balance;
            if (balance > 0.01) totalMonthly += payment;
            totalInterest += debt.getTotalInterest();
            totalPaid += paid;
        }

        totalDebtField.setText(UIUtils.fmt(totalDebt, state.getCurrencySymbol()));
        monthlyPaymentsField.setText(UIUtils.fmt(totalMonthly, state.getCurrencySymbol()));
        totalInterestField.setText(UIUtils.fmt(totalInterest, state.getCurrencySymbol()));
        totalPaidField.setText(UIUtils.fmt(totalPaid, state.getCurrencySymbol()));
    }

    // ======================== HANDLERS ========================

    @FXML
    private void handleAddDebt() {
        try {
            String name = debtNameField.getText().trim();
            if (name.isEmpty()) { showMsg("Name is required", true); return; }

            double principal = Double.parseDouble(debtPrincipalField.getText());
            if (principal <= 0) { showMsg("Principal must be positive", true); return; }

            double rate = Double.parseDouble(debtRateField.getText());
            if (rate < 0) { showMsg("Rate cannot be negative", true); return; }

            int term = Integer.parseInt(debtTermField.getText());
            if (term <= 0) { showMsg("Term must be positive", true); return; }

            LocalDate startDate = debtStartDate.getValue();
            if (startDate == null) { showMsg("Start date is required", true); return; }

            double monthlyPayment = 0;
            String paymentText = debtPaymentField.getText().trim();
            if (!paymentText.isEmpty()) {
                monthlyPayment = Double.parseDouble(paymentText);
                if (monthlyPayment <= 0) { showMsg("Payment must be positive", true); return; }
            }

            String id = UUID.randomUUID().toString().substring(0, 8);
            Debt debt = new Debt(id, name, principal, rate, term, startDate, "MONTHLY", monthlyPayment,
                state.getCurrencyManager().getBaseCurrency());

            if (monthlyPayment == 0) {
                debt.setMonthlyPayment(debt.calculateMonthlyPayment());
            }

            state.getDebts().add(debt);
            saveDebts();
            refresh();
            resetDebtForm();
            showMsg(String.format("Added %s — %s/month for %d months", name,
                UIUtils.fmt(debt.getMonthlyPayment(), state.getCurrencySymbol()), term), false);
        } catch (NumberFormatException e) {
            showMsg("Invalid number format", true);
        }
    }

    @FXML
    private void handleDeleteDebt() {
        Debt selected = debtTable.getSelectionModel().getSelectedItem();
        if (selected == null) { showMsg("Select a debt to delete", true); return; }

        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.initOwner(state.getStage());
        confirm.setTitle("Delete Debt");
        confirm.setHeaderText("Delete " + selected.getName() + "?");
        confirm.setContentText("This will also delete all payment records for this debt.");
        confirm.getDialogPane().getStylesheets().add(getClass().getResource("/styles.css").toExternalForm());

        confirm.showAndWait().ifPresent(result -> {
            if (result == ButtonType.OK) {
                state.getDebts().remove(selected);
                state.getDebtPayments().removeIf(p -> p.getDebtId().equals(selected.getId()));
                saveDebts();
                savePayments();
                refresh();
                showMsg("Debt deleted", false);
            }
        });
    }

    @FXML
    private void handleEditDebt() {
        Debt selected = debtTable.getSelectionModel().getSelectedItem();
        if (selected == null) { showMsg("Select a debt to edit", true); return; }

        Stage dialog = new Stage();
        dialog.initModality(Modality.WINDOW_MODAL);
        dialog.initOwner(state.getStage());
        dialog.setTitle("Edit Debt — " + selected.getName());

        Label nameLabel = new Label("Name:");
        nameLabel.getStyleClass().add("form-label");
        TextField nameField = new TextField(selected.getName());
        nameField.getStyleClass().add("text-field");

        Label principalLabel = new Label("Principal:");
        principalLabel.getStyleClass().add("form-label");
        TextField principalField = new TextField(String.valueOf(selected.getPrincipal()));
        principalField.getStyleClass().add("text-field");

        Label rateLabel = new Label("Annual Rate (%):");
        rateLabel.getStyleClass().add("form-label");
        TextField rateField = new TextField(String.valueOf(selected.getAnnualRate()));
        rateField.getStyleClass().add("text-field");

        Label termLabel = new Label("Term (months):");
        termLabel.getStyleClass().add("form-label");
        TextField termField = new TextField(String.valueOf(selected.getTermMonths()));
        termField.getStyleClass().add("text-field");

        Label paymentLabel = new Label("Monthly Payment:");
        paymentLabel.getStyleClass().add("form-label");
        TextField paymentField = new TextField(String.valueOf(selected.getMonthlyPayment()));
        paymentField.getStyleClass().add("text-field");

        Label errorLabel = new Label();
        errorLabel.getStyleClass().add("error-label");

        Button saveBtn = new Button("Save Changes");
        saveBtn.getStyleClass().add("success-button");
        saveBtn.setOnAction(e -> {
            try {
                String name = nameField.getText().trim();
                if (name.isEmpty()) { errorLabel.setText("Name is required"); return; }
                double principal = Double.parseDouble(principalField.getText());
                if (principal <= 0) { errorLabel.setText("Principal must be positive"); return; }
                double rate = Double.parseDouble(rateField.getText());
                if (rate < 0) { errorLabel.setText("Rate cannot be negative"); return; }
                int term = Integer.parseInt(termField.getText());
                if (term <= 0) { errorLabel.setText("Term must be positive"); return; }
                double payment = Double.parseDouble(paymentField.getText());
                if (payment < 0) { errorLabel.setText("Payment cannot be negative"); return; }

                selected.setName(name);
                selected.setPrincipal(principal);
                selected.setAnnualRate(rate);
                selected.setTermMonths(term);
                selected.setMonthlyPayment(payment > 0 ? payment : selected.calculateMonthlyPayment());
                saveDebts();
                refresh();
                showMsg("Debt updated", false);
                dialog.close();
            } catch (NumberFormatException ex) {
                errorLabel.setText("Invalid number format");
            }
        });

        Button cancelBtn = new Button("Cancel");
        cancelBtn.getStyleClass().add("danger-button");
        cancelBtn.setOnAction(e -> dialog.close());

        HBox buttons = new HBox(10, saveBtn, cancelBtn);
        buttons.setAlignment(Pos.CENTER_RIGHT);

        VBox content = new VBox(10,
            nameLabel, nameField,
            principalLabel, principalField,
            rateLabel, rateField,
            termLabel, termField,
            paymentLabel, paymentField,
            errorLabel, buttons);
        content.setPadding(new Insets(20));
        content.getStyleClass().add("root-pane");

        Scene scene = new Scene(content, 400, 520);
        scene.getStylesheets().add(getClass().getResource("/styles.css").toExternalForm());
        dialog.setScene(scene);
        dialog.showAndWait();
    }

    @FXML
    private void handleRecordPayment() {
        Debt selected = debtTable.getSelectionModel().getSelectedItem();
        if (selected == null) { showMsg("Select a debt first", true); return; }

        Stage dialog = new Stage();
        dialog.initModality(Modality.WINDOW_MODAL);
        dialog.initOwner(state.getStage());
        dialog.setTitle("Record Payment — " + selected.getName());

        Label amountLabel = new Label("Payment Amount:");
        amountLabel.getStyleClass().add("form-label");
        TextField amountField = new TextField();
        amountField.setPromptText("e.g., " + String.format("%.2f", selected.getMonthlyPayment()));
        amountField.getStyleClass().add("text-field");

        Label dateLabel = new Label("Date:");
        dateLabel.getStyleClass().add("form-label");
        DatePicker datePicker = new DatePicker(LocalDate.now());
        datePicker.getStyleClass().add("date-picker");
        datePicker.setMaxWidth(Double.MAX_VALUE);

        Label noteLabel = new Label("Note (optional):");
        noteLabel.getStyleClass().add("form-label");
        TextField noteField = new TextField();
        noteField.setPromptText("e.g., Regular monthly payment");
        noteField.getStyleClass().add("text-field");

        Label errorLabel = new Label();
        errorLabel.getStyleClass().add("error-label");

        Button confirmBtn = new Button("Record Payment");
        confirmBtn.getStyleClass().add("success-button");
        confirmBtn.setOnAction(e -> {
            try {
                double amount = Double.parseDouble(amountField.getText());
                if (amount <= 0) { errorLabel.setText("Amount must be positive"); return; }
                LocalDate date = datePicker.getValue();
                if (date == null) { errorLabel.setText("Date is required"); return; }

                DebtPayment payment = new DebtPayment(selected.getId(), amount, date, noteField.getText().trim());
                state.getDebtPayments().add(payment);
                savePayments();
                refresh();
                showMsg(String.format("Recorded %s payment for %s",
                    UIUtils.fmt(amount, state.getCurrencySymbol()), selected.getName()), false);
                dialog.close();
            } catch (NumberFormatException ex) {
                errorLabel.setText("Invalid amount");
            }
        });

        Button cancelBtn = new Button("Cancel");
        cancelBtn.getStyleClass().add("danger-button");
        cancelBtn.setOnAction(e -> dialog.close());

        HBox buttons = new HBox(10, confirmBtn, cancelBtn);
        buttons.setAlignment(Pos.CENTER_RIGHT);

        VBox content = new VBox(10, amountLabel, amountField, dateLabel, datePicker,
            noteLabel, noteField, errorLabel, buttons);
        content.setPadding(new Insets(20));
        content.getStyleClass().add("root-pane");

        Scene scene = new Scene(content, 400, 380);
        scene.getStylesheets().add(getClass().getResource("/styles.css").toExternalForm());
        dialog.setScene(scene);
        dialog.showAndWait();
    }

    @FXML
    private void handleViewSchedule() {
        Debt selected = debtTable.getSelectionModel().getSelectedItem();
        if (selected == null) { showMsg("Select a debt first", true); return; }

        List<Debt.AmortizationEntry> schedule = selected.getAmortizationSchedule();
        double totalPaid = getTotalPaidForDebt(selected.getId());

        TableView<Debt.AmortizationEntry> scheduleTable = new TableView<>();
        scheduleTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);

        TableColumn<Debt.AmortizationEntry, Integer> monthCol = new TableColumn<>("Month");
        monthCol.setCellValueFactory(cellData ->
            new javafx.beans.property.SimpleObjectProperty<>(cellData.getValue().month));
        monthCol.setPrefWidth(60);

        TableColumn<Debt.AmortizationEntry, String> dateCol = new TableColumn<>("Date");
        dateCol.setCellValueFactory(cellData ->
            new javafx.beans.property.SimpleStringProperty(cellData.getValue().date.format(DATE_FORMAT)));
        dateCol.setPrefWidth(100);

        TableColumn<Debt.AmortizationEntry, String> paymentCol = new TableColumn<>("Payment");
        paymentCol.setCellValueFactory(cellData ->
            new javafx.beans.property.SimpleStringProperty(fmtDebt(cellData.getValue().payment, selected)));
        paymentCol.setPrefWidth(100);

        TableColumn<Debt.AmortizationEntry, String> principalCol = new TableColumn<>("Principal");
        principalCol.setCellValueFactory(cellData ->
            new javafx.beans.property.SimpleStringProperty(fmtDebt(cellData.getValue().principal, selected)));
        principalCol.setPrefWidth(100);

        TableColumn<Debt.AmortizationEntry, String> interestCol = new TableColumn<>("Interest");
        interestCol.setCellValueFactory(cellData ->
            new javafx.beans.property.SimpleStringProperty(fmtDebt(cellData.getValue().interest, selected)));
        interestCol.setPrefWidth(100);

        TableColumn<Debt.AmortizationEntry, String> balanceCol = new TableColumn<>("Balance");
        balanceCol.setCellValueFactory(cellData ->
            new javafx.beans.property.SimpleStringProperty(fmtDebt(cellData.getValue().remainingBalance, selected)));
        balanceCol.setPrefWidth(100);

        scheduleTable.getColumns().addAll(monthCol, dateCol, paymentCol, principalCol, interestCol, balanceCol);
        scheduleTable.setItems(FXCollections.observableArrayList(schedule));
        scheduleTable.setPrefHeight(500);

        // Highlight rows based on current progress
        scheduleTable.setRowFactory(tv -> new TableRow<Debt.AmortizationEntry>() {
            @Override
            protected void updateItem(Debt.AmortizationEntry item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setStyle("");
                } else {
                    double cumulativeScheduled = item.month * (selected.getMonthlyPayment() > 0
                        ? selected.getMonthlyPayment() : selected.calculateMonthlyPayment());
                    if (cumulativeScheduled <= totalPaid) {
                        setStyle("-fx-background-color: rgba(38, 222, 129, 0.1);"); // paid
                    } else {
                        setStyle("");
                    }
                }
            }
        });

        Label header = new Label(String.format("%s — Amortization Schedule  |  Total: %s  |  Interest: %s",
            selected.getName(),
            fmtDebt(selected.getTotalCost(), selected),
            fmtDebt(selected.getTotalInterest(), selected)));
        header.getStyleClass().add("section-title");
        header.setWrapText(true);

        VBox content = new VBox(12, header, scheduleTable);
        content.setPadding(new Insets(20));
        content.getStyleClass().add("root-pane");

        Alert dialog = new Alert(Alert.AlertType.NONE);
        dialog.initOwner(state.getStage());
        dialog.setTitle("Amortization Schedule — " + selected.getName());
        dialog.getDialogPane().setContent(content);
        dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        dialog.getDialogPane().getStylesheets().add(getClass().getResource("/styles.css").toExternalForm());
        dialog.getDialogPane().setPrefSize(700, 600);
        dialog.showAndWait();
    }

    // ======================== HELPERS ========================

    private double getTotalPaidForDebt(String debtId) {
        return state.getDebtPayments().stream()
            .filter(p -> p.getDebtId().equals(debtId))
            .mapToDouble(DebtPayment::getAmount)
            .sum();
    }

    private String fmtDebt(double amount, Debt debt) {
        String currency = debt.getCurrency();
        if (currency != null && CurrencyManager.CURRENCIES.containsKey(currency)) {
            return CurrencyManager.fmt(amount, currency);
        }
        return UIUtils.fmt(amount, state.getCurrencySymbol());
    }

    private void resetDebtForm() {
        debtNameField.clear();
        debtPrincipalField.clear();
        debtRateField.clear();
        debtTermField.clear();
        debtStartDate.setValue(LocalDate.now());
        debtPaymentField.clear();
        calculatedPaymentLabel.setText("");
        addDebtPane.setExpanded(false);
    }

    private void saveDebts() {
        try {
            state.getStorage().saveDebts(new ArrayList<>(state.getDebts()));
        } catch (IOException e) {
            showMsg("Error saving debts: " + e.getMessage(), true);
        }
    }

    private void savePayments() {
        try {
            state.getStorage().saveDebtPayments(new ArrayList<>(state.getDebtPayments()));
        } catch (IOException e) {
            showMsg("Error saving payments: " + e.getMessage(), true);
        }
    }

    private void showMsg(String message, boolean isError) {
        UIUtils.showMessage(message, isError, debtErrorLabel);
        if (debtViewErrorLabel != null) {
            UIUtils.showMessage(message, isError, debtViewErrorLabel);
        }
    }
}
