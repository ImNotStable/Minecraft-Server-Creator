package io.github.imnotstable.msc.bot;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import net.kyori.adventure.text.Component;
import org.geysermc.mcprotocollib.network.packet.Packet;
import org.geysermc.mcprotocollib.protocol.data.game.BossBarColor;
import org.geysermc.mcprotocollib.protocol.data.game.scoreboard.ObjectiveAction;
import org.geysermc.mcprotocollib.protocol.data.game.scoreboard.ScoreboardPosition;
import org.geysermc.mcprotocollib.protocol.data.game.scoreboard.TeamAction;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundBossEventPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundSystemChatPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundTabListPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.scoreboard.ClientboundResetScorePacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.scoreboard.ClientboundSetDisplayObjectivePacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.scoreboard.ClientboundSetObjectivePacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.scoreboard.ClientboundSetPlayerTeamPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.scoreboard.ClientboundSetScorePacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.title.ClientboundSetActionBarTextPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.title.ClientboundSetSubtitleTextPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.title.ClientboundSetTitleTextPacket;

/**
 * What is on the test player's screen besides the world: boss bars, the sidebar, the last action bar and title, the
 * tab list header, and whether the resource pack loaded. Reported only by the {@code hud} action.
 */
final class Hud {
    private static final int SIDEBAR_LINES = 15;
    private static final Pattern LEGACY_CODES = Pattern.compile("\u00a7.");

    private record Bar(String title, float health, BossBarColor color) {
    }

    private record Score(int value, String display) {
    }

    private record Team(String prefix, String suffix) {
    }

    private final Map<UUID, Bar> bars = new LinkedHashMap<>();
    private final Map<String, String> objectives = new HashMap<>();
    private final Map<String, Map<String, Score>> scores = new HashMap<>();
    private final Map<String, Team> teams = new HashMap<>();
    private final Map<String, String> teamOf = new HashMap<>();
    private String sidebar;
    private String actionBar;
    private String title;
    private String subtitle;
    private String tabHeader;
    private String pack = "none offered";

    /** Take in a packet; false when it is not about the screen. */
    synchronized boolean accept(Packet packet) {
        switch (packet) {
            case ClientboundBossEventPacket p -> boss(p);
            case ClientboundSetObjectivePacket p -> {
                if (p.getAction() == ObjectiveAction.REMOVE) {
                    objectives.remove(p.getName());
                    scores.remove(p.getName());
                } else {
                    objectives.put(p.getName(), Text.plain(p.getDisplayName()));
                }
            }
            case ClientboundSetDisplayObjectivePacket p -> {
                if (p.getPosition() == ScoreboardPosition.SIDEBAR) {
                    sidebar = p.getName() == null || p.getName().isEmpty() ? null : p.getName();
                }
            }
            case ClientboundSetScorePacket p -> scores.computeIfAbsent(p.getObjective(), k -> new HashMap<>())
                    .put(p.getOwner(), new Score(p.getValue(), p.getDisplay() == null ? null : Text.plain(p.getDisplay())));
            case ClientboundResetScorePacket p -> {
                if (p.getObjective() == null) {
                    scores.values().forEach(s -> s.remove(p.getOwner()));
                } else {
                    scores.getOrDefault(p.getObjective(), Map.of()).remove(p.getOwner());
                }
            }
            case ClientboundSetPlayerTeamPacket p -> team(p);
            case ClientboundSetActionBarTextPacket p -> actionBar = Text.plain(p.getText());
            case ClientboundSystemChatPacket p -> {
                if (!p.isOverlay()) {
                    return false;
                }
                actionBar = Text.plain(p.getContent());
            }
            case ClientboundSetTitleTextPacket p -> {
                title = Text.plain(p.getText());
                subtitle = null;
            }
            case ClientboundSetSubtitleTextPacket p -> subtitle = Text.plain(p.getText());
            case ClientboundTabListPacket p -> {
                String header = Text.plain(p.getHeader());
                String footer = Text.plain(p.getFooter());
                tabHeader = (header + (footer.isEmpty() ? "" : " / " + footer)).strip();
            }
            default -> {
                return false;
            }
        }
        return true;
    }

