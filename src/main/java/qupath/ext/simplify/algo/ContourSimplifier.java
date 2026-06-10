package qupath.ext.simplify.algo;

import java.util.ArrayList;
import java.util.List;

/**
 * Simplifies a single {@link Contour} (one ring or polyline) using the chosen
 * method, while preserving genuine corners so the shape is smoothed but not warped.
 * <p>
 * The pipeline is:
 * <ol>
 *   <li>drop consecutive duplicate points;</li>
 *   <li>optionally decimate with Douglas–Peucker (the "smoothing strength" knob for
 *       the spline methods; the Bézier fitter handles decimation itself);</li>
 *   <li>detect corners — vertices where the path turns sharply — and split the
 *       contour into smooth spans between them;</li>
 *   <li>fit/smooth each span independently and re-join.</li>
 * </ol>
 */
public final class ContourSimplifier {

    /** Smoothing method exposed to the user. */
    public enum Method {
        BEZIER("Bézier fit (Inkscape-style)"),
        CHAIKIN("Spline — Chaikin"),
        CATMULL_ROM("Spline — Catmull-Rom");

        private final String displayName;

        Method(String displayName) {
            this.displayName = displayName;
        }

        @Override
        public String toString() {
            return displayName;
        }
    }

    /**
     * User-tunable settings.
     *
     * @param method            smoothing method
     * @param smoothing         smoothing strength (pixels). For {@link Method#BEZIER} this is
     *                          the maximum allowed deviation of the fitted curve from the
     *                          input points. For the spline methods it is the Douglas–Peucker
     *                          tolerance applied before smoothing (higher = fewer input nodes).
     * @param resolution        output resampling tolerance (pixels): the maximum deviation of
     *                          the stored polygon from the smooth curve. Smaller values keep
     *                          more points and look smoother; larger values reduce node count.
     * @param cornerAngleDeg    turn-angle threshold (degrees) above which a vertex is treated
     *                          as a sharp corner and kept; 180 disables corner preservation
     *                          (everything is smoothed).
     * @param cornerNeighborhood arc-length distance (pixels) over which the bend angle is
     *                          measured when detecting corners. Larger values ignore fine
     *                          wiggles/noise and only flag genuinely sustained turns;
     *                          {@code <= 0} falls back to measuring across adjacent segments.
     * @param chaikinIterations number of Chaikin corner-cutting iterations.
     */
    public record Settings(Method method, double smoothing, double resolution,
                           double cornerAngleDeg, double cornerNeighborhood, int chaikinIterations) {}

    private ContourSimplifier() {}

    public static Contour simplify(Contour contour, Settings settings) {
        List<Vec> pts = dropDuplicates(contour.points());
        boolean closed = contour.closed();

        if (pts.size() < (closed ? 4 : 3))
            return new Contour(pts, closed);

        // 1. Optional pre-decimation (the smoothing knob for spline methods).
        double preTol = settings.method() == Method.BEZIER ? 0.0 : settings.smoothing();
        if (preTol > 0) {
            pts = closed ? DouglasPeucker.simplifyClosed(pts, preTol)
                         : DouglasPeucker.simplifyOpen(pts, preTol);
            if (pts.size() < (closed ? 4 : 3))
                return new Contour(pts, closed);
        }

        // 2. Corner detection.
        List<Integer> corners = detectCorners(pts, closed, settings.cornerAngleDeg(),
                settings.cornerNeighborhood());

        // 3 & 4. Split into spans and smooth each.
        List<Vec> out;
        if (closed && corners.isEmpty()) {
            out = smoothClosedNoCorners(pts, settings);
        } else {
            out = smoothBySpans(pts, closed, corners, settings);
        }

        out = dropDuplicates(out);
        return new Contour(out, closed);
    }

    // ---- corner detection ---------------------------------------------------

    private static List<Integer> detectCorners(List<Vec> pts, boolean closed,
                                               double cornerAngleDeg, double neighborhood) {
        List<Integer> corners = new ArrayList<>();
        if (cornerAngleDeg >= 180.0)
            return corners;
        double threshold = Math.toRadians(cornerAngleDeg);
        int n = pts.size();

        // Measure the bend at every vertex over the neighbourhood, so individual
        // noisy segments don't register as corners.
        double[] turn = new double[n];
        for (int i = 0; i < n; i++)
            turn[i] = bendAngle(pts, i, neighborhood, closed);

        int start = closed ? 0 : 1;
        int end = closed ? n : n - 1;
        for (int i = start; i < end; i++) {
            // Threshold + non-maximum suppression: keep only the sharpest vertex within
            // a neighbourhood, so a single corner sampled by several points isn't flagged
            // multiple times.
            if (turn[i] >= threshold && isLocalMax(turn, pts, i, neighborhood, closed))
                corners.add(i);
        }
        return corners;
    }

