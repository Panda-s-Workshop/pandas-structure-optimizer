package com.panda.structopt.height;

import com.panda.structopt.StructOpt;
import com.panda.structopt.config.StructOptConfig;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.chunk.ChunkGenerator;
import org.jspecify.annotations.Nullable;

/** Which generators get a height cache (the allow list of 3.7, by generator type id), and the switch. */
public final class Heights {
    private static volatile boolean enabled;

    private Heights() {}

    public static void configure(StructOptConfig config) {
        enabled = config.heightCacheEnabled && !StructOpt.off();
    }

    public static void disable() {
        enabled = false;
    }

    private static final ThreadLocal<Boolean> BYPASS = ThreadLocal.withInitial(() -> false);

    /** Runs {@code r} with the height cache skipped on this thread (the equivalence test's uncached answers). */
    public static void bypass(Runnable r) {
        BYPASS.set(true);
        try {
            r.run();
        } finally {
            BYPASS.set(false);
        }
    }

    public static boolean bypassing() {
        return BYPASS.get();
    }

    public static boolean allowed(ChunkGenerator generator) {
        if (!enabled) return false;
        Identifier type = BuiltInRegistries.CHUNK_GENERATOR.getKey(generator.codec());
        return type != null && StructOptConfig.get().heightCacheGenerators.contains(type.toString()) && VanillaTerrain.is(generator);
    }

    public static @Nullable HeightCache create(Object randomState, int minY, int height) {
        if (!enabled) return null;
        return new HeightCache(randomState, minY, height, StructOptConfig.get().heightCacheEntriesPerDimension);
    }
}
