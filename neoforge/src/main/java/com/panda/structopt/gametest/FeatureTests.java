package com.panda.structopt.gametest;

import com.google.gson.JsonObject;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.panda.structopt.StructOpt;
import com.panda.structopt.config.StructOptConfig;
import com.panda.structopt.coordinator.Coordinator;
import com.panda.structopt.coordinator.CoordinatorRules;
import com.panda.structopt.height.Heights;
import com.panda.structopt.height.VanillaTerrain;
import com.panda.structopt.mixin.StructureTemplateManagerAccessor;
import com.panda.structopt.mixin.TemplateSourceAccessor;
import com.panda.structopt.spawn.SpawnProbes;
import com.panda.structopt.start.Lookahead;
import com.panda.structopt.start.StartCache;
import com.panda.structopt.stats.Stats;
import com.panda.structopt.template.CacheBuilder;
import com.panda.structopt.template.TemplateCache;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.TreeMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.SharedConstants;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.resources.FileToIdConverter;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.biome.MultiNoiseBiomeSource;
import net.minecraft.world.level.biome.MultiNoiseBiomeSourceParameterLists;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.NoiseRouter;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.densityfunction.DensityFunction;
import net.minecraft.world.level.levelgen.structure.BuiltinStructures;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.pieces.PiecesContainer;
import net.minecraft.world.level.levelgen.structure.pieces.StructurePieceSerializationContext;
import net.minecraft.world.level.levelgen.structure.structures.BuriedTreasurePieces;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import net.minecraft.world.level.levelgen.structure.templatesystem.loader.ResourceManagerTemplateSource;

/** The GameTests of the optimizations. Each runs at once and passes or fails with a message. */
final class FeatureTests {
    private static final FileToIdConverter FILES = new FileToIdConverter("structure", ".nbt");

    private FeatureTests() {}

    // ---- 3.1 -------------------------------------------------------------------------------------------------------------

    /**
     * A vanilla load and a cached load give the same template ({@code StructureTemplate.save} output), for a template saved at an
     * older data version (the fixers change it) and one at the current version, from vanilla's {@code structure/} folder and
     * from this mod's own namespace.
     */
    static void templateCacheRoundtrip(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        TemplateCache cache = need(helper, TemplateCache.get(), "the template cache is off");
        ResourceManager resources = server.getResourceManager();
        ResourceManagerTemplateSource source = ((StructureTemplateManagerAccessor) server.getStructureTemplateManager()).structopt$resourceManagerSource();
        int current = SharedConstants.getCurrentVersion().dataVersion().version();
        Identifier old = null, now = null;
        for (Map.Entry<Identifier, Resource> e : new TreeMap<>(FILES.listMatchingResources(resources)).entrySet()) {
            if (old != null && now != null) break;
            int version = NbtUtils.getDataVersion(read(helper, resources, e.getKey()), 500);
            if (version < current && old == null) old = FILES.fileToId(e.getKey());
            if (version >= current && now == null && e.getKey().getNamespace().equals("minecraft")) now = FILES.fileToId(e.getKey());
        }
        List<Identifier> ids = new ArrayList<>(List.of(StructOpt.id("test_arena")));
        if (old != null && !ids.contains(old)) ids.add(old);
        if (now != null) ids.add(now);
        if (old == null) helper.fail("no template with an older data version was found to compare (current " + current + ")");
        for (Identifier id : ids) {
            CompoundTag vanilla = ((TemplateSourceAccessor) source).structopt$readStructure(read(helper, resources, FILES.idToFile(id)))
                    .save(new CompoundTag());
            StructureTemplate first = TemplateCache.load(id, FILES.idToFile(id), resources, source).orElseThrow();
            cache.flush();
            long hits = Stats.TEMPLATE_HITS.sum();
            StructureTemplate second = TemplateCache.load(id, FILES.idToFile(id), resources, source).orElseThrow();
            if (Stats.TEMPLATE_HITS.sum() != hits + 1) helper.fail("the second load of " + id + " didn't come from the disk cache");
            if (!vanilla.equals(first.save(new CompoundTag()))) helper.fail("first load of " + id + " differs from vanilla");
            if (!vanilla.equals(second.save(new CompoundTag()))) helper.fail("cached load of " + id + " differs from vanilla");
        }
        StructOpt.LOGGER.info("[structopt test] template_cache_roundtrip compared {} (older: {}, current {})", ids, old, current);
        helper.succeed();
    }

