package com.panda.structopt.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.datafixers.DataFixer;
import com.panda.structopt.template.Capture;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.levelgen.structure.templatesystem.loader.TemplateSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * 3.1: on a template-cache miss, takes the tag the data fixers return (vanilla's and every mod's pass wrapped around
 * {@code DataFixTypes}) and the time they took. Only loads the cache started itself are captured.
 */
@Mixin(TemplateSource.class)
abstract class TemplateSourceMixin {
    @WrapOperation(method = "readStructure(Lnet/minecraft/nbt/CompoundTag;)Lnet/minecraft/world/level/levelgen/structure/templatesystem/StructureTemplate;",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/util/datafix/DataFixTypes;updateToCurrentVersion(Lcom/mojang/datafixers/DataFixer;Lnet/minecraft/nbt/CompoundTag;I)Lnet/minecraft/nbt/CompoundTag;"))
    private CompoundTag structopt$captureFixed(DataFixTypes type, DataFixer fixer, CompoundTag tag, int version, Operation<CompoundTag> original) {
        Capture capture = Capture.current();
        if (capture == null) return original.call(type, fixer, tag, version);
        long t0 = System.nanoTime();
        CompoundTag fixed = original.call(type, fixer, tag, version);
        capture.micros = (System.nanoTime() - t0) / 1000;
        capture.fixed = fixed.copy();
        return fixed;
    }
}
