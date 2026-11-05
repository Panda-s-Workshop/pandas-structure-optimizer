package com.panda.structopt.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class SingleFlightTest {
    @Test
    void sameKeyIsComputedOnceAndShared() throws Exception {
        SingleFlight<String, Integer> flight = new SingleFlight<>();
        AtomicInteger runs = new AtomicInteger();
        CountDownLatch inside = new CountDownLatch(1), release = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            Future<Integer> first = pool.submit(() -> flight.run("t", () -> {
                runs.incrementAndGet();
                inside.countDown();
                await(release);
                return 42;
            }));
            assertTrue(inside.await(5, TimeUnit.SECONDS));
            List<Future<Integer>> others = new ArrayList<>();
            for (int i = 0; i < 7; i++) others.add(pool.submit(() -> flight.run("t", () -> {
                runs.incrementAndGet();
                return -1;
            })));
            Thread.sleep(100);
            release.countDown();
            assertEquals(42, first.get(5, TimeUnit.SECONDS));
            for (Future<Integer> f : others) assertEquals(42, f.get(5, TimeUnit.SECONDS));
            assertEquals(1, runs.get());
            assertFalse(flight.isInFlight("t"));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void differentKeysDoNotWaitForEachOther() throws Exception {
        SingleFlight<String, String> flight = new SingleFlight<>();
        CountDownLatch inside = new CountDownLatch(1), release = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<String> slow = pool.submit(() -> flight.run("slow", () -> {
                inside.countDown();
                await(release);
                return "slow";
            }));
            assertTrue(inside.await(5, TimeUnit.SECONDS));
            // "slow" is still running; another key finishes at once
            assertEquals("fast", pool.submit(() -> flight.run("fast", () -> "fast")).get(2, TimeUnit.SECONDS));
            assertTrue(flight.isInFlight("slow"));
            release.countDown();
            assertEquals("slow", slow.get(5, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void failureReachesTheWaitersAndIsNotKept() {
        SingleFlight<String, String> flight = new SingleFlight<>();
        assertThrows(IllegalStateException.class, () -> flight.run("x", () -> {
            throw new IllegalStateException("bad template");
        }));
        assertEquals("ok", flight.run("x", () -> "ok"));
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
