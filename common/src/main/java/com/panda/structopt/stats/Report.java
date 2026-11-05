package com.panda.structopt.stats;

import com.panda.structopt.StructOpt;
import com.panda.structopt.Texts;
import com.panda.structopt.config.StructOptConfig;
import com.panda.structopt.coordinator.Coordinator;
import com.panda.structopt.start.Lookahead;
import com.panda.structopt.start.StartCache;
import com.panda.structopt.template.TemplateCache;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.levelgen.structure.StructureSet;

/** 3.9 and 3.10: the stats lines (chat and log), the periodic log line, and the one-per-session slow-structure warnings. */
public final class Report {
    private static final Map<String, String> SET_OF = new HashMap<>();
    private static final Map<String, String> COST_ENGLISH = Map.of("templates", "template loading", "height", "height sampling",
            "placement", "placement");

    private Report() {}

    /** Remembers which set each structure belongs to, for the warning line. */
    public static void indexSets(MinecraftServer server) {
        SET_OF.clear();
        server.registryAccess().lookupOrThrow(Registries.STRUCTURE_SET).listElements().forEach(ref -> {
            StructureSet set = ref.value();
            for (StructureSet.StructureSelectionEntry e : set.structures())
                e.structure().unwrapKey().ifPresent(k -> SET_OF.putIfAbsent(k.identifier().toString(), ref.key().identifier().toString()));
        });
    }

    public static List<Component> lines() {
        List<Component> out = new ArrayList<>();
        out.add(Component.translatable(Texts.STATS_HEADER, Math.round(Stats.sessionHours() * 60)));
        if (StructOpt.off()) {
            out.add(Component.translatable(Texts.STATS_OFF));
            return out;
        }
        out.add(Component.translatable(Texts.STATS_TEMPLATES, Stats.TEMPLATE_HITS.sum(), Stats.TEMPLATE_MISSES.sum(),
                fmt(Stats.TEMPLATE_AVOIDED_MICROS.sum() / 1e6)));
        TemplateCache cache = TemplateCache.get();
        out.add(cache == null ? Component.translatable(Texts.STATS_TEMPLATE_CACHE_OFF)
                : Component.translatable(Texts.STATS_TEMPLATE_CACHE, cache.entries(), cache.bytes() >> 20, cache.ageHours()));
        out.add(Component.translatable(Texts.STATS_LOADS, Stats.TEMPLATE_LOADS.sum(), Stats.TEMPLATE_SHARED_LOADS.sum()));
        out.add(Component.translatable(Texts.STATS_BUILDER, Stats.builderState, Stats.BUILDER_DONE.get(), Stats.BUILDER_TOTAL.get()));
        long[] kept = StartCache.size();
        out.add(Component.translatable(Texts.STATS_STARTS, Stats.START_HITS.sum(), Stats.START_MISSES.sum(), Stats.START_SHARED.sum(),
                kept[0], kept[1] >> 20));
        long computed = Stats.AHEAD_COMPUTED.sum(), used = Stats.AHEAD_USED.sum();
        out.add(Component.translatable(Texts.STATS_AHEAD, computed, used, Math.max(0, computed - used), Stats.AHEAD_DROPPED.sum(),
                Lookahead.queuedCount()));
        out.add(Component.translatable(Texts.STATS_HEIGHT, Stats.HEIGHT_HITS.sum(), Stats.HEIGHT_MISSES.sum()));
        out.add(Component.translatable(Texts.STATS_SPAWNS, Stats.SPAWN_SKIPS.sum()));
        out.add(Component.translatable(Texts.STATS_COORDINATOR, Component.translatable(Coordinator.active() ? Texts.ON : Texts.OFF),
                Stats.COORDINATOR_BLOCKED.sum()));
        List<Stats.StructureRecord> top = Stats.top(10);
        if (top.isEmpty()) {
            out.add(Component.translatable(Texts.STATS_NONE));
        } else {
            out.add(Component.translatable(Texts.STATS_TOP));
            for (Stats.StructureRecord r : top) {
                long n = r.attempts.sum();
                out.add(Component.translatable(Texts.STATS_TOP_LINE, r.id, n, n == 0 ? 0 : Math.round(100.0 * r.successes.sum() / n),
                        fmt(r.meanMillis()), fmt(r.maxNanos.get() / 1e6),
                        Component.translatable("command.pandas_structure_optimizer.cost." + r.mainCost())));
            }
        }
        return out;
    }

    /** The periodic summary for the server log. */
    public static void logSummary() {
        StructOpt.LOGGER.info("[structopt] templates {} cached / {} upgraded ({} s avoided); starts {} reused / {} computed / {} not shareable; ahead {} computed / {} used; heights {} hits; spawn skips {}",
                Stats.TEMPLATE_HITS.sum(), Stats.TEMPLATE_MISSES.sum(), fmt(Stats.TEMPLATE_AVOIDED_MICROS.sum() / 1e6), Stats.START_HITS.sum(),
                Stats.START_MISSES.sum(), Stats.START_UNSHAREABLE.sum(), Stats.AHEAD_COMPUTED.sum(), Stats.AHEAD_USED.sum(), Stats.HEIGHT_HITS.sum(), Stats.SPAWN_SKIPS.sum());
    }

    /** 3.10: one log line per session for each structure that matches {@link SlowStructureRule}. */
    public static void checkSlow() {
        if (!StructOptConfig.get().warningsSlowStructures) return;
        double hours = Stats.sessionHours();
        for (Stats.StructureRecord r : Stats.structures()) {
            if (r.warned) continue;
            long n = r.attempts.sum();
            if (!SlowStructureRule.isSlow(n, r.successes.sum(), r.nanos.sum(), hours)) continue;
            r.warned = true;
            StructOpt.LOGGER.warn("Slow structure: {} (set {}) placed in {}% of {} attempts and used {} s of CPU so far, mostly {}. "
                            + "Its biome list or spacing may be worth a look.", r.id, SET_OF.getOrDefault(r.id, "unknown"),
                    fmt(100.0 * r.successes.sum() / n), n, fmt(r.nanos.sum() / 1e9), COST_ENGLISH.getOrDefault(r.mainCost(), r.mainCost()));
        }
    }

    static String fmt(double v) {
        return String.format(Locale.ROOT, v >= 100 ? "%.0f" : "%.1f", v);
    }
}
