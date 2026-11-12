package io.github.imnotstable.msc.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LegacyInstallTest {
    @TempDir
    Path root;

    @Test
    void refreshesAFlatInstallFromTheNewOneWithoutDeleting() throws Exception {
        write("lib/msc-core-0.1.0-SNAPSHOT.jar", "old core");
        write("lib/gson-2.10.jar", "old gson");
        write("agents/msc-agent-paper-0.1.0-SNAPSHOT-all.jar", "old agent");
        write("install.properties", "installed=1\n");
        write("claude-mcp-add.txt", "claude mcp add msc -- java -cp lib/* Main\n");
        Path fresh = root.resolve("msc-core-0.1.0-SNAPSHOT-20261112-090000");
        write(fresh.resolve("lib/msc-core-0.1.0-SNAPSHOT.jar"), "new core");
        write(fresh.resolve("lib/gson-2.15.0.jar"), "new gson");
        write(fresh.resolve("agents/msc-agent-paper-0.1.0-SNAPSHOT-all.jar"), "new agent");
        write(fresh.resolve("bot/26.2/protocol-26.2.jar"), "26.2");
        write(fresh.resolve("install.properties"), "installed=2\njava=/jdk/bin/java\n");

        assertThat(LegacyInstall.refresh(root, fresh)).first().asString().contains("1 old jars renamed");

        assertThat(root.resolve("lib/msc-core-0.1.0-SNAPSHOT.jar")).hasContent("new core");
        assertThat(root.resolve("lib/gson-2.15.0.jar")).hasContent("new gson");
        // Out of lib/*, still on disk.
        assertThat(root.resolve("lib/gson-2.10.jar")).doesNotExist();
        assertThat(root.resolve("lib/gson-2.10.jar.replaced")).hasContent("old gson");
        assertThat(root.resolve("agents/msc-agent-paper-0.1.0-SNAPSHOT-all.jar")).hasContent("new agent");
        assertThat(root.resolve("bot/26.2/protocol-26.2.jar")).hasContent("26.2");
        assertThat(root.resolve("install.properties")).content().contains("installed=2");
        assertThat(root.resolve("claude-mcp-add.txt")).content()
                .contains("\"/jdk/bin/java\" -cp \"" + LegacyInstall.slash(root.resolve("lib")) + "/*\"")
                .contains("claude mcp remove msc -s user");
        // A second install with nothing stale renames nothing.
        assertThat(LegacyInstall.refresh(root, fresh)).first().asString().contains("(0 old jars renamed");
    }

    @Test
    void leavesAVersionedOnlyRootAlone() throws Exception {
        Path fresh = root.resolve("msc-core-0.1.0-SNAPSHOT-20261112-090000");
        write(fresh.resolve("lib/msc-core-0.1.0-SNAPSHOT.jar"), "new core");
        write(fresh.resolve("install.properties"), "installed=2\n");
        assertThat(LegacyInstall.refresh(root, fresh)).isEmpty();
        assertThat(root.resolve("lib")).doesNotExist();
        assertThat(root.resolve("claude-mcp-add.txt")).doesNotExist();
    }

    @Test
    void theInstallCommandNamesANonDefaultFolder() {
        Path home = root.resolve("home");
        Path source = root.resolve("repo");
        String base = "\"" + LegacyInstall.slash(source.resolve("gradlew")) + "\" -p \"" + LegacyInstall.slash(source)
                + "\" :msc-core:installMsc";
        assertThat(LegacyInstall.installCommand(home.resolve(".msc/core/msc-core-1-a"), source, home)).isEqualTo(base);
        assertThat(LegacyInstall.installCommand(home.resolve(".msc/core"), source, home)).isEqualTo(base);
        assertThat(LegacyInstall.installCommand(root.resolve("other/msc-core-1-a"), source, home))
                .isEqualTo(base + " -Pmsc.installDir=\"" + LegacyInstall.slash(root.resolve("other")) + "\"");
        assertThat(LegacyInstall.installCommand(root.resolve("flat"), source, home))
                .isEqualTo(base + " -Pmsc.installDir=\"" + LegacyInstall.slash(root.resolve("flat")) + "\"");
    }

    private void write(String rel, String text) throws Exception {
        write(root.resolve(rel), text);
    }

    private static void write(Path file, String text) throws Exception {
        Files.createDirectories(file.getParent());
        Files.writeString(file, text);
    }
}
