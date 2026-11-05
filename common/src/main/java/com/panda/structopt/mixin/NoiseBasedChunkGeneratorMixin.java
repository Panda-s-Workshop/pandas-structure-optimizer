package com.panda.structopt.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.panda.structopt.height.HeightCache;
import com.panda.structopt.height.Heights;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.RandomState;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/** 3.7: terrain height answers of the noise generator come from the height cache when its type is on the allow list. */
@Mixin(NoiseBasedChunkGenerator.class)
abstract class NoiseBasedChunkGeneratorMixin {
    @Unique
    private volatile @Nullable HeightCache structopt$heights;
    @Unique
    private volatile boolean structopt$notAllowed;

    @WrapMethod(method = "getBaseHeight")
    private int structopt$cachedHeight(int x, int z, Heightmap.Types type, LevelHeightAccessor heightAccessor, RandomState randomState,
                                       Operation<Integer> original) {
        if (Heights.bypassing()) return original.call(x, z, type, heightAccessor, randomState);
        HeightCache cache = structopt$heights;
        int minY = heightAccessor.getMinY(), height = heightAccessor.getHeight();
        if (cache == null) {
            if (structopt$notAllowed) return original.call(x, z, type, heightAccessor, randomState);
            if (!Heights.allowed((NoiseBasedChunkGenerator) (Object) this)) {
                structopt$notAllowed = true;
                return original.call(x, z, type, heightAccessor, randomState);
            }
            cache = Heights.create(randomState, minY, height);
            if (cache == null) return original.call(x, z, type, heightAccessor, randomState);
            structopt$heights = cache;
        }
        if (!cache.matches(randomState, minY, height)) return original.call(x, z, type, heightAccessor, randomState);
        return cache.get(x, z, type.ordinal(), () -> original.call(x, z, type, heightAccessor, randomState));
    }
}
