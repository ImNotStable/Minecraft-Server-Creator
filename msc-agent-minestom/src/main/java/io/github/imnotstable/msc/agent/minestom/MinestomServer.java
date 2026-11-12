package io.github.imnotstable.msc.agent.minestom;

import io.github.imnotstable.msc.agent.common.AgentServer;
import java.net.URI;
import java.util.Collection;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;
import net.kyori.adventure.resource.ResourcePackInfo;
import net.kyori.adventure.resource.ResourcePackRequest;
import net.minestom.server.command.CommandManager;
import net.minestom.server.command.builder.CommandResult;
import net.minestom.server.entity.Player;

/** {@link AgentServer} on a Minestom server. */
final class MinestomServer implements AgentServer {
    static final String PLATFORM = "minestom";

    private final CommandManager commands;
    private final Worlds worlds;
    private final Supplier<? extends Collection<Player>> players;
    private final ServerFacts facts;

    MinestomServer(CommandManager commands, Worlds worlds, Supplier<? extends Collection<Player>> players, ServerFacts facts) {
        this.commands = commands;
        this.worlds = worlds;
        this.players = players;
        this.facts = facts;
    }

    @Override
    public String platform() {
        return PLATFORM;
    }

    @Override
    public String platformVersion() {
        return facts.platformVersion();
    }

    @Override
    public String minecraftVersion() {
        return facts.minecraftVersion();
    }

    @Override
    public double tps() {
        return facts.tps();
    }

    @Override
    public List<String> worldNames() {
        return worlds.names();
    }

    @Override
    public int onlinePlayers() {
        return players.get().size();
    }

    /**
     * Minestom has no vanilla commands: only commands the server registered exist. Success means the
     * command was found and parsed; an unknown command or bad syntax fails with a line saying which.
     */
    @Override
    public boolean runCommand(String line, Consumer<String> feedback) {
        CapturingSender sender = new CapturingSender();
        CommandResult result = commands.execute(sender, line);
        List<String> output = sender.output();
        output.forEach(feedback);
        switch (result.getType()) {
            case UNKNOWN -> feedback.accept("Unknown command: " + line.split(" ", 2)[0]);
            case INVALID_SYNTAX -> {
                if (output.isEmpty()) {
                    feedback.accept("Invalid syntax: " + line);
                }
            }
            default -> {
            }
        }
        return result.getType() == CommandResult.Type.SUCCESS;
    }

    @Override
    public int sendPack(Pack pack) {
        ResourcePackRequest request = request(pack);
        int sent = 0;
        for (Player player : players.get()) {
            player.sendResourcePacks(request);
            sent++;
        }
        return sent;
    }

    /** The pack as a request that replaces whatever packs the player has. */
    static ResourcePackRequest request(Pack pack) {
        return ResourcePackRequest.resourcePackRequest()
                .packs(ResourcePackInfo.resourcePackInfo(pack.id(), URI.create(pack.url()), pack.sha1()))
                .required(pack.required())
                .replace(true)
                .build();
    }
}
