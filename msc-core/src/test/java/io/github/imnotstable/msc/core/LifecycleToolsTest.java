package io.github.imnotstable.msc.core;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import io.github.imnotstable.msc.core.fixture.FakeBuildMain;
import io.github.imnotstable.msc.core.fixture.FakeServerMain;
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
                .startsWith("server ready in ").endsWith("s, agent connected\nno errors or warnings");
        assertThat(h.ok("msc_status")).contains("server: running").contains("agent: paper fake, minecraft 26.3, 9 operations");
        assertThat(h.ok("world_summary", "region", "0,60,0 1,61,1")).contains("blocks: stone 8");
        assertThat(h.ok("server_command", "command", "/say hi")).isEqualTo("ran say hi");
        assertThat(h.ok("server_logs", "mode", "tail", "lines", 1)).contains("Done (0.1s)!");
        assertThat(h.ok("server_logs", "mode", "grep", "pattern", "starting")).contains("Starting minecraft server");
        assertThat(h.ok("server_logs")).endsWith("lines, 0 errors, 0 warnings");
        assertThat(h.error("server_start")).contains("already running");
        assertThat(h.ok("server_stop")).isEqualTo("server stopped");
        assertThat(h.ok("server_stop")).isEqualTo("server was not running");
        assertThat(h.core.server().running()).isFalse();
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
                .contains("agent connected").endsWith("no errors or warnings");
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
    void scaffoldCreatesAProjectOnceAndRefusesToOverwrite() {
        assertThat(h.ok("project_scaffold", "name", "MyPlugin", "dir", "fresh"))
                .startsWith("created paper-plugin project in ").doesNotContain("assword").doesNotContain("eula")
                .endsWith("next: edit the code, then dev_cycle");
        assertThat(workspace.resolve("fresh")).isNotEmptyDirectory();
        assertThat(h.error("project_scaffold", "name", "MyPlugin", "dir", "fresh")).startsWith("error: ");
        assertThat(h.error("project_scaffold", "name", "X", "template", "nope")).contains("unknown template nope");
        assertThat(h.error("project_scaffold")).isEqualTo("error: missing argument: name");
    }

    @Test
    void scaffoldIntoTheWorkspaceIgnoresTheCoresOwnDataFolder() throws Exception {
        Path ws = Files.createDirectories(workspace.resolve("ws"));
        Files.createDirectories(ws.resolve(".msc"));
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
    void startFailsFastWithoutTheEula() throws Exception {
        Path ws = Files.createDirectories(workspace.resolve("ws3"));
        Files.createDirectories(ws.resolve("run"));
        Files.writeString(ws.resolve("run/paper.jar"), "not a jar");
        try (CoreHarness s = new CoreHarness(ws)) {
            assertThat(s.error("server_start")).contains("Minecraft EULA is not accepted").contains("acceptEula=true")
                    .doesNotContain("\n");
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
            assertThat(s.error("server_setup", "platform", "forge")).contains("unknown platform forge")
                    .contains("paper, fabric, minestom");
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
        assertThat(failed.get("error").getAsString()).isEqualTo("error: server is not running");
        assertThat(h.ok("msc_stats")).startsWith("tool calls errors ms resultTokens\nmsc_status 1 0 ")
                .contains("\nserver_command 1 1 ").contains("\ntotal 3 2 ");
    }
}
