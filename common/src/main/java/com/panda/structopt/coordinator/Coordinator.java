package com.panda.structopt.coordinator;

import com.panda.structopt.StructOpt;
import com.panda.structopt.config.StructOptConfig;
import com.panda.structopt.mixin.JigsawStructureAccessor;
import com.panda.structopt.stats.Stats;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.IntBinaryOperator;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeResolver;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import net.minecraft.world.level.levelgen.structure.placement.ConcentricRingsStructurePlacement;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement;
import net.minecraft.world.level.levelgen.structure.placement.StructurePlacement;
import net.minecraft.world.level.levelgen.structure.structures.JigsawStructure;
import org.jspecify.annotations.Nullable;

/**
 * 3.13, the opt-in structure coordinator. With it on for a world, a structure set's start in a chunk is dropped before it runs
 * when a higher-ranked set's possible start (from the seed, with its biome check at the candidate point) is too close
 * ({@link CoordinatorRules}). The decision reads only seed-derived candidates, so it is the same in any generation order, with
 * any thread count, with C2ME or without.
 */
public final class Coordinator {
    private static volatile @Nullable Coordinator instance;
    private static final int UNKNOWN_PLACEMENT_SCAN_LIMIT = 16;

    private final MinecraftServer server;
    private final StructOptConfig config;
    private final Map<ResourceKey<Level>, LevelSets> levels = new ConcurrentHashMap<>();

    /** One dimension's structure sets as the coordinator sees them, and its decisions so far. */
    private final class LevelSets {
        final ChunkGeneratorStructureState state;
        final ChunkGenerator generator;
        final long seed;
        final List<SetInfo> sets = new ArrayList<>();
        final Map<Structure, SetInfo> byStructure = new IdentityHashMap<>();
        final Map<Long, Boolean> decisions = new ConcurrentHashMap<>();
        final int maxRadius;

        LevelSets(ChunkGeneratorStructureState state, ChunkGenerator generator, long seed) {
            this.state = state;
            this.generator = generator;
            this.seed = seed;
            int max = 0;
            for (Holder<StructureSet> set : state.possibleStructureSets()) {
                SetInfo info = new SetInfo(set, sets.size());
                sets.add(info);
                for (StructureSet.StructureSelectionEntry e : set.value().structures()) byStructure.putIfAbsent(e.structure().value(), info);
                max = Math.max(max, info.radius);
            }
            this.maxRadius = max;
        }
    }

    private final class SetInfo {
        final Holder<StructureSet> set;
        final int index;
        final String id;
        final int priority;
        final int spacing;
        final int radius;

        SetInfo(Holder<StructureSet> set, int index) {
            this.set = set;
            this.index = index;
            this.id = set.unwrapKey().map(k -> k.identifier().toString()).orElse("set#" + index);
            int p = Integer.MIN_VALUE, r = 0;
            for (StructureSet.StructureSelectionEntry e : set.value().structures()) {
                p = Math.max(p, priorityOf(e.structure()));
                r = Math.max(r, radiusOf(e.structure()));
            }
            Integer setPriority = config.coordinatorPriorities.get(id);
            this.priority = setPriority != null ? setPriority : (p == Integer.MIN_VALUE ? 0 : p);
            Integer setRadius = config.coordinatorFootprintOverrides.get(id);
            this.radius = setRadius != null ? setRadius : Math.max(r, 16);
            StructurePlacement placement = set.value().placement();
            this.spacing = placement instanceof RandomSpreadStructurePlacement spread ? spread.spacing()
                    : placement instanceof ConcentricRingsStructurePlacement ? 10_000 : 0;
        }
    }

    private Coordinator(MinecraftServer server, StructOptConfig config) {
        this.server = server;
        this.config = config;
    }

    /** Switches the coordinator on or off for the server's world (decided once per world by {@link CoordinatorWorld}). */
    public static void open(MinecraftServer server, StructOptConfig config, boolean enabledForWorld) {
        instance = enabledForWorld && !StructOpt.off() ? new Coordinator(server, config) : null;
    }

    public static void close() {
        instance = null;
    }

    public static boolean active() {
        return instance != null;
    }

    /** Whether the coordinator drops this structure's start in this chunk. */
    public static boolean blocks(ResourceKey<Level> level, Holder<Structure> structure, ChunkPos pos) {
        Coordinator c = instance;
        if (c == null) return false;
        boolean blocked = c.decide(level, structure, pos);
        if (blocked) Stats.COORDINATOR_BLOCKED.increment();
        return blocked;
    }

    private boolean decide(ResourceKey<Level> levelKey, Holder<Structure> structure, ChunkPos pos) {
        LevelSets sets = levelSets(levelKey);
        return sets != null && decide(sets, structure, pos);
    }

