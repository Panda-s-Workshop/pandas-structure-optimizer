package com.panda.structopt.mixin;

import com.mojang.datafixers.util.Either;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.levelgen.structure.pools.SinglePoolElement;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The template a pool element places, for the template prefetch (3.6) and the cache builder (3.2). */
@Mixin(SinglePoolElement.class)
public interface SinglePoolElementAccessor {
    @Accessor("template")
    Either<Identifier, StructureTemplate> structopt$template();
}
