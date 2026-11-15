package io.github.imnotstable.msc.bench;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.github.imnotstable.msc.process.ClosedPort;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * #639: every exit code the README lists for {@code check} and {@code run}, through the command line. A step of the
 * bench's own work on accepted input (freeing the ports, the reset) fails the same way in both: a failed check or
 * attempt (3), never a bad argument (2). #638: the path {@code run} prints after {@code Report:} is one
 * {@code report} takes.
 */
class ExitCodesTest {

    private static final String JAVA = ProcessHandle.current().info().command().orElse("java").replace("\\", "\\\\");
    private static final String CLASSPATH = System.getProperty("java.class.path").replace("\\", "\\\\");

    @TempDir
    Path dir;

    private ClosedPort rconPort;
    private ClosedPort gamePort;

    @BeforeEach
    void tasks() throws IOException {
        rconPort = ClosedPort.reserve();
        gamePort = ClosedPort.reserve();
        task("file", "{\"category\":\"code\",\"title\":\"a file\",\"checks\":[{\"type\":\"file\",\"path\":\"done.txt\"}]}");
        task("missing", "{\"category\":\"code\",\"title\":\"a file\",\"checks\":[{\"type\":\"file\",\"path\":\"no.txt\"}]}");
        task("reset", """
                {"category":"code","title":"a reset","resetBeforeChecks":{"delete":["plugins/config.yml"]},
                 "checks":[{"type":"file","path":"done.txt"}]}""");
        task("game", """
                {"category":"game","title":"a game on the bench's ports","platform":"minestom",
                 "checks":[{"type":"file","path":"done.txt"}]}""");
        Files.writeString(Files.createDirectories(dir.resolve("outside")).resolve("config.yml"), "not the bench's");
        Files.writeString(Files.createDirectories(dir.resolve("ws")).resolve("done.txt"), "done");
        config("{}");
        Files.createDirectories(dir.resolve("home"));
    }

    @AfterEach
    void ports() throws IOException {
        rconPort.close();
        gamePort.close();
    }

    @Test
    void checkPassesWith0() {
        Outcome check = exec("check", "file", "@ws", "--tasks-dir", "@tasks", "--config", "@bench.json");

        assertThat(check.exit()).as(check.err()).isEqualTo(Main.OK);
        assertThat(check.out()).startsWith("PASS");
    }

    @Test
    void checkRefusesABadArgumentWith2() {
        Outcome check = exec("check", "file", "@no-such-ws", "--tasks-dir", "@tasks", "--config", "@bench.json");

        assertThat(check.exit()).isEqualTo(Main.BAD_INPUT);
        assertThat(check.err()).startsWith("msc-bench: workspace ").contains("does not exist");
    }

    @Test
    void checkFailsAFailedCheckWith3() {
        Outcome check = exec("check", "missing", "@ws", "--tasks-dir", "@tasks", "--config", "@bench.json");

        assertThat(check.exit()).as(check.err()).isEqualTo(Main.FAILED);
        assertThat(check.out()).startsWith("FAIL");
        assertThat(check.err()).isEmpty();
    }

    @Test
    void checkFailsAPortItCannotFreeWith3NamingTheStep() throws IOException {
        try (ServerSocket taken = new ServerSocket(gamePort(), 50, InetAddress.getLoopbackAddress())) {
            Outcome check = exec("check", "game", "@ws", "--tasks-dir", "@tasks", "--config", "@bench.json");

            assertThat(check.exit()).as(check.err()).isEqualTo(Main.FAILED);
            assertThat(check.err().lines().toList()).singleElement().asString()
                    .startsWith("msc-bench: freeing the bench's ports before the checks: port " + taken.getLocalPort()
                            + " is still taken")
                    .contains("check kills no process").endsWith("stop it and check again")
                    .doesNotContain("this run");
        }
    }

    @Test
    void checkFailsAResetItRefusesWith3NamingTheStep() throws IOException {
        assumeTrue(link(dir.resolve("ws/plugins"), dir.resolve("outside")), "this file system makes no links");

        Outcome check = exec("check", "reset", "@ws", "--tasks-dir", "@tasks", "--config", "@bench.json");

        assertThat(check.exit()).as(check.err()).isEqualTo(Main.FAILED);
        assertThat(check.err().lines().toList()).singleElement().asString()
                .startsWith("msc-bench: reset before checks: ").contains("plugins/config.yml leads outside the workspace");
        assertThat(dir.resolve("outside/config.yml")).exists();
    }

    @Test
    void runPassesWith0AndReportTakesThePathItPrints() throws IOException {
        // #638: run prints the report file's path after Report:, and report takes it back, as the README says.
        Outcome run = exec("run", "--tasks", "file", "--tasks-dir", "@tasks", "--config", "@bench.json", "--out", "@runs");

        assertThat(run.exit()).as(run.out() + run.err()).isEqualTo(Main.OK);
        String printed = reportPath(run);
        Files.writeString(Path.of(printed), "stale");
        Outcome report = exec("report", printed);
        assertThat(report.exit()).as(report.err()).isEqualTo(Main.OK);
        assertThat(reportPath(report)).isEqualTo(printed);
        assertThat(Path.of(printed)).content().startsWith("# Benchmark report");
        Outcome folder = exec("report", Path.of(printed).getParent().toString());
        assertThat(folder.exit()).as(folder.err()).isEqualTo(Main.OK);
        assertThat(reportPath(folder)).isEqualTo(printed);
    }

