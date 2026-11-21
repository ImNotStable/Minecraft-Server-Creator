package io.github.imnotstable.msc.bench;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.google.gson.JsonParser;
import io.github.imnotstable.msc.bench.check.Check;
import io.github.imnotstable.msc.bench.check.CheckContext;
import io.github.imnotstable.msc.bench.check.CheckResult;
import io.github.imnotstable.msc.bench.check.Checks;
import io.github.imnotstable.msc.process.rcon.FakeRconServer;
import io.github.imnotstable.msc.process.rcon.RconClient;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

@Timeout(value = 5, unit = TimeUnit.MINUTES, threadMode = Timeout.ThreadMode.SEPARATE_THREAD) // a hang fails
class ChecksTest {

    private static final String RUBY_RULES = "\"width\":16,\"height\":16,\"maxColors\":8,\"minOpaqueFraction\":0.2,"
            + "\"maxOpaqueFraction\":0.85,\"hue\":{\"from\":330,\"to\":15,\"minFraction\":0.5,\"minSaturation\":0.35}";

    @TempDir
    Path workspace;

    @TempDir
    Path taskDir;

    private CheckResult run(String json) {
        return run(json, null);
    }

    private CheckResult run(String json, FakeRconServer server) {
        return run(Checks.parse(UserJson.of(JsonParser.parseString(json).getAsJsonObject(), "check.", Checks.KEYS),
                taskDir), server);
    }

    private CheckResult run(Check check, FakeRconServer server) {
        return run(check, server, Map.of());
    }

    /** {@code env} goes into the environment of every process the check starts. */
    private CheckResult run(Check check, FakeRconServer server, Map<String, String> env) {
        return check.run(new CheckContext() {
            @Override
            public Process start(ProcessBuilder builder) throws IOException {
                builder.environment().putAll(env);
                return builder.start();
            }

            @Override
            public Path workspace() {
                return workspace;
            }

            @Override
            public Map<String, String> placeholders() {
                return Map.of("java", ProcessHandle.current().info().command().orElse("java"),
                        "classpath", System.getProperty("java.class.path"), "serverPort", "1");
            }

            @Override
            public RconClient rcon() throws IOException {
                return RconClient.connect("127.0.0.1", server.port(), "pw", Duration.ofSeconds(5));
            }
        });
    }

    /** A command check that passes when {@code file} in the workspace holds {@code text}. */
    private static String fileHolds(String file, String text) {
        return "{\"type\":\"command\",\"addFiles\":\"hidden\",\"outputMatches\":\"^" + text + "$\",\"run\":[\"{java}\","
                + "\"-cp\",\"{classpath}\",\"io.github.imnotstable.msc.bench.CatFile\",\"" + file + "\"]}";
    }

    @Test
    void commandCheckHasTheTasksHiddenFilesWhileItRunsOnly() throws IOException {
        // #603: addFiles is in the workspace while the command runs, and the workspace is the agent's again after.
        Files.createDirectories(taskDir.resolve("hidden/src/test"));
        Files.writeString(taskDir.resolve("hidden/src/test/Hidden.txt"), "secret");
        Files.writeString(taskDir.resolve("hidden/Shared.txt"), "hidden");
        Files.createDirectories(workspace.resolve("src"));
        Files.writeString(workspace.resolve("src/Agent.txt"), "agent");
        Files.writeString(workspace.resolve("Shared.txt"), "agent's");

        assertThat(run(fileHolds("src/test/Hidden.txt", "secret")).passed()).isTrue();
        assertThat(run(fileHolds("Shared.txt", "hidden")).passed()).isTrue();

        assertThat(workspace.resolve("src/test")).doesNotExist();
        assertThat(workspace.resolve("src/Agent.txt")).hasContent("agent");
        assertThat(workspace.resolve("Shared.txt")).hasContent("agent's");
        assertThat(catchThrowable(() -> run(
                "{\"type\":\"command\",\"addFiles\":\"../outside\",\"run\":[\"x\"]}")))
                .hasMessageContaining("no folder \"../outside\" in the task");
    }

