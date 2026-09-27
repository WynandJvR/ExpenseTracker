package com.wyn.expensetracker;

import javafx.animation.PauseTransition;
import javafx.geometry.BoundingBox;
import javafx.geometry.Bounds;
import javafx.geometry.Rectangle2D;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Control;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TitledPane;
import javafx.scene.control.Tooltip;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.ScrollEvent;
import javafx.stage.Screen;
import javafx.util.Duration;

import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.function.Supplier;

/**
 * Hover text that doesn't flicker.
 *
 * <p>JavaFX's own tooltips open a few pixels below-right of the cursor and stay put. With a short
 * show delay the mouse is often still moving, slides onto the tooltip, the node underneath sees
 * "mouse exited", the tooltip hides, the mouse is back on the node, it shows again — a loop that
 * reads as flicker. Near the screen edge JavaFX also nudges the tooltip back under the cursor.
 *
 * <p>Here the tooltip follows the cursor, is placed so it never sits under it (flipping left/up at
 * the screen edge), and an "exit" onto the tooltip itself is ignored. Only one is shown at a time.
 */
public final class HoverTip {

    private static final String KEY = "hover-tip";
    private static final Duration SHOW_DELAY = Duration.millis(250);
    private static final double OFFSET_X = 14, OFFSET_Y = 20, GAP = 6;

    /** The tooltip currently on screen (at most one app-wide). */
    private static Tooltip showing;
    /** Handlers whose node the pointer is currently over (a parent and its child can both be). */
    private static final Set<Handler> hovered = Collections.newSetFromMap(new WeakHashMap<>());

    private HoverTip() {}

    public static void install(Node node, String text) {
        install(node, () -> text);
    }

    /** {@code text} is read each time the tip is about to show; null/blank shows nothing. */
    public static void install(Node node, Supplier<String> text) {
        if (node == null) return;
        Object existing = node.getProperties().get(KEY);
        if (existing instanceof Handler h) {
            h.text = text;
            return;
        }
        node.getProperties().put(KEY, new Handler(node, text));
    }

    public static void uninstall(Node node) {
        if (node == null) return;
        if (node.getProperties().remove(KEY) instanceof Handler h) h.dispose();
    }

    /**
     * Swaps every standard tooltip under {@code root} (e.g. ones declared in FXML) for a HoverTip.
     * Tooltips set later on those controls are not picked up — use {@link #install} for those.
     */
    public static void adopt(Parent root) {
        if (root == null) return;
        if (root instanceof Control c && c.getTooltip() != null) {
            Tooltip t = c.getTooltip();
            c.setTooltip(null);
            install(c, t.getText());
        }
        for (Node child : root.getChildrenUnmodifiable()) {
            if (child instanceof Parent p) adopt(p);
        }
        // Before the scene is shown these controls have no skin, so their content isn't a child yet.
        if (root instanceof ScrollPane sp && sp.getContent() instanceof Parent p) adopt(p);
        if (root instanceof TitledPane tp && tp.getContent() instanceof Parent p) adopt(p);
        if (root instanceof TabPane tabs) {
            for (Tab tab : tabs.getTabs()) if (tab.getContent() instanceof Parent p) adopt(p);
        }
    }

