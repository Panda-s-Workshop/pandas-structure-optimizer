package com.panda.structopt.start;

import com.panda.structopt.StructOpt;
import com.panda.structopt.config.StructOptConfig;
import com.panda.structopt.stats.Stats;
import com.panda.structopt.stats.Timing;
import com.panda.structopt.util.SingleFlight;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import com.panda.structopt.mixin.StructurePieceAccessor;
import java.util.List;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.pieces.StructurePieceSerializationContext;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import org.jspecify.annotations.Nullable;

/**
 * 3.4, shared structure-start results. Inside {@code ChunkGenerator.tryGenerateStructure} (where the biome rule is always the
 * structure's own biome list), the outcome of {@code Structure.generate} is kept for the session: a failure as a marker, a
 * success as the start's NBT exactly as a chunk saves it. A second call for the same inputs gets a fresh {@code StructureStart}
 * loaded from that NBT, so no piece or reference counter is ever shared between two chunks.
 * <p>
 * The key holds every input that can change the outcome: dimension, structure, chunk, seed, reference count, the generator and
 * random state instances, and the height range. Calls with a different generator or seed are simply other keys.
 */
public final class StartCache {
    record Key(ResourceKey<Level> level, ResourceKey<Structure> structure, long chunk, long seed, int references,
               ChunkGenerator generator, RandomState randomState, int minY, int height) {}

    static final class Entry {
        final @Nullable CompoundTag tag;
        final long bytes;
        final boolean ahead;
        /** False for a start whose copy wouldn't save to the same NBT: never shared, every caller computes its own. */
        final boolean shareable;
        final AtomicBoolean used = new AtomicBoolean();

        Entry(@Nullable CompoundTag tag, boolean ahead, boolean shareable) {
            this.tag = tag;
            this.bytes = tag == null ? 16 : 64L + tag.sizeInBytes();
            this.ahead = ahead;
            this.shareable = shareable;
        }
    }

    private record Computed(@Nullable StructureStart start, Entry entry) {}

    private static volatile @Nullable StartCache instance;

    private final MinecraftServer server;
    private final long capBytes;
    private long bytes;
    private final LinkedHashMap<Key, Entry> entries = new LinkedHashMap<>(4096, 0.75f, true);
    private final SingleFlight<Key, Computed> flight = new SingleFlight<>();

    private StartCache(MinecraftServer server, long capBytes) {
        this.server = server;
        this.capBytes = capBytes;
    }

    public static void open(MinecraftServer server, StructOptConfig config) {
        instance = config.startCacheEnabled && !StructOpt.off() ? new StartCache(server, (long) config.startCacheMaxMegabytes << 20) : null;
    }

    public static void close() {
        StartCache c = instance;
        instance = null;
        if (c != null) synchronized (c) {
            c.entries.clear();
            c.bytes = 0;
        }
    }

    public static boolean active() {
        return instance != null;
    }

    /**
     * The result of {@code structure.generate(...)} for these inputs: computed here (and kept), or loaded from the kept NBT.
     * Every real computation is timed into the per-structure stats.
     */
    public static StructureStart generate(Holder<Structure> holder, ResourceKey<Level> level, RegistryAccess registries,
                                          ChunkGenerator generator, RandomState randomState, StructureTemplateManager templates,
                                          long seed, ChunkPos pos, int references, LevelHeightAccessor height,
                                          Supplier<StructureStart> compute) {
        StartCache cache = instance;
        Optional<ResourceKey<Structure>> structureKey = holder.unwrapKey();
        if (cache == null || structureKey.isEmpty()) return timed(holder, compute);
        Key key = new Key(level, structureKey.get(), pos.pack(), seed, references, generator, randomState, height.getMinY(), height.getHeight());
        StructurePieceSerializationContext context = new StructurePieceSerializationContext(cache.server.getResourceManager(), registries, templates);
        Entry found = cache.lookup(key);
        if (found != null && !found.shareable) return timed(holder, compute);
        if (found != null) {
            StructureStart start = cache.materialize(found, context, seed);
            if (start != null) {
                Stats.START_HITS.increment();
                if (found.ahead && found.used.compareAndSet(false, true)) Stats.AHEAD_USED.increment();
                return start;
            }
        }
        boolean[] shared = new boolean[1];
        Computed computed = cache.flight.run(key, () -> cache.compute(key, context, holder, pos, compute, false), shared);
        if (!shared[0] && computed.start() != null) return computed.start();
        if (!computed.entry().shareable) return timed(holder, compute);
        Stats.START_SHARED.increment();
        if (computed.entry().ahead && computed.entry().used.compareAndSet(false, true)) Stats.AHEAD_USED.increment();
        StructureStart start = cache.materialize(computed.entry(), context, seed);
        return start != null ? start : timed(holder, compute);
    }