    private boolean decide(LevelSets sets, Holder<Structure> structure, ChunkPos pos) {
        SetInfo own = sets.byStructure.get(structure.value());
        if (own == null) return false;
        long key = pos.pack() * 1_000_003L + own.index;
        Boolean known = sets.decisions.get(key);
        if (known != null) return known;
        boolean loses = CoordinatorRules.loses(candidate(own, pos, true), candidatesNear(sets, own, pos), sets.seed,
                minDistances());
        if (sets.decisions.size() > 1_000_000) sets.decisions.clear();
        sets.decisions.put(key, loses);
        return loses;
    }

    private @Nullable LevelSets levelSets(ResourceKey<Level> key) {
        LevelSets s = levels.get(key);
        if (s != null) return s;
        ServerLevel level = server.getLevel(key);
        if (level == null) return null;
        return levels.computeIfAbsent(key, k -> new LevelSets(level.getChunkSource().getGeneratorState(),
                level.getChunkSource().getGenerator(), level.getSeed()));
    }

    /** Every other set's live candidate starts within reach of {@code pos} (reach: the largest conflict distance possible). */
    private List<CoordinatorRules.Candidate> candidatesNear(LevelSets sets, SetInfo own, ChunkPos pos) {
        List<CoordinatorRules.Candidate> out = new ArrayList<>();
        ChunkGeneratorStructureState state = sets.state;
        ChunkGenerator generator = sets.generator;
        BiomeResolver biomes = null;
        int maxMin = 0;
        for (int v : config.coordinatorMinDistances.values()) maxMin = Math.max(maxMin, v);
        for (SetInfo other : sets.sets) {
            if (other == own) continue;
            int reachBlocks = Math.max(own.radius + other.radius, maxMin);
            int reach = (reachBlocks + 15) / 16;
            List<ChunkPos> positions = new ArrayList<>();
            StructurePlacement placement = other.set.value().placement();
            if (placement instanceof RandomSpreadStructurePlacement spread && spread.spacing() > 0) {
                int s = spread.spacing();
                for (int rx = Math.floorDiv(pos.x() - reach, s); rx <= Math.floorDiv(pos.x() + reach, s); rx++)
                    for (int rz = Math.floorDiv(pos.z() - reach, s); rz <= Math.floorDiv(pos.z() + reach, s); rz++) {
                        ChunkPos p = spread.getPotentialStructureChunk(state.getLevelSeed(), rx * s, rz * s);
                        if (Math.abs(p.x() - pos.x()) <= reach && Math.abs(p.z() - pos.z()) <= reach) positions.add(p);
                    }
            } else if (placement instanceof ConcentricRingsStructurePlacement rings) {
                List<ChunkPos> ring = state.getRingPositionsFor(rings);
                if (ring != null) for (ChunkPos p : ring)
                    if (Math.abs(p.x() - pos.x()) <= reach && Math.abs(p.z() - pos.z()) <= reach) positions.add(p);
            } else if (reach <= UNKNOWN_PLACEMENT_SCAN_LIMIT) {
                for (int x = pos.x() - reach; x <= pos.x() + reach; x++)
                    for (int z = pos.z() - reach; z <= pos.z() + reach; z++) positions.add(new ChunkPos(x, z));
            }
            for (ChunkPos p : positions) {
                if (!placement.isStructureChunk(state, p.x(), p.z())) continue;
                if (biomes == null) biomes = generator.getBiomeSource().createUncachedResolver(state.randomState());
                out.add(candidate(other, p, liveAt(other, p, biomes, generator)));
            }
        }
        return out;
    }

    /** The candidate counts when one of its set's structures accepts the biome at the chunk centre, at sea level. */
    private static boolean liveAt(SetInfo set, ChunkPos p, BiomeResolver biomes, ChunkGenerator generator) {
        Holder<Biome> biome = biomes.getNoiseBiome(QuartPos.fromBlock(p.getMiddleBlockX()), QuartPos.fromBlock(generator.getSeaLevel()),
                QuartPos.fromBlock(p.getMiddleBlockZ()));
        for (StructureSet.StructureSelectionEntry e : set.set.value().structures())
            if (e.structure().value().biomes().contains(biome)) return true;
        return false;
    }

    private static CoordinatorRules.Candidate candidate(SetInfo set, ChunkPos pos, boolean live) {
        return new CoordinatorRules.Candidate(set.id, pos.x(), pos.z(), set.priority, set.spacing, set.radius, live);
    }

    private IntBinaryOperator minDistances() {
        Map<String, Integer> m = config.coordinatorMinDistances;
        if (m.isEmpty()) return (a, b) -> 0;
        return (a, b) -> {
            Integer v = m.get(Math.min(a, b) + "," + Math.max(a, b));
            return v == null ? 0 : v;
        };
    }

