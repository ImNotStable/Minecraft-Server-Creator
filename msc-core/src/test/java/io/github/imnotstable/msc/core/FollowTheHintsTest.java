package io.github.imnotstable.msc.core;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.imnotstable.msc.core.fixture.FakeBuildMain;
import io.github.imnotstable.msc.core.fixture.FakeServerMain;
import io.github.imnotstable.msc.core.tool.ToolRegistry;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The tools' own next steps, followed in order from a fresh workspace, reach a build (#664, #665): scaffold then
 * setup, setup then a scaffold into the workspace root, and a scaffold into a subfolder. Minestom and Velocity end
 * with a deploy; Paper, whose owner has not agreed to the EULA, ends with dev_cycle's EULA refusal and nothing else.
 */
class FollowTheHintsTest {
    private static FakeDownloads downloads;

    @TempDir
    Path dir;

    @BeforeAll
    static void serve() throws IOException {
        downloads = FakeDownloads.start();
    }

    @AfterAll
    static void stop() {
        downloads.close();
    }

    private static List<String> java(Class<?> main) {
        return List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", System.getProperty("java.class.path"), main.getName());
    }

    /** The template each platform's flow scaffolds. */
    private static String template(String platform) {
        return switch (platform) {
            case "minestom" -> "minestom-game";
            case "velocity" -> "velocity-plugin";
            default -> "paper-plugin";
        };
    }

    private CoreHarness harness(String platform) throws Exception {
        Path ws = Files.createDirectories(dir.resolve(platform));
        // Paper keeps its real start check, so the EULA refusal is the one a user meets.
        return new CoreHarness(ws, c -> {
            CoreConfig fake = downloads.apply(c).withBuildCommand(java(FakeBuildMain.class));
            return platform.equals("paper") ? fake : fake.withServerCommand(java(FakeServerMain.class));
        });
    }

    /** A tool named in a next step, with its arguments. */
    private static final Pattern STEP = Pattern.compile(
            "\\b(server_setup|project_scaffold|dev_cycle)\\b((?: \\w+=[^\\s,;)]+)*)");

    /** What the last "next:" of a result says to do, and what it says on the owner's yes. */
    private static String nextSteps(String result) {
        int at = result.toLowerCase(Locale.ROOT).lastIndexOf("next:");
        assertThat(at).as("a next step in: %s", result).isNotNegative();
        return result.substring(at + "next:".length());
    }

    /**
     * Calls the first tool the result's next step names, again and again, until dev_cycle ran. The owner says no to
     * the EULA, so an acceptEula=true is left out. "edit the code" is a version.txt the fake build reads. Returns
     * dev_cycle's result; {@code ran} gets each call as made.
     */
    private static ToolRegistry.Result follow(CoreHarness h, String result, List<String> ran, Object... scaffold)
            throws IOException {
        String current = result;
        for (int i = 0; i < 5; i++) {
            Matcher m = STEP.matcher(nextSteps(current));
            assertThat(m.find()).as("a tool to call in: %s", current).isTrue();
            String tool = m.group(1);
            List<Object> args = new ArrayList<>();
            for (String arg : m.group(2).strip().split(" ")) {
                if (!arg.isEmpty() && !arg.startsWith("acceptEula=")) {
                    String[] kv = arg.split("=", 2);
                    args.add(kv[0]);
                    args.add(kv[1].replaceAll("\\.$", ""));
                }
            }
            if (tool.equals("project_scaffold")) {
                args.addAll(List.of(scaffold));
            }
            ran.add((tool + " " + String.join(" ", args.stream().map(String::valueOf).toList())).strip());
            if (tool.equals("dev_cycle")) {
                Path project = h.core.config().workspace().resolve(args.isEmpty() ? "." : (String) args.get(1));
                Files.writeString(project.resolve("version.txt"), "1.0");
                return h.call(tool, args.toArray());
            }
            current = h.ok(tool, args.toArray());
        }
        throw new AssertionError("no dev_cycle after " + ran);
    }

