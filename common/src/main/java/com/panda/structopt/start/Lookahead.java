package com.panda.structopt.start;

import com.panda.structopt.StructOpt;
import com.panda.structopt.config.StructOptConfig;
import com.panda.structopt.coordinator.Coordinator;
import com.panda.structopt.height.VanillaTerrain;
import com.panda.structopt.stats.Stats;
import com.panda.structopt.template.Pools;
import com.panda.structopt.template.TemplateCache;
import com.panda.structopt.template.TemplateWalk;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.core.Holder;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.visitors.CollectFields;
import net.minecraft.nbt.visitors.FieldSelector;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.chunk.status.ChunkPyramid;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.LegacyRandomSource;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import net.minecraft.world.level.levelgen.densityfunction.SamplerContext;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement;
import net.minecraft.world.level.levelgen.structure.placement.StructurePlacement;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * 3.5, structure starts computed ahead of the players, and 3.6, their templates loaded first.
 * <p>
 * Each player is looked at once per cycle ({@code lookahead.cycleTicks}), the players split round-robin into one batch per tick
 * ({@link RoundRobin}). For a moving player the scan looks at the band of chunks just past the ones the chunk system already
 * needs (view distance plus the radius at which full chunks need structure starts), extended along the direction of travel. In
 * that band it finds each structure set's candidate chunk per spacing region (a random-spread set has one per region) and keeps
 * those that pass the set's own {@code isStructureChunk}. At most {@code lookahead.scanCapPerTick} candidates are checked per
 * tick; the rest wait for the next tick.
 * <p>
 * Candidates go to the mod's own low-priority threads, nearest first. There the set loop runs exactly as vanilla's
 * ({@code ChunkGenerator.createStructures}): the single structure, or weighted picks with {@code setLargeFeatureSeed} and a retry
 * after each failure. Every attempt goes through the shared start results (3.4), which is the only way a result reaches the world:
 * when the chunk worker asks. The chunk system itself is never touched: no tickets, no scheduling.
 */
public final class Lookahead {
    private static final long IDLE_NANOS = 5_000_000_000L;
    /** At most this many templates are loaded ahead for one candidate chunk of one set. */
    private static final int PREFETCH_PER_SET = 256;
    /** Blocks per tick below which a player counts as standing still. */
    public static final double MIN_SPEED = 0.05;
    private static volatile @Nullable Lookahead instance;

    private final MinecraftServer server;
    private final StructOptConfig config;
    private final ThreadPoolExecutor executor;
    private final RoundRobin<UUID> players = new RoundRobin<>();
    private final ArrayDeque<Scan> scans = new ArrayDeque<>();
    private final Set<Long> seen = new HashSet<>();
    private final AtomicInteger queued = new AtomicInteger();
    private final AtomicLong sequence = new AtomicLong();
    private long lastMoveNanos;
    private final int startsRadius;

    /** One player's scan: the sets of the player's dimension and the band to look in, walked region by region. */
    private static final class Scan {
        final ServerLevel level;
        final List<Holder<StructureSet>> sets;
        final int cx, cz, inner, outer;
        final double fx, fz, vx, vz;
        /** Not Minecraft's own terrain: templates are loaded ahead, starts are not computed (see {@link VanillaTerrain}). */
        final boolean templatesOnly;
        int set, rx, rz;
        boolean started;

        Scan(ServerLevel level, List<Holder<StructureSet>> sets, int cx, int cz, double fx, double fz, int inner, int outer, double vx,
                double vz, boolean templatesOnly) {
            this.level = level;
            this.templatesOnly = templatesOnly;
            this.sets = sets;
            this.cx = cx;
            this.cz = cz;
            this.fx = fx;
            this.fz = fz;
            this.inner = inner;
            this.outer = outer;
            this.vx = vx;
            this.vz = vz;
        }
    }

    private Lookahead(MinecraftServer server, StructOptConfig config) {
        this.server = server;
        this.config = config;
        int threads = config.lookaheadThreadCount();
        this.executor = new ThreadPoolExecutor(threads, threads, 30, TimeUnit.SECONDS, new PriorityBlockingQueue<>(), r -> {
            Thread t = new Thread(r, "StructOpt lookahead");
            t.setDaemon(true);
            t.setPriority(Thread.MIN_PRIORITY);
            return t;
        });
        this.executor.allowCoreThreadTimeOut(true);
        this.startsRadius = ChunkPyramid.GENERATION_PYRAMID.getStepTo(ChunkStatus.FULL).getAccumulatedRadiusOf(ChunkStatus.STRUCTURE_STARTS);
    }

    /** What the lookahead may do on a generator: compute starts (Minecraft's own terrain), only load templates, or nothing. */
    public enum Mode { STARTS, TEMPLATES, NONE }

