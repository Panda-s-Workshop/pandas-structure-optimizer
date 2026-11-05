package com.panda.structopt.template;

import com.panda.structopt.StructOpt;
import com.panda.structopt.mixin.StructureTemplateManagerAccessor;
import com.panda.structopt.stats.Stats;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.FileToIdConverter;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.world.level.levelgen.structure.pools.StructureTemplatePool;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import net.minecraft.world.level.levelgen.structure.templatesystem.loader.ResourceManagerTemplateSource;

/**
 * 3.2, the background cache builder. One low-priority thread walks every template the pack can place (the template pools'
 * elements first, then every other {@code structure/*.nbt} in the data packs) and upgrades each one not yet in the disk cache.
 * It writes only to disk; nothing stays in memory. It waits while structures are being generated, stops at shutdown, and the
 * next start carries on (entries already written are skipped).
 */
public final class CacheBuilder {
    private static volatile Thread thread;
    private static final AtomicBoolean STOP = new AtomicBoolean();
    private static final FileToIdConverter FILES = new FileToIdConverter("structure", ".nbt");

    private CacheBuilder() {}

    public static synchronized void start(MinecraftServer server) {
        stop();
        TemplateCache cache = TemplateCache.get();
        if (cache == null) return;
        STOP.set(false);
        Thread t = new Thread(() -> run(server, cache), "StructOpt template cache builder");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        thread = t;
        t.start();
    }

    public static synchronized void stop() {
        Thread t = thread;
        thread = null;
        if (t == null) return;
        STOP.set(true);
        t.interrupt();
        try {
            t.join(10_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public static boolean running() {
        Thread t = thread;
        return t != null && t.isAlive();
    }

    private static void run(MinecraftServer server, TemplateCache cache) {
        long t0 = System.nanoTime();
        try {
            StructureTemplateManager manager = server.getStructureTemplateManager();
            ResourceManagerTemplateSource source = ((StructureTemplateManagerAccessor) manager).structopt$resourceManagerSource();
            ResourceManager resources = server.getResourceManager();
            Set<Identifier> ids = new LinkedHashSet<>();
            server.registryAccess().lookupOrThrow(Registries.TEMPLATE_POOL).forEach(pool -> Pools.templates(pool, ids, Integer.MAX_VALUE));
            List<Identifier> all = new ArrayList<>(FILES.listMatchingResources(resources).keySet());
            for (Identifier file : all) ids.add(FILES.fileToId(file));
            Stats.BUILDER_TOTAL.set(ids.size());
            Stats.builderState = "running";
            StructOpt.LOGGER.info("Template cache builder: checking {} templates in the background", ids.size());
            int upgraded = 0;
            for (Identifier id : ids) {
                if (STOP.get()) {
                    Stats.builderState = "stopped";
                    return;
                }
                waitWhileGenerating();
                if (cache.warm(id, FILES.idToFile(id), resources, source)) upgraded++;
                Stats.BUILDER_DONE.incrementAndGet();
            }
            Stats.builderState = "done";
            StructOpt.LOGGER.info("Template cache builder: done, {} templates upgraded into the cache in {} s", upgraded,
                    Math.round((System.nanoTime() - t0) / 1e9));
        } catch (RuntimeException e) {
            Stats.builderState = "failed";
            if (!STOP.get()) StructOpt.LOGGER.warn("Template cache builder stopped: {}", e.toString());
        }
    }

    /** Yields to the chunk workers: while structures were attempted in the last half second, it waits. */
    private static void waitWhileGenerating() {
        long seen = Stats.START_MISSES.sum() + Stats.AHEAD_COMPUTED.sum();
        while (!STOP.get()) {
            try {
                Thread.sleep(25);
            } catch (InterruptedException e) {
                return;
            }
            long now = Stats.START_MISSES.sum() + Stats.AHEAD_COMPUTED.sum();
            if (now == seen) return;
            seen = now;
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                return;
            }
        }
    }
}
