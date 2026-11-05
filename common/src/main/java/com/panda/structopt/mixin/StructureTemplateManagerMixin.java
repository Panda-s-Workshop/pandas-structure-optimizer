package com.panda.structopt.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.panda.structopt.template.TemplateLoads;
import java.util.Map;
import java.util.Optional;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

/**
 * 3.3: separate template loads. Vanilla loads inside {@code ConcurrentHashMap.computeIfAbsent}, which holds the hash bin while a
 * template upgrades. The load now runs first, outside the map, with one future per template id (two threads wanting the same
 * template share one load); then vanilla's {@code get} finds the value in whatever map is in place. The map itself is never
 * replaced, so other mods that swap it keep working.
 */
@Mixin(StructureTemplateManager.class)
abstract class StructureTemplateManagerMixin {
    @Shadow @Final private Map<Identifier, Optional<StructureTemplate>> structureRepository;

    @Shadow
    private Optional<StructureTemplate> tryLoad(Identifier id) {
        throw new AssertionError();
    }

    @Unique
    private final TemplateLoads structopt$loads = new TemplateLoads();

    @WrapMethod(method = "get")
    private Optional<StructureTemplate> structopt$get(Identifier id, Operation<Optional<StructureTemplate>> original) {
        return structopt$loads.get(structureRepository, id, this::tryLoad, original::call);
    }
}
