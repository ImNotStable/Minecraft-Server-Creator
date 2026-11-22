package io.github.imnotstable.msc.stdlib.messaging;

import io.github.imnotstable.msc.stdlib.util.Json;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Messages between servers (or parts of one) by channel: chat across a network, "player joined game X", cache
 * invalidation, cross-server commands. Payloads are any Gson-serialisable value. {@link #local} stays in this JVM;
 * {@code Redis.messenger()} reaches every server on the same Redis.
 *
 * <pre>{@code
 * record Broadcast(String from, String text) {}
 * messenger.subscribe("chat", Broadcast.class, b -> Bukkit.broadcast(Text.mm("<gray>[<from>]</gray> <text>", ...)));
 * messenger.publish("chat", new Broadcast("lobby", "Event starts in 5 minutes"));
 * }</pre>
 *
 * Handlers run on the messenger's thread; hop to the game thread before touching the world.
 */
public interface Messenger extends AutoCloseable {
    void publish(String channel, Object message);

    /** Handles every message on {@code channel} decoded as {@code type}; close the result to stop. */
    <T> Subscription subscribe(String channel, Class<T> type, Consumer<T> handler);

    @Override
    void close();

    /** Stops one subscription. */
    interface Subscription extends AutoCloseable {
        @Override
        void close();
    }

    /** A messenger inside this JVM: same API, for one server or tests. Messages pass through JSON like Redis's. */
    static Messenger local() {
        Map<String, List<Consumer<String>>> handlers = new ConcurrentHashMap<>();
        return new Messenger() {
            @Override
            public void publish(String channel, Object message) {
                String json = Json.write(message);
                handlers.getOrDefault(channel, List.of()).forEach(h -> h.accept(json));
            }

            @Override
            public <T> Subscription subscribe(String channel, Class<T> type, Consumer<T> handler) {
                Consumer<String> h = json -> handler.accept(Json.read(json, type));
                handlers.computeIfAbsent(channel, c -> new CopyOnWriteArrayList<>()).add(h);
                return () -> handlers.getOrDefault(channel, List.of()).remove(h);
            }

            @Override
            public void close() {
                handlers.clear();
            }
        };
    }
}
