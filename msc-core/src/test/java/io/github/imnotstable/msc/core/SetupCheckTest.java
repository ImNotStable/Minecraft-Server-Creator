package io.github.imnotstable.msc.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SetupCheckTest {
    @TempDir
    Path dir;

    private Path repo() throws Exception {
        Path repo = Files.createDirectories(dir.resolve("repo"));
        Files.writeString(repo.resolve("settings.gradle.kts"), "rootProject.name = \"minecraft-server-creator\"\n");
        Path source = Files.createDirectories(repo.resolve("msc-core/src/main/java")).resolve("Main.java");
        Files.writeString(source, "class Main {}");
        Files.setLastModifiedTime(source, FileTime.fromMillis(2_000_000_000_000L));
        return repo;
    }

    private Path install(Path repo, long installed) throws Exception {
        Path install = Files.createDirectories(dir.resolve("install"));
        Files.writeString(install.resolve("install.properties"),
                "source=" + repo.toString().replace("\\", "\\\\") + "\ninstalled=" + installed + "\n");
        return install;
    }

    @Test
    void warnsWhenTheWorkspaceIsTheRepo() throws Exception {
        Path repo = repo();
        assertThat(SetupCheck.isMscRepo(repo)).isTrue();
        assertThat(SetupCheck.isMscRepo(dir)).isFalse();
        assertThat(SetupCheck.warnings(CoreConfig.forWorkspace(repo)))
                .singleElement().asString().startsWith("warning: the workspace is the Minecraft Server Creator repo");
        assertThat(SetupCheck.warnings(CoreConfig.forWorkspace(dir))).isEmpty();
    }

    @Test
    void warnsWhenTheCheckoutChangedAfterTheInstall() throws Exception {
        Path repo = repo();
        assertThat(SetupCheck.staleInstall(install(repo, 1_999_000_000_000L)))
                .startsWith("warning: this msc-core (installed ")
                .contains("msc-core/src/main/java/Main.java changed 2033-05-18T03:33:20Z")
                .endsWith("then restart Claude Code.");
        assertThat(SetupCheck.staleInstall(install(repo, 2_001_000_000_000L))).isNull();
        assertThat(SetupCheck.staleInstall(dir.resolve("nowhere"))).isNull();
    }
}