    /**
     * Bend angle (radians) at vertex {@code i}, measured between the direction arriving
     * from {@code neighborhood} pixels back and the direction leaving {@code neighborhood}
     * pixels ahead (arc length). 0 means straight; larger means a sharper turn.
     */
    private static double bendAngle(List<Vec> pts, int i, double neighborhood, boolean closed) {
        int n = pts.size();
        Vec cur = pts.get(i);
        Vec backPt;
        Vec fwdPt;
        if (neighborhood <= 0) {
            backPt = pts.get((i - 1 + n) % n);
            fwdPt = pts.get((i + 1) % n);
        } else {
            backPt = walk(pts, i, neighborhood, -1, closed);
            fwdPt = walk(pts, i, neighborhood, 1, closed);
        }
        Vec incoming = cur.sub(backPt);
        Vec outgoing = fwdPt.sub(cur);
        double la = incoming.length();
        double lb = outgoing.length();
        if (la == 0 || lb == 0)
            return 0;
        double cos = Math.max(-1.0, Math.min(1.0, incoming.dot(outgoing) / (la * lb)));
        return Math.acos(cos);
    }

    /**
     * Walk along the contour from vertex {@code i} in direction {@code dir} (+1 forward,
     * -1 backward) until {@code radius} pixels of arc length have been covered, returning
     * the (interpolated) point reached. For open contours, stops at the endpoint.
     */
    private static Vec walk(List<Vec> pts, int i, double radius, int dir, boolean closed) {
        int n = pts.size();
        Vec cur = pts.get(i);
        double remaining = radius;
        int idx = i;
        for (int step = 0; step < n; step++) {
            int next = idx + dir;
            if (closed)
                next = ((next % n) + n) % n;
            else if (next < 0 || next >= n)
                return cur;
            Vec nextPt = pts.get(next);
            double seg = cur.dist(nextPt);
            if (seg >= remaining) {
                double t = seg == 0 ? 0 : remaining / seg;
                return new Vec(cur.x() + (nextPt.x() - cur.x()) * t,
                        cur.y() + (nextPt.y() - cur.y()) * t);
            }
            remaining -= seg;
            cur = nextPt;
            idx = next;
            if (idx == i)
                break; // wrapped all the way around a small closed ring
        }
        return cur;
    }

    /** True if {@code turn[i]} is the strongest bend within {@code neighborhood} pixels either way. */
    private static boolean isLocalMax(double[] turn, List<Vec> pts, int i,
                                      double neighborhood, boolean closed) {
        if (neighborhood <= 0)
            return true;
        int n = pts.size();
        for (int dir = -1; dir <= 1; dir += 2) {
            double acc = 0;
            int idx = i;
            for (int step = 0; step < n; step++) {
                int next = idx + dir;
                if (closed)
                    next = ((next % n) + n) % n;
                else if (next < 0 || next >= n)
                    break;
                acc += pts.get(idx).dist(pts.get(next));
                if (acc > neighborhood)
                    break;
                // Suppress i if a neighbour bends more (ties broken towards the lower index).
                if (turn[next] > turn[i] || (turn[next] == turn[i] && next < i))
                    return false;
                idx = next;
                if (idx == i)
                    break;
            }
        }
        return true;
    }

    // ---- span-based smoothing (corners preserved) ---------------------------

    private static List<Vec> smoothBySpans(List<Vec> pts, boolean closed,
                                           List<Integer> corners, Settings settings) {
        int n = pts.size();
        // Anchor indices that bound the spans.
        List<Integer> anchors = new ArrayList<>();
        if (closed) {
            anchors.addAll(corners);
        } else {
            anchors.add(0);
            anchors.addAll(corners);
            anchors.add(n - 1);
        }

        List<Vec> out = new ArrayList<>();
        int spanCount = closed ? anchors.size() : anchors.size() - 1;
        for (int s = 0; s < spanCount; s++) {
            int a = anchors.get(s);
            int b = closed ? anchors.get((s + 1) % anchors.size()) : anchors.get(s + 1);
            List<Vec> span = extractSpan(pts, a, b, closed);
            List<Vec> smoothed = smoothSpan(span, settings);
            appendSpan(out, smoothed);
        }
        return out;
    }

