package com.panda.structopt.start;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class BandTest {
    private static final int RADIUS = 20, EXTRA = 8;
    private static final double MID = 0.5;

    /**
     * Brute force, the chunk system's way: the player moves from {@code (fx, fz)} along {@code (vx, vz)}; between two moments it
     * crosses a chunk border it stays in one chunk, and a chunk is needed while it is within RADIUS of that chunk. Every such stretch
     * is checked at its middle, so nothing is sampled and nothing is missed.
     */
    private static boolean flight(int dx, int dz, double fx, double fz, double vx, double vz) {
        double end = 100;
        List<Double> times = new ArrayList<>(List.of(0.0, end));
        crossings(fx, vx, end, times);
        crossings(fz, vz, end, times);
        times.sort(null);
        for (int i = 0; i + 1 < times.size(); i++) {
            double a = times.get(i), b = times.get(i + 1);
            if (b - a <= Band.TOUCH) continue;
            double t = (a + b) / 2;
            long cx = (long) Math.floor(fx + (Math.abs(vx) < 1e-9 ? 0 : vx) * t);
            long cz = (long) Math.floor(fz + (Math.abs(vz) < 1e-9 ? 0 : vz) * t);
            if (Math.max(Math.abs(dx - cx), Math.abs(dz - cz)) <= RADIUS) return true;
        }
        return false;
    }

    private static void crossings(double f, double v, double end, List<Double> times) {
        if (Math.abs(v) < 1e-9) return;
        for (long k = (long) Math.floor(Math.min(f, f + v * end)); k <= Math.ceil(Math.max(f, f + v * end)); k++) {
            double t = (k - f) / v;
            if (t > 0 && t < end) times.add(t);
        }
    }

    private static boolean wanted(int dx, int dz, double vx, double vz) {
        return Band.wanted(dx, dz, MID, MID, vx, vz, RADIUS, EXTRA);
    }

    @Test
    void takesTheChunksJustAheadOfTheNeededOnes() {
        assertTrue(wanted(RADIUS, 0, 1, 0));
        assertTrue(wanted(RADIUS + EXTRA, 0, 1, 0));
        assertTrue(wanted(RADIUS + 3, RADIUS - 2, 1, 0));
    }

    @Test
    void skipsChunksAlreadyNeededOrTooFar() {
        assertFalse(wanted(RADIUS - 5, 0, 1, 0));
        assertFalse(wanted(RADIUS + EXTRA + 1, 0, 1, 0));
    }

    @Test
    void skipsChunksBehind() {
        assertFalse(wanted(-(RADIUS + 2), 0, 1, 0));
    }

    /** The cause of the low use rate seen on the bench: chunks far to the side, which a straight flight never reaches. */
    @Test
    void skipsChunksFarToTheSide() {
        assertFalse(wanted(0, RADIUS + 4, 1, 0));
        assertFalse(wanted(5, RADIUS + 6, 1, 0));
        assertFalse(wanted(-3, -(RADIUS + 2), 1, 0));
    }

    @Test
    void followsADiagonalDirection() {
        double h = Math.sqrt(0.5);
        assertTrue(wanted(RADIUS + 2, RADIUS + 2, h, h));
        assertFalse(wanted(RADIUS + 4, -(RADIUS + 4), h, h));
    }

    /**
     * On a diagonal the needed square reaches further to the side: (24, -16) is 28.3 chunks off the line of travel, yet it is
     * inside the square once the player is at chunk (4, 4).
     */
    @Test
    void keepsChunksTheSquareReachesOnADiagonal() {
        double h = Math.sqrt(0.5);
        assertTrue(wanted(24, -16, h, h));
        assertTrue(Math.max(Math.abs(24 - 4), Math.abs(-16 - 4)) <= RADIUS);
    }

    /** At 7.5 degrees the square's corner sweeps over (-12, 21), a chunk behind the player, about 8 chunks into the flight. */
    @Test
    void keepsChunksTheCornerSweepsOverBehind() {
        double vx = Math.cos(Math.toRadians(7.5)), vz = Math.sin(Math.toRadians(7.5));
        assertTrue(-12 * vx + 21 * vz < 0);
        assertTrue(wanted(-12, 21, vx, vz));
    }

    /**
     * A player near the edge of its chunk crosses into the next one almost at once, and the chunk system then wants a new row: at
     * 7.5 degrees from (0.1, 0.95), the row z = 21 is needed after 0.4 chunks of travel, its far end (-20) only until the player
     * has gone 0.9 chunks. Measured from the middle of the chunk those chunks were never reached in time.
     */
    @Test
    void countsWhereInItsChunkThePlayerIs() {
        double vx = Math.cos(Math.toRadians(7.5)), vz = Math.sin(Math.toRadians(7.5));
        for (int dx = -20; dx <= -13; dx++) {
            assertTrue(Band.wanted(dx, 21, 0.1, 0.95, vx, vz, RADIUS, EXTRA), dx + ",21");
            assertTrue(flight(dx, 21, 0.1, 0.95, vx, vz));
        }
        assertFalse(Band.wanted(-20, 21, 0.5, 0.5, vx, vz, RADIUS, EXTRA));
    }

    /**
     * In every direction and from several places inside the chunk, a chunk of the ring is in the band exactly when a straight
     * flight puts the player within RADIUS of it at some point: nothing the flight will need is missed, nothing it won't is
     * computed.
     */
    @Test
    void theBandIsExactlyWhatAStraightFlightReaches() {
        double[][] starts = {{0.5, 0.5}, {0.1, 0.95}, {0.93, 0.07}, {0.37, 0.61}, {0, 0}};
        for (double[] f : starts) {
            for (double angle = 0; angle < 360; angle += 7.5) {
                double vx = Math.cos(Math.toRadians(angle)), vz = Math.sin(Math.toRadians(angle));
                for (int dx = -RADIUS - EXTRA; dx <= RADIUS + EXTRA; dx++) {
                    for (int dz = -RADIUS - EXTRA; dz <= RADIUS + EXTRA; dz++) {
                        int d = Math.max(Math.abs(dx), Math.abs(dz));
                        if (d < RADIUS - 1 || d > RADIUS + EXTRA) continue;
                        assertEquals(flight(dx, dz, f[0], f[1], vx, vz), Band.wanted(dx, dz, f[0], f[1], vx, vz, RADIUS, EXTRA),
                                f[0] + "," + f[1] + " at " + angle + " deg: " + dx + "," + dz);
                    }
                }
            }
        }
    }

    /** Along an axis the band is the front strip plus what is already needed around the player: far less than the old half-ring. */
    @Test
    void theSideWedgesOfTheOldRingAreGone() {
        int old = 0, now = 0;
        for (int dx = -RADIUS - EXTRA; dx <= RADIUS + EXTRA; dx++) {
            for (int dz = -RADIUS - EXTRA; dz <= RADIUS + EXTRA; dz++) {
                int d = Math.max(Math.abs(dx), Math.abs(dz));
                if (d >= RADIUS - 1 && d <= RADIUS + EXTRA && dx >= 0) old++;
                if (wanted(dx, dz, 1, 0) && dx >= 0) now++;
            }
        }
        assertEquals(950, old);
        assertTrue(now <= 486, now + " of " + old);
    }
}
