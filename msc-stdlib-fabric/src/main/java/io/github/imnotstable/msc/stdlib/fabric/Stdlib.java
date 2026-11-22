package io.github.imnotstable.msc.stdlib.fabric;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.MinecraftServer;

/**
 * Call {@link #init} in {@code onInitialize}: it hooks the scheduler into the server tick and remembers the server.
 * Fabric loads one copy of this library for every mod that bundles it, so calling it from several mods is fine.
 */
public final class Stdlib {
    private static final FabricScheduler SCHEDULER = new FabricScheduler();
    private static boolean hooked;
    private static MinecraftServer server;

    private Stdlib() {}

    public static synchronized void init() {
        if (hooked) {
            return;
        }
        hooked = true;
        ServerLifecycleEvents.SERVER_STARTING.register(s -> server = s);
        ServerLifecycleEvents.SERVER_STOPPED.register(s -> server = null);
        ServerTickEvents.END_SERVER_TICK.register(s -> SCHEDULER.tick());
    }

    /** The running server; throws before it starts. */
    public static MinecraftServer server() {
        if (server == null) {
            throw new IllegalStateException("No server yet: call Stdlib.init() in onInitialize and use this once it starts");
        }
        return server;
    }

    public static FabricScheduler scheduler() {
        return SCHEDULER;
    }
}
