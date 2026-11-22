package io.github.imnotstable.msc.stdlib.paper;

import io.github.imnotstable.msc.stdlib.text.Text;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

/**
 * Prefixes, suffixes and name colours over heads and in the tab list: ranks, teams, roles. Uses teams on the server's
 * main scoreboard, so it shows for everyone (players shown a {@link Sidebar} get it copied onto that board too).
 *
 * <pre>{@code
 * Nametags.set(player, "<red>[RED] ", "", NamedTextColor.RED);
 * Nametags.clear(player);
 * }</pre>
 */
public final class Nametags {
    private Nametags() {}

    public static void set(Player player, String prefix, String suffix, NamedTextColor color) {
        set(Bukkit.getScoreboardManager().getMainScoreboard(), player, prefix, suffix, color);
    }

    /** The same on a given scoreboard (a {@link Sidebar#scoreboard()}). */
    public static void set(Scoreboard board, Player player, String prefix, String suffix, NamedTextColor color) {
        String name = teamName(player);
        Team team = board.getTeam(name);
        if (team == null) {
            team = board.registerNewTeam(name);
        }
        team.prefix(Text.mm(prefix));
        team.suffix(Text.mm(suffix));
        if (color != null) {
            team.color(color);
        }
        team.addEntry(player.getName());
    }

    public static void clear(Player player) {
        Team team = Bukkit.getScoreboardManager().getMainScoreboard().getTeam(teamName(player));
        if (team != null) {
            team.unregister();
        }
    }

    private static String teamName(Player p) {
        return "msc_" + p.getUniqueId().toString().substring(0, 12);
    }
}
