package com.example.verifyme;

import com.velocitypowered.api.command.BrigadierCommand;
import com.velocitypowered.api.event.player.ServerConnectedEvent;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import java.time.Duration;
import java.util.Optional;

/** Uses the names CLAUDE.md lists, so one the pinned Velocity API does not have fails the build. Never called. */
final class DocNames {
    private DocNames() {
    }

    static Optional<RegisteredServer> lobby(ProxyServer proxy) {
        return proxy.getServer("lobby");
    }

    static String switched(ServerConnectedEvent event) {
        return event.getServer().getServerInfo().getName();
    }

    static Class<?> brigadier() {
        return BrigadierCommand.class;
    }

    static void later(ProxyServer proxy, Object plugin) {
        proxy.getScheduler().buildTask(plugin, () -> { }).delay(Duration.ofSeconds(1)).schedule();
    }
}
