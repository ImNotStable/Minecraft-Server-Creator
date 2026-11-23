package io.github.imnotstable.msc.core;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.github.imnotstable.msc.core.fixture.FakeBuildMain;
import io.github.imnotstable.msc.core.server.MachineEula;
import io.github.imnotstable.msc.core.tool.Tool;
import io.github.imnotstable.msc.core.tool.ToolRegistry;
import io.github.imnotstable.msc.process.ClosedPort;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * #457, by running the product (ADR 0036): no tool result is ever {@code error: internal: <ExceptionClass>} for bad
 * user input. Every registered tool is called with each of its string arguments set to each bad value (NUL, control
 * characters, {@code ..}, an absolute path outside the workspace, a path below a file, a 300-character name, empty,
 * unicode). The result may be a success (a unicode project name is fine), but never an internal error or a Java
 * exception name; a refusal is one line and leaves the workspace and the folder beside it as they were.
 */
class BadInputBehaviourTest {
    private static final String REGION = "0,62,0 3,66,3";

    /**
     * One tool's call: arguments that pass on their own, so each bad value meets the tool's own checks, and for an
     * argument that holds a path inside other text (build_batch's {@code file=}) the shapes to put the value in.
     */
    private record Row(String tool, Map<String, List<String>> shapes, Object... args) {
        Row(String tool, Object... args) {
            this(tool, Map.of(), args);
        }
    }

    private static final List<Row> ROWS = List.of(
            new Row("msc_status"),
            new Row("msc_workspace", "create", true),
            new Row("project_scaffold", "name", "Demo", "dir", "p"),
            new Row("server_setup", "platform", "paper"),
            new Row("plugins"),
            new Row("server_start", "timeoutSeconds", 5),
            new Row("server_detach"),
            new Row("server_stop"),
            new Row("dev_cycle", "restart", false),
            new Row("server_logs"),
            new Row("server_command", "command", "list"),
            new Row("server_wait", "until", "Done", "timeoutSeconds", 1),
            new Row("api_search", "query", "timeout"),
            new Row("api_class", "name", "Assertions"),
            new Row("world_summary", Map.of("path", List.of("0,64,0 %s")), "region", REGION),
            new Row("world_slice", "region", REGION),
            new Row("world_entities", "region", REGION),
            new Row("world_heightmap", "area", "0,0 3,3"),
            new Row("world_render", "region", REGION, "size", 64),
            new Row("build_batch", Map.of("steps", List.of("%s", "structure 0,70,0 file=%s", "fill %s stone")),
                    "steps", "fill 0,70,0 1,71,1 stone", "dryRun", true),
            new Row("build_undo"),
            new Row("world_create", "name", "w", "terrain", "flat", "layers", List.of("bedrock", "dirt:2")),
            new Row("structure_save", "region", REGION, "file", "s/a.schem"),
            new Row("player_test", "actions", List.of("wait 1"), "leave", true),
            new Row("texture", Map.of("name", List.of("%s", "%s.png", "item/%s")),
                    "name", "item/ruby", "kind", "gem", "color", "#d4263a"),
            new Row("texture_view", Map.of("name", List.of("%s", "item/%s")), "name", "item/ruby"),
            new Row("model_create", "name", "item/ruby", "kind", "item", "textures", List.of("item/ruby")),
            new Row("pack_asset", "type", "lang", "name", "en_us", "json", "{}"),
            new Row("pack", "apply", false));

    /** A real jar to index for api_search and api_class: JUnit's API, on the test class path. */
    private static final Path API_JAR;

