package io.github.imnotstable.msc.bot;

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
import org.geysermc.mcprotocollib.protocol.data.game.ClientCommand;
import org.geysermc.mcprotocollib.protocol.data.game.ResourcePackStatus;
import org.geysermc.mcprotocollib.protocol.data.game.entity.object.Direction;
import org.geysermc.mcprotocollib.protocol.data.game.entity.player.Hand;
import org.geysermc.mcprotocollib.protocol.data.game.entity.player.PlayerState;
import org.geysermc.mcprotocollib.protocol.data.game.entity.player.PositionElement;
import org.geysermc.mcprotocollib.protocol.packet.common.clientbound.ClientboundResourcePackPushPacket;
import org.geysermc.mcprotocollib.protocol.packet.common.serverbound.ServerboundResourcePackPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundLoginPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundPlayerChatPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundRespawnPacket;
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
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.title.ClientboundSetActionBarTextPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.title.ClientboundSetSubtitleTextPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.title.ClientboundSetTitleTextPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.ServerboundChatCommandPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.ServerboundChatPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.ServerboundClientCommandPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.ServerboundClientTickEndPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.ServerboundPlayerLoadedPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.level.ServerboundAcceptTeleportationPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.level.ServerboundChunkBatchReceivedPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.level.ServerboundPlayerInputPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundAttackPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundMovePlayerPosRotPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundPlayerCommandPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundSetCarriedItemPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundUseItemOnPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundUseItemPacket;

/**
 * A headless player on the dev server: an offline-mode client that moves with its own physics against blocks read
 * through the agent, sends what a real client sends each tick, and records what the server tells it.
 */
public final class TestPlayer implements AutoCloseable {
    /** The Minecraft version this player speaks; it cannot join a server on another version. */
    public static final String MINECRAFT_VERSION = MinecraftCodec.CODEC.getMinecraftVersion();
    /** A tracked entity near the player. */
    record Tracked(String type, double x, double y, double z) {
    }

    private final String name;
    private final ClientSession session;
    private final ScheduledExecutorService ticker;
    private final BlockSource blocks;
    private final List<String> events = new ArrayList<>();
    private final Map<Integer, Tracked> entities = new ConcurrentHashMap<>();
    private final AtomicInteger sequence = new AtomicInteger();
    private final CountDownLatch spawned = new CountDownLatch(1);
    final Physics body = new Physics();
    private WorldView view;
    private volatile String disconnect;
    private volatile int entityId;
    private volatile boolean positioned;
    private volatile boolean dead;
    private int deadTicks;
    float yaw;
    float pitch;
    private float health = 20;
    private int food = 20;
    private Physics.Input lastInput = Physics.Input.NONE;
    private boolean sprinting;
    private Script.Step current;
    private long ticks;

