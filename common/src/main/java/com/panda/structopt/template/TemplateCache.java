package com.panda.structopt.template;

import com.panda.structopt.StructOpt;
import com.panda.structopt.mixin.TemplateSourceAccessor;
import com.panda.structopt.stats.Stats;
import com.panda.structopt.util.SingleFlight;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.level.levelgen.structure.templatesystem.loader.TemplateSource;
import org.jspecify.annotations.Nullable;

/**
 * 3.1, the persistent upgraded-template cache. The first time a template file from the resource manager loads, its NBT after every
 * data fixer (vanilla's, plus any mod's pass wrapped around {@code DataFixTypes}) is written to
 * {@code <game dir>/.cache/pandas_structure_optimizer/templates/<fingerprint>/}. Later loads read that copy and skip the fixers.
 * <p>
 * The source still finds the file the vanilla way ({@code ResourceManager.open} on the source's own path); the cache only sees its
 * bytes. A miss goes through vanilla's {@code readStructure}, and {@link Capture} takes the tag the fixers returned.
 */
public final class TemplateCache {
    public static final String ROOT = ".cache/" + StructOpt.MODID + "/templates";
    private static final String INDEX = "index.tsv";
    private static final String SESSIONS = "sessions.tsv";
    private static final String CLEAR_MARKER = "clear-on-next-start";
    private static volatile @Nullable TemplateCache instance;

