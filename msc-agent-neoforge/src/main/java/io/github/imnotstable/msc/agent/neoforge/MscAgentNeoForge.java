package io.github.imnotstable.msc.agent.neoforge;

import io.github.imnotstable.msc.agent.vanilla.Loader;
import io.github.imnotstable.msc.agent.vanilla.VanillaAgent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.NeoForgeVersion;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/** Entry point on NeoForge: forwards NeoForge's server events to the shared {@link VanillaAgent}. */
@Mod(value = MscAgentNeoForge.MOD_ID, dist = Dist.DEDICATED_SERVER)
public final class MscAgentNeoForge {
    static final String MOD_ID = "msc_agent";
    static final String PLATFORM = "neoforge";

    public MscAgentNeoForge() {
        register(NeoForge.EVENT_BUS, new VanillaAgent(new Loader(PLATFORM, NeoForgeVersion.getVersion())));
    }

    static void register(IEventBus bus, VanillaAgent agent) {
        bus.addListener(ServerStartingEvent.class, e -> agent.starting(e.getServer()));
        bus.addListener(ServerStartedEvent.class, e -> agent.started());
        bus.addListener(ServerStoppingEvent.class, e -> agent.stopping());
        bus.addListener(ServerTickEvent.Post.class, e -> agent.tick());
        bus.addListener(PlayerEvent.PlayerLoggedInEvent.class, e -> {
            if (asServerPlayer(e.getEntity()) instanceof ServerPlayer p) {
                agent.joined(p);
            }
        });
        bus.addListener(PlayerEvent.PlayerLoggedOutEvent.class, e -> {
            if (asServerPlayer(e.getEntity()) instanceof ServerPlayer p) {
                agent.quit(p);
            }
        });
    }

    private static ServerPlayer asServerPlayer(Player player) {
        return player instanceof ServerPlayer p ? p : null;
    }
}
