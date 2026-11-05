package com.panda.structopt.util;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Runs one computation per key at a time: a caller that finds the key already in flight waits for that result instead of
 * computing it again. Different keys never wait for each other (the map holds a future, filled with {@code putIfAbsent}, so no
 * lock is held while computing).
 */
public final class SingleFlight<K, V> {
    private final ConcurrentHashMap<K, CompletableFuture<V>> inFlight = new ConcurrentHashMap<>();

    /** The result for the key; {@code shared[0]} is set when it came from another caller's computation. */
    public V run(K key, Supplier<V> compute, boolean[] shared) {
        CompletableFuture<V> mine = new CompletableFuture<>();
        CompletableFuture<V> other = inFlight.putIfAbsent(key, mine);
        if (other != null) {
            if (shared != null) shared[0] = true;
            try {
                return other.join();
            } catch (CompletionException e) {
                if (e.getCause() instanceof RuntimeException r) throw r;
                if (e.getCause() instanceof Error err) throw err;
                throw e;
            }
        }
        try {
            V value = compute.get();
            mine.complete(value);
            return value;
        } catch (Throwable t) {
            mine.completeExceptionally(t);
            throw t;
        } finally {
            inFlight.remove(key, mine);
        }
    }

    public V run(K key, Supplier<V> compute) {
        return run(key, compute, null);
    }

    public boolean isInFlight(K key) {
        return inFlight.containsKey(key);
    }

    public int inFlightCount() {
        return inFlight.size();
    }
}
