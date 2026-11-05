package com.panda.structopt.coordinator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.IntBinaryOperator;
import org.junit.jupiter.api.Test;

class CoordinatorOrderTest {
    private static final IntBinaryOperator NONE = (a, b) -> 0;

    private static List<CoordinatorRules.Candidate> field(long seed) {
        Random r = new Random(seed);
        List<CoordinatorRules.Candidate> out = new ArrayList<>();
        for (int i = 0; i < 400; i++) {
            int set = r.nextInt(12);
            out.add(new CoordinatorRules.Candidate("mod:set_" + set, r.nextInt(120) - 60, r.nextInt(120) - 60, set % 3, 10 + set * 4,
                    16 + r.nextInt(113), r.nextInt(10) > 0));
        }
        return out;
    }

    private static Set<String> winners(List<CoordinatorRules.Candidate> order, long seed, IntBinaryOperator min) {
        Set<String> out = new TreeSet<>();
        for (CoordinatorRules.Candidate c : order)
            if (c.live() && !CoordinatorRules.loses(c, order, seed, min)) out.add(c.set() + "@" + c.chunkX() + "," + c.chunkZ());
        return out;
    }

    @Test
    void sameWinnersInAnyEvaluationOrder() {
        for (long seed = 1; seed <= 20; seed++) {
            List<CoordinatorRules.Candidate> candidates = field(seed);
            Set<String> expected = winners(candidates, seed, NONE);
            for (int shuffle = 0; shuffle < 10; shuffle++) {
                List<CoordinatorRules.Candidate> order = new ArrayList<>(candidates);
                Collections.shuffle(order, new Random(seed * 100 + shuffle));
                assertEquals(expected, winners(order, seed, NONE), "seed " + seed + ", shuffle " + shuffle);
            }
        }
    }

    @Test
    void winnersOfDifferentSetsNeverOverlap() {
        IntBinaryOperator min = (a, b) -> a == 2 && b == 2 ? 600 : 0;
        for (long seed = 1; seed <= 20; seed++) {
            List<CoordinatorRules.Candidate> candidates = field(seed);
            List<CoordinatorRules.Candidate> won = new ArrayList<>();
            for (CoordinatorRules.Candidate c : candidates)
                if (c.live() && !CoordinatorRules.loses(c, candidates, seed, min)) won.add(c);
            for (CoordinatorRules.Candidate a : won)
                for (CoordinatorRules.Candidate b : won)
                    assertFalse(CoordinatorRules.conflict(a, b, min), a + " overlaps " + b);
        }
    }

    @Test
    void higherPriorityThenRarerSetWins() {
        var common = new CoordinatorRules.Candidate("a:common", 0, 0, 0, 10, 48, true);
        var rare = new CoordinatorRules.Candidate("b:rare", 2, 0, 0, 80, 48, true);
        var important = new CoordinatorRules.Candidate("c:important", 0, 3, 5, 5, 48, true);
        assertTrue(CoordinatorRules.loses(common, List.of(rare), 1, NONE));
        assertFalse(CoordinatorRules.loses(rare, List.of(common), 1, NONE));
        assertTrue(CoordinatorRules.loses(rare, List.of(important), 1, NONE));
        // far apart: no conflict
        var far = new CoordinatorRules.Candidate("b:rare", 40, 0, 0, 80, 48, true);
        assertFalse(CoordinatorRules.loses(common, List.of(far), 1, NONE));
        // a candidate that fails its biome check never blocks
        var dead = new CoordinatorRules.Candidate("b:rare", 2, 0, 0, 80, 48, false);
        assertFalse(CoordinatorRules.loses(common, List.of(dead), 1, NONE));
    }

    @Test
    void tieBreakDependsOnTheSeedNotTheOrder() {
        var a = new CoordinatorRules.Candidate("x:a", 0, 0, 0, 20, 48, true);
        var b = new CoordinatorRules.Candidate("x:b", 1, 0, 0, 20, 48, true);
        for (long seed = 0; seed < 50; seed++) {
            boolean aLoses = CoordinatorRules.loses(a, List.of(b), seed, NONE);
            boolean bLoses = CoordinatorRules.loses(b, List.of(a), seed, NONE);
            assertTrue(aLoses ^ bLoses, "exactly one loses, seed " + seed);
        }
    }
}
