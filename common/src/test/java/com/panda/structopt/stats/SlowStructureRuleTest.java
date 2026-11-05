package com.panda.structopt.stats;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class SlowStructureRuleTest {
    private static final long SECOND = 1_000_000_000L;

    @Test
    void slowRareAndExpensiveIsFlagged() {
        // 100 attempts, 2 placed (2%), 30 s of CPU in one hour
        assertTrue(SlowStructureRule.isSlow(100, 2, 30 * SECOND, 1.0));
    }

    @Test
    void tooFewAttemptsIsNotJudged() {
        assertFalse(SlowStructureRule.isSlow(19, 0, 1000 * SECOND, 1.0));
    }

    @Test
    void placingOftenIsFine() {
        assertFalse(SlowStructureRule.isSlow(100, 5, 1000 * SECOND, 1.0));
    }

    @Test
    void cheapIsFine() {
        assertFalse(SlowStructureRule.isSlow(100, 0, 9 * SECOND, 1.0));
    }

    @Test
    void theFirstMinutesUseATenMinuteWindow() {
        // 2 s in the first minute is 12 s/h over the 10-minute window: flagged; 1.5 s is 9 s/h: not
        assertTrue(SlowStructureRule.isSlow(50, 0, 2 * SECOND, 1.0 / 60));
        assertFalse(SlowStructureRule.isSlow(50, 0, 3 * SECOND / 2, 1.0 / 60));
    }
}
