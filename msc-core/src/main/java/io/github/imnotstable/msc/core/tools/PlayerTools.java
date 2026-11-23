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
import io.github.imnotstable.msc.core.server.Platform;
import io.github.imnotstable.msc.core.server.ProxyForwarding;
import io.github.imnotstable.msc.process.ServerAddress;
import io.github.imnotstable.msc.process.ServerPropertiesFile;
import io.github.imnotstable.msc.protocol.op.Ops;
import io.github.imnotstable.msc.protocol.world.BlockPos;
import io.github.imnotstable.msc.protocol.world.Region;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** A headless test player, so the AI can play what it built: walk, jump gaps, press buttons, fight, and read back. */
public final class PlayerTools implements AutoCloseable {
    /** Blocks around the two ends a walk route may wander through. */
    private static final int ROUTE_MARGIN = 16;
    /** How far from where the bot thinks it stands the agent looks for it: the server's position may lag a little. */
    static final int LOCATE_REACH = 4;
    private static final String PLAYER = "minecraft:player";
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_]{3,16}");
    private final CoreConfig config;
    private final AgentHub hub;
    private final WorldService worlds;
    private final WorldBackups backups;
    /**
     * Guards {@link #player} and {@link #closed}, never held across network I/O: {@link #close()} takes only this, so
     * it does not wait for a running player_test (which holds this object's monitor for its whole run).
     */
    private final Object slot = new Object();
    private Bot player;
    private boolean closed;

    private PlayerTools(CoreConfig config, AgentHub hub, WorldService worlds, WorldBackups backups) {
        this.config = config;
        this.hub = hub;
        this.worlds = worlds;
        this.backups = backups;
    }

    public static PlayerTools register(ToolRegistry registry, CoreConfig config, AgentHub hub, WorldService worlds,
                                       WorldBackups backups) {
        PlayerTools t = new PlayerTools(config, hub, worlds, backups);
        registry.add("player_test",
                "Play-test: a headless player joins the dev server, runs actions, reports results, damage, deaths, "
                        + "chat, titles. Stays joined. Vanilla-like moves; a missed jump may suit people. "
                        + "inventory/hud: items, menu, bossbars, sidebar, served pack lint, sounds, glyphs it lacks.",
                Schema.of().req("actions", "strings", "goto x,y,z|forward ticks [sprint] [jump], walk x,y,z (path route), jump, "
                                + "look yaw pitch, lookat x,y,z, wait ticks, use [x,y,z], attack|interact [type|name], "
                                + "click menu-slot|chat-text, slot 1-9, say text, /command, tp x,y,z, entities, inventory, "
                                + "hud, close")
                        .opt("name", "string", "Default MscTester")
                        .opt("gamemode", "string", "survival, adventure, creative. Default: the game's")
                        .opt("leave", "boolean", "Disconnect after"),
                t::run);
        return t;
    }

    private synchronized String run(io.github.imnotstable.msc.core.tool.Args args) throws Exception {
        synchronized (slot) {
            if (closed) {
                throw ToolException.workspaceClosed("player_test did not run");
            }
        }
        // Behind a proxy with a network (ADR 0088) the player joins the proxy, which has no agent: no block view.
        boolean proxy = Platform.current(config).proxy();
        // The same reason as the world tools give (#705, #723), else that this session's server has no agent (yet).
        boolean connected = proxy || hub.current() != null;
        String refused = proxy ? null : connected ? hub.stopping() : hub.refusal();
        if (!connected || refused != null) {
            throw new ToolException(refused != null ? refused
                    : "the server is not running with the agent. Start it with server_start first.");
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
        boolean leave = args.bool("leave", false);
        if (proxy) {
            if (gamemode != null) {
                throw new ToolException("gamemode is set on a backend behind the proxy: server_command \"@<backend> "
                        + "gamemode " + gamemode + " " + name + "\"");
            }
            for (String a : actions) {
                if (a.strip().matches("(?i)(goto|walk|forward|jump)\\b.*")) {
                    throw new ToolException("\"" + a.strip() + "\": through the proxy the test player sees no blocks "
                            + "(the proxy has no agent), so it does not walk or jump; tp, /commands, use, interact, "
                            + "click, say, hud and inventory work");
                }
            }
        }
        // Every action is read before anything happens (#717): a refusal leaves no player joined, dropped, made op or
        // switched to another game mode, as build_batch checks every block name before it places one.
        try {
            Bots.check(actions);
        } catch (IllegalArgumentException e) {
            throw new ToolException(e.getMessage());
        }
        // A player that uses, attacks or runs commands may change the world: a backup first, as for build_batch.
        String backup = proxy ? "" : actions.stream().map(String::strip).anyMatch(a -> a.matches("(use|attack|interact)\\b.*"))
                ? backups.beforeEdit() : backups.beforeCommands(String.join("\n",
                        actions.stream().map(String::strip).filter(a -> a.startsWith("/")).toList()));
        // The overworld; the test player asks worldOf which world the server put it in, else goes by the dimension.
        // Behind a proxy: none, so the player stands where the server puts it.
        List<String> worlds = proxy ? List.of() : hub.call(Ops.SERVER_INFO, new Ops.Empty()).worlds();
        String world = proxy ? null : worlds.isEmpty() ? "world" : worlds.getFirst();
        StringBuilder out = new StringBuilder(backup.isEmpty() ? "" : backup + "\n");
        Bot bot = current();
        if (bot != null && (!bot.connected() || !bot.name().equals(name))) {
            drop(bot);
            bot = null;
        }
        if (bot == null) {
            // Bots pings the server and picks the client for its version, or says which versions it speaks.
            ServerAddress address = proxy ? new ServerAddress("127.0.0.1",
                    ProxyForwarding.listenPort(config.serverDir(), Platform.current(config))) : address();
            try {
                bot = adopt(proxy ? Bots.join(name, address.host(), address.port(), region -> {
                    throw new IllegalStateException("the proxy has no world");
                }, null, null, Duration.ofSeconds(20), config.installDir() == null ? null
                        : config.installDir().resolve("bot")) : Bots.join(name, address.host(), address.port(),
                        region -> hub.call(Ops.WORLD_READ, new Ops.ReadParams(region, false)).grid(), this::route,
                        (who, at) -> worldOf(hub.call(Ops.SERVER_INFO, new Ops.Empty()).worlds(), who, at,
                                region -> hub.call(Ops.WORLD_ENTITIES, new Ops.EntitiesParams(region, PLAYER, 0))
                                        .entities()),
                        Duration.ofSeconds(20), config.installDir() == null ? null : config.installDir().resolve("bot")));
            } catch (IllegalStateException e) {
                throw new ToolException(e.getMessage() + (e.getMessage().contains("not_whitelisted")
                        // whitelist add can't help: offline mode stores the lowercased name's UUID.
                        ? ". The whitelist is on: server_command \"whitelist off\", and set white-list=false"
                                + " in " + ServerPropertiesFile.NAME + " so it stays off"
                        : ""));
            }
            // A dev-server test player: op so commands and tp work, in the mode real players will have.
            // Minestom has only the commands the server registered, so these may not exist there.
            boolean op = !proxy && console("op " + name);
            String mode = gamemode == null ? "the game's own mode" : gamemode(gamemode, name);
            out.append("joined as ").append(name).append(" (").append(mode)
                    .append(op ? ", op" : proxy ? ", through the proxy; not op: server_command \"@<backend> op "
                            + name + "\"" : ", not op: the server has no op command").append(")\n");
        } else if (gamemode != null && !proxy) {
            // Already joined: said only when the mode was not set, so the actions are not read as in that mode (#626).
            String mode = gamemode(gamemode, name);
            if (!mode.equals(gamemode)) {
                out.append(mode).append('\n');
            }
        }
        try {
            out.append(bot.run(actions, world));
        } catch (IllegalArgumentException e) {
            throw new ToolException(e.getMessage());
        }
        if (!bot.connected()) {
            // A kick is a failed play-test, not a result: say so first and keep what happened before it.
            String why = bot.disconnectReason();
            drop(bot);
            synchronized (slot) {
                if (closed) {
                    throw ToolException.workspaceClosed("the test player was disconnected");
                }
            }
            // One budget (#473): the kick line and "error: " are kept, what happened before it gets the rest.
            throw new ToolException(new Budget.Parts()
                    .add("the test player was kicked" + (why != null ? ": " + why : "") + "\n").cut(cut(out))
                    .text(Budget.DEFAULT_CHARS - "error: ".length(), null, FEWER));
        }
        String left = null;
        if (leave) {
            drop(bot);
            left = "left the server";
        }
        return new Budget.Parts().cut(cut(out)).text(Budget.DEFAULT_CHARS, left, FEWER);
    }

    /** Lists the entities of one type in a region; the agent's {@code world.entities}. */
    @FunctionalInterface
    interface EntityLookup {
        List<Ops.EntityInfo> find(Region region) throws Exception;
    }

    /**
     * The world in which the agent lists player {@code who} near {@code at}, or null. The bot cannot tell this from
     * its packets: Minestom moves a player between instances of one dimension without a Respawn and names its
     * instances its own way.
     */
    static String worldOf(List<String> worlds, String who, BlockPos at, EntityLookup players) throws Exception {
        for (String world : worlds) {
            Region near = Region.of(world, at.x() - LOCATE_REACH, at.y() - LOCATE_REACH, at.z() - LOCATE_REACH,
                    at.x() + LOCATE_REACH, at.y() + LOCATE_REACH, at.z() + LOCATE_REACH);
            for (Ops.EntityInfo e : players.find(near)) {
                if (who.equals(e.name())) {
                    return world;
                }
            }
        }
        return null;
    }

    /** The walk action's route: world_summary path's search over the blocks around both ends, in the player's world. */
    private List<int[]> route(String world, BlockPos from, BlockPos to) throws Exception {
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
        return !unknownCommand(hub.call(Ops.SERVER_COMMAND, new Ops.CommandParams(command)));
    }

    /** True when the server answered that it has no such command. */
    private static boolean unknownCommand(Ops.CommandResult result) {
        String text = String.join(" ", result.output()).toLowerCase(java.util.Locale.ROOT);
        return !result.success() && text.contains("unknown") && text.contains("command");
    }

    /** Sets the test player's game mode; what {@link #gameModeSet} says came of it. */
    private String gamemode(String mode, String name) throws Exception {
        return gameModeSet(mode, hub.call(Ops.SERVER_COMMAND, new Ops.CommandParams("gamemode " + mode + " " + name)));
    }

    /** Longest server reply a failed gamemode line quotes. */
    private static final int REPLY_CHARS = 200;

    /**
     * What came of {@code gamemode <mode> <player>}, from the server's answer {@code result}: {@code mode} when it was
     * set, else why not. A server with no such command (Minestom has only the commands the project registers) leaves
     * the mode to the server; any other failure that says why quotes the server. A failure that says nothing counts as
     * set, as vanilla answers a player already in that mode (#626).
     */
    static String gameModeSet(String mode, Ops.CommandResult result) {
        if (unknownCommand(result)) {
            return "game mode left to the server: it has no gamemode command";
        }
        String reply = String.join(" ", result.output()).strip();
        if (result.success() || reply.isEmpty()) {
            return mode;
        }
        return "game mode not set to " + mode + ": " + (reply.length() > REPLY_CHARS
                ? reply.substring(0, REPLY_CHARS) + "..." : reply);
    }

    /**
     * Where the dev server takes players: server-ip and server-port from its server.properties, read as the server
     * reads them and as server_stop's RCON reads them ({@link ServerAddress}).
     */
    private ServerAddress address() throws ToolException {
        Map<String, String> p;
        try {
            p = ServerPropertiesFile.read(config.serverDir().resolve(ServerPropertiesFile.NAME));
        } catch (IOException e) {
            throw new ToolException("cannot read " + ServerPropertiesFile.NAME + ": " + e.getMessage());
        }
        ServerAddress address = ServerAddress.game(p);
        if (address == null) {
            throw new ToolException(ServerAddress.GAME_PORT_KEY + "=" + p.get(ServerAddress.GAME_PORT_KEY) + " in "
                    + ServerPropertiesFile.NAME + " is not a port from 1 to 65535, so the server takes no players."
                    + " Set it to the port to join on and restart the server");
        }
        return address;
    }

    /** The joined test player, or null. */
    private Bot current() {
        synchronized (slot) {
            return player;
        }
    }

    /**
     * Keep a player that just joined, unless the workspace closed while it joined: then it leaves at once, since
     * nothing would disconnect it later.
     */
    Bot adopt(Bot joined) {
        synchronized (slot) {
            if (!closed) {
                player = joined;
                return joined;
            }
        }
        joined.close();
        throw ToolException.workspaceClosed("the test player left again");
    }

    /** Disconnect {@code bot} and forget it if it is still the current one. */
    private void drop(Bot bot) {
        synchronized (slot) {
            if (player == bot) {
                player = null;
            }
        }
        bot.close();
    }

    /** True while a test player is joined: a workspace switch says it left. */
    public boolean joined() {
        return current() != null;
    }

    /**
     * Disconnect the test player and refuse later play-tests. Does not wait for a running player_test: closing the
     * bot ends what it was doing.
     */
    @Override
    public void close() {
        synchronized (slot) {
            closed = true;
        }
        leave();
    }

    /** Disconnect the test player; a later player_test joins again (a switch away and back, #735). */
    public void leave() {
        Bot left;
        synchronized (slot) {
            left = player;
            player = null;
        }
        if (left != null) {
            left.close();
        }
    }

    private static final String FEWER = "Use fewer actions per call.";

    private static java.util.function.IntFunction<String> cut(StringBuilder out) {
        String text = out.toString();
        return n -> Budget.truncate(text, n, FEWER);
    }
}
