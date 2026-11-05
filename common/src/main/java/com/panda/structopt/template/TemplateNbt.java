package com.panda.structopt.template;

import java.util.BitSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.resources.Identifier;

/** What the template prefetch reads from a template's (upgraded) NBT without building it. */
public final class TemplateNbt {
    private static final String JIGSAW = "minecraft:jigsaw";
    private static final String EMPTY_POOL = "minecraft:empty";

    private TemplateNbt() {}

    /** The template pools the template's jigsaw blocks open, in block order, without repeats ({@code minecraft:empty} left out). */
    public static List<Identifier> jigsawPools(CompoundTag tag) {
        ListTag palette = tag.contains("palettes") ? tag.getListOrEmpty("palettes").getListOrEmpty(0) : tag.getListOrEmpty("palette");
        BitSet jigsaws = new BitSet();
        for (int i = 0; i < palette.size(); i++) {
            CompoundTag state = palette.getCompoundOrEmpty(i);
            // 26.3 writes a palette state as {id, properties}; older data versions as {Name, Properties}
            if (JIGSAW.equals(state.getStringOr("id", state.getStringOr("Name", "")))) jigsaws.set(i);
        }
        if (jigsaws.isEmpty()) return List.of();
        Set<Identifier> out = new LinkedHashSet<>();
        ListTag blocks = tag.getListOrEmpty("blocks");
        for (int i = 0; i < blocks.size(); i++) {
            CompoundTag block = blocks.getCompoundOrEmpty(i);
            int state = block.getIntOr("state", -1);
            if (state < 0 || !jigsaws.get(state)) continue;
            String pool = block.getCompoundOrEmpty("nbt").getStringOr("pool", "");
            if (pool.isEmpty() || pool.equals(EMPTY_POOL)) continue;
            Identifier id = Identifier.tryParse(pool);
            if (id != null) out.add(id);
        }
        return List.copyOf(out);
    }

    /**
     * A rough size in memory of the built template, in bytes: each block of each palette is a block info and a position, a block
     * entity's tag and an entity cost more.
     */
    public static long weight(CompoundTag tag) {
        int palettes = tag.contains("palettes") ? Math.max(1, tag.getListOrEmpty("palettes").size()) : 1;
        ListTag blocks = tag.getListOrEmpty("blocks");
        long withNbt = 0;
        for (int i = 0; i < blocks.size(); i++) if (blocks.getCompoundOrEmpty(i).contains("nbt")) withNbt++;
        return 1024 + (long) blocks.size() * palettes * 72 + withNbt * palettes * 768 + tag.getListOrEmpty("entities").size() * 1024L;
    }
}
