package qupath.ext.simplify;

import javafx.application.Platform;
import javafx.beans.InvalidationListener;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ColorPicker;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.Separator;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.simplify.PreviewCanvas.PreviewItem;
import qupath.ext.simplify.algo.ContourSimplifier;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.viewer.QuPathViewer;
import qupath.lib.images.ImageData;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.PathObjects;
import qupath.lib.objects.hierarchy.PathObjectHierarchy;
import qupath.lib.roi.interfaces.ROI;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Interactive command: smooth/simplify the ROIs of the currently selected
 * annotations using Inkscape-style curve fitting (or a spline alternative).
 * <p>
 * Opens a non-modal dialog with slider controls and a live preview: as the
 * parameters change, the proposed result is drawn on top of the (untouched)
 * original annotations via a {@link PreviewCanvas}. Only when the user clicks OK
 * are the originals replaced with smoothed copies that keep their classification,
 * name, colour and measurements. The replacement goes through the hierarchy's
 * add/remove methods so it participates in QuPath's undo/redo.
 */
public class SimplifyPathCommand implements Runnable {

    private static final Logger logger = LoggerFactory.getLogger(SimplifyPathCommand.class);

    private final QuPathGUI gui;

    /** Factory-default settings, used to initialise the dialog and for the Reset button. */
    private static final ContourSimplifier.Settings DEFAULTS = new ContourSimplifier.Settings(
            ContourSimplifier.Method.BEZIER, 2.0, 0.5, 75.0, 5.0, 3);

    // Last-used settings, remembered across dialog openings within a session.
    private ContourSimplifier.Settings lastSettings = DEFAULTS;

    public SimplifyPathCommand(QuPathGUI gui) {
        this.gui = gui;
    }

    @Override
    public void run() {
        ImageData<BufferedImage> imageData = gui.getImageData();
        if (imageData == null) {
            qupath.fx.dialogs.Dialogs.showErrorMessage("Simplify path", "No image is open.");
            return;
        }
        PathObjectHierarchy hierarchy = imageData.getHierarchy();

        List<PathObject> annotations = hierarchy.getSelectionModel().getSelectedObjects().stream()
                .filter(p -> p.isAnnotation() && p.hasROI() && !p.getROI().isEmpty())
                .toList();
        if (annotations.isEmpty()) {
            qupath.fx.dialogs.Dialogs.showErrorMessage("Simplify path",
                    "Please select one or more annotations to simplify.");
            return;
        }

        showPreviewDialog(hierarchy, annotations);
    }

    // ---- preview dialog -----------------------------------------------------

