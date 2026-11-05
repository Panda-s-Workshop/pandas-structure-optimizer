package com.panda.structopt.mixin;

import net.minecraft.core.Holder;
import net.minecraft.world.level.levelgen.structure.pools.StructureTemplatePool;
import net.minecraft.world.level.levelgen.structure.structures.JigsawStructure;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The start pool (template prefetch, 3.6) and the reach (coordinator footprints, 3.13) of a jigsaw structure. */
@Mixin(JigsawStructure.class)
public interface JigsawStructureAccessor {
    @Accessor("startPool")
    Holder<StructureTemplatePool> structopt$startPool();

    @Accessor("maxDepth")
    int structopt$maxDepth();

    @Accessor("maxDistanceFromCenter")
    JigsawStructure.MaxDistance structopt$maxDistance();
}