    /**
     * 3.5: computes a start ahead of the chunk system, into the kept results only. Returns whether the start is valid (the set
     * loop goes on to the next pick when it isn't), or null when the cache is off.
     */
    public static @Nullable Boolean ahead(Holder<Structure> holder, ResourceKey<Level> level, RegistryAccess registries,
                                          ChunkGenerator generator, RandomState randomState, StructureTemplateManager templates,
                                          long seed, ChunkPos pos, LevelHeightAccessor height, Supplier<StructureStart> compute) {
        StartCache cache = instance;
        Optional<ResourceKey<Structure>> structureKey = holder.unwrapKey();
        if (cache == null || structureKey.isEmpty()) return null;
        Key key = new Key(level, structureKey.get(), pos.pack(), seed, 0, generator, randomState, height.getMinY(), height.getHeight());
        Entry found = cache.lookup(key);
        if (found != null) return found.tag != null;

        StructurePieceSerializationContext context = new StructurePieceSerializationContext(cache.server.getResourceManager(), registries, templates);
        Computed computed = cache.flight.run(key, () -> cache.compute(key, context, holder, pos, compute, true));
        return computed.entry().tag != null;
    }

    private Computed compute(Key key, StructurePieceSerializationContext context, Holder<Structure> holder, ChunkPos pos,
                             Supplier<StructureStart> compute, boolean ahead) {
        Entry again = lookup(key);
        if (again != null) return new Computed(null, again);
        StructureStart start = timed(holder, compute);
        Entry entry;
        try {
            if (!start.isValid()) {
                entry = new Entry(null, ahead, true);
            } else {
                CompoundTag tag = start.createTag(context, pos);
                Entry candidate = new Entry(tag, ahead, true);
                // share it only if a copy saves to exactly the same NBT (any piece type that doesn't round-trip stays unshared)
                StructureStart copy = materialize(candidate, context, key.seed());
                boolean same = copy != null && copy.createTag(context, pos).equals(tag);
                entry = same ? candidate : new Entry(null, ahead, false);
                if (!same) Stats.START_UNSHAREABLE.increment();
            }
        } catch (RuntimeException e) {
            // a start that can't be saved is never shared; the caller still gets it
            entry = new Entry(null, ahead, false);
            Stats.START_UNSHAREABLE.increment();
        }
        put(key, entry);
        if (ahead) Stats.AHEAD_COMPUTED.increment();
        else Stats.START_MISSES.increment();
        return new Computed(start, entry);
    }

    private @Nullable StructureStart materialize(Entry entry, StructurePieceSerializationContext context, long seed) {
        if (entry.tag == null) return StructureStart.INVALID_START;
        StructureStart start = StructureStart.loadStaticStart(context, entry.tag.copy(), seed);
        if (start == null || !start.isValid()) return start;
        // a loaded jigsaw piece recomputes its box from its template; put back the box it was saved with
        ListTag children = entry.tag.getListOrEmpty("Children");
        List<StructurePiece> pieces = start.getPieces();
        if (children.size() == pieces.size()) {
            for (int i = 0; i < pieces.size(); i++) {
                int[] b = children.getCompoundOrEmpty(i).getIntArray("BB").orElse(null);
                if (b != null && b.length == 6)
                    ((StructurePieceAccessor) pieces.get(i)).structopt$setBoundingBox(new BoundingBox(b[0], b[1], b[2], b[3], b[4], b[5]));
            }
        }
        return start;
    }

    private synchronized @Nullable Entry lookup(Key key) {
        return entries.get(key);
    }

    private synchronized void put(Key key, Entry entry) {
        Entry before = entries.put(key, entry);
        if (before != null) bytes -= before.bytes;
        bytes += entry.bytes;
        var it = entries.entrySet().iterator();
        while (bytes > capBytes && it.hasNext()) {
            Map.Entry<Key, Entry> oldest = it.next();
            bytes -= oldest.getValue().bytes;
            if (oldest.getValue().ahead && !oldest.getValue().used.get()) Stats.AHEAD_DROPPED.increment();
            it.remove();
        }
    }

    /** Whether a result for these inputs is already kept or being computed (the lookahead skips those). */
    public static boolean known(ResourceKey<Level> level, Holder<Structure> holder, ChunkPos pos, long seed, ChunkGenerator generator,
                                RandomState randomState, LevelHeightAccessor height) {
        StartCache cache = instance;
        Optional<ResourceKey<Structure>> structureKey = holder.unwrapKey();
        if (cache == null || structureKey.isEmpty()) return false;
        Key key = new Key(level, structureKey.get(), pos.pack(), seed, 0, generator, randomState, height.getMinY(), height.getHeight());
        return cache.lookup(key) != null || cache.flight.isInFlight(key);
    }

    /** Kept results and their estimated size in bytes. */
    public static long[] size() {
        StartCache cache = instance;
        if (cache == null) return new long[] {0, 0};
        synchronized (cache) {
            return new long[] {cache.entries.size(), cache.bytes};
        }
    }

    static StructureStart timed(Holder<Structure> holder, Supplier<StructureStart> compute) {
        long t0 = System.nanoTime();
        long template0 = Timing.get(Timing.TEMPLATE), height0 = Timing.get(Timing.HEIGHT);
        StructureStart start = compute.get();
        long nanos = System.nanoTime() - t0;
        String id = holder.unwrapKey().map(k -> k.identifier().toString()).orElse("(unregistered)");
        Stats.attempt(id, start.isValid(), nanos, Timing.get(Timing.TEMPLATE) - template0, Timing.get(Timing.HEIGHT) - height0);
        return start;
    }
}
