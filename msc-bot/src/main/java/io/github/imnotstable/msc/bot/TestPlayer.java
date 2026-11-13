package io.github.imnotstable.msc.bot;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.cloudburstmc.math.vector.Vector3d;
import org.cloudburstmc.math.vector.Vector3i;
import org.geysermc.mcprotocollib.network.ClientSession;
import org.geysermc.mcprotocollib.network.Session;
import org.geysermc.mcprotocollib.network.event.session.DisconnectedEvent;
import org.geysermc.mcprotocollib.network.event.session.SessionAdapter;
import org.geysermc.mcprotocollib.network.factory.ClientNetworkSessionFactory;
import org.geysermc.mcprotocollib.network.packet.Packet;
import org.geysermc.mcprotocollib.protocol.MinecraftProtocol;
import org.geysermc.mcprotocollib.protocol.codec.MinecraftCodec;
import org.geysermc.mcprotocollib.protocol.data.ProtocolState;
import org.geysermc.mcprotocollib.protocol.data.game.ClientCommand;
import org.geysermc.mcprotocollib.protocol.data.game.ResourcePackStatus;
import org.geysermc.mcprotocollib.protocol.data.game.entity.object.Direction;
import org.geysermc.mcprotocollib.protocol.data.game.entity.player.Hand;
import org.geysermc.mcprotocollib.protocol.data.game.entity.player.PlayerState;
import org.geysermc.mcprotocollib.protocol.data.game.entity.player.PositionElement;
import org.geysermc.mcprotocollib.protocol.data.game.level.notify.EnterCreditsValue;
import org.geysermc.mcprotocollib.protocol.data.game.level.notify.RespawnScreenValue;
import org.geysermc.mcprotocollib.protocol.packet.common.clientbound.ClientboundPingPacket;
import org.geysermc.mcprotocollib.protocol.packet.common.clientbound.ClientboundResourcePackPushPacket;
import org.geysermc.mcprotocollib.protocol.packet.common.serverbound.ServerboundPongPacket;
import org.geysermc.mcprotocollib.protocol.packet.common.serverbound.ServerboundResourcePackPacket;
import org.geysermc.mcprotocollib.protocol.packet.configuration.clientbound.ClientboundCodeOfConductPacket;
import org.geysermc.mcprotocollib.protocol.packet.configuration.serverbound.ServerboundAcceptCodeOfConductPacket;
import org.geysermc.mcprotocollib.protocol.packet.cookie.clientbound.ClientboundCookieRequestPacket;
import org.geysermc.mcprotocollib.protocol.packet.cookie.serverbound.ServerboundCookieResponsePacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundLoginPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundPlayerChatPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundRespawnPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundStartConfigurationPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundSystemChatPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.ClientboundEntityPositionSyncPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.ClientboundMoveEntityPosPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.ClientboundMoveEntityPosRotPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.ClientboundRemoveEntitiesPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.ClientboundTeleportEntityPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.player.ClientboundPlayerCombatKillPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.player.ClientboundPlayerPositionPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.player.ClientboundSetHealthPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.ClientboundAddEntityPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.level.ClientboundChunkBatchFinishedPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.level.ClientboundGameEventPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.title.ClientboundSetActionBarTextPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.title.ClientboundSetSubtitleTextPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.title.ClientboundSetTitleTextPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.ServerboundChatCommandPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.ServerboundChatPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.ServerboundClientCommandPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.ServerboundClientTickEndPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.ServerboundPlayerLoadedPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.inventory.ServerboundContainerClosePacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.level.ServerboundChunkBatchReceivedPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.level.ServerboundPlayerInputPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundAttackPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundInteractPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundMovePlayerPosRotPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundPlayerCommandPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundSetCarriedItemPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundUseItemOnPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundUseItemPacket;
import org.geysermc.mcprotocollib.protocol.packet.login.clientbound.ClientboundCustomQueryPacket;
import org.geysermc.mcprotocollib.protocol.packet.login.serverbound.ServerboundCustomQueryAnswerPacket;

/**
 * A headless player on the dev server: an offline-mode client that moves with its own physics against blocks read
 * through the agent, sends what a real client sends each tick, and records what the server tells it.
 */
public final class TestPlayer implements Bot {
    /** A tracked entity near the player. */
    record Tracked(String type, double x, double y, double z) {
    }

