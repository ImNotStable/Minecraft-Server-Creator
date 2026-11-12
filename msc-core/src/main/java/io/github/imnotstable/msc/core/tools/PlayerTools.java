package io.github.imnotstable.msc.core.tools;

import io.github.imnotstable.msc.bot.TestPlayer;
import io.github.imnotstable.msc.core.CoreConfig;
import io.github.imnotstable.msc.core.agent.AgentHub;
import io.github.imnotstable.msc.core.tool.Budget;
import io.github.imnotstable.msc.core.tool.Schema;
import io.github.imnotstable.msc.core.tool.ToolException;
import io.github.imnotstable.msc.core.tool.ToolRegistry;
import io.github.imnotstable.msc.protocol.op.Ops;
import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Properties;
import java.util.regex.Pattern;

/** A headless test player, so the AI can play what it built: walk, jump gaps, press buttons, fight, and read back. */
public final class PlayerTools implements AutoCloseable {
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_]{3,16}");
    private final CoreConfig config;
    private final AgentHub hub;
    private TestPlayer player;

    private PlayerTools(CoreConfig config, AgentHub hub) {
        this.config = config;
        this.hub = hub;
    }

    public static PlayerTools register(ToolRegistry registry, CoreConfig config, AgentHub hub) {
        PlayerTools t = new PlayerTools(config, hub);
        registry.add("player_test",
                "Play-test: a headless player joins the dev server, runs the actions and reports results, "
                        + "damage, deaths, chat, titles and the pack offer. Stays joined between calls. Vanilla-like "
                        + "movement; a missed jump may still be fine for people.",
                Schema.of().req("actions", "strings", "goto x,y,z [sprint] [jump], forward ticks [sprint] [jump], jump, "
                                + "look yaw pitch, lookat x,y,z, wait ticks, use [x,y,z], attack, slot 1-9, say text, "
                                + "/command, tp x,y,z, entities")
                        .opt("name", "string", "Player name. Default MscTester")
                        .opt("gamemode", "string", "survival (default), adventure or creative")
                        .opt("leave", "boolean", "Disconnect after. Default false"),
                t::run);
        return t;
    }

    private synchronized String run(io.github.imnotstable.msc.core.tool.Args args) throws Exception {
        if (hub.current() == null) {
            throw new ToolException("the server is not running with the agent. Start it with server_start first.");
        }
        List<String> actions = args.strings("actions");
        if (actions.isEmpty()) {
            throw new ToolException("actions is empty");
        }
        String name = args.str("name", "MscTester");
        if (!NAME.matcher(name).matches()) {
            throw new ToolException("name must be 3 to 16 letters, digits or _");
        }
        String gamemode = args.str("gamemode", "survival");
        if (!List.of("survival", "adventure", "creative").contains(gamemode)) {
            throw new ToolException("gamemode must be survival, adventure or creative");
        }
        Ops.ServerInfo info = hub.call(Ops.SERVER_INFO, new Ops.Empty());
        String world = info.worlds().isEmpty() ? "world" : info.worlds().getFirst();
        StringBuilder out = new StringBuilder();
        if (player != null && (!player.connected() || !player.name().equals(name))) {
            player.close();
            player = null;
        }
        if (player == null) {
            if (info.minecraftVersion() != null && !info.minecraftVersion().equals(TestPlayer.MINECRAFT_VERSION)) {
                throw new ToolException("this " + info.platform() + " server runs Minecraft " + info.minecraftVersion()
                        + " but the test player speaks " + TestPlayer.MINECRAFT_VERSION + ", so it cannot join."
                        + ("minestom".equals(info.platform())
                                ? " Test players in Minestom's test environment instead (@EnvTest, env.createPlayer, env.tick)."
                                : ""));
            }
            String[] address = address();
            try {
                player = TestPlayer.join(name, address[0], Integer.parseInt(address[1]),
                        region -> hub.call(Ops.WORLD_READ, new Ops.ReadParams(region, false)).grid(),
                        Duration.ofSeconds(20));
            } catch (IllegalStateException e) {
                throw new ToolException(e.getMessage());
            }
            // A dev-server test player: op so commands and tp work, in the mode real players will have.
            hub.call(Ops.SERVER_COMMAND, new Ops.CommandParams("op " + name));
            hub.call(Ops.SERVER_COMMAND, new Ops.CommandParams("gamemode " + gamemode + " " + name));
            out.append("joined as ").append(name).append(" (").append(gamemode).append(", op)\n");
        } else if (args.has("gamemode")) {
            hub.call(Ops.SERVER_COMMAND, new Ops.CommandParams("gamemode " + gamemode + " " + name));
        }
        try {
            out.append(player.run(actions, world));
        } catch (IllegalArgumentException e) {
            throw new ToolException(e.getMessage());
        }
        if (args.bool("leave", false)) {
            player.close();
            player = null;
            out.append("\nleft the server");
        }
        return Budget.truncate(out.toString(), Budget.DEFAULT_CHARS, "Use fewer actions per call.");
    }

    /** server-ip and server-port from the dev server's server.properties. */
    private String[] address() throws IOException {
        Path file = config.serverDir().resolve("server.properties");
        Properties p = new Properties();
        if (Files.exists(file)) {
            try (Reader in = Files.newBufferedReader(file)) {
                p.load(in);
            }
        }
        String ip = p.getProperty("server-ip", "").isBlank() ? "127.0.0.1" : p.getProperty("server-ip").strip();
        return new String[] {ip, p.getProperty("server-port", "25565").strip()};
    }

    @Override
    public synchronized void close() {
        if (player != null) {
            player.close();
            player = null;
        }
    }
}
