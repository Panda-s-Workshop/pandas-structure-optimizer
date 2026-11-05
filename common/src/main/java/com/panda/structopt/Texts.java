package com.panda.structopt;

import java.util.List;

/** Every translation key the mod uses, so a test can check each has its English text. */
public final class Texts {
    /** The mod's name and description in mod lists that read them from the lang file. */
    public static final String NAME = "modmenu.nameTranslation.pandas_structure_optimizer";
    public static final String DESCRIPTION = "modmenu.descriptionTranslation.pandas_structure_optimizer";

    private static final String P = "command.pandas_structure_optimizer.";
    public static final String STATS_HEADER = P + "stats.header";
    public static final String STATS_OFF = P + "stats.off";
    public static final String STATS_TEMPLATES = P + "stats.templates";
    public static final String STATS_TEMPLATE_CACHE = P + "stats.template_cache";
    public static final String STATS_TEMPLATE_CACHE_OFF = P + "stats.template_cache_off";
    public static final String STATS_LOADS = P + "stats.loads";
    public static final String STATS_BUILDER = P + "stats.builder";
    public static final String STATS_STARTS = P + "stats.starts";
    public static final String STATS_AHEAD = P + "stats.ahead";
    public static final String STATS_HEIGHT = P + "stats.height";
    public static final String STATS_SPAWNS = P + "stats.spawns";
    public static final String STATS_COORDINATOR = P + "stats.coordinator";
    public static final String STATS_TOP = P + "stats.top";
    public static final String STATS_TOP_LINE = P + "stats.top_line";
    public static final String STATS_NONE = P + "stats.none";
    public static final String CACHE_CLEARED = P + "cache.cleared";
    public static final String CACHE_REBUILD = P + "cache.rebuild";
    public static final String CACHE_OFF = P + "cache.off";
    public static final String COORDINATOR_ON = P + "coordinator.on";
    public static final String COORDINATOR_OFF = P + "coordinator.off";
    public static final String COORDINATOR_SET = P + "coordinator.set";
    public static final String ON = P + "on";
    public static final String OFF = P + "off";
    public static final String COST_TEMPLATES = P + "cost.templates";
    public static final String COST_HEIGHT = P + "cost.height";
    public static final String COST_PLACEMENT = P + "cost.placement";

    public static final List<String> ALL = List.of(NAME, DESCRIPTION, STATS_HEADER, STATS_OFF, STATS_TEMPLATES, STATS_TEMPLATE_CACHE,
            STATS_TEMPLATE_CACHE_OFF, STATS_LOADS, STATS_BUILDER, STATS_STARTS, STATS_AHEAD, STATS_HEIGHT, STATS_SPAWNS,
            STATS_COORDINATOR, STATS_TOP, STATS_TOP_LINE, STATS_NONE, CACHE_CLEARED, CACHE_REBUILD, CACHE_OFF, COORDINATOR_ON,
            COORDINATOR_OFF, COORDINATOR_SET, ON, OFF, COST_TEMPLATES, COST_HEIGHT, COST_PLACEMENT);

    private Texts() {}
}
