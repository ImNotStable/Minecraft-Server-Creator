package io.github.imnotstable.msc.core.server;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MachineEulaTest {
    @TempDir
    Path dir;

    @Test
    void readsOnlyACompleteAcceptance() throws Exception {
        Path file = dir.resolve("eula.json");
        assertThat(MachineEula.read(file)).isEmpty();
        assertThat(MachineEula.read(null)).isEmpty();
        for (String bad : new String[] {"", "not json", "[]", "{\"accepted\":true}",
                "{\"eula\":\"https://aka.ms/MinecraftEULA\",\"accepted\":false,\"acceptedBy\":\"a\",\"acceptedAt\":\"2026-11-12T08:00:00Z\"}",
                "{\"eula\":\"https://example.com\",\"accepted\":true,\"acceptedBy\":\"a\",\"acceptedAt\":\"2026-11-12T08:00:00Z\"}",
                "{\"eula\":\"https://aka.ms/MinecraftEULA\",\"accepted\":true,\"acceptedBy\":\"a\",\"acceptedAt\":\"yesterday\"}"}) {
            Files.writeString(file, bad);
            assertThat(MachineEula.read(file)).as(bad).isEmpty();
        }
        MachineEulaAccess.accepted(file);
        assertThat(MachineEula.read(file)).contains(
                new MachineEula.Acceptance("owner", "pc", Instant.parse("2026-11-12T08:00:00Z")));
        assertThat(file).content().contains("\"how\": \"typed yes to 'msc eula accept' in an interactive terminal\"");
    }

    /** The fixtures msc-bench's MachineEulaTest reads too, so the two readers keep one rule (ADR 0004 forbids sharing code). */
    @Test
    void sharedFixturesAreAcceptedOnlyWhenNamedSo() throws Exception {
        try (var files = Files.list(Path.of("src/test/resources/eula-fixtures"))) {
            for (Path f : files.toList()) {
                boolean expected = f.getFileName().toString().startsWith("accepted-");
                assertThat(MachineEula.read(f).isPresent()).as(f.getFileName().toString()).isEqualTo(expected);
            }
        }
    }

    @Test
    void appliesTheAcceptanceToAServerOnlyWhenThereIsOne() throws Exception {
        Path file = dir.resolve("home/.msc/eula.json");
        Path eulaTxt = Files.writeString(dir.resolve("eula.txt"), "eula=false\n");
        assertThat(MachineEula.applyTo(file, eulaTxt)).isEmpty();
        assertThat(eulaTxt).hasContent("eula=false");
        MachineEulaAccess.accepted(file);
        assertThat(MachineEula.applyTo(file, eulaTxt)).isPresent();
        assertThat(eulaTxt).content().contains("accepted by owner on pc, 2026-11-12 for this machine")
                .contains("\neula=true\n").doesNotContain("eula=false");
        // Already accepted: left alone.
        Files.writeString(eulaTxt, "eula=true\n");
        assertThat(MachineEula.applyTo(file, eulaTxt)).isEmpty();
        assertThat(eulaTxt).hasContent("eula=true");
        Path fresh = dir.resolve("new-server/eula.txt");
        assertThat(MachineEula.applyTo(file, fresh)).isPresent();
        assertThat(ServerManager.eulaAccepted(fresh)).isTrue();
    }

    @Test
    void theAcceptCommandPointsAtTheInstall() {
        assertThat(MachineEula.defaultFile("/home/me")).isEqualTo(Path.of("/home/me", ".msc", "eula.json"));
        assertThat(MachineEula.acceptCommand(dir)).contains("-cp \"" + dir.resolve("lib"))
                .endsWith(" io.github.imnotstable.msc.core.Main eula accept");
        assertThat(MachineEula.acceptCommand(null)).contains("installMsc");
    }
}
