package com.panda.structopt.client;

/**
 * Dev runs only: the Architectury dev launcher leaves a non-daemon thread pool behind, so after Minecraft shuts down the
 * JVM never exits (it holds the dev jars, and the next build can't replace them). Once the client is stopping, a daemon
 * thread halts the JVM after the world has had time to save. Released jars never arm it.
 */
public final class DevExit {
    /** Set by Architectury's dev launcher, absent in a real game. */
    private static final boolean DEV = System.getProperty("architectury.main.class") != null;

    private static boolean armed;

    private DevExit() {}

    /**
     * Call on the client thread when the client starts shutting down. Once that thread has finished (the world is
     * saved and {@code main} has returned), the JVM is halted a second later: well before vanilla's shutdown watchdog
     * (15 s after {@code main} returns) would write a crash report about the leftover thread pool.
     */
    public static synchronized void onClientStopping() {
        if (!DEV || armed) return;
        armed = true;
        Thread client = Thread.currentThread();
        Thread halt = new Thread(() -> {
            try {
                client.join(20_000);
                Thread.sleep(1_000);
            } catch (InterruptedException ignored) {
            }
            Runtime.getRuntime().halt(0);
        }, "pandas_structure_optimizer dev exit");
        halt.setDaemon(true);
        halt.start();
    }
}
