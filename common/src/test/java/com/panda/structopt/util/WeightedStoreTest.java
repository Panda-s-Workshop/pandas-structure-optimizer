package com.panda.structopt.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class WeightedStoreTest {
    @Test
    void handsEachValueOutOnce() {
        WeightedStore<String, String> store = new WeightedStore<>(100);
        assertTrue(store.put("a", "A", 10));
        assertEquals("A", store.take("a"));
        assertNull(store.take("a"));
        assertEquals(0, store.weight());
    }

    @Test
    void dropsTheOldestOverTheBudget() {
        WeightedStore<String, String> store = new WeightedStore<>(100);
        store.put("a", "A", 40);
        store.put("b", "B", 40);
        store.put("c", "C", 40);
        assertFalse(store.contains("a"));
        assertTrue(store.contains("b") && store.contains("c"));
        assertEquals(80, store.weight());
        assertEquals(1, store.dropped());
    }

    @Test
    void neverTakesAValueBiggerThanTheBudgetOrTwiceTheSameKey() {
        WeightedStore<String, String> store = new WeightedStore<>(100);
        assertFalse(store.put("big", "X", 101));
        assertTrue(store.put("a", "A", 10));
        assertFalse(store.put("a", "A2", 10));
        assertEquals("A", store.take("a"));
        assertEquals(0, store.dropped());
    }

    @Test
    void aZeroBudgetKeepsNothing() {
        WeightedStore<String, String> store = new WeightedStore<>(0);
        assertFalse(store.put("a", "A", 1));
        assertEquals(0, store.size());
    }
}
