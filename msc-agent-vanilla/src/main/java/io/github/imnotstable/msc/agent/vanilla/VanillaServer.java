package io.github.imnotstable.msc.agent.vanilla;

import io.github.imnotstable.msc.agent.common.AgentServer;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import net.minecraft.SharedConstants;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.ClientboundResourcePackPopPacket;
import net.minecraft.network.protocol.common.ClientboundResourcePackPushPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * {@link AgentServer} on a running {@code MinecraftServer}. Only vanilla Minecraft classes: the loader the agent runs
 * on (Fabric, Quilt, NeoForge, Forge) only names itself through {@link Loader}.
 */
final class VanillaServer implements AgentServer {
    private final MinecraftServer server;
    private final VanillaLevels levels;
    private final Loader loader;

    VanillaServer(MinecraftServer server, VanillaLevels levels, Loader loader) {
        this.server = server;
        this.levels = levels;
        this.loader = loader;
    }

    @Override
    public String platform() {
        return loader.platform();
    }

    @Override
    public String platformVersion() {
        return loader.version();
    }

    @Override
    public String minecraftVersion() {
        return SharedConstants.getCurrentVersion().name();
    }

    @Override
    public double tps() {
        long nanos = server.getAverageTickTimeNanos();
        if (nanos <= 0) {
            return -1;
        }
        return Math.min(server.tickRateManager().tickrate(), 1_000_000_000.0 / nanos);
    }

    @Override
    public List<String> worldNames() {
        return levels.names();
    }

    @Override
    public int onlinePlayers() {
        return server.getPlayerList().getPlayerCount();
    }

    /** Runs as the server console (full permissions, at the overworld spawn), like Paper's feedback sender. */
    @Override
    public boolean runCommand(String line, Consumer<String> feedback) {
        boolean[] success = {false};
        CommandSourceStack source = server.createCommandSourceStack()
                .withSource(new FeedbackSource(feedback))
                .withCallback((ok, result) -> success[0] = ok);
        server.getCommands().performPrefixedCommand(source, line);
        return success[0];
    }

    @Override
    public int sendPack(Pack pack) {
        List<ServerPlayer> players = server.getPlayerList().getPlayers();
        for (ServerPlayer player : players) {
            send(player, pack);
        }
        return players.size();
    }

    /** Replace whatever packs a player has with this one, as Paper's {@code replace(true)} does. */
    static void send(ServerPlayer player, Pack pack) {
        player.connection.send(new ClientboundResourcePackPopPacket(Optional.empty()));
        player.connection.send(new ClientboundResourcePackPushPacket(pack.id(), pack.url(), pack.sha1(),
                pack.required(), Optional.empty()));
    }

    /** Collects what a command says back, success and failure messages alike. */
    private record FeedbackSource(Consumer<String> feedback) implements CommandSource {
        @Override
        public void sendSystemMessage(Component message) {
            feedback.accept(message.getString());
        }

        @Override
        public boolean acceptsSuccess() {
            return true;
        }

        @Override
        public boolean acceptsFailure() {
            return true;
        }

        @Override
        public boolean shouldInformAdmins() {
            return false;
        }
    }
}
