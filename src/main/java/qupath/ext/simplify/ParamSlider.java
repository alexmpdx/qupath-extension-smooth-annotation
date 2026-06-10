package qupath.ext.simplify;

import javafx.beans.property.DoubleProperty;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.scene.control.Label;
import javafx.scene.control.Slider;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;

/**
 * A numeric parameter control: a {@link Slider} over a <em>suggested</em> range
 * paired with a {@link TextField} for exact entry.
 * <p>
 * The text field accepts any value, including values beyond the slider's
 * min/max. When the value falls outside the suggested range the field is tinted
 * orange (and its tooltip explains the range), so it's obvious the slider thumb —
 * pinned at its limit — no longer reflects the true value. {@link #valueProperty()}
 * is the single source of truth.
 */
class ParamSlider {

    private static final String OFF_RANGE_STYLE =
            "-fx-control-inner-background: #ffe0b2; -fx-border-color: #fb8c00; -fx-border-width: 1.2;";

    private final DoubleProperty value = new SimpleDoubleProperty();
    private final double min;
    private final double max;
    private final boolean integer;

    private final Label nameLabel;
    private final Slider slider;
    private final TextField field = new TextField();
    private final Tooltip baseTooltip;

    private boolean updatingUi = false;

    ParamSlider(String name, String unit, double min, double max, double initial,
                boolean integer, String tooltip) {
        this.min = min;
        this.max = max;
        this.integer = integer;

        nameLabel = new Label(unit == null || unit.isEmpty() ? name : name + " (" + unit + ")");
        baseTooltip = new Tooltip(tooltip);
        Tooltip.install(nameLabel, baseTooltip);
        field.setTooltip(baseTooltip);
        field.setPrefColumnCount(integer ? 4 : 6);

        slider = new Slider(min, max, clamp(initial));
        slider.setMaxWidth(Double.MAX_VALUE);
        if (integer) {
            slider.setMajorTickUnit(1);
            slider.setMinorTickCount(0);
            slider.setSnapToTicks(true);
            slider.setBlockIncrement(1);
        } else {
            slider.setBlockIncrement((max - min) / 20.0);
        }

        // value is the source of truth; keep the UI in sync with it.
        value.addListener((obs, oldV, newV) -> syncUi(newV.doubleValue()));
        slider.valueProperty().addListener((obs, oldV, newV) -> {
            if (!updatingUi)
                setValue(newV.doubleValue());
        });
        field.setOnAction(e -> commitField());
        field.focusedProperty().addListener((obs, was, isFocused) -> {
            if (!isFocused)
                commitField();
        });

        setValue(initial);
    }

    DoubleProperty valueProperty() {
        return value;
    }

    double get() {
        return value.get();
    }

    /** Programmatically set the value (e.g. for a reset); updates the slider and field. */
    void set(double v) {
        setValue(v);
    }

    Label getNameLabel() {
        return nameLabel;
    }

    Slider getSlider() {
        return slider;
    }

    TextField getField() {
        return field;
    }

    private void setValue(double v) {
        if (integer)
            v = Math.rint(v);
        value.set(v);
        // Ensure the UI reflects the (possibly unchanged) committed value.
        syncUi(v);
    }

    private void commitField() {
        try {
            setValue(Double.parseDouble(field.getText().trim()));
        } catch (NumberFormatException ex) {
            syncUi(value.get()); // revert invalid text
        }
    }

    private void syncUi(double v) {
        updatingUi = true;
        try {
            slider.setValue(clamp(v));
            field.setText(format(v));
            boolean offRange = v < min - 1e-9 || v > max + 1e-9;
            if (offRange) {
                field.setStyle(OFF_RANGE_STYLE);
                field.setTooltip(new Tooltip(String.format(
                        "Outside the suggested range (%s–%s); the slider is pinned at its limit.",
                        format(min), format(max))));
            } else {
                field.setStyle("");
                field.setTooltip(baseTooltip);
            }
        } finally {
            updatingUi = false;
        }
    }

    private double clamp(double v) {
        return Math.max(min, Math.min(max, v));
    }

    private String format(double v) {
        if (integer)
            return Integer.toString((int) Math.rint(v));
        if (v == Math.rint(v))
            return Long.toString((long) v);
        return Double.toString(Math.round(v * 1000.0) / 1000.0);
    }
}