    @Test
    void commandCheckTakesItsFilesOutWhenTheCommandFailsOrCannotStart() throws IOException {
        Files.createDirectories(taskDir.resolve("hidden/src"));
        Files.writeString(taskDir.resolve("hidden/src/Hidden.txt"), "secret");
        Files.writeString(taskDir.resolve("hidden/Shared.txt"), "hidden");
        Files.writeString(workspace.resolve("Shared.txt"), "agent's");

        for (String command : new String[] {"\"{java}\",\"-cp\",\"{classpath}\",\"no.such.Main\"",
                "\"no-such-program-msc-bench\""}) {
            CheckResult result = run("{\"type\":\"command\",\"addFiles\":\"hidden\",\"run\":[" + command + "]}");

            assertThat(result.passed()).as(command).isFalse();
            assertThat(workspace.resolve("src")).as(command).doesNotExist();
            assertThat(workspace.resolve("Shared.txt")).as(command).hasContent("agent's");
        }
    }

    @Test
    void checksRunTwiceOnOneWorkspaceGiveTheSameResults() throws IOException {
        // #603: the issue's case. The first check stands for "gradlew build", which runs everything in src/test; the
        // second adds hidden tests that fail. A second check of the kept workspace must meet what the first met.
        Files.createDirectories(taskDir.resolve("hidden/src/test"));
        Files.writeString(taskDir.resolve("hidden/src/test/HiddenTest.txt"), "hidden");
        Check builds = parse("{\"type\":\"command\",\"name\":\"builds\",\"run\":[\"{java}\",\"-cp\",\"{classpath}\","
                + "\"io.github.imnotstable.msc.bench.CatFile\",\"src/test/HiddenTest.txt\"],\"expectExit\":1}");
        Check hidden = parse("{\"type\":\"command\",\"name\":\"hidden\",\"addFiles\":\"hidden\",\"run\":[\"{java}\","
                + "\"-cp\",\"{classpath}\",\"no.such.Main\"]}");

        List<CheckResult> first = List.of(run(builds, null), run(hidden, null));
        List<CheckResult> second = List.of(run(builds, null), run(hidden, null));

        assertThat(first).extracting(CheckResult::passed).containsExactly(true, false);
        assertThat(second).isEqualTo(first);
    }

    private Check parse(String json) {
        return Checks.parse(UserJson.of(JsonParser.parseString(json).getAsJsonObject(), "check.", Checks.KEYS),
                taskDir);
    }

    @Test
    void playerCheckStartsTheBuiltJarAndMatchesConsoleReplies() throws IOException {
        Files.createDirectories(workspace.resolve("build/libs"));
        Files.writeString(workspace.resolve("build/libs/game-1.0.0.jar"), "stands in for the server jar");
        String check = """
                {"type":"player","jar":"build/libs/game-1.0.0.jar",
                 "command":["{java}","-cp","{classpath}","io.github.imnotstable.msc.bench.FakeServer"],
                 "console":["game best Steve"],"expect":[%s],"timeoutSeconds":30}
                """;

        CheckResult pass = run(check.formatted("\"reply to game best Steve\""));
        CheckResult fail = run(check.formatted("\"Steve best: \\\\d\""));
        CheckResult noJar = run(check.replace("game-1.0.0", "other").formatted("\"x\""));

        assertThat(pass.passed()).as(pass.detail()).isTrue();
        assertThat(workspace.resolve(".bench-player-check/server.jar")).hasContent("stands in for the server jar");
        assertThat(fail.passed()).isFalse();
        assertThat(fail.detail()).contains("no match for /Steve best: \\d/").contains("reply to game best Steve");
        assertThat(noJar.detail()).contains("no server jar");
    }

    /** The check writes the console and reads the server's output as UTF-8; under a C locale so must the server. */
    @Test
    void playerCheckServerEchoesNonAsciiTextUnderACLocale() throws IOException {
        Files.createDirectories(workspace.resolve("build/libs"));
        Files.writeString(workspace.resolve("build/libs/game-1.0.0.jar"), "stands in for the server jar");
        String text = EchoServer.TEXT;
        String check = """
                {"type":"player","jar":"build/libs/game-1.0.0.jar",
                 "command":["{java}","-cp","{classpath}","io.github.imnotstable.msc.bench.EchoServer"],
                 "console":["say %s"],"expect":["log: %s","got: say %s"],"timeoutSeconds":120}
                """.formatted(text, text, text);

        CheckResult result = run(Checks.parse(UserJson.of(JsonParser.parseString(check).getAsJsonObject(), "check.",
                Checks.KEYS), taskDir), null, Map.of("LC_ALL", "C", "LANG", "C"));

        assertThat(result.passed()).as(result.detail()).isTrue();
    }

