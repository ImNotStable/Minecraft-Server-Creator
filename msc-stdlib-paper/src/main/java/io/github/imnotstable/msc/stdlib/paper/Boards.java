package io.github.imnotstable.msc.stdlib.paper;

import io.github.imnotstable.msc.stdlib.sched.Scheduler;
import io.github.imnotstable.msc.stdlib.text.Text;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * A sidebar for every player, its lines rebuilt from their own data on a timer: coins, rank, kills, time left. Lines
 * are MiniMessage; pair them with {@code Format} for numbers, bars and times.
 *
 * <pre>{@code
 * Boards.show(player, "<gold><bold>SKYWARS", p -> List.of(
 *         "<gray>Kills: <white>" + kills.score(p.getUniqueId()),
 *         "<gray>Time: <white>" + Format.clock(game.left()),
 *         "",
 *         "<yellow>play.example.net"), 20);
 * Boards.hide(player);
 * }</pre>
 */
public final class Boards {
    private record Shown(Sidebar sidebar, Scheduler.Task task) {}

    private static final Map<UUID, Shown> SHOWN = new ConcurrentHashMap<>();

    private Boards() {}

    /** Shows (or replaces) the player's board, refreshed every {@code ticks} while they are online. */
    public static Sidebar show(Player player, String title, Function<Player, List<String>> lines, long ticks) {
        hide(player);
        Sidebar s = new Sidebar(Text.mm(title));
        Runnable refresh = () -> s.lines(lines.apply(player).stream().map(l -> l.isEmpty() ? Component.empty()
                : Text.mm(l)).toList());
        refresh.run();
        s.show(player);
        UUID id = player.getUniqueId();
        Scheduler.Task task = Stdlib.scheduler().repeat(ticks, ticks, () -> {
            if (!player.isOnline()) {
                hide(player);
                return;
            }
            refresh.run();
        });
        SHOWN.put(id, new Shown(s, task));
        return s;
    }

    public static void hide(Player player) {
        Shown old = SHOWN.remove(player.getUniqueId());
        if (old != null) {
            old.task().cancel();
            old.sidebar().hide(player);
        }
    }

    public static boolean showing(Player player) {
        return SHOWN.containsKey(player.getUniqueId());
    }
}
