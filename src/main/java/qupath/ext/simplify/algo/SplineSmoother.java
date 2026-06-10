package qupath.ext.simplify.algo;

import java.util.ArrayList;
import java.util.List;

/**
 * Spline-based smoothing alternatives to the Bézier fitter:
 * <ul>
 *   <li><b>Chaikin</b> — iterative corner cutting. Simple, extremely robust, and
 *       produces visually smooth curves. Endpoints of open spans are kept fixed.</li>
 *   <li><b>Catmull–Rom</b> — an interpolating spline that passes exactly through
 *       the input points (so the outline never pulls away from the traced vertices),
 *       converted to cubic Béziers and flattened.</li>
 * </ul>
 * Corner handling is the caller's responsibility: {@link ContourSimplifier} splits
 * the contour at corners and smooths each span independently, so corners stay sharp.
 */
public final class SplineSmoother {

    private SplineSmoother() {}

    // ---- Chaikin corner cutting ---------------------------------------------

    /** Chaikin smoothing of an open span; the first and last points are preserved exactly. */
    public static List<Vec> chaikinOpen(List<Vec> pts, int iterations) {
        List<Vec> current = new ArrayList<>(pts);
        for (int it = 0; it < iterations && current.size() >= 3; it++) {
            List<Vec> next = new ArrayList<>();
            next.add(current.get(0)); // keep the start anchor
            for (int i = 0; i < current.size() - 1; i++) {
                Vec p = current.get(i);
                Vec q = current.get(i + 1);
                next.add(lerp(p, q, 0.25));
                next.add(lerp(p, q, 0.75));
            }
            next.add(current.get(current.size() - 1)); // keep the end anchor
            current = next;
        }
        return current;
    }

    /** Chaikin smoothing of a closed ring. */
    public static List<Vec> chaikinClosed(List<Vec> pts, int iterations) {
        List<Vec> current = new ArrayList<>(pts);
        for (int it = 0; it < iterations && current.size() >= 3; it++) {
            List<Vec> next = new ArrayList<>();
            int n = current.size();
            for (int i = 0; i < n; i++) {
                Vec p = current.get(i);
                Vec q = current.get((i + 1) % n);
                next.add(lerp(p, q, 0.25));
                next.add(lerp(p, q, 0.75));
            }
            current = next;
        }
        return current;
    }

    // ---- Catmull–Rom interpolating spline -----------------------------------

    /**
     * Catmull–Rom spline through an open span, flattened to {@code flatness} pixels.
     * The curve interpolates every input point, so the outline stays anchored to
     * the traced vertices.
     */
    public static List<Vec> catmullRomOpen(List<Vec> pts, double flatness) {
        int n = pts.size();
        if (n < 3)
            return new ArrayList<>(pts);
        List<Vec[]> beziers = new ArrayList<>();
        for (int i = 0; i < n - 1; i++) {
            Vec p0 = pts.get(Math.max(i - 1, 0));
            Vec p1 = pts.get(i);
            Vec p2 = pts.get(i + 1);
            Vec p3 = pts.get(Math.min(i + 2, n - 1));
            beziers.add(catmullToBezier(p0, p1, p2, p3));
        }
        return BezierFit.flatten(beziers, flatness);
    }

    /** Catmull–Rom spline through a closed ring, flattened to {@code flatness} pixels. */
    public static List<Vec> catmullRomClosed(List<Vec> pts, double flatness) {
        int n = pts.size();
        if (n < 3)
            return new ArrayList<>(pts);
        List<Vec[]> beziers = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            Vec p0 = pts.get((i - 1 + n) % n);
            Vec p1 = pts.get(i);
            Vec p2 = pts.get((i + 1) % n);
            Vec p3 = pts.get((i + 2) % n);
            beziers.add(catmullToBezier(p0, p1, p2, p3));
        }
        return BezierFit.flatten(beziers, flatness);
    }

    /**
     * Convert a uniform Catmull–Rom segment (defined by p1..p2 with neighbours
     * p0 and p3) to an equivalent cubic Bézier.
     */
    private static Vec[] catmullToBezier(Vec p0, Vec p1, Vec p2, Vec p3) {
        Vec b1 = p1.add(p2.sub(p0).scale(1.0 / 6.0));
        Vec b2 = p2.sub(p3.sub(p1).scale(1.0 / 6.0));
        return new Vec[] {p1, b1, b2, p2};
    }

    private static Vec lerp(Vec a, Vec b, double t) {
        return new Vec(a.x() + (b.x() - a.x()) * t, a.y() + (b.y() - a.y()) * t);
    }
}
