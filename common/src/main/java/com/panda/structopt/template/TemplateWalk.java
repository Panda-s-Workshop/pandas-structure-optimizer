package com.panda.structopt.template;

import com.panda.structopt.mixin.JigsawStructureAccessor;
import com.panda.structopt.mixin.StructureTemplateManagerAccessor;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.BooleanSupplier;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.FileToIdConverter;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.pools.StructureTemplatePool;
import net.minecraft.world.level.levelgen.structure.structures.JigsawStructure;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.level.levelgen.structure.templatesystem.loader.ResourceManagerTemplateSource;

/**
 * 3.6 where starts aren't computed ahead: walks a jigsaw structure's pools from its start pool, level by level down to its depth,
 * and loads every template it meets into the template cache's prefetch store. The next level's pools come from the jigsaw blocks
 * of the templates just loaded. Templates already in the game's memory are not loaded again. Nothing here touches terrain, so it
 * is safe on any world.
 */
public final class TemplateWalk {
    private static final FileToIdConverter FILES = new FileToIdConverter("structure", ".nbt");

    private record Step(Holder<StructureTemplatePool> pool, int level) {}

    private final TemplateCache cache;
    private final Map<Identifier, Optional<StructureTemplate>> loaded;
    private final ResourceManagerTemplateSource source;
    private final ResourceManager resources;
    private final HolderGetter<StructureTemplatePool> pools;
    private final Set<Identifier> seenPools = new HashSet<>();
    private final Set<Identifier> seenTemplates = new HashSet<>();
    private final List<Identifier> loads = new ArrayList<>();
    private final BooleanSupplier stop;
    private int budget;

    public TemplateWalk(MinecraftServer server, TemplateCache cache, int budget, BooleanSupplier stop) {
        this.cache = cache;
        this.stop = stop;
        StructureTemplateManagerAccessor manager = (StructureTemplateManagerAccessor) server.getStructureTemplateManager();
        this.loaded = manager.structopt$structureRepository();
        this.source = manager.structopt$resourceManagerSource();
        this.resources = server.getResourceManager();
        this.pools = server.registryAccess().lookupOrThrow(Registries.TEMPLATE_POOL);
        this.budget = budget;
    }

    /** Walks one structure: its whole pool tree when {@code deep}, else its start pool only. */
    public void structure(Holder<Structure> structure, boolean deep) {
        if (!(structure.value() instanceof JigsawStructure jigsaw)) return;
        JigsawStructureAccessor access = (JigsawStructureAccessor) (Object) jigsaw;
        int depth = deep ? access.structopt$maxDepth() : 0;
        ArrayDeque<Step> queue = new ArrayDeque<>();
        enqueue(queue, access.structopt$startPool(), 0);
        while (!queue.isEmpty() && budget > 0) {
            Step step = queue.poll();
            StructureTemplatePool pool = step.pool().value();
            enqueue(queue, pool.getFallback(), step.level());
            List<Identifier> ids = new ArrayList<>();
            Pools.templates(pool, ids, Integer.MAX_VALUE);
            for (Identifier id : ids) {
                if (budget <= 0 || stop.getAsBoolean()) return;
                if (!seenTemplates.add(id)) continue;
                List<Identifier> next = load(id);
                if (step.level() >= depth) continue;
                for (Identifier p : next) pools.get(ResourceKey.create(Registries.TEMPLATE_POOL, p)).ifPresent(h -> enqueue(queue, h, step.level() + 1));
            }
        }
    }

    /** The templates this walk loaded ahead, in order. */
    public List<Identifier> loads() {
        return loads;
    }

    private void enqueue(ArrayDeque<Step> queue, Holder<StructureTemplatePool> pool, int level) {
        if (!pool.isBound()) return;
        Identifier id = pool.unwrapKey().map(ResourceKey::identifier).orElse(null);
        if (id != null && !seenPools.add(id)) return;
        queue.add(new Step(pool, level));
    }

    private List<Identifier> load(Identifier id) {
        // Already in the game's memory: not loaded again, but the walk goes on through its jigsaw blocks
        Optional<StructureTemplate> held = loaded.get(id);
        if (held != null) return held.map(t -> cache.knownJigsawPools(id, t)).orElse(List.of());
        budget--;
        loads.add(id);
        return cache.preload(id, FILES.idToFile(id), resources, source);
    }
}
