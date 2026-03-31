package com.wyn.expensetracker;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.*;
import javafx.stage.Modality;
import javafx.stage.Stage;

import java.util.*;

public class TagEditorPopup {

    private final Stage dialog;
    private final Set<String> selectedTags;
    private boolean confirmed = false;

    public TagEditorPopup(Stage owner, Set<String> currentTags, List<String> allTags) {
        this.selectedTags = new LinkedHashSet<>(currentTags);
        this.dialog = new Stage();
        dialog.initModality(Modality.WINDOW_MODAL);
        dialog.initOwner(owner);
        dialog.setTitle("Manage Tags");

        Label header = new Label("Select or create tags:");
        header.getStyleClass().add("section-title");

        FlowPane tagFlow = new FlowPane(8, 8);
        tagFlow.setPadding(new Insets(8));

        // Build toggle chips for all known tags
        for (String tag : allTags) {
            tagFlow.getChildren().add(createTagChip(tag));
        }

        ScrollPane scrollPane = new ScrollPane(tagFlow);
        scrollPane.setFitToWidth(true);
        scrollPane.setPrefHeight(200);
        scrollPane.getStyleClass().add("scroll-pane");

        // New tag input
        HBox addRow = new HBox(8);
        addRow.setAlignment(Pos.CENTER_LEFT);
        TextField newTagField = new TextField();
        newTagField.setPromptText("New tag name...");
        newTagField.getStyleClass().add("text-field");
        HBox.setHgrow(newTagField, Priority.ALWAYS);
        Button addBtn = new Button("Add Tag");
        addBtn.getStyleClass().add("primary-button");
        addBtn.setOnAction(e -> {
            String tag = newTagField.getText().trim()
                .replace("|", "").replace(",", "").replace("\n", "").replace("\r", "");
            if (!tag.isEmpty()) {
                selectedTags.add(tag);
                // Add chip if not already present
                boolean exists = tagFlow.getChildren().stream()
                    .anyMatch(n -> n.getUserData() != null && n.getUserData().equals(tag));
                if (!exists) {
                    tagFlow.getChildren().add(createTagChip(tag));
                }
                newTagField.clear();
                // Refresh all chips
                refreshChips(tagFlow);
            }
        });
        newTagField.setOnKeyPressed(e -> { if (e.getCode() == KeyCode.ENTER) addBtn.fire(); });
        addRow.getChildren().addAll(newTagField, addBtn);

        // Buttons
        Button okBtn = new Button("OK");
        okBtn.getStyleClass().add("success-button");
        okBtn.setOnAction(e -> { confirmed = true; dialog.close(); });
        Button cancelBtn = new Button("Cancel");
        cancelBtn.getStyleClass().add("danger-button");
        cancelBtn.setOnAction(e -> dialog.close());
        HBox buttons = new HBox(10, okBtn, cancelBtn);
        buttons.setAlignment(Pos.CENTER_RIGHT);

        VBox content = new VBox(12, header, scrollPane, addRow, buttons);
        content.setPadding(new Insets(20));
        content.getStyleClass().add("root-pane");

        Scene scene = new Scene(content, 420, 380);
        scene.getStylesheets().add(getClass().getResource("/styles.css").toExternalForm());
        dialog.setScene(scene);
    }

    private HBox createTagChip(String tag) {
        HBox chip = new HBox(4);
        chip.setAlignment(Pos.CENTER_LEFT);
        chip.setUserData(tag);

        boolean isSelected = selectedTags.contains(tag);
        Label label = new Label(tag);
        label.setStyle("-fx-text-fill: " + (isSelected ? "#FFFFFF" : "#9FA8DA") + "; -fx-font-size: 12px;");

        chip.setStyle(isSelected
            ? "-fx-background-color: #5C6BC0; -fx-padding: 5 12; -fx-background-radius: 16; -fx-cursor: hand;"
            : "-fx-background-color: rgba(92, 107, 192, 0.15); -fx-padding: 5 12; -fx-background-radius: 16; -fx-border-color: rgba(92, 107, 192, 0.3); -fx-border-radius: 16; -fx-border-width: 1; -fx-cursor: hand;");

        chip.getChildren().add(label);
        chip.setOnMouseClicked(e -> {
            if (selectedTags.contains(tag)) {
                selectedTags.remove(tag);
            } else {
                selectedTags.add(tag);
            }
            if (chip.getParent() instanceof FlowPane flow) {
                refreshChips(flow);
            }
        });
        return chip;
    }

    private void refreshChips(FlowPane tagFlow) {
        for (javafx.scene.Node node : tagFlow.getChildren()) {
            if (node instanceof HBox chip && chip.getUserData() instanceof String tag) {
                boolean isSelected = selectedTags.contains(tag);
                Label label = (Label) chip.getChildren().get(0);
                label.setStyle("-fx-text-fill: " + (isSelected ? "#FFFFFF" : "#9FA8DA") + "; -fx-font-size: 12px;");
                chip.setStyle(isSelected
                    ? "-fx-background-color: #5C6BC0; -fx-padding: 5 12; -fx-background-radius: 16; -fx-cursor: hand;"
                    : "-fx-background-color: rgba(92, 107, 192, 0.15); -fx-padding: 5 12; -fx-background-radius: 16; -fx-border-color: rgba(92, 107, 192, 0.3); -fx-border-radius: 16; -fx-border-width: 1; -fx-cursor: hand;");
            }
        }
    }

    public Set<String> showAndWait() {
        dialog.showAndWait();
        return confirmed ? selectedTags : null;
    }
}
