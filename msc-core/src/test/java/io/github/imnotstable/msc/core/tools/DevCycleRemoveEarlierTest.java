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

    private String remove(Path earlier, Path target) throws IOException {
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
        assertThat(remove(jar, jar)).isNull();
        assertThat(jar).hasContent("new");
    }

    @Test
    void anotherSpellingOfTheDeployedJarIsKept() throws IOException {
        Path jar = Files.writeString(plugins().resolve("lobby.jar"), "new");
        assertThat(remove(alias(plugins()).resolve("lobby.jar"), jar)).isNull();
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
        assertThat(remove(earlier, jar)).isEqualTo(caseInsensitive ? null : "");
        assertThat(jar).hasContent("new");
        assertThat(redeploys).hasValue(0);
    }

    @Test
    void anotherNameIsRemoved() throws IOException {
        Path jar = Files.writeString(plugins().resolve("arena.jar"), "new");
        Path earlier = Files.writeString(plugins().resolve("demo.jar"), "old");
        assertThat(remove(earlier, jar)).isEmpty();
        assertThat(earlier).doesNotExist();
        assertThat(jar).hasContent("new");
        assertThat(remove(plugins().resolve("gone.jar"), jar)).as("nothing there").isNull();
        assertThat(entries()).containsExactly("arena.jar");
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
        assertThat(remove(link, jar)).isEmpty();
        assertThat(Files.exists(link, java.nio.file.LinkOption.NOFOLLOW_LINKS)).isFalse();
        assertThat(jar).hasContent("new");
    }

    /**
     * #405: a volume whose sameness answer is wrong, where removing one name removes the other too (here a linked
     * folder makes that real). The new jar stays, in place and never redeployed, and the call carries on with a note.
     */
    @Test
    void aWrongSamenessAnswerNeverLosesTheNewJarAndDoesNotFail() throws IOException {
        Path jar = Files.writeString(plugins().resolve("lobby.jar"), "new");
        Path earlier = alias(plugins()).resolve("lobby.jar");
        String note = DevCycle.removeEarlier(earlier, jar, (a, b) -> false, redeploys::incrementAndGet);
        assertThat(note).isEqualTo(earlier + " is the new jar under another name on this volume, so it was kept");
        assertThat(jar).hasContent("new");
        assertThat(redeploys).hasValue(0);
        assertThat(entries()).containsExactlyInAnyOrder("lobby.jar", "same");
    }

    @Test
    void aMoveThatFailsLeavesTheEarlierJarAndSaysHowToRecover() throws IOException {
        Path jar = Files.writeString(plugins().resolve("arena.jar"), "new");
        Path earlier = Files.writeString(plugins().resolve("demo.jar"), "old");
        // The aside name is taken by a folder that is not empty: the rename fails, nothing changes.
        Files.writeString(Files.createDirectories(plugins().resolve("demo.jar" + DevCycle.ASIDE)).resolve("x"), "x");
        assertThatThrownBy(() -> remove(earlier, jar)).isInstanceOf(ToolException.class)
                .hasMessageStartingWith("cannot remove the earlier deploy " + earlier);
        assertThat(earlier).hasContent("old");
        assertThat(jar).hasContent("new");
    }

    private java.util.List<String> entries() throws IOException {
        try (var list = Files.list(plugins())) {
            return list.map(p -> p.getFileName().toString()).toList();
        }
    }

    @Test
    void aFolderInLibsIsLeftAloneAndOneWhereALibraryGoesIsRefusedFirst() throws IOException {
        Path src = Files.createDirectories(dir.resolve("src"));
        Path dest = Files.createDirectories(dir.resolve("libs"));
        Files.writeString(src.resolve("dep.jar"), "dep");
        Files.writeString(Files.createDirectories(dest.resolve("notes")).resolve("readme.txt"), "kept");
        DevCycle.checkMirror(src, dest);
        assertThat(DevCycle.mirror(src, dest, false)).isEqualTo(1);
        assertThat(DevCycle.mirror(src, dest, true)).as("nothing left to do, so no retry loop").isZero();
        assertThat(dest.resolve("notes/readme.txt")).hasContent("kept");
        Files.delete(dest.resolve("dep.jar"));
        Files.createDirectories(dest.resolve("dep.jar"));
        assertThatThrownBy(() -> DevCycle.checkMirror(src, dest)).isInstanceOf(ToolException.class)
                .hasMessageContaining("is a folder where the library dep.jar goes. Remove it, then dev_cycle.");
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
