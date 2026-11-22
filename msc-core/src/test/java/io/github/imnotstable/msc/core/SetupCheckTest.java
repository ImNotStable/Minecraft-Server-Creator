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

    /** #633: the one-time notice is about the workspace the result is about, worked out after the call. */
    @Test
    void theRepoNoticeFollowsTheWorkspaceSwitch() throws Exception {
        Path repo = repo();
        Path server = Files.createDirectories(dir.resolve("server"));
        String warning = "warning: the workspace is the Minecraft Server Creator repo";
        try (CoreHarness h = new CoreHarness(repo)) {
            assertThat(h.ok("msc_workspace", "path", server.toString())).startsWith("workspace: ")
                    .doesNotContain(warning);
            assertThat(h.ok("msc_workspace", "path", repo.toString())).startsWith(warning + " (" + repo.toRealPath());
            assertThat(h.ok("msc_workspace")).doesNotContain(warning);
        }
        try (CoreHarness h = new CoreHarness(repo)) {
            assertThat(h.ok("msc_workspace")).startsWith(warning);
            assertThat(h.ok("msc_workspace")).doesNotContain(warning);
        }
    }

    /** #637: msc_status as the first call lists each setup warning once, not again in the notice in front. */
    @Test
    void mscStatusFirstShowsEachWarningOnce() throws Exception {
        Path repo = repo();
        String warning = "warning: the workspace is the Minecraft Server Creator repo";
        try (CoreHarness h = new CoreHarness(repo)) {
            String status = h.ok("msc_status");
            assertThat(status).startsWith("msc-core ").containsOnlyOnce(warning);
            assertThat(h.ok("msc_status")).containsOnlyOnce(warning);
        }
        try (CoreHarness h = new CoreHarness(repo)) {
            // stats=true does not list the warnings, so the notice still goes in front.
            assertThat(h.ok("msc_status", "stats", true)).startsWith(warning);
            assertThat(h.ok("msc_status", "stats", true)).doesNotContain(warning);
        }
    }

    @Test
    void warnsWhenTheCheckoutChangedAfterTheInstall() throws Exception {
        Path repo = repo();
        assertThat(SetupCheck.staleInstall(install(repo, 1_999_000_000_000L)))
                .startsWith("warning: this msc-core (installed ")
                .contains("msc-core/src/main/java/Main.java changed 2033-05-18T03:33:20Z")
                .contains("Run `\"" + LegacyInstall.slash(repo.resolve("gradlew")) + "\" -p \"" + LegacyInstall.slash(repo)
                        + "\" :msc-core:installMsc -Pmsc.installDir=")
                .endsWith("then reconnect msc in /mcp.");
        assertThat(SetupCheck.staleInstall(install(repo, 2_001_000_000_000L))).isNull();
        assertThat(SetupCheck.staleInstall(dir.resolve("nowhere"))).isNull();
    }

    @Test
    void warnsWhenANewerInstallSitsNextToThisOne() throws Exception {
        Path running = core(dir.resolve("msc-core-1-a"), 1_000L);
        assertThat(SetupCheck.newerInstall(running)).isNull();

        core(dir.resolve("msc-core-1-b"), 2_000L);
        Files.createDirectories(dir.resolve("no-core"));
        Files.writeString(dir.resolve("no-core/install.properties"), "installed=9000\n");

        assertThat(SetupCheck.newerInstall(running))
                .startsWith("warning: a newer msc-core was installed 1970-01-01T00:00:02Z in ")
                .contains("msc-core-1-b")
                .endsWith("Reconnect msc in /mcp to use it.");
        assertThat(SetupCheck.newerInstall(dir.resolve("msc-core-1-b"))).isNull();
        assertThat(SetupCheck.warnings(CoreConfig.forWorkspace(dir).withInstallDir(running)))
                .singleElement().asString().contains("msc-core-1-b");
    }

    @Test
    void theFlatInstallSeesTheNewerInstallsInsideItAndSaysToDropTheOldRegistration() throws Exception {
        Path flat = core(dir.resolve("core"), 1_000L);
        assertThat(SetupCheck.newerInstall(flat)).isNull();

        core(flat.resolve("msc-core-1-b"), 2_000L);
        assertThat(SetupCheck.newerInstall(flat)).contains("msc-core-1-b").contains("claude mcp remove msc -s user");
    }

    private static Path core(Path install, long installed) throws Exception {
        Files.createDirectories(install.resolve("lib"));
        Files.createFile(install.resolve("lib/msc-core-1.jar"));
        Files.writeString(install.resolve("install.properties"), "installed=" + installed + "\n");
        return install;
    }
}
