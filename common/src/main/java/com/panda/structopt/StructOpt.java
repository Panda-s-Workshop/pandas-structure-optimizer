package com.panda.structopt;

import com.mojang.logging.LogUtils;
import com.panda.structopt.config.StructOptConfig;
import com.panda.structopt.coordinator.Coordinator;
import com.panda.structopt.coordinator.CoordinatorWorld;
import com.panda.structopt.height.Heights;
import com.panda.structopt.platform.Services;
import com.panda.structopt.start.Lookahead;
import com.panda.structopt.start.StartCache;
import com.panda.structopt.stats.Report;
import com.panda.structopt.stats.Stats;
import com.panda.structopt.template.CacheBuilder;
import com.panda.structopt.template.TemplateCache;
import net.minecraft.SharedConstants;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;

/** The mod's common side: the server lifecycle every feature hangs off. Loader code only forwards events here. */
public final class StructOpt {
    public static final String MODID = "pandas_structure_optimizer";
    public static final Logger LOGGER = LogUtils.getLogger();
    /** {@code -Dpandas_structure_optimizer.off=true}: no mixin is applied and nothing runs (the parity runs' "mod off"). */
    private static final boolean OFF = Boolean.getBoolean(MODID + ".off");
    private static final int MINUTE_TICKS = 20 * 60;
    private static boolean initialised;

    private StructOpt() {}

    public static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(MODID, path);
    }

    public static void init() {
        initialised = true;
    }

    public static boolean initialised() {
        return initialised;
    }

    public static boolean off() {
        return OFF;
    }

    /** Before the levels exist: settings, the template cache (spawn chunks already use it), start results, height cache, coordinator. */
    public static void serverStarting(MinecraftServer server) {
        StructOptConfig config = StructOptConfig.load(Services.PLATFORM.configDir());
        Stats.reset();
        if (OFF) {
            LOGGER.info("Panda's Structure Optimizer on {}: switched off for this run", Services.PLATFORM.loaderName());
            return;
        }
        if (config.templateCacheEnabled) {
            TemplateCache.open(Services.PLATFORM.gameDir(), SharedConstants.getCurrentVersion().dataVersion().version(),
                    Services.PLATFORM.modVersions(), config.templateCacheMaxGigabytes);
        }
        StartCache.open(server, config);
        Heights.configure(config);
        boolean coordinator = CoordinatorWorld.decide(server, config.coordinatorNewWorlds);
        Coordinator.open(server, config, coordinator);
        TemplateCache cache = TemplateCache.get();
        LOGGER.info("Panda's Structure Optimizer on {}: template cache {}, builder {}, lookahead {} thread(s), coordinator {}, C2ME {}",
                Services.PLATFORM.loaderName(), cache == null ? "off" : cache.folder(), config.templateCacheBuilder && cache != null ? "on" : "off",
                config.lookaheadEnabled ? config.lookaheadThreadCount() : 0, coordinator ? "on" : "off",
                Services.PLATFORM.isLoaded("c2me") ? "detected" : "not loaded");
    }

    /** Once the levels exist: the background builder and the lookahead start. */
    public static void serverStarted(MinecraftServer server) {
        if (OFF) return;
        StructOptConfig config = StructOptConfig.get();
        Report.indexSets(server);
        if (config.templateCacheBuilder) CacheBuilder.start(server);
        Lookahead.open(server, config);
    }

    public static void serverTick(MinecraftServer server) {
        if (OFF) return;
        Lookahead.tick(server);
        int tick = server.getTickCount();
        if (tick % MINUTE_TICKS == 0) {
            Report.checkSlow();
            int every = StructOptConfig.get().statsLogEveryMinutes;
            if (every > 0 && server.isDedicatedServer() && tick % (every * MINUTE_TICKS) == 0) Report.logSummary();
        }
    }

    public static void serverStopped(MinecraftServer server) {
        CacheBuilder.stop();
        Lookahead.close();
        StartCache.close();
        Coordinator.close();
        Heights.disable();
        TemplateCache.close();
    }
}
