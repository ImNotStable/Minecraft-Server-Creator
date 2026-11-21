package io.github.imnotstable.msc.core;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.imnotstable.msc.core.fixture.FakeServerMain;
import io.github.imnotstable.msc.core.server.Platform;
import io.github.imnotstable.msc.core.tool.Tool;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * ADR 0045 decision 5 over every tool (#591): on a Velocity workspace each world, build and player tool answers the proxy
 * line before it sends, saves, stops or restores anything, running or not, and every tool is either such a tool or one
 * that works on a proxy, so a new tool has to be put on one side.
 */
@Timeout(value = 2, unit = TimeUnit.MINUTES, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class ProxyWorldToolsTest {
    /** The tools that work on a proxy: none reads or writes a world. */
    private static final List<String> PROXY_TOOLS = List.of("msc_status", "project_scaffold", "server_setup",
            "plugins", "server_start", "server_stop", "server_detach", "dev_cycle", "server_logs", "server_command", "server_wait",
            "api_search", "api_class", "texture", "texture_view", "model_create", "pack", "msc_workspace");

    /** Each world tool with arguments that reach its handler, the backup ones once per kind of backup= call. */
    private static final Map<String, List<Object[]>> CALLS = new LinkedHashMap<>();

    static {
        String region = "0,0,0 1,1,1";
        CALLS.put("world_slice", List.<Object[]>of(new Object[] {"region", region}));
        CALLS.put("world_heightmap", List.<Object[]>of(new Object[] {"area", "0,0 1,1"}));
        CALLS.put("world_render", List.<Object[]>of(new Object[] {"region", region}));
        CALLS.put("world_summary", List.<Object[]>of(new Object[] {"region", region}));
        CALLS.put("world_entities", List.<Object[]>of(new Object[] {"region", region}));
        CALLS.put("build_undo", List.of(new Object[] {}, new Object[] {"backup", "list"},
                new Object[] {"backup", "save"}, new Object[] {"backup", "paper-days"}));
        CALLS.put("structure_save", List.<Object[]>of(new Object[] {"region", region, "file", "a.schem"}));
        CALLS.put("build_batch", List.<Object[]>of(new Object[] {"steps", "set 0,0,0 stone"}));
        CALLS.put("player_test", List.<Object[]>of(new Object[] {"actions", List.of("say hi")}));
    }

    @TempDir
    Path workspace;

    private static List<String> fakeServer() {
        return List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", System.getProperty("java.class.path"), FakeServerMain.class.getName());
    }

    @Test
    void everyToolIsAWorldToolOrWorksOnAProxy() throws Exception {
        try (CoreHarness h = new CoreHarness(workspace)) {
            List<String> all = h.core.registry().tools().stream().map(Tool::name).toList();
            List<String> sides = new ArrayList<>(Core.WORLD_TOOLS);
            sides.addAll(PROXY_TOOLS);
            assertThat(all).containsExactlyInAnyOrderElementsOf(sides);
            assertThat(CALLS.keySet()).containsExactlyInAnyOrderElementsOf(Core.WORLD_TOOLS);
        }
    }

    @Test
    void worldToolsOnAProxyAnswerOneLineAndTouchNothing() throws Exception {
        // A world and a backup left from the workspace's Paper days, then the folder became a proxy's.
        Path level = workspace.resolve("run/world/level.dat");
        Files.createDirectories(level.getParent());
        Files.writeString(level, "paper days");
        Path backup = Files.createDirectories(workspace.resolve(".msc/backups/20200101-000000-paper-days/world"));
        Files.writeString(backup.resolve("level.dat"), "older");
        Files.writeString(workspace.resolve("run/no-agent.flag"), "");
        Platform.remember(CoreConfig.forWorkspace(workspace), Platform.VELOCITY);
        String line = "error: " + Platform.VELOCITY.noWorldTools();
        try (CoreHarness h = new CoreHarness(workspace, c -> c.withServerCommand(fakeServer()))) {
            Map<String, String> before = TreeSnapshot.of(workspace);
            assertAllRefused(h, line);
            assertThat(TreeSnapshot.of(workspace)).isEqualTo(before);

            assertThat(h.ok("server_start", "timeoutSeconds", 30)).contains("a proxy: no world or agent");
            String log = h.ok("server_logs", "mode", "tail");
            assertAllRefused(h, line);
            // Nothing was typed into the proxy's console, and the proxy was neither stopped nor restarted.
            assertThat(h.ok("server_logs", "mode", "tail")).isEqualTo(log).doesNotContain("console:");
            assertThat(h.ok("msc_status")).contains("running");
            assertThat(level).hasContent("paper days");
            try (Stream<Path> backups = Files.list(workspace.resolve(".msc/backups"))) {
                assertThat(backups.map(p -> p.getFileName().toString())).containsExactly("20200101-000000-paper-days");
            }
        }
    }

    private static void assertAllRefused(CoreHarness h, String line) {
        CALLS.forEach((tool, calls) -> {
            for (Object[] args : calls) {
                assertThat(h.error(tool, args)).as(tool + " " + List.of(args)).isEqualTo(line);
            }
        });
    }
}
