package com.panda.structopt.height;

import com.panda.structopt.StructOpt;
import com.panda.structopt.config.StructOptConfig;
import java.lang.reflect.RecordComponent;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.function.Predicate;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseRouter;
import net.minecraft.world.level.levelgen.densityfunction.DensityFunction;
import net.minecraft.world.level.levelgen.densityfunction.DfRewriteRule;
import org.jspecify.annotations.Nullable;

/**
 * Whether a generator's terrain is built only from Minecraft's own code: its generator and biome source classes and every density
 * function of its noise router. Those are pure: the same column gives the
 * same height on any thread, at any time. A noise generator whose router contains a mod's own density function types (a terrain
 * mod such as UltraTerraForged) may not be: on the bench, starts computed ahead on the mod's threads came out 1-2 blocks higher
 * or lower than the chunk worker's. Starts computed ahead and the height cache are only used where this is true. Data pack
 * terrain built from vanilla types (Terralith, for one) counts as vanilla. A terrain mod known to be pure can be trusted by the
 * setting {@code terrainTrustedPackages} (class name prefixes): its classes then count as Minecraft's own.
 */
public final class VanillaTerrain {
    private static final Map<ChunkGenerator, Boolean> KNOWN = Collections.synchronizedMap(new WeakHashMap<>());

    private VanillaTerrain() {}

    public static boolean is(ChunkGenerator generator) {
        Boolean known = KNOWN.get(generator);
        if (known != null) return known;
        String foreign = foreignDensityFunction(generator, StructOptConfig.get().terrainTrustedPackages);
        boolean vanilla = foreign == null;
        if (!vanilla) {
            StructOpt.LOGGER.info("Terrain of {} uses {}, which is not Minecraft's own: structure starts are not computed ahead there and the "
                    + "height cache is off, so the world stays exactly as without this mod (terrainTrustedPackages lists terrain mods known "
                    + "to give the same heights on every thread)", generator.getClass().getSimpleName(), foreign);
        }
        KNOWN.put(generator, vanilla);
        return vanilla;
    }

    /**
     * The class name of the first piece of terrain code outside Minecraft's own (the generator, its biome source, or a density
     * function of its router), or null when there is none.
     */
    public static @Nullable String foreignDensityFunction(ChunkGenerator generator, List<String> trusted) {
        Predicate<Object> minecraft = o -> {
            String name = o.getClass().getName();
            if (name.startsWith("net.minecraft.")) return true;
            for (String prefix : trusted) if (!prefix.isEmpty() && name.startsWith(prefix)) return true;
            return false;
        };
        // Another mod's generator, a subclass of Minecraft's included, or its biome source (starts check biomes) is not known to be pure
        if (!minecraft.test(generator)) return generator.getClass().getName();
        if (!minecraft.test(generator.getBiomeSource())) return generator.getBiomeSource().getClass().getName();
        if (!(generator instanceof NoiseBasedChunkGenerator noise)) return null; // flat, debug
        NoiseRouter router = noise.generatorSettings().value().noiseRouter();
        Set<DensityFunction> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        String[] found = new String[1];
        DfRewriteRule visit = new DfRewriteRule() {
            @Override
            public DensityFunction rewrite(DensityFunction f) {
                if (found[0] != null || !seen.add(f)) return f;
                if (!minecraft.test(f)) {
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
