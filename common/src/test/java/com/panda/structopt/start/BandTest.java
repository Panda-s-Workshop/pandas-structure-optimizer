package com.panda.structopt.start;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class BandTest {
    private static final int RADIUS = 20, EXTRA = 8;

    /** Brute force: does the needed square (half-size RADIUS + slack), moving along (vx, vz), cover (dx, dz) at some t >= 0? */
    private static boolean sweep(int dx, int dz, double vx, double vz, double slack) {
        for (double t = 0; t <= 2 * (RADIUS + EXTRA) + 2; t += 0.01) {
            if (Math.max(Math.abs(dx - vx * t), Math.abs(dz - vz * t)) <= RADIUS + slack) return true;
        }
        return false;
    }

    @Test
    void takesTheChunksJustAheadOfTheNeededOnes() {
        assertTrue(Band.wanted(RADIUS, 0, 1, 0, RADIUS, EXTRA));
        assertTrue(Band.wanted(RADIUS + EXTRA, 0, 1, 0, RADIUS, EXTRA));
        assertTrue(Band.wanted(RADIUS + 3, RADIUS - 2, 1, 0, RADIUS, EXTRA));
    }

    @Test
    void skipsChunksAlreadyNeededOrTooFar() {
        assertFalse(Band.wanted(RADIUS - 5, 0, 1, 0, RADIUS, EXTRA));
        assertFalse(Band.wanted(RADIUS + EXTRA + 1, 0, 1, 0, RADIUS, EXTRA));
    }

    @Test
    void skipsChunksBehind() {
        assertFalse(Band.wanted(-(RADIUS + 2), 0, 1, 0, RADIUS, EXTRA));
    }

    /** The cause of the low use rate seen on the bench: chunks far to the side, which a straight flight never reaches. */
    @Test
    void skipsChunksFarToTheSide() {
        assertFalse(Band.wanted(0, RADIUS + 4, 1, 0, RADIUS, EXTRA));
        assertFalse(Band.wanted(5, RADIUS + 6, 1, 0, RADIUS, EXTRA));
        assertFalse(Band.wanted(-3, -(RADIUS + 2), 1, 0, RADIUS, EXTRA));
    }

    @Test
    void followsADiagonalDirection() {
        double h = Math.sqrt(0.5);
        assertTrue(Band.wanted(RADIUS + 2, RADIUS + 2, h, h, RADIUS, EXTRA));
        assertFalse(Band.wanted(RADIUS + 4, -(RADIUS + 4), h, h, RADIUS, EXTRA));
    }

    /**
     * On a diagonal the needed square reaches further to the side: (24, -16) is 28.3 chunks off the line of travel, yet it is
     * inside the square once the player is at chunk (4, 4).
     */
    @Test
    void keepsChunksTheSquareReachesOnADiagonal() {
        double h = Math.sqrt(0.5);
        assertTrue(Band.wanted(24, -16, h, h, RADIUS, EXTRA));
        assertTrue(Math.max(Math.abs(24 - 4), Math.abs(-16 - 4)) <= RADIUS);
    }

    /** At 7.5 degrees the square's corner sweeps over (-12, 21), a chunk behind the player, about 8 chunks into the flight. */
    @Test
    void keepsChunksTheCornerSweepsOverBehind() {
        double vx = Math.cos(Math.toRadians(7.5)), vz = Math.sin(Math.toRadians(7.5));
        assertTrue(-12 * vx + 21 * vz < 0);
        assertTrue(Band.wanted(-12, 21, vx, vz, RADIUS, EXTRA));
    }

    /**
     * In every direction, a chunk of the ring is in the band exactly when the moving square covers it at some point: nothing the
     * flight will need is missed, nothing it won't is computed.
     */
    @Test
    void theBandIsExactlyWhatAStraightFlightReaches() {
        for (double angle = 0; angle < 360; angle += 7.5) {
            double vx = Math.cos(Math.toRadians(angle)), vz = Math.sin(Math.toRadians(angle));
            for (int dx = -RADIUS - EXTRA; dx <= RADIUS + EXTRA; dx++) {
                for (int dz = -RADIUS - EXTRA; dz <= RADIUS + EXTRA; dz++) {
                    int d = Math.max(Math.abs(dx), Math.abs(dz));
                    if (d < RADIUS - 1 || d > RADIUS + EXTRA) continue;
                    boolean inside = sweep(dx, dz, vx, vz, -0.02), touching = sweep(dx, dz, vx, vz, 0.02);
                    if (inside != touching) continue; // the square only grazes it: closer than the sampling step can tell
                    assertEquals(inside, Band.wanted(dx, dz, vx, vz, RADIUS, EXTRA), angle + " deg: " + dx + "," + dz);
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
                if (Band.wanted(dx, dz, 1, 0, RADIUS, EXTRA) && dx >= 0) now++;
            }
        }
        assertEquals(950, old);
        assertTrue(now <= 486, now + " of " + old);
    }
}
