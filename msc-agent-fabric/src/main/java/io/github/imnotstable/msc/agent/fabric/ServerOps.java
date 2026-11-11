package io.github.imnotstable.msc.agent.fabric;

import io.github.imnotstable.msc.protocol.op.Ops;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/** Server-level operations: info, console commands and resource packs. */
final class ServerOps {
    static final String PLATFORM = "fabric";

    private final ServerAccess server;
    private final MainThread main;
    private final AtomicReference<ServerAccess.Pack> pack = new AtomicReference<>();

    ServerOps(ServerAccess server, MainThread main) {
        this.server = server;
        this.main = main;
    }

    Ops.ServerInfo info() {
        return main.call(() -> new Ops.ServerInfo(PLATFORM, server.platformVersion(), server.minecraftVersion(),
                server.worldNames(), server.onlinePlayers(), server.tps()));
    }

    /**
     * Runs a command as a console-like source and captures the feedback it sends. Success means the
     * command was found and its executor reported success; vanilla commands that fail usually
     * still say why in the output.
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
            List<String> output = Collections.synchronizedList(new ArrayList<>());
            boolean success = server.runCommand(line, output::add);
            return new Ops.CommandResult(success, List.copyOf(output));
        });
    }

    Ops.PackResult applyPack(Ops.PackParams params) {
        if (!params.sha1().matches("[0-9a-f]{40}")) {
            throw new IllegalArgumentException("sha1 must be 40 lowercase hex characters");
        }
        try {
            new URI(params.url());
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("bad url: " + params.url(), e);
        }
        UUID id = UUID.nameUUIDFromBytes(("msc-pack:" + params.url()).getBytes(StandardCharsets.UTF_8));
        ServerAccess.Pack request = new ServerAccess.Pack(id, params.url(), params.sha1(), params.required());
        pack.set(request);
        return main.call(() -> new Ops.PackResult(server.sendPack(request)));
    }

    /** The pack to send to a player who joins, if one was applied. */
    Optional<ServerAccess.Pack> currentPack() {
        return Optional.ofNullable(pack.get());
    }
}
