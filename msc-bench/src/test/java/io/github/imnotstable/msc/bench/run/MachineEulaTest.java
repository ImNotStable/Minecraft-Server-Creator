package io.github.imnotstable.msc.bench.run;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.imnotstable.msc.buildlogic.RepoFiles;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MachineEulaTest {
    @TempDir
    Path home;

    /** The same eula.json cases msc-core's MachineEula.read is tested with: both must agree on each. */
    @Test
    void agreesWithCoreOnTheSharedFixtures() throws Exception {
        assertThat(MachineEula.accepted(home)).isFalse();
        Path file = MachineEula.file(home);
        Files.createDirectories(file.getParent());
        List<String> fixtures = RepoFiles.list("eulaFixtures");
        assertThat(fixtures).anyMatch(f -> f.contains("/accepted-")).anyMatch(f -> f.contains("/rejected-"));
        for (String name : fixtures) {
            Path f = RepoFiles.path("eulaFixtures", name);
            Files.copy(f, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            boolean expected = f.getFileName().toString().startsWith("accepted-");
            assertThat(MachineEula.accepted(home)).as(f.getFileName().toString()).isEqualTo(expected);
        }
    }
}