    static {
        try {
            API_JAR = Path.of(Test.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        } catch (URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }

    private static FakeDownloads downloads;

    @TempDir
    Path dir;

    @BeforeAll
    static void serve() throws IOException {
        downloads = FakeDownloads.start();
    }

    @AfterAll
    static void stopServing() {
        downloads.close();
    }

    private static List<String> java(Class<?> main) {
        return List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", System.getProperty("java.class.path"), main.getName());
    }

    private CoreHarness harness(Path ws) throws Exception {
        Path home = Files.createDirectories(dir.resolve("home"));
        return new CoreHarness(ws, c -> downloads.apply(c).withMachineEula(MachineEula.forHome(home))
                .withBuildCommand(java(FakeBuildMain.class)).withApiJars(List.of(API_JAR))
                .withToolLog(dir.resolve("log").resolve(ws.getFileName() + ".jsonl"))).withAgent();
    }

    private List<String> badValues(Path outside) {
        return List.of("a\u0000b", "a\u0007b\nc", "..", "../..", outside.resolve("abs").toString(), "version.txt/below",
                "a".repeat(300), "", "üñï/名前 😀");
    }

    @Test
    void everyRegisteredToolHasARow() throws Exception {
        try (CoreHarness h = harness(Files.createDirectories(dir.resolve("ws")))) {
            List<String> registered = h.core.registry().tools().stream().map(Tool::name).toList();
            assertThat(ROWS.stream().map(Row::tool).toList()).as("one row per registered tool, so this test calls it")
                    .containsExactlyInAnyOrderElementsOf(registered);
        }
    }

    @Test
    void noBadValueInAnyStringArgumentGivesAnInternalError() throws Exception {
        List<String> failures = new ArrayList<>();
        int calls = 0;
        for (Row row : ROWS) {
            Path root = Files.createDirectories(dir.resolve(row.tool()));
            Path ws = Files.createDirectories(root.resolve("ws"));
            Path outside = Files.createDirectories(root.resolve("outside"));
            Files.writeString(ws.resolve("version.txt"), "1.0");
            try (ClosedPort gamePort = CoreHarness.noGameServer(ws.resolve("run")); CoreHarness h = harness(ws)) {
                assertThat(gamePort.port()).as("player_test's join finds nothing").isPositive();
                Tool tool = h.core.registry().tools().stream().filter(t -> t.name().equals(row.tool())).findFirst()
                        .orElseThrow();
                JsonObject properties = tool.inputSchema().getAsJsonObject("properties");
                for (String arg : properties.keySet()) {
                    String type = properties.getAsJsonObject(arg).get("type").getAsString();
                    if (!type.equals("string") && !type.equals("array")) {
                        continue;
                    }
                    for (String shape : row.shapes().getOrDefault(arg, List.of("%s"))) {
                        for (String bad : badValues(outside)) {
                            String value = shape.replace("%s", bad);
                            JsonObject args = CoreHarness.args(row.args());
                            args.add(arg, type.equals("array") ? array(value) : new com.google.gson.JsonPrimitive(value));
                            Map<String, String> before = snapshot(root);
                            ToolRegistry.Result r = h.core.registry().call(row.tool(), args);
                            calls++;
                            String what = row.tool() + " " + arg + "=" + printable(value) + " -> " + firstLines(r.text());
                            if (r.text().contains("internal:") || r.text().matches("(?s).*\\b\\w+(Exception|Error)\\b.*")) {
                                failures.add(what);
                            } else if (!r.ok() && r.text().contains("\n")) {
                                failures.add(what + " (more than one line)");
                            } else if (!r.ok()) {
                                Map<String, String> after = snapshot(root);
                                if (!after.equals(before)) {
                                    failures.add(what + " (changed " + changes(before, after) + ")");
                                }
                            }
                        }
                    }
                }
                h.call("server_stop");
            }
        }
        assertThat(calls).as("calls made").isGreaterThan(300);
        assertThat(failures).as("calls with a bad value that gave an internal or multi-line error, or changed files")
                .isEmpty();
    }

    private static JsonElement array(String value) {
        JsonArray array = new JsonArray();
        array.add(value);
        return array;
    }

    /** The workspace and the folder beside it, without the tool log (kept outside) and MSC's own lock files. */
    private static Map<String, String> snapshot(Path root) throws IOException {
        Map<String, String> out = new TreeMap<>(TreeSnapshot.of(root));
        out.keySet().removeIf(k -> k.endsWith(".lock"));
        return out;
    }

    private static List<String> changes(Map<String, String> before, Map<String, String> after) {
        java.util.Set<String> keys = new java.util.TreeSet<>(before.keySet());
        keys.addAll(after.keySet());
        return keys.stream().filter(k -> !java.util.Objects.equals(before.get(k), after.get(k))).limit(5).toList();
    }

    private static String printable(String value) {
        StringBuilder out = new StringBuilder();
        value.codePoints().forEach(c -> out.append(c < 32 ? String.format("\\u%04x", c) : Character.toString(c)));
        return out.length() > 40 ? out.substring(0, 40) + "..." : out.toString();
    }

    private static String firstLines(String text) {
        return text.lines().limit(3).reduce((a, b) -> a + " | " + b).orElse("");
    }
}
