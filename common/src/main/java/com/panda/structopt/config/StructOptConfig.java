package com.panda.structopt.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;
import com.panda.structopt.StructOpt;
import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The server settings: {@code config/pandas_structure_optimizer.json}, the same file on both loaders. It is read when the server
 * starts, and written with every key and its default when it is missing or a key is new. Settings are not synced to clients.
 */
public final class StructOptConfig {
    public static final String FILE = StructOpt.MODID + ".json";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static volatile StructOptConfig current = new StructOptConfig();

    // 3.1, 3.2
    public boolean templateCacheEnabled = true;
    public double templateCacheMaxGigabytes = 4;
    public boolean templateCacheBuilder = true;
    public int templateCacheBuilderThreads = 1;
    // 3.3
    public boolean templateLoadsSeparate = true;
    // 3.4
    public boolean startCacheEnabled = true;
    public int startCacheMaxMegabytes = 64;
    // 3.5, 3.6
    public boolean lookaheadEnabled = true;
    public int lookaheadThreads = 0;
    public int lookaheadExtraChunks = 8;
    public int lookaheadCycleTicks = 10;
    public int lookaheadScanCapPerTick = 256;
    public int lookaheadQueue = 256;
    public boolean lookaheadPrefetchTemplates = true;
    // 3.7
    public boolean heightCacheEnabled = true;
    public int heightCacheEntriesPerDimension = 262_144;
    public List<String> heightCacheGenerators = List.of("minecraft:noise");
    // 3.8
    public boolean spawnProbesSkipUnfinishedChunks = true;
    // 3.9, 3.10
    public int statsLogEveryMinutes = 5;
    public boolean warningsSlowStructures = true;
    // 3.13
    public boolean coordinatorNewWorlds = false;
    public Map<String, Integer> coordinatorPriorities = new LinkedHashMap<>();
    public Map<String, Integer> coordinatorFootprintOverrides = new LinkedHashMap<>();
    public Map<String, Integer> coordinatorMinDistances = new LinkedHashMap<>();

    public static StructOptConfig get() {
        return current;
    }

    /** For tests: replace the settings in use. */
    public static void set(StructOptConfig config) {
        current = config;
    }

    /** The number of lookahead threads in use: the setting, or a quarter of the cores (at least 1) when it is 0. */
    public int lookaheadThreadCount() {
        return lookaheadThreads > 0 ? lookaheadThreads : Math.max(1, Runtime.getRuntime().availableProcessors() / 4);
    }

    /** Reads the file (writing the defaults for anything missing) and makes it the settings in use. */
    public static StructOptConfig load(Path configDir) {
        Path file = configDir.resolve(FILE);
        StructOptConfig config = new StructOptConfig();
        JsonObject read = new JsonObject();
        if (Files.exists(file)) {
            try {
                read = parse(Files.readString(file, StandardCharsets.UTF_8));
                config = fromJson(read);
            } catch (IOException | RuntimeException e) {
                StructOpt.LOGGER.warn("Couldn't read {}, using the defaults: {}", file, e.toString());
                read = null;
            }
        }
        config.clamp();
        JsonObject full = config.toJson();
        if (read != null && !full.keySet().equals(read.keySet())) {
            try {
                Files.createDirectories(configDir);
                Files.writeString(file, GSON.toJson(full) + "\n", StandardCharsets.UTF_8);
            } catch (IOException e) {
                StructOpt.LOGGER.warn("Couldn't write {}: {}", file, e.toString());
            }
        }
        current = config;
        return config;
    }

    static JsonObject parse(String text) throws IOException {
        try (Reader r = new StringReader(text)) {
            JsonReader reader = new JsonReader(r);
            reader.setStrictness(Strictness.LENIENT);
            JsonElement e = JsonParser.parseReader(reader);
            return e.isJsonObject() ? e.getAsJsonObject() : new JsonObject();
        }
    }

    public static StructOptConfig fromJson(JsonObject json) {
        StructOptConfig c = GSON.fromJson(json, StructOptConfig.class);
        return c == null ? new StructOptConfig() : c;
    }

    public JsonObject toJson() {
        return GSON.toJsonTree(this).getAsJsonObject();
    }

    /** Keeps every number in its sensible range. */
    public void clamp() {
        templateCacheMaxGigabytes = Math.clamp(templateCacheMaxGigabytes, 0.1, 1024);
        templateCacheBuilderThreads = Math.clamp(templateCacheBuilderThreads, 1, 8);
        startCacheMaxMegabytes = Math.clamp(startCacheMaxMegabytes, 1, 4096);
        lookaheadThreads = Math.clamp(lookaheadThreads, 0, 64);
        lookaheadExtraChunks = Math.clamp(lookaheadExtraChunks, 0, 32);
        lookaheadCycleTicks = Math.clamp(lookaheadCycleTicks, 1, 200);
        lookaheadScanCapPerTick = Math.clamp(lookaheadScanCapPerTick, 1, 100_000);
        lookaheadQueue = Math.clamp(lookaheadQueue, 1, 100_000);
        heightCacheEntriesPerDimension = Math.clamp(heightCacheEntriesPerDimension, 1024, 1 << 24);
        statsLogEveryMinutes = Math.clamp(statsLogEveryMinutes, 0, 1440);
        if (heightCacheGenerators == null) heightCacheGenerators = List.of("minecraft:noise");
        if (coordinatorPriorities == null) coordinatorPriorities = new LinkedHashMap<>();
        if (coordinatorFootprintOverrides == null) coordinatorFootprintOverrides = new LinkedHashMap<>();
        if (coordinatorMinDistances == null) coordinatorMinDistances = new LinkedHashMap<>();
    }
}
