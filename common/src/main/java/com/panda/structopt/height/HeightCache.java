package com.panda.structopt.height;

import com.panda.structopt.stats.Stats;
import com.panda.structopt.stats.Timing;
import java.util.function.IntSupplier;

/**
 * 3.7, a bounded per-dimension cache of terrain height answers ({@code getBaseHeight}), keyed by column and heightmap type. It is
 * direct-mapped: each column hashes to one slot, a newer answer replaces the older one, and a slot is one immutable object, so
 * readers never see half an entry. It belongs to one random state and height range; any other call bypasses it.
 */
public final class HeightCache {
    private record Slot(int x, int z, int type, int height) {}

    private final Object randomState;
    private final int minY;
    private final int levelHeight;
    private final Slot[] slots;
    private final int mask;

    public HeightCache(Object randomState, int minY, int levelHeight, int entries) {
        this.randomState = randomState;
        this.minY = minY;
        this.levelHeight = levelHeight;
        int size = Integer.highestOneBit(Math.max(1024, entries));
        this.slots = new Slot[size];
        this.mask = size - 1;
    }

    public boolean matches(Object randomState, int minY, int levelHeight) {
        return this.randomState == randomState && this.minY == minY && this.levelHeight == levelHeight;
    }

    public int get(int x, int z, int type, IntSupplier compute) {
        int i = index(x, z, type);
        Slot s = slots[i];
        if (s != null && s.x == x && s.z == z && s.type == type) {
            Stats.HEIGHT_HITS.increment();
            return s.height;
        }
        long t0 = System.nanoTime();
        int h = compute.getAsInt();
        Timing.add(Timing.HEIGHT, System.nanoTime() - t0);
        Stats.HEIGHT_MISSES.increment();
        slots[i] = new Slot(x, z, type, h);
        return h;
    }

    int index(int x, int z, int type) {
        long h = (x * 0x9E3779B97F4A7C15L) ^ (z * 0xC2B2AE3D27D4EB4FL) ^ (type * 0x165667B19E3779F9L);
        h ^= h >>> 29;
        h *= 0xBF58476D1CE4E5B9L;
        h ^= h >>> 32;
        return (int) h & mask;
    }

    public int capacity() {
        return slots.length;
    }
}
