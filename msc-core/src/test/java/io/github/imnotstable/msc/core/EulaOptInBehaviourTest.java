package io.github.imnotstable.msc.core;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.imnotstable.msc.core.fixture.FakeBuildMain;
import io.github.imnotstable.msc.core.fixture.FakeServerMain;
import io.github.imnotstable.msc.core.server.EulaCommand;
import io.github.imnotstable.msc.core.server.MachineEula;
import io.github.imnotstable.msc.core.server.Platform;
import io.github.imnotstable.msc.core.tool.Tool;
import io.github.imnotstable.msc.core.tool.ToolRegistry;
import io.github.imnotstable.msc.process.ClosedPort;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * ADR 0020's promise, checked by running the product (ADR 0036): installing or using MSC alone never accepts the
 * Minecraft EULA. Every tool, the {@code eula} command short of a typed yes and the other {@code Main} subcommand run
 * against a temporary home and workspace with fake builds, fake servers and fake downloads; afterwards there is no
 * machine acceptance under the home and no {@code eula.txt} in the workspace says {@code eula=true}. Each tool row's
 * outcome is asserted too, so a row that stops before the tool's own work shows. Then each
 * documented opt-in that runs in-process does write it, so the check is not passing for an unrelated reason.
 * installMsc's {@code -Pmsc.acceptEula=true} runs in Gradle and is pinned by EulaWritersTest instead.
 */
class EulaOptInBehaviourTest {
    private static final List<String> REGION = List.of("region", "0,62,0 3,66,3");

    /** One tool call with no opt-in. Several rows may call one tool. */
    private record Row(String tool, Object... args) {
    }

    /**
     * The calls with the real server start path, where the EULA gate and the machine acceptance are applied: a
     * Paper server whose jar is a fake download, so nothing could run even if the gate let it through.
     */
    private static final List<Row> GATED = List.of(
            new Row("msc_status"), new Row("msc_status", "stats", true), new Row("msc_workspace"),
            new Row("project_scaffold", "name", "Plug", "dir", "plug"),
            new Row("project_scaffold", "name", "Srv", "dir", "srv", "template", "paper-server"),
            new Row("project_scaffold", "name", "Srv", "dir", "srv2", "template", "paper-server", "acceptEula", false),
            new Row("server_setup", "platform", "paper"), new Row("server_setup", "acceptEula", false),
            new Row("plugins"), new Row("server_start", "timeoutSeconds", 60), new Row("server_start", "acceptEula", false),
            new Row("dev_cycle", "project", "demo"), new Row("world_create", "name", "w"), new Row("server_stop"),
            new Row("server_export", "dir", "export/gated"));

    /**
     * The calls that need a running server and an agent: a Minestom workspace (no EULA) with a stand-in server
     * process ({@link FakeServerMain}) and a real API jar (JUnit's), so every world, build, pack and API tool does its
     * work. player_test runs its checks and its agent call, then stops at the join: the game port is held closed
     * ({@link CoreHarness#noGameServer}), since a test player must never join a real server on this machine. That is
     * as far as MSC's own code goes: a joined player only talks to the server, and writes no file.
     */
    private static final List<Row> RUNNING = List.of(
            new Row("server_setup", "platform", "minestom"), new Row("dev_cycle", "project", "game"),
            new Row("server_start", "timeoutSeconds", 60), new Row("server_profile", "seconds", 5),
            new Row("server_logs"), new Row("server_command", "command", "list"),
            new Row("server_wait", "until", "Starting", "timeoutSeconds", 1),
            new Row("api_search", "query", "timeout"), new Row("api_class", "name", "Assertions"),
            new Row("world_summary", REGION.toArray()), new Row("world_slice", REGION.toArray()),
            new Row("world_entities", REGION.toArray()), new Row("world_heightmap", "area", "0,0 3,3"),
            new Row("world_render", "region", REGION.get(1), "size", 64),
            new Row("world_tour", "region", REGION.get(1), "stops", 1),
            new Row("build_batch", "steps", "fill 0,70,0 1,71,1 minecraft:stone"), new Row("build_undo"),
            new Row("structure_save", "region", REGION.get(1), "file", "structures/a.schem"),
            new Row("player_test", "actions", List.of("wait 1"), "leave", true),
            new Row("texture", "name", "item/ruby", "palette", Drawings.palette("#d4263a"), "rows", Drawings.rows()),
            new Row("texture_view", "name", "item/ruby"),
            new Row("model_create", "name", "item/ruby", "kind", "item", "textures", List.of("item/ruby")),
            new Row("pack_asset", "type", "lang", "name", "en_us", "json", "{}"),
            new Row("pack", "check", true), new Row("pack"),
            new Row("server_detach"), new Row("server_stop"));

