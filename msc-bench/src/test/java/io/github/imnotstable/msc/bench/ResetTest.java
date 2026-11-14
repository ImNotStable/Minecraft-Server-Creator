package io.github.imnotstable.msc.bench;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.github.imnotstable.msc.bench.task.Reset;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** #495: what task.json's resetBeforeChecks deletes, and what it never touches. */
class ResetTest {

    @TempDir
    Path dir;

    @Test
    void deletesFilesAndFoldersAndSkipsWhatIsMissing() throws IOException {
        Path workspace = Files.createDirectories(dir.resolve("workspace"));
        Files.writeString(Files.createDirectories(workspace.resolve("server/plugins/P")).resolve("config.yml"), "x");
        Files.writeString(Files.createDirectories(workspace.resolve("data/deep")).resolve("f"), "x");
        Files.writeString(workspace.resolve("keep.txt"), "x");

        new Reset(List.of("server/plugins/P/config.yml", "data", "never/made")).apply(workspace);

        assertThat(workspace.resolve("server/plugins/P/config.yml")).doesNotExist();
        assertThat(workspace.resolve("server/plugins/P")).isDirectory();
        assertThat(workspace.resolve("data")).doesNotExist();
        assertThat(workspace.resolve("keep.txt")).exists();
    }

    @Test
    void aPathThroughALinkOutOfTheWorkspaceDeletesNothing() throws IOException {
        Path workspace = Files.createDirectories(dir.resolve("workspace"));
        Path outside = Files.createDirectories(dir.resolve("outside"));
        Files.writeString(outside.resolve("config.yml"), "not the bench's");
        Files.writeString(workspace.resolve("mine.txt"), "x");
        assumeTrue(link(workspace.resolve("plugins"), outside), "this file system makes no symbolic links");

        assertThatThrownBy(() -> new Reset(List.of("mine.txt", "plugins/config.yml")).apply(workspace))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("plugins/config.yml leads outside the workspace");
        assertThat(outside.resolve("config.yml")).exists();
        assertThat(workspace.resolve("mine.txt")).as("nothing is deleted").exists();
    }

    @Test
    void aLinkItselfIsDeletedNeverWhatItPointsTo() throws IOException {
        Path workspace = Files.createDirectories(dir.resolve("workspace"));
        Path outside = Files.createDirectories(dir.resolve("outside"));
        Files.writeString(outside.resolve("config.yml"), "not the bench's");
        assumeTrue(link(workspace.resolve("plugins"), outside), "this file system makes no symbolic links");

        new Reset(List.of("plugins")).apply(workspace);

        assertThat(workspace.resolve("plugins")).doesNotExist();
        assertThat(outside.resolve("config.yml")).exists();
    }

    private static boolean link(Path link, Path target) {
        try {
            Files.createSymbolicLink(link, target);
            return true;
        } catch (IOException | UnsupportedOperationException e) {
            return false; // Windows without the privilege
        }
    }
}