    @Test
    void fileCheckFindsTextAndReportsMissingFiles() throws IOException {
        Files.writeString(workspace.resolve("answer.txt"), "13 -57 22\n");

        assertThat(run("{\"type\":\"file\",\"path\":\"answer.txt\",\"contains\":\"\\\\A\\\\s*13\\\\s+-57\\\\s+22\\\\s*\\\\z\"}").passed()).isTrue();
        assertThat(run("{\"type\":\"file\",\"path\":\"answer.txt\",\"contains\":\"^14\"}").passed()).isFalse();
        assertThat(run("{\"type\":\"file\",\"path\":\"nope.txt\"}").detail()).contains("not found");
    }

    @Test
    void commandCheckUsesExitCodeAndOutput() {
        assertThat(run("{\"type\":\"command\",\"run\":[\"{java}\",\"-version\"],\"outputMatches\":\"version\"}").passed()).isTrue();
        CheckResult bad = run("{\"type\":\"command\",\"run\":[\"{java}\",\"-no-such-flag\"]}");
        assertThat(bad.passed()).isFalse();
        assertThat(bad.detail()).startsWith("exit ");
    }

    @Test
    void commandCheckReadsOutputThatIsNotUtf8() {
        CheckResult result = run("{\"type\":\"command\",\"run\":[\"{java}\",\"-cp\",\"{classpath}\","
                + "\"io.github.imnotstable.msc.bench.PrintNonUtf8\"],\"outputMatches\":\"caf\"}");

        assertThat(result.passed()).as(result.detail()).isTrue();
    }

    @Test
    void pngCheckAcceptsAnItemShapedRuby() throws IOException {
        Files.createDirectories(workspace.resolve("textures"));
        Files.write(workspace.resolve("textures/ruby.png"), ruby(0xFFD01030, 0xFF801020, 0xFFFF8090));

        assertThat(run("{\"type\":\"png\",\"path\":\"textures/ruby.png\"," + RUBY_RULES + "}").passed()).isTrue();
    }

    @Test
    void pngCheckRejectsWrongColourSizeAndFilledSquares() throws IOException {
        Files.write(workspace.resolve("blue.png"), ruby(0xFF2040D0, 0xFF102080, 0xFF8090FF));
        Files.write(workspace.resolve("square.png"), image(16, 16, (x, y) -> 0xFFD01030));
        Files.write(workspace.resolve("big.png"), image(32, 32, (x, y) -> 0xFFD01030));
        Files.write(workspace.resolve("noisy.png"), image(16, 16, (x, y) -> x > 3 && x < 12 ? 0xFF000000 | (200 << 16) | x * 16 + y : 0));

        assertThat(run("{\"type\":\"png\",\"path\":\"blue.png\"," + RUBY_RULES + "}").detail()).contains("hue");
        assertThat(run("{\"type\":\"png\",\"path\":\"square.png\"," + RUBY_RULES + "}").detail()).contains("transparent background");
        assertThat(run("{\"type\":\"png\",\"path\":\"big.png\"," + RUBY_RULES + "}").detail()).contains("size 32x32");
        assertThat(run("{\"type\":\"png\",\"path\":\"noisy.png\"," + RUBY_RULES + "}").detail()).contains("colours");
    }

    @Test
    void zipCheckLooksInsideTheResourcePack() throws IOException {
        try (OutputStream file = Files.newOutputStream(workspace.resolve("pack.zip"));
                ZipOutputStream zip = new ZipOutputStream(file)) {
            zip.putNextEntry(new ZipEntry("pack.mcmeta"));
            zip.write("{\"pack\":{\"pack_format\":80,\"description\":\"Rubies\"}}".getBytes(StandardCharsets.UTF_8));
            zip.putNextEntry(new ZipEntry("assets/minecraft/textures/item/diamond.png"));
            zip.write(ruby(0xFFD01030, 0xFF801020, 0xFFFF8090));
        }
        String base = "{\"type\":\"zip\",\"path\":\"pack.zip\",\"entries\":[\"pack.mcmeta\"],"
                + "\"textEntries\":[{\"entry\":\"pack.mcmeta\",\"contains\":\"\\\"(pack_format|min_format)\\\"\\\\s*:\"}],"
                + "\"pngEntries\":[{\"entry\":\"assets/minecraft/textures/item/%s.png\"," + RUBY_RULES + "}]}";

        assertThat(run(base.formatted("diamond")).passed()).isTrue();
        assertThat(run(base.formatted("emerald")).detail()).contains("missing assets/minecraft/textures/item/emerald.png");
    }

