package com.panda.structopt.mixin;

import com.panda.structopt.template.TemplateCache;
import java.util.Optional;
import net.minecraft.resources.FileToIdConverter;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.level.levelgen.structure.templatesystem.loader.ResourceManagerTemplateSource;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 3.1: templates from mods and data packs load through the upgraded-template cache. The file is found exactly as vanilla finds
 * it (this source's own path converter and resource manager); when the cache can't help, vanilla's load runs unchanged. World
 * templates (structure blocks) and test templates come from other sources and never reach this.
 */
@Mixin(ResourceManagerTemplateSource.class)
abstract class ResourceManagerTemplateSourceMixin {
    @Shadow private ResourceManager resourceManager;
    @Shadow @Final private FileToIdConverter fileToIdConverter;

    @Inject(method = "load(Lnet/minecraft/resources/Identifier;)Ljava/util/Optional;", at = @At("HEAD"), cancellable = true)
    private void structopt$loadCached(Identifier id, CallbackInfoReturnable<Optional<StructureTemplate>> cir) {
        if (!TemplateCache.active()) return;
        try {
            Optional<StructureTemplate> cached = TemplateCache.load(id, fileToIdConverter.idToFile(id), resourceManager,
                    (ResourceManagerTemplateSource) (Object) this);
            if (cached != null) cir.setReturnValue(cached);
        } catch (RuntimeException e) {
            // a template that fails is never cached; vanilla's load reports it the usual way
        }
    }
}