    // ---- 3.2 -------------------------------------------------------------------------------------------------------------

    /**
     * The builder's upgrade step leaves every template it is given in the disk cache, and its upgrades count as the builder's,
     * never as the game's own misses. (The count is only exercised when the cache is cold, as on CI: a warm cache has nothing
     * left to upgrade for these twelve.)
     */
    static void cacheBuilderFills(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        TemplateCache cache = need(helper, TemplateCache.get(), "the template cache is off");
        ResourceManager resources = server.getResourceManager();
        ResourceManagerTemplateSource source = ((StructureTemplateManagerAccessor) server.getStructureTemplateManager()).structopt$resourceManagerSource();
        List<Identifier> files = new ArrayList<>(new TreeMap<>(FILES.listMatchingResources(resources)).keySet()).subList(0, 12);
        long misses = Stats.TEMPLATE_MISSES.sum(), builder = Stats.BUILDER_UPGRADED.sum();
        int upgraded = 0;
        for (Identifier file : files) if (cache.warm(FILES.fileToId(file), file, resources, source)) upgraded++;
        cache.flush();
        if (Stats.TEMPLATE_MISSES.sum() != misses) helper.fail("the builder's upgrades were counted as the game's misses");
        if (Stats.BUILDER_UPGRADED.sum() - builder < upgraded) helper.fail(upgraded + " upgrades, but the builder count grew by " + (Stats.BUILDER_UPGRADED.sum() - builder));
        for (Identifier file : files) {
            byte[] bytes = bytes(helper, resources, file);
            if (!cache.contains(FILES.fileToId(file), bytes)) helper.fail(FILES.fileToId(file) + " is not in the cache after the builder step");
        }
        helper.succeed();
    }

    // ---- 3.3 -------------------------------------------------------------------------------------------------------------

    /** 16 threads asking for the same unloaded template share one load and get the same template. */
    static void templateParallelLoads(GameTestHelper helper) {
        StructureTemplateManager manager = helper.getLevel().getServer().getStructureTemplateManager();
        Identifier id = Identifier.withDefaultNamespace("igloo/top");
        manager.remove(id);
        long loads = Stats.TEMPLATE_LOADS.sum();
        ExecutorService pool = Executors.newFixedThreadPool(16);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Optional<StructureTemplate>>> results = new ArrayList<>();
        try {
            for (int i = 0; i < 16; i++) results.add(pool.submit(() -> {
                go.await();
                return manager.get(id);
            }));
            go.countDown();
            StructureTemplate first = null;
            for (Future<Optional<StructureTemplate>> f : results) {
                StructureTemplate t = f.get(30, TimeUnit.SECONDS).orElse(null);
                if (t == null) helper.fail(id + " didn't load");
                if (first == null) first = t;
                else if (t != first) helper.fail("two threads got different template objects for " + id);
            }
        } catch (Exception e) {
            helper.fail("parallel loads failed: " + e);
        } finally {
            pool.shutdownNow();
        }
        long ran = Stats.TEMPLATE_LOADS.sum() - loads;
        if (ran != 1) helper.fail("expected one load for 16 parallel requests, saw " + ran);
        helper.succeed();
    }

    // ---- 3.4 -------------------------------------------------------------------------------------------------------------

