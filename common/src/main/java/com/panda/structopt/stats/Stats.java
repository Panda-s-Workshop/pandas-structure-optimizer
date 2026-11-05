package com.panda.structopt.stats;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/** This session's counters, shown by {@code /structopt stats} and the periodic log line. Reset when a server starts. */
public final class Stats {
    public static final LongAdder TEMPLATE_HITS = new LongAdder();
    /** Templates upgraded because the game asked for them (not by the builder). */
    public static final LongAdder TEMPLATE_MISSES = new LongAdder();
    /** Templates the background builder upgraded into the cache. */
    public static final LongAdder BUILDER_UPGRADED = new LongAdder();
    public static final LongAdder TEMPLATE_AVOIDED_MICROS = new LongAdder();
    public static final LongAdder TEMPLATE_UPGRADE_MICROS = new LongAdder();
    public static final LongAdder TEMPLATE_LOADS = new LongAdder();
    public static final LongAdder TEMPLATE_SHARED_LOADS = new LongAdder();
    public static final LongAdder START_HITS = new LongAdder();
    public static final LongAdder START_MISSES = new LongAdder();
    public static final LongAdder START_SHARED = new LongAdder();
    public static final LongAdder START_UNSHAREABLE = new LongAdder();
    public static final LongAdder AHEAD_COMPUTED = new LongAdder();
    public static final LongAdder AHEAD_USED = new LongAdder();
    public static final LongAdder AHEAD_SCANNED = new LongAdder();
    /** Player scans started (one per moving player per cycle). */
    public static final LongAdder AHEAD_PLAYER_SCANS = new LongAdder();
    public static final LongAdder AHEAD_DROPPED = new LongAdder();
    public static final LongAdder PREFETCHED = new LongAdder();
    public static final LongAdder HEIGHT_HITS = new LongAdder();
    public static final LongAdder HEIGHT_MISSES = new LongAdder();
    public static final LongAdder SPAWN_SKIPS = new LongAdder();
    public static final LongAdder COORDINATOR_BLOCKED = new LongAdder();
    public static final AtomicLong BUILDER_DONE = new AtomicLong();
    public static final AtomicLong BUILDER_TOTAL = new AtomicLong();
    public static volatile String builderState = "idle";
    public static volatile long sessionStartNanos = System.nanoTime();

    private static final Map<String, StructureRecord> STRUCTURES = new ConcurrentHashMap<>();

    private Stats() {}

    /** One structure's attempts this session (real computations only; cache hits are not attempts). */
    public static final class StructureRecord {
        public final String id;
        public final LongAdder attempts = new LongAdder();
        public final LongAdder successes = new LongAdder();
        public final LongAdder nanos = new LongAdder();
        public final AtomicLong maxNanos = new AtomicLong();
        public final LongAdder templateNanos = new LongAdder();
        public final LongAdder heightNanos = new LongAdder();
        public volatile boolean warned;

        StructureRecord(String id) {
            this.id = id;
        }

        public double meanMillis() {
            long n = attempts.sum();
            return n == 0 ? 0 : nanos.sum() / 1e6 / n;
        }

        /** What took most of this structure's time: "templates" (template loading), "height" (height sampling) or "placement". */
        public String mainCost() {
            long total = nanos.sum(), t = templateNanos.sum(), h = heightNanos.sum();
            long rest = Math.max(0, total - t - h);
            if (t >= h && t >= rest) return "templates";
            if (h >= rest) return "height";
            return "placement";
        }
    }

    public static void attempt(String structureId, boolean success, long nanos, long templateNanos, long heightNanos) {
        StructureRecord r = STRUCTURES.computeIfAbsent(structureId, StructureRecord::new);
        r.attempts.increment();
        if (success) r.successes.increment();
        r.nanos.add(nanos);
        r.maxNanos.accumulateAndGet(nanos, Math::max);
        r.templateNanos.add(templateNanos);
        r.heightNanos.add(heightNanos);
    }

    public static List<StructureRecord> structures() {
        return new ArrayList<>(STRUCTURES.values());
    }

    /** The most expensive structures this session, by total time. */
    public static List<StructureRecord> top(int n) {
        List<StructureRecord> all = structures();
        all.sort(Comparator.comparingLong((StructureRecord r) -> r.nanos.sum()).reversed());
        return all.subList(0, Math.min(n, all.size()));
    }

    public static double sessionHours() {
        return (System.nanoTime() - sessionStartNanos) / 3.6e12;
    }

    public static void reset() {
        for (LongAdder a : List.of(TEMPLATE_HITS, TEMPLATE_MISSES, BUILDER_UPGRADED, TEMPLATE_AVOIDED_MICROS, TEMPLATE_UPGRADE_MICROS, TEMPLATE_LOADS,
                TEMPLATE_SHARED_LOADS, START_HITS, START_MISSES, START_SHARED, START_UNSHAREABLE, AHEAD_COMPUTED, AHEAD_USED, AHEAD_SCANNED, AHEAD_PLAYER_SCANS, AHEAD_DROPPED,
                PREFETCHED, HEIGHT_HITS, HEIGHT_MISSES, SPAWN_SKIPS, COORDINATOR_BLOCKED)) a.reset();
        BUILDER_DONE.set(0);
        BUILDER_TOTAL.set(0);
        builderState = "idle";
        STRUCTURES.clear();
        sessionStartNanos = System.nanoTime();
    }
}
