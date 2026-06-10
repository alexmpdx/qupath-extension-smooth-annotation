package qupath.ext.simplify.algo;

/**
 * Minimal immutable 2D vector with the handful of operations needed by the
 * curve-fitting and smoothing routines. Kept deliberately tiny so the algorithm
 * code reads like the maths it implements.
 */
public record Vec(double x, double y) {

    public Vec add(Vec o) {
        return new Vec(x + o.x, y + o.y);
    }

    public Vec sub(Vec o) {
        return new Vec(x - o.x, y - o.y);
    }

    public Vec scale(double s) {
        return new Vec(x * s, y * s);
    }

    public double dot(Vec o) {
        return x * o.x + y * o.y;
    }

    public double lengthSq() {
        return x * x + y * y;
    }

    public double length() {
        return Math.sqrt(lengthSq());
    }

    public double dist(Vec o) {
        return sub(o).length();
    }

    public double distSq(Vec o) {
        return sub(o).lengthSq();
    }

    /** Return a unit-length copy; a zero vector is returned unchanged. */
    public Vec normalize() {
        double len = length();
        if (len == 0)
            return this;
        return new Vec(x / len, y / len);
    }

    public Vec negate() {
        return new Vec(-x, -y);
    }
}
