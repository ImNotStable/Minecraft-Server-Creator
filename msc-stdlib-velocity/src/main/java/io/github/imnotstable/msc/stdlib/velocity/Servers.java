package io.github.imnotstable.msc.stdlib.velocity;

import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import io.github.imnotstable.msc.stdlib.text.Text;

import java.util.Collection;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/** Moving players between backends and talking to everyone: hubs, game queues, network broadcasts. */
public final class Servers {
    private Servers() {}

    /** Sends {@code player} to the backend named {@code server}; true when they arrived. */
    public static CompletableFuture<Boolean> send(Player player, String server) {
        Optional<RegisteredServer> to = Stdlib.proxy().getServer(server);
        if (to.isEmpty()) {
            return CompletableFuture.completedFuture(false);
        }
        return player.createConnectionRequest(to.get()).connect().thenApply(r -> r.isSuccessful());
    }

    /** The backend name the player is on, or empty while switching. */
    public static Optional<String> current(Player player) {
        return player.getCurrentServer().map(c -> c.getServerInfo().getName());
    }

    /** Players on {@code server}. */
    public static Collection<Player> players(String server) {
        return Stdlib.proxy().getServer(server).map(RegisteredServer::getPlayersConnected).orElse(java.util.List.of());
    }

    /** MiniMessage to every player on the network. */
    public static void broadcast(String miniMessage, Object... placeholders) {
        Stdlib.proxy().sendMessage(Text.mm(miniMessage, placeholders));
    }
}
