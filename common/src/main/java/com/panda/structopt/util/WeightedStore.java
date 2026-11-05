package com.panda.structopt.util;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * A map with a weight budget: each value is put with a weight, and while the total is over the budget the oldest values are
 * dropped. A value is handed out once ({@link #take}). Thread-safe.
 */
public final class WeightedStore<K, V> {
    private record Entry<V>(V value, long weight) {}

    private final Map<K, Entry<V>> map = new LinkedHashMap<>();
    private long budget;
    private long weight;
    private long dropped;

    public WeightedStore(long budget) {
        this.budget = budget;
    }

    /** Adds a value unless the key is there already or the value alone is over the budget; returns whether it was added. */
    public synchronized boolean put(K key, V value, long w) {
        if (w > budget || map.containsKey(key)) return false;
        map.put(key, new Entry<>(value, w));
        weight += w;
        Iterator<Entry<V>> it = map.values().iterator();
        while (weight > budget && it.hasNext()) {
            weight -= it.next().weight;
            it.remove();
            dropped++;
        }
        return true;
    }

    /** Removes and returns the value for a key, or null. */
    public synchronized @Nullable V take(K key) {
        Entry<V> e = map.remove(key);
        if (e == null) return null;
        weight -= e.weight;
        return e.value;
    }

    public synchronized boolean contains(K key) {
        return map.containsKey(key);
    }

    public synchronized void clear() {
        map.clear();
        weight = 0;
    }

    public synchronized void budget(long budget) {
        this.budget = budget;
        clear();
    }

    public synchronized int size() {
        return map.size();
    }

    public synchronized long weight() {
        return weight;
    }

    /** Values dropped for the budget since the store was made. */
    public synchronized long dropped() {
        return dropped;
    }
}
