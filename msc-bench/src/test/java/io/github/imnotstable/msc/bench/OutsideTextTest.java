package io.github.imnotstable.msc.bench;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.InstanceOfAssertFactories.STRING;

import com.google.gson.JsonParser;
import io.github.imnotstable.msc.bench.check.Check;
import io.github.imnotstable.msc.bench.check.CheckContext;
import io.github.imnotstable.msc.bench.check.CheckResult;
import io.github.imnotstable.msc.bench.check.Checks;
import io.github.imnotstable.msc.bench.run.ToolLogReader;
import io.github.imnotstable.msc.bench.run.ToolStats;
import io.github.imnotstable.msc.bench.task.TaskDef;
import io.github.imnotstable.msc.bench.task.TaskLoader;
import io.github.imnotstable.msc.buildlogic.RepoFiles;
import io.github.imnotstable.msc.process.rcon.RconClient;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Text the agent, a child process or msc-core wrote reaches every reader the same way (Text.read): a byte-order mark,
 * CRLF, a Cp1252 byte, a character cut in half or an empty file never makes a check or a run fail with a read error.
 */
@Timeout(value = 5, unit = TimeUnit.MINUTES, threadMode = Timeout.ThreadMode.SEPARATE_THREAD) // a hang fails
class OutsideTextTest {

    private static final String ANSWER = "^13\\\\s+-57\\\\s+22\\\\s*$";

    @TempDir
    Path workspace;

    @TempDir
    Path taskDir;

    /** What a Windows tool or a crash may write, and whether the answer is in it. */
    enum Variant {
        PLAIN(true, bytes("13 -57 22\n")),
        BOM(true, concat(new byte[] {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF}, bytes("13 -57 22\r\n"))),
        CRLF(true, bytes("13 -57 22\r\n")),
        CP1252_BYTE(true, concat(bytes("13 -57 22\r\n# caf"), new byte[] {(byte) 0xE9, '\n'})),
        CUT_CHARACTER(true, concat(bytes("13 -57 22\nprice: "), new byte[] {(byte) 0xE2, (byte) 0x82})),
        EMPTY(false, new byte[0]);

        final boolean holdsAnswer;
        final byte[] content;

        Variant(boolean holdsAnswer, byte[] content) {
            this.holdsAnswer = holdsAnswer;
            this.content = content;
        }
    }

    /** Every check type that reads text someone else wrote, as JSON reading {@code out.txt} or its zip copy. */
    private static final Map<String, String> CHECKS = Map.of(
            "file", "{\"type\":\"file\",\"path\":\"out.txt\",\"contains\":\"" + ANSWER + "\"}",
            "zip", "{\"type\":\"zip\",\"path\":\"out.zip\",\"entries\":[\"out.txt\"],"
                    + "\"textEntries\":[{\"entry\":\"out.txt\",\"contains\":\"" + ANSWER + "\"}]}",
            "command", "{\"type\":\"command\",\"run\":[\"{java}\",\"-cp\",\"{classpath}\","
                    + "\"io.github.imnotstable.msc.bench.CatFile\",\"out.txt\"],\"outputMatches\":\"" + ANSWER + "\"}",
            "player", "{\"type\":\"player\",\"jar\":\"server.jar\",\"command\":[\"{java}\",\"-cp\",\"{classpath}\","
                    + "\"io.github.imnotstable.msc.bench.CatFile\",\"../out.txt\",\"server\"],"
                    + "\"expect\":[\"" + ANSWER + "\"],\"timeoutSeconds\":120}");

    static Stream<Arguments> everyCheckAndVariant() {
        List<Arguments> out = new ArrayList<>();
        for (String type : List.of("file", "zip", "command", "player")) {
            for (Variant v : Variant.values()) {
                out.add(Arguments.of(type, v));
            }
        }
        return out.stream();
    }

