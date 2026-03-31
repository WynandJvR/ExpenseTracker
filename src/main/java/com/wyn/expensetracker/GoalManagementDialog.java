package com.wyn.expensetracker;

import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.scene.layout.*;
import javafx.stage.Modality;
import javafx.stage.Stage;

import java.io.IOException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.UUID;

public class GoalManagementDialog {

    private final SharedState state;
    private final Stage dialog;
    private final TableView<SavingsGoal> goalTable;
    private final Label errorLabel;

    public GoalManagementDialog(SharedState state) {
        this.state = state;
        this.dialog = new Stage();
        dialog.initModality(Modality.WINDOW_MODAL);
        dialog.initOwner(state.getStage());
        dialog.setTitle("Manage Savings Goals");

        Label header = new Label("Savings Goals");
        header.getStyleClass().add("section-title");

        goalTable = new TableView<>(FXCollections.observableArrayList(state.getSavingsGoals()));
        goalTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);

        TableColumn<SavingsGoal, String> nameCol = new TableColumn<>("Goal");
        nameCol.setCellValueFactory(new PropertyValueFactory<>("name"));

        TableColumn<SavingsGoal, Double> targetCol = new TableColumn<>("Target");
        targetCol.setCellValueFactory(new PropertyValueFactory<>("targetAmount"));
        targetCol.setCellFactory(col -> new TableCell<>() {
            @Override protected void updateItem(Double item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : UIUtils.fmt(item, state.getCurrencySymbol()));
                setAlignment(Pos.CENTER_RIGHT);
            }
        });

        TableColumn<SavingsGoal, Double> savedCol = new TableColumn<>("Saved");
        savedCol.setCellValueFactory(new PropertyValueFactory<>("targetAmount")); // placeholder
        savedCol.setCellFactory(col -> new TableCell<>() {
            @Override protected void updateItem(Double item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || getTableRow() == null || getTableRow().getItem() == null) {
                    setText(null);
                } else {
                    SavingsGoal goal = getTableRow().getItem();
                    double saved = getSavedAmount(goal);
                    setText(UIUtils.fmt(saved, state.getCurrencySymbol()));
                }
                setAlignment(Pos.CENTER_RIGHT);
            }
        });

        TableColumn<SavingsGoal, Double> progressCol = new TableColumn<>("Progress");
        progressCol.setCellValueFactory(new PropertyValueFactory<>("targetAmount")); // placeholder
        progressCol.setCellFactory(col -> new TableCell<>() {
            @Override protected void updateItem(Double item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || getTableRow() == null || getTableRow().getItem() == null) {
                    setGraphic(null); setText(null);
                } else {
                    SavingsGoal goal = getTableRow().getItem();
                    double saved = getSavedAmount(goal);
                    double pct = goal.getTargetAmount() > 0 ? saved / goal.getTargetAmount() : 0;
                    ProgressBar bar = new ProgressBar(Math.min(pct, 1.0));
                    bar.setPrefWidth(80);
                    bar.setStyle(pct >= 1.0 ? "-fx-accent: #43A047;" : "-fx-accent: #5C6BC0;");
                    Label lbl = new Label(String.format("%.0f%%", pct * 100));
                    lbl.setStyle("-fx-text-fill: #E0E0E0; -fx-font-size: 11px;");
                    HBox box = new HBox(6, bar, lbl);
                    box.setAlignment(Pos.CENTER_LEFT);
                    setGraphic(box);
                    setText(null);
                }
            }
        });

        goalTable.getColumns().addAll(nameCol, targetCol, savedCol, progressCol);
        goalTable.setPrefHeight(250);

        errorLabel = new Label();
        errorLabel.getStyleClass().add("error-label");

        Button addGoalBtn = new Button("Add Goal");
        addGoalBtn.getStyleClass().add("success-button");
        addGoalBtn.setOnAction(e -> handleAddGoal());

        Button addContribBtn = new Button("Add Contribution");
        addContribBtn.getStyleClass().add("primary-button");
        addContribBtn.setOnAction(e -> handleAddContribution());

        Button deleteBtn = new Button("Delete Goal");
        deleteBtn.getStyleClass().add("danger-button");
        deleteBtn.setOnAction(e -> handleDeleteGoal());

        Button closeBtn = new Button("Close");
        closeBtn.getStyleClass().add("secondary-button");
        closeBtn.setOnAction(e -> dialog.close());

        HBox buttons = new HBox(10, addGoalBtn, addContribBtn, deleteBtn, new Region(), closeBtn);
        HBox.setHgrow(buttons.getChildren().get(3), Priority.ALWAYS);
        buttons.setAlignment(Pos.CENTER_LEFT);

        VBox content = new VBox(12, header, goalTable, buttons, errorLabel);
        content.setPadding(new Insets(20));
        content.getStyleClass().add("root-pane");

        Scene scene = new Scene(content, 600, 420);
        scene.getStylesheets().add(getClass().getResource("/styles.css").toExternalForm());
        dialog.setScene(scene);
    }

    public void show() {
        dialog.showAndWait();
    }

    private double getSavedAmount(SavingsGoal goal) {
        return state.getGoalContributions().stream()
            .filter(c -> c.getGoalId().equals(goal.getId()))
            .mapToDouble(GoalContribution::getAmount)
            .sum();
    }

    private void handleAddGoal() {
        Stage addDialog = new Stage();
        addDialog.initModality(Modality.WINDOW_MODAL);
        addDialog.initOwner(dialog);
        addDialog.setTitle("Add Savings Goal");

        TextField nameField = new TextField();
        nameField.setPromptText("e.g., Emergency Fund");
        nameField.getStyleClass().add("text-field");

        TextField targetField = new TextField();
        targetField.setPromptText("e.g., 10000");
        targetField.getStyleClass().add("text-field");

        DatePicker deadlinePicker = new DatePicker();
        deadlinePicker.setPromptText("Optional deadline");
        deadlinePicker.getStyleClass().add("date-picker");

        TextField monthlyField = new TextField();
        monthlyField.setPromptText("Monthly target (optional)");
        monthlyField.getStyleClass().add("text-field");

        Label addError = new Label();
        addError.getStyleClass().add("error-label");

        Button confirmBtn = new Button("Create Goal");
        confirmBtn.getStyleClass().add("success-button");
        confirmBtn.setOnAction(e -> {
            String name = nameField.getText().trim();
            if (name.isEmpty()) { addError.setText("Name is required"); return; }
            double target;
            try {
                target = Double.parseDouble(targetField.getText());
                if (target <= 0) { addError.setText("Target must be positive"); return; }
            } catch (NumberFormatException ex) {
                addError.setText("Invalid target amount"); return;
            }
            double monthly = 0;
            if (!monthlyField.getText().trim().isEmpty()) {
                try { monthly = Double.parseDouble(monthlyField.getText()); }
                catch (NumberFormatException ex) { addError.setText("Invalid monthly amount"); return; }
            }

            SavingsGoal goal = new SavingsGoal(
                "GOAL-" + UUID.randomUUID().toString().substring(0, 8),
                name, target, deadlinePicker.getValue(), monthly, LocalDate.now());
            state.getSavingsGoals().add(goal);
            saveGoals();
            goalTable.setItems(FXCollections.observableArrayList(state.getSavingsGoals()));
            goalTable.refresh();
            addDialog.close();
        });

        Button cancelBtn = new Button("Cancel");
        cancelBtn.getStyleClass().add("danger-button");
        cancelBtn.setOnAction(e -> addDialog.close());

        VBox content = new VBox(10,
            new Label("Goal Name:") {{ getStyleClass().add("form-label"); }}, nameField,
            new Label("Target Amount:") {{ getStyleClass().add("form-label"); }}, targetField,
            new Label("Deadline:") {{ getStyleClass().add("form-label"); }}, deadlinePicker,
            new Label("Monthly Target:") {{ getStyleClass().add("form-label"); }}, monthlyField,
            addError, new HBox(10, confirmBtn, cancelBtn));
        content.setPadding(new Insets(20));
        content.getStyleClass().add("root-pane");

        Scene scene = new Scene(content, 380, 440);
        scene.getStylesheets().add(getClass().getResource("/styles.css").toExternalForm());
        addDialog.setScene(scene);
        addDialog.showAndWait();
    }

    private void handleAddContribution() {
        SavingsGoal selected = goalTable.getSelectionModel().getSelectedItem();
        if (selected == null) {
            UIUtils.showMessage("Select a goal first", true, errorLabel);
            return;
        }

        TextInputDialog amountDialog = new TextInputDialog();
        amountDialog.initOwner(dialog);
        amountDialog.setTitle("Add Contribution");
        amountDialog.setHeaderText("Add contribution to: " + selected.getName());
        amountDialog.setContentText("Amount:");
        amountDialog.getDialogPane().getStylesheets().add(getClass().getResource("/styles.css").toExternalForm());

        amountDialog.showAndWait().ifPresent(input -> {
            try {
                double amount = Double.parseDouble(input);
                if (amount <= 0) { UIUtils.showMessage("Amount must be positive", true, errorLabel); return; }
                GoalContribution contrib = new GoalContribution(selected.getId(), amount, LocalDate.now(), "");
                state.getGoalContributions().add(contrib);
                saveContributions();
                goalTable.refresh();
                UIUtils.showMessage("Added " + UIUtils.fmt(amount, state.getCurrencySymbol()) + " to " + selected.getName(), false, errorLabel);
            } catch (NumberFormatException ex) {
                UIUtils.showMessage("Invalid amount", true, errorLabel);
            }
        });
    }

    private void handleDeleteGoal() {
        SavingsGoal selected = goalTable.getSelectionModel().getSelectedItem();
        if (selected == null) {
            UIUtils.showMessage("Select a goal to delete", true, errorLabel);
            return;
        }
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.initOwner(dialog);
        confirm.setTitle("Delete Goal");
        confirm.setHeaderText("Delete \"" + selected.getName() + "\"?");
        confirm.setContentText("This will also remove all contributions.");
        confirm.getDialogPane().getStylesheets().add(getClass().getResource("/styles.css").toExternalForm());
        confirm.showAndWait().ifPresent(result -> {
            if (result == ButtonType.OK) {
                state.getSavingsGoals().remove(selected);
                state.getGoalContributions().removeIf(c -> c.getGoalId().equals(selected.getId()));
                saveGoals();
                saveContributions();
                goalTable.setItems(FXCollections.observableArrayList(state.getSavingsGoals()));
                goalTable.refresh();
            }
        });
    }

    private void saveGoals() {
        try {
            state.getStorage().saveGoals(new ArrayList<>(state.getSavingsGoals()));
        } catch (IOException e) {
            UIUtils.showMessage("Failed to save goals: " + e.getMessage(), true, errorLabel);
        }
    }

    private void saveContributions() {
        try {
            state.getStorage().saveGoalContributions(new ArrayList<>(state.getGoalContributions()));
        } catch (IOException e) {
            UIUtils.showMessage("Failed to save contributions: " + e.getMessage(), true, errorLabel);
        }
    }
}