    private final String name;
    /** The connection; null for a player built by {@link #offline}, which only records what it sends. */
    private final ClientSession session;
    private final java.util.function.Consumer<Packet> sender;
    /** The state the connection encodes outgoing packets in: a packet the state has no id for would end it. */
    private final java.util.function.Supplier<ProtocolState> outbound;
    private ScheduledExecutorService ticker;
    private final BlockSource blocks;
    private final RoutePlanner planner;
    /** Says which world the player is in; null to go by the dimension. */
    private final WorldLocator locator;
    /** Most events kept between two calls. */
    static final int MAX_EVENTS = 300;
    private final java.util.ArrayDeque<String> events = new java.util.ArrayDeque<>();
    private long droppedEvents;
    private final Map<Integer, Tracked> entities = new ConcurrentHashMap<>();
    private final AtomicInteger sequence = new AtomicInteger();
    private final CountDownLatch spawned = new CountDownLatch(1);
    private final Inventory inventory = new Inventory(Items.forVersion(minecraftVersion()));
    private final Hud hud = new Hud();
    private final Outbox outbox = new Outbox();
    /** A server move longer than this is a teleport, not a correction. */
    static final double TELEPORT = 2.0;
    final Physics body = new Physics();
    private WorldView view;
    /** The world the core named in the last call: the server's first, its overworld. */
    private String baseWorld;
    /** The dimension the server says the player is in, such as minecraft:the_nether; null before the login. */
    private String dimension;
    /**
     * Set when the server may have put the player in another world (a login, a respawn, a teleport, or the start of a
     * call, since Minestom moves a player between two instances of one dimension with no packet that says so) until
     * the locator says which world it is. The question goes out as soon as the player's position is known.
     */
    private boolean relocate;
    /**
     * Set by a dimension change (a login or a respawn) until the locator answers: the old world is surely wrong, so
     * the player does not simulate meanwhile. A teleport keeps the view, as it is most likely in the same world.
     */
    private boolean worldUnknown;
    /** Counts {@link #relocate} settings, so a locator answer that a newer world change overtook is dropped. */
    private long worldChanges;
    /** The question to the locator for world change {@link #lookupChange}; null before the first. */
    private java.util.concurrent.CompletableFuture<Void> lookup;
    private long lookupChange = -1;
    /** The world the locator found; null to go by the dimension. */
    private String located;
    static final String RELOCATING = "the server put the player in another world; finding out which";
    private volatile String disconnect;
    private volatile int entityId;
    /**
     * Alive, dead, waiting for a respawn or a position: every packet that changes it goes through {@link #on}, and
     * the tick asks it whether to move. After a login or a respawn the first position also tells the server the player
     * has loaded, like the vanilla client: until that, or a 60-tick timeout, the server ignores the player's moves and
     * keeps it invulnerable, even to /kill, and Paper restarts that wait on every world change.
     */
    private final Life life = new Life();
    float yaw;
    float pitch;
    private float health = 20;
    private int food = 20;
    private Physics.Input lastInput = Physics.Input.NONE;
    private boolean sprinting;
    private Script.Step current;
    private long ticks;
    private long serverMoves;

    private TestPlayer(String name, BlockSource blocks, RoutePlanner planner, WorldLocator locator,
                       ClientSession session, java.util.function.Consumer<Packet> sender,
                       java.util.function.Supplier<ProtocolState> outbound) {
        this.name = name;
        this.blocks = blocks;
        this.planner = planner;
        this.locator = locator;
        this.session = session;
        this.sender = sender;
        this.outbound = outbound;
    }

    /**
     * A player with no connection, for tests: packets go in through {@link #receive}, what it would send lands in
     * {@code sent}, and the test drives {@link #tick} itself.
     */
    static TestPlayer offline(String name, BlockSource blocks, RoutePlanner planner, List<Packet> sent) {
        return offline(name, blocks, planner, null, sent);
    }

    /** As {@link #offline(String, BlockSource, RoutePlanner, List)}, with a locator. */
    static TestPlayer offline(String name, BlockSource blocks, RoutePlanner planner, WorldLocator locator,
                              List<Packet> sent) {
        return offline(name, blocks, planner, locator, sent::add, () -> ProtocolState.GAME);
    }

    /** As {@link #offline(String, BlockSource, RoutePlanner, List)}, sending in the state {@code outbound} says. */
    static TestPlayer offline(String name, BlockSource blocks, RoutePlanner planner, WorldLocator locator,
                              java.util.function.Consumer<Packet> sender,
                              java.util.function.Supplier<ProtocolState> outbound) {
        return new TestPlayer(name, blocks, planner, locator, null, sender, outbound);
    }

    /** Join the server and wait until the player is in the world. */
    public static TestPlayer join(String name, String host, int port, BlockSource blocks, Duration timeout)
            throws InterruptedException {
        return join(name, host, port, blocks, null, timeout);
    }

    /** As {@link #join(String, String, int, BlockSource, Duration)}, with a planner for the walk action. */
    public static TestPlayer join(String name, String host, int port, BlockSource blocks, RoutePlanner planner,
                                  Duration timeout) throws InterruptedException {
        return join(name, host, port, blocks, planner, null, timeout);
    }

