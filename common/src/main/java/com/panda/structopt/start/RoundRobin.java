package com.panda.structopt.start;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 3.5's scan schedule: every member is looked at once per cycle of {@code cycle} ticks, and the members are split round-robin
 * into {@code cycle} batches, one batch per tick: about N / cycle members per tick (50 players, 10 ticks: 5 per tick), so the
 * work never piles onto one tick. The order is stable between cycles; a newcomer is appended, so it joins the batch after the
 * last member's.
 */
public final class RoundRobin<T> {
    private final List<T> order = new ArrayList<>();

    /** Drops members that left and appends new ones, keeping everyone else's place. */
    public void sync(Collection<T> present) {
        Set<T> now = new HashSet<>(present);
        order.removeIf(t -> !now.contains(t));
        Set<T> known = new HashSet<>(order);
        for (T t : present) if (known.add(t)) order.add(t);
    }

    /** The members to look at on this tick. */
    public List<T> batch(long tick, int cycle) {
        List<T> out = new ArrayList<>();
        int slot = (int) Math.floorMod(tick, (long) cycle);
        for (int i = slot; i < order.size(); i += cycle) out.add(order.get(i));
        return out;
    }

    public int size() {
        return order.size();
    }
}
