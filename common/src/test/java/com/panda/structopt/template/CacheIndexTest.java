package com.panda.structopt.template;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CacheIndexTest {
    private static String key(char c) {
        return String.valueOf(c).repeat(64);
    }

    @Test
    void parsesLinesSkipsTornOnesAndAppliesRemovals() {
        CacheIndex.Entry a = new CacheIndex.Entry(key('a'), "minecraft:a", 1200, 300);
        CacheIndex.Entry b = new CacheIndex.Entry(key('b'), "mod:b", 50, 100);
        CacheIndex index = CacheIndex.parse(List.of(a.line(), b.line(), "garbage", key('c') + "\tmod:c\t12", CacheIndex.removalLine(key('b'))));
        assertEquals(1, index.size());
        assertEquals(a, index.get(key('a')));
        assertNull(index.get(key('b')));
        assertNull(index.get(key('c')));
    }

    @Test
    void laterLineForTheSameKeyWins() {
        CacheIndex index = CacheIndex.parse(List.of(new CacheIndex.Entry(key('a'), "x:a", 1, 10).line(),
                new CacheIndex.Entry(key('a'), "x:a", 2, 20).line()));
        assertEquals(20, index.get(key('a')).bytes());
        assertEquals(20, index.totalBytes());
    }

    @Test
    void evictsLeastRecentlyUsedUntilNinetyPercentOfTheCap() {
        CacheIndex index = new CacheIndex();
        for (char c = 'a'; c <= 'j'; c++) index.put(new CacheIndex.Entry(key(c), "x:" + c, 0, 100));
        Map<String, Long> used = Map.of(key('a'), 5L, key('b'), 1L, key('c'), 9L);
        List<CacheIndex.Entry> out = index.evictions(800, e -> used.getOrDefault(e.key(), 100L));
        // 1000 bytes, cap 800: delete down to 720, so three entries go, oldest first
        assertEquals(3, out.size());
        assertEquals(key('b'), out.get(0).key());
        assertEquals(key('a'), out.get(1).key());
        assertEquals(key('c'), out.get(2).key());
        assertTrue(index.evictions(1000, e -> 0).isEmpty());
    }

    @Test
    void compactedLinesRoundTrip() {
        CacheIndex index = new CacheIndex();
        index.put(new CacheIndex.Entry(key('d'), "x:d", 7, 70));
        index.put(new CacheIndex.Entry(key('e'), "x:e", 8, 80));
        CacheIndex again = CacheIndex.parse(index.lines());
        assertNotNull(again.get(key('d')));
        assertEquals(150, again.totalBytes());
    }
}
