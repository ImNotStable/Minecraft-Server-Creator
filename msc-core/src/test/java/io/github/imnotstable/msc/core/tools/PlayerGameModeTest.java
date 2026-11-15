package io.github.imnotstable.msc.core.tools;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.imnotstable.msc.protocol.op.Ops;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * #626: what player_test says came of its gamemode command, on the join and for a player already joined. A joined
 * test player needs a real server, so the e2e test drives the Minestom case through MCP; this pins each answer.
 */
class PlayerGameModeTest {
    private static String set(boolean success, String... output) {
        return PlayerTools.gameModeSet("creative", new Ops.CommandResult(success, List.of(output)));
    }

    @Test
    void saysWhatCameOfTheGamemodeCommand() {
        assertThat(set(true, "Set MscTester's game mode to Creative Mode")).isEqualTo("creative");
        // Minestom has only the commands the project registers.
        assertThat(set(false, "Unknown command: gamemode"))
                .isEqualTo("game mode left to the server: it has no gamemode command");
        assertThat(set(false, "No player was found")).isEqualTo("game mode not set to creative: No player was found");
        assertThat(set(false, "x".repeat(300))).hasSize("game mode not set to creative: ".length() + 203);
        // Vanilla answers a player already in that mode with nothing: it is in the mode asked for.
        assertThat(set(false)).isEqualTo("creative");
    }
}
