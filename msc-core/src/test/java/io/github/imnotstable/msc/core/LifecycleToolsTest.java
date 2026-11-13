package io.github.imnotstable.msc.core;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import io.github.imnotstable.msc.core.fixture.FakeBuildMain;
import io.github.imnotstable.msc.core.fixture.FakeServerMain;
import io.github.imnotstable.msc.core.server.MachineEulaAccess;
import io.github.imnotstable.msc.core.tools.LifecycleToolsAccess;
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
        assertThat(h.ok("server_start", "timeoutSeconds", 30)).contains("\nresource pack sent again: players who join get it\n");
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
        assertThat(workspace.resolve(".msc/server.pid")).content().matches("\\d+ \\d+\n");
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
    void aFailedDeployStopsTheTestsRunningBesideIt() throws Exception {
        Path ws = Files.createDirectories(workspace.resolve("ws9"));
        Path slow = Files.createDirectories(ws.resolve("slow"));
        Files.writeString(slow.resolve("version.txt"), "1.0");
        Files.writeString(slow.resolve("slow-check.flag"), "");
        Files.createDirectories(ws.resolve("run"));
        Files.writeString(ws.resolve("run/paper.jar"), "not a jar");
        try (CoreHarness s = new CoreHarness(ws, c -> c.withBuildCommand(java(FakeBuildMain.class)))) {
            assertThat(s.error("dev_cycle", "project", "slow")).contains("EULA");
            Path started = slow.resolve("check-started");
            for (int i = 0; i < 50 && !Files.exists(started); i++) {
                Thread.sleep(100);
            }
            // The check may be stopped before its process starts; once started, it must not outlive the failure.
            if (Files.exists(started)) {
                ProcessHandle check = ProcessHandle.of(Long.parseLong(Files.readString(started).strip())).orElse(null);
                for (int i = 0; i < 100 && check != null && check.isAlive(); i++) {
                    Thread.sleep(100);
                }
                assertThat(check == null || !check.isAlive()).isTrue();
            }
        }
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
    }

    @Test
    void scaffoldCreatesAProjectOnceAndRefusesToOverwrite() throws Exception {
        assertThat(h.ok("project_scaffold", "name", "MyPlugin", "dir", "fresh"))
                .startsWith("created paper-plugin project in ").doesNotContain("assword").doesNotContain("eula=")
                .contains("next: ask the user exactly: \"To run a Minecraft server you must agree to the Minecraft "
                        + "EULA (https://aka.ms/MinecraftEULA). Do you agree to it? (yes/no)\"")
                .endsWith("On yes: server_setup acceptEula=true, then edit the code and dev_cycle");
        Files.createDirectories(workspace.resolve("run"));
        Files.writeString(workspace.resolve("run/paper.jar"), "set up");
        assertThat(h.ok("project_scaffold", "name", "Other", "dir", "second"))
                .endsWith("next: edit the code, then dev_cycle");
        assertThat(workspace.resolve("fresh")).isNotEmptyDirectory();
        assertThat(h.error("project_scaffold", "name", "MyPlugin", "dir", "fresh")).startsWith("error: ");
        assertThat(h.error("project_scaffold", "name", "X", "template", "nope")).contains("unknown template nope");
        assertThat(h.ok("project_scaffold", "name", "Arena", "dir", "game", "template", "paper-game"))
                .startsWith("created paper-game project in ").contains("packageName: com.example.arena");
        assertThat(workspace.resolve("game/src/main/java/com/example/arena/GameCommand.java")).isRegularFile();
        assertThat(h.error("project_scaffold")).isEqualTo("error: missing argument: name");
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
        try (var src = Files.walk(ws.resolve("src"))) {
            assertThat(src.filter(p -> p.toString().endsWith("Test.java"))).hasSize(2);
        }
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
    void scaffoldAndStartUseTheMachineWideAcceptance() throws Exception {
        Path machineEula = MachineEulaAccess.accepted(workspace.resolve("home/.msc/eula.json"));
        Path ws = Files.createDirectories(workspace.resolve("ws5"));
        try (CoreHarness s = new CoreHarness(ws, c -> c.withEulaFile(machineEula))) {
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
        try (CoreHarness s = new CoreHarness(ws, c -> c.withEulaFile(machineEula))) {
            assertThat(s.error("server_start")).contains("EULA is not accepted");
            assertThat(machineEula).doesNotExist();
            assertThat(s.call("server_start", "acceptEula", true, "timeoutSeconds", 20).text())
                    .startsWith("Minecraft EULA (https://aka.ms/MinecraftEULA) accepted for this machine, saved in ")
                    .contains("TERMS.md").contains("every Paper, Purpur, Fabric, Quilt, NeoForge and Forge server");
            // The notice is shown once.
            assertThat(s.call("server_start", "acceptEula", true, "timeoutSeconds", 20).text()).doesNotContain("TERMS.md");
            assertThat(ws.resolve("run/eula.txt")).content().contains("eula=true");
            assertThat(machineEula).content().contains("\"how\": \"the user's own yes, passed as acceptEula=true\"");
        }
        Path other = Files.createDirectories(workspace.resolve("ws7/run")).getParent();
        Files.writeString(other.resolve("run/paper.jar"), "not a jar");
        try (CoreHarness s = new CoreHarness(other, c -> c.withEulaFile(machineEula))) {
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
        Path machineEula = MachineEulaAccess.accepted(workspace.resolve("home/.msc/eula.json"));
        try (CoreHarness s = new CoreHarness(workspace.resolve("ws4"), c -> c.withDownloadsUrl(base).withInstallDir(install)
                .withEulaFile(machineEula))) {
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
                    .contains("EULA is not accepted").endsWith("next: server_start or dev_cycle");
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
                    .contains("paper, purpur, fabric, quilt, neoforge, forge, minestom");
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
        assertThat(failed.get("error").getAsString()).isEqualTo("error: server is not running");
        assertThat(h.ok("msc_status", "stats", true)).startsWith("tool calls errors ms resultTokens\nmsc_status 1 0 ")
                .contains("\nserver_command 1 1 ").contains("\ntotal 3 2 ");
    }
}
