package com.panda.structopt.start;

/**
 * Which chunks the lookahead computes for one moving player. The chunk system wants structure starts for every chunk within
 * {@code radius} chunks (Chebyshev distance: view distance plus the starts radius of a full chunk) of the chunk the player is in.
 * The band is the ring from just inside that square out to {@code extra} chunks past it, limited to the chunks the square will
 * actually cover if the player keeps going in a straight line: a chunk is wanted when, for some {@code t >= 0}, it lies within
 * {@code radius} of the chunk containing the point {@code t} chunks along the direction of travel. Where in its chunk the player
 * is matters: a player near an edge crosses into the next chunk, and pulls in a new row, almost at once. Chunks only a turn would
 * bring into reach are left out, since computing them ahead would usually be wasted; a turning player gets a new scan on the next
 * cycle.
 */
public final class Band {
    private Band() {}

    /**
     * True for a chunk at offset {@code dx, dz} from the player's chunk, with {@code fx, fz} the player's position inside that
     * chunk (in chunks, 0 to 1) and {@code vx, vz} the unit direction of travel.
     */
    public static boolean wanted(int dx, int dz, double fx, double fz, double vx, double vz, int radius, int extra) {
        int d = Math.max(Math.abs(dx), Math.abs(dz));
        if (d < radius - 1 || d > radius + extra) return false;
        return reached(dx, dz, fx, fz, vx, vz, radius);
    }

    /**
     * Whether the player, moving from {@code (fx, fz)} along {@code (vx, vz)}, is ever in a chunk within {@code radius} of
     * {@code (dx, dz)}.
     */
    static boolean reached(int dx, int dz, double fx, double fz, double vx, double vz, int radius) {
        double[] x = interval(dx, fx, vx, radius), z = interval(dz, fz, vz, radius);
        if (x == null || z == null) return false;
        double lo = Math.max(0, Math.max(x[0], z[0]));
        double hi = Math.min(x[1], z[1]);
        return hi - lo > TOUCH;
    }

    /** A chunk needed for less than this much travel (one axis leaving range as the other enters it) is not worth computing. */
    static final double TOUCH = 1e-9;

    /**
     * The times {@code t} at which the player's chunk {@code floor(f + v t)} is within {@code r} of {@code c}, that is
     * {@code c - r <= f + v t < c + r + 1}, or null when never.
     */
    private static double[] interval(int c, double f, double v, int r) {
        double lo = c - r - f, hi = c + r + 1 - f;
        if (Math.abs(v) < 1e-9) return lo <= 0 && 0 < hi ? new double[] {Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY} : null;
        double a = lo / v, b = hi / v;
        return new double[] {Math.min(a, b), Math.max(a, b)};
    }
}
