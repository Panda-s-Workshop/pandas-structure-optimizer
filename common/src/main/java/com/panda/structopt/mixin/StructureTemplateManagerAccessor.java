package com.panda.structopt.mixin;

import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import net.minecraft.world.level.levelgen.structure.templatesystem.loader.ResourceManagerTemplateSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The cache builder upgrades templates through the manager's own resource source (its fixer and block lookup). */
@Mixin(StructureTemplateManager.class)
public interface StructureTemplateManagerAccessor {
    @Accessor("resourceManagerSource")
    ResourceManagerTemplateSource structopt$resourceManagerSource();
}
