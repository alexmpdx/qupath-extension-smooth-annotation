package qupath.ext.simplify.algo;

import java.util.ArrayList;
import java.util.List;

/**
 * Fits a sequence of points with cubic Bézier curves using Philip J. Schneider's
 * algorithm ("An Algorithm for Automatically Fitting Digitized Curves",
 * <i>Graphics Gems</i>, 1990) — the same approach Inkscape's "Simplify" command
 * is built on.
 * <p>
 * The fitter takes an <i>open</i> span of points plus the desired tangent
 * directions at each end, performs a least-squares cubic fit, refines the
 * parameterisation with Newton–Raphson, and recursively subdivides at the point
 * of maximum error when a single cubic cannot meet the tolerance. The result is
 * one or more cubic segments that follow the points smoothly.
 * <p>
 * Because QuPath ROIs are polygonal and cannot store Bézier curves, the fitted
 * curves are subsequently {@linkplain #flatten flattened} back into a dense-enough
 * point list. Corner handling (deciding where the path should stay sharp) is the
 * caller's responsibility — see {@link ContourSimplifier}.
 */
public final class BezierFit {

    private static final int MAX_ITERATIONS = 4;

    private BezierFit() {}

    /**
     * Fit an open span of points with cubic Béziers.
     *
     * @param pts   the span vertices, in order (length >= 2)
     * @param tHat1 unit tangent at the start point, pointing into the span
     * @param tHat2 unit tangent at the end point, pointing into the span
     * @param error maximum allowed deviation (pixels) of any input point from the
     *              fitted curve
     * @return ordered list of cubic segments, each a {@code Vec[4]} of control points
     */
    public static List<Vec[]> fitSpan(List<Vec> pts, Vec tHat1, Vec tHat2, double error) {
        List<Vec[]> out = new ArrayList<>();
        Vec[] d = pts.toArray(new Vec[0]);
        if (d.length < 2)
            return out;
        if (d.length == 2) {
            out.add(lineAsCubic(d[0], d[1], tHat1, tHat2));
            return out;
        }
        fitCubic(d, 0, d.length - 1, tHat1, tHat2, error, out);
        return out;
    }

    private static void fitCubic(Vec[] d, int first, int last, Vec tHat1, Vec tHat2,
                                 double error, List<Vec[]> out) {
        double errorSq = error * error;
        double iterationErrorSq = errorSq * 4.0;
        int nPts = last - first + 1;

        if (nPts == 2) {
            out.add(lineAsCubic(d[first], d[last], tHat1, tHat2));
            return;
        }

        double[] u = chordLengthParameterize(d, first, last);
        Vec[] bez = generateBezier(d, first, last, u, tHat1, tHat2);

        int[] splitPoint = new int[1];
        double maxError = computeMaxError(d, first, last, bez, u, splitPoint);
        if (maxError < errorSq) {
            out.add(bez);
            return;
        }

        // If the error isn't too large, try refining the parameterisation a few times.
        if (maxError < iterationErrorSq) {
            for (int i = 0; i < MAX_ITERATIONS; i++) {
                double[] uPrime = reparameterize(d, first, last, u, bez);
                bez = generateBezier(d, first, last, uPrime, tHat1, tHat2);
                maxError = computeMaxError(d, first, last, bez, uPrime, splitPoint);
                if (maxError < errorSq) {
                    out.add(bez);
                    return;
                }
                u = uPrime;
            }
        }

        // Fitting failed — split at the point of maximum error and recurse.
        Vec tHatCenter = computeCenterTangent(d, splitPoint[0]);
        fitCubic(d, first, splitPoint[0], tHat1, tHatCenter, error, out);
        fitCubic(d, splitPoint[0], last, tHatCenter.negate(), tHat2, error, out);
    }

    /** Two-point span: place handles 1/3 of the way along the chord. */
    private static Vec[] lineAsCubic(Vec p0, Vec p3, Vec tHat1, Vec tHat2) {
        double dist = p0.dist(p3) / 3.0;
        return new Vec[] {
                p0,
                p0.add(tHat1.scale(dist)),
                p3.add(tHat2.scale(dist)),
                p3
        };
    }

