package com.panda.structopt.neoforge.client;

import com.panda.structopt.StructOpt;
import com.panda.structopt.client.DevExit;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.GameShuttingDownEvent;

/** The NeoForge client entry point. The mod draws nothing; this only ends dev runs cleanly. */
@Mod(value = StructOpt.MODID, dist = Dist.CLIENT)
public final class StructOptNeoForgeClient {
    public StructOptNeoForgeClient(IEventBus modBus, ModContainer container) {
        NeoForge.EVENT_BUS.addListener((GameShuttingDownEvent e) -> DevExit.onClientStopping());
    }
}
