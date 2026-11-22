package io.github.imnotstable.msc.stdlib.paper;

import io.github.imnotstable.msc.stdlib.text.Text;
import io.papermc.paper.event.player.AsyncChatEvent;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Asks a player to type something in chat: a name for their home, an amount to pay, a town name. Their next chat
 * message is the answer (not shown to others); typing {@code cancel} or waiting past the timeout gives up.
 *
 * <pre>{@code
 * Prompt.ask(player, "<yellow>Name your home:", Duration.ofSeconds(30),
 *         name -> homes.create(player, name),
 *         () -> Feedback.fail(player, "No name given"));
 * }</pre>
 */
public final class Prompt {
    private record Pending(Consumer<String> answer, Runnable cancelled, io.github.imnotstable.msc.stdlib.sched.Scheduler.Task timeout) {}

    private static final Map<UUID, Pending> PENDING = new ConcurrentHashMap<>();
    private static boolean listening;

    private Prompt() {}

    /** {@code answer} and {@code cancelled} run on the main thread. A new prompt replaces an open one. */
    public static void ask(Player player, String question, Duration timeout, Consumer<String> answer, Runnable cancelled) {
        listen();
        player.sendMessage(Text.mm(question));
        player.sendMessage(Text.mm("<gray>Type your answer in chat, or <white>cancel</white>."));
        UUID id = player.getUniqueId();
        var task = Stdlib.scheduler().later(Math.max(1, timeout.toMillis() / 50), () -> {
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

    /** Whether the player has an open prompt. */
    public static boolean waiting(Player player) {
        return PENDING.containsKey(player.getUniqueId());
    }

    /** Hands {@code text} to the player's prompt as if typed (also used by the chat listener). */
    static boolean answer(Player player, String text) {
        Pending p = PENDING.remove(player.getUniqueId());
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
        var plugin = Stdlib.plugin();
        plugin.getServer().getPluginManager().registerEvents(new Listener() {
            @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
            public void chat(AsyncChatEvent e) {
                if (answer(e.getPlayer(), Text.plain(e.message()))) {
                    e.setCancelled(true);
                }
            }

            @EventHandler
            public void quit(PlayerQuitEvent e) {
                Pending p = PENDING.remove(e.getPlayer().getUniqueId());
                if (p != null) {
                    p.timeout().cancel();
                }
            }
        }, plugin);
    }

    /** Called by {@link Stdlib#init}: a new plugin instance (reload, tests) registers the listener again. */
    static synchronized void reset() {
        listening = false;
        PENDING.clear();
    }
}
