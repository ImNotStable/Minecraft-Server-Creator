package {{packageName}};

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class GameEventsTest {
    @Test
    void offlinePlayersKeepVanillasUuidSoProgressSurvivesAReconnect() {
        // Vanilla's offline UUID for "Notch": version 3, from "OfflinePlayer:Notch".
        assertThat(GameEvents.offlineUuid("Notch")).isEqualTo(UUID.fromString("b50ad385-829d-3141-a216-7e7d7539ba7f"));
    }
}
