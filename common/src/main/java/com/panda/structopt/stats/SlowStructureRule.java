package com.panda.structopt.stats;

/**
 * 3.10's rule: a structure is worth a warning when it has had at least 20 attempts, places in under 5% of them, and costs more
 * than 10 s of CPU per hour of play. The hour rate uses at least a 10-minute window, so the first busy minute of a session
 * doesn't make every structure look slow.
 */
public final class SlowStructureRule {
    public static final int MIN_ATTEMPTS = 20;
    public static final double MAX_SUCCESS = 0.05;
    public static final double MIN_SECONDS_PER_HOUR = 10;
    public static final double MIN_WINDOW_HOURS = 1.0 / 6;

    private SlowStructureRule() {}

    public static boolean isSlow(long attempts, long successes, long cpuNanos, double sessionHours) {
        if (attempts < MIN_ATTEMPTS) return false;
        if ((double) successes / attempts >= MAX_SUCCESS) return false;
        double perHour = cpuNanos / 1e9 / Math.max(sessionHours, MIN_WINDOW_HOURS);
        return perHour > MIN_SECONDS_PER_HOUR;
    }
}
