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
import org.geysermc.mcprotocollib.protocol.data.game.level.notify.EnterCreditsValue;
import org.geysermc.mcprotocollib.protocol.data.game.level.notify.GameEvent;
import org.geysermc.mcprotocollib.protocol.data.game.level.notify.RespawnScreenValue;
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
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.player.ClientboundPlayerCombatKillPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.player.ClientboundPlayerPositionPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.player.ClientboundSetHealthPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.level.ClientboundChunkBatchFinishedPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.level.ClientboundGameEventPacket;
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
    /** Killed with only the kill message, no health packet: the vanilla client still takes it as its death. */
    private static final State KILLED = new State("play, killed", PLAY.path.andThen(w -> w.deliver(killed())));
    /**
     * Sent the credits: through the End's exit portal the server keeps the player in no world until it asks to
     * respawn (#302); as an ending it keeps the player where it is, and the player plays on after a wait (#319).
     */
    private static final State LEFT_END = new State("play, sent the credits", PLAY.path.andThen(w -> w.deliver(wonGame())));
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
    private static final List<State> STATES = List.of(LOGIN, CONFIGURATION, JOINED, PLAY, DEAD, KILLED,
            LEFT_END, RESPAWNED,
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
            new Input("PlayerCombatKill", Wire::killed),
            new Input("GameEvent WIN_GAME", Wire::wonGame),
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

    /** The kill message for the player {@link TestPlayerTest#login} made. */
    private static Packet killed() {
        return new ClientboundPlayerCombatKillPacket(7, net.kyori.adventure.text.Component.text("fell"));
    }

    private static Packet wonGame() {
        return new ClientboundGameEventPacket(GameEvent.WIN_GAME, EnterCreditsValue.SEEN_BEFORE);
    }

    /** A life-cycle state, reached from a fresh connection by {@code path}; named by the state the player reports. */
    private record LifeState(String how, Consumer<Wire> path) {
    }

    /** One or more packets a server sends in a row, or something else that happens to the connection. */
    private record LifeInput(String name, Consumer<Wire> deliver, Supplier<Packet> first) {
        static LifeInput of(String name, Supplier<Packet> packet) {
            return new LifeInput(name, w -> w.deliver(packet.get()), packet);
        }
    }

    /** Ticks enough for every wait in the life-cycle: the death screen's, and the credits'. */
    static final int LIFE_TICKS = Life.CREDITS_WAIT + 1;

    private static final List<LifeState> LIFE_STATES = List.of(
            new LifeState("logged in", JOINED.path),
            new LifeState("died before its first position", JOINED.path.andThen(w -> w.deliver(died()))),
            new LifeState("placed", PLAY.path),
            new LifeState("died", DEAD.path),
            new LifeState("died, then " + TICKS + " ticks", DEAD.path.andThen(w -> w.tick(TICKS))),
            new LifeState("respawned", RESPAWNED.path),
            new LifeState("sent WIN_GAME", PLAY.path.andThen(w -> w.deliver(wonGame()))),
            new LifeState("sent StartConfiguration", RECONFIGURING.path),
            new LifeState("disconnected", PLAY.path.andThen(w -> w.player.disconnected("bye"))));

    /** Every life-cycle input, own and other entity ids, both credits values; in every state, in any order. */
    private static final List<LifeInput> LIFE_INPUTS = List.of(
            LifeInput.of("Login", () -> TestPlayerTest.login("minecraft:overworld")),
            new LifeInput("FinishConfiguration, Login", w -> w.join("minecraft:overworld"),
                    ClientboundFinishConfigurationPacket::new),
            LifeInput.of("Respawn", () -> new ClientboundRespawnPacket(TestPlayerTest.spawn("minecraft:the_nether"),
                    false, false)),
            LifeInput.of("PlayerPosition", Wire::position),
            LifeInput.of("SetHealth 0", Wire::died),
            LifeInput.of("SetHealth 20", () -> new ClientboundSetHealthPacket(20, 20, 5)),
            LifeInput.of("PlayerCombatKill, own id", Wire::killed),
            LifeInput.of("PlayerCombatKill, other id", () -> new ClientboundPlayerCombatKillPacket(99,
                    net.kyori.adventure.text.Component.text("someone else fell"))),
            LifeInput.of("GameEvent WIN_GAME SEEN_BEFORE", Wire::wonGame),
            LifeInput.of("GameEvent WIN_GAME FIRST_TIME", () -> new ClientboundGameEventPacket(GameEvent.WIN_GAME,
                    EnterCreditsValue.FIRST_TIME)),
            LifeInput.of("GameEvent IMMEDIATE_RESPAWN", () -> new ClientboundGameEventPacket(
                    GameEvent.IMMEDIATE_RESPAWN, RespawnScreenValue.IMMEDIATE_RESPAWN)),
            LifeInput.of("StartConfiguration", ClientboundStartConfigurationPacket::new),
            new LifeInput("(disconnect)", w -> w.player.disconnected("bye"), () -> null),
            new LifeInput("(1 tick)", w -> w.tick(1), () -> null),
            new LifeInput("(" + LIFE_TICKS + " ticks)", w -> w.tick(LIFE_TICKS), () -> null));

    private void tick(int ticks) {
        for (int i = 0; i < ticks; i++) {
            player.tick();
        }
    }

    /**
     * For each life-cycle state, reached by real packets, each input there: what the player sends (once each, in
     * order) and the state it is in after; a Markdown table. Inputs the connection's state has no id for are left out.
     */
    static String lifeTable() {
        StringBuilder out = new StringBuilder("| state (reached by) | server sends | bot answers | next state |\n"
                + "|---|---|---|---|\n");
        for (LifeState state : LIFE_STATES) {
            for (LifeInput input : LIFE_INPUTS) {
                Wire w = new Wire();
                state.path.accept(w);
                w.take();
                String from = w.player.life() + " (" + state.how + ")";
                Packet first = input.first.get();
                if (first != null && !w.accepts(first)) {
                    continue;
                }
                input.deliver.accept(w);
                String answers = String.join(", ", new LinkedHashSet<>(List.of(w.take().split(", "))));
                out.append("| ").append(from).append(" | ").append(input.name).append(" | ").append(answers)
                        .append(" | ").append(w.player.life()).append(" |\n");
            }
        }
        return out.toString();
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
