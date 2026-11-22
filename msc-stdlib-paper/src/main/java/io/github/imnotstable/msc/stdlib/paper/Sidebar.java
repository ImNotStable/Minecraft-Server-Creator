package io.github.imnotstable.msc.stdlib.paper;

import io.papermc.paper.scoreboard.numbers.NumberFormat;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.Criteria;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;

import java.util.List;

/**
 * The scoreboard on the right of the screen, lines top to bottom, no score numbers. One sidebar can be shown to many
 * players; make one per player for per-player lines.
 *
 * <pre>{@code
 * Sidebar board = new Sidebar(Text.mm("<gold><bold>SKY SPRINT"));
 * board.lines(List.of(Text.mm("Stage: <s>", "s", 3), Component.empty(), Text.mm("<yellow>play.example.net")));
 * board.show(player);
 * }</pre>
 */
public final class Sidebar {
    private final Scoreboard board = Bukkit.getScoreboardManager().getNewScoreboard();
    private final Objective objective;
    private int shown;

    public Sidebar(Component title) {
        objective = board.registerNewObjective("sidebar", Criteria.DUMMY, title);
        objective.setDisplaySlot(DisplaySlot.SIDEBAR);
        objective.numberFormat(NumberFormat.blank());
    }

    public void title(Component title) {
        objective.displayName(title);
    }

    /** Replaces every line (at most 15 show). */
    public void lines(List<Component> lines) {
        int n = Math.min(lines.size(), 15);
        for (int i = 0; i < n; i++) {
            var score = objective.getScore(entry(i));
            score.setScore(n - i);
            score.customName(lines.get(i));
        }
        for (int i = n; i < shown; i++) {
            board.resetScores(entry(i));
        }
        shown = n;
    }

    /** Changes one line, 0 the top. */
    public void line(int index, Component text) {
        if (index >= shown) {
            throw new IndexOutOfBoundsException("Line " + index + " of " + shown + "; set them with lines() first");
        }
        objective.getScore(entry(index)).customName(text);
    }

    public void show(Player player) {
        player.setScoreboard(board);
    }

    /** Puts the player back on the server's main scoreboard. */
    public void hide(Player player) {
        if (player.getScoreboard() == board) {
            player.setScoreboard(Bukkit.getScoreboardManager().getMainScoreboard());
        }
    }

    /** The scoreboard, for teams (name tag colours, prefixes) alongside the sidebar. */
    public Scoreboard scoreboard() {
        return board;
    }

    private static String entry(int i) {
        return "§" + Integer.toHexString(i) + "§r";
    }
}