    /**
     * Use least-squares to fit a single cubic Bézier to the points, with the end
     * control points constrained to lie along the supplied tangent directions.
     */
    private static Vec[] generateBezier(Vec[] d, int first, int last, double[] uPrime,
                                        Vec tHat1, Vec tHat2) {
        int nPts = last - first + 1;
        Vec[][] a = new Vec[nPts][2];
        for (int i = 0; i < nPts; i++) {
            double u = uPrime[i];
            a[i][0] = tHat1.scale(b1(u));
            a[i][1] = tHat2.scale(b2(u));
        }

        double c00 = 0, c01 = 0, c11 = 0, x0 = 0, x1 = 0;
        Vec p0 = d[first];
        Vec p3 = d[last];
        for (int i = 0; i < nPts; i++) {
            double u = uPrime[i];
            c00 += a[i][0].dot(a[i][0]);
            c01 += a[i][0].dot(a[i][1]);
            c11 += a[i][1].dot(a[i][1]);

            // tmp = d[first+i] - point on the line segment endpoints weighted by the Bernstein basis
            Vec tmp = d[first + i]
                    .sub(p0.scale(b0(u)))
                    .sub(p0.scale(b1(u)))
                    .sub(p3.scale(b2(u)))
                    .sub(p3.scale(b3(u)));
            x0 += a[i][0].dot(tmp);
            x1 += a[i][1].dot(tmp);
        }
        double c10 = c01;

        double detC0C1 = c00 * c11 - c10 * c01;
        double detC0X = c00 * x1 - c10 * x0;
        double detXC1 = x0 * c11 - x1 * c01;

        double alphaL = detC0C1 == 0 ? 0.0 : detXC1 / detC0C1;
        double alphaR = detC0C1 == 0 ? 0.0 : detC0X / detC0C1;

        // If alpha is negative or implausibly small, fall back to the Wu/Barsky
        // heuristic (handles 1/3 of the chord length along each tangent).
        double segLength = p0.dist(p3);
        double epsilon = 1.0e-6 * segLength;
        if (alphaL < epsilon || alphaR < epsilon) {
            return lineAsCubic(p0, p3, tHat1, tHat2);
        }
        return new Vec[] {
                p0,
                p0.add(tHat1.scale(alphaL)),
                p3.add(tHat2.scale(alphaR)),
                p3
        };
    }

    /** Assign an initial parameter value to each point via chord-length parameterisation. */
    private static double[] chordLengthParameterize(Vec[] d, int first, int last) {
        int n = last - first + 1;
        double[] u = new double[n];
        u[0] = 0;
        for (int i = 1; i < n; i++)
            u[i] = u[i - 1] + d[first + i].dist(d[first + i - 1]);
        double total = u[n - 1];
        if (total == 0) {
            for (int i = 0; i < n; i++)
                u[i] = (double) i / (n - 1);
        } else {
            for (int i = 1; i < n; i++)
                u[i] /= total;
        }
        return u;
    }

    /** Refine parameter values using one Newton–Raphson step per point. */
    private static double[] reparameterize(Vec[] d, int first, int last, double[] u, Vec[] bez) {
        int n = last - first + 1;
        double[] uPrime = new double[n];
        for (int i = 0; i < n; i++)
            uPrime[i] = newtonRaphson(bez, d[first + i], u[i]);
        return uPrime;
    }

    private static double newtonRaphson(Vec[] q, Vec p, double u) {
        // Control points of the 1st and 2nd derivative curves.
        Vec[] q1 = new Vec[3];
        for (int i = 0; i <= 2; i++)
            q1[i] = q[i + 1].sub(q[i]).scale(3.0);
        Vec[] q2 = new Vec[2];
        for (int i = 0; i <= 1; i++)
            q2[i] = q1[i + 1].sub(q1[i]).scale(2.0);

        Vec qu = bezierEval(3, q, u);
        Vec q1u = bezierEval(2, q1, u);
        Vec q2u = bezierEval(1, q2, u);

        double numerator = (qu.x() - p.x()) * q1u.x() + (qu.y() - p.y()) * q1u.y();
        double denominator = q1u.x() * q1u.x() + q1u.y() * q1u.y()
                + (qu.x() - p.x()) * q2u.x() + (qu.y() - p.y()) * q2u.y();
        if (denominator == 0)
            return u;
        return u - numerator / denominator;
    }

