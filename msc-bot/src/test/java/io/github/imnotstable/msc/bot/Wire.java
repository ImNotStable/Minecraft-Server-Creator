package io.github.imnotstable.msc.bot;

import io.netty.buffer.Unpooled;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;
import net.kyori.adventure.key.Key;
import org.geysermc.mcprotocollib.auth.GameProfile;
import org.geysermc.mcprotocollib.network.Session;
import org.geysermc.mcprotocollib.network.packet.Packet;
import org.geysermc.mcprotocollib.protocol.ClientListener;
import org.geysermc.mcprotocollib.protocol.MinecraftProtocol;
import org.geysermc.mcprotocollib.protocol.codec.MinecraftCodec;
import org.geysermc.mcprotocollib.protocol.codec.MinecraftPacket;
import org.geysermc.mcprotocollib.protocol.data.ProtocolState;
import org.geysermc.mcprotocollib.protocol.data.handshake.HandshakeIntent;
import org.geysermc.mcprotocollib.protocol.packet.common.clientbound.ClientboundKeepAlivePacket;
import org.geysermc.mcprotocollib.protocol.packet.common.clientbound.ClientboundPingPacket;
import org.geysermc.mcprotocollib.protocol.packet.common.clientbound.ClientboundResourcePackPushPacket;
import org.geysermc.mcprotocollib.protocol.packet.common.serverbound.ServerboundResourcePackPacket;
import org.geysermc.mcprotocollib.protocol.packet.configuration.clientbound.ClientboundCodeOfConductPacket;
import org.geysermc.mcprotocollib.protocol.packet.configuration.clientbound.ClientboundFinishConfigurationPacket;
import org.geysermc.mcprotocollib.protocol.packet.configuration.clientbound.ClientboundSelectKnownPacks;
import org.geysermc.mcprotocollib.protocol.packet.cookie.clientbound.ClientboundCookieRequestPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundRespawnPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundStartConfigurationPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.player.ClientboundPlayerPositionPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.player.ClientboundSetHealthPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.level.ClientboundChunkBatchFinishedPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.ServerboundClientCommandPacket;
import org.geysermc.mcprotocollib.protocol.packet.login.clientbound.ClientboundCustomQueryPacket;
import org.geysermc.mcprotocollib.protocol.packet.login.clientbound.ClientboundLoginFinishedPacket;

/**
 * A test player behind MCProtocolLib's own {@link ClientListener}, as on a real connection, with the connection's
 * states tracked by a real {@link MinecraftProtocol}: every packet either sends is checked to have an id in the
 * outgoing state and to encode. {@link #table()} drives it into each state and feeds it each packet a server needs
 * answered there. {@link ProtocolTableTest} runs it with each MCProtocolLib the bot speaks.
 */
final class Wire {
    private final MinecraftProtocol protocol = new MinecraftProtocol("MscTester");
    private final ClientListener listener = new ClientListener(HandshakeIntent.LOGIN);
    private final List<String> sent = Collections.synchronizedList(new ArrayList<>());
    private final Session session = (Session) Proxy.newProxyInstance(Wire.class.getClassLoader(),
            new Class<?>[] {Session.class}, (proxy, method, args) -> session(proxy, method, args));
    private final TestPlayer player = TestPlayer.offline("MscTester", null, null, null, this::record,
            protocol::getOutboundState);

    private Wire() {
        protocol.setInboundState(ProtocolState.LOGIN);
        protocol.setOutboundState(ProtocolState.LOGIN);
    }

    /** The part of a session ClientListener uses: sending, switching state, the protocol and its flags. */
    private Object session(Object proxy, Method method, Object[] args) {
        return switch (method.getName()) {
            case "send" -> {
                record((Packet) args[0]);
                yield null;
            }
            case "switchInboundState", "switchOutboundState" -> {
                ((Runnable) args[0]).run();
                yield null;
            }
            case "getPacketProtocol" -> protocol;
            case "getFlag" -> args.length == 2 ? args[1] : null;
            case "equals" -> proxy == args[0];
            case "hashCode" -> System.identityHashCode(proxy);
            case "toString" -> "wire";
            default -> method.getReturnType() == boolean.class ? false : null;
        };
    }

