package com.panda.structopt.mixin;

import java.util.Map;
import java.util.Optional;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import net.minecraft.world.level.levelgen.structure.templatesystem.loader.ResourceManagerTemplateSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * The cache builder upgrades templates through the manager's own resource source (its fixer and block lookup); the template
 * prefetch skips templates already in its map.
 */
@Mixin(StructureTemplateManager.class)
public interface StructureTemplateManagerAccessor {
    @Accessor("resourceManagerSource")
    ResourceManagerTemplateSource structopt$resourceManagerSource();

    @Accessor("structureRepository")
    Map<Identifier, Optional<StructureTemplate>> structopt$structureRepository();
}