    /**
     * Find the maximum squared distance from the points to the fitted curve.
     * The index of the worst-fitting point is returned via {@code splitPoint}.
     */
    private static double computeMaxError(Vec[] d, int first, int last, Vec[] bez,
                                          double[] u, int[] splitPoint) {
        splitPoint[0] = (last - first + 1) / 2 + first;
        double maxDist = 0;
        for (int i = first + 1; i < last; i++) {
            Vec p = bezierEval(3, bez, u[i - first]);
            double dist = p.distSq(d[i]);
            if (dist >= maxDist) {
                maxDist = dist;
                splitPoint[0] = i;
            }
        }
        return maxDist;
    }

    private static Vec computeCenterTangent(Vec[] d, int center) {
        Vec v1 = d[center - 1].sub(d[center]);
        Vec v2 = d[center].sub(d[center + 1]);
        return new Vec((v1.x() + v2.x()) / 2.0, (v1.y() + v2.y()) / 2.0).normalize();
    }

    /** Evaluate a Bézier of the given degree at parameter t via de Casteljau. */
    private static Vec bezierEval(int degree, Vec[] v, double t) {
        Vec[] tmp = new Vec[degree + 1];
        System.arraycopy(v, 0, tmp, 0, degree + 1);
        for (int i = 1; i <= degree; i++) {
            for (int j = 0; j <= degree - i; j++) {
                tmp[j] = new Vec(
                        (1.0 - t) * tmp[j].x() + t * tmp[j + 1].x(),
                        (1.0 - t) * tmp[j].y() + t * tmp[j + 1].y());
            }
        }
        return tmp[0];
    }

    // Cubic Bernstein basis functions.
    private static double b0(double u) { double t = 1.0 - u; return t * t * t; }
    private static double b1(double u) { double t = 1.0 - u; return 3.0 * u * t * t; }
    private static double b2(double u) { double t = 1.0 - u; return 3.0 * u * u * t; }
    private static double b3(double u) { return u * u * u; }

    /**
     * Flatten fitted cubic segments into a point list by adaptive subdivision,
     * stopping when each piece is within {@code flatness} pixels of a straight line.
     *
     * @param beziers  cubic segments to flatten (sharing endpoints between consecutive segments)
     * @param flatness maximum deviation (pixels) of the polyline from the true curve;
     *                 smaller values yield more, smoother points
     * @return ordered output points, including the very first start point and every segment end
     */
    public static List<Vec> flatten(List<Vec[]> beziers, double flatness) {
        List<Vec> out = new ArrayList<>();
        if (beziers.isEmpty())
            return out;
        double tol = Math.max(flatness, 1e-4);
        out.add(beziers.get(0)[0]);
        for (Vec[] bez : beziers) {
            subdivide(bez[0], bez[1], bez[2], bez[3], tol, out, 0);
            out.add(bez[3]);
        }
        return out;
    }

    private static void subdivide(Vec p0, Vec p1, Vec p2, Vec p3, double tol,
                                  List<Vec> out, int depth) {
        if (depth >= 24 || isFlat(p0, p1, p2, p3, tol))
            return; // endpoint is added by the caller / by the parent's second half

        // de Casteljau split at t = 0.5
        Vec p01 = mid(p0, p1);
        Vec p12 = mid(p1, p2);
        Vec p23 = mid(p2, p3);
        Vec p012 = mid(p01, p12);
        Vec p123 = mid(p12, p23);
        Vec p0123 = mid(p012, p123);

        subdivide(p0, p01, p012, p0123, tol, out, depth + 1);
        out.add(p0123);
        subdivide(p0123, p123, p23, p3, tol, out, depth + 1);
    }

    /** A cubic is "flat enough" when both inner control points are within tol of the chord. */
    private static boolean isFlat(Vec p0, Vec p1, Vec p2, Vec p3, double tol) {
        double d1 = perpDistSq(p1, p0, p3);
        double d2 = perpDistSq(p2, p0, p3);
        return Math.max(d1, d2) <= tol * tol;
    }

    private static double perpDistSq(Vec p, Vec a, Vec b) {
        double dx = b.x() - a.x();
        double dy = b.y() - a.y();
        double lenSq = dx * dx + dy * dy;
        if (lenSq == 0)
            return p.distSq(a);
        double cross = (p.x() - a.x()) * dy - (p.y() - a.y()) * dx;
        return (cross * cross) / lenSq;
    }

    private static Vec mid(Vec a, Vec b) {
        return new Vec((a.x() + b.x()) / 2.0, (a.y() + b.y()) / 2.0);
    }
}