    private void showPreviewDialog(PathObjectHierarchy hierarchy, List<PathObject> annotations) {
        QuPathViewer viewer = gui.getViewer();
        PreviewCanvas previewCanvas = viewer == null ? null : new PreviewCanvas(viewer);

        // --- controls ---
        ComboBox<ContourSimplifier.Method> methodCombo = new ComboBox<>(
                FXCollections.observableArrayList(ContourSimplifier.Method.values()));
        methodCombo.getSelectionModel().select(lastSettings.method());
        methodCombo.setMaxWidth(Double.MAX_VALUE);

        ParamSlider smoothing = new ParamSlider("Smoothing strength", "px", 0, 40,
                lastSettings.smoothing(), false,
                "Bézier: maximum deviation of the fitted curve from the original points. "
                        + "Splines: Douglas–Peucker tolerance applied before smoothing. "
                        + "Higher = smoother and fewer nodes.");
        ParamSlider resolution = new ParamSlider("Output resolution", "px", 0.1, 20,
                lastSettings.resolution(), false,
                "Maximum deviation of the stored polygon from the smooth curve. "
                        + "Smaller = more points and a smoother outline; larger = fewer points.");
        ParamSlider cornerAngle = new ParamSlider("Preserve corners above", "°", 0, 180,
                lastSettings.cornerAngleDeg(), false,
                "Vertices where the path turns by more than this angle are kept as sharp corners. "
                        + "Set to 180 to smooth everything.");
        ParamSlider cornerNeighborhood = new ParamSlider("Corner neighborhood", "px", 0, 80,
                lastSettings.cornerNeighborhood(), false,
                "Distance over which the bend angle is measured when detecting corners. "
                        + "Larger values ignore fine wiggles/noise; set to 0 to use single segments.");
        ParamSlider chaikin = new ParamSlider("Chaikin iterations", "", 1, 24,
                lastSettings.chaikinIterations(), true,
                "Number of corner-cutting passes (only used by the Chaikin method).");

        Supplier<ContourSimplifier.Settings> readSettings = () -> new ContourSimplifier.Settings(
                methodCombo.getValue(),
                Math.max(0, smoothing.get()),
                Math.max(0.01, resolution.get()),
                Math.max(0, Math.min(180, cornerAngle.get())),
                Math.max(0, cornerNeighborhood.get()),
                Math.max(1, (int) Math.rint(chaikin.get())));

        // --- layout ---
        GridPane grid = new GridPane();
        grid.setHgap(8);
        grid.setVgap(6);
        ColumnConstraints c0 = new ColumnConstraints();
        ColumnConstraints c1 = new ColumnConstraints();
        c1.setHgrow(Priority.ALWAYS);
        c1.setMinWidth(140);
        ColumnConstraints c2 = new ColumnConstraints();
        grid.getColumnConstraints().addAll(c0, c1, c2);

        grid.add(new Label("Method"), 0, 0);
        grid.add(methodCombo, 1, 0, 2, 1);
        ParamSlider[] sliders = {smoothing, resolution, cornerAngle, cornerNeighborhood, chaikin};
        for (int i = 0; i < sliders.length; i++) {
            int row = i + 1;
            grid.add(sliders[i].getNameLabel(), 0, row);
            grid.add(sliders[i].getSlider(), 1, row);
            grid.add(sliders[i].getField(), 2, row);
        }

        CheckBox showPreview = new CheckBox("Show preview");
        showPreview.setSelected(true);
        showPreview.setDisable(previewCanvas == null);
        CheckBox filledPreview = new CheckBox("Filled");
        filledPreview.setSelected(false);
        ColorPicker colorPicker = new ColorPicker(PreviewCanvas.DEFAULT_COLOR);
        colorPicker.setTooltip(new javafx.scene.control.Tooltip("Preview colour"));
        Label colorLabel = new Label("Colour:");
        if (previewCanvas == null) {
            filledPreview.setDisable(true);
            colorPicker.setDisable(true);
        } else {
            filledPreview.disableProperty().bind(showPreview.selectedProperty().not());
            colorPicker.disableProperty().bind(showPreview.selectedProperty().not());
        }

        Button resetButton = new Button("Reset");
        resetButton.setTooltip(new javafx.scene.control.Tooltip("Reset all settings to their defaults"));
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        HBox previewControls = new HBox(10, showPreview, filledPreview, colorLabel, colorPicker,
                spacer, resetButton);
        previewControls.setAlignment(Pos.CENTER_LEFT);

        Label status = new Label("Computing preview…");
        status.setWrapText(true);

        VBox content = new VBox(8, grid, previewControls, new Separator(), status);
        content.setPadding(new Insets(5));
        content.setPrefWidth(420);

        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("Simplify path");
        dialog.initModality(Modality.NONE); // non-modal so the viewer stays pannable/zoomable
        dialog.initOwner(gui.getStage());
        dialog.setResizable(true);
        dialog.getDialogPane().setContent(content);
        dialog.getDialogPane().getButtonTypes().setAll(ButtonType.OK, ButtonType.CANCEL);

        // --- live preview wiring ---
        ExecutorService exec = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "simplify-preview");
            t.setDaemon(true);
            return t;
        });
        AtomicLong seq = new AtomicLong();

        Runnable recompute = () -> {
            ContourSimplifier.Settings settings = readSettings.get();
            long mySeq = seq.incrementAndGet();
            exec.submit(() -> {
                Preview preview;
                try {
                    preview = computePreview(annotations, settings);
                } catch (Throwable t) {
                    logger.error("Preview computation failed", t);
                    return;
                }
                Platform.runLater(() -> {
                    if (mySeq != seq.get())
                        return; // a newer request superseded this one
                    status.setText(preview.statusText());
                    if (previewCanvas != null)
                        previewCanvas.setItems(showPreview.isSelected() ? preview.items() : List.of());
                });
            });
        };

        InvalidationListener changeListener = obs -> recompute.run();
        methodCombo.valueProperty().addListener(changeListener);
        for (ParamSlider ps : sliders)
            ps.valueProperty().addListener(changeListener);

        if (previewCanvas != null) {
            previewCanvas.setFilled(filledPreview.isSelected());
            previewCanvas.setColor(colorPicker.getValue());
            showPreview.selectedProperty().addListener((obs, was, isSelected) -> {
                if (isSelected)
                    recompute.run();
                else
                    previewCanvas.setItems(List.of());
            });
            filledPreview.selectedProperty().addListener((obs, was, isSelected) ->
                    previewCanvas.setFilled(isSelected));
            colorPicker.valueProperty().addListener((obs, was, color) ->
                    previewCanvas.setColor(color));
        }

        // Reset all parameters (and the preview colour) to their defaults.
        resetButton.setOnAction(e -> {
            methodCombo.getSelectionModel().select(DEFAULTS.method());
            smoothing.set(DEFAULTS.smoothing());
            resolution.set(DEFAULTS.resolution());
            cornerAngle.set(DEFAULTS.cornerAngleDeg());
            cornerNeighborhood.set(DEFAULTS.cornerNeighborhood());
            chaikin.set(DEFAULTS.chaikinIterations());
            colorPicker.setValue(PreviewCanvas.DEFAULT_COLOR);
        });

        recompute.run();

        dialog.setResultConverter(bt -> bt);
        dialog.setOnHidden(e -> {
            exec.shutdownNow();
            if (previewCanvas != null)
                previewCanvas.detach();
            if (dialog.getResult() == ButtonType.OK) {
                ContourSimplifier.Settings settings = readSettings.get();
                lastSettings = settings;
                apply(hierarchy, annotations, settings);
            }
        });
        dialog.show();
    }

    private record Preview(List<PreviewItem> items, String statusText) {}

    /** Compute the previewed (not yet applied) simplified outlines. Runs off the FX thread. */
    private Preview computePreview(List<PathObject> annotations, ContourSimplifier.Settings settings) {
        List<PreviewItem> items = new ArrayList<>();
        long before = 0;
        long after = 0;
        int changed = 0;
        Throwable firstError = null;

        for (PathObject annotation : annotations) {
            ROI original = annotation.getROI();
            ROI simplified;
            try {
                simplified = RoiSimplifier.simplify(original, settings);
            } catch (Throwable t) {
                logger.error("Preview failed for {} ({})", annotation, original.getRoiName(), t);
                if (firstError == null)
                    firstError = t;
                continue;
            }
            before += original.getNumPoints();
            after += simplified.getNumPoints();
            if (simplified != original)
                changed++;
            items.add(new PreviewItem(simplified.getShape(), simplified.getAllPoints()));
        }

        String text;
        if (items.isEmpty() && firstError != null)
            text = "Preview failed: " + firstError;
        else
            text = String.format("Preview: %d annotation(s), %d → %d points (%d changed)",
                    annotations.size(), before, after, changed);
        return new Preview(List.copyOf(items), text);
    }

    // ---- apply --------------------------------------------------------------

    private void apply(PathObjectHierarchy hierarchy, List<PathObject> annotations,
                       ContourSimplifier.Settings settings) {
        List<PathObject> toRemove = new ArrayList<>();
        List<PathObject> toAdd = new ArrayList<>();
        long pointsBefore = 0;
        long pointsAfter = 0;
        Throwable firstError = null;

        for (PathObject annotation : annotations) {
            ROI original = annotation.getROI();
            ROI simplified;
            try {
                simplified = RoiSimplifier.simplify(original, settings);
            } catch (Throwable e) {
                logger.error("Unable to simplify {} ({})", annotation, original.getRoiName(), e);
                if (firstError == null)
                    firstError = e;
                continue;
            }
            if (simplified == original || simplified == null)
                continue;

            pointsBefore += original.getNumPoints();
            pointsAfter += simplified.getNumPoints();

            PathObject replacement = PathObjects.createAnnotationObject(simplified, annotation.getPathClass());
            replacement.setName(annotation.getName());
            if (annotation.getColor() != null)
                replacement.setColor(annotation.getColor());
            copyMeasurements(annotation, replacement);

            toRemove.add(annotation);
            toAdd.add(replacement);
        }

        if (toAdd.isEmpty()) {
            if (firstError != null) {
                qupath.fx.dialogs.Dialogs.showErrorMessage("Simplify path",
                        "Simplification failed:\n" + firstError + "\n\nSee the log for details.");
            } else {
                String types = annotations.stream()
                        .map(p -> p.getROI().getRoiName())
                        .distinct()
                        .collect(Collectors.joining(", "));
                logger.info("No annotations changed; selected ROI types: {}", types);
                qupath.fx.dialogs.Dialogs.showInfoNotification("Simplify path",
                        "No annotations were changed. Selected ROI type(s): " + types
                                + ". Rectangles, ellipses and points are left unchanged.");
            }
            return;
        }

        hierarchy.removeObjects(toRemove, true);
        hierarchy.addObjects(toAdd);
        hierarchy.resolveHierarchy();
        hierarchy.getSelectionModel().setSelectedObjects(toAdd, toAdd.get(0));

        logger.info("Simplified {} annotation(s): {} -> {} points", toAdd.size(), pointsBefore, pointsAfter);
        qupath.fx.dialogs.Dialogs.showInfoNotification("Simplify path",
                String.format("Simplified %d annotation(s): %d → %d points", toAdd.size(), pointsBefore, pointsAfter));
    }

    private static void copyMeasurements(PathObject from, PathObject to) {
        var src = from.getMeasurementList();
        if (src.getMeasurements().isEmpty())
            return;
        try (var dst = to.getMeasurementList()) {
            dst.putAll(src);
        }
    }
}
