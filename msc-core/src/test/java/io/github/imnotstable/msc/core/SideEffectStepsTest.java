package io.github.imnotstable.msc.core;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import io.github.imnotstable.msc.core.fixture.FakeBuildMain;
import io.github.imnotstable.msc.core.fixture.FakeServerMain;
import io.github.imnotstable.msc.core.texture.PackProperties;
import io.github.imnotstable.msc.core.tool.ToolRegistry;
import io.github.imnotstable.msc.core.tools.LifecycleToolsAccess;
import io.github.imnotstable.msc.core.tools.StepsAccess;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Every state-changing tool runs its side effects as named steps (#373, after #362 and #367). For each tool, a
 * successful call lists its steps; then a failure is injected at each step in turn, and the test checks that the call
 * says what was and was not done (never a bare internal error), that the state left behind is consistent, and that the
 * same call then succeeds. A scenario may also bring a state that makes a step fail by itself at every call (#405), not
 * once: there too the call must end consistent and the same call then succeed.
 */
@Timeout(value = 5, unit = TimeUnit.MINUTES, threadMode = Timeout.ThreadMode.SEPARATE_THREAD) // a hang fails
class SideEffectStepsTest {
    private static HttpServer downloads;
    private static String base;

    @TempDir
    Path root;

    /** What a scenario does in a fresh workspace: prepare it, make the call, and check the state after it. */
    private interface Scenario {
        CoreHarness open(Path ws) throws Exception;

        default void prepare(CoreHarness h, Path ws) throws Exception {
        }

        /** The tool and its arguments. */
        Object[] call(Path ws);

        /** Holds whatever happened: after a failed call at {@code step} (null: after a successful one). */
        void consistent(CoreHarness h, Path ws, String step) throws Exception;

        /**
         * Housekeeping steps ({@code Steps.optional}): a failure only adds a note and the call still succeeds, and a
         * failure of another step never lists them as done or not done.
         */
        default List<String> optional() {
            return List.of();
        }

        default void close(CoreHarness h) throws Exception {
        }
    }

    @BeforeAll
    static void serve() throws IOException {
        downloads = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        base = "http://127.0.0.1:" + downloads.getAddress().getPort();
        downloads.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getRawPath();
            String body = path.endsWith("/builds")
                    ? "[{\"id\":5,\"channel\":\"STABLE\",\"downloads\":{\"server:default\":{\"name\":\"paper-26.3-5.jar\","
                            + "\"url\":\"" + base + "/fill/paper.jar\"}}}]"
                    : path.equals("/fill/paper.jar") ? "paper server" : null;
            byte[] bytes = body == null ? null : body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(bytes == null ? 404 : 200, bytes == null ? -1 : bytes.length);
            if (bytes != null) {
                exchange.getResponseBody().write(bytes);
            }
            exchange.close();
        });
        downloads.start();
    }

    @AfterAll
    static void stopServing() {
        downloads.stop(0);
    }

    static Stream<Arguments> tools() {
        return Stream.of(
                Arguments.of("pack", new PackScenario(), List.of("build the pack and serve it",
                        "point server.properties at this build", "send it to the server's players", "save the zip")),
                Arguments.of("texture", new TextureScenario(), List.of("write the texture and its model",
                        "write the animation")),
                Arguments.of("texture .png", new LoosePngScenario(), List.of("write the png")),
                Arguments.of("model_create", new ModelScenario(), List.of("write the model and item definition")),
                Arguments.of("structure_save", new StructureScenario(), List.of("write the schematic")),
                Arguments.of("world_render", new RenderScenario(), List.of("save a copy of the image",
                        "delete old saved images")),
                Arguments.of("project_scaffold", new ScaffoldScenario(), List.of("write the project files",
                        "remember the workspace platform")),
                Arguments.of("server_setup", new SetupScenario(), List.of("download and install the server",
                        "write missing default config files", "set the game mode in server.properties",
                        "install the msc agent", "apply the EULA answer", "remember the workspace platform")),
                Arguments.of("server_setup minestom", new MinestomSetupScenario(), List.of("create the server folder",
                        "remember the workspace platform")),
                Arguments.of("server_start", new StartScenario(), List.of(
                        "stop the server an earlier session left running",
                        "take over the pack an earlier session served", "update the installed agent jar",
                        "remove what an interrupted earlier call left in plugins/, mods/ and libs/",
                        "start the server")),
                Arguments.of("dev_cycle", new DevCycleScenario(), List.of(
                        "stop a server an earlier session left holding the jar", "stop the server",
                        "remove what an interrupted earlier call left", "deploy the jar",
                        "remove the earlier deploy", "record the deploy", "remember the workspace platform",
                        "stop the server an earlier session left running", "take over the pack an earlier session served",
                        "update the installed agent jar", "start the server")),
                Arguments.of("dev_cycle on a volume that hides one file under two names", new DevCycleAliasScenario(),
                        List.of("stop a server an earlier session left holding the jar", "stop the server",
                        "remove what an interrupted earlier call left", "deploy the jar", "remove the earlier deploy",
                        "record the deploy",
                        "remember the workspace platform", "stop the server an earlier session left running",
                        "take over the pack an earlier session served", "update the installed agent jar",
                        "start the server")),
                Arguments.of("server_detach", new DetachScenario(), List.of("detach the server",
                        "hand the pack to a standalone host", "offer the pack in server.properties")));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("tools")
    void aFailureAtAnyStepSaysWhatWasDoneAndLeavesAWorkingState(String name, Scenario scenario, List<String> steps)
            throws Exception {
        Object[] call;
        // The table lists every step the call runs, in order: a new step must be added here, and so is tested.
        Path clean = Files.createDirectories(root.resolve("clean"));
        CoreHarness h = scenario.open(clean);
        try {
            scenario.prepare(h, clean);
            call = scenario.call(clean);
            List<String> trace = StepsAccess.trace();
            try {
                h.ok((String) call[0], Arrays.copyOfRange(call, 1, call.length));
            } finally {
                StepsAccess.stopTrace();
            }
            assertThat(trace).as(name).containsExactlyElementsOf(steps.stream().map(s -> tool(call) + ": " + s).toList());
            scenario.consistent(h, clean, null);
        } finally {
            scenario.close(h);
            h.close();
        }
        for (int i = 0; i < steps.size(); i++) {
            String step = steps.get(i);
            Path ws = Files.createDirectories(root.resolve("fail-" + i));
            CoreHarness f = scenario.open(ws);
            try {
                scenario.prepare(f, ws);
                Object[] args = scenario.call(ws);
                Object[] rest = Arrays.copyOfRange(args, 1, args.length);
                ToolRegistry.Result r;
                StepsAccess.failAt(step);
                try {
                    r = f.call(tool(args), rest);
                } finally {
                    StepsAccess.failAt(null);
                }
                if (scenario.optional().contains(step)) {
                    assertThat(r.ok()).as("%s at %s: %s", name, step, r.text()).isTrue();
                    assertThat(r.text()).contains("not done: " + step + " (injected failure)");
                } else {
                    List<String> planned = steps.stream().filter(s -> !scenario.optional().contains(s)).toList();
                    assertThat(r.ok()).as("%s at %s", name, step).isFalse();
                    assertThat(r.text()).as("%s at %s", name, step).doesNotContain("internal")
                            .contains("injected failure\n" + tool(args) + " stopped at: " + step + "\n")
                            .contains("\nnot done: " + String.join("; ", planned.subList(planned.indexOf(step),
                                    planned.size())));
                    String done = r.text().lines().filter(l -> l.startsWith("done: ")).findFirst().orElseThrow();
                    for (String later : steps.subList(i, steps.size())) {
                        assertThat(done).as("%s at %s", name, step).doesNotContain(later);
                    }
                    for (String extra : scenario.optional()) {
                        assertThat(r.text()).as("%s at %s: housekeeping is never listed", name, step)
                                .doesNotContain(extra);
                    }
                }
                scenario.consistent(f, ws, step);
                // Nothing half done blocks the next call: the same call now goes through.
                Object[] again = scenario.call(ws);
                f.ok(tool(again), Arrays.copyOfRange(again, 1, again.length));
                scenario.consistent(f, ws, null);
            } finally {
                scenario.close(f);
                f.close();
            }
        }
    }

    private static String tool(Object[] call) {
        return (String) call[0];
    }

    private static List<String> java(Class<?> main) {
        return List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", System.getProperty("java.class.path"), main.getName());
    }

    private static CoreHarness withServer(Path ws, UnaryOperator<CoreConfig> more) throws Exception {
        return new CoreHarness(ws, c -> more.apply(c.withServerCommand(java(FakeServerMain.class))
                .withBuildCommand(java(FakeBuildMain.class))));
    }

    /** No file named like a temp part is left anywhere under {@code dir}. */
    private static void noPartFiles(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> tree = Files.walk(dir)) {
            assertThat(tree.map(p -> p.getFileName().toString()).filter(n -> n.endsWith(".part"))).isEmpty();
        }
    }

    private static int status(String url) throws Exception {
        try (HttpClient client = HttpClient.newHttpClient()) {
            return client.send(HttpRequest.newBuilder(URI.create(url)).build(), HttpResponse.BodyHandlers.discarding())
                    .statusCode();
        }
    }

    private static byte[] fetch(String url) throws Exception {
        try (HttpClient client = HttpClient.newHttpClient()) {
            return client.send(HttpRequest.newBuilder(URI.create(url)).build(), HttpResponse.BodyHandlers.ofByteArray())
                    .body();
        }
    }

    /**
     * pack in a detached session's workspace (server.properties offers our pack), with players connected and file=:
     * the URL the players were last sent, the pack host and server.properties always agree (#373).
     */
    static final class PackScenario implements Scenario {
        @Override
        public CoreHarness open(Path ws) throws Exception {
            return new CoreHarness(ws).withAgent();
        }

        @Override
        public void prepare(CoreHarness h, Path ws) throws Exception {
            h.ok("texture", "name", "item/ruby", "kind", "gem", "color", "#d4263a");
            String built = h.ok("pack");
            String url = built.lines().filter(l -> l.startsWith("url: ")).findFirst().orElseThrow().substring(5);
            Files.createDirectories(ws.resolve("run"));
            Files.writeString(ws.resolve("run/server.properties"), "motd=kept\nresource-pack=" + url + "\n");
            h.ok("texture", "name", "item/ruby", "kind", "gem", "color", "#2655d4");
        }

        @Override
        public Object[] call(Path ws) {
            return new Object[] {"pack", "file", "out/ruby-pack.zip"};
        }

        @Override
        public void consistent(CoreHarness h, Path ws, String step) throws Exception {
            String offered = PackProperties.url(ws.resolve("run/server.properties"));
            assertThat(status(offered)).as("server.properties offers %s", offered).isEqualTo(200);
            String sent;
            synchronized (h.agent) {
                sent = h.agent.packs.getLast().url();
            }
            assertThat(sent).as("the players were sent what server.properties offers").isEqualTo(offered);
            assertThat(ws.resolve("run/server.properties")).content().startsWith("motd=kept\n");
            Path saved = ws.resolve("out/ruby-pack.zip");
            if (Files.exists(saved)) {
                assertThat(Files.readAllBytes(saved)).isEqualTo(fetch(offered));
            }
            noPartFiles(ws);
        }
    }

    /** texture drawn and animated: a texture is written whole or not at all, and the pack stays valid. */
    static final class TextureScenario implements Scenario {
        @Override
        public CoreHarness open(Path ws) throws Exception {
            return new CoreHarness(ws);
        }

        @Override
        public Object[] call(Path ws) {
            return new Object[] {"texture", "name", "item/ruby", "kind", "gem", "color", "#d4263a", "animate", "pulse"};
        }

        @Override
        public void consistent(CoreHarness h, Path ws, String step) throws Exception {
            Path png = ws.resolve(".msc/pack/assets/msc/textures/item/ruby.png");
            if (Files.exists(png)) {
                assertThat(ImageIO.read(png.toFile())).isNotNull();
                assertThat(h.ok("pack", "check", true)).contains(" 0 errors");
            }
            if (step == null) {
                assertThat(png.resolveSibling("ruby.png.mcmeta")).isRegularFile();
            }
            noPartFiles(ws);
        }
    }

    /** texture written to a .png path outside the pack. */
    static final class LoosePngScenario implements Scenario {
        @Override
        public CoreHarness open(Path ws) throws Exception {
            return new CoreHarness(ws);
        }

        @Override
        public Object[] call(Path ws) {
            return new Object[] {"texture", "name", "art/ruby.png", "kind", "gem", "color", "#d4263a"};
        }

        @Override
        public void consistent(CoreHarness h, Path ws, String step) throws Exception {
            Path png = ws.resolve("art/ruby.png");
            assertThat(Files.exists(png)).isEqualTo(step == null);
            if (step == null) {
                assertThat(ImageIO.read(png.toFile()).getWidth()).isEqualTo(16);
            }
            noPartFiles(ws);
        }
    }

    static final class ModelScenario implements Scenario {
        @Override
        public CoreHarness open(Path ws) throws Exception {
            return new CoreHarness(ws);
        }

        @Override
        public void prepare(CoreHarness h, Path ws) {
            h.ok("texture", "name", "block/ruby_block", "kind", "bricks", "color", "#d4263a", "model", "none");
        }

        @Override
        public Object[] call(Path ws) {
            return new Object[] {"model_create", "name", "block/ruby_block", "kind", "cube_all", "textures",
                    List.of("block/ruby_block")};
        }

        @Override
        public void consistent(CoreHarness h, Path ws, String step) throws Exception {
            Path model = ws.resolve(".msc/pack/assets/msc/models/block/ruby_block.json");
            assertThat(Files.exists(model)).isEqualTo(step == null);
            assertThat(h.ok("pack", "check", true)).contains(" 0 errors");
            noPartFiles(ws);
        }
    }

    static final class StructureScenario implements Scenario {
        @Override
        public CoreHarness open(Path ws) throws Exception {
            return new CoreHarness(ws).withAgent();
        }

        @Override
        public Object[] call(Path ws) {
            return new Object[] {"structure_save", "region", "0,60,0 3,62,3", "file", "structures/house.schem"};
        }

        @Override
        public void consistent(CoreHarness h, Path ws, String step) throws Exception {
            Path file = ws.resolve("structures/house.schem");
            assertThat(Files.exists(file)).isEqualTo(step == null);
            if (step == null) {
                assertThat(h.ok("world_summary", "region", "0,60,0 3,62,3", "diff", "structures/house.schem"))
                        .doesNotContain("error");
            }
            noPartFiles(ws);
        }
    }

    /** world_render: the image is the answer; its saved copy and the pruning only add a note when they fail. */
    static final class RenderScenario implements Scenario {
        @Override
        public CoreHarness open(Path ws) throws Exception {
            return new CoreHarness(ws).withAgent();
        }

        @Override
        public Object[] call(Path ws) {
            return new Object[] {"world_render", "region", "0,60,0 3,62,3", "size", 64, "entities", false};
        }

        @Override
        public List<String> optional() {
            return List.of("save a copy of the image", "delete old saved images");
        }

        @Override
        public void consistent(CoreHarness h, Path ws, String step) throws Exception {
            Path renders = ws.resolve(".msc/renders");
            if (Files.exists(renders)) {
                try (Stream<Path> files = Files.list(renders)) {
                    for (Path png : files.toList()) {
                        assertThat(ImageIO.read(png.toFile())).as("%s", png).isNotNull();
                    }
                }
            }
        }
    }

    /** project_scaffold: the files whole, then the platform; a later failure says the project is there. */
    static final class ScaffoldScenario implements Scenario {
        private int calls;

        @Override
        public CoreHarness open(Path ws) throws Exception {
            calls = 0;
            return new CoreHarness(ws);
        }

        @Override
        public Object[] call(Path ws) {
            // Each call scaffolds into a new folder: a scaffold needs an empty one.
            return new Object[] {"project_scaffold", "name", "Demo", "dir", "demo" + calls++};
        }

        @Override
        public void consistent(CoreHarness h, Path ws, String step) throws Exception {
            Path platform = ws.resolve(".msc/platform");
            if ("write the project files".equals(step)) {
                assertThat(ws.resolve("demo0")).doesNotExist();
                assertThat(platform).doesNotExist();
            } else if (step != null) {
                assertThat(ws.resolve("demo0/build.gradle.kts")).isRegularFile();
                assertThat(platform).doesNotExist();
            } else {
                assertThat(platform).hasContent("paper\n");
            }
        }
    }

    /** server_setup for Paper against a local fake download service. The EULA is never accepted. */
    static final class SetupScenario implements Scenario {
        @Override
        public CoreHarness open(Path ws) throws Exception {
            Path agents = Files.createDirectories(ws.resolve(".msc/agent"));
            Files.writeString(agents.resolve("msc-agent-paper-0.1.0.jar"), "agent paper");
            return new CoreHarness(ws, c -> c.withDownloadsUrl(base + "/fill").withMinecraft("26.3"));
        }

        @Override
        public Object[] call(Path ws) {
            return new Object[] {"server_setup", "platform", "paper", "mode", "build"};
        }

        @Override
        public void consistent(CoreHarness h, Path ws, String step) throws Exception {
            Path run = ws.resolve("run");
            if (Files.exists(run.resolve("eula.txt"))) {
                assertThat(Files.readAllLines(run.resolve("eula.txt"))).doesNotContain("eula=true");
            }
            if (step == null) {
                assertThat(ws.resolve(".msc/platform")).hasContent("paper\n");
                assertThat(run.resolve("paper.jar")).hasContent("paper server");
                assertThat(run.resolve("plugins/msc-agent-paper.jar")).hasContent("agent paper");
                assertThat(run.resolve("server.properties")).content().contains("gamemode=creative");
            } else {
                // The platform is remembered last: a setup that did not finish leaves the workspace's as it was.
                assertThat(ws.resolve(".msc/platform")).doesNotExist();
            }
            noPartFiles(ws);
        }
    }

    static final class MinestomSetupScenario implements Scenario {
        @Override
        public CoreHarness open(Path ws) throws Exception {
            return new CoreHarness(ws);
        }

        @Override
        public Object[] call(Path ws) {
            return new Object[] {"server_setup", "platform", "minestom"};
        }

        @Override
        public void consistent(CoreHarness h, Path ws, String step) {
            if (step == null) {
                assertThat(ws.resolve(".msc/platform")).hasContent("minestom\n");
                assertThat(ws.resolve("run")).isDirectory();
            } else {
                assertThat(ws.resolve(".msc/platform")).doesNotExist();
            }
        }
    }

    /** server_start with an agent jar to update: a start that did not happen leaves the server stopped. */
    static final class StartScenario implements Scenario {
        @Override
        public CoreHarness open(Path ws) throws Exception {
            Path agents = Files.createDirectories(ws.resolve(".msc/agent"));
            Files.writeString(agents.resolve("msc-agent-paper-0.1.0.jar"), "agent paper 2");
            Path plugins = Files.createDirectories(ws.resolve("run/plugins"));
            Files.writeString(plugins.resolve("msc-agent-paper.jar"), "agent paper 1");
            return withServer(ws, c -> c);
        }

        @Override
        public Object[] call(Path ws) {
            return new Object[] {"server_start", "timeoutSeconds", 60};
        }

        @Override
        public List<String> optional() {
            return List.of("remove what an interrupted earlier call left in plugins/, mods/ and libs/");
        }

        @Override
        public void consistent(CoreHarness h, Path ws, String step) throws Exception {
            assertThat(h.core.server().running()).isEqualTo(step == null || optional().contains(step));
            assertThat(ws.resolve("run/plugins/msc-agent-paper.jar")).content().startsWith("agent paper ");
            noPartFiles(ws);
            if (h.core.server().running()) {
                h.ok("server_stop");
            }
        }

        @Override
        public void close(CoreHarness h) {
            h.call("server_stop");
        }
    }

    /**
     * dev_cycle on a running server, for a project whose jar was renamed (the earlier deploy goes) in a workspace with
     * no platform yet (the deploy remembers it). The plugin folder never holds a half-written jar.
     */
    static class DevCycleScenario implements Scenario {
        @Override
        public CoreHarness open(Path ws) throws Exception {
            Path project = Files.createDirectories(ws.resolve("demo/src/main/resources"));
            Files.writeString(project.resolve("plugin.yml"), "name: Arena\n");
            Files.writeString(ws.resolve("demo/version.txt"), "2.0");
            Files.writeString(ws.resolve("demo/name.txt"), "arena");
            Path plugins = Files.createDirectories(ws.resolve("run/plugins"));
            Files.writeString(plugins.resolve("demo.jar"), "1.0");
            return withServer(ws, c -> c);
        }

        @Override
        public void prepare(CoreHarness h, Path ws) {
            h.ok("server_start", "timeoutSeconds", 60);
        }

        @Override
        public Object[] call(Path ws) {
            return new Object[] {"dev_cycle", "project", "demo"};
        }

        @Override
        public void consistent(CoreHarness h, Path ws, String step) throws Exception {
            Path plugins = ws.resolve("run/plugins");
            List<String> jars = new ArrayList<>();
            try (Stream<Path> list = Files.list(plugins)) {
                list.forEach(p -> jars.add(p.getFileName().toString()));
            }
            assertThat(jars).as("never a half-written jar").noneMatch(n -> n.endsWith(".part"));
            if (Files.exists(plugins.resolve("arena.jar"))) {
                assertThat(plugins.resolve("arena.jar")).hasContent("2.0");
            }
            if (step == null) {
                assertThat(jars).containsExactly("arena.jar");
                assertThat(ws.resolve(".msc/platform")).hasContent("paper\n");
                assertThat(h.core.server().running()).isTrue();
            } else if (List.of("stop a server an earlier session left holding the jar", "stop the server",
                    "deploy the jar").contains(step)) {
                // Nothing deployed: the earlier jar is still the one there.
                assertThat(jars).containsExactly("demo.jar");
            }
        }

        @Override
        public List<String> optional() {
            return List.of("remove what an interrupted earlier call left");
        }

        @Override
        public void close(CoreHarness h) {
            h.call("server_stop");
        }
    }

    /**
     * #405: dev_cycle where the record spells the deployed jar another way, on a volume that reports the two names as
     * two files while removing one removes both (a linked folder, with the sameness check made to answer
     * "different"). Every call ends with the new jar in place and, once it gets that far, recorded under its own name.
     */
    static final class DevCycleAliasScenario extends DevCycleScenario {
        @Override
        public CoreHarness open(Path ws) throws Exception {
            CoreHarness h = super.open(ws);
            Path plugins = ws.resolve("run/plugins");
            Files.delete(plugins.resolve("demo.jar"));
            Files.writeString(plugins.resolve("arena.jar"), "1.0");
            Files.createSymbolicLink(plugins.resolve("same"), Path.of("."));
            java.util.Properties record = new java.util.Properties();
            record.setProperty(ws.resolve("demo").toAbsolutePath().normalize().toString(), "plugins/same/arena.jar");
            Files.createDirectories(ws.resolve(".msc"));
            try (java.io.OutputStream out = Files.newOutputStream(ws.resolve(".msc/deployed.properties"))) {
                record.store(out, null);
            }
            LifecycleToolsAccess.sameFile((a, b) -> false);
            return h;
        }

        @Override
        public void consistent(CoreHarness h, Path ws, String step) throws Exception {
            Path plugins = ws.resolve("run/plugins");
            assertThat(plugins.resolve("arena.jar")).as("the new jar or the earlier one, never none")
                    .content().isIn("1.0", "2.0");
            try (Stream<Path> list = Files.list(plugins)) {
                assertThat(list.map(p -> p.getFileName().toString()))
                        .containsExactlyInAnyOrder("arena.jar", "same");
            }
            if (step == null) {
                assertThat(plugins.resolve("arena.jar")).hasContent("2.0");
                assertThat(ws.resolve(".msc/deployed.properties")).content().contains("=plugins/arena.jar");
                assertThat(h.core.server().running()).isTrue();
            }
        }

        @Override
        public void close(CoreHarness h) {
            LifecycleToolsAccess.sameFile(null);
            super.close(h);
        }
    }

    /** server_detach after a pack was built: the standalone host takes over, and server.properties offers it. */
    static final class DetachScenario implements Scenario {
        @Override
        public CoreHarness open(Path ws) throws Exception {
            Files.createDirectories(ws.resolve("run"));
            Files.writeString(ws.resolve("run/server.properties"), "motd=kept\n");
            return withServer(ws, c -> c);
        }

        @Override
        public void prepare(CoreHarness h, Path ws) {
            h.ok("server_start", "timeoutSeconds", 60);
            h.ok("texture", "name", "item/ruby", "kind", "gem", "color", "#d4263a");
            h.ok("pack");
        }

        @Override
        public Object[] call(Path ws) {
            return new Object[] {"server_detach"};
        }

        @Override
        public void consistent(CoreHarness h, Path ws, String step) throws Exception {
            assertThat(h.core.server().running()).isTrue();
            String offered = PackProperties.url(ws.resolve("run/server.properties"));
            if (offered != null) {
                assertThat(status(offered)).as("server.properties offers %s", offered).isEqualTo(200);
            }
            if (step == null) {
                assertThat(offered).isNotNull();
            }
        }

        @Override
        public void close(CoreHarness h) {
            // Detached means it outlives the session: stop it and its pack host here.
            h.call("server_stop");
        }
    }
}
