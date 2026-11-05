package com.panda.structopt.start;

/**
 * Which chunks the lookahead computes for one moving player. The chunk system already wants structure starts out to
 * {@code radius} chunks (view distance plus the starts radius of a full chunk), so the band begins just inside that and runs
 * {@code extra} chunks further along the direction of travel. Sideways it stays within {@code radius} of the line of travel:
 * a chunk further to the side is only reached if the player turns, and computing it ahead would usually be wasted.
 */
public final class Band {
    private Band() {}

    /**
     * True for a chunk at offset {@code dx, dz} from the player's chunk, with {@code vx, vz} the unit direction of travel.
     */
    public static boolean wanted(int dx, int dz, double vx, double vz, int radius, int extra) {
        int d = Math.max(Math.abs(dx), Math.abs(dz));
        if (d < radius - 1 || d > radius + extra) return false;
        double forward = dx * vx + dz * vz;
        double side = Math.abs(dz * vx - dx * vz);
        return forward >= 0 && side <= radius;
    }
}
