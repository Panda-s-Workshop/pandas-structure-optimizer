package com.panda.structopt.fabric;

import com.panda.structopt.StructOpt;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;

/** The Fabric entry point: runs the common side and wires its hooks to Fabric API's events. */
public final class StructOptFabric implements ModInitializer {
    @Override
    public void onInitialize() {
        StructOpt.init();
        ServerLifecycleEvents.SERVER_STARTING.register(StructOpt::serverStarting);
        ServerLifecycleEvents.SERVER_STOPPED.register(StructOpt::serverStopped);
    }
}
