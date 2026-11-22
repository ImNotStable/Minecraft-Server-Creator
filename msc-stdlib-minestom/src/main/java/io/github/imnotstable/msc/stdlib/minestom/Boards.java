package io.github.imnotstable.msc.stdlib.minestom;

import io.github.imnotstable.msc.stdlib.sched.Scheduler;
import io.github.imnotstable.msc.stdlib.text.Text;
import net.kyori.adventure.text.Component;
import net.minestom.server.entity.Player;
import net.minestom.server.scoreboard.Sidebar;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * A sidebar for every player, its MiniMessage lines rebuilt from their data on a timer: coins, kills, time left.
 *
 * <pre>{@code
 * Boards.show(player, "<gold><bold>SKYWARS", p -> List.of("<gray>Kills: <white>" + kills.score(p.getUuid()), "",
 *         "<yellow>play.example.net"), 20);
 * }</pre>
 */
public final class Boards {
    private record Shown(Sidebar sidebar, Scheduler.Task task, int[] lines) {}

    private static final Map<UUID, Shown> SHOWN = new ConcurrentHashMap<>();

    private Boards() {}

    public static Sidebar show(Player player, String title, Function<Player, List<String>> lines, long ticks) {
        hide(player);
        Sidebar s = new Sidebar(Text.mm(title));
        int[] count = {0};
        Runnable refresh = () -> {
            List<String> now = lines.apply(player);
            int n = Math.min(15, now.size());
            for (int i = 0; i < n; i++) {
                String id = "l" + i;
                Component c = now.get(i).isEmpty() ? Component.empty() : Text.mm(now.get(i));
                if (i < count[0]) {
                    s.updateLineContent(id, c);
                } else {
                    s.createLine(new Sidebar.ScoreboardLine(id, c, n - i, Sidebar.NumberFormat.blank()));
                }
                s.updateLineScore(id, n - i);
            }
            for (int i = n; i < count[0]; i++) {
                s.removeLine("l" + i);
            }
            count[0] = n;
        };
        refresh.run();
        s.addViewer(player);
        Scheduler.Task task = Stdlib.scheduler().repeat(ticks, ticks, () -> {
            if (!player.isOnline()) {
                hide(player);
                return;
            }
            refresh.run();
        });
        SHOWN.put(player.getUuid(), new Shown(s, task, count));
        return s;
    }

    public static void hide(Player player) {
        Shown old = SHOWN.remove(player.getUuid());
        if (old != null) {
            old.task().cancel();
            old.sidebar().removeViewer(player);
        }
    }
}
