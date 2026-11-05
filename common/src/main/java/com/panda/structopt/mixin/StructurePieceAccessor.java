package com.panda.structopt.mixin;

import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * A start copied from its saved NBT gets each piece's saved box back: a jigsaw piece recomputes its box from the template when
 * loaded, which loses the height a terrain-matching piece was stretched to while the structure was assembled.
 */
@Mixin(StructurePiece.class)
public interface StructurePieceAccessor {
    @Accessor("boundingBox")
    void structopt$setBoundingBox(BoundingBox box);
}
