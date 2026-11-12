package io.github.imnotstable.msc.core.tools;

import io.github.imnotstable.msc.bot.Bot;
import io.github.imnotstable.msc.bot.Bots;
import io.github.imnotstable.msc.core.CoreConfig;
import io.github.imnotstable.msc.core.agent.AgentHub;
import io.github.imnotstable.msc.core.tool.Budget;
import io.github.imnotstable.msc.core.tool.Schema;
import io.github.imnotstable.msc.core.tool.ToolException;
import io.github.imnotstable.msc.core.tool.ToolRegistry;
import io.github.imnotstable.msc.core.world.Walk;
import io.github.imnotstable.msc.core.world.WorldService;
import io.github.imnotstable.msc.protocol.op.Ops;
import io.github.imnotstable.msc.protocol.world.BlockPos;
import io.github.imnotstable.msc.protocol.world.Region;
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
    /** Blocks around the two ends a walk route may wander through. */
    private static final int ROUTE_MARGIN = 16;
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_]{3,16}");
    private final CoreConfig config;
    private final AgentHub hub;
    private final WorldService worlds;
    private Bot player;
    private volatile String world = "world";

    private PlayerTools(CoreConfig config, AgentHub hub, WorldService worlds) {
        this.config = config;
        this.hub = hub;
        this.worlds = worlds;
    }

    public static PlayerTools register(ToolRegistry registry, CoreConfig config, AgentHub hub, WorldService worlds) {
        PlayerTools t = new PlayerTools(config, hub, worlds);
        registry.add("player_test",
                "Play-test: a headless player joins the dev server, runs actions, reports results, damage, deaths, "
                        + "chat, titles. Stays joined. Vanilla-like moves; a missed jump may suit people. "
                        + "inventory/hud: items, menu, bossbars, sidebar, pack.",
                Schema.of().req("actions", "strings", "goto x,y,z|forward ticks [sprint] [jump], walk x,y,z (path route), jump, "
                                + "look yaw pitch, lookat x,y,z, wait ticks, use [x,y,z], attack|interact [type], "
                                + "slot 1-9, say text, /command, tp x,y,z, entities, inventory, hud, close")
                        .opt("name", "string", "Default MscTester")
                        .opt("gamemode", "string", "survival, adventure, creative. Default: the game's")
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
        // No game mode given: keep the one the game gives its players, which is what a play-test should see.
        String gamemode = args.str("gamemode", null);
        if (gamemode != null && !List.of("survival", "adventure", "creative").contains(gamemode)) {
            throw new ToolException("gamemode must be survival, adventure or creative");
        }
        Ops.ServerInfo info = hub.call(Ops.SERVER_INFO, new Ops.Empty());
        world = info.worlds().isEmpty() ? "world" : info.worlds().getFirst();
        StringBuilder out = new StringBuilder();
        if (player != null && (!player.connected() || !player.name().equals(name))) {
            player.close();
            player = null;
        }
        if (player == null) {
            // Bots pings the server and picks the client for its version, or says which versions it speaks.
            String[] address = address();
            try {
                player = Bots.join(name, address[0], Integer.parseInt(address[1]),
                        region -> hub.call(Ops.WORLD_READ, new Ops.ReadParams(region, false)).grid(), this::route,
                        Duration.ofSeconds(20), config.installDir() == null ? null : config.installDir().resolve("bot"));
            } catch (IllegalStateException e) {
                throw new ToolException(e.getMessage() + (e.getMessage().contains("not_whitelisted")
                        // whitelist add can't help: offline mode stores the lowercased name's UUID.
                        ? ". The whitelist is on: server_command \"whitelist off\", and set white-list=false"
                                + " in server.properties so it stays off"
                        : ""));
            }
            // A dev-server test player: op so commands and tp work, in the mode real players will have.
            // Minestom has only the commands the server registered, so these may not exist there.
            boolean op = console("op " + name);
            boolean mode = gamemode != null && console("gamemode " + gamemode + " " + name);
            out.append("joined as ").append(name).append(" (")
                    .append(mode ? gamemode : gamemode == null ? "the game's own mode"
                            : "game mode left to the server: it has no gamemode command")
                    .append(op ? ", op" : ", not op: the server has no op command").append(")\n");
        } else if (gamemode != null) {
            console("gamemode " + gamemode + " " + name);
        }
        try {
            out.append(player.run(actions, world));
        } catch (IllegalArgumentException e) {
            throw new ToolException(e.getMessage());
        }
        if (!player.connected()) {
            // A kick is a failed play-test, not a result: say so first and keep what happened before it.
            String why = player.disconnectReason();
            player.close();
            player = null;
            throw new ToolException("the test player was kicked" + (why != null ? ": " + why : "") + "\n"
                    + Budget.truncate(out.toString(), Budget.DEFAULT_CHARS, "Use fewer actions per call."));
        }
        if (args.bool("leave", false)) {
            player.close();
            player = null;
            out.append("\nleft the server");
        }
        return Budget.truncate(out.toString(), Budget.DEFAULT_CHARS, "Use fewer actions per call.");
    }

    /** The walk action's route: world_summary path's search over the blocks around both ends. */
    private List<int[]> route(BlockPos from, BlockPos to) throws Exception {
        int margin = ROUTE_MARGIN;
        Region region;
        do {
            region = Region.of(world, Math.min(from.x(), to.x()) - margin, Math.min(from.y(), to.y()) - 4,
                    Math.min(from.z(), to.z()) - margin, Math.max(from.x(), to.x()) + margin,
                    Math.max(from.y(), to.y()) + 4, Math.max(from.z(), to.z()) + margin);
            margin /= 2;
        } while (region.volume() > WorldService.MAX_BULK_VOLUME && margin > 0);
        try {
            return new Walk(worlds.readAll(region, WorldService.MAX_BULK_VOLUME, "walk")).steps(from, to);
        } catch (ToolException e) {
            // The region is ours, not the caller's: drop the advice about it.
            throw new ToolException(e.getMessage().replace(", and include the floor layer in the region", ""));
        }
    }

    /** Runs a console command; false only when the server has no such command (a repeated op also fails). */
    private boolean console(String command) throws Exception {
        Ops.CommandResult result = hub.call(Ops.SERVER_COMMAND, new Ops.CommandParams(command));
        String text = String.join(" ", result.output()).toLowerCase(java.util.Locale.ROOT);
        return result.success() || !(text.contains("unknown") && text.contains("command"));
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
