package io.github.imnotstable.msc.core.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** #457: the one way a path argument becomes a Path. */
class PathArgTest {
    @TempDir
    Path ws;

    @Test
    void resolvesRelativePathsInsideTheWorkspace() {
        assertThat(PathArg.inside(ws).resolve("file", "a/../b/c.txt")).isEqualTo(ws.resolve("b/c.txt"));
        assertThat(PathArg.inside(ws).resolve("dir", ".")).isEqualTo(ws.toAbsolutePath().normalize());
        assertThat(PathArg.inside(ws).resolve("file", ws.resolve("x").toString())).isEqualTo(ws.resolve("x"));
        assertThat(PathArg.anywhere(ws).resolve("path", "..")).isEqualTo(ws.getParent());
    }

    @Test
    void refusesWhatTheFileSystemCannotHoldInOneLineNamingTheArgument() {
        PathArg rule = PathArg.anywhere(ws);
        assertThatThrownBy(() -> rule.resolve("file", "a\u0000b")).isInstanceOf(ToolException.class)
                .hasMessage("argument file has a control character (U+0000); give a plain path");
        assertThatThrownBy(() -> rule.resolve("file", "a\nb")).hasMessageContaining("U+000A");
        assertThatThrownBy(() -> rule.resolve("dir", "x/" + "a".repeat(300))).isInstanceOf(ToolException.class)
                .hasMessageStartingWith("argument dir has a name of 300 bytes (aaaa").hasMessageContaining("at most 255");
    }

    @Test
    void theInsideRuleRefusesPathsOutsideTheWorkspace() {
        assertThatThrownBy(() -> PathArg.inside(ws).resolve("project", "..")).isInstanceOf(ToolException.class)
                .hasMessageStartingWith("argument project must be inside the workspace ")
                .hasMessageEndingWith("switch with msc_workspace.");
        assertThatThrownBy(() -> PathArg.inside(ws).resolve("project", "/etc")).hasMessageContaining("inside the workspace");
        assertThatThrownBy(() -> PathArg.inside(ws).resolve("project", "a/../../b")).hasMessageContaining("inside");
        // A sibling whose name starts with the workspace's is still outside.
        assertThatThrownBy(() -> PathArg.inside(ws).resolve("f", "../" + ws.getFileName() + "x/f"))
                .hasMessageContaining("inside");
    }

    @Test
    void createFoldersRefusesInOneLineWithTheReason() throws IOException {
        Files.writeString(ws.resolve("version.txt"), "1");
        assertThatThrownBy(() -> PathArg.createFolders(ws.resolve("version.txt/sub"), "folder"))
                .isInstanceOf(ToolException.class).hasMessageStartingWith("folder " + ws.resolve("version.txt/sub")
                        + " could not be created: ").hasMessageNotContaining("\n").hasMessageNotContaining("Exception");
        assertThat(PathArg.createFolders(ws.resolve("a/b"), "folder")).isDirectory();
    }
}
