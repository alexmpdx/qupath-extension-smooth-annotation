# QuPath Smooth Annotation

An **Inkscape-style** tool to smooth and simplify annotation outlines in
[QuPath](https://qupath.github.io/).

QuPath's built-in *Simplify shape* uses **Visvalingam vertex removal**: it deletes
points and connects the survivors with **straight lines**, which is why simplified
ROIs often pick up harsh, faceted angles. This extension instead **fits smooth
curves** to the outline — the same approach Inkscape's *Simplify* (`Ctrl+L`) uses —
so the shape is simplified *without* being warped.

Because QuPath stores ROIs as polygons (it has no curved-segment representation),
the fitted curves are re-sampled back into a dense-enough polygon. You control the
trade-off between node count and smoothness directly in the dialog.

## Methods

| Method | What it does |
| --- | --- |
| **Bézier fit (Inkscape-style)** | Philip J. Schneider's cubic Bézier curve-fitting algorithm (*Graphics Gems*, 1990) — the algorithm Inkscape is built on. Best fidelity to Inkscape's look. |
| **Spline — Chaikin** | Iterative corner-cutting subdivision. Simple, robust, very smooth. |
| **Spline — Catmull–Rom** | An interpolating spline that passes exactly through the (decimated) points, so the outline stays anchored to the traced vertices. |

All three **preserve sharp corners**: vertices where the path turns by more than the
configured angle are kept crisp, while the rest of the outline is smoothed.

Each numeric parameter has a **slider** over a suggested range plus a **text box**.
The text box accepts values *beyond* the slider's range; when a value is off-range
the box is tinted orange (with a tooltip showing the suggested range) and the slider
thumb pins at its limit. A **Show preview** checkbox toggles the overlay on/off, a
**Filled** checkbox shows the preview as a semi-transparent filled region (holes
included) instead of just an outline, and a **Colour** picker changes the preview
colour. A **Reset** button restores all settings (and the preview colour) to defaults.

## Parameters

- **Smoothing strength (px)** — for *Bézier*, the maximum deviation of the fitted
  curve from the original points; for the *spline* methods, a Douglas–Peucker
  tolerance applied before smoothing. Higher → smoother and fewer nodes.
- **Output resolution (px)** — the maximum deviation of the stored polygon from the
  smooth curve. Smaller → more points and a smoother outline; larger → fewer points.
- **Preserve corners above (°)** — turn-angle threshold; vertices that bend more
  than this stay sharp. Set to `180` to smooth everything.
- **Corner neighborhood (px)** — the arc-length distance over which the bend angle
  is measured when detecting corners. Larger values ignore fine wiggles/noise so
  only genuinely sustained turns are kept as corners; `0` measures across single
  segments (sensitive to noise). Non-maximum suppression ensures one corner sampled
  by several points isn't flagged multiple times.
- **Chaikin iterations** — number of corner-cutting passes (Chaikin only).

## Usage

1. Select one or more **annotations**.
2. **Extensions → Smooth Annotation → Smooth annotations…**
3. A **non-modal dialog** opens with a **live preview**: the proposed result is drawn
   in magenta (outline + node dots) on top of your untouched annotation, and updates
   as you change any parameter. A status line shows the running *before → after* point
   count. The viewer stays pannable/zoomable so you can inspect closely.
4. Click **OK** to apply, or **Cancel** to leave everything untouched.

When applied, the selected annotations are replaced with smoothed copies that keep
their classification, name, colour and measurements. The change is undoable
(`Edit → Undo`). Rectangles, ellipses and points are left unchanged.

## Building

The extension targets **QuPath 0.7.0** and is built with **JDK 21+** (QuPath 0.7
bundles Java 25). A copy of the JDK used during development lives in `.jdk/` and is
git-ignored.

```bash
export JAVA_HOME=/path/to/jdk-21-or-newer
./gradlew build
```

The drop-in jar is written to `build/libs/`. Install it by dragging it onto a
running QuPath window, or copy it into your QuPath user extensions directory
(`Extensions → Manage extensions` shows the location).

## Layout

```
src/main/java/qupath/ext/simplify/
  SimplifyPathExtension.java   – registers the menu command
  SimplifyPathCommand.java     – live-preview dialog + applies smoothing to selected annotations
  PreviewCanvas.java           – on-top JavaFX canvas that draws the non-destructive preview
  ParamSlider.java             – slider + free-entry text box with off-range indication
  RoiSimplifier.java           – ROI ⇄ contour conversion, rebuilds ROIs (holes preserved)
  algo/
    Vec.java                   – tiny 2D vector
    Contour.java               – a ring or polyline
    DouglasPeucker.java        – polyline decimation (pre-pass)
    BezierFit.java             – Schneider cubic-Bézier fitting + adaptive flattening
    SplineSmoother.java        – Chaikin and Catmull–Rom smoothing
    ContourSimplifier.java     – corner detection, span splitting, method dispatch
```