    /** Priority from config: the structure id, then a tag ({@code #ns:path}) it is in, then its namespace; 0 otherwise. */
    private int priorityOf(Holder<Structure> structure) {
        Map<String, Integer> m = config.coordinatorPriorities;
        if (m.isEmpty()) return 0;
        String id = structure.unwrapKey().map(k -> k.identifier().toString()).orElse("");
        Integer v = m.get(id);
        if (v != null) return v;
        int best = Integer.MIN_VALUE;
        for (Map.Entry<String, Integer> e : m.entrySet()) {
            String k = e.getKey();
            if (k.startsWith("#")) {
                Identifier tag = Identifier.tryParse(k.substring(1));
                if (tag != null && structure.is(TagKey.create(Registries.STRUCTURE, tag))) best = Math.max(best, e.getValue());
            }
        }
        if (best != Integer.MIN_VALUE) return best;
        int colon = id.indexOf(':');
        v = colon > 0 ? m.get(id.substring(0, colon)) : null;
        return v == null ? 0 : v;
    }

    /** Footprint radius in blocks: the config override, a jigsaw structure's horizontal reach (16-128), or 48. */
    private int radiusOf(Holder<Structure> structure) {
        String id = structure.unwrapKey().map(k -> k.identifier().toString()).orElse("");
        Integer v = config.coordinatorFootprintOverrides.get(id);
        if (v != null) return v;
        if (structure.value() instanceof JigsawStructure jigsaw)
            return Math.clamp(((JigsawStructureAccessor) (Object) jigsaw).structopt$maxDistance().horizontal(), 16, 128);
        return 48;
    }

    /** For {@code /structopt coordinator status} and the GameTest: the sets of a dimension and their rank inputs. */
    public static List<String> describe(ResourceKey<Level> level, int limit) {
        Coordinator c = instance;
        List<String> out = new ArrayList<>();
        if (c == null) return out;
        LevelSets sets = c.levelSets(level);
        if (sets == null) return out;
        for (SetInfo s : sets.sets) {
            if (out.size() >= limit) break;
            out.add(s.id + " priority " + s.priority + ", spacing " + s.spacing + ", radius " + s.radius);
        }
        return out;
    }

    /**
     * For the GameTest: a coordinator over any generator (the GameTest world is flat, without structures). Its
     * {@code allowedIn} lists the live candidate starts in a square of chunks that it lets through.
     */
    public static Probe probe(StructOptConfig config, ChunkGeneratorStructureState state, ChunkGenerator generator, long seed) {
        Coordinator c = new Coordinator(null, config);
        return new Probe(c, c.new LevelSets(state, generator, seed));
    }

    public static final class Probe {
        private final Coordinator coordinator;
        private final LevelSets sets;

        private Probe(Coordinator coordinator, LevelSets sets) {
            this.coordinator = coordinator;
            this.sets = sets;
        }

        public int setCount() {
            return sets.sets.size();
        }

        public IntBinaryOperator minDistances() {
            return coordinator.minDistances();
        }

        /** Live candidates in the square that the coordinator lets through, and the count it dropped. */
        public List<CoordinatorRules.Candidate> allowedIn(int minX, int minZ, int size, int[] dropped) {
            List<CoordinatorRules.Candidate> out = new ArrayList<>();
            BiomeResolver biomes = sets.generator.getBiomeSource().createUncachedResolver(sets.state.randomState());
            for (SetInfo s : sets.sets) {
                if (!(s.set.value().placement() instanceof RandomSpreadStructurePlacement spread) || spread.spacing() <= 0) continue;
                int sp = spread.spacing();
                for (int rx = Math.floorDiv(minX, sp); rx <= Math.floorDiv(minX + size - 1, sp); rx++)
                    for (int rz = Math.floorDiv(minZ, sp); rz <= Math.floorDiv(minZ + size - 1, sp); rz++) {
                        ChunkPos p = spread.getPotentialStructureChunk(sets.seed, rx * sp, rz * sp);
                        if (p.x() < minX || p.z() < minZ || p.x() >= minX + size || p.z() >= minZ + size) continue;
                        if (!spread.isStructureChunk(sets.state, p.x(), p.z()) || !liveAt(s, p, biomes, sets.generator)) continue;
                        if (coordinator.decide(sets, s.set.value().structures().getFirst().structure(), p)) dropped[0]++;
                        else out.add(candidate(s, p, true));
                    }
            }
            return out;
        }
    }

    /** The minimum-distance rule in use (for the GameTest's overlap check). */
    public static IntBinaryOperator minDistanceRule() {
        Coordinator c = instance;
        return c == null ? (a, b) -> 0 : c.minDistances();
    }

    public static int setCount(ResourceKey<Level> level) {
        Coordinator c = instance;
        if (c == null) return 0;
        LevelSets sets = c.levelSets(level);
        return sets == null ? 0 : sets.sets.size();
    }
}
