package com.panda.structopt.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.panda.structopt.coordinator.Coordinator;
import com.panda.structopt.start.StartCache;
import java.util.function.Predicate;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 3.4: the start computed inside {@code tryGenerateStructure} goes through the shared start results (and is timed for the stats).
 * 3.13: with the coordinator on for this world, a structure that loses its spot never runs its generation.
 */
@Mixin(ChunkGenerator.class)
abstract class ChunkGeneratorMixin {
    @Inject(method = "tryGenerateStructure", at = @At("HEAD"), cancellable = true)
    private void structopt$coordinate(StructureSet.StructureSelectionEntry selected, StructureManager structureManager,
                                      RegistryAccess registryAccess, RandomState randomState, StructureTemplateManager templates,
                                      long seed, ChunkAccess centerChunk, ChunkPos pos, ResourceKey<Level> level,
                                      Climate.Sampler sampler, CallbackInfoReturnable<Boolean> cir) {
        if (Coordinator.blocks(level, selected.structure(), pos)) cir.setReturnValue(false);
    }

    @WrapOperation(method = "tryGenerateStructure", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/levelgen/structure/Structure;generate(Lnet/minecraft/core/Holder;Lnet/minecraft/resources/ResourceKey;Lnet/minecraft/core/RegistryAccess;Lnet/minecraft/world/level/chunk/ChunkGenerator;Lnet/minecraft/world/level/biome/BiomeSource;Lnet/minecraft/world/level/biome/Climate$Sampler;Lnet/minecraft/world/level/levelgen/RandomState;Lnet/minecraft/world/level/levelgen/structure/templatesystem/StructureTemplateManager;JLnet/minecraft/world/level/ChunkPos;ILnet/minecraft/world/level/LevelHeightAccessor;Ljava/util/function/Predicate;)Lnet/minecraft/world/level/levelgen/structure/StructureStart;"))
    private StructureStart structopt$sharedStart(Structure structure, Holder<Structure> holder, ResourceKey<Level> level,
                                                 RegistryAccess registries, ChunkGenerator generator, BiomeSource biomeSource,
                                                 Climate.Sampler sampler, RandomState randomState, StructureTemplateManager templates,
                                                 long seed, ChunkPos pos, int references, LevelHeightAccessor height,
                                                 Predicate<Holder<Biome>> validBiome, Operation<StructureStart> original) {
        return StartCache.generate(holder, level, registries, generator, randomState, templates, seed, pos, references, height,
                () -> original.call(structure, holder, level, registries, generator, biomeSource, sampler, randomState, templates, seed,
                        pos, references, height, validBiome));
    }
}
