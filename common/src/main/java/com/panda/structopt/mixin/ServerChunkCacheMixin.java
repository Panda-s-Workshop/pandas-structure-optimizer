package com.panda.structopt.mixin;

import com.panda.structopt.spawn.SpawnProbes;
import com.panda.structopt.stats.Stats;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 3.8: during the custom spawners, {@code hasChunk} answers "is this a full, loaded chunk" instead of "is it scheduled at full
 * ticket level", so a spawner never waits on a chunk that is still generating.
 */
@Mixin(ServerChunkCache.class)
abstract class ServerChunkCacheMixin {
    @Shadow
    public abstract LevelChunk getChunkNow(int x, int z);

    @Inject(method = "hasChunk", at = @At("HEAD"), cancellable = true)
    private void structopt$fullOnly(int x, int z, CallbackInfoReturnable<Boolean> cir) {
        if (!SpawnProbes.active()) return;
        boolean full = getChunkNow(x, z) != null;
        if (!full) Stats.SPAWN_SKIPS.increment();
        cir.setReturnValue(full);
    }
}