    /** Minestom and Velocity deploy the project; Paper stops at the EULA, before any build. */
    private static void reachesTheBuild(String platform, ToolRegistry.Result cycle, Path project) {
        assertThat(cycle.text()).doesNotContain("project_scaffold").doesNotContain("Gradle wrapper");
        if (platform.equals("paper")) {
            assertThat(cycle.ok()).isFalse();
            assertThat(cycle.text()).contains("EULA");
            assertThat(project.resolve("tasks.log")).doesNotExist();
        } else {
            assertThat(cycle.ok()).as(cycle.text()).isTrue();
            assertThat(cycle.text()).contains("build ok in ").contains(", deployed ");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"minestom", "velocity", "paper"})
    void scaffoldThenSetupThenDevCycle(String platform) throws Exception {
        try (CoreHarness h = harness(platform)) {
            List<String> ran = new ArrayList<>();
            String scaffolded = h.ok("project_scaffold", "name", "Arena", "template", template(platform));
            ToolRegistry.Result cycle = follow(h, scaffolded, ran);
            // Minestom's server is the project's own jar: nothing to set up first.
            assertThat(ran).containsExactlyElementsOf(switch (platform) {
                case "minestom" -> List.of("dev_cycle");
                case "velocity" -> List.of("server_setup platform velocity", "dev_cycle");
                default -> List.of("server_setup", "dev_cycle");
            });
            reachesTheBuild(platform, cycle, h.core.config().workspace());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"minestom", "velocity", "paper"})
    void setupThenScaffoldIntoTheWorkspaceThenDevCycle(String platform) throws Exception {
        try (CoreHarness h = harness(platform)) {
            List<String> ran = new ArrayList<>();
            String setup = h.ok("server_setup", "platform", platform);
            assertThat(setup).endsWith("project_scaffold then dev_cycle");
            Path ws = h.core.config().workspace();
            Path properties = ws.resolve("run/server.properties");
            String own = Files.exists(properties) ? Files.readString(properties) : null;
            ToolRegistry.Result cycle = follow(h, setup, ran, "name", "Arena", "template", template(platform));
            assertThat(ran).containsExactly("project_scaffold name Arena template " + template(platform), "dev_cycle");
            if (own != null) {
                // The template's run/ defaults go beside the server's own files, never over them.
                assertThat(properties).hasContent(own);
            }
            assertThat(ws.resolve("gradlew")).isRegularFile();
            assertThat(ws.resolve("run")).isDirectory();
            reachesTheBuild(platform, cycle, ws);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"minestom", "velocity", "paper"})
    void scaffoldIntoASubfolderThenDevCycle(String platform) throws Exception {
        try (CoreHarness h = harness(platform)) {
            List<String> ran = new ArrayList<>();
            String scaffolded = h.ok("project_scaffold", "name", "Lobby", "template", template(platform), "dir",
                    "lobby");
            ToolRegistry.Result cycle = follow(h, scaffolded, ran);
            assertThat(ran.getLast()).isEqualTo("dev_cycle project lobby");
            reachesTheBuild(platform, cycle, h.core.config().workspace().resolve("lobby"));
        }
    }

    /** #665: the refusal of a workspace with no project of its own names the subfolder that has one. */
    @Test
    void aPlainDevCycleNamesTheSubfolderProject() throws Exception {
        try (CoreHarness h = new CoreHarness(Files.createDirectories(dir.resolve("ws")))) {
            h.ok("project_scaffold", "name", "Lobby", "template", "minestom-server", "dir", "lobby");
            assertThat(h.error("dev_cycle", "restart", false))
                    .endsWith("A subfolder holds one: dev_cycle project=lobby.");
        }
    }

    /**
     * #664: what MSC creates, wherever the configuration puts it (a server folder in a subfolder, a tool log in the
     * root), leaves the workspace empty for a scaffold; anything else is refused with the step that works.
     */
    @Test
    void onlyWhatMscCreatedCountsAsEmpty() throws Exception {
        Path ws = Files.createDirectories(dir.resolve("custom"));
        try (CoreHarness h = new CoreHarness(ws, c -> new CoreConfig(c.workspace(), ws.resolve("servers/dev"),
                c.agentPort(), c.token(), ws.resolve("calls.jsonl"), c.javaCommand(), null, null, c.downloadsUrl(),
                c.minecraft(), c.apiJars(), c.packHost(), c.packFormat(), null, c.fabricMetaUrl(), c.fabricMavenUrl(),
                null, 0, c.machineEula(), c.loaderUrls(), c.downloadStore(), null))) {
            h.ok("server_setup", "platform", "minestom");
            h.ok("msc_status");
            Files.createDirectories(ws.resolve(".msc/backups/b1"));
            Files.writeString(ws.resolve("notes.txt"), "mine");
            assertThat(h.error("project_scaffold", "name", "Arena", "template", "minestom-game"))
                    .isEqualTo("error: " + ws + " already holds files that are not MSC's (notes.txt): give "
                            + "dir=<new folder> to create the project beside them");
            Files.delete(ws.resolve("notes.txt"));
            assertThat(h.ok("project_scaffold", "name", "Arena", "template", "minestom-game"))
                    .endsWith("\nnext: edit the code, then dev_cycle");
            assertThat(ws.resolve("servers/dev/.msc-loader")).isRegularFile();
            assertThat(ws.resolve("calls.jsonl")).isRegularFile();
        }
    }
}