    /** Extract the inclusive span of points from index a to index b (wrapping if closed). */
    private static List<Vec> extractSpan(List<Vec> pts, int a, int b, boolean closed) {
        int n = pts.size();
        List<Vec> span = new ArrayList<>();
        if (!closed) {
            for (int i = a; i <= b; i++)
                span.add(pts.get(i));
            return span;
        }
        // do-while so that a == b (a single corner on the ring) produces the full
        // loop back to the corner, rather than a degenerate one-point span.
        int i = a;
        span.add(pts.get(i));
        do {
            i = (i + 1) % n;
            span.add(pts.get(i));
        } while (i != b);
        return span;
    }

    private static List<Vec> smoothSpan(List<Vec> span, Settings settings) {
        if (span.size() <= 2)
            return new ArrayList<>(span);
        switch (settings.method()) {
            case BEZIER -> {
                Vec tHat1 = span.get(1).sub(span.get(0)).normalize();
                Vec tHat2 = span.get(span.size() - 2).sub(span.get(span.size() - 1)).normalize();
                List<Vec[]> beziers = BezierFit.fitSpan(span, tHat1, tHat2, settings.smoothing());
                return BezierFit.flatten(beziers, settings.resolution());
            }
            case CATMULL_ROM -> {
                return SplineSmoother.catmullRomOpen(span, settings.resolution());
            }
            case CHAIKIN -> {
                List<Vec> sm = SplineSmoother.chaikinOpen(span, settings.chaikinIterations());
                // Thin to the requested output resolution so the node count is controllable.
                return DouglasPeucker.simplifyOpen(sm, settings.resolution());
            }
            default -> {
                return new ArrayList<>(span);
            }
        }
    }

    private static List<Vec> smoothClosedNoCorners(List<Vec> pts, Settings settings) {
        switch (settings.method()) {
            case BEZIER -> {
                int n = pts.size();
                // Treat the ring as an open span that returns to its start, with a
                // smooth (G1-continuous) tangent across the seam.
                List<Vec> d = new ArrayList<>(pts);
                d.add(pts.get(0));
                Vec prev = pts.get(n - 1);
                Vec next = pts.get(1);
                Vec tHat1 = next.sub(prev).normalize();
                Vec tHat2 = tHat1.negate();
                List<Vec[]> beziers = BezierFit.fitSpan(d, tHat1, tHat2, settings.smoothing());
                List<Vec> flat = BezierFit.flatten(beziers, settings.resolution());
                // Drop the repeated closing point; the polygon closes implicitly.
                if (flat.size() > 1)
                    flat.remove(flat.size() - 1);
                return flat;
            }
            case CATMULL_ROM -> {
                return SplineSmoother.catmullRomClosed(pts, settings.resolution());
            }
            case CHAIKIN -> {
                List<Vec> sm = SplineSmoother.chaikinClosed(pts, settings.chaikinIterations());
                return DouglasPeucker.simplifyClosed(sm, settings.resolution());
            }
            default -> {
                return new ArrayList<>(pts);
            }
        }
    }

    /** Append a smoothed span, dropping the leading point if it duplicates the current tail. */
    private static void appendSpan(List<Vec> out, List<Vec> span) {
        if (span.isEmpty())
            return;
        int from = 0;
        if (!out.isEmpty() && nearlyEqual(out.get(out.size() - 1), span.get(0)))
            from = 1;
        for (int i = from; i < span.size(); i++)
            out.add(span.get(i));
    }

    // ---- helpers ------------------------------------------------------------

    private static List<Vec> dropDuplicates(List<Vec> pts) {
        List<Vec> out = new ArrayList<>(pts.size());
        for (Vec p : pts) {
            if (out.isEmpty() || !nearlyEqual(out.get(out.size() - 1), p))
                out.add(p);
        }
        // For a ring, also drop a final point equal to the first.
        if (out.size() > 1 && nearlyEqual(out.get(0), out.get(out.size() - 1)))
            out.remove(out.size() - 1);
        return out;
    }

    private static boolean nearlyEqual(Vec a, Vec b) {
        return a.distSq(b) < 1e-12;
    }
}
