package com.panda.structopt.template;

import com.panda.structopt.config.StructOptConfig;
import com.panda.structopt.stats.Stats;
import com.panda.structopt.stats.Timing;
import com.panda.structopt.util.SingleFlight;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;

/** 3.3, one template manager's in-flight loads (see {@code StructureTemplateManagerMixin}). */
public final class TemplateLoads {
    private final SingleFlight<Identifier, Optional<StructureTemplate>> flight = new SingleFlight<>();

    public Optional<StructureTemplate> get(Map<Identifier, Optional<StructureTemplate>> repository, Identifier id,
                                           Function<Identifier, Optional<StructureTemplate>> load,
                                           Function<Identifier, Optional<StructureTemplate>> original) {
        if (repository.get(id) != null || !enabled()) return original.apply(id);
        long t0 = System.nanoTime();
        boolean[] shared = new boolean[1];
        flight.run(id, () -> {
            if (repository.get(id) != null) return repository.get(id);
            Optional<StructureTemplate> loaded = load.apply(id);
            Optional<StructureTemplate> before = repository.putIfAbsent(id, loaded);
            Stats.TEMPLATE_LOADS.increment();
            return before != null ? before : loaded;
        }, shared);
        if (shared[0]) Stats.TEMPLATE_SHARED_LOADS.increment();
        Timing.add(Timing.TEMPLATE, System.nanoTime() - t0);
        return original.apply(id);
    }

    private static boolean enabled() {
        return StructOptConfig.get().templateLoadsSeparate && !com.panda.structopt.StructOpt.off();
    }
}
