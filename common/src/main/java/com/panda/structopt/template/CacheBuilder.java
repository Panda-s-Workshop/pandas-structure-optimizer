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
import org.jspecify.annotations.Nullable;

/**
 * 3.2, the background cache builder. Low-priority threads ({@code templateCacheBuilderThreads}) walk every template the pack can
 * place (the template pools' elements first, then every other {@code structure/*.nbt} in the data packs) and upgrade each one
 * not yet in the disk cache. They write only to disk; nothing stays in memory. They run on spare CPU: while the whole machine is
 * busier than {@code templateCacheBuilderMaxCpu} they wait. They stop at shutdown, and the next start carries on (entries
 * already written are skipped).
 * <p>
 * Each start is its own {@link Run} with its own stop flag and threads, so a run that is slow to stop (a long upgrade, or still
 * listing templates) can never be revived by the next start, and only the current run reports its state.
 */
public final class CacheBuilder {
    private static final FileToIdConverter FILES = new FileToIdConverter("structure", ".nbt");
    private static final long BUSY_WAIT_MILLIS = 500;
    /** Guards {@link #current} and the builder's stats, so a run can't report after it stopped being current. Never held while waiting. */
    private static final Object STATE = new Object();
    private static volatile @Nullable Run current;

    private static final class Run {
        final AtomicBoolean stop = new AtomicBoolean();
        final List<Thread> threads = new CopyOnWriteArrayList<>();

        boolean isCurrent() {
            return current == this;
        }

        void state(String state) {
            synchronized (STATE) {
                if (isCurrent()) Stats.builderState = state;
            }
        }

        void total(int total) {
            synchronized (STATE) {
                if (isCurrent()) Stats.BUILDER_TOTAL.set(total);
            }
        }

        void checked() {
            synchronized (STATE) {
                if (isCurrent()) Stats.BUILDER_DONE.incrementAndGet();
            }
        }
    }

    private CacheBuilder() {}

    public static synchronized void start(MinecraftServer server) {
        stop();
        TemplateCache cache = TemplateCache.get();
        if (cache == null) return;
        StructOptConfig config = StructOptConfig.get();
        Run run = new Run();
        synchronized (STATE) {
            current = run;
            Stats.BUILDER_DONE.set(0);
            Stats.BUILDER_TOTAL.set(0);
            Stats.builderState = "listing"; // never the last run's "done"
        }
        Thread lister = new Thread(() -> run(run, server, cache, config), "StructOpt template cache builder");
        lister.setDaemon(true);
        lister.setPriority(Thread.MIN_PRIORITY);
        run.threads.add(lister);
        lister.start();
    }

    public static synchronized void stop() {
        Run run;
        synchronized (STATE) {
            run = current;
            if (run == null) return;
            if (!"done".equals(Stats.builderState)) Stats.builderState = "stopped";
            current = null;
        }
        run.stop.set(true);
        for (Thread t : run.threads) t.interrupt();
        for (Thread t : run.threads) {
            try {
                t.join(10_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    public static boolean running() {
        Run run = current;
        if (run == null) return false;
        for (Thread t : run.threads) if (t.isAlive()) return true;
        return false;
    }

    private static void run(Run run, MinecraftServer server, TemplateCache cache, StructOptConfig config) {
        long t0 = System.nanoTime();
        try {
            StructureTemplateManager manager = server.getStructureTemplateManager();
            ResourceManagerTemplateSource source = ((StructureTemplateManagerAccessor) manager).structopt$resourceManagerSource();
            ResourceManager resources = server.getResourceManager();
            Set<Identifier> set = new LinkedHashSet<>();
            server.registryAccess().lookupOrThrow(Registries.TEMPLATE_POOL).forEach(pool -> Pools.templates(pool, set, Integer.MAX_VALUE));
            for (Identifier file : FILES.listMatchingResources(resources).keySet()) set.add(FILES.fileToId(file));
            List<Identifier> ids = List.copyOf(set);
            if (run.stop.get()) return;
            run.total(ids.size());
            run.state("running");
            StructOpt.LOGGER.info("Template cache builder: checking {} templates in the background on {} thread(s)", ids.size(),
                    config.templateCacheBuilderThreads);
            AtomicInteger next = new AtomicInteger();
            AtomicInteger upgraded = new AtomicInteger();
            Runnable work = () -> work(run, ids, next, upgraded, cache, resources, source, config.templateCacheBuilderMaxCpu);
            List<Thread> helpers = new ArrayList<>();
            for (int i = 1; i < config.templateCacheBuilderThreads; i++) {
                Thread t = new Thread(work, "StructOpt template cache builder " + (i + 1));
                t.setDaemon(true);
                t.setPriority(Thread.MIN_PRIORITY);
                helpers.add(t);
            }
            run.threads.addAll(helpers);
            if (run.stop.get()) return;
            for (Thread t : helpers) t.start();
            work.run();
            for (Thread t : helpers) t.join();
            if (run.stop.get()) return;
            run.state("done");
            StructOpt.LOGGER.info("Template cache builder: done, {} templates upgraded into the cache in {} s", upgraded.get(),
                    Math.round((System.nanoTime() - t0) / 1e9));
        } catch (InterruptedException e) {
            // stopped
        } catch (RuntimeException e) {
            run.state("failed");
            if (!run.stop.get()) StructOpt.LOGGER.warn("Template cache builder stopped: {}", e.toString());
        }
    }

    private static void work(Run run, List<Identifier> ids, AtomicInteger next, AtomicInteger upgraded, TemplateCache cache,
            ResourceManager resources, ResourceManagerTemplateSource source, double maxCpu) {
        for (int i; (i = next.getAndIncrement()) < ids.size(); ) {
            if (!SpareCpu.await(maxCpu, SpareCpu.SYSTEM, run.stop::get, BUSY_WAIT_MILLIS)) return;
            Identifier id = ids.get(i);
            try {
                if (cache.warm(id, FILES.idToFile(id), resources, source)) upgraded.incrementAndGet();
            } catch (RuntimeException e) {
                if (run.stop.get()) return;
                throw e;
            }
            run.checked();
        }
    }
}