    /** A kept start comes back as a new object with equal NBT; references never leak between copies; failures are kept too. */
    static void startCacheCopy(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        if (!StartCache.active()) helper.fail("the start cache is off");
        RegistryAccess registries = level.registryAccess();
        Holder<Structure> holder = registries.lookupOrThrow(Registries.STRUCTURE).getOrThrow(BuiltinStructures.BURIED_TREASURE);
        ChunkPos pos = new ChunkPos(900_001, 900_003);
        StructureStart made = new StructureStart(holder.value(), pos, 0,
                new PiecesContainer(List.of(new BuriedTreasurePieces.BuriedTreasurePiece(pos.getMiddleBlockPosition(40)))));
        AtomicInteger computed = new AtomicInteger();
        var gen = level.getChunkSource().getGenerator();
        var randomState = level.getChunkSource().randomState();
        var templates = level.getServer().getStructureTemplateManager();
        long seed = level.getSeed();
        StructureStart a = StartCache.generate(holder, level.dimension(), registries, gen, randomState, templates, seed, pos, 0, level, () -> {
            computed.incrementAndGet();
            return made;
        });
        StructureStart b = StartCache.generate(holder, level.dimension(), registries, gen, randomState, templates, seed, pos, 0, level, () -> {
            computed.incrementAndGet();
            return made;
        });
        if (computed.get() != 1) helper.fail("computed " + computed.get() + " times, expected once");
        if (a != made) helper.fail("the first caller should get the start it computed");
        if (b == a) helper.fail("the second caller got the same object");
        StructurePieceSerializationContext context = StructurePieceSerializationContext.fromLevel(level);
        if (!a.createTag(context, pos).equals(b.createTag(context, pos))) helper.fail("the copy's NBT differs");
        b.addReference();
        StructureStart c = StartCache.generate(holder, level.dimension(), registries, gen, randomState, templates, seed, pos, 0, level, () -> made);
        if (c.getReferences() != 0 || a.getReferences() != 0) helper.fail("a reference added to one copy reached another");
        ChunkPos empty = new ChunkPos(900_011, 900_013);
        AtomicInteger failures = new AtomicInteger();
        for (int i = 0; i < 2; i++) {
            StructureStart f = StartCache.generate(holder, level.dimension(), registries, gen, randomState, templates, seed, empty, 0, level, () -> {
                failures.incrementAndGet();
                return StructureStart.INVALID_START;
            });
            if (f.isValid()) helper.fail("a kept failure came back valid");
        }
        if (failures.get() != 1) helper.fail("a failure was computed " + failures.get() + " times");
        helper.succeed();
    }

    // ---- 3.7 -------------------------------------------------------------------------------------------------------------

    /** 10,000 random columns of a real overworld noise generator: cached answers equal uncached ones, for every heightmap type. */
    static void heightCacheEquivalence(GameTestHelper helper) {
        RegistryAccess registries = helper.getLevel().registryAccess();
        var settings = registries.lookupOrThrow(Registries.NOISE_SETTINGS).getOrThrow(NoiseGeneratorSettings.OVERWORLD);
        var biomes = MultiNoiseBiomeSource.createFromPreset(registries.lookupOrThrow(Registries.MULTI_NOISE_BIOME_SOURCE_PARAMETER_LIST)
                .getOrThrow(MultiNoiseBiomeSourceParameterLists.OVERWORLD));
        NoiseBasedChunkGenerator generator = new NoiseBasedChunkGenerator(biomes, settings);
        RandomState randomState = RandomState.create(registries.lookupOrThrow(Registries.NOISE), 20261105L, settings.value());
        LevelHeightAccessor height = LevelHeightAccessor.create(-64, 384);
        Heightmap.Types[] types = Heightmap.Types.values();
        Random random = new Random(1234);
        long hits = Stats.HEIGHT_HITS.sum();
        int[] uncached = new int[1];
        for (int i = 0; i < 10_000; i++) {
            int x = random.nextInt(200_000) - 100_000, z = random.nextInt(200_000) - 100_000;
            Heightmap.Types type = types[i % types.length];
            Heights.bypass(() -> uncached[0] = generator.getBaseHeight(x, z, type, height, randomState));
            int first = generator.getBaseHeight(x, z, type, height, randomState);
            int second = generator.getBaseHeight(x, z, type, height, randomState);
            if (first != uncached[0] || second != uncached[0])
                helper.fail("column " + x + "," + z + " " + type + ": uncached " + uncached[0] + ", cached " + first + "/" + second);
        }
        long gained = Stats.HEIGHT_HITS.sum() - hits;
        if (gained < 9_000) helper.fail("the height cache answered only " + gained + " of 10,000 repeated questions");
        helper.succeed();
    }

    // ---- 3.5 -------------------------------------------------------------------------------------------------------------

