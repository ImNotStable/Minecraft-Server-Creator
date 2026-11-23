package io.github.imnotstable.msc.core;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.imnotstable.msc.core.fixture.FakeServerMain;
import io.github.imnotstable.msc.core.server.MachineEula;
import io.github.imnotstable.msc.core.tool.Tool;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * #619: a tool that picks a way (a mode, a flag, the platform) refuses an argument that way does not read, in one
 * line and before it does anything, instead of dropping it with an OK. Driven through the registry, as a caller sends
 * the calls.
 */
class WayArgsBehaviourTest {
    @TempDir
    Path dir;

    private static List<String> java(Class<?> main) {
        return List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", System.getProperty("java.class.path"), main.getName());
    }

    /** An argument that only some modes read: two values that give a reader different results, and the refusal. */
    private record ModeArg(Set<String> readers, Object one, Object other, String refusal) {
    }

    /** server_logs' arguments that only some modes read. Every other argument is read by every mode. */
    private static final Map<String, ModeArg> LOG_ARGS = Map.of(
            "pattern", new ModeArg(Set.of("grep"), "part1:", "part2:",
                    "error: pattern is for mode=grep; add mode=grep or leave it out"),
            "lines", new ModeArg(Set.of("tail", "grep"), 1, 2,
                    "error: lines is for mode=tail and mode=grep; leave it out"));

    /** The arguments every mode reads, with a value to send. */
    private static final Map<String, Object> EVERY_MODE = Map.of("all", true, "maxChars", 6000);

    /** What a mode needs to run: grep a pattern. */
    private static List<Object> base(String mode) {
        return mode.equals("grep") ? new ArrayList<>(List.of("mode", mode, "pattern", "ERROR"))
                : new ArrayList<>(List.of("mode", mode));
    }

    private static List<Object> with(List<Object> base, String arg, Object value) {
        List<Object> all = new ArrayList<>(base);
        int at = all.indexOf(arg);
        if (at >= 0 && at % 2 == 0) {
            all.set(at + 1, value);
        } else {
            all.addAll(List.of(arg, value));
        }
        return all;
    }

    @Test
    void serverLogsRefusesWhatItsModeDoesNotRead() throws Exception {
        Path ws = Files.createDirectories(dir.resolve("logs"));
        Files.writeString(Files.createDirectories(ws.resolve("run")).resolve("many-errors.flag"), "");
        try (CoreHarness h = new CoreHarness(ws, c -> c.withServerCommand(java(FakeServerMain.class)))) {
            Tool tool = h.core.registry().tools().stream().filter(t -> t.name().equals("server_logs")).findFirst()
                    .orElseThrow();
            Set<String> args = new TreeSet<>(tool.inputSchema().getAsJsonObject("properties").keySet());
            Set<String> declared = new TreeSet<>(LOG_ARGS.keySet());
            declared.addAll(EVERY_MODE.keySet());
            declared.add("mode");
            assertThat(args).as("every server_logs argument is declared here").isEqualTo(declared);
            String unknown = h.error("server_logs", "mode", "nope");
            assertThat(unknown).startsWith("error: mode must be ");
            List<String> modes = List.of(unknown.substring("error: mode must be ".length()).split(", | or "));
            assertThat(modes).containsExactly("errors", "tail", "grep", "triage");

            // Refused before anything: no server has run, nothing waits for a log.
            for (String mode : modes) {
                for (Map.Entry<String, ModeArg> e : LOG_ARGS.entrySet()) {
                    if (!e.getValue().readers().contains(mode)) {
                        assertThat(h.error("server_logs", with(base(mode), e.getKey(), e.getValue().one()).toArray()))
                                .as("%s %s", mode, e.getKey()).isEqualTo(e.getValue().refusal());
                    }
                }
            }
            h.ok("server_start", "timeoutSeconds", 60);
            for (String mode : modes) {
                List<Object> base = base(mode);
                for (Map.Entry<String, ModeArg> e : LOG_ARGS.entrySet()) {
                    ModeArg a = e.getValue();
                    List<Object> one = with(base, e.getKey(), a.one());
                    if (a.readers().contains(mode)) {
                        assertThat(h.ok("server_logs", one.toArray())).as("%s reads %s", mode, e.getKey())
                                .isNotEqualTo(h.ok("server_logs", with(base, e.getKey(), a.other()).toArray()));
                    } else {
                        assertThat(h.error("server_logs", one.toArray())).as("%s %s", mode, e.getKey())
                                .isEqualTo(a.refusal());
                    }
                }
                for (Map.Entry<String, Object> e : EVERY_MODE.entrySet()) {
                    h.ok("server_logs", with(base, e.getKey(), e.getValue()).toArray());
                }
            }
            // The issue's calls.
            assertThat(h.error("server_logs", "pattern", "zzzz_no_such", "maxChars", 400))
                    .isEqualTo(LOG_ARGS.get("pattern").refusal());
            assertThat(h.error("server_logs", "mode", "triage", "lines", 2)).isEqualTo(LOG_ARGS.get("lines").refusal());
            h.ok("server_stop");
        }
    }