    synchronized void pack(String state) {
        pack = state;
    }

    private void boss(ClientboundBossEventPacket p) {
        UUID id = p.getUuid();
        Bar old = bars.get(id);
        switch (p.getAction()) {
            case ADD -> bars.put(id, new Bar(Text.plain(p.getTitle()), p.getHealth(), p.getColor()));
            case REMOVE -> bars.remove(id);
            case UPDATE_HEALTH -> {
                if (old != null) {
                    bars.put(id, new Bar(old.title(), p.getHealth(), old.color()));
                }
            }
            case UPDATE_TITLE -> {
                if (old != null) {
                    bars.put(id, new Bar(Text.plain(p.getTitle()), old.health(), old.color()));
                }
            }
            case UPDATE_STYLE -> {
                if (old != null) {
                    bars.put(id, new Bar(old.title(), old.health(), p.getColor()));
                }
            }
            default -> {
                // flags (fog, dark sky) do not change what the bar says
            }
        }
    }

    private void team(ClientboundSetPlayerTeamPacket p) {
        String name = p.getTeamName();
        switch (p.getAction()) {
            case REMOVE -> {
                teams.remove(name);
                teamOf.values().removeIf(name::equals);
                return;
            }
            case CREATE, UPDATE -> teams.put(name, new Team(plain(p.getPlayerPrefix()), plain(p.getPlayerSuffix())));
            default -> {
                // members only
            }
        }
        if (p.getPlayers() != null) {
            for (String player : p.getPlayers()) {
                if (p.getAction() == TeamAction.REMOVE_PLAYER) {
                    teamOf.remove(player, name);
                } else {
                    teamOf.put(player, name);
                }
            }
        }
    }

    private static String plain(Component c) {
        return Text.raw(c);
    }

    synchronized String report() {
        List<String> lines = new ArrayList<>();
        for (Bar bar : bars.values()) {
            lines.add(String.format("bossbar \"%s\" %.0f%% %s", bar.title(), bar.health() * 100,
                    bar.color() == null ? "" : bar.color().name().toLowerCase(Locale.ROOT)).strip());
        }
        if (sidebar != null) {
            List<Map.Entry<String, Score>> rows = new ArrayList<>(scores.getOrDefault(sidebar, Map.of()).entrySet());
            rows.sort(Comparator.<Map.Entry<String, Score>>comparingInt(e -> -e.getValue().value())
                    .thenComparing(Map.Entry::getKey));
            List<String> shown = new ArrayList<>();
            for (Map.Entry<String, Score> row : rows.subList(0, Math.min(SIDEBAR_LINES, rows.size()))) {
                shown.add(line(row.getKey(), row.getValue()));
            }
            lines.add("sidebar \"" + objectives.getOrDefault(sidebar, sidebar) + "\": "
                    + (shown.isEmpty() ? "no lines" : String.join(" | ", shown)));
        }
        if (actionBar != null && !actionBar.isEmpty()) {
            lines.add("last actionbar: " + actionBar);
        }
        if (title != null && !title.isEmpty()) {
            lines.add("last title: " + title + (subtitle != null && !subtitle.isEmpty() ? " / " + subtitle : ""));
        }
        if (tabHeader != null && !tabHeader.isEmpty()) {
            lines.add("tab list: " + tabHeader);
        }
        lines.add("pack: " + pack);
        return String.join("\n", lines);
    }

    private String line(String owner, Score score) {
        String text;
        // Sidebar plugins name their lines with colour codes and put the words in a team prefix.
        // Minestom's sidebar sends an empty display and puts the words in the team prefix.
        if (score.display() != null && !score.display().isEmpty()) {
            text = score.display();
        } else {
            Team team = teams.get(teamOf.get(owner));
            text = team == null ? owner : (team.prefix() + owner + team.suffix()).strip();
        }
        return LEGACY_CODES.matcher(text).replaceAll("").strip() + " " + score.value();
    }
}
