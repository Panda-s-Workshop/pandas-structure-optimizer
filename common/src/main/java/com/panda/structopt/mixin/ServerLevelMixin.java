package com.panda.structopt.mixin;

import com.panda.structopt.config.StructOptConfig;
import com.panda.structopt.spawn.SpawnProbes;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 3.8: marks the time the custom spawners of the level run, so their chunk checks skip unfinished chunks. */
@Mixin(ServerLevel.class)
abstract class ServerLevelMixin {
    @Inject(method = "tickCustomSpawners", at = @At("HEAD"))
    private void structopt$spawnersBegin(boolean spawnEnemies, CallbackInfo ci) {
        if (StructOptConfig.get().spawnProbesSkipUnfinishedChunks) SpawnProbes.begin();
    }

    @Inject(method = "tickCustomSpawners", at = @At("RETURN"))
    private void structopt$spawnersEnd(boolean spawnEnemies, CallbackInfo ci) {
        SpawnProbes.end();
    }
}