    private void record(Packet packet) {
        ProtocolState state = protocol.getOutboundState();
        String name = name(packet);
        if (!TestPlayer.sendable(state, packet)) {
            name += " [no id in " + state + "]";
        } else if (packet instanceof MinecraftPacket p) {
            try {
                p.serialize(Unpooled.buffer());
            } catch (RuntimeException e) {
                name += " [does not encode: " + e + "]";
            }
        }
        sent.add(name);
    }

    /** Take in a packet as the connection does: ClientListener first, then the player. */
    private void deliver(Packet packet) {
        listener.packetReceived(session, packet);
        player.receive(packet);
    }

    private boolean accepts(Packet packet) {
        try {
            MinecraftCodec.CODEC.getCodec(protocol.getInboundState()).getClientboundId(packet.getClass());
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /** What went out since the last call, and every report of a packet the player held back. */
    private String take() {
        List<String> out;
        synchronized (sent) {
            out = new ArrayList<>(sent);
            sent.clear();
        }
        StringBuilder events = new StringBuilder();
        player.drainEvents(events);
        for (String line : events.toString().split("\n")) {
            if (line.contains("[not sent]")) {
                out.add(line.trim());
            }
        }
        return out.isEmpty() ? "-" : String.join(", ", out);
    }

    private static String name(Packet p) {
        String n = p.getClass().getSimpleName().replaceFirst("^Serverbound", "").replaceFirst("Packet$", "");
        return switch (p) {
            case ServerboundResourcePackPacket r -> n + "(" + r.getStatus() + ")";
            case ServerboundClientCommandPacket c -> n + "(" + c.getRequest() + ")";
            default -> n;
        };
    }

    /** A connection state, reached from a fresh connection by {@code path}. */
    private record State(String name, Consumer<Wire> path) {
    }

    /** A packet a server sends that needs an answer, or changes what the player answers. */
    private record Input(String name, Supplier<Packet> packet) {
    }

    /** Ticks enough for a dead player's respawn request (20 ticks after it died). */
    static final int TICKS = 21;

    private static final State LOGIN = new State("login", w -> {
    });
    private static final State CONFIGURATION = new State("configuration", LOGIN.path.andThen(
            w -> w.deliver(new ClientboundLoginFinishedPacket(new GameProfile(UUID.randomUUID(), "MscTester"),
                    UUID.randomUUID()))));
    private static final State JOINED = new State("play, before the first position", CONFIGURATION.path.andThen(
            w -> w.join("minecraft:overworld")));
    private static final State PLAY = new State("play", JOINED.path.andThen(w -> w.deliver(position())));
    private static final State DEAD = new State("play, dead", PLAY.path.andThen(w -> w.deliver(died())));
    private static final State RESPAWNED = new State("play, respawned, before its position", DEAD.path.andThen(
            w -> w.deliver(new ClientboundRespawnPacket(TestPlayerTest.spawn("minecraft:overworld"), false, false))));
    private static final State RECONFIGURING = new State("re-configuration", PLAY.path.andThen(
            w -> w.deliver(new ClientboundStartConfigurationPacket())));
    private static final State RECONFIGURING_DEAD = new State("re-configuration while dead", DEAD.path.andThen(
            w -> w.deliver(new ClientboundStartConfigurationPacket())));
    private static final State REJOINED = new State("play after re-configuration, before its position",
            RECONFIGURING.path.andThen(w -> w.join("minecraft:the_nether")));
    private static final State REPLAY = new State("play after re-configuration", REJOINED.path.andThen(
            w -> w.deliver(position())));
    private static final State REPLAY_DEAD = new State("play after re-configuration while dead",
            RECONFIGURING_DEAD.path.andThen(w -> w.join("minecraft:overworld")).andThen(w -> w.deliver(position())));
    private static final List<State> STATES = List.of(LOGIN, CONFIGURATION, JOINED, PLAY, DEAD, RESPAWNED,
            RECONFIGURING, RECONFIGURING_DEAD, REJOINED, REPLAY, REPLAY_DEAD);

    private static final List<Input> INPUTS = List.of(
            new Input("CustomQuery", () -> new ClientboundCustomQueryPacket(3, Key.key("msc:query"), new byte[0])),
            new Input("CookieRequest", () -> new ClientboundCookieRequestPacket(Key.key("msc:cookie"))),
            new Input("KeepAlive", () -> new ClientboundKeepAlivePacket(7)),
            new Input("Ping", () -> new ClientboundPingPacket(9)),
            new Input("CodeOfConduct", () -> new ClientboundCodeOfConductPacket("be kind")),
            new Input("SelectKnownPacks", () -> new ClientboundSelectKnownPacks(List.of())),
            new Input("ResourcePackPush", () -> new ClientboundResourcePackPushPacket(new UUID(0, 1),
                    "http://127.0.0.1:1/pack.zip", "", false, null)),
            new Input("FinishConfiguration", ClientboundFinishConfigurationPacket::new),
            new Input("PlayerPosition", Wire::position),
            new Input("ChunkBatchFinished", () -> new ClientboundChunkBatchFinishedPacket(4)),
            new Input("SetHealth 0", Wire::died),
            new Input("Respawn", () -> new ClientboundRespawnPacket(TestPlayerTest.spawn("minecraft:overworld"),
                    false, false)),
            new Input("StartConfiguration", ClientboundStartConfigurationPacket::new));

    private void join(String world) {
        deliver(new ClientboundFinishConfigurationPacket());
        deliver(TestPlayerTest.login(world));
    }

    private static Packet position() {
        return new ClientboundPlayerPositionPacket(5, 0.5, 64, 0.5, 0, 0, 0, 0, 0);
    }

    private static Packet died() {
        return new ClientboundSetHealthPacket(0, 20, 5);
    }

    /**
     * For each state, each packet a server can send there and what the player answers at once, then what it sends
     * over {@link #TICKS} ticks; a Markdown table.
     */
    static String table() throws InterruptedException {
        StringBuilder out = new StringBuilder("| state | server sends | bot answers |\n|---|---|---|\n");
        for (State state : STATES) {
            for (Input input : INPUTS) {
                Wire w = at(state);
                Packet packet = input.packet.get();
                if (!w.accepts(packet)) {
                    continue;
                }
                w.deliver(packet);
                if (packet instanceof ClientboundResourcePackPushPacket) {
                    w.awaitPackAnswers();
                }
                row(out, state, input.name, w.take());
            }
            Wire w = at(state);
            for (int i = 0; i < TICKS; i++) {
                w.player.tick();
            }
            row(out, state, "(" + TICKS + " ticks)", String.join(", ", new LinkedHashSet<>(
                    List.of(w.take().split(", ")))));
        }
        return out.toString();
    }

    private static Wire at(State state) {
        Wire w = new Wire();
        state.path.accept(w);
        w.take();
        return w;
    }

    private static void row(StringBuilder out, State state, String input, String answer) {
        out.append("| ").append(state.name).append(" | ").append(input).append(" | ").append(answer).append(" |\n");
    }

    /** The pack check runs on its own thread: wait for its final answer (the download fails at once). */
    private void awaitPackAnswers() throws InterruptedException {
        long end = System.nanoTime() + 10_000_000_000L;
        while (System.nanoTime() < end) {
            synchronized (sent) {
                if (sent.stream().filter(s -> s.startsWith("ResourcePack(")).count() >= 2) {
                    return;
                }
            }
            Thread.sleep(5);
        }
    }
}
