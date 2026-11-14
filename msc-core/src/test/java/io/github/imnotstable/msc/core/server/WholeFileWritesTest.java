package io.github.imnotstable.msc.core.server;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.imnotstable.msc.core.CoreConfig;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The pid file, the loader marker and the remembered platform are written whole or not at all (AtomicFile): the new
 * file replaces the old one instead of being written into it, so a hard link to the old file, standing for a reader
 * that has it open, keeps the old content whole.
 */
class WholeFileWritesTest {
    @TempDir
    Path dir;

    @Test
    void thePidFileIsReplacedNotRewrittenInPlace() throws IOException {
        Path file = dir.resolve("run/server.pid");
        PidFile.write(file, ProcessHandle.current());
        String first = Files.readString(file);
        Path old = Files.createLink(dir.resolve("old"), file);
        PidFile.markResisting(file);
        assertThat(old).hasContent(first.strip());
        assertThat(Files.readString(file)).isEqualTo(first.strip() + " " + PidFile.RESISTING + "\n");
        assertThat(PidFile.resisting(file)).isTrue();
        Path older = Files.createLink(dir.resolve("older"), file);
        PidFile.write(file, ProcessHandle.current());
        assertThat(Files.readString(older)).isEqualTo(first.strip() + " " + PidFile.RESISTING + "\n");
        assertThat(file).hasContent(first.strip());
        assertThat(leftovers(dir.resolve("run"))).containsExactly("server.pid");
    }

    @Test
    void theLoaderMarkerIsReplacedNotRewrittenInPlace() throws IOException {
        Path server = dir.resolve("server");
        LoaderInstaller.mark(server, "neoforge 26.3.0.1");
        Path old = Files.createLink(dir.resolve("old"), server.resolve(LoaderInstaller.MARKER));
        LoaderInstaller.mark(server, "neoforge 26.3.0.2");
        assertThat(old).hasContent("neoforge 26.3.0.1");
        assertThat(server.resolve(LoaderInstaller.MARKER)).hasContent("neoforge 26.3.0.2");
        assertThat(leftovers(server)).containsExactly(LoaderInstaller.MARKER);
    }

    @Test
    void theRememberedPlatformIsReplacedNotRewrittenInPlace() throws IOException {
        CoreConfig config = CoreConfig.forWorkspace(dir);
        Platform.remember(config, Platform.FABRIC);
        Path old = Files.createLink(dir.resolve("old"), Platform.file(config));
        Platform.remember(config, Platform.PAPER);
        assertThat(old).hasContent(Platform.FABRIC.id());
        assertThat(Platform.current(config)).isEqualTo(Platform.PAPER);
    }

    private static List<String> leftovers(Path folder) throws IOException {
        try (var files = Files.list(folder)) {
            return files.map(p -> p.getFileName().toString()).toList();
        }
    }
}
