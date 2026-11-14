package {{packageName}};

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIOException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProgressTest {
    @TempDir
    Path dir;

    @Test
    void survivesARestart() throws IOException {
        UUID steve = UUID.randomUUID();
        UUID alex = UUID.randomUUID();
        Path file = dir.resolve("progress.properties");
        Progress progress = new Progress(file).load();
        progress.setStage(steve, "arena");
        progress.setStage(alex, "start");
        progress.clear(alex);
        progress.flush();

        Progress reloaded = new Progress(file).load();

        assertThat(reloaded.stage(steve)).isEqualTo("arena");
        assertThat(reloaded.stage(alex)).isNull();
        assertThat(reloaded.size()).isEqualTo(1);
        assertThat(Files.readString(file)).contains(steve + "=arena");
    }

    @Test
    void flushWithoutChangesWritesNothing() throws IOException {
        Path file = dir.resolve("progress.properties");

        new Progress(file).load().flush();

        assertThat(file).doesNotExist();
    }

    @Test
    void eachFlushWritesOnlyTheChangesSinceTheLastOne() throws IOException {
        UUID steve = UUID.randomUUID();
        Path file = dir.resolve("progress.properties");
        Progress progress = new Progress(file).load();
        progress.setStage(steve, "start");
        progress.flush();
        progress.setStage(steve, "arena");
        progress.flush();
        Files.delete(file);

        progress.flush();

        assertThat(file).doesNotExist();
        progress.clear(steve);
        progress.flush();
        assertThat(new Progress(file).load().size()).isZero();
    }

    @Test
    void oddStageNamesRoundTripAndTheFileHasNoDateLine() throws IOException {
        String[] names = {"a=b", "key: value", "#not a comment", "  spaced  ", "line\nbreak", "été ☃", ""};
        Path file = dir.resolve("progress.properties");
        Progress progress = new Progress(file).load();
        UUID[] players = new UUID[names.length];
        for (int i = 0; i < names.length; i++) {
            players[i] = UUID.randomUUID();
            progress.setStage(players[i], names[i]);
        }
        progress.flush();

        Progress reloaded = new Progress(file).load();

        for (int i = 0; i < names.length; i++) {
            assertThat(reloaded.stage(players[i])).as(names[i]).isEqualTo(names[i]);
        }
        assertThat(Files.readAllLines(file)).filteredOn(l -> l.startsWith("#")).hasSize(1);
    }

    @Test
    void aFailedWriteKeepsTheChangesForTheNextFlush() throws IOException {
        UUID steve = UUID.randomUUID();
        Path blocker = dir.resolve("blocker");
        Files.writeString(blocker, "a file where the folder should be");
        Path file = blocker.resolve("progress.properties");
        Progress progress = new Progress(file).load();
        progress.setStage(steve, "arena");

        assertThatIOException().isThrownBy(progress::flush);
        Files.delete(blocker);
        progress.flush();

        assertThat(new Progress(file).load().stage(steve)).isEqualTo("arena");
    }

    @Test
    void aKeyThatIsNotAUuidNamesTheFileAndTheKey() throws IOException {
        Path file = dir.resolve("progress.properties");
        Files.writeString(file, "not-a-uuid=arena\n");

        assertThatIOException().isThrownBy(() -> new Progress(file).load())
                .withMessageContaining(file.toString())
                .withMessageContaining("not a player UUID: not-a-uuid");
    }
}
