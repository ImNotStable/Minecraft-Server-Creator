package io.github.imnotstable.msc.core;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.imnotstable.msc.core.fixture.FakeServerMain;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

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

    @Test
    void rollbackStopsRestoresAndStartsTheServerAgain() throws Exception {
        Path level = world("before");
        List<String> java = List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", System.getProperty("java.class.path"), FakeServerMain.class.getName());
        try (CoreHarness h = new CoreHarness(workspace, c -> c.withServerCommand(java))) {
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
        }
    }
}
