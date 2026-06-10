package qupath.ext.simplify;

import qupath.ext.simplify.algo.Contour;
import qupath.ext.simplify.algo.ContourSimplifier;
import qupath.ext.simplify.algo.Vec;
import qupath.lib.geom.Point2;
import qupath.lib.regions.ImagePlane;
import qupath.lib.roi.EllipseROI;
import qupath.lib.roi.PolygonROI;
import qupath.lib.roi.PolylineROI;
import qupath.lib.roi.RectangleROI;
import qupath.lib.roi.ROIs;
import qupath.lib.roi.interfaces.ROI;

import java.awt.Shape;
import java.awt.geom.PathIterator;
import java.awt.geom.Path2D;
import java.util.ArrayList;
import java.util.List;

/**
 * Bridges QuPath {@link ROI}s and the geometry-only smoothing algorithms.
 * <p>
 * A ROI is decomposed into one or more contours (the exterior ring plus any holes
 * for an area; the single polyline for a line), each contour is smoothed by
 * {@link ContourSimplifier}, and a new ROI of the appropriate type is rebuilt.
 * Rectangle, ellipse and point ROIs are returned unchanged — they have no vertices
 * worth simplifying.
 */
public final class RoiSimplifier {

    /** Flatness used when reading curved segments (e.g. ellipses) from a Shape, in pixels. */
    private static final double SHAPE_FLATTEN_TOLERANCE = 0.1;

    private RoiSimplifier() {}

    /**
     * Return a smoothed copy of {@code roi}, or the original instance unchanged if
     * the ROI type is not supported or is too small to simplify. Callers can use
     * reference equality ({@code result == roi}) to detect "no change".
     */
    public static ROI simplify(ROI roi, ContourSimplifier.Settings settings) {
        if (roi == null || roi.isEmpty() || roi.isPoint())
            return roi;

        // Parametric shapes have no traced vertices worth simplifying.
        if (roi instanceof RectangleROI || roi instanceof EllipseROI)
            return roi;

        ImagePlane plane = roi.getImagePlane();

        // Polyline (open multi-point line).
        if (roi instanceof PolylineROI) {
            List<Vec> pts = toVecList(roi.getAllPoints());
            if (pts.size() < 3)
                return roi;
            Contour result = ContourSimplifier.simplify(new Contour(pts, false), settings);
            if (result.points().size() < 2)
                return roi;
            return ROIs.createPolylineROI(toPoint2List(result.points()), plane);
        }

        // Simple two-point line: nothing to simplify.
        if (roi.isLine())
            return roi;

        // Area ROIs (polygons, geometry ROIs, possibly with holes).
        if (roi.isArea()) {
            ExtractResult extracted = extractAreaContours(roi.getShape());
            List<Contour> contours = extracted.contours;
            int windingRule = extracted.windingRule;

            if (contours.isEmpty())
                return roi;

            List<Contour> simplified = new ArrayList<>(contours.size());
            for (Contour c : contours) {
                Contour s = ContourSimplifier.simplify(c, settings);
                // Never drop a valid ring: fall back to the original if simplification
                // produced something degenerate.
                if (s.points().size() >= 3)
                    simplified.add(s);
                else if (c.points().size() >= 3)
                    simplified.add(c);
            }
            if (simplified.isEmpty())
                return roi;

            // Single-ring polygon stays a PolygonROI to preserve the original type.
            if (roi instanceof PolygonROI && simplified.size() == 1) {
                return ROIs.createPolygonROI(toPoint2List(simplified.get(0).points()), plane);
            }

            Path2D path = new Path2D.Double(windingRule);
            for (Contour c : simplified) {
                List<Vec> pts = c.points();
                path.moveTo(pts.get(0).x(), pts.get(0).y());
                for (int i = 1; i < pts.size(); i++)
                    path.lineTo(pts.get(i).x(), pts.get(i).y());
                path.closePath();
            }
            return ROIs.createAreaROI(path, plane);
        }

        return roi;
    }

    private record ExtractResult(List<Contour> contours, int windingRule) {}

    /** Walk a Shape's path, collecting each closed sub-path as a contour. */
    private static ExtractResult extractAreaContours(Shape shape) {
        List<Contour> contours = new ArrayList<>();
        PathIterator pi = shape.getPathIterator(null, SHAPE_FLATTEN_TOLERANCE);
        int windingRule = pi.getWindingRule();
        double[] coords = new double[6];
        List<Vec> current = null;
        while (!pi.isDone()) {
            int type = pi.currentSegment(coords);
            switch (type) {
                case PathIterator.SEG_MOVETO -> {
                    if (current != null && current.size() >= 3)
                        contours.add(new Contour(current, true));
                    current = new ArrayList<>();
                    current.add(new Vec(coords[0], coords[1]));
                }
                case PathIterator.SEG_LINETO -> {
                    if (current != null)
                        current.add(new Vec(coords[0], coords[1]));
                }
                case PathIterator.SEG_CLOSE -> {
                    if (current != null && current.size() >= 3)
                        contours.add(new Contour(current, true));
                    current = null;
                }
                default -> {
                    // SEG_QUADTO / SEG_CUBICTO should not occur because we requested a
                    // flattened iterator, but guard anyway by taking the endpoint.
                    if (current != null)
                        current.add(new Vec(coords[0], coords[1]));
                }
            }
            pi.next();
        }
        if (current != null && current.size() >= 3)
            contours.add(new Contour(current, true));
        return new ExtractResult(contours, windingRule);
    }

    private static List<Vec> toVecList(List<Point2> points) {
        List<Vec> out = new ArrayList<>(points.size());
        for (Point2 p : points)
            out.add(new Vec(p.getX(), p.getY()));
        return out;
    }

    private static List<Point2> toPoint2List(List<Vec> points) {
        List<Point2> out = new ArrayList<>(points.size());
        for (Vec v : points)
            out.add(new Point2(v.x(), v.y()));
        return out;
    }
}
