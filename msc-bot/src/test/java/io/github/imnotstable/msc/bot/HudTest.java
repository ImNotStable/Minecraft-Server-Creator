package io.github.imnotstable.msc.bot;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import net.kyori.adventure.text.Component;
import org.geysermc.mcprotocollib.protocol.data.game.BossBarColor;
import org.geysermc.mcprotocollib.protocol.data.game.BossBarDivision;
import org.geysermc.mcprotocollib.protocol.data.game.scoreboard.CollisionRule;
import org.geysermc.mcprotocollib.protocol.data.game.scoreboard.NameTagVisibility;
import org.geysermc.mcprotocollib.protocol.data.game.scoreboard.ObjectiveAction;
import org.geysermc.mcprotocollib.protocol.data.game.scoreboard.ScoreType;
import org.geysermc.mcprotocollib.protocol.data.game.scoreboard.ScoreboardPosition;
import org.geysermc.mcprotocollib.protocol.data.game.scoreboard.TeamColor;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundBossEventPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundSystemChatPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundTabListPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.scoreboard.ClientboundResetScorePacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.scoreboard.ClientboundSetDisplayObjectivePacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.scoreboard.ClientboundSetObjectivePacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.scoreboard.ClientboundSetPlayerTeamPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.scoreboard.ClientboundSetScorePacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.title.ClientboundClearTitlesPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.title.ClientboundSetActionBarTextPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.title.ClientboundSetSubtitleTextPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.title.ClientboundSetTitleTextPacket;
import org.junit.jupiter.api.Test;

class HudTest {
    private final Hud hud = new Hud();

    @Test
    void startsWithOnlyThePackLine() {
        assertThat(hud.report()).isEqualTo("pack: none offered");
        assertThat(hud.accept(new ClientboundSystemChatPacket(Component.text("hello"), false))).isFalse();
    }

    @Test
    void followsBossBars() {
        UUID id = UUID.randomUUID();
        hud.accept(new ClientboundBossEventPacket(id, Component.text("Wave 3"), 1f, BossBarColor.RED,
                BossBarDivision.NONE, false, false, false));
        hud.accept(new ClientboundBossEventPacket(id, 0.45f));
        assertThat(hud.report()).startsWith("bossbar \"Wave 3\" 45% red\n");
        hud.accept(new ClientboundBossEventPacket(id, Component.text("Boss")));
        assertThat(hud.report()).startsWith("bossbar \"Boss\" 45% red\n");
        hud.accept(new ClientboundBossEventPacket(id));
        assertThat(hud.report()).doesNotContain("bossbar");
    }

    @Test
    void readsTheSidebarLikeAPlayer() {
        hud.accept(new ClientboundSetObjectivePacket("side", ObjectiveAction.ADD, Component.text("Lumen Siege"),
                ScoreType.INTEGER, null));
        hud.accept(new ClientboundSetDisplayObjectivePacket(ScoreboardPosition.SIDEBAR, "side"));
        hud.accept(new ClientboundSetScorePacket("Kills", "side", 3));
        hud.accept(new ClientboundSetScorePacket("§a", "side", 9));
        hud.accept(new ClientboundSetScorePacket("gone", "side", 1));
        hud.accept(new ClientboundResetScorePacket("gone", "side"));
        // Sidebar plugins put the words in a team prefix on a colour-code owner.
        hud.accept(new ClientboundSetPlayerTeamPacket("line9", Component.empty(), Component.text("Wave: "),
                Component.text("/10"), false, false, NameTagVisibility.ALWAYS, CollisionRule.ALWAYS, TeamColor.WHITE,
                new String[] {"§a"}));
        hud.accept(new ClientboundSetScorePacket("Gold", "side", 5).withDisplay(Component.text("Gold: 120")));
        // An empty display (Minestom's sidebar) falls back to the team prefix.
        hud.accept(new ClientboundSetScorePacket("\u00a7a", "side", 9).withDisplay(Component.empty()));
        assertThat(hud.report()).startsWith("sidebar \"Lumen Siege\": Wave: /10 9 | Gold: 120 5 | Kills 3\n");
        hud.accept(new ClientboundSetDisplayObjectivePacket(ScoreboardPosition.SIDEBAR, ""));
        assertThat(hud.report()).doesNotContain("sidebar");
    }

    @Test
    void keepsASubtitleSentBeforeItsTitleUntilTheTitlesAreCleared() {
        // Adventure's showTitle and vanilla /title send the subtitle first.
        hud.accept(new ClientboundSetSubtitleTextPacket(Component.text("Hold the gate")));
        hud.accept(new ClientboundSetTitleTextPacket(Component.text("Wave 2")));
        assertThat(hud.report()).startsWith("last title: Wave 2 / Hold the gate\n");
        hud.accept(new ClientboundClearTitlesPacket(true));
        hud.accept(new ClientboundSetTitleTextPacket(Component.text("Wave 3")));
        assertThat(hud.report()).startsWith("last title: Wave 3\n");
    }

    @Test
    void keepsTheLastActionBarTitleTabListAndPack() {
        hud.accept(new ClientboundSetActionBarTextPacket(Component.text("Mana 40")));
        hud.accept(new ClientboundSystemChatPacket(Component.text("Mana 35"), true));
        hud.accept(new ClientboundSetTitleTextPacket(Component.text("Wave 2")));
        hud.accept(new ClientboundSetSubtitleTextPacket(Component.text("Hold the gate")));
        hud.accept(new ClientboundTabListPacket(Component.text("Lumen"), Component.empty()));
        hud.pack("loaded (HTTP 200, 120 bytes, sha1 matches)");
        assertThat(hud.report()).isEqualTo("""
                last actionbar: Mana 35
                last title: Wave 2 / Hold the gate
                tab list: Lumen
                pack: loaded (HTTP 200, 120 bytes, sha1 matches)""");
    }
}
