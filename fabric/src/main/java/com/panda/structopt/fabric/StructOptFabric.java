package com.panda.structopt.fabric;

import com.panda.structopt.StructOpt;
import com.panda.structopt.command.StructOptCommand;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;

/** The Fabric entry point: runs the common side and wires its hooks to Fabric API's events. */
public final class StructOptFabric implements ModInitializer {
    @Override
    public void onInitialize() {
        StructOpt.init();
        ServerLifecycleEvents.SERVER_STARTING.register(StructOpt::serverStarting);
        ServerLifecycleEvents.SERVER_STARTED.register(StructOpt::serverStarted);
        ServerTickEvents.END_SERVER_TICK.register(StructOpt::serverTick);
        ServerLifecycleEvents.SERVER_STOPPED.register(StructOpt::serverStopped);
        CommandRegistrationCallback.EVENT.register((dispatcher, context, selection) -> StructOptCommand.register(dispatcher));
    }
}
