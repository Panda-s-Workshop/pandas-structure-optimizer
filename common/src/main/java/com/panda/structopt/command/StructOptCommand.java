package com.panda.structopt.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import com.panda.structopt.StructOpt;
import com.panda.structopt.Texts;
import com.panda.structopt.coordinator.Coordinator;
import com.panda.structopt.stats.Report;
import com.panda.structopt.template.CacheBuilder;
import com.panda.structopt.template.TemplateCache;
import java.io.IOException;
import java.util.List;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

/**
 * {@code /structopt stats} (level 2): this session's numbers, in chat and in the log.
 * {@code /structopt cache clear} (level 3): this pack version's template cache is deleted at the next start.
 * {@code /structopt cache rebuild} (level 3): the background builder checks every template again, now.
 * {@code /structopt coordinator status} (level 2): whether the coordinator is on for this world, and its sets.
 */
public final class StructOptCommand {
    private StructOptCommand() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("structopt")
                .then(Commands.literal("stats")
                        .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                        .executes(StructOptCommand::stats))
                .then(Commands.literal("cache")
                        .requires(Commands.hasPermission(Commands.LEVEL_ADMINS))
                        .then(Commands.literal("clear").executes(StructOptCommand::clear))
                        .then(Commands.literal("rebuild").executes(StructOptCommand::rebuild)))
                .then(Commands.literal("coordinator")
                        .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                        .then(Commands.literal("status").executes(StructOptCommand::coordinator))));
    }

    private static int stats(CommandContext<CommandSourceStack> c) {
        List<Component> lines = Report.lines();
        for (Component line : lines) {
            c.getSource().sendSuccess(() -> line, false);
            StructOpt.LOGGER.info("[structopt] {}", line.getString());
        }
        return lines.size();
    }

    private static int clear(CommandContext<CommandSourceStack> c) {
        TemplateCache cache = TemplateCache.get();
        if (cache == null) {
            c.getSource().sendFailure(Component.translatable(Texts.CACHE_OFF));
            return 0;
        }
        try {
            cache.requestClear();
        } catch (IOException e) {
            c.getSource().sendFailure(Component.literal(e.toString()));
            return 0;
        }
        c.getSource().sendSuccess(() -> Component.translatable(Texts.CACHE_CLEARED, cache.fingerprint()), true);
        return 1;
    }

    private static int rebuild(CommandContext<CommandSourceStack> c) {
        if (TemplateCache.get() == null) {
            c.getSource().sendFailure(Component.translatable(Texts.CACHE_OFF));
            return 0;
        }
        CacheBuilder.start(c.getSource().getServer());
        c.getSource().sendSuccess(() -> Component.translatable(Texts.CACHE_REBUILD), true);
        return 1;
    }

    private static int coordinator(CommandContext<CommandSourceStack> c) {
        if (!Coordinator.active()) {
            c.getSource().sendSuccess(() -> Component.translatable(Texts.COORDINATOR_OFF), false);
            return 0;
        }
        var level = c.getSource().getLevel().dimension();
        c.getSource().sendSuccess(() -> Component.translatable(Texts.COORDINATOR_ON, Coordinator.setCount(level)), false);
        for (String line : Coordinator.describe(level, 20))
            c.getSource().sendSuccess(() -> Component.translatable(Texts.COORDINATOR_SET, line), false);
        return 1;
    }
}