    @ParameterizedTest(name = "{0} check, {1}")
    @MethodSource("everyCheckAndVariant")
    void everyCheckReadsOutsideTextLeniently(String type, Variant variant) throws IOException {
        Files.write(workspace.resolve("out.txt"), variant.content);
        try (OutputStream file = Files.newOutputStream(workspace.resolve("out.zip"));
                ZipOutputStream zip = new ZipOutputStream(file)) {
            zip.putNextEntry(new ZipEntry("out.txt"));
            zip.write(variant.content);
        }
        Files.writeString(workspace.resolve("server.jar"), "stands in for the server jar");

        CheckResult result = run(Checks.parse(UserJson.of(
                JsonParser.parseString(CHECKS.get(type)).getAsJsonObject(), "check."), taskDir));

        assertThat(result.passed()).as(result.detail()).isEqualTo(variant.holdsAnswer);
        if (!variant.holdsAnswer) {
            assertThat(result.detail()).contains("no match").doesNotContain("cannot read");
        }
    }

    @Test
    void shippedWorldFindBlockAnswerPassesWithAByteOrderMark() throws IOException {
        TaskDef task = TaskLoader.load(RepoFiles.dir("bundledTasks", "msc-bench/tasks").resolve("world-find-block"));
        Check answer = task.checks().stream().filter(c -> c.name().equals("answer")).findFirst().orElseThrow();
        // What Notepad or Windows PowerShell 5.1's Set-Content -Encoding utf8 writes.
        Files.write(workspace.resolve("answer.txt"), Variant.BOM.content);

        CheckResult result = run(answer);

        assertThat(result.passed()).as(result.detail()).isTrue();
    }

    @Test
    void toolLogWithBrokenLinesStillGivesAResultAndOneWarning() throws IOException {
        Path log = workspace.resolve("tool-calls.jsonl");
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        bytes.write(bytes("{\"v\":1,\"tool\":\"world.slice\",\"ok\":true,\"durationMs\":12,\"resultTokens\":100}\n"));
        bytes.write(bytes("{\"v\":1,\"tool\":null,\"ok\":true,\"resultTokens\":5}\n"));
        bytes.write(bytes("{\"v\":1,\"tool\":\"server.start\",\"ok\":null}\n"));
        bytes.write(bytes("{\"v\":1,\"tool\":\"world.find\",\"ok\":false,\"durationMs\":3,\"resultTokens\":7}\n"));
        bytes.write(bytes("{\"v\":1,\"tool\":\"caf"));
        bytes.write(new byte[] {(byte) 0xC3}); // a crash cut the é
        Files.write(log, bytes.toByteArray());
        List<String> warnings = new ArrayList<>();

        ToolStats stats = ToolLogReader.read(log, warnings::add);

        assertThat(stats).isEqualTo(new ToolStats(2, 1, 107, 0, 0, 15, Map.of(
                "world.find", new ToolStats.PerTool(1, 1, 7, 3),
                "world.slice", new ToolStats.PerTool(1, 0, 100, 12))));
        assertThat(warnings).singleElement(STRING)
                .startsWith("warning: tool log").contains("3 lines");
    }

    @Test
    void textDropsByteOrderMarksAtLineStartsAndReplacesMalformedBytes() {
        assertThat(Text.decode(Variant.BOM.content)).isEqualTo("13 -57 22\r\n");
        // Two such files joined, or a JVM notice before a tool's output: a mark at any line start goes too.
        assertThat(Text.decode(concat(Variant.BOM.content, Variant.BOM.content))).isEqualTo("13 -57 22\r\n13 -57 22\r\n");
        assertThat(Text.decode(bytes("a\uFEFFb"))).isEqualTo("a\uFEFFb");
        assertThat(Text.decode(Variant.CP1252_BYTE.content)).endsWith("caf" + Text.REPLACEMENT + "\n");
        assertThat(Text.decode(Variant.CUT_CHARACTER.content)).endsWith("price: " + Text.REPLACEMENT);
        assertThat(Text.decode(new byte[0])).isEmpty();
    }

    private CheckResult run(Check check) {
        return check.run(new CheckContext() {
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
                throw new IOException("no RCON in this test");
            }
        });
    }

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = new byte[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }
}
