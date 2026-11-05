package com.panda.structopt.template;

import com.panda.structopt.mixin.JigsawStructureAccessor;
import com.panda.structopt.mixin.ListPoolElementAccessor;
import com.panda.structopt.mixin.SinglePoolElementAccessor;
import com.panda.structopt.mixin.StructureTemplatePoolAccessor;
import com.mojang.datafixers.util.Pair;
import java.util.Collection;
import net.minecraft.core.Holder;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.pools.ListPoolElement;
import net.minecraft.world.level.levelgen.structure.pools.SinglePoolElement;
import net.minecraft.world.level.levelgen.structure.pools.StructurePoolElement;
import net.minecraft.world.level.levelgen.structure.pools.StructureTemplatePool;
import net.minecraft.world.level.levelgen.structure.structures.JigsawStructure;

/** Walks template pools to the template ids their elements place. */
public final class Pools {
    private Pools() {}

    /** Adds the templates of a pool's elements to {@code out}, stopping at {@code cap} ids. */
    public static void templates(StructureTemplatePool pool, Collection<Identifier> out, int cap) {
        for (Pair<StructurePoolElement, Integer> entry : ((StructureTemplatePoolAccessor) pool).structopt$rawTemplates()) {
            if (out.size() >= cap) return;
            element(entry.getFirst(), out, cap);
        }
    }

    /** The start pool's templates of a jigsaw structure (nothing for other structure types). */
    public static void startTemplates(Holder<Structure> structure, Collection<Identifier> out, int cap) {
        if (structure.value() instanceof JigsawStructure jigsaw) {
            Holder<StructureTemplatePool> start = ((JigsawStructureAccessor) (Object) jigsaw).structopt$startPool();
            if (start.isBound()) templates(start.value(), out, cap);
        }
    }

    private static void element(StructurePoolElement element, Collection<Identifier> out, int cap) {
        if (out.size() >= cap) return;
        if (element instanceof SinglePoolElement single) {
            ((SinglePoolElementAccessor) single).structopt$template().left().ifPresent(out::add);
        } else if (element instanceof ListPoolElement list) {
            for (StructurePoolElement e : ((ListPoolElementAccessor) list).structopt$elements()) element(e, out, cap);
        }
    }
}