    @Test
    void zipCheckWantsAPackMadeForTheRunsFormat() throws IOException {
        String check = "{\"type\":\"zip\",\"path\":\"pack.zip\",\"packFormat\":\"%s\"}";
        // The 2026-11-21 bench's baseline packs said 46 and passed a check that only looked for the key.
        Map<String, String> metas = new LinkedHashMap<>();
        metas.put("{\"pack\":{\"pack_format\":97}}", null);
        metas.put("{\"pack\":{\"pack_format\":46}}", "pack_format is 46, the game needs 97");
        metas.put("{\"pack\":{\"pack_format\":46,\"min_format\":97,\"max_format\":9999}}", null);
        metas.put("{\"pack\":{\"min_format\":[90,0],\"max_format\":[97,1]}}", null);
        metas.put("{\"pack\":{\"min_format\":98,\"max_format\":99}}", "formats 98 to 99 leave out format 97");
        metas.put("{\"pack\":{\"min_format\":97}}", "min_format needs a max_format");
        metas.put("[1]", "no \"pack\" object");
        for (Map.Entry<String, String> meta : metas.entrySet()) {
            try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(workspace.resolve("pack.zip")))) {
                zip.putNextEntry(new ZipEntry("pack.mcmeta"));
                zip.write(meta.getKey().getBytes(StandardCharsets.UTF_8));
            }
            CheckResult result = run(check.formatted("97"));
            if (meta.getValue() == null) {
                assertThat(result.passed()).as(meta.getKey()).isTrue();
            } else {
                assertThat(result.detail()).as(meta.getKey()).contains(meta.getValue());
            }
        }
        // Unfilled: the run's Minecraft version is not the one msc-core targets.
        assertThat(run(check.formatted("{resourcePackFormat}")).detail())
                .contains("no resource pack format is known for this run's Minecraft version");
    }

    @Test
    void rconAndBlocksChecksAskTheServer() throws IOException {
        Set<String> placed = Set.of("execute if block 1 2 3 minecraft:stone", "execute if block 2 2 3 minecraft:stone");
        try (FakeRconServer server = new FakeRconServer("pw", cmd -> cmd.equals("greet Steve") ? "§aHello, Steve!"
                : placed.contains(cmd) ? "Test passed" : "Test failed")) {
            assertThat(run("{\"type\":\"rcon\",\"command\":\"greet Steve\",\"expect\":\"^Hello, Steve!$\"}", server).passed()).isTrue();
            assertThat(run("{\"type\":\"blocks\",\"regions\":[{\"from\":[1,2,3],\"to\":[2,2,3],\"block\":\"minecraft:stone\"}]}", server)
                    .passed()).isTrue();

            CheckResult partial = run("{\"type\":\"blocks\",\"regions\":[{\"from\":[1,2,3],\"to\":[3,2,3],\"block\":\"minecraft:stone\"}]}", server);
            assertThat(partial.passed()).isFalse();
            assertThat(partial.detail()).startsWith("2/3 blocks match").contains("3 2 3 is not minecraft:stone");
        }
    }

    private interface Pixels {
        int argb(int x, int y);
    }

    /** A diamond-shaped gem: transparent corners, body colour, dark edge, light highlight. */
    static byte[] ruby(int body, int edge, int light) throws IOException {
        return image(16, 16, (x, y) -> {
            int d = Math.abs(x - 7) + Math.abs(y - 7);
            if (d > 7) {
                return 0;
            }
            if (d == 7) {
                return edge;
            }
            return x < 6 && y < 6 ? light : body;
        });
    }

    static byte[] image(int w, int h, Pixels pixels) throws IOException {
        BufferedImage image = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                image.setRGB(x, y, pixels.argb(x, y));
            }
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }
}
