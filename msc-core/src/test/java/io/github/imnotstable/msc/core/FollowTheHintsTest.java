package io.github.imnotstable.msc.core;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.imnotstable.msc.core.fixture.FakeBuildMain;
import io.github.imnotstable.msc.core.fixture.FakeServerMain;
import io.github.imnotstable.msc.core.server.Platform;
import io.github.imnotstable.msc.core.tool.ToolRegistry;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;
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

    /** An argument of a step: a plain value, or a placeholder such as {@code <new folder>}. */
    private static final String ARG = "(\\w+)=(<[^>]*>|[^\\s,;)<]+)";

    /** A tool named in a next step, with its arguments. */
    private static final Pattern STEP = Pattern.compile(
            "\\b(server_setup|project_scaffold|dev_cycle)\\b((?: \\w+=(?:<[^>]*>|[^\\s,;)<]+))*)");

    /** A new version for each build, so every dev_cycle deploys a jar of its own. */
    private static final AtomicInteger VERSION = new AtomicInteger();

    /** The folder the follower picks for a {@code <new folder>} placeholder. */
    private static final String NEW_FOLDER = "fresh";

    /** What the last "next:" of a result says to do, and what it says on the owner's yes. */
    private static String nextSteps(String result) {
        int at = result.toLowerCase(Locale.ROOT).lastIndexOf("next:");
        assertThat(at).as("a next step in: %s", result).isNotNegative();
        return result.substring(at + "next:".length());
    }

    /** The calls a result's next step offers as alternatives ("dev_cycle project=a or dev_cycle project=b"). */
    private static List<String> alternatives(String result) {
        String steps = nextSteps(result);
        // A note after the calls ("; proxy is a Velocity project ...") names no alternative.
        Matcher first = STEP.matcher(steps);
        int note = first.find() ? steps.indexOf(';', first.end()) : -1;
        List<String> found = new ArrayList<>();
        for (String part : (note < 0 ? steps : steps.substring(0, note)).split(" or ")) {
            if (STEP.matcher(part).find()) {
                found.add(part);
            }
        }
        assertThat(found).as("a tool to call in: %s", result).isNotEmpty();
        return found;
    }

    private static ToolRegistry.Result follow(CoreHarness h, String result, List<String> ran, Object... scaffold)
            throws IOException {
        return follow(h, result, 0, ran, scaffold);
    }

    /**
     * Calls the first tool the result's next step names ({@code choice}: the alternative at this index first), again
     * and again, until dev_cycle ran: every call but dev_cycle must succeed, and none may repeat. The owner says no to
     * the EULA, so an acceptEula=true is left out. "edit the code" is a version.txt the fake build reads. Returns
     * dev_cycle's result; {@code ran} gets each call as made.
     */
    private static ToolRegistry.Result follow(CoreHarness h, String result, int choice, List<String> ran,
                                              Object... scaffold) throws IOException {
        String current = result;
        for (int i = 0; i < 5; i++) {
            Matcher m = STEP.matcher(i == 0 ? alternatives(current).get(choice) : nextSteps(current));
            assertThat(m.find()).as("a tool to call in: %s", current).isTrue();
            String tool = m.group(1);
            List<Object> args = new ArrayList<>();
            Matcher arg = Pattern.compile(ARG).matcher(m.group(2));
            while (arg.find()) {
                if (!arg.group(1).equals("acceptEula")) {
                    args.add(arg.group(1));
                    args.add(arg.group(2).startsWith("<") ? NEW_FOLDER : arg.group(2).replaceAll("\\.$", ""));
                }
            }
            if (tool.equals("project_scaffold")) {
                args.addAll(List.of(scaffold));
            }
            ran.add((tool + " " + String.join(" ", args.stream().map(String::valueOf).toList())).strip());
            assertThat(ran).as("a loop").doesNotHaveDuplicates();
            if (tool.equals("dev_cycle")) {
                Path project = h.core.config().workspace().resolve(args.isEmpty() ? "." : (String) args.get(1));
                Files.writeString(project.resolve("version.txt"), "1." + VERSION.incrementAndGet());
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

    /** #679: a project in a nested folder is built by the steps the tools name, each time they name one. */
    @ParameterizedTest
    @ValueSource(strings = {"minestom", "velocity", "paper"})
    void scaffoldIntoANestedFolderThenDevCycle(String platform) throws Exception {
        try (CoreHarness h = harness(platform)) {
            Path maze = h.core.config().workspace().resolve("games/maze");
            String scaffolded = h.ok("project_scaffold", "name", "Maze", "template", template(platform), "dir",
                    "games/maze");
            List<String> ran = new ArrayList<>();
            ToolRegistry.Result cycle = follow(h, scaffolded, ran);
            assertThat(ran.getLast()).isEqualTo("dev_cycle project games/maze");
            reachesTheBuild(platform, cycle, maze);

            h.call("server_stop");
            String setup = h.ok("server_setup", "platform", platform);
            assertThat(setup).endsWith(" dev_cycle project=games/maze");
            List<String> again = new ArrayList<>();
            reachesTheBuild(platform, follow(h, setup, again), maze);
            assertThat(again).containsExactly("dev_cycle project games/maze");
            h.call("server_stop");
        }
    }

    /** The template of another agent family than each workspace kind's, so that kind's server never runs it. */
    private static String otherFamily(String platform) {
        return switch (platform) {
            case "minestom" -> "velocity-plugin";
            case "velocity" -> "paper-plugin";
            default -> "minestom-game";
        };
    }

    /**
     * #680: a project the workspace's server never runs is never the next step. The step says so, names the switch,
     * and leads to a new project beside it that the server does run.
     */
    @ParameterizedTest
    @ValueSource(strings = {"minestom", "velocity", "paper"})
    void aSubfolderProjectOfAnotherFamilyIsNeverTheNextStep(String platform) throws Exception {
        try (CoreHarness h = harness(platform)) {
            Path ws = h.core.config().workspace();
            h.ok("server_setup", "platform", platform);
            String other = otherFamily(platform);
            h.ok("project_scaffold", "name", "Other", "template", other, "dir", "games/other");
            Platform otherPlatform = Platform.of(other.substring(0, other.indexOf('-')));
            String setup = h.ok("server_setup", "platform", platform);
            assertThat(nextSteps(setup)).doesNotContain("project=games/other")
                    .endsWith(" project_scaffold dir=<new folder> then dev_cycle project=<new folder>; games/other is a "
                            + otherPlatform.displayName() + " project, which " + Platform.of(platform).displayName()
                            + " never runs: server_setup platform=" + otherPlatform.id()
                            + " switches the workspace to it");
            List<String> ran = new ArrayList<>();
            ToolRegistry.Result cycle = follow(h, setup, ran, "name", "Arena", "template", template(platform));
            assertThat(ran).containsExactly("project_scaffold dir " + NEW_FOLDER + " name Arena template "
                    + template(platform), "dev_cycle project " + NEW_FOLDER);
            reachesTheBuild(platform, cycle, ws.resolve(NEW_FOLDER));

            // Beside a project the server runs, only that one is named.
            h.call("server_stop");
            assertThat(h.ok("server_setup", "platform", platform)).endsWith(" dev_cycle project=" + NEW_FOLDER);
        }
    }

    /** #680: a workspace project of another family (the workspace switched away from it) is not the next step either. */
    @Test
    void aWorkspaceProjectOfAnotherFamilyIsNeverTheNextStep() throws Exception {
        try (CoreHarness h = harness("minestom")) {
            h.ok("project_scaffold", "name", "Proxy", "template", "velocity-plugin");
            String setup = h.ok("server_setup", "platform", "minestom");
            assertThat(setup).endsWith("next: project_scaffold dir=<new folder> then dev_cycle project=<new folder>; "
                    + "the workspace's project is a Velocity project, which Minestom never runs: server_setup "
                    + "platform=velocity switches the workspace to it");
            List<String> ran = new ArrayList<>();
            ToolRegistry.Result cycle = follow(h, setup, ran, "name", "Arena", "template", "minestom-game");
            assertThat(ran).containsExactly("project_scaffold dir " + NEW_FOLDER + " name Arena template minestom-game",
                    "dev_cycle project " + NEW_FOLDER);
            reachesTheBuild("minestom", cycle, h.core.config().workspace().resolve(NEW_FOLDER));
            h.call("server_stop");
        }
    }

    /** Every project a next step offers, at any depth and only those the server runs, builds when followed. */
    @Test
    void everyAlternativeOfTheNextStepBuilds() throws Exception {
        try (CoreHarness h = harness("minestom")) {
            h.ok("project_scaffold", "name", "Lobby", "template", "minestom-game", "dir", "lobby");
            h.ok("project_scaffold", "name", "Maze", "template", "minestom-game", "dir", "games/maze");
            h.ok("project_scaffold", "name", "Proxy", "template", "velocity-plugin", "dir", "proxy");
            String setup = h.ok("server_setup", "platform", "minestom");
            assertThat(setup).endsWith("next: dev_cycle project=lobby or dev_cycle project=games/maze");
            List<String> projects = List.of("lobby", "games/maze");
            for (int choice = 0; choice < projects.size(); choice++) {
                List<String> ran = new ArrayList<>();
                ToolRegistry.Result cycle = follow(h, setup, choice, ran);
                assertThat(ran).containsExactly("dev_cycle project " + projects.get(choice));
                reachesTheBuild("minestom", cycle, h.core.config().workspace().resolve(projects.get(choice)));
            }
            h.call("server_stop");
        }
    }

    /**
     * #679, #680: with no fake build or server, the refusals of dev_cycle (no wrapper) and of a Minestom server_start
     * name a nested project, and never one the server does not run.
     */
    @Test
    void refusalsNameANestedProjectTheServerRuns() throws Exception {
        try (CoreHarness h = new CoreHarness(Files.createDirectories(dir.resolve("nested")))) {
            h.ok("project_scaffold", "name", "Maze", "template", "minestom-server", "dir", "games/maze");
            h.ok("project_scaffold", "name", "Proxy", "template", "velocity-plugin", "dir", "proxy");
            String named = "A subfolder holds one: dev_cycle project=games/maze.";
            assertThat(h.error("dev_cycle", "restart", false)).endsWith(named);
            assertThat(h.error("dev_cycle", "project", "games", "restart", false)).endsWith(named);
            assertThat(h.ok("server_setup", "platform", "minestom")).endsWith("next: dev_cycle project=games/maze");
            assertThat(h.error("server_start"))
                    .endsWith("Run dev_cycle project=games/maze to build the project and put its jar there.");
        }
        try (CoreHarness h = new CoreHarness(Files.createDirectories(dir.resolve("other")))) {
            h.ok("server_setup", "platform", "minestom");
            h.ok("project_scaffold", "name", "Proxy", "template", "velocity-plugin", "dir", "proxy");
            String none = "project_scaffold dir=<new folder> then dev_cycle project=<new folder>";
            String note = "; proxy is a Velocity project, which Minestom never runs: server_setup platform=velocity "
                    + "switches the workspace to it";
            assertThat(h.error("dev_cycle", "restart", false)).endsWith("Run " + none + note + ".");
            assertThat(h.error("server_start"))
                    .endsWith("Run " + none + " to build the project and put its jar there" + note + ".");
            assertThat(h.ok("server_setup", "platform", "minestom")).endsWith("next: " + none + note);
        }
    }

    /** #679: project_scaffold puts a project only where the next steps look for one. */
    @Test
    void aProjectGoesOnlyWhereTheNextStepsFindIt() throws Exception {
        Path ws = Files.createDirectories(dir.resolve("deep"));
        try (CoreHarness h = new CoreHarness(ws)) {
            assertThat(h.error("project_scaffold", "name", "Deep", "template", "minestom-server", "dir", "a/b/c/d/e"))
                    .isEqualTo("error: dir a/b/c/d/e is 5 folders deep, and MSC finds projects at most 4 deep: give a "
                            + "shorter dir");
            for (String unsearched : List.of("build/x", "node_modules/x", ".git/x", "run/x", ".msc/x", "a/.gradle")) {
                String in = unsearched.substring(0, unsearched.lastIndexOf('/'));
                if (unsearched.equals("a/.gradle")) {
                    in = unsearched;
                }
                assertThat(h.error("project_scaffold", "name", "Hidden", "template", "minestom-server", "dir",
                        unsearched)).isEqualTo("error: dir " + unsearched + " is in " + in + ", where MSC never looks "
                        + "for projects (its own files, build output or another tool's): give a dir outside it");
            }
            assertThat(ws.resolve("a")).doesNotExist();
            assertThat(ws.resolve("build")).doesNotExist();
            h.ok("project_scaffold", "name", "Deep", "template", "minestom-server", "dir", "a/b/c/d");
            assertThat(h.error("dev_cycle", "restart", false)).endsWith("A subfolder holds one: dev_cycle project=a/b/c/d.");
        }
    }

    /**
     * #664: what MSC creates, wherever the configuration puts it (a server folder in a subfolder, a tool log in the
     * root), leaves the workspace empty for a scaffold; anything else is refused with the step that works.
     */
    @Test
    void onlyWhatMscCreatedCountsAsEmpty() throws Exception {
        Path ws = Files.createDirectories(dir.resolve("custom"));
        try (CoreHarness h = new CoreHarness(ws, c -> CoreConfig.fromEnvironment(java.util.Map.of(
                CoreConfig.ENV_WORKSPACE, ws.toString(), CoreConfig.ENV_SERVER_DIR, ws.resolve("servers/dev").toString(),
                CoreConfig.ENV_TOOL_LOG, ws.resolve("calls.jsonl").toString()))
                .withMachineEula(c.machineEula()).withDownloadStore(c.downloadStore()).withProxySecret(null)
                .withGradleUserHome(c.gradleUserHome()))) {
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