    private static FakeDownloads downloads;

    @TempDir
    Path dir;
    private Path home;
    private Path ws;
    private String userHome;

    @BeforeAll
    static void serve() throws IOException {
        downloads = FakeDownloads.start();
    }

    @AfterAll
    static void stopServing() {
        downloads.close();
    }

    @BeforeEach
    void folders() throws IOException {
        home = Files.createDirectories(dir.resolve("home"));
        ws = Files.createDirectories(dir.resolve("ws"));
        // Code that looked past CoreConfig for the home folder lands here too, not in the real one.
        userHome = System.getProperty("user.home");
        System.setProperty("user.home", home.toString());
    }

    @AfterEach
    void restoreHome() {
        System.setProperty("user.home", userHome);
    }

    /** A real jar to index for api_search and api_class: JUnit's API, on the test class path. */
    private static final Path API_JAR;

    static {
        try {
            API_JAR = Path.of(Test.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        } catch (URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }

    private static List<String> java(Class<?> main) {
        return List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", System.getProperty("java.class.path"), main.getName());
    }

    /** A core on the temp workspace and home; {@code fakeServer} replaces the server command, and its EULA gate. */
    private CoreHarness harness(Path workspace, boolean fakeServer) throws Exception {
        return new CoreHarness(workspace, c -> {
            CoreConfig config = downloads.apply(c).withMachineEula(MachineEula.forHome(home))
                    .withBuildCommand(java(FakeBuildMain.class)).withApiJars(List.of(API_JAR));
            return fakeServer ? config.withServerCommand(java(FakeServerMain.class)) : config;
        });
    }

    @Test
    void everyRegisteredToolHasARow() throws Exception {
        Set<String> rows = new LinkedHashSet<>(Stream.concat(GATED.stream(), RUNNING.stream()).map(Row::tool).toList());
        try (CoreHarness h = harness(ws, false)) {
            List<String> registered = h.core.registry().tools().stream().map(Tool::name).toList();
            assertThat(rows).as("a row in GATED or RUNNING for each tool, so this test runs it").containsAll(registered);
            assertThat(registered).as("rows for tools that no longer exist").containsAll(rows);
        }
    }

    @Test
    void noToolAcceptsTheEulaWithoutTheOwnersYes() throws Exception {
        Path demo = Files.createDirectories(ws.resolve("demo"));
        Files.writeString(Files.createDirectories(demo.resolve("src/main/resources")).resolve("plugin.yml"),
                "name: Demo\n");
        Files.writeString(demo.resolve("version.txt"), "1.0");
        Files.writeString(demo.resolve("name.txt"), "demo");
        // The Minestom workspace of RUNNING runs only a Minestom project (ADR 0048).
        Path game = Files.createDirectories(ws.resolve("game"));
        Files.writeString(game.resolve("build.gradle.kts"), "dependencies { implementation(\"net.minestom:minestom\") }\n");
        Files.writeString(game.resolve("version.txt"), "1.0");
        try (ClosedPort gamePort = CoreHarness.noGameServer(ws.resolve("run"))) {
            for (boolean fakeServer : new boolean[] {false, true}) {
                // The rows that fail, and with what; every other row succeeds. In GATED the EULA gate stops every
                // start, which is the point. In RUNNING server_start finds the server dev_cycle started, and
                // player_test stops at the join (see RUNNING).
                String noEula = "the Minecraft EULA is not accepted";
                Map<String, String> stops = fakeServer
                        ? Map.of("server_start", "server is already running",
                                "player_test", "no Minecraft server answers at 127.0.0.1:" + gamePort.port())
                        : Map.of("server_start", noEula, "dev_cycle", noEula);
                try (CoreHarness h = harness(ws, fakeServer)) {
                    for (Row row : fakeServer ? RUNNING : GATED) {
                        ToolRegistry.Result result = h.call(row.tool(), row.args());
                        String what = row.tool() + " " + List.of(row.args());
                        String stop = stops.get(row.tool());
                        assertThat(result.ok()).as(what + ": " + result.text()).isEqualTo(stop == null);
                        if (stop != null) {
                            assertThat(result.text()).as(what).contains(stop);
                        }
                        assertNothingAccepted(what);
                    }
                    h.call("server_stop");
                }
            }
        }
        assertNothingAccepted("after the session");
    }

    @ParameterizedTest
    @EnumSource(Platform.class)
    void setupAndStartWithoutAcceptEulaAcceptNothing(Platform platform) throws Exception {
        Path agents = Files.createDirectories(ws.resolve(".msc/agent"));
        for (String agent : List.of("paper", "fabric", "neoforge", "forge")) {
            Files.writeString(agents.resolve("msc-agent-" + agent + "-0.1.0.jar"), "agent " + agent);
        }
        try (CoreHarness h = harness(ws, false)) {
            for (Row row : List.of(new Row("server_setup", "platform", platform.id()),
                    new Row("server_setup", "acceptEula", false), new Row("server_start", "timeoutSeconds", 60),
                    new Row("server_start", "acceptEula", false, "timeoutSeconds", 60))) {
                h.call(row.tool(), row.args());
                assertNothingAccepted(platform.id() + " " + row.tool() + " " + List.of(row.args()));
            }
            h.call("server_stop");
        }
        if (platform.needsEula()) {
            assertThat(ws.resolve("run/eula.txt")).as(platform.id()).exists();
        }
        assertNothingAccepted(platform.id());
    }

    @Test
    void theOwnersCommandsAcceptNothingWithoutATypedYes() throws Exception {
        MachineEula machine = MachineEula.forHome(home);
        for (List<String> args : List.<List<String>>of(List.of(), List.of("status"), List.of("revoke"), List.of("accept"),
                List.of("agree"))) {
            for (Terminal t : List.of(new Terminal(false, "yes"), new Terminal(true, "no"), new Terminal(true))) {
                EulaCommand.run(args, machine, null, t, Clock.systemUTC(), "owner", "pc");
                assertNothingAccepted("eula " + args + " " + t);
            }
        }
        // The other Main subcommand, run by installMsc after each install.
        Path root = Files.createDirectories(dir.resolve("install"));
        LegacyInstall.refresh(root, Files.createDirectories(root.resolve("msc-core-1")));
        assertNothingAccepted("refresh-legacy-install");
    }

    @Test
    void eachDocumentedOptInDoesWrite() throws Exception {
        // 1. Main eula accept, with the owner's typed yes.
        EulaCommand.run(List.of("accept"), MachineEula.forHome(home), null, new Terminal(true, "yes"),
                Clock.systemUTC(), "owner", "pc");
        assertThat(MachineEula.forHome(home).read()).as("eula accept").isPresent();
        // 3. acceptEula=true on server_setup, and on server_start.
        for (String tool : List.of("server_setup", "server_start")) {
            MachineEula.forHome(home).revoke();
            Path fresh = Files.createDirectories(dir.resolve(tool));
            Files.writeString(Files.createDirectories(fresh.resolve("run")).resolve("paper.jar"), "fake");
            try (CoreHarness h = harness(fresh, true)) {
                h.call(tool, "acceptEula", true);
                h.call("server_stop");
            }
            assertThat(MachineEula.forHome(home).read()).as(tool).isPresent();
            assertThat(fresh.resolve("run/eula.txt")).as(tool).content().contains("eula=true");
        }
    }

    /** No machine acceptance under the home folder, and no eula.txt under the workspace with eula=true. */
    private void assertNothingAccepted(String after) throws IOException {
        assertThat(files(home, "eula.json")).as("machine acceptance after " + after).isEmpty();
        List<Path> accepted = new ArrayList<>();
        for (Path f : files(ws, "eula.txt")) {
            if (Files.readAllLines(f).stream().anyMatch(l -> l.strip().equals("eula=true"))) {
                accepted.add(f);
            }
        }
        assertThat(accepted).as("eula.txt with eula=true after " + after).isEmpty();
    }

    /** The files called {@code name} under {@code root}; one a running server removes meanwhile is passed over. */
    private static List<Path> files(Path root, String name) throws IOException {
        List<Path> out = new ArrayList<>();
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (file.getFileName().toString().equals(name)) {
                    out.add(file);
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException e) {
                return FileVisitResult.CONTINUE;
            }
        });
        return out;
    }

    /** A scripted terminal: {@code interactive} false stands for piped input, as from a tool or script. */
    private record Terminal(boolean interactive, String... answers) implements EulaCommand.Terminal {
        @Override
        public String readLine(String prompt) {
            return answers.length == 0 ? null : answers[0];
        }

        @Override
        public void println(String line) {
        }

        @Override
        public String toString() {
            return (interactive ? "terminal " : "piped ") + List.of(answers);
        }
    }
}