    @Test
    void reportTakesTheReportPathBeforeAReportIsWritten() throws IOException {
        // After a report the disk refused, the printed path's file is missing; it still names the run folder.
        Path runDir = Files.createDirectories(dir.resolve("runs/20261115-000000"));
        Files.writeString(runDir.resolve("results.jsonl"),
                "{\"task\":\"t\",\"category\":\"code\",\"mode\":\"baseline\",\"repeat\":1,\"passed\":true}\n");

        Outcome report = exec("report", runDir.resolve(Main.REPORT).toString());

        assertThat(report.exit()).as(report.err()).isEqualTo(Main.OK);
        assertThat(runDir.resolve(Main.REPORT)).content().startsWith("# Benchmark report");
    }

    @Test
    void runRefusesABadArgumentWith2() {
        Outcome run = exec("run", "--tasks", "nope", "--tasks-dir", "@tasks", "--config", "@bench.json", "--out", "@runs");

        assertThat(run.exit()).isEqualTo(Main.BAD_INPUT);
        assertThat(run.err()).startsWith("msc-bench: ").contains("nope");
        assertThat(dir.resolve("runs")).doesNotExist();
    }

    @Test
    void runFailsAFailedAttemptWith3() {
        Outcome run = exec("run", "--tasks", "missing", "--tasks-dir", "@tasks", "--config", "@bench.json", "--out",
                "@runs");

        assertThat(run.exit()).as(run.out() + run.err()).isEqualTo(Main.FAILED);
        assertThat(run.out()).contains("missing #1 FAIL");
    }

    @Test
    void runFailsAPortItCannotFreeWith3NamingTheStep() throws IOException {
        try (ServerSocket taken = new ServerSocket(gamePort(), 50, InetAddress.getLoopbackAddress())) {
            Outcome run = exec("run", "--tasks", "game", "--tasks-dir", "@tasks", "--config", "@bench.json", "--out",
                    "@runs");

            assertThat(run.exit()).as(run.out() + run.err()).isEqualTo(Main.FAILED);
            assertThat(run.out()).contains("(freeing the bench's ports: port " + taken.getLocalPort()
                    + " is still taken");
            assertThat(run.err()).isEmpty();
        }
    }

    @Test
    void runFailsAResetItRefusesWith3NamingTheStep() throws IOException {
        assumeTrue(link(dir.resolve("probe"), dir.resolve("outside")), "this file system makes no links");
        config("{\"FAKE_LINK\":\"plugins=" + dir.resolve("outside").toString().replace("\\", "\\\\") + "\"}");

        Outcome run = exec("run", "--tasks", "reset", "--tasks-dir", "@tasks", "--config", "@bench.json", "--out",
                "@runs");

        assertThat(run.exit()).as(run.out() + run.err()).isEqualTo(Main.FAILED);
        assertThat(run.out()).contains("(reset before checks: ")
                .contains("plugins/config.yml leads outside the workspace");
        assertThat(dir.resolve("outside/config.yml")).exists();
    }

    @Test
    void runStopsOnAUsageLimitWith4() throws IOException {
        config("{\"FAKE_USAGE_LIMIT\":\"1\"}");

        Outcome run = exec("run", "--tasks", "file", "--tasks-dir", "@tasks", "--config", "@bench.json", "--out", "@runs");

        assertThat(run.exit()).as(run.out() + run.err()).isEqualTo(Main.USAGE_LIMIT);
        assertThat(run.out()).contains("Stopped early on a usage limit");
    }

    @Test
    void runStopsWhenItsOwnFileCannotBeWrittenWith5() throws IOException {
        // The agent makes a folder where the run's results.jsonl goes: run folder/attempts/<task>/<mode>/workspace.
        config("{\"FAKE_LEAVE\":\"../../../../results.jsonl/x=y\"}");

        Outcome run = exec("run", "--tasks", "file", "--tasks-dir", "@tasks", "--config", "@bench.json", "--out", "@runs");

        assertThat(run.exit()).as(run.out() + run.err()).isEqualTo(Main.NOT_RECORDED);
        assertThat(run.out()).contains("Stopped early: ").contains("results.jsonl could not be written");
    }

    private int gamePort() throws IOException {
        int port = gamePort.port();
        gamePort.close();
        return port;
    }

    private void task(String id, String json) throws IOException {
        Path task = Files.createDirectories(dir.resolve("tasks").resolve(id));
        Files.writeString(task.resolve("task.json"), json);
        Files.writeString(task.resolve("prompt.md"), "write done.txt");
    }

    /** bench.json with the fake agent, its baseline mode's {@code env}, and the test's closed ports. */
    private void config(String env) throws IOException {
        Files.writeString(dir.resolve("bench.json"), """
                {"agent":{"command":["%s","-cp","%s","io.github.imnotstable.msc.bench.FakeAgent","{prompt}"],
                  "timeoutSeconds":120},
                 "modes":{"baseline":{"env":%s}},
                 "server":{"rconPort":%d,"serverPort":%d}}
                """.formatted(JAVA, CLASSPATH, env, rconPort.port(), gamePort.port()));
    }

    private static String reportPath(Outcome outcome) {
        String last = outcome.out().strip().lines().reduce((a, b) -> b).orElse("");
        assertThat(last).startsWith("Report: ");
        return last.substring("Report: ".length());
    }

    private static boolean link(Path link, Path target) {
        try {
            Files.createSymbolicLink(link, target);
            return true;
        } catch (IOException | UnsupportedOperationException e) {
            return false; // Windows without the privilege
        }
    }

    private record Outcome(int exit, String out, String err) {
    }

    /** The command line as {@link Main#exec} runs it, judging the run folder's project no higher than the test's. */
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
