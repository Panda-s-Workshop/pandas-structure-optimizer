package com.panda.structopt.neoforge;

import com.panda.structopt.StructOpt;
import com.panda.structopt.command.StructOptCommand;
import com.panda.structopt.gametest.StructOptGameTests;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.server.ServerAboutToStartEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/** The NeoForge entry point: runs the common side and wires its hooks to NeoForge's events. */
@Mod(StructOpt.MODID)
public final class StructOptNeoForge {
    public StructOptNeoForge(IEventBus modBus, ModContainer container) {
        StructOpt.init();
        StructOptGameTests.register(modBus);
        var game = NeoForge.EVENT_BUS;
        game.addListener((ServerAboutToStartEvent e) -> StructOpt.serverStarting(e.getServer()));
        game.addListener((ServerStartedEvent e) -> StructOpt.serverStarted(e.getServer()));
        game.addListener((ServerTickEvent.Post e) -> StructOpt.serverTick(e.getServer()));
        game.addListener((ServerStoppedEvent e) -> StructOpt.serverStopped(e.getServer()));
        game.addListener((RegisterCommandsEvent e) -> StructOptCommand.register(e.getDispatcher()));
    }
}
