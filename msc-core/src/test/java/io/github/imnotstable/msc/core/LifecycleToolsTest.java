package io.github.imnotstable.msc.core;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import io.github.imnotstable.msc.core.fixture.FakeBuildMain;
import io.github.imnotstable.msc.core.fixture.FakeServerMain;
import io.github.imnotstable.msc.core.server.FakeLeftover;
import io.github.imnotstable.msc.core.server.MachineEula;
import io.github.imnotstable.msc.core.server.MachineEulaAccess;
import io.github.imnotstable.msc.core.server.PidFile;
import io.github.imnotstable.msc.core.server.PidFileAccess;
import io.github.imnotstable.msc.core.server.Platform;
import io.github.imnotstable.msc.core.tools.LifecycleToolsAccess;
import io.github.imnotstable.msc.core.tools.StepsAccess;
import io.github.imnotstable.msc.process.rcon.FakeRconServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LifecycleToolsTest {
    @TempDir
    Path workspace;
    private CoreHarness h;
    private Path project;

    private static List<String> java(Class<?> main) {
        return List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", System.getProperty("java.class.path"), main.getName());
    }

    @BeforeEach
    void start() throws Exception {
        project = Files.createDirectories(workspace.resolve("demo"));
        Files.writeString(project.resolve("version.txt"), "1.0");
        h = new CoreHarness(workspace, c -> c.withServerCommand(java(FakeServerMain.class))
                .withBuildCommand(java(FakeBuildMain.class)).withToolLog(workspace.resolve("calls.jsonl")));
    }

    @AfterEach
    void stop() throws Exception {
        h.close();
    }

    @Test
    void startConnectsTheAgentAndStopEndsTheProcess() {
        assertThat(h.ok("server_start", "timeoutSeconds", 30))
                .startsWith("server ready in ").containsPattern("s, agent connected\nworld world: ground is y=-?\\d+ \\(most common top block around 0,0; build from y=-?\\d+\\)\n")
                .endsWith("\nno errors or warnings");
        assertThat(h.ok("msc_status")).startsWith("msc-core ").contains("server: running").contains("agent: paper fake, minecraft 26.3, 8 operations");
        assertThat(h.ok("world_summary", "region", "0,60,0 1,61,1")).contains("blocks: stone 8");
        assertThat(h.ok("server_command", "command", "/say hi")).isEqualTo("ran say hi");
        assertThat(h.ok("server_command", "command", "say a\n\n/say b\n")).isEqualTo("> say a\nran say a\n> /say b\nran say b");
        assertThat(h.error("server_command", "command", "say x\n".repeat(65))).contains("at most 64 commands");
        assertThat(h.error("server_command", "command", " \n\n")).contains("command is empty");
        // #473: the commands' outputs share the cap, so every command keeps its line and a share of its output.
        StringBuilder many = new StringBuilder();
        for (int i = 0; i < 20; i++) {
            many.append("say ").append(i).append(' ').append("w".repeat(140)).append('\n');
        }
        String shared = h.ok("server_command", "command", many.toString());
        assertThat(shared).hasSizeLessThanOrEqualTo(6000).doesNotContain("Narrow the call.").endsWith("Output cut.]");
        for (int i = 0; i < 20; i++) {
            assertThat(shared).contains("> say " + i + " w").contains("ran say " + i + " w");
        }
        assertThat(h.ok("server_logs", "mode", "tail", "lines", 1)).contains("Done (0.1s)!");
        assertThat(h.ok("server_logs", "mode", "grep", "pattern", "starting")).contains("Starting minecraft server");
        assertThat(h.ok("server_logs")).endsWith("lines, 0 errors, 0 warnings");
        assertThat(h.ok("server_command", "command", "broadcast Wave 3 begins"))
                .isEqualTo("console during the command:\n[Server] Wave 3 begins");
        assertThat(h.error("server_start")).contains("already running");
        assertThat(h.ok("server_stop")).isEqualTo("server stopped");
        assertThat(h.ok("server_stop")).isEqualTo("server was not running");
        assertThat(h.core.server().running()).isFalse();
    }

    @Test
    void theStartReportSaysTheGameModeFromServerProperties() throws Exception {
        Files.createDirectories(workspace.resolve("run"));
        Files.writeString(workspace.resolve("run/server.properties"), "gamemode=survival\ndifficulty=normal\n");
        assertThat(h.ok("server_start", "timeoutSeconds", 30))
                .contains("\nmode: survival, normal difficulty (server_setup mode=build for creative, peaceful)\n");
    }

    @Test
    void theStartReportGivesTheBedrockPortWhenGeyserIsInPlugins() throws Exception {
        // ADR 0046: after the mode line; nothing about Bedrock without Geyser.
        Path run = Files.createDirectories(workspace.resolve("run"));
        Files.writeString(run.resolve("server.properties"), "gamemode=creative\ndifficulty=peaceful\nserver-port=25570\n");
        assertThat(h.ok("server_start", "timeoutSeconds", 30)).doesNotContain("bedrock");
        h.ok("server_stop");
        Files.createDirectories(run.resolve("plugins/Geyser-Spigot"));
        Files.writeString(run.resolve("plugins/Geyser-Spigot.jar"), "geyser");
        Files.writeString(run.resolve("plugins/Geyser-Spigot/config.yml"), "bedrock:\n  address: 0.0.0.0\n  port: 19140\n");
        assertThat(h.ok("server_start", "timeoutSeconds", 30)).contains("difficulty (server_setup mode=game for "
                + "survival, normal: mobs attack and players take damage)\nbedrock: UDP 0.0.0.0:19140 (Geyser, fronting "
                + "Java port 25570; Floodgate sign-in, open to the network)\n");
    }

    @Test
    void startRemovesTheAsideCopiesOlderCoresLeftInTheScanFolders() throws Exception {
        // #434: cores before #430 set a jar aside as x.jar.msc-removing in mods/, which Quilt loads as a second copy.
        Path mods = Files.createDirectories(workspace.resolve("run/mods"));
        Path plugins = Files.createDirectories(workspace.resolve("run/plugins"));
        Files.writeString(mods.resolve("lobby.jar.msc-removing"), "old");
        Files.writeString(plugins.resolve("arena.jar.msc-removing"), "old");
        Files.writeString(mods.resolve("lobby.jar"), "kept");
        assertThat(h.ok("server_start", "timeoutSeconds", 30))
                .startsWith("removed 2 files an interrupted earlier call left\nserver ready in ");
        assertThat(mods.resolve("lobby.jar.msc-removing")).doesNotExist();
        assertThat(plugins.resolve("arena.jar.msc-removing")).doesNotExist();
        assertThat(mods.resolve("lobby.jar")).hasContent("kept");
        h.ok("server_stop");
        assertThat(h.ok("server_start", "timeoutSeconds", 30)).startsWith("server ready in ");
    }

    @Test
    void waitReturnsTheFirstMatchingLineAndNeverMatchesTheSameLineTwice() throws Exception {
        assertThat(h.error("server_wait", "until", "x")).contains("none is running");
        h.ok("server_start", "timeoutSeconds", 30);
        Thread later = Thread.ofVirtual().start(() -> {
            try {
                Thread.sleep(300);
                h.core.server().console("Wave 2 started");
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        assertThat(h.ok("server_wait", "until", "wave \\d started", "timeoutSeconds", 20))
                .startsWith("matched after ").endsWith(": [12:00:05 INFO]: console: Wave 2 started");
        later.join();
        assertThat(h.ok("server_wait", "until", "wave \\d started", "timeoutSeconds", 1))
                .startsWith("no line matched in 1s; no new lines");

        // A line printed between two calls is still seen.
        h.core.server().console("Boss spawned");
        Thread.sleep(500);
        assertThat(h.ok("server_wait", "until", "boss", "timeoutSeconds", 5)).endsWith("console: Boss spawned");

        assertThat(h.error("server_wait", "until", "(")).contains("bad regex");
        assertThat(h.error("server_wait", "until", "x", "timeoutSeconds", 601)).contains("1 to 600");
        Thread stopper = Thread.ofVirtual().start(() -> {
            try {
                Thread.sleep(300);
                h.core.server().stop(java.time.Duration.ofSeconds(10));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        assertThat(h.ok("server_wait", "until", "never", "timeoutSeconds", 30)).startsWith("the server stopped after ")
                .contains("Stopping server");
        stopper.join();
    }

    @Test
    void aServerLeftRunningByAnEarlierCoreIsStoppedOnlyWhenThePidFileMatches() throws Exception {
        Process leftover = new ProcessBuilder(java(io.github.imnotstable.msc.core.fixture.SleepMain.class)).start();
        try {
            long started = leftover.info().startInstant().orElseThrow().toEpochMilli();
            Path pidFile = workspace.resolve(".msc/server.pid");
            Files.createDirectories(pidFile.getParent());
            Files.writeString(pidFile, leftover.pid() + " " + (started + 1) + "\n");
            assertThat(h.ok("server_stop")).isEqualTo("server was not running");
            assertThat(leftover.isAlive()).isTrue();

            Files.writeString(pidFile, leftover.pid() + " " + started + "\n");
            assertThat(h.ok("server_stop")).isEqualTo("stopped a server left running by an earlier session (pid "
                    + leftover.pid() + ", killed: world changes since its last autosave may be lost)");
            assertThat(leftover.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            assertThat(pidFile).doesNotExist();
        } finally {
            leftover.destroyForcibly();
        }
    }

    @Test
    void aDetachedServerAndPackHostOutliveTheCoreUntilTheNextStart() throws Exception {
        assertThat(h.error("server_detach")).contains("the server is not running");
        h.ok("server_start", "timeoutSeconds", 30);
        h.ok("texture", "name", "item/dot", "palette", List.of("a=#ff0000"), "rows", List.of("a"));
        String url = h.ok("pack", "apply", false).lines().filter(l -> l.startsWith("url: ")).findFirst()
                .orElseThrow().substring(5);
        assertThat(h.ok("server_detach")).startsWith("server detached").contains("and so does the resource pack host");
        assertThat(fetch(url)).isEqualTo(200);
        ProcessHandle server = ProcessHandle.of(Long.parseLong(
                Files.readString(workspace.resolve(".msc/server.pid")).split(" ")[0])).orElseThrow();
        h.close();
        assertThat(server.isAlive()).isTrue();
        assertThat(fetch(url)).isEqualTo(200);

        h = new CoreHarness(workspace, c -> c.withServerCommand(java(FakeServerMain.class)));
        assertThat(h.ok("server_start", "timeoutSeconds", 30))
                .contains("stopped a server left running by an earlier session (pid " + server.pid())
                .contains("stopped the pack host left running by an earlier session");
        assertThat(server.isAlive()).isFalse();
        assertThat(fetch(url)).isEqualTo(-1);
    }

    @Test
    void aDetachedServerWhoseAgentAnnouncesAStopOutlivesTheSessionWithItsPackHost() throws Exception {
        // #733: the announce is a hint (#729), so the server runs on; the session end stopped it and its pack host.
        h.ok("server_start", "timeoutSeconds", 30);
        h.ok("texture", "name", "item/dot", "palette", List.of("a=#ff0000"), "rows", List.of("a"));
        String url = h.ok("pack", "apply", false).lines().filter(l -> l.startsWith("url: ")).findFirst()
                .orElseThrow().substring(5);
        h.ok("server_detach");
        ProcessHandle server = ProcessHandle.of(Long.parseLong(
                Files.readString(workspace.resolve(".msc/server.pid")).split(" ")[0])).orElseThrow();
        try {
            h.core.server().console("announce"); // typed into the server: its agent announces, the server runs on
            until("announced", () -> h.core.server().reportsStopping());
            assertThat(h.ok("msc_status")).contains("\nserver: stopping (");
            assertThat(h.core.server().detached()).as("the detach holds").isTrue();
            h.close();
            assertThat(server.isAlive()).as("the detached server runs on").isTrue();
            assertThat(fetch(url)).as("and so does its pack host").isEqualTo(200);
        } finally {
            server.destroyForcibly();
            server.onExit().get(10, java.util.concurrent.TimeUnit.SECONDS);
            h = new CoreHarness(workspace, c -> c.withServerCommand(java(FakeServerMain.class)));
            h.ok("server_stop"); // the leftover pack host
        }
    }

    @Test
    void aServerStartedAfterADetachedOneExitedIsNotDetached() throws Exception {
        h.ok("server_start", "timeoutSeconds", 30);
        h.ok("server_detach");
        h.core.server().console("stop");
        for (int i = 0; i < 100 && h.core.server().running(); i++) {
            Thread.sleep(100);
        }
        assertThat(h.core.server().running()).isFalse();
        h.ok("server_start", "timeoutSeconds", 30);
        assertThat(h.ok("msc_status")).contains("server: running").doesNotContain("detached");
        h.core.server().close();
        assertThat(h.core.server().running()).isFalse();
    }

    @Test
    void stoppingADetachedServerInTheSameSessionStopsItsPackHostToo() throws Exception {
        h.ok("server_start", "timeoutSeconds", 30);
        h.ok("texture", "name", "item/dot", "palette", List.of("a=#ff0000"), "rows", List.of("a"));
        String url = h.ok("pack", "apply", false).lines().filter(l -> l.startsWith("url: ")).findFirst()
                .orElseThrow().substring(5);
        h.ok("server_detach");
        assertThat(h.ok("server_stop")).isEqualTo("server stopped, and the pack host it kept running");
        assertThat(fetch(url)).isEqualTo(-1);
        assertThat(workspace.resolve(".msc/pack-host.pid")).doesNotExist();
        assertThat(h.ok("pack", "apply", false)).contains(url);
        assertThat(fetch(url)).isEqualTo(200);
    }

    @Test
    void aRestartSendsTheAppliedPackAgain() {
        assertThat(h.ok("server_start", "timeoutSeconds", 30)).doesNotContain("resource pack");
        h.ok("texture", "name", "item/dot", "palette", List.of("a=#ff0000"), "rows", List.of("a"));
        assertThat(h.ok("pack")).contains("applied: sent to 1 players; players who join later get it too");
        h.ok("server_stop");
        assertThat(h.ok("server_start", "timeoutSeconds", 30)).contains("\nresource pack sent: players who join get it\n");
    }

    static int fetchCode(String url) throws InterruptedException {
        return fetch(url);
    }

    private static int fetch(String url) throws InterruptedException {
        // The host stops a moment after it is told to.
        for (int i = 0; i < 50; i++) {
            try {
                var c = (java.net.HttpURLConnection) java.net.URI.create(url).toURL().openConnection();
                c.setConnectTimeout(500);
                int code = c.getResponseCode();
                c.disconnect();
                return code;
            } catch (java.io.IOException e) {
                Thread.sleep(100);
                if (i >= 10) {
                    return -1;
                }
            }
        }
        return -1;
    }

    @Test
    void devCycleStopsALeftoverServerBeforeDeploying() throws Exception {
        Process leftover = new ProcessBuilder(java(io.github.imnotstable.msc.core.fixture.SleepMain.class)).start();
        try {
            long started = leftover.info().startInstant().orElseThrow().toEpochMilli();
            Files.createDirectories(workspace.resolve(".msc"));
            Files.writeString(workspace.resolve(".msc/server.pid"), leftover.pid() + " " + started + "\n");
            assertThat(h.ok("dev_cycle", "project", "demo"))
                    .startsWith("stopped a server left running by an earlier session (pid " + leftover.pid())
                    .contains("\nbuild ok in ").contains("deployed demo-1.0.jar as plugins/demo.jar");
            assertThat(leftover.isAlive()).isFalse();
        } finally {
            leftover.destroyForcibly();
            h.ok("server_stop");
        }
    }

    @Test
    void noStartOrDeployRunsWhileALeftoverThatCannotBeStoppedRuns() throws Exception {
        // #408: server_start went on after "stop it by hand", and the new server's pid replaced the leftover's.
        FakeLeftover leftover = FakeLeftover.install(h.core.server(), java.time.Duration.ofMillis(1));
        Path pidFile = FakeLeftover.pidFile(h.core.server());
        String pid = "pid " + leftover.pid();
        assertThat(h.error("server_start", "timeoutSeconds", 30)).contains(pid, "could not be stopped", "over RCON",
                "Nothing was started", "kill -9 " + leftover.pid());
        assertThat(h.core.server().running()).isFalse();
        assertThat(Files.readString(pidFile)).startsWith(leftover.pid() + " ");

        assertThat(h.error("server_start", "timeoutSeconds", 30)).contains(pid, "survived a whole stop before");
        assertThat(h.error("dev_cycle", "project", "demo")).contains(pid, "survived a whole stop before");
        assertThat(workspace.resolve("run/plugins/demo.jar")).doesNotExist();
        assertThat(h.core.server().running()).isFalse();
        int destroys = leftover.destroys.get();
        assertThat(h.error("server_stop")).contains(pid, "over RCON", "is still running");
        assertThat(leftover.destroys.get()).as("server_stop tries the whole stop again").isEqualTo(destroys + 1);
        assertThat(Files.readString(pidFile)).startsWith(leftover.pid() + " ");

        leftover.diesWhenKilled();
        assertThat(h.ok("server_stop")).startsWith("stopped a server left running by an earlier session (" + pid);
        assertThat(pidFile).doesNotExist();
        try {
            assertThat(h.ok("server_start", "timeoutSeconds", 30)).startsWith("server ready in ");
        } finally {
            h.ok("server_stop");
        }
    }

    @Test
    void deployedJarIsNamedAfterTheBuiltJarWithoutItsVersion() {
        Path folder = Path.of("my-server");
        assertThat(LifecycleToolsAccess.deployName(Path.of("lobby-0.1.0-SNAPSHOT.jar"), folder)).isEqualTo("lobby");
        assertThat(LifecycleToolsAccess.deployName(Path.of("demo-1.0.jar"), folder)).isEqualTo("demo");
        assertThat(LifecycleToolsAccess.deployName(Path.of("my-mod-2.3+26.3.jar"), folder)).isEqualTo("my-mod");
        assertThat(LifecycleToolsAccess.deployName(Path.of("plugin.jar"), folder)).isEqualTo("plugin");
        assertThat(LifecycleToolsAccess.deployName(Path.of("parkour-2-0.1.0-SNAPSHOT.jar"), folder)).isEqualTo("parkour-2");
        assertThat(LifecycleToolsAccess.deployName(Path.of("bedwars-4v4-1.0.jar"), folder)).isEqualTo("bedwars-4v4");
        assertThat(LifecycleToolsAccess.deployName(Path.of("lobby-1v1-2.0-rc1.jar"), folder)).isEqualTo("lobby-1v1");
        assertThat(LifecycleToolsAccess.deployName(Path.of("arena-2-1.0.jar"), folder)).isEqualTo("arena-2");
    }

    @Test
    void startRemembersTheServerPidAndStopForgetsIt() {
        h.ok("server_start", "timeoutSeconds", 30);
        assertThat(workspace.resolve(".msc/server.pid")).content().matches("\\d+ \\d+ " + ProcessHandle.current().pid() + " \\d+\n");
        h.ok("server_stop");
        assertThat(workspace.resolve(".msc/server.pid")).doesNotExist();
    }

    @Test
    void failedStartupReturnsTheErrorNotTheWholeLog() throws Exception {
        Files.createDirectories(workspace.resolve("run"));
        Files.writeString(workspace.resolve("run/crash.flag"), "");
        String result = h.ok("server_start", "timeoutSeconds", 30);
        assertThat(result).startsWith("server exited during startup after ")
                .contains("12:00:01 ERROR Failed to start the minecraft server").doesNotContain("Starting minecraft");
    }

    @Test
    void devCycleBuildsDeploysRestartsAndReportsRuntimeErrors() throws Exception {
        h.ok("server_start", "timeoutSeconds", 30);
        String first = h.ok("dev_cycle", "project", "demo");
        assertThat(first).startsWith("build ok in ").contains("deployed demo-1.0.jar as plugins/demo.jar")
                .contains("agent connected").contains("\nno errors or warnings\ntime: ");
        assertThat(h.ok("server_logs", "mode", "grep", "pattern", "Demo")).contains("Enabling Demo v1.0");

        Files.writeString(project.resolve("version.txt"), "2.0");
        Files.writeString(workspace.resolve("run/error.flag"), "");
        String second = h.ok("dev_cycle", "project", "demo");
        assertThat(second).contains("1 errors, 0 warnings")
                .contains("java.lang.IllegalStateException: arena missing")
                .contains("\tat com.example.demo.Demo.onEnable(Demo.java:17)")
                .contains("... 1 framework frames");
        assertThat(h.ok("server_logs", "mode", "grep", "pattern", "\\[Demo\\]")).contains("Demo v2.0").doesNotContain("v1.0");
    }

    @Test
    void aRenamedJarReplacesTheEarlierDeployInsteadOfSittingBesideIt() throws Exception {
        Path legacy = Files.createDirectories(workspace.resolve("legacy"));
        Files.writeString(legacy.resolve("version.txt"), "1.0");
        Files.writeString(legacy.resolve("name.txt"), "demo");
        Path plugins = Files.createDirectories(workspace.resolve("run/plugins"));
        Files.writeString(plugins.resolve("legacy.jar"), "deployed by an older core under the folder name");
        Files.writeString(plugins.resolve("msc-agent-paper.jar"), "agent");

        assertThat(h.ok("dev_cycle", "project", "legacy", "restart", false)).contains("as plugins/demo.jar");
        try (var jars = Files.list(plugins)) {
            assertThat(jars.map(p -> p.getFileName().toString())).containsExactlyInAnyOrder("demo.jar", "msc-agent-paper.jar");
        }
        Files.writeString(legacy.resolve("name.txt"), "arena");
        assertThat(h.ok("dev_cycle", "project", "legacy", "restart", false)).contains("as plugins/arena.jar");
        try (var jars = Files.list(plugins)) {
            assertThat(jars.map(p -> p.getFileName().toString())).containsExactlyInAnyOrder("arena.jar", "msc-agent-paper.jar");
        }
    }

    @Test
    void aProjectNamedLikeTheAgentJarIsRefusedAndTheAgentStays() throws Exception {
        Path plugins = Files.createDirectories(workspace.resolve("run/plugins"));
        Files.writeString(plugins.resolve("msc-agent-paper.jar"), "agent");
        // #311: the jar's name minus its version is the agent's, in any case.
        for (String name : List.of("msc-agent-paper", "MSC-Agent-Paper")) {
            Path p = Files.createDirectories(workspace.resolve("p-" + name));
            Files.writeString(p.resolve("version.txt"), "0.1.0-SNAPSHOT");
            Files.writeString(p.resolve("name.txt"), name);
            assertThat(h.error("dev_cycle", "project", "p-" + name, "restart", false))
                    .contains("plugins/" + name + ".jar").contains("MSC's agent jar on Paper");
        }
        // A project folder named like the agent jar, with no earlier deploy recorded, is not taken for one.
        Path folder = Files.createDirectories(workspace.resolve("msc-agent-paper"));
        Files.writeString(folder.resolve("version.txt"), "1.0");
        Files.writeString(folder.resolve("name.txt"), "demo");
        assertThat(h.ok("dev_cycle", "project", "msc-agent-paper", "restart", false)).contains("as plugins/demo.jar");
        assertThat(plugins.resolve("msc-agent-paper.jar")).hasContent("agent");
        try (var jars = Files.list(plugins)) {
            assertThat(jars.map(p -> p.getFileName().toString())).containsExactlyInAnyOrder("demo.jar", "msc-agent-paper.jar");
        }
    }

    @Test
    void aFailedDeployStopsTheTestsRunningBesideIt() throws Exception {
        Path ws = Files.createDirectories(workspace.resolve("ws9"));
        Path slow = Files.createDirectories(ws.resolve("slow"));
        Files.writeString(slow.resolve("version.txt"), "1.0");
        Files.writeString(slow.resolve("slow-check.flag"), "");
        Path started = slow.resolve("check-started");
        Files.createDirectories(ws.resolve("run"));
        Files.writeString(ws.resolve("run/paper.jar"), "not a jar");
        Files.writeString(ws.resolve("run/eula.txt"), "eula=true\n");
        // The deploy first stops a server an earlier core left running, over RCON. Its fake RCON lets that server
        // exit only once the check has started, so the deploy, made to fail, fails while the check is surely running.
        // (A refusal such as a missing EULA comes before the build, so it never runs beside the tests: #445.)
        Process leftover = new ProcessBuilder(java(io.github.imnotstable.msc.core.fixture.SleepMain.class)).start();
        try (FakeRconServer rcon = new FakeRconServer("pw", command -> {
            if (command.equals("stop")) {
                Thread.ofVirtual().start(() -> stopWhenStarted(leftover, started));
            }
            return "Stopping the server";
        })) {
            Files.writeString(ws.resolve("run/server.properties"),
                    "enable-rcon=true\nrcon.port=" + rcon.port() + "\nrcon.password=pw\n");
            Files.createDirectories(ws.resolve(".msc"));
            Files.writeString(ws.resolve(".msc/server.pid"),
                    leftover.pid() + " " + leftover.info().startInstant().orElseThrow().toEpochMilli() + "\n");
            try (CoreHarness s = new CoreHarness(ws, c -> c.withBuildCommand(java(FakeBuildMain.class)))) {
                StepsAccess.failAt("deploy the jar");
                try {
                    assertThat(s.error("dev_cycle", "project", "slow")).contains("injected failure");
                } finally {
                    StepsAccess.failAt(null);
                }
            }
            ProcessHandle check = ProcessHandle.of(Long.parseLong(Files.readString(started).strip())).orElse(null);
            if (check != null) {
                check.onExit().get(10, java.util.concurrent.TimeUnit.SECONDS);
            }
        } finally {
            leftover.destroyForcibly();
        }
    }

    /** What the fake server does on "stop": ends the leftover server once the check has started. */
    private static void stopWhenStarted(Process server, Path started) {
        try {
            for (int i = 0; i < 300 && !Files.exists(started); i++) {
                Thread.sleep(100);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        server.destroyForcibly();
    }

    @Test
    void devCycleStopsAtABrokenBuildAndLeavesTheServerAlone() throws Exception {
        h.ok("server_start", "timeoutSeconds", 30);
        Files.writeString(project.resolve("broken.flag"), "");
        String result = h.ok("dev_cycle", "project", "demo");
        assertThat(result).startsWith("BUILD FAILED in ").contains("Demo.java:12: error: cannot find symbol")
                .doesNotContain("* Try").doesNotContain("--info");
        assertThat(h.core.server().running()).isTrue();
        assertThat(h.error("dev_cycle", "project", "nowhere")).contains("no project at");
        // #457: a project outside the workspace is refused; MSC builds only what is in it.
        assertThat(h.error("dev_cycle", "project", "/etc")).contains("must be inside the workspace");
        assertThat(h.error("dev_cycle", "project", "..")).contains("must be inside the workspace");
    }

    /** A start timeout below one second is refused with the range, not taken as an instant timeout. */
    @Test
    void serverStartRefusesATimeoutOutsideItsRange() {
        for (int timeout : new int[] {0, -5, 3601}) {
            assertThat(h.error("server_start", "timeoutSeconds", timeout))
                    .isEqualTo("error: argument timeoutSeconds must be 1 to 3600, not " + timeout);
        }
        assertThat(h.core.server().running()).isFalse();
    }

    @Test
    void scaffoldCreatesAProjectOnceAndRefusesToOverwrite() throws Exception {
        assertThat(h.ok("project_scaffold", "name", "MyPlugin", "dir", "fresh"))
                .startsWith("created paper-plugin project in ").doesNotContain("assword").doesNotContain("eula=")
                .contains("next: ask the user exactly: \"To run a Minecraft server you must agree to the Minecraft "
                        + "EULA (https://aka.ms/MinecraftEULA). Do you agree to it? (yes/no)\"")
                .endsWith("On yes: server_setup acceptEula=true, then edit the code and dev_cycle project=fresh");
        Files.createDirectories(workspace.resolve("run"));
        Files.writeString(workspace.resolve("run/paper.jar"), "set up");
        assertThat(h.ok("project_scaffold", "name", "Other", "dir", "second"))
                .endsWith("next: edit the code, then dev_cycle project=second");
        assertThat(workspace.resolve("fresh")).isNotEmptyDirectory();
        assertThat(h.error("project_scaffold", "name", "MyPlugin", "dir", "fresh")).startsWith("error: ");
        assertThat(h.error("project_scaffold", "name", "X", "template", "nope")).contains("unknown template nope");
        assertThat(h.ok("project_scaffold", "name", "Arena", "dir", "game", "template", "fabric-mod"))
                .startsWith("created fabric-mod project in ").contains("packageName: com.example.arena");
        assertThat(workspace.resolve("game/src/main/java/com/example/arena/ArenaPlugin.java")).isRegularFile();
        assertThat(h.error("project_scaffold")).isEqualTo("error: missing argument: name");
    }

    @Test
    void scaffoldSaysWhatEachPlatformNeedsBeforeTheFirstDevCycle() throws Exception {
        // #627: Velocity has no EULA but needs server_setup (velocity.jar, velocity.toml, the forwarding secret).
        assertThat(h.ok("project_scaffold", "name", "Proxy", "dir", "proxy", "template", "velocity-plugin"))
                .doesNotContain("EULA").endsWith("\nnext: server_setup platform=velocity, then edit the code and dev_cycle project=proxy");
        Files.writeString(Files.createDirectories(workspace.resolve("run")).resolve("velocity.jar"), "set up");
        assertThat(h.ok("project_scaffold", "name", "Proxy", "dir", "proxy2", "template", "velocity-plugin"))
                .endsWith("\nnext: edit the code, then dev_cycle project=proxy2");
        // The workspace is Velocity now: a Minestom project in a subfolder needs the switch first (#636).
        assertThat(h.ok("project_scaffold", "name", "Game", "dir", "game", "template", "minestom-server"))
                .contains("\nthe workspace stays Velocity, which never loads this Minestom project: ")
                .endsWith("\nnext: server_setup platform=minestom, then edit the code and dev_cycle project=game");
        // Minestom's server is the project's own jar, which dev_cycle builds: nothing to set up first.
        try (CoreHarness s = new CoreHarness(Files.createDirectories(workspace.resolve("ws-minestom")))) {
            assertThat(s.ok("project_scaffold", "name", "Game", "dir", "game", "template", "minestom-server"))
                    .endsWith("\nnext: edit the code, then dev_cycle project=game");
        }
    }

    /**
     * #636 (ADR 0048): a subfolder project of another family than the workspace's server keeps the workspace's
     * platform; the scaffold names server_setup platform= for the switch, and dev_cycle refuses until it is made.
     */
    @Test
    void aProjectTheWorkspaceServerDoesNotRunIsNotDeployedThere() throws Exception {
        Files.createDirectories(workspace.resolve(".msc"));
        Files.writeString(workspace.resolve(".msc/platform"), "paper\n");
        Path run = Files.createDirectories(workspace.resolve("run"));
        Files.writeString(run.resolve("paper.jar"), "set up");
        Files.writeString(run.resolve("eula.txt"), "eula=true\n");
        String switches = "\nthe workspace stays Paper, which never loads this Fabric project: server_setup "
                + "platform=fabric switches it (Paper projects then need server_setup platform=paper)\n";
        assertThat(h.ok("project_scaffold", "name", "Foo", "dir", "foo", "template", "fabric-mod"))
                .contains(switches + "next: ask the user exactly: ")
                .endsWith("\nOn yes: server_setup platform=fabric acceptEula=true, then edit the code and dev_cycle project=foo");
        assertThat(h.core.server().platform()).isEqualTo(Platform.PAPER);
        // Even with the loader's server jar in the folder, Paper is what starts.
        Files.writeString(run.resolve("fabric-server-launch.jar"), "set up");
        assertThat(h.ok("project_scaffold", "name", "Bar", "dir", "bar", "template", "fabric-mod"))
                .contains(switches).doesNotContain("next: edit the code");

        Path mod = workspace.resolve("foo");
        Files.writeString(mod.resolve("version.txt"), "1.0");
        assertThat(h.error("dev_cycle", "project", "foo", "restart", false)).isEqualTo("error: foo is a Fabric "
                + "project, but this workspace runs Paper, which never loads it; nothing was built or deployed. "
                + "server_setup platform=fabric switches the workspace to Fabric (Paper projects then need "
                + "server_setup platform=paper), or msc_workspace gives the project its own workspace.");
        assertThat(mod.resolve("tasks.log")).doesNotExist();
        assertThat(run.resolve("mods")).doesNotExist();
        // A Paper plugin beside it still deploys, and after the switch the mod does.
        assertThat(h.ok("dev_cycle", "project", "demo", "restart", false)).contains("as plugins/demo.jar");
        Platform.remember(h.core.config(), Platform.FABRIC);
        assertThat(h.ok("dev_cycle", "project", "foo", "restart", false)).contains("as mods/demo.jar");
    }

    /**
     * #642: a jar that is both a Paper and a Velocity plugin (plugin.yml and velocity-plugin.json, as ViaVersion ships)
     * runs on the proxy, so a Velocity workspace deploys it; a Fabric one runs neither and refuses it.
     */
    @Test
    void aPaperAndVelocityPluginDeploysToAVelocityWorkspace() throws Exception {
        Files.createDirectories(workspace.resolve(".msc"));
        Files.writeString(workspace.resolve(".msc/platform"), "velocity\n");
        Path run = Files.createDirectories(workspace.resolve("run"));
        Files.writeString(run.resolve("velocity.jar"), "set up");
        Path resources = Files.createDirectories(project.resolve("src/main/resources"));
        Files.writeString(resources.resolve("plugin.yml"), "name: demo\n");
        Files.writeString(resources.resolve("velocity-plugin.json"), "{\"id\":\"demo\"}");
        assertThat(h.ok("dev_cycle", "project", "demo", "restart", false)).contains("as plugins/demo.jar");
        assertThat(run.resolve("plugins/demo.jar")).isRegularFile();
        assertThat(workspace.resolve(".msc/platform")).hasContent("velocity");
        Platform.remember(h.core.config(), Platform.FABRIC);
        assertThat(h.error("dev_cycle", "project", "demo", "restart", false))
                .startsWith("error: demo is a Paper project, but this workspace runs Fabric, which never loads it;");

        // The scaffold and dev_cycle agree: a Paper plugin alone is not the proxy's, until it is a Velocity plugin too.
        Platform.remember(h.core.config(), Platform.VELOCITY);
        assertThat(h.ok("project_scaffold", "name", "Both", "dir", "both", "template", "paper-plugin"))
                .contains("\nthe workspace stays Velocity, which never loads this Paper project: server_setup "
                        + "platform=paper switches it (Velocity projects then need server_setup platform=velocity)\n");
        Path both = workspace.resolve("both");
        Files.writeString(both.resolve("version.txt"), "1.0");
        assertThat(h.error("dev_cycle", "project", "both", "restart", false))
                .startsWith("error: both is a Paper project, but this workspace runs Velocity, which never loads it;");
        Files.writeString(Files.createDirectories(both.resolve("src/main/resources")).resolve("velocity-plugin.json"),
                "{\"id\":\"both\"}");
        assertThat(h.ok("dev_cycle", "project", "both", "restart", false)).contains("as plugins/");
        assertThat(workspace.resolve(".msc/platform")).hasContent("velocity");
    }

    /**
     * #648: ModDevGradle's legacyforge plugin id contains its NeoForge one, but a Forge mod built with it is no NeoForge
     * mod: a NeoForge workspace refuses it, as before #642, and a Forge one deploys it.
     */
    @Test
    void aLegacyForgeModIsNotDeployedToANeoForgeWorkspace() throws Exception {
        Files.createDirectories(workspace.resolve(".msc"));
        Files.writeString(workspace.resolve(".msc/platform"), "neoforge\n");
        Path run = Files.createDirectories(workspace.resolve("run"));
        Files.writeString(run.resolve("server.jar"), "set up");
        Files.writeString(run.resolve("eula.txt"), "eula=true\n");
        Files.createDirectories(project.resolve("src/main/resources/META-INF"));
        Files.writeString(project.resolve("src/main/resources/META-INF/mods.toml"), "");
        Files.writeString(project.resolve("build.gradle"),
                "plugins {\n    id 'net.neoforged.moddev.legacyforge' version '2.0.91'\n}\n");
        assertThat(h.error("dev_cycle", "project", "demo", "restart", false))
                .startsWith("error: demo is a Forge project, but this workspace runs NeoForge, which never loads it;");
        assertThat(run.resolve("mods")).doesNotExist();
        Platform.remember(h.core.config(), Platform.FORGE);
        assertThat(h.ok("dev_cycle", "project", "demo", "restart", false)).contains("as mods/demo.jar");
    }

    /**
     * #643: paper-server writes only server files, used by every platform: no project the workspace's server could
     * fail to run, so no switch line, and its next step is for the workspace's own server.
     */
    @Test
    void serverFilesAreNoProjectOfAnotherPlatform() throws Exception {
        Files.createDirectories(workspace.resolve(".msc"));
        Files.writeString(workspace.resolve(".msc/platform"), "fabric\n");
        Path run = Files.createDirectories(workspace.resolve("run"));
        Files.writeString(run.resolve("fabric-server-launch.jar"), "set up");
        Files.writeString(run.resolve("eula.txt"), "eula=true\n");
        assertThat(h.ok("project_scaffold", "name", "server", "dir", "defaults", "template", "paper-server"))
                .doesNotContain("never loads").doesNotContain("platform=").doesNotContain("EULA")
                .endsWith("\nnext: edit the code, then dev_cycle");
        Files.delete(run.resolve("fabric-server-launch.jar"));
        assertThat(h.ok("project_scaffold", "name", "server", "dir", "defaults2", "template", "paper-server"))
                .doesNotContain("never loads").doesNotContain("platform=")
                .endsWith("\nOn yes: server_setup acceptEula=true, then edit the code and dev_cycle");
        assertThat(workspace.resolve(".msc/platform")).hasContent("fabric");
        Platform.remember(h.core.config(), Platform.VELOCITY);
        assertThat(h.ok("project_scaffold", "name", "server", "dir", "defaults3", "template", "paper-server"))
                .doesNotContain("never loads").doesNotContain("EULA")
                .endsWith("\nnext: server_setup platform=velocity, then edit the code and dev_cycle");
    }

    /** #631: Quilt runs Fabric mods and Purpur Paper plugins, so the workspace's own server counts as set up. */
    @Test
    void scaffoldCountsAWorkspaceServerThatRunsTheTemplate() throws Exception {
        for (String[] c : new String[][] {{"quilt", "quilt-server-launch.jar", "fabric-mod"},
                {"purpur", "purpur.jar", "paper-plugin"}, {"folia", "folia.jar", "paper-plugin"}}) {
            Path ws = Files.createDirectories(workspace.resolve("ws-" + c[0]));
            Files.createDirectories(ws.resolve(".msc"));
            Files.writeString(ws.resolve(".msc/platform"), c[0] + "\n");
            Path run = Files.createDirectories(ws.resolve("run"));
            Files.writeString(run.resolve(c[1]), "set up");
            Files.writeString(run.resolve("eula.txt"), "eula=true\n");
            try (CoreHarness s = new CoreHarness(ws)) {
                assertThat(s.ok("project_scaffold", "name", "Foo", "dir", "foo", "template", c[2]))
                        .doesNotContain("EULA").endsWith("\nnext: edit the code, then dev_cycle project=foo");
            }
            assertThat(ws.resolve(".msc/platform")).hasContent(c[0]);
        }
    }

    /** #631: with MSC_SERVER_DIR elsewhere a scaffold into the workspace root keeps its Quilt platform. */
    @Test
    void scaffoldIntoTheWorkspaceKeepsAPlatformThatRunsTheTemplate() throws Exception {
        Path ws = Files.createDirectories(workspace.resolve("ws-root"));
        Files.createDirectories(ws.resolve(".msc"));
        Files.writeString(ws.resolve(".msc/platform"), "quilt\n");
        Path run = Files.createDirectories(workspace.resolve("elsewhere"));
        Files.writeString(run.resolve("quilt-server-launch.jar"), "set up");
        Files.writeString(run.resolve("eula.txt"), "eula=true\n");
        try (CoreHarness s = new CoreHarness(ws, c -> CoreConfig.fromEnvironment(java.util.Map.of(
                CoreConfig.ENV_WORKSPACE, ws.toString(), CoreConfig.ENV_SERVER_DIR, run.toString()))
                .withMachineEula(MachineEula.none()))) {
            assertThat(s.ok("project_scaffold", "name", "Foo", "template", "fabric-mod"))
                    .startsWith("created fabric-mod project in " + ws).endsWith("\nnext: edit the code, then dev_cycle");
            assertThat(ws.resolve(".msc/platform")).hasContent("quilt");
            assertThat(s.core.server().platform().id()).isEqualTo("quilt");
        }
    }

    @Test
    void scaffoldUsesTheChosenPackage() {
        assertThat(h.ok("project_scaffold", "name", "Lobby", "dir", "pkg", "package", "io.github.alex.lobby"))
                .contains("\npackageName: io.github.alex.lobby\n");
        assertThat(workspace.resolve("pkg/src/main/java/io/github/alex/lobby/LobbyPlugin.java")).exists();
        assertThat(h.error("project_scaffold", "name", "Lobby", "dir", "bad", "package", "Io.Alex"))
                .startsWith("error: packageName must match ");
    }

    @Test
    void scaffoldIntoTheWorkspaceIgnoresTheCoresOwnDataAndClaudeCodeConfig() throws Exception {
        Path ws = Files.createDirectories(workspace.resolve("ws"));
        Files.createDirectories(ws.resolve(".msc"));
        Files.createDirectories(ws.resolve(".claude"));
        Files.writeString(ws.resolve(".mcp.json"), "{}");
        try (CoreHarness other = new CoreHarness(ws)) {
            other.ok("msc_status");
            assertThat(other.ok("project_scaffold", "name", "MyPlugin")).startsWith("created paper-plugin project in " + ws);
        }
        assertThat(ws.resolve("build.gradle.kts")).exists();
        assertThat(ws.resolve("run/server.properties")).isRegularFile();
        assertThat(ws.resolve("run/eula.txt")).content().contains("eula=false");
        assertThat(ws.resolve("src/main/java/com/example/myplugin/MyPlugin.java")).isRegularFile();
        assertThat(ws.resolve(".msc")).isDirectory();
        assertThat(ws.resolve(".mcp.json")).hasContent("{}");
        try (var left = Files.list(ws.resolve(".msc"))) {
            assertThat(left.filter(p -> p.getFileName().toString().startsWith("scaffold-"))).isEmpty();
        }
    }

    @Test
    void scaffoldWritesTheEulaOnlyWhenAccepted() {
        h.ok("project_scaffold", "name", "Srv", "dir", "a", "template", "paper-server");
        h.ok("project_scaffold", "name", "Srv", "dir", "b", "template", "paper-server", "acceptEula", true);
        assertThat(workspace.resolve("a/eula.txt")).content().contains("eula=false");
        assertThat(workspace.resolve("b/eula.txt")).content().contains("eula=true");
    }

    @Test
    void scaffoldAcceptEulaIsNotKeptForTheMachine() throws Exception {
        // tools.md: only server_setup and server_start record acceptEula=true in ~/.msc/eula.json (#376).
        Path machineEula = workspace.resolve("home/.msc/eula.json");
        Path ws = Files.createDirectories(workspace.resolve("ws8"));
        try (CoreHarness s = new CoreHarness(ws, c -> c.withMachineEula(MachineEula.forHome(workspace.resolve("home"))))) {
            s.ok("project_scaffold", "name", "Srv", "dir", "srv", "template", "paper-server", "acceptEula", true);
            s.ok("project_scaffold", "name", "Plug", "dir", "plug", "acceptEula", true);
        }
        assertThat(ws.resolve("srv/eula.txt")).content().contains("eula=true");
        assertThat(machineEula).doesNotExist();
    }

    @Test
    void scaffoldAndStartUseTheMachineWideAcceptance() throws Exception {
        Path machineEula = MachineEulaAccess.accepted(workspace.resolve("home"));
        Path ws = Files.createDirectories(workspace.resolve("ws5"));
        try (CoreHarness s = new CoreHarness(ws, c -> c.withMachineEula(MachineEula.forHome(workspace.resolve("home"))))) {
            assertThat(s.ok("project_scaffold", "name", "MyPlugin")).doesNotContain("ask the user")
                    .endsWith("next: server_setup (the Minecraft EULA is accepted for this machine by owner on pc, "
                            + "2026-11-12), then edit the code and dev_cycle");
            assertThat(ws.resolve("run/eula.txt")).content().contains("eula=false");
            // A server set up before the acceptance: server_start applies it instead of refusing.
            Files.writeString(ws.resolve("run/paper.jar"), "not a jar");
            assertThat(s.call("server_start", "timeoutSeconds", 20).text()).doesNotContain("EULA");
            assertThat(ws.resolve("run/eula.txt")).content().contains("eula=true").doesNotContain("eula=false");
        }
    }

    @Test
    void acceptEulaOnStartAlsoCoversLaterServersOnThisMachine() throws Exception {
        Path machineEula = workspace.resolve("home/.msc/eula.json");
        Path ws = Files.createDirectories(workspace.resolve("ws6/run")).getParent();
        Files.writeString(ws.resolve("run/paper.jar"), "not a jar");
        try (CoreHarness s = new CoreHarness(ws, c -> c.withMachineEula(MachineEula.forHome(workspace.resolve("home"))))) {
            assertThat(s.error("server_start")).contains("EULA is not accepted");
            assertThat(machineEula).doesNotExist();
            assertThat(s.call("server_start", "acceptEula", true, "timeoutSeconds", 20).text())
                    .startsWith("Minecraft EULA (https://aka.ms/MinecraftEULA) accepted for this machine, saved in ")
                    .contains("TERMS.md").contains("every Paper, Purpur, Folia, Fabric, Quilt, NeoForge and Forge server");
            // The notice is shown once.
            assertThat(s.call("server_start", "acceptEula", true, "timeoutSeconds", 20).text()).doesNotContain("TERMS.md");
            assertThat(ws.resolve("run/eula.txt")).content().contains("eula=true");
            assertThat(machineEula).content().contains("\"how\": \"the user's own yes, passed as acceptEula=true\"");
        }
        Path other = Files.createDirectories(workspace.resolve("ws7/run")).getParent();
        Files.writeString(other.resolve("run/paper.jar"), "not a jar");
        try (CoreHarness s = new CoreHarness(other, c -> c.withMachineEula(MachineEula.forHome(workspace.resolve("home"))))) {
            assertThat(s.call("server_start", "timeoutSeconds", 20).text()).doesNotContain("EULA");
            assertThat(other.resolve("run/eula.txt")).content().contains("eula=true");
        }
    }

    @Test
    void startFailsFastWithoutTheEula() throws Exception {
        Path ws = Files.createDirectories(workspace.resolve("ws3"));
        Files.createDirectories(ws.resolve("run"));
        Files.writeString(ws.resolve("run/paper.jar"), "not a jar");
        try (CoreHarness s = new CoreHarness(ws)) {
            assertThat(s.error("server_start")).contains("Minecraft EULA is not accepted").contains("acceptEula=true")
                    .contains("runs once in their own terminal: ").doesNotContain("\n");
            Files.writeString(ws.resolve("run/eula.txt"), "eula=false\n");
            assertThat(s.error("server_start")).contains("EULA is not accepted");
            assertThat(s.core.server().running()).isFalse();
        }
    }

    /** #551: eula.txt is read as the server reads it, a properties file; MSC writes nothing to it here. */
    @Test
    void startReadsTheEulaAsTheServerDoes() throws Exception {
        Path ws = Files.createDirectories(workspace.resolve("ws8"));
        Files.createDirectories(ws.resolve("run"));
        Files.writeString(ws.resolve("run/paper.jar"), "not a jar");
        Path eula = ws.resolve("run/eula.txt");
        try (CoreHarness s = new CoreHarness(ws)) {
            for (String text : List.of("eula=false\n", "eula=yes\n", "#eula=true\n", "eula.txt=true\n")) {
                Files.writeString(eula, text);
                assertThat(s.error("server_start")).as(text).contains("EULA is not accepted");
                assertThat(eula).as(text).hasContent(text.strip());
            }
            for (String text : List.of("eula = true\n", "eula=TRUE\n", "#comment\n  eula:true\n")) {
                Files.writeString(eula, text);
                assertThat(s.call("server_start", "timeoutSeconds", 20).text()).as(text).doesNotContain("EULA");
                assertThat(eula).as(text).hasContent(text.strip());
            }
        }
    }

    @Test
    void setupDownloadsTheNewestStableBuildAndInstallsTheAgent() throws Exception {
        byte[] jar = "paper jar bytes".getBytes(StandardCharsets.UTF_8);
        String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(jar));
        HttpServer fill = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        String base = "http://127.0.0.1:" + fill.getAddress().getPort();
        int[] downloads = {0};
        fill.createContext("/v3/projects/paper/versions/26.3/builds", exchange -> {
            String body = "[{\"id\":7,\"channel\":\"ALPHA\",\"downloads\":{\"server:default\":{\"url\":\"" + base + "/bad.jar\"}}},"
                    + "{\"id\":5,\"channel\":\"STABLE\",\"downloads\":{\"server:default\":{\"name\":\"paper-26.3-5.jar\",\"url\":\""
                    + base + "/paper.jar\",\"checksums\":{\"sha256\":\"" + sha + "\"}}}}]";
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(exchange.getRequestHeaders().getFirst("User-Agent").startsWith("msc-core") ? 200 : 403,
                    bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        fill.createContext("/paper.jar", exchange -> {
            downloads[0]++;
            exchange.sendResponseHeaders(200, jar.length);
            exchange.getResponseBody().write(jar);
            exchange.close();
        });
        fill.start();
        Path agentJar = Files.writeString(workspace.resolve("agent.jar"), "agent");
        try (CoreHarness s = new CoreHarness(workspace.resolve("ws2"), c -> c.withDownloadsUrl(base))) {
            String first = s.ok("server_setup");
            assertThat(first).contains("paper 26.3 build 5 (downloaded)").contains("warning: msc paper agent not found");
            Path run = workspace.resolve("ws2/run");
            assertThat(run.resolve("paper.jar")).hasBinaryContent(jar);
            assertThat(run.resolve("eula.txt")).content().contains("eula=false");
            assertThat(first).contains("EULA is not accepted").contains("acceptEula=true");
            Files.createDirectories(workspace.resolve("ws2/.msc/agent"));
            Files.copy(agentJar, workspace.resolve("ws2/.msc/agent/msc-agent-paper-0.1.0.jar"));
            assertThat(s.ok("server_setup")).contains("build 5 (cached)").contains("agent installed in plugins/");
            assertThat(run.resolve("plugins/msc-agent-paper.jar")).hasContent("agent");
            assertThat(downloads[0]).isEqualTo(1);
            assertThat(s.ok("server_setup", "acceptEula", true)).contains("eula.txt has eula=true");
            assertThat(run.resolve("eula.txt")).content().contains("eula=true").doesNotContain("eula=false");
            assertThat(s.error("server_setup", "version", "1.0")).contains("Paper has no version 1.0").doesNotContain("internal");
            assertThat(s.ok("msc_status")).contains("platform: paper\n").contains("server: stopped");

            assertThat(s.ok("server_setup", "mode", "game")).contains("mode game: survival, normal difficulty");
            assertThat(run.resolve("server.properties")).content().contains("\ngamemode=survival\n")
                    .contains("\ndifficulty=normal\n").contains("force-gamemode=true").contains("online-mode=false");
            s.ok("server_setup", "mode", "build");
            assertThat(run.resolve("server.properties")).content().contains("\ngamemode=creative\n")
                    .contains("\ndifficulty=peaceful\n").doesNotContain("survival");
            assertThat(s.error("server_setup", "mode", "hardcore")).contains("mode must be build or game");
        }
        // An installed core ships its agents in <install>/agents; the workspace's .msc/agent still wins.
        Path install = Files.createDirectories(workspace.resolve("install/agents")).getParent();
        Files.writeString(install.resolve("agents/msc-agent-fabric-0.1.0.jar"), "fabric");
        Files.writeString(install.resolve("agents/msc-agent-paper-0.1.0.jar"), "bundled");
        // The owner's one-time acceptance for this machine (ADR 0020) stands in for acceptEula=true.
        Path machineEula = MachineEulaAccess.accepted(workspace.resolve("home"));
        try (CoreHarness s = new CoreHarness(workspace.resolve("ws4"), c -> c.withDownloadsUrl(base).withInstallDir(install)
                .withMachineEula(MachineEula.forHome(workspace.resolve("home"))))) {
            assertThat(s.ok("server_setup")).contains("agent installed in plugins/")
                    .contains("eula.txt has eula=true from the owner's one-time acceptance for this machine (owner on pc, 2026-11-12)")
                    .doesNotContain("ask the user");
            assertThat(workspace.resolve("ws4/run/plugins/msc-agent-paper.jar")).hasContent("bundled");
            assertThat(workspace.resolve("ws4/run/eula.txt")).content().contains("eula=true").doesNotContain("eula=false");
        }
        // dev_cycle or server_start acceptEula=true may fill run/ first: the missing defaults are still written.
        Path ws8 = workspace.resolve("ws8");
        Files.createDirectories(ws8.resolve("run/plugins"));
        Files.writeString(ws8.resolve("run/plugins/demo.jar"), "deployed before setup");
        Files.writeString(ws8.resolve("run/eula.txt"), "eula=true\n");
        try (CoreHarness s = new CoreHarness(ws8, c -> c.withDownloadsUrl(base))) {
            assertThat(s.ok("server_setup")).contains("wrote 1 default config files");
            assertThat(ws8.resolve("run/server.properties")).content().contains("online-mode=false");
            assertThat(ws8.resolve("run/eula.txt")).hasContent("eula=true\n");
            assertThat(ws8.resolve("run/plugins/demo.jar")).hasContent("deployed before setup");
            assertThat(s.ok("server_setup")).doesNotContain("default config files");
        } finally {
            fill.stop(0);
        }
    }

    @Test
    void fabricSetupInstallsTheLauncherFabricApiAndTheFabricAgent() throws Exception {
        String api = io.github.imnotstable.msc.templates.Templates.versions().get("fabricApiVersion");
        String loader = io.github.imnotstable.msc.templates.Templates.versions().get("fabricLoaderVersion");
        String minecraft = api.substring(api.indexOf('+') + 1);
        HttpServer fabric = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        String base = "http://127.0.0.1:" + fabric.getAddress().getPort();
        List<String> paths = new java.util.concurrent.CopyOnWriteArrayList<>();
        fabric.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getRawPath();
            paths.add(path);
            byte[] bytes;
            if (path.equals("/meta/v2/versions/installer")) {
                bytes = "[{\"version\":\"9.9.9\",\"stable\":false},{\"version\":\"1.1.2\",\"stable\":true}]"
                        .getBytes(StandardCharsets.UTF_8);
            } else if (path.equals("/meta/v2/versions/loader/" + minecraft + "/" + loader + "/1.1.2/server/jar")) {
                bytes = "launcher".getBytes(StandardCharsets.UTF_8);
            } else if (path.startsWith("/maven/net/fabricmc/fabric-api/fabric-api/") && path.endsWith(".jar")) {
                bytes = "fabric api".getBytes(StandardCharsets.UTF_8);
            } else {
                exchange.sendResponseHeaders(404, -1);
                exchange.close();
                return;
            }
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        fabric.start();
        Path ws = Files.createDirectories(workspace.resolve("fab"));
        Files.createDirectories(ws.resolve(".msc/agent"));
        Files.writeString(ws.resolve(".msc/agent/msc-agent-paper-0.1.0.jar"), "paper agent");
        try (CoreHarness s = new CoreHarness(ws, c -> c.withFabricUrls(base + "/meta", base + "/maven")
                .withMinecraft(minecraft))) {
            String first = s.ok("server_setup", "platform", "fabric");
            assertThat(first).contains("fabric launcher for " + minecraft + ", loader " + loader + " (downloaded)")
                    .contains("fabric api " + api + " (downloaded)").contains("warning: msc fabric agent not found")
                    .contains("EULA is not accepted")
                    .endsWith("next: server_start or project_scaffold template=fabric-mod then dev_cycle");
            Path run = ws.resolve("run");
            assertThat(run.resolve("fabric-server-launch.jar")).hasContent("launcher");
            assertThat(run.resolve("mods/fabric-api.jar")).hasContent("fabric api");
            assertThat(run.resolve("eula.txt")).content().contains("eula=false");
            assertThat(run.resolve("plugins")).doesNotExist();
            assertThat(ws.resolve(".msc/platform")).hasContent("fabric\n");
            assertThat(s.ok("msc_status")).contains("platform: fabric\n").contains("server: stopped");
            assertThat(s.error("server_start")).contains("Minecraft EULA is not accepted");

            Files.writeString(ws.resolve(".msc/agent/msc-agent-fabric-0.1.0.jar"), "fabric agent");
            int before = paths.size();
            assertThat(s.ok("server_setup")).contains("(cached)").contains("agent installed in mods/");
            assertThat(run.resolve("mods/msc-agent-fabric.jar")).hasContent("fabric agent");
            assertThat(paths).hasSize(before);
            assertThat(s.error("server_setup", "version", "1.0")).contains("Fabric setup supports Minecraft " + minecraft);
            assertThat(s.error("server_setup", "platform", "spigot")).contains("unknown platform spigot")
                    .contains("paper, purpur, folia, fabric, quilt, neoforge, forge, minestom, velocity");
            // Quilt runs Fabric mods: a version Fabric API does not cover is refused before any download.
            assertThat(s.error("server_setup", "platform", "quilt", "version", "1.0"))
                    .contains("Fabric setup supports Minecraft " + minecraft);
            assertThat(s.error("server_setup", "platform", "neoforge", "version", "1.0"))
                    .contains("NeoForge setup supports Minecraft " + minecraft);
            assertThat(s.error("server_setup", "platform", "forge", "version", "1.0"))
                    .contains("Forge setup supports Minecraft " + minecraft);
        } finally {
            fabric.stop(0);
        }
    }

    @Test
    void minestomNeedsNoSetupAndRunsTheProjectJarAsTheServer() throws Exception {
        Path ws = Files.createDirectories(workspace.resolve("mini"));
        try (CoreHarness s = new CoreHarness(ws, c -> c.withBuildCommand(java(FakeBuildMain.class)))) {
            assertThat(s.ok("project_scaffold", "name", "Lobby", "template", "minestom-server"))
                    .startsWith("created minestom-server project in ");
            assertThat(ws.resolve(".msc/platform")).hasContent("minestom\n");
            assertThat(ws.resolve("libs/msc-agent-minestom.jar")).isRegularFile();
            assertThat(s.ok("server_setup")).startsWith("minestom needs no server download").contains("next: dev_cycle");
            assertThat(ws.resolve("run/eula.txt")).doesNotExist();
            assertThat(s.error("server_start")).contains("no minestom server in").contains("Run dev_cycle");
            Files.writeString(ws.resolve("version.txt"), "3.0");
            assertThat(s.ok("dev_cycle", "restart", false)).contains("deployed demo-1.0.jar as server.jar");
            assertThat(ws.resolve("run/server.jar")).hasContent("3.0");
            assertThat(s.ok("msc_status")).contains("platform: minestom\n").contains("server: stopped");

            // A thin server jar brings its dependency folder; a dependency the project dropped goes too.
            Files.writeString(ws.resolve("thin.flag"), "dependency");
            Files.createDirectories(ws.resolve("run/libs"));
            Files.writeString(ws.resolve("run/libs/dropped-1.jar"), "old");
            assertThat(s.ok("dev_cycle", "restart", false)).contains("deployed server.jar as server.jar");
            assertThat(ws.resolve("run/server.jar")).hasContent("thin 3.0");
            try (var libs = Files.list(ws.resolve("run/libs"))) {
                assertThat(libs.map(p -> p.getFileName().toString())).containsExactly("dep-1.jar");
            }
            assertThat(ws.resolve("run/libs/dep-1.jar")).hasContent("dependency");
        }
    }

    @Test
    void devCycleTimesEachPhaseAndKeepsTheServerWhenTheJarIsUnchanged() throws Exception {
        String first = h.ok("dev_cycle", "project", "demo");
        assertThat(first).contains("deployed demo-1.0.jar as plugins/demo.jar")
                .containsPattern("\ntime: jar \\d+\\.\\ds, start \\d+\\.\\ds, agent \\d+\\.\\ds, tests \\d+\\.\\ds, total \\d+\\.\\ds$");
        // The start ends when the agent says ready, which the fake server does before it logs "Done": wait for that
        // line, so the mark is the whole startup log and a line after it can only come from a restart.
        awaitLogLine("Done (");
        long logMark = h.core.server().log().mark();
        assertThat(project.resolve("tasks.log")).hasContent("mscJar\ncheck");

        String second = h.ok("dev_cycle", "project", "demo");
        assertThat(second).contains("jar unchanged: plugins/demo.jar kept, server not restarted")
                .doesNotContain("server ready").containsPattern("\ntime: jar [^,]+, tests [^,]+, total ");
        assertThat(h.core.server().log().mark()).isEqualTo(logMark);

        Files.writeString(project.resolve("version.txt"), "2.0");
        assertThat(h.ok("dev_cycle", "project", "demo")).contains("\ntime: jar ").contains(", stop ")
                .contains("server ready");
        h.ok("server_stop");
    }

    /**
     * #668, #681: every line of a dev_cycle result is true for each outcome of the tests and of the jar, with and
     * without a restart. Only a jar this call deployed is "deployed anyway"; an unchanged one is kept, also with
     * restart=false, and the result says only that. A kept jar keeps its file time, so the next dev_cycle does not
     * restart for nothing. Each changed jar is deployed first without a restart, then with one, so an unchanged jar is
     * always the one the running server loaded. #690: a changed pack reaches the players of every server the call
     * leaves running, whatever happens to the jar; an unchanged one only a new connection (a restart).
     */
    @Test
    void devCycleLinesAreTrueForEveryTestsJarAndRestartOutcome() throws Exception {
        assertThat(h.ok("dev_cycle", "project", "demo")).contains("deployed demo-1.0.jar as plugins/demo.jar");
        h.ok("texture", "name", "item/dot", "palette", List.of("a=#000001"), "rows", List.of("a"));
        assertThat(h.ok("pack")).contains("applied: sent to 1 players");
        long sends = packSends();
        Path deployed = h.core.config().serverDir().resolve("plugins/demo.jar");
        int version = 0;
        int color = 1;
        for (String tests : List.of("pass", "fail", "skipped")) {
            for (String jar : List.of("changed", "unchanged", "none")) {
                for (boolean restart : List.of(false, true)) {
                    for (boolean packChanged : List.of(true, false)) {
                        boolean changed = jar.equals("changed");
                        Files.deleteIfExists(project.resolve("failing-test.flag"));
                        Files.deleteIfExists(project.resolve("no-tests.flag"));
                        Files.deleteIfExists(project.resolve("no-jar.flag"));
                        if (!tests.equals("pass")) {
                            Files.writeString(project.resolve(tests.equals("fail") ? "failing-test.flag"
                                    : "no-tests.flag"), "");
                        }
                        if (jar.equals("none")) {
                            Files.writeString(project.resolve("no-jar.flag"), "");
                        }
                        if (changed) {
                            Files.writeString(project.resolve("version.txt"), "1." + ++version);
                        }
                        if (packChanged) {
                            h.ok("texture", "name", "item/dot", "palette",
                                    List.of("a=#" + "%06x".formatted(++color)), "rows", List.of("a"));
                        }
                        var before = Files.getLastModifiedTime(deployed);
                        String result = h.ok("dev_cycle", "project", "demo", "restart", restart);
                        var row = assertThat(result).as("tests %s, jar %s, restart %s, pack %s:%n%s", tests, jar,
                                restart, packChanged ? "changed" : "unchanged", result);
                        if (tests.equals("fail")) {
                            row.startsWith("TESTS FAILED in ").contains("DemoTest > greets() FAILED");
                        } else {
                            row.doesNotContain("TESTS FAILED").doesNotContain("FAILED");
                        }
                        // A restart's new connection always gets the pack; a server kept running only a changed one.
                        boolean sent = packChanged || changed && restart;
                        if (sent) {
                            row.contains("\nresource pack sent: players who join get it\n");
                        } else {
                            row.doesNotContain("resource pack");
                        }
                        long now = packSends();
                        assertThat(now - sends).as("packs sent: %s", result).isEqualTo(sent ? 1 : 0);
                        sends = now;
                        if (jar.equals("none")) {
                            row.contains("build ok in ").contains(" but no jar in ").contains("; nothing deployed")
                                    .doesNotContain("deployed demo").doesNotContain("server ready")
                                    .containsPattern("\ntime: jar [^,]+, tests [^,]+, total [^,\n]+$");
                            assertThat(Files.getLastModifiedTime(deployed)).as("kept jar's time").isEqualTo(before);
                        } else if (changed) {
                            row.contains("deployed demo-1.0.jar as plugins/demo.jar").doesNotContain("jar unchanged");
                            if (restart) {
                                row.contains("server ready").doesNotContain("old jar")
                                        .containsPattern("\ntime: jar [^,]+, stop [^\n]*, tests ");
                            } else {
                                row.contains("as plugins/demo.jar; server still running the old jar until restarted")
                                        .doesNotContain("server ready")
                                        .containsPattern("\ntime: jar [^,]+, tests [^,]+, total [^,\n]+$");
                            }
                            if (tests.equals("fail")) {
                                row.contains("TESTS FAILED in ").contains("s (the new jar is deployed anyway)\n");
                            }
                        } else {
                            row.contains("build ok in ")
                                    .contains(", jar unchanged: plugins/demo.jar kept, server not restarted")
                                    .doesNotContain("deployed").doesNotContain("server ready").doesNotContain("old jar")
                                    .containsPattern("\ntime: jar [^,]+, tests [^,]+, total [^,\n]+$");
                            assertThat(Files.getLastModifiedTime(deployed)).as("kept jar's time").isEqualTo(before);
                        }
                    }
                }
            }
        }
        h.ok("server_stop");
    }

    /**
     * #699: an edit id lasts as long as the server run it was made in. After a server_stop, a dev_cycle restart, or a
     * server that stopped on its own, the agent's undo history is gone, so build_undo answers that the id is unknown
     * (not that the undo budget ran out), and the dead ids no longer block undoing anything.
     */
    @Test
    void editIdsEndWithTheServerRunTheyWereMadeIn() throws Exception {
        String unknown = " (already undone, or made before the server last stopped: build_undo backup= rolls the "
                + "world back)";
        assertThat(h.ok("dev_cycle", "project", "demo")).contains("agent connected");
        String e1 = editId(h.ok("build_batch", "steps", "fill 0,70,0 3,70,3 stone"));
        String e2 = editId(h.ok("build_batch", "steps", "fill 2,70,2 5,70,5 dirt"));
        h.ok("server_stop");
        assertThat(h.error("build_undo", "edit", e1)).isEqualTo("error: unknown edit " + e1 + unknown);
        h.ok("server_start", "timeoutSeconds", 30);
        assertThat(h.error("build_undo", "edit", e2)).isEqualTo("error: unknown edit " + e2 + unknown);
        assertThat(h.error("build_undo")).contains("nothing to undo");
        String e3 = editId(h.ok("build_batch", "steps", "fill 0,70,0 3,70,3 stone"));
        Files.writeString(project.resolve("version.txt"), "2.0");
        assertThat(h.ok("dev_cycle", "project", "demo")).contains("server ready");
        assertThat(h.error("build_undo", "edit", e3)).isEqualTo("error: unknown edit " + e3 + unknown);
        String e4 = editId(h.ok("build_batch", "steps", "fill 0,70,0 3,70,3 stone"));
        // Stopped on its own, as far as the core knows: the console line goes straight to the process.
        h.core.server().console("stop");
        long deadline = System.nanoTime() + java.time.Duration.ofSeconds(60).toNanos();
        while (h.core.server().running() && System.nanoTime() < deadline) {
            Thread.sleep(50);
        }
        assertThat(h.error("build_undo")).contains("nothing to undo");
        h.ok("server_start", "timeoutSeconds", 30);
        assertThat(h.error("build_undo", "edit", e4)).isEqualTo("error: unknown edit " + e4 + unknown);
        String e5 = editId(h.ok("build_batch", "steps", "fill 0,70,0 3,70,3 stone"));
        assertThat(h.ok("build_undo")).isEqualTo("undid " + e5 + ": 16 blocks restored");
        h.ok("server_stop");
    }

    private static String editId(String result) {
        assertThat(result).startsWith("edit e");
        return result.substring(5, result.indexOf(':'));
    }

    /**
     * #698: a server that can have an agent but has none connected (here it never connects) does not get the pack, and
     * every dev_cycle that keeps it running says so, as pack promised; once its agent connects, it gets the pack.
     */
    @Test
    void devCycleWithNoAgentConnectedSaysThePackWasNotSent() throws Exception {
        Path run = Files.createDirectories(workspace.resolve("run"));
        Files.writeString(run.resolve("no-agent.flag"), "");
        assertThat(h.ok("dev_cycle", "project", "demo")).contains("no agent connected").doesNotContain("resource pack");
        h.ok("texture", "name", "item/dot", "palette", List.of("a=#000001"), "rows", List.of("a"));
        assertThat(h.ok("pack")).contains("\nnot sent yet: no server agent connected; the next server_start or "
                + "dev_cycle sends it");
        String notSent = "\nresource pack not sent: no server agent connected; it is sent when one connects";
        assertThat(h.ok("dev_cycle", "project", "demo")).contains("jar unchanged").contains(notSent);
        Files.writeString(project.resolve("version.txt"), "1.1");
        assertThat(h.ok("dev_cycle", "project", "demo", "restart", false)).contains("old jar").contains(notSent);
        Files.delete(run.resolve("no-agent.flag"));
        assertThat(h.ok("dev_cycle", "project", "demo")).contains("server ready").doesNotContain(notSent)
                .contains("\nresource pack sent: players who join get it\n");
        assertThat(packSends()).isEqualTo(1);
        h.ok("server_stop");
    }

    /**
     * #694: a proxy has no agent, so no pack ever reaches it. pack says why; every dev_cycle, whatever happens to the
     * jar and the pack, says nothing about the pack, as server_start does there.
     */
    @Test
    void devCycleOnAProxySaysNothingAboutThePack() throws Exception {
        Files.createDirectories(workspace.resolve(".msc"));
        Files.writeString(workspace.resolve(".msc/platform"), "velocity\n");
        Path run = Files.createDirectories(workspace.resolve("run"));
        Files.writeString(run.resolve("velocity.jar"), "set up");
        Files.writeString(run.resolve("no-agent.flag"), "");
        Path resources = Files.createDirectories(project.resolve("src/main/resources"));
        Files.writeString(resources.resolve("velocity-plugin.json"), "{\"id\":\"demo\"}");
        assertThat(h.ok("dev_cycle", "project", "demo")).contains("a proxy: no world or agent")
                .doesNotContain("resource pack");
        h.ok("texture", "name", "item/dot", "palette", List.of("a=#000001"), "rows", List.of("a"));
        assertThat(h.ok("pack")).contains("\nnot sent: Velocity is a proxy");
        int version = 0;
        int color = 1;
        for (String jar : List.of("changed", "unchanged", "none")) {
            for (boolean restart : List.of(false, true)) {
                for (boolean packChanged : List.of(true, false)) {
                    Files.deleteIfExists(project.resolve("no-jar.flag"));
                    if (jar.equals("none")) {
                        Files.writeString(project.resolve("no-jar.flag"), "");
                    } else if (jar.equals("changed")) {
                        Files.writeString(project.resolve("version.txt"), "1." + ++version);
                    }
                    if (packChanged) {
                        h.ok("texture", "name", "item/dot", "palette",
                                List.of("a=#" + "%06x".formatted(++color)), "rows", List.of("a"));
                    }
                    String result = h.ok("dev_cycle", "project", "demo", "restart", restart);
                    assertThat(result).as("jar %s, restart %s, pack changed %s:%n%s", jar, restart, packChanged,
                            result).doesNotContain("resource pack").doesNotContain("pack again");
                }
            }
        }
        h.ok("server_stop");
    }

    /**
     * #667: dev_cycle with an unchanged jar sends the workspace's pack only when it differs from what the running
     * server's agent connection was last sent; a new connection (a restart) always gets it (#660).
     */
    @Test
    void anUnchangedPackIsSentOncePerConnection() throws Exception {
        assertThat(h.ok("dev_cycle", "project", "demo")).contains("server ready");
        h.ok("texture", "name", "item/dot", "palette", List.of("a=#ff0000"), "rows", List.of("a"));
        assertThat(h.ok("pack")).contains("applied: sent to 1 players");
        assertThat(packSends()).isEqualTo(1);

        for (int cycle = 0; cycle < 2; cycle++) {
            assertThat(h.ok("dev_cycle", "project", "demo")).as("cycle %d", cycle)
                    .contains("jar unchanged").doesNotContain("resource pack");
        }
        assertThat(packSends()).as("the same pack to the same connection").isEqualTo(1);

        // A reconnect: a changed jar restarts the server, and its new connection gets the pack.
        Files.writeString(project.resolve("version.txt"), "2.0");
        assertThat(h.ok("dev_cycle", "project", "demo")).contains("server ready")
                .contains("\nresource pack sent: players who join get it\n");
        assertThat(packSends()).isEqualTo(2);
        assertThat(h.ok("dev_cycle", "project", "demo")).contains("jar unchanged").doesNotContain("resource pack");
        assertThat(packSends()).isEqualTo(2);

        // A changed pack still reaches the players of a server that keeps running.
        h.ok("texture", "name", "item/dot", "palette", List.of("a=#00ff00"), "rows", List.of("a"));
        assertThat(h.ok("dev_cycle", "project", "demo")).contains("jar unchanged")
                .contains("\nresource pack sent: players who join get it\n");
        assertThat(packSends()).isEqualTo(3);

        // #681: restart=false keeps an unchanged jar the same way, and sends a changed pack too.
        h.ok("texture", "name", "item/dot", "palette", List.of("a=#0000ff"), "rows", List.of("a"));
        assertThat(h.ok("dev_cycle", "project", "demo", "restart", false)).contains("jar unchanged")
                .contains("\nresource pack sent: players who join get it\n").doesNotContain("old jar");
        assertThat(packSends()).isEqualTo(4);
        assertThat(h.ok("dev_cycle", "project", "demo")).contains("jar unchanged").doesNotContain("server ready")
                .doesNotContain("resource pack");
        h.ok("server_stop");
    }

    private int fences;

    /**
     * The packs the fake server was sent so far: its log is read up to a console line sent now, which it prints after
     * any send that already happened.
     */
    private long packSends() throws Exception {
        String fence = "fence " + ++fences;
        h.core.server().console(fence);
        awaitLogLine("console: " + fence);
        return h.core.server().log().since(0).stream().filter(l -> l.contains("[fake] pack sent ")).count();
    }

    /** Waits (generously: a loaded machine) until the server log holds a line containing {@code text}. */
    private void awaitLogLine(String text) throws InterruptedException {
        long deadline = System.nanoTime() + java.time.Duration.ofSeconds(60).toNanos();
        while (h.core.server().log().since(0).stream().noneMatch(l -> l.contains(text))) {
            assertThat(System.nanoTime()).as("no log line with " + text).isLessThan(deadline);
            Thread.sleep(10);
        }
    }

    @Test
    void failingTestsAreReportedFirstWhileTheNewJarAlreadyRuns() throws Exception {
        Files.writeString(project.resolve("failing-test.flag"), "");
        String result = h.ok("dev_cycle", "project", "demo");
        assertThat(result).startsWith("TESTS FAILED in ").contains("(the new jar is deployed anyway)")
                .contains("DemoTest > greets() FAILED").contains("deployed demo-1.0.jar as plugins/demo.jar")
                .contains("server ready");
        assertThat(h.core.server().running()).isTrue();
        h.ok("server_stop");
    }

    /**
     * #473: failed tests and a noisy start share dev_cycle's one budget: at every cap the result fits, the time line is
     * last, and each cut keeps its own hint, which names what shows the rest.
     */
    @Test
    void manyFailedTestsAndANoisyStartShareOneBudget() throws Exception {
        Files.writeString(project.resolve("many-failing-tests.flag"), "");
        Files.createDirectories(workspace.resolve("run"));
        Files.writeString(workspace.resolve("run/many-errors.flag"), "");
        int version = 1;
        for (int cap : new int[] {io.github.imnotstable.msc.core.tool.Budget.DEFAULT_CHARS, 200, 300, 24_000}) {
            Files.writeString(project.resolve("version.txt"), "1." + version++);
            String result = h.ok("dev_cycle", "project", "demo", "maxChars", cap);
            assertThat(result).as("maxChars %d", cap).hasSizeLessThanOrEqualTo(cap)
                    .containsPattern("\ntime: jar [^\n]*, total \\d+\\.\\ds$");
            assertHintsAreTrue(result, cap);
            if (cap == 300) {
                assertThat(result).as("dev_cycle's own lines cut with its own hint").contains(
                        "Failed tests: demo/build/test-results. Startup log: server_logs. Or raise maxChars");
            }
            if (cap > 300) {
                assertThat(result).as("maxChars %d", cap).startsWith("TESTS FAILED in ")
                        .contains("Fix the first errors and build again.]").contains("server ready")
                        .contains("Use server_logs with grep to narrow.]").doesNotContain("Narrow the call.");
            }
        }
        h.ok("server_stop");
        String start = h.ok("server_start", "timeoutSeconds", 60);
        assertThat(start).hasSizeLessThanOrEqualTo(io.github.imnotstable.msc.core.tool.Budget.DEFAULT_CHARS)
                .startsWith("server ready").endsWith("Use server_logs with grep to narrow.]");
        h.ok("server_stop");
    }

    /**
     * #620: every piece of advice in every cut line of a dev_cycle result (200 failed tests, 300 startup errors) is
     * followed here, and must show what that result left out: the failed tests it does not name are in the test
     * results file the hint names, the startup errors it does not show are what server_logs returns, and raising
     * maxChars is said only below the largest cap. A hint of dev_cycle's own (not a part's) names each of the two.
     */
    private void assertHintsAreTrue(String result, int cap) throws java.io.IOException {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                "\\[(?:truncated: \\d+ more lines, \\d+ chars\\.|\\d+ of \\d+ outputs cut to fit\\.) ([^\\]]*)\\]")
                .matcher(result);
        List<Integer> unshownTests = java.util.stream.IntStream.range(0, 200)
                .filter(i -> !result.contains("case" + i + "()")).boxed().toList();
        List<Integer> unshownErrors = java.util.stream.IntStream.range(0, 300)
                .filter(i -> !result.contains("part" + i + ":")).boxed().toList();
        String own = "Fix the first errors and build again.|Use server_logs with grep to narrow.";
        while (m.find()) {
            String hint = m.group(1);
            if (!hint.matches(own)) {
                assertThat(hint).as("maxChars %d: dev_cycle's hint names the test results", cap).contains("Failed tests: ");
                assertThat(hint).as("maxChars %d: dev_cycle's hint names the startup log", cap)
                        .contains("Startup log: server_logs.");
            }
            for (String advice : hint.split("(?<=\\.) ")) {
                if (advice.startsWith("Failed tests: ")) {
                    Path results = workspace.resolve(advice.substring("Failed tests: ".length(), advice.length() - 1));
                    String xml;
                    try (var files = Files.walk(results)) {
                        xml = files.filter(Files::isRegularFile).map(f -> {
                            try {
                                return Files.readString(f);
                            } catch (java.io.IOException e) {
                                throw new java.io.UncheckedIOException(e);
                            }
                        }).collect(java.util.stream.Collectors.joining());
                    }
                    assertThat(unshownTests).as("maxChars %d: %s holds every failed test the result left out", cap,
                            advice).allMatch(i -> xml.contains("\"case" + i + "()\""));
                } else if (advice.equals("Startup log: server_logs.") || advice.equals("Use server_logs with grep to narrow.")) {
                    for (int i : unshownErrors.isEmpty() ? List.<Integer>of()
                            : List.of(unshownErrors.getFirst(), unshownErrors.getLast())) {
                        assertThat(h.ok("server_logs", "mode", "grep", "pattern", "part" + i + ":"))
                                .as("maxChars %d: %s shows the startup error the result left out", cap, advice)
                                .contains("Could not load plugin part" + i + ":");
                    }
                } else if (advice.matches("(Or r|R)aise maxChars \\(at most 24000\\)\\.")) {
                    assertThat(cap).as("raise is said only below the largest maxChars").isLessThan(24_000);
                } else {
                    assertThat(advice).as("maxChars %d: advice this test can follow", cap)
                            .isEqualTo("Fix the first errors and build again.");
                }
            }
        }
    }

    @Test
    void statusShowsAServerAnEarlierSessionLeftRunning() throws Exception {
        Process leftover = new ProcessBuilder(java(io.github.imnotstable.msc.core.fixture.SleepMain.class)).start();
        try {
            long started = leftover.info().startInstant().orElseThrow().toEpochMilli();
            Files.createDirectories(workspace.resolve(".msc"));
            Files.writeString(workspace.resolve(".msc/server.pid"), leftover.pid() + " " + started + "\n");
            assertThat(h.ok("msc_status")).contains("server: running, left by an earlier session (pid " + leftover.pid());
            assertThat(leftover.isAlive()).isTrue();
            h.ok("server_stop");
            assertThat(h.ok("msc_status")).doesNotContain("server: running");
        } finally {
            leftover.destroyForcibly();
        }
        h.ok("server_start", "timeoutSeconds", 30);
        h.ok("server_detach");
        assertThat(h.ok("msc_status")).contains("server: running, detached");
        h.ok("server_stop");
    }

    @Test
    void everyToolThatNeedsTheServerNamesOneAnEarlierSessionLeftRunningAsMscStatusDoes() throws Exception {
        // #705: msc_status said running while world tools and server_command said "the server is not running".
        FakeLeftover leftover = FakeLeftover.install(h.core.server(), java.time.Duration.ofMillis(1));
        try {
            String pid = "pid " + leftover.pid();
            assertThat(h.ok("msc_status")).contains("server: running, left by an earlier session (" + pid);
            String why = "the server running here (" + pid + ") was left by an earlier session and has no connection "
                    + "to this one: server_start or dev_cycle restarts it under this session, server_stop stops it";
            assertThat(h.error("world_slice", "region", "0,60,0 1,60,1")).isEqualTo("error: " + why);
            assertThat(h.error("world_summary", "region", "0,60,0 1,60,1")).isEqualTo("error: " + why);
            assertThat(h.error("server_command", "command", "say hi")).isEqualTo("error: " + why);
            assertThat(h.error("server_detach")).isEqualTo("error: " + why);
            assertThat(h.error("player_test", "actions", "say hi")).isEqualTo("error: " + why);
            assertThat(h.error("server_wait", "until", "x")).contains("none is running; " + why);
            assertThat(leftover.isAlive()).as("a refusal changes nothing").isTrue();
        } finally {
            leftover.exit();
        }
        assertThat(h.error("world_slice", "region", "0,60,0 1,60,1"))
                .isEqualTo("error: " + io.github.imnotstable.msc.core.agent.AgentHub.NOT_RUNNING);
    }

    @Test
    void worldToolsFailAtOnceWhileTheServerStopsAfterItsAgentLeft() throws Exception {
        // #703: the agent left before the process exited, and a call in that window waited 30 s for an agent.
        Files.createDirectories(workspace.resolve("run"));
        Files.writeString(workspace.resolve("run/linger-stop.flag"), "");
        h.ok("server_start", "timeoutSeconds", 30);
        h.core.hub().connectWait(java.time.Duration.ofSeconds(60));
        var stop = java.util.concurrent.CompletableFuture.supplyAsync(() -> h.call("server_stop"));
        try {
            awaitLogLine("agent gone, lingering");
            long deadline = System.nanoTime() + java.time.Duration.ofSeconds(60).toNanos();
            while (h.core.hub().current() != null) {
                assertThat(System.nanoTime()).as("the agent leaves").isLessThan(deadline);
                Thread.sleep(10);
            }
            assertThat(h.core.server().running()).as("the process lingers").isTrue();
            long start = System.nanoTime();
            assertThat(h.error("world_summary", "region", "0,60,0 1,61,1")).contains("the server is stopping");
            assertThat(h.error("world_slice", "region", "0,60,0 1,60,1")).contains("the server is stopping");
            assertThat(java.time.Duration.ofNanos(System.nanoTime() - start)).isLessThan(java.time.Duration.ofSeconds(20));
        } finally {
            Files.writeString(workspace.resolve("run/release.flag"), "");
        }
        assertThat(stop.get(60, java.util.concurrent.TimeUnit.SECONDS).text()).isEqualTo("server stopped");
    }

    @Test
    void aWorldCallWhileARestartLaunchesWaitsForTheNewAgent() throws Exception {
        // #715: the last run's server.stopping made a world call during the next launch say "the server is stopping".
        h.core.hub().connectWait(java.time.Duration.ofSeconds(60));
        h.ok("server_start", "timeoutSeconds", 30);
        assertThat(h.ok("server_stop")).isEqualTo("server stopped");
        // The hub has seen the agent's server.stopping: an attached core would refuse at once.
        h.core.hub().agentExpected(() -> true);
        long deadline = System.nanoTime() + java.time.Duration.ofSeconds(20).toNanos();
        while (h.core.hub().refusal() == null) {
            assertThat(System.nanoTime()).as("server.stopping arrives").isLessThan(deadline);
            Thread.sleep(10);
        }
        h.core.hub().agentExpected(h.core.server()::awaitsAgent);
        // Another core holds the server lock, so the restart stops inside its launch, holding ServerManager's monitor.
        Process holder = io.github.imnotstable.msc.core.fixture.LockHoldMain.hold(workspace.resolve(".msc/server.lock"));
        var restart = new java.util.concurrent.CompletableFuture<Object>();
        String[] world = new String[1];
        Thread caller = new Thread(() -> world[0] = h.call("world_summary", "region", "0,60,0 1,61,1").text());
        try {
            Thread starter = Thread.ofPlatform().start(() -> {
                try {
                    restart.complete(h.core.server().start(java.time.Duration.ofSeconds(30)));
                } catch (Exception | Error e) {
                    restart.completeExceptionally(e);
                }
            });
            var threads = java.lang.management.ManagementFactory.getThreadMXBean();
            while (java.util.Arrays.stream(threads.getThreadInfo(new long[] {starter.threadId()}, true, false)[0]
                    .getLockedMonitors()).noneMatch(m -> m.getClassName().endsWith(".ServerManager"))) {
                assertThat(System.nanoTime()).as("the launch begins").isLessThan(deadline);
                assertThat(restart).isNotDone();
                Thread.sleep(10);
            }
            caller.start();
            while (caller.getState() != Thread.State.BLOCKED) {
                assertThat(System.nanoTime()).as("the world call waits for the launch").isLessThan(deadline);
                assertThat(caller.isAlive()).as("answered " + world[0]).isTrue();
                Thread.sleep(10);
            }
        } finally {
            holder.destroy();
        }
        caller.join(java.time.Duration.ofSeconds(60));
        assertThat(world[0]).as("waits for the new run's agent").contains("blocks: stone 8");
        assertThat(((io.github.imnotstable.msc.core.server.ServerManager.StartResult)
                restart.get(60, java.util.concurrent.TimeUnit.SECONDS)).ready()).isTrue();
    }

    @Test
    void serverCommandWhileTheServerStopsIsRefusedAsTheWorldToolsAre() throws Exception {
        // #716: the server closed its console on stop and server_command failed with an internal broken pipe.
        Files.createDirectories(workspace.resolve("run"));
        Files.writeString(workspace.resolve("run/close-console-stop.flag"), "");
        String stopping = "error: " + io.github.imnotstable.msc.core.agent.AgentHub.STOPPING;
        for (String stop : List.of("server_stop", "a stop typed into the server")) {
            Files.deleteIfExists(workspace.resolve("run/release.flag"));
            h.ok("server_start", "timeoutSeconds", 30);
            long mark = h.core.server().log().mark();
            var stopped = stop.equals("server_stop")
                    ? java.util.concurrent.CompletableFuture.supplyAsync(() -> h.call("server_stop")) : null;
            if (stopped == null) {
                h.core.server().console("stop"); // MSC's stop not asked: the closed console is the only sign
            }
            try {
                // This run's line: the fake prints it once its stdin is closed.
                long deadline = System.nanoTime() + java.time.Duration.ofSeconds(60).toNanos();
                while (h.core.server().log().since(mark).stream().noneMatch(l -> l.contains("console closed"))) {
                    assertThat(System.nanoTime()).as("the console closes").isLessThan(deadline);
                    Thread.sleep(10);
                }
                deadline = System.nanoTime() + java.time.Duration.ofSeconds(20).toNanos();
                while (h.core.hub().current() != null) {
                    assertThat(System.nanoTime()).as("the agent leaves").isLessThan(deadline);
                    Thread.sleep(10);
                }
                assertThat(h.core.server().running()).as("the process lingers").isTrue();
                assertThat(h.error("server_command", "command", "list")).as(stop).isEqualTo(stopping);
                assertThat(h.error("world_summary", "region", "0,60,0 1,61,1")).as(stop).isEqualTo(stopping);
                assertThat(h.error("build_undo", "backup", "save")).as(stop).isEqualTo(stopping);
            } finally {
                Files.writeString(workspace.resolve("run/release.flag"), "");
            }
            if (stopped != null) {
                assertThat(stopped.get(60, java.util.concurrent.TimeUnit.SECONDS).text()).isEqualTo("server stopped");
            } else {
                long deadline = System.nanoTime() + java.time.Duration.ofSeconds(60).toNanos();
                while (h.core.server().running()) {
                    assertThat(System.nanoTime()).as("the server exits").isLessThan(deadline);
                    Thread.sleep(10);
                }
            }
            assertThat(h.error("server_command", "command", "list")).as(stop)
                    .isEqualTo("error: " + io.github.imnotstable.msc.core.agent.AgentHub.NOT_RUNNING);
        }
    }

    @Test
    void everyToolThatNeedsTheServerGivesOneAnswerWhileItStops() throws Exception {
        // #723: server_detach said the server keeps running (and started a pack host), msc_status said "running,
        // detached" and server_command typed into the console, while the world tools said "the server is stopping".
        java.util.Map<String, Object[]> table = new java.util.LinkedHashMap<>();
        table.put("world_slice", new Object[] {"region", "0,60,0 1,60,1"});
        table.put("world_heightmap", new Object[] {"area", "0,0 1,1"});
        table.put("world_render", new Object[] {"region", "0,60,0 1,60,1"});
        table.put("world_summary", new Object[] {"region", "0,60,0 1,61,1"});
        table.put("world_entities", new Object[] {"region", "0,60,0 1,61,1"});
        table.put("build_undo", new Object[] {"backup", "save"});
        table.put("structure_save", new Object[] {"region", "0,60,0 1,60,1", "file", "s.schem"});
        table.put("build_batch", new Object[] {"steps", "fill 0,64,0 1,64,1 stone"});
        table.put("player_test", new Object[] {"actions", List.of("say hi")});
        table.put("server_command", new Object[] {"command", "list"});
        table.put("server_detach", new Object[] {});
        assertThat(table.keySet()).as("every world tool is in the table").containsAll(Core.WORLD_TOOLS);
        String stopping = "error: " + io.github.imnotstable.msc.core.agent.AgentHub.STOPPING;
        Path run = Files.createDirectories(workspace.resolve("run"));
        h.ok("server_start", "timeoutSeconds", 30);
        h.ok("texture", "name", "item/dot", "palette", List.of("a=#ff0000"), "rows", List.of("a"));
        h.ok("pack", "apply", false); // a built pack: a detach would hand it to a standalone host
        h.ok("server_stop");
        for (String way : List.of("server_stop", "a stop the agent announced")) {
            boolean ours = way.equals("server_stop");
            Files.deleteIfExists(run.resolve("release.flag"));
            Files.deleteIfExists(run.resolve("hold-stop.flag"));
            Files.deleteIfExists(run.resolve("announce-stop.flag"));
            Files.writeString(run.resolve(ours ? "hold-stop.flag" : "announce-stop.flag"), "");
            h.ok("server_start", "timeoutSeconds", 30);
            long mark = h.core.server().log().mark();
            var stopped = ours ? java.util.concurrent.CompletableFuture.supplyAsync(() -> h.call("server_stop")) : null;
            if (!ours) {
                h.core.server().console("stop"); // typed into the server: only its agent tells MSC
            }
            try {
                long deadline = System.nanoTime() + java.time.Duration.ofSeconds(20).toNanos();
                while (h.core.server().log().since(mark).stream().noneMatch(l -> l.contains("lingering"))
                        || !ours && h.core.hub().current() != null) {
                    assertThat(System.nanoTime()).as(way + ": the server begins to stop").isLessThan(deadline);
                    Thread.sleep(10);
                }
                assertThat(h.core.server().running()).as(way + ": the process lingers").isTrue();
                assertThat(h.core.hub().current() != null).as(way + ": its agent is still connected").isEqualTo(ours);
                java.util.Map<String, String> answers = new java.util.LinkedHashMap<>();
                table.forEach((tool, args) -> {
                    if (ours || !tool.equals("server_command")) {
                        answers.put(tool, h.error(tool, args));
                    }
                });
                assertThat(answers).as(way).allSatisfy((tool, answer) -> assertThat(answer).as(way + ": " + tool)
                        .isEqualTo(stopping));
                if (!ours) {
                    // #729: an announced stop is a hint, as the server may run on; its open console takes commands.
                    assertThat(h.ok("server_command", "command", "list")).isEqualTo("sent (no output within 0.7s)");
                }
                assertThat(h.ok("msc_status")).as(way).contains("\nserver: stopping (");
                assertThat(h.error("server_start")).as(way).isEqualTo("error: the server is still stopping: "
                        + "server_stop waits until it has exited, then server_start starts it again.");
                assertThat(h.core.server().detached()).as(way + ": nothing detached").isFalse();
                assertThat(workspace.resolve(".msc/pack-host.pid")).as(way + ": no pack host").doesNotExist();
            } finally {
                Files.writeString(run.resolve("release.flag"), "");
            }
            if (ours) {
                assertThat(stopped.get(60, java.util.concurrent.TimeUnit.SECONDS).text()).isEqualTo("server stopped");
            } else {
                // "stop" is typed (#729), into a console nothing reads any more: the server exits as it was going to.
                assertThat(h.ok("server_stop")).isEqualTo("server stopped");
            }
            assertThat(h.error("server_detach")).as(way).isEqualTo("error: "
                    + io.github.imnotstable.msc.core.agent.AgentHub.NOT_RUNNING);
        }
    }

    @Test
    void anAgentThatAnnouncesAStopWhileItsServerRunsOnLeavesTheServerToAGracefulStop() throws Exception {
        // #729: an agent says server.stopping also when only the agent stops (Paper's /reload, a plugin manager,
        // Minestom's MscAgent.stop()). server_stop then skipped "stop", waited 30 s and killed the server unsaved, and
        // server_command and server_start refused a live server until then.
        for (String way : List.of("announce", "unload", "reload")) {
            h.ok("server_start", "timeoutSeconds", 30);
            h.core.server().console(way.equals("reload") ? "unload" : way);
            until(way, () -> h.core.server().reportsStopping() && (way.equals("announce") || h.core.hub().current() == null));
            assertThat(h.ok("msc_status")).as(way).contains("\nserver: stopping (");
            if (way.equals("reload")) {
                assertThat(h.ok("server_command", "command", "load")).as(way).contains("console: load");
                until(way, () -> h.core.hub().current() != null && !h.core.server().reportsStopping());
                assertThat(h.ok("msc_status")).as(way).contains("\nserver: running (");
                assertThat(h.ok("world_summary", "region", "0,60,0 1,61,1")).as(way).contains("blocks: stone 8");
            } else {
                assertThat(h.ok("server_command", "command", "say still here")).as(way)
                        .contains("console: say still here");
            }
            long mark = h.core.server().log().mark();
            long begin = System.nanoTime();
            assertThat(h.ok("server_stop")).as(way).isEqualTo("server stopped");
            assertThat(java.time.Duration.ofNanos(System.nanoTime() - begin)).as(way + ": not waited out")
                    .isLessThan(java.time.Duration.ofSeconds(20));
            assertThat(h.core.server().log().since(mark)).as(way + ": stopped by its own stop, so saved")
                    .anyMatch(l -> l.contains("Stopping server"));
            assertThat(h.core.server().running()).as(way).isFalse();
        }
    }

    private static void until(String what, java.util.function.BooleanSupplier done) throws InterruptedException {
        long deadline = System.nanoTime() + java.time.Duration.ofSeconds(20).toNanos();
        while (!done.getAsBoolean()) {
            assertThat(System.nanoTime()).as(what).isLessThan(deadline);
            Thread.sleep(10);
        }
    }

    @Test
    void anAttachedCoreSaysNotRunningOnceTheStoppedServerIsGone() throws Exception {
        // #711: with MSC_CORE_ADDRESS set an agent is always expected (Core leaves agentExpected true), and the
        // server.stopping its agent sent made every world tool say "stopping" after server_stop said "stopped".
        h.core.hub().agentExpected(() -> true);
        h.core.hub().connectWait(java.time.Duration.ofSeconds(60));
        for (String stop : List.of("server_stop", "a stop typed into the server")) {
            h.ok("server_start", "timeoutSeconds", 30);
            if (stop.equals("server_stop")) {
                assertThat(h.ok("server_stop")).isEqualTo("server stopped");
            } else {
                h.core.server().console("stop"); // not through MSC's stop: only the agent says server.stopping
            }
            long deadline = System.nanoTime() + java.time.Duration.ofSeconds(20).toNanos();
            while (h.core.hub().current() != null || h.core.server().running()) {
                assertThat(System.nanoTime()).as("the server is gone").isLessThan(deadline);
                Thread.sleep(10);
            }
            long start = System.nanoTime();
            assertThat(h.ok("msc_status")).as(stop).doesNotContain("server: running");
            assertThat(h.error("world_summary", "region", "0,60,0 1,61,1")).as(stop)
                    .isEqualTo("error: " + io.github.imnotstable.msc.core.agent.AgentHub.NOT_RUNNING);
            assertThat(java.time.Duration.ofNanos(System.nanoTime() - start)).isLessThan(java.time.Duration.ofSeconds(20));
        }
    }

    @Test
    void aCallWaitingForTheAgentFailsAsSoonAsTheServerExits() throws Exception {
        // #703: only a session coming or going woke the wait, never the process exit.
        Files.createDirectories(workspace.resolve("run"));
        Files.writeString(workspace.resolve("run/no-agent.flag"), "");
        h.ok("server_start", "timeoutSeconds", 30);
        h.core.hub().connectWait(java.time.Duration.ofSeconds(60));
        String[] answer = new String[1];
        Thread caller = new Thread(() -> answer[0] = h.error("world_summary", "region", "0,60,0 1,61,1"));
        long start = System.nanoTime();
        caller.start();
        long deadline = System.nanoTime() + java.time.Duration.ofSeconds(20).toNanos();
        while (caller.getState() != Thread.State.TIMED_WAITING && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        ((Process) h.core.server().serverRun()).destroyForcibly();
        caller.join(java.time.Duration.ofSeconds(60));
        assertThat(caller.isAlive()).isFalse();
        assertThat(answer[0]).contains("the server is not running");
        assertThat(java.time.Duration.ofNanos(System.nanoTime() - start)).isLessThan(java.time.Duration.ofSeconds(40));
    }

    @Test
    void aServerAnotherLiveSessionRunsIsNeitherStoppedNorReplaced() throws Exception {
        // #482: a second session on the workspace took the first one's live server for a leftover and killed it.
        Process other = new ProcessBuilder(java(io.github.imnotstable.msc.core.fixture.SleepMain.class)).start();
        try {
            Path pidFile = Files.createDirectories(workspace.resolve(".msc")).resolve("server.pid");
            // The other session's core stands as its own server: both alive, neither this JVM.
            PidFileAccess.write(pidFile, other.toHandle(), PidFile.Identity.of(other.toHandle()));
            String written = Files.readString(pidFile);
            String pids = "(server pid " + other.pid() + ", MSC core pid " + other.pid() + ";";
            assertThat(h.ok("msc_status")).contains("server: running under another session that is still running " + pids);
            for (String tool : List.of("server_start", "server_stop", "server_setup")) {
                assertThat(h.call(tool).text()).as(tool).contains("belongs to another session that is still running "
                        + "(MSC core pid " + other.pid() + "): use that session, or end it");
            }
            assertThat(other.isAlive()).isTrue();
            assertThat(pidFile).content().isEqualTo(written);
            // Its core killed: the server is a leftover, as before (ADR 0013 point 8).
            other.destroyForcibly().waitFor();
            assertThat(h.ok("msc_status")).doesNotContain("another session");
        } finally {
            other.destroyForcibly();
        }
    }

    @Test
    void aStartWaitsForAnAgentThatConnectsAfterDone() throws Exception {
        Files.createDirectories(workspace.resolve("run/plugins"));
        Files.writeString(workspace.resolve("run/plugins/msc-agent-paper.jar"), "agent");
        Files.writeString(workspace.resolve("run/late-agent.flag"), "");
        assertThat(h.ok("server_start", "timeoutSeconds", 30)).contains(", agent connected");
        h.ok("server_stop");
        Files.delete(workspace.resolve("run/plugins/msc-agent-paper.jar"));
        // No agent installed: nothing to wait for, so the start reports at "Done".
        assertThat(h.ok("server_start", "timeoutSeconds", 30)).contains("no agent connected");
        h.ok("server_stop");
    }

    @Test
    void aServerThatDiesRightAfterDoneIsReportedAsExitedWithItsTrace() throws Exception {
        Files.createDirectories(workspace.resolve("run/plugins"));
        Files.writeString(workspace.resolve("run/plugins/msc-agent-paper.jar"), "agent");
        Files.writeString(workspace.resolve("run/done-crash.flag"), "");
        String fix = "  fix: fix the code at Demo.java:30 (com.example.demo.Demo.tick)\n";
        assertThat(h.ok("server_start", "timeoutSeconds", 30)).startsWith("server exited right after it was ready, after ")
                .contains("s\nfatal exception in plugin or mod code [log 12:00:03]\n  culprit: com.example.demo.Demo.tick\n")
                .contains(fix)
                .contains("12:00:03 ERROR Encountered an unexpected exception\njava.lang.IllegalStateException: tick failed")
                .doesNotContain("server ready");
        // The same triage on its own, with the crash report the server wrote; the report adds its file name.
        Files.createDirectories(workspace.resolve("run/crash-reports"));
        Files.writeString(workspace.resolve("run/crash-reports/crash-1-server.txt"), "Description: Ticking world\n\n"
                + "java.lang.IllegalStateException: tick failed\n\tat com.example.demo.Demo.tick(Demo.java:30)\n");
        assertThat(h.ok("server_logs", "mode", "triage")).startsWith("crash report: Ticking world "
                + "[crash-reports/crash-1-server.txt, log 12:00:03]").endsWith(fix.strip());
        Files.delete(workspace.resolve("run/done-crash.flag"));
        h.ok("server_start", "timeoutSeconds", 30);
        try {
            // The crash report is from before this start, so it is not this server's problem.
            assertThat(h.ok("server_logs", "mode", "triage")).startsWith("no crash or startup failure found in ")
                    .endsWith("; server running");
        } finally {
            h.ok("server_stop");
        }
    }

    @Test
    void devCycleDeploysAFabricModIntoMods() throws Exception {
        Files.createDirectories(project.resolve("src/main/resources"));
        Files.writeString(project.resolve("src/main/resources/fabric.mod.json"), "{}");
        assertThat(h.ok("dev_cycle", "project", "demo", "restart", false)).contains("deployed demo-1.0.jar as mods/demo.jar");
        assertThat(workspace.resolve("run/mods/demo.jar")).hasContent("1.0");
        assertThat(workspace.resolve("run/plugins")).doesNotExist();
        assertThat(workspace.resolve(".msc/platform")).hasContent("fabric\n");
    }

    @Test
    void startPicksUpARebuiltAgentJar() throws Exception {
        Path agents = Files.createDirectories(workspace.resolve(".msc/agent"));
        Files.writeString(agents.resolve("msc-agent-paper-0.2.jar"), "new agent");
        Path installed = Files.createDirectories(workspace.resolve("run/plugins")).resolve("msc-agent-paper.jar");
        Files.writeString(installed, "old agent");
        assertThat(h.ok("server_start", "timeoutSeconds", 30)).contains("agent connected, agent jar updated");
        assertThat(installed).hasContent("new agent");
        h.ok("server_stop");
        assertThat(h.ok("server_start", "timeoutSeconds", 30)).doesNotContain("agent jar updated");
    }

    @Test
    void everyCallIsLoggedForTheBenchmark() throws Exception {
        h.ok("msc_status");
        h.error("server_logs", "mode", "bogus");
        h.error("server_command", "command", "say hi");
        List<String> lines = Files.readAllLines(workspace.resolve("calls.jsonl"));
        assertThat(lines).hasSize(3);
        JsonObject ok = JsonParser.parseString(lines.get(0)).getAsJsonObject();
        assertThat(ok.keySet()).containsExactly("v", "ts", "tool", "ok", "durationMs", "argsChars", "resultChars", "resultTokens");
        assertThat(ok.get("tool").getAsString()).isEqualTo("msc_status");
        assertThat(ok.get("ok").getAsBoolean()).isTrue();
        assertThat(ok.get("resultTokens").getAsInt()).isEqualTo((ok.get("resultChars").getAsInt() + 3) / 4);
        JsonObject failed = JsonParser.parseString(lines.get(2)).getAsJsonObject();
        assertThat(failed.get("ok").getAsBoolean()).isFalse();
        assertThat(failed.get("error").getAsString())
                .isEqualTo("error: " + io.github.imnotstable.msc.core.agent.AgentHub.NOT_RUNNING);
        assertThat(h.ok("msc_status", "stats", true)).startsWith("tool calls errors ms resultTokens\nmsc_status 1 0 ")
                .contains("\nserver_command 1 1 ").contains("\ntotal 3 2 ");
    }

    /**
     * A server_setup that fails (a version the platform's pinned loader does not cover, a download that fails) leaves
     * the workspace as it was: its platform, its run/ folder and so its working server (#356).
     */
    @Test
    void aFailedSetupLeavesTheWorkspaceAsItWas() throws Exception {
        String api = io.github.imnotstable.msc.templates.Templates.versions().get("fabricApiVersion");
        String minecraft = api.substring(api.indexOf('+') + 1);
        // Every download fails, as offline.
        HttpServer offline = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        offline.createContext("/", exchange -> {
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
        });
        offline.start();
        String base = "http://127.0.0.1:" + offline.getAddress().getPort();
        Path ws = Files.createDirectories(workspace.resolve("setup-fails"));
        Path run = ws.resolve("run");
        Files.createDirectories(run.resolve("plugins"));
        Files.writeString(run.resolve("paper.jar"), "paper");
        Files.writeString(run.resolve("plugins/msc-agent-paper.jar"), "agent");
        Files.writeString(run.resolve("eula.txt"), "eula=false\n");
        Files.writeString(run.resolve("server.properties"), "gamemode=creative\n");
        Files.createDirectories(ws.resolve(".msc"));
        Files.writeString(ws.resolve(".msc/platform"), "paper\n");
        Path fresh = Files.createDirectories(workspace.resolve("setup-fails-fresh"));
        List<List<Object>> calls = List.of(
                List.of("platform", "fabric", "version", "1.0"),
                List.of("platform", "quilt", "version", "1.0"),
                List.of("platform", "neoforge", "version", "1.0"),
                List.of("platform", "forge", "version", "1.0"),
                List.of("platform", "fabric"),
                List.of("platform", "quilt", "mode", "game"),
                List.of("platform", "fabric", "mode", "game"),
                List.of("version", "1.0", "mode", "game"),
                List.of("mode", "game"));
        try (CoreHarness s = new CoreHarness(ws, c -> c.withFabricUrls(base + "/meta", base + "/maven")
                .withDownloadsUrl(base).withMinecraft(minecraft));
             CoreHarness f = new CoreHarness(fresh, c -> c.withFabricUrls(base + "/meta", base + "/maven")
                     .withDownloadsUrl(base).withMinecraft(minecraft))) {
            var before = TreeSnapshot.of(run);
            for (List<Object> call : calls) {
                s.error("server_setup", call.toArray());
                assertThat(TreeSnapshot.of(run)).as("run/ after %s", call).isEqualTo(before);
                assertThat(ws.resolve(".msc/platform")).as("platform after %s", call).hasContent("paper\n");
                assertThat(s.ok("msc_status")).as("status after %s", call).contains("platform: paper\n")
                        .contains("server: stopped");
                f.error("server_setup", call.toArray());
                assertThat(fresh.resolve("run")).as("fresh run/ after %s", call).doesNotExist();
                assertThat(fresh.resolve(".msc/platform")).as("fresh platform after %s", call).doesNotExist();
            }
        } finally {
            offline.stop(0);
        }
    }

    /** dev_cycle remembers a fresh workspace's platform only once it deployed the project's jar (#356). */
    @Test
    void aFailedDevCycleDoesNotSetTheWorkspacePlatform() throws Exception {
        Files.createDirectories(project.resolve("src/main/resources"));
        Files.writeString(project.resolve("src/main/resources/fabric.mod.json"), "{}");
        Files.writeString(project.resolve("broken.flag"), "");
        assertThat(h.ok("dev_cycle", "project", "demo", "restart", false)).startsWith("BUILD FAILED in ");
        assertThat(workspace.resolve(".msc/platform")).doesNotExist();
        Files.delete(project.resolve("broken.flag"));
        // A jar named as the agent is refused before anything is deployed.
        Files.writeString(project.resolve("name.txt"), "msc-agent-fabric");
        assertThat(h.error("dev_cycle", "project", "demo", "restart", false)).contains("MSC's agent jar");
        assertThat(workspace.resolve(".msc/platform")).doesNotExist();
        Files.delete(project.resolve("name.txt"));
        assertThat(h.ok("dev_cycle", "project", "demo", "restart", false)).contains("deployed demo-1.0.jar as mods/demo.jar");
        assertThat(workspace.resolve(".msc/platform")).hasContent("fabric\n");
    }
}
