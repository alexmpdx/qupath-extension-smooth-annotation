package qupath.ext.simplify.algo;

import java.util.ArrayList;
import java.util.List;

/**
 * Classic Ramer–Douglas–Peucker polyline decimation.
 * <p>
 * This is used only as an optional pre-pass: it removes points that already lie
 * (within a tolerance) on a straight line between their neighbours, giving the
 * curve fitter cleaner, less redundant input. It is <i>not</i> the smoothing
 * step — on its own it produces exactly the kind of straight-segment result that
 * QuPath's built-in simplifier does.
 */
public final class DouglasPeucker {

    private DouglasPeucker() {}

    /**
     * Decimate an open polyline.
     *
     * @param points    input vertices
     * @param tolerance maximum perpendicular deviation (pixels) a removed point
     *                  may have from the retained chord; {@code <= 0} returns the
     *                  input unchanged
     */
    public static List<Vec> simplifyOpen(List<Vec> points, double tolerance) {
        if (tolerance <= 0 || points.size() < 3)
            return new ArrayList<>(points);
        boolean[] keep = new boolean[points.size()];
        keep[0] = true;
        keep[points.size() - 1] = true;
        recurse(points, 0, points.size() - 1, tolerance, keep);
        return collect(points, keep);
    }

    /**
     * Decimate a closed ring. The ring is split at the two points furthest apart
     * so the result is independent of where the contour happens to start.
     */
    public static List<Vec> simplifyClosed(List<Vec> points, double tolerance) {
        int n = points.size();
        if (tolerance <= 0 || n < 4)
            return new ArrayList<>(points);

        // Anchor on the point furthest from points.get(0), then the point
        // furthest from that, to get two stable split anchors.
        int a = farthestFrom(points, 0);
        int b = farthestFrom(points, a);
        if (a == b)
            return new ArrayList<>(points);
        int lo = Math.min(a, b);
        int hi = Math.max(a, b);

        boolean[] keep = new boolean[n];
        keep[lo] = true;
        keep[hi] = true;
        recurse(points, lo, hi, tolerance, keep);
        // The wrap-around segment hi -> ... -> n-1 -> 0 -> ... -> lo
        recurseWrap(points, hi, lo, tolerance, keep);
        return collect(points, keep);
    }

    private static int farthestFrom(List<Vec> pts, int idx) {
        Vec p = pts.get(idx);
        int best = idx;
        double bestD = -1;
        for (int i = 0; i < pts.size(); i++) {
            double d = p.distSq(pts.get(i));
            if (d > bestD) {
                bestD = d;
                best = i;
            }
        }
        return best;
    }

    private static void recurse(List<Vec> pts, int first, int last, double tol, boolean[] keep) {
        if (last <= first + 1)
            return;
        double maxDist = -1;
        int index = -1;
        Vec a = pts.get(first);
        Vec b = pts.get(last);
        for (int i = first + 1; i < last; i++) {
            double d = perpDistance(pts.get(i), a, b);
            if (d > maxDist) {
                maxDist = d;
                index = i;
            }
        }
        if (maxDist > tol && index > 0) {
            keep[index] = true;
            recurse(pts, first, index, tol, keep);
            recurse(pts, index, last, tol, keep);
        }
    }

    /** Recurse over the segment that wraps from {@code first} forward past the end to {@code last}. */
    private static void recurseWrap(List<Vec> pts, int first, int last, double tol, boolean[] keep) {
        int n = pts.size();
        int count = (last - first + n) % n;
        if (count <= 1)
            return;
        double maxDist = -1;
        int index = -1;
        Vec a = pts.get(first);
        Vec b = pts.get(last);
        for (int k = 1; k < count; k++) {
            int i = (first + k) % n;
            double d = perpDistance(pts.get(i), a, b);
            if (d > maxDist) {
                maxDist = d;
                index = i;
            }
        }
        if (maxDist > tol && index >= 0) {
            keep[index] = true;
            recurseWrap(pts, first, index, tol, keep);
            recurseWrap(pts, index, last, tol, keep);
        }
    }

    private static double perpDistance(Vec p, Vec a, Vec b) {
        double dx = b.x() - a.x();
        double dy = b.y() - a.y();
        double lenSq = dx * dx + dy * dy;
        if (lenSq == 0)
            return p.dist(a);
        double t = ((p.x() - a.x()) * dx + (p.y() - a.y()) * dy) / lenSq;
        double projX = a.x() + t * dx;
        double projY = a.y() + t * dy;
        double ex = p.x() - projX;
        double ey = p.y() - projY;
        return Math.sqrt(ex * ex + ey * ey);
    }

    private static List<Vec> collect(List<Vec> pts, boolean[] keep) {
        List<Vec> out = new ArrayList<>();
        for (int i = 0; i < pts.size(); i++) {
            if (keep[i])
                out.add(pts.get(i));
        }
        return out;
    }
}