    public static Mode mode(ChunkGenerator generator, StructOptConfig config) {
        if (VanillaTerrain.is(generator)) return Mode.STARTS;
        return config.lookaheadPrefetchTemplates && config.lookaheadPrefetchMegabytes > 0 && TemplateCache.active() ? Mode.TEMPLATES : Mode.NONE;
    }

    public static void open(MinecraftServer server, StructOptConfig config) {
        close();
        if (config.lookaheadEnabled && StartCache.active() && !StructOpt.off()) instance = new Lookahead(server, config);
    }

    public static void close() {
        Lookahead l = instance;
        instance = null;
        if (l == null) return;
        // No interrupts: a thread interrupted while it reads a file (or a mod jar) can break that file's channel; the running jobs
        // see that this lookahead is closed and stop at their next step
        l.executor.shutdown();
        l.executor.getQueue().clear();
    }

    public static boolean active() {
        return instance != null;
    }

    public static int queuedCount() {
        Lookahead l = instance;
        return l == null ? 0 : l.queued.get();
    }

    public static void tick(MinecraftServer server) {
        Lookahead l = instance;
        if (l != null && l.server == server) l.onTick();
    }

    private void onTick() {
        List<ServerPlayer> online = server.getPlayerList().getPlayers();
        List<UUID> ids = new ArrayList<>(online.size());
        for (ServerPlayer p : online) ids.add(p.getUUID());
        players.sync(ids);
        long now = System.nanoTime();
        int radius = server.getPlayerList().getViewDistance() + startsRadius;
        for (UUID id : players.batch(server.getTickCount(), config.lookaheadCycleTicks)) {
            ServerPlayer p = server.getPlayerList().getPlayer(id);
            if (p == null) continue;
            Vec3 v = movement(p);
            double speed = Math.sqrt(v.x * v.x + v.z * v.z);
            if (speed < MIN_SPEED) continue;
            lastMoveNanos = now;
            ServerLevel level = p.level();
            Mode mode = mode(level.getChunkSource().getGenerator(), config);
            if (mode == Mode.NONE) continue;
            boolean templatesOnly = mode == Mode.TEMPLATES;
            ChunkGeneratorStructureState state = level.getChunkSource().getGeneratorState();
            ChunkPos c = p.chunkPosition();
            double fx = Math.clamp(p.getX() / 16 - c.x(), 0, 1), fz = Math.clamp(p.getZ() / 16 - c.z(), 0, 1);
            scans.addLast(new Scan(level, state.possibleStructureSets(), c.x(), c.z(), fx, fz, radius - 1,
                    radius + config.lookaheadExtraChunks, v.x / speed, v.z / speed, templatesOnly));
            Stats.AHEAD_PLAYER_SCANS.increment();
            while (scans.size() > 64) scans.removeFirst();
        }
        if (now - lastMoveNanos > IDLE_NANOS) {
            scans.clear();
            return;
        }
        int budget = config.lookaheadScanCapPerTick;
        while (budget > 0 && !scans.isEmpty()) {
            Scan s = scans.peekFirst();
            budget = step(s, budget);
            if (s.set >= s.sets.size()) scans.removeFirst();
        }
        if (seen.size() > 500_000) seen.clear();
    }

    /**
     * A player's movement per tick as the server knows it. A player moves on the client and the server only receives positions,
     * so {@code getDeltaMovement()} stays near zero for walking and flying players; the known movement is taken from those
     * position updates (and from the vehicle when the player steers one).
     */
    public static Vec3 movement(ServerPlayer p) {
        return p.getKnownMovement();
    }

    /** Walks one scan for at most {@code budget} candidate checks; returns the budget left. */
    private int step(Scan s, int budget) {
        ChunkGeneratorStructureState state = s.level.getChunkSource().getGeneratorState();
        while (s.set < s.sets.size() && budget > 0) {
            Holder<StructureSet> set = s.sets.get(s.set);
            if (!(set.value().placement() instanceof RandomSpreadStructurePlacement spread) || spread.spacing() <= 0) {
                s.set++;
                s.started = false;
                continue;
            }
            int spacing = spread.spacing();
            int minRx = Math.floorDiv(s.cx - s.outer, spacing), maxRx = Math.floorDiv(s.cx + s.outer, spacing);
            int minRz = Math.floorDiv(s.cz - s.outer, spacing), maxRz = Math.floorDiv(s.cz + s.outer, spacing);
            if (!s.started) {
                s.rx = minRx;
                s.rz = minRz;
                s.started = true;
            }
            while (s.rx <= maxRx && budget > 0) {
                ChunkPos potential = spread.getPotentialStructureChunk(state.getLevelSeed(), s.rx * spacing, s.rz * spacing);
                budget--;
                Stats.AHEAD_SCANNED.increment();
                consider(s, set, spread, state, potential);
                if (++s.rz > maxRz) {
                    s.rz = minRz;
                    s.rx++;
                }
            }
            if (s.rx > maxRx) {
                s.set++;
                s.started = false;
            }
        }
        return budget;
    }

