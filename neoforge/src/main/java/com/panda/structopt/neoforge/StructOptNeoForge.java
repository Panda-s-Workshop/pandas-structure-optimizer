package com.panda.structopt.neoforge;

import com.panda.structopt.StructOpt;
import com.panda.structopt.gametest.StructOptGameTests;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerAboutToStartEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;

/** The NeoForge entry point: runs the common side and wires its hooks to NeoForge's events. */
@Mod(StructOpt.MODID)
public final class StructOptNeoForge {
    public StructOptNeoForge(IEventBus modBus, ModContainer container) {
        StructOpt.init();
        StructOptGameTests.register(modBus);
        var game = NeoForge.EVENT_BUS;
        game.addListener((ServerAboutToStartEvent e) -> StructOpt.serverStarting(e.getServer()));
        game.addListener((ServerStoppedEvent e) -> StructOpt.serverStopped(e.getServer()));
    }
}
