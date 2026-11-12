package io.github.imnotstable.msc.agent.fabric;

import io.github.imnotstable.msc.agent.common.AgentServer;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.SharedConstants;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.ClientboundResourcePackPopPacket;
import net.minecraft.network.protocol.common.ClientboundResourcePackPushPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/** {@link AgentServer} on a running {@code MinecraftServer}. */
final class FabricServerAccess implements AgentServer {
    static final String PLATFORM = "fabric";

    private final MinecraftServer server;
    private final FabricLevels levels;

    FabricServerAccess(MinecraftServer server, FabricLevels levels) {
        this.server = server;
        this.levels = levels;
    }

    @Override
    public String platform() {
        return PLATFORM;
    }

    /** Fabric Loader's version. */
    @Override
    public String platformVersion() {
        return FabricLoader.getInstance().getModContainer("fabricloader")
                .map(c -> c.getMetadata().getVersion().getFriendlyString())
                .orElse("unknown");
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
