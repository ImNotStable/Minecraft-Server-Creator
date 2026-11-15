package io.github.imnotstable.msc.bench;

import static io.github.imnotstable.msc.bench.ReaderView.expected;
import static io.github.imnotstable.msc.bench.ReaderView.firstHidden;
import static io.github.imnotstable.msc.bench.ReaderView.unescapeMarkdown;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.imnotstable.msc.process.ClosedPort;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * #677 through the command line: a check's output holding a zero-width space, a right-to-left override, a bidi
 * isolate, ESC, a C1 control, a line break and Markdown reads the same, escaped and on one line, in {@code check}'s
 * output, in {@code run}'s progress line and in report.md (#678), and nothing in any of them is hidden.
 */
class RunTextOnScreenTest {

    private static final String JAVA = ProcessHandle.current().info().command().orElse("java").replace("\\", "\\\\");
    private static final String CLASSPATH = System.getProperty("java.class.path").replace("\\", "\\\\");
    private static final String OUTPUT = "Hello​builders ‮abc‬ ⁦x⁩ \u001B[31mred\u0085"
            + "\u009B\nnext\tline &amp; | `code` *a* [l](u)";

    @TempDir
    Path dir;

    private ClosedPort rconPort;
    private ClosedPort gamePort;

    @BeforeEach
    void task() throws IOException {
        rconPort = ClosedPort.reserve();
        gamePort = ClosedPort.reserve();
        Path printed = dir.resolve("output.txt");
        Files.writeString(printed, OUTPUT);
        Path task = Files.createDirectories(dir.resolve("tasks/cat"));
        Files.writeString(task.resolve("task.json"), """
                {"category":"code","title":"prints","checks":[{"type":"command","name":"out​",
                 "run":["%s","-cp","%s","io.github.imnotstable.msc.bench.CatFile","%s"],"expectExit":1}]}"""
                .formatted(JAVA, CLASSPATH, printed.toString().replace("\\", "\\\\")));
        Files.writeString(task.resolve("prompt.md"), "nothing to do");
        Files.createDirectories(dir.resolve("ws"));
        Files.createDirectories(dir.resolve("home"));
        Files.writeString(dir.resolve("bench.json"), """
                {"agent":{"command":["%s","-cp","%s","io.github.imnotstable.msc.bench.FakeAgent","{prompt}"],
                  "timeoutSeconds":120},
                 "modes":{"baseline":{}},
                 "server":{"rconPort":%d,"serverPort":%d}}
                """.formatted(JAVA, CLASSPATH, rconPort.port(), gamePort.port()));
    }

    @AfterEach
    void ports() throws IOException {
        rconPort.close();
        gamePort.close();
    }

    @Test
    void checkRunAndReportShowTheSameVisibleDetail() throws IOException {
        assertThat(expected(OUTPUT)).startsWith("Hello\\u200bbuilders \\u202eabc\\u202c \\u2066x\\u2069 "
                + "\\u001b[31mred\\u0085\\u009b\\nnext\\tline &amp; |");

        Outcome check = exec("check", "cat", "@ws", "--tasks-dir", "@tasks", "--config", "@bench.json");
        assertThat(check.exit()).as(check.err()).isEqualTo(Main.FAILED);
        assertThat(check.out().lines()).hasSize(1);
        // The output may start with a line the JVM printed (Picked up JAVA_TOOL_OPTIONS), shown escaped the same way.
        String shown = check.out().strip().substring("FAIL ".length());
        assertThat(shown).startsWith(expected("out​") + ": exit 0: ").endsWith(expected(OUTPUT));
        assertThat(firstHidden(check.out().strip())).isNull();

        Outcome run = exec("run", "--tasks", "cat", "--tasks-dir", "@tasks", "--config", "@bench.json", "--out",
                "@runs");
        assertThat(run.exit()).as(run.out() + run.err()).isEqualTo(Main.FAILED);
        assertThat(firstHidden(run.out())).as(run.out()).isNull();
        assertThat(run.out().lines()).anySatisfy(line -> assertThat(line)
                .startsWith("[baseline] cat #1 FAIL").endsWith(" (" + shown + ")"));

        String last = run.out().strip().lines().reduce((a, b) -> b).orElse("");
        String md = Files.readString(Path.of(last.substring("Report: ".length())));
        assertThat(firstHidden(md)).as(md).isNull();
        String failure = md.lines().filter(l -> l.startsWith("- cat ")).findFirst().orElseThrow();
        // no entity is decoded, no cell is split, no code, emphasis or link is made
        assertThat(failure).contains("\\&amp; \\| \\`code\\` \\*a\\* \\[l\\](u)");
        assertThat(unescapeMarkdown(failure)).isEqualTo("- cat (baseline #1): " + shown);
    }

    private record Outcome(int exit, String out, String err) {
    }

    private Outcome exec(String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        String[] resolved = Stream.of(args).map(a -> a.startsWith("@") ? dir.resolve(a.substring(1)).toString() : a)
                .toArray(String[]::new);
        PrintStream outStream = new PrintStream(out, true, StandardCharsets.UTF_8);
        int exit = Main.guarded(new PrintStream(err, true, StandardCharsets.UTF_8),
                () -> Main.run(resolved, outStream, dir.resolve("home"), dir.resolve("temp"), dir));
        return new Outcome(exit, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }
}