    /**
     * A player moved by its client: the server only receives positions, which the packet listener records as the known
     * movement, while the server-side velocity stays zero. The lookahead must scan ahead of that player.
     */
    static void lookaheadMovingPlayer(GameTestHelper helper) {
        if (!Lookahead.active()) helper.fail("the lookahead is off");
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        if (helper.getLevel().getServer().getPlayerList().getPlayer(player.getUUID()) == null) helper.fail("the test player isn't in the player list");
        Vec3 step = new Vec3(0.55, 0, 0.1);
        long before = Stats.AHEAD_PLAYER_SCANS.sum();
        int[] ticks = {0};
        // the test times out at 100 ticks: the player goes before that either way, so a failed run can't leave a mover behind
        helper.onEachTick(() -> {
            if (++ticks[0] >= 95) {
                removePlayer(helper, player);
                return;
            }
            player.setDeltaMovement(Vec3.ZERO);
            player.setKnownMovement(step);
        });
        helper.succeedWhen(() -> {
            helper.assertTrue(player.getDeltaMovement().horizontalDistance() < Lookahead.MIN_SPEED, "the test player has a server-side velocity");
            helper.assertTrue(Lookahead.movement(player).horizontalDistance() >= Lookahead.MIN_SPEED, "the lookahead doesn't see the player move");
            helper.assertTrue(Stats.AHEAD_PLAYER_SCANS.sum() > before, "no scan started for the moving player");
            removePlayer(helper, player);
        });
    }

    private static void removePlayer(GameTestHelper helper, ServerPlayer player) {
        var list = helper.getLevel().getServer().getPlayerList();
        if (list.getPlayer(player.getUUID()) != null) list.remove(player);
    }

    /**
     * Starts are computed ahead, and heights cached, only on terrain built from Minecraft's own density functions: the vanilla
     * overworld router and the flat test world pass; the same router with one mod-made density function swapped in does not.
     */
    static void vanillaTerrainCheck(GameTestHelper helper) {
        RegistryAccess registries = helper.getLevel().registryAccess();
        var settings = registries.lookupOrThrow(Registries.NOISE_SETTINGS).getOrThrow(NoiseGeneratorSettings.OVERWORLD);
        var biomes = MultiNoiseBiomeSource.createFromPreset(registries.lookupOrThrow(Registries.MULTI_NOISE_BIOME_SOURCE_PARAMETER_LIST)
                .getOrThrow(MultiNoiseBiomeSourceParameterLists.OVERWORLD));
        NoiseBasedChunkGenerator vanilla = new NoiseBasedChunkGenerator(biomes, settings);
        if (!VanillaTerrain.is(vanilla)) helper.fail("the vanilla overworld counts as modded terrain");
        if (!VanillaTerrain.is(helper.getLevel().getChunkSource().getGenerator())) helper.fail("the flat test world counts as modded terrain");
        NoiseRouter router = settings.value().noiseRouter();
        DensityFunction foreign = new ForeignDensity(router.finalDensity());
        try {
            NoiseRouter modded = withComponent(router, "finalDensity", foreign);
            NoiseGeneratorSettings moddedSettings = withComponent(settings.value(), "noiseRouter", modded);
            NoiseBasedChunkGenerator generator = new NoiseBasedChunkGenerator(biomes, Holder.direct(moddedSettings));
            if (VanillaTerrain.is(generator)) helper.fail("a router with a mod-made density function counts as vanilla");
            if (Heights.allowed(generator)) helper.fail("the height cache would serve modded terrain");
        } catch (ReflectiveOperationException e) {
            helper.fail("couldn't build the modded router: " + e);
        }
        helper.succeed();
    }

    /** A copy of a record with one component replaced, through its canonical constructor. */
    @SuppressWarnings("unchecked")
    private static <R extends Record> R withComponent(R record, String name, Object value) throws ReflectiveOperationException {
        var components = record.getClass().getRecordComponents();
        Class<?>[] types = new Class<?>[components.length];
        Object[] args = new Object[components.length];
        boolean found = false;
        for (int i = 0; i < components.length; i++) {
            types[i] = components[i].getType();
            boolean match = components[i].getName().equals(name);
            found |= match;
            args[i] = match ? value : components[i].getAccessor().invoke(record);
        }
        if (!found) throw new NoSuchFieldException(name);
        return (R) record.getClass().getDeclaredConstructor(types).newInstance(args);
    }

    /** Stands for a terrain mod's own density function type: it wraps a vanilla one, but its class is not Minecraft's. */
    private record ForeignDensity(DensityFunction inner) implements DensityFunction {
        @Override
        public net.minecraft.world.level.levelgen.densityfunction.DensitySampler compileSampler(DensityFunction.CompileContext context) {
            return inner.compileSampler(context);
        }

        @Override
        public DensityFunction rewriteChildren(net.minecraft.world.level.levelgen.densityfunction.DfRewriteRule rule) {
            return new ForeignDensity(rule.rewrite(inner));
        }

