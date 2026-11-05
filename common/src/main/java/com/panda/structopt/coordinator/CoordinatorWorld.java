package com.panda.structopt.coordinator;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.panda.structopt.StructOpt;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

/**
 * Whether the coordinator is on for a world. It is decided once, when the world is created (config {@code coordinatorNewWorlds}),
 * saved in the world's {@code data/} folder, and never switched silently afterwards: an existing world without the file (made
 * before the mod was added) gets "off".
 */
public final class CoordinatorWorld {
    public static final String FILE = StructOpt.MODID + "_coordinator.json";

    private CoordinatorWorld() {}

    public static Path file(MinecraftServer server) {
        return server.getWorldPath(LevelResource.ROOT).resolve("data").resolve(FILE);
    }

    /** Reads the world's choice, or makes it now: on for a new world when the config says so, off for any older world. */
    public static boolean decide(MinecraftServer server, boolean newWorlds) {
        Path file = file(server);
        if (Files.exists(file)) {
            try {
                JsonObject o = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
                return o.has("enabled") && o.get("enabled").getAsBoolean();
            } catch (IOException | RuntimeException e) {
                StructOpt.LOGGER.warn("Couldn't read {}; the coordinator stays off for this world", file);
                return false;
            }
        }
        boolean isNew = server.getWorldData().overworldData().getGameTime() == 0;
        boolean enabled = isNew && newWorlds;
        JsonObject o = new JsonObject();
        o.addProperty("enabled", enabled);
        o.addProperty("decided", isNew ? "when the world was created" : "on the first start with the mod (an existing world)");
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, o + "\n", StandardCharsets.UTF_8);
        } catch (IOException e) {
            StructOpt.LOGGER.warn("Couldn't write {}: {}", file, e.toString());
        }
        return enabled;
    }
}
