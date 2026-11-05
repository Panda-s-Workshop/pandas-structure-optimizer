package com.panda.structopt.spawn;

/**
 * 3.8: while the level's custom spawners run (cat, patrol, phantom, wandering trader, village siege and any mod's), "is this chunk
 * loaded" means "is it a full, loaded chunk", so a spawner skips a chunk that is still generating instead of blocking the Server
 * thread on it. Only the thread running the spawners sees the change.
 */
public final class SpawnProbes {
    private static volatile Thread probing;

    private SpawnProbes() {}

    public static void begin() {
        probing = Thread.currentThread();
    }

    public static void end() {
        probing = null;
    }

    public static boolean active() {
        return probing == Thread.currentThread();
    }
}