        @Override
        public net.minecraft.util.Interval range() {
            return inner.range();
        }

        @Override
        public int domainAxes() {
            return inner.domainAxes();
        }

        @Override
        public com.mojang.serialization.MapCodec<? extends DensityFunction> codec() {
            return inner.codec();
        }
    }

    // ---- 3.8 -------------------------------------------------------------------------------------------------------------

    /** During the custom spawners, a chunk that is scheduled but not generated reads as absent, at once; a full one reads as present. */
    static void spawnProbeSkips(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ServerChunkCache chunks = level.getChunkSource();
        int x = 60_000, z = 60_017;
        level.setChunkForced(x, z, true);
        try {
            BlockPos here = helper.absolutePos(BlockPos.ZERO);
            SpawnProbes.begin();
            long t0 = System.nanoTime();
            boolean far, near;
            try {
                far = chunks.hasChunk(x, z);
                near = chunks.hasChunk(here.getX() >> 4, here.getZ() >> 4);
            } finally {
                SpawnProbes.end();
            }
            long ms = (System.nanoTime() - t0) / 1_000_000;
            if (far && chunks.getChunkNow(x, z) == null) helper.fail("an unfinished chunk read as present");
            if (!near) helper.fail("the test's own full chunk read as absent");
            if (ms > 100) helper.fail("the probes took " + ms + " ms");
        } finally {
            level.setChunkForced(x, z, false);
        }
        helper.succeed();
    }

    // ---- 3.9, 3.12 -----------------------------------------------------------------------------------------------------------

    static void statsCommand(GameTestHelper helper) {
        int lines = run(helper, "structopt stats");
        if (lines < 10) helper.fail("/structopt stats printed " + lines + " lines");
        helper.succeed();
    }

    static void cacheCommands(GameTestHelper helper) {
        TemplateCache cache = need(helper, TemplateCache.get(), "the template cache is off");
        if (run(helper, "structopt cache clear") != 1) helper.fail("cache clear failed");
        if (!cache.clearRequested()) helper.fail("cache clear didn't mark the folder");
        try {
            cache.cancelClear();
        } catch (IOException e) {
            helper.fail("couldn't take the clear back: " + e);
        }
        if (run(helper, "structopt cache rebuild") != 1) helper.fail("cache rebuild failed");
        if (!CacheBuilder.running() && !"done".equals(Stats.builderState)) helper.fail("the builder isn't running after rebuild: " + Stats.builderState);
        helper.succeed();
    }

    // ---- 3.11 ----------------------------------------------------------------------------------------------------------------

    static void configDefaults(GameTestHelper helper) {
        StructOptConfig before = StructOptConfig.get();
        try {
            StructOptConfig d = new StructOptConfig();
            if (!d.templateCacheEnabled || d.templateCacheMaxGigabytes != 4 || !d.templateCacheBuilder || d.templateCacheBuilderThreads != 1 || d.templateCacheBuilderMaxCpu != 0.75
                    || !d.templateLoadsSeparate || !d.startCacheEnabled || d.startCacheMaxMegabytes != 64 || !d.lookaheadEnabled
                    || d.lookaheadThreads != 0 || d.lookaheadExtraChunks != 8 || d.lookaheadCycleTicks != 10 || d.lookaheadScanCapPerTick != 256
                    || d.lookaheadQueue != 256 || !d.lookaheadPrefetchTemplates || !d.heightCacheEnabled
                    || d.heightCacheEntriesPerDimension != 262_144 || !d.heightCacheGenerators.equals(List.of("minecraft:noise"))
                    || !d.spawnProbesSkipUnfinishedChunks || d.statsLogEveryMinutes != 5 || !d.warningsSlowStructures
                    || d.coordinatorNewWorlds || !d.coordinatorPriorities.isEmpty()) helper.fail("a default differs from the design: " + d.toJson());
            Path dir = Files.createTempDirectory("structopt-config");
            StructOptConfig.load(dir);
            JsonObject written = StructOptConfig.fromJson(com.google.gson.JsonParser.parseString(
                    Files.readString(dir.resolve(StructOptConfig.FILE), StandardCharsets.UTF_8)).getAsJsonObject()).toJson();
            if (!written.equals(d.toJson())) helper.fail("the written file isn't the defaults: " + written);
            Files.writeString(dir.resolve(StructOptConfig.FILE), "{\"lookaheadCycleTicks\": 0, \"startCacheMaxMegabytes\": 32}", StandardCharsets.UTF_8);
            StructOptConfig partial = StructOptConfig.load(dir);
            if (partial.lookaheadCycleTicks != 1) helper.fail("an out-of-range value wasn't clamped");
            if (partial.startCacheMaxMegabytes != 32 || !partial.templateCacheEnabled) helper.fail("a partial file lost a value or a default");
            if (!Files.readString(dir.resolve(StructOptConfig.FILE), StandardCharsets.UTF_8).contains("heightCacheGenerators"))
                helper.fail("missing keys weren't written back");
        } catch (IOException e) {
            helper.fail("config file: " + e);
        } finally {
            StructOptConfig.set(before);
        }
        helper.succeed();
    }

