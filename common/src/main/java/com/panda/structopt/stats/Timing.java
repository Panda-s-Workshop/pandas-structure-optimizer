package com.panda.structopt.stats;

/**
 * Per-thread time counters, so a structure attempt can tell how much of its time went to template loading and to terrain height
 * sampling: the attempt reads the counters before and after it runs.
 */
public final class Timing {
    public static final int TEMPLATE = 0;
    public static final int HEIGHT = 1;
    private static final ThreadLocal<long[]> COUNTERS = ThreadLocal.withInitial(() -> new long[2]);

    private Timing() {}

    public static void add(int kind, long nanos) {
        COUNTERS.get()[kind] += nanos;
    }

    public static long get(int kind) {
        return COUNTERS.get()[kind];
    }
}
