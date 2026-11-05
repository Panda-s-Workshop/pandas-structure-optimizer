package com.panda.structopt.coordinator;

import java.util.Collection;
import java.util.function.IntBinaryOperator;

/**
 * 3.13's decision, as pure arithmetic so it can be tested on its own. A candidate is a structure set's possible start (from the
 * seed), with its footprint radius. Two candidates of different sets conflict when their centres are closer than the sum of their
 * radii (or the minimum distance between their priority classes, when that is larger). A candidate loses when any live candidate
 * of another set conflicts with it and ranks higher. The answer depends only on the candidates, never on what has generated, so
 * it is the same in any order.
 */
public final class CoordinatorRules {
    /** One possible start: chunk position, set id, priority, spacing (rarity), footprint radius in blocks, and its biome check. */
    public record Candidate(String set, int chunkX, int chunkZ, int priority, int spacing, int radius, boolean live) {}

    private CoordinatorRules() {}

    /** Positive when {@code a} outranks {@code b}: higher priority, then the rarer set (larger spacing), then a seed-mixed hash. */
    public static int rank(Candidate a, Candidate b, long seed) {
        int c = Integer.compare(a.priority(), b.priority());
        if (c != 0) return c;
        c = Integer.compare(a.spacing(), b.spacing());
        if (c != 0) return c;
        c = Long.compare(mix(seed, a.set()), mix(seed, b.set()));
        if (c != 0) return c;
        return a.set().compareTo(b.set());
    }

    /** The distance in blocks below which two candidates conflict. */
    public static int conflictDistance(Candidate a, Candidate b, IntBinaryOperator minDistance) {
        return Math.max(a.radius() + b.radius(), minDistance.applyAsInt(a.priority(), b.priority()));
    }

    public static boolean conflict(Candidate a, Candidate b, IntBinaryOperator minDistance) {
        if (a.set().equals(b.set())) return false;
        long dx = (a.chunkX() - (long) b.chunkX()) * 16, dz = (a.chunkZ() - (long) b.chunkZ()) * 16;
        long d = conflictDistance(a, b, minDistance);
        return dx * dx + dz * dz < d * d;
    }

    /** Whether {@code a} loses its spot to one of {@code others}. */
    public static boolean loses(Candidate a, Collection<Candidate> others, long seed, IntBinaryOperator minDistance) {
        for (Candidate b : others) {
            if (!b.live() || b.set().equals(a.set())) continue;
            if (conflict(a, b, minDistance) && rank(b, a, seed) > 0) return true;
        }
        return false;
    }

    static long mix(long seed, String id) {
        long h = seed ^ 0x9E3779B97F4A7C15L;
        for (int i = 0; i < id.length(); i++) {
            h ^= id.charAt(i);
            h *= 0x100000001B3L;
            h ^= h >>> 31;
        }
        h *= 0xBF58476D1CE4E5B9L;
        return h ^ (h >>> 29);
    }
}
