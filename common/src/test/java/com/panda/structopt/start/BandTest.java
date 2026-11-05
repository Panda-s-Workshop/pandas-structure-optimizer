package com.panda.structopt.start;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class BandTest {
    private static final int RADIUS = 20, EXTRA = 8;

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
        assertFalse(Band.wanted(-RADIUS, 0, 1, 0, RADIUS, EXTRA));
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

    @Test
    /** View distance 20, 8 extra: the old forward half-ring had 950 chunks, 464 of them beside the line of travel. */
    void theSideWedgesOfTheOldRingAreGone() {
        int old = 0, now = 0;
        for (int dx = -RADIUS - EXTRA; dx <= RADIUS + EXTRA; dx++) {
            for (int dz = -RADIUS - EXTRA; dz <= RADIUS + EXTRA; dz++) {
                int d = Math.max(Math.abs(dx), Math.abs(dz));
                if (d >= RADIUS - 1 && d <= RADIUS + EXTRA && dx >= 0) old++;
                if (Band.wanted(dx, dz, 1, 0, RADIUS, EXTRA)) now++;
            }
        }
        assertEquals(950, old);
        assertEquals(486, now);
    }
}
