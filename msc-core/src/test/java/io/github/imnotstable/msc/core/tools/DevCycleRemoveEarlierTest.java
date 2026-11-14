package io.github.imnotstable.msc.core.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.github.imnotstable.msc.core.files.AtomicFile;
import io.github.imnotstable.msc.core.tool.ToolException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * #398: dev_cycle deploys the new jar, then removes the earlier one. Whether the earlier one is the jar just deployed
 * is the file system's answer, never a path comparison: on a case-insensitive volume {@code Lobby.jar} is
 * {@code lobby.jar}. Where this file system is case-sensitive, a path through a linked folder is the other spelling.
 */
class DevCycleRemoveEarlierTest {
    @TempDir
    Path dir;

    private final AtomicInteger redeploys = new AtomicInteger();

    private Path plugins() throws IOException {
        return Files.createDirectories(dir.resolve("plugins"));
    }

    private boolean remove(Path earlier, Path target) throws IOException {
        return DevCycle.removeEarlier(earlier, target, AtomicFile::sameFile, redeploys::incrementAndGet);
    }

    private static Path alias(Path dir) throws IOException {
        try {
            return Files.createSymbolicLink(dir.resolve("same"), Path.of("."));
        } catch (UnsupportedOperationException | IOException e) {
            assumeTrue(false, "symbolic links: " + e.getMessage());
            throw e;
        }
    }

    @Test
    void theSameNameIsKept() throws IOException {
        Path jar = Files.writeString(plugins().resolve("lobby.jar"), "new");
        assertThat(remove(jar, jar)).isFalse();
        assertThat(jar).hasContent("new");
    }

    @Test
    void anotherSpellingOfTheDeployedJarIsKept() throws IOException {
        Path jar = Files.writeString(plugins().resolve("lobby.jar"), "new");
        assertThat(remove(alias(plugins()).resolve("lobby.jar"), jar)).isFalse();
        assertThat(jar).hasContent("new");
        assertThat(redeploys).hasValue(0);
    }

    @Test
    void aNameThatDiffersOnlyInCaseKeepsTheNewJarOnAnyVolume() throws IOException {
        Path jar = Files.writeString(plugins().resolve("lobby.jar"), "new");
        Path earlier = plugins().resolve("Lobby.jar");
        boolean caseInsensitive = Files.exists(earlier);
        if (!caseInsensitive) {
            Files.writeString(earlier, "old");
        }
        // Case-sensitive: two jars, the earlier goes. Case-insensitive: one file, which stays.
        assertThat(remove(earlier, jar)).isEqualTo(!caseInsensitive);
        assertThat(jar).hasContent("new");
        assertThat(redeploys).hasValue(0);
    }

    @Test
    void anotherNameIsRemoved() throws IOException {
        Path jar = Files.writeString(plugins().resolve("arena.jar"), "new");
        Path earlier = Files.writeString(plugins().resolve("demo.jar"), "old");
        assertThat(remove(earlier, jar)).isTrue();
        assertThat(earlier).doesNotExist();
        assertThat(jar).hasContent("new");
        assertThat(remove(plugins().resolve("gone.jar"), jar)).as("nothing there").isFalse();
    }

    @Test
    void aLinkToTheNewJarIsASecondEntryAndGoes() throws IOException {
        Path jar = Files.writeString(plugins().resolve("lobby.jar"), "new");
        Path link;
        try {
            link = Files.createSymbolicLink(plugins().resolve("Lobby-old.jar"), jar.getFileName());
        } catch (UnsupportedOperationException | IOException e) {
            assumeTrue(false, "symbolic links: " + e.getMessage());
            return;
        }
        assertThat(remove(link, jar)).isTrue();
        assertThat(Files.exists(link, java.nio.file.LinkOption.NOFOLLOW_LINKS)).isFalse();
        assertThat(jar).hasContent("new");
    }

    @Test
    void aDeleteThatTookTheNewJarAnywayRedeploysItAndFailsLoudly() throws IOException {
        Path jar = Files.writeString(plugins().resolve("lobby.jar"), "new");
        Path earlier = alias(plugins()).resolve("lobby.jar");
        // A sameness check that is wrong, as a path comparison is on a case-insensitive volume.
        assertThatThrownBy(() -> DevCycle.removeEarlier(earlier, jar, (a, b) -> false, () -> {
            redeploys.incrementAndGet();
            Files.writeString(jar, "new");
        })).isInstanceOf(ToolException.class)
                .hasMessageContaining("also removed the jar just deployed as " + jar)
                .hasMessageContaining("deployed again");
        assertThat(redeploys).hasValue(1);
        assertThat(jar).hasContent("new");
    }

    @Test
    void aLibraryUnderAnotherSpellingOfAKeptOneIsNotDeleted() throws IOException {
        Path src = Files.createDirectories(dir.resolve("src"));
        Path dest = Files.createDirectories(dir.resolve("libs"));
        Files.writeString(src.resolve("dep.jar"), "dep");
        Path kept = Files.writeString(dest.resolve("dep.jar"), "dep");
        // On a case-insensitive volume Dep.jar and dep.jar are one file; a hard link is one file under two names here.
        Path other = dest.resolve("Dep.jar");
        if (!Files.exists(other)) {
            try {
                Files.createLink(other, kept);
            } catch (UnsupportedOperationException | IOException e) {
                assumeTrue(false, "hard links: " + e.getMessage());
            }
        }
        assertThat(DevCycle.mirror(src, dest, true)).isZero();
        assertThat(DevCycle.mirror(src, dest, false)).isZero();
        assertThat(kept).hasContent("dep");
        Files.writeString(dest.resolve("stale.jar"), "old");
        assertThat(DevCycle.mirror(src, dest, false)).isEqualTo(1);
        assertThat(dest.resolve("stale.jar")).doesNotExist();
        assertThat(kept).hasContent("dep");
    }
}