    private final Path folder;
    private final String fingerprint;
    private final CacheIndex index;
    private final long capBytes;
    private final Set<String> usedThisSession = ConcurrentHashMap.newKeySet();
    private final SingleFlight<String, Result> flight = new SingleFlight<>();
    private final ExecutorService io = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "StructOpt template cache writer");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        return t;
    });
    private final Object indexLock = new Object();

    private record Result(StructureTemplate template, CompoundTag fixed) {}

    private TemplateCache(Path folder, String fingerprint, CacheIndex index, long capBytes) {
        this.folder = folder;
        this.fingerprint = fingerprint;
        this.index = index;
        this.capBytes = capBytes;
    }

    public static @Nullable TemplateCache get() {
        return instance;
    }

    public static boolean active() {
        return instance != null;
    }

    /** Opens this pack's cache folder: drops old folders, applies a pending clear, loads the index and fits the size cap. */
    public static void open(Path gameDir, int dataVersion, Map<String, String> mods, double maxGigabytes) {
        close();
        Path root = gameDir.resolve(ROOT);
        String fingerprint = CacheKey.fingerprint(dataVersion, mods);
        Path folder = root.resolve(fingerprint);
        try {
            Files.createDirectories(root);
            housekeeping(root, fingerprint);
            if (Files.exists(folder.resolve(CLEAR_MARKER))) {
                deleteTree(folder);
                StructOpt.LOGGER.info("Template cache cleared as asked: {}", folder);
            }
            Files.createDirectories(folder);
            Files.writeString(folder.resolve("fingerprint.txt"), CacheKey.fingerprintText(dataVersion, mods), StandardCharsets.UTF_8);
            Path indexFile = folder.resolve(INDEX);
            CacheIndex index = Files.exists(indexFile) ? CacheIndex.parse(Files.readAllLines(indexFile, StandardCharsets.UTF_8))
                    : new CacheIndex();
            // entries whose file is gone (deleted by hand) are dropped
            for (CacheIndex.Entry e : index.all())
                if (!Files.exists(folder.resolve(CacheKey.entryPath(e.key())))) index.remove(e.key());
            TemplateCache cache = new TemplateCache(folder, fingerprint, index, (long) (maxGigabytes * (1L << 30)));
            cache.evictToCap();
            cache.rewriteIndex();
            instance = cache;
            StructOpt.LOGGER.info("Template cache {}: {} entries, {} MB", folder, index.size(), index.totalBytes() >> 20);
        } catch (IOException | RuntimeException e) {
            StructOpt.LOGGER.warn("Template cache off this session: couldn't open {}: {}", folder, e.toString());
            instance = null;
        }
    }

    /** Marks recently used entries (for the size cap's least-recently-used order) and stops the writer. */
    public static void close() {
        TemplateCache cache = instance;
        instance = null;
        if (cache == null) return;
        cache.io.shutdown();
        try {
            cache.io.awaitTermination(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        FileTime now = FileTime.fromMillis(System.currentTimeMillis());
        for (String key : cache.usedThisSession) {
            try {
                Files.setLastModifiedTime(cache.folder.resolve(CacheKey.entryPath(key)), now);
            } catch (IOException ignored) {
                // gone already
            }
        }
    }

    /**
     * The template for a resource-manager load, through the cache. Null means "let vanilla load it" (no such file, or the cache
     * couldn't read it); vanilla's path then runs unchanged.
     */
    public static @Nullable Optional<StructureTemplate> load(Identifier id, Identifier file, ResourceManager resources, TemplateSource source) {
        TemplateCache cache = instance;
        if (cache == null) return null;
        byte[] bytes;
        try (InputStream in = resources.open(file)) {
            bytes = in.readAllBytes();
        } catch (IOException e) {
            return null;
        }
        String key = CacheKey.entry(id.toString(), CacheKey.sha256(bytes));
        TemplateSourceAccessor access = (TemplateSourceAccessor) source;
        CompoundTag cached = cache.read(key);
        if (cached != null) {
            StructureTemplate template = new StructureTemplate();
            template.load(access.structopt$blockLookup(), cached);
            return Optional.of(template);
        }
        boolean[] shared = new boolean[1];
        Result result = cache.flight.run(key, () -> cache.upgrade(key, id, bytes, access), shared);
        if (!shared[0]) return Optional.of(result.template());
        StructureTemplate template = new StructureTemplate();
        template.load(access.structopt$blockLookup(), result.fixed().copy());
        return Optional.of(template);
    }

    /**
     * For the background builder: upgrades one template into the cache if it isn't there yet. Nothing stays in memory.
     * @return true when it upgraded the template now
     */
    public boolean warm(Identifier id, Identifier file, ResourceManager resources, TemplateSource source) {
        byte[] bytes;
        try (InputStream in = resources.open(file)) {
            bytes = in.readAllBytes();
        } catch (IOException e) {
            return false;
        }
        String key = CacheKey.entry(id.toString(), CacheKey.sha256(bytes));
        if (index.get(key) != null || flight.isInFlight(key)) return false;
        try {
            flight.run(key, () -> upgrade(key, id, bytes, (TemplateSourceAccessor) source));
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    public boolean contains(Identifier id, byte[] bytes) {
        return index.get(CacheKey.entry(id.toString(), CacheKey.sha256(bytes))) != null;
    }

    private @Nullable CompoundTag read(String key) {
        CacheIndex.Entry entry = index.get(key);
        if (entry == null) return null;
        Path file = folder.resolve(CacheKey.entryPath(key));
        try {
            CompoundTag tag = NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap());
            Stats.TEMPLATE_HITS.increment();
            Stats.TEMPLATE_AVOIDED_MICROS.add(entry.upgradeMicros());
            usedThisSession.add(key);
            return tag;
        } catch (IOException | RuntimeException e) {
            StructOpt.LOGGER.warn("Template cache entry for {} unreadable, loading it the vanilla way: {}", entry.templateId(), e.toString());
            forget(key);
            try {
                Files.deleteIfExists(file);
            } catch (IOException ignored) {
                // it will be overwritten
            }
            return null;
        }
    }

    private Result upgrade(String key, Identifier id, byte[] bytes, TemplateSourceAccessor source) {
        CompoundTag raw;
        try {
            raw = NbtIo.readCompressed(new ByteArrayInputStream(bytes), NbtAccounter.unlimitedHeap());
        } catch (IOException e) {
            throw new IllegalStateException("unreadable template " + id, e);
        }
        Capture capture = Capture.begin();
        StructureTemplate template;
        try {
            template = source.structopt$readStructure(raw);
        } finally {
            Capture.end();
        }
        Stats.TEMPLATE_MISSES.increment();
        Stats.TEMPLATE_UPGRADE_MICROS.add(capture.micros);
        CompoundTag fixed = capture.fixed;
        if (fixed != null) store(key, id, fixed, capture.micros);
        return new Result(template, fixed != null ? fixed : template.save(new CompoundTag()));
    }

    private void store(String key, Identifier id, CompoundTag fixed, long micros) {
        if (io.isShutdown()) return;
        try {
            io.execute(() -> {
                Path file = folder.resolve(CacheKey.entryPath(key));
                Path tmp = file.resolveSibling(key + ".tmp");
                try {
                    Files.createDirectories(file.getParent());
                    NbtIo.writeCompressed(fixed, tmp);
                    try {
                        Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                    } catch (AtomicMoveNotSupportedException e) {
                        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
                    }
                    CacheIndex.Entry entry = new CacheIndex.Entry(key, id.toString(), micros, Files.size(file));
                    appendIndex(entry.line());
                    index.put(entry);
                    usedThisSession.add(key);
                } catch (IOException | RuntimeException e) {
                    StructOpt.LOGGER.warn("Couldn't write the template cache entry for {}: {}", id, e.toString());
                    try {
                        Files.deleteIfExists(tmp);
                    } catch (IOException ignored) {
                        // left for the next start's cleanup
                    }
                }
            });
        } catch (RuntimeException ignored) {
            // the writer stopped (server stopping): the entry is written next session
        }
    }

    private void forget(String key) {
        index.remove(key);
        appendIndex(CacheIndex.removalLine(key));
    }

    private void appendIndex(String line) {
        synchronized (indexLock) {
            try (Writer w = Files.newBufferedWriter(folder.resolve(INDEX), StandardCharsets.UTF_8, StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND)) {
                w.write(line);
                w.write('\n');
            } catch (IOException e) {
                StructOpt.LOGGER.warn("Couldn't append to the template cache index: {}", e.toString());
            }
        }
    }

    private void rewriteIndex() throws IOException {
        synchronized (indexLock) {
            Path tmp = folder.resolve(INDEX + ".tmp");
            Files.write(tmp, index.lines(), StandardCharsets.UTF_8);
            Files.move(tmp, folder.resolve(INDEX), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void evictToCap() {
        List<CacheIndex.Entry> out = index.evictions(capBytes, e -> {
            try {
                return Files.getLastModifiedTime(folder.resolve(CacheKey.entryPath(e.key()))).toMillis();
            } catch (IOException ex) {
                return 0;
            }
        });
        for (CacheIndex.Entry e : out) {
            index.remove(e.key());
            try {
                Files.deleteIfExists(folder.resolve(CacheKey.entryPath(e.key())));
            } catch (IOException ignored) {
                // next start tries again
            }
        }
        if (!out.isEmpty()) StructOpt.LOGGER.info("Template cache over its cap: removed {} least recently used entries", out.size());
    }

    /** Waits until every entry queued so far is on disk (tests, and the cache tools). */
    public void flush() {
        try {
            io.submit(() -> {}).get(60, TimeUnit.SECONDS);
        } catch (Exception e) {
            // stopped or slow: the entries are written when they are written
        }
    }

    /** Asks for this pack's folder to be deleted at the next start ({@code /structopt cache clear}). */
    public void requestClear() throws IOException {
        Files.writeString(folder.resolve(CLEAR_MARKER), "cleared by command\n", StandardCharsets.UTF_8);
    }

    /** Takes back a pending clear (the GameTest of the cache tools). */
    public void cancelClear() throws IOException {
        Files.deleteIfExists(folder.resolve(CLEAR_MARKER));
    }

    public boolean clearRequested() {
        return Files.exists(folder.resolve(CLEAR_MARKER));
    }

    public Path folder() {
        return folder;
    }

    public String fingerprint() {
        return fingerprint;
    }

    public int entries() {
        return index.size();
    }

    public long bytes() {
        return index.totalBytes();
    }

    /** Age of the folder in hours (since its first entry was written, by the folder's own time). */
    public long ageHours() {
        try {
            return Math.max(0, (System.currentTimeMillis() - Files.getLastModifiedTime(folder.resolve("fingerprint.txt")).toMillis()) / 3_600_000L);
        } catch (IOException e) {
            return 0;
        }
    }

    /**
     * Counts this start as a session for its fingerprint and deletes folders unused for more than 2 sessions (old pack versions),
     * plus leftover temp files.
     */
    private static void housekeeping(Path root, String fingerprint) throws IOException {
        Path sessions = root.resolve(SESSIONS);
        Map<String, Long> last = new ConcurrentHashMap<>();
        long counter = 0;
        if (Files.exists(sessions)) {
            for (String line : Files.readAllLines(sessions, StandardCharsets.UTF_8)) {
                String[] f = line.split("\t");
                if (f.length != 2) continue;
                try {
                    long n = Long.parseLong(f[1]);
                    last.put(f[0], n);
                    counter = Math.max(counter, n);
                } catch (NumberFormatException ignored) {
                    // skip
                }
            }
        }
        long session = counter + 1;
        last.put(fingerprint, session);
        List<String> keep = new ArrayList<>();
        try (DirectoryStream<Path> dirs = Files.newDirectoryStream(root, Files::isDirectory)) {
            for (Path dir : dirs) {
                String name = dir.getFileName().toString();
                Long used = last.get(name);
                if (used == null) used = session;
                if (session - used > 2) {
                    deleteTree(dir);
                    last.remove(name);
                    StructOpt.LOGGER.info("Template cache: removed the folder of an old pack version ({})", name);
                }
            }
        }
        last.forEach((k, v) -> keep.add(k + "\t" + v));
        keep.sort(Comparator.naturalOrder());
        Files.write(sessions, keep, StandardCharsets.UTF_8);
        Path folder = root.resolve(fingerprint);
        if (Files.isDirectory(folder)) {
            try (Stream<Path> files = Files.walk(folder)) {
                for (Path p : files.filter(p -> p.toString().endsWith(".tmp")).toList()) Files.deleteIfExists(p);
            }
        }
    }

    static void deleteTree(Path dir) throws IOException {
        if (!Files.exists(dir)) return;
        try (Stream<Path> files = Files.walk(dir)) {
            for (Path p : files.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
        }
    }
}