    /**
     * Shows {@code tip} next to the screen point (x, y), never underneath it: to the right and
     * below by default, flipped left/up when it would run off the screen.
     */
    static void showNear(Tooltip tip, Node owner, double x, double y) {
        if (owner.getScene() == null || owner.getScene().getWindow() == null) return;
        if (showing != null && showing != tip) showing.hide();
        showing = tip;
        if (tip.getProperties().putIfAbsent("hover-tip-clear", Boolean.TRUE) == null) {
            // Forget it however it gets hidden (e.g. its window closed), so nothing is kept alive.
            tip.addEventHandler(javafx.stage.WindowEvent.WINDOW_HIDDEN, e -> { if (showing == tip) showing = null; });
        }
        if (!tip.isShowing()) {
            // Show off to the side first so the size is known, then place it properly.
            tip.show(owner, x + OFFSET_X, y + OFFSET_Y);
        }
        double w = tip.getWidth(), h = tip.getHeight();
        List<Screen> screens = Screen.getScreensForRectangle(x, y, 1, 1);
        Rectangle2D area = (screens.isEmpty() ? Screen.getPrimary() : screens.get(0)).getVisualBounds();
        double px = x + OFFSET_X, py = y + OFFSET_Y;
        if (px + w > area.getMaxX()) px = x - w - GAP;
        if (py + h > area.getMaxY()) py = y - h - GAP;
        tip.setX(Math.max(area.getMinX(), px));
        tip.setY(Math.max(area.getMinY(), py));
    }

    static void hide(Tooltip tip) {
        tip.hide();
        if (showing == tip) showing = null;
    }

    /**
     * True when the screen point is on the tooltip window itself: the "exit" that brought it
     * there isn't a real exit (the tooltip just got in the way).
     */
    static boolean pointerOnTip(Tooltip tip, double screenX, double screenY) {
        return tip.isShowing()
            && new BoundingBox(tip.getX(), tip.getY(), tip.getWidth(), tip.getHeight()).contains(screenX, screenY);
    }

    /** True when the screen point is over the visible part of {@code node}. */
    static boolean pointerStillOver(Node node, double screenX, double screenY) {
        Bounds b = node.localToScreen(node.getLayoutBounds());
        return b != null && b.contains(screenX, screenY);
    }

    private static boolean isDescendant(Node node, Node ancestor) {
        for (Node n = node; n != null; n = n.getParent()) if (n == ancestor) return true;
        return false;
    }

    private static final class Handler {
        Node node; // not final: the handler lambdas below read it after construction
        final Tooltip tip = new Tooltip();
        final PauseTransition delay = new PauseTransition(SHOW_DELAY);
        Supplier<String> text;
        double lastX, lastY;

        /** After a click or scroll the user is busy with the control: no tip until they come back. */
        boolean suppressed;

        final javafx.event.EventHandler<MouseEvent> onEnter = e -> {
            track(e);
            hovered.add(this);
            suppressed = false;
            if (!tip.isShowing()) delay.playFromStart();
        };
        final javafx.event.EventHandler<MouseEvent> onMove = e -> {
            track(e);
            if (tip.isShowing()) showNear(tip, node, lastX, lastY);
            // Back on this node after a child with its own tip: start this one's delay again.
            else if (!suppressed && delay.getStatus() != javafx.animation.Animation.Status.RUNNING && !childHovered()) delay.playFromStart();
        };
        final javafx.event.EventHandler<MouseEvent> onExit = e -> {
            if (pointerOnTip(tip, e.getScreenX(), e.getScreenY())) {
                // The pointer went onto the tooltip; step it out of the way instead of hiding.
                showNear(tip, node, e.getScreenX(), e.getScreenY());
                return;
            }
            hovered.remove(this);
            cancel();
        };
        /**
         * While the tip shows, every move in the window: once the pointer is over something that
         * isn't this node (a scrollbar, the clipped-off part of a row, another control), hide.
         * Node exit events alone miss those cases.
         */
        final javafx.event.EventHandler<MouseEvent> sceneWatch = e -> {
            if (e.getEventType() == MouseEvent.MOUSE_EXITED_TARGET && e.getTarget() instanceof javafx.scene.Scene) {
                if (!pointerOnTip(tip, e.getScreenX(), e.getScreenY())) leave();
            } else if (e.getEventType() == MouseEvent.MOUSE_MOVED || e.getEventType() == MouseEvent.MOUSE_DRAGGED) {
                Node over = e.getPickResult() != null ? e.getPickResult().getIntersectedNode() : null;
                if (over == null || !isDescendant(over, node)) leave();
            }
        };
        final javafx.beans.value.ChangeListener<Boolean> onWindowFocus = (o, was, is) -> { if (!is) leave(); };
        javafx.scene.Scene watchedScene;
        final javafx.event.EventHandler<MouseEvent> onPress = e -> { suppressed = true; cancel(); };
        final javafx.event.EventHandler<ScrollEvent> onScroll = e -> { suppressed = true; cancel(); };
        final javafx.beans.value.ChangeListener<Object> onDetach = (o, a, b) -> leave();

