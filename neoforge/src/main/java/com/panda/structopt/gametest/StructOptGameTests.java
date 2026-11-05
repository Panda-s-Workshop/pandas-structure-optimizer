package com.panda.structopt.gametest;

import com.panda.structopt.StructOpt;
import com.panda.structopt.Texts;
import com.panda.structopt.platform.Services;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.FunctionGameTestInstance;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestData;
import net.minecraft.gametest.framework.TestEnvironmentDefinition;
import net.minecraft.locale.Language;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.registries.DeferredRegister;

/** Checks that run on a GameTest server. */
public final class StructOptGameTests {
    private static final DeferredRegister<Consumer<GameTestHelper>> FUNCTIONS =
            DeferredRegister.create(Registries.TEST_FUNCTION, StructOpt.MODID);
    private static final Identifier ARENA = StructOpt.id("test_arena");

    static {
        FUNCTIONS.register("mod_loaded", () -> StructOptGameTests::modLoaded);
        FUNCTIONS.register("lang_coverage", () -> StructOptGameTests::langCoverage);
        FUNCTIONS.register("template_cache_roundtrip", () -> FeatureTests::templateCacheRoundtrip);
        FUNCTIONS.register("cache_builder_fills", () -> FeatureTests::cacheBuilderFills);
        FUNCTIONS.register("template_parallel_loads", () -> FeatureTests::templateParallelLoads);
        FUNCTIONS.register("start_cache_copy", () -> FeatureTests::startCacheCopy);
        FUNCTIONS.register("template_prefetch", () -> FeatureTests::templatePrefetch);
        FUNCTIONS.register("lookahead_moving_player", () -> FeatureTests::lookaheadMovingPlayer);
        FUNCTIONS.register("height_cache_equivalence", () -> FeatureTests::heightCacheEquivalence);
        FUNCTIONS.register("vanilla_terrain_check", () -> FeatureTests::vanillaTerrainCheck);
        FUNCTIONS.register("spawn_probe_skips", () -> FeatureTests::spawnProbeSkips);
        FUNCTIONS.register("stats_command", () -> FeatureTests::statsCommand);
        FUNCTIONS.register("cache_commands", () -> FeatureTests::cacheCommands);
        FUNCTIONS.register("config_defaults", () -> FeatureTests::configDefaults);
        FUNCTIONS.register("coordinator_no_overlap", () -> FeatureTests::coordinatorNoOverlap);
    }

    private StructOptGameTests() {}

    public static void register(IEventBus modBus) {
        FUNCTIONS.register(modBus);
        modBus.addListener(StructOptGameTests::registerTests);
    }

    private static void registerTests(RegisterGameTestsEvent event) {
        Holder<TestEnvironmentDefinition<?>> env = event.registerEnvironment(StructOpt.id("default"));
        // -Dpandas_structure_optimizer.tests=a,b_*: only the tests named (each a name, * for any run of letters)
        String only = System.getProperty(StructOpt.MODID + ".tests", "");
        List<Pattern> wanted = Arrays.stream(only.split(",")).map(String::trim).filter(n -> !n.isEmpty())
                .map(n -> Pattern.compile(n.replaceAll("[^a-z0-9_*]", "").replace("*", "[a-z0-9_]*"))).toList();
        for (var entry : FUNCTIONS.getEntries()) {
            String path = entry.getId().getPath();
            if (!wanted.isEmpty() && wanted.stream().noneMatch(w -> w.matcher(path).matches())) continue;
            ResourceKey<Consumer<GameTestHelper>> key = ResourceKey.create(Registries.TEST_FUNCTION, entry.getId());
            event.registerTest(key.identifier(), new FunctionGameTestInstance(key, new TestData<>(env, ARENA, 100, 0, true)));
        }
    }

    /** The common side ran and the loader's platform service is found. */
    private static void modLoaded(GameTestHelper helper) {
        if (!StructOpt.initialised()) helper.fail("StructOpt.init() never ran");
        String loader = Services.PLATFORM.loaderName();
        if (!"neoforge".equals(loader)) helper.fail("the platform service is " + loader);
        if (!Services.PLATFORM.isLoaded(StructOpt.MODID)) helper.fail("the mod list doesn't contain " + StructOpt.MODID);
        helper.succeed();
    }

    /** Every translation key the mod uses has its English text. */
    private static void langCoverage(GameTestHelper helper) {
        Language lang = Language.getInstance();
        List<String> missing = Texts.ALL.stream().filter(key -> !lang.has(key)).toList();
        if (!missing.isEmpty()) helper.fail("no English text for " + missing);
        helper.succeed();
    }
}
