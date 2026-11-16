package io.github.imnotstable.msc.core;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.imnotstable.msc.core.fixture.FakeServerMain;
import io.github.imnotstable.msc.core.fixture.SleepMain;
import io.github.imnotstable.msc.core.server.FakeLeftover;
import io.github.imnotstable.msc.core.server.PidFile;
import io.github.imnotstable.msc.core.server.PidFileAccess;
import io.github.imnotstable.msc.core.server.Platform;
import io.github.imnotstable.msc.core.tools.WorldBackupsAccess;
import java.time.Duration;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** build_undo backup=: whole-world backups, the one a big build takes first, and rollback (ADR 0041). */
@Timeout(value = 2, unit = TimeUnit.MINUTES, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class WorldBackupToolsTest {
    @TempDir
    Path workspace;

    private Path world(String text) throws Exception {
        Path level = workspace.resolve("run/world/level.dat");
        Files.createDirectories(level.getParent());
        Files.writeString(level, text);
        return level;
    }

    /**
     * The templates' Minestom world as Minestom 2026.10.07-26.2 writes it: region files under
     * {@code dimensions/minecraft/overworld}, no {@code level.dat} (#654).
     */
    private Path minestomWorld(String text) throws Exception {
        Path region = workspace.resolve("run/world/dimensions/minecraft/overworld/region/r.0.0.mca");
        Files.createDirectories(region.getParent());
        Files.writeString(region, text);
        return region;
    }

    private static List<String> fakeServer() {
        return List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", System.getProperty("java.class.path"), FakeServerMain.class.getName());
    }

    /** The console lines the fake server echoed for save commands, in order, once save-on has arrived. */
    private static List<String> saveCommands(CoreHarness h) {
        h.ok("server_wait", "until", "console: save-on", "timeoutSeconds", 10);
        return h.ok("server_logs", "mode", "grep", "pattern", "console: save").lines()
                .map(l -> l.substring(l.indexOf("console: ") + 9)).toList();
    }

    @Test
    void withoutAnAgentTheConsoleFlushesAndWaitsForTheSave() throws Exception {
        world("v1");
        Files.writeString(workspace.resolve("run/no-agent.flag"), "");
        try (CoreHarness h = new CoreHarness(workspace, c -> c.withServerCommand(fakeServer()))) {
            assertThat(h.ok("server_start", "timeoutSeconds", 30)).contains("no agent connected");
            assertThat(h.ok("build_undo", "backup", "save")).matches("backup \\d{8}-\\d{6}-manual \\(world; 0\\.0 MB\\)");
            assertThat(saveCommands(h)).containsExactly("save-off", "save-all flush", "save-on");
        }
    }

    @Test
    void aServerThatNeverConfirmsTheSaveGetsNoBackupAndAutosaveBack() throws Exception {
        world("v1");
        Files.writeString(workspace.resolve("run/no-agent.flag"), "");
        Files.writeString(workspace.resolve("run/no-save.flag"), "");
        Duration old = WorldBackupsAccess.saveWait(Duration.ofSeconds(1));
        try (CoreHarness h = new CoreHarness(workspace, c -> c.withServerCommand(fakeServer()))) {
            h.ok("server_start", "timeoutSeconds", 30);
            assertThat(h.error("build_undo", "backup", "save"))
                    .isEqualTo("error: the server did not confirm save-all flush within 1s; no backup taken");
            assertThat(saveCommands(h)).containsExactly("save-off", "save-all flush", "save-on");
            assertThat(h.ok("build_undo", "backup", "list")).isEqualTo("no backups yet");
        } finally {
            WorldBackupsAccess.saveWait(old);
        }
    }

    @Test
    void minestomSendsNoCommandsAndSaysTheBackupIsTheWorldAsLastStopped() throws Exception {
        minestomWorld("v1");
        Platform.remember(CoreConfig.forWorkspace(workspace), Platform.MINESTOM);
        try (CoreHarness h = new CoreHarness(workspace).withAgent()) {
            assertThat(h.ok("build_undo", "backup", "save")).startsWith("backup ").contains(" (world; ").endsWith(
                    "\nMinestom saves the world only when it stops: this backup is the world as last stopped");
            assertThat(h.agent.commands).isEmpty();
        }
    }

    /**
     * #709: a save copied the world of a server this session neither started nor reaches, unflushed and with no note.
     * It is refused as every call that needs this session's server is (#705), for one an earlier session left running
     * and one another live session runs, on Minestom too; once that server has stopped, the save works again.
     */
    @Test
    void saveRefusesAServerOfAnotherSessionAsEveryCallThatNeedsTheServerDoes() throws Exception {
        minestomWorld("v1");
        Platform.remember(CoreConfig.forWorkspace(workspace), Platform.MINESTOM);
        try (CoreHarness h = new CoreHarness(workspace)) {
            // Another session's core, still running, standing as its own server too (ADR 0037).
            Process other = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                    "-cp", System.getProperty("java.class.path"), SleepMain.class.getName(), "60").start();
            try {
                PidFileAccess.write(Files.createDirectories(workspace.resolve(".msc")).resolve("server.pid"),
                        other.toHandle(), PidFile.Identity.of(other.toHandle()));
                String why = h.error("world_slice", "region", "0,60,0 1,60,1");
                assertThat(why).contains("another session that is still running");
                assertThat(h.error("build_undo", "backup", "save x")).isEqualTo(why);
                assertThat(h.error("build_undo", "backup", "x")).as("a restore, as before").isEqualTo(why);
            } finally {
                other.destroyForcibly().waitFor(10, TimeUnit.SECONDS);
            }
            FakeLeftover leftover = FakeLeftover.install(h.core.server(), Duration.ofMillis(1));
            try {
                String why = h.error("world_slice", "region", "0,60,0 1,60,1");
                assertThat(why).contains("pid " + leftover.pid() + ") was left by an earlier session");
                assertThat(h.error("build_undo", "backup", "save y")).isEqualTo(why);
            } finally {
                leftover.exit();
            }
            assertThat(h.ok("build_undo", "backup", "list")).isEqualTo("no backups yet");
            assertThat(h.ok("build_undo", "backup", "save z")).matches("backup \\d{8}-\\d{6}-z \\(world; 0\\.0 MB\\)");
        }
    }

    @Test
    void saveFlushesWithAutosaveOffAndBigBuildsTakeOneFirst() throws Exception {
        world("v1");
        try (CoreHarness h = new CoreHarness(workspace).withAgent()) {
            assertThat(h.ok("build_undo", "backup", "list")).isEqualTo("no backups yet");
            assertThat(h.ok("build_undo", "backup", "save before towers"))
                    .matches("backup \\d{8}-\\d{6}-before-towers \\(world; 0\\.0 MB\\)");
            assertThat(h.agent.commands).containsExactly("save-off", "save-all flush", "save-on");
            assertThat(h.error("build_undo", "edit", "e1", "backup", "list")).contains("give edit or backup, not both");
            // An attached server this session did not start cannot be stopped here, so its folders stay as they are.
            assertThat(h.error("build_undo", "backup", "before-towers")).contains("not started by this session");

            // Below the threshold: no backup. A big one is due only when the newest is older than five minutes.
            assertThat(h.ok("build_batch", "steps", "fill 0,64,0 9,64,9 stone")).doesNotContain("backup");
            assertThat(h.ok("build_batch", "steps", "fill 0,0,0 99,9,99 stone")).doesNotContain("backup");
            Path backups = workspace.resolve(".msc/backups");
            try (var kept = Files.list(backups)) {
                for (Path p : kept.toList()) {
                    Files.move(p, backups.resolve("20200101-000000-old"));
                }
            }
            assertThat(h.ok("build_batch", "steps", "fill 0,0,0 99,9,99 dirt"))
                    .containsPattern("\nbackup \\d{8}-\\d{6}-auto taken first \\(build_undo backup=NAME restores it\\)$");
            assertThat(h.ok("build_undo", "backup", "list").lines()).hasSize(2);
            assertThat(h.ok("build_undo")).startsWith("undid e3");
        }
    }

    /** Who runs a server in the folder when a rollback starts. */
    enum Running { NONE, THIS_SESSION, EARLIER_SESSION, ATTACHED }

    /**
     * Rollback over every server state (#583), on a platform that saves while running and on Minestom: the state comes
     * from the stop itself, so a server an earlier session left running is reported as server_start reports its stop,
     * and started again like one this session started. An attached server this session cannot stop is refused.
     */
    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("serverStates")
    void rollbackStopsWhicheverServerRunsAndStartsItAgain(Platform platform, Running running) throws Exception {
        Path level = platform == Platform.MINESTOM ? minestomWorld("before") : world("before");
        if (platform != Platform.PAPER) {
            Platform.remember(CoreConfig.forWorkspace(workspace), platform);
        }
        Process leftover = null;
        try (CoreHarness h = new CoreHarness(workspace, c -> c.withServerCommand(fakeServer()))) {
            String saved = h.ok("build_undo", "backup", "save").split(" ")[1];
            Files.writeString(level, "after");
            switch (running) {
                case NONE -> { }
                case THIS_SESSION -> h.ok("server_start", "timeoutSeconds", 30);
                case EARLIER_SESSION -> {
                    leftover = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                            "-cp", System.getProperty("java.class.path"), SleepMain.class.getName()).start();
                    PidFile.write(workspace.resolve(".msc/server.pid"), leftover.toHandle());
                    assertThat(h.ok("msc_status")).contains("server: running, left by an earlier session (pid "
                            + leftover.pid());
                }
                case ATTACHED -> h.withAgent();
            }

            if (running == Running.ATTACHED) {
                assertThat(h.error("build_undo", "backup", saved)).isEqualTo(
                        "error: the connected server was not started by this session; stop it, then roll back");
                assertThat(level).hasContent("after");
                return;
            }
            String out = h.ok("build_undo", "backup", saved);
            assertThat(level).hasContent("before");
            String stoppedLeftover = "\nstopped a server left running by an earlier session (pid ";
            if (running == Running.EARLIER_SESSION) {
                assertThat(out).contains(stoppedLeftover + leftover.pid() + ", killed: ");
                assertThat(leftover.waitFor(10, TimeUnit.SECONDS)).isTrue();
            } else {
                assertThat(out).doesNotContain(stoppedLeftover);
            }
            if (running == Running.NONE) {
                assertThat(out).endsWith("\nserver not started (it was not running)");
                assertThat(h.ok("msc_status")).doesNotContain("server: running");
            } else {
                assertThat(out).contains("server ready in ").doesNotContain("not started");
                assertThat(h.ok("msc_status")).contains("server: running (");
            }
        } finally {
            if (leftover != null) {
                leftover.destroyForcibly();
            }
        }
    }

    static Stream<Arguments> serverStates() {
        return Stream.concat(Arrays.stream(Running.values()).map(r -> Arguments.of(Platform.PAPER, r)),
                Stream.of(Running.THIS_SESSION, Running.EARLIER_SESSION).map(r -> Arguments.of(Platform.MINESTOM, r)));
    }

    @Test
    void rollbackStopsRestoresAndStartsTheServerAgain() throws Exception {
        Path level = world("before");
        try (CoreHarness h = new CoreHarness(workspace, c -> c.withServerCommand(fakeServer()))) {
            String saved = h.ok("build_undo", "backup", "save").split(" ")[1];
            h.ok("server_start", "timeoutSeconds", 30);
            h.ok("build_batch", "steps", "set 0,64,0 stone");
            Files.writeString(level, "after");
            Files.writeString(level.resolveSibling("session.lock"), "held");

            String out = h.ok("build_undo", "backup", saved);
            assertThat(out).startsWith("rolled back to " + saved + " (world; ")
                    .containsPattern("\nthe world before it is backup \\d{8}-\\d{6}-before-rollback\n")
                    .contains("server ready in ");
            assertThat(level).hasContent("before");
            assertThat(h.ok("msc_status")).contains("server: running");
            // The edit ids named blocks of the world that was replaced.
            assertThat(h.error("build_undo")).contains("nothing to undo");
            // The world it replaced is a backup too, so a rollback can be rolled back.
            h.ok("server_stop");
            assertThat(h.ok("build_undo", "backup", "before-rollback"))
                    .contains("\nserver not started (it was not running)");
            assertThat(level).hasContent("after");
            assertThat(h.error("build_undo", "backup", "nope")).contains("no backup nope; kept: ");
            // Blank or ambiguous never rolls back (#573): two before-rollback backups now, both taken this second.
            Files.writeString(level, "kept");
            assertThat(h.error("build_undo", "backup", "")).startsWith("error: backup takes list, save [label], or a backup name; kept: ");
            assertThat(h.error("build_undo", "backup", "  ")).contains("backup takes list");
            assertThat(h.error("build_undo", "backup", "before-rollback")).contains("backup before-rollback matches 2: ")
                    .endsWith("; give the whole name");
            assertThat(level).hasContent("kept");
        }
    }
}
