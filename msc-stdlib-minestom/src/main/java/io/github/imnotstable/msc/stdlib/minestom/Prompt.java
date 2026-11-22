package io.github.imnotstable.msc.stdlib.minestom;

import io.github.imnotstable.msc.stdlib.sched.Scheduler;
import io.github.imnotstable.msc.stdlib.text.Text;
import net.minestom.server.entity.Player;
import net.minestom.server.event.player.PlayerChatEvent;
import net.minestom.server.event.player.PlayerDisconnectEvent;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Asks a player to type an answer in chat (not shown to others); {@code cancel} or the timeout gives up.
 *
 * <pre>{@code
 * Prompt.ask(player, "<yellow>Name your arena:", Duration.ofSeconds(30), name -> arenas.create(name), () -> {});
 * }</pre>
 */
public final class Prompt {
    private record Pending(Consumer<String> answer, Runnable cancelled, Scheduler.Task timeout) {}

    private static final Map<UUID, Pending> PENDING = new ConcurrentHashMap<>();
    private static boolean listening;

    private Prompt() {}

    public static void ask(Player player, String question, Duration timeout, Consumer<String> answer, Runnable cancelled) {
        listen();
        player.sendMessage(Text.mm(question));
        player.sendMessage(Text.mm("<gray>Type your answer in chat, or <white>cancel</white>."));
        UUID id = player.getUuid();
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

    public static boolean waiting(Player player) {
        return PENDING.containsKey(player.getUuid());
    }

    static boolean answer(Player player, String text) {
        Pending p = PENDING.remove(player.getUuid());
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

    private static synchronized void listen() {
        if (listening) {
            return;
        }
        listening = true;
        Stdlib.events().addListener(PlayerChatEvent.class, e -> {
            if (answer(e.getPlayer(), e.getRawMessage())) {
                e.setCancelled(true);
            }
        });
        Stdlib.events().addListener(PlayerDisconnectEvent.class, e -> {
            Pending p = PENDING.remove(e.getPlayer().getUuid());
            if (p != null) {
                p.timeout().cancel();
            }
        });
    }

    static synchronized void reset() {
        listening = false;
        PENDING.clear();
    }
}