    @Test
    void buildBatchRefusesPhysicsInADryRun() throws Exception {
        try (CoreHarness h = new CoreHarness(Files.createDirectories(dir.resolve("build"))).withAgent()) {
            String steps = "fill 0,70,0 1,71,1 minecraft:stone";
            int before = h.agent.blocks.size();
            String refusal = "error: physics is for a build that places blocks; leave it out with dryRun=true";
            assertThat(h.error("build_batch", "steps", steps, "dryRun", true, "physics", true)).isEqualTo(refusal);
            assertThat(h.error("build_batch", "steps", steps, "dryRun", true, "physics", false)).isEqualTo(refusal);
            assertThat(h.agent.blocks).hasSize(before);
            assertThat(h.ok("build_batch", "steps", steps, "dryRun", true)).startsWith("dry run, nothing placed");
            assertThat(h.ok("build_batch", "steps", steps, "physics", true)).startsWith("edit ");
        }
    }

    @Test
    void mscWorkspaceRefusesCreateWithoutAPath() throws Exception {
        Path ws = Files.createDirectories(dir.resolve("ws"));
        try (CoreHarness h = new CoreHarness(ws)) {
            assertThat(h.error("msc_workspace", "create", true))
                    .isEqualTo("error: create is for a path to switch to; leave it out");
            assertThat(h.ok("msc_workspace", "path", dir.resolve("other").toString(), "create", true))
                    .contains("other");
        }
    }

    @Test
    void serverSetupRefusesWhatItsPlatformDoesNotRead() throws Exception {
        Map<String, String> refusals = Map.of(
                "version", "error: version is for the Minecraft servers MSC installs; minestom and a proxy without "
                        + "servers= do not take one, leave it out",
                "mode", "error: mode is for a Minecraft server's world; minestom sets it in its own code and "
                        + "a proxy has no world, leave it out",
                "acceptEula", "error: acceptEula is for a server with a Minecraft EULA; minestom and a proxy without "
                        + "servers= have none, leave it out");
        Map<String, Object> values = Map.of("version", "26.3", "mode", "game", "acceptEula", false);
        Path ws = Files.createDirectories(dir.resolve("setup"));
        try (CoreHarness h = new CoreHarness(ws)) {
            for (String platform : List.of("minestom", "velocity")) {
                for (Map.Entry<String, String> e : refusals.entrySet()) {
                    assertThat(h.error("server_setup", "platform", platform, e.getKey(), values.get(e.getKey())))
                            .as("%s %s", platform, e.getKey()).isEqualTo(e.getValue());
                }
            }
            assertThat(ws.resolve("run")).as("a refusal changes nothing").doesNotExist();
            // The workspace's platform is the way when none is given.
            h.ok("server_setup", "platform", "minestom");
            assertThat(h.error("server_setup", "mode", "build")).isEqualTo(refusals.get("mode"));
        }
    }

    @Test
    void everyToolThatTakesAcceptEulaRefusesItWhereThePlatformHasNone() throws Exception {
        // #625: server_start dropped it with an OK (a fake server started); project_scaffold goes by the template.
        String refusal = "error: acceptEula is for a server with a Minecraft EULA; minestom and a proxy without "
                + "servers= have none, leave it out";
        Path home = dir.resolve("home");
        for (String template : List.of("minestom-server", "velocity-plugin")) {
            Path ws = Files.createDirectories(dir.resolve(template));
            try (CoreHarness h = new CoreHarness(ws, c -> c.withServerCommand(java(FakeServerMain.class))
                    .withMachineEula(MachineEula.forHome(home)))) {
                for (boolean value : new boolean[] {true, false}) {
                    assertThat(h.error("project_scaffold", "name", "Proj", "template", template, "acceptEula", value))
                            .as("%s %s", template, value).isEqualTo(refusal);
                }
                assertThat(ws.resolve("build.gradle.kts")).as("a refused scaffold writes nothing").doesNotExist();
                // The scaffold remembers the template's platform, which server_start then goes by.
                h.ok("project_scaffold", "name", "Proj", "template", template);
                for (boolean value : new boolean[] {true, false}) {
                    assertThat(h.error("server_start", "acceptEula", value)).as("%s %s", template, value)
                            .isEqualTo(refusal);
                }
                assertThat(h.core.server().running()).isFalse();
            }
            assertThat(ws.resolve("run/eula.txt")).doesNotExist();
        }
        assertThat(home.resolve(".msc/eula.json")).as("nothing kept for the machine").doesNotExist();
    }
}
