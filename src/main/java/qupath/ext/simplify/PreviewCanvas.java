package qupath.ext.simplify;

import javafx.application.Platform;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.layout.Pane;
import javafx.scene.paint.Color;
import qupath.lib.geom.Point2;
import qupath.lib.gui.viewer.QuPathViewer;

import java.awt.Shape;
import java.awt.geom.PathIterator;
import java.awt.geom.Point2D;
import java.util.List;

/**
 * Draws a non-destructive preview of the proposed simplified outlines on a JavaFX
 * {@link Canvas} layered <em>on top of</em> the viewer.
 * <p>
 * QuPath paints custom {@code PathOverlay}s beneath the annotation/hierarchy layer,
 * so a preview drawn there is hidden behind the original annotation outline. Drawing
 * on a transparent canvas added to the viewer's pane keeps the preview clearly
 * visible above everything QuPath renders. The canvas is mouse-transparent, so the
 * viewer remains fully interactive (pan/zoom/rotate), and it redraws whenever the
 * viewer repaints so the preview stays registered to the image.
 */
class PreviewCanvas {

    /** Outline + node markers for a single previewed annotation, in image coordinates. */
    record PreviewItem(Shape shape, List<Point2> vertices) {}

    static final Color DEFAULT_COLOR = Color.rgb(255, 60, 220);
    private static final Color HALO_COLOR = Color.rgb(0, 0, 0, 0.5);

    private final QuPathViewer viewer;
    private final Pane view;
    private final Canvas canvas = new Canvas();
    private final javafx.beans.value.ChangeListener<Number> repaintListener;

    private volatile List<PreviewItem> items = List.of();
    private volatile boolean filled = false;
    private volatile Color strokeColor = DEFAULT_COLOR;
    private volatile Color fillColor = deriveFill(DEFAULT_COLOR);

    PreviewCanvas(QuPathViewer viewer) {
        this.viewer = viewer;
        this.view = viewer.getView();

        canvas.setMouseTransparent(true);
        canvas.setManaged(false); // we size/position it ourselves, pinned to the pane
        canvas.setLayoutX(0);
        canvas.setLayoutY(0);
        canvas.widthProperty().bind(view.widthProperty());
        canvas.heightProperty().bind(view.heightProperty());
        canvas.widthProperty().addListener((obs, o, n) -> redraw());
        canvas.heightProperty().addListener((obs, o, n) -> redraw());

        // Redraw whenever the viewer repaints (pan/zoom/rotate/refresh).
        repaintListener = (obs, o, n) -> redrawOnFxThread();
        viewer.repaintTimestamp().addListener(repaintListener);

        view.getChildren().add(canvas);
    }

    void setItems(List<PreviewItem> items) {
        this.items = items == null ? List.of() : items;
        redrawOnFxThread();
    }

    void setFilled(boolean filled) {
        this.filled = filled;
        redrawOnFxThread();
    }

    void setColor(Color color) {
        if (color == null)
            color = DEFAULT_COLOR;
        this.strokeColor = color;
        this.fillColor = deriveFill(color);
        redrawOnFxThread();
    }

    private static Color deriveFill(Color color) {
        return new Color(color.getRed(), color.getGreen(), color.getBlue(), 0.25);
    }

    void detach() {
        viewer.repaintTimestamp().removeListener(repaintListener);
        canvas.widthProperty().unbind();
        canvas.heightProperty().unbind();
        view.getChildren().remove(canvas);
    }

    private void redrawOnFxThread() {
        if (Platform.isFxApplicationThread())
            redraw();
        else
            Platform.runLater(this::redraw);
    }

    private void redraw() {
        GraphicsContext gc = canvas.getGraphicsContext2D();
        gc.clearRect(0, 0, canvas.getWidth(), canvas.getHeight());
        List<PreviewItem> snapshot = items;
        if (snapshot.isEmpty())
            return;

        Point2D src = new Point2D.Double();
        Point2D dst = new Point2D.Double();
        double[] coords = new double[6];

        boolean doFill = filled;
        Color stroke = strokeColor;
        Color fill = fillColor;
        for (PreviewItem item : snapshot) {
            if (item.shape() == null)
                continue;
            // Optional semi-transparent fill underneath the outline.
            if (doFill) {
                buildPath(gc, item.shape(), src, dst, coords);
                gc.setFill(fill);
                gc.fill();
            }
            // A dark halo underneath plus the bright line keeps the preview legible over
            // both light and dark backgrounds.
            buildPath(gc, item.shape(), src, dst, coords);
            gc.setStroke(HALO_COLOR);
            gc.setLineWidth(3.5);
            gc.stroke();
            buildPath(gc, item.shape(), src, dst, coords);
            gc.setStroke(stroke);
            gc.setLineWidth(1.75);
            gc.stroke();

            if (item.vertices() != null) {
                gc.setFill(stroke);
                for (Point2 v : item.vertices()) {
                    src.setLocation(v.getX(), v.getY());
                    Point2D p = viewer.imagePointToComponentPoint(src, dst, false);
                    gc.fillOval(p.getX() - 2.5, p.getY() - 2.5, 5, 5);
                }
            }
        }
    }

    /** Issue the path commands for {@code shape} (all sub-paths) into the graphics context. */
    private void buildPath(GraphicsContext gc, Shape shape, Point2D src, Point2D dst, double[] coords) {
        gc.beginPath();
        PathIterator pi = shape.getPathIterator(null);
        while (!pi.isDone()) {
            int type = pi.currentSegment(coords);
            switch (type) {
                case PathIterator.SEG_MOVETO -> {
                    Point2D p = toComponent(src, dst, coords[0], coords[1]);
                    gc.moveTo(p.getX(), p.getY());
                }
                case PathIterator.SEG_LINETO -> {
                    Point2D p = toComponent(src, dst, coords[0], coords[1]);
                    gc.lineTo(p.getX(), p.getY());
                }
                case PathIterator.SEG_CLOSE -> gc.closePath();
                default -> {
                    // Our rebuilt ROIs are polygonal; fall back to the endpoint just in case.
                    Point2D p = toComponent(src, dst, coords[0], coords[1]);
                    gc.lineTo(p.getX(), p.getY());
                }
            }
            pi.next();
        }
    }

    private Point2D toComponent(Point2D src, Point2D dst, double x, double y) {
        src.setLocation(x, y);
        return viewer.imagePointToComponentPoint(src, dst, false);
    }
}