    private void consider(Scan s, Holder<StructureSet> set, StructurePlacement placement, ChunkGeneratorStructureState state, ChunkPos pos) {
        int dx = pos.x() - s.cx, dz = pos.z() - s.cz;
        int d = Math.max(Math.abs(dx), Math.abs(dz));
        if (!Band.wanted(dx, dz, s.fx, s.fz, s.vx, s.vz,s.inner + 1, s.outer - s.inner - 1)) return;
        long mark = pos.pack() * 31 + System.identityHashCode(set.value()) + 17L * System.identityHashCode(s.level);
        if (!seen.add(mark)) return;
        ChunkHolder holder = s.level.getChunkSource().chunkMap.getVisibleChunkIfPresent(pos.pack());
        if (holder != null) {
            ChunkStatus latest = holder.getLatestStatus();
            if (latest != null && latest.isOrAfter(ChunkStatus.STRUCTURE_STARTS)) return;
        }
        if (!placement.isStructureChunk(state, pos.x(), pos.z())) return;
        submit(s.level, set, pos, d, holder == null, s.templatesOnly);
    }

    /** Queues one candidate chunk of one set, past the queue cap (the dev parity harness queues a whole square this way). */
    public static boolean submitCandidate(ServerLevel level, Holder<StructureSet> set, ChunkPos pos, int distance, boolean checkDisk) {
        Lookahead l = instance;
        if (l == null) return false;
        Mode mode = mode(level.getChunkSource().getGenerator(), l.config);
        if (mode == Mode.NONE) return false;
        boolean templatesOnly = mode == Mode.TEMPLATES;
        l.queued.incrementAndGet();
        l.executor.execute(l.new Job(level, set, pos, distance, checkDisk, templatesOnly, l.sequence.getAndIncrement()));
        return true;
    }

    private void submit(ServerLevel level, Holder<StructureSet> set, ChunkPos pos, int distance, boolean checkDisk, boolean templatesOnly) {
        if (queued.get() >= config.lookaheadQueue) {
            Stats.AHEAD_DROPPED.increment();
            return;
        }
        queued.incrementAndGet();
        executor.execute(new Job(level, set, pos, distance, checkDisk, templatesOnly, sequence.getAndIncrement()));
    }

    private final class Job implements Runnable, Comparable<Job> {
        final ServerLevel level;
        final Holder<StructureSet> set;
        final ChunkPos pos;
        final int distance;
        final boolean checkDisk;
        final boolean templatesOnly;
        final long order;

        Job(ServerLevel level, Holder<StructureSet> set, ChunkPos pos, int distance, boolean checkDisk, boolean templatesOnly, long order) {
            this.level = level;
            this.templatesOnly = templatesOnly;
            this.set = set;
            this.pos = pos;
            this.distance = distance;
            this.checkDisk = checkDisk;
            this.order = order;
        }

        @Override
        public int compareTo(Job o) {
            int c = Integer.compare(distance, o.distance);
            return c != 0 ? c : Long.compare(order, o.order);
        }

        @Override
        public void run() {
            try {
                if (instance != Lookahead.this || server.isStopped()) return;
                if (checkDisk && savedWithStarts()) return;
                if (templatesOnly) {
                    prefetchSet(level, set, pos);
                } else {
                    computeSet(level, set, pos);
                }
            } catch (RuntimeException e) {
                StructOpt.LOGGER.debug("Lookahead skipped {} at {}: {}", set.getRegisteredName(), pos, e.toString());
            } finally {
                queued.decrementAndGet();
            }
        }

        private boolean savedWithStarts() {
            CollectFields fields = new CollectFields(new FieldSelector(StringTag.TYPE, "Status"));
            level.getChunkSource().chunkMap.chunkScanner().scanChunk(pos, fields).join();
            if (!(fields.getResult() instanceof CompoundTag tag)) return false;
            String status = tag.getStringOr("Status", "");
            return !status.isEmpty() && ChunkStatus.byName(status).isOrAfter(ChunkStatus.STRUCTURE_STARTS);
        }
    }

