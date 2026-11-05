package com.panda.structopt.template;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.ToLongFunction;

/**
 * The cache's index: one line per entry, {@code key<TAB>template id<TAB>upgrade microseconds<TAB>bytes}, appended as entries are
 * written. A line that doesn't parse is skipped; a later line for the same key replaces the earlier one; a line {@code -<TAB>key}
 * removes it. The upgrade time is what a hit avoids, summed into the stats.
 */
public final class CacheIndex {
    public record Entry(String key, String templateId, long upgradeMicros, long bytes) {
        public String line() {
            return key + "\t" + templateId + "\t" + upgradeMicros + "\t" + bytes;
        }
    }

    private final Map<String, Entry> entries = new ConcurrentHashMap<>();

    public static CacheIndex parse(List<String> lines) {
        CacheIndex index = new CacheIndex();
        for (String line : lines) {
            String[] f = line.split("\t");
            if (f.length == 2 && f[0].equals("-")) {
                index.entries.remove(f[1]);
                continue;
            }
            if (f.length != 4 || f[0].length() != 64) continue;
            try {
                index.entries.put(f[0], new Entry(f[0], f[1], Long.parseLong(f[2]), Long.parseLong(f[3])));
            } catch (NumberFormatException ignored) {
                // a torn last line from a crash: skip it
            }
        }
        return index;
    }

    public static String removalLine(String key) {
        return "-\t" + key;
    }

    public Entry get(String key) {
        return entries.get(key);
    }

    public void put(Entry entry) {
        entries.put(entry.key(), entry);
    }

    public void remove(String key) {
        entries.remove(key);
    }

    public int size() {
        return entries.size();
    }

    public long totalBytes() {
        return entries.values().stream().mapToLong(Entry::bytes).sum();
    }

    public List<Entry> all() {
        return new ArrayList<>(entries.values());
    }

    /**
     * The entries to delete so the cache fits in {@code capBytes}: least recently used first (by {@code lastUsed}, oldest first),
     * until the rest takes at most 90% of the cap. Nothing when it already fits.
     */
    public List<Entry> evictions(long capBytes, ToLongFunction<Entry> lastUsed) {
        long total = totalBytes();
        List<Entry> out = new ArrayList<>();
        if (total <= capBytes) return out;
        long target = (long) (capBytes * 0.9);
        List<Entry> sorted = all();
        sorted.sort(Comparator.comparingLong(lastUsed).thenComparing(Entry::key));
        for (Entry e : sorted) {
            if (total <= target) break;
            out.add(e);
            total -= e.bytes();
        }
        return out;
    }

    /** The lines of a compacted index (one per live entry, sorted by key). */
    public List<String> lines() {
        return entries.values().stream().sorted(Comparator.comparing(Entry::key)).map(Entry::line).toList();
    }
}
