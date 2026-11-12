package io.github.imnotstable.msc.core.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.imnotstable.msc.core.e2e.McpStdioClient.Result;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A real Minestom server driven end to end through the installed core over MCP stdio, the way Claude Code drives it:
 * scaffold minestom-game, dev_cycle (Gradle build, deploy, start), build_batch, world_slice, world_summary,
 * world_render, player_test (a real client joins), world_entities, an unchanged dev_cycle (no restart), an edit and a dev_cycle (restart), server_logs and
 * server_stop, then stdin closes and the core exits. Minestom has no EULA, so CI can run it. Run with {@code ./gradlew :msc-core:e2eTest}.
 */
@Tag("e2e")
class MinestomEndToEndTest {
    private static final Duration SHORT = Duration.ofSeconds(60);
    /** dev_cycle runs the scaffolded project's own Gradle wrapper: a cold runner downloads Gradle and Minestom. */
    private static final Duration BUILD = Duration.ofMinutes(15);

    private final List<String> transcript = new ArrayList<>();

    @Test
    void scaffoldsBuildsRunsBuildsInAndPlaysAMinestomGame(@TempDir Path workspace) throws Exception {
        Path install = Path.of(System.getProperty("msc.e2e.install", "")).toAbsolutePath();
        assertThat(install.resolve("lib")).as("installDist output (set msc.e2e.install)").isDirectory();
        Path stderr = workspace.resolve("core-stderr.log");
        Path java = Path.of(System.getProperty("java.home"), "bin", "java");
        ProcessBuilder pb = new ProcessBuilder(java.toString(), "-cp",
                install.resolve("lib") + File.separator + "*", "io.github.imnotstable.msc.core.Main")
                .directory(workspace.toFile())
                .redirectError(stderr.toFile());
        Map<String, String> env = pb.environment();
        env.put("MSC_WORKSPACE", workspace.toString());
        env.put("MSC_JAVA", java.toString());
        try (McpStdioClient core = new McpStdioClient(pb.start())) {
            try {
                run(core, workspace);
                // Closing stdin ends the session; the core stops cleanly.
                assertThat(core.closeAndWait(SHORT)).as("core exit code").isZero();
            } catch (Throwable t) {
                writeReport(stderr);
                // The last tool results here; all of them are in build/e2e/transcript.txt.
                List<String> last = transcript.subList(Math.max(0, transcript.size() - 3), transcript.size());
                throw new AssertionError(t.getMessage() + "\n--- last tool results ---\n" + String.join("\n", last)
                        + "\n--- core stderr (tail) ---\n" + tail(stderr, 30), t);
            }
        }
        writeReport(stderr);
    }

    /** Every tool result, as an AI would have seen it, and the core's stderr to build/e2e/ (a CI artifact). */
    private void writeReport(Path stderr) throws IOException {
        String dir = System.getProperty("msc.e2e.report");
        if (dir == null) {
            return;
        }
        Path out = Files.createDirectories(Path.of(dir));
        Files.writeString(out.resolve("transcript.txt"), String.join("\n\n", transcript) + "\n");
        if (Files.exists(stderr)) {
            Files.copy(stderr, out.resolve("core-stderr.log"), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void run(McpStdioClient core, Path workspace) throws Exception {
        core.initialize();

        ok(core, "project_scaffold", SHORT, "name", "E2eGame", "dir", "game", "template", "minestom-game");
        assertThat(ok(core, "server_setup", SHORT, "platform", "minestom").text()).contains("no EULA step");

        // Build, deploy and start: the server reports ready with the agent connected.
        assertThat(ok(core, "dev_cycle", BUILD, "project", "game").text())
                .contains("build ok").contains("server ready").contains("agent connected");

        // Build in the world the template made (superflat, grass at y=-61).
        Result batch = ok(core, "build_batch", SHORT, "steps", """
                fill 4,-60,4 8,-58,8 stone_bricks hollow
                set 6,-57,6 glowstone
                cylinder 14,-60,6 r=2 h=3 oak_planks""");
        assertThat(batch.text()).containsPattern("e\\d+");

        assertThat(ok(core, "world_slice", SHORT, "region", "4,-60,4 8,-58,8").text())
                .contains("stone_bricks");
        assertThat(ok(core, "world_slice", SHORT, "region", "6,-57,6 6,-57,6").text()).contains("glowstone");
        ok(core, "world_summary", SHORT, "region", "0,-64,0 20,-50,20");

        Result render = ok(core, "world_render", SHORT, "region", "0,-62,0 20,-55,20", "size", 128);
        assertThat(render.image()).as("world_render returns a PNG").isTrue();

        // A real client joins, moves and looks around; it stays until leave.
        Result played = ok(core, "player_test", Duration.ofMinutes(2), "actions",
                new String[] {"tp 0.5,-60,0.5", "wait 10", "goto 2,-60,2", "say hello from e2e", "entities"},
                "name", "MscE2e", "leave", false);
        assertThat(played.text()).contains("joined as MscE2e");

        assertThat(ok(core, "world_entities", SHORT, "region", "-16,-64,-16 16,-40,16").text()).contains("MscE2e");
        ok(core, "player_test", SHORT, "actions", new String[] {"wait 5"}, "name", "MscE2e", "leave", true);

        // Nothing changed: the same jar, so the server keeps running.
        assertThat(ok(core, "dev_cycle", BUILD, "project", "game").text()).contains("jar unchanged")
                .doesNotContain("server ready");

        // The edit-test loop a second time: an edit, a rebuild, a restart, and what was built survives the restart.
        Path main = workspace.resolve("game/src/main/java/com/example/e2egame/E2eGameServer.java");
        Files.writeString(main, Files.readString(main).replace("game ready: ", "game ready (edited): "));
        assertThat(ok(core, "dev_cycle", BUILD, "project", "game").text()).contains("server ready");
        assertThat(ok(core, "world_slice", SHORT, "region", "6,-57,6 6,-57,6").text()).contains("glowstone");

        // The server's own output, as the AI reads it; the template's ready line is in it.
        assertThat(ok(core, "server_logs", SHORT, "mode", "tail", "lines", 20, "all", true).text()).contains("game ready");
        assertThat(ok(core, "server_stop", SHORT).text()).contains("stopped");
    }

    private Result ok(McpStdioClient core, String tool, Duration timeout, Object... args) throws Exception {
        Result r = core.call(tool, timeout, args);
        transcript.add("> " + tool + (r.error() ? " (error)" : "") + "\n" + r.text());
        assertThat(r.error()).as(tool + " failed: " + r.text()).isFalse();
        return r;
    }

    private static String tail(Path file, int lines) {
        try {
            if (!Files.exists(file)) {
                return "(no " + file.getFileName() + ")";
            }
            List<String> all = Files.readAllLines(file, StandardCharsets.UTF_8);
            return String.join("\n", all.subList(Math.max(0, all.size() - lines), all.size()));
        } catch (IOException e) {
            return "(cannot read " + file + ": " + e.getMessage() + ")";
        }
    }
}
