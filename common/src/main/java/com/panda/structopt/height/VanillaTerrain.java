package com.panda.structopt.height;

import com.panda.structopt.StructOpt;
import java.lang.reflect.RecordComponent;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseRouter;
import net.minecraft.world.level.levelgen.densityfunction.DensityFunction;
import net.minecraft.world.level.levelgen.densityfunction.DfRewriteRule;
import org.jspecify.annotations.Nullable;

/**
 * Whether a generator's terrain is built only from Minecraft's own density functions. Those are pure: the same column gives the
 * same height on any thread, at any time. A noise generator whose router contains a mod's own density function types (a terrain
 * mod such as UltraTerraForged) may not be: on the bench, starts computed ahead on the mod's threads came out 1-2 blocks higher
 * or lower than the chunk worker's. Starts computed ahead and the height cache are only used where this is true. Data pack
 * terrain built from vanilla types (Terralith, for one) counts as vanilla.
 */
public final class VanillaTerrain {
    private static final Map<ChunkGenerator, Boolean> KNOWN = Collections.synchronizedMap(new WeakHashMap<>());

    private VanillaTerrain() {}

    public static boolean is(ChunkGenerator generator) {
        Boolean known = KNOWN.get(generator);
        if (known != null) return known;
        String foreign = foreignDensityFunction(generator);
        boolean vanilla = foreign == null;
        if (!vanilla) {
            StructOpt.LOGGER.info("Terrain of {} uses the density function {}: structure starts are not computed ahead there and the "
                    + "height cache is off, so the world stays exactly as without this mod", generator.getClass().getSimpleName(), foreign);
        }
        KNOWN.put(generator, vanilla);
        return vanilla;
    }

    /** The class name of the first density function outside Minecraft's own, or null when there is none. */
    static @Nullable String foreignDensityFunction(ChunkGenerator generator) {
        if (!(generator instanceof NoiseBasedChunkGenerator noise)) {
            // Minecraft's other generators (flat, debug) are pure; another mod's generator is not known to be
            String name = generator.getClass().getName();
            return name.startsWith("net.minecraft.") ? null : name;
        }
        NoiseRouter router = noise.generatorSettings().value().noiseRouter();
        Set<DensityFunction> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        String[] found = new String[1];
        DfRewriteRule visit = new DfRewriteRule() {
            @Override
            public DensityFunction rewrite(DensityFunction f) {
                if (found[0] != null || !seen.add(f)) return f;
                if (!f.getClass().getName().startsWith("net.minecraft.")) {
                    found[0] = f.getClass().getName();
                    return f;
                }
                f.rewriteChildren(this);
                return f;
            }
        };
        try {
            for (RecordComponent c : NoiseRouter.class.getRecordComponents()) {
                if (DensityFunction.class.isAssignableFrom(c.getType())) visit.rewrite((DensityFunction) c.getAccessor().invoke(router));
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            return "unreadable router (" + e + ")";
        }
        return found[0];
    }
}
