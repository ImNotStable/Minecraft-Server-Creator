package io.github.imnotstable.msc.agent.forge;

import io.github.imnotstable.msc.agent.vanilla.Loader;
import io.github.imnotstable.msc.agent.vanilla.VanillaAgent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.versions.forge.ForgeVersion;

/** Entry point on Minecraft Forge: forwards Forge's server events to the shared {@link VanillaAgent}. */
@Mod(MscAgentForge.MOD_ID)
public final class MscAgentForge {
    static final String MOD_ID = "msc_agent";
    static final String PLATFORM = "forge";

    public MscAgentForge() {
        VanillaAgent agent = new VanillaAgent(new Loader(PLATFORM, ForgeVersion.getVersion()));
        ServerStartingEvent.BUS.addListener(e -> agent.starting(e.getServer()));
        ServerStartedEvent.BUS.addListener(e -> agent.started());
        ServerStoppingEvent.BUS.addListener(e -> agent.stopping());
        TickEvent.ServerTickEvent.Post.BUS.addListener(e -> agent.tick());
        PlayerEvent.PlayerLoggedInEvent.BUS.addListener(e -> {
            if (e.getEntity() instanceof ServerPlayer p) {
                agent.joined(p);
            }
        });
        PlayerEvent.PlayerLoggedOutEvent.BUS.addListener(e -> {
            if (e.getEntity() instanceof ServerPlayer p) {
                agent.quit(p);
            }
        });
    }
}
