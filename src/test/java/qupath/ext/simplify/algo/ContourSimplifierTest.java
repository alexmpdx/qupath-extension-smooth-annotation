package qupath.ext.simplify.algo;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContourSimplifierTest {

    private static final ContourSimplifier.Settings BEZIER =
            new ContourSimplifier.Settings(ContourSimplifier.Method.BEZIER, 1.5, 0.25, 75.0, 4.0, 3);

    @Test
    void smoothedCircleStaysCloseToCircle() {
        double cx = 100, cy = 100, r = 50;
        List<Vec> pts = new ArrayList<>();
        // Dense, slightly noisy circle.
        for (int i = 0; i < 200; i++) {
            double a = 2 * Math.PI * i / 200;
            double noise = ((i * 37) % 5 - 2) * 0.1; // small deterministic jitter
            pts.add(new Vec(cx + (r + noise) * Math.cos(a), cy + (r + noise) * Math.sin(a)));
        }
        Contour result = ContourSimplifier.simplify(new Contour(pts, true), BEZIER);

        assertFalse(result.points().isEmpty());
        // Far fewer nodes than the dense input.
        assertTrue(result.points().size() < pts.size() / 2,
                "Expected node reduction, got " + result.points().size());
        // Every output vertex should still lie near the original circle.
        double maxErr = 0;
        for (Vec p : result.points()) {
            double d = Math.abs(Math.hypot(p.x() - cx, p.y() - cy) - r);
            maxErr = Math.max(maxErr, d);
        }
        assertTrue(maxErr < 2.0, "Outline drifted from the circle by " + maxErr + " px");
    }

    @Test
    void closedContourWithSingleCornerDoesNotCollapse() {
        // Regression: a closed ring with exactly one detected corner must produce a
        // full smoothed loop, not a degenerate one-point span.
        double cx = 200, cy = 200, rx = 60, ry = 80;
        List<Vec> pts = new ArrayList<>();
        for (int i = 0; i < 120; i++) {
            double t = 2 * Math.PI * i / 120;
            double w = 1 + 0.15 * Math.sin(5 * t) + ((i * 17) % 5 - 2) * 0.01;
            pts.add(new Vec(cx + rx * w * Math.cos(t), cy + ry * w * Math.sin(t)));
        }
        // neighborhood = 0 forces single-segment corner detection, which finds exactly
        // one corner on this ring -- the case that used to collapse to a single point.
        var singleSegment = new ContourSimplifier.Settings(
                ContourSimplifier.Method.BEZIER, 2.0, 0.5, 75.0, 0.0, 3);
        Contour result = ContourSimplifier.simplify(new Contour(pts, true), singleSegment);
        assertTrue(result.points().size() > 10,
                "Single-corner ring collapsed to " + result.points().size() + " points");
    }

    @Test
    void neighborhoodDetectionSuppressesNoiseCorners() {
        // Noisy circle: strong per-vertex radial jitter makes single-segment angles
        // spike past the corner threshold, but the underlying shape is smooth.
        double cx = 100, cy = 100, r = 50;
        List<Vec> pts = new ArrayList<>();
        for (int i = 0; i < 220; i++) {
            double a = 2 * Math.PI * i / 220;
            double noise = ((i * 131) % 7 - 3) * 0.5; // ±1.5 px, flips every vertex
            pts.add(new Vec(cx + (r + noise) * Math.cos(a), cy + (r + noise) * Math.sin(a)));
        }
        // Smoothing tolerance is set above the noise amplitude, so what's being tested
        // is purely whether noise is mistaken for corners (which pins the fit to the
        // noisy vertices), not the smoothing tolerance itself.
        var withNeighborhood = new ContourSimplifier.Settings(
                ContourSimplifier.Method.BEZIER, 3.0, 0.3, 75.0, 6.0, 3);
        var perSegment = new ContourSimplifier.Settings(
                ContourSimplifier.Method.BEZIER, 3.0, 0.3, 75.0, 0.0, 3);

        int sharpNbr = countSharpTurns(
                ContourSimplifier.simplify(new Contour(pts, true), withNeighborhood).points(),
                Math.toRadians(60));
        int sharpSeg = countSharpTurns(
                ContourSimplifier.simplify(new Contour(pts, true), perSegment).points(),
                Math.toRadians(60));

        assertTrue(sharpNbr <= sharpSeg,
                "Neighborhood detection should produce no more kinks than per-segment (" + sharpNbr
                        + " vs " + sharpSeg + ")");
        assertTrue(sharpNbr <= 3, "Neighborhood result should be essentially smooth, found " + sharpNbr);
    }

    @Test
    void openLineDoesNotOvershoot() {
        // Regression: a near-collinear jittery polyline with a tight tolerance and no
        // corner preservation used to make the Bézier fit produce huge control handles,
        // ballooning points far off the line. The output must stay near the input bounds.
        List<Vec> pts = new ArrayList<>();
        for (int i = 0; i < 32; i++) {
            double x = i * 4.0;
            double y = 5 + Math.sin(i * 0.5) * 1.2 + ((i * 29) % 7 - 3) * 0.4; // small wiggle/noise
            pts.add(new Vec(x, y));
        }
        double minX = Double.MAX_VALUE, maxX = -Double.MAX_VALUE;
        double minY = Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
        for (Vec p : pts) {
            minX = Math.min(minX, p.x()); maxX = Math.max(maxX, p.x());
            minY = Math.min(minY, p.y()); maxY = Math.max(maxY, p.y());
        }
        // Trigger conditions: tiny smoothing tolerance, corner preservation disabled.
        var tight = new ContourSimplifier.Settings(
                ContourSimplifier.Method.BEZIER, 0.05, 1.0, 180.0, 10.0, 3);
        Contour out = ContourSimplifier.simplify(new Contour(pts, false), tight);

        double margin = 5.0; // allow modest curve overshoot, but nothing wild
        for (Vec p : out.points()) {
            assertTrue(p.x() >= minX - margin && p.x() <= maxX + margin
                            && p.y() >= minY - margin && p.y() <= maxY + margin,
                    "Output point " + p + " ballooned outside input bounds "
                            + "x[" + minX + "," + maxX + "] y[" + minY + "," + maxY + "]");
        }
    }

    @Test
    void sharpCornersArePreserved() {
        // A square traced with extra points along each edge.
        List<Vec> corners = List.of(new Vec(0, 0), new Vec(100, 0), new Vec(100, 100), new Vec(0, 100));
        List<Vec> pts = new ArrayList<>();
        for (int c = 0; c < 4; c++) {
            Vec a = corners.get(c);
            Vec b = corners.get((c + 1) % 4);
            for (int k = 0; k < 10; k++) {
                double t = k / 10.0;
                pts.add(new Vec(a.x() + (b.x() - a.x()) * t, a.y() + (b.y() - a.y()) * t));
            }
        }
        Contour result = ContourSimplifier.simplify(new Contour(pts, true), BEZIER);

        // Count near-right-angle turns in the output; a preserved square keeps ~4.
        int sharp = countSharpTurns(result.points(), Math.toRadians(60));
        assertTrue(sharp >= 4, "Expected the 4 square corners to survive, found " + sharp);
        // And the smoothing shouldn't have exploded the node count.
        assertTrue(result.points().size() < 30,
                "Square should simplify to few nodes, got " + result.points().size());
    }

    private static int countSharpTurns(List<Vec> pts, double threshold) {
        int n = pts.size();
        int count = 0;
        for (int i = 0; i < n; i++) {
            Vec prev = pts.get((i - 1 + n) % n);
            Vec cur = pts.get(i);
            Vec next = pts.get((i + 1) % n);
            Vec u = cur.sub(prev);
            Vec v = next.sub(cur);
            if (u.length() == 0 || v.length() == 0)
                continue;
            double cos = Math.max(-1, Math.min(1, u.dot(v) / (u.length() * v.length())));
            if (Math.acos(cos) >= threshold)
                count++;
        }
        return count;
    }
}