        Handler(Node node, Supplier<String> text) {
            this.node = node;
            this.text = text;
            tip.setWrapText(true);
            tip.setMaxWidth(380);
            delay.setOnFinished(e -> {
                String t = this.text == null ? null : this.text.get();
                // A child with its own tip is under the pointer: that one speaks, not this one.
                if (t == null || t.isBlank() || !node.isHover() || childHovered()) return;
                tip.setText(t);
                showNear(tip, node, lastX, lastY);
                watch();
            });
            tip.addEventHandler(javafx.stage.WindowEvent.WINDOW_HIDDEN, e -> unwatch());
            // If the pointer lands on the tooltip itself, keep it clear of the pointer (or hide
            // it once the pointer has left the node altogether).
            tip.addEventHandler(MouseEvent.MOUSE_MOVED, e -> {
                if (pointerStillOver(node, e.getScreenX(), e.getScreenY())) {
                    track(e);
                    showNear(tip, node, lastX, lastY);
                } else {
                    leave();
                }
            });
            node.addEventHandler(MouseEvent.MOUSE_ENTERED, onEnter);
            node.addEventHandler(MouseEvent.MOUSE_MOVED, onMove);
            node.addEventHandler(MouseEvent.MOUSE_EXITED, onExit);
            node.addEventFilter(MouseEvent.MOUSE_PRESSED, onPress);
            node.addEventFilter(ScrollEvent.SCROLL, onScroll);
            node.sceneProperty().addListener(onDetach);
            node.visibleProperty().addListener(onDetach);
        }

        void track(MouseEvent e) {
            lastX = e.getScreenX();
            lastY = e.getScreenY();
        }

        boolean childHovered() {
            for (Handler h : hovered) {
                if (h != this && h.node.isHover() && isDescendant(h.node, node)) return true;
            }
            return false;
        }

        /** Watches the whole window while the tip is up (see {@link #sceneWatch}). */
        void watch() {
            unwatch();
            watchedScene = node.getScene();
            if (watchedScene == null) return;
            watchedScene.addEventFilter(MouseEvent.ANY, sceneWatch);
            if (watchedScene.getWindow() != null) watchedScene.getWindow().focusedProperty().addListener(onWindowFocus);
        }

        void unwatch() {
            if (watchedScene == null) return;
            watchedScene.removeEventFilter(MouseEvent.ANY, sceneWatch);
            if (watchedScene.getWindow() != null) watchedScene.getWindow().focusedProperty().removeListener(onWindowFocus);
            watchedScene = null;
        }

        void leave() {
            hovered.remove(this);
            cancel();
        }

        void cancel() {
            delay.stop();
            if (tip.isShowing()) hide(tip);
            unwatch();
        }

        void dispose() {
            hovered.remove(this);
            cancel();
            node.removeEventHandler(MouseEvent.MOUSE_ENTERED, onEnter);
            node.removeEventHandler(MouseEvent.MOUSE_MOVED, onMove);
            node.removeEventHandler(MouseEvent.MOUSE_EXITED, onExit);
            node.removeEventFilter(MouseEvent.MOUSE_PRESSED, onPress);
            node.removeEventFilter(ScrollEvent.SCROLL, onScroll);
            node.sceneProperty().removeListener(onDetach);
            node.visibleProperty().removeListener(onDetach);
        }
    }
}