    /** As {@link #join(String, String, int, BlockSource, RoutePlanner, Duration)}, with a locator (see {@link Bots}). */
    public static TestPlayer join(String name, String host, int port, BlockSource blocks, RoutePlanner planner,
                                  WorldLocator locator, Duration timeout) throws InterruptedException {
        ClientSession session = ClientNetworkSessionFactory.factory().setAddress(host, port)
                .setProtocol(new MinecraftProtocol(name)).create();
        TestPlayer p = new TestPlayer(name, blocks, planner, locator, session, session::send,
                () -> session.getPacketProtocol().getOutboundState());
        p.session.addListener(p.new Listener());
        p.session.connect(true);
        // A kick also releases the latch, so check for one: being turned away is not a join.
        if (!p.spawned.await(timeout.toMillis(), TimeUnit.MILLISECONDS) || p.disconnect != null) {
            String why = p.disconnect;
            p.close();
            throw new IllegalStateException((why != null ? "kicked while joining " : "could not join ") + host + ":"
                    + port + " as " + name + (why != null ? ": " + why : " within " + timeout.toSeconds() + "s"));
        }
        p.ticker = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "msc-test-player");
            t.setDaemon(true);
            return t;
        });
        p.ticker.scheduleAtFixedRate(p::tick, 50, 50, TimeUnit.MILLISECONDS);
        return p;
    }

    /** The protocol number of the MCProtocolLib this class was loaded with, such as 777. */
    public static int protocolVersion() {
        return MinecraftCodec.CODEC.getProtocolVersion();
    }

    /** The Minecraft version of the MCProtocolLib this class was loaded with, such as 26.3. */
    public static String minecraftVersion() {
        return MinecraftCodec.CODEC.getMinecraftVersion();
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public String disconnectReason() {
        return disconnect;
    }

    @Override
    public boolean connected() {
        return disconnect == null && (session == null || session.isConnected());
    }

    @Override
    public String run(List<String> lines, String world) throws InterruptedException {
        List<Script.Step> steps = new ArrayList<>();
        for (String line : lines) {
            steps.add(Script.parse(line));
        }
        useWorld(world);
        recheckWorld();
        StringBuilder out = new StringBuilder();
        drainEvents(out); // what happened since the last call, joining included
        for (Script.Step step : steps) {
            if (!connected()) {
                out.append("stopped: disconnected").append(disconnect != null ? " (" + disconnect + ")" : "")
                        .append('\n');
                break;
            }
            begin(step);
            String result = await(step, Script.Step.WAIT);
            out.append(step.text()).append(": ").append(result).append('\n');
            drainEvents(out);
        }
        synchronized (this) {
            out.append(status());
        }
        return out.toString();
    }

    /** Simulate movement in the world the player is in; {@code base} is the server's first world. */
    synchronized void useWorld(String base) {
        baseWorld = base;
        refreshView();
    }

    /** Point the simulation at the player's world: the located one, else the dimension's; none while unknown. */
    private void refreshView() {
        String name = worldUnknown ? null : located != null ? located
                : baseWorld != null ? worldName(baseWorld, dimension) : null;
        if (name == null) {
            view = null;
        } else if (view == null || !view.world().equals(name)) {
            view = new WorldView(blocks, name);
        }
    }

    /**
     * The server may have put the player in another world: ask the locator. A dimension change ({@code dimension})
     * stops the simulation until the answer; a teleport or a call start keeps the current view meanwhile, so the
     * player keeps falling and walking. Without a locator the dimension alone says the world.
     */
    private synchronized void worldMayHaveChanged(boolean dimension) {
        if (locator == null) {
            return;
        }
        relocate = true;
        worldChanges++;
        if (dimension) {
            worldUnknown = true;
            located = null;
            refreshView();
        }
    }

    /** Ask the locator again at the start of a call: a server can swap the player's world in place, with no teleport. */
    private void recheckWorld() {
        synchronized (this) {
            if (locator == null || !life.positioned() || relocate) {
                return;
            }
            worldMayHaveChanged(false);
        }
        locate();
    }

    /**
     * Start asking the locator about the latest world change, unless that question is out already; null when there
     * is nothing to ask yet (no change, or no position: a login or respawn asks at the position that follows). The
     * question runs on its own thread, so the tick thread and the network thread never wait for the agent.
     */
    private synchronized java.util.concurrent.CompletableFuture<Void> startLocate() {
        if (!relocate || !life.positioned()) {
            return null;
        }
        if (lookup == null || lookupChange != worldChanges) {
            long change = worldChanges;
            io.github.imnotstable.msc.protocol.world.BlockPos at = feet();
            lookupChange = change;
            lookup = java.util.concurrent.CompletableFuture.runAsync(() -> ask(change, at),
                    r -> Thread.ofVirtual().name("msc-test-player-locate").start(r));
        }
        return lookup;
    }

    /** Ask the locator which world the player is in, and wait for the answer: a step runs in the right world. */
    void locate() {
        java.util.concurrent.CompletableFuture<Void> pending = startLocate();
        if (pending != null) {
            try {
                pending.get();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (java.util.concurrent.ExecutionException e) {
                // ask catches everything; nothing to report
            }
        }
    }

    /** One question to the locator, outside the lock. When it finds the player nowhere the bot goes by the dimension. */
    private void ask(long change, io.github.imnotstable.msc.protocol.world.BlockPos at) {
        String found;
        try {
            found = locator.locate(name, at);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            found = null;
        } catch (Exception e) {
            found = null;
        }
        synchronized (this) {
            if (change == worldChanges) {
                relocate = false;
                worldUnknown = false;
                located = found;
                refreshView();
            }
        }
    }

    private io.github.imnotstable.msc.protocol.world.BlockPos feet() {
        return new io.github.imnotstable.msc.protocol.world.BlockPos((int) Math.floor(body.x),
                (int) Math.floor(body.y + 0.01), (int) Math.floor(body.z));
    }

    /**
     * The agent's name for a dimension, as the vanilla-family agents name worlds (Paper's style): the overworld is
     * the base world, the vanilla nether and end add {@code _nether} and {@code _the_end}, any other is its id. Only
     * used without a locator, or when it finds the player nowhere: Minestom names instances its own way.
     */
    static String worldName(String base, String dimension) {
        if (dimension == null) {
            return base;
        }
        return switch (dimension) {
            case "minecraft:overworld" -> base;
            case "minecraft:the_nether" -> base + "_nether";
            case "minecraft:the_end" -> base + "_the_end";
            default -> dimension;
        };
    }

    /** The server sent the player to {@code key}'s world: by dying and respawning, or by a world change. */
    private synchronized void spawnedIn(String key) {
        dimension = key;
        worldMayHaveChanged(true);
        refreshView();
    }

    /** Make {@code step} the running step; the tick thread runs it from the next tick. */
    void begin(Script.Step step) {
        locate();
        step.prepare(this); // outside the lock: it may read the world through the agent
        synchronized (this) {
            step.start(this);
            current = step;
        }
    }

    /**
     * The step's result, waiting at most {@code wait}. A step that gives none in time is finished and dropped, so the
     * tick thread does not keep steering the player after the call has returned.
     */
    String await(Script.Step step, Duration wait) throws InterruptedException {
        String result = step.await(wait);
        synchronized (this) {
            step.finish(result);
            if (current == step) {
                current = null;
            }
        }
        return result;
    }

    synchronized Script.Step current() {
        return current;
    }

    String status() {
        return String.format("%s at %.2f,%.2f,%.2f yaw %.0f pitch %.0f%s, health %.1f, food %d%s", name, body.x,
                body.y, body.z, yaw, pitch, body.onGround ? " on ground" : " in air", health, food,
                life.dead() ? ", dead" : "");
    }

    void drainEvents(StringBuilder out) {
        synchronized (events) {
            int shown = Math.min(events.size(), 40);
            java.util.Iterator<String> it = events.iterator();
            for (int i = 0; i < shown; i++) {
                out.append("  ").append(it.next()).append('\n');
            }
            long more = events.size() - shown + droppedEvents;
            if (more > 0) {
                out.append("  (").append(more).append(" more)\n");
            }
            events.clear();
            droppedEvents = 0;
        }
    }

    void event(String text) {
        synchronized (events) {
            // Action bars repeat every second or so: keep one of each in a row.
            if (!events.isEmpty() && events.getLast().equals(text)) {
                return;
            }
            // A player left joined hears the server until the next call: keep the newest, count the rest.
            if (events.size() == MAX_EVENTS) {
                events.removeFirst();
                droppedEvents++;
            }
            events.add(text);
        }
    }

    int pendingEvents() {
        synchronized (events) {
            return events.size();
        }
    }

    /**
     * Send, unless the connection's state has no id for the packet: encoding it would end the connection, as a play
     * packet does during a re-configuration. Such a packet is dropped and reported instead.
     */
    private void send(Packet packet) {
        ProtocolState state = outbound.get();
        if (!sendable(state, packet)) {
            event("[not sent] " + packet.getClass().getSimpleName() + " during " + state);
            return;
        }
        sender.accept(packet);
    }

    static boolean sendable(ProtocolState state, Packet packet) {
        try {
            MinecraftCodec.CODEC.getCodec(state).getServerboundId(packet.getClass());
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    void tick() {
        try {
            synchronized (this) {
                ticks++;
                // A vanilla client stops ticking while the server re-configures it, until the next login and position.
                if (!connected() || outbound.get() != ProtocolState.GAME) {
                    return;
                }
                if (!act(life.tick()).has(Life.Action.MOVE)) {
                    return;
                }
                Script.Step step = current;
                if (view == null && step != null && step.endsOnTeleport() && relocate) {
                    step.finish("stopped: " + RELOCATING);
                    current = null;
                    step = null;
                }
                Physics.Input in = step == null ? Physics.Input.NONE : step.input(this);
                if (view != null) {
                    try {
                        body.tick(new Physics.Input(in.forward(), in.strafe(), in.jump(), in.sprint(), yaw), view);
                    } catch (Exception e) {
                        if (step != null) {
                            step.finish("stopped: cannot read blocks (" + e.getMessage() + ")");
                            current = null;
                        }
                        return;
                    }
                }
                sendInput(in);
                outbox.move(new ServerboundMovePlayerPosRotPacket(body.onGround, body.horizontalCollision, body.x,
                        body.y, body.z, yaw, pitch));
                for (Packet packet : outbox.drain(ServerboundClientTickEndPacket.INSTANCE)) {
                    send(packet);
                }
                if (step != null && step.check(this)) {
                    current = null;
                }
            }
        } catch (RuntimeException e) {
            event("[error] " + e);
        }
    }

    private void sendInput(Physics.Input in) {
        boolean sprint = in.sprint() && in.forward() > 0;
        if (sprint != sprinting) {
            sprinting = sprint;
            send(new ServerboundPlayerCommandPacket(entityId,
                    sprint ? PlayerState.START_SPRINTING : PlayerState.STOP_SPRINTING));
        }
        if (!in.equals(lastInput)) {
            lastInput = in;
            send(new ServerboundPlayerInputPacket(in.forward() > 0, in.forward() < 0, in.strafe() > 0,
                    in.strafe() < 0, in.jump(), false, sprint));
        }
    }

    /** A route from the block the player stands in to {@code to}, from the core's planner. */
    List<int[]> plan(io.github.imnotstable.msc.protocol.world.BlockPos to) throws Exception {
        if (planner == null) {
            throw new IllegalStateException("this test player has no route planner");
        }
        io.github.imnotstable.msc.protocol.world.BlockPos from;
        String world;
        synchronized (this) {
            if (view == null) {
                throw new IllegalStateException(relocate ? RELOCATING : "the player is in no known world");
            }
            world = view.world();
            from = feet();
        }
        return planner.route(world, from, to);
    }

    /** How many times the server has set the player's position. */
    synchronized long serverMoves() {
        return serverMoves;
    }

    long ticks() {
        return ticks;
    }

    synchronized boolean dead() {
        return life.dead();
    }

    synchronized Life.State life() {
        return life.state();
    }

    float health() {
        return health;
    }

    synchronized WorldView view() {
        return view;
    }

    // ---- one-shot actions, called on the tick thread or under the lock -----------------------------------

    /** Sends a chat line or, with a slash, a command; throws for what a client could not type (see {@link ChatLine}). */
    void chat(String text) {
        ChatLine.check(text);
        if (text.startsWith("/")) {
            send(new ServerboundChatCommandPacket(text.substring(1)));
        } else {
            send(new ServerboundChatPacket(text, System.currentTimeMillis(), 0L, null, 0,
                    new java.util.BitSet(), 0));
        }
    }

    void selectSlot(int slot) {
        send(new ServerboundSetCarriedItemPacket(slot));
        inventory.select(slot);
    }

    String inventoryReport() {
        return inventory.report();
    }

    String hudReport() {
        return hud.report();
    }

    /** Close the menu a server opened, like pressing Escape; false when none is open. */
    boolean closeMenu() {
        int id = inventory.screenId();
        if (id < 0) {
            return false;
        }
        send(new ServerboundContainerClosePacket(id));
        inventory.closed();
        return true;
    }

    void useItem() {
        send(new ServerboundUseItemPacket(Hand.MAIN_HAND, sequence.incrementAndGet(), yaw, pitch));
    }

    /** Right-click a block on the face that looks at the player. */
    void useOn(int x, int y, int z) {
        double ex = body.x - (x + 0.5);
        double ey = body.y + 1.62 - (y + 0.5);
        double ez = body.z - (z + 0.5);
        Direction face;
        if (Math.abs(ey) >= Math.abs(ex) && Math.abs(ey) >= Math.abs(ez)) {
            face = ey > 0 ? Direction.UP : Direction.DOWN;
        } else if (Math.abs(ex) >= Math.abs(ez)) {
            face = ex > 0 ? Direction.EAST : Direction.WEST;
        } else {
            face = ez > 0 ? Direction.SOUTH : Direction.NORTH;
        }
        send(new ServerboundUseItemOnPacket(Vector3i.from(x, y, z), face, Hand.MAIN_HAND, 0.5f, 0.5f, 0.5f,
                false, false, sequence.incrementAndGet()));
    }

    /** Attack the nearest entity within reach, of a type when one is given; returns what was hit, or null. */
    String attackNearest(String type, double reach) {
        Map.Entry<Integer, Tracked> target = faceNearest(type, reach);
        if (target == null) {
            return null;
        }
        outbox.after(new ServerboundAttackPacket(target.getKey()));
        return describe(target.getValue());
    }

    /** Right-click the nearest entity within reach (an NPC, a villager, an interaction box); null when none. */
    String interactNearest(String type, double reach) {
        Map.Entry<Integer, Tracked> target = faceNearest(type, reach);
        if (target == null) {
            return null;
        }
        // Where on the entity the click lands, relative to its feet: about chest height.
        outbox.after(new ServerboundInteractPacket(target.getKey(), Hand.MAIN_HAND, Vector3d.from(0, 0.9, 0),
                false));
        return describe(target.getValue());
    }

    private String describe(Tracked t) {
        return String.format("%s at %.1f blocks", t.type(), Math.sqrt(Math.pow(t.x() - body.x, 2)
                + Math.pow(t.y() + 0.9 - body.y - 1.62, 2) + Math.pow(t.z() - body.z, 2)));
    }

    /** Turn toward the nearest matching entity within reach of the eyes; null when none. */
    private Map.Entry<Integer, Tracked> faceNearest(String type, double reach) {
        double ex = body.x;
        double ey = body.y + 1.62;
        double ez = body.z;
        Map.Entry<Integer, Tracked> best = null;
        double bestD = reach;
        for (Map.Entry<Integer, Tracked> e : entities.entrySet()) {
            Tracked t = e.getValue();
            double d = Math.sqrt(Math.pow(t.x() - ex, 2) + Math.pow(t.y() + 0.9 - ey, 2) + Math.pow(t.z() - ez, 2));
            if (e.getKey() != entityId && d <= bestD && (type == null || t.type().equals(type))) {
                best = e;
                bestD = d;
            }
        }
        if (best == null) {
            return null;
        }
        Tracked t = best.getValue();
        double dx = t.x() - ex;
        double dz = t.z() - ez;
        yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        pitch = (float) -Math.toDegrees(Math.atan2(t.y() + 0.9 - ey, Math.sqrt(dx * dx + dz * dz)));
        // No move packet here: the next tick's move carries the new facing (see Outbox).
        return best;
    }

    /** Nearby entities, nearest first. */
    List<String> nearby(int limit) {
        return entities.entrySet().stream().filter(e -> e.getKey() != entityId)
                .map(e -> Map.entry(e.getValue(), Math.sqrt(Math.pow(e.getValue().x() - body.x, 2)
                        + Math.pow(e.getValue().y() - body.y, 2) + Math.pow(e.getValue().z() - body.z, 2))))
                .sorted(Map.Entry.comparingByValue()).limit(limit)
                .map(e -> String.format("%s %.1f,%.1f,%.1f (%.1f away)", e.getKey().type(), e.getKey().x(),
                        e.getKey().y(), e.getKey().z(), e.getValue()))
                .toList();
    }

    @Override
    public void close() {
        if (ticker != null) {
            ticker.shutdownNow();
        }
        if (session != null && session.isConnected()) {
            session.disconnect("test player done");
        }
    }

    private final class Listener extends SessionAdapter {
        @Override
        public void packetReceived(Session s, Packet packet) {
            receive(packet);
        }

        @Override
        public void disconnected(DisconnectedEvent event) {
            TestPlayer.this.disconnected(Text.plain(event.getReason()));
        }
    }

    /** Take in a packet from the server. */
    void receive(Packet packet) {
        // Inventory and screen state is kept for the inventory and hud actions; events below still log titles.
        if (inventory.accept(packet)) {
            return;
        }
        hud.accept(packet);
        switch (packet) {
            case ClientboundLoginPacket p -> {
                synchronized (this) {
                    // A login after a re-configuration is a new player in a new level, like a respawn.
                    entityId = p.getEntityId();
                    life.immediateRespawn(!p.isEnableRespawnScreen());
                    entities.clear();
                    on(Life.Input.LOGIN, null);
                }
                spawnedIn(p.getCommonPlayerSpawnInfo().getWorldName().asString());
            }
            case ClientboundStartConfigurationPacket p -> reconfigure();
            // Answered like the vanilla client: accept the code of conduct, hold no cookies, know no login channel.
            case ClientboundCodeOfConductPacket p -> send(ServerboundAcceptCodeOfConductPacket.INSTANCE);
            case ClientboundCookieRequestPacket p -> send(new ServerboundCookieResponsePacket(p.getKey(), null));
            case ClientboundCustomQueryPacket p -> send(new ServerboundCustomQueryAnswerPacket(p.getMessageId()));
            case ClientboundPlayerPositionPacket p -> position(p);
            case ClientboundSetHealthPacket p -> health(p.getHealth(), p.getFood());
            case ClientboundPlayerCombatKillPacket p -> {
                // Another player's kill message is no death of this one.
                if (p.getPlayerId() == entityId) {
                    event("[death] " + Text.plain(p.getMessage()));
                    // The vanilla client takes this as its death, health packet or not.
                    died();
                }
            }
            case ClientboundGameEventPacket p -> gameEvent(p);
            case ClientboundRespawnPacket p -> {
                boolean wasDead;
                synchronized (this) {
                    wasDead = life.dead();
                    // A new player entity: it moves once the server places it, like after a login.
                    on(Life.Input.RESPAWN, null);
                }
                String key = p.getCommonPlayerSpawnInfo().getWorldName().asString();
                spawnedIn(key);
                event(wasDead ? "[respawned]" : "[changed world] " + key);
            }
            case ClientboundSystemChatPacket p -> event((p.isOverlay() ? "[actionbar] " : "[chat] ")
                    + Text.plain(p.getContent()));
            case ClientboundPlayerChatPacket p -> event("[chat] <" + Text.plain(p.getName()) + "> "
                    + (p.getUnsignedContent() != null ? Text.plain(p.getUnsignedContent()) : p.getContent()));
            case ClientboundSetTitleTextPacket p -> event("[title] " + Text.plain(p.getText()));
            case ClientboundSetSubtitleTextPacket p -> event("[subtitle] " + Text.plain(p.getText()));
            case ClientboundSetActionBarTextPacket p -> event("[actionbar] " + Text.plain(p.getText()));
            case ClientboundResourcePackPushPacket p -> pack(p);
            // Like the vanilla client; Fabric API's configuration waits for this pong.
            case ClientboundPingPacket p -> send(new ServerboundPongPacket(p.getId()));
            case ClientboundChunkBatchFinishedPacket p -> send(new ServerboundChunkBatchReceivedPacket(64f));
            case ClientboundAddEntityPacket p -> entities.put(p.getEntityId(), new Tracked(
                    p.getType().name().toLowerCase(java.util.Locale.ROOT), p.getX(), p.getY(), p.getZ()));
            case ClientboundRemoveEntitiesPacket p -> {
                for (int id : p.getEntityIds()) {
                    entities.remove(id);
                }
            }
            case ClientboundMoveEntityPosPacket p -> moveEntity(p.getEntityId(), p.getMoveX(), p.getMoveY(),
                    p.getMoveZ());
            case ClientboundMoveEntityPosRotPacket p -> moveEntity(p.getEntityId(), p.getMoveX(),
                    p.getMoveY(), p.getMoveZ());
            case ClientboundTeleportEntityPacket p -> placeEntity(p.getId(), p.getPosition());
            case ClientboundEntityPositionSyncPacket p -> {
                Vector3d end = Compat.syncPosition(p);
                if (end != null) {
                    placeEntity(p.getId(), end);
                }
            }
            default -> {
            }
        }
    }

    /**
     * The server takes the player back to configuration (MCProtocolLib has acknowledged it): the player is out of the
     * world until the next login and position, so the tick thread sends nothing and the running step ends.
     */
    private void reconfigure() {
        event("[configuring] the server re-configures the player");
        synchronized (this) {
            on(Life.Input.CONFIGURE, "stopped: the server re-configured the player");
        }
    }

    void disconnected(String reason) {
        disconnect = reason;
        event("[disconnected] " + disconnect);
        spawned.countDown();
        synchronized (this) {
            // End the running step now rather than when it times out.
            on(Life.Input.DISCONNECT, "stopped: disconnected");
        }
    }

    private void moveEntity(int id, double dx, double dy, double dz) {
        entities.computeIfPresent(id, (k, t) -> new Tracked(t.type(), t.x() + dx, t.y() + dy, t.z() + dz));
    }

    private void placeEntity(int id, Vector3d pos) {
        entities.computeIfPresent(id, (k, t) -> new Tracked(t.type(), pos.getX(), pos.getY(), pos.getZ()));
    }

    private void position(ClientboundPlayerPositionPacket p) {
        synchronized (this) {
            if (life.state() == Life.State.DISCONNECTED) {
                return; // a packet still in flight when the connection closed
            }
            serverMoves++;
            List<PositionElement> rel = p.getRelatives();
            Vector3d pos = p.getPosition();
            double nx = rel.contains(PositionElement.X) ? body.x + pos.getX() : pos.getX();
            double ny = rel.contains(PositionElement.Y) ? body.y + pos.getY() : pos.getY();
            double nz = rel.contains(PositionElement.Z) ? body.z + pos.getZ() : pos.getZ();
            boolean teleported = false;
            if (life.positioned() && Math.abs(nx - body.x) + Math.abs(ny - body.y) + Math.abs(nz - body.z) > 0.01) {
                event(String.format("[moved by server] from %.2f,%.2f,%.2f to %.2f,%.2f,%.2f", body.x, body.y, body.z,
                        nx, ny, nz));
                // A teleport (a goal box, a respawn point, a stage change) ends a walk: its target is somewhere else
                // now. A small correction is the server pulling the player back, so the walk goes on.
                double moved = Math.sqrt(Math.pow(nx - body.x, 2) + Math.pow(ny - body.y, 2) + Math.pow(nz - body.z, 2));
                Script.Step step = current;
                if (moved > TELEPORT && step != null && step.endsOnTeleport()) {
                    step.finish(String.format("moved by server to %.2f,%.2f,%.2f (%.1f blocks)", nx, ny, nz, moved));
                    current = null;
                }
                teleported = moved > TELEPORT;
            }
            body.x = nx;
            body.y = ny;
            body.z = nz;
            Vector3d d = p.getDeltaMovement();
            body.vx = rel.contains(PositionElement.DELTA_X) ? body.vx + d.getX() : d.getX();
            body.vy = rel.contains(PositionElement.DELTA_Y) ? body.vy + d.getY() : d.getY();
            body.vz = rel.contains(PositionElement.DELTA_Z) ? body.vz + d.getZ() : d.getZ();
            yaw = rel.contains(PositionElement.Y_ROT) ? yaw + p.getYRot() : p.getYRot();
            pitch = rel.contains(PositionElement.X_ROT) ? pitch + p.getXRot() : p.getXRot();
            send(Compat.acceptTeleport(p.getId(), body.x, body.y, body.z, yaw, pitch));
            on(Life.Input.POSITION, null); // after the confirmation, as the vanilla client sends PlayerLoaded
            if (life.positioned()) {
                spawned.countDown();
            }
            if (teleported) {
                worldMayHaveChanged(false); // Minestom sends no other sign between same-dimension instances
            }
            startLocate(); // after a teleport, or the first position after a login or respawn
        }
    }

    private void health(float now, int newFood) {
        synchronized (this) {
            if (now < health - 0.01f) {
                event(String.format("[damage] health %.1f -> %.1f", health, now));
            } else if (now > health + 0.01f && now >= 20 && health < 20) {
                event("[healed] health 20.0");
            }
            health = now;
            food = newFood;
            if (now <= 0) {
                died();
            }
        }
    }

    private synchronized void died() {
        on(Life.Input.DIED, "stopped: died");
    }

    /**
     * Take {@code input} through the life-cycle and do what the transition says; {@code stopped} is the running
     * step's result when the transition ends it. Called under the lock.
     */
    private Life.Transition on(Life.Input input, String stopped) {
        Life.Transition t = act(life.on(input));
        if (t.has(Life.Action.END_STEP) && stopped != null && current != null) {
            current.finish(stopped);
            current = null;
        }
        return t;
    }

    /** Send what a transition sends; the caller runs the move and ends the step. */
    private Life.Transition act(Life.Transition t) {
        for (Life.Action action : t.actions()) {
            switch (action) {
                case ASK_RESPAWN -> send(new ServerboundClientCommandPacket(ClientCommand.PERFORM_RESPAWN));
                case LOADED -> send(ServerboundPlayerLoadedPacket.INSTANCE);
                case MOVE, END_STEP -> {
                }
            }
        }
        if (t.input() == Life.Input.WAIT_OVER && t.state() == Life.State.CREDITS) {
            event("[credits closed] no respawn came; playing on where the player is");
        }
        return t;
    }

    /**
     * The game events that change what the player must send. Every other one (weather, sounds, the game mode) only
     * changes what a person would see.
     */
    private void gameEvent(ClientboundGameEventPacket p) {
        switch (p.getNotification()) {
            case WIN_GAME -> credits(p.getValue() == EnterCreditsValue.FIRST_TIME);
            case IMMEDIATE_RESPAWN -> {
                synchronized (this) {
                    life.immediateRespawn(p.getValue() == RespawnScreenValue.IMMEDIATE_RESPAWN);
                }
            }
            default -> {
            }
        }
    }

    /**
     * The server sent the credits. Through the End's exit portal it took the player out of the End and keeps it in no
     * world until the client asks to respawn, which the vanilla client does at once, or when the credits close; the
     * player skips the credits, and the server's Respawn and position bring it back like any world change. A server
     * can also show the credits as an ending and keep the player where it is (Paper's Player#showWinScreen, Minestom's
     * WIN_GAME); it then ignores the request, and the player plays on once {@link Life#CREDITS_WAIT} ticks pass with
     * no Respawn (#319). Only in the End does a step aimed at a place end.
     */
    private void credits(boolean shown) {
        synchronized (this) {
            boolean inTheEnd = "minecraft:the_end".equals(dimension);
            event((inTheEnd ? "[left the End] through the exit portal" : "[credits] the server sent the win screen")
                    + (shown ? ", credits skipped" : ""));
            Script.Step step = current;
            on(Life.Input.WIN_GAME, inTheEnd && step != null && step.endsOnTeleport()
                    ? "stopped: left the End through the exit portal" : null);
        }
    }

    /** A client only loads a zip with pack.mcmeta at its root. */
    static boolean hasPackMeta(byte[] zip) {
        try (java.util.zip.ZipInputStream in = new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(zip))) {
            for (java.util.zip.ZipEntry e = in.getNextEntry(); e != null; e = in.getNextEntry()) {
                if (e.getName().equals("pack.mcmeta")) {
                    return true;
                }
            }
            return false;
        } catch (java.io.IOException e) {
            return false;
        }
    }

    /** What a pack download came to: whether a client would load it, and what the hud and events say. */
    record PackCheck(boolean ok, String result) {
    }

    /** Download a pack like the client: follow redirects, need HTTP 200, the hash (when given) and pack.mcmeta. */
    static PackCheck checkPack(String url, String hash) throws IOException, InterruptedException,
            java.security.NoSuchAlgorithmException {
        try (HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NORMAL).build()) {
            HttpResponse<byte[]> r = http.send(HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(10)).build(), HttpResponse.BodyHandlers.ofByteArray());
            String sha1 = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(r.body()));
            boolean ok = r.statusCode() == 200 && (hash == null || hash.isEmpty() || hash.equalsIgnoreCase(sha1));
            boolean pack = ok && hasPackMeta(r.body());
            String result = "HTTP " + r.statusCode() + ", " + r.body().length + " bytes"
                    + (r.statusCode() == 200 ? (ok ? ", sha1 matches" : ", sha1 DIFFERS from " + hash) : "")
                    + (ok && !pack ? ", but no pack.mcmeta at the zip root" : "");
            return new PackCheck(pack, result);
        }
    }

    /** Accept the pack, and check that it downloads and matches its hash, which a real client would need. */
    private void pack(ClientboundResourcePackPushPacket p) {
        event("[pack] offered " + p.getUrl() + (p.isRequired() ? " (required)" : ""));
        hud.pack("offered, downloading " + p.getUrl());
        send(new ServerboundResourcePackPacket(p.getId(), ResourcePackStatus.ACCEPTED));
        Thread.ofVirtual().start(() -> {
            ResourcePackStatus status;
            try {
                PackCheck check = checkPack(p.getUrl(), p.getHash());
                event("[pack] download: " + check.result());
                hud.pack((check.ok() ? "loaded (" : "FAILED (") + check.result() + ")"
                        + (p.isRequired() ? ", required" : ""));
                status = check.ok() ? ResourcePackStatus.SUCCESSFULLY_LOADED : ResourcePackStatus.FAILED_DOWNLOAD;
            } catch (Exception e) {
                event("[pack] download failed: " + e.getMessage());
                hud.pack("FAILED (download: " + e.getMessage() + ")" + (p.isRequired() ? ", required" : ""));
                status = ResourcePackStatus.FAILED_DOWNLOAD;
            }
            if (status == ResourcePackStatus.SUCCESSFULLY_LOADED) {
                send(new ServerboundResourcePackPacket(p.getId(), ResourcePackStatus.DOWNLOADED));
            }
            send(new ServerboundResourcePackPacket(p.getId(), status));
        });
    }
}
