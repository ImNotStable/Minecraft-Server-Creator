package io.github.imnotstable.msc.agent.common;

import io.github.imnotstable.msc.protocol.Limits;
import io.github.imnotstable.msc.protocol.op.Ops;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/** Server-level operations: info, console commands and resource packs. */
public final class ServerOps {
    private final AgentServer server;
    private final MainThread main;
    private final AtomicReference<AgentServer.Pack> pack = new AtomicReference<>();

    public ServerOps(AgentServer server, MainThread main) {
        this.server = server;
        this.main = main;
    }

    public Ops.ServerInfo info() {
        return main.call(() -> new Ops.ServerInfo(server.platform(), server.platformVersion(), server.minecraftVersion(),
                server.worldNames(), server.onlinePlayers(), server.tps()));
    }

    /**
     * Runs a command as a console-like sender and captures the feedback it sends. Success means the
     * command was found and its executor reported success; commands that fail usually still say
     * why in the output. The output is capped at {@link Limits#MAX_RESULT_TEXT_CHARS} characters with a marker
     * ({@link CommandOutput}), so even a huge one fits in a frame (#341).
     */
    public Ops.CommandResult command(Ops.CommandParams params) {
        String command = params.command().strip();
        if (command.startsWith("/")) {
            command = command.substring(1);
        }
        if (command.isEmpty()) {
            throw new IllegalArgumentException("command is empty");
        }
        String line = command;
        return main.call(() -> {
            CommandOutput output = new CommandOutput(Limits.MAX_RESULT_TEXT_CHARS);
            boolean success = server.runCommand(line, output);
            return new Ops.CommandResult(success, output.lines());
        });
    }

    public Ops.PackResult applyPack(Ops.PackParams params) {
        if (!params.sha1().matches("[0-9a-f]{40}")) {
            throw new IllegalArgumentException("sha1 must be 40 lowercase hex characters");
        }
        try {
            new URI(params.url());
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("bad url: " + params.url(), e);
        }
        UUID id = UUID.nameUUIDFromBytes(("msc-pack:" + params.url()).getBytes(StandardCharsets.UTF_8));
        AgentServer.Pack request = new AgentServer.Pack(id, params.url(), params.sha1(), params.required());
        pack.set(request);
        return main.call(() -> new Ops.PackResult(server.sendPack(request)));
    }

    /** Send this pack to each player who joins from now on, unless MSC applies another ({@link SavedPack}). */
    public void standby(String url, String sha1) {
        UUID id = UUID.nameUUIDFromBytes(("msc-pack:" + url).getBytes(StandardCharsets.UTF_8));
        pack.compareAndSet(null, new AgentServer.Pack(id, url, sha1, false));
    }

    /** The pack to send to a player who joins, if one was applied. */
    public Optional<AgentServer.Pack> currentPack() {
        return Optional.ofNullable(pack.get());
    }

}
