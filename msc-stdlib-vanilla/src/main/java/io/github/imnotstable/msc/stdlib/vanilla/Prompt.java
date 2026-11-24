package io.github.imnotstable.msc.stdlib.vanilla;

import io.github.imnotstable.msc.stdlib.sched.Scheduler;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Asks a player to type an answer in chat (not shown to others); {@code cancel} or the timeout gives up.
 *
 * <pre>{@code
 * Prompt.ask(player, Component.literal("Name your home (or cancel):"), Duration.ofSeconds(30), name -> homes.create(player, name), () -> {});
 * }</pre>
 */
public final class Prompt {
    private record Pending(Consumer<String> answer, Runnable cancelled, Scheduler.Task timeout) {}

    private static final Map<UUID, Pending> PENDING = new ConcurrentHashMap<>();

    private Prompt() {}

    public static void ask(ServerPlayer player, Component question, Duration timeout, Consumer<String> answer,
                           Runnable cancelled) {
        player.sendSystemMessage(question);
        UUID id = player.getUUID();
        Scheduler.Task task = Stdlib.scheduler().later(Math.max(1, timeout.toMillis() / 50), () -> {
            Pending p = PENDING.remove(id);
            if (p != null) {
                p.cancelled().run();
            }
        });
        Pending old = PENDING.put(id, new Pending(answer, cancelled, task));
        if (old != null) {
            old.timeout().cancel();
        }
    }

    public static boolean waiting(ServerPlayer player) {
        return PENDING.containsKey(player.getUUID());
    }

    /** A chat message from {@code player}: true when it answered a prompt (and must not be broadcast). */
    public static boolean answer(UUID player, String text) {
        Pending p = PENDING.remove(player);
        if (p == null) {
            return false;
        }
        p.timeout().cancel();
        Stdlib.scheduler().sync().execute(() -> {
            if (text.trim().equalsIgnoreCase("cancel")) {
                p.cancelled().run();
            } else {
                p.answer().accept(text.trim());
            }
        });
        return true;
    }

    static void left(UUID player) {
        Pending p = PENDING.remove(player);
        if (p != null) {
            p.timeout().cancel();
        }
    }
}
