package io.github.imnotstable.msc.core;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.imnotstable.msc.core.fixture.FakeBuildMain;
import io.github.imnotstable.msc.core.fixture.LockHoldMain;
import io.github.imnotstable.msc.core.fixture.SleepMain;
import io.github.imnotstable.msc.core.server.MachineEula;
import io.github.imnotstable.msc.core.server.MachineEulaAccess;
import io.github.imnotstable.msc.core.server.PidFile;
import io.github.imnotstable.msc.core.server.PidFileAccess;
import io.github.imnotstable.msc.core.server.ServerLockAccess;
import io.github.imnotstable.msc.core.tool.ToolRegistry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The Steps rule "check first" (#445): a refusal that can be known before any side effect is decided before the first
 * one. So every refusal of every tool built on Steps is the same plain one-line answer whether or not an interrupted
 * earlier call left something to clean up (an aside jar in {@code mods/} or {@code plugins/}, a file in staging), and
 * the workspace and the owner's home are exactly as they were: the cleanup only runs once the call goes ahead.
 */
class RefusalsChangeNothingTest {
    @TempDir
    Path root;

    /** A workspace state that makes {@code call} refuse, and a fragment of the refusal. */
    private record Refusal(String name, Prepare prepare, boolean machineEula, Object[] call, String says) {
        @Override
        public String toString() {
            return name;
        }
    }

    @FunctionalInterface
    private interface Prepare {
        void into(Path ws) throws Exception;
    }

    private static Refusal refusal(String name, Prepare prepare, String says, Object... call) {
        return new Refusal(name, prepare, false, call, says);
    }

    private static final Prepare NOTHING = ws -> {
    };
    private static final Prepare PAPER_JAR = ws -> Files.writeString(
            Files.createDirectories(ws.resolve("run")).resolve("paper.jar"), "not a jar");

    /** A Paper plugin project the fake build turns into {@code <name>-1.0.jar}. */
    private static Prepare project(String name) {
        return ws -> {
            PAPER_JAR.into(ws);
            Path resources = Files.createDirectories(ws.resolve("demo/src/main/resources"));
            Files.writeString(resources.resolve("plugin.yml"), "name: Demo\n");
            Files.writeString(ws.resolve("demo/version.txt"), "1.0");
            Files.writeString(ws.resolve("demo/name.txt"), name);
        };
    }

    /**
     * Another session's core, still running, standing as its own server too (ADR 0037): a live server whose live
     * owner is not this JVM. One process for every row, so the refusals of both runs name the same pids.
     */
    private static Process otherSession;

    private static synchronized Process otherSession() throws Exception {
        if (otherSession == null) {
            otherSession = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-cp",
                    System.getProperty("java.class.path"), SleepMain.class.getName(), "3600").start();
        }
        return otherSession;
    }

    /** Other cores in the middle of a start: each holds a workspace's {@code .msc/server.lock}. */
    private static final List<Process> lockHolders = new ArrayList<>();

    @AfterAll
    static void endOtherSession() {
        if (otherSession != null) {
            otherSession.destroyForcibly();
        }
        lockHolders.forEach(Process::destroyForcibly);
    }

    /** {@code prepare}, then another process holding the lock every change to the server's pid file is made under. */
    private static Prepare whileAnotherCoreStarts(Prepare prepare) {
        return ws -> {
            prepare.into(ws);
            Process holder = LockHoldMain.hold(ws.resolve(".msc/server.lock"));
            synchronized (lockHolders) {
                lockHolders.add(holder);
            }
        };
    }

    /** {@code prepare}, then a pid file naming {@link #otherSession()}'s server and core. */
    private static Prepare underOtherSession(Prepare prepare) {
        return ws -> {
            prepare.into(ws);
            Process other = otherSession();
            PidFileAccess.write(Files.createDirectories(ws.resolve(".msc")).resolve("server.pid"), other.toHandle(),
                    PidFile.Identity.of(other.toHandle()));
        };
    }

    private static final String OTHER_SESSION = "belongs to another session that is still running";

    static Stream<Arguments> refusals() {
        return Stream.of(
                refusal("server_start under another live session", underOtherSession(PAPER_JAR), OTHER_SESSION,
                        "server_start", "acceptEula", true),
                refusal("server_stop under another live session", underOtherSession(NOTHING), OTHER_SESSION,
                        "server_stop"),
                refusal("server_setup under another live session", underOtherSession(NOTHING), OTHER_SESSION,
                        "server_setup", "platform", "paper"),
                refusal("dev_cycle under another live session", underOtherSession(project("arena")), OTHER_SESSION,
                        "dev_cycle", "project", "demo"),
                refusal("dev_cycle restart=false under another live session", underOtherSession(project("arena")),
                        OTHER_SESSION, "dev_cycle", "project", "demo", "restart", false),
                refusal("server_start while another core holds the server lock", whileAnotherCoreStarts(ws -> {
                    PAPER_JAR.into(ws);
                    Files.writeString(ws.resolve("run/eula.txt"), "eula=true\n");
                }), "is starting or stopping the server right now", "server_start"),
                refusal("server_start without a server", NOTHING, "no paper server in ", "server_start"),
                refusal("server_start acceptEula=true without a server", NOTHING, "no paper server in ",
                        "server_start", "acceptEula", true),
                refusal("server_start without the EULA", PAPER_JAR, "the Minecraft EULA is not accepted",
                        "server_start"),
                refusal("server_start with eula=false", ws -> {
                    PAPER_JAR.into(ws);
                    Files.writeString(ws.resolve("run/eula.txt"), "eula=false\n");
                }, "the Minecraft EULA is not accepted", "server_start"),
                new Refusal("server_start where the machine acceptance cannot be written", ws -> {
                    PAPER_JAR.into(ws);
                    Files.createDirectories(ws.resolve("run/eula.txt"));
                }, true, new Object[] {"server_start"}, "is a folder"),
                refusal("dev_cycle without the EULA", project("arena"), "the Minecraft EULA is not accepted",
                        "dev_cycle", "project", "demo"),
                refusal("dev_cycle of a jar named like the agent", ws -> {
                    project("msc-agent-paper").into(ws);
                    Files.writeString(ws.resolve("run/eula.txt"), "eula=true\n");
                }, "MSC's agent jar",
                        "dev_cycle", "project", "demo"),
                refusal("server_setup with an unknown mode", NOTHING, "mode must be build or game",
                        "server_setup", "mode", "hardcore"),
                refusal("server_setup with an unknown platform", NOTHING, "unknown platform spigot",
                        "server_setup", "platform", "spigot"),
                refusal("server_setup quilt for a version Fabric API lacks", NOTHING, "Fabric setup supports Minecraft",
                        "server_setup", "platform", "quilt", "version", "1.0"),
                refusal("server_detach without a server", NOTHING, "the server is not running", "server_detach"),
                refusal("project_scaffold with an unknown template", NOTHING, "unknown template",
                        "project_scaffold", "name", "Demo", "template", "nope"),
                refusal("texture with nothing to draw", NOTHING, "give rows and palette to draw",
                        "texture", "name", "item/ruby", "color", "#d4263a"),
                refusal("pack check of an empty pack", NOTHING, "the pack is empty", "pack", "check", true),
                refusal("model_create of an unknown kind", NOTHING, "unknown model kind",
                        "model_create", "name", "block/x", "kind", "slab", "textures", List.of("block/a")),
                refusal("structure_save without a server", NOTHING, "the server is not running",
                        "structure_save", "region", "0,64,0 65536,64,0", "file", "wall.schem"),
                refusal("world_render of an unknown view", NOTHING, "view",
                        "world_render", "region", "0,60,0 3,62,3", "view", "sideways"))
                .map(Arguments::of);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("refusals")
    void aRefusalIsOneLineAndChangesNothingWithOrWithoutLeftovers(Refusal refusal) throws Exception {
        String clean = refuse(refusal, "clean", false);
        String leftovers = refuse(refusal, "leftovers", true);
        assertThat(clean).contains(refusal.says()).doesNotContain("\n").doesNotContain("internal");
        assertThat(leftovers).isEqualTo(clean);
        if (refusal.says().equals(OTHER_SESSION)) {
            assertThat(otherSession().isAlive()).as("the other session's server").isTrue();
        }
    }

    /** The refusal in a fresh workspace, its own path replaced by {@code <ws>}, after checking it changed nothing. */
    private String refuse(Refusal refusal, String name, boolean leftovers) throws Exception {
        Path ws = Files.createDirectories(root.resolve(name));
        Path home = Files.createDirectories(root.resolve(name + "-home"));
        refusal.prepare().into(ws);
        // MSC's own folder, as any used workspace has it, so the build's init script is the only thing it can gain.
        Files.createDirectories(ws.resolve(".msc"));
        if (leftovers) {
            Files.writeString(Files.createDirectories(ws.resolve("run/mods")).resolve("lobby.jar.msc-removing"), "old");
            Files.writeString(Files.createDirectories(ws.resolve("run/plugins")).resolve("arena.jar.msc-removing"),
                    "old");
            Files.writeString(Files.createDirectories(ws.resolve("run/.msc/staging")).resolve("arena.jar"), "old");
        }
        if (refusal.machineEula()) {
            MachineEulaAccess.accepted(home);
        }
        UnaryOperator<CoreConfig> config = c -> c.withMachineEula(MachineEula.forHome(home))
                .withBuildCommand(List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                        "-cp", System.getProperty("java.class.path"), FakeBuildMain.class.getName()));
        try (CoreHarness h = new CoreHarness(ws, config)) {
            // A row whose lock another core holds refuses whatever the wait: not the whole production one (#497).
            ServerLockAccess.refuseQuickly(h.core.server());
            Map<String, String> before = state(ws, home);
            Object[] call = refusal.call();
            ToolRegistry.Result r = h.call((String) call[0], Arrays.copyOfRange(call, 1, call.length));
            assertThat(r.ok()).as("%s: %s", refusal, r.text()).isFalse();
            assertThat(state(ws, home)).as("%s left everything as it was", refusal).isEqualTo(before);
            return r.text().replace(ws.toAbsolutePath().toString(), "<ws>").replace(ws.toString(), "<ws>");
        }
    }

    /**
     * The workspace and the owner's home, but what building the project writes: its own output, and the init script
     * every build writes (a refusal that needs the built jar's name comes after the build).
     */
    private static Map<String, String> state(Path ws, Path home) throws Exception {
        Map<String, String> state = new TreeMap<>();
        TreeSnapshot.of(ws).forEach((path, content) -> {
            if (!path.startsWith("demo") && !path.equals(".msc/msc-init.gradle")) {
                state.put("ws/" + path, content);
            }
        });
        TreeSnapshot.of(home).forEach((path, content) -> state.put("home/" + path, content));
        return state;
    }
}
