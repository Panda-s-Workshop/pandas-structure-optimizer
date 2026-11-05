package com.panda.structopt.start;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RoundRobinTest {
    @Test
    void fiftyPlayersTenTicksFivePerTickEachOncePerCycle() {
        RoundRobin<Integer> rr = new RoundRobin<>();
        List<Integer> players = new ArrayList<>();
        for (int i = 0; i < 50; i++) players.add(i);
        rr.sync(players);
        Map<Integer, Integer> seen = new HashMap<>();
        for (long tick = 100; tick < 110; tick++) {
            List<Integer> batch = rr.batch(tick, 10);
            assertEquals(5, batch.size(), "tick " + tick);
            for (int p : batch) seen.merge(p, 1, Integer::sum);
        }
        assertEquals(50, seen.size());
        assertTrue(seen.values().stream().allMatch(n -> n == 1));
    }

    @Test
    void unevenCountsSpreadWithinOne() {
        RoundRobin<Integer> rr = new RoundRobin<>();
        List<Integer> players = new ArrayList<>();
        for (int i = 0; i < 23; i++) players.add(i);
        rr.sync(players);
        for (long tick = 0; tick < 10; tick++) {
            int n = rr.batch(tick, 10).size();
            assertTrue(n == 2 || n == 3, "tick " + tick + ": " + n);
        }
    }

    @Test
    void orderIsStableAndNewcomersAreAppended() {
        RoundRobin<String> rr = new RoundRobin<>();
        rr.sync(List.of("a", "b", "c"));
        assertEquals(List.of("b"), rr.batch(1, 10));
        rr.sync(List.of("c", "a", "b", "d"));
        assertEquals(List.of("b"), rr.batch(1, 10));
        assertEquals(List.of("d"), rr.batch(3, 10));
        rr.sync(List.of("a", "c", "d"));
        assertEquals(3, rr.size());
        assertEquals(List.of("c"), rr.batch(1, 10));
    }
}
