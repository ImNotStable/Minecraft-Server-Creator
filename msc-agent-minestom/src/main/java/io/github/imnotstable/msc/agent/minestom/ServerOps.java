package io.github.imnotstable.msc.agent.minestom;

import io.github.imnotstable.msc.protocol.op.Ops;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import net.kyori.adventure.resource.ResourcePackInfo;
import net.kyori.adventure.resource.ResourcePackRequest;
import net.minestom.server.command.CommandManager;
import net.minestom.server.command.builder.CommandResult;
import net.minestom.server.entity.Player;

/** Server-level operations: info, commands and resource packs. */
final class ServerOps {
    private final CommandManager commands;
    private final Worlds worlds;
    private final Supplier<? extends Collection<Player>> players;
    private final MainThread main;
    private final ServerFacts facts;
    private final AtomicReference<ResourcePackRequest> pack = new AtomicReference<>();

    ServerOps(CommandManager commands, Worlds worlds, Supplier<? extends Collection<Player>> players,
              MainThread main, ServerFacts facts) {
        this.commands = commands;
        this.worlds = worlds;
        this.players = players;
        this.main = main;
        this.facts = facts;
    }

    Ops.ServerInfo info() {
        return main.call(() -> new Ops.ServerInfo("minestom", facts.platformVersion(), facts.minecraftVersion(),
                worlds.names(), players.get().size(), facts.tps()));
    }

    /**
     * Runs a command as a console-like sender and captures the messages it sends. Minestom has no
     * vanilla commands: only commands the server registered exist. Success means the command was
     * found and parsed; an unknown command or bad syntax answers {@code success=false} with a line
     * saying which.
     */
    Ops.CommandResult command(Ops.CommandParams params) {
        String command = params.command().strip();
        if (command.startsWith("/")) {
            command = command.substring(1);
        }
        if (command.isEmpty()) {
            throw new IllegalArgumentException("command is empty");
        }
        String line = command;
        return main.call(() -> {
            CapturingSender sender = new CapturingSender();
            CommandResult result = commands.execute(sender, line);
            List<String> output = new ArrayList<>(sender.output());
            switch (result.getType()) {
                case UNKNOWN -> output.add("Unknown command: " + line.split(" ", 2)[0]);
                case INVALID_SYNTAX -> {
                    if (output.isEmpty()) {
                        output.add("Invalid syntax: " + line);
                    }
                }
                default -> {
                }
            }
            return new Ops.CommandResult(result.getType() == CommandResult.Type.SUCCESS, List.copyOf(output));
        });
    }

    Ops.PackResult applyPack(Ops.PackParams params) {
        if (!params.sha1().matches("[0-9a-f]{40}")) {
            throw new IllegalArgumentException("sha1 must be 40 lowercase hex characters");
        }
        URI uri;
        try {
            uri = new URI(params.url());
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("bad url: " + params.url(), e);
        }
        UUID id = UUID.nameUUIDFromBytes(("msc-pack:" + params.url()).getBytes(StandardCharsets.UTF_8));
        ResourcePackRequest request = ResourcePackRequest.resourcePackRequest()
                .packs(ResourcePackInfo.resourcePackInfo(id, uri, params.sha1()))
                .required(params.required())
                .replace(true)
                .build();
        pack.set(request);
        return main.call(() -> {
            int sent = 0;
            for (Player player : players.get()) {
                player.sendResourcePacks(request);
                sent++;
            }
            return new Ops.PackResult(sent);
        });
    }

    /** Send the current pack, if any, to a player who just spawned for the first time. */
    void onJoin(Player player) {
        ResourcePackRequest request = pack.get();
        if (request != null) {
            player.sendResourcePacks(request);
        }
    }
}
