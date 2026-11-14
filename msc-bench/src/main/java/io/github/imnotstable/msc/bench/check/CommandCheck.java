package io.github.imnotstable.msc.bench.check;

import io.github.imnotstable.msc.bench.Text;
import io.github.imnotstable.msc.bench.run.ChildProcesses;
import io.github.imnotstable.msc.bench.run.Workspaces;
import io.github.imnotstable.msc.process.ServerJava;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * Runs a command in the workspace and passes on the expected exit code (and output pattern, if
 * given). The {@code {gradlew}} placeholder resolves to the workspace's Gradle wrapper for the
 * current OS. {@code addFiles} is a folder of the task copied into the workspace first, such as tests
 * the agent never saw.
 */
record CommandCheck(String name, List<String> run, int timeoutSeconds, int expectExit, Pattern outputMatches,
        Path addFiles) implements Check {

    CommandCheck {
        run = List.copyOf(run);
        if (run.isEmpty()) {
            throw new IllegalArgumentException("command check needs a non-empty \"run\"");
        }
    }

    @Override
    public CheckResult run(CheckContext context) {
        List<String> command = Placeholders.fill(run, context.placeholders());
        Path log = null;
        try {
            if (addFiles != null) {
                Workspaces.copyTree(addFiles, context.workspace());
            }
            log = Files.createTempFile("msc-bench-check", ".log");
            // A Java tool (or a Gradle build) writes its output as UTF-8, as it is read below (#478).
            Process process = context.start(ServerJava.processBuilder(command)
                    .directory(context.workspace().toFile())
                    .redirectErrorStream(true)
                    .redirectOutput(log.toFile()));
            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                ChildProcesses.killTree(process);
                return CheckResult.fail(name, "timed out after " + timeoutSeconds + "s");
            }
            // Lenient UTF-8: tools write in the console charset (Cp1252 on Windows), and a stray byte must not fail
            // a command that passed.
            String output = new String(Files.readAllBytes(log), StandardCharsets.UTF_8);
            if (process.exitValue() != expectExit) {
                return CheckResult.fail(name, "exit " + process.exitValue() + ": " + tail(output));
            }
            if (outputMatches != null && !outputMatches.matcher(output).find()) {
                return CheckResult.fail(name, "output has no match for /" + outputMatches.pattern() + "/");
            }
            return CheckResult.pass(name, "exit " + expectExit);
        } catch (IOException e) {
            return CheckResult.fail(name, "cannot run " + command + ": " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return CheckResult.fail(name, "interrupted");
        } finally {
            if (log != null) {
                try {
                    Files.deleteIfExists(log);
                } catch (IOException ignored) {
                    // temp file; the OS cleans it up eventually
                }
            }
        }
    }

    private static String tail(String output) {
        return Text.tail(output.strip(), 200);
    }
}
