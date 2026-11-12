package {{packageName}};

import static org.assertj.core.api.Assertions.assertThat;

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
}
