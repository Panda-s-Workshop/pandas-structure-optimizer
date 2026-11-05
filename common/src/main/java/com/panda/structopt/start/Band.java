package com.panda.structopt.start;

/**
 * Which chunks the lookahead computes for one moving player. The chunk system wants structure starts for every chunk within
 * {@code radius} chunks (Chebyshev distance: view distance plus the starts radius of a full chunk) of the player. The band is
 * the ring from just inside that square out to {@code extra} chunks past it, limited to the chunks the square will actually cover
 * if the player keeps going in a straight line: a chunk is wanted when, for some {@code t >= 0}, it lies within {@code radius} of
 * the point {@code t} chunks along the direction of travel. Chunks only a turn would bring into reach are left out, since computing
 * them ahead would usually be wasted; a turning player gets a new scan on the next cycle.
 */
public final class Band {
    private Band() {}

    /** True for a chunk at offset {@code dx, dz} from the player's chunk, with {@code vx, vz} the unit direction of travel. */
    public static boolean wanted(int dx, int dz, double vx, double vz, int radius, int extra) {
        int d = Math.max(Math.abs(dx), Math.abs(dz));
        if (d < radius - 1 || d > radius + extra) return false;
        return reached(dx, dz, vx, vz, radius);
    }

    /** Whether the square of half-size {@code radius}, moving from the origin along {@code (vx, vz)}, ever covers {@code (dx, dz)}. */
    static boolean reached(int dx, int dz, double vx, double vz, int radius) {
        double lo = 0, hi = Double.POSITIVE_INFINITY;
        double[] x = interval(dx, vx, radius), z = interval(dz, vz, radius);
        if (x == null || z == null) return false;
        lo = Math.max(lo, Math.max(x[0], z[0]));
        hi = Math.min(hi, Math.min(x[1], z[1]));
        return lo <= hi;
    }

    /** The times {@code t} at which {@code |c - v t| <= r}, or null when never. */
    private static double[] interval(int c, double v, int r) {
        if (Math.abs(v) < 1e-9) return Math.abs(c) <= r ? new double[] {Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY} : null;
        double a = (c - r) / v, b = (c + r) / v;
        return new double[] {Math.min(a, b), Math.max(a, b)};
    }
}