    // ---- 3.13 ----------------------------------------------------------------------------------------------------------------

    /**
     * On a real overworld generator (vanilla's structure sets), the starts the coordinator lets through in a 256×256-chunk square
     * never conflict with each other, and it does drop some.
     */
    static void coordinatorNoOverlap(GameTestHelper helper) {
        RegistryAccess registries = helper.getLevel().registryAccess();
        var settings = registries.lookupOrThrow(Registries.NOISE_SETTINGS).getOrThrow(NoiseGeneratorSettings.OVERWORLD);
        var biomes = MultiNoiseBiomeSource.createFromPreset(registries.lookupOrThrow(Registries.MULTI_NOISE_BIOME_SOURCE_PARAMETER_LIST)
                .getOrThrow(MultiNoiseBiomeSourceParameterLists.OVERWORLD));
        NoiseBasedChunkGenerator generator = new NoiseBasedChunkGenerator(biomes, settings);
        long seed = 20261105L;
        RandomState randomState = RandomState.create(registries.lookupOrThrow(Registries.NOISE), seed, settings.value());
        ChunkGeneratorStructureState state = generator.createState(registries.lookupOrThrow(Registries.STRUCTURE_SET), randomState, seed);
        Coordinator.Probe probe = Coordinator.probe(new StructOptConfig(), state, generator, seed);
        if (probe.setCount() < 5) helper.fail("only " + probe.setCount() + " structure sets on the overworld generator");
        int[] dropped = new int[1];
        List<CoordinatorRules.Candidate> allowed = probe.allowedIn(-128, -128, 256, dropped);
        for (int i = 0; i < allowed.size(); i++)
            for (int j = i + 1; j < allowed.size(); j++)
                if (CoordinatorRules.conflict(allowed.get(i), allowed.get(j), probe.minDistances()))
                    helper.fail(allowed.get(i) + " overlaps " + allowed.get(j));
        if (allowed.isEmpty()) helper.fail("no structure candidates in the square");
        if (dropped[0] == 0) helper.fail("the coordinator dropped nothing in 65,536 chunks; the test proves nothing");
        StructOpt.LOGGER.info("[structopt test] coordinator_no_overlap: {} sets, {} starts kept, {} dropped", probe.setCount(), allowed.size(), dropped[0]);
        helper.succeed();
    }

    // ---- helpers ---------------------------------------------------------------------------------------------------------

    private static int run(GameTestHelper helper, String command) {
        MinecraftServer server = helper.getLevel().getServer();
        CommandSourceStack source = server.createCommandSourceStack().withSuppressedOutput();
        try {
            return server.getCommands().getDispatcher().execute(command, source);
        } catch (CommandSyntaxException e) {
            helper.fail("/" + command + ": " + e.getMessage());
            return 0;
        }
    }

    private static <T> T need(GameTestHelper helper, T value, String message) {
        if (value == null) helper.fail(message);
        return value;
    }

    private static byte[] bytes(GameTestHelper helper, ResourceManager resources, Identifier file) {
        try (InputStream in = resources.open(file)) {
            return in.readAllBytes();
        } catch (IOException e) {
            helper.fail("can't read " + file + ": " + e);
            return new byte[0];
        }
    }

    private static CompoundTag read(GameTestHelper helper, ResourceManager resources, Identifier file) {
        try {
            return NbtIo.readCompressed(new ByteArrayInputStream(bytes(helper, resources, file)), NbtAccounter.unlimitedHeap());
        } catch (IOException e) {
            helper.fail("can't parse " + file + ": " + e);
            return new CompoundTag();
        }
    }
}
