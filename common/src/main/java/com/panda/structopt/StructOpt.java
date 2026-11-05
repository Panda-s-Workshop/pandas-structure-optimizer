package com.panda.structopt;

import com.mojang.logging.LogUtils;
import com.panda.structopt.platform.Services;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;

/** The mod's common side. Each loader module has its own entry point, which calls {@link #init()} first. */
public final class StructOpt {
    public static final String MODID = "pandas_structure_optimizer";
    public static final Logger LOGGER = LogUtils.getLogger();

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

    /** The server is about to start: the registries and data packs are loaded. */
    public static void serverStarting(MinecraftServer server) {
        LOGGER.info("Panda's Structure Optimizer on {}", Services.PLATFORM.loaderName());
    }

    /** The server has stopped. */
    public static void serverStopped(MinecraftServer server) {
    }
}
