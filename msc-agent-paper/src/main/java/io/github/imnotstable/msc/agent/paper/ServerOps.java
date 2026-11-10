package io.github.imnotstable.msc.agent.paper;

import io.github.imnotstable.msc.protocol.op.Ops;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;
import net.kyori.adventure.resource.ResourcePackInfo;
import net.kyori.adventure.resource.ResourcePackRequest;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/** Server-level operations: info, console commands and resource packs. */
final class ServerOps {
    private final Server server;
    private final MainThread main;
    private final ServerFacts facts;
    private final Function<Consumer<Component>, CommandSender> senders;
    private final AtomicReference<ResourcePackRequest> pack = new AtomicReference<>();

    ServerOps(Server server, MainThread main, ServerFacts facts) {
        this(server, main, facts, server::createCommandSender);
    }

    /** @param senders makes a command sender that passes the feedback it receives to the consumer */
    ServerOps(Server server, MainThread main, ServerFacts facts, Function<Consumer<Component>, CommandSender> senders) {
        this.server = server;
        this.main = main;
        this.facts = facts;
        this.senders = senders;
    }

    Ops.ServerInfo info() {
        return main.call(() -> new Ops.ServerInfo("paper", facts.platformVersion(), facts.minecraftVersion(),
                server.getWorlds().stream().map(World::getName).toList(),
                server.getOnlinePlayers().size(), facts.tps()));
    }

    /**
     * Runs a command as a console-like sender and captures the feedback it sends. Success means the
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
            Consumer<Component> feedback = c -> output.add(PlainTextComponentSerializer.plainText().serialize(c));
            CommandSender sender = senders.apply(feedback);
            boolean success = server.dispatchCommand(sender, line);
            return new Ops.CommandResult(success, List.copyOf(output));
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
            for (Player player : server.getOnlinePlayers()) {
                player.sendResourcePacks(request);
                sent++;
            }
            return new Ops.PackResult(sent);
        });
    }

    /** Send the current pack, if any, to a player who just joined. Call on the main thread. */
    void onJoin(Player player) {
        ResourcePackRequest request = pack.get();
        if (request != null) {
            player.sendResourcePacks(request);
        }
    }
}
