package io.github.imnotstable.msc.bench.check;

import io.github.imnotstable.msc.bench.Text;
import io.github.imnotstable.msc.bench.run.ChildProcesses;
import io.github.imnotstable.msc.bench.run.Workspaces;
import io.github.imnotstable.msc.process.ServerJava;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * Runs a command in the workspace and passes on the expected exit code (and output pattern, if
 * given). The {@code {gradlew}} placeholder (filled, like every check key, by {@link Checks}) is the workspace's
 * Gradle wrapper for the current OS. {@code addFiles} is a folder of the task copied into the workspace first, such as tests
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
        List<String> command = run;
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
            // Lenient UTF-8 (Text.read): tools write in the console charset (Cp1252 on Windows), and a stray byte must
            // not fail a command that passed.
            String output = Text.read(log);
            if (process.exitValue() != expectExit) {
                String prefix = "exit " + process.exitValue() + ": ";
                return CheckResult.fail(name, prefix + tail(output, CheckResult.MAX_DETAIL - prefix.length()));
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

    /** The end of {@code output}, where a failing tool says why, in {@code max} chars so CheckResult keeps it whole. */
    private static String tail(String output, int max) {
        return Text.tailWithEllipsis(output.strip(), max);
    }
}