    /** Vanilla's per-set loop of {@code createStructures}, through the shared start results. */
    void computeSet(ServerLevel level, Holder<StructureSet> set, ChunkPos pos) {
        ChunkGenerator generator = level.getChunkSource().getGenerator();
        ChunkGeneratorStructureState state = level.getChunkSource().getGeneratorState();
        RandomState randomState = state.randomState();
        StructureTemplateManager templates = server.getStructureTemplateManager();
        long seed = state.getLevelSeed();
        List<StructureSet.StructureSelectionEntry> structures = set.value().structures();
        if (structures.isEmpty()) return;
        if (Coordinator.blocks(level.dimension(), structures.getFirst().structure(), pos)) return;
        if (config.lookaheadPrefetchTemplates) prefetch(structures, templates);
        Climate.Sampler sampler = randomState.createClimateSampler(SamplerContext.builder().enableCaches().build());
        if (structures.size() == 1) {
            attempt(level, structures.getFirst(), generator, randomState, templates, seed, pos, sampler);
            return;
        }
        ArrayList<StructureSet.StructureSelectionEntry> options = new ArrayList<>(structures);
        WorldgenRandom random = new WorldgenRandom(new LegacyRandomSource(0L));
        random.setLargeFeatureSeed(seed, pos.x(), pos.z());
        int total = 0;
        for (StructureSet.StructureSelectionEntry option : options) total += option.weight();
        while (!options.isEmpty()) {
            int choice = random.nextInt(total);
            int index = 0;
            for (StructureSet.StructureSelectionEntry option : options) {
                choice -= option.weight();
                if (choice < 0) break;
                index++;
            }
            StructureSet.StructureSelectionEntry selected = options.get(index);
            Boolean valid = attempt(level, selected, generator, randomState, templates, seed, pos, sampler);
            if (valid == null || valid) return;
            options.remove(index);
            total -= selected.weight();
        }
    }

    private @Nullable Boolean attempt(ServerLevel level, StructureSet.StructureSelectionEntry selected, ChunkGenerator generator,
                                      RandomState randomState, StructureTemplateManager templates, long seed, ChunkPos pos,
                                      Climate.Sampler sampler) {
        Holder<Structure> holder = selected.structure();
        if (Coordinator.blocks(level.dimension(), holder, pos)) return false;
        Structure structure = holder.value();
        return StartCache.ahead(holder, level.dimension(), level.registryAccess(), generator, randomState, templates, seed, pos, level,
                () -> structure.generate(holder, level.dimension(), level.registryAccess(), generator, generator.getBiomeSource(), sampler,
                        randomState, templates, seed, pos, 0, level, structure.biomes()::contains));
    }

    /**
     * 3.6 where starts aren't computed ahead: the templates this set's start will most likely ask for, loaded into the prefetch
     * store. The structure a set tries first at a chunk depends only on the seed (its weighted pick), so that one's whole pool tree
     * is walked; the others, tried only when the first doesn't fit the biome, get their start pools.
     */
    void prefetchSet(ServerLevel level, Holder<StructureSet> set, ChunkPos pos) {
        TemplateCache cache = TemplateCache.get();
        List<StructureSet.StructureSelectionEntry> structures = set.value().structures();
        if (cache == null || structures.isEmpty()) return;
        int first = structures.size() == 1 ? 0 : firstPick(structures, level.getChunkSource().getGeneratorState().getLevelSeed(), pos);
        TemplateWalk walk = new TemplateWalk(server, cache, PREFETCH_PER_SET, () -> instance != this || server.isStopped());
        walk.structure(structures.get(first).structure(), true);
        for (int i = 0; i < structures.size(); i++) if (i != first) walk.structure(structures.get(i).structure(), false);
    }

    /** The index of the structure vanilla's set loop tries first at this chunk (its first weighted pick). */
    static int firstPick(List<StructureSet.StructureSelectionEntry> structures, long seed, ChunkPos pos) {
        WorldgenRandom random = new WorldgenRandom(new LegacyRandomSource(0L));
        random.setLargeFeatureSeed(seed, pos.x(), pos.z());
        int total = 0;
        for (StructureSet.StructureSelectionEntry option : structures) total += option.weight();
        int choice = random.nextInt(total);
        int index = 0;
        for (StructureSet.StructureSelectionEntry option : structures) {
            choice -= option.weight();
            if (choice < 0) break;
            index++;
        }
        return index;
    }

    /** 3.6: the templates of the start pools this set can begin with, loaded here so the worker finds them in memory. */
    private void prefetch(List<StructureSet.StructureSelectionEntry> structures, StructureTemplateManager templates) {
        Set<Identifier> ids = new LinkedHashSet<>();
        for (StructureSet.StructureSelectionEntry e : structures) Pools.startTemplates(e.structure(), ids, 64);
        for (Identifier id : ids) {
            templates.get(id);
            Stats.PREFETCHED.increment();
        }
    }
}
