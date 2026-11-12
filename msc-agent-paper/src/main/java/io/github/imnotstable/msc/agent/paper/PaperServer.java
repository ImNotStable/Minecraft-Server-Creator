package io.github.imnotstable.msc.agent.paper;

import io.github.imnotstable.msc.agent.common.AgentServer;
import java.net.URI;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import net.kyori.adventure.resource.ResourcePackInfo;
import net.kyori.adventure.resource.ResourcePackRequest;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.command.CommandException;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/** {@link AgentServer} on a Bukkit {@link Server}. */
final class PaperServer implements AgentServer {
    static final String PLATFORM = "paper";

    private final Server server;
    private final ServerFacts facts;
    private final Function<Consumer<Component>, CommandSender> senders;

    PaperServer(Server server, ServerFacts facts) {
        this(server, facts, server::createCommandSender);
    }

    /** @param senders makes a command sender that passes the feedback it receives to the consumer */
    PaperServer(Server server, ServerFacts facts, Function<Consumer<Component>, CommandSender> senders) {
        this.server = server;
        this.facts = facts;
        this.senders = senders;
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
        return server.getWorlds().stream().map(World::getName).toList();
    }

    @Override
    public int onlinePlayers() {
        return server.getOnlinePlayers().size();
    }

    @Override
    public boolean runCommand(String line, Consumer<String> feedback) {
        CommandSender sender = senders.apply(c -> feedback.accept(PlainTextComponentSerializer.plainText().serialize(c)));
        try {
            return server.dispatchCommand(sender, line);
        } catch (CommandException e) {
            // Bukkit wraps what the command threw, a syntax error included; the innermost message says what is wrong.
            feedback.accept(rootMessage(e));
            return false;
        }
    }

    static String rootMessage(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null && t.getCause() != t) {
            t = t.getCause();
        }
        return t.getMessage() != null ? t.getMessage() : t.toString();
    }

    @Override
    public int sendPack(Pack pack) {
        ResourcePackRequest request = request(pack);
        int sent = 0;
        for (Player player : server.getOnlinePlayers()) {
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
