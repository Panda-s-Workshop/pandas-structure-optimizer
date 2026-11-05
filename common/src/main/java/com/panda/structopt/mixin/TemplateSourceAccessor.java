package com.panda.structopt.mixin;

import net.minecraft.core.HolderGetter;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.level.levelgen.structure.templatesystem.loader.TemplateSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

/** The template cache builds templates with the source's own block lookup, and runs its own fixer path on a miss. */
@Mixin(TemplateSource.class)
public interface TemplateSourceAccessor {
    @Accessor("blockLookup")
    HolderGetter<Block> structopt$blockLookup();

    @Invoker("readStructure")
    StructureTemplate structopt$readStructure(CompoundTag tag);
}
