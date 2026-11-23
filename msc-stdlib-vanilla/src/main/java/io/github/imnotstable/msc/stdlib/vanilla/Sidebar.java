package io.github.imnotstable.msc.stdlib.vanilla;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.numbers.BlankFormat;
import net.minecraft.network.protocol.game.ClientboundResetScorePacket;
import net.minecraft.network.protocol.game.ClientboundSetDisplayObjectivePacket;
import net.minecraft.network.protocol.game.ClientboundSetObjectivePacket;
import net.minecraft.network.protocol.game.ClientboundSetScorePacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.scores.DisplaySlot;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.Scoreboard;
import net.minecraft.world.scores.criteria.ObjectiveCriteria;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * A per-player sidebar (the scoreboard on the right of the screen) with a title and up to 15 lines of any text, no
 * score numbers, sent straight to the player so it never touches the world's scoreboard or other players.
 *
 * <pre>{@code
 * Sidebar.show(player, Component.literal("Ruins"), p -> List.of(Component.literal("Kills: " + kills(p)),
 *         Component.literal("Time: " + time())), 20);   // refreshed every second
 * Sidebar.hide(player);
 * }</pre>
 */
public final class Sidebar {
    private static final String NAME = "msc_sidebar";
    private static final Scoreboard BOARD = new Scoreboard();
    private static final Map<UUID, State> SHOWN = new ConcurrentHashMap<>();

    private record State(Objective objective, int lines, io.github.imnotstable.msc.stdlib.sched.Scheduler.Task task) {
    }

    private Sidebar() {}

    /** Show fixed lines (top first). */
    public static void set(ServerPlayer player, Component title, List<Component> lines) {
        State old = SHOWN.get(player.getUUID());
        Objective objective = new Objective(BOARD, NAME, ObjectiveCriteria.DUMMY, title,
                ObjectiveCriteria.RenderType.INTEGER, false, BlankFormat.INSTANCE);
        if (old == null) {
            player.connection.send(new ClientboundSetObjectivePacket(objective, ClientboundSetObjectivePacket.METHOD_ADD));
            player.connection.send(new ClientboundSetDisplayObjectivePacket(DisplaySlot.SIDEBAR, objective));
        } else {
            player.connection.send(new ClientboundSetObjectivePacket(objective, ClientboundSetObjectivePacket.METHOD_CHANGE));
        }
        int n = Math.min(15, lines.size());
        for (int i = 0; i < n; i++) {
            // Higher scores sit higher: line 0 gets the top score.
            player.connection.send(new ClientboundSetScorePacket(owner(i), NAME, n - i, Optional.of(lines.get(i)),
                    Optional.of(BlankFormat.INSTANCE)));
        }
        int before = old == null ? 0 : old.lines();
        for (int i = n; i < before; i++) {
            player.connection.send(new ClientboundResetScorePacket(owner(i), NAME));
        }
        SHOWN.put(player.getUUID(), new State(objective, n, old == null ? null : old.task()));
    }

    /** Show lines worked out again every {@code ticks} (20 is a second), until {@link #hide}. */
    public static void show(ServerPlayer player, Component title, Function<ServerPlayer, List<Component>> lines,
                            long ticks) {
        hide(player);
        set(player, title, lines.apply(player));
        UUID id = player.getUUID();
        var task = Hooks.scheduler().repeat(ticks, ticks, () -> {
            ServerPlayer p = Hooks.server().getPlayerList().getPlayer(id);
            if (p != null && SHOWN.containsKey(id)) {
                set(p, title, lines.apply(p));
            }
        });
        State s = SHOWN.get(id);
        SHOWN.put(id, new State(s.objective(), s.lines(), task));
    }

    public static void hide(ServerPlayer player) {
        State s = SHOWN.remove(player.getUUID());
        if (s == null) {
            return;
        }
        if (s.task() != null) {
            s.task().cancel();
        }
        player.connection.send(new ClientboundSetObjectivePacket(s.objective(), ClientboundSetObjectivePacket.METHOD_REMOVE));
    }

    static void left(UUID player) {
        State s = SHOWN.remove(player);
        if (s != null && s.task() != null) {
            s.task().cancel();
        }
    }

    /** Lines of text, for {@link #set}. */
    public static List<Component> lines(String... text) {
        List<Component> out = new ArrayList<>();
        for (String t : text) {
            out.add(Component.literal(t));
        }
        return out;
    }

    private static String owner(int line) {
        return "msc_line_" + line;
    }
}
