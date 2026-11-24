package io.github.imnotstable.msc.stdlib.bungeecord;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import net.md_5.bungee.api.config.ServerInfo;
import net.md_5.bungee.api.connection.ProxiedPlayer;

/** Moving players between backends and talking to everyone: hubs, game queues, network broadcasts. */
public final class Servers {
    private Servers() {}

    /** Sends {@code player} to the backend named {@code server}; true when they arrived. */
    public static CompletableFuture<Boolean> send(ProxiedPlayer player, String server) {
        ServerInfo to = Stdlib.proxy().getServerInfo(server);
        CompletableFuture<Boolean> done = new CompletableFuture<>();
        if (to == null) {
            done.complete(false);
            return done;
        }
        player.connect(to, (ok, error) -> done.complete(ok != null && ok && error == null));
        return done;
    }

    /** The backend name the player is on, or empty while switching. */
    public static Optional<String> current(ProxiedPlayer player) {
        return Optional.ofNullable(player.getServer()).map(s -> s.getInfo().getName());
    }

    /** Players on {@code server}. */
    public static Collection<ProxiedPlayer> players(String server) {
        ServerInfo info = Stdlib.proxy().getServerInfo(server);
        return info == null ? List.of() : info.getPlayers();
    }

    /** MiniMessage to every player on the network (and the console). */
    public static void broadcast(String miniMessage, Object... placeholders) {
        Stdlib.proxy().broadcast(BungeeText.mm(miniMessage, placeholders));
    }
}
