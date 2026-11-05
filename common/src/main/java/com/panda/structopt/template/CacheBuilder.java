package com.panda.structopt.template;

import com.panda.structopt.StructOpt;
import com.panda.structopt.config.StructOptConfig;
import com.panda.structopt.mixin.StructureTemplateManagerAccessor;
import com.panda.structopt.stats.Stats;
import com.panda.structopt.util.SpareCpu;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.FileToIdConverter;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import net.minecraft.world.level.levelgen.structure.templatesystem.loader.ResourceManagerTemplateSource;

/**
 * 3.2, the background cache builder. Low-priority threads ({@code templateCacheBuilderThreads}) walk every template the pack can
 * place (the template pools' elements first, then every other {@code structure/*.nbt} in the data packs) and upgrade each one
 * not yet in the disk cache. They write only to disk; nothing stays in memory. They run on spare CPU: while the whole machine is
 * busier than {@code templateCacheBuilderMaxCpu} they wait. They stop at shutdown, and the next start carries on (entries
 * already written are skipped).
 */
public final class CacheBuilder {
    private static final List<Thread> THREADS = new CopyOnWriteArrayList<>();
    private static final AtomicBoolean STOP = new AtomicBoolean();
    private static final FileToIdConverter FILES = new FileToIdConverter("structure", ".nbt");
    private static final long BUSY_WAIT_MILLIS = 500;

    private CacheBuilder() {}

    public static synchronized void start(MinecraftServer server) {
        stop();
        TemplateCache cache = TemplateCache.get();
        if (cache == null) return;
        STOP.set(false);
        StructOptConfig config = StructOptConfig.get();
        Thread lister = new Thread(() -> run(server, cache, config), "StructOpt template cache builder");
        lister.setDaemon(true);
        lister.setPriority(Thread.MIN_PRIORITY);
        THREADS.add(lister);
        lister.start();
    }

    public static synchronized void stop() {
        if (THREADS.isEmpty()) return;
        STOP.set(true);
        for (Thread t : THREADS) t.interrupt();
        for (Thread t : THREADS) {
            try {
                t.join(10_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        THREADS.clear();
    }

    public static boolean running() {
        for (Thread t : THREADS) if (t.isAlive()) return true;
        return false;
    }

    private static void run(MinecraftServer server, TemplateCache cache, StructOptConfig config) {
        long t0 = System.nanoTime();
        try {
            StructureTemplateManager manager = server.getStructureTemplateManager();
            ResourceManagerTemplateSource source = ((StructureTemplateManagerAccessor) manager).structopt$resourceManagerSource();
            ResourceManager resources = server.getResourceManager();
            Set<Identifier> set = new LinkedHashSet<>();
            server.registryAccess().lookupOrThrow(Registries.TEMPLATE_POOL).forEach(pool -> Pools.templates(pool, set, Integer.MAX_VALUE));
            for (Identifier file : FILES.listMatchingResources(resources).keySet()) set.add(FILES.fileToId(file));
            List<Identifier> ids = List.copyOf(set);
            Stats.BUILDER_TOTAL.set(ids.size());
            Stats.builderState = "running";
            StructOpt.LOGGER.info("Template cache builder: checking {} templates in the background on {} thread(s)", ids.size(),
                    config.templateCacheBuilderThreads);
            AtomicInteger next = new AtomicInteger();
            AtomicInteger upgraded = new AtomicInteger();
            Runnable work = () -> work(ids, next, upgraded, cache, resources, source, config.templateCacheBuilderMaxCpu);
            List<Thread> helpers = new ArrayList<>();
            for (int i = 1; i < config.templateCacheBuilderThreads; i++) {
                Thread t = new Thread(work, "StructOpt template cache builder " + (i + 1));
                t.setDaemon(true);
                t.setPriority(Thread.MIN_PRIORITY);
                helpers.add(t);
            }
            THREADS.addAll(helpers);
            if (STOP.get()) return;
            for (Thread t : helpers) t.start();
            work.run();
            for (Thread t : helpers) t.join();
            if (STOP.get()) {
                Stats.builderState = "stopped";
                return;
            }
            Stats.builderState = "done";
            StructOpt.LOGGER.info("Template cache builder: done, {} templates upgraded into the cache in {} s", upgraded.get(),
                    Math.round((System.nanoTime() - t0) / 1e9));
        } catch (InterruptedException e) {
            Stats.builderState = "stopped";
        } catch (RuntimeException e) {
            Stats.builderState = "failed";
            if (!STOP.get()) StructOpt.LOGGER.warn("Template cache builder stopped: {}", e.toString());
        }
    }

    private static void work(List<Identifier> ids, AtomicInteger next, AtomicInteger upgraded, TemplateCache cache,
            ResourceManager resources, ResourceManagerTemplateSource source, double maxCpu) {
        for (int i; (i = next.getAndIncrement()) < ids.size(); ) {
            if (!SpareCpu.await(maxCpu, SpareCpu.SYSTEM, STOP::get, BUSY_WAIT_MILLIS)) return;
            Identifier id = ids.get(i);
            try {
                if (cache.warm(id, FILES.idToFile(id), resources, source)) upgraded.incrementAndGet();
            } catch (RuntimeException e) {
                if (STOP.get()) return;
                throw e;
            }
            Stats.BUILDER_DONE.incrementAndGet();
        }
    }
}
