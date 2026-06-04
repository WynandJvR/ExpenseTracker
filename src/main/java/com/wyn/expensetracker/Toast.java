package com.wyn.expensetracker;

import javafx.animation.FadeTransition;
import javafx.animation.PauseTransition;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.layout.StackPane;
import javafx.util.Duration;

/**
 * A lightweight, app-wide transient notification shown at the bottom-center of the content area.
 * Used for confirmations (e.g. "Expense added"); errors stay inline near their form.
 */
public final class Toast {

    private Toast() {}

    private static Label label;
    private static PauseTransition hold;

    /** Installs the toast overlay into the given StackPane. Call once after the main view loads. */
    public static void init(StackPane host) {
        if (host == null) return;
        label = new Label();
        label.getStyleClass().add("toast");
        label.setVisible(false);
        label.setManaged(false);
        label.setMouseTransparent(true);
        StackPane.setAlignment(label, Pos.BOTTOM_CENTER);
        StackPane.setMargin(label, new Insets(0, 0, 28, 0));
        host.getChildren().add(label);
    }

    /**
     * Shows {@code message} as a fading toast.
     * @return true if displayed, false if no overlay is installed (caller should fall back to inline messaging).
     */
    public static boolean show(String message) {
        if (label == null) return false;
        label.setText(message);
        label.setManaged(true);
        label.setVisible(true);
        label.toFront();
        label.setOpacity(0);

        FadeTransition in = new FadeTransition(Duration.millis(150), label);
        in.setFromValue(0);
        in.setToValue(1);
        in.play();

        if (hold != null) hold.stop();
        hold = new PauseTransition(Duration.seconds(2.5));
        hold.setOnFinished(e -> {
            FadeTransition out = new FadeTransition(Duration.millis(400), label);
            out.setFromValue(1);
            out.setToValue(0);
            out.setOnFinished(ev -> {
                label.setVisible(false);
                label.setManaged(false);
            });
            out.play();
        });
        hold.play();
        return true;
    }
}
