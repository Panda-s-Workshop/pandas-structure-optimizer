package com.panda.structopt.util;

import java.lang.management.ManagementFactory;
import java.util.function.BooleanSupplier;
import java.util.function.DoubleSupplier;

/** Waits for spare CPU: background work goes on only while the whole machine is less busy than a limit. */
public final class SpareCpu {
    /** The machine's recent CPU load, 0 to 1, or a negative number when the JVM can't tell. */
    public static final DoubleSupplier SYSTEM = system();

    private SpareCpu() {}

    /**
     * Returns true as soon as {@code load} is below {@code max} (or unknown, negative); while it isn't, sleeps {@code sleepMillis}
     * and looks again. Returns false when {@code stop} becomes true or the thread is interrupted.
     */
    public static boolean await(double max, DoubleSupplier load, BooleanSupplier stop, long sleepMillis) {
        while (!stop.getAsBoolean()) {
            double l = load.getAsDouble();
            if (l < 0 || l < max) return true;
            try {
                Thread.sleep(sleepMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    private static DoubleSupplier system() {
        try {
            if (ManagementFactory.getOperatingSystemMXBean() instanceof com.sun.management.OperatingSystemMXBean os) return os::getCpuLoad;
        } catch (LinkageError e) {
            // no jdk.management module: callers run without the load check
        }
        return () -> -1;
    }
}
