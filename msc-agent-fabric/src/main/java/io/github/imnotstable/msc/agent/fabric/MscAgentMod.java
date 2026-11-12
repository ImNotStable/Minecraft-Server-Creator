package io.github.imnotstable.msc.agent.fabric;

import io.github.imnotstable.msc.agent.vanilla.Loader;
import io.github.imnotstable.msc.agent.vanilla.VanillaAgent;
import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;

/**
 * Entry point on Fabric and on Quilt, which loads Fabric mods. It forwards Fabric API's server events to the shared
 * {@link VanillaAgent}.
 */
public final class MscAgentMod implements DedicatedServerModInitializer {
    @Override
    public void onInitializeServer() {
        VanillaAgent agent = new VanillaAgent(loader(FabricLoader.getInstance()));
        ServerLifecycleEvents.SERVER_STARTING.register(agent::starting);
        ServerLifecycleEvents.SERVER_STARTED.register(server -> agent.started());
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> agent.stopping());
        ServerTickEvents.END_SERVER_TICK.register(server -> agent.tick());
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> agent.joined(handler.getPlayer()));
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> agent.quit(handler.getPlayer()));
    }

    /** Quilt Loader also answers to {@code fabricloader}, so look for Quilt first. */
    static Loader loader(FabricLoader loader) {
        return loader.getModContainer("quilt_loader")
                .map(c -> new Loader("quilt", c.getMetadata().getVersion().getFriendlyString()))
                .orElseGet(() -> new Loader("fabric", loader.getModContainer("fabricloader")
                        .map(c -> c.getMetadata().getVersion().getFriendlyString())
                        .orElse("unknown")));
    }
}