    private TestPlayer(String name, String host, int port, BlockSource blocks) {
        this.name = name;
        this.blocks = blocks;
        this.session = ClientNetworkSessionFactory.factory().setAddress(host, port)
                .setProtocol(new MinecraftProtocol(name)).create();
        this.ticker = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "msc-test-player");
            t.setDaemon(true);
            return t;
        });
    }

    /** Join the server and wait until the player is in the world. */
    public static TestPlayer join(String name, String host, int port, BlockSource blocks, Duration timeout)
            throws InterruptedException {
        TestPlayer p = new TestPlayer(name, host, port, blocks);
        p.session.addListener(p.new Listener());
        p.session.connect(true);
        // A kick during login also ends the wait, so check for it too.
        if (!p.spawned.await(timeout.toMillis(), TimeUnit.MILLISECONDS) || p.disconnect != null) {
            String why = p.disconnect;
            p.close();
            throw new IllegalStateException("could not join " + host + ":" + port + " as " + name
                    + (why != null ? ": " + why : " within " + timeout.toSeconds() + "s"));
        }
        p.ticker.scheduleAtFixedRate(p::tick, 50, 50, TimeUnit.MILLISECONDS);
        return p;
    }

    public String name() {
        return name;
    }

    public boolean connected() {
        return disconnect == null && session.isConnected();
    }

    /** Run script lines and report what happened, one line per step plus what the server said. */
    public String run(List<String> lines, String world) throws InterruptedException {
        List<Script.Step> steps = new ArrayList<>();
        for (String line : lines) {
            steps.add(Script.parse(line));
        }
        synchronized (this) {
            if (view == null) {
                view = new WorldView(blocks, world);
            }
        }
        StringBuilder out = new StringBuilder();
        drainEvents(out); // what happened since the last call, joining included
        for (Script.Step step : steps) {
            if (!connected()) {
                out.append("stopped: disconnected").append(disconnect != null ? " (" + disconnect + ")" : "")
                        .append('\n');
                break;
            }
            synchronized (this) {
                step.start(this);
                current = step;
            }
            String result = step.await();
            out.append(step.text()).append(": ").append(result).append('\n');
            drainEvents(out);
        }
        synchronized (this) {
            out.append(status());
        }
        return out.toString();
    }

    String status() {
        return String.format("%s at %.2f,%.2f,%.2f yaw %.0f pitch %.0f%s, health %.1f, food %d%s", name, body.x,
                body.y, body.z, yaw, pitch, body.onGround ? " on ground" : " in air", health, food,
                dead ? ", dead" : "");
    }

    private void drainEvents(StringBuilder out) {
        synchronized (events) {
            int shown = Math.min(events.size(), 40);
            for (int i = 0; i < shown; i++) {
                out.append("  ").append(events.get(i)).append('\n');
            }
            if (events.size() > shown) {
                out.append("  (").append(events.size() - shown).append(" more)\n");
            }
            events.clear();
        }
    }

    void event(String text) {
        synchronized (events) {
            // Action bars repeat every second or so: keep one of each in a row.
            if (!events.isEmpty() && events.getLast().equals(text)) {
                return;
            }
            events.add(text);
        }
    }

    private void tick() {
        try {
            synchronized (this) {
                ticks++;
                if (!positioned || !connected()) {
                    return;
                }
                if (dead) {
                    if (++deadTicks == 20) {
                        session.send(new ServerboundClientCommandPacket(ClientCommand.PERFORM_RESPAWN));
                    }
                    return;
                }
                Script.Step step = current;
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
                session.send(new ServerboundMovePlayerPosRotPacket(body.onGround, body.horizontalCollision, body.x,
                        body.y, body.z, yaw, pitch));
                session.send(ServerboundClientTickEndPacket.INSTANCE);
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
            session.send(new ServerboundPlayerCommandPacket(entityId,
                    sprint ? PlayerState.START_SPRINTING : PlayerState.STOP_SPRINTING));
        }
        if (!in.equals(lastInput)) {
            lastInput = in;
            session.send(new ServerboundPlayerInputPacket(in.forward() > 0, in.forward() < 0, in.strafe() > 0,
                    in.strafe() < 0, in.jump(), false, sprint));
        }
    }

    long ticks() {
        return ticks;
    }

    boolean dead() {
        return dead;
    }

    float health() {
        return health;
    }

    WorldView view() {
        return view;
    }

    // ---- one-shot actions, called on the tick thread or under the lock -----------------------------------

    void chat(String text) {
        if (text.startsWith("/")) {
            session.send(new ServerboundChatCommandPacket(text.substring(1)));
        } else {
            session.send(new ServerboundChatPacket(text, System.currentTimeMillis(), 0L, null, 0,
                    new java.util.BitSet(), 0));
        }
    }

    void selectSlot(int slot) {
        session.send(new ServerboundSetCarriedItemPacket(slot));
    }

    void useItem() {
        session.send(new ServerboundUseItemPacket(Hand.MAIN_HAND, sequence.incrementAndGet(), yaw, pitch));
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
        session.send(new ServerboundUseItemOnPacket(Vector3i.from(x, y, z), face, Hand.MAIN_HAND, 0.5f, 0.5f, 0.5f,
                false, false, sequence.incrementAndGet()));
    }

    /** Attack the nearest entity within reach; returns what was hit, or null. */
    String attackNearest(double reach) {
        double ex = body.x;
        double ey = body.y + 1.62;
        double ez = body.z;
        Map.Entry<Integer, Tracked> best = null;
        double bestD = reach;
        for (Map.Entry<Integer, Tracked> e : entities.entrySet()) {
            Tracked t = e.getValue();
            double d = Math.sqrt(Math.pow(t.x() - ex, 2) + Math.pow(t.y() + 0.9 - ey, 2) + Math.pow(t.z() - ez, 2));
            if (e.getKey() != entityId && d <= bestD) {
                best = e;
                bestD = d;
            }
        }
        if (best == null) {
            return null;
        }
        Tracked t = best.getValue();
        yaw = (float) Math.toDegrees(Math.atan2(-(t.x() - ex), t.z() - ez));
        session.send(new ServerboundMovePlayerPosRotPacket(body.onGround, false, body.x, body.y, body.z, yaw, pitch));
        session.send(new ServerboundAttackPacket(best.getKey()));
        return String.format("%s at %.1f blocks", t.type(), bestD);
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
        ticker.shutdownNow();
        if (session.isConnected()) {
            session.disconnect("test player done");
        }
    }

    private final class Listener extends SessionAdapter {
        @Override
        public void packetReceived(Session s, Packet packet) {
            switch (packet) {
                case ClientboundLoginPacket p -> entityId = p.getEntityId();
                case ClientboundPlayerPositionPacket p -> position(p);
                case ClientboundSetHealthPacket p -> health(p.getHealth(), p.getFood());
                case ClientboundPlayerCombatKillPacket p -> {
                    if (p.getPlayerId() == entityId) {
                        event("[death] " + Text.plain(p.getMessage()));
                    }
                }
                case ClientboundRespawnPacket p -> {
                    synchronized (TestPlayer.this) {
                        dead = false;
                        deadTicks = 0;
                    }
                    event("[respawned]");
                }
                case ClientboundSystemChatPacket p -> event((p.isOverlay() ? "[actionbar] " : "[chat] ")
                        + Text.plain(p.getContent()));
                case ClientboundPlayerChatPacket p -> event("[chat] <" + Text.plain(p.getName()) + "> "
                        + (p.getUnsignedContent() != null ? Text.plain(p.getUnsignedContent()) : p.getContent()));
                case ClientboundSetTitleTextPacket p -> event("[title] " + Text.plain(p.getText()));
                case ClientboundSetSubtitleTextPacket p -> event("[subtitle] " + Text.plain(p.getText()));
                case ClientboundSetActionBarTextPacket p -> event("[actionbar] " + Text.plain(p.getText()));
                case ClientboundResourcePackPushPacket p -> pack(s, p);
                case ClientboundChunkBatchFinishedPacket p -> s.send(new ServerboundChunkBatchReceivedPacket(64f));
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
                    if (p.getEndPosition() != null) {
                        placeEntity(p.getId(), p.getEndPosition());
                    }
                }
                default -> {
                }
            }
        }

        @Override
        public void disconnected(DisconnectedEvent event) {
            disconnect = Text.plain(event.getReason());
            event("[disconnected] " + disconnect);
            spawned.countDown();
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
            List<PositionElement> rel = p.getRelatives();
            Vector3d pos = p.getPosition();
            double nx = rel.contains(PositionElement.X) ? body.x + pos.getX() : pos.getX();
            double ny = rel.contains(PositionElement.Y) ? body.y + pos.getY() : pos.getY();
            double nz = rel.contains(PositionElement.Z) ? body.z + pos.getZ() : pos.getZ();
            if (positioned && Math.abs(nx - body.x) + Math.abs(ny - body.y) + Math.abs(nz - body.z) > 0.01) {
                event(String.format("[moved by server] from %.2f,%.2f,%.2f to %.2f,%.2f,%.2f", body.x, body.y, body.z,
                        nx, ny, nz));
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
            session.send(new ServerboundAcceptTeleportationPacket(p.getId(), body.x, body.y, body.z, yaw, pitch));
            if (!positioned) {
                positioned = true;
                session.send(ServerboundPlayerLoadedPacket.INSTANCE);
                spawned.countDown();
            }
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
            if (now <= 0 && !dead) {
                dead = true;
                deadTicks = 0;
                Script.Step step = current;
                if (step != null) {
                    step.finish("stopped: died");
                    current = null;
                }
            }
        }
    }

    /** Accept the pack, and check that it downloads and matches its hash, which a real client would need. */
    private void pack(Session s, ClientboundResourcePackPushPacket p) {
        event("[pack] offered " + p.getUrl() + (p.isRequired() ? " (required)" : ""));
        s.send(new ServerboundResourcePackPacket(p.getId(), ResourcePackStatus.ACCEPTED));
        Thread.ofVirtual().start(() -> {
            ResourcePackStatus status;
            try (HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()) {
                HttpResponse<byte[]> r = http.send(HttpRequest.newBuilder(URI.create(p.getUrl()))
                        .timeout(Duration.ofSeconds(10)).build(), HttpResponse.BodyHandlers.ofByteArray());
                String sha1 = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(r.body()));
                boolean ok = r.statusCode() == 200 && (p.getHash() == null || p.getHash().isEmpty()
                        || p.getHash().equalsIgnoreCase(sha1));
                event("[pack] download: HTTP " + r.statusCode() + ", " + r.body().length + " bytes"
                        + (r.statusCode() == 200 ? (ok ? ", sha1 matches" : ", sha1 DIFFERS from " + p.getHash()) : ""));
                status = ok ? ResourcePackStatus.SUCCESSFULLY_LOADED : ResourcePackStatus.FAILED_DOWNLOAD;
            } catch (Exception e) {
                event("[pack] download failed: " + e.getMessage());
                status = ResourcePackStatus.FAILED_DOWNLOAD;
            }
            if (status == ResourcePackStatus.SUCCESSFULLY_LOADED) {
                s.send(new ServerboundResourcePackPacket(p.getId(), ResourcePackStatus.DOWNLOADED));
            }
            s.send(new ServerboundResourcePackPacket(p.getId(), status));
        });
    }
}
