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
        Path file = dir.resolve("plugin/progress.yml");
        UUID steve = UUID.randomUUID();
        UUID alex = UUID.randomUUID();
        Progress progress = new Progress(file).load();
        progress.setStage(steve, "arena");
        progress.setStage(alex, "start");
        progress.clear(alex);
        progress.flush();

        Progress reloaded = new Progress(file).load();
        assertThat(reloaded.stage(steve)).isEqualTo("arena");
        assertThat(reloaded.stage(alex)).isNull();
        assertThat(reloaded.size()).isEqualTo(1);
    }

    @Test
    void flushWritesOnlyWhenSomethingChanged() throws IOException {
        Path file = dir.resolve("progress.yml");
        new Progress(file).load().flush();
        assertThat(file).doesNotExist();
        Files.writeString(file, "stage: {}\n");
        assertThat(new Progress(file).load().size()).isZero();
    }
}
